package com.instinct.micfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements Recorder.Listener {

    private static final int REQ_PERM = 7;
    private static final int REQ_OVERLAY = 8;

    private TextView status;
    private WaveformView wave;
    private Button btnRec, btnPlay, btnShare, btnDel, btnBubble;
    private Recorder rec;
    private MediaPlayer player;
    private boolean wantBubble = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rec = Recorder.get(this);
        buildUi();
        if (!rec.hasMicPermission()) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        }
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        rec.listeners.add(this);
        refresh();
    }

    @Override
    protected void onStop() {
        rec.listeners.remove(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wantBubble && canOverlay()) {
            wantBubble = false;
            BubbleService.start(this);
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (!BubbleService.running) rec.stop();
        stopPlayer();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                rec.setStatus("جاهز");
            } else {
                rec.setStatus("محتاج إذن المايك عشان يسجل");
            }
        }
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void toggleBubble() {
        if (BubbleService.running) {
            BubbleService.stop(this);
            btnBubble.postDelayed(new Runnable() { public void run() { refresh(); } }, 300);
            return;
        }
        if (canOverlay()) {
            BubbleService.start(this);
            btnBubble.postDelayed(new Runnable() { public void run() { refresh(); } }, 300);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("إذن العرض فوق التطبيقات")
            .setMessage("الفقاعة العائمة بتفضل ظاهرة فوق أي تطبيق تاني (زي فيسبوك أو واتساب) عشان تسجل وتبعت بضغطة واحدة من غير ما تفتح التطبيق.\n\nعشان كده أندرويد بيطلب منك إذن \"العرض فوق التطبيقات الأخرى\". هتتفتح صفحة الإعدادات: دوس على مسجل الصوت وفعّل الإذن، وارجع للتطبيق.")
            .setPositiveButton("فتح الإعدادات", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    wantBubble = true;
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    try { startActivityForResult(i, REQ_OVERLAY); }
                    catch (Throwable t) { rec.setStatus("مش قادر أفتح الإعدادات"); }
                }
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void refresh() {
        if (status == null) return;
        status.setText(rec.status);
        btnRec.setText(rec.recording ? "■ إيقاف" : "● سجل");
        setActionButtons(!rec.saving && !rec.recording);
        btnBubble.setText(BubbleService.running ? "إخفاء الفقاعة العائمة" : "تشغيل الفقاعة العائمة");
    }

    public void onChange() { refresh(); }
    public void onAmp(float amp) { if (wave != null) wave.addAmplitude(amp); }

    private int dp(int d) {
        return (int) (getResources().getDisplayMetrics().density * d + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#E0E0E0"));
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setText("جاهز");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        wave = new WaveformView(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, dp(90));
        wp.setMargins(0, dp(20), 0, 0);
        root.addView(wave, wp);

        btnRec = new Button(this);
        btnRec.setText("● سجل");
        btnRec.setTextColor(Color.WHITE);
        btnRec.setTextSize(20);
        GradientDrawable red = new GradientDrawable();
        red.setColor(Color.parseColor("#D32F2F"));
        red.setCornerRadius(dp(14));
        btnRec.setBackground(red);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(72));
        rp.setMargins(0, dp(16), 0, dp(16));
        root.addView(btnRec, rp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        btnPlay = actionButton("اسمع");
        btnShare = actionButton("ابعت");
        btnDel = actionButton("احذف");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        cp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(btnPlay, cp);
        row.addView(btnShare, cp);
        row.addView(btnDel, cp);

        btnRec.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (rec.saving) {
                    rec.setStatus("استنى لحد ما الحفظ يخلص");
                } else if (rec.recording) {
                    rec.stop();
                } else {
                    wave.reset();
                    rec.start();
                }
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { playLast(); }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { shareLast(); }
        });
        btnDel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { deleteLast(); }
        });

        btnBubble = new Button(this);
        btnBubble.setTextColor(Color.WHITE);
        btnBubble.setTextSize(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1565C0"));
        bg.setCornerRadius(dp(14));
        btnBubble.setBackground(bg);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(56));
        bp.setMargins(0, dp(16), 0, 0);
        root.addView(btnBubble, bp);
        btnBubble.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleBubble(); }
        });

        TextView footer = new TextView(this);
        footer.setText("© Ahmed Esmat");
        footer.setTextColor(Color.parseColor("#777777"));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.setMargins(0, dp(28), 0, 0);
        root.addView(footer, fp);

        setContentView(root);
    }

    private Button actionButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.parseColor("#E0E0E0"));
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#2A2A2A"));
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        return b;
    }

    private void setActionButtons(boolean enabled) {
        btnPlay.setEnabled(enabled);
        btnShare.setEnabled(enabled);
        btnDel.setEnabled(enabled);
    }

    // ---------- actions ----------

    private boolean checkFile() {
        if (rec.lastFile == null || !rec.lastFile.exists() || rec.lastFile.length() == 0) {
            rec.lastFile = null;
            rec.setStatus("لا يوجد تسجيل");
            return false;
        }
        return true;
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignore) { }
            try { player.release(); } catch (Throwable ignore) { }
            player = null;
        }
    }

    private void playLast() {
        if (rec.saving || !checkFile()) return;
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(rec.lastFile.getAbsolutePath());
            player.prepare();
            player.start();
            rec.setStatus("بتسمع...");
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    stopPlayer();
                    if (rec.lastFile != null && rec.lastFile.exists()) {
                        rec.setStatus("اتحفظ التسجيل (" + Math.max(1, rec.lastFile.length() / 1024) + " كيلو)");
                    }
                }
            });
        } catch (Throwable t) {
            stopPlayer();
            rec.setStatus("خطأ في التشغيل: " + t.getClass().getSimpleName()
                    + ": " + (t.getMessage() != null ? t.getMessage() : ""));
        }
    }

    private void shareLast() {
        if (rec.saving || !checkFile()) return;
        Uri uri = Uri.parse("content://com.instinct.micfix.audio/" + rec.lastFile.getName());
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/mp4");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newUri(getContentResolver(), "تسجيل", uri));
        try {
            startActivity(Intent.createChooser(i, "ابعت التسجيل"));
        } catch (Throwable t) {
            rec.setStatus("خطأ في المشاركة: " + t.getClass().getSimpleName());
        }
    }

    private void deleteLast() {
        if (rec.saving) return;
        stopPlayer();
        if (rec.lastFile == null || !rec.lastFile.exists()) {
            rec.lastFile = null;
            rec.setStatus("لا يوجد تسجيل");
            return;
        }
        boolean ok = rec.lastFile.delete();
        rec.lastFile = null;
        rec.setStatus(ok ? "اتمسح التسجيل" : "مش قادر أمسح الملف");
    }
}
