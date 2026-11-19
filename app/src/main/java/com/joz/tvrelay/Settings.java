package com.joz.tvrelay;

import android.content.Context;
import android.content.SharedPreferences;

/** Preferences. Choices store the selected index; the longer explanations live in strings.xml (info_*). */
final class Settings {
    static final String[] FORMAT_LABELS = {"Uncompressed (PCM)", "Compressed (Opus)"};
    static final String[] QUALITY_LABELS = {"96 kbps", "128 kbps", "192 kbps", "256 kbps"};
    static final int[] QUALITY_KBPS = {96, 128, 192, 256};
    static final String[] CHANNEL_LABELS = {"Stereo", "Mono"};
    static final String[] BUFFER_LABELS = {"Auto", "10 ms", "15 ms", "20 ms", "30 ms",
            "50 ms", "80 ms", "120 ms", "200 ms"};
    static final int[] BUFFER_MS = {0, 10, 15, 20, 30, 50, 80, 120, 200};
    static final String[] OUTPUT_LABELS = {"Low latency", "Normal", "Power saving"};
    static final int OUTPUT_LOW_LATENCY = 0;
    static final int OUTPUT_NORMAL = 1;
    static final int OUTPUT_POWER_SAVING = 2;
    static final String[] CATCHUP_LABELS = {"Gentle", "Fast", "Instant"};
    static final int CATCHUP_GENTLE = 0;
    static final int CATCHUP_NORMAL = 1;
    static final int CATCHUP_AGGRESSIVE = 2;
    static final String[] KODI_LABELS = {"Off", "Automatic", "Automatic, at pause", "Manual"};
    static final int[] KODI_MODES = {Protocol.KODI_OFF, Protocol.KODI_AUTO, Protocol.KODI_AUTO_PAUSE,
            Protocol.KODI_MANUAL}; // the mode behind each label
    static final int SCREEN_AUTO = 0;
    static final int SCREEN_TV = 1;      // capture and send: the remote-friendly TV screen
    static final int SCREEN_CLIENT = 2;  // listen: the phone screen
    static final int KODI_ADJUST_MIN_MS = -500;
    static final int KODI_ADJUST_MAX_MS = 1000;
    static final int KODI_ADJUST_STEP_MS = 5;

    int format;
    int quality = 1;
    int channels;
    int buffer;
    int output;
    int catchup = CATCHUP_AGGRESSIVE;
    boolean exclusive;
    boolean sendTwice;
    boolean keepScreenOn;
    boolean pip;           // mini window; off by default since v0.10
    boolean keepTvStreaming; // phone side: Stop leaves the TV streaming, without asking
    boolean startTipShown;   // phone side: explained automatic start mode once
    // Advanced mode: unlocked by tapping the version in the update dialog 7 times.
    boolean advancedUnlocked;
    boolean advanced;
    int screenMode;          // SCREEN_AUTO, SCREEN_TV or SCREEN_CLIENT; only used with advanced on
    int kodiMode;
    int kodiAdjustMs;
    boolean kodiFlip;
    boolean kodiEnabled;      // Kodi sync tab and controls; off unless asked for, since Kodi is optional
    boolean showAdvanced;     // Settings shows the advanced section
    boolean kodiOnListen;  // also show the Kodi mode and adjustment on the Listen tab
    // phone side: Kodi's HTTP remote control login
    String kodiHost = "";  // blank = Kodi runs on the TV that's streaming
    int kodiPort = 8080;
    String kodiUser = "kodi";
    String kodiPass = "";
    String host = "";   // phone side: TV address, blank = discover
    String lastTvHost = ""; // phone side: where the TV was last found
    boolean updatePrereleases = true; // every release so far is a pre-release

    static Settings load(Context c) {
        SharedPreferences p = prefs(c);
        Settings s = new Settings();
        s.format = p.getInt("format", 0);
        s.quality = p.getInt("quality", 1);
        s.channels = p.getInt("channels", 0);
        s.buffer = p.getInt("buffer", 0);
        s.output = p.getInt("output", OUTPUT_LOW_LATENCY);
        if (s.output == 3) { // exclusive used to be an output path
            s.output = OUTPUT_LOW_LATENCY;
            s.exclusive = true;
        }
        s.exclusive = p.getInt("exclusive", s.exclusive ? 1 : 0) == 1;
        // Aggressive became the default in v2; older installs saved Normal without choosing it.
        s.catchup = p.getBoolean("v2", false) ? p.getInt("catchup", CATCHUP_AGGRESSIVE) : CATCHUP_AGGRESSIVE;
        s.sendTwice = p.getInt("sendTwice", 0) == 1;
        s.keepScreenOn = p.getInt("keepScreenOn", 0) == 1;
        s.pip = p.getBoolean("miniWindow", false); // new key: the old "pip" defaulted to on
        s.keepTvStreaming = p.getBoolean("keepTvStreaming", false);
        s.startTipShown = p.getBoolean("startTipShown", false);
        s.advancedUnlocked = p.getBoolean("advancedUnlocked", false);
        s.advanced = p.getBoolean("advanced", false);
        s.advancedUnlocked &= s.advanced; // since v1.4, turning advanced mode off relocks it
        s.screenMode = p.getInt("screenMode", SCREEN_AUTO);
        s.kodiMode = p.getInt("kodiMode", 0);
        // v1.7: Automatic now always follows; the gentler at-pause behaviour became its own mode
        // and the default, which is what Automatic did in v1.6.
        if (!p.contains("kodiModeV2") && s.kodiMode == Protocol.KODI_AUTO) s.kodiMode = Protocol.KODI_AUTO_PAUSE;
        s.kodiAdjustMs = p.getInt("kodiAdjustMs", 0);
        s.kodiFlip = p.getInt("kodiFlip", 0) == 1;
        s.kodiOnListen = p.getBoolean("kodiOnListen", false);
        // Before v1.8 the Kodi tab was always there; keep it for anyone who'd turned lip sync on.
        s.kodiEnabled = p.getBoolean("kodiEnabled", s.kodiMode != Protocol.KODI_OFF);
        s.showAdvanced = p.getBoolean("showAdvanced", false);
        s.kodiHost = p.getString("kodiHost", "");
        s.kodiPort = p.getInt("kodiPort", 8080);
        s.kodiUser = p.getString("kodiUser", "kodi");
        s.kodiPass = p.getString("kodiPass", "");
        s.host = p.getString("host", "");
        s.lastTvHost = p.getString("lastTvHost", "");
        s.updatePrereleases = p.getBoolean("updatePrereleases", true);
        return s;
    }

    void save(Context c) {
        prefs(c).edit()
                .putBoolean("v2", true)
                .putInt("format", format)
                .putInt("quality", quality)
                .putInt("channels", channels)
                .putInt("buffer", buffer)
                .putInt("output", output)
                .putInt("catchup", catchup)
                .putInt("exclusive", exclusive ? 1 : 0)
                .putInt("sendTwice", sendTwice ? 1 : 0)
                .putInt("keepScreenOn", keepScreenOn ? 1 : 0)
                .putBoolean("miniWindow", pip)
                .putBoolean("keepTvStreaming", keepTvStreaming)
                .putBoolean("startTipShown", startTipShown)
                .putBoolean("advancedUnlocked", advancedUnlocked)
                .putBoolean("advanced", advanced)
                .putInt("screenMode", screenMode)
                .putInt("kodiMode", kodiMode)
                .putBoolean("kodiModeV2", true)
                .putInt("kodiAdjustMs", kodiAdjustMs)
                .putInt("kodiFlip", kodiFlip ? 1 : 0)
                .putBoolean("kodiOnListen", kodiOnListen)
                .putBoolean("kodiEnabled", kodiEnabled)
                .putBoolean("showAdvanced", showAdvanced)
                .putString("kodiHost", kodiHost)
                .putInt("kodiPort", kodiPort)
                .putString("kodiUser", kodiUser)
                .putString("kodiPass", kodiPass)
                .putString("host", host)
                .putBoolean("updatePrereleases", updatePrereleases)
                .apply();
    }

    /**
     * Whether this device shows the TV screen. Android's own guess (TV or not) unless advanced mode
     * is on and a mode was picked, for devices it guesses wrong.
     */
    static boolean tvScreen(Context c) {
        Settings s = load(c);
        if (s.advanced && s.screenMode != SCREEN_AUTO) return s.screenMode == SCREEN_TV;
        return c.getSystemService(android.app.UiModeManager.class).getCurrentModeType()
                == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** Written on its own so the receiver doesn't overwrite settings changed on screen meanwhile. */
    static void saveLastTvHost(Context c, String host) {
        prefs(c).edit().putString("lastTvHost", host).apply();
    }

    /** Whether GitHub marks this version as a pre-release, remembered from the last check. */
    static boolean isPrerelease(Context c, String version) {
        SharedPreferences p = prefs(c);
        String key = "pre_" + version;
        // GitHub's answer wins (a release can be promoted later); until then, what the build says.
        return p.contains(key) ? p.getBoolean(key, false)
                : version.equals(Updater.versionName(c)) && BuildConfig.PRERELEASE;
    }

    static void setPrerelease(Context c, String version, boolean prerelease) {
        prefs(c).edit().putBoolean("pre_" + version, prerelease).apply();
    }

    static int kodiModePosition(int mode) {
        for (int i = 0; i < KODI_MODES.length; i++) if (KODI_MODES[i] == mode) return i;
        return 0;
    }

    StreamConfig stream() {
        return new StreamConfig(format == 1 ? Protocol.CODEC_OPUS : Protocol.CODEC_PCM,
                channels == 1 ? 1 : 2, QUALITY_KBPS[quality]);
    }

    /** Jitter buffer target in 5 ms chunks, 0 = adaptive. */
    int bufferChunks() {
        return BUFFER_MS[buffer] / 5;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
}
