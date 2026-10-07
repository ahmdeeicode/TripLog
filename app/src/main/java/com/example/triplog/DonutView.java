package com.example.triplog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** الدائرة الملونة في وسط اللوحة: الأزرق = كهرباء، البرتقالي = محرك. */
final class DonutView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eng = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float evFrac;
    private boolean hasData;

    DonutView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Ui.CARD2);
        arc.setStyle(Paint.Style.STROKE);
        small.setColor(Ui.MUTED);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(Typeface.DEFAULT_BOLD);
        big.setColor(Ui.TEXT);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        eng.setColor(Ui.ENG);
        eng.setTextAlign(Paint.Align.CENTER);
        eng.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setData(double evKm, double engKm) {
        double all = evKm + engKm;
        hasData = all > 0.001;
        evFrac = hasData ? (float) (evKm / all) : 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float stroke = s * 0.13f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = (s - stroke) / 2f;
        box.set(cx - r, cy - r, cx + r, cy + r);

        ring.setStrokeWidth(stroke);
        c.drawOval(box, ring);

        if (hasData) {
            arc.setStrokeWidth(stroke);
            float evSweep = 360f * evFrac;
            float gap = (evFrac > 0.01f && evFrac < 0.99f) ? 2f : 0f;
            if (evSweep > 0) {
                arc.setColor(Ui.EV);
                c.drawArc(box, -90f + gap / 2, evSweep - gap, false, arc);
            }
            if (evSweep < 360f) {
                arc.setColor(Ui.ENG);
                c.drawArc(box, -90f + evSweep + gap / 2, 360f - evSweep - gap, false, arc);
            }
        }

        small.setTextSize(s * 0.10f);
        big.setTextSize(s * 0.22f);
        eng.setTextSize(s * 0.09f);
        c.drawText("EV", cx, cy - s * 0.12f, small);
        if (!hasData) {
            c.drawText("—", cx, cy + s * 0.08f, big);
            return;
        }
        int evPct = Math.round(evFrac * 100);
        c.drawText(evPct + "%", cx, cy + s * 0.08f, big);
        // نسبة المحرك = الباقي، حتى يكون المجموع 100%
        c.drawText("ENGINE " + (100 - evPct) + "%", cx, cy + s * 0.21f, eng);
    }
}
