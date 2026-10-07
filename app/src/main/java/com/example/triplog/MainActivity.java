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

import java.io.File;
import java.util.ArrayList;
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

    // قراءة معاينة عندما لا توجد رحلة (لترى أن الاتصال يعمل قبل البدء)
    private CarBridge preview;
    private HandlerThread previewThread;
    private Handler previewHandler;

    // عناصر شاشة الرحلة الجارية
    private TextView vMode, vSource, vSpeed, vFuelFlow, vPower, vSoc, vFuel,
            vStart, vElapsed, vDist, vEvShare, vFuelUsed, vKwhOut, vKwhIn;
    // عناصر شاشة البداية
    private TextView pStatus;

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
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
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
    private View buildIdle() {
        LinearLayout wrap = Ui.row(this);

        LinearLayout left = Ui.card(this);
        left.setGravity(Gravity.CENTER);
        left.addView(Ui.text(this, "ابدأ رحلة جديدة", 26, Ui.TEXT, true));
        TextView hint = Ui.text(this, "سيتم تقسيم الرحلة حسب نمط القيادة\nوحسب الكهرباء أو المحرك", 16, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        left.addView(hint);
        left.addView(Ui.button(this, "▶   START", Ui.GOOD, v -> startTrip()));
        pStatus = Ui.text(this, "…", 15, Ui.MUTED, false);
        pStatus.setGravity(Gravity.CENTER);
        pStatus.setPadding(0, Ui.dp(this, 24), 0, 0);
        left.addView(pStatus);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -1, 1f);
        llp.setMarginEnd(Ui.dp(this, 16));
        wrap.addView(left, llp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(Ui.text(this, "الرحلات السابقة", 20, Ui.TEXT, true));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        List<File> files = store.history();
        if (files.isEmpty()) {
            TextView e = Ui.text(this, "لا توجد رحلات بعد", 16, Ui.MUTED, false);
            e.setPadding(0, Ui.dp(this, 12), 0, 0);
            list.addView(e);
        }
        int n = 0;
        for (File f : files) {
            if (++n > 50) break;
            TripSession s = SessionStore.read(f);
            if (s == null) continue;
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, Ui.date(s.startWall) + "  →  " + Ui.time(s.endWall), 17, Ui.TEXT, true));
            c.addView(Ui.text(this, String.format(Locale.US,
                    "%s كم  ·  %s  ·  كهرباء %s%%  ·  %s لتر  ·  %s kWh",
                    Ui.num(s.totalDistKm(), 1), Ui.dur(s.totalDurMs()), Ui.num(s.evShare(), 0),
                    Ui.num(s.totalFuelL(), 2), Ui.num(s.totalKwhOut(), 2)), 15, Ui.MUTED, false));
            c.setOnClickListener(v -> openReport(f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 10);
            list.addView(c, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        right.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        wrap.addView(right, new LinearLayout.LayoutParams(0, -1, 1.3f));
        return wrap;
    }

    private void updateIdle() {
        if (pStatus == null || preview == null) return;
        Live l = preview.live;
        pStatus.setText(String.format(Locale.US, "الاتصال: %s\nالنمط %s  ·  %s  ·  بطارية %s  ·  وقود %s",
                l.source, Live.modeName(l.driveMode), l.engineOn ? "محرك" : "كهرباء",
                Ui.pct(l.socPct), Ui.pct(l.fuelPct)));
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

    // ======================= شاشة الرحلة الجارية =======================
    private View buildLive() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);

        LinearLayout r1 = Ui.row(this);
        vMode = Ui.tile(r1, "نمط القيادة", Ui.TEXT);
        vSource = Ui.tile(r1, "مصدر الحركة", Ui.EV);
        vSpeed = Ui.tile(r1, "السرعة", Ui.TEXT);
        vElapsed = Ui.tile(r1, "المدة", Ui.TEXT);
        v.addView(r1);

        LinearLayout r2 = Ui.row(this);
        vFuelFlow = Ui.tile(r2, "استهلاك البنزين الآن", Ui.ENG);
        vPower = Ui.tile(r2, "قدرة البطارية الآن", Ui.EV);
        vSoc = Ui.tile(r2, "البطارية", Ui.EV);
        vFuel = Ui.tile(r2, "الوقود", Ui.ENG);
        v.addView(r2);

        LinearLayout r3 = Ui.row(this);
        vDist = Ui.tile(r3, "المسافة", Ui.TEXT);
        vEvShare = Ui.tile(r3, "نسبة الكهرباء", Ui.EV);
        vFuelUsed = Ui.tile(r3, "بنزين مستهلك", Ui.ENG);
        vKwhOut = Ui.tile(r3, "كهرباء مستهلكة", Ui.EV);
        vKwhIn = Ui.tile(r3, "كهرباء مسترجعة", Ui.GOOD);
        v.addView(r3);

        LinearLayout bottom = Ui.row(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        vStart = Ui.text(this, "", 18, Ui.MUTED, false);
        bottom.addView(vStart, new LinearLayout.LayoutParams(0, -2, 1f));
        bottom.addView(Ui.button(this, "■   END", Ui.BAD, x -> confirmEnd()));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 16);
        v.addView(bottom, blp);

        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        return sv;
    }

    private void updateLive() {
        TripService svc = TripService.instance;
        if (svc == null || vMode == null) return;
        Live l = svc.live();
        TripSession s = svc.session();
        if (s == null) return;

        vMode.setText(Live.modeName(l.driveMode));
        vSource.setText(l.engineOn ? "⛽ ENGINE" : "⚡ EV");
        vSource.setTextColor(l.engineOn ? Ui.ENG : Ui.EV);
        vSpeed.setText(l.speedKmh == null ? "—" : Ui.num(l.speedKmh, 0) + " كم/س");
        vElapsed.setText(Ui.clock(System.currentTimeMillis() - s.startWall));

        vFuelFlow.setText(l.engineOn ? Ui.num(l.fuelFlowLph, 1) + " ل/س" : "0 ل/س");
        Float kw = l.packKw;
        if (kw == null) vPower.setText("—");
        else {
            float out = s.negIsIn ? kw : -kw;
            vPower.setText((out >= 0 ? "" : "+") + Ui.num(Math.abs(out), 1) + " kW");
            vPower.setTextColor(out >= 0 ? Ui.EV : Ui.GOOD);
        }
        vSoc.setText(Ui.pct(l.socPct));
        vFuel.setText(Ui.pct(l.fuelPct));

        vDist.setText(Ui.num(s.totalDistKm(), 2) + " كم");
        vEvShare.setText(Ui.num(s.evShare(), 0) + "%");
        vFuelUsed.setText(Ui.num(s.totalFuelL(), 2) + " لتر");
        vKwhOut.setText(Ui.num(s.totalKwhOut(), 2) + " kWh");
        vKwhIn.setText(Ui.num(s.totalKwhIn(), 2) + " kWh");
        vStart.setText("بدأت: " + Ui.date(s.startWall));
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
        TripService svc = TripService.instance;
        if (svc != null) f = svc.endTrip();
        else {
            TripSession s = store.loadActive();
            f = s == null ? null : store.finish(s);
        }
        render();
        if (f != null) openReport(f);
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
              .append("القير: ").append(l.gear).append('\n');
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
