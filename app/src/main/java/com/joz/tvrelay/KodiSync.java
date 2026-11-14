package com.joz.tvrelay;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

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
 * Runs on the phone while listening. Tells Kodi on the TV, over Kodi's HTTP remote control, to play
 * its audio early by the relay's delay via JSON-RPC Player.SetAudioDelay (Kodi 21+), so the sound on
 * the phone lines up with the picture. The receiver feeds in mode, adjustment and measured delay.
 */
final class KodiSync {
    private static final String TAG = "TvRelayKodi";
    private static final double AUTO_HYSTERESIS = 0.049; // Automatic ignores wobbles smaller than this
    private static final double STEP = 0.025;   // Kodi only accepts multiples of 25 ms

    static volatile String status = "Kodi sync off";
    static volatile int state = Protocol.KODI_STATE_OFF;
    static volatile int appliedMs;
    static volatile int pendingMs = Integer.MIN_VALUE; // what the next sync would set, if different
    private static volatile boolean syncRequested;

    private final Context context;
    private volatile String host;  // the TV's IP, once the receiver knows it
    private String kodiHost;       // set in the login when Kodi isn't on that TV
    private int port;
    private String auth;
    private volatile int mode;
    private volatile int adjustMs;
    private volatile int latencyMs;
    private volatile boolean flip;
    private volatile long lastUpdateMs;
    private volatile boolean running;
    private Thread thread;

    // sync thread only
    private double applied = Double.NaN;
    private int appliedAdjust;
    private boolean appliedFlip;
    private int appliedMode;
    private String lastItem;

    KodiSync(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Re-read each tick, so editing the Kodi login applies without restarting. */
    private void loadLogin() {
        Settings s = Settings.load(context);
        kodiHost = s.kodiHost.isEmpty() ? null : s.kodiHost;
        port = s.kodiPort;
        auth = s.kodiUser.isEmpty() ? null : "Basic " + Base64.encodeToString(
                (s.kodiUser + ":" + s.kodiPass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    /** The phone's Sync now button: apply the current delay on the next tick, mid-video or not. */
    static void requestSync() {
        syncRequested = true;
    }

    void setHost(String host) {
        this.host = host;
    }

    void update(int mode, int adjustMs, int latencyMs, boolean flip) {
        this.mode = mode;
        this.adjustMs = adjustMs;
        this.latencyMs = latencyMs;
        this.flip = flip;
        lastUpdateMs = SystemClock.elapsedRealtime();
    }

    void start() {
        running = true;
        thread = new Thread(this::loop, "relay-kodi");
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                tick();
            } catch (AuthException e) {
                setState(Protocol.KODI_STATE_AUTH, "Kodi: wrong username/password");
            } catch (Exception e) {
                Log.d(TAG, "kodi unreachable", e);
                lastItem = null;
                setState(Protocol.KODI_STATE_UNREACHABLE, "Kodi: not reachable");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        resetIfChanged(); // leave Kodi as we found it
        setState(Protocol.KODI_STATE_OFF, "Kodi sync off");
    }

    private void tick() throws Exception {
        loadLogin();
        boolean phoneActive = SystemClock.elapsedRealtime() - lastUpdateMs < 5000;
        int m = phoneActive ? mode : Protocol.KODI_OFF;
        if (target() == null) {
            setState(Protocol.KODI_STATE_OFF, "Waiting for the TV");
            return;
        }
        if (m == Protocol.KODI_OFF) {
            resetIfChanged();
            if (phoneActive) {
                kodiVersion(); // still check the login, so the phone can show it's connected
                setState(Protocol.KODI_STATE_READY, "Kodi: connected, lip sync off");
            } else {
                setState(Protocol.KODI_STATE_OFF, "Kodi sync off");
            }
            return;
        }

        int player = videoPlayer();
        if (player < 0) {
            lastItem = null;
            applied = Double.NaN;
            pendingMs = Integer.MIN_VALUE;
            setState(Protocol.KODI_STATE_IDLE, "Kodi: connected, nothing playing");
            return;
        }

        String item = currentItem(player);
        boolean newItem = !item.equals(lastItem); // Kodi resets the delay for each new video
        boolean auto = m == Protocol.KODI_AUTO || m == Protocol.KODI_AUTO_PAUSE;
        int delayMs = (auto ? latencyMs : 0) + adjustMs;
        // Relay audio arrives late, so Kodi must play audio early: a negative Kodi audio delay.
        double target = Math.round(delayMs / 1000.0 / STEP) * STEP * (flip ? 1 : -1);
        target = Math.max(-10, Math.min(10, target));
        // Every change makes Kodi catch the picture up (it skips frames for a moment). Automatic
        // follows the measured delay once it moves by ~50 ms; at-pause mode only at the start of a
        // video, while paused, or on Sync now. The user's own adjustments apply straight away.
        double threshold = m == Protocol.KODI_AUTO ? AUTO_HYSTERESIS : STEP / 2;
        boolean differs = Double.isNaN(applied) || Math.abs(target - applied) >= threshold;
        boolean userChange = adjustMs != appliedAdjust || flip != appliedFlip || m != appliedMode;
        boolean syncNow = syncRequested;
        syncRequested = false;
        if (newItem || Double.isNaN(applied) || (differs && (userChange || syncNow || m == Protocol.KODI_AUTO
                || (m == Protocol.KODI_AUTO_PAUSE && isPaused(player))))) {
            setDelay(player, target);
            applied = target;
        }
        appliedAdjust = adjustMs;
        appliedFlip = flip;
        appliedMode = m;
        lastItem = item;
        appliedMs = (int) Math.round(applied * 1000);
        int targetMs = (int) Math.round(target * 1000);
        pendingMs = m == Protocol.KODI_AUTO_PAUSE && targetMs != appliedMs ? targetMs : Integer.MIN_VALUE;
        setState(Protocol.KODI_STATE_APPLIED, String.format(java.util.Locale.US,
                "Kodi: audio delay %+.3f s (%s)", applied, auto ? "auto" : "manual"));
    }

    private void resetIfChanged() {
        if (Double.isNaN(applied) || applied == 0) return;
        try {
            int player = videoPlayer();
            if (player >= 0) setDelay(player, 0);
        } catch (Exception ignored) {
        }
        applied = Double.NaN;
        appliedMs = 0;
    }

    private static void setState(int s, String text) {
        state = s;
        status = text;
    }

    /** Checks the saved login against Kodi right away, for the Test button. Blocking. */
    static String testLogin(Context context, String tvHost) {
        KodiSync k = new KodiSync(context);
        k.loadLogin();
        k.host = tvHost;
        String host = k.target();
        try {
            int[] v = k.kodiVersion();
            String name = "Kodi " + v[0] + "." + v[1];
            return v[0] >= 21 ? "Connected to " + name + "."
                    : "Connected to " + name + ", but lip sync needs Kodi 21 or newer. Update Kodi from the Play Store.";
        } catch (AuthException e) {
            return "Kodi says the username or password is wrong.";
        } catch (Exception e) {
            return "Couldn't reach Kodi at " + host + ":" + k.port + ". Check Kodi is open on the TV (it can stay "
                    + "in the background) and Settings > Services > Control > Allow remote control via HTTP is on.";
        }
    }

    /** {major, minor}; also proves the login works. */
    private String target() {
        return kodiHost != null ? kodiHost : host;
    }

    private int[] kodiVersion() throws Exception {
        JSONObject params = new JSONObject().put("properties", new JSONArray().put("version"));
        JSONObject v = rpc("Application.GetProperties", params).getJSONObject("result").getJSONObject("version");
        return new int[]{v.optInt("major"), v.optInt("minor")};
    }

    private int videoPlayer() throws Exception {
        JSONArray players = rpc("Player.GetActivePlayers", new JSONObject()).getJSONArray("result");
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.getJSONObject(i);
            if ("video".equals(p.optString("type"))) return p.getInt("playerid");
        }
        return -1;
    }

    private boolean isPaused(int player) throws Exception {
        JSONObject params = new JSONObject().put("playerid", player)
                .put("properties", new JSONArray().put("speed"));
        return rpc("Player.GetProperties", params).getJSONObject("result").optInt("speed", 1) == 0;
    }

    private String currentItem(int player) throws Exception {
        JSONObject params = new JSONObject().put("playerid", player)
                .put("properties", new JSONArray().put("file"));
        JSONObject item = rpc("Player.GetItem", params).getJSONObject("result").getJSONObject("item");
        return item.optString("file", item.optString("label", ""));
    }

    private void setDelay(int player, double seconds) throws Exception {
        rpc("Player.SetAudioDelay", new JSONObject().put("playerid", player).put("offset", seconds));
        Log.i(TAG, "audio delay -> " + seconds);
    }

    private JSONObject rpc(String method, JSONObject params) throws Exception {
        JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("method", method).put("params", params);
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + target() + ":" + port + "/jsonrpc").openConnection();
        try {
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (auth != null) c.setRequestProperty("Authorization", auth);
            try (OutputStream os = c.getOutputStream()) {
                os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() == 401) throw new AuthException();
            JSONObject res = new JSONObject(read(c.getInputStream()));
            if (res.has("error")) throw new IOException(method + ": " + res.getJSONObject("error").optString("message"));
            return res;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = is.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static final class AuthException extends Exception {}
}
