package io.github.zinzuzu.feedguard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Status screen. It only shows state and links to Android's settings: there is
 * deliberately no switch here that turns any protection off.
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

        TextView title = text("Feed Guard", 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);
        list.addView(text("Instagram and Reddit only open in Firefox while the Reddit NSFW Blocker add-on is running on the page. "
                + "Other browsers, and the Instagram and Reddit apps, are paused when they're installed.", 15));

        boolean on = Guard.serviceEnabled(this);
        row(on, "Accessibility access",
                on ? "On." : "Off. Turn on Feed Guard in Settings › Accessibility.",
                on ? null : "Open accessibility settings", new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));

        boolean delegated = AppGate.delegated(this);
        row(delegated, "Permission from OwnDroid",
                delegated ? "Given. Feed Guard can pause apps."
                        : "Not given yet. In OwnDroid, add Feed Guard under Delegated admins with “Change package state”.",
                null, null);

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
        row(!ffPaused, "Firefox",
                ffPaused ? "Paused, because Feed Guard's accessibility access was off. It opens again once the access is back on."
                        : "Not paused by Feed Guard.",
                null, null);

        Set<String> ours = new TreeSet<>(p.getStringSet(Guard.K_OURS, Collections.<String>emptySet()));
        row(true, "Paused apps", ours.isEmpty() ? "None." : Guard.labels(this, ours) + ".", null, null);

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

    private void row(boolean ok, String label, String detail, String action, final Intent target) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(16), 0, 0);
        TextView head = text((ok ? "✓ " : "✗ ") + label, 17);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(head);
        box.addView(text(detail, 15));
        if (action != null && target != null) {
            Button b = new Button(this);
            b.setText(action);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(target);
                    } catch (RuntimeException ignored) {
                        // No settings screen for this on the phone.
                    }
                }
            });
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
