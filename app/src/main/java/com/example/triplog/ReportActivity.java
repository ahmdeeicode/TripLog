package com.example.triplog;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/** تقرير الرحلة: ملخص + جدول (EV | Engine) × (Sport, Eco, Normal, Others, Total). */
public class ReportActivity extends Activity {
    static final String EXTRA_FILE = "file";

    private static final int MODE_W = 110, COL_W = 118, ROW_H = 52;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String path = getIntent().getStringExtra(EXTRA_FILE);
        TripSession s = path == null ? null : SessionStore.read(new File(path));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 20);
        root.setPadding(p, p, p, p);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // الجدول بالإنجليزية من اليسار لليمين

        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹", 34, Ui.MUTED, true);
        back.setPadding(0, 0, p, 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        header.addView(Ui.text(this, "My Trip Dashboard  ·  تقرير رحلتي", 28, Ui.TEXT, true));
        root.addView(header);

        if (s == null) {
            root.addView(Ui.text(this, "تعذر قراءة الرحلة", 18, Ui.BAD, false));
            setContentView(root);
            return;
        }

        TextView when = Ui.text(this, Ui.date(s.startWall) + "  →  " + Ui.date(s.endWall), 16, Ui.MUTED, false);
        when.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        root.addView(when);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(summary(s));
        body.addView(evBar(s));
        body.addView(table(s));

        TextView note = Ui.text(this, notes(s), 14, Ui.MUTED, false);
        note.setPadding(0, Ui.dp(this, 12), 0, 0);
        body.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    // ---------------- الملخص ----------------
    private View summary(TripSession s) {
        LinearLayout r = Ui.row(this);
        double dist = s.totalDistKm();
        Ui.tile(r, "Total distance", Ui.TEXT).setText(Ui.num(dist, 1) + " km");
        Ui.tile(r, "Duration", Ui.TEXT).setText(Ui.dur(s.totalDurMs()));
        Ui.tile(r, "EV share", Ui.EV).setText(Ui.num(s.evShare(), 0) + " %");
        Ui.tile(r, "Fuel used", Ui.ENG).setText(Ui.num(s.totalFuelL(), 2) + " L");
        Ui.tile(r, "L/100 km", Ui.ENG).setText(dist > 0.5 ? Ui.num(s.totalFuelL() / dist * 100, 1) : "—");
        Ui.tile(r, "Battery used", Ui.EV).setText(Ui.num(s.totalKwhOut(), 2) + " kWh");
        Ui.tile(r, "kWh/100 km", Ui.EV).setText(dist > 0.5 ? Ui.num(s.totalKwhOut() / dist * 100, 1) : "—");
        return r;
    }

    /** شريط يوضح نسبة EV مقابل Engine من المسافة. */
    private View evBar(TripSession s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 16));
        double ev = s.total(TripSession.EV).distKm, eng = s.total(TripSession.ENG).distKm;
        double all = ev + eng;
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(Ui.CARD2, Ui.dp(this, 8)));
        if (all > 0.001) {
            View a = new View(this); a.setBackground(Ui.round(Ui.EV, Ui.dp(this, 8)));
            View c = new View(this); c.setBackground(Ui.round(Ui.ENG, Ui.dp(this, 8)));
            bar.addView(a, new LinearLayout.LayoutParams(0, -1, (float) Math.max(ev, 0.0001)));
            bar.addView(c, new LinearLayout.LayoutParams(0, -1, (float) Math.max(eng, 0.0001)));
        }
        box.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 14)));
        LinearLayout legend = Ui.row(this);
        legend.addView(Ui.text(this, "⚡ EV  " + Ui.num(ev, 1) + " km", 15, Ui.EV, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView e = Ui.text(this, "⛽ Engine  " + Ui.num(eng, 1) + " km", 15, Ui.ENG, true);
        e.setGravity(Gravity.END);
        legend.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
        legend.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(legend);
        return box;
    }

    // ---------------- الجدول ----------------
    private static final String[] EV_COLS = {"Duration", "Charge start", "Charge end", "Re-charged",
            "kWh consumed", "Distance", "Average speed"};
    private static final String[] ENG_COLS = {"Duration", "Fuel start", "Fuel end", "Refueled",
            "Fuel consumed", "Distance", "Average speed"};

    private View table(TripSession s) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 14)));
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));

        // صف 1: عناوين المجموعات
        LinearLayout g = Ui.row(this);
        g.addView(cell("", MODE_W, Ui.TEXT, false, 0));
        g.addView(cell("⚡ EV", COL_W * 7, Ui.EV, true, 0x3329B6F6));
        g.addView(cell("⛽ Engine", COL_W * 7, Ui.ENG, true, 0x33FFA726));
        t.addView(g);

        // صف 2: أسماء الأعمدة
        LinearLayout h = Ui.row(this);
        h.addView(cell("Mode", MODE_W, Ui.MUTED, true, 0));
        for (String c : EV_COLS) h.addView(cell(c, COL_W, Ui.MUTED, true, 0));
        for (String c : ENG_COLS) h.addView(cell(c, COL_W, Ui.MUTED, true, 0));
        t.addView(h);
        t.addView(divider());

        for (int m = 0; m < 4; m++) {
            TripSession.Cell ev = s.cells[m][TripSession.EV];
            TripSession.Cell eng = s.cells[m][TripSession.ENG];
            double recharged = ev.kwhIn + eng.kwhIn;   // الاسترجاع + الشحن من المحرك في هذا النمط
            t.addView(dataRow(TripSession.MODE_NAMES[m], ev, eng, recharged, false));
        }
        t.addView(divider());
        t.addView(dataRow("Total", s.total(TripSession.EV), s.total(TripSession.ENG), s.totalKwhIn(), true));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(t);
        return hs;
    }

    private View dataRow(String name, TripSession.Cell ev, TripSession.Cell eng, double recharged, boolean total) {
        LinearLayout r = Ui.row(this);
        int c = total ? Ui.TEXT : 0xFFCFD8DC;
        r.addView(cell(name, MODE_W, Ui.TEXT, true, 0));

        boolean hasEv = ev.durMs > 0 || total;
        r.addView(cell(hasEv ? Ui.dur(ev.durMs) : "—", COL_W, c, total, 0));
        r.addView(cell(hasEv ? Ui.pct(ev.socFirst) : "—", COL_W, c, total, 0));
        r.addView(cell(hasEv ? Ui.pct(ev.socLast) : "—", COL_W, c, total, 0));
        r.addView(cell(recharged > 0.005 ? Ui.num(recharged, 2) + " kWh" : "—", COL_W, Ui.GOOD, total, 0));
        r.addView(cell(hasEv ? Ui.num(ev.kwhOut, 2) + " kWh" : "—", COL_W, Ui.EV, total, 0));
        r.addView(cell(hasEv ? Ui.num(ev.distKm, 1) + " km" : "—", COL_W, c, total, 0));
        r.addView(cell(hasEv ? Ui.num(Ui.avg(ev.distKm, ev.durMs), 0) + " km/h" : "—", COL_W, c, total, 0));

        boolean hasEng = eng.durMs > 0 || total;
        r.addView(cell(hasEng ? Ui.dur(eng.durMs) : "—", COL_W, c, total, 0));
        r.addView(cell(hasEng ? Ui.pct(eng.fuelFirst) : "—", COL_W, c, total, 0));
        r.addView(cell(hasEng ? Ui.pct(eng.fuelLast) : "—", COL_W, c, total, 0));
        r.addView(cell(eng.refuelPct > 0 ? "+" + Ui.num(eng.refuelPct, 0) + "%" : "—", COL_W, Ui.GOOD, total, 0));
        r.addView(cell(hasEng ? Ui.num(eng.fuelL, 2) + " L" : "—", COL_W, Ui.ENG, total, 0));
        r.addView(cell(hasEng ? Ui.num(eng.distKm, 1) + " km" : "—", COL_W, c, total, 0));
        r.addView(cell(hasEng ? Ui.num(Ui.avg(eng.distKm, eng.durMs), 0) + " km/h" : "—", COL_W, c, total, 0));
        return r;
    }

    private TextView cell(String s, int widthDp, int color, boolean bold, int bg) {
        Context c = this;
        TextView t = Ui.text(c, s, 15, color, bold);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        if (bg != 0) t.setBackground(Ui.round(bg, Ui.dp(c, 8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, widthDp), Ui.dp(c, ROW_H));
        t.setLayoutParams(lp);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        return v;
    }

    private String notes(TripSession s) {
        StringBuilder sb = new StringBuilder();
        if (!Float.isNaN(s.odoStart) && !Float.isNaN(s.odoEnd)) {
            sb.append(String.format(Locale.US, "حسب عداد السيارة: %.1f كم.  ", s.odoEnd - s.odoStart));
        }
        double engBattery = s.total(TripSession.ENG).kwhOut;
        if (engBattery > 0.01) {
            sb.append(String.format(Locale.US, "صُرف %.2f kWh من البطارية أثناء عمل المحرك (مساندة هجينة).  ", engBattery));
        }
        sb.append("Re-charged = الطاقة الداخلة للبطارية في ذلك النمط (استرجاع الفرامل + الشحن من المحرك).");
        return sb.toString();
    }
}
