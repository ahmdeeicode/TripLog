package com.example.triplog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

/** رحلة واحدة: تتجمع قيمها من القراءات المتتالية. */
public final class Trip {
    public long startMs, endMs, lastTs, movingMs;
    public float lastSpeed, maxSpeed;
    public double distKm;                 // من تكامل السرعة (أدق للرحلات القصيرة)
    public double packPosKwh, packNegKwh; // تكامل قدرة البطارية، الإشارة تختلف حسب الطراز
    public Float odoStart, odoEnd, socStart, socEnd, fuelStart, fuelEnd, tempC;
    public Integer rangeStart, rangeEnd;

    static final float MOVING_KMH = 3f;

    static Trip begin(Snapshot s) {
        Trip t = new Trip();
        t.startMs = t.endMs = t.lastTs = s.timeMs;
        t.odoStart = s.odometerKm;
        t.socStart = s.socPct;
        t.fuelStart = s.fuelPct;
        t.rangeStart = s.rangeKm;
        t.tempC = s.outsideTempC;
        t.sample(s);
        return t;
    }

    void sample(Snapshot s) {
        float v = s.speedKmh == null ? 0f : s.speedKmh;
        long dt = s.timeMs - lastTs;
        if (dt > 0 && dt <= 10_000) { // تجاهل الفجوات الطويلة حتى لا تُضخَّم المسافة
            double h = dt / 3_600_000.0;
            distKm += ((lastSpeed + v) / 2.0) * h;
            if (lastSpeed >= MOVING_KMH || v >= MOVING_KMH) movingMs += dt;
            if (s.packPowerKw != null) {
                if (s.packPowerKw >= 0) packPosKwh += s.packPowerKw * h;
                else packNegKwh += -s.packPowerKw * h;
            }
        }
        lastTs = s.timeMs;
        lastSpeed = v;
        if (v > maxSpeed) maxSpeed = v;
        if (v >= MOVING_KMH) endMs = s.timeMs;
        if (s.odometerKm != null) { odoEnd = s.odometerKm; if (odoStart == null) odoStart = s.odometerKm; }
        if (s.socPct != null)     { socEnd = s.socPct;     if (socStart == null) socStart = s.socPct; }
        if (s.fuelPct != null)    { fuelEnd = s.fuelPct;   if (fuelStart == null) fuelStart = s.fuelPct; }
        if (s.rangeKm != null)    { rangeEnd = s.rangeKm;  if (rangeStart == null) rangeStart = s.rangeKm; }
        if (tempC == null) tempC = s.outsideTempC;
    }

    public long durationMs() { return Math.max(0, endMs - startMs); }

    public double odoDistanceKm() {
        return (odoStart != null && odoEnd != null) ? odoEnd - odoStart : -1;
    }

    public double avgSpeedKmh() {
        return movingMs > 0 ? distKm / (movingMs / 3_600_000.0) : 0;
    }

    // ---------- CSV ----------
    public static final String CSV_HEADER =
            "start,end,duration_min,moving_min,distance_km,odo_distance_km,avg_kmh,max_kmh,"
          + "soc_start,soc_end,fuel_start,fuel_end,range_start,range_end,"
          + "pack_pos_kwh,pack_neg_kwh,outside_temp_c";

    public String toCsv() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        return f.format(new Date(startMs)) + "," + f.format(new Date(endMs)) + ","
                + n(durationMs() / 60000.0, 1) + "," + n(movingMs / 60000.0, 1) + ","
                + n(distKm, 2) + "," + (odoDistanceKm() >= 0 ? n(odoDistanceKm(), 1) : "") + ","
                + n(avgSpeedKmh(), 1) + "," + n(maxSpeed, 0) + ","
                + o(socStart) + "," + o(socEnd) + "," + o(fuelStart) + "," + o(fuelEnd) + ","
                + o(rangeStart) + "," + o(rangeEnd) + ","
                + n(packPosKwh, 2) + "," + n(packNegKwh, 2) + "," + o(tempC);
    }

    private static String n(double v, int d) { return String.format(Locale.US, "%." + d + "f", v); }
    private static String o(Object v) {
        if (v == null) return "";
        if (v instanceof Float) return n((Float) v, 1);
        return String.valueOf(v);
    }

    // ---------- حفظ الرحلة الجارية (لو أُغلقت الخدمة) ----------
    Properties toProps() {
        Properties p = new Properties();
        p.setProperty("startMs", "" + startMs); p.setProperty("endMs", "" + endMs);
        p.setProperty("lastTs", "" + lastTs);   p.setProperty("movingMs", "" + movingMs);
        p.setProperty("lastSpeed", "" + lastSpeed); p.setProperty("maxSpeed", "" + maxSpeed);
        p.setProperty("distKm", "" + distKm);
        p.setProperty("packPosKwh", "" + packPosKwh); p.setProperty("packNegKwh", "" + packNegKwh);
        put(p, "odoStart", odoStart); put(p, "odoEnd", odoEnd);
        put(p, "socStart", socStart); put(p, "socEnd", socEnd);
        put(p, "fuelStart", fuelStart); put(p, "fuelEnd", fuelEnd);
        put(p, "tempC", tempC);
        put(p, "rangeStart", rangeStart); put(p, "rangeEnd", rangeEnd);
        return p;
    }

    static Trip fromProps(Properties p) {
        Trip t = new Trip();
        t.startMs = Long.parseLong(p.getProperty("startMs"));
        t.endMs = Long.parseLong(p.getProperty("endMs"));
        t.lastTs = Long.parseLong(p.getProperty("lastTs"));
        t.movingMs = Long.parseLong(p.getProperty("movingMs"));
        t.lastSpeed = Float.parseFloat(p.getProperty("lastSpeed"));
        t.maxSpeed = Float.parseFloat(p.getProperty("maxSpeed"));
        t.distKm = Double.parseDouble(p.getProperty("distKm"));
        t.packPosKwh = Double.parseDouble(p.getProperty("packPosKwh"));
        t.packNegKwh = Double.parseDouble(p.getProperty("packNegKwh"));
        t.odoStart = f(p, "odoStart"); t.odoEnd = f(p, "odoEnd");
        t.socStart = f(p, "socStart"); t.socEnd = f(p, "socEnd");
        t.fuelStart = f(p, "fuelStart"); t.fuelEnd = f(p, "fuelEnd");
        t.tempC = f(p, "tempC");
        t.rangeStart = i(p, "rangeStart"); t.rangeEnd = i(p, "rangeEnd");
        return t;
    }

    private static void put(Properties p, String k, Object v) { if (v != null) p.setProperty(k, v.toString()); }
    private static Float f(Properties p, String k) { String s = p.getProperty(k); return s == null ? null : Float.valueOf(s); }
    private static Integer i(Properties p, String k) { String s = p.getProperty(k); return s == null ? null : Integer.valueOf(s); }
}
