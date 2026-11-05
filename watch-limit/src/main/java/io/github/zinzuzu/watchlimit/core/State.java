package io.github.zinzuzu.watchlimit.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Everything the app remembers, with conversion to and from a flat key-value map. */
public final class State {
    public final ClockGuard clock = new ClockGuard();
    public final Ledger ledger = new Ledger();
    public final Policy policy = new Policy();
    /** Apps suspended because the watcher was switched off. Lifted when it comes back. */
    public final Set<String> failClosed = new TreeSet<String>();
    /** Apps suspended for a moment to get them off the screen. */
    public final Set<String> flashSuspended = new TreeSet<String>();

    public static State load(Map<String, ?> m) {
        State s = new State();
        s.clock.skewMs = getLong(m, "clock.skew", 0);
        s.clock.anchorWall = getLong(m, "clock.wall", 0);
        s.clock.anchorElapsed = getLong(m, "clock.elapsed", 0);
        s.clock.anchorBoot = (int) getLong(m, "clock.boot", ClockGuard.UNKNOWN_BOOT);
        s.ledger.usedMs = getLong(m, "day.used", 0);
        s.ledger.passesUsed = (int) getLong(m, "day.passes", 0);
        s.ledger.warned5 = getLong(m, "day.warned5", 0) != 0;
        s.ledger.warned1 = getLong(m, "day.warned1", 0) != 0;
        s.ledger.lastResetAt = getLong(m, "day.lastReset", 0);
        s.ledger.nextResetAt = getLong(m, "day.nextReset", 0);
        s.policy.active = Rules.decode(getString(m, "rules.active"), Rules.DEFAULT);
        s.policy.pending = Rules.decode(getString(m, "rules.pending"), null);
        s.policy.pendingRemainingMs = s.policy.pending == null ? 0 : getLong(m, "rules.wait", 0);
        s.policy.locked = getLong(m, "rules.locked", 0) != 0;
        readSet(getString(m, "suspend.failClosed"), s.failClosed);
        readSet(getString(m, "suspend.flash"), s.flashSuspended);
        return s;
    }

    /** All values are Long or String. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("clock.skew", clock.skewMs);
        m.put("clock.wall", clock.anchorWall);
        m.put("clock.elapsed", clock.anchorElapsed);
        m.put("clock.boot", (long) clock.anchorBoot);
        m.put("day.used", ledger.usedMs);
        m.put("day.passes", (long) ledger.passesUsed);
        m.put("day.warned5", ledger.warned5 ? 1L : 0L);
        m.put("day.warned1", ledger.warned1 ? 1L : 0L);
        m.put("day.lastReset", ledger.lastResetAt);
        m.put("day.nextReset", ledger.nextResetAt);
        m.put("rules.active", policy.active.encode());
        m.put("rules.pending", policy.pending == null ? "" : policy.pending.encode());
        m.put("rules.wait", policy.pendingRemainingMs);
        m.put("rules.locked", policy.locked ? 1L : 0L);
        m.put("suspend.failClosed", join(failClosed));
        m.put("suspend.flash", join(flashSuspended));
        return m;
    }

    private static long getLong(Map<String, ?> m, String k, long d) {
        Object v = m.get(k);
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return (Integer) v;
        return d;
    }

    private static String getString(Map<String, ?> m, String k) {
        Object v = m.get(k);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }

    private static void readSet(String s, Set<String> out) {
        if (s == null) return;
        for (String p : s.split(",")) {
            if (Rules.isPackageName(p)) out.add(p);
        }
    }

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }
}
