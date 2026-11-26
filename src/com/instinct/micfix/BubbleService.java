package com.instinct.micfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Foreground service that shows a draggable floating bubble over other apps. */
public class BubbleService extends Service implements Recorder.Listener {

    public static volatile boolean running = false;
    private static final String ACTION_STOP = "com.instinct.micfix.STOP_BUBBLE";
    private static final String CHANNEL = "bubble";
    private static final int NOTIF_ID = 11;

    public static void start(Context c) {
        Intent i = new Intent(c, BubbleService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, BubbleService.class));
    }

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout panel;
    private TextView mainBubble, recBubble, shareBubble;
    private Recorder rec;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean expanded = false;
    private boolean shiftedUp = false;
    private int size, gap, screenW, screenH;
    private boolean wasBusy = false;

    private final Runnable ticker = new Runnable() {
        public void run() {
            updateViews();
            if (rec.recording) ui.postDelayed(this, 500);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    private int dp(int d) {
        return (int) (getResources().getDisplayMetrics().density * d + 0.5f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        if (panel == null) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "إذن العرض فوق التطبيقات مش متفعل", Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            running = true;
            rec = Recorder.get(this);
            rec.listeners.add(this);
            createBubble();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (rec != null) {
            rec.listeners.remove(this);
            if (rec.recording) rec.stop();
        }
        ui.removeCallbacksAndMessages(null);
        if (panel != null && wm != null) {
            try { wm.removeView(panel); } catch (Throwable ignore) { }
            panel = null;
        }
        super.onDestroy();
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "الفقاعة العائمة", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        boolean recording = rec != null && rec.recording;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent po = PendingIntent.getActivity(this, 0, open, 0);
        Intent stop = new Intent(this, BubbleService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, 0);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
         .setContentTitle("مسجل الصوت")
         .setContentText(recording ? "بتسجل دلوقتي..." : "الفقاعة العائمة شغالة")
         .setContentIntent(po)
         .setOngoing(true)
         .addAction(new Notification.Action.Builder(
                 android.R.drawable.ic_menu_close_clear_cancel, "إخفاء الفقاعة", ps).build());
        return b.build();
    }

    private TextView makeBubble(String text, int color, int textSp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(textSp);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), Color.parseColor("#33FFFFFF"));
        t.setBackground(g);
        t.setElevation(dp(6));
        return t;
    }

    private void setColor(TextView t, int color) {
        ((GradientDrawable) t.getBackground()).setColor(color);
    }

    private void createBubble() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        size = dp(56);
        gap = dp(10);

        mainBubble = makeBubble("🎙", Color.parseColor("#1565C0"), 24);
        recBubble = makeBubble("●", Color.parseColor("#D32F2F"), 26);
        shareBubble = makeBubble("📤", Color.parseColor("#2E7D32"), 22);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        rebuildPanel();

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = screenW - size;
        lp.y = screenH / 3;
        wm.addView(panel, lp);

        attachMainTouch();
        recBubble.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (rec.saving) {
                    toast("استنى لحد ما الحفظ يخلص");
                } else if (rec.recording) {
                    rec.stop();
                } else if (!rec.hasMicPermission()) {
                    Intent i = new Intent(BubbleService.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    toast("اديني إذن المايك الأول");
                } else {
                    rec.start();
                }
                collapse();
            }
        });
        shareBubble.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                shareLast();
                collapse();
            }
        });
        updateViews();
    }

    private void rebuildPanel() {
        panel.removeAllViews();
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(size, size);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(size, size);
        if (!expanded) {
            panel.addView(mainBubble, mp);
        } else if (shiftedUp) {
            cp.bottomMargin = gap;
            panel.addView(shareBubble, cp);
            LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(size, size);
            cp2.bottomMargin = gap;
            panel.addView(recBubble, cp2);
            panel.addView(mainBubble, mp);
        } else {
            panel.addView(mainBubble, mp);
            cp.topMargin = gap;
            panel.addView(recBubble, cp);
            LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(size, size);
            cp2.topMargin = gap;
            panel.addView(shareBubble, cp2);
        }
    }

    private void expand() {
        if (expanded) return;
        int total = size * 3 + gap * 2;
        shiftedUp = lp.y + total > screenH - dp(48);
        expanded = true;
        if (shiftedUp) lp.y -= (total - size);
        rebuildPanel();
        wm.updateViewLayout(panel, lp);
    }

    private void collapse() {
        if (!expanded) return;
        int total = size * 3 + gap * 2;
        if (shiftedUp) lp.y += (total - size);
        expanded = false;
        shiftedUp = false;
        rebuildPanel();
        wm.updateViewLayout(panel, lp);
    }

    private void attachMainTouch() {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        mainBubble.setOnTouchListener(new View.OnTouchListener() {
            long lastMoveT; float downX, downY; int startX, startY; boolean dragging;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = lp.x; startY = lp.y; dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            dragging = true;
                            if (expanded) {
                                // collapse in place around the main bubble's position
                                collapse();
                                startX = lp.x; startY = lp.y;
                            }
                        }
                        if (dragging) {
                            int nx = clamp(startX + (int) dx, 0, screenW - size);
                            int ny = clamp(startY + (int) dy, 0, screenH - size - dp(24));
                            long t = e.getEventTime();
                            if ((nx != lp.x || ny != lp.y) && t - lastMoveT >= 16) {
                                lastMoveT = t;
                                lp.x = nx; lp.y = ny;
                                wm.updateViewLayout(panel, lp);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            lp.x = (lp.x + size / 2 < screenW / 2) ? 0 : screenW - size;
                            wm.updateViewLayout(panel, lp);
                        } else {
                            if (expanded) collapse(); else expand();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void shareLast() {
        if (rec.saving) { toast("استنى لحد ما الحفظ يخلص"); return; }
        if (rec.recording) { toast("وقف التسجيل الأول"); return; }
        java.io.File f = rec.lastFile;
        if (f == null || !f.exists() || f.length() == 0) { toast("مفيش تسجيل تبعته"); return; }
        Uri uri = Uri.parse("content://com.instinct.micfix.audio/" + f.getName());
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/mp4");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newUri(getContentResolver(), "تسجيل", uri));
        Intent ch = Intent.createChooser(i, "ابعت التسجيل");
        ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(ch); } catch (Throwable t) { toast("خطأ في المشاركة"); }
    }

    private int viewState = -1;

    private void updateViews() {
        if (panel == null) return;
        if (rec.recording) {
            long s = (System.currentTimeMillis() - rec.startedAtMs) / 1000;
            mainBubble.setText(String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
            if (viewState != 1) {
                viewState = 1;
                mainBubble.setTextSize(14);
                setColor(mainBubble, Color.parseColor("#D32F2F"));
                recBubble.setText("■");
                setColor(recBubble, Color.parseColor("#B71C1C"));
            }
        } else if (rec.saving) {
            if (viewState != 2) {
                viewState = 2;
                mainBubble.setText("...");
                mainBubble.setTextSize(18);
                setColor(mainBubble, Color.parseColor("#F9A825"));
                recBubble.setText("●");
                setColor(recBubble, Color.parseColor("#D32F2F"));
            }
        } else if (viewState != 0) {
            viewState = 0;
            mainBubble.setText("🎙");
            mainBubble.setTextSize(24);
            setColor(mainBubble, Color.parseColor("#1565C0"));
            recBubble.setText("●");
            setColor(recBubble, Color.parseColor("#D32F2F"));
        }
        shareBubble.setAlpha(!rec.recording && !rec.saving && rec.lastFile != null ? 1f : 0.5f);
    }

    public void onChange() {
        updateViews();
        boolean busy = rec.recording || rec.saving;
        if (rec.recording) { ui.removeCallbacks(ticker); ui.post(ticker); }
        if (busy != wasBusy) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        }
        if (wasBusy && !busy) toast(rec.status);
        wasBusy = busy;
    }

    public void onAmp(float amp) { }
}
