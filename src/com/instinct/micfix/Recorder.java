package com.instinct.micfix;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared recording engine (v10 pipeline) used by both the activity and the floating bubble. */
public class Recorder {

    public interface Listener {
        void onChange();
        void onAmp(float amp);
    }

    private static Recorder instance;
    public static synchronized Recorder get(Context c) {
        if (instance == null) instance = new Recorder(c.getApplicationContext());
        return instance;
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int MAX_SECONDS = 180;
    private static final float GAIN = 0.4f;
    private static final int GATE_WIN = SAMPLE_RATE / 50; // 20 ms
    private static final int GATE_WARMUP_WINDOWS = 50;    // ~1 s before gating kicks in

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    public final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    public volatile boolean recording = false;
    public volatile boolean saving = false;
    public volatile String status = "جاهز";
    public volatile long startedAtMs = 0;
    public volatile File lastFile;
    private Thread capThread;

    private MediaCodec enc;
    private MediaMuxer muxer;
    private boolean muxerStarted;
    private int muxTrack;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
    private long ptsUs;
    private File outFile;
    private int channel = -1;
    private long sumL, sumR;
    private long totalMonoSamples;
    private int sessionPeak;
    private final int[] rmsHist = new int[700];
    private long winCount;
    private float gatePrevGain = 1f;

    private Recorder(Context c) { ctx = c; }

    // coalesced amplitude delivery: no per-chunk Runnable allocation, at most one pending post
    private volatile float pendingAmp;
    private final java.util.concurrent.atomic.AtomicBoolean ampPosted = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable ampRunnable = new Runnable() { public void run() {
        ampPosted.set(false);
        float a = pendingAmp;
        for (Listener l : listeners) l.onAmp(a);
    } };
    private void postAmp(float a) {
        pendingAmp = a;
        if (ampPosted.compareAndSet(false, true)) ui.post(ampRunnable);
    }

    public boolean hasMicPermission() {
        return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public void setStatus(String s) {
        status = s;
        fire();
    }

    private void fire() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() { public void run() { for (Listener l : listeners) l.onChange(); } });
        } else {
            for (Listener l : listeners) l.onChange();
        }
    }

    /** Call on the main thread. Returns false if it could not start (status explains why). */
    public boolean start() {
        if (saving) { setStatus("استنى لحد ما الحفظ يخلص"); return false; }
        if (recording) return false;
        if (!hasMicPermission()) { setStatus("محتاج إذن المايك الأول"); return false; }
        session++;
        recording = true;
        startedAtMs = System.currentTimeMillis();
        status = "بتسجل...";
        fire();
        capThread = new Thread(new Runnable() { public void run() { captureLoop(); } });
        capThread.start();
        return true;
    }

    private volatile AudioRecord curAr;
    private volatile String stage = "start";
    private int session = 0;

    public void stop() {
        if (!recording || saving) return;
        recording = false;
        showSaving();
        final int sess = session;
        // unblock a stuck ar.read()
        new Thread(new Runnable() { public void run() {
            AudioRecord a = curAr;
            if (a != null) { try { a.stop(); } catch (Throwable ignore) { } }
        } }).start();
        // watchdog: never stay in "saving" forever
        ui.postDelayed(new Runnable() { public void run() {
            if (saving && session == sess) {
                saving = false;
                setStatus("الحفظ علق عند مرحلة: " + stage + " — جرب سجل تاني");
            }
        } }, 12000);
    }

    private void showSaving() {
        saving = true;
        setStatus("جاري الحفظ...");
    }

    private AudioRecord openRecorder(int minBuf) {
        int[] srcs = {MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.DEFAULT};
        String[] names = {"كاميرا", "خام", "افتراضي"};
        for (int i = 0; i < srcs.length; i++) {
            AudioRecord cand = null;
            try {
                cand = new AudioRecord(srcs[i], SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
                if (cand.getState() == AudioRecord.STATE_INITIALIZED) {
                    cand.startRecording();
                    if (cand.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        final String sn = names[i];
                        ui.post(new Runnable() { public void run() {
                            if (recording) setStatus("بتسجل... (المصدر: " + sn + ")");
                        } });
                        return cand;
                    }
                }
            } catch (Throwable ignore) { }
            if (cand != null) { try { cand.release(); } catch (Throwable ignore) { } }
        }
        return null;
    }

    private void captureLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); } catch (Throwable ignore) { }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 8192;

        stage = "open";
        AudioRecord ar = openRecorder(minBuf);
        curAr = ar;
        if (ar == null) {
            recording = false;
            ui.post(new Runnable() { public void run() {
                setStatus("خطأ في فتح المايك");
            } });
            return;
        }

        // fresh session state
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) dir = ctx.getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        outFile = new File(dir, "REC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a");
        channel = -1;
        sumL = 0; sumR = 0;
        ptsUs = 0;
        totalMonoSamples = 0;
        sessionPeak = 0;
        winCount = 0;
        gatePrevGain = 1f;
        Arrays.fill(rmsHist, 0);
        muxerStarted = false;
        enc = null;
        muxer = null;

        ByteArrayOutputStream pending = new ByteArrayOutputStream(SAMPLE_RATE * 4 + 8192);
        byte[] gateLeft = new byte[0];
        String err = null;
        boolean auto = false;
        try {
            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            byte[] buf = new byte[minBuf];
            long lastDataMs = System.currentTimeMillis();
            int maxBytes = SAMPLE_RATE * 2 * 2 * MAX_SECONDS;
            long total = 0;
            int sincePost = 0;
            int chunkPeak = 0;
            while (recording) {
                stage = "read";
                int n = ar.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
                long nowMs = System.currentTimeMillis();
                if (n <= 0) {
                    if (nowMs - lastDataMs > 4000) {
                        // mic gives no data: bail out instead of hanging
                        recording = false;
                        final AudioRecord stuck = ar;
                        ar = null;
                        curAr = null;
                        new Thread(new Runnable() { public void run() {
                            try { stuck.stop(); } catch (Throwable ignore) { }
                            try { stuck.release(); } catch (Throwable ignore) { }
                        } }).start();
                        cleanupCodec();
                        if (outFile != null) { try { outFile.delete(); } catch (Throwable ignore) { } }
                        postResult(null, "المايك ما رجّعش صوت (read=" + n + ") بعد " + ((nowMs - lastDataMs) / 1000) + " ثواني");
                        return;
                    }
                    try { Thread.sleep(10); } catch (InterruptedException ignore) { }
                    continue;
                }
                lastDataMs = nowMs;
                total += n;
                for (int i = 0; i + 3 < n; i += 4) {
                    int l = (buf[i] & 0xff) | (buf[i + 1] << 8);
                    int r = (buf[i + 2] & 0xff) | (buf[i + 3] << 8);
                    int m = Math.max(Math.abs((short) l), Math.abs((short) r));
                    if (m > chunkPeak) chunkPeak = m;
                }
                sincePost += n;
                if (sincePost >= 9600) { // ~50 ms metering for the waveform
                    final float amp = Math.min(1f, chunkPeak / 32768f);
                    postAmp(amp);
                    sincePost = 0;
                    chunkPeak = 0;
                }
                if (channel < 0) {
                    accumulateSums(buf, 0, n);
                    pending.write(buf, 0, n);
                    if (pending.size() >= SAMPLE_RATE * 4) { // 1 s of stereo: lock the channel
                        channel = sumR > sumL ? 1 : 0;
                        byte[] all = pending.toByteArray();
                        pending = new ByteArrayOutputStream(0);
                        gateLeft = feedPcm(all, 0, all.length, gateLeft);
                    }
                } else {
                    gateLeft = feedPcm(buf, 0, n, gateLeft);
                }
                if (total >= maxBytes) {
                    recording = false;
                    auto = true;
                }
            }
            try { ar.stop(); } catch (Throwable ignore) { }
            try { ar.release(); } catch (Throwable ignore) { }
            ar = null;
            if (auto) {
                ui.post(new Runnable() { public void run() { showSaving(); } });
            }
            // short recording: decide the channel from whatever we heard and flush
            if (channel < 0) {
                channel = sumR > sumL ? 1 : 0;
                byte[] all = pending.toByteArray();
                gateLeft = feedPcm(all, 0, all.length, gateLeft);
            }
            pending = null;
            // sub-20ms leftover goes in ungated
            if (gateLeft.length > 0) {
                feedEncoderBytes(gateLeft, 0, gateLeft.length);
                totalMonoSamples += gateLeft.length / 2;
            }
            stage = "drain";
            signalEosAndDrain();
            stage = "cleanup";
            cleanupCodec();
            if (!outFile.exists() || outFile.length() == 0) {
                throw new IllegalStateException("الملف مش موجود بعد الترميز");
            }
            if (totalMonoSamples < SAMPLE_RATE / 5) {
                try { outFile.delete(); } catch (Throwable ignore) { }
                postResult(null, "SHORT");
                return;
            }
            postResult(outFile, null);
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "");
            if (ar != null) {
                try { ar.stop(); } catch (Throwable ignore) { }
                try { ar.release(); } catch (Throwable ignore) { }
            }
            cleanupCodec();
            if (outFile != null) { try { outFile.delete(); } catch (Throwable ignore) { } }
            postResult(null, err);
        }
    }

    private void cleanupCodec() {
        if (enc != null) {
            try { enc.stop(); } catch (Throwable ignore) { }
            try { enc.release(); } catch (Throwable ignore) { }
            enc = null;
        }
        if (muxer != null) {
            if (muxerStarted) { try { muxer.stop(); } catch (Throwable ignore) { } }
            try { muxer.release(); } catch (Throwable ignore) { }
            muxer = null;
            muxerStarted = false;
        }
    }

    private void postResult(final File f, final String err) {
        final int ch = channel < 0 ? 0 : channel;
        final long p = sessionPeak;
        ui.post(new Runnable() { public void run() {
            saving = false;
            if (f != null) {
                lastFile = f;
                long kb = Math.max(1, f.length() / 1024);
                setStatus("اتحفظ التسجيل (" + kb + " كيلو) — قناة "
                        + (ch == 1 ? "يمين" : "شمال") + " — أعلى صوت " + (p * 100 / 32767)
                        + "% — تنقية الصوت شغالة");
            } else if ("SHORT".equals(err)) {
                setStatus("التسجيل قصير جدًا، جرب تاني");
            } else {
                setStatus("خطأ في حفظ التسجيل: " + err);
            }
        } });
    }

    // ---------- incremental PCM pipeline (capture thread only) ----------

    private void accumulateSums(byte[] b, int off, int len) {
        for (int i = off; i + 3 < off + len; i += 4) {
            short l = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            short r = (short) ((b[i + 2] & 0xff) | (b[i + 3] << 8));
            sumL += Math.abs(l);
            sumR += Math.abs(r);
        }
    }

    /** Convert stereo frames to gated mono and feed the encoder. Returns the sub-20ms leftover. */
    private byte[] feedPcm(byte[] stereo, int off, int len, byte[] gateLeft) throws Exception {
        int frames = len / 4;
        byte[] mono = new byte[gateLeft.length + frames * 2];
        System.arraycopy(gateLeft, 0, mono, 0, gateLeft.length);
        int base0 = channel * 2;
        for (int f = 0; f < frames; f++) {
            int b = off + f * 4 + base0;
            short s = (short) ((stereo[b] & 0xff) | (stereo[b + 1] << 8));
            int g = (int) (s * GAIN);
            if (g > 32767) g = 32767;
            if (g < -32768) g = -32768;
            int m = gateLeft.length + f * 2;
            mono[m] = (byte) (g & 0xff);
            mono[m + 1] = (byte) ((g >> 8) & 0xff);
        }
        int totalSamples = mono.length / 2;
        int whole = (totalSamples / GATE_WIN) * GATE_WIN;
        applyLiveGate(mono, whole);
        feedEncoderBytes(mono, 0, whole * 2);
        totalMonoSamples += whole;
        byte[] left = new byte[(totalSamples - whole) * 2];
        System.arraycopy(mono, whole * 2, left, 0, left.length);
        return left;
    }

    /**
     * Live adaptive noise gate: the noise floor is estimated from the quietest
     * 10% of the 20 ms windows seen so far (histogram), only after the first
     * second of audio. Windows below 2x the floor fade toward silence (min
     * gain 0.1) with per-sample ramping so there are no clicks.
     */
    private void applyLiveGate(byte[] mono, int uptoSamples) {
        int nw = uptoSamples / GATE_WIN;
        for (int w = 0; w < nw; w++) {
            long sum = 0;
            for (int i = 0; i < GATE_WIN; i++) {
                int idx = (w * GATE_WIN + i) * 2;
                short s = (short) ((mono[idx] & 0xff) | (mono[idx + 1] << 8));
                sum += (long) s * s;
            }
            double rms = Math.sqrt(sum / (double) GATE_WIN);
            float target = 1f;
            if (winCount >= GATE_WARMUP_WINDOWS) {
                double thresh = Math.max(2.0 * floorFromHist(), 20.0);
                if (rms < thresh) target = 0.1f;
            }
            for (int i = 0; i < GATE_WIN; i++) {
                float g = gatePrevGain + (target - gatePrevGain) * (i / (float) GATE_WIN);
                int idx = (w * GATE_WIN + i) * 2;
                short s = (short) ((mono[idx] & 0xff) | (mono[idx + 1] << 8));
                int v = (int) (s * g);
                if (v > 32767) v = 32767;
                if (v < -32768) v = -32768;
                mono[idx] = (byte) (v & 0xff);
                mono[idx + 1] = (byte) ((v >> 8) & 0xff);
                int a = v < 0 ? -v : v;
                if (a > sessionPeak) sessionPeak = a;
            }
            gatePrevGain = target;
            int bucket = (int) (rms / 50.0);
            if (bucket >= rmsHist.length) bucket = rmsHist.length - 1;
            rmsHist[bucket]++;
            winCount++;
        }
    }

    private double floorFromHist() {
        long need = Math.max(1, winCount / 10);
        double sum = 0;
        long used = 0;
        for (int b = 0; b < rmsHist.length && need > 0; b++) {
            int c = rmsHist[b];
            if (c == 0) continue;
            long take = Math.min(c, need);
            sum += take * (b * 50.0 + 25.0);
            used += take;
            need -= take;
        }
        return used > 0 ? sum / used : 0.0;
    }

    private void feedEncoderBytes(byte[] mono, int off, int len) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (len > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("encoder input timeout");
            }
            int idx = enc.dequeueInputBuffer(10000);
            if (idx >= 0) {
                ByteBuffer ib = enc.getInputBuffer(idx);
                ((java.nio.Buffer) ib).clear();
                int chunk = Math.min(ib.capacity(), len);
                chunk &= ~1;
                if (chunk <= 0) continue;
                ib.put(mono, off, chunk);
                enc.queueInputBuffer(idx, 0, chunk, ptsUs, 0);
                ptsUs += (long) (chunk / 2) * 1000000L / SAMPLE_RATE;
                off += chunk;
                len -= chunk;
            }
            drainOutput(false);
        }
        drainOutput(false);
    }

    private void signalEosAndDrain() throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        boolean eosQueued = false;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("encoder drain timeout");
            }
            if (!eosQueued) {
                int idx = enc.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    enc.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eosQueued = true;
                }
            }
            if (drainOutput(true)) return;
        }
    }

    /** Drain encoder output into the muxer. Returns true once EOS is seen. */
    private boolean drainOutput(boolean blocking) {
        while (true) {
            int oi = enc.dequeueOutputBuffer(bufInfo, blocking ? 20000 : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false;
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    muxTrack = muxer.addTrack(enc.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (oi >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(oi);
                if (muxerStarted && bufInfo.size > 0
                        && (bufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ((java.nio.Buffer) ob).position(bufInfo.offset);
                    ((java.nio.Buffer) ob).limit(bufInfo.offset + bufInfo.size);
                    muxer.writeSampleData(muxTrack, ob, bufInfo);
                }
                enc.releaseOutputBuffer(oi, false);
                if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            }
        }
    }

}
