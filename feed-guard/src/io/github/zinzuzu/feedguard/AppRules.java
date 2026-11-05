package io.github.zinzuzu.feedguard;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which installed apps Feed Guard pauses. Two rules:
 *
 * - The browser rule, always on: every browser except Firefox, and apps for
 *   Instagram and Reddit. Firefox is the only browser that runs the add-on, so
 *   any other browser, or the apps themselves, would show the feeds. Each app
 *   is paused once: if your friend unpauses it in OwnDroid, it's left alone.
 * - The list rule, only with "Only apps on the list" on: every app with an
 *   icon in the app drawer, and every app you installed yourself, unless it's
 *   on the list or one of the phone's basics. This one keeps re-pausing: the
 *   list is the only way to let an app run.
 *
 * Pure Java, so it can be tested off the phone.
 */
final class AppRules {
    private AppRules() {}

    static final String FIREFOX = "org.mozilla.firefox";
    static final String OWNDROID = "com.bintianqi.owndroid";
    static final String WATCH_LIMIT = "io.github.zinzuzu.watchlimit";

    /**
     * Never paused by either rule: the lock itself, and the parts of Android
     * that calls, messages, typing and the other basics need. AppGate adds the
     * phone's own handlers for those basics and the defaults you picked.
     */
    static final Set<String> ESSENTIAL = set(
            OWNDROID,
            WATCH_LIMIT,
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            // Pixel Launcher also draws the recent apps screen and the gestures.
            "com.google.android.apps.nexuslauncher",
            // The file picker other apps open, for attachments.
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.google.android.contacts",
            "com.google.android.deskclock",
            "com.google.android.inputmethod.latin",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.stk",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.google.android.cellbroadcastreceiver",
            "com.android.cellbroadcastreceiver");

    /**
     * Open web links but aren't browsers for the browser rule: Firefox, which
     * runs the add-on, the Play Store and the Google app. The list rule still
     * pauses them unless they're on the list.
     */
    static final Set<String> NOT_A_BROWSER = set(
            FIREFOX,
            "com.android.vending",
            "com.google.android.googlequicksearchbox");

    /** Instagram and Reddit apps whose package names don't say so. */
    static final Set<String> FEED_APPS = set(
            "com.instander.android",
            "org.quantumbadger.redreader",
            "com.onelouder.baconreader",
            "o.o.joey");

    /** Named like a feed app but left alone: Threads. */
    static final Set<String> NOT_FEED_APPS = set("com.instagram.barcelona");

    static boolean isFeedApp(String pkg) {
        if (pkg == null) return false;
        if (FEED_APPS.contains(pkg)) return true;
        if (NOT_FEED_APPS.contains(pkg)) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("instagram") || p.contains("reddit");
    }

    /** What the phone looks like, and what your friend set in OwnDroid. */
    static final class Inputs {
        /** Every installed package. */
        Set<String> installed = Collections.emptySet();
        /** Packages with an icon in the app drawer. */
        Set<String> launchable = Collections.emptySet();
        /** Packages you installed, as opposed to ones that came with the phone. */
        Set<String> userInstalled = Collections.emptySet();
        /** Packages that open any web address. */
        Set<String> browsers = Collections.emptySet();
        /** The phone's basics on top of ESSENTIAL, including Feed Guard itself. */
        Set<String> essential = Collections.emptySet();
        /** The list (allowed_apps), minus copies that failed their signature check. */
        Set<String> allowed = Collections.emptySet();
        /** blocked_apps. */
        Set<String> extraBlocked = Collections.emptySet();
        /** only_listed_apps. */
        boolean listMode;
        /** Apps the browser rule already dealt with. */
        Set<String> handled = Collections.emptySet();
        /** Apps Feed Guard paused. */
        Set<String> ours = Collections.emptySet();
        /** Packages to leave exactly as they are this time: Firefox while the dead-man switch holds it. */
        Set<String> skip = Collections.emptySet();
    }

    static final class Plan {
        /** Wanted paused by the browser rule (whether or not already dealt with). */
        final Set<String> browserRule = new TreeSet<>();
        /** Wanted paused by the list rule. */
        final Set<String> listRule = new TreeSet<>();
        /** To pause now, unless something already paused them. */
        final Set<String> pause = new TreeSet<>();
        /** Feed Guard's own pauses that no rule wants any more. */
        final Set<String> release = new TreeSet<>();
    }

    static Plan plan(Inputs in) {
        Plan p = new Plan();
        for (String pkg : in.installed) {
            if (pkg == null || ESSENTIAL.contains(pkg) || in.essential.contains(pkg)) continue;
            if (in.allowed.contains(pkg)) continue;
            boolean browserLike = in.browsers.contains(pkg) || isFeedApp(pkg) || in.extraBlocked.contains(pkg);
            if (browserLike && !NOT_A_BROWSER.contains(pkg)) p.browserRule.add(pkg);
            if (in.listMode && (browserLike || in.launchable.contains(pkg) || in.userInstalled.contains(pkg))) {
                p.listRule.add(pkg);
            }
        }
        for (String pkg : p.browserRule) {
            if (!in.handled.contains(pkg)) p.pause.add(pkg);
        }
        p.pause.addAll(p.listRule);
        p.pause.removeAll(in.skip);
        for (String pkg : in.ours) {
            if (in.installed.contains(pkg) && !p.browserRule.contains(pkg) && !p.listRule.contains(pkg)
                    && !in.skip.contains(pkg)) {
                p.release.add(pkg);
            }
        }
        return p;
    }

    /**
     * The installed apps the browser rule pauses.
     *
     * @param installed every installed package
     * @param browsers packages that open any web address
     * @param allowed packages your friend allowed in Feed Guard's managed configuration
     * @param extraBlocked packages your friend added in Feed Guard's managed configuration
     * @param self Feed Guard's own package
     */
    static Set<String> toPause(Collection<String> installed, Collection<String> browsers,
            Set<String> allowed, Set<String> extraBlocked, String self) {
        Inputs in = new Inputs();
        in.installed = new HashSet<>(installed);
        in.browsers = new HashSet<>(browsers);
        in.allowed = allowed;
        in.extraBlocked = extraBlocked;
        in.essential = Collections.singleton(self);
        return plan(in).browserRule;
    }

    /** Splits a managed-configuration list ("a.b, c.d e.f") into package names. */
    static Set<String> parseList(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String part : text.split("[\\s,;]+")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    static Set<String> set(String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }
}
