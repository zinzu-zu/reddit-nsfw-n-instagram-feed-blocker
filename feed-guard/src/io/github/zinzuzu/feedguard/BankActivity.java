package io.github.zinzuzu.feedguard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The "Open bank app" icon: banking mode first, then the bank app. It switches
 * Feed Guard and Watch Limit off (BankMode), waits until Android no longer lists
 * them as switched on, and only then opens the bank app, so the bank's check at
 * start-up finds them off. Opening the bank app the usual way works too, but
 * Feed Guard only notices it once it's on screen, which can be after the bank
 * has checked.
 */
public final class BankActivity extends Activity {
    /** How long to wait for Android to switch the guards off before opening the bank app anyway. */
    private static final long WAIT_MS = 3_000;
    private static final long POLL_MS = 100;
    /** A moment more, so every app's own copy of the accessibility state has caught up. */
    private static final long SETTLE_MS = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SecureSettings.granted(this)) {
            done("Banking mode needs Feed Guard's secure settings permission. Open Feed Guard to see how to give it.");
            return;
        }
        final List<String> banks = BankMode.bankApps(this);
        if (banks.isEmpty()) {
            done(noBankText());
            return;
        }
        if (banks.size() == 1) {
            open(banks.get(0));
            return;
        }
        String[] names = new String[banks.size()];
        for (int i = 0; i < names.length; i++) names[i] = label(banks.get(i));
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Open which bank app?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        open(banks.get(which));
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) {
                        finish();
                    }
                })
                .show();
    }

    private void open(final String pkg) {
        final Context app = getApplicationContext();
        final String name = label(pkg);
        // A bank app left open from before banking mode is still showing its warning.
        final boolean fresh = !BankMode.active(app);
        Toast.makeText(app, "Opening " + name + " in banking mode…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = BankMode.standDown(app, pkg);
                if (ok) waitUntilGuardsOff(app);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            launch(pkg, fresh, name);
                        } else {
                            Toast.makeText(app, "Feed Guard couldn't switch the guards off, so " + name
                                    + " would refuse to open. Open Feed Guard to see why.", Toast.LENGTH_LONG).show();
                        }
                        finish();
                    }
                });
            }
        }, "feedguard-bank").start();
    }

    private void launch(String pkg, boolean fresh, String name) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(getApplicationContext(), "Can't open " + name + ".", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Start it afresh, so it checks again now that the guards are off.
        if (fresh) launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(launch);
        } catch (RuntimeException e) {
            Toast.makeText(getApplicationContext(), "Can't open " + name + ".", Toast.LENGTH_LONG).show();
        }
    }

    /** Waits, at most WAIT_MS, until Android no longer lists Feed Guard or Watch Limit as switched on. */
    private static void waitUntilGuardsOff(Context c) {
        AccessibilityManager am = c.getSystemService(AccessibilityManager.class);
        if (am == null) return;
        long giveUpAt = SystemClock.elapsedRealtime() + WAIT_MS;
        while (guardListed(am)) {
            if (SystemClock.elapsedRealtime() >= giveUpAt) return;
            SystemClock.sleep(POLL_MS);
        }
        SystemClock.sleep(SETTLE_MS);
    }

    /** Whether Android lists Feed Guard's or Watch Limit's service as switched on, as any app would see it. */
    private static boolean guardListed(AccessibilityManager am) {
        List<AccessibilityServiceInfo> on;
        try {
            on = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        } catch (RuntimeException e) {
            return false;
        }
        if (on == null) return false;
        for (AccessibilityServiceInfo s : on) {
            ResolveInfo ri = s.getResolveInfo();
            if (ri != null && ri.serviceInfo != null && BankRules.GUARD_PACKAGES.contains(ri.serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private String noBankText() {
        Set<String> wanted = Config.read(this).bankApps;
        if (wanted.isEmpty()) return "No bank app is set in OwnDroid's settings for Feed Guard.";
        return Guard.labels(this, wanted) + " can't open: it isn't installed, or it's paused.";
    }

    private void done(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        finish();
    }

    private String label(String pkg) {
        return Guard.labels(this, Collections.singleton(pkg));
    }

    private int dialogTheme() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }
}
