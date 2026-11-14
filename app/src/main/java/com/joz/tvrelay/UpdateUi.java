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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.LinearLayout;
import android.widget.Switch;
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
                        + "uninstall TV Relay, then install the older APK from the GitHub releases page.";
            }
            message("Update failed", msg != null ? msg : "Android didn't say why.");
        }
    };

    UpdateUi(Activity activity, TextView label) {
        this.activity = activity;
        this.label = label;
        label.setFocusable(true);
        label.setClickable(true);
        label.setOnClickListener(v -> openDialog());
        label.setOnFocusChangeListener((v, focused) -> render()); // blue on the blue focus chip is unreadable
        activity.registerReceiver(installStatus, new IntentFilter(Updater.ACTION_INSTALL_STATUS),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        render();
        fetch(null);
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

    /** Loads the release list in the background, then runs {@code then} (on the UI thread) if given. */
    private void fetch(Runnable then) {
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
                    for (Updater.Release r : found) Settings.setPrerelease(activity, r.version, r.prerelease);
                    newest = Updater.newest(activity, found, settings().updatePrereleases);
                    render();
                }
                if (then == null) return;
                if (failure != null) {
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
            fetch(this::openDialog);
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
                + " is available." : "That's the newest version."));
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
                .setTitle("TV Relay updates")
                .setView(box)
                .setNeutralButton("Other versions", (d, w) -> pickVersion())
                .setNegativeButton("Close", null);
        if (newest != null) b.setPositiveButton("Install v" + newest.version, (d, w) -> install(newest));
        AlertDialog dialog = b.show();
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

    private void pickVersion() {
        String current = Updater.versionName(activity);
        String[] items = new String[releases.size()];
        for (int i = 0; i < items.length; i++) {
            Updater.Release r = releases.get(i);
            int cmp = Updater.compare(r.version, current);
            items[i] = "v" + r.version + (r.prerelease ? "  pre-release" : "") + "   " + r.date
                    + (cmp == 0 ? "   (installed)" : cmp < 0 ? "   (older)" : "");
        }
        new AlertDialog.Builder(activity)
                .setTitle("Install a specific version")
                .setItems(items, (d, which) -> confirm(releases.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirm(Updater.Release r) {
        int cmp = Updater.compare(r.version, Updater.versionName(activity));
        if (cmp < 0) {
            // Tested: Android refuses the downgrade even for this debuggable app, after the download.
            message("v" + r.version, "Android doesn't let an app install an older version of itself. To go back "
                    + "to v" + r.version + ", uninstall TV Relay, then install that version from "
                    + "github.com/joz1trillion/tv-relay/releases. Your settings would be reset.");
            return;
        }
        String msg = cmp == 0 ? "Reinstall the version you already have?" : "Update to v" + r.version + "?";
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
