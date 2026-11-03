package io.github.zinzuzu.watchlimit.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The apps whose screen time counts. All of them share one daily budget, so switching
 * to another YouTube app doesn't get around the limit. YouTube Music isn't on the list,
 * because listening is what the limit is meant to leave alone.
 */
public final class Tracked {
    /** Package name to a readable name. Also listed under queries in the manifest. */
    public static final Map<String, String> BUILT_IN;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("app.revanced.android.youtube", "YouTube ReVanced");
        m.put("com.google.android.youtube", "YouTube");
        m.put("app.rvx.android.youtube", "YouTube ReVanced Extended");
        m.put("app.morphe.android.youtube", "YouTube Morphe");
        m.put("com.vanced.android.youtube", "YouTube Vanced");
        m.put("com.google.android.apps.youtube.kids", "YouTube Kids");
        m.put("org.schabi.newpipe", "NewPipe");
        m.put("org.polymorphicshade.tubular", "Tubular");
        m.put("InfinityLoop1309.NewPipeEnhanced", "PipePipe");
        m.put("com.github.libretube", "LibreTube");
        m.put("com.futo.platformplayer", "Grayjay");
        BUILT_IN = Collections.unmodifiableMap(m);
    }

    private Tracked() {
    }

    public static Set<String> all(Rules rules) {
        TreeSet<String> s = new TreeSet<String>(BUILT_IN.keySet());
        s.addAll(rules.extraApps);
        return s;
    }
}
