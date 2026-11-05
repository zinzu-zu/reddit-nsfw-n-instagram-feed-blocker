package io.github.zinzuzu.watchlimit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;

import io.github.zinzuzu.watchlimit.core.Fmt;
import io.github.zinzuzu.watchlimit.core.Policy;
import io.github.zinzuzu.watchlimit.core.Rules;
import io.github.zinzuzu.watchlimit.core.Tracked;

/** Status, protection checks and settings. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView usedLine;
    private TextView leftLine;
    private TextView passesLine;
    private TextView countingLine;
    private LinearLayout checks;
    private String checksShown;
    private TextView modeLine;
    private TextView pendingLine;
    private Button cancelButton;
    private EditText limitField;
    private EditText passesField;
    private EditText passSecField;
    private EditText delayField;
    private TextView saveResult;
    private Button lockButton;
    private LinearLayout appsBox;
    private EditText addField;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = Ui.page(this);
        Ui.text(this, page, "Watch Limit", 28, true);
        Ui.text(this, page, "Only screen-on time in YouTube counts. Listening with the screen "
                + "off, or while you use other apps, never counts and keeps playing after "
                + "the limit.", 15, false);

        LinearLayout today = Ui.card(this, page);
        Ui.text(this, today, "Today", 14, true);
        usedLine = Ui.text(this, today, "", 22, true);
        leftLine = Ui.text(this, today, "", 15, false);
        passesLine = Ui.text(this, today, "", 15, false);
        countingLine = Ui.text(this, today, "", 15, false);

        LinearLayout protection = Ui.card(this, page);
        Ui.text(this, protection, "Protection", 14, true);
        checks = Ui.column(this);
        protection.addView(checks);
        Ui.button(this, protection, "Open Accessibility settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        Ui.button(this, protection, "Open App info (for “Allow restricted settings”)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                });

        LinearLayout limits = Ui.card(this, page);
        Ui.text(this, limits, "Limits", 14, true);
        modeLine = Ui.text(this, limits, "", 15, false);
        pendingLine = Ui.text(this, limits, "", 15, true);
        cancelButton = Ui.button(this, limits, "Cancel the waiting change",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Engine.get(MainActivity.this).cancelPending();
                        saveResult.setText("The waiting change is cancelled.");
                        fillFields();
                        refresh();
                    }
                });
        Rules target = Engine.get(this).targetRules();
        limitField = Ui.numberField(this, limits, "Daily limit (minutes)", target.limitMin);
        passesField = Ui.numberField(this, limits, "Audio passes per day", target.passes);
        passSecField = Ui.numberField(this, limits, "Audio pass length (seconds)", target.passSec);
        delayField = Ui.numberField(this, limits, "Wait before loosening (hours)", target.delayH);
        Ui.button(this, limits, "Save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        saveResult = Ui.text(this, limits, "", 15, false);
        lockButton = Ui.button(this, limits, "Lock the settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmLock();
            }
        });

        LinearLayout apps = Ui.card(this, page);
        Ui.text(this, apps, "Apps that count", 14, true);
        Ui.text(this, apps, "They share one daily limit. Apps that aren't installed are "
                + "watched too, in case you install them later.", 15, false);
        appsBox = Ui.column(this);
        apps.addView(appsBox);
        addField = Ui.textField(this, apps, "Another app's package name, e.g. com.example.video");
        Ui.button(this, apps, "Add app", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addApp();
            }
        });

        Ui.text(this, page, "Version " + versionName(), 13, false).setTextColor(Ui.NEUTRAL);
        GuardJob.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderApps();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void refresh() {
        Engine e = Engine.get(this);
        e.refresh();
        Rules r = e.activeRules();
        long used = e.usedMs();
        usedLine.setText(Fmt.duration(used) + " of " + Fmt.duration(r.limitMs()) + " used");
        long left = r.limitMs() - used;
        String reset = "a new day starts in " + Fmt.durationUp(e.untilResetMs());
        leftLine.setText(left > 0
                ? Fmt.durationUp(left) + " left, " + reset + "."
                : "Limit reached, " + reset + ".");
        passesLine.setText("Audio passes left: " + e.passesLeft() + " of " + r.passes
                + " (" + Fmt.duration(r.passSec * 1000L) + " each)");
        String last = e.lastSeenPackage() == null ? "nothing yet since the app started"
                : e.label(e.lastSeenPackage()) + " at "
                        + DateFormat.getTimeFormat(this).format(new Date(e.lastSeenWallTime()));
        countingLine.setText("Counting right now: " + (e.countingNow() ? "yes" : "no")
                + ". Last counted: " + last + ".");

        renderChecks(e);

        boolean locked = e.settingsLocked();
        modeLine.setText(locked
                ? "Locked. Stricter changes apply right away. Changes that allow more "
                        + "YouTube wait " + r.delayH + " h first, and you can cancel them "
                        + "while they wait."
                : "Setup mode: every change applies right away. Test with a short limit, "
                        + "then set your real limit and lock the settings.");
        lockButton.setVisibility(locked ? View.GONE : View.VISIBLE);
        Rules pending = e.pendingRules();
        if (pending != null) {
            pendingLine.setVisibility(View.VISIBLE);
            cancelButton.setVisibility(View.VISIBLE);
            pendingLine.setText("Waiting: " + describeChange(r, pending) + ". Takes effect in "
                    + Fmt.durationUp(e.pendingRemainingMs()) + ".");
        } else {
            pendingLine.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
        }
    }

    private void renderChecks(Engine e) {
        List<CharSequence> lines = new ArrayList<CharSequence>();
        lines.add(check(e.watcherRunning(), "The watcher is on.",
                "The watcher is off, so nothing counts. Turn on Watch Limit under "
                        + "Accessibility. If Android says it's a restricted setting, open App "
                        + "info, tap ⋮, choose “Allow restricted settings”, then try again."));
        lines.add(check(e.hasDelegation(),
                "Turning the watcher off pauses the YouTube apps until it's back on.",
                "Turning the watcher off would end the limit. In OwnDroid, tap the shield "
                        + "icon at the top, then Delegated admins → Add delegated admin. Pick "
                        + "Watch Limit and tick “Change package state”."));
        lines.add(check(e.uninstallBlocked(), "Watch Limit can't be uninstalled.",
                "Watch Limit can still be uninstalled. In OwnDroid, add it under "
                        + "Applications state → Block uninstall."));
        lines.add(note("Force stop and Clear storage: block them in OwnDroid under "
                + "Applications state → Disable user control. This one can't be checked "
                + "from here."));
        lines.add(check(e.dateTimeLocked(), "The date and time settings are locked.",
                "The date and time can still be changed. In OwnDroid, turn on User "
                        + "restriction → Other → “Configure date or time”."));
        lines.add(check(e.autoTime(), "Date and time are set automatically.",
                "Date and time are set by hand. Turn on “Set time automatically”."));
        // Rebuild only when something changed, so the screen doesn't flicker every second.
        String shown = TextUtils.join("\n", lines);
        if (shown.equals(checksShown)) return;
        checksShown = shown;
        checks.removeAllViews();
        for (CharSequence line : lines) Ui.text(this, checks, line, 15, false);
    }

    private static CharSequence check(boolean ok, String good, String bad) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(ok ? "✓  " : "✗  ");
        sb.setSpan(new ForegroundColorSpan(ok ? Ui.GOOD : Ui.BAD), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(ok ? good : bad);
        return sb;
    }

    private static CharSequence note(String s) {
        SpannableStringBuilder sb = new SpannableStringBuilder("•  ");
        sb.setSpan(new ForegroundColorSpan(Ui.NEUTRAL), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(s);
        return sb;
    }

    private void fillFields() {
        Rules t = Engine.get(this).targetRules();
        limitField.setText(String.valueOf(t.limitMin));
        passesField.setText(String.valueOf(t.passes));
        passSecField.setText(String.valueOf(t.passSec));
        delayField.setText(String.valueOf(t.delayH));
    }

    private void save() {
        Engine e = Engine.get(this);
        Rules t = e.targetRules();
        Rules wanted = t.withLimits(
                Ui.readInt(limitField, t.limitMin),
                Ui.readInt(passesField, t.passes),
                Ui.readInt(passSecField, t.passSec),
                Ui.readInt(delayField, t.delayH));
        Policy.Outcome o = e.requestRules(wanted);
        saveResult.setText(describeOutcome(o, e));
        fillFields();
        refresh();
    }

    private void confirmLock() {
        Rules r = Engine.get(this).activeRules();
        new AlertDialog.Builder(this)
                .setTitle("Lock the settings?")
                .setMessage("After this, any change that allows more YouTube waits " + r.delayH
                        + " hours before it takes effect. Stricter changes still apply right "
                        + "away. Locking can't be undone.")
                .setPositiveButton("Lock", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Engine.get(MainActivity.this).lockSettings();
                        saveResult.setText("Settings locked.");
                        refresh();
                    }
                })
                .setNegativeButton("Not yet", null)
                .show();
    }

    private void addApp() {
        String pkg = addField.getText().toString().trim();
        if (!Rules.isPackageName(pkg)) {
            saveResult.setText("That doesn't look like a package name.");
            return;
        }
        Engine e = Engine.get(this);
        Policy.Outcome o = e.requestRules(e.targetRules().withApp(pkg));
        addField.setText("");
        saveResult.setText(describeOutcome(o, e));
        renderApps();
        refresh();
    }

    private void removeApp(String pkg) {
        Engine e = Engine.get(this);
        Policy.Outcome o = e.requestRules(e.targetRules().withoutApp(pkg));
        saveResult.setText(describeOutcome(o, e));
        renderApps();
        refresh();
    }

    private void renderApps() {
        appsBox.removeAllViews();
        Engine e = Engine.get(this);
        PackageManager pm = getPackageManager();
        List<String> installed = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();
        for (String p : Tracked.BUILT_IN.keySet()) {
            if (Engine.isInstalled(pm, p)) installed.add(e.label(p));
            else missing.add(Tracked.BUILT_IN.get(p));
        }
        Ui.text(this, appsBox, "Installed: " + (installed.isEmpty() ? "none" : join(installed)),
                15, false);
        Ui.text(this, appsBox, "Also watched: " + join(missing), 15, false)
                .setTextColor(Ui.NEUTRAL);
        TreeSet<String> extras = new TreeSet<String>(e.activeRules().extraApps);
        extras.addAll(e.targetRules().extraApps);
        for (final String p : extras) {
            boolean leaving = !e.targetRules().extraApps.contains(p);
            Ui.text(this, appsBox, e.label(p) + " — " + p
                    + (leaving ? " (stops counting when the wait is over)" : ""), 15, false);
            if (leaving) continue;
            Ui.button(this, appsBox, "Stop counting " + e.label(p), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeApp(p);
                }
            });
        }
    }

    private static String describeOutcome(Policy.Outcome o, Engine e) {
        switch (o) {
            case APPLIED:
                return "Saved. It applies now.";
            case WAITING:
                return "Saved. This allows more YouTube, so it takes effect in "
                        + Fmt.durationUp(e.pendingRemainingMs()) + ". You can cancel it before then.";
            case APPLIED_AND_WAITING:
                return "Saved. The stricter part applies now. The rest allows more YouTube, so "
                        + "it takes effect in " + Fmt.durationUp(e.pendingRemainingMs()) + ".";
            default:
                return "Nothing changed.";
        }
    }

    private static String describeChange(Rules from, Rules to) {
        List<String> parts = new ArrayList<String>();
        if (from.limitMin != to.limitMin) {
            parts.add("daily limit " + from.limitMin + " → " + to.limitMin + " min");
        }
        if (from.passes != to.passes) {
            parts.add("audio passes " + from.passes + " → " + to.passes);
        }
        if (from.passSec != to.passSec) {
            parts.add("pass length " + from.passSec + " → " + to.passSec + " s");
        }
        if (from.delayH != to.delayH) {
            parts.add("wait " + from.delayH + " → " + to.delayH + " h");
        }
        for (String p : from.extraApps) {
            if (!to.extraApps.contains(p)) parts.add("stop counting " + p);
        }
        return parts.isEmpty() ? "no visible change" : join(parts);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
            saveResult.setText("Couldn't open that settings screen.");
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
