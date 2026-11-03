package io.github.zinzuzu.watchlimit.core;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

/**
 * Tests for the parts that decide what counts and when limits change. Plain Java, so
 * they run on any JDK: java -cp out io.github.zinzuzu.watchlimit.core.CoreTest
 */
public final class CoreTest {
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
    private static final ZoneId KIRITIMATI = ZoneId.of("Pacific/Kiritimati");

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        rulesCompareAndEncode();
        rulesClampAndValidate();
        policyUnlockedAppliesEverything();
        policyLockedTighteningIsImmediate();
        policyLockedLooseningWaits();
        policyMixedRequest();
        policyWaitKeepsOrRestarts();
        policyAppsAddNowRemoveLater();
        policyCancelAndSameAsActive();
        clockIgnoresManualChanges();
        clockFollowsNetworkFixes();
        clockAfterReboot();
        clockSmallAdjustments();
        clockTrustsNetworkTime();
        ledgerResetsAtMidnight();
        ledgerTimeZoneCantBringResetForward();
        ledgerPhoneOffForDays();
        ledgerRecoversFromFarFutureReset();
        ledgerDaylightSaving();
        stateRoundTrip();
        formatting();
        System.out.println(passed + " checks passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ Rules

    static void rulesCompareAndEncode() {
        Rules a = new Rules(60, 3, 60, 24, set("com.example.a"));
        Rules b = new Rules(90, 2, 60, 12, set());
        Rules s = a.strictest(b);
        eq(60, s.limitMin, "strictest limit");
        eq(2, s.passes, "strictest passes");
        eq(24, s.delayH, "strictest delay is the longer one");
        eq(set("com.example.a"), s.extraApps, "strictest keeps every app");
        check(b.looserThan(a), "higher limit and shorter delay is looser");
        check(a.looserThan(b), "more passes is looser");
        check(!a.looserThan(a), "equal is not looser");
        check(new Rules(60, 3, 60, 24, set()).looserThan(a), "fewer apps is looser");
        check(!a.looserThan(new Rules(60, 3, 60, 24, set())), "more apps is not looser");
        eq(a, Rules.decode(a.encode(), null), "encode round trip");
        eq(Rules.DEFAULT, Rules.decode("garbage", Rules.DEFAULT), "bad input falls back");
        eq(null, Rules.decode(null, null), "missing input falls back");
    }

    static void rulesClampAndValidate() {
        Rules r = new Rules(-5, 99, 1, 0, set("not a package", "org.ok.app"));
        eq(0, r.limitMin, "limit clamps at 0");
        eq(Rules.MAX_PASSES, r.passes, "passes clamp");
        eq(Rules.MIN_PASS_SEC, r.passSec, "pass length clamps");
        eq(Rules.MIN_DELAY_H, r.delayH, "delay clamps");
        eq(set("org.ok.app"), r.extraApps, "invalid package names are dropped");
        check(Rules.isPackageName("InfinityLoop1309.NewPipeEnhanced"), "PipePipe's name is valid");
        check(!Rules.isPackageName("youtube"), "a single word is not a package name");
        check(!Rules.isPackageName("com..double"), "empty segments are invalid");
    }

    // ------------------------------------------------------------------ Policy

    static void policyUnlockedAppliesEverything() {
        Policy p = new Policy();
        eq(Policy.Outcome.APPLIED, p.request(Rules.DEFAULT.withLimits(120, 5, 90, 48)),
                "unlocked: a looser change applies");
        eq(120, p.active.limitMin, "unlocked: new limit in force");
        eq(null, p.pending, "unlocked: nothing waits");
        eq(Policy.Outcome.UNCHANGED, p.request(p.active), "unlocked: same rules change nothing");
    }

    static void policyLockedTighteningIsImmediate() {
        Policy p = locked(Rules.DEFAULT);
        eq(Policy.Outcome.APPLIED, p.request(Rules.DEFAULT.withLimits(30, 1, 30, 48)),
                "locked: stricter applies now");
        eq(30, p.active.limitMin, "locked: lower limit in force");
        eq(48, p.active.delayH, "locked: longer wait in force");
        eq(null, p.pending, "locked: nothing waits");
    }

    static void policyLockedLooseningWaits() {
        Policy p = locked(Rules.DEFAULT); // 60 min, wait 24 h
        eq(Policy.Outcome.WAITING, p.request(Rules.DEFAULT.withLimits(90, 3, 60, 24)),
                "locked: higher limit waits");
        eq(60, p.active.limitMin, "locked: old limit still in force");
        eq(90, p.pending.limitMin, "locked: new limit waiting");
        eq(24 * HOUR, p.pendingRemainingMs, "locked: wait is the configured 24 h");
        check(!p.advance(23 * HOUR), "23 h is not enough");
        eq(60, p.active.limitMin, "still the old limit after 23 h");
        check(p.advance(HOUR), "24 h is enough");
        eq(90, p.active.limitMin, "new limit after 24 h");
        eq(null, p.pending, "nothing waits after it applied");
        check(!p.advance(0), "advancing by nothing does nothing");
    }

    static void policyMixedRequest() {
        Policy p = locked(Rules.DEFAULT);
        eq(Policy.Outcome.APPLIED_AND_WAITING, p.request(Rules.DEFAULT.withLimits(90, 1, 60, 24)),
                "mixed: tighten passes, loosen limit");
        eq(1, p.active.passes, "mixed: fewer passes now");
        eq(60, p.active.limitMin, "mixed: limit unchanged for now");
        eq(90, p.pending.limitMin, "mixed: higher limit waits");
        p.advance(DAY);
        eq(90, p.active.limitMin, "mixed: higher limit later");
        eq(1, p.active.passes, "mixed: passes stay low");

        Policy q = locked(Rules.DEFAULT);
        q.request(Rules.DEFAULT.withLimits(90, 3, 60, 72));
        eq(72, q.active.delayH, "longer wait applies at once");
        eq(72 * HOUR, q.pendingRemainingMs, "and the new, longer wait is the one used");
    }

    static void policyWaitKeepsOrRestarts() {
        Policy p = locked(Rules.DEFAULT);
        p.request(Rules.DEFAULT.withLimits(90, 3, 60, 24));
        p.advance(20 * HOUR);
        eq(Policy.Outcome.WAITING, p.request(Rules.DEFAULT.withLimits(90, 3, 60, 24)),
                "asking for the same thing again");
        eq(4 * HOUR, p.pendingRemainingMs, "same request keeps the time already waited");
        p.request(Rules.DEFAULT.withLimits(80, 3, 60, 24));
        eq(4 * HOUR, p.pendingRemainingMs, "a smaller raise keeps the time already waited");
        eq(80, p.pending.limitMin, "and becomes the target");
        p.request(Rules.DEFAULT.withLimits(120, 3, 60, 24));
        eq(24 * HOUR, p.pendingRemainingMs, "a bigger raise starts the wait over");
        p.request(Rules.DEFAULT.withLimits(120, 3, 60, 12));
        eq(24 * HOUR, p.pendingRemainingMs, "shortening the wait itself has to wait the old wait");
        eq(24, p.active.delayH, "wait unchanged for now");
    }

    static void policyAppsAddNowRemoveLater() {
        Policy p = locked(Rules.DEFAULT);
        eq(Policy.Outcome.APPLIED, p.request(Rules.DEFAULT.withApp("com.example.video")),
                "adding an app applies now");
        check(p.active.extraApps.contains("com.example.video"), "app counted at once");
        eq(Policy.Outcome.WAITING, p.request(p.active.withoutApp("com.example.video")),
                "removing an app waits");
        check(p.active.extraApps.contains("com.example.video"), "still counted while waiting");
        p.advance(DAY);
        check(!p.active.extraApps.contains("com.example.video"), "removed after the wait");

        Policy q = locked(Rules.DEFAULT);
        q.request(Rules.DEFAULT.withLimits(90, 3, 60, 24));
        q.advance(10 * HOUR);
        eq(Policy.Outcome.APPLIED_AND_WAITING, q.request(q.target().withApp("org.other.app")),
                "adding an app while a raise waits");
        check(q.active.extraApps.contains("org.other.app"), "the app counts at once");
        eq(14 * HOUR, q.pendingRemainingMs, "and the raise keeps its place in line");
    }

    static void policyCancelAndSameAsActive() {
        Policy p = locked(Rules.DEFAULT);
        p.request(Rules.DEFAULT.withLimits(90, 3, 60, 24));
        p.cancelPending();
        eq(null, p.pending, "cancel clears the waiting change");
        p.advance(2 * DAY);
        eq(60, p.active.limitMin, "a cancelled change never applies");
        p.request(Rules.DEFAULT.withLimits(90, 3, 60, 24));
        eq(Policy.Outcome.APPLIED, p.request(p.active), "asking for the current rules");
        eq(null, p.pending, "drops the waiting change");
    }

    // ------------------------------------------------------------------ ClockGuard

    static void clockIgnoresManualChanges() {
        ClockGuard c = new ClockGuard();
        long t0 = at(2026, 11, 3, 21, 0, SAO_PAULO);
        long e0 = 5 * HOUR;
        eq(t0, c.observe(t0, e0, 7, false), "first observation is trusted");
        // One minute later the clock is set forward by a day.
        long trusted = c.observe(t0 + MIN + DAY, e0 + MIN, 7, false);
        eq(t0 + MIN, trusted, "jumping a day ahead is ignored");
        eq(MIN, c.lastDelta(), "real time passed: one minute");
        // And back by two days.
        trusted = c.observe(t0 + 2 * MIN - DAY, e0 + 2 * MIN, 7, false);
        eq(t0 + 2 * MIN, trusted, "jumping back is ignored too");
        // Setting it right again by hand.
        trusted = c.observe(t0 + 3 * MIN, e0 + 3 * MIN, 7, false);
        eq(t0 + 3 * MIN, trusted, "setting it right again");
        eq(0L, c.skewMs, "no skew left");
    }

    static void clockFollowsNetworkFixes() {
        // Manual change forward with automatic time off, then automatic time back on.
        ClockGuard c = new ClockGuard();
        long t0 = at(2026, 11, 3, 21, 0, SAO_PAULO);
        c.observe(t0, HOUR, 3, false);
        c.observe(t0 + MIN + DAY, HOUR + MIN, 3, false);
        // Automatic time switched on, network time not in yet: still the manual clock.
        eq(t0 + 2 * MIN, c.observe(t0 + 2 * MIN + DAY, HOUR + 2 * MIN, 3, true),
                "before network time arrives, the skew still applies");
        eq(t0 + 3 * MIN, c.observe(t0 + 3 * MIN, HOUR + 3 * MIN, 3, true),
                "network time puts the clock back");
        eq(0L, c.skewMs, "skew gone");

        // Manual change backward, then network time.
        ClockGuard d = new ClockGuard();
        d.observe(t0, HOUR, 3, false);
        d.observe(t0 + MIN - DAY, HOUR + MIN, 3, false);
        eq(t0 + 2 * MIN, d.observe(t0 + 2 * MIN, HOUR + 2 * MIN, 3, true),
                "network time after a backward change");
        eq(0L, d.skewMs, "skew gone after backward change");

        // A clock that was wrong at boot, fixed by network time.
        ClockGuard e = new ClockGuard();
        long wrong = at(2020, 1, 1, 0, 0, SAO_PAULO);
        e.observe(wrong, 30_000, 4, true);
        eq(t0, e.observe(t0, 60_000, 4, true), "network fix of a wrong clock is trusted");
    }

    static void clockAfterReboot() {
        ClockGuard c = new ClockGuard();
        long t0 = at(2026, 11, 3, 21, 0, SAO_PAULO);
        c.observe(t0, 10 * HOUR, 5, false);
        long later = t0 + 3 * HOUR;
        eq(later, c.observe(later, 2 * MIN, 6, false), "after a reboot the clock is taken as is");
        eq(0L, c.lastDelta(), "time while off isn't known");
        c.observe(later + MIN, 3 * MIN, 6, false);
        eq(MIN, c.lastDelta(), "counting resumes after the reboot");
        // A manual change before the reboot stays in effect after it.
        ClockGuard d = new ClockGuard();
        d.observe(t0, HOUR, 5, false);
        d.observe(t0 + MIN + DAY, HOUR + MIN, 5, false);
        eq(t0 + 10 * MIN, d.observe(t0 + 10 * MIN + DAY, 2 * MIN, 6, false),
                "manual change survives a reboot");
    }

    static void clockSmallAdjustments() {
        ClockGuard c = new ClockGuard();
        long t0 = at(2026, 11, 3, 21, 0, SAO_PAULO);
        c.observe(t0, HOUR, 1, true);
        eq(t0 + HOUR + 2000, c.observe(t0 + HOUR + 2000, 2 * HOUR, 1, true),
                "a 2 s network correction is accepted");
        eq(0L, c.skewMs, "no skew from small corrections");
        c.observe(t0 + 2 * HOUR + 4 * MIN, 3 * HOUR, 1, false);
        eq(0L, c.skewMs, "changes under 5 minutes are ignored");
    }

    static void clockTrustsNetworkTime() {
        long t0 = at(2026, 11, 3, 21, 0, SAO_PAULO);
        // The clock was set a day ahead after a restart, where observe() can't see it.
        ClockGuard c = new ClockGuard();
        c.observe(t0, HOUR, 5, false);
        long wall = t0 + 10 * MIN + DAY;
        eq(wall, c.observe(wall, 2 * MIN, 6, false), "after a restart the change goes unseen");
        eq(t0 + 10 * MIN, c.trustNetworkTime(wall, t0 + 10 * MIN), "network time catches it");
        eq(t0 + 11 * MIN, c.observe(wall + MIN, 3 * MIN, 6, false), "and it sticks");
        // Setting the clock right again by hand, then the next network check.
        eq(t0 + 12 * MIN, c.observe(t0 + 12 * MIN, 4 * MIN, 6, false), "set right by hand");
        eq(t0 + 12 * MIN, c.trustNetworkTime(t0 + 12 * MIN, t0 + 12 * MIN), "network agrees");
        eq(0L, c.skewMs, "no skew left");
        // A phone clock a few seconds off from network time keeps its own time.
        ClockGuard d = new ClockGuard();
        d.observe(t0, HOUR, 1, true);
        eq(t0, d.trustNetworkTime(t0, t0 - 3000), "seconds of difference don't matter");
        eq(0L, d.skewMs, "no skew from seconds of difference");
    }

    // ------------------------------------------------------------------ Ledger

    static void ledgerResetsAtMidnight() {
        Ledger l = new Ledger();
        long evening = at(2026, 11, 3, 21, 0, SAO_PAULO);
        check(!l.rollIfDue(evening, SAO_PAULO), "first look doesn't reset");
        eq(at(2026, 11, 4, 0, 0, SAO_PAULO), l.nextResetAt, "next reset is midnight");
        l.usedMs = 45 * MIN;
        l.passesUsed = 2;
        l.warned5 = true;
        check(!l.rollIfDue(at(2026, 11, 3, 23, 59, SAO_PAULO), SAO_PAULO), "not yet at 23:59");
        eq(45 * MIN, l.usedMs, "usage kept before midnight");
        check(l.rollIfDue(at(2026, 11, 4, 0, 0, SAO_PAULO) + 1000, SAO_PAULO), "resets at midnight");
        eq(0L, l.usedMs, "usage cleared");
        eq(0, l.passesUsed, "passes back");
        check(!l.warned5, "warnings back");
        eq(at(2026, 11, 5, 0, 0, SAO_PAULO), l.nextResetAt, "following reset is the next midnight");
    }

    static void ledgerTimeZoneCantBringResetForward() {
        Ledger l = new Ledger();
        long t = at(2026, 11, 3, 23, 0, SAO_PAULO);
        l.rollIfDue(t, SAO_PAULO);
        // Switching to the zone with the earliest midnight doesn't move the stored reset.
        check(!l.rollIfDue(t + MIN, KIRITIMATI), "a zone change alone doesn't reset");
        long midnight = at(2026, 11, 4, 0, 0, SAO_PAULO);
        check(l.rollIfDue(midnight, KIRITIMATI), "the stored midnight still resets");
        check(l.nextResetAt >= midnight + Ledger.MIN_DAY_MS, "the next reset is at least 20 h later");
        check(!l.rollIfDue(midnight + 19 * HOUR, KIRITIMATI), "no second reset within 20 h");
    }

    static void ledgerPhoneOffForDays() {
        Ledger l = new Ledger();
        l.rollIfDue(at(2026, 11, 3, 21, 0, AMSTERDAM), AMSTERDAM);
        l.usedMs = 30 * MIN;
        long back = at(2026, 11, 7, 8, 0, AMSTERDAM);
        check(l.rollIfDue(back, AMSTERDAM), "resets when the phone comes back");
        eq(0L, l.usedMs, "usage cleared");
        eq(at(2026, 11, 8, 0, 0, AMSTERDAM), l.nextResetAt, "next reset is the coming midnight");
        check(!l.rollIfDue(back + HOUR, AMSTERDAM), "only one reset");
    }

    static void ledgerRecoversFromFarFutureReset() {
        Ledger l = new Ledger();
        l.rollIfDue(at(2027, 6, 1, 12, 0, AMSTERDAM), AMSTERDAM);
        long now = at(2026, 11, 3, 12, 0, AMSTERDAM);
        check(!l.rollIfDue(now, AMSTERDAM), "a far-future reset isn't a reset");
        eq(at(2026, 11, 4, 0, 0, AMSTERDAM), l.nextResetAt, "but it's moved to the next midnight");
    }

    static void ledgerDaylightSaving() {
        Ledger l = new Ledger();
        // Clocks go back on 25 October 2026 in Amsterdam: that day has 25 hours.
        l.rollIfDue(at(2026, 10, 24, 12, 0, AMSTERDAM), AMSTERDAM);
        check(l.rollIfDue(at(2026, 10, 25, 0, 0, AMSTERDAM), AMSTERDAM), "reset at midnight");
        eq(at(2026, 10, 26, 0, 0, AMSTERDAM), l.nextResetAt, "next midnight after a 25 h day");
        eq(25 * HOUR, l.nextResetAt - l.lastResetAt, "the day really is 25 h long");
    }

    // ------------------------------------------------------------------ State and Fmt

    static void stateRoundTrip() {
        State s = new State();
        s.clock.observe(at(2026, 11, 3, 21, 0, SAO_PAULO), HOUR, 9, false);
        s.clock.skewMs = DAY;
        s.ledger.rollIfDue(at(2026, 11, 3, 21, 0, SAO_PAULO), SAO_PAULO);
        s.ledger.usedMs = 12 * MIN;
        s.ledger.passesUsed = 1;
        s.ledger.warned5 = true;
        s.policy.locked = true;
        s.policy.request(Rules.DEFAULT.withLimits(90, 3, 60, 24).withApp("org.x.y"));
        s.failClosed.add("app.revanced.android.youtube");
        s.flashSuspended.add("com.google.android.youtube");
        Map<String, Object> m = s.toMap();
        State r = State.load(m);
        eq(s.clock.skewMs, r.clock.skewMs, "skew kept");
        eq(s.clock.anchorWall, r.clock.anchorWall, "anchor wall kept");
        eq(s.clock.anchorElapsed, r.clock.anchorElapsed, "anchor elapsed kept");
        eq(s.clock.anchorBoot, r.clock.anchorBoot, "boot kept");
        eq(s.ledger.usedMs, r.ledger.usedMs, "usage kept");
        eq(s.ledger.passesUsed, r.ledger.passesUsed, "passes kept");
        eq(s.ledger.warned5, r.ledger.warned5, "warning kept");
        eq(s.ledger.nextResetAt, r.ledger.nextResetAt, "next reset kept");
        eq(s.policy.active, r.policy.active, "active rules kept");
        eq(s.policy.pending, r.policy.pending, "pending rules kept");
        eq(s.policy.pendingRemainingMs, r.policy.pendingRemainingMs, "wait kept");
        eq(true, r.policy.locked, "lock kept");
        eq(s.failClosed, r.failClosed, "fail-closed list kept");
        eq(s.flashSuspended, r.flashSuspended, "flash list kept");
        State empty = State.load(Collections.<String, Object>emptyMap());
        eq(Rules.DEFAULT, empty.policy.active, "fresh install starts with the defaults");
        check(!empty.policy.locked, "fresh install starts unlocked");
    }

    static void formatting() {
        eq("0 s", Fmt.duration(0), "zero");
        eq("45 s", Fmt.duration(45_000), "seconds");
        eq("12 min", Fmt.duration(12 * MIN + 59_000), "minutes round down");
        eq("2 h 05 min", Fmt.duration(2 * HOUR + 5 * MIN), "hours");
        eq("1 s", Fmt.durationUp(1), "a moment left rounds up to a second");
        eq("1 min", Fmt.durationUp(59_500), "just under a minute rounds up to a minute");
        eq("13 min", Fmt.durationUp(12 * MIN + 1), "time to wait rounds up");
        eq("24 h 00 min", Fmt.durationUp(DAY), "a full day");
    }

    // ------------------------------------------------------------------ helpers

    private static Policy locked(Rules r) {
        Policy p = new Policy();
        p.request(r);
        p.locked = true;
        return p;
    }

    private static long at(int y, int mo, int d, int h, int mi, ZoneId z) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, z).toInstant().toEpochMilli();
    }

    private static HashSet<String> set(String... s) {
        return new HashSet<String>(Arrays.asList(s));
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok && expected instanceof Number && actual instanceof Number) {
            ok = ((Number) expected).longValue() == ((Number) actual).longValue();
        }
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what + ": expected " + expected + ", got " + actual);
        }
    }
}
