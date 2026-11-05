package io.github.zinzuzu.feedguard;

import android.accessibilityservice.AccessibilityService;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The guard itself. While Firefox is on screen it checks, at most every 0.6 s
 * and at least every 2 s, whether the page is Instagram or Reddit and whether
 * the add-on's marker is there. Checks run on a background thread; the cover is
 * shown and hidden on the main thread.
 *
 * It also pauses apps through OwnDroid (AppGate), keeps the screen black and
 * white (ColorGuard), and if its accessibility access is switched off, it
 * switches it back on, or failing that pauses Firefox.
 */
public final class GuardService extends AccessibilityService {
    private static final long MIN_GAP_MS = 600;
    private static final long RECHECK_MS = 2000;
    /** Write status timestamps at most this often. */
    private static final long STATUS_WRITE_MS = 60_000;
    /** The address bar counts as unreadable after this long without finding it while a page is up. */
    private static final long URL_UNREADABLE_MS = 60_000;
    /** Package changes come in bursts (an update can fire several): scan once they settle. */
    private static final long SCAN_DELAY_MS = 400;

    private static volatile boolean connected;

    /** Whether the service is connected and guarding, in this process. */
    static boolean isRunning() {
        return connected;
    }

    private HandlerThread worker;
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Overlay overlay;
    private ColorGuard colors;
    private final CoverDecision decision = new CoverDecision();

    private volatile boolean running;
    private volatile boolean checkQueued;
    private volatile boolean firefoxWasVisible;
    private long lastCheckAt;
    private long lastMarkerWrite, lastUrlWrite;
    private long urlMissingSince = -1;
    private boolean receiversRegistered;
    private final Set<String> freshInstalls = new HashSet<>();
    private boolean scanQueued;

    private final Runnable check = new Runnable() {
        @Override public void run() { runCheck(); }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (worker == null) {
            worker = new HandlerThread("feedguard");
            worker.start();
            bg = new Handler(worker.getLooper());
        }
        overlay = new Overlay(this);
        if (colors != null) colors.stop();
        final ColorGuard cg = new ColorGuard(this, bg);
        colors = cg;
        running = true;
        connected = true;
        registerReceivers();
        bg.post(new Runnable() {
            @Override public void run() {
                cg.start();
                AppGate.releaseFirefox(GuardService.this);
                AppGate.scan(GuardService.this, null);
                WatchdogJob.schedule(GuardService.this);
            }
        });
        queueCheck(0);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || bg == null) return;
        int type = event.getEventType();
        CharSequence pkg = event.getPackageName();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            ColorGuard cg = colors;
            if (cg != null) cg.onWindowsChanged();
        }
        boolean fromFirefox = pkg != null && Guard.FIREFOX.contentEquals(pkg);
        if (fromFirefox || firefoxWasVisible
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            queueCheck(0);
        }
    }

    @Override
    public void onInterrupt() {
        // Nothing is spoken or announced, so there's nothing to interrupt.
    }

    /**
     * Called when the access is switched off. With the secure settings
     * permission Feed Guard switches it straight back on. Without it, the
     * dead-man switch pauses Firefox.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        boolean stillOn = SecureSettings.keepServiceOn(this);
        if (!stillOn && Guard.armed(this)) AppGate.pauseFirefox(this);
        WatchdogJob.checkSoon(this);
        shutDown();
        return false;
    }

    @Override
    public void onDestroy() {
        shutDown();
        super.onDestroy();
    }

    private void shutDown() {
        running = false;
        connected = false;
        if (overlay != null) {
            overlay.hide();
            overlay = null;
        }
        if (colors != null) {
            colors.stop(); // after this, no check can let colour back
            colors = null;
            // Nothing watches which app is on screen any more: no colour exception for maps.
            ColorGuard.enforceAlone(this);
        }
        if (receiversRegistered) {
            try {
                unregisterReceiver(packageReceiver);
                unregisterReceiver(configReceiver);
            } catch (RuntimeException ignored) {
                // Not registered.
            }
            receiversRegistered = false;
        }
        synchronized (this) {
            freshInstalls.clear();
            scanQueued = false;
        }
        if (worker != null) {
            worker.quitSafely();
            worker = null;
            bg = null;
        }
    }

    private void queueCheck(long delayMs) {
        Handler h = bg;
        if (h == null || checkQueued) return;
        checkQueued = true;
        long sinceLast = SystemClock.elapsedRealtime() - lastCheckAt;
        h.postDelayed(check, Math.max(delayMs, MIN_GAP_MS - sinceLast));
    }

    private void runCheck() {
        checkQueued = false;
        lastCheckAt = SystemClock.elapsedRealtime();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException e) {
            windows = null;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        PageInspector.Snapshot snap;
        try {
            snap = PageInspector.inspect(windows, (long) dm.widthPixels * dm.heightPixels);
        } catch (RuntimeException e) {
            Log.w(Guard.TAG, "inspect failed", e);
            snap = new PageInspector.Snapshot();
            snap.firefoxVisible = firefoxWasVisible;
        }

        SharedPreferences prefs = Guard.prefs(this);
        CoverDecision.Input in = new CoverDecision.Input();
        in.firefoxVisible = snap.firefoxVisible;
        in.urlBoxFound = snap.urlBoxFound;
        in.urlText = snap.urlText;
        in.loading = snap.loading;
        in.pageTitle = snap.pageTitle;
        in.markerLabel = snap.markerLabel;
        in.armed = prefs.getLong(Guard.K_ARMED_AT, 0) > 0;
        in.nowElapsedMs = lastCheckAt;
        in.nowWallMs = System.currentTimeMillis();
        CoverDecision.Outcome out = decision.decide(in);

        recordStatus(prefs, snap, out, in);
        if (snap.firefoxVisible) LastLook.record(snap, out, in.nowWallMs);

        final boolean cover = out.cover;
        main.post(new Runnable() {
            @Override public void run() {
                Overlay o = overlay;
                if (o == null) return;
                if (cover && running) o.show(); else o.hide();
            }
        });

        firefoxWasVisible = snap.firefoxVisible;
        if (snap.firefoxVisible) queueCheck(RECHECK_MS);
    }

    private void recordStatus(SharedPreferences prefs, PageInspector.Snapshot snap,
            CoverDecision.Outcome out, CoverDecision.Input in) {
        SharedPreferences.Editor e = null;
        long now = in.nowWallMs;
        if (out.arm) {
            e = prefs.edit().putLong(Guard.K_ARMED_AT, now);
            Guard.notify(this, Guard.N_ARMED, "Feed Guard is on",
                    "It saw the add-on running. From now on, Instagram and Reddit only open in Firefox while the add-on is on.",
                    new Intent(this, MainActivity.class));
        }
        if (out.markerFresh && in.nowElapsedMs - lastMarkerWrite > STATUS_WRITE_MS) {
            lastMarkerWrite = in.nowElapsedMs;
            e = (e == null ? prefs.edit() : e).putLong(Guard.K_MARKER_SEEN_AT, now);
        }
        if (snap.urlBoxFound) {
            urlMissingSince = -1;
            if (in.nowElapsedMs - lastUrlWrite > STATUS_WRITE_MS || prefs.getBoolean(Guard.K_URL_UNREADABLE, false)) {
                lastUrlWrite = in.nowElapsedMs;
                e = (e == null ? prefs.edit() : e).putLong(Guard.K_URL_SEEN_AT, now).putBoolean(Guard.K_URL_UNREADABLE, false);
                Guard.cancel(this, Guard.N_URL_UNREADABLE);
            }
        } else if (snap.firefoxVisible && snap.webContent) {
            if (urlMissingSince < 0) urlMissingSince = in.nowElapsedMs;
            if (in.nowElapsedMs - urlMissingSince > URL_UNREADABLE_MS && !prefs.getBoolean(Guard.K_URL_UNREADABLE, false)) {
                e = (e == null ? prefs.edit() : e).putBoolean(Guard.K_URL_UNREADABLE, true);
                Guard.notify(this, Guard.N_URL_UNREADABLE, "Feed Guard can't read Firefox's address bar",
                        "Firefox may have changed. Until Feed Guard is updated, it goes by page titles, which catches the Instagram and Reddit home feeds but not every page.",
                        new Intent(this, MainActivity.class));
            }
        }
        if (out.cover && !overlayShownForStatus) {
            e = (e == null ? prefs.edit() : e).putLong(Guard.K_LAST_COVER_AT, now);
        }
        overlayShownForStatus = out.cover;
        if (e != null) e.apply();
    }

    private boolean overlayShownForStatus;

    private void registerReceivers() {
        if (receiversRegistered) return;
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        // Managed configuration changed, or OwnDroid just gave (or took back) the
        // "Change package state" permission: scan again straight away rather than
        // at the next 15-minute check. The delegation broadcast only reaches
        // receivers registered at runtime, like this one.
        IntentFilter config = new IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);
        config.addAction(DevicePolicyManager.ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED);
        // Someone lifted a pause (OwnDroid, Watch Limit): with "Only apps on the
        // list" on, an app that isn't listed gets paused again.
        config.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(configReceiver, config, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packages);
            registerReceiver(configReceiver, config);
        }
        receiversRegistered = true;
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            Uri data = intent.getData();
            if (data == null) return;
            boolean fresh = Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                    && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            queueScan(fresh ? data.getSchemeSpecificPart() : null);
        }
    };

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (!Intent.ACTION_PACKAGES_UNSUSPENDED.equals(intent.getAction())) {
                final Handler h = bg;
                if (h != null) {
                    h.post(new Runnable() {
                        @Override public void run() {
                            ColorGuard cg = colors;
                            if (cg != null) cg.onConfigChanged();
                        }
                    });
                }
            }
            queueScan(null);
        }
    };

    /**
     * Scans on the background thread. A newly installed app is scanned at once,
     * so it's paused before it can be opened; other changes wait a moment and
     * share one scan.
     */
    private synchronized void queueScan(String freshInstall) {
        Handler h = bg;
        if (h == null) return;
        if (freshInstall != null) {
            freshInstalls.add(freshInstall);
            h.removeCallbacks(runScan);
            scanQueued = true;
            h.post(runScan);
            return;
        }
        if (scanQueued) return;
        scanQueued = true;
        h.postDelayed(runScan, SCAN_DELAY_MS);
    }

    private final Runnable runScan = new Runnable() {
        @Override public void run() {
            Set<String> fresh;
            synchronized (GuardService.this) {
                fresh = new HashSet<>(freshInstalls);
                freshInstalls.clear();
                scanQueued = false;
            }
            AppGate.scan(GuardService.this, fresh);
        }
    };
}
