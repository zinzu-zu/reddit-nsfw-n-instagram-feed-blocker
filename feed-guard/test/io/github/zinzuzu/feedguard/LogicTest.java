package io.github.zinzuzu.feedguard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tests for the parts of Feed Guard that don't need a phone: reading the
 * address bar, the add-on's marker, the cover decision, the app rules (with
 * "Only apps on the list"), when colour is allowed, the accessibility
 * services list and signature pins.
 * Run with test/run.sh. No test framework, so it runs on a plain JDK.
 */
public final class LogicTest {
    private static int passed, failed;

    public static void main(String[] args) {
        urlText();
        markerText();
        coverDecision();
        appRules();
        listRule();
        colourRules();
        serviceList();
        pins();
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

    // --- Only apps on the list ---------------------------------------------

    static final String SELF = "io.github.zinzuzu.feedguard";

    /** A phone like the user's Pixel: what's installed, what came with it, what's in the app drawer. */
    static AppRules.Inputs phone() {
        AppRules.Inputs in = new AppRules.Inputs();
        in.installed = set("org.mozilla.firefox", "com.android.chrome", "org.mozilla.focus", "com.instagram.android",
                "com.whatsapp", "org.telegram.messenger", "com.anthropic.claude", "com.google.android.apps.maps",
                "com.google.android.youtube", "com.google.android.calculator", "com.android.vending",
                "com.google.android.googlequicksearchbox", "com.android.settings", "com.bintianqi.owndroid",
                "io.github.zinzuzu.watchlimit", SELF, "com.google.android.gms", "com.example.sharetarget",
                "com.google.android.apps.nexuslauncher", "com.google.android.dialer", "com.google.android.apps.messaging",
                "com.google.android.contacts", "com.google.android.deskclock", "com.google.android.inputmethod.latin",
                "com.google.android.documentsui", "com.android.providers.media", "helium314.keyboard",
                "com.google.android.apps.photos", "com.google.android.GoogleCamera");
        // Came with the phone: everything except these.
        in.userInstalled = set("org.mozilla.firefox", "org.mozilla.focus", "com.instagram.android", "com.whatsapp",
                "org.telegram.messenger", "com.anthropic.claude", "com.bintianqi.owndroid", "io.github.zinzuzu.watchlimit",
                SELF, "com.example.sharetarget", "helium314.keyboard");
        in.launchable = set("org.mozilla.firefox", "com.android.chrome", "org.mozilla.focus", "com.instagram.android",
                "com.whatsapp", "org.telegram.messenger", "com.anthropic.claude", "com.google.android.apps.maps",
                "com.google.android.youtube", "com.google.android.calculator", "com.android.vending",
                "com.google.android.googlequicksearchbox", "com.android.settings", "com.bintianqi.owndroid",
                "io.github.zinzuzu.watchlimit", SELF, "com.google.android.dialer", "com.google.android.apps.messaging",
                "com.google.android.contacts", "com.google.android.deskclock", "com.google.android.apps.photos",
                "com.google.android.GoogleCamera", "helium314.keyboard");
        in.browsers = set("org.mozilla.firefox", "com.android.chrome", "org.mozilla.focus");
        // HeliBoard is the keyboard in use, so AppGate counts it as a basic.
        in.essential = set(SELF, "helium314.keyboard");
        return in;
    }

    static void listRule() {
        AppRules.Inputs in = phone();
        AppRules.Plan plan = AppRules.plan(in);
        eq("list off: only the browser rule", set("com.android.chrome", "org.mozilla.focus", "com.instagram.android"),
                new HashSet<>(plan.pause));
        check("list off: nothing for the list rule", plan.listRule.isEmpty());

        in.listMode = true;
        in.allowed = set("com.whatsapp", "org.telegram.messenger", "com.anthropic.claude", "com.google.android.apps.maps",
                "com.google.android.calculator");
        plan = AppRules.plan(in);
        eq("list on: everything else in the drawer, and what you installed",
                set("org.mozilla.firefox", "com.android.chrome", "org.mozilla.focus", "com.instagram.android",
                        "com.google.android.youtube", "com.android.vending", "com.google.android.googlequicksearchbox",
                        "com.example.sharetarget", "com.google.android.apps.photos", "com.google.android.GoogleCamera"),
                new HashSet<>(plan.pause));
        check("list on: listed apps open", !plan.pause.contains("com.whatsapp") && !plan.pause.contains("com.google.android.apps.maps"));
        check("list on: the basics stay", !plan.pause.contains("com.google.android.dialer")
                && !plan.pause.contains("com.google.android.apps.messaging") && !plan.pause.contains("com.google.android.contacts")
                && !plan.pause.contains("com.google.android.deskclock") && !plan.pause.contains("com.android.settings")
                && !plan.pause.contains("com.google.android.documentsui"));
        check("list on: the lock stays", !plan.pause.contains("com.bintianqi.owndroid")
                && !plan.pause.contains("io.github.zinzuzu.watchlimit") && !plan.pause.contains(SELF));
        check("list on: the keyboard in use stays", !plan.pause.contains("helium314.keyboard"));
        check("list on: system parts without an icon stay", !plan.pause.contains("com.google.android.gms")
                && !plan.pause.contains("com.android.providers.media"));
        check("list on: Firefox is paused unless listed", plan.pause.contains("org.mozilla.firefox"));
        check("list on: Play Store is paused unless listed", plan.listRule.contains("com.android.vending"));

        in.allowed = set("com.whatsapp", "org.mozilla.firefox");
        check("list on: listed Firefox opens", !AppRules.plan(in).pause.contains("org.mozilla.firefox"));

        in.allowed = set("com.whatsapp", "com.bintianqi.owndroid");
        in.extraBlocked = set("com.bintianqi.owndroid", "com.google.android.dialer");
        plan = AppRules.plan(in);
        check("basics can't be paused, even as extra apps", !plan.pause.contains("com.bintianqi.owndroid")
                && !plan.pause.contains("com.google.android.dialer"));
        in.extraBlocked = Collections.emptySet();

        // The browser rule pauses once; the list rule keeps pausing.
        in = phone();
        in.handled = set("com.android.chrome");
        check("list off: Chrome unpaused in OwnDroid stays unpaused", !AppRules.plan(in).pause.contains("com.android.chrome"));
        in.listMode = true;
        check("list on: Chrome is paused again", AppRules.plan(in).pause.contains("com.android.chrome"));

        // Lifting Feed Guard's own pauses.
        in = phone();
        in.listMode = true;
        in.allowed = set("com.whatsapp");
        in.ours = set("com.whatsapp", "com.google.android.youtube", "com.android.chrome", "gone.app");
        plan = AppRules.plan(in);
        eq("list on: lifts only what's now listed", set("com.whatsapp"), new HashSet<>(plan.release));
        in.listMode = false;
        plan = AppRules.plan(in);
        eq("list off: lifts list pauses, keeps the browser rule's", set("com.whatsapp", "com.google.android.youtube"),
                new HashSet<>(plan.release));
        check("never lifts an uninstalled app", !plan.release.contains("gone.app"));

        in = phone();
        in.ours = set("com.whatsapp");
        in.extraBlocked = set("com.whatsapp");
        check("extra app stays paused", AppRules.plan(in).release.isEmpty());
        in.extraBlocked = Collections.emptySet();
        check("extra app taken off: lifted", AppRules.plan(in).release.contains("com.whatsapp"));

        // The dead-man switch holds Firefox: neither paused nor lifted by the rules.
        in = phone();
        in.listMode = true;
        in.ours = set("org.mozilla.firefox");
        in.allowed = set("org.mozilla.firefox");
        in.skip = set("org.mozilla.firefox");
        plan = AppRules.plan(in);
        check("dead-man Firefox isn't lifted", !plan.release.contains("org.mozilla.firefox"));
        in.allowed = Collections.emptySet();
        check("dead-man Firefox isn't paused twice", !AppRules.plan(in).pause.contains("org.mozilla.firefox"));

        // Signature pins take an app off the list (AppGate removes it from allowed).
        in = phone();
        in.listMode = true;
        in.allowed = set("com.google.android.apps.maps");
        check("a repackaged WhatsApp is paused", AppRules.plan(in).pause.contains("com.whatsapp"));
    }

    // --- Colour ------------------------------------------------------------

    static final long SCREEN = 1080L * 2400;
    static final long BAR = 1080L * 80;
    static final Set<String> MAPS = ColorRules.DEFAULT_COLOUR_APPS;

    static ColorRules.Win app(String pkg) {
        return new ColorRules.Win(ColorRules.APP, pkg, SCREEN);
    }

    static ColorRules.Win win(int kind, long area) {
        return new ColorRules.Win(kind, null, area);
    }

    static boolean colour(ColorRules.Win... windows) {
        return ColorRules.colourAllowed(new ArrayList<>(Arrays.asList(windows)), SCREEN, MAPS);
    }

    static void colourRules() {
        ColorRules.Win statusBar = win(ColorRules.SYSTEM, BAR);
        ColorRules.Win navBar = win(ColorRules.SYSTEM, BAR);
        check("Maps alone: colour", colour(app("com.google.android.apps.maps"), statusBar, navBar));
        check("CoMaps alone: colour", colour(app("app.comaps.google"), statusBar));
        check("Organic Maps alone: colour", colour(app("app.organicmaps")));
        check("WhatsApp: black and white", !colour(app("com.whatsapp"), statusBar, navBar));
        check("home screen with Maps in a corner: black and white",
                !colour(new ColorRules.Win(ColorRules.APP, "com.google.android.apps.maps", SCREEN / 9),
                        app("com.google.android.apps.nexuslauncher")));
        check("Maps next to WhatsApp: black and white", !colour(app("com.google.android.apps.maps"), app("com.whatsapp")));
        check("Maps next to CoMaps: colour", colour(app("com.google.android.apps.maps"), app("app.comaps.fdroid")));
        check("keyboard over Maps: black and white",
                !colour(app("com.google.android.apps.maps"), win(ColorRules.KEYBOARD, SCREEN / 3)));
        check("shade over Maps: black and white", !colour(app("com.google.android.apps.maps"), win(ColorRules.SYSTEM, SCREEN)));
        check("half-screen overlay: black and white",
                !colour(app("com.google.android.apps.maps"), win(ColorRules.SYSTEM, SCREEN / 2)));
        check("small chat bubble: still colour",
                colour(app("com.google.android.apps.maps"), win(ColorRules.SYSTEM, 200L * 200)));
        check("Feed Guard's cover ignored", colour(app("com.google.android.apps.maps"), win(ColorRules.IGNORED, SCREEN)));
        check("lock screen: black and white", !colour(win(ColorRules.SYSTEM, SCREEN)));
        check("app unreadable: black and white", !colour(new ColorRules.Win(ColorRules.APP, null, SCREEN)));
        check("nothing on screen: black and white", !colour());
        check("windows unreadable: black and white", !ColorRules.colourAllowed(null, SCREEN, MAPS));
        check("no colour apps: black and white",
                !ColorRules.colourAllowed(Collections.singletonList(app("com.google.android.apps.maps")), SCREEN,
                        Collections.<String>emptySet()));
        check("screen size unknown: black and white",
                !ColorRules.colourAllowed(Collections.singletonList(app("com.google.android.apps.maps")), 0, MAPS));

        eq("colour apps unset: maps", MAPS, ColorRules.colourApps(null));
        check("default includes Google Maps", MAPS.contains("com.google.android.apps.maps"));
        check("default includes CoMaps from Play and F-Droid",
                MAPS.contains("app.comaps.google") && MAPS.contains("app.comaps.fdroid"));
        eq("colour apps cleared: none", Collections.<String>emptySet(), ColorRules.colourApps("  "));
        eq("colour apps \"none\": none", Collections.<String>emptySet(), ColorRules.colourApps(" None "));
        eq("colour apps set", set("net.osmand", "com.google.android.apps.maps"),
                ColorRules.colourApps("net.osmand, com.google.android.apps.maps"));
    }

    // --- Accessibility services list ----------------------------------------

    static void serviceList() {
        String me = "io.github.zinzuzu.feedguard/io.github.zinzuzu.feedguard.GuardService";
        String other = "io.github.zinzuzu.watchlimit/io.github.zinzuzu.watchlimit.WatchService";
        eq("empty list: just Feed Guard", me, ServiceList.withService(null, me));
        eq("blank list: just Feed Guard", me, ServiceList.withService("", me));
        eq("added after the others", other + ":" + me, ServiceList.withService(other, me));
        eq("stray colons dropped", other + ":" + me, ServiceList.withService(":" + other + ": :", me));
        eq("already there: nothing to do", null, ServiceList.withService(other + ":" + me, me));
        eq("short form counts", null, ServiceList.withService("io.github.zinzuzu.feedguard/.GuardService", me));
        eq("spaces around it count", null, ServiceList.withService(" " + other + " : " + me + " ", me));
        check("a similar name isn't Feed Guard", !ServiceList.contains("io.github.zinzuzu.feedguard/.GuardServiceX", me));
        check("another package isn't Feed Guard", !ServiceList.contains("io.github.zinzuzu.feedguardx/.GuardService", me));
        check("garbage isn't Feed Guard", !ServiceList.contains("garbage", me));
        check("null isn't Feed Guard", !ServiceList.contains(null, me));
        eq("package of a keyboard", "helium314.keyboard", ServiceList.packageOf("helium314.keyboard/.latin.LatinIME"));
        eq("package of nothing", null, ServiceList.packageOf(null));
        eq("package of garbage", null, ServiceList.packageOf("nonsense"));
    }

    // --- Signature pins -----------------------------------------------------

    static void pins() {
        Map<String, String> parsed = Pins.parse(Arrays.asList("com.whatsapp=0A1b", "broken", "=00", "org.x=", "org.y=zz", "org.z=abc"));
        Map<String, String> want = new HashMap<>();
        want.put("com.whatsapp", "0a1b");
        eq("pins: good entries only", want, new HashMap<>(parsed));
        eq("pins: round trip", parsed, Pins.parse(Pins.format(parsed)));
        eq("pins: nothing stored", 0, Pins.parse(null).size());
        eq("hex", "00ff10", Pins.hex(new byte[] {0, (byte) 0xff, 0x10}));
        eq("bytes", "00ff10", Pins.hex(Pins.bytes("00ff10")));
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
