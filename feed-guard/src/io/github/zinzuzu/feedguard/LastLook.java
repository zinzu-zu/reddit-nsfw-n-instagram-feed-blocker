package io.github.zinzuzu.feedguard;

/**
 * What the guard last saw in Firefox, for the status screen. Kept in memory
 * only. It holds the page's host, never the full address.
 */
final class LastLook {
    private LastLook() {}

    static volatile String summary;
    static volatile long at;

    static void record(PageInspector.Snapshot s, CoverDecision.Outcome o, long wallMs) {
        String host = UrlText.host(s.urlText);
        StringBuilder b = new StringBuilder();
        b.append("Address bar: ").append(s.urlBoxFound ? (host == null ? "found, no address" : host) : "not found");
        b.append("\nPage: ").append(s.webContent ? "found" : "not found");
        b.append("\nAdd-on marker: ").append(s.markerLabel == null ? "not on the page" : (o.markerFresh ? "fresh" : "stale"));
        b.append("\nInstagram or Reddit page: ").append(o.guardedPage ? "yes" : "no");
        b.append("\nLoading: ").append(s.loading ? "yes" : "no");
        b.append("\nCovered: ").append(o.cover ? "yes" : "no");
        b.append("\nItems checked: ").append(s.nodesVisited);
        summary = b.toString();
        at = wallMs;
    }
}
