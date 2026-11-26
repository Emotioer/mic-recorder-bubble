package com.instinct.micfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Simple rolling waveform visualizer. The recording thread feeds it one
 * normalized amplitude (0..1) roughly every 50 ms; it draws the most recent
 * values as centered vertical bars in the app's red accent.
 */
public class WaveformView extends View {

    private static final int MAX_BARS = 64;
    private final float[] amps = new float[MAX_BARS];
    private int count = 0;
    private final android.graphics.RectF rect = new android.graphics.RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveformView(Context context) {
        super(context);
        paint.setColor(Color.parseColor("#E53935"));
        paint.setStyle(Paint.Style.FILL);
    }

    public void addAmplitude(float a) {
        synchronized (this) { addLocked(a); }
        invalidate();
    }

    private void addLocked(float a) {
        if (count < MAX_BARS) {
            amps[count++] = a;
        } else {
            System.arraycopy(amps, 1, amps, 0, MAX_BARS - 1);
            amps[MAX_BARS - 1] = a;
        }
    }

    public synchronized void reset() {
        count = 0;
        postInvalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (count == 0) {
            paint.setAlpha(50);
            canvas.drawRect(0, h / 2f - 2, w, h / 2f + 2, paint);
            paint.setAlpha(255);
            return;
        }
        float bw = w / (float) MAX_BARS;
        float barW = bw * 0.6f;
        for (int i = 0; i < count; i++) {
            float a = Math.min(1f, amps[i]);
            float bh = Math.max(h * 0.06f, a * h);
            float left = i * bw + (bw - barW) / 2f;
            float top = (h - bh) / 2f;
            rect.set(left, top, left + barW, top + bh);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
        }
    }
}
