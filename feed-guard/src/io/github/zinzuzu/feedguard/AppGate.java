package io.github.zinzuzu.feedguard;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pauses apps through OwnDroid. OwnDroid hands Feed Guard its "Change package
 * state" power (a delegated admin), so a pause here looks like any admin pause:
 * the app is greyed out and can't be opened until OwnDroid, behind its lock,
 * lifts it.
 *
 * Feed Guard only lifts pauses it made itself, so it doesn't undo one set in
 * OwnDroid or by Watch Limit. Which apps it pauses is decided in AppRules.
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

    /** Pauses or unpauses. Returns the packages Android refused, or null if the call failed altogether. */
    private static Set<String> setPaused(Context c, Collection<String> pkgs, boolean paused) {
        if (pkgs.isEmpty()) return new HashSet<>();
        try {
            String[] notChanged = dpm(c).setPackagesSuspended(null, pkgs.toArray(new String[0]), paused);
            Set<String> failed = new HashSet<>();
            if (notChanged != null) failed.addAll(Arrays.asList(notChanged));
            return failed;
        } catch (RuntimeException e) {
            Log.w(Guard.TAG, "setPackagesSuspended(" + paused + ") failed", e);
            return null;
        }
    }

    static Set<String> stored(SharedPreferences p, String key) {
        return new HashSet<>(p.getStringSet(key, Collections.<String>emptySet()));
    }

    /** The dead-man switch: Feed Guard's access is off, so Firefox stops opening. */
    static synchronized void pauseFirefox(Context c) {
        SharedPreferences p = Guard.prefs(c);
        if (p.getBoolean(Guard.K_FIREFOX_PAUSED, false)) return;
        if (!isInstalled(c, Guard.FIREFOX)) return;
        if (isPaused(c, Guard.FIREFOX)) {
            // Already paused. If that's Feed Guard's own pause (from the list), the
            // dead-man switch takes it over, so editing the list can't lift it while
            // the guard is off.
            Set<String> ours = stored(p, Guard.K_OURS);
            if (ours.remove(Guard.FIREFOX)) {
                p.edit().putStringSet(Guard.K_OURS, ours).putBoolean(Guard.K_FIREFOX_PAUSED, true).commit();
            }
            return;
        }
        Set<String> failed = setPaused(c, Collections.singleton(Guard.FIREFOX), true);
        if (failed != null && failed.isEmpty()) {
            p.edit().putBoolean(Guard.K_FIREFOX_PAUSED, true).commit();
            Guard.notify(c, Guard.N_FIREFOX_PAUSED, "Firefox is paused",
                    "Feed Guard's accessibility access is off. Turn Feed Guard back on in Settings > Accessibility, and Firefox opens again.",
                    new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    /**
     * Feed Guard runs again: the dead-man pause becomes an ordinary Feed Guard
     * pause. The scan that follows lifts it, unless "Only apps on the list"
     * wants Firefox paused. Never touches a pause someone else made.
     */
    static synchronized void releaseFirefox(Context c) {
        SharedPreferences p = Guard.prefs(c);
        if (!p.getBoolean(Guard.K_FIREFOX_PAUSED, false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean(Guard.K_FIREFOX_PAUSED, false);
        if (isInstalled(c, Guard.FIREFOX)) {
            Set<String> ours = stored(p, Guard.K_OURS);
            ours.add(Guard.FIREFOX);
            e.putStringSet(Guard.K_OURS, ours);
        }
        e.commit();
        Guard.cancel(c, Guard.N_FIREFOX_PAUSED);
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

    /** Packages with an icon in the app drawer. */
    static Set<String> launchable(PackageManager pm) {
        Set<String> out = new HashSet<>();
        Intent drawer = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(drawer, 0)) {
            if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
        }
        return out;
    }

    /** Packages you installed yourself (everything that didn't come with the phone). */
    static Set<String> userInstalled(List<ApplicationInfo> apps) {
        Set<String> out = new HashSet<>();
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) out.add(ai.packageName);
        }
        return out;
    }

    /**
     * The basics that are never paused: AppRules.ESSENTIAL, Feed Guard, the
     * phone's own apps for the home screen, calls, texts, contacts, alarms, the
     * file picker and emergencies, its keyboards, and the defaults you picked.
     */
    static Set<String> essentials(Context c, Set<String> userInstalled) {
        Set<String> out = new HashSet<>(AppRules.ESSENTIAL);
        out.add(c.getPackageName());
        PackageManager pm = c.getPackageManager();
        for (Intent basic : basics()) {
            try {
                for (ResolveInfo ri : pm.queryIntentActivities(basic, 0)) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    // Only apps that came with the phone: an app you install can't make itself a basic.
                    if (!userInstalled.contains(pkg)) out.add(pkg);
                }
            } catch (RuntimeException ignored) {
                // Skip this one.
            }
        }
        // Defaults count even if you installed them: Android won't let anyone pause
        // the home screen or the phone app anyway, and texts and typing must work.
        addIfSet(out, defaultHome(pm));
        addIfSet(out, defaultDialer(c));
        addIfSet(out, defaultSms(c));
        try {
            addIfSet(out, ServiceList.packageOf(
                    Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD)));
            InputMethodManager imm = c.getSystemService(InputMethodManager.class);
            if (imm != null) {
                for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
                    if (!userInstalled.contains(imi.getPackageName())) out.add(imi.getPackageName());
                }
            }
        } catch (RuntimeException ignored) {
            // Keyboards unknown: Gboard is in ESSENTIAL.
        }
        return out;
    }

    private static List<Intent> basics() {
        List<Intent> out = new ArrayList<>();
        out.add(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        out.add(new Intent(Intent.ACTION_DIAL));
        out.add(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:0")));
        out.add(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:0")));
        out.add(new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI));
        out.add(new Intent(AlarmClock.ACTION_SET_ALARM));
        out.add(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
        out.add(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"));
        out.add(new Intent("android.telephony.action.EMERGENCY_ASSISTANCE"));
        return out;
    }

    private static void addIfSet(Set<String> out, String pkg) {
        if (pkg != null && !pkg.isEmpty()) out.add(pkg);
    }

    private static String defaultHome(PackageManager pm) {
        try {
            ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String defaultDialer(Context c) {
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            return tm == null ? null : tm.getDefaultDialerPackage();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String defaultSms(Context c) {
        try {
            return Telephony.Sms.getDefaultSmsPackage(c);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** SHA-256 of the certificate an installed app is signed with now, as hex; null if unknown. */
    @SuppressWarnings("deprecation")
    static String signer(PackageManager pm, String pkg) {
        try {
            byte[] cert;
            if (Build.VERSION.SDK_INT >= 28) {
                SigningInfo si = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                if (si == null) return null;
                if (si.hasMultipleSigners()) {
                    // Several signers: pin the lowest digest, so it's the same every time.
                    String lowest = null;
                    for (Signature s : si.getApkContentsSigners()) {
                        String d = sha256(s.toByteArray());
                        if (lowest == null || d.compareTo(lowest) < 0) lowest = d;
                    }
                    return lowest;
                }
                Signature[] history = si.getSigningCertificateHistory();
                if (history == null || history.length == 0) return null;
                cert = history[history.length - 1].toByteArray(); // the current one is last
            } else {
                Signature[] sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
                if (sigs == null || sigs.length == 0) return null;
                cert = sigs[0].toByteArray();
            }
            return sha256(cert);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether the installed app is the pinned one: signed with the pinned
     * certificate, or with one that took over from it by Android's key rotation.
     */
    static boolean matchesPin(PackageManager pm, String pkg, String pinHex) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return pm.hasSigningCertificate(pkg, Pins.bytes(pinHex), PackageManager.CERT_INPUT_SHA256);
            } catch (RuntimeException e) {
                return true; // Can't tell: don't treat it as a stranger.
            }
        }
        String now = signer(pm, pkg);
        return now == null || now.equals(pinHex);
    }

    private static String sha256(byte[] data) {
        try {
            return Pins.hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Applies both rules (see AppRules): pauses what they want paused, and lifts
     * Feed Guard's own pauses that neither wants any more.
     *
     * @param fresh packages just installed (not updated), so they count as new; may be null
     */
    static synchronized void scan(Context c, Collection<String> fresh) {
        try {
            scanNow(c, fresh);
        } catch (RuntimeException e) {
            // Never let one odd app or setting crash the guard: the next scan tries again.
            Log.e(Guard.TAG, "scan failed", e);
        }
    }

    private static void scanNow(Context c, Collection<String> fresh) {
        if (!delegated(c)) return;
        PackageManager pm = c.getPackageManager();
        SharedPreferences p = Guard.prefs(c);

        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        Set<String> installed = new HashSet<>();
        for (ApplicationInfo ai : apps) installed.add(ai.packageName);
        Set<String> userInstalled = userInstalled(apps);

        Set<String> handled = stored(p, Guard.K_HANDLED);
        Set<String> ours = stored(p, Guard.K_OURS);
        handled.retainAll(installed);
        ours.retainAll(installed);
        if (fresh != null) {
            handled.removeAll(fresh);
            ours.removeAll(fresh);
        }

        Config cfg = Config.read(c);

        // Signature pins for listed apps you installed yourself (see Pins).
        Map<String, String> pins = Pins.parse(p.getStringSet(Guard.K_PINS, null));
        pins.keySet().retainAll(cfg.allowed);
        Set<String> notSameApp = new TreeSet<>();
        for (String pkg : cfg.allowed) {
            if (!userInstalled.contains(pkg)) continue;
            String pin = pins.get(pkg);
            String now = signer(pm, pkg);
            if (now == null) continue;
            if (pin == null) {
                pins.put(pkg, now);
            } else if (!now.equals(pin) && !matchesPin(pm, pkg, pin)) {
                notSameApp.add(pkg);
            }
        }
        Set<String> allowed = new HashSet<>(cfg.allowed);
        allowed.removeAll(notSameApp);

        AppRules.Inputs in = new AppRules.Inputs();
        in.installed = installed;
        in.userInstalled = userInstalled;
        in.launchable = cfg.listMode ? launchable(pm) : Collections.<String>emptySet();
        in.browsers = browsers(pm);
        in.essential = essentials(c, userInstalled);
        in.allowed = allowed;
        in.extraBlocked = cfg.extraBlocked;
        in.listMode = cfg.listMode;
        in.handled = handled;
        in.ours = ours;
        in.skip = p.getBoolean(Guard.K_FIREFOX_PAUSED, false)
                ? Collections.singleton(Guard.FIREFOX) : Collections.<String>emptySet();
        AppRules.Plan plan = AppRules.plan(in);

        Set<String> toPause = new TreeSet<>();
        for (String pkg : plan.pause) {
            if (isPaused(c, pkg)) {
                // Already paused, perhaps by someone else: never record that as Feed Guard's.
                if (plan.browserRule.contains(pkg)) handled.add(pkg);
            } else {
                toPause.add(pkg);
            }
        }
        List<String> pausedNow = new ArrayList<>();
        Set<String> unpausable = null;
        if (!toPause.isEmpty()) {
            Set<String> failed = setPaused(c, toPause, true);
            if (failed != null) {
                unpausable = new TreeSet<>();
                for (String pkg : toPause) {
                    if (failed.contains(pkg)) {
                        unpausable.add(pkg);
                        continue;
                    }
                    ours.add(pkg);
                    if (plan.browserRule.contains(pkg)) handled.add(pkg);
                    pausedNow.add(pkg);
                }
            }
        } else {
            unpausable = new TreeSet<>();
        }

        if (!plan.release.isEmpty()) {
            Set<String> failed = setPaused(c, plan.release, false);
            if (failed != null) {
                for (String pkg : plan.release) {
                    if (!failed.contains(pkg)) ours.remove(pkg);
                }
            }
        }

        Set<String> unpausableBefore = stored(p, Guard.K_UNPAUSABLE);
        Set<String> notSameBefore = stored(p, Guard.K_NOT_SAME_APP);
        SharedPreferences.Editor e = p.edit()
                .putStringSet(Guard.K_HANDLED, handled)
                .putStringSet(Guard.K_OURS, ours)
                .putStringSet(Guard.K_PINS, Pins.format(pins))
                .putStringSet(Guard.K_NOT_SAME_APP, notSameApp);
        if (unpausable != null) e.putStringSet(Guard.K_UNPAUSABLE, unpausable);
        e.apply();

        Intent status = new Intent(c, MainActivity.class);
        if (!pausedNow.isEmpty()) {
            boolean fromList = false;
            for (String pkg : pausedNow) {
                if (!plan.browserRule.contains(pkg)) fromList = true;
            }
            Guard.notify(c, Guard.N_APPS_PAUSED,
                    pausedNow.size() <= 4 ? "Paused " + Guard.labels(c, pausedNow) : "Paused " + pausedNow.size() + " apps",
                    fromList
                            ? "Only the apps on your list open. Apps go on the list in OwnDroid's settings for Feed Guard."
                            : "Only Firefox, with the add-on, can show Instagram and Reddit. Other browsers and the Instagram and Reddit apps stay paused.",
                    status);
        }
        if (unpausable != null) {
            Set<String> added = new TreeSet<>(unpausable);
            added.removeAll(unpausableBefore);
            if (!added.isEmpty()) {
                Guard.notify(c, Guard.N_UNPAUSABLE, "Feed Guard can't pause " + Guard.labels(c, added),
                        "Android doesn't let other apps pause it. If you don't want it, hide it in OwnDroid › Applications.",
                        status);
            }
        }
        Set<String> strangers = new TreeSet<>(notSameApp);
        strangers.removeAll(notSameBefore);
        if (!strangers.isEmpty()) {
            Guard.notify(c, Guard.N_NOT_SAME_APP, Guard.labels(c, strangers) + " isn't the copy on your list",
                    "It's signed by someone other than the copy that was on your list, so it counts as not listed. "
                            + "To accept this copy, take it off the list in OwnDroid, save, then put it back.",
                    status);
        }
    }

    /** Apps someone might want on the list: drawer apps and apps you installed, minus the basics. */
    static Set<String> listCandidates(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        Set<String> user = userInstalled(apps);
        Set<String> out = new TreeSet<>(launchable(pm));
        out.addAll(user);
        out.removeAll(essentials(c, user));
        return out;
    }
}
