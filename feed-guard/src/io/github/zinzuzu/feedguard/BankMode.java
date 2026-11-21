package io.github.zinzuzu.feedguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Banking mode on the phone (BankRules says why and holds its settings).
 *
 * Switching the guards off, in this order, so nothing they guard is ever open
 * while they're off: first a note of when banking mode ends, so Feed Guard's own
 * "switch back on" checks leave it off; then Firefox is paused; then Feed Guard's
 * and Watch Limit's accessibility access go off together, which also makes Watch
 * Limit pause the YouTube apps.
 *
 * Switching them back on: when the time is up, when you tap "Turn back on now",
 * after a restart, or when someone switches Feed Guard back on meanwhile. The
 * note goes first, so nothing holds them off any more, then both are switched
 * back on. Feed Guard lifts its Firefox pause once it runs again, and Watch
 * Limit lifts its YouTube pauses. Every safety net (the watchdog checks, the
 * check after a restart) ends a banking mode whose time is up.
 */
final class BankMode {
    private BankMode() {}

    // Stored state, in Guard.prefs. The first four are the note; K_RESTORE outlives it
    // until the services are really back on.
    static final String K_BOOT = "bankBoot";            // start-up count when banking mode began
    static final String K_UNTIL = "bankUntil";          // when it ends: SystemClock.elapsedRealtime() ms
    static final String K_UNTIL_WALL = "bankUntilWall"; // the same as a wall-clock time, for what's shown
    static final String K_APP = "bankApp";              // the bank app it was for, for what's shown
    static final String K_RESTORE = "bankRestore";      // the services it switched off, exactly as Settings had them

    static final int N_BANKING = 7;
    private static final String CHANNEL = "banking";
    static final String ACTION_BACK_ON = "io.github.zinzuzu.feedguard.action.BANK_BACK_ON";

    /** Which start-up this is (Settings.Global.BOOT_COUNT), or BankRules.UNKNOWN_BOOT. */
    static long bootCount(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | RuntimeException e) {
            return BankRules.UNKNOWN_BOOT;
        }
    }

    /** Whether banking mode holds right now. */
    static boolean active(Context c) {
        SharedPreferences p = Guard.prefs(c);
        return BankRules.active(p.getLong(K_BOOT, BankRules.UNKNOWN_BOOT), p.getLong(K_UNTIL, 0),
                bootCount(c), SystemClock.elapsedRealtime());
    }

    /** How long banking mode still holds, in ms; 0 if it isn't on. */
    static long timeLeft(Context c) {
        if (!active(c)) return 0;
        return Math.max(0, Guard.prefs(c).getLong(K_UNTIL, 0) - SystemClock.elapsedRealtime());
    }

    /** When banking mode ends, as a wall-clock time; 0 if it isn't on. */
    static long endsAt(Context c) {
        return active(c) ? Guard.prefs(c).getLong(K_UNTIL_WALL, 0) : 0;
    }

    /** The bank app the current banking mode is for; "" if none. */
    static String bankApp(Context c) {
        return active(c) ? Guard.prefs(c).getString(K_APP, "") : "";
    }

    /**
     * The bank apps from Feed Guard's managed configuration that can be opened:
     * installed, not paused, and not a browser.
     */
    static List<String> bankApps(Context c) {
        Set<String> wanted = Config.read(c).bankApps;
        List<String> out = new ArrayList<>();
        if (wanted.isEmpty()) return out;
        PackageManager pm = c.getPackageManager();
        Set<String> browsers;
        try {
            browsers = AppGate.browsers(pm);
        } catch (RuntimeException e) {
            return out;
        }
        for (String pkg : wanted) {
            if (browsers.contains(pkg) || !AppGate.isInstalled(c, pkg) || AppGate.isPaused(c, pkg)) continue;
            out.add(pkg);
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Switches the guards off for banking mode, or, if it's already on, starts
     * its time again. Only for one of bankApps(): an app set as a bank app in the
     * managed configuration that can open. Returns false if it couldn't; then a
     * banking mode that wasn't on stays off, with nothing changed.
     */
    static synchronized boolean standDown(Context c, String bankPkg) {
        if (!SecureSettings.granted(c)) return false;
        if (bankPkg == null || !bankApps(c).contains(bankPkg)) return false;
        // Without the start-up count, active() can't tell this banking mode from one
        // before a restart, so it would end at once.
        if (bootCount(c) == BankRules.UNKNOWN_BOOT) {
            Log.w(Guard.TAG, "no start-up count, so no banking mode");
            return false;
        }
        SharedPreferences p = Guard.prefs(c);
        ContentResolver cr = c.getContentResolver();
        boolean wasActive = active(c);
        String list;
        try {
            list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (RuntimeException e) {
            return false;
        }
        String[] split = ServiceList.without(list, BankRules.GUARD_PACKAGES);
        String restoreBefore = p.getString(K_RESTORE, "");
        // Keep what an earlier banking mode switched off, so all of it comes back.
        String restore = ServiceList.withAll(restoreBefore, split[1]);
        boolean firefoxWasPaused = p.getBoolean(Guard.K_FIREFOX_PAUSED, false);
        long until = BankRules.deadline(SystemClock.elapsedRealtime());
        long untilWall = System.currentTimeMillis() + BankRules.WINDOW_MS;

        // 1. The note of when it ends, first: from now on nothing switches the guards back on early.
        p.edit()
                .putLong(K_BOOT, bootCount(c))
                .putLong(K_UNTIL, until)
                .putLong(K_UNTIL_WALL, untilWall)
                .putString(K_APP, bankPkg)
                .putString(K_RESTORE, restore)
                .commit();
        // 2. Firefox can't open while the guard is off.
        if (Guard.armed(c)) AppGate.pauseFirefoxQuietly(c);
        // 3. Both guards off together.
        if (!split[1].isEmpty()) {
            try {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, split[0]);
            } catch (RuntimeException e) {
                Log.w(Guard.TAG, "can't switch the guards off for banking mode", e);
                if (!wasActive) {
                    // Nothing was switched off, so there's no banking mode to keep.
                    clearNote(p);
                    p.edit().putString(K_RESTORE, restoreBefore).commit();
                    if (!firefoxWasPaused) {
                        AppGate.releaseFirefox(c);
                        AppGate.scan(c, null);
                    }
                }
                return false;
            }
        }
        notifyBanking(c, bankPkg, untilWall);
        WatchdogJob.startBankChecks(c);
        Log.i(Guard.TAG, "banking mode on for " + bankPkg);
        return true;
    }

    /**
     * Switches the guards back on and ends banking mode. Returns false if Android
     * didn't let it; then what it switched off stays noted and the next safety-net
     * check tries again, while Firefox and YouTube stay paused.
     */
    static synchronized boolean standUp(Context c, String why) {
        SharedPreferences p = Guard.prefs(c);
        String restore = p.getString(K_RESTORE, "");
        // The note goes first, so nothing holds the guards off any more.
        clearNote(p);
        boolean ok = true;
        boolean reconnects = false;
        String toAdd = installedOnly(c, restore);
        if (!toAdd.isEmpty()) {
            ContentResolver cr = c.getContentResolver();
            String me = new ComponentName(c, GuardService.class).flattenToString();
            try {
                String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                String updated = ServiceList.withAll(list, toAdd);
                if (!updated.equals(list == null ? "" : list)) {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
                    reconnects = !ServiceList.contains(list, me) && ServiceList.contains(updated, me);
                }
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (RuntimeException e) {
                Log.w(Guard.TAG, "can't switch the guards back on after banking mode", e);
                ok = false;
            }
        }
        if (ok) p.edit().remove(K_RESTORE).commit();
        Guard.cancel(c, N_BANKING);
        // Feed Guard lifts banking mode's Firefox pause when its service connects again. If
        // the service never went off, nothing connects again, so lift the pause here.
        if (ok && !reconnects && GuardService.isRunning()) {
            AppGate.releaseFirefox(c);
            AppGate.scan(c, null);
        }
        // The banking checks still scheduled find banking mode over and run as ordinary checks.
        Log.i(Guard.TAG, "banking mode off (" + why + ")" + (ok ? "" : ", but the guards couldn't be switched back on yet"));
        return ok;
    }

    /**
     * For the safety nets. While banking mode holds, keeps Firefox paused and its
     * checks scheduled, and returns true. Once its time is up, or after a restart,
     * switches the guards back on and returns false.
     */
    static synchronized boolean check(Context c) {
        if (active(c)) {
            if (Guard.armed(c)) AppGate.pauseFirefoxQuietly(c);
            WatchdogJob.keepBankChecks(c);
            return true;
        }
        SharedPreferences p = Guard.prefs(c);
        if (p.contains(K_UNTIL) || !p.getString(K_RESTORE, "").isEmpty()) standUp(c, "time up");
        return false;
    }

    private static void clearNote(SharedPreferences p) {
        p.edit().remove(K_BOOT).remove(K_UNTIL).remove(K_UNTIL_WALL).remove(K_APP).commit();
    }

    /** The services in a colon-separated list whose app is still installed. */
    private static String installedOnly(Context c, String services) {
        StringBuilder b = new StringBuilder();
        if (services == null) return "";
        for (String item : services.split(":")) {
            String t = item.trim();
            String pkg = ServiceList.packageOf(t);
            if (pkg == null || !AppGate.isInstalled(c, pkg)) continue;
            if (b.length() > 0) b.append(':');
            b.append(t);
        }
        return b.toString();
    }

    private static void notifyBanking(Context c, String bankPkg, long untilWall) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Banking mode", NotificationManager.IMPORTANCE_LOW));
        String bank = Guard.labels(c, Collections.singleton(bankPkg));
        String time = DateFormat.getTimeFormat(c).format(new Date(untilWall));
        String text = "Feed Guard and Watch Limit are off so " + bank + " can open. Firefox and YouTube stay paused "
                + "until they're back on at " + time + ", or until you tap Turn back on now.";
        PendingIntent backOn = PendingIntent.getBroadcast(c, N_BANKING,
                new Intent(c, BankReceiver.class).setAction(ACTION_BACK_ON),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(c, N_BANKING,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("Banking mode until " + time)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(untilWall)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_guard), "Turn back on now", backOn).build())
                .build();
        try {
            nm.notify(N_BANKING, n);
        } catch (RuntimeException ignored) {
            // Notifications not allowed: Feed Guard's status screen has the same button.
        }
    }
}
