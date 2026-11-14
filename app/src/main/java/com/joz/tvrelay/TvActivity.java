package com.joz.tvrelay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The TV's screen: one big Start/Stop button, everything else read-only. */
public class TvActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final int REQ_CAPTURE = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button toggle;
    private TextView state;
    private TextView detail;
    private TextView hint;
    private TextView ip;
    private UpdateUi updates;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv);
        toggle = findViewById(R.id.toggle);
        state = findViewById(R.id.state);
        detail = findViewById(R.id.detail);
        hint = findViewById(R.id.hint);

        toggle.setOnClickListener(v -> {
            if (SenderService.running) {
                stopService(new Intent(this, SenderService.class));
            } else {
                requestCapture();
            }
        });
        ip = findViewById(R.id.ip);
        toggle.setOnFocusChangeListener((v, focused) ->
                v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(120).start());
        toggle.requestFocus();

        updates = new UpdateUi(this, findViewById(R.id.version));
    }

    @Override
    protected void onDestroy() {
        updates.destroy();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    private void render() {
        boolean on = SenderService.running;
        toggle.setText(on ? "Stop streaming" : "Start streaming");
        String s = SenderService.status;
        if (s.startsWith("Error")) {
            state.setText("Something went wrong");
            detail.setText(s.substring(Math.min(s.length(), 7)));
        } else if (!on) {
            state.setText("Not streaming");
            detail.setText("Press OK on Start streaming, then allow the recording prompt.");
        } else if (SenderService.clientCount == 0) {
            state.setText("Waiting for your phone");
            detail.setText("Open TV Relay on your phone and tap Listen.");
        } else {
            int n = SenderService.clientCount;
            state.setText("Streaming to " + n + (n == 1 ? " phone" : " phones"));
            detail.setText(SenderService.format);
        }
        String address = ipAddress();
        ip.setText(address != null ? "IP address " + address : "No network");
        hint.setText(on ? "Streaming keeps going in the background. Press Home or Back to return to what you were watching."
                : "All settings live in the phone app. The TV switches format by itself.");
    }

    private void requestCapture() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
            return;
        }
        try {
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),
                    REQ_CAPTURE);
        } catch (Exception e) {
            SenderService.status = "Error: this TV has no capture consent screen";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            SenderService.status = "Error: audio capture permission denied";
            return;
        }
        requestCapture();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            SenderService.status = "Error: recording wasn't allowed";
            return;
        }
        startForegroundService(new Intent(this, SenderService.class)
                .putExtra(SenderService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(SenderService.EXTRA_RESULT_DATA, data));
    }

    /** The TV's IPv4 address on the active network (Wi-Fi or Ethernet), or null. */
    private String ipAddress() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) return la.getAddress().getHostAddress();
            }
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
