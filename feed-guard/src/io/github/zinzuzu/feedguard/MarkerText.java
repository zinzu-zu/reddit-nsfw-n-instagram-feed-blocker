package io.github.zinzuzu.feedguard;

/**
 * The add-on puts a tiny invisible element on every Instagram and Reddit page,
 * labelled "feedguard {unix seconds}", and rewrites the time every two seconds.
 * The element only shows while the add-on's stylesheet is applied, and the time
 * only moves while the add-on's script runs. Turning the add-on off therefore
 * makes the label disappear or go stale.
 *
 * Pure Java, so it can be tested off the phone.
 */
final class MarkerText {
    private MarkerText() {}

    static final String ELEMENT_ID = "rnb-guard-marker";
    static final String PREFIX = "feedguard ";
    /** How old the label may be. The add-on writes it every 2 s, and right away when a tab comes back. */
    static final long MAX_AGE_SECONDS = 12;
    /** Allowance for a label slightly ahead of the phone's clock. */
    static final long MAX_AHEAD_SECONDS = 5;

    static boolean looksLikeMarker(CharSequence text) {
        return text != null && text.toString().trim().startsWith(PREFIX);
    }

    /** The label's time in unix seconds, or -1 if it isn't a marker label. */
    static long seconds(CharSequence label) {
        if (!looksLikeMarker(label)) return -1;
        String digits = label.toString().trim().substring(PREFIX.length()).trim();
        if (digits.isEmpty() || digits.length() > 12) return -1;
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return -1;
        }
        return Long.parseLong(digits);
    }

    /** True if the label is a marker written within the last MAX_AGE_SECONDS. */
    static boolean isFresh(CharSequence label, long nowMillis) {
        long s = seconds(label);
        if (s < 0) return false;
        long now = nowMillis / 1000;
        return s <= now + MAX_AHEAD_SECONDS && now - s <= MAX_AGE_SECONDS;
    }
}
