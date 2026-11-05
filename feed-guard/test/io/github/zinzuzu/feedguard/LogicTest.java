package io.github.zinzuzu.feedguard;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tests for the parts of Feed Guard that don't need a phone: reading the
 * address bar, the add-on's marker, the cover decision and the app rules.
 * Run with test/run.sh. No test framework, so it runs on a plain JDK.
 */
public final class LogicTest {
    private static int passed, failed;

    public static void main(String[] args) {
        urlText();
        markerText();
        coverDecision();
        appRules();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // --- Address bar -------------------------------------------------------

    static void urlText() {
        // Compose toolbar: "{title} {url}. {hint}"
        eq("compose, feed", "instagram.com", UrlText.host("Instagram instagram.com/. Search or enter address"));
        eq("compose, full url", "www.reddit.com",
                UrlText.host("Reddit - Dive into anything https://www.reddit.com/. Search or enter address"));
        eq("compose, no title", "www.instagram.com", UrlText.host(" https://www.instagram.com/direct/inbox/. Search or enter address"));
        eq("compose, home screen shows only the hint", null, UrlText.host("Search or enter address"));
        eq("title mentioning another site doesn't win", "example.org",
                UrlText.host("Why I quit reddit.com example.org/post. Search or enter address"));
        eq("German hint with z.B.", "instagram.com", UrlText.host("Instagram instagram.com. Suche z.B. oder Adresse eingeben"));
        // View-based toolbar: plain text
        eq("plain host", "instagram.com", UrlText.host("instagram.com"));
        eq("plain url", "www.instagram.com", UrlText.host("https://www.instagram.com/direct/inbox/"));
        eq("view-source", "www.instagram.com", UrlText.host("view-source:https://www.instagram.com/"));
        eq("port", "instagram.com", UrlText.host("instagram.com:443/explore/"));
        eq("user info", "www.reddit.com", UrlText.host("https://user@www.reddit.com/r/pics"));
        eq("quoted", "instagram.com", UrlText.host("“instagram.com”"));
        eq("about page", null, UrlText.host("about:blank"));
        eq("add-on page", null, UrlText.host("moz-extension://4f1c2d3e-aaaa-bbbb-cccc-1234567890ab/blocked.html"));
        eq("search words", null, UrlText.host("reddit nsfw"));
        eq("null", null, UrlText.host(null));

        check("guarded: instagram.com", UrlText.isGuardedHost("instagram.com"));
        check("guarded: www.instagram.com", UrlText.isGuardedHost("www.instagram.com"));
        check("guarded: l.instagram.com", UrlText.isGuardedHost("l.instagram.com"));
        check("guarded: reddit.com", UrlText.isGuardedHost("reddit.com"));
        check("guarded: old.reddit.com", UrlText.isGuardedHost("old.reddit.com"));
        check("not guarded: notinstagram.com", !UrlText.isGuardedHost("notinstagram.com"));
        check("not guarded: ddinstagram.com", !UrlText.isGuardedHost("ddinstagram.com"));
        check("not guarded: instagram.com.evil.net", !UrlText.isGuardedHost("instagram.com.evil.net"));
        check("not guarded: redditmedia.com", !UrlText.isGuardedHost("redditmedia.com"));
        check("not guarded: null", !UrlText.isGuardedHost(null));

        check("title: Instagram", UrlText.titleLooksGuarded("Instagram"));
        check("title: Reddit home", UrlText.titleLooksGuarded("Reddit - Dive into anything"));
        check("title: profile", UrlText.titleLooksGuarded("Ana (@ana) • Instagram photos and videos"));
        check("title: AskReddit isn't a match", !UrlText.titleLooksGuarded("AskReddit"));
        check("title: inbox", !UrlText.titleLooksGuarded("Inbox • Direct"));
        check("title: null", !UrlText.titleLooksGuarded(null));
    }

    // --- Marker ------------------------------------------------------------

    static void markerText() {
        long now = 1_800_000_000_000L; // ms
        long s = now / 1000;
        check("fresh: now", MarkerText.isFresh("feedguard " + s, now));
        check("fresh: 12 s old", MarkerText.isFresh("feedguard " + (s - 12), now));
        check("stale: 13 s old", !MarkerText.isFresh("feedguard " + (s - 13), now));
        check("fresh: 5 s ahead", MarkerText.isFresh("feedguard " + (s + 5), now));
        check("stale: 6 s ahead", !MarkerText.isFresh("feedguard " + (s + 6), now));
        check("not a marker: text", !MarkerText.isFresh("feedguard abc", now));
        check("not a marker: empty", !MarkerText.isFresh("feedguard ", now));
        check("not a marker: other", !MarkerText.isFresh("Instagram", now));
        check("not a marker: null", !MarkerText.isFresh(null, now));
        check("not a marker: negative", !MarkerText.isFresh("feedguard -5", now));
    }

    // --- Cover decision ----------------------------------------------------

    static final long WALL = 1_800_000_000_000L;

    static CoverDecision.Input page(String url, boolean marker, boolean armed, long t) {
        CoverDecision.Input in = new CoverDecision.Input();
        in.firefoxVisible = true;
        in.urlBoxFound = url != null;
        in.urlText = url;
        in.markerLabel = marker ? "feedguard " + ((WALL + t) / 1000) : null;
        in.armed = armed;
        in.nowElapsedMs = 10_000 + t;
        in.nowWallMs = WALL + t;
        return in;
    }

    static final String IG = "Instagram instagram.com/. Search or enter address";
    static final String IG_DM = "Inbox • Direct instagram.com/direct/inbox/. Search or enter address";
    static final String OTHER = "Example example.org/. Search or enter address";

    static void coverDecision() {
        CoverDecision d = new CoverDecision();
        CoverDecision.Outcome o = d.decide(page(IG, false, false, 0));
        check("not armed: no cover", !o.cover && !o.arm && o.guardedPage);
        o = d.decide(page(IG, false, false, 60_000));
        check("not armed: still no cover later", !o.cover);

        d = new CoverDecision();
        o = d.decide(page(IG, true, false, 0));
        check("first fresh marker arms", o.arm && !o.cover && o.markerFresh);

        d = new CoverDecision();
        check("armed, marker missing: grace at 0 s", !d.decide(page(IG, false, true, 0)).cover);
        check("armed, marker missing: grace at 2.4 s", !d.decide(page(IG, false, true, 2400)).cover);
        check("armed, marker missing: covered at 2.5 s", d.decide(page(IG, false, true, 2500)).cover);
        check("covered while marker stays missing", d.decide(page(IG_DM, false, true, 5000)).cover);
        check("marker back: uncovered", !d.decide(page(IG, true, true, 6000)).cover);

        d = new CoverDecision();
        CoverDecision.Input in = page(IG, false, true, 0);
        in.loading = true;
        d.decide(in);
        in = page(IG, false, true, 7900);
        in.loading = true;
        check("loading: longer grace", !d.decide(in).cover);
        in = page(IG, false, true, 8000);
        in.loading = true;
        check("loading: covered after 8 s", d.decide(in).cover);
        check("covered stays covered when loading starts again", d.decide(page(IG, false, true, 8100)).cover);

        d = new CoverDecision();
        d.decide(page(OTHER, false, true, 0));
        check("other site: never covered", !d.decide(page(OTHER, false, true, 30_000)).cover);

        d = new CoverDecision();
        d.decide(page(IG, false, true, 0));
        d.decide(page(IG, false, true, 3000));
        in = page(IG, false, true, 4000);
        in.firefoxVisible = false;
        check("Firefox off screen: no cover", !d.decide(in).cover);
        check("same page again: covered at once", d.decide(page(IG, false, true, 5000)).cover);
        check("a different guarded page gets the grace", !new CoverDecision().decide(page(IG_DM, false, true, 0)).cover);

        // Address bar briefly missing (full-screen video): the last reading holds for 15 s.
        d = new CoverDecision();
        d.decide(page(IG, false, true, 0));
        check("covered", d.decide(page(IG, false, true, 3000)).cover);
        in = page(null, false, true, 10_000);
        in.pageTitle = "Inbox • Direct";
        check("address bar gone: memory keeps it covered", d.decide(in).cover);
        in = page(null, false, true, 19_000);
        in.pageTitle = "Instagram";
        check("memory expired: title says Instagram", d.decide(in).cover);
        in = page(null, false, true, 20_000);
        in.pageTitle = "Inbox • Direct";
        check("memory expired: other title isn't covered", !d.decide(in).cover);

        // Address bar never readable: title fallback.
        d = new CoverDecision();
        in = page(null, false, true, 0);
        in.pageTitle = "Reddit - Dive into anything";
        d.decide(in);
        in = page(null, false, true, 3000);
        in.pageTitle = "Reddit - Dive into anything";
        check("no address bar: Reddit title covered", d.decide(in).cover);

        // Home screen: the address bar only shows the hint.
        d = new CoverDecision();
        d.decide(page(IG, false, true, 0));
        d.decide(page(IG, false, true, 3000));
        check("new tab: uncovered", !d.decide(page("Search or enter address", false, true, 3500)).cover);

        // Stale marker counts as missing.
        d = new CoverDecision();
        in = page(IG, true, true, 0);
        d.decide(in);
        in = page(IG, false, true, 1000);
        in.markerLabel = "feedguard " + ((WALL - 20_000) / 1000);
        d.decide(in);
        in = page(IG, false, true, 3600);
        in.markerLabel = "feedguard " + ((WALL - 20_000) / 1000);
        check("stale marker: covered", d.decide(in).cover);
    }

    // --- App rules ---------------------------------------------------------

    static void appRules() {
        Set<String> installed = set("org.mozilla.firefox", "com.android.chrome", "org.mozilla.focus",
                "com.instagram.android", "com.instagram.barcelona", "com.instagram.lite", "com.whatsapp",
                "io.github.zinzuzu.feedguard", "com.reddit.frontpage", "ml.docilealligator.infinityforreddit",
                "org.quantumbadger.redreader", "com.android.vending", "com.bintianqi.owndroid", "com.google.android.webview");
        Set<String> browsers = set("org.mozilla.firefox", "com.android.chrome", "org.mozilla.focus", "com.android.vending");
        Set<String> none = Collections.emptySet();
        eq("pause list", new TreeSet<>(Arrays.asList("com.android.chrome", "com.instagram.android", "com.instagram.lite",
                        "com.reddit.frontpage", "ml.docilealligator.infinityforreddit", "org.mozilla.focus", "org.quantumbadger.redreader")),
                AppRules.toPause(installed, browsers, none, none, "io.github.zinzuzu.feedguard"));
        check("allowed app is left alone",
                !AppRules.toPause(installed, browsers, set("com.android.chrome"), none, "io.github.zinzuzu.feedguard").contains("com.android.chrome"));
        check("extra app is paused",
                AppRules.toPause(installed, browsers, none, set("com.whatsapp"), "io.github.zinzuzu.feedguard").contains("com.whatsapp"));
        check("Firefox is never paused, even if listed",
                !AppRules.toPause(installed, browsers, none, set("org.mozilla.firefox"), "io.github.zinzuzu.feedguard").contains("org.mozilla.firefox"));
        check("Threads is left alone", !AppRules.isFeedApp("com.instagram.barcelona"));
        eq("parse list", set("a.b", "c.d", "e.f", "g.h"), AppRules.parseList(" a.b, c.d;e.f  g.h "));
        eq("parse empty", Collections.<String>emptySet(), AppRules.parseList("  "));
    }

    // --- Harness -----------------------------------------------------------

    static Set<String> set(String... items) {
        return new HashSet<>(Arrays.asList(items));
    }

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) System.out.println("  expected " + expected + ", got " + actual);
        check(name, ok);
    }
}
