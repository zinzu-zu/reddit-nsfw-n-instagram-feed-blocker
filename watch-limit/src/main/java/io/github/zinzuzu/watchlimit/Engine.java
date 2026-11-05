package io.github.zinzuzu.watchlimit;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.zinzuzu.watchlimit.core.ClockGuard;
import io.github.zinzuzu.watchlimit.core.Policy;
import io.github.zinzuzu.watchlimit.core.Rules;
import io.github.zinzuzu.watchlimit.core.State;
import io.github.zinzuzu.watchlimit.core.Tracked;

/**
 * Counts screen time and enforces the limit. One instance per process, shared by the
 * accessibility service and the screens. Main thread only.
 *
 * Time counts only while all of these hold: the screen is on, the phone is unlocked,
 * and a watched app has a window on screen (full screen, split screen, floating or
 * picture-in-picture). Playback with the screen off or behind other apps never counts.
 *
 * Over the limit, a watched app that comes on screen is covered by the block screen.
 * It is not paused or killed, so audio that's already playing carries on.
 */
final class Engine {
    private static final String TAG = "WatchLimit";
    private static final long TICK_MS = 1000;
    static final long EVENT_DELAY_MS = 150;
    /** Upper bound for one counting step, in case the main thread stalled. */
    private static final long MAX_STEP_MS = 5000;
    private static final long SAVE_EVERY_MS = 15_000;
    private static final long ESCALATE_AFTER_MS = 1500;
    private static final long FLASH_MS = 1500;
    private static final long NETWORK_TIME_EVERY_MS = 60_000;

    private static Engine sInstance;

    static Engine get(Context c) {
        if (sInstance == null) sInstance = new Engine(c.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final State st;

    private WatchService service;

    private String countingPkg;
    private long lastCountAt;
    private String lastSeenPkg;
    private long lastSeenWall;

    private long passUntil;

    private long episodeStart;
    private long lastActionAt;
    private int actions;

    private long lastSaveAt;
    private boolean evalPending;
    private long evalAt;

    private boolean networkTimeDue = true;
    private long networkTimeAt;

    private final Runnable evalRunnable = new Runnable() {
        @Override
        public void run() {
            evaluate();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (!Intent.ACTION_SCREEN_OFF.equals(a) && !Intent.ACTION_SCREEN_ON.equals(a)) {
                networkTimeDue = true;
            }
            requestEval(0);
        }
    };

    private Engine(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE);
        st = State.load(prefs.getAll());
    }

    // ---------------------------------------------------------------- service lifecycle

    void onServiceConnected(WatchService s) {
        if (service != null && service != s) {
            try {
                service.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // Not registered.
            }
        }
        service = s;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            s.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            s.registerReceiver(receiver, f);
        }
        sync();
        // The watcher is back, so lift the suspensions it put in place.
        setSuspended(new ArrayList<String>(st.failClosed), false, st.failClosed);
        setSuspended(new ArrayList<String>(st.flashSuspended), false, st.flashSuspended);
        save(true);
        GuardJob.schedule(app);
        requestEval(0);
    }

    /**
     * The watcher is being switched off. Fail closed: suspend the watched apps until it
     * is switched back on, so turning it off is never a way around the limit.
     */
    void onServiceGone(WatchService s) {
        if (service == null || service != s) return;
        sync();
        countingPkg = null;
        try {
            s.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered.
        }
        handler.removeCallbacksAndMessages(null);
        evalPending = false;
        service = null;
        setSuspended(unpausedWatchedApps(), true, st.failClosed);
        save(true);
    }

    /**
     * The background check. While the watcher is switched off, pauses the watched apps
     * that aren't paused, such as one installed or reinstalled since.
     */
    void checkWatcher() {
        if (service != null || watcherEnabled()) return;
        sync();
        setSuspended(unpausedWatchedApps(), true, st.failClosed);
        save(true);
    }

    /** Whether Watch Limit is switched on under Accessibility, even if not running yet. */
    private boolean watcherEnabled() {
        String list = Settings.Secure.getString(app.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = new ComponentName(app, WatchService.class);
        for (String s : list.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- clocks and counting

    /** Brings the clocks, the waiting period and the day up to date, and adds screen time. */
    private long sync() {
        long el = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        int boot = Settings.Global.getInt(app.getContentResolver(),
                Settings.Global.BOOT_COUNT, ClockGuard.UNKNOWN_BOOT);
        long trusted = st.clock.observe(wall, el, boot, autoTime());
        if (networkTimeDue || el - networkTimeAt >= NETWORK_TIME_EVERY_MS) {
            networkTimeDue = false;
            networkTimeAt = el;
            long network = networkTime();
            if (network > 0) trusted = st.clock.trustNetworkTime(wall, network);
        }
        if (countingPkg != null) {
            long step = el - lastCountAt;
            if (step > 0) st.ledger.usedMs += Math.min(step, MAX_STEP_MS);
        }
        lastCountAt = el;
        st.policy.advance(st.clock.lastDelta());
        if (st.ledger.rollIfDue(trusted, ZoneId.systemDefault())) passUntil = 0;
        return trusted;
    }

    /**
     * Android's network time (Android 13 and newer), or 0 if it has none yet, for
     * example right after a restart without a connection.
     */
    private static long networkTime() {
        if (Build.VERSION.SDK_INT < 33) return 0;
        try {
            return SystemClock.currentNetworkTimeClock().millis();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    void requestEval(long delayMs) {
        long at = SystemClock.elapsedRealtime() + delayMs;
        if (evalPending && evalAt <= at) return;
        handler.removeCallbacks(evalRunnable);
        handler.postDelayed(evalRunnable, delayMs);
        evalPending = true;
        evalAt = at;
    }

    private void evaluate() {
        evalPending = false;
        sync();
        WatchService s = service;
        boolean wasCounting = countingPkg != null;
        WatchService.Seen seen = WatchService.Seen.NOTHING;
        if (s != null && screenInUse()) seen = s.scan(Tracked.all(st.policy.active));
        String pkg = seen.any();
        countingPkg = pkg;
        if (pkg == null) {
            endEpisode();
            maybeSave(wasCounting);
            return;
        }
        lastSeenPkg = pkg;
        lastSeenWall = System.currentTimeMillis();
        long el = SystemClock.elapsedRealtime();
        long left = st.policy.active.limitMs() - st.ledger.usedMs;
        warnIfNearLimit(left);
        if (left <= 0 && el >= passUntil) {
            enforce(s, seen, el);
        } else {
            endEpisode();
        }
        maybeSave(false);
        requestEval(TICK_MS);
    }

    private boolean screenInUse() {
        PowerManager pm = app.getSystemService(PowerManager.class);
        KeyguardManager km = app.getSystemService(KeyguardManager.class);
        return pm != null && pm.isInteractive() && (km == null || !km.isKeyguardLocked());
    }

    private void warnIfNearLimit(long left) {
        if (st.policy.active.limitMin < 10 || left <= 0) return;
        if (left <= 60_000L) {
            if (!st.ledger.warned1) {
                st.ledger.warned1 = true;
                st.ledger.warned5 = true;
                toast("1 minute of YouTube left today");
            }
        } else if (left <= 5 * 60_000L && !st.ledger.warned5) {
            st.ledger.warned5 = true;
            toast("5 minutes of YouTube left today");
        }
    }

    // ---------------------------------------------------------------- enforcement

    /**
     * Gets a watched app off the screen, gently first. The first try covers it with the
     * block screen, which keeps its audio going, and closes any picture-in-picture
     * window. If it's still visible 1.5 s later (split screen, a floating window, or the
     * block screen couldn't open), the next tries go to the home screen first. After
     * that, the app is suspended for a moment, which also stops its playback.
     */
    private void enforce(WatchService s, WatchService.Seen seen, long el) {
        if (episodeStart == 0) {
            episodeStart = el;
            actions = 0;
        }
        if (actions > 0 && el - lastActionAt < ESCALATE_AFTER_MS) return;
        actions++;
        lastActionAt = el;
        if (actions > 3) {
            flashSuspend(seen.any());
            lastActionAt = el + FLASH_MS;
            return;
        }
        if (seen.pipPkg != null) s.closePictureInPicture(Tracked.all(st.policy.active));
        if (seen.fullPkg == null) return;
        if (actions > 1) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        showBlockScreen(s, seen.fullPkg);
    }

    private void endEpisode() {
        episodeStart = 0;
        actions = 0;
    }

    private void showBlockScreen(Context from, String pkg) {
        // NO_USER_ACTION matters: without it the covered app gets onUserLeaveHint and
        // YouTube would jump into picture-in-picture instead of playing in the background.
        Intent i = new Intent(app, BlockActivity.class)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        try {
            from.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't show the block screen", e);
        }
    }

    private void flashSuspend(final String pkg) {
        if (!setSuspended(Collections.singletonList(pkg), true, st.flashSuspended)) {
            if (service != null) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        save(true);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // If the watcher went away meanwhile, the app stays suspended until it's back.
                if (service == null) return;
                setSuspended(Collections.singletonList(pkg), false, st.flashSuspended);
                save(true);
            }
        }, FLASH_MS);
    }

    /**
     * Suspends or unsuspends apps through the "Change package state" delegation that
     * OwnDroid grants. Returns true if at least one app changed.
     */
    private boolean setSuspended(Collection<String> pkgs, boolean suspend, Set<String> record) {
        if (pkgs.isEmpty()) return false;
        DevicePolicyManager dpm = app.getSystemService(DevicePolicyManager.class);
        if (dpm == null) return false;
        try {
            String[] failed = dpm.setPackagesSuspended(null, pkgs.toArray(new String[0]), suspend);
            Set<String> done = new TreeSet<String>(pkgs);
            if (failed != null) done.removeAll(Arrays.asList(failed));
            if (suspend) {
                record.addAll(done);
            } else {
                record.removeAll(pkgs);
            }
            return !done.isEmpty();
        } catch (SecurityException e) {
            Log.w(TAG, "Can't suspend apps. Is the OwnDroid delegation set?", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Suspending apps failed", e);
        }
        return false;
    }

    /**
     * Watched apps that are installed and not paused. Apps something else paused, such as
     * OwnDroid itself, are left out, so Watch Limit never lifts a pause it didn't make.
     */
    private List<String> unpausedWatchedApps() {
        PackageManager pm = app.getPackageManager();
        List<String> out = new ArrayList<String>();
        for (String p : Tracked.all(st.policy.active)) {
            try {
                if (!pm.isPackageSuspended(p)) out.add(p);
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed.
            }
        }
        return out;
    }

    static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void toast(String msg) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- saving

    private void maybeSave(boolean force) {
        if (force || SystemClock.elapsedRealtime() - lastSaveAt >= SAVE_EVERY_MS) save(false);
    }

    private void save(boolean now) {
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, Object> en : st.toMap().entrySet()) {
            Object v = en.getValue();
            if (v instanceof Long) {
                e.putLong(en.getKey(), (Long) v);
            } else {
                e.putString(en.getKey(), (String) v);
            }
        }
        if (now) {
            e.commit();
        } else {
            e.apply();
        }
        lastSaveAt = SystemClock.elapsedRealtime();
    }

    // ---------------------------------------------------------------- for the screens

    /** Brings everything up to date. Call before reading the getters below. */
    void refresh() {
        sync();
    }

    long usedMs() {
        return st.ledger.usedMs;
    }

    Rules activeRules() {
        return st.policy.active;
    }

    Rules pendingRules() {
        return st.policy.pending;
    }

    Rules targetRules() {
        return st.policy.target();
    }

    long pendingRemainingMs() {
        return st.policy.pendingRemainingMs;
    }

    boolean settingsLocked() {
        return st.policy.locked;
    }

    long untilResetMs() {
        long trusted = System.currentTimeMillis() - st.clock.skewMs;
        return Math.max(0, st.ledger.nextResetAt - trusted);
    }

    int passesLeft() {
        return Math.max(0, st.policy.active.passes - st.ledger.passesUsed);
    }

    boolean countingNow() {
        return countingPkg != null;
    }

    String lastSeenPackage() {
        return lastSeenPkg;
    }

    long lastSeenWallTime() {
        return lastSeenWall;
    }

    boolean watcherRunning() {
        return service != null;
    }

    Policy.Outcome requestRules(Rules wanted) {
        sync();
        Policy.Outcome o = st.policy.request(wanted);
        save(true);
        requestEval(0);
        return o;
    }

    void cancelPending() {
        sync();
        st.policy.cancelPending();
        save(true);
    }

    void lockSettings() {
        sync();
        st.policy.locked = true;
        save(true);
    }

    /** Starts an audio pass. Returns false if none are left today. */
    boolean startPass() {
        sync();
        if (passesLeft() <= 0) return false;
        st.ledger.passesUsed++;
        passUntil = SystemClock.elapsedRealtime() + st.policy.active.passSec * 1000L;
        endEpisode();
        save(true);
        return true;
    }

    /** Whether OwnDroid has given this app the "Change package state" delegation. */
    boolean hasDelegation() {
        DevicePolicyManager dpm = app.getSystemService(DevicePolicyManager.class);
        if (dpm == null) return false;
        try {
            List<String> scopes = dpm.getDelegatedScopes(null, app.getPackageName());
            return scopes != null && scopes.contains(DevicePolicyManager.DELEGATION_PACKAGE_ACCESS);
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean uninstallBlocked() {
        DevicePolicyManager dpm = app.getSystemService(DevicePolicyManager.class);
        try {
            return dpm != null && dpm.isUninstallBlocked(null, app.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean autoTime() {
        return Settings.Global.getInt(app.getContentResolver(), Settings.Global.AUTO_TIME, 1) != 0;
    }

    boolean dateTimeLocked() {
        UserManager um = app.getSystemService(UserManager.class);
        return um != null && um.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME);
    }

    String label(String pkg) {
        PackageManager pm = app.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            String known = Tracked.BUILT_IN.get(pkg);
            return known != null ? known : pkg;
        }
    }
}
