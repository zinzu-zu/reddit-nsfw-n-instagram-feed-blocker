package io.github.zinzuzu.watchlimit.core;

/** Short, readable durations: "45 s", "12 min", "2 h 05 min". */
public final class Fmt {
    private Fmt() {
    }

    public static String duration(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        if (totalSec < 60) return totalSec + " s";
        long totalMin = totalSec / 60;
        if (totalMin < 60) return totalMin + " min";
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h + " h " + (m < 10 ? "0" : "") + m + " min";
    }

    /** Like {@link #duration} but rounds up, for time still to wait. */
    public static String durationUp(long ms) {
        if (ms <= 0) return "0 s";
        if (ms < 60_000L) return duration(ms + 999);
        return duration(((ms + 59_999L) / 60_000L) * 60_000L);
    }
}
