package com.joz.tvrelay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The TV's screen: one big Start/Stop button, everything else read-only. */
public class TvActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final int REQ_CAPTURE = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button toggle;
    private TextView state;
    private TextView detail;
    private TextView kodiStatus;
    private TextView hint;
    private Button update;

    /** Newest release on GitHub when it's newer than this install. */
    private Updater.Release available;
    private boolean updating;

    private final BroadcastReceiver installStatus = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            updating = false;
            renderUpdate();
            if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                showMessage("Update failed", msg != null ? msg : "Android didn't say why.");
            }
        }
    };

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
        kodiStatus = findViewById(R.id.kodiStatus);
        hint = findViewById(R.id.hint);
        update = findViewById(R.id.update);
        ((TextView) findViewById(R.id.version)).setText("v" + Updater.versionName(this));

        toggle.setOnClickListener(v -> {
            if (SenderService.running) {
                stopService(new Intent(this, SenderService.class));
            } else {
                requestCapture();
            }
        });
        findViewById(R.id.back).setOnClickListener(v -> moveTaskToBack(true));
        findViewById(R.id.kodi).setOnClickListener(v -> showKodiLogin());
        update.setOnClickListener(v -> onUpdatePressed());

        for (int id : new int[]{R.id.toggle, R.id.back, R.id.kodi, R.id.update}) {
            findViewById(id).setOnFocusChangeListener((v, focused) ->
                    v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                            .setDuration(120).start());
        }
        toggle.requestFocus();

        registerReceiver(installStatus, new IntentFilter(Updater.ACTION_INSTALL_STATUS),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        renderUpdate();
        checkForUpdate(false);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(installStatus);
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
        kodiStatus.setText(on ? KodiSync.status : "");
        kodiStatus.setVisibility(on && KodiSync.state != Protocol.KODI_STATE_OFF ? View.VISIBLE : View.GONE);
        hint.setText(on
                ? "Streaming keeps going in the background. Press Back to my show (or Home) to return to what you were watching."
                : "All settings live in the phone app. The TV switches format by itself.");
    }

    private void renderUpdate() {
        if (!updating) {
            update.setText(available != null ? "Get v" + available.version : "Updates");
        }
    }

    private void onUpdatePressed() {
        if (updating) return;
        if (available == null) {
            checkForUpdate(true);
        } else if (!getPackageManager().canRequestPackageInstalls()) {
            askInstallPermission();
        } else {
            install(available);
        }
    }

    /** Quietly on launch; with feedback when the button was pressed. */
    private void checkForUpdate(boolean userAsked) {
        updating = true;
        if (userAsked) update.setText("Checking…");
        new Thread(() -> {
            Updater.Release release = null;
            String error = null;
            try {
                release = Updater.checkForUpdate(this);
            } catch (Exception e) {
                error = e.getMessage();
            }
            Updater.Release found = release;
            String failure = error;
            runOnUiThread(() -> {
                updating = false;
                available = found;
                renderUpdate();
                if (!userAsked || isFinishing()) return;
                if (failure != null) {
                    showMessage("Couldn't check for updates", failure);
                } else if (found == null) {
                    Toast.makeText(this, "You're on the latest version (v" + Updater.versionName(this) + ")",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "update-check").start();
    }

    private void askInstallPermission() {
        new AlertDialog.Builder(this)
                .setTitle("Allow updates")
                .setMessage("Android needs your OK, once, for TV Relay to install its own updates. "
                        + "On the next screen, turn on TV Relay, then come back and press Get again.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        showMessage("Allow updates", "This TV has no shortcut to that setting. Look in "
                                + "Settings > Apps > Special app access > Install unknown apps, and turn on TV Relay.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void install(Updater.Release release) {
        updating = true;
        update.setText("Updating…");
        new Thread(() -> {
            try {
                Updater.downloadAndInstall(this, release, percent ->
                        runOnUiThread(() -> update.setText(percent + "%")));
                runOnUiThread(() -> update.setText("Installing…"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updating = false;
                    renderUpdate();
                    showMessage("Update failed", String.valueOf(e.getMessage()));
                });
            }
        }, "update-install").start();
    }

    private void showMessage(String title, String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
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

    /** Port and username are prefilled with Kodi's defaults, so usually only the password needs typing. */
    private void showKodiLogin() {
        Settings settings = Settings.load(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText port = field(form, "Port", String.valueOf(settings.kodiPort), InputType.TYPE_CLASS_NUMBER);
        EditText user = field(form, "Username", settings.kodiUser, InputType.TYPE_CLASS_TEXT);
        EditText pass = field(form, "Password", settings.kodiPass,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(this)
                .setTitle("Kodi login")
                .setMessage("In Kodi: Settings > Services > Control > Allow remote control via HTTP. "
                        + "Enter the same details here. Only needed for Kodi lip sync.")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        settings.kodiPort = Integer.parseInt(port.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        settings.kodiPort = 8080;
                    }
                    settings.kodiUser = user.getText().toString().trim();
                    settings.kodiPass = pass.getText().toString();
                    settings.save(this);
                })
                .setNegativeButton("Cancel", null)
                .show();
        pass.requestFocus();
    }

    private EditText field(LinearLayout form, String label, String value, int inputType) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(16);
        form.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(inputType);
        e.setSingleLine(true);
        form.addView(e);
        return e;
    }
}
