package io.github.zinzuzu.watchlimit.core;

/**
 * Keeps a "trusted" clock that ignores manual changes to the phone's date and time.
 *
 * Every observation pairs the wall clock with the monotonic clock (time since boot,
 * which nobody can change). Within one boot the two must move together. When the wall
 * clock jumps by more than {@link #JUMP_MS} relative to the monotonic clock, the jump
 * is treated like this:
 * <ul>
 *   <li>Automatic time is off: someone set the clock by hand. The jump goes into
 *       {@link #skewMs} and the trusted clock carries on as if it never happened.</li>
 *   <li>Automatic time is on and the jump cancels out an earlier manual change: network
 *       time is putting the clock back, so the skew shrinks again.</li>
 *   <li>Automatic time is on otherwise: network time is fixing a clock that was wrong
 *       (for example right after boot). The new time is trusted.</li>
 * </ul>
 * Whenever Android has network time, {@link #trustNetworkTime} replaces the estimate.
 */
public final class ClockGuard {
    public static final long JUMP_MS = 5 * 60_000L;
    /** Used when the phone doesn't report a boot count. */
    public static final int UNKNOWN_BOOT = Integer.MIN_VALUE;

    /** Sum of manual clock changes still in effect. Trusted time = wall clock - skew. */
    public long skewMs;
    public long anchorWall;
    public long anchorElapsed;
    public int anchorBoot = UNKNOWN_BOOT;

    private long lastDelta;

    /**
     * Records an observation and returns the trusted wall-clock time in milliseconds.
     *
     * @param wall     System.currentTimeMillis()
     * @param elapsed  SystemClock.elapsedRealtime()
     * @param boot     the boot count, or {@link #UNKNOWN_BOOT}
     * @param autoTime whether "Set time automatically" is on
     */
    public long observe(long wall, long elapsed, int boot, boolean autoTime) {
        long delta = 0;
        boolean sameBoot = anchorWall != 0 && boot == anchorBoot && elapsed >= anchorElapsed;
        if (sameBoot) {
            delta = elapsed - anchorElapsed;
            long jump = wall - (anchorWall + delta);
            if (Math.abs(jump) >= JUMP_MS) {
                if (!autoTime) {
                    skewMs += jump;
                } else if (Math.abs(skewMs + jump) < Math.abs(skewMs)) {
                    skewMs += jump;
                }
                if (Math.abs(skewMs) < JUMP_MS) skewMs = 0;
            }
        }
        anchorWall = wall;
        anchorElapsed = elapsed;
        anchorBoot = boot;
        lastDelta = delta;
        return wall - skewMs;
    }

    /**
     * Takes Android's network time (from an NTP server, which nobody on the phone can
     * set) as the truth, and returns the trusted time. This also catches clock changes
     * that {@link #observe} can't see, such as one made right after a restart. Call it
     * right after {@link #observe}, with the same wall-clock reading.
     */
    public long trustNetworkTime(long wall, long network) {
        long skew = wall - network;
        skewMs = Math.abs(skew) < JUMP_MS ? 0 : skew;
        return wall - skewMs;
    }

    /**
     * Real time that passed between the previous observation and the latest one, or 0
     * if the phone restarted in between (time while it was off isn't known, so it
     * doesn't count).
     */
    public long lastDelta() {
        return lastDelta;
    }
}
