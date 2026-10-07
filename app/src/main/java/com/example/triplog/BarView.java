package com.example.triplog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** شريط أفقي ملون يمثل المسافة، والقيمة مكتوبة عليه. */
final class BarView extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float frac;
    private String label = "";

    BarView(Context c, int color) {
        super(c);
        bg.setColor(Ui.CARD2);
        fill.setColor(color);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void set(float fraction, String label) {
        frac = Math.max(0f, Math.min(1f, fraction));
        this.label = label;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = h * 0.25f;
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, bg);
        if (frac > 0) {
            r.set(0, 0, Math.max(h * 0.5f, w * frac), h);
            c.drawRoundRect(r, rad, rad, fill);
        }
        text.setTextSize(h * 0.48f);
        c.drawText(label, h * 0.3f, h * 0.68f, text);
    }
}
