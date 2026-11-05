package io.github.zinzuzu.feedguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or an update of Feed Guard: run the safety-net check and keep the watchdog scheduled. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    WatchdogJob.schedule(app);
                    Guard.periodicCheck(app);
                } finally {
                    result.finish();
                }
            }
        }, "feedguard-boot").start();
    }
}
