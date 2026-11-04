package io.github.zinzuzu.feedguard;

import android.content.Context;
import android.content.RestrictionsManager;
import android.os.Bundle;

import java.util.Set;

/**
 * Feed Guard's managed configuration, set in OwnDroid (Applications › Feed
 * Guard › Managed configuration). OwnDroid only stores the keys someone set,
 * so a missing key means the default below.
 */
final class Config {
    static final String ALLOWED = "allowed_apps";
    static final String BLOCKED = "blocked_apps";
    static final String ONLY_LISTED = "only_listed_apps";
    static final String GRAYSCALE = "grayscale";
    static final String COLOR_APPS = "color_apps";

    /** The list: apps Feed Guard never pauses. Default: none. */
    final Set<String> allowed;
    /** More apps to pause like browsers. Default: none. */
    final Set<String> extraBlocked;
    /** Pause every app that isn't on the list. Default: off. */
    final boolean listMode;
    /** Keep the screen black and white. Default: on (it needs the adb permission anyway). */
    final boolean grayscale;
    /** Apps that may show colour. Default: the maps apps in ColorRules. */
    final Set<String> colourApps;

    private Config(Bundle b) {
        allowed = AppRules.parseList(string(b, ALLOWED));
        extraBlocked = AppRules.parseList(string(b, BLOCKED));
        listMode = bool(b, ONLY_LISTED, false);
        grayscale = bool(b, GRAYSCALE, true);
        colourApps = ColorRules.colourApps(b.containsKey(COLOR_APPS) ? string(b, COLOR_APPS) : null);
    }

    static Config read(Context c) {
        Bundle b = null;
        try {
            RestrictionsManager rm = c.getSystemService(RestrictionsManager.class);
            b = rm == null ? null : rm.getApplicationRestrictions();
        } catch (RuntimeException ignored) {
            // No configuration readable: defaults.
        }
        return new Config(b == null ? new Bundle() : b);
    }

    @SuppressWarnings("deprecation")
    private static String string(Bundle b, String key) {
        Object v = b.get(key);
        return v == null ? "" : v.toString();
    }

    /** Accepts a real boolean, or "true"/"false" in case a tool stores text. */
    @SuppressWarnings("deprecation")
    private static boolean bool(Bundle b, String key, boolean fallback) {
        Object v = b.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return fallback;
    }
}
