package com.example.triplog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** واجهة بسيطة جداً مبنية بالكود: الحالة الحية + قائمة الرحلات. */
public class MainActivity extends Activity {
    private TextView status;
    private LinearLayout list;
    private TripStore store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int tickCount;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new TripStore(this);
        requestNeededPermissions();
        TripService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("سجل الرحلات", 26, Color.WHITE);
        root.addView(title);

        status = text("…", 18, 0xFFB0BEC5);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("إنهاء الرحلة وحفظها", v -> endTripNow()));
        buttons.addView(button("مسح السجل", v -> confirmClear()));
        root.addView(buttons);

        TextView path = text("الملف: " + store.csvFile().getAbsolutePath(), 13, 0xFF78909C);
        path.setPadding(0, dp(8), 0, dp(8));
        root.addView(path);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        refreshTrips();
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            if (++tickCount % 10 == 0) refreshTrips();
            ui.postDelayed(this, 1000);
        }
    };

    private void renderStatus() {
        Snapshot s = TripService.lastSnap;
        StringBuilder sb = new StringBuilder();
        sb.append("المصدر: ").append(TripService.source)
          .append(TripService.running ? "  ●" : "  (الخدمة متوقفة)").append('\n');
        if (s != null) {
            sb.append("السرعة: ").append(fmt(s.speedKmh, "%.0f كم/س"))
              .append("   القير: ").append(s.gearLabel())
              .append("   البطارية: ").append(fmt(s.socPct, "%.0f%%"))
              .append("   الوقود: ").append(fmt(s.fuelPct, "%.0f%%")).append('\n')
              .append("العداد: ").append(fmt(s.odometerKm, "%.0f كم"))
              .append("   المدى: ").append(s.rangeKm == null ? "—" : s.rangeKm + " كم")
              .append("   الحرارة: ").append(fmt(s.outsideTempC, "%.0f°")).append('\n');
        }
        Trip t = TripService.liveTrip;
        if (t != null) {
            sb.append(String.format(Locale.US, "▶ رحلة جارية: %.2f كم · %d د · أقصى %.0f كم/س",
                    t.distKm, t.durationMs() / 60000, t.maxSpeed));
        } else {
            sb.append("لا توجد رحلة جارية");
        }
        status.setText(sb.toString());
    }

    private void refreshTrips() {
        list.removeAllViews();
        List<String[]> rows = store.readAll();
        if (rows.isEmpty()) {
            list.addView(text("لم تُسجَّل رحلات بعد", 16, 0xFF78909C));
            return;
        }
        int n = 0;
        for (String[] r : rows) {
            if (r.length < 17 || ++n > 100) break;
            // start,end,duration,moving,dist,odoDist,avg,max,socS,socE,fuelS,fuelE,rangeS,rangeE,pos,neg,temp
            String line1 = r[0] + "  ←  " + r[1].substring(Math.max(0, r[1].length() - 5));
            String line2 = "المسافة " + r[4] + " كم · المدة " + r[2] + " د · متوسط " + r[6]
                    + " · أقصى " + r[7] + " كم/س";
            List<String> extra = new ArrayList<>();
            if (!r[8].isEmpty() && !r[9].isEmpty()) extra.add("البطارية " + r[8] + "% → " + r[9] + "%");
            if (!r[10].isEmpty() && !r[11].isEmpty()) extra.add("الوقود " + r[10] + "% → " + r[11] + "%");
            if (!r[5].isEmpty()) extra.add("حسب العداد " + r[5] + " كم");

            TextView card = text(line1 + "\n" + line2 + (extra.isEmpty() ? "" : "\n" + String.join(" · ", extra)),
                    16, Color.WHITE);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF263238);
            bg.setCornerRadius(dp(12));
            card.setBackground(bg);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            list.addView(card, lp);
        }
    }

    private void endTripNow() {
        startService(new android.content.Intent(this, TripService.class)
                .setAction(TripService.ACTION_END_TRIP));
        ui.postDelayed(this::refreshTrips, 500);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("حذف كل الرحلات المسجلة؟")
                .setPositiveButton("حذف", (d, w) -> { store.clear(); refreshTrips(); })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        String[] perms = {
                "android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY",
                "android.car.permission.CAR_MILEAGE", "android.car.permission.CAR_POWERTRAIN",
                "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
        };
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) need.add("android.permission.POST_NOTIFICATIONS");
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    // ---------- أدوات صغيرة ----------
    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private static String fmt(Float v, String f) { return v == null ? "—" : String.format(Locale.US, f, v); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
