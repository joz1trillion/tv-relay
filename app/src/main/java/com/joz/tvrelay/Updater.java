package com.joz.tvrelay;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks GitHub for a newer release (prereleases included) and hands the APK to Android's installer. */
public final class Updater {
    public static final String ACTION_INSTALL_STATUS = "com.joz.tvrelay.INSTALL_STATUS";
    private static final String RELEASES = "https://api.github.com/repos/joz1trillion/tv-relay/releases?per_page=10";

    public static final class Release {
        public final String version;
        public final String apkUrl;
        public final long size;

        Release(String version, String apkUrl, long size) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.size = size;
        }
    }

    public interface Progress {
        void onProgress(int percent);
    }

    private Updater() {
    }

    public static String versionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** The newest release with an APK attached if it's newer than this install, otherwise null. Blocking. */
    public static Release checkForUpdate(Context context) throws IOException {
        HttpURLConnection c = open(RELEASES);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("GitHub replied " + c.getResponseCode());
            JSONArray releases = new JSONArray(readAll(c.getInputStream()));
            String current = versionName(context);
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                if (r.optBoolean("draft")) continue;
                String version = r.getString("tag_name").replaceFirst("^v", "");
                JSONArray assets = r.getJSONArray("assets");
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    if (!a.getString("name").endsWith(".apk")) continue;
                    if (compare(version, current) <= 0) return null;
                    return new Release(version, a.getString("browser_download_url"), a.optLong("size", -1));
                }
            }
            return null;
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected reply from GitHub");
        } finally {
            c.disconnect();
        }
    }

    /**
     * Streams the APK straight into an install session. Android answers with an
     * {@link #ACTION_INSTALL_STATUS} broadcast, which usually asks for the user's OK. Blocking.
     */
    public static void downloadAndInstall(Context context, Release release, Progress progress) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (release.size > 0) params.setSize(release.size);
        int id = installer.createSession(params);
        HttpURLConnection c = open(release.apkUrl);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            if (c.getResponseCode() != 200) throw new IOException("Download failed (" + c.getResponseCode() + ")");
            long total = release.size > 0 ? release.size : c.getContentLengthLong();
            try (InputStream in = c.getInputStream();
                 OutputStream out = session.openWrite("tv-relay.apk", 0, total)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int lastPercent = -1;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    if (percent != lastPercent) progress.onProgress(lastPercent = percent);
                }
                session.fsync(out);
            }
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(id);
            throw e;
        } finally {
            c.disconnect();
        }
    }

    /** Compares dotted version numbers: 0.10 is newer than 0.9. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = Integer.compare(part(x, i), part(y, i));
            if (d != 0) return d;
        }
        return 0;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i].replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "TV-Relay");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
