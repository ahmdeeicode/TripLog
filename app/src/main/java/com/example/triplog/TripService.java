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
import android.util.Log;

import java.util.Locale;

/** خدمة أمامية تقرأ بيانات السيارة كل ثانية وتمررها لـ TripRecorder. */
public class TripService extends Service {
    private static final String CH = "trip";
    private static final int NOTIF_ID = 1;

    // حالة حيّة تعرضها الواجهة
    public static volatile Snapshot lastSnap;
    public static volatile Trip liveTrip;
    public static volatile String source = "—";
    public static volatile boolean running;

    private HandlerThread thread;
    private Handler handler;
    private CarBridge car;
    private TripRecorder recorder;
    private TripStore store;
    private long lastSave, lastNotif;

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, TripService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "سجل الرحلات", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, notif("بانتظار بداية الرحلة"));

        store = new TripStore(this);
        car = new CarBridge();
        recorder = new TripRecorder(trip -> {
            store.append(trip);
            Log.i("TripService", "رحلة محفوظة: " + trip.toCsv());
        });
        recorder.restoreState(store.loadState());

        thread = new HandlerThread("trip-poll");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
        running = true;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                if (!car.connected()) car.connect(TripService.this);
                Snapshot s = car.read();
                lastSnap = s;
                source = car.source();
                recorder.onSample(s);
                liveTrip = recorder.current();

                long now = s.timeMs;
                if (now - lastSave > 30_000 || liveTrip == null) {
                    store.saveState(recorder.saveState());
                    lastSave = now;
                }
                if (now - lastNotif > 5_000) {
                    updateNotif();
                    lastNotif = now;
                }
            } catch (Throwable t) {
                Log.e("TripService", "tick", t);
            }
            handler.postDelayed(this, 1000);
        }
    };

    private void updateNotif() {
        Trip t = liveTrip;
        String text = t == null ? "بانتظار بداية الرحلة"
                : String.format(Locale.US, "رحلة جارية: %.1f كم · %d دقيقة", t.distKm, t.durationMs() / 60000);
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notif(text));
    }

    private Notification notif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("سجل الرحلات")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public static final String ACTION_END_TRIP = "com.example.triplog.END_TRIP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_END_TRIP.equals(intent.getAction()) && handler != null) {
            handler.post(() -> {
                recorder.finish();               // يحفظ الرحلة إذا تجاوزت الحد الأدنى
                liveTrip = null;
                store.saveState(null);
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (recorder != null) store.saveState(recorder.saveState());
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
