package io.github.zinzuzu.watchlimit;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * A check every 15 minutes, also after a restart. While the watcher is switched off, it
 * pauses every watched app that isn't paused yet, including ones installed since, so
 * installing another YouTube app isn't a way around the limit either.
 */
public final class GuardJob extends JobService {
    private static final int JOB_ID = 1;
    private static final long EVERY_MS = 15 * 60_000L;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, GuardJob.class))
                .setPeriodic(EVERY_MS)
                .setPersisted(true)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException e) {
            Log.w("WatchLimit", "Couldn't schedule the background check", e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Engine.get(this).checkWatcher();
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
