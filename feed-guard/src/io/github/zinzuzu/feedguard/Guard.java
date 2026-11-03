package io.github.zinzuzu.feedguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Shared names, stored state and notifications. */
final class Guard {
    private Guard() {}

    static final String TAG = "FeedGuard";

    static final String FIREFOX = AppRules.FIREFOX;
    static final String FIREFOX_INTENT_RECEIVER = "org.mozilla.fenix.IntentReceiverActivity";
    /** Firefox's own "New tab" launcher shortcut. */
    static final String FIREFOX_NEW_TAB = "org.mozilla.fenix.OPEN_TAB";

    // Stored state.
    static final String K_ARMED_AT = "armedAt";              // wall-clock ms when the marker was first seen; 0 = not yet
    static final String K_MARKER_SEEN_AT = "markerSeenAt";   // wall-clock ms
    static final String K_URL_SEEN_AT = "urlSeenAt";         // wall-clock ms
    static final String K_URL_UNREADABLE = "urlUnreadable";  // the address bar hasn't been found for a while
    static final String K_LAST_COVER_AT = "lastCoverAt";     // wall-clock ms
    static final String K_HANDLED = "handledPackages";       // apps already dealt with; not paused again unless reinstalled
    static final String K_OURS = "pausedByGuard";            // apps Feed Guard itself paused
    static final String K_FIREFOX_PAUSED = "firefoxPaused";  // Feed Guard paused Firefox because its access was off

    // Notification ids.
    static final int N_ARMED = 1;
    static final int N_FIREFOX_PAUSED = 2;
    static final int N_APPS_PAUSED = 3;
    static final int N_URL_UNREADABLE = 4;
    private static final String CHANNEL = "alerts";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("guard", Context.MODE_PRIVATE);
    }

    static boolean armed(Context c) {
        return prefs(c).getLong(K_ARMED_AT, 0) > 0;
    }

    /** Whether Feed Guard is switched on in Settings > Accessibility (whether or not it's running yet). */
    static boolean serviceEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        ComponentName me = new ComponentName(c, GuardService.class);
        for (String item : list.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(item.trim()))) return true;
        }
        return false;
    }

    static void notify(Context c, int id, String title, String text, Intent tap) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Feed Guard", NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        if (tap != null) {
            tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(c, id, tap,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        try {
            nm.notify(id, b.build());
        } catch (RuntimeException ignored) {
            // Notifications not allowed: the status screen still shows everything.
        }
    }

    static void cancel(Context c, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }

    /** App names for a list of packages, for notifications and the status screen. */
    static String labels(Context c, Collection<String> packages) {
        PackageManager pm = c.getPackageManager();
        List<String> names = new ArrayList<>();
        for (String pkg : packages) {
            String name = pkg;
            try {
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // Keep the package name.
            }
            names.add(name);
        }
        return TextUtils.join(", ", names);
    }

    /** Run from the watchdog job and at start-up: the safety net for when the service isn't running. */
    static void periodicCheck(Context c) {
        if (armed(c) && !serviceEnabled(c)) AppGate.pauseFirefox(c);
        AppGate.scan(c, null);
    }
}
