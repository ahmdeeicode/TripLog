package com.example.triplog;

import java.util.Properties;

/**
 * منطق اكتشاف بداية ونهاية الرحلة (بدون أي اعتماد على أندرويد).
 *  - تبدأ الرحلة عند تجاوز السرعة 3 كم/س.
 *  - تنتهي عند: البقاء على P لمدة دقيقة، أو التوقف 3 دقائق بدون حركة،
 *    أو انقطاع البيانات أكثر من دقيقتين (الشاشة نامت/أُطفئت السيارة).
 *  - الرحلات الأقصر من دقيقة أو 200 متر تُهمل.
 */
public final class TripRecorder {

    public interface Sink { void onTripFinished(Trip trip); }

    static final long END_PARK_MS = 60_000;
    static final long END_IDLE_MS = 3 * 60_000;
    static final long DATA_GAP_MS = 2 * 60_000;
    static final long MIN_TRIP_MS = 60_000;
    static final double MIN_TRIP_KM = 0.2;

    private final Sink sink;
    private Trip cur;
    private long lastMoveMs, parkSinceMs = -1, lastSampleMs;

    public TripRecorder(Sink sink) { this.sink = sink; }

    public Trip current() { return cur; }

    public void onSample(Snapshot s) {
        long now = s.timeMs;
        if (cur != null && lastSampleMs > 0 && now - lastSampleMs > DATA_GAP_MS) finish();

        float v = s.speedKmh == null ? 0f : s.speedKmh;
        boolean moving = v >= Trip.MOVING_KMH;

        if (cur == null) {
            if (moving) { cur = Trip.begin(s); lastMoveMs = now; parkSinceMs = -1; }
            lastSampleMs = now;
            return;
        }

        cur.sample(s);
        if (moving) { lastMoveMs = now; parkSinceMs = -1; }

        boolean parked = !moving && s.gear != null && s.gear == 1;
        if (parked) { if (parkSinceMs < 0) parkSinceMs = now; }
        else parkSinceMs = -1;

        lastSampleMs = now;
        if ((parkSinceMs >= 0 && now - parkSinceMs >= END_PARK_MS) || now - lastMoveMs >= END_IDLE_MS) {
            finish();
        }
    }

    /** إنهاء يدوي (مثلاً عند إيقاف الخدمة من المستخدم). */
    public void finish() {
        Trip t = cur;
        cur = null;
        parkSinceMs = -1;
        if (t != null && t.durationMs() >= MIN_TRIP_MS && t.distKm >= MIN_TRIP_KM) sink.onTripFinished(t);
    }

    // ---------- حفظ/استرجاع الحالة ----------
    public Properties saveState() {
        if (cur == null) return null;
        Properties p = cur.toProps();
        p.setProperty("rec.lastMoveMs", "" + lastMoveMs);
        p.setProperty("rec.lastSampleMs", "" + lastSampleMs);
        return p;
    }

    public void restoreState(Properties p) {
        if (p == null || p.getProperty("startMs") == null) return;
        try {
            cur = Trip.fromProps(p);
            lastMoveMs = Long.parseLong(p.getProperty("rec.lastMoveMs"));
            lastSampleMs = Long.parseLong(p.getProperty("rec.lastSampleMs"));
        } catch (RuntimeException e) {
            cur = null;
        }
    }
}
