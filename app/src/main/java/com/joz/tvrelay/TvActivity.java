package com.joz.tvrelay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
    static final String EXTRA_START = "start";  // the phone asked to start streaming
    static volatile boolean visible;            // on screen, so the phone's start request can work
    static final String EXTRA_FROM_BACKGROUND = "fromBackground"; // popped up over another app
    private boolean returnWhenStarted;          // go back to that app once capture is allowed

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button toggle;
    private TextView state;
    private TextView detail;
    private TextView hint;
    private TextView ip;
    private TextView kodi;
    private TextView startMode;
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
        kodi = findViewById(R.id.kodi);
        kodi.setOnClickListener(v -> showKodi());
        startMode = findViewById(R.id.startMode);
        startMode.setOnClickListener(v -> showStartMode());
        toggle.setOnFocusChangeListener((v, focused) ->
                v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(120).start());
        toggle.requestFocus();

        updates = new UpdateUi(this, findViewById(R.id.version));
        startForegroundService(new Intent(this, LinkService.class)); // so the phone can find us from now on
        handleStart(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleStart(intent);
    }

    private void handleStart(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_START, false) && !SenderService.running) {
            intent.removeExtra(EXTRA_START);
            returnWhenStarted = intent.getBooleanExtra(EXTRA_FROM_BACKGROUND, false);
            requestCapture();
        }
    }

    @Override
    protected void onDestroy() {
        updates.destroy();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
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
        startMode.setText("Start mode: " + (autoStart() ? "Auto" : "Manual"));
        boolean found = LinkService.kodiFound;
        SpannableStringBuilder k = new SpannableStringBuilder("● ");
        k.setSpan(new ForegroundColorSpan(found ? Color.rgb(0x34, 0xC7, 0x59) : Color.rgb(0xFF, 0x45, 0x3A)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        kodi.setText(k.append(found ? "Kodi found" : "Kodi not found"));
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
        if (returnWhenStarted) moveTaskToBack(true); // back to the show we interrupted
        returnWhenStarted = false;
    }

    /** Auto start is Android's "display over other apps" permission, which this TV lets you grant in its settings. */
    private boolean autoStart() {
        return android.provider.Settings.canDrawOverlays(this);
    }

    private void showStartMode() {
        boolean auto = autoStart();
        new AlertDialog.Builder(this)
                .setTitle(auto ? "Start mode: Auto" : "Turn on automatic start?")
                .setMessage((auto ? "Your phone can start streaming any time, even while you're watching something "
                        + "else: the TV's recording prompt pops up over your show, and after Start now you're "
                        + "back in it.\n\nTo switch back to manual, find TV Relay on the next screen and turn it off."
                        : "Lets your phone start streaming any time, even while you're watching something else: "
                        + "tapping Listen pops the TV's recording prompt up over your show, and after Start now "
                        + "you're back in it.\n\nAndroid needs TV Relay allowed to \"Display over other apps\". "
                        + "On the next screen, find TV Relay and turn it on, then press Back."))
                .setPositiveButton("Open setting", (d, w) -> openOverlaySetting())
                .setNegativeButton(auto ? "Close" : "Not now", null)
                .show();
    }

    private void openOverlaySetting() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("Look in Settings > Apps > Special app access > "
                    + "Display over other apps.").setPositiveButton("OK", null).show();
        }
    }

    private void showKodi() {
        String ip = ipAddress();
        new AlertDialog.Builder(this)
                .setTitle("Kodi lip sync (optional)")
                .setMessage((LinkService.kodiFound
                        ? "Kodi found at " + (ip != null ? ip : "this TV") + ":" + LinkService.KODI_PORT + "."
                        : "Kodi not found.")
                        + "\n\nOnly if you watch in Kodi: TV Relay can adjust Kodi's audio delay so the picture "
                        + "lines up with the sound on your phone. Set it up in the phone app's Kodi sync tab.")
                .setPositiveButton("OK", null)
                .show();
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
