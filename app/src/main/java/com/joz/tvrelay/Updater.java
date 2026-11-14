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
import java.util.ArrayList;
import java.util.List;

/** Lists the GitHub releases that have an APK and hands the chosen one to Android's installer. */
public final class Updater {
    public static final String ACTION_INSTALL_STATUS = "com.joz.tvrelay.INSTALL_STATUS";
    private static final String RELEASES = "https://api.github.com/repos/joz1trillion/tv-relay/releases?per_page=10";

    public static final class Release {
        public final String version;
        public final boolean prerelease;
        public final String date;  // yyyy-mm-dd
        public final String apkUrl;
        public final long size;

        Release(String version, boolean prerelease, String date, String apkUrl, long size) {
            this.version = version;
            this.prerelease = prerelease;
            this.date = date;
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

    public static int versionCode(Context context) {
        try {
            return (int) context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Every published release with an APK attached, newest first. Blocking. */
    public static List<Release> releases() throws IOException {
        HttpURLConnection c = open(RELEASES);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("GitHub replied " + c.getResponseCode());
            JSONArray releases = new JSONArray(readAll(c.getInputStream()));
            List<Release> list = new ArrayList<>();
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                if (r.optBoolean("draft")) continue;
                JSONArray assets = r.getJSONArray("assets");
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    if (!a.getString("name").endsWith(".apk")) continue;
                    String date = r.optString("published_at", "");
                    list.add(new Release(r.getString("tag_name").replaceFirst("^v", ""),
                            r.optBoolean("prerelease"), date.length() >= 10 ? date.substring(0, 10) : date,
                            a.getString("browser_download_url"), a.optLong("size", -1)));
                    break;
                }
            }
            list.sort((x, y) -> compare(y.version, x.version));
            return list;
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected reply from GitHub");
        } finally {
            c.disconnect();
        }
    }

    /** The newest release that's newer than this install, or null. */
    public static Release newest(Context context, List<Release> releases, boolean includePrereleases) {
        String current = versionName(context);
        for (Release r : releases) {
            if (r.prerelease && !includePrereleases) continue;
            return compare(r.version, current) > 0 ? r : null;
        }
        return null;
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
        if (compare(release.version, versionName(context)) < 0) requestDowngrade(params);
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

    /**
     * Android refuses older versions unless the installer asks for a downgrade, a flag apps can't
     * normally set. Some devices allow it for debuggable apps like this one, so try it via reflection.
     */
    private static void requestDowngrade(PackageInstaller.SessionParams params) {
        try {
            java.lang.reflect.Field flags = PackageInstaller.SessionParams.class.getDeclaredField("installFlags");
            flags.setAccessible(true);
            flags.setInt(params, flags.getInt(params) | 0x00000080); // INSTALL_REQUEST_DOWNGRADE
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the install then fails with a downgrade error, which the caller explains
        }
    }

    /** Compares version numbers: 0.10 is newer than 0.9, and 0.4-1 is newer than 0.4. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
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
