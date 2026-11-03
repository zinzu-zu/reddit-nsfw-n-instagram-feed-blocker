package io.github.zinzuzu.feedguard;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pauses apps through OwnDroid. OwnDroid hands Feed Guard its "Change package
 * state" power (a delegated admin), so a pause here looks like any admin pause:
 * the app is greyed out and can't be opened until OwnDroid, behind your
 * friend's code, lifts it.
 *
 * Feed Guard never lifts a pause it didn't make, so it can't undo one set in
 * OwnDroid or by Watch Limit.
 */
final class AppGate {
    private AppGate() {}

    private static DevicePolicyManager dpm(Context c) {
        return c.getSystemService(DevicePolicyManager.class);
    }

    /** Whether OwnDroid has given Feed Guard the "Change package state" delegation. */
    static boolean delegated(Context c) {
        try {
            List<String> scopes = dpm(c).getDelegatedScopes(null, c.getPackageName());
            return scopes != null && scopes.contains(DevicePolicyManager.DELEGATION_PACKAGE_ACCESS);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean isPaused(Context c, String pkg) {
        try {
            return dpm(c).isPackageSuspended(null, pkg);
        } catch (Exception e) {
            return false;
        }
    }

    /** Pauses or unpauses; returns the packages that could not be changed. */
    private static Set<String> setPaused(Context c, Collection<String> pkgs, boolean paused) {
        Set<String> failed = new HashSet<>(pkgs);
        if (pkgs.isEmpty()) return failed;
        try {
            String[] notChanged = dpm(c).setPackagesSuspended(null, pkgs.toArray(new String[0]), paused);
            failed.clear();
            if (notChanged != null) failed.addAll(Arrays.asList(notChanged));
        } catch (RuntimeException e) {
            Log.w(Guard.TAG, "setPackagesSuspended(" + paused + ") failed", e);
        }
        return failed;
    }

    /** The dead-man switch: Feed Guard's access is off, so Firefox stops opening. */
    static synchronized void pauseFirefox(Context c) {
        SharedPreferences p = Guard.prefs(c);
        if (p.getBoolean(Guard.K_FIREFOX_PAUSED, false)) return;
        if (!isInstalled(c, Guard.FIREFOX) || isPaused(c, Guard.FIREFOX)) return;
        if (setPaused(c, Collections.singleton(Guard.FIREFOX), true).isEmpty()) {
            p.edit().putBoolean(Guard.K_FIREFOX_PAUSED, true).commit();
            Guard.notify(c, Guard.N_FIREFOX_PAUSED, "Firefox is paused",
                    "Feed Guard's accessibility access is off. Turn Feed Guard back on in Settings > Accessibility, and Firefox opens again.",
                    new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    /** Undoes pauseFirefox once Feed Guard runs again. Leaves any other pause alone. */
    static synchronized void releaseFirefox(Context c) {
        SharedPreferences p = Guard.prefs(c);
        if (!p.getBoolean(Guard.K_FIREFOX_PAUSED, false)) return;
        if (!isInstalled(c, Guard.FIREFOX) || setPaused(c, Collections.singleton(Guard.FIREFOX), false).isEmpty()) {
            p.edit().putBoolean(Guard.K_FIREFOX_PAUSED, false).commit();
            Guard.cancel(c, Guard.N_FIREFOX_PAUSED);
        }
    }

    /** Packages that open any web address: browsers. */
    static Set<String> browsers(PackageManager pm) {
        Set<String> out = new HashSet<>();
        for (String scheme : new String[] {"https", "http"}) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(scheme + "://feedguard.invalid/"))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            for (ResolveInfo ri : pm.queryIntentActivities(view, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        }
        return out;
    }

    static Bundle managedConfig(Context c) {
        try {
            RestrictionsManager rm = c.getSystemService(RestrictionsManager.class);
            Bundle b = rm == null ? null : rm.getApplicationRestrictions();
            return b == null ? new Bundle() : b;
        } catch (RuntimeException e) {
            return new Bundle();
        }
    }

    /**
     * Pauses browsers other than Firefox, and Instagram and Reddit apps.
     *
     * An app is dealt with once. If your friend later unpauses it in OwnDroid,
     * Feed Guard leaves it alone, until it is uninstalled and installed again.
     *
     * @param freshInstall a package that was just installed (not updated), or null
     */
    static synchronized void scan(Context c, String freshInstall) {
        if (!delegated(c)) return;
        PackageManager pm = c.getPackageManager();
        SharedPreferences p = Guard.prefs(c);

        Set<String> installed = new HashSet<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) installed.add(ai.packageName);

        Set<String> handled = new HashSet<>(p.getStringSet(Guard.K_HANDLED, Collections.<String>emptySet()));
        Set<String> ours = new HashSet<>(p.getStringSet(Guard.K_OURS, Collections.<String>emptySet()));
        handled.retainAll(installed);
        ours.retainAll(installed);
        if (freshInstall != null) {
            handled.remove(freshInstall);
            ours.remove(freshInstall);
        }

        Bundle config = managedConfig(c);
        Set<String> allowed = AppRules.parseList(config.getString("allowed_apps", ""));
        Set<String> extra = AppRules.parseList(config.getString("blocked_apps", ""));

        Set<String> todo = new TreeSet<>(AppRules.toPause(installed, browsers(pm), allowed, extra, c.getPackageName()));
        todo.removeAll(handled);
        List<String> pausedNow = new ArrayList<>();
        Set<String> toPause = new TreeSet<>();
        for (String pkg : todo) {
            if (isPaused(c, pkg)) {
                handled.add(pkg); // Someone else's pause: note it, but never record it as ours.
            } else {
                toPause.add(pkg);
            }
        }
        if (!toPause.isEmpty()) {
            Set<String> failed = setPaused(c, toPause, true);
            for (String pkg : toPause) {
                if (failed.contains(pkg)) continue;
                handled.add(pkg);
                ours.add(pkg);
                pausedNow.add(pkg);
            }
        }

        // Your friend allowed an app in the managed configuration: lift Feed Guard's own pause.
        Set<String> release = new TreeSet<>(ours);
        release.retainAll(allowed);
        if (!release.isEmpty()) {
            Set<String> failed = setPaused(c, release, false);
            for (String pkg : release) {
                if (!failed.contains(pkg)) ours.remove(pkg);
            }
        }

        p.edit().putStringSet(Guard.K_HANDLED, handled).putStringSet(Guard.K_OURS, ours).apply();
        if (!pausedNow.isEmpty()) {
            Guard.notify(c, Guard.N_APPS_PAUSED, "Paused " + Guard.labels(c, pausedNow),
                    "Only Firefox, with the add-on, can show Instagram and Reddit. Other browsers and the Instagram and Reddit apps stay paused.",
                    new Intent(c, MainActivity.class));
        }
    }
}
