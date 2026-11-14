package com.joz.tvrelay;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runs on the TV: captures what other apps are playing and streams it to subscribed phones. */
public class SenderService extends Service {
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    private static final String TAG = "TvRelaySender";

    static volatile boolean running;
    static volatile String status = "Stopped";
    static volatile int clientCount;
    static volatile String format = ""; // what's being sent, for the TV screen
    static volatile long packetsSent;

    private final Map<InetSocketAddress, Long> clients = new ConcurrentHashMap<>();
    private final byte[] packet = new byte[Protocol.MAX_PACKET];
    private volatile StreamConfig requested = StreamConfig.DEFAULT;
    private volatile boolean sendTwice;
    private StreamConfig active; // capture thread only
    private int tick;            // capture thread only: current 5 ms chunk
    private final byte[][] sent = new byte[8][Protocol.MAX_PACKET]; // recent packets, for resending
    private final int[] sentLen = new int[8];
    private final int[] sentTick = new int[8];
    private int sentPos;
    private MediaProjection projection;
    private AudioRecord record;
    private DatagramSocket socket;
    private Thread captureThread;
    private Thread controlThread;
    private WifiManager.WifiLock lowLatencyLock;
    private WifiManager.WifiLock highPerfLock;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || Notifications.ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY; // can't restart on our own: capture needs fresh user consent
        }
        // Android 14 requires the foreground service to be running before getMediaProjection().
        startForeground(1, Notifications.build(this, "Streaming TV audio"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (running) return START_NOT_STICKY;

        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                    intent.getParcelableExtra(EXTRA_RESULT_DATA));
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
            start();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            status = "Error: " + e.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before we start
    private void start() throws IOException {
        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        int minBuf = AudioRecord.getMinBufferSize(Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Protocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build())
                // Generous capture buffer so a scheduling hiccup on the TV doesn't drop audio.
                .setBufferSizeInBytes(Math.max(minBuf, Protocol.PAYLOAD_BYTES) * 8)
                .setAudioPlaybackCaptureConfig(config)
                .build();

        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(Protocol.PORT));
        socket.setBroadcast(true);
        socket.setTrafficClass(Protocol.TRAFFIC_CLASS_VOICE);
        socket.setSendBufferSize(256 * 1024);

        acquireLocks();
        running = true;
        packetsSent = 0;
        status = "Waiting for a phone";

        controlThread = new Thread(this::controlLoop, "relay-control");
        captureThread = new Thread(this::captureLoop, "relay-capture");
        controlThread.start();
        captureThread.start();
    }

    private void controlLoop() {
        byte[] buf = new byte[64];
        ByteBuffer in = ByteBuffer.wrap(buf);
        byte[] pong = new byte[Protocol.PONG_BYTES];
        ByteBuffer out = ByteBuffer.wrap(pong);
        out.putInt(0, Protocol.MAGIC_PONG);
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
            } catch (IOException e) {
                break; // socket closed on shutdown
            }
            if (p.getLength() < Protocol.SUBSCRIBE_BYTES || in.getInt(0) != Protocol.MAGIC_SUBSCRIBE) continue;
            clients.put(new InetSocketAddress(p.getAddress(), p.getPort()), SystemClock.elapsedRealtime());

            int codec = buf[4] == Protocol.CODEC_OPUS ? Protocol.CODEC_OPUS : Protocol.CODEC_PCM;
            int channels = buf[5] == 1 ? 1 : 2;
            int kbps = Math.max(32, Math.min(510, in.getShort(6) & 0xffff));
            if (!requested.matches(codec, channels, kbps)) {
                requested = new StreamConfig(codec, channels, kbps); // most recent phone wins
            }
            sendTwice = (buf[16] & Protocol.FLAG_SEND_TWICE) != 0;
            out.putLong(4, in.getLong(8));
            try {
                socket.send(new DatagramPacket(pong, pong.length, p.getSocketAddress()));
            } catch (IOException ignored) {
            }
        }
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] raw = new byte[Protocol.PAYLOAD_BYTES];
        byte[] mono = new byte[Protocol.PAYLOAD_BYTES / 2];
        OpusCodec.Encoder encoder = null;
        String note = "";
        int chunk = 0;
        record.startRecording();

        try {
            while (running) {
                if (!readFully(raw, 0, raw.length)) break;
                int seq = chunk++;
                tick = seq;
                if (!pruneClients()) {
                    status = "Waiting for a phone";
                    continue;
                }

                StreamConfig want = requested;
                if (!want.equals(active)) {
                    if (encoder != null) encoder.release();
                    encoder = null;
                    note = "";
                    active = want;
                    if (want.codec == Protocol.CODEC_OPUS) {
                        try {
                            encoder = new OpusCodec.Encoder(want.channels, want.bitrateKbps * 1000);
                        } catch (Exception e) {
                            Log.w(TAG, "Opus encoder unavailable", e);
                            note = " (Opus unavailable, sending PCM)";
                        }
                    }
                }

                byte[] pcm = raw;
                if (active.channels == 1) {
                    downmix(raw, mono);
                    pcm = mono;
                }
                if (encoder != null) {
                    encoder.encode(pcm, pcm.length, seq * (long) Protocol.CHUNK_US, this::sendOpus);
                } else {
                    send(seq, Protocol.CODEC_PCM, pcm, pcm.length);
                }
                if (sendTwice) resend(seq - Protocol.RESEND_DELAY_CHUNKS);
                int n = clients.size();
                clientCount = n;
                format = (encoder != null ? active.describe() : "PCM "
                        + (active.channels == 2 ? "stereo" : "mono")) + note
                        + (sendTwice ? ", each packet sent twice" : "");
                status = "Streaming " + format + " to " + n + (n == 1 ? " phone" : " phones");
            }
        } finally {
            if (encoder != null) encoder.release();
        }
    }

    private void sendOpus(long ptsUs, byte[] data, int len) {
        send((int) ((ptsUs + Protocol.CHUNK_US / 2) / Protocol.CHUNK_US), Protocol.CODEC_OPUS, data, len);
    }

    private void send(int seq, int codec, byte[] payload, int len) {
        ByteBuffer h = ByteBuffer.wrap(packet);
        h.putInt(0, Protocol.MAGIC_AUDIO);
        h.putInt(4, seq);
        packet[8] = (byte) codec;
        packet[9] = (byte) active.channels;
        packet[10] = 0;
        System.arraycopy(payload, 0, packet, Protocol.HEADER_BYTES, len);
        int size = Protocol.HEADER_BYTES + len;
        System.arraycopy(packet, 0, sent[sentPos], 0, size);
        sentLen[sentPos] = size;
        sentTick[sentPos] = tick;
        sentPos = (sentPos + 1) % sent.length;
        sendToAll(packet, size);
    }

    /** Sends a second copy of whatever went out during {@code oldTick}, flagged as a resend. */
    private void resend(int oldTick) {
        for (int i = 0; i < sent.length; i++) {
            if (sentLen[i] == 0 || sentTick[i] != oldTick) continue;
            sent[i][10] = 1;
            sendToAll(sent[i], sentLen[i]);
        }
    }

    private void sendToAll(byte[] packet, int size) {
        for (InetSocketAddress c : clients.keySet()) {
            try {
                socket.send(new DatagramPacket(packet, size, c));
                packetsSent++;
            } catch (IOException ignored) {
                // transient (e.g. Wi-Fi blip); the phone's jitter buffer covers it
            }
        }
    }

    /** Drops phones that stopped sending keepalives; returns whether anyone is still listening. */
    private boolean pruneClients() {
        long now = SystemClock.elapsedRealtime();
        for (Iterator<Map.Entry<InetSocketAddress, Long>> it = clients.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > Protocol.CLIENT_TIMEOUT_MS) it.remove();
        }
        clientCount = clients.size();
        return clientCount > 0;
    }

    private static void downmix(byte[] stereo, byte[] mono) {
        ByteBuffer in = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < Protocol.FRAMES_PER_PACKET; f++) {
            out.putShort(f * 2, (short) ((in.getShort(f * 4) + in.getShort(f * 4 + 2)) / 2));
        }
    }

    private boolean readFully(byte[] buf, int off, int len) {
        int done = 0;
        while (done < len) {
            int n = record.read(buf, off + done, len - done, AudioRecord.READ_BLOCKING);
            if (n <= 0) return false;
            done += n;
        }
        return true;
    }

    private void acquireLocks() {
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        // Stops the TV's Wi-Fi from napping between packets, a classic cause of stutter.
        lowLatencyLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "relay-ll");
        highPerfLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "relay-hp");
        multicastLock = wm.createMulticastLock("relay-discovery"); // receive phones' broadcast hellos
        lowLatencyLock.acquire();
        highPerfLock.acquire();
        multicastLock.acquire();
        wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvrelay:sender");
        wakeLock.acquire();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (record != null) {
            try {
                record.stop(); // unblocks read()
            } catch (IllegalStateException ignored) {
                // never initialised (start failed)
            }
        }
        if (socket != null) socket.close();
        join(captureThread);
        join(controlThread);
        if (record != null) record.release();
        if (projection != null) projection.stop();
        release(lowLatencyLock);
        release(highPerfLock);
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        clients.clear();
        clientCount = 0;
        if (!status.startsWith("Error")) status = "Stopped";
        super.onDestroy();
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(1000);
        } catch (InterruptedException ignored) {
        }
    }

    private static void release(WifiManager.WifiLock lock) {
        if (lock != null && lock.isHeld()) lock.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
