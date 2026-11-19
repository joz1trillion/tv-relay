package com.joz.tvrelay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Phone screen: Listen (controls, stats, graph), Settings, and Kodi sync tabs. */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final int REQ_STREAM_PERMS = 2;
    private static final int REQ_CAPTURE = 3;
    private static final int ACCENT = Color.rgb(0x3D, 0x8B, 0xFF);
    private static final int GOOD = Color.rgb(0x34, 0xC7, 0x59);
    private static final int WARN = Color.rgb(0xFF, 0x9F, 0x0A);
    static final String KODI_GUIDE = "https://github.com/joz1trillion/tv-relay/blob/main/docs/kodi.md";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView kodiStatusView;
    private TextView kodiStatusDetail;
    private GradientDrawable kodiStatusCard;
    private String kodiTestResult;   // last Test connection answer, shown while not listening
    private boolean kodiTestOk;
    // Kodi mode and adjustment appear on the Kodi tab and, optionally, the Listen tab.
    private final List<Spinner> kodiModeSpinners = new ArrayList<>();
    private final List<SeekBar> kodiSliders = new ArrayList<>();
    private final List<TextView> kodiAdjustViews = new ArrayList<>();
    private LinearLayout kodiQuick;
    private TextView tvStateView;
    private TextView tvPicker;
    private EditText hostField;    // Connect directly, kept in step with the TV picker
    private Switch keepTvSwitch;
    private LatencyGraph graph;
    private TextView pipView;
    private TextView[] tabs;
    private View[] pages;
    private Settings settings;
    private boolean inPip;
    private boolean wide;         // two panes: Listen on the left, Settings / Kodi sync on the right
    private int currentTab;
    private int rightTab = 1;     // last tab shown in the right pane
    private UpdateUi updates;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (inPip) {
                pipView.setText(describePip());
            } else {
                statusView.setText(describe());
                showTvState();
                showKodiStatus();
                graph.invalidate();
            }
            updatePipParams();
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
        if (Settings.tvScreen(this)) {
            startActivity(new Intent(this, TvActivity.class)); // the TV has its own remote-friendly screen
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        settings = Settings.load(this);
        updates = new UpdateUi(this, findViewById(R.id.version));
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

        findViewById(R.id.receive).setOnClickListener(v -> listen());
        findViewById(R.id.stop).setOnClickListener(v -> stopListening());

        buildSettings(findViewById(R.id.settingsList));
        buildKodi(findViewById(R.id.kodiList));
        buildConnectDirectly(findViewById(R.id.listenExtras));
        tvStateView = findViewById(R.id.tvState);
        tvStateView.setOnClickListener(v -> pickTv());
        tvPicker = findViewById(R.id.tvPicker);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(dp(10));
        box.setStroke(dp(1), ACCENT);
        tvPicker.setBackground(box);
        tvPicker.setPadding(dp(14), dp(10), dp(14), dp(10));
        tvPicker.setOnClickListener(v -> pickTv());
        findViewById(R.id.stream).setOnClickListener(v -> streamFromHere());
        kodiQuick = findViewById(R.id.kodiQuick);
        buildKodiCompact(kodiQuick);
        applyPanes(getResources().getConfiguration());
        applyKodiEnabled();
        applyStreamButton();
    }

    /**
     * Stream (send this device's own audio) shows on a device set to Client mode, where it's the
     * point (a TV box that should stream, say), and otherwise with Advanced settings on.
     */
    private void applyStreamButton() {
        boolean client = Settings.load(this).screenMode == Settings.SCREEN_CLIENT;
        findViewById(R.id.stream).setVisibility(client || settings.showAdvanced ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        if (updates != null) updates.destroy();
        super.onDestroy();
    }

    private void showTab(int index) {
        if (wide && index == 0) index = rightTab; // Listen is always on screen in its own pane
        currentTab = index;
        if (index != 0) rightTab = index;
        if (kodiQuick != null) syncKodiControls();
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == index;
            pages[i].setVisibility(on || (wide && i == 0) ? View.VISIBLE : View.GONE);
            tabs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            tabs[i].setTextColor(on ? ACCENT : statusView.getCurrentTextColor());
        }
    }

    /**
     * Wide screens (an unfolded Fold, tablets, landscape) put the Listen page in a pane of its own
     * beside the other tabs, so settings can be changed while watching the graph. Runs again on
     * fold and unfold; the activity isn't recreated, so playback and scroll positions carry over.
     */
    private void applyPanes(Configuration config) {
        boolean nowWide = config.screenWidthDp >= 600;
        View listen = pages[0];
        ViewGroup left = findViewById(R.id.leftPane);
        ViewGroup frame = findViewById(R.id.pagesFrame);
        ViewGroup parent = (ViewGroup) listen.getParent();
        if (nowWide && parent != left) {
            parent.removeView(listen);
            left.addView(listen);
        } else if (!nowWide && parent != frame) {
            parent.removeView(listen);
            frame.addView(listen, 0);
        }
        wide = nowWide;
        left.setVisibility(wide ? View.VISIBLE : View.GONE);
        findViewById(R.id.paneDivider).setVisibility(wide ? View.VISIBLE : View.GONE);
        tabs[0].setVisibility(wide ? View.GONE : View.VISIBLE);
        ViewGroup.LayoutParams lp = graph.getLayoutParams();
        lp.height = dp(wide ? 260 : 180); // more room for the graph when it has a pane to itself
        graph.setLayoutParams(lp);
        showTab(currentTab);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (pages != null) applyPanes(newConfig);
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
        choice(list, "Catch-up after a delay spike", R.string.info_catchup, Settings.CATCHUP_LABELS,
                settings.catchup, v -> settings.catchup = v, true);

        // Everything else is tucked away behind a section header that is itself the switch.
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(settings.showAdvanced ? View.VISIBLE : View.GONE);
        Switch advSwitch = toggle(list, "Advanced settings", R.string.info_show_advanced, settings.showAdvanced, v -> {
            settings.showAdvanced = v;
            adv.setVisibility(v ? View.VISIBLE : View.GONE);
            applyStreamButton();
        }, false);
        styleAsHeader((LinearLayout) advSwitch.getParent());
        list.addView(adv);
        list = adv;

        toggle(list, "Kodi sync", R.string.info_kodi_enabled, settings.kodiEnabled, v -> {
            settings.kodiEnabled = v;
            applyKodiEnabled();
        }, false);
        toggle(list, "Keep screen on", R.string.info_keep_screen_on, settings.keepScreenOn,
                v -> settings.keepScreenOn = v, false);
        toggle(list, "Mini window", R.string.info_pip, settings.pip, v -> {
            settings.pip = v;
            if (v && !pipAllowed()) {
                new AlertDialog.Builder(this)
                        .setTitle("Allow the mini window")
                        .setMessage("Picture-in-picture is switched off for TV Relay in Android's settings. "
                                + "Turn it on there for the mini window to work.")
                        .setPositiveButton("Open settings", (d, w) -> {
                            try {
                                startActivity(new Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Exception e) {
                                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())));
                            }
                        })
                        .setNegativeButton("Not now", null)
                        .show();
            }
        }, false);
        keepTvSwitch = toggle(list, "Keep TV streaming when I stop", R.string.info_keep_tv, settings.keepTvStreaming,
                v -> settings.keepTvStreaming = v, false);

        // For devices Android guesses wrong: a TV box that shows the phone screen, or a tablet to stream from.
        header(list, "Device mode");
        list.addView(ModeSwitch.build(this, 15));

        header(list, "Experimental");
        toggle(list, "Send every packet twice", R.string.info_send_twice, settings.sendTwice,
                v -> settings.sendTwice = v, true);
        toggle(list, "Exclusive mode", R.string.info_exclusive, settings.exclusive,
                v -> settings.exclusive = v, true);

    }

    // ---- Listen tab extras ----

    /** A "▸ title" row that shows or hides the returned box underneath it. */
    private LinearLayout dropdown(LinearLayout list, String title, boolean open) {
        TextView toggle = text(list, "", 16);
        toggle.setPadding(0, dp(12), 0, dp(8));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        list.addView(body);
        Runnable label = () -> toggle.setText((body.getVisibility() == View.VISIBLE ? "▾ " : "▸ ") + title);
        label.run();
        toggle.setOnClickListener(v -> {
            body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            label.run();
        });
        return body;
    }

    /** Collapsed by default: only needed when the phone can't find the TV by itself. */
    private void buildConnectDirectly(LinearLayout box) {
        LinearLayout body = dropdown(box, "Connect directly", !settings.host.isEmpty());

        TextView help = text(body, getString(R.string.info_host), 13);
        help.setAlpha(0.7f);
        EditText host = new EditText(this);
        hostField = host;
        host.setHint("TV IP address, e.g. 192.168.1.50");
        host.setSingleLine(true);
        host.setText(settings.host);
        host.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                settings.host = s.toString().trim();
                settings.save(MainActivity.this);
                TvLink.setPreferred(settings.host);
            }
        });
        body.addView(host);
    }

    // ---- Kodi tab ----

    private void buildKodi(LinearLayout list) {
        TextView intro = text(list, "Optional, only if you watch in Kodi on the TV: lets you adjust Kodi's audio "
                + "delay so the picture lines up with the sound on this phone.", 14);
        intro.setAlpha(0.8f);
        intro.setPadding(0, 0, 0, dp(12));

        // Connection card: tinted green/orange/grey to match the dot.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        kodiStatusCard = new GradientDrawable();
        kodiStatusCard.setCornerRadius(dp(12));
        card.setBackground(kodiStatusCard);
        list.addView(card);
        kodiStatusView = text(card, "", 18);
        kodiStatusView.setTypeface(Typeface.DEFAULT_BOLD);
        kodiStatusDetail = text(card, "", 14);
        kodiStatusDetail.setAlpha(0.8f);

        buildKodiSetup(list);
        buildKodiLogin(list);

        header(list, "Lip sync");
        buildKodiControls(list);
        toggle(list, "Flip direction", R.string.info_kodi_flip, settings.kodiFlip, v -> {
            settings.kodiFlip = v;
            ReceiverService.kodiFlip = v;
        }, false);
        toggle(list, "Show on Listen tab", R.string.info_kodi_on_listen, settings.kodiOnListen, v -> {
            settings.kodiOnListen = v;
            kodiQuick.setVisibility(v && settings.kodiEnabled ? View.VISIBLE : View.GONE);
        }, false);
    }

    /** Mode, adjustment slider and nudges. Built once per tab that shows them; kept in step on tab switch. */
    private void buildKodiControls(LinearLayout list) {
        kodiModeSpinners.add(choice(list, "Mode", R.string.info_kodi_mode, Settings.KODI_LABELS,
                Settings.kodiModePosition(settings.kodiMode), v -> setKodiMode(Settings.KODI_MODES[v]), false));

        label(list, "Adjustment", R.string.info_kodi_adjust);
        TextView value = text(list, "", 28);
        value.setGravity(Gravity.CENTER);
        kodiAdjustViews.add(value);
        SeekBar slider = new SeekBar(this);
        int steps = (Settings.KODI_ADJUST_MAX_MS - Settings.KODI_ADJUST_MIN_MS) / Settings.KODI_ADJUST_STEP_MS;
        slider.setMax(steps);
        slider.setPadding(dp(16), dp(12), dp(16), dp(12));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean dragging;

            // While dragging only the number moves; Kodi gets the value when you let go, so it
            // catches up once instead of on every step. Keys (no drag) apply each press.
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int ms = Settings.KODI_ADJUST_MIN_MS + progress * Settings.KODI_ADJUST_STEP_MS;
                if (dragging) previewKodiAdjust(ms); else setKodiAdjust(ms);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                setKodiAdjust(Settings.KODI_ADJUST_MIN_MS + bar.getProgress() * Settings.KODI_ADJUST_STEP_MS);
            }
        });
        list.addView(slider);
        kodiSliders.add(slider);

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
    }

    /** Kodi sync off hides the Kodi tab and Listen-tab controls, and stops touching Kodi. */
    private void applyKodiEnabled() {
        boolean on = settings.kodiEnabled;
        ReceiverService.kodiMode = on ? settings.kodiMode : Protocol.KODI_OFF;
        tabs[2].setVisibility(on ? View.VISIBLE : View.GONE);
        kodiQuick.setVisibility(on && settings.kodiOnListen ? View.VISIBLE : View.GONE);
        if (!on && currentTab == 2) showTab(1);
    }

    private void setKodiMode(int mode) {
        settings.kodiMode = mode;
        ReceiverService.kodiMode = settings.kodiEnabled ? mode : Protocol.KODI_OFF;
    }

    /** Two-row version of the Kodi controls for the Listen tab: mode and value, then − slider +. */
    private void buildKodiCompact(LinearLayout list) {
        list.setPadding(0, dp(16), 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Kodi");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);
        Spinner mode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, Settings.KODI_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(adapter);
        mode.setSelection(Settings.kodiModePosition(settings.kodiMode), false);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (Settings.KODI_MODES[position] == settings.kodiMode) return;
                setKodiMode(Settings.KODI_MODES[position]);
                settings.save(MainActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        top.addView(mode, new LinearLayout.LayoutParams(0, dp(44), 1));
        kodiModeSpinners.add(mode);
        TextView value = new TextView(this);
        value.setTextSize(20);
        value.setPadding(dp(8), 0, 0, 0);
        value.setContentDescription("Adjustment; tap to reset");
        value.setOnClickListener(v -> setKodiAdjust(0));
        top.addView(value);
        kodiAdjustViews.add(value);
        list.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(smallNudge("−5", -Settings.KODI_ADJUST_STEP_MS));
        SeekBar slider = new SeekBar(this);
        slider.setMax((Settings.KODI_ADJUST_MAX_MS - Settings.KODI_ADJUST_MIN_MS) / Settings.KODI_ADJUST_STEP_MS);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean dragging;

            // While dragging only the number moves; Kodi gets the value when you let go, so it
            // catches up once instead of on every step. Keys (no drag) apply each press.
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int ms = Settings.KODI_ADJUST_MIN_MS + progress * Settings.KODI_ADJUST_STEP_MS;
                if (dragging) previewKodiAdjust(ms); else setKodiAdjust(ms);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                setKodiAdjust(Settings.KODI_ADJUST_MIN_MS + bar.getProgress() * Settings.KODI_ADJUST_STEP_MS);
            }
        });
        bottom.addView(slider, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        kodiSliders.add(slider);
        bottom.addView(smallNudge("+5", Settings.KODI_ADJUST_STEP_MS));
        list.addView(bottom);
        showKodiAdjust();
    }

    private Button smallNudge(String text, int delta) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setOnClickListener(v -> setKodiAdjust(settings.kodiAdjustMs + delta));
        return b;
    }

    /** The other tab's copy of the Kodi controls may be out of date. */
    private void syncKodiControls() {
        int position = Settings.kodiModePosition(settings.kodiMode);
        for (Spinner sp : kodiModeSpinners) {
            if (sp.getSelectedItemPosition() != position) sp.setSelection(position, false);
        }
        showKodiAdjust();
    }

    /** What Kodi sync is and how to set it up, collapsed. The full guide is on GitHub. */
    private void buildKodiSetup(LinearLayout list) {
        LinearLayout body = dropdown(list, "How to set it up", false);

        TextView steps = text(body, "1. On the TV, install Kodi (version 21 or newer) from the Play Store.\n"
                + "2. In Kodi: Settings → Services → Control → turn on \"Allow remote control via HTTP\" "
                + "and set a password.\n"
                + "3. Here: open Kodi login, enter that password and tap Test connection.\n"
                + "4. Tap Listen, set Mode to Automatic, and play something in Kodi.", 14);
        steps.setLineSpacing(dp(4), 1f);
        TextView link = text(body, "Full guide, including Plex in Kodi →", 14);
        link.setTextColor(ACCENT);
        link.setPadding(0, dp(10), 0, dp(10));
        link.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(KODI_GUIDE)));
            } catch (Exception ignored) {
                // no browser
            }
        });
    }

    /** Kodi's HTTP login, typed here rather than with the TV remote, and a button to try it. */
    private void buildKodiLogin(LinearLayout parent) {
        LinearLayout list = dropdown(parent, "Kodi login", false);
        loginField(list, "Kodi IP (optional; blank = the TV you stream from)", settings.kodiHost,
                InputType.TYPE_CLASS_TEXT, v -> settings.kodiHost = v);
        loginField(list, "Port", String.valueOf(settings.kodiPort), InputType.TYPE_CLASS_NUMBER, v -> {
            try {
                settings.kodiPort = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                settings.kodiPort = 8080;
            }
        });
        loginField(list, "Username", settings.kodiUser, InputType.TYPE_CLASS_TEXT, v -> settings.kodiUser = v);
        loginField(list, "Password", settings.kodiPass,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, v -> settings.kodiPass = v);
        Button test = new Button(this);
        test.setText("Test connection");
        test.setAllCaps(false);
        test.setOnClickListener(v -> testKodi(test));
        list.addView(test);
    }

    private interface TextSetter {
        void set(String value);
    }

    private void loginField(LinearLayout list, String title, String value, int inputType, TextSetter setter) {
        TextView l = text(list, title, 14);
        l.setPadding(0, dp(8), 0, 0);
        EditText e = new EditText(this);
        e.setSingleLine(true); // before setInputType: it would replace the password masking
        e.setInputType(inputType);
        e.setText(value);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                String v = s.toString();
                setter.set(inputType == InputType.TYPE_CLASS_TEXT ? v.trim() : v);
                settings.save(MainActivity.this); // the receiver's Kodi sync rereads it each second
            }
        });
        list.addView(e);
    }

    private void testKodi(Button button) {
        String host = ReceiverService.tvHost;
        if (host == null) host = !settings.host.isEmpty() ? settings.host : Settings.load(this).lastTvHost;
        if (host.isEmpty() && settings.kodiHost.isEmpty()) {
            kodiTestOk = false;
            kodiTestResult = "The phone doesn't know where the TV is yet. Tap Listen once, or enter the TV's IP "
                    + "under Connect directly (it's shown on the TV under Start streaming).";
            showKodiStatus();
            return;
        }
        button.setEnabled(false);
        button.setText("Testing…");
        String tv = host;
        new Thread(() -> {
            String result = KodiSync.testLogin(this, tv);
            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("Test connection");
                kodiTestOk = result.startsWith("Connected");
                kodiTestResult = result;
                showKodiStatus();
            });
        }, "kodi-test").start();
    }

    /**
     * "● Kodi connected" in green, orange when something's wrong, grey when unknown. Live state
     * while listening and the TV is found; otherwise the last Test connection result.
     */
    private void showKodiStatus() {
        int state = ReceiverService.running ? KodiSync.state : Protocol.KODI_STATE_OFF;
        boolean live = state != Protocol.KODI_STATE_OFF;
        boolean connected = live ? state == Protocol.KODI_STATE_IDLE || state == Protocol.KODI_STATE_APPLIED
                || state == Protocol.KODI_STATE_READY : kodiTestResult != null && kodiTestOk;
        boolean problem = live ? state == Protocol.KODI_STATE_UNREACHABLE || state == Protocol.KODI_STATE_AUTH
                : kodiTestResult != null && !kodiTestOk;
        int color = connected ? GOOD : problem ? WARN : Color.GRAY;
        SpannableStringBuilder sb = new SpannableStringBuilder("● ");
        sb.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(connected ? "Kodi connected" : problem ? "Kodi not connected" : "Kodi: not checked yet");
        kodiStatusView.setText(sb);
        kodiStatusDetail.setText(live ? describeKodi() : kodiTestResult != null ? kodiTestResult
                : "Tap Test connection below, or Listen.");
        kodiStatusCard.setColor(Color.argb(0x26, Color.red(color), Color.green(color), Color.blue(color)));
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
        showKodiAdjust();
    }

    private void previewKodiAdjust(int ms) {
        for (TextView t : kodiAdjustViews) t.setText((ms > 0 ? "+" : "") + ms + " ms");
    }

    private void showKodiAdjust() {
        int v = settings.kodiAdjustMs;
        for (TextView t : kodiAdjustViews) t.setText((v > 0 ? "+" : "") + v + " ms");
        for (SeekBar sb : kodiSliders) {
            sb.setProgress((v - Settings.KODI_ADJUST_MIN_MS) / Settings.KODI_ADJUST_STEP_MS);
        }
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

    /** Makes a label row look like a section header (blue, bold), for a header with its own switch. */
    private void styleAsHeader(LinearLayout row) {
        TextView t = (TextView) row.getChildAt(0);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ACCENT);
        row.setPadding(0, dp(24), 0, 0);
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

    private Spinner choice(LinearLayout list, String title, int infoRes, String[] labels, int selected,
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
        return spinner;
    }

    private Switch toggle(LinearLayout list, String title, int infoRes, boolean checked, BoolSetter setter,
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
        return sw;
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
        TvLink.start(settings.host, Settings.load(this).lastTvHost);
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        TvLink.stop();
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
        if (Build.VERSION.SDK_INT >= 31) return; // auto-enter handles it, see updatePipParams()
        if (!settings.pip || !ReceiverService.running) return;
        try {
            enterPictureInPictureMode(pipParams(false));
        } catch (IllegalStateException ignored) {
            // PiP turned off for this app in system settings
        }
    }

    private PictureInPictureParams pipParams(boolean autoEnter) {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9));
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(false);
        return b.build();
    }

    private Boolean pipArmed;  // what the system was last told, so it's only told on changes

    /**
     * Android 12+ shrinks the app to the mini window by itself when you go home, if told in advance.
     * Entering at the last moment from onUserLeaveHint often loses the race with gesture navigation.
     */
    private void updatePipParams() {
        if (Build.VERSION.SDK_INT < 31) return;
        boolean want = settings.pip && ReceiverService.running;
        if (pipArmed != null && pipArmed == want) return;
        pipArmed = want;
        try {
            setPictureInPictureParams(pipParams(want));
        } catch (IllegalStateException ignored) {
            // PiP turned off for this app in system settings
        }
    }

    /** True unless picture-in-picture is switched off for TV Relay in Android's settings. */
    private boolean pipAllowed() {
        android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
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

    /** Stop listening; if the TV is still streaming, offer to stop it too (unless told to keep it). */
    private void stopListening() {
        stopService(new Intent(this, ReceiverService.class));
        if (SenderService.running) { // this device was streaming too (Stream button)
            stopService(new Intent(this, SenderService.class));
            if (!Settings.tvScreen(this)) stopService(new Intent(this, LinkService.class));
        }
        if (!TvLink.found() || !TvLink.streaming || settings.keepTvStreaming) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        CheckBox keep = new CheckBox(this);
        keep.setText("Always keep the TV streaming, don't ask again");
        box.addView(keep);
        TextView note = text(box, "You can change this later in Settings.", 13);
        note.setAlpha(0.7f);
        new AlertDialog.Builder(this)
                .setTitle("Stop the TV too?")
                .setMessage("The TV is still capturing audio. Stopping it here means you'll need to "
                        + "allow the recording prompt on the TV again next time.")
                .setView(box)
                .setPositiveButton("Stop TV", (d, w) -> {
                    rememberKeep(keep.isChecked());
                    TvLink.requestStop();
                })
                .setNegativeButton("Keep it streaming", (d, w) -> rememberKeep(keep.isChecked()))
                .show();
    }

    private void rememberKeep(boolean keep) {
        if (!keep) return;
        settings.keepTvStreaming = true;
        settings.save(this);
        if (keepTvSwitch != null) keepTvSwitch.setChecked(true);
    }

    /** Listen also asks the TV to start streaming, if it's found but not streaming yet. */
    private void listen() {
        if (TvLink.found() && !TvLink.streaming) {
            if (TvLink.screenOpen || TvLink.canPopUp) {
                TvLink.requestStart();
                Toast.makeText(this, "Press Start now on the TV", Toast.LENGTH_LONG).show();
            } else if (settings.startTipShown) {
                Toast.makeText(this, "Open TV Relay on the TV and press Start streaming", Toast.LENGTH_LONG).show();
            }
            if (!TvLink.canPopUp && !settings.startTipShown) {
                settings.startTipShown = true;
                settings.save(this);
                new AlertDialog.Builder(this)
                        .setTitle("Start the TV from your phone")
                        .setMessage("The TV isn't streaming yet. To let Listen start it any time, even while "
                                + "you're watching something, turn on automatic start on the TV:\n\n"
                                + "TV Relay on the TV → Start mode → Open setting → turn TV Relay on.\n\n"
                                + "Until then, press Start streaming on the TV.")
                        .setPositiveButton("Got it", null)
                        .show();
            }
        }
        requestNotificationsThenListen();
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
        if (requestCode == REQ_STREAM_PERMS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                streamFromHere();
            } else {
                Toast.makeText(this, "Streaming needs the audio recording permission", Toast.LENGTH_LONG).show();
            }
            return;
        }
        startReceiver(); // notifications are optional
    }

    /**
     * Stream button (Advanced settings): this device streams its own audio like a TV, for a device the app took for a
     * phone, or to send a phone's audio to another device. Same capture prompt as on the TV.
     */
    private void streamFromHere() {
        if (SenderService.running) {
            Toast.makeText(this, "Already streaming from this device", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_STREAM_PERMS);
            return;
        }
        startActivityForResult(getSystemService(android.media.projection.MediaProjectionManager.class)
                .createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Recording wasn't allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        startForegroundService(new Intent(this, SenderService.class)
                .putExtra(SenderService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(SenderService.EXTRA_RESULT_DATA, data));
        startForegroundService(new Intent(this, LinkService.class)); // so other devices can find this one
    }

    // ---- status text ----

    /** With more than one TV on the network, choose which one to listen to. */
    private void pickTv() {
        List<TvLink.Tv> tvs = TvLink.all();
        String[] items = new String[tvs.size() + 1];
        items[0] = "Automatic (the first TV that answers)" + (settings.host.isEmpty() ? "  ✓" : "");
        for (int i = 0; i < tvs.size(); i++) {
            TvLink.Tv tv = tvs.get(i);
            items[i + 1] = tv.label() + (tv.streaming ? " · streaming" : "")
                    + (tv.address.equals(settings.host) ? "  ✓" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(tvs.isEmpty() ? "No TVs found yet" : "Listen to which TV?")
                .setItems(items, (d, which) -> {
                    String host = which == 0 ? "" : tvs.get(which - 1).address;
                    if (hostField != null) hostField.setText(host); // saves it and tells TvLink
                    restartReceiverIfRunning();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "● TV found · not streaming" and friends, above the stats. Works without listening. */
    private void showTvState() {
        boolean playing = ReceiverService.running && ReceiverService.status.startsWith("Playing");
        boolean found = TvLink.found();
        int color;
        String text;
        if (playing) {
            color = GOOD;
            text = "Connected · streaming";
        } else if (found && TvLink.streaming) {
            color = GOOD;
            text = "TV found · streaming, tap Listen";
        } else if (found) {
            color = ACCENT;
            text = TvLink.screenOpen ? "TV found · not streaming, tap Listen to start"
                    : "TV found · not streaming. Start it on the TV";
        } else {
            color = Color.GRAY;
            text = "TV not found · open TV Relay on the TV";
        }
        SpannableStringBuilder sb = new SpannableStringBuilder("● ");
        sb.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(text);
        if (found && TvLink.versionCode != Updater.versionCode(this)) {
            sb.append("\nThe TV has a different TV Relay version: update both to the same one.");
        }
        tvStateView.setText(sb);

        // The selector: which TV, and that it's a choice.
        int count = TvLink.all().size();
        String which = !found ? (count == 0 ? "No TV found yet" : "Looking…")
                : TvLink.name != null ? TvLink.name + "  ·  " + TvLink.address : TvLink.address;
        SpannableStringBuilder p = new SpannableStringBuilder("TV  ");
        p.setSpan(new ForegroundColorSpan(ACCENT), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        p.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        p.append(which);
        if (settings.host.isEmpty() && found) p.append("  (automatic)");
        p.append(count > 1 ? "   ▾ " + count + " TVs" : "   ▾");
        tvPicker.setText(p);
    }

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
        if (!ReceiverService.running) return "Tap Listen to check the connection to Kodi";
        switch (KodiSync.state) {
            case Protocol.KODI_STATE_READY: return "Lip sync is off: pick a mode below";
            case Protocol.KODI_STATE_IDLE: return "Nothing playing in Kodi";
            case Protocol.KODI_STATE_APPLIED:
                return "Kodi audio delay " + KodiSync.appliedMs + " ms"
                        + (KodiSync.pendingMs != Integer.MIN_VALUE
                        ? " · " + KodiSync.pendingMs + " ms at the next pause" : "");
            case Protocol.KODI_STATE_UNREACHABLE: return "Can't reach Kodi: is it open, with HTTP remote control on?";
            case Protocol.KODI_STATE_AUTH: return "Kodi says the username or password is wrong";
            default: return "Waiting for the TV…";
        }
    }

    private String describe() {
        StringBuilder sb = new StringBuilder();
        if (SenderService.running) {
            int n = SenderService.clientCount;
            sb.append("Streaming from this device to ").append(n).append(n == 1 ? " listener" : " listeners")
                    .append(SenderService.format.isEmpty() ? "" : " · " + SenderService.format).append("\n");
        }
        sb.append(ReceiverService.status);
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
                    .append(", ").append(ReceiverService.outputUnderruns).append(" glitches");
            if (settings.kodiEnabled && settings.kodiMode != Protocol.KODI_OFF) sb.append("\n").append(describeKodi());
        }
        return sb.toString();
    }
}
