package io.github.zinzuzu.feedguard;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which installed apps Feed Guard pauses: every browser except Firefox, and
 * apps for Instagram and Reddit. Firefox is the only browser that runs the
 * add-on, so any other browser, or the apps themselves, would show the feeds.
 *
 * Pure Java, so it can be tested off the phone.
 */
final class AppRules {
    private AppRules() {}

    static final String FIREFOX = "org.mozilla.firefox";

    /** Never paused, even if they open web links. */
    static final Set<String> NEVER = set(
            FIREFOX,
            "com.bintianqi.owndroid",
            "io.github.zinzuzu.watchlimit",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.nexuslauncher");

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

    /**
     * The installed apps that should be paused.
     *
     * @param installed every installed package
     * @param browsers packages that open any web address
     * @param allowed packages your friend allowed in Feed Guard's managed configuration
     * @param extraBlocked packages your friend added in Feed Guard's managed configuration
     * @param self Feed Guard's own package
     */
    static Set<String> toPause(Collection<String> installed, Collection<String> browsers,
            Set<String> allowed, Set<String> extraBlocked, String self) {
        Set<String> out = new TreeSet<>();
        for (String pkg : installed) {
            if (pkg == null || pkg.equals(self) || NEVER.contains(pkg) || allowed.contains(pkg)) continue;
            if (browsers.contains(pkg) || isFeedApp(pkg) || extraBlocked.contains(pkg)) out.add(pkg);
        }
        return out;
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

    private static Set<String> set(String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }
}
