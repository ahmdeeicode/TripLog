package com.example.triplog;

import android.app.Activity;
import android.app.AlertDialog;
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
        header.addView(Ui.text(this, "My Trip Stats  ·  تقرير رحلتي", 28, Ui.TEXT, true));
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
        body.addView(dashboard(s));
        TextView hint = Ui.text(this, "اضغط على أي نمط لعرض تفاصيله", 14, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 18));
        body.addView(hint);
        body.addView(Ui.text(this, "Full report", 20, Ui.TEXT, true));
        View tbl = table(s);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.dp(this, 8);
        body.addView(tbl, tlp);

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
        // المعدلات على مسافة كل نوع فقط: البنزين ÷ مسافة المحرك، وكهرباء القيادة ÷ مسافة الكهرباء
        TripSession.Cell ev = s.total(TripSession.EV), eng = s.total(TripSession.ENG);
        Ui.tile(r, "L/100 km", Ui.ENG).setText(eng.distKm > 0.5 ? Ui.num(eng.fuelL / eng.distKm * 100, 1) : "—");
        Ui.tile(r, "Battery used", Ui.EV).setText(Ui.num(s.totalKwhOut(), 2) + " kWh");
        Ui.tile(r, "kWh/100 km", Ui.EV).setText(ev.distKm > 0.5 ? Ui.num(ev.kwhOut / ev.distKm * 100, 1) : "—");
        return r;
    }

    // ---------------- اللوحة: EV | الدائرة | Engine ----------------
    private View dashboard(TripSession s) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        double max = 0.01;
        for (int m = 0; m < 4; m++)
            for (int k = 0; k < 2; k++) max = Math.max(max, s.cells[m][k].distKm);

        LinearLayout ev = panel(s, TripSession.EV, max);
        LinearLayout eng = panel(s, TripSession.ENG, max);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        mid.addView(Ui.text(this, "TOTAL TRIP", 14, Ui.MUTED, true));
        mid.addView(Ui.text(this, Ui.num(s.totalDistKm(), 1) + " km", 30, Ui.TEXT, true));
        DonutView donut = new DonutView(this);
        donut.setData(s.total(TripSession.EV).distKm, s.total(TripSession.ENG).distKm);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 200), Ui.dp(this, 200));
        dlp.topMargin = Ui.dp(this, 10);
        mid.addView(donut, dlp);
        TextView legend = Ui.text(this, "⚡ " + Ui.num(s.total(TripSession.EV).distKm, 1) + " km   ⛽ "
                + Ui.num(s.total(TripSession.ENG).distKm, 1) + " km", 14, Ui.MUTED, false);
        legend.setPadding(0, Ui.dp(this, 10), 0, 0);
        mid.addView(legend);

        int gap = Ui.dp(this, 12);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(ev, plp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(this, 250), -2);
        mlp.setMargins(gap, 0, gap, 0);
        row.addView(mid, mlp);
        row.addView(eng, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    /** لوحة EV أو Engine: لكل نمط شريط مسافة + الطاقة/الوقود + متوسط السرعة. */
    private LinearLayout panel(TripSession s, int kind, double maxDist) {
        boolean isEv = kind == TripSession.EV;
        int color = isEv ? Ui.EV : Ui.ENG;
        LinearLayout p = Ui.card(this);

        p.addView(Ui.text(this, isEv ? "⚡ EV" : "⛽ ENGINE", 22, color, true));

        LinearLayout h = Ui.row(this);
        h.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        h.addView(small("Mode", Ui.dp(this, 76)));
        TextView d = Ui.text(this, isEv ? "EV Distance" : "Distance", 13, Ui.MUTED, true);
        h.addView(d, new LinearLayout.LayoutParams(0, -2, 1f));
        h.addView(small(isEv ? "kWh" : "Fuel", Ui.dp(this, 70)));
        h.addView(small("Speed", Ui.dp(this, 70)));
        p.addView(h);

        for (int m = 0; m < 4; m++) {
            TripSession.Cell c = s.cells[m][kind];
            final int mode = m;
            p.addView(panelRow(TripSession.MODE_NAMES[m], c, isEv, color, (float) (c.distKm / maxDist), false,
                    v -> showDetails(s, mode)));
        }
        View line = divider();
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
        llp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        p.addView(line, llp);
        TripSession.Cell t = s.total(kind);
        p.addView(panelRow("Total", t, isEv, color, -1f, true, v -> showDetails(s, -1)));
        return p;
    }

    private View panelRow(String name, TripSession.Cell c, boolean isEv, int color, float frac,
                          boolean total, View.OnClickListener click) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        r.setOnClickListener(click);

        TextView n = Ui.text(this, name, 16, Ui.TEXT, true);
        r.addView(n, new LinearLayout.LayoutParams(Ui.dp(this, 76), -2));

        boolean has = c.durMs > 0;
        if (total) {
            TextView tv = Ui.text(this, Ui.num(c.distKm, 1) + " km", 16, color, true);
            r.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            BarView bar = new BarView(this, color);
            bar.set(frac, has ? Ui.num(c.distKm, 1) + " km" : "—");
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(this, 30), 1f);
            blp.setMarginEnd(Ui.dp(this, 8));
            r.addView(bar, blp);
        }

        String energy = isEv ? (has || total ? Ui.num(c.kwhOut, 1) : "—")
                             : (has || total ? Ui.num(c.fuelL, 2) + " L" : "—");
        TextView e = Ui.text(this, energy, 15, Ui.TEXT, total);
        e.setGravity(Gravity.CENTER);
        r.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, 70), -2));

        double avg = Ui.avg(c.distKm, c.durMs);
        TextView sp = Ui.text(this, has || total ? Ui.num(avg, 0) : "—", 15, Ui.TEXT, total);
        sp.setGravity(Gravity.CENTER);
        r.addView(sp, new LinearLayout.LayoutParams(Ui.dp(this, 70), -2));
        return r;
    }

    private TextView small(String s, int widthPx) {
        TextView t = Ui.text(this, s, 13, Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(widthPx, -2));
        if (s.equals("Mode")) t.setGravity(Gravity.START);
        return t;
    }

    // ---------------- نافذة تفاصيل النمط ----------------
    private void showDetails(TripSession s, int mode) {
        TripSession.Cell ev = mode < 0 ? s.total(TripSession.EV) : s.cells[mode][TripSession.EV];
        TripSession.Cell eng = mode < 0 ? s.total(TripSession.ENG) : s.cells[mode][TripSession.ENG];
        double recharged = mode < 0 ? s.totalKwhIn() : ev.kwhIn + eng.kwhIn;
        String title = (mode < 0 ? "TOTAL" : TripSession.MODE_NAMES[mode].toUpperCase()) + " MODE DETAILS";

        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        v.setBackgroundColor(Ui.CARD);
        int p = Ui.dp(this, 20);
        v.setPadding(p, p, p, p);

        LinearLayout a = section("⚡ EV", Ui.EV, new String[][]{
                {"Duration", Ui.dur(ev.durMs)},
                {"Charge start", Ui.pct(ev.socFirst)},
                {"Charge end", Ui.pct(ev.socLast)},
                {"Re-charged", Ui.num(recharged, 2) + " kWh"},
                {"kWh consumed", Ui.num(ev.kwhOut, 2) + " kWh"},
                {"Distance", Ui.num(ev.distKm, 1) + " km"},
                {"Average speed", Ui.num(Ui.avg(ev.distKm, ev.durMs), 0) + " km/h"}});
        LinearLayout b = section("⛽ Engine", Ui.ENG, new String[][]{
                {"Duration", Ui.dur(eng.durMs)},
                {"Fuel start", Ui.pct(eng.fuelFirst)},
                {"Fuel end", Ui.pct(eng.fuelLast)},
                {"Refueled", eng.refuelPct > 0 ? "+" + Ui.num(eng.refuelPct, 0) + "%" : "—"},
                {"Fuel consumed", Ui.num(eng.fuelL, 2) + " L"},
                {"Distance", Ui.num(eng.distKm, 1) + " km"},
                {"Average speed", Ui.num(Ui.avg(eng.distKm, eng.durMs), 0) + " km/h"}});
        v.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        View sep = new View(this);
        sep.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(this, 1), -1);
        slp.setMargins(p, 0, p, 0);
        v.addView(sep, slp);
        v.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(v)
                .setPositiveButton("إغلاق", null)
                .show();
    }

    private LinearLayout section(String title, int color, String[][] rows) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(Ui.text(this, title, 20, color, true));
        for (String[] r : rows) {
            LinearLayout line = Ui.row(this);
            line.setPadding(0, Ui.dp(this, 7), 0, 0);
            line.addView(Ui.text(this, r[0], 16, Ui.MUTED, false), new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(Ui.text(this, r[1], 16, Ui.TEXT, true));
            l.addView(line);
        }
        return l;
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
        // الرحلات القديمة سجّلت عداداً لا يتحرك (0.0)، فلا نعرض الفرق إلا إذا تحرك فعلاً
        if (!Float.isNaN(s.odoStart) && !Float.isNaN(s.odoEnd) && s.odoEnd - s.odoStart > 0.05f) {
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
