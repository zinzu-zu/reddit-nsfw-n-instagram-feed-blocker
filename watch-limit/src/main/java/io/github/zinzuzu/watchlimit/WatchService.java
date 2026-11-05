package io.github.zinzuzu.watchlimit;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The watcher. It reads which app windows are on screen; it never reads what's inside
 * them beyond the app's package name, and the app has no internet access.
 */
public final class WatchService extends AccessibilityService {

    /** Which watched apps have a window on screen right now. */
    static final class Seen {
        static final Seen NOTHING = new Seen(null, null);

        /** A watched app shown normally: full screen, split screen or a floating window. */
        final String fullPkg;
        /** A watched app in picture-in-picture. */
        final String pipPkg;

        Seen(String fullPkg, String pipPkg) {
            this.fullPkg = fullPkg;
            this.pipPkg = pipPkg;
        }

        String any() {
            return fullPkg != null ? fullPkg : pipPkg;
        }
    }

    /** Package per window id, from window events, for windows whose content can't be read. */
    private final Map<Integer, String> windowPackages = new HashMap<Integer, String>();

    @Override
    protected void onServiceConnected() {
        Engine.get(this).onServiceConnected(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            if (windowPackages.size() > 64) windowPackages.clear();
            windowPackages.put(event.getWindowId(), event.getPackageName().toString());
        }
        Engine.get(this).requestEval(Engine.EVENT_DELAY_MS);
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: this service gives no spoken or haptic feedback.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Engine.get(this).onServiceGone(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Engine.get(this).onServiceGone(this);
        super.onDestroy();
    }

    Seen scan(Set<String> watched) {
        String full = null;
        String pip = null;
        List<AccessibilityWindowInfo> windows = windows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            String pkg = packageOf(w);
            if (pkg == null || !watched.contains(pkg)) continue;
            if (w.isInPictureInPictureMode()) {
                pip = pkg;
            } else if (full == null) {
                full = pkg;
            }
        }
        recycle(windows);
        return full == null && pip == null ? Seen.NOTHING : new Seen(full, pip);
    }

    /** Closes the picture-in-picture window of any watched app. */
    void closePictureInPicture(Set<String> watched) {
        List<AccessibilityWindowInfo> windows = windows();
        for (AccessibilityWindowInfo w : windows) {
            if (!w.isInPictureInPictureMode()) continue;
            AccessibilityNodeInfo root = null;
            try {
                root = rootOf(w);
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg != null && watched.contains(pkg.toString())) {
                    // On a picture-in-picture window, the system routes this action to
                    // the PiP controls, where it closes the window.
                    root.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
                }
            } catch (RuntimeException ignored) {
                // The window went away meanwhile.
            } finally {
                recycle(root);
            }
        }
        recycle(windows);
    }

    private List<AccessibilityWindowInfo> windows() {
        try {
            List<AccessibilityWindowInfo> w = getWindows();
            if (w != null) return w;
        } catch (RuntimeException ignored) {
            // The system can throw while windows change.
        }
        return Collections.emptyList();
    }

    private String packageOf(AccessibilityWindowInfo w) {
        AccessibilityNodeInfo root = null;
        try {
            root = rootOf(w);
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        } catch (RuntimeException ignored) {
            // Fall back to what window events said.
        } finally {
            recycle(root);
        }
        return windowPackages.get(w.getId());
    }

    /** The window's root node only, without fetching the rest of the app's views. */
    private static AccessibilityNodeInfo rootOf(AccessibilityWindowInfo w) {
        return Build.VERSION.SDK_INT >= 33 ? w.getRoot(0) : w.getRoot();
    }

    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo n) {
        if (n != null && Build.VERSION.SDK_INT < 33) n.recycle();
    }

    @SuppressWarnings("deprecation")
    private static void recycle(List<AccessibilityWindowInfo> windows) {
        if (Build.VERSION.SDK_INT >= 33) return;
        for (AccessibilityWindowInfo w : windows) w.recycle();
    }
}
