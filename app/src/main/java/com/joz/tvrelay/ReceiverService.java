package com.joz.tvrelay;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Runs on the phone: finds the TV, keeps the subscription alive and plays the stream. */
public class ReceiverService extends Service {
    static final String EXTRA_HOST = "host";
    private static final String TAG = "TvRelayReceiver";

    static volatile boolean running;
    static volatile String status = "Stopped";
    static volatile JitterBuffer jitter;
    static volatile String outputInfo = "";
    static volatile long outputUnderruns;
    static volatile long duplicates;
    static volatile long recovered; // originals lost, saved by the second copy
    static final LatencyHistory history = new LatencyHistory();
    // Kodi sync knobs; the screen changes these live, without restarting playback.
    static volatile int kodiMode;
    static volatile int kodiAdjustMs;
    static volatile boolean kodiFlip;
    static volatile String tvHost;  // the TV's IP once found, for Kodi and the Test button

    private Settings settings;
    private volatile InetAddress manualHost;
    private volatile InetAddress server;
    private volatile long lastPacketMs;
    private volatile int streamCodec = Protocol.CODEC_PCM;
    private volatile float rttMs = -1;
    private final short[] chunk = new short[Protocol.SAMPLES_PER_PACKET];
    private final int[] seen = new int[256]; // recent packet seqs, to drop the duplicate copy
    private OpusCodec.Decoder decoder; // net thread only
    private DatagramSocket socket;
    private volatile AudioOut out;
    private Thread netThread;
    private Thread playThread;
    private Thread keepaliveThread;
    private WifiManager.WifiLock lowLatencyLock;
    private WifiManager.WifiLock highPerfLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Notifications.ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(2, Notifications.build(this, "Playing TV audio"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        if (running) return START_NOT_STICKY;

        String host = intent != null ? intent.getStringExtra(EXTRA_HOST) : null;
        settings = Settings.load(this);
        kodiMode = settings.kodiMode;
        kodiAdjustMs = settings.kodiAdjustMs;
        kodiFlip = settings.kodiFlip;
        tvHost = null;
        new Thread(() -> {
            try {
                if (host != null && !host.isEmpty()) manualHost = InetAddress.getByName(host);
                start();
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                status = "Error: " + e.getMessage();
                stopSelf();
            }
        }, "relay-start").start();
        return START_NOT_STICKY;
    }

    private void start() throws IOException {
        socket = new DatagramSocket();
        socket.setBroadcast(true);
        socket.setTrafficClass(Protocol.TRAFFIC_CLASS_VOICE);
        socket.setReceiveBufferSize(1 << 20); // room for Wi-Fi bursts
        socket.setSoTimeout(1000);

        acquireLocks();
        boolean opus = settings.stream().codec == Protocol.CODEC_OPUS;
        // With send-twice the copy lands ~10 ms late, so keep at least that much buffered to use it.
        int minChunks = opus ? 6 : settings.sendTwice ? 3 : 2;
        jitter = new JitterBuffer(settings.bufferChunks(), minChunks, settings.catchup);
        java.util.Arrays.fill(seen, Integer.MIN_VALUE);
        outputUnderruns = 0;
        duplicates = 0;
        recovered = 0;
        running = true;
        status = "Looking for the TV";

        netThread = new Thread(this::netLoop, "relay-net");
        playThread = new Thread(this::playLoop, "relay-play");
        keepaliveThread = new Thread(this::keepaliveLoop, "relay-keepalive");
        netThread.start();
        playThread.start();
        keepaliveThread.start();
    }

    private void netLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[Protocol.MAX_PACKET + 64];
        ByteBuffer in = ByteBuffer.wrap(buf);
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            while (running) {
                try {
                    p.setLength(buf.length);
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    break;
                }
                int len = p.getLength();
                if (len < 12) continue;
                int magic = in.getInt(0);
                if (magic == Protocol.MAGIC_PONG && len >= Protocol.PONG_BYTES) {
                    float rtt = (System.nanoTime() - in.getLong(4)) / 1e6f;
                    rttMs = rttMs < 0 ? rtt : rttMs + (rtt - rttMs) * 0.2f;
                    continue;
                }
                if (magic != Protocol.MAGIC_AUDIO) continue;

                InetAddress from = p.getAddress();
                if (server == null) {
                    server = from; // lock on to the first TV that answers
                } else if (!server.equals(from)) {
                    continue;
                }
                lastPacketMs = SystemClock.elapsedRealtime();
                int seq = in.getInt(4);
                if (seen[seq & 255] == seq) {
                    duplicates++;
                    continue;
                }
                seen[seq & 255] = seq;
                if (buf[10] == 1) recovered++;
                int codec = buf[8];
                int channels = buf[9] == 1 ? 1 : 2;
                int payload = len - Protocol.HEADER_BYTES;
                streamCodec = codec;

                if (codec == Protocol.CODEC_OPUS) {
                    decodeOpus(buf, payload, seq, channels);
                } else {
                    ShortBuffer pcm = ByteBuffer.wrap(buf, Protocol.HEADER_BYTES, payload)
                            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    pushPcm(seq, pcm, payload / 2 / channels, channels);
                }
            }
        } finally {
            if (decoder != null) decoder.release();
        }
    }

    private void decodeOpus(byte[] buf, int payload, int seq, int channels) {
        if (decoder == null || decoder.channels != channels) {
            if (decoder != null) decoder.release();
            decoder = null;
            try {
                decoder = new OpusCodec.Decoder(channels);
            } catch (Exception e) {
                Log.e(TAG, "no Opus decoder", e);
                status = "Error: this phone can't decode Opus";
                return;
            }
        }
        decoder.decode(buf, Protocol.HEADER_BYTES, payload, seq * (long) Protocol.CHUNK_US,
                (ptsUs, pcm, frames, ch) ->
                        pushPcm((int) ((ptsUs + Protocol.CHUNK_US / 2) / Protocol.CHUNK_US), pcm, frames, ch));
    }

    /** Splits decoded audio into 5 ms stereo chunks for the jitter buffer. */
    private void pushPcm(int seq, ShortBuffer src, int frames, int channels) {
        for (int k = 0; (k + 1) * Protocol.FRAMES_PER_PACKET <= frames; k++) {
            for (int f = 0; f < Protocol.FRAMES_PER_PACKET; f++) {
                short l = src.get();
                short r = channels == 2 ? src.get() : l;
                chunk[f * 2] = l;
                chunk[f * 2 + 1] = r;
            }
            jitter.put(seq + k, chunk);
        }
    }

    private void playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buf = new short[(Protocol.FRAMES_PER_PACKET + 8) * Protocol.CHANNELS]; // room for slow-down
        long baseUnderruns = 0;
        while (running) {
            AudioOut o = openOutput();
            out = o;
            outputInfo = o.describe();
            while (running) {
                int frames = jitter.next(buf);
                // Blocking write paces this loop to the phone's audio clock.
                if (!o.write(buf, frames)) break; // route changed (headphones etc.): reopen
                outputUnderruns = baseUnderruns + o.underruns();
                if ((++framesSinceInfo & 63) == 0) outputInfo = o.describe();
            }
            baseUnderruns += o.underruns();
            out = null;
            o.release();
        }
    }

    private int framesSinceInfo;

    private AudioOut openOutput() {
        if (settings.exclusive) {
            AAudioOut a = AAudioOut.open();
            if (a != null) return a;
            Log.w(TAG, "AAudio unavailable, falling back to AudioTrack");
        }
        return new TrackOut(settings.output, framesPerBurst());
    }

    private int framesPerBurst() {
        try {
            return Integer.parseInt(getSystemService(AudioManager.class)
                    .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
        } catch (Exception e) {
            return 256;
        }
    }

    private void keepaliveLoop() {
        // The phone drives Kodi itself now; older TVs still do it when asked, so never ask them.
        KodiSync kodi = new KodiSync(this);
        String lastSavedHost = settings.lastTvHost;
        kodi.start();
        StreamConfig cfg = settings.stream();
        byte[] hello = new byte[Protocol.SUBSCRIBE_BYTES];
        ByteBuffer h = ByteBuffer.wrap(hello);
        h.putInt(0, Protocol.MAGIC_SUBSCRIBE);
        hello[4] = (byte) cfg.codec;
        hello[5] = (byte) cfg.channels;
        h.putShort(6, (short) cfg.bitrateKbps);
        InetAddress broadcast;
        try {
            broadcast = InetAddress.getByName("255.255.255.255");
        } catch (IOException e) {
            return;
        }

        for (int tick = 0; running; tick++) {
            long now = SystemClock.elapsedRealtime();
            boolean receiving = server != null && now - lastPacketMs < 3000;
            if (tick % 4 == 0) {
                if (server != null && !receiving) server = null; // TV went away; search again
                InetAddress dest = manualHost != null ? manualHost : server != null ? server : broadcast;
                h.putLong(8, System.nanoTime());
                hello[16] = (byte) (settings.sendTwice ? Protocol.FLAG_SEND_TWICE : 0);
                hello[17] = (byte) Protocol.KODI_OFF;
                // Kodi only takes 25 ms steps, so round the adjustment up to one and make up the
                // remainder here by holding audio back a little longer: 5 ms precision overall.
                int adjust = kodiMode == Protocol.KODI_OFF ? 0 : kodiAdjustMs;
                int coarse = kodiMode == Protocol.KODI_OFF ? 0 : (int) Math.ceil(adjust / 25.0) * 25;
                int fineMs = coarse - adjust;
                jitter.setExtraDelay(Math.round(fineMs / 5f));
                // 5 s average, so Kodi isn't chasing every Wi-Fi wobble. The fine-tune delay is left
                // out, or Kodi would cancel it again.
                kodi.update(kodiMode, coarse, Math.max(0, Math.round(history.averageTotal(20)) - fineMs), kodiFlip);
                InetAddress tv = receiving ? server : manualHost;
                tvHost = tv != null ? tv.getHostAddress() : null;
                kodi.setHost(tvHost);
                if (receiving && !tvHost.equals(lastSavedHost)) {
                    Settings.saveLastTvHost(this, tvHost); // lets the Test button work before the next Listen
                    lastSavedHost = tvHost;
                }
                try {
                    socket.send(new DatagramPacket(hello, hello.length, dest, Protocol.PORT));
                } catch (IOException ignored) {
                }
                status = receiving ? "Playing from " + server.getHostAddress() : "Looking for the TV";
            }
            AudioOut o = out;
            if (receiving) {
                float codec = streamCodec == Protocol.CODEC_OPUS ? Protocol.OPUS_LATENCY_MS : 0;
                history.add(Math.max(0, rttMs) / 2, codec, jitter.avgBufferMs(), o == null ? 0 : o.latencyMs());
            }
            SystemClock.sleep(250);
        }
        kodi.stop(); // puts Kodi's delay back to zero
    }

    private void acquireLocks() {
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        // Phone Wi-Fi power save buffers packets at the AP and delivers them in bursts.
        lowLatencyLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "relay-ll");
        highPerfLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "relay-hp");
        lowLatencyLock.acquire();
        highPerfLock.acquire();
        wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvrelay:receiver");
        wakeLock.acquire();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (socket != null) socket.close();
        AudioOut o = out;
        if (o != null) o.stop(); // unblocks write()
        join(netThread);
        join(playThread);
        join(keepaliveThread);
        if (lowLatencyLock != null && lowLatencyLock.isHeld()) lowLatencyLock.release();
        if (highPerfLock != null && highPerfLock.isHeld()) highPerfLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (!status.startsWith("Error")) status = "Stopped";
        super.onDestroy();
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(1500);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
