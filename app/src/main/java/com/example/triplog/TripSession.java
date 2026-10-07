package com.example.triplog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * رحلة واحدة من Start إلى End.
 * كل لحظة تُنسب لخانة: [النمط] × [كهرباء EV أو محرك Engine].
 */
public final class TripSession {
    public static final int SPORT = 0, ECO = 1, NORMAL = 2, OTHERS = 3;
    public static final String[] MODE_NAMES = {"Sport", "Eco", "Normal", "Others"};
    public static final int EV = 0, ENG = 1;

    public static final class Cell {
        public long durMs;
        public double distKm, kwhOut, kwhIn, fuelL, refuelPct;
        public float socFirst = Float.NaN, socLast = Float.NaN;
        public float fuelFirst = Float.NaN, fuelLast = Float.NaN;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("dur", durMs).put("dist", distKm).put("out", kwhOut).put("in", kwhIn)
             .put("fuel", fuelL).put("refuel", refuelPct);
            putF(o, "socF", socFirst); putF(o, "socL", socLast);
            putF(o, "fuelF", fuelFirst); putF(o, "fuelL", fuelLast);
            return o;
        }

        static Cell fromJson(JSONObject o) {
            Cell c = new Cell();
            c.durMs = o.optLong("dur");
            c.distKm = o.optDouble("dist", 0);
            c.kwhOut = o.optDouble("out", 0);
            c.kwhIn = o.optDouble("in", 0);
            c.fuelL = o.optDouble("fuel", 0);
            c.refuelPct = o.optDouble("refuel", 0);
            c.socFirst = getF(o, "socF"); c.socLast = getF(o, "socL");
            c.fuelFirst = getF(o, "fuelF"); c.fuelLast = getF(o, "fuelL");
            return c;
        }
    }

    public long startWall, endWall;
    public boolean active;
    /** true = القيمة السالبة لقدرة البطارية تعني شحناً (الافتراضي في DisplayMirror). */
    public boolean negIsIn = true;
    public float socStart = Float.NaN, socEnd = Float.NaN, fuelStart = Float.NaN, fuelEnd = Float.NaN;
    public float odoStart = Float.NaN, odoEnd = Float.NaN;
    public final Cell[][] cells = new Cell[4][2];

    private long lastAdvance = -1;   // elapsedRealtime، لا يُحفظ
    private float minFuelPct = Float.NaN;

    public TripSession() {
        for (int m = 0; m < 4; m++) for (int k = 0; k < 2; k++) cells[m][k] = new Cell();
    }

    public static int bucket(Integer mode) {
        if (mode == null) return OTHERS;
        switch (mode) {
            case 0: return ECO;
            case 1: return NORMAL;
            case 2: return SPORT;
            default: return OTHERS;
        }
    }

    private Cell cur(Live l) { return cells[bucket(l.driveMode)][l.engineOn ? ENG : EV]; }

    /** يُستدعى قبل أي تغيّر في القيم الحية: ينسب الفترة الماضية للخانة الحالية. */
    public synchronized void advance(long now, Live l) {
        if (!active) { lastAdvance = now; return; }
        if (lastAdvance > 0) {
            long dt = now - lastAdvance;
            if (dt > 0 && dt <= 5000) { // الفجوات الطويلة (السيارة مطفأة) لا تُحسب
                Cell c = cur(l);
                c.durMs += dt;
                double h = dt / 3_600_000.0;
                Float v = l.speedKmh;
                if (v != null) c.distKm += v * h;
                Float p = l.packKw;
                if (p != null) {
                    float out = negIsIn ? p : -p;   // موجب = صرف من البطارية
                    if (out >= 0) c.kwhOut += out * h;
                    else c.kwhIn += -out * h;
                }
            }
        }
        lastAdvance = now;
    }

    public synchronized void addFuel(double litres, Live l) {
        if (active && litres > 0) cur(l).fuelL += litres;
    }

    /** يُستدعى كل ثانية لتسجيل البطارية والوقود والعداد. */
    public synchronized void sampleLevels(Live l) {
        if (!active) return;
        Cell c = cur(l);
        Float soc = l.socPct;
        if (soc != null) {
            if (Float.isNaN(c.socFirst)) c.socFirst = soc;
            c.socLast = soc;
            if (Float.isNaN(socStart)) socStart = soc;
            socEnd = soc;
        }
        Float fuel = l.fuelPct;
        if (fuel != null) {
            if (Float.isNaN(c.fuelFirst)) c.fuelFirst = fuel;
            c.fuelLast = fuel;
            if (Float.isNaN(fuelStart)) fuelStart = fuel;
            fuelEnd = fuel;
            // اكتشاف التعبئة: ارتفاع 8% أو أكثر عن أدنى قيمة
            if (Float.isNaN(minFuelPct) || fuel < minFuelPct) minFuelPct = fuel;
            else if (fuel - minFuelPct >= 8f) { c.refuelPct += fuel - minFuelPct; minFuelPct = fuel; }
        }
        Float odo = l.odometerKm;
        if (odo != null) {
            if (Float.isNaN(odoStart)) odoStart = odo;
            odoEnd = odo;
        }
    }

    // ---------- مجاميع ----------
    public synchronized Cell total(int kind) {
        Cell t = new Cell();
        for (int m = 0; m < 4; m++) {
            Cell c = cells[m][kind];
            t.durMs += c.durMs; t.distKm += c.distKm; t.kwhOut += c.kwhOut; t.kwhIn += c.kwhIn;
            t.fuelL += c.fuelL; t.refuelPct += c.refuelPct;
        }
        t.socFirst = socStart; t.socLast = socEnd; t.fuelFirst = fuelStart; t.fuelLast = fuelEnd;
        return t;
    }

    public synchronized double totalDistKm() { return total(EV).distKm + total(ENG).distKm; }
    public synchronized double totalFuelL() { return total(EV).fuelL + total(ENG).fuelL; }
    public synchronized double totalKwhOut() { return total(EV).kwhOut + total(ENG).kwhOut; }
    public synchronized double totalKwhIn() { return total(EV).kwhIn + total(ENG).kwhIn; }
    public synchronized long totalDurMs() { return total(EV).durMs + total(ENG).durMs; }

    /** نسبة المسافة على الكهرباء. */
    public synchronized double evShare() {
        double all = totalDistKm();
        return all > 0.01 ? total(EV).distKm / all * 100.0 : 0;
    }

    // ---------- JSON ----------
    public synchronized JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", 1).put("start", startWall).put("end", endWall).put("active", active).put("negIsIn", negIsIn);
        putF(o, "socStart", socStart); putF(o, "socEnd", socEnd);
        putF(o, "fuelStart", fuelStart); putF(o, "fuelEnd", fuelEnd);
        putF(o, "odoStart", odoStart); putF(o, "odoEnd", odoEnd);
        putF(o, "minFuel", minFuelPct);
        JSONArray a = new JSONArray();
        for (int m = 0; m < 4; m++) {
            JSONArray row = new JSONArray();
            row.put(cells[m][EV].toJson());
            row.put(cells[m][ENG].toJson());
            a.put(row);
        }
        o.put("cells", a);
        return o;
    }

    public static TripSession fromJson(JSONObject o) throws JSONException {
        TripSession s = new TripSession();
        s.startWall = o.getLong("start");
        s.endWall = o.optLong("end");
        s.active = o.optBoolean("active");
        s.negIsIn = o.optBoolean("negIsIn", true);
        s.socStart = getF(o, "socStart"); s.socEnd = getF(o, "socEnd");
        s.fuelStart = getF(o, "fuelStart"); s.fuelEnd = getF(o, "fuelEnd");
        s.odoStart = getF(o, "odoStart"); s.odoEnd = getF(o, "odoEnd");
        s.minFuelPct = getF(o, "minFuel");
        JSONArray a = o.getJSONArray("cells");
        for (int m = 0; m < 4 && m < a.length(); m++) {
            JSONArray row = a.getJSONArray(m);
            s.cells[m][EV] = Cell.fromJson(row.getJSONObject(0));
            s.cells[m][ENG] = Cell.fromJson(row.getJSONObject(1));
        }
        return s;
    }

    static void putF(JSONObject o, String k, float v) throws JSONException {
        if (!Float.isNaN(v)) o.put(k, (double) v);
    }

    static float getF(JSONObject o, String k) {
        return o.has(k) ? (float) o.optDouble(k, Double.NaN) : Float.NaN;
    }
}
