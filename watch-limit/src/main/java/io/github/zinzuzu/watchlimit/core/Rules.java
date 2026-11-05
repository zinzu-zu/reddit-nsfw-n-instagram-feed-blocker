package io.github.zinzuzu.watchlimit.core;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The user's settings. Immutable.
 *
 * "Stricter" always means less YouTube: a lower limit, fewer or shorter audio passes,
 * a longer wait before loosening, or more apps being watched.
 */
public final class Rules {
    public static final int MAX_LIMIT_MIN = 24 * 60;
    public static final int MAX_PASSES = 10;
    public static final int MIN_PASS_SEC = 15;
    public static final int MAX_PASS_SEC = 300;
    public static final int MIN_DELAY_H = 1;
    public static final int MAX_DELAY_H = 30 * 24;

    public static final Rules DEFAULT =
            new Rules(60, 3, 60, 24, Collections.<String>emptySet());

    private static final Pattern PACKAGE =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    /** Daily limit for screen-on time, in minutes. 0 means YouTube can't be on screen at all. */
    public final int limitMin;
    /** Audio passes per day: short windows to pick something to listen to after the limit. */
    public final int passes;
    /** Length of one audio pass, in seconds. */
    public final int passSec;
    /** Hours a loosening change waits before it takes effect. */
    public final int delayH;
    /** Apps watched on top of the built-in list. Sorted and unmodifiable. */
    public final Set<String> extraApps;

    public Rules(int limitMin, int passes, int passSec, int delayH, Set<String> extraApps) {
        this.limitMin = clamp(limitMin, 0, MAX_LIMIT_MIN);
        this.passes = clamp(passes, 0, MAX_PASSES);
        this.passSec = clamp(passSec, MIN_PASS_SEC, MAX_PASS_SEC);
        this.delayH = clamp(delayH, MIN_DELAY_H, MAX_DELAY_H);
        TreeSet<String> apps = new TreeSet<String>();
        if (extraApps != null) {
            for (String p : extraApps) {
                if (p != null && isPackageName(p.trim())) apps.add(p.trim());
            }
        }
        this.extraApps = Collections.unmodifiableSet(apps);
    }

    public long limitMs() {
        return limitMin * 60_000L;
    }

    public long delayMs() {
        return delayH * 3_600_000L;
    }

    /** Field by field, whichever of the two is stricter. */
    public Rules strictest(Rules o) {
        TreeSet<String> apps = new TreeSet<String>(extraApps);
        apps.addAll(o.extraApps);
        return new Rules(
                Math.min(limitMin, o.limitMin),
                Math.min(passes, o.passes),
                Math.min(passSec, o.passSec),
                Math.max(delayH, o.delayH),
                apps);
    }

    /** True if any field of this one allows more YouTube than {@code o} does. */
    public boolean looserThan(Rules o) {
        return limitMin > o.limitMin
                || passes > o.passes
                || passSec > o.passSec
                || delayH < o.delayH
                || !extraApps.containsAll(o.extraApps);
    }

    public Rules withLimits(int limitMin, int passes, int passSec, int delayH) {
        return new Rules(limitMin, passes, passSec, delayH, extraApps);
    }

    public Rules withApp(String pkg) {
        TreeSet<String> apps = new TreeSet<String>(extraApps);
        apps.add(pkg);
        return new Rules(limitMin, passes, passSec, delayH, apps);
    }

    public Rules withoutApp(String pkg) {
        TreeSet<String> apps = new TreeSet<String>(extraApps);
        apps.remove(pkg);
        return new Rules(limitMin, passes, passSec, delayH, apps);
    }

    public static boolean isPackageName(String s) {
        return s != null && s.length() <= 255 && PACKAGE.matcher(s).matches();
    }

    public String encode() {
        StringBuilder sb = new StringBuilder("1|");
        sb.append(limitMin).append('|').append(passes).append('|')
                .append(passSec).append('|').append(delayH).append('|');
        boolean first = true;
        for (String p : extraApps) {
            if (!first) sb.append(',');
            sb.append(p);
            first = false;
        }
        return sb.toString();
    }

    /** Returns {@code fallback} if {@code s} is missing or unreadable. */
    public static Rules decode(String s, Rules fallback) {
        if (s == null) return fallback;
        String[] f = s.split("\\|", -1);
        if (f.length != 6 || !"1".equals(f[0])) return fallback;
        try {
            TreeSet<String> apps = new TreeSet<String>();
            if (!f[5].isEmpty()) Collections.addAll(apps, f[5].split(","));
            return new Rules(Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                    Integer.parseInt(f[3]), Integer.parseInt(f[4]), apps);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Rules)) return false;
        Rules r = (Rules) o;
        return limitMin == r.limitMin && passes == r.passes && passSec == r.passSec
                && delayH == r.delayH && extraApps.equals(r.extraApps);
    }

    @Override
    public int hashCode() {
        return encode().hashCode();
    }

    @Override
    public String toString() {
        return encode();
    }
}
