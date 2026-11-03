package io.github.zinzuzu.feedguard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Safety net, every 15 minutes and after a restart. If Feed Guard's access was
 * switched off in a way that skipped the dead-man switch (for example while its
 * process wasn't running), this pauses Firefox. It also catches apps installed
 * while Feed Guard wasn't running.
 */
public final class WatchdogJob extends JobService {
    private static final int JOB_ID = 4711;

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
