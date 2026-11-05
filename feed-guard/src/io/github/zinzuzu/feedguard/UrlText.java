package io.github.zinzuzu.feedguard;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the page's host from what Firefox shows in its address bar.
 *
 * Firefox's address bar comes in two shapes. The older view-based toolbar shows
 * the address as plain text. The newer Compose toolbar exposes one button whose
 * description is "{title} {url}. {hint}", for example
 * "Instagram instagram.com/direct/inbox/. Search or enter address". The hint is
 * translated and the title can be anything, so the address is taken to be the
 * last word that looks like a web address.
 *
 * Pure Java, so it can be tested off the phone.
 */
final class UrlText {
    private UrlText() {}

    private static final String[] GUARDED = {"instagram.com", "reddit.com"};

    // One label of a host name: letters, digits and inner hyphens.
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    // Top-level domain: letters only (or punycode), at least two characters.
    private static final Pattern TLD = Pattern.compile("(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})");
    // Words in a page title that mean an Instagram or Reddit page. Only used
    // when the address bar can't be read at all.
    private static final Pattern GUARDED_TITLE = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(instagram|reddit)(?![\\p{L}\\p{N}])");

    /** The host of the last address-like word in the text, lower-cased, or null. */
    static String host(CharSequence text) {
        if (text == null) return null;
        String[] words = text.toString().trim().split("\\s+");
        for (int i = words.length - 1; i >= 0; i--) {
            String h = hostOfWord(words[i]);
            if (h != null) return h;
        }
        return null;
    }

    /** The host if this single word looks like a web address, otherwise null. */
    static String hostOfWord(String word) {
        if (word == null) return null;
        String w = word.trim();
        // Strip wrapping punctuation, then the ". " that separates the address from the hint.
        w = w.replaceAll("^[\"'(\\[<“‘]+", "").replaceAll("[\"')\\]>,;:”’]+$", "");
        while (w.endsWith(".")) w = w.substring(0, w.length() - 1);
        if (w.isEmpty()) return null;
        String lower = w.toLowerCase(Locale.ROOT);
        // "view-source:https://..." and other wrappers: keep what follows the last scheme.
        int scheme = lower.lastIndexOf("://");
        if (scheme >= 0) {
            lower = lower.substring(scheme + 3);
        } else if (lower.contains(":") && !lower.matches("^[^/:]+:\\d+(?:[/?#].*)?$")) {
            // "about:blank", "moz-extension:..." and similar: not a web host.
            return null;
        }
        int end = lower.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = lower.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        String authority = lower.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);
        while (authority.endsWith(".")) authority = authority.substring(0, authority.length() - 1);
        return isHostName(authority) ? authority : null;
    }

    static boolean isHostName(String h) {
        if (h == null || h.length() > 253) return false;
        String[] labels = h.split("\\.", -1);
        if (labels.length < 2) return false;
        for (int i = 0; i < labels.length; i++) {
            if (!LABEL.matcher(labels[i]).matches()) return false;
        }
        return TLD.matcher(labels[labels.length - 1]).matches();
    }

    /** True for instagram.com, reddit.com and their subdomains. */
    static boolean isGuardedHost(String host) {
        if (host == null) return false;
        for (String g : GUARDED) {
            if (host.equals(g) || host.endsWith("." + g)) return true;
        }
        return false;
    }

    /** Fallback for when the address bar can't be read: the page title names Instagram or Reddit. */
    static boolean titleLooksGuarded(CharSequence title) {
        return title != null && GUARDED_TITLE.matcher(title).find();
    }
}
