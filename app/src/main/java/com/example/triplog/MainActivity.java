package com.example.triplog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
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

    private void onUpdateClicked() {
        if (tripActive()) {
            message("أنهِ الرحلة الجارية أولاً، ثم حدّث التطبيق.");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("خطوة لمرة واحدة")
                    .setMessage("لكي يحدّث التطبيق نفسه، اسمح له بتثبيت التطبيقات.\n\n"
                            + "اضغط \"فتح الإعدادات\"، ثم فعّل خيار السماح، وارجع واضغط تحديث مرة أخرى.")
                    .setPositiveButton("فتح الإعدادات", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            message("لم أستطع فتح الإعدادات تلقائياً. افتحها يدوياً: التطبيقات ← رحلتي ← تثبيت تطبيقات غير معروفة.");
                        }
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
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
