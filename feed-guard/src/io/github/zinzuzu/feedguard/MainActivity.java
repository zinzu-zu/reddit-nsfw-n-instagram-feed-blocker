package io.github.zinzuzu.feedguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Status screen. It only shows state and links to Android's settings: there is
 * deliberately no switch here that turns any protection off. Settings live in
 * OwnDroid's managed configuration for Feed Guard.
 */
public final class MainActivity extends Activity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Build.VERSION.SDK_INT >= 29
                ? android.R.style.Theme_DeviceDefault_DayNight
                : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        SharedPreferences p = Guard.prefs(this);
        Config cfg = Config.read(this);
        boolean secure = SecureSettings.granted(this);

        TextView title = text("Feed Guard", 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);
        list.addView(text("Instagram and Reddit only open in Firefox while the Reddit NSFW Blocker add-on is running on the page. "
                + "Other browsers, and the Instagram and Reddit apps, are paused when they're installed."
                + (cfg.listMode ? " Only the apps on your list open." : "")
                + (cfg.grayscale && secure ? " The screen stays black and white, except in maps." : ""), 15));

        boolean on = Guard.serviceEnabled(this);
        row(on, "Accessibility access",
                on ? (secure ? "On. If it's switched off, Feed Guard switches it back on." : "On.")
                        : "Off. Turn on Feed Guard in Settings › Accessibility.",
                on ? null : "Open accessibility settings", go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        boolean delegated = AppGate.delegated(this);
        row(delegated, "Permission from OwnDroid",
                delegated ? "Given. Feed Guard can pause apps."
                        : "Not given yet. In OwnDroid, add Feed Guard under Delegated admins with “Change package state”.",
                null, null);

        row(secure, "Secure settings permission",
                secure ? "Given. Feed Guard keeps black and white on and switches its accessibility access back on."
                        : "Not given. Black and white, and switching itself back on, need it. Give it from your Mac while USB debugging is still on:\n"
                                + SecureSettings.ADB_GRANT,
                null, null);

        if (!cfg.grayscale) {
            row(true, "Black and white", "Off in OwnDroid's settings for Feed Guard.", null, null);
        } else if (!secure) {
            row(false, "Black and white", "Waiting for the secure settings permission.", null, null);
        } else {
            row(true, "Black and white", "On. " + colourText(cfg.colourApps), null, null);
        }

        long armedAt = p.getLong(Guard.K_ARMED_AT, 0);
        long markerAt = p.getLong(Guard.K_MARKER_SEEN_AT, 0);
        row(armedAt > 0, "Add-on check",
                armedAt > 0
                        ? "Active since " + when(armedAt) + ". Last saw the add-on running " + ago(markerAt) + "."
                        : "Waiting. Open instagram.com in Firefox with add-on version 1.2.0 or newer. Feed Guard starts guarding the first time it sees the add-on.",
                null, null);

        boolean unreadable = p.getBoolean(Guard.K_URL_UNREADABLE, false);
        long urlAt = p.getLong(Guard.K_URL_SEEN_AT, 0);
        row(urlAt > 0 && !unreadable, "Firefox's address bar",
                unreadable ? "Can't read it. Firefox may have changed, so Feed Guard needs an update. Until then it goes by page titles."
                        : urlAt > 0 ? "Read fine, last " + ago(urlAt) + "." : "Not read yet. Open any page in Firefox.",
                null, null);

        boolean ffPaused = p.getBoolean(Guard.K_FIREFOX_PAUSED, false);
        boolean ffNotListed = AppGate.stored(p, Guard.K_OURS).contains(Guard.FIREFOX);
        row(!ffPaused, "Firefox",
                ffPaused ? "Paused, because Feed Guard's accessibility access was off. It opens again once the access is back on."
                        : ffNotListed ? "Paused, because it isn't on your list."
                        : "Not paused by Feed Guard.",
                null, null);

        View.OnClickListener picker = new View.OnClickListener() {
            @Override public void onClick(View v) { pickApps(); }
        };
        if (cfg.listMode) {
            row(true, "Only apps on the list",
                    "On. " + cfg.allowed.size() + (cfg.allowed.size() == 1 ? " app" : " apps")
                            + " on your list. Every other app is paused, apart from calls, texts, contacts, the clock, the keyboard, settings and OwnDroid.",
                    "Pick apps for your list", picker);
        } else {
            row(true, "Only apps on the list",
                    "Off. Turned on in OwnDroid's settings for Feed Guard, it pauses every app that isn't on your list, "
                            + "apart from calls, texts, contacts, the clock, the keyboard, settings and OwnDroid.",
                    "Pick apps for your list", picker);
        }

        Set<String> notSame = AppGate.stored(p, Guard.K_NOT_SAME_APP);
        if (!notSame.isEmpty()) {
            row(false, "Not the copy on your list",
                    Guard.labelsWithPackages(this, notSame)
                            + "\nSigned by someone other than the copy that was on your list, so it's treated as not listed. "
                            + "To accept this copy, take it off the list in OwnDroid, save, then put it back.",
                    null, null);
        }

        Set<String> unpausable = AppGate.stored(p, Guard.K_UNPAUSABLE);
        if (!unpausable.isEmpty()) {
            row(false, "Can't pause",
                    Guard.labelsWithPackages(this, unpausable)
                            + "\nAndroid doesn't let other apps pause these. If you don't want them, hide them in OwnDroid › Applications.",
                    null, null);
        }

        List<String> others = otherSecureSettingsApps();
        if (!others.isEmpty()) {
            row(false, "Other apps that can switch Private DNS off",
                    Guard.labelsWithPackages(this, others)
                            + "\nThey hold the secure settings permission. Take it away from your Mac while USB debugging is still on:\n"
                            + "adb shell pm revoke PACKAGE android.permission.WRITE_SECURE_SETTINGS",
                    null, null);
        }

        Set<String> ours = new TreeSet<>(AppGate.stored(p, Guard.K_OURS));
        row(true, "Paused by Feed Guard", ours.isEmpty() ? "None." : Guard.labelsWithPackages(this, ours), null, null);

        long coverAt = p.getLong(Guard.K_LAST_COVER_AT, 0);
        if (coverAt > 0) row(true, "Last covered a page", ago(coverAt) + ".", null, null);

        if (LastLook.summary != null) {
            row(true, "Last look at Firefox, " + ago(LastLook.at), LastLook.summary, null, null);
        }

        TextView test = text("To test it: open instagram.com in Firefox, then turn the add-on off in Firefox's menu › Extensions. "
                + "Within a few seconds the page is covered. Turn the add-on back on to undo.", 14);
        test.setPadding(0, dp(20), 0, 0);
        list.addView(test);

        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView v = text("Version " + version, 12);
            v.setPadding(0, dp(12), 0, 0);
            list.addView(v);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Can't happen for our own package.
        }
    }

    /** Which colour apps are installed, in words. */
    private String colourText(Set<String> colourApps) {
        List<String> installed = new ArrayList<>();
        for (String pkg : colourApps) {
            if (AppGate.isInstalled(this, pkg)) installed.add(pkg);
        }
        if (colourApps.isEmpty()) return "No app shows colour.";
        if (installed.isEmpty()) return "None of the colour apps is installed, so nothing shows colour.";
        return "Colour comes back only while nothing but " + Guard.labels(this, installed) + " is on screen.";
    }

    /** Apps other than Feed Guard and OwnDroid that you installed and that hold WRITE_SECURE_SETTINGS. */
    private List<String> otherSecureSettingsApps() {
        List<String> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (ai.packageName.equals(getPackageName()) || ai.packageName.equals(AppRules.OWNDROID)) continue;
                if (pm.checkPermission(Manifest.permission.WRITE_SECURE_SETTINGS, ai.packageName)
                        == PackageManager.PERMISSION_GRANTED) {
                    out.add(ai.packageName);
                }
            }
        } catch (RuntimeException ignored) {
            // Skip the check.
        }
        return out;
    }

    /**
     * Lets you tick apps and copies their package names, ready to paste into
     * "Apps to leave alone" in OwnDroid. It changes nothing by itself.
     */
    private void pickApps() {
        final Set<String> current = Config.read(this).allowed;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String[]> apps = new ArrayList<>();
                for (String pkg : AppGate.listCandidates(MainActivity.this)) {
                    apps.add(new String[] {Guard.labels(MainActivity.this, Collections.singleton(pkg)), pkg});
                }
                Collections.sort(apps, new Comparator<String[]>() {
                    @Override public int compare(String[] a, String[] b) { return a[0].compareToIgnoreCase(b[0]); }
                });
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing()) showPicker(apps, current);
                    }
                });
            }
        }, "feedguard-picker").start();
    }

    private void showPicker(final List<String[]> apps, final Set<String> current) {
        String[] names = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i)[0] + "\n" + apps.get(i)[1];
            checked[i] = current.contains(apps.get(i)[1]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Apps for your list")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        copyList(apps, checked, current);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyList(List<String[]> apps, boolean[] checked, Set<String> current) {
        Set<String> picked = new TreeSet<>();
        Set<String> shown = new HashSet<>();
        for (int i = 0; i < apps.size(); i++) {
            shown.add(apps.get(i)[1]);
            if (checked[i]) picked.add(apps.get(i)[1]);
        }
        // Keep listed apps that weren't shown, such as ones not installed right now.
        for (String pkg : current) {
            if (!shown.contains(pkg)) picked.add(pkg);
        }
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Feed Guard list", TextUtils.join(", ", picked)));
        Toast.makeText(this, "Copied " + picked.size() + " package names. In OwnDroid, paste them into Feed Guard's “Apps to leave alone”.",
                Toast.LENGTH_LONG).show();
    }

    private View.OnClickListener go(final Intent target) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(target);
                } catch (RuntimeException ignored) {
                    // No settings screen for this on the phone.
                }
            }
        };
    }

    private void row(boolean ok, String label, String detail, String action, View.OnClickListener onAction) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(16), 0, 0);
        TextView head = text((ok ? "✓ " : "✗ ") + label, 17);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(head);
        TextView body = text(detail, 15);
        body.setTextIsSelectable(true); // package names and adb commands can be copied
        box.addView(body);
        if (action != null && onAction != null) {
            Button b = new Button(this);
            b.setText(action);
            b.setOnClickListener(onAction);
            box.addView(b);
        }
        list.addView(box);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private String when(long wallMs) {
        return DateUtils.formatDateTime(this, wallMs, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
    }

    private String ago(long wallMs) {
        if (wallMs <= 0) return "never";
        return DateUtils.getRelativeTimeSpanString(wallMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
