package com.joz.tvrelay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
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
        kodiStatus = findViewById(R.id.kodiStatus);
        hint = findViewById(R.id.hint);

        toggle.setOnClickListener(v -> {
            if (SenderService.running) {
                stopService(new Intent(this, SenderService.class));
            } else {
                requestCapture();
            }
        });
        findViewById(R.id.kodi).setOnClickListener(v -> showKodiLogin());

        for (int id : new int[]{R.id.toggle, R.id.kodi}) {
            findViewById(id).setOnFocusChangeListener((v, focused) ->
                    v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                            .setDuration(120).start());
        }
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
        kodiStatus.setText(on ? KodiSync.status : "");
        // Not worth a line on the TV for people who don't use Kodi.
        int k = KodiSync.state;
        boolean quiet = k == Protocol.KODI_STATE_OFF || k == Protocol.KODI_STATE_MISSING
                || (k == Protocol.KODI_STATE_UNREACHABLE && !KodiSync.syncing);
        kodiStatus.setVisibility(on && !quiet ? View.VISIBLE : View.GONE);
        hint.setText(on
                ? "Streaming keeps going in the background. Press Home or Back to return to what you were watching."
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
                    testKodiLogin();
                })
                .setNegativeButton("Cancel", null)
                .show();
        pass.requestFocus();
    }

    /** Saving the login checks it straight away, so mistakes show up here and not mid-movie. */
    private void testKodiLogin() {
        AlertDialog checking = new AlertDialog.Builder(this)
                .setTitle("Kodi login")
                .setMessage("Checking the connection to Kodi…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String result = KodiSync.testLogin(this);
            runOnUiThread(() -> {
                checking.dismiss();
                if (isFinishing()) return;
                boolean ok = result.startsWith("Connected");
                AlertDialog.Builder b = new AlertDialog.Builder(this)
                        .setTitle(ok ? "Kodi connected" : "Kodi not connected")
                        .setMessage(result)
                        .setPositiveButton("OK", null);
                if (!ok) b.setNegativeButton("Edit login", (d, w) -> showKodiLogin());
                b.show();
            });
        }, "kodi-test").start();
    }

    private EditText field(LinearLayout form, String label, String value, int inputType) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(16);
        form.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true); // before setInputType: it would replace the password masking
        e.setInputType(inputType);
        e.setText(value);
        form.addView(e);
        return e;
    }
}
