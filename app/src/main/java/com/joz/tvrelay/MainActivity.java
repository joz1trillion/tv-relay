package com.joz.tvrelay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/** Phone screen: Listen (controls, stats, graph), Settings, and Kodi sync tabs. */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final int ACCENT = Color.rgb(0x3D, 0x8B, 0xFF);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView kodiStatusView;
    private TextView kodiAdjustView;
    private SeekBar kodiSlider;
    private LatencyGraph graph;
    private TextView pipView;
    private TextView[] tabs;
    private View[] pages;
    private Settings settings;
    private boolean inPip;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (inPip) {
                pipView.setText(describePip());
            } else {
                statusView.setText(describe());
                kodiStatusView.setText(describeKodi());
                graph.invalidate();
            }
            // Android only keeps Wi-Fi in low-latency mode while the screen is on and we're in front.
            if (settings.keepScreenOn && ReceiverService.running) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSystemService(UiModeManager.class).getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            startActivity(new Intent(this, TvActivity.class)); // the TV has its own remote-friendly screen
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        settings = Settings.load(this);
        statusView = findViewById(R.id.status);
        graph = findViewById(R.id.graph);
        pipView = findViewById(R.id.pipView);

        tabs = new TextView[]{findViewById(R.id.tabListen), findViewById(R.id.tabSettings), findViewById(R.id.tabKodi)};
        pages = new View[]{findViewById(R.id.pageListen), findViewById(R.id.pageSettings), findViewById(R.id.pageKodi)};
        for (int i = 0; i < tabs.length; i++) {
            int page = i;
            tabs[i].setOnClickListener(v -> showTab(page));
        }
        showTab(0);

        findViewById(R.id.receive).setOnClickListener(v -> requestNotificationsThenListen());
        findViewById(R.id.stop).setOnClickListener(v -> stopService(new Intent(this, ReceiverService.class)));

        buildSettings(findViewById(R.id.settingsList));
        buildKodi(findViewById(R.id.kodiList));
    }

    private void showTab(int index) {
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == index;
            pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            tabs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            tabs[i].setTextColor(on ? ACCENT : statusView.getCurrentTextColor());
        }
    }

    // ---- Settings tab ----

    private void buildSettings(LinearLayout list) {
        header(list, "Stream");
        choice(list, "Format", R.string.info_format, Settings.FORMAT_LABELS, settings.format,
                v -> settings.format = v, true);
        choice(list, "Opus quality", R.string.info_quality, Settings.QUALITY_LABELS, settings.quality,
                v -> settings.quality = v, true);
        choice(list, "Channels", R.string.info_channels, Settings.CHANNEL_LABELS, settings.channels,
                v -> settings.channels = v, true);

        header(list, "Playback");
        choice(list, "Buffer", R.string.info_buffer, Settings.BUFFER_LABELS, settings.buffer,
                v -> settings.buffer = v, true);
        choice(list, "Output path", R.string.info_output, Settings.OUTPUT_LABELS, settings.output,
                v -> settings.output = v, true);
        toggle(list, "Exclusive mode", R.string.info_exclusive, settings.exclusive,
                v -> settings.exclusive = v, true);
        choice(list, "Catch-up after a delay spike", R.string.info_catchup, Settings.CATCHUP_LABELS,
                settings.catchup, v -> settings.catchup = v, true);

        header(list, "Wi-Fi");
        toggle(list, "Send every packet twice", R.string.info_send_twice, settings.sendTwice,
                v -> settings.sendTwice = v, true);
        toggle(list, "Keep screen on", R.string.info_keep_screen_on, settings.keepScreenOn,
                v -> settings.keepScreenOn = v, false);
        toggle(list, "Mini window", R.string.info_pip, settings.pip, v -> settings.pip = v, false);

        header(list, "Connection");
        label(list, "TV IP address", R.string.info_host);
        EditText host = new EditText(this);
        host.setHint("Blank = find it automatically");
        host.setSingleLine(true);
        host.setText(settings.host);
        host.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                settings.host = s.toString().trim();
                settings.save(MainActivity.this);
            }
        });
        list.addView(host);
    }

    // ---- Kodi tab ----

    private void buildKodi(LinearLayout list) {
        TextView intro = text(list, "Makes Kodi on the TV play its audio slightly early, so the sound on this "
                + "phone lines up with the picture. Needs Kodi 21+ on the TV, \"Allow remote control via HTTP\" "
                + "turned on in Kodi, and the Kodi login entered in the TV app.", 15);
        intro.setAlpha(0.8f);

        kodiStatusView = text(list, "", 15);
        kodiStatusView.setTypeface(Typeface.DEFAULT_BOLD);
        kodiStatusView.setPadding(0, dp(12), 0, 0);

        choice(list, "Mode", R.string.info_kodi_mode, Settings.KODI_LABELS, settings.kodiMode, v -> {
            settings.kodiMode = v;
            ReceiverService.kodiMode = v;
        }, false);

        label(list, "Adjustment", R.string.info_kodi_adjust);
        kodiAdjustView = text(list, "", 28);
        kodiAdjustView.setGravity(Gravity.CENTER);
        kodiSlider = new SeekBar(this);
        int steps = (Settings.KODI_ADJUST_MAX_MS - Settings.KODI_ADJUST_MIN_MS) / Settings.KODI_ADJUST_STEP_MS;
        kodiSlider.setMax(steps);
        kodiSlider.setProgress((settings.kodiAdjustMs - Settings.KODI_ADJUST_MIN_MS) / Settings.KODI_ADJUST_STEP_MS);
        kodiSlider.setPadding(dp(16), dp(12), dp(16), dp(12));
        kodiSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) setKodiAdjust(Settings.KODI_ADJUST_MIN_MS + progress * Settings.KODI_ADJUST_STEP_MS);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        list.addView(kodiSlider);

        // Nudge buttons for exact 5 ms steps, which are fiddly to hit by dragging.
        LinearLayout nudges = new LinearLayout(this);
        nudges.addView(nudge("−5 ms", -Settings.KODI_ADJUST_STEP_MS), weighted());
        nudges.addView(nudge("Reset", 0), weighted());
        nudges.addView(nudge("+5 ms", Settings.KODI_ADJUST_STEP_MS), weighted());
        list.addView(nudges);
        TextView scale = text(list, "← sound later          sound earlier →", 13);
        scale.setGravity(Gravity.CENTER);
        scale.setAlpha(0.6f);
        showKodiAdjust();

        toggle(list, "Flip direction", R.string.info_kodi_flip, settings.kodiFlip, v -> {
            settings.kodiFlip = v;
            ReceiverService.kodiFlip = v;
        }, false);
    }

    private Button nudge(String text, int delta) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> setKodiAdjust(delta == 0 ? 0 : settings.kodiAdjustMs + delta));
        return b;
    }

    private void setKodiAdjust(int ms) {
        ms = Math.max(Settings.KODI_ADJUST_MIN_MS, Math.min(Settings.KODI_ADJUST_MAX_MS, ms));
        settings.kodiAdjustMs = ms;
        ReceiverService.kodiAdjustMs = ms; // applied within a second, no restart
        settings.save(this);
        kodiSlider.setProgress((ms - Settings.KODI_ADJUST_MIN_MS) / Settings.KODI_ADJUST_STEP_MS);
        showKodiAdjust();
    }

    private void showKodiAdjust() {
        int v = settings.kodiAdjustMs;
        kodiAdjustView.setText((v > 0 ? "+" : "") + v + " ms");
    }

    // ---- row builders ----

    private interface IntSetter {
        void set(int value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private void header(LinearLayout list, String title) {
        TextView t = text(list, title, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ACCENT);
        t.setPadding(0, list.getChildCount() == 0 ? 0 : dp(24), 0, dp(4));
    }

    /** A label with its ℹ️ button; returns the row so a control can be added beside it. */
    private LinearLayout label(LinearLayout list, String title, int infoRes) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        row.addView(t);
        ImageView info = new ImageView(this);
        info.setImageResource(R.drawable.ic_info);
        info.setImageTintList(ColorStateList.valueOf(t.getCurrentTextColor()));
        info.setAlpha(0.7f);
        info.setScaleType(ImageView.ScaleType.CENTER);
        info.setContentDescription("About " + title);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        info.setBackgroundResource(ripple.resourceId);
        info.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(infoRes)
                .setPositiveButton("Got it", null)
                .show());
        // Right next to the label (48 dp touch target, icon drawn at 20 dp); a spacer pushes any switch to the edge.
        row.addView(info, new LinearLayout.LayoutParams(dp(40), dp(48)));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        list.addView(row);
        return row;
    }

    private void choice(LinearLayout list, String title, int infoRes, String[] labels, int selected,
                        IntSetter setter, boolean restart) {
        label(list, title, infoRes);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.min(selected, labels.length - 1), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            int current = Math.min(selected, labels.length - 1);

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == current) return; // also swallows the initial callback
                current = position;
                setter.set(position);
                settings.save(MainActivity.this);
                if (restart) restartReceiverIfRunning();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        list.addView(spinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void toggle(LinearLayout list, String title, int infoRes, boolean checked, BoolSetter setter,
                        boolean restart) {
        LinearLayout row = label(list, title, infoRes);
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener((b, on) -> {
            setter.set(on);
            settings.save(this);
            if (restart) restartReceiverIfRunning();
        });
        row.addView(sw);
    }

    private TextView text(LinearLayout list, String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        list.addView(t);
        return t;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- lifecycle and picture-in-picture ----

    // Start/stop rather than resume/pause: in picture-in-picture the activity is paused but visible.
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

    /**
     * Shrinking to a mini window keeps this app counted as "in front", which is what Android
     * requires to keep the phone's Wi-Fi in low-latency mode (the screen must still be on).
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!settings.pip || !ReceiverService.running) return;
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).build());
        } catch (IllegalStateException ignored) {
            // PiP turned off for this app in system settings
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(pip, newConfig);
        inPip = pip;
        findViewById(R.id.main).setVisibility(pip ? View.GONE : View.VISIBLE);
        pipView.setVisibility(pip ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    // ---- starting playback ----

    /** Settings are read when the receiver starts, so apply a change by restarting it. */
    private void restartReceiverIfRunning() {
        if (!ReceiverService.running) return;
        stopService(new Intent(this, ReceiverService.class));
        handler.postDelayed(this::startReceiver, 400);
    }

    private void startReceiver() {
        startForegroundService(new Intent(this, ReceiverService.class)
                .putExtra(ReceiverService.EXTRA_HOST, settings.host));
    }

    private void requestNotificationsThenListen() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
        } else {
            startReceiver();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        startReceiver(); // notifications are optional
    }

    // ---- status text ----

    private String describePip() {
        if (!ReceiverService.running) return "Stopped";
        float[] last = ReceiverService.history.latest();
        if (last == null) return ReceiverService.status;
        float total = 0;
        for (float v : last) total += v;
        StringBuilder sb = new StringBuilder().append(Math.round(total)).append(" ms");
        JitterBuffer jb = ReceiverService.jitter;
        if (jb != null) sb.append("\nlost ").append(jb.lost).append(" · underruns ").append(jb.underruns);
        return sb.toString();
    }

    private static String describeKodi() {
        if (!ReceiverService.running) return "Start listening to connect to Kodi on the TV.";
        switch (ReceiverService.kodiState) {
            case Protocol.KODI_STATE_IDLE: return "Kodi on TV: connected, nothing playing";
            case Protocol.KODI_STATE_APPLIED:
                return "Kodi on TV: audio delay " + ReceiverService.kodiAppliedMs + " ms";
            case Protocol.KODI_STATE_UNREACHABLE: return "Kodi on TV: not reachable (enable HTTP remote control)";
            case Protocol.KODI_STATE_AUTH: return "Kodi on TV: wrong username/password (set it in the TV app)";
            default: return "Kodi sync off";
        }
    }

    private String describe() {
        StringBuilder sb = new StringBuilder(ReceiverService.status);
        JitterBuffer jb = ReceiverService.jitter;
        if (ReceiverService.running && jb != null) {
            sb.append("\nbuffer ").append(jb.bufferMs()).append(" ms (target ").append(jb.targetMs())
                    .append(" ms)\nreceived ").append(jb.received)
                    .append("  lost ").append(jb.lost)
                    .append("  late ").append(jb.late)
                    .append("\nunderruns ").append(jb.underruns)
                    .append("  skips ").append(jb.skips)
                    .append("\ncopies dropped ").append(ReceiverService.duplicates)
                    .append("  saved by copy ").append(ReceiverService.recovered)
                    .append("\noutput: ").append(ReceiverService.outputInfo)
                    .append(", ").append(ReceiverService.outputUnderruns).append(" glitches")
                    .append("\n").append(describeKodi());
        }
        return sb.toString();
    }
}
