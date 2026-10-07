package com.example.triplog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** الشاشة الرئيسية: Start / الرحلة الجارية / End + سجل الرحلات. */
public class MainActivity extends Activity {
    static final String PREFS = "mytrip";
    static final String PREF_NEG_IS_IN = "neg_is_in";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SessionStore store;
    private SharedPreferences prefs;
    private FrameLayout content;
    private boolean showingLive;
    private TextView updateBtn;
    private Updater.Info pendingUpdate;

    // قراءة معاينة عندما لا توجد رحلة (لترى أن الاتصال يعمل قبل البدء)
    private CarBridge preview;
    private HandlerThread previewThread;
    private Handler previewHandler;

    // عناصر شاشة الرحلة الجارية (لوحة حية)
    private TextView vMode, vSource, vElapsed, vStart, vTotal, vSpeed, vPower, vFuelFlow, vLevels;
    private TextView vEvTotal, vEngTotal;
    private DonutView vDonut;
    private final BarView[][] vBars = new BarView[4][2];
    private final TextView[][] vVals = new TextView[4][2];
    private final LinearLayout[][] vRows = new LinearLayout[4][2];
    // عناصر شاشة البداية
    private TextView pStatus, pMode, pKind, pEvRange, pRange;
    private RingGauge gBattery, gFuel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new SessionStore(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        requestNeededPermissions();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 20);
        root.setPadding(p, p, p, p);

        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "رحلتي  ·  My Trip", 30, Ui.TEXT, true);
        header.addView(title);
        TextView ver = Ui.text(this, "  v" + Updater.installedName(this), 15, Ui.MUTED, false);
        header.addView(ver, new LinearLayout.LayoutParams(0, -2, 1f));
        updateBtn = Ui.text(this, "⟳  تحديث", 18, Ui.MUTED, false);
        updateBtn.setPadding(p, p / 2, p, p / 2);
        updateBtn.setOnClickListener(v -> onUpdateClicked());
        header.addView(updateBtn);
        TextView diag = Ui.text(this, "⚙  تشخيص", 18, Ui.MUTED, false);
        diag.setPadding(p, p / 2, p, p / 2);
        diag.setOnClickListener(v -> showDiagnostics());
        header.addView(diag);
        root.addView(header);

        content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = p / 2;
        root.addView(content, clp);
        setContentView(root);

        if (store.loadActive() != null) TripService.start(this); // استئناف رحلة لم تُنهَ
        checkUpdateQuietly();
    }

    // ======================= التحديث =======================
    /** فحص صامت عند الفتح: إذا وُجدت نسخة أحدث يتغير لون الزر. */
    private void checkUpdateQuietly() {
        new Thread(() -> {
            try {
                Updater.Info i = Updater.fetchLatest();
                if (i != null && i.code > Updater.installedCode(this)) {
                    ui.post(() -> {
                        pendingUpdate = i;
                        updateBtn.setText("⬆  تحديث متوفر");
                        updateBtn.setTextColor(Ui.GOOD);
                    });
                }
            } catch (Exception ignored) { }
        }).start();
    }

    static final String INSTALL_PAGE = "ahmdeeicode.github.io/TripLog";

    private void onUpdateClicked() {
        if (tripActive()) {
            message("أنهِ الرحلة الجارية أولاً، ثم حدّث التطبيق.");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            // شاشة السيارة لا تعرض إعداد "تثبيت تطبيقات غير معروفة"، فالصلاحية تُمنح من صفحة التثبيت عبر ADB
            message("لكي يحدّث التطبيق نفسه يحتاج صلاحية تثبيت التطبيقات، وشاشة السيارة لا تعرض هذا الخيار في الإعدادات.\n\n"
                    + "ثبّت التطبيق مرة واحدة من صفحة التثبيت بالكمبيوتر (تمنحه الصلاحية تلقائياً):\n"
                    + INSTALL_PAGE + "\n\nبعدها يعمل زر «تحديث» دائماً بدون كمبيوتر.");
            return;
        }
        TextView status = Ui.text(this, "جاري البحث عن تحديث…", 18, Ui.TEXT, false);
        status.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(status).setCancelable(false).show();
        new Thread(() -> {
            try {
                Updater.Info i = Updater.fetchLatest();
                long mine = Updater.installedCode(this);
                ui.post(() -> {
                    dlg.dismiss();
                    if (i == null || i.code <= mine) {
                        message("لديك آخر نسخة ✅\n\nالنسخة الحالية: v" + Updater.installedName(this));
                    } else {
                        offerUpdate(i);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); message("تعذر الاتصال بـ GitHub.\nتأكد من اتصال الشاشة بالإنترنت.\n\n" + e.getMessage()); });
            }
        }).start();
    }

    private void offerUpdate(Updater.Info i) {
        String notes = i.notes == null || i.notes.trim().isEmpty() ? "" : "\n\nما الجديد:\n" + i.notes.trim();
        new AlertDialog.Builder(this)
                .setTitle("نسخة جديدة متوفرة")
                .setMessage("النسخة الحالية: v" + Updater.installedName(this)
                        + "\nالنسخة الجديدة: " + i.tag + notes)
                .setPositiveButton("تحديث الآن", (d, w) -> install(i))
                .setNegativeButton("لاحقاً", null)
                .show();
    }

    private void install(Updater.Info i) {
        TextView status = Ui.text(this, "جاري التحميل… 0%", 18, Ui.TEXT, false);
        status.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(status).setCancelable(false).show();
        new Thread(() -> {
            try {
                final int[] last = {-1};
                Updater.downloadAndInstall(this, i, (done, total) -> {
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != last[0]) {
                        last[0] = pct;
                        ui.post(() -> status.setText(pct >= 0 ? "جاري التحميل… " + pct + "%"
                                : "جاري التحميل… " + (done / 1024) + " KB"));
                    }
                });
                ui.post(() -> {
                    dlg.dismiss();
                    updateBtn.setText("⟳  تحديث");
                    updateBtn.setTextColor(Ui.MUTED);
                });
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); message("فشل التحميل:\n" + e.getMessage()); });
            }
        }).start();
    }

    private void message(String m) {
        new AlertDialog.Builder(this).setMessage(m).setPositiveButton("حسناً", null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        ui.post(refresh);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
        stopPreview();
    }

    private boolean tripActive() { return TripService.instance != null || store.loadActive() != null; }

    private void render() {
        boolean live = tripActive();
        showingLive = live;
        content.removeAllViews();
        if (live) { stopPreview(); content.addView(buildLive()); }
        else { content.addView(buildIdle()); startPreview(); }
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (tripActive() != showingLive) render();
            if (showingLive) updateLive(); else updateIdle();
            ui.postDelayed(this, 500);
        }
    };

    // ======================= شاشة البداية =======================
    // التخطيط من اليمين لليسار: أول عنصر يُضاف يظهر يميناً. الرحلات يميناً، وSTART يساراً جهة السائق.
    private View buildIdle() {
        LinearLayout wrap = Ui.row(this);
        List<TripSession> trips = loadTrips();

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1.25f);
        rlp.setMarginStart(Ui.dp(this, 16));
        wrap.addView(buildTripsPanel(trips), rlp);
        wrap.addView(buildStartPanel(), new LinearLayout.LayoutParams(0, -1, 1f));
        return wrap;
    }

    /** لوحة START: حالة السيارة الآن (النمط، البطارية، الوقود، المدى) وزر البدء. */
    private View buildStartPanel() {
        LinearLayout p = Ui.card(this);
        p.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout chips = Ui.row(this);
        chips.setGravity(Gravity.CENTER);
        pMode = chip("—", Ui.GOOD);
        pKind = chip("—", Ui.EV);
        chips.addView(pMode);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMarginStart(Ui.dp(this, 10));
        chips.addView(pKind, clp);
        p.addView(chips);

        LinearLayout gauges = Ui.row(this);
        gauges.setGravity(Gravity.CENTER);
        gauges.setBaselineAligned(false);
        gBattery = new RingGauge(this, "البطارية", Ui.EV);
        gFuel = new RingGauge(this, "الوقود", Ui.ENG);
        gFuel.setLowWarning(15);
        pEvRange = Ui.text(this, " ", 16, Ui.MUTED, false);
        pRange = Ui.text(this, " ", 16, Ui.MUTED, false);
        gauges.addView(gaugeColumn(gBattery, pEvRange), new LinearLayout.LayoutParams(0, -2, 1f));
        gauges.addView(gaugeColumn(gFuel, pRange), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = Ui.dp(this, 12);
        p.addView(gauges, glp);

        TextView start = Ui.button(this, "▶   START", Ui.GOOD, v -> startTrip());
        start.setTextSize(28);
        int pad = Ui.dp(this, 24);
        start.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 16);
        p.addView(start, slp);

        pStatus = Ui.text(this, "…", 13, Ui.MUTED, false);
        pStatus.setGravity(Gravity.CENTER);
        pStatus.setPadding(0, Ui.dp(this, 12), 0, 0);
        p.addView(pStatus);
        return p;
    }

    private View gaugeColumn(RingGauge g, TextView under) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int size = Ui.dp(this, 230);
        col.addView(g, new LinearLayout.LayoutParams(size, size));
        under.setGravity(Gravity.CENTER);
        col.addView(under);
        return col;
    }

    /** يعزل رقماً بوحدته (مثل "90 km") داخل نص عربي حتى لا ينقلب ترتيبه. */
    private static String ltr(String s) { return "\u2066" + s + "\u2069"; }

    private TextView chip(String s, int color) {
        TextView t = Ui.text(this, s, 18, 0xFFFFFFFF, true);
        t.setBackground(Ui.round(color, Ui.dp(this, 10)));
        int h = Ui.dp(this, 14), v = Ui.dp(this, 6);
        t.setPadding(h, v, h, v);
        return t;
    }

    /** لوحة الرحلات: ملخص كل الرحلات، ثم الرحلات مجمّعة حسب اليوم. */
    private View buildTripsPanel(List<TripSession> trips) {
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        if (!trips.isEmpty()) {
            list.addView(Ui.text(this, "إحصائياتك  ·  عدد الرحلات " + trips.size(), 20, Ui.TEXT, true));
            list.addView(statsRow(trips));
        }
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(this, trips.isEmpty() ? 0 : 18);
        list.addView(Ui.text(this, "الرحلات السابقة", 20, Ui.TEXT, true), hlp);
        if (trips.isEmpty()) {
            TextView e = Ui.text(this, "لا توجد رحلات بعد. اضغط START لتبدأ أول رحلة.", 16, Ui.MUTED, false);
            e.setPadding(0, Ui.dp(this, 12), 0, 0);
            list.addView(e);
        } else {
            list.addView(Ui.text(this, "اضغط على رحلة لعرض تقريرها، ومطولاً لحذفها", 13, Ui.MUTED, false));
        }

        String lastDay = null;
        int n = 0;
        for (TripSession s : trips) {
            if (++n > 50) break;
            String day = dayLabel(s.startWall);
            if (!day.equals(lastDay)) {
                lastDay = day;
                TextView d = Ui.text(this, day, 15, Ui.MUTED, true);
                d.setPadding(0, Ui.dp(this, 14), 0, 0);
                list.addView(d);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 8);
            list.addView(tripCard(s), lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        right.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        return right;
    }

    private View statsRow(List<TripSession> trips) {
        double km = 0, evKm = 0, kwh = 0, litres = 0;
        for (TripSession s : trips) {
            km += s.totalDistKm();
            evKm += s.total(TripSession.EV).distKm;
            kwh += s.totalKwhOut();
            litres += s.totalFuelL();
        }
        LinearLayout r = Ui.row(this);
        Ui.tile(r, "إجمالي المسافة", Ui.TEXT).setText(Ui.num(km, 1) + " km");
        Ui.tile(r, "على الكهرباء", Ui.EV).setText(km > 0.01 ? ltr(Ui.num(evKm / km * 100, 0) + "%") : "—");
        Ui.tile(r, "كهرباء مصروفة", Ui.EV).setText(Ui.num(kwh, 1) + " kWh");
        Ui.tile(r, "بنزين مصروف", Ui.ENG).setText(Ui.num(litres, 2) + " L");
        return r;
    }

    private View tripCard(TripSession s) {
        LinearLayout c = Ui.card(this);
        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView km = Ui.text(this, ltr(Ui.num(s.totalDistKm(), 1) + " km"), 26, Ui.TEXT, true);
        km.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        top.addView(km, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(Ui.text(this, ltr(Ui.time(s.startWall) + "  →  " + Ui.time(s.endWall)), 17, Ui.MUTED, false));
        c.addView(top);

        // شريط الكهرباء مقابل المحرك
        double ev = s.total(TripSession.EV).distKm, all = s.totalDistKm();
        float evW = all > 0.001 ? (float) (ev / all) : 0f;
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(Ui.CARD2, Ui.dp(this, 5)));
        bar.setClipToOutline(true);
        View evPart = new View(this);
        evPart.setBackgroundColor(Ui.EV);
        View engPart = new View(this);
        engPart.setBackgroundColor(all > 0.001 ? Ui.ENG : Ui.CARD2);
        bar.addView(evPart, new LinearLayout.LayoutParams(0, -1, evW));
        bar.addView(engPart, new LinearLayout.LayoutParams(0, -1, 1f - evW));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 10));
        blp.topMargin = Ui.dp(this, 10);
        blp.bottomMargin = Ui.dp(this, 10);
        c.addView(bar, blp);

        c.addView(Ui.text(this, String.format(Locale.US, "\u2066⚡ %s%%  ·  %s kWh     ⛽ %s L     ⏱ %s\u2069",
                Ui.num(s.evShare(), 0), Ui.num(s.totalKwhOut(), 2), Ui.num(s.totalFuelL(), 2),
                Ui.dur(s.totalDurMs())), 15, Ui.MUTED, false));

        File f = s.file;
        c.setOnClickListener(v -> openReport(f));
        c.setOnLongClickListener(v -> { confirmDelete(f, s); return true; });
        return c;
    }

    private List<TripSession> loadTrips() {
        List<TripSession> out = new ArrayList<>();
        for (File f : store.history()) {
            TripSession s = SessionStore.read(f);
            if (s == null) continue;
            s.file = f;
            out.add(s);
        }
        return out;
    }

    private static String dayLabel(long wall) {
        Calendar t = Calendar.getInstance();
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(wall);
        if (t.get(Calendar.YEAR) == d.get(Calendar.YEAR)) {
            int diff = t.get(Calendar.DAY_OF_YEAR) - d.get(Calendar.DAY_OF_YEAR);
            if (diff == 0) return "اليوم";
            if (diff == 1) return "أمس";
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(wall));
    }

    private void updateIdle() {
        if (pStatus == null || preview == null) return;
        Live l = preview.live;
        pMode.setText(Live.modeName(l.driveMode));
        pKind.setText(l.engineOn ? "⛽ محرك" : "⚡ كهرباء");
        pKind.setBackground(Ui.round(l.engineOn ? Ui.ENG : Ui.EV, Ui.dp(this, 10)));
        gBattery.setValue(l.socPct);
        gFuel.setValue(l.fuelPct);
        pEvRange.setText(l.evRangeKm == null ? " " : "مدى كهربائي  " + ltr("~" + Math.round(l.evRangeKm) + " km"));
        pRange.setText(l.rangeKm == null ? " " : "المدى  " + ltr("~" + Math.round(l.rangeKm) + " km"));
        pStatus.setText("الاتصال: " + l.source);
    }

    private void startPreview() {
        if (preview != null) return;
        preview = new CarBridge();
        previewThread = new HandlerThread("preview");
        previewThread.start();
        previewHandler = new Handler(previewThread.getLooper());
        previewHandler.post(new Runnable() {
            @Override public void run() {
                try { preview.connect(MainActivity.this); preview.poll(); } catch (Throwable ignored) { }
                if (previewHandler != null) previewHandler.postDelayed(this, 1000);
            }
        });
    }

    private void stopPreview() {
        if (preview == null) return;
        previewHandler.removeCallbacksAndMessages(null);
        previewThread.quitSafely();
        preview.release();
        preview = null;
        previewHandler = null;
    }

    private void startTrip() {
        stopPreview();
        TripSession s = new TripSession();
        s.active = true;
        s.startWall = System.currentTimeMillis();
        s.negIsIn = prefs.getBoolean(PREF_NEG_IS_IN, true);
        store.saveActive(s);
        TripService.start(this);
        render();
    }

    // ======================= شاشة الرحلة الجارية (لوحة حية) =======================
    private View buildLive() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        // الشريط العلوي: النمط + المصدر + الوقت
        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        vMode = pill("—", Ui.CARD2, Ui.TEXT);
        top.addView(vMode);
        vSource = pill("—", Ui.CARD2, Ui.TEXT);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.setMarginStart(Ui.dp(this, 10));
        top.addView(vSource, slp);
        vStart = Ui.text(this, "", 15, Ui.MUTED, false);
        vStart.setPadding(Ui.dp(this, 16), 0, 0, 0);
        top.addView(vStart, new LinearLayout.LayoutParams(0, -2, 1f));
        vElapsed = Ui.text(this, "0:00:00", 26, Ui.TEXT, true);
        top.addView(vElapsed);
        v.addView(top);

        // اللوحة: EV | الدائرة | Engine
        LinearLayout dash = Ui.row(this);
        dash.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ev = livePanel(TripSession.EV);
        LinearLayout eng = livePanel(TripSession.ENG);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        mid.addView(Ui.text(this, "TOTAL TRIP", 14, Ui.MUTED, true));
        vTotal = Ui.text(this, "0.0 km", 30, Ui.TEXT, true);
        mid.addView(vTotal);
        vDonut = new DonutView(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 190), Ui.dp(this, 190));
        dlp.topMargin = Ui.dp(this, 8);
        mid.addView(vDonut, dlp);

        int gap = Ui.dp(this, 12);
        dash.addView(ev, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(this, 230), -2);
        mlp.setMargins(gap, 0, gap, 0);
        dash.addView(mid, mlp);
        dash.addView(eng, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams dalp = new LinearLayout.LayoutParams(-1, -2);
        dalp.topMargin = Ui.dp(this, 14);
        v.addView(dash, dalp);

        // القراءات اللحظية + زر الإنهاء
        LinearLayout bottom = Ui.row(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        vSpeed = Ui.tile(bottom, "Speed", Ui.TEXT);
        vPower = Ui.tile(bottom, "Battery power", Ui.EV);
        vFuelFlow = Ui.tile(bottom, "Fuel flow", Ui.ENG);
        vLevels = Ui.tile(bottom, "Battery · Fuel", Ui.TEXT);
        TextView end = Ui.button(this, "■  END", Ui.BAD, x -> confirmEnd());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.setMarginStart(Ui.dp(this, 8));
        bottom.addView(end, elp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 10);
        v.addView(bottom, blp);

        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        return sv;
    }

    private TextView pill(String s, int bg, int color) {
        TextView t = Ui.text(this, s, 18, color, true);
        t.setBackground(Ui.round(bg, Ui.dp(this, 10)));
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        return t;
    }

    /** لوحة EV أو Engine: صف لكل نمط (اسم + شريط مسافة + طاقة/وقود) ثم المجموع. */
    private LinearLayout livePanel(int kind) {
        boolean isEv = kind == TripSession.EV;
        int color = isEv ? Ui.EV : Ui.ENG;
        LinearLayout p = Ui.card(this);
        p.addView(Ui.text(this, isEv ? "⚡ EV" : "⛽ ENGINE", 22, color, true));
        for (int m = 0; m < 4; m++) {
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Ui.dp(this, 6);
            r.setPadding(pad, pad, pad, pad);
            r.addView(Ui.text(this, TripSession.MODE_NAMES[m], 16, Ui.TEXT, true),
                    new LinearLayout.LayoutParams(Ui.dp(this, 72), -2));
            BarView bar = new BarView(this, color);
            bar.set(0, "—");
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(this, 30), 1f);
            blp.setMarginEnd(Ui.dp(this, 8));
            r.addView(bar, blp);
            TextView val = Ui.text(this, "—", 15, Ui.TEXT, false);
            val.setGravity(Gravity.END);
            r.addView(val, new LinearLayout.LayoutParams(Ui.dp(this, 82), -2));
            vBars[m][kind] = bar;
            vVals[m][kind] = val;
            vRows[m][kind] = r;
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = Ui.dp(this, 4);
            p.addView(r, rlp);
        }
        TextView total = Ui.text(this, "Total —", 16, color, true);
        total.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), 0, 0);
        p.addView(total);
        if (isEv) vEvTotal = total; else vEngTotal = total;
        return p;
    }

    private void updateLive() {
        TripService svc = TripService.instance;
        if (svc == null || vMode == null) return;
        Live l = svc.live();
        TripSession s = svc.session();
        if (s == null) return;

        // الشريط العلوي
        String mode = Live.modeName(l.driveMode);
        vMode.setText(mode);
        vMode.setBackground(Ui.round(modeColor(l.driveMode), Ui.dp(this, 10)));
        vSource.setText(l.engineOn ? "⛽ ENGINE" : "⚡ EV");
        vSource.setBackground(Ui.round(l.engineOn ? 0xFF8A5A00 : 0xFF0D5C8C, Ui.dp(this, 10)));
        vElapsed.setText(Ui.clock(System.currentTimeMillis() - s.startWall));
        vStart.setText("بدأت " + Ui.date(s.startWall));

        // اللوحة
        double max = 1.0; // كم: حتى لا تقفز الأشرطة في أول الرحلة
        for (int m = 0; m < 4; m++)
            for (int k = 0; k < 2; k++) max = Math.max(max, s.cells[m][k].distKm);
        int curMode = TripSession.bucket(l.driveMode);
        int curKind = l.engineOn ? TripSession.ENG : TripSession.EV;
        for (int m = 0; m < 4; m++) {
            for (int k = 0; k < 2; k++) {
                TripSession.Cell c = s.cells[m][k];
                boolean has = c.durMs > 0;
                vBars[m][k].set((float) (c.distKm / max), has ? Ui.num(c.distKm, 1) + " km" : "");
                vVals[m][k].setText(!has ? "—" : k == TripSession.EV
                        ? Ui.num(c.kwhOut, 2) + " kWh" : Ui.num(c.fuelL, 2) + " L");
                boolean current = m == curMode && k == curKind;
                vRows[m][k].setBackground(current
                        ? Ui.round(k == TripSession.EV ? 0x3329B6F6 : 0x33FFA726, Ui.dp(this, 10)) : null);
            }
        }
        TripSession.Cell te = s.total(TripSession.EV), tg = s.total(TripSession.ENG);
        vEvTotal.setText("Total  " + Ui.num(te.distKm, 1) + " km  ·  " + Ui.num(te.kwhOut, 2) + " kWh");
        vEngTotal.setText("Total  " + Ui.num(tg.distKm, 1) + " km  ·  " + Ui.num(tg.fuelL, 2) + " L");
        vTotal.setText(Ui.num(te.distKm + tg.distKm, 1) + " km");
        vDonut.setData(te.distKm, tg.distKm);

        // القراءات اللحظية
        vSpeed.setText(l.speedKmh == null ? "—" : Ui.num(l.speedKmh, 0) + " km/h");
        Float kw = l.packKw;
        if (kw == null) vPower.setText("—");
        else {
            float out = s.negIsIn ? kw : -kw;
            vPower.setText((out >= 0 ? "" : "+") + Ui.num(Math.abs(out), 1) + " kW");
            vPower.setTextColor(out >= 0 ? Ui.EV : Ui.GOOD);
        }
        vFuelFlow.setText(l.engineOn ? Ui.num(l.fuelFlowLph, 1) + " L/h" : "0.0 L/h");
        vLevels.setText(Ui.pct(l.socPct) + " · " + Ui.pct(l.fuelPct));
    }

    private static int modeColor(Integer mode) {
        switch (TripSession.bucket(mode)) {
            case TripSession.SPORT: return 0xFFB3261E;
            case TripSession.ECO: return 0xFF2E7D32;
            case TripSession.NORMAL: return 0xFF37474F;
            default: return 0xFF5D4037;
        }
    }

    private void confirmDelete(File f, TripSession s) {
        new AlertDialog.Builder(this)
                .setTitle("حذف هذه الرحلة؟")
                .setMessage(Ui.date(s.startWall) + "  →  " + Ui.time(s.endWall) + "\n"
                        + Ui.num(s.totalDistKm(), 1) + " كم\n\nلا يمكن التراجع عن الحذف.")
                .setPositiveButton("حذف", (d, w) -> { store.delete(f); render(); })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void confirmEnd() {
        new AlertDialog.Builder(this)
                .setTitle("إنهاء الرحلة؟")
                .setMessage("سيتم حفظ الرحلة وعرض التقرير.")
                .setPositiveButton("إنهاء", (d, w) -> endTrip())
                .setNegativeButton("متابعة", null)
                .show();
    }

    private void endTrip() {
        File f;
        boolean had;
        TripService svc = TripService.instance;
        if (svc != null) { had = true; f = svc.endTrip(); }
        else {
            TripSession s = store.loadActive();
            had = s != null;
            f = s == null ? null : store.finish(s);
        }
        render();
        if (f != null) openReport(f);
        else if (had) Toast.makeText(this, "الرحلة أقصر من 0.1 كم، لم تُحفظ", Toast.LENGTH_LONG).show();
    }

    private void openReport(File f) {
        startActivity(new Intent(this, ReportActivity.class).putExtra(ReportActivity.EXTRA_FILE, f.getAbsolutePath()));
    }

    // ======================= التشخيص =======================
    private void showDiagnostics() {
        TripService svc = TripService.instance;
        Live l = svc != null ? svc.live() : (preview != null ? preview.live : null);
        long now = SystemClock.elapsedRealtime();
        StringBuilder sb = new StringBuilder();
        if (l == null) sb.append("لا توجد قراءات بعد.");
        else {
            sb.append("المصدر: ").append(l.source).append('\n')
              .append("المستمع اللحظي: ").append(l.realtime).append('\n')
              .append("آخر إشارة لحظية (ث): سرعة ").append(age(now, l.speedCbTs))
              .append(" · دورات ").append(age(now, l.rpmCbTs))
              .append(" · قدرة ").append(age(now, l.powerCbTs))
              .append(" · نمط ").append(age(now, l.modeCbTs))
              .append(" · وقود ").append(age(now, l.fuelCbTs)).append('\n')
              .append("نبضات عداد الوقود: ").append(l.fuelCbCount).append("\n\n")
              .append("السرعة: ").append(l.speedKmh).append('\n')
              .append("الدورات RPM: ").append(l.rpm).append("  → ").append(l.engineOn ? "محرك" : "كهرباء").append('\n')
              .append("نمط القيادة (خام): ").append(l.driveMode).append(" = ").append(Live.modeName(l.driveMode)).append('\n')
              .append("قدرة البطارية (خام) kW: ").append(l.packKw).append('\n')
              .append("البطارية %: ").append(l.socPct).append('\n')
              .append("الوقود %: ").append(l.fuelPct).append("  لتر: ").append(l.fuelLitres).append('\n')
              .append("العداد كم: ").append(l.odometerKm).append('\n')
              .append("القير: ").append(l.gear).append('\n')
              .append("المدى الكهربائي كم: ").append(l.evRangeKm).append('\n')
              .append("مدى العدادات كم: ").append(l.rangeKm).append('\n');
        }
        boolean neg = prefs.getBoolean(PREF_NEG_IS_IN, true);
        sb.append("\nاتجاه قدرة البطارية: ").append(neg ? "السالب = شحن (افتراضي)" : "الموجب = شحن (معكوس)")
          .append("\nاختبار: أثناء القيادة على الكهرباء يجب أن تظهر \"كهرباء مستهلكة\" تزيد، لا \"المسترجعة\".");

        new AlertDialog.Builder(this)
                .setTitle("تشخيص")
                .setMessage(sb.toString())
                .setPositiveButton("إغلاق", null)
                .setNeutralButton("عكس اتجاه البطارية", (d, w) -> {
                    boolean nv = !prefs.getBoolean(PREF_NEG_IS_IN, true);
                    prefs.edit().putBoolean(PREF_NEG_IS_IN, nv).apply();
                    TripService s2 = TripService.instance;
                    if (s2 != null && s2.session() != null) s2.session().negIsIn = nv;
                })
                .show();
    }

    private static String age(long now, long ts) {
        return ts == 0 ? "—" : String.format(Locale.US, "%.1f", (now - ts) / 1000.0);
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        String[] perms = {
                "android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY",
                "android.car.permission.CAR_MILEAGE", "android.car.permission.CAR_POWERTRAIN",
                "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"};
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) need.add("android.permission.POST_NOTIFICATIONS");
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }
}
