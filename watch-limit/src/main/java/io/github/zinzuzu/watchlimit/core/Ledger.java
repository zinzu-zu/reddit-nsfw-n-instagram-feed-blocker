package io.github.zinzuzu.watchlimit.core;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Today's usage. The day ends at local midnight.
 *
 * The next reset is stored as an absolute time, worked out when the previous day
 * started. Changing the time zone therefore can't bring the reset forward, and two
 * resets are always at least {@link #MIN_DAY_MS} apart.
 */
public final class Ledger {
    public static final long MIN_DAY_MS = 20 * 3_600_000L;
    private static final long MAX_AHEAD_MS = 48 * 3_600_000L;

    public long usedMs;
    public int passesUsed;
    public boolean warned5;
    public boolean warned1;
    /** Trusted time of the last reset. */
    public long lastResetAt;
    /** Trusted time of the next reset. 0 until the first observation. */
    public long nextResetAt;

    /** Starts a new day if one is due. Returns true if it did. */
    public boolean rollIfDue(long now, ZoneId zone) {
        if (nextResetAt == 0) {
            lastResetAt = now;
            nextResetAt = nextMidnight(now, zone);
            return false;
        }
        if (now < nextResetAt) {
            if (nextResetAt - now > MAX_AHEAD_MS) {
                // Only possible if the trusted clock itself moved back a long way, for
                // example when network time fixes a clock that was set in the future.
                lastResetAt = now;
                nextResetAt = nextMidnight(now, zone);
            }
            return false;
        }
        usedMs = 0;
        passesUsed = 0;
        warned5 = false;
        warned1 = false;
        lastResetAt = nextResetAt;
        nextResetAt = Math.max(nextMidnight(now, zone), lastResetAt + MIN_DAY_MS);
        return true;
    }

    public static long nextMidnight(long now, ZoneId zone) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }
}
