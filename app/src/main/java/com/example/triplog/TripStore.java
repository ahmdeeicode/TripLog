package com.example.triplog;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** يكتب الرحلات المنتهية في trips.csv ويحفظ الرحلة الجارية مؤقتاً. */
public final class TripStore {
    private final File csv, state;

    public TripStore(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        csv = new File(ext != null ? ext : ctx.getFilesDir(), "trips.csv");
        state = new File(ctx.getFilesDir(), "live_trip.properties");
    }

    public File csvFile() { return csv; }

    public synchronized void append(Trip t) {
        boolean header = !csv.exists() || csv.length() == 0;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(csv, true), StandardCharsets.UTF_8)) {
            if (header) w.write(Trip.CSV_HEADER + "\n");
            w.write(t.toCsv() + "\n");
        } catch (Exception e) {
            Log.e("TripStore", "append failed", e);
        }
    }

    /** كل الصفوف بدون العنوان، الأحدث أولاً. */
    public synchronized List<String[]> readAll() {
        List<String[]> rows = new ArrayList<>();
        if (!csv.exists()) return rows;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(csv), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; }
                if (!line.trim().isEmpty()) rows.add(0, line.split(",", -1));
            }
        } catch (Exception e) {
            Log.e("TripStore", "read failed", e);
        }
        return rows;
    }

    public synchronized void clear() { csv.delete(); }

    public void saveState(Properties p) {
        if (p == null) { state.delete(); return; }
        try (FileOutputStream o = new FileOutputStream(state)) {
            p.store(o, null);
        } catch (Exception e) {
            Log.e("TripStore", "saveState failed", e);
        }
    }

    public Properties loadState() {
        if (!state.exists()) return null;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(state)) {
            p.load(in);
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
