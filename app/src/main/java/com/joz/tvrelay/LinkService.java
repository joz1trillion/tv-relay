package com.joz.tvrelay;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * Runs on the TV from the first time TV Relay is opened until it's stopped or the TV reboots.
 * Answers the phone's status queries on {@link Protocol#LINK_PORT} (found, streaming, Kodi there,
 * version) even when nothing is streaming, and opens the capture prompt when the phone asks to start.
 * Also keeps an eye on whether Kodi's web server is up, for the TV screen.
 */
public class LinkService extends Service {
    private static final String TAG = "TvRelayLink";
    static final int KODI_PORT = 8080;

    static volatile boolean running;
    static volatile boolean kodiFound;

    private DatagramSocket socket;
    private Thread linkThread;
    private Thread kodiThread;
    private long lastStartMs;  // link thread only

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Notifications.ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Foreground, or Android stops it a minute after you go back to your show.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(3, Notifications.build(this, "Ready for your phone"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(3, Notifications.build(this, "Ready for your phone"));
        }
        if (running) return START_NOT_STICKY;
        running = true;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(Protocol.LINK_PORT));
        } catch (IOException e) {
            Log.e(TAG, "link socket", e);
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        linkThread = new Thread(this::linkLoop, "relay-link");
        kodiThread = new Thread(this::kodiLoop, "relay-kodi-probe");
        linkThread.start();
        kodiThread.start();
        return START_NOT_STICKY;
    }

    private void linkLoop() {
        byte[] buf = new byte[64];
        ByteBuffer in = ByteBuffer.wrap(buf);
        // The TV's name follows the fixed part, so a phone can tell two TVs apart.
        byte[] nameBytes = deviceName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int nameLen = Math.min(nameBytes.length, 40);
        byte[] reply = new byte[Protocol.STATUS_BYTES + 1 + nameLen];
        reply[Protocol.STATUS_BYTES] = (byte) nameLen;
        System.arraycopy(nameBytes, 0, reply, Protocol.STATUS_BYTES + 1, nameLen);
        ByteBuffer out = ByteBuffer.wrap(reply);
        out.putInt(0, Protocol.MAGIC_STATUS);
        int versionCode = Updater.versionCode(this);
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
            } catch (IOException e) {
                break; // closed on shutdown
            }
            if (p.getLength() < Protocol.QUERY_BYTES || in.getInt(0) != Protocol.MAGIC_QUERY) continue;
            // A device switched to Client mode (advanced) isn't a TV to phones, unless it's streaming.
            if (!SenderService.running && !Settings.tvScreen(this)) continue;
            if (buf[12] == Protocol.QUERY_START && !SenderService.running) askToStart();
            if (buf[12] == Protocol.QUERY_STOP && SenderService.running) {
                stopService(new Intent(this, SenderService.class)); // fine from the background
            }
            out.putLong(4, in.getLong(4));
            reply[12] = (byte) (SenderService.running ? 1 : 0);
            reply[13] = (byte) (kodiFound ? 1 : 0);
            out.putShort(14, (short) versionCode);
            reply[16] = (byte) (TvActivity.visible ? 1 : 0);
            reply[17] = (byte) (canPopUp() ? 1 : 0);
            try {
                socket.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Opens the capture prompt. Android only lets this through while TV Relay is on screen: a
     * background app can't open its activity, and this TV ignores full-screen notifications too
     * (tested), so otherwise the phone tells the user to open TV Relay on the TV.
     */
    private void askToStart() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastStartMs < 5000) return; // the phone sends each request to more than one address
        if (!TvActivity.visible && !canPopUp()) return;
        lastStartMs = now;
        startActivity(new Intent(this, TvActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(TvActivity.EXTRA_START, true)
                .putExtra(TvActivity.EXTRA_FROM_BACKGROUND, !TvActivity.visible));
    }

    /**
     * Automatic start mode: with "display over other apps" granted (adb on most TVs), Android lets TV Relay
     * open its screen from the background, so the phone can start streaming any time.
     */
    private boolean canPopUp() {
        return android.provider.Settings.canDrawOverlays(this);
    }

    /** "Living room TV" if the TV has been given a name in its settings, else the model. */
    private String deviceName() {
        String n = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        return n != null && !n.isEmpty() ? n : Build.MODEL;
    }

    /** Every few seconds: is something answering on Kodi's port, and is it Kodi? */
    private void kodiLoop() {
        while (running) {
            kodiFound = isKodi();
            try {
                Thread.sleep(kodiFound ? 10_000 : 4_000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Kodi's web server either asks for a login in a realm called "Kodi" or serves its JSON-RPC API. */
    static boolean isKodi() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + KODI_PORT + "/jsonrpc").openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
            int code = c.getResponseCode();
            if (code == 401) {
                String realm = c.getHeaderField("WWW-Authenticate");
                return realm != null && realm.contains("Kodi");
            }
            if (code == 200) {
                byte[] head = new byte[512];
                int n = c.getInputStream().read(head);
                String body = n > 0 ? new String(head, 0, n, "UTF-8") : "";
                return body.contains("jsonrpc") && (body.contains("xbmc") || body.contains("Kodi"));
            }
            return false;
        } catch (IOException e) {
            return false; // nothing listening
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (socket != null) socket.close();
        if (kodiThread != null) kodiThread.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
