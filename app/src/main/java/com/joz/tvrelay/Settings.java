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
    static final String[] CATCHUP_LABELS = {"Gentle", "Normal", "Aggressive"};
    static final int CATCHUP_GENTLE = 0;
    static final int CATCHUP_NORMAL = 1;
    static final int CATCHUP_AGGRESSIVE = 2;
    static final String[] KODI_LABELS = {"Off", "Automatic", "Manual"};
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
    boolean pip = true;
    int kodiMode;
    int kodiAdjustMs;
    boolean kodiFlip;
    // TV side: how to reach Kodi on the same TV
    int kodiPort = 8080;
    String kodiUser = "kodi";
    String kodiPass = "";
    String host = "";   // phone side: TV address, blank = discover
    boolean kodiUnlocked;  // Kodi tab is hidden until the graph is tapped 7 times

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
        s.pip = p.getInt("pip", 0) == 0; // stored as 0 = on
        s.kodiMode = p.getInt("kodiMode", 0);
        s.kodiAdjustMs = p.getInt("kodiAdjustMs", 0);
        s.kodiFlip = p.getInt("kodiFlip", 0) == 1;
        s.kodiPort = p.getInt("kodiPort", 8080);
        s.kodiUser = p.getString("kodiUser", "kodi");
        s.kodiPass = p.getString("kodiPass", "");
        s.host = p.getString("host", "");
        s.kodiUnlocked = p.getBoolean("kodiUnlocked", false);
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
                .putInt("pip", pip ? 0 : 1)
                .putInt("kodiMode", kodiMode)
                .putInt("kodiAdjustMs", kodiAdjustMs)
                .putInt("kodiFlip", kodiFlip ? 1 : 0)
                .putInt("kodiPort", kodiPort)
                .putString("kodiUser", kodiUser)
                .putString("kodiPass", kodiPass)
                .putString("host", host)
                .putBoolean("kodiUnlocked", kodiUnlocked)
                .apply();
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
