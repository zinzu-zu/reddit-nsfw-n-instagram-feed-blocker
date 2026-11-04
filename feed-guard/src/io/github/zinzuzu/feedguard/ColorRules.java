package io.github.zinzuzu.feedguard;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * When the screen may show colour: only while nothing but colour apps (maps)
 * are on screen. Any other app, the keyboard, the notification shade or the
 * lock screen turns it black and white again. When in doubt, black and white.
 *
 * Pure Java, so it can be tested off the phone.
 */
final class ColorRules {
    private ColorRules() {}

    /** Window kinds, mapped from AccessibilityWindowInfo's types by ColorGuard. */
    static final int IGNORED = 0;   // accessibility overlays (Feed Guard's own cover), split-screen divider, magnifier
    static final int APP = 1;       // an app's window, picture-in-picture included
    static final int KEYBOARD = 2;
    static final int SYSTEM = 3;    // status bar, navigation bar, shade, lock screen, volume panel, apps drawing over others

    /** Google Maps, CoMaps (Play and F-Droid builds) and Organic Maps. */
    static final Set<String> DEFAULT_COLOUR_APPS = AppRules.set(
            "com.google.android.apps.maps",
            "app.comaps.google",
            "app.comaps.fdroid",
            "app.organicmaps");

    static final class Win {
        final int kind;
        /** The app, for APP windows; null if it couldn't be read. */
        final String pkg;
        /** Width x height in pixels. */
        final long area;

        Win(int kind, String pkg, long area) {
            this.kind = kind;
            this.pkg = pkg;
            this.area = area;
        }
    }

    /**
     * @param windows what's on screen; null if it couldn't be read
     * @param screenArea width x height of the screen in pixels
     * @param colourApps packages allowed to show colour
     */
    static boolean colourAllowed(List<Win> windows, long screenArea, Set<String> colourApps) {
        if (windows == null || colourApps == null || colourApps.isEmpty() || screenArea <= 0) return false;
        boolean colourApp = false;
        for (Win w : windows) {
            if (w == null) continue;
            switch (w.kind) {
                case APP:
                    if (w.pkg == null || !colourApps.contains(w.pkg)) return false;
                    colourApp = true;
                    break;
                case KEYBOARD:
                    return false;
                case SYSTEM:
                    // The status and navigation bars are thin strips. The shade, the lock
                    // screen and big overlays cover at least half the screen.
                    if (w.area * 2 >= screenArea) return false;
                    break;
                default:
                    break;
            }
        }
        return colourApp;
    }

    /**
     * The colour apps set in Feed Guard's managed configuration.
     *
     * @param value color_apps, or null if your friend never set it
     */
    static Set<String> colourApps(String value) {
        if (value == null) return DEFAULT_COLOUR_APPS;
        String v = value.trim();
        if (v.isEmpty() || v.toLowerCase(Locale.ROOT).equals("none")) return Collections.emptySet();
        return AppRules.parseList(v);
    }
}
