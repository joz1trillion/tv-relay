package com.joz.tvrelay;

/**
 * Wire format (UDP, big-endian header):
 *   audio:     [int AUDIO][int seq][byte codec][byte channels][byte resend][byte 0][payload]
 *              seq = index of the payload's first 5 ms chunk, so PCM and Opus share one timeline.
 *              PCM payload: one 5 ms chunk of PCM16 LE. Opus payload: one Opus packet.
 *   subscribe: [int SUBSCRIBE][byte codec][byte channels][short kbps][long pingNanos][byte flags]
 *              [7 bytes 0]
 *              sent by the phone 1x/s; doubles as keepalive and picks the stream format. The zero
 *              bytes once carried Kodi sync settings: TVs before v0.7 drove Kodi, and zero keeps it off.
 *   pong:      [int PONG][long pingNanos][int 0]
 *              TV echoes the ping so the phone can measure RTT.
 *
 * Link (UDP LINK_PORT), answered by the TV whenever TV Relay has been opened, streaming or not:
 *   query:     [int QUERY][long pingNanos][byte command][3 bytes 0]   command: status, start or stop
 *   status:    [int STATUS][long pingNanos][byte streaming][byte kodiFound][short versionCode]
 *              [byte screenOpen][byte canPopUp][2 bytes 0]
 *              screenOpen: TV Relay is on the TV's screen, so start can work.
 *              canPopUp: automatic start mode, TV Relay may open its screen over other apps, so start always works.
 *              Then, from v1.3: [byte nameLength][UTF-8 TV name], so a phone can tell TVs apart.
 */
final class Protocol {
    static final int PORT = 48100;
    static final int LINK_PORT = 48101;
    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;
    static final int FRAMES_PER_PACKET = 240; // 5 ms
    static final int CHUNK_US = 5000;
    static final int SAMPLES_PER_PACKET = FRAMES_PER_PACKET * CHANNELS;
    static final int PAYLOAD_BYTES = SAMPLES_PER_PACKET * 2;
    static final int HEADER_BYTES = 12;
    static final int MAX_PACKET = 1500;

    static final int MAGIC_AUDIO = 0x54524132;     // "TRA2"
    static final int MAGIC_SUBSCRIBE = 0x54525332; // "TRS2"
    static final int MAGIC_PONG = 0x54525032;      // "TRP2"
    static final int SUBSCRIBE_BYTES = 24;
    static final int MAGIC_QUERY = 0x54525132;     // "TRQ2"
    static final int MAGIC_STATUS = 0x54525432;    // "TRT2"
    static final int QUERY_BYTES = 16;
    static final int STATUS_BYTES = 20;
    static final byte QUERY_STATUS = 0;
    static final byte QUERY_START = 1;
    static final byte QUERY_STOP = 2;
    static final int FLAG_SEND_TWICE = 1;
    // Wi-Fi loss comes in bursts, so the copy goes out 10 ms after the original, not right behind it.
    static final int RESEND_DELAY_CHUNKS = 2;
    static final int PONG_BYTES = 16;

    static final int KODI_OFF = 0;
    static final int KODI_AUTO = 1;
    static final int KODI_MANUAL = 2;
    static final int KODI_AUTO_PAUSE = 3;  // automatic, but only at a video's start, on pause, or Sync now
    static final int KODI_STATE_OFF = 0;
    static final int KODI_STATE_IDLE = 1;        // reachable, nothing playing
    static final int KODI_STATE_APPLIED = 2;
    static final int KODI_STATE_UNREACHABLE = 3;
    static final int KODI_STATE_AUTH = 4;
    static final int KODI_STATE_READY = 5;       // login works, lip sync turned off

    static final int CODEC_PCM = 0;
    static final int CODEC_OPUS = 1;
    static final float OPUS_LATENCY_MS = 26.5f; // 20 ms frame + 6.5 ms encoder lookahead

    static final long CLIENT_TIMEOUT_MS = 5000;

    // DSCP EF: routers/APs that honour WMM put these packets in the voice queue.
    static final int TRAFFIC_CLASS_VOICE = 0xB8;

    private Protocol() {}
}
