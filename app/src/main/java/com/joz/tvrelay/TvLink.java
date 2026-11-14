package com.joz.tvrelay;

import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * Phone side of the link: while the phone app is open, asks the TV once a second whether it's there,
 * streaming, and has Kodi, whether or not the phone is listening. TVs before v0.9 don't answer.
 */
final class TvLink {
    private static final long FRESH_MS = 3500;

    static volatile long lastReplyMs;
    static volatile boolean streaming;
    static volatile boolean kodiFound;
    static volatile boolean screenOpen;
    static volatile int versionCode;
    static volatile String address;

    private static volatile boolean running;
    private static volatile boolean startRequested;
    private static Thread thread;

    static boolean found() {
        return lastReplyMs != 0 && SystemClock.elapsedRealtime() - lastReplyMs < FRESH_MS;
    }

    /** Asks the TV to open its capture prompt; only works while TV Relay is on the TV's screen. */
    static void requestStart() {
        startRequested = true;
    }

    static synchronized void start(String manualHost, String lastHost) {
        if (running) return;
        running = true;
        thread = new Thread(() -> loop(manualHost, lastHost), "tv-link");
        thread.start();
    }

    static synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private static void loop(String manualHost, String lastHost) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            byte[] q = new byte[Protocol.QUERY_BYTES];
            ByteBuffer qb = ByteBuffer.wrap(q);
            qb.putInt(0, Protocol.MAGIC_QUERY);
            byte[] r = new byte[64];
            ByteBuffer rb = ByteBuffer.wrap(r);
            DatagramPacket reply = new DatagramPacket(r, r.length);
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            long nextQuery = 0;
            while (running) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextQuery || startRequested) {
                    nextQuery = now + 1000;
                    boolean start = startRequested;
                    startRequested = false;
                    qb.putLong(4, System.nanoTime());
                    q[12] = start ? Protocol.QUERY_START : Protocol.QUERY_STATUS;
                    // Where the TV is known to be, plus a broadcast in case it moved.
                    for (String host : new String[]{manualHost, found() ? address : lastHost}) {
                        if (host != null && !host.isEmpty()) send(socket, q, InetAddress.getByName(host));
                    }
                    send(socket, q, broadcast);
                }
                try {
                    reply.setLength(r.length);
                    socket.receive(reply);
                } catch (IOException timeout) {
                    continue;
                }
                if (reply.getLength() < 16 || rb.getInt(0) != Protocol.MAGIC_STATUS) continue;
                streaming = r[12] == 1;
                kodiFound = r[13] == 1;
                versionCode = rb.getShort(14) & 0xffff;
                screenOpen = reply.getLength() >= Protocol.STATUS_BYTES && r[16] == 1;
                address = reply.getAddress().getHostAddress();
                lastReplyMs = SystemClock.elapsedRealtime();
            }
        } catch (IOException ignored) {
            // no network; the screen shows "TV not found"
        }
        running = false;
    }

    private static void send(DatagramSocket socket, byte[] q, InetAddress to) {
        try {
            socket.send(new DatagramPacket(q, q.length, to, Protocol.LINK_PORT));
        } catch (IOException ignored) {
        }
    }

    private TvLink() {}
}
