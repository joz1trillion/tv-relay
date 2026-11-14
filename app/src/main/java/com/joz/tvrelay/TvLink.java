package com.joz.tvrelay;

import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phone side of the link: while the phone app is open, asks every TV on the network once a second
 * whether it's there, streaming, and has Kodi, whether or not the phone is listening. With more than
 * one TV, the static fields describe the chosen one (see {@link #setPreferred}), and start/stop
 * commands only go to it. TVs before v0.9 don't answer.
 */
final class TvLink {
    private static final long FRESH_MS = 3500;

    static final class Tv {
        final String address;
        volatile String name;   // the TV's device name; null from TVs before v1.3
        volatile boolean streaming;
        volatile long lastReplyMs;

        Tv(String address) {
            this.address = address;
        }

        String label() {
            return name != null ? name + " (" + address + ")" : address;
        }
    }

    // The chosen TV, for the screen.
    static volatile long lastReplyMs;
    static volatile boolean streaming;
    static volatile boolean kodiFound;
    static volatile boolean screenOpen;
    static volatile boolean canPopUp;
    static volatile int versionCode;
    static volatile String address;
    static volatile String name;

    private static final Map<String, Tv> tvs = new LinkedHashMap<>();
    private static volatile String preferred;  // a TV picked by hand (Connect directly), or null
    private static volatile boolean running;
    private static volatile byte command = Protocol.QUERY_STATUS; // sent with the next query
    private static Thread thread;

    static boolean found() {
        return lastReplyMs != 0 && SystemClock.elapsedRealtime() - lastReplyMs < FRESH_MS;
    }

    /** TVs that answered in the last few seconds. */
    static List<Tv> all() {
        long now = SystemClock.elapsedRealtime();
        List<Tv> list = new ArrayList<>();
        synchronized (tvs) {
            for (Tv tv : tvs.values()) if (now - tv.lastReplyMs < FRESH_MS) list.add(tv);
        }
        return list;
    }

    /** Use this TV (an IP), or null to follow whichever the phone plays from or finds first. */
    static void setPreferred(String host) {
        String p = host == null || host.isEmpty() ? null : host;
        if (p == null ? preferred == null : p.equals(preferred)) return;
        preferred = p;
        lastReplyMs = 0; // until the chosen TV answers
    }

    /** Asks the TV to open its capture prompt; works while TV Relay is on the TV's screen, or in automatic start mode. */
    static void requestStart() {
        command = Protocol.QUERY_START;
    }

    /** Asks the TV to stop streaming; works with TV Relay in the background. */
    static void requestStop() {
        command = Protocol.QUERY_STOP;
    }

    static synchronized void start(String manualHost, String lastHost) {
        setPreferred(manualHost);
        if (running) return;
        running = true;
        thread = new Thread(() -> loop(lastHost), "tv-link");
        thread.start();
    }

    static synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private static void loop(String lastHost) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            byte[] q = new byte[Protocol.QUERY_BYTES];
            ByteBuffer qb = ByteBuffer.wrap(q);
            qb.putInt(0, Protocol.MAGIC_QUERY);
            byte[] r = new byte[96];
            ByteBuffer rb = ByteBuffer.wrap(r);
            DatagramPacket reply = new DatagramPacket(r, r.length);
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            long nextQuery = 0;
            while (running) {
                long now = SystemClock.elapsedRealtime();
                byte cmd = command;
                if (cmd != Protocol.QUERY_STATUS && address != null) {
                    // Start and stop go to the chosen TV only, never to every TV on the network.
                    command = Protocol.QUERY_STATUS;
                    qb.putLong(4, System.nanoTime());
                    q[12] = cmd;
                    send(socket, q, InetAddress.getByName(address));
                } else if (now >= nextQuery) {
                    nextQuery = now + 1000;
                    qb.putLong(4, System.nanoTime());
                    q[12] = Protocol.QUERY_STATUS;
                    // Where TVs are known to be, plus a broadcast for any others.
                    for (String host : new String[]{preferred, lastHost}) {
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
                int len = reply.getLength();
                if (len < 16 || rb.getInt(0) != Protocol.MAGIC_STATUS) continue;
                if (NetUtil.isLocal(reply.getAddress())) continue; // never list this device itself
                String from = reply.getAddress().getHostAddress();
                Tv tv;
                synchronized (tvs) {
                    tv = tvs.get(from);
                    if (tv == null) tvs.put(from, tv = new Tv(from));
                }
                tv.streaming = r[12] == 1;
                tv.lastReplyMs = SystemClock.elapsedRealtime();
                if (len > Protocol.STATUS_BYTES) {
                    int n = Math.min(r[Protocol.STATUS_BYTES] & 0xff, len - Protocol.STATUS_BYTES - 1);
                    tv.name = new String(r, Protocol.STATUS_BYTES + 1, n, StandardCharsets.UTF_8);
                }
                if (!from.equals(chosen())) continue;
                streaming = tv.streaming;
                kodiFound = r[13] == 1;
                versionCode = rb.getShort(14) & 0xffff;
                screenOpen = len >= Protocol.STATUS_BYTES && r[16] == 1;
                canPopUp = len >= Protocol.STATUS_BYTES && r[17] == 1;
                address = from;
                name = tv.name;
                lastReplyMs = tv.lastReplyMs;
            }
        } catch (IOException ignored) {
            // no network; the screen shows "TV not found"
        }
        running = false;
    }

    /** The picked TV, else the one being played from, else the current one, else the first to answer. */
    private static String chosen() {
        if (preferred != null) return preferred;
        String playing = ReceiverService.tvHost;
        if (playing != null) return playing;
        List<Tv> live = all();
        if (live.isEmpty()) return null;
        for (Tv tv : live) if (tv.address.equals(address)) return address; // stick with the current one
        return live.get(0).address;
    }

    private static void send(DatagramSocket socket, byte[] q, InetAddress to) {
        try {
            socket.send(new DatagramPacket(q, q.length, to, Protocol.LINK_PORT));
        } catch (IOException ignored) {
        }
    }

    private TvLink() {}
}
