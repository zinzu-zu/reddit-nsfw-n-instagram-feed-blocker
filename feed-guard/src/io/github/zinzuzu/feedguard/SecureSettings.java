package io.github.zinzuzu.feedguard;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * What the secure settings permission (WRITE_SECURE_SETTINGS, granted once
 * over adb) is used for, and nothing else: keeping colour correction on
 * Grayscale, and turning Feed Guard's own accessibility access back on.
 */
final class SecureSettings {
    private SecureSettings() {}

    /** Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED (hidden): colour correction on or off. */
    static final String DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled";
    /** Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER (hidden): which colour correction. */
    static final String DALTONIZER = "accessibility_display_daltonizer";
    /** AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY: "Grayscale" under Color correction. */
    static final int MONOCHROMACY = 0;

    static final String ADB_GRANT =
            "adb shell pm grant io.github.zinzuzu.feedguard android.permission.WRITE_SECURE_SETTINGS";

    static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Turns black and white on (true) or off (false). Returns whether anything was written. */
    static synchronized boolean setGray(Context c, boolean gray) {
        if (!granted(c)) return false;
        ContentResolver cr = c.getContentResolver();
        boolean wrote = false;
        try {
            if (gray) {
                // The mode first, so switching on never flashes another colour filter.
                if (!is(readInt(cr, DALTONIZER), MONOCHROMACY)) {
                    Settings.Secure.putInt(cr, DALTONIZER, MONOCHROMACY);
                    wrote = true;
                }
                if (!is(readInt(cr, DALTONIZER_ENABLED), 1)) {
                    Settings.Secure.putInt(cr, DALTONIZER_ENABLED, 1);
                    wrote = true;
                }
                SharedPreferences p = Guard.prefs(c);
                if (!p.getBoolean(Guard.K_GRAY_OWNED, false)) p.edit().putBoolean(Guard.K_GRAY_OWNED, true).apply();
            } else if (!is(readInt(cr, DALTONIZER_ENABLED), 0)) {
                Settings.Secure.putInt(cr, DALTONIZER_ENABLED, 0);
                wrote = true;
            }
        } catch (SecurityException e) {
            Log.w(Guard.TAG, "can't write colour correction", e);
        }
        return wrote;
    }

    /**
     * Black and white was turned off in OwnDroid: give colour back once, then
     * leave colour correction alone, so you can still use it yourself.
     */
    static synchronized void releaseGray(Context c) {
        SharedPreferences p = Guard.prefs(c);
        if (!p.getBoolean(Guard.K_GRAY_OWNED, false) || !granted(c)) return;
        try {
            Settings.Secure.putInt(c.getContentResolver(), DALTONIZER_ENABLED, 0);
            p.edit().putBoolean(Guard.K_GRAY_OWNED, false).apply();
        } catch (SecurityException e) {
            Log.w(Guard.TAG, "can't write colour correction", e);
        }
    }

    /**
     * Puts Feed Guard back among the switched-on accessibility services, if
     * something took it out. Returns whether it's switched on now.
     */
    static synchronized boolean keepServiceOn(Context c) {
        ContentResolver cr = c.getContentResolver();
        String me = new ComponentName(c, GuardService.class).flattenToString();
        String list;
        try {
            list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (RuntimeException e) {
            return false;
        }
        String updated = ServiceList.withService(list, me);
        if (updated == null) return true;
        if (!granted(c)) return false;
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Guard.prefs(c).edit().putLong(Guard.K_TURNED_BACK_ON_AT, System.currentTimeMillis()).apply();
            Log.i(Guard.TAG, "accessibility access was switched off; switched it back on");
            return true;
        } catch (SecurityException e) {
            Log.w(Guard.TAG, "can't switch accessibility access back on", e);
            return false;
        }
    }

    private static boolean is(Integer value, int expected) {
        return value != null && value == expected;
    }

    /** null when unset or when Android doesn't let apps read it: then it's simply written. */
    private static Integer readInt(ContentResolver cr, String key) {
        try {
            return Settings.Secure.getInt(cr, key);
        } catch (Settings.SettingNotFoundException | SecurityException e) {
            return null;
        }
    }
}
