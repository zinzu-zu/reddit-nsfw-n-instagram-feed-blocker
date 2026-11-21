package io.github.zinzuzu.feedguard;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Banking mode. Bank apps such as Nubank refuse to open while an accessibility
 * service installed from outside the Play Store is switched on, because that is
 * what remote-access scams ("golpe da mão fantasma") use to empty accounts.
 * Feed Guard and Watch Limit are such services. So while a bank app opens,
 * Feed Guard switches its own and Watch Limit's accessibility access off for a
 * short while, then switches both back on.
 *
 * Nothing they guard can open meanwhile: Feed Guard pauses Firefox first, and
 * Watch Limit pauses the YouTube apps by itself when it's switched off. Apps
 * installed meanwhile are caught by a check every few seconds. Banking mode
 * only switches the guards off while it really is off, so the bank's own check
 * sees the phone as it is; nothing is hidden from it.
 *
 * Its settings live here, in one place. The decisions are pure Java, so they
 * can be tested off the phone.
 */
final class BankRules {
    private BankRules() {}

    /**
     * How long the guards stay off once a bank app opens. Opening one again starts
     * the time again. The texts in res/values/strings.xml and the README say
     * "10 minutes" too.
     */
    static final long WINDOW_MS = 10 * 60_000L;
    /** While the guards are off, look for newly installed apps (and whether the time is up) this often. */
    static final long CHECK_EVERY_MS = 20_000L;
    /** The bank apps when bank_apps isn't set: Nubank. */
    static final Set<String> DEFAULT_BANK_APPS = AppRules.set("com.nu.production");
    /** Feed Guard and Watch Limit: the accessibility services banking mode switches off, and only these. */
    static final Set<String> GUARD_PACKAGES = AppRules.set("io.github.zinzuzu.feedguard", AppRules.WATCH_LIMIT);
    /** What BootCount reads as when Android doesn't say. */
    static final long UNKNOWN_BOOT = -1;

    /**
     * The bank apps set in Feed Guard's managed configuration, minus anything
     * that must never switch the guards off.
     *
     * @param value bank_apps, or null if it was never set
     */
    static Set<String> bankApps(String value) {
        Set<String> out;
        if (value == null) {
            out = new HashSet<>(DEFAULT_BANK_APPS);
        } else {
            String v = value.trim();
            if (v.isEmpty() || v.toLowerCase(Locale.ROOT).equals("none")) return Collections.emptySet();
            out = new HashSet<>(AppRules.parseList(v));
        }
        for (Iterator<String> it = out.iterator(); it.hasNext(); ) {
            if (!mayBeBank(it.next())) it.remove();
        }
        return out;
    }

    /**
     * Whether an app may be a bank app. Firefox, the Instagram and Reddit apps,
     * the Play Store, the lock itself and the phone's basics never can: listing
     * one of them must not be a way to switch the guards off. (Browsers are left
     * out on the phone too, where Feed Guard can tell which apps open web pages.)
     */
    static boolean mayBeBank(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (AppRules.FIREFOX.equals(pkg) || AppRules.isFeedApp(pkg)) return false;
        if (AppRules.NOT_A_BROWSER.contains(pkg)) return false;
        if (AppRules.ESSENTIAL.contains(pkg)) return false;
        return !GUARD_PACKAGES.contains(pkg);
    }

    /**
     * Whether a banking mode started earlier still holds. When in doubt, it
     * doesn't, and the guards switch back on: after a restart, when Android
     * doesn't say which start-up this is, once the time is up, or if the end
     * time is further away than one window could ever be.
     *
     * @param storedBoot the start-up count when banking mode began
     * @param untilElapsed when it ends, in time since start-up (ms); 0 if none
     * @param nowBoot the start-up count now
     * @param nowElapsed time since start-up now (ms)
     */
    static boolean active(long storedBoot, long untilElapsed, long nowBoot, long nowElapsed) {
        if (untilElapsed <= 0) return false;
        if (storedBoot == UNKNOWN_BOOT || nowBoot == UNKNOWN_BOOT || storedBoot != nowBoot) return false;
        if (nowElapsed >= untilElapsed) return false;
        return untilElapsed - nowElapsed <= WINDOW_MS;
    }

    /** When a banking mode starting now ends, in time since start-up (ms). */
    static long deadline(long nowElapsed) {
        return nowElapsed + WINDOW_MS;
    }
}
