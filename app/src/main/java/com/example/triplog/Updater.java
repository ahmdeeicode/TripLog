package com.example.triplog;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * التحديث من داخل التطبيق:
 *  1) يسأل GitHub عن آخر Release في مستودع TripLog.
 *  2) يقارن رقم البناء برقم النسخة المثبتة.
 *  3) يحمّل ملف APK مباشرة إلى مثبّت أندرويد (PackageInstaller)، فيظهر للمستخدم زر "تحديث".
 */
final class Updater {
    static final String OWNER = "ahmdeeicode";
    static final String REPO = "TripLog";
    /** versionCode = 100 + رقم البناء في GitHub Actions (انظر app/build.gradle). */
    static final int CODE_BASE = 100;

    static final class Info {
        long code;
        String tag, notes, apkUrl;
        long size;
    }

    interface Progress { void on(long done, long total); }

    private Updater() { }

    /** آخر نسخة منشورة، أو null إن لم توجد. يعمل على خيط خلفي. */
    static Info fetchLatest() throws Exception {
        HttpURLConnection c = open("https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int rc = c.getResponseCode();
        if (rc == 404) return null;
        if (rc != 200) throw new IOException("GitHub HTTP " + rc);
        JSONObject o = new JSONObject(readAll(c.getInputStream()));
        Info i = new Info();
        i.tag = o.optString("tag_name");
        i.notes = o.optString("body", "");
        String digits = i.tag.replaceAll("\\D+", "");
        if (digits.isEmpty()) return null;
        i.code = CODE_BASE + Long.parseLong(digits);
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.getJSONObject(k);
                if (a.optString("name").endsWith(".apk")) {
                    i.apkUrl = a.optString("browser_download_url");
                    i.size = a.optLong("size");
                }
            }
        }
        return i.apkUrl == null ? null : i;
    }

    static long installedCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** يحمّل النسخة ويسلّمها لمثبّت أندرويد. يعمل على خيط خلفي. */
    static void downloadAndInstall(Context ctx, Info info, Progress progress) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            HttpURLConnection c = open(info.apkUrl);
            int rc = c.getResponseCode();
            if (rc != 200) throw new IOException("Download HTTP " + rc);
            long total = c.getContentLengthLong();
            if (total <= 0) total = info.size;
            try (InputStream in = c.getInputStream();
                 OutputStream out = session.openWrite("MyTrip.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    progress.on(done, total);
                }
                session.fsync(out);
            }
            Intent result = new Intent(ctx, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, result, flags);
            session.commit(pending.getIntentSender());
            session.close();
        } catch (Exception e) {
            session.abandon();
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "MyTrip-Updater");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
