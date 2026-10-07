package com.example.triplog;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** حفظ الرحلة الجارية وسجل الرحلات المنتهية (ملف JSON لكل رحلة). */
public final class SessionStore {
    private final File active, historyDir;

    public SessionStore(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        historyDir = new File(base, "trips");
        historyDir.mkdirs();
        active = new File(ctx.getFilesDir(), "active_trip.json");
    }

    public File historyDir() { return historyDir; }

    public synchronized TripSession loadActive() {
        TripSession s = read(active);
        return (s != null && s.active) ? s : null;
    }

    public synchronized void saveActive(TripSession s) {
        if (s == null) { active.delete(); return; }
        try { write(active, s.toJson().toString()); } catch (Exception e) { Log.e("Store", "saveActive", e); }
    }

    /** أقل مسافة لحفظ الرحلة في السجل (الضغط على Start ثم End بدون قيادة). */
    public static final double MIN_SAVE_KM = 0.1;

    /** ينهي الرحلة ويحفظها في السجل، ويعيد الملف. يعيد null إذا كانت أقصر من MIN_SAVE_KM فلا تُحفظ. */
    public synchronized File finish(TripSession s) {
        s.active = false;
        s.endWall = System.currentTimeMillis();
        if (s.totalDistKm() < MIN_SAVE_KM) { active.delete(); return null; }
        File f = new File(historyDir, "trip_" + s.startWall + ".json");
        try { write(f, s.toJson().toString(2)); } catch (Exception e) { Log.e("Store", "finish", e); }
        active.delete();
        return f;
    }

    /** حذف رحلة من السجل (فقط ملفات مجلد السجل). */
    public synchronized void delete(File f) {
        if (f != null && historyDir.equals(f.getParentFile())) f.delete();
    }

    /** الأحدث أولاً. */
    public synchronized List<File> history() {
        File[] fs = historyDir.listFiles((d, n) -> n.startsWith("trip_") && n.endsWith(".json"));
        List<File> out = new ArrayList<>(fs == null ? new ArrayList<File>() : Arrays.asList(fs));
        out.sort((a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    public static TripSession read(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            return TripSession.fromJson(new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.e("Store", "read " + f, e);
            return null;
        }
    }

    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }
}
