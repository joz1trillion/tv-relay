package com.joz.tvrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;
import android.widget.TextView;

import java.util.List;

/**
 * The version label in the corner of both screens. Checks GitHub on launch, shows a dot when a
 * newer version is out, and opens the update dialog (install the newest, or pick any version).
 */
final class UpdateUi {
    private static final int ACCENT = Color.rgb(0x3D, 0x8B, 0xFF);

    private final Activity activity;
    private final TextView label;
    private List<Updater.Release> releases;  // null until GitHub answered
    private Updater.Release newest;
    private long checkedAt;                  // elapsedRealtime of the last good check
    private final Runnable onAdvancedChanged;
    private int versionTaps;
    private long lastVersionTap;
    private Toast tapToast;
    private boolean installing;
    private AlertDialog progress;

    private final BroadcastReceiver installStatus = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            finishInstall();
            if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_FAILURE_ABORTED) return;
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (msg != null && msg.contains("DOWNGRADE")) {
                msg = "Android won't put an older version over a newer one on this device. To go back, "
                        + "uninstall TV Relay, then install the older APK from the GitHub releases page.\n\n("
                        + msg + ")";
            }
            message("Update failed", msg != null ? msg : "Android didn't say why.");
        }
    };

    UpdateUi(Activity activity, TextView label, Runnable onAdvancedChanged) {
        this.activity = activity;
        this.label = label;
        this.onAdvancedChanged = onAdvancedChanged;
        label.setFocusable(true);
        label.setClickable(true);
        label.setOnClickListener(v -> openDialog());
        label.setOnFocusChangeListener((v, focused) -> render()); // blue on the blue focus chip is unreadable
        activity.registerReceiver(installStatus, new IntentFilter(Updater.ACTION_INSTALL_STATUS),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        render();
        fetch(null, null);
    }

    void destroy() {
        activity.unregisterReceiver(installStatus);
    }

    private Settings settings() {
        return Settings.load(activity);
    }

    /** "v0.4 pre-release", plus a blue "● v0.5" when there's something newer. */
    private void render() {
        String current = Updater.versionName(activity);
        SpannableStringBuilder sb = new SpannableStringBuilder("v" + current);
        if (Settings.isPrerelease(activity, current)) sb.append(" pre-release");
        if (newest != null) {
            int start = sb.length();
            sb.append("   ● v").append(newest.version).append(" available");
            if (!label.isFocused()) sb.setSpan(new ForegroundColorSpan(ACCENT), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        label.setText(sb);
        label.setContentDescription(sb + ". Updates");
    }

    /**
     * Loads the release list in the background, then runs {@code then} (on the UI thread) if given,
     * or reports the error and runs {@code failed}.
     */
    private void fetch(Runnable then, Runnable failed) {
        new Thread(() -> {
            List<Updater.Release> list = null;
            String error = null;
            try {
                list = Updater.releases();
            } catch (Exception e) {
                error = e.getMessage();
            }
            List<Updater.Release> found = list;
            String failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                if (found != null) {
                    releases = found;
                    checkedAt = SystemClock.elapsedRealtime();
                    for (Updater.Release r : found) Settings.setPrerelease(activity, r.version, r.prerelease);
                    newest = Updater.newest(activity, found, settings().updatePrereleases);
                    render();
                }
                if (then == null) return;
                if (failure != null) {
                    if (failed != null) failed.run();
                    message("Couldn't reach GitHub", failure);
                } else {
                    then.run();
                }
            });
        }, "update-check").start();
    }

    private void openDialog() {
        if (installing) return;
        if (releases == null) {
            fetch(this::openDialog, null);
            return;
        }
        Settings s = settings();
        String current = Updater.versionName(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        TextView text = new TextView(activity);
        text.setTextSize(16);
        text.setText("You have v" + current + (Settings.isPrerelease(activity, current) ? " (pre-release)" : "") + ".\n"
                + (newest != null ? "v" + newest.version + (newest.prerelease ? " (pre-release)" : "")
                + " is available." : "That's the newest version.") + "\nChecked " + checkedAgo() + ".");
        // Reachable with a TV remote for the advanced-mode taps, and visibly so when selected.
        text.setFocusable(true);
        text.setBackgroundResource(R.drawable.tv_chip);
        text.setPadding(dp(8), dp(6), dp(8), dp(6));
        text.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.WHITE, text.getCurrentTextColor()}));
        text.setOnClickListener(v -> onVersionTapped());
        box.addView(text);
        Switch pre = new Switch(activity);
        pre.setText("Include pre-releases");
        pre.setTextSize(16);
        pre.setChecked(s.updatePrereleases);
        pre.setPadding(0, dp(16), 0, 0);
        box.addView(pre);
        TextView preHelp = new TextView(activity);
        preHelp.setText("Pre-releases get new features first but are less tested.");
        preHelp.setAlpha(0.7f);
        box.addView(preHelp);

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setCustomTitle(title())
                .setView(box)
                .setNeutralButton("Other versions", (d, w) -> pickVersion())
                .setNegativeButton("Close", null);
        if (newest != null) b.setPositiveButton("Install v" + newest.version, (d, w) -> install(newest));
        AlertDialog dialog = b.show();
        Button check = dialog.findViewById(R.id.checkNow);
        check.setOnClickListener(v -> {
            check.setText("Checking…");
            check.setEnabled(false);
            fetch(() -> {
                dialog.dismiss();
                openDialog();
            }, () -> {
                check.setText("Check now");
                check.setEnabled(true);
            });
        });
        pre.setOnCheckedChangeListener((v, on) -> {
            Settings changed = settings();
            changed.updatePrereleases = on;
            changed.save(activity);
            newest = Updater.newest(activity, releases, on);
            render();
            dialog.dismiss();
            openDialog();
        });
    }

    /** The dialog title with a "Check now" button in its top-right corner. */
    private LinearLayout title() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(16), dp(12), 0);
        TextView t = new TextView(activity);
        t.setText("TV Relay updates");
        t.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button check = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        check.setId(R.id.checkNow);
        check.setText("Check now");
        check.setAllCaps(false);
        row.addView(check);
        return row;
    }

    private String checkedAgo() {
        long min = (SystemClock.elapsedRealtime() - checkedAt) / 60_000;
        return min < 1 ? "just now" : min == 1 ? "1 minute ago" : min < 60 ? min + " minutes ago"
                : min / 60 == 1 ? "1 hour ago" : min / 60 + " hours ago";
    }

    /** Like Android's build number: 7 taps on the version text make you an advanced user. */
    private void onVersionTapped() {
        Settings s = settings();
        if (s.advancedUnlocked) {
            toast("You're already an advanced user.");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastVersionTap > 2000) versionTaps = 0;
        lastVersionTap = now;
        int left = 7 - ++versionTaps;
        if (left <= 0) {
            s.advancedUnlocked = true;
            s.advanced = true;
            s.save(activity);
            if (onAdvancedChanged != null) onAdvancedChanged.run();
            toast("You are now an advanced user!");
        } else if (versionTaps >= 2) {
            toast("You are " + left + (left == 1 ? " tap" : " taps") + " away from being an advanced user.");
        }
    }

    private void toast(String text) {
        if (tapToast != null) tapToast.cancel();
        tapToast = Toast.makeText(activity, text, Toast.LENGTH_SHORT);
        tapToast.show();
    }

    private void pickVersion() {
        String current = Updater.versionName(activity);
        String[] items = new String[releases.size()];
        for (int i = 0; i < items.length; i++) {
            Updater.Release r = releases.get(i);
            int cmp = Updater.compare(r.version, current);
            items[i] = "v" + r.version + (r.prerelease ? "  pre-release" : "") + "   " + r.date
                    + (cmp == 0 ? "   (installed)" : "");
        }
        new AlertDialog.Builder(activity)
                .setTitle("Install a specific version")
                .setItems(items, (d, which) -> confirm(releases.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirm(Updater.Release r) {
        int cmp = Updater.compare(r.version, Updater.versionName(activity));
        // The TV (Android 12) refuses downgrades; some phones may allow them for this debuggable app.
        String msg = cmp == 0 ? "Reinstall the version you already have?"
                : cmp > 0 ? "Update to v" + r.version + "?"
                : "Go back to v" + r.version + "? Android may refuse to install an older version.";
        new AlertDialog.Builder(activity)
                .setTitle("v" + r.version)
                .setMessage(msg)
                .setPositiveButton("Install", (d, w) -> install(r))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void install(Updater.Release release) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            askInstallPermission();
            return;
        }
        installing = true;
        TextView text = new TextView(activity);
        text.setTextSize(18);
        text.setPadding(dp(24), dp(16), dp(24), dp(8));
        text.setText("Downloading v" + release.version + "…");
        progress = new AlertDialog.Builder(activity).setView(text).setCancelable(false).show();
        new Thread(() -> {
            try {
                Updater.downloadAndInstall(activity, release, percent -> activity.runOnUiThread(() ->
                        text.setText("Downloading v" + release.version + "… " + percent + "%")));
                activity.runOnUiThread(() -> text.setText("Installing v" + release.version + "…"));
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    finishInstall();
                    message("Update failed", String.valueOf(e.getMessage()));
                });
            }
        }, "update-install").start();
    }

    private void finishInstall() {
        installing = false;
        if (progress != null) progress.dismiss();
        progress = null;
    }

    private void askInstallPermission() {
        new AlertDialog.Builder(activity)
                .setTitle("Allow updates")
                .setMessage("Android needs your OK, once, for TV Relay to install its own updates. "
                        + "On the next screen, find TV Relay and turn it on, then come back and try again.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName())));
                    } catch (Exception e) {
                        message("Allow updates", "This device has no shortcut to that setting. Look in "
                                + "Settings > Apps > Special app access > Install unknown apps, and turn on TV Relay.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void message(String title, String message) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
