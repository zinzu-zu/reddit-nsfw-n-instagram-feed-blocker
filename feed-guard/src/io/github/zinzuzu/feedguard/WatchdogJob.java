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
 */
public final class WatchdogJob extends JobService {
    private static final int JOB_ID = 4711;
    private static final int JOB_ID_SOON = 4712;

    /** One extra check in a few seconds, after the accessibility access went off. */
    static void checkSoon(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID_SOON, new ComponentName(c, WatchdogJob.class))
                .setMinimumLatency(5_000)
                .setOverrideDeadline(30_000)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException ignored) {
            // Not allowed: the 15-minute check still runs.
        }
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

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Guard.periodicCheck(WatchdogJob.this);
                } finally {
                    jobFinished(params, false);
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
