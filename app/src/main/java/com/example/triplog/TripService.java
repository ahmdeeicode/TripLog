package com.example.triplog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * خدمة أمامية تعمل فقط أثناء الرحلة (من Start إلى End).
 * تقرأ السيارة كل ثانية + تستقبل الإشارات اللحظية، وتجمعها في TripSession.
 */
public class TripService extends Service implements CarBridge.Callback {
    private static final String CH = "mytrip";
    private static final int NOTIF_ID = 1;

    /** متاح للواجهة (نفس العملية). */
    public static volatile TripService instance;

    private final CarBridge car = new CarBridge();
    private SessionStore store;
    private volatile TripSession session;
    private HandlerThread thread;
    private Handler handler;
    private long lastSave, lastNotif;

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, TripService.class));
    }

    public Live live() { return car.live; }
    public TripSession session() { return session; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "رحلتي", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, notif("جاري الاتصال بالسيارة…"));

        store = new SessionStore(this);
        session = store.loadActive();
        car.setCallback(this);

        thread = new HandlerThread("mytrip-poll");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (session == null) session = store.loadActive();
        if (session == null) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                car.connect(TripService.this);
                car.poll();
                TripSession s = session;
                long now = SystemClock.elapsedRealtime();
                if (s != null) {
                    s.advance(now, car.live);
                    s.sampleLevels(car.live);
                    if (now - lastSave > 15_000) { store.saveActive(s); lastSave = now; }
                    if (now - lastNotif > 5_000) { updateNotif(s); lastNotif = now; }
                }
            } catch (Throwable t) {
                Log.e("TripService", "tick", t);
            }
            handler.postDelayed(this, 1000);
        }
    };

    // ---------- CarBridge.Callback ----------
    @Override public void beforeChange(long now) {
        TripSession s = session;
        if (s != null) s.advance(now, car.live);
    }

    @Override public void onFuelLitres(double litres) {
        TripSession s = session;
        if (s != null) s.addFuel(litres, car.live);
    }

    // ---------- إنهاء الرحلة ----------
    /** يُستدعى من الواجهة. يعيد ملف التقرير. */
    public File endTrip() {
        TripSession s = session;
        if (s == null) return null;
        s.advance(SystemClock.elapsedRealtime(), car.live);
        s.sampleLevels(car.live);
        session = null;
        instance = null;            // لتتحول الواجهة فوراً لشاشة البداية
        car.release();
        File f = store.finish(s);
        stopForeground(true);
        stopSelf();
        return f;
    }

    private void updateNotif(TripSession s) {
        long mins = (System.currentTimeMillis() - s.startWall) / 60000;
        String text = String.format(Locale.US, "%.1f كم · %d:%02d · %s",
                s.totalDistKm(), mins / 60, mins % 60, car.live.engineOn ? "محرك" : "كهرباء");
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notif(text));
    }

    private Notification notif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("رحلتي — جارية")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        TripSession s = session;
        if (s != null && store != null) store.saveActive(s);
        if (thread != null) thread.quitSafely();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
