package io.github.zinzuzu.watchlimit.core;

/**
 * Which rules are in force, and the waiting period for loosening them.
 *
 * Before the settings are locked, every change applies at once, so the user can set
 * things up and test. After locking, a change applies at once only where it is
 * stricter; everything looser waits {@link Rules#delayH} hours of real time first.
 * The wait counts the monotonic clock, so changing the date doesn't shorten it.
 */
public final class Policy {
    public enum Outcome {
        /** Nothing changed. */
        UNCHANGED,
        /** Everything requested is in force now. */
        APPLIED,
        /** Nothing changed yet; the request waits. */
        WAITING,
        /** The stricter parts apply now and the looser parts wait. */
        APPLIED_AND_WAITING
    }

    public Rules active = Rules.DEFAULT;
    /** Where the rules are heading once the wait is over, or null. */
    public Rules pending;
    public long pendingRemainingMs;
    public boolean locked;

    public Outcome request(Rules wanted) {
        if (!locked) {
            boolean changed = !wanted.equals(active) || pending != null;
            active = wanted;
            clearPending();
            return changed ? Outcome.APPLIED : Outcome.UNCHANGED;
        }
        Rules tightened = active.strictest(wanted);
        boolean tightenedNow = !tightened.equals(active);
        active = tightened;
        if (wanted.looserThan(active)) {
            // Keep the time already waited if the new target is no looser than the one
            // that was already waiting. Otherwise the wait starts over.
            if (pending == null || wanted.looserThan(pending)) {
                pendingRemainingMs = active.delayMs();
            }
            pending = wanted;
            return tightenedNow ? Outcome.APPLIED_AND_WAITING : Outcome.WAITING;
        }
        boolean hadPending = pending != null;
        clearPending();
        return (tightenedNow || hadPending) ? Outcome.APPLIED : Outcome.UNCHANGED;
    }

    /** The settings the user is heading for: the pending ones if any, else the active ones. */
    public Rules target() {
        return pending != null ? pending : active;
    }

    /** Counts down the wait. Returns true if pending rules took effect. */
    public boolean advance(long realMs) {
        if (pending == null || realMs <= 0) return false;
        pendingRemainingMs -= realMs;
        if (pendingRemainingMs > 0) return false;
        active = pending;
        clearPending();
        return true;
    }

    public void cancelPending() {
        clearPending();
    }

    private void clearPending() {
        pending = null;
        pendingRemainingMs = 0;
    }
}
