package io.github.zinzuzu.feedguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * "Turn back on now", on banking mode's notification: ends banking mode early.
 * Not exported, so only that notification can send it.
 */
public final class BankReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BankMode.ACTION_BACK_ON.equals(intent.getAction())) return;
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BankMode.standUp(app, "Turn back on now");
                } finally {
                    result.finish();
                }
            }
        }, "feedguard-bank").start();
    }
}
