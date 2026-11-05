package io.github.zinzuzu.feedguard;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Signature pins for apps on the list. The first time Feed Guard sees a listed
 * app you installed yourself, it remembers the certificate that app was signed
 * with. A later app with the same package name but a different signer (say, a
 * browser repackaged as "com.whatsapp" after the real one was uninstalled)
 * doesn't count as listed.
 *
 * A pin is kept while the package stays on the list, even if the app is
 * uninstalled. Taking it off the list drops the pin, so putting it back
 * accepts whatever copy is installed then.
 *
 * Stored as "package=sha256hex" strings. Pure Java, so it can be tested off
 * the phone.
 */
final class Pins {
    private Pins() {}

    static Map<String, String> parse(Collection<String> stored) {
        Map<String, String> out = new TreeMap<>();
        if (stored == null) return out;
        for (String s : stored) {
            if (s == null) continue;
            int eq = s.indexOf('=');
            if (eq <= 0 || eq == s.length() - 1) continue;
            String hex = s.substring(eq + 1).toLowerCase(Locale.ROOT);
            if (!hex.matches("[0-9a-f]+") || hex.length() % 2 != 0) continue;
            out.put(s.substring(0, eq), hex);
        }
        return out;
    }

    static Set<String> format(Map<String, String> pins) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : pins.entrySet()) out.add(e.getKey() + "=" + e.getValue());
        return out;
    }

    static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return b.toString();
    }

    static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
