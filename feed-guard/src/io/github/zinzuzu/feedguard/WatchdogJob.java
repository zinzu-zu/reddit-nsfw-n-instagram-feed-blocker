package io.github.zinzuzu.feedguard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Safety net, every 15 minutes, after a restart, and a few seconds after the
 * accessibility access goes off. It switches the access back on when it can,
 * pauses Firefox when it can't, keeps the screen black and white while the
 * service isn't running, and catches apps installed while it wasn't running.
 *
 * During banking mode it also runs every 20 seconds, to catch apps installed
 * meanwhile, and once just after banking mode's time is up, to switch the
 * guards back on (see BankMode).
 */
public final class WatchdogJob extends JobService {
    private static final int JOB_ID = 4711;
    private static final int JOB_ID_SOON = 4712;
    private static final int JOB_ID_BANK = 4713;
    private static final int JOB_ID_BANK_END = 4714;
    /** The end-of-banking check runs this long after the end, so the time is surely up. */
    private static final long BANK_END_AFTER_MS = 2_000;

    /** One extra check in a few seconds, after the accessibility access went off. */
    static void checkSoon(Context c) {
        once(c, JOB_ID_SOON, 5_000);
    }

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, WatchdogJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException ignored) {
            // Too many jobs or not allowed: the service and boot checks still run.
        }
    }

    /** Banking mode starts, or starts again: the 20-second checks, and the one at its (new) end. */
    static void startBankChecks(Context c) {
        once(c, JOB_ID_BANK, BankRules.CHECK_EVERY_MS);
        once(c, JOB_ID_BANK_END, BankMode.timeLeft(c) + BANK_END_AFTER_MS);
    }

    /** While banking mode holds: puts back whichever of its checks is missing. */
    static void keepBankChecks(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (js.getPendingJob(JOB_ID_BANK) == null) once(c, JOB_ID_BANK, BankRules.CHECK_EVERY_MS);
        if (js.getPendingJob(JOB_ID_BANK_END) == null) once(c, JOB_ID_BANK_END, BankMode.timeLeft(c) + BANK_END_AFTER_MS);
    }

    /** A single check after the delay. Replaces one already scheduled under the same id. */
    private static void once(Context c, int id, long delayMs) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(id, new ComponentName(c, WatchdogJob.class))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 30_000)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException ignored) {
            // Not allowed: the 15-minute check still runs.
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Guard.periodicCheck(WatchdogJob.this);
                } finally {
                    jobFinished(params, false);
                }
                // The next 20-second check, now that this one is done (scheduling it
                // while this one still ran would stop this one).
                if (params.getJobId() == JOB_ID_BANK && BankMode.active(WatchdogJob.this)) {
                    once(WatchdogJob.this, JOB_ID_BANK, BankRules.CHECK_EVERY_MS);
                }
            }
        }, "feedguard-watchdog").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
