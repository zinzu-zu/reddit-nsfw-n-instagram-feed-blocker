package io.github.zinzuzu.feedguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the screen black and white with Android's colour correction set to
 * Grayscale, and lets colour back only while nothing but colour apps (maps)
 * are on screen. If anything switches colour correction off or to another
 * mode, it's back within a moment.
 *
 * Runs inside the accessibility service, which tells it when windows change.
 * When the service isn't running, WatchdogJob keeps the screen black and
 * white without the maps exception (enforceAlone).
 */
final class ColorGuard {
    /** Colour comes back only after the screen has shown just colour apps this long. */
    private static final long SETTLE_MS = 300;
    /** While in colour, look again this often, in case a window change was missed. */
    private static final long COLOUR_RECHECK_MS = 2000;
    /** After a window change, let the windows settle this long. */
    private static final long WINDOW_DELAY_MS = 50;

    private final AccessibilityService service;
    private final Handler bg;
    private final ContentObserver observer;
    private volatile Config config;
    private boolean observing;
    /** Set by stop(); after that no colour is let back, even by a check already running. */
    private boolean stopped;
    private long dueAt = Long.MAX_VALUE;
    private long colourSince = -1;

    private final Runnable evaluate = new Runnable() {
        @Override public void run() { evaluate(); }
    };

    ColorGuard(AccessibilityService service, Handler bg) {
        this.service = service;
        this.bg = bg;
        this.observer = new ContentObserver(bg) {
            @Override public void onChange(boolean selfChange) { queue(0); }
        };
    }

    /** Call on the background thread. */
    void start() {
        config = Config.read(service);
        try {
            service.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(SecureSettings.DALTONIZER_ENABLED), false, observer);
            service.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(SecureSettings.DALTONIZER), false, observer);
            observing = true;
        } catch (RuntimeException e) {
            Log.w(Guard.TAG, "can't watch colour correction", e);
        }
        queue(0);
    }

    void stop() {
        if (observing) {
            try {
                service.getContentResolver().unregisterContentObserver(observer);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            observing = false;
        }
        synchronized (this) {
            stopped = true;
            bg.removeCallbacks(evaluate);
            dueAt = Long.MAX_VALUE;
        }
    }

    void onWindowsChanged() {
        queue(WINDOW_DELAY_MS);
    }

    /** Call on the background thread. */
    void onConfigChanged() {
        config = Config.read(service);
        queue(0);
    }

    /** Runs a check after delayMs, unless one is already due sooner. */
    private synchronized void queue(long delayMs) {
        if (stopped) return;
        long at = SystemClock.uptimeMillis() + delayMs;
        if (at >= dueAt) return;
        bg.removeCallbacks(evaluate);
        dueAt = at;
        bg.postAtTime(evaluate, at);
    }

    private void evaluate() {
        synchronized (this) {
            dueAt = Long.MAX_VALUE;
        }
        try {
            evaluateNow();
        } catch (RuntimeException e) {
            Log.e(Guard.TAG, "colour check failed", e);
            colourSince = -1;
            SecureSettings.setGray(service, true);
        }
    }

    private void evaluateNow() {
        Config cfg = config;
        if (cfg == null || !SecureSettings.granted(service)) return;
        if (!cfg.grayscale) {
            colourSince = -1;
            SecureSettings.releaseGray(service);
            return;
        }
        boolean colour = !cfg.colourApps.isEmpty()
                && ColorRules.colourAllowed(windows(), screenArea(), cfg.colourApps);
        if (!colour) {
            colourSince = -1;
            SecureSettings.setGray(service, true);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (colourSince < 0) colourSince = now;
        long wait = SETTLE_MS - (now - colourSince);
        if (wait > 0) {
            queue(wait);
            return;
        }
        synchronized (this) {
            if (stopped) return;
            SecureSettings.setGray(service, false);
        }
        queue(COLOUR_RECHECK_MS);
    }

    /** Without the service there's no telling what's on screen: black and white, no exceptions. */
    static void enforceAlone(Context c) {
        if (!SecureSettings.granted(c)) return;
        if (Config.read(c).grayscale) {
            SecureSettings.setGray(c, true);
        } else {
            SecureSettings.releaseGray(c);
        }
    }

    private List<ColorRules.Win> windows() {
        List<AccessibilityWindowInfo> list;
        try {
            list = service.getWindows();
        } catch (RuntimeException e) {
            return null;
        }
        if (list == null) return null;
        List<ColorRules.Win> out = new ArrayList<>(list.size());
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : list) {
            int kind = kind(w.getType());
            w.getBoundsInScreen(r);
            long area = (long) Math.max(0, r.width()) * Math.max(0, r.height());
            out.add(new ColorRules.Win(kind, kind == ColorRules.APP ? packageOf(w) : null, area));
        }
        return out;
    }

    private static int kind(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return ColorRules.APP;
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return ColorRules.KEYBOARD;
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return ColorRules.SYSTEM;
            default:
                // Accessibility overlays (Feed Guard's own cover), split-screen divider, magnifier.
                return ColorRules.IGNORED;
        }
    }

    /** Only the window's app is read here, never its content. */
    private static String packageOf(AccessibilityWindowInfo w) {
        try {
            AccessibilityNodeInfo root;
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    root = w.getRoot(0); // just the root node, nothing below it
                } catch (IllegalArgumentException e) {
                    root = w.getRoot();
                }
            } else {
                root = w.getRoot();
            }
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private long screenArea() {
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        return (long) dm.widthPixels * dm.heightPixels;
    }
}
