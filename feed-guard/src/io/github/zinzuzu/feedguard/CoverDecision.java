package io.github.zinzuzu.feedguard;

/**
 * Decides, check by check, whether to cover Firefox.
 *
 * The rule: while Firefox shows an Instagram or Reddit page, the add-on's
 * marker must be on that page and fresh. If it's missing for longer than a
 * short grace period, the page is covered until it comes back or Firefox
 * leaves that page.
 *
 * Nothing is covered before the guard is "armed", which happens the first time
 * it sees a fresh marker. That way it can be installed before the add-on update
 * that adds the marker reaches the phone, without locking you out of Instagram.
 *
 * Pure Java, so it can be tested off the phone.
 */
final class CoverDecision {
    /** How long the marker may be missing before the page is covered. */
    static final long GRACE_MS = 2500;
    /** Longer grace while Firefox shows its loading bar, since the old page stays up until the new one starts. */
    static final long LOADING_GRACE_MS = 8000;
    /** How long to trust the last address read when the address bar is briefly gone (full-screen video, menus). */
    static final long URL_MEMORY_MS = 15000;

    static final class Input {
        boolean firefoxVisible;
        /** The address bar was found. Its text may still be empty, such as on Firefox's home screen. */
        boolean urlBoxFound;
        String urlText;
        boolean loading;
        String pageTitle;
        String markerLabel;
        boolean armed;
        long nowElapsedMs;
        long nowWallMs;
    }

    static final class Outcome {
        boolean cover;
        boolean guardedPage;
        boolean markerFresh;
        /** First fresh marker seen: the caller should record that the guard is armed. */
        boolean arm;
        /** The page was judged from the address bar rather than memory or the title. */
        boolean fromAddressBar;
    }

    /** Grace isn't given twice for the same address: leaving Firefox and coming back doesn't buy a look. */
    static final long REPEAT_MEMORY_MS = 10 * 60 * 1000;

    private long missingSince = -1;
    private boolean covering;
    private String coveredUrl;
    private long coveredAt;
    private boolean remembered;
    private boolean rememberedGuarded;
    private long rememberedAt;

    Outcome decide(Input in) {
        Outcome out = new Outcome();
        if (!in.firefoxVisible) {
            missingSince = -1;
            covering = false;
            return out;
        }
        out.markerFresh = MarkerText.isFresh(in.markerLabel, in.nowWallMs);
        if (out.markerFresh) coveredUrl = null;

        if (in.urlBoxFound) {
            out.guardedPage = UrlText.isGuardedHost(UrlText.host(in.urlText));
            out.fromAddressBar = true;
            remembered = true;
            rememberedGuarded = out.guardedPage;
            rememberedAt = in.nowElapsedMs;
        } else if (remembered && in.nowElapsedMs - rememberedAt <= URL_MEMORY_MS) {
            out.guardedPage = rememberedGuarded;
        } else {
            out.guardedPage = UrlText.titleLooksGuarded(in.pageTitle);
        }

        out.arm = out.markerFresh && !in.armed;
        boolean armed = in.armed || out.arm;

        if (armed && out.guardedPage && !out.markerFresh) {
            if (missingSince < 0) missingSince = in.nowElapsedMs;
            long grace = in.loading ? LOADING_GRACE_MS : GRACE_MS;
            boolean seenBefore = in.urlBoxFound && in.urlText != null && in.urlText.equals(coveredUrl)
                    && in.nowElapsedMs - coveredAt <= REPEAT_MEMORY_MS;
            // Once covered, stay covered until the marker is back or the page changes.
            covering = covering || seenBefore || in.nowElapsedMs - missingSince >= grace;
            out.cover = covering;
            if (covering && in.urlBoxFound) {
                coveredUrl = in.urlText;
                coveredAt = in.nowElapsedMs;
            }
        } else {
            missingSince = -1;
            covering = false;
        }
        return out;
    }
}
