package com.omnideck.mobile.ui;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/** Bundled OFL fonts converted from the ones OllamaChat.html embeds (cached). */
public final class Fonts {
    private static final Map<String, Typeface> cache = new HashMap<String, Typeface>();

    private Fonts() {}

    /** Orbitron 700 — titles and the HUD wordmark. */
    public static Typeface title(Context c) {
        return load(c, "fonts/title.ttf", Typeface.create("sans-serif-condensed", Typeface.BOLD));
    }

    /** Orbitron 600 — HUD micro-caps labels. */
    public static Typeface titleMedium(Context c) {
        return load(c, "fonts/title-medium.ttf", Typeface.create("sans-serif-condensed", Typeface.NORMAL));
    }

    /** Share Tech Mono — telemetry readouts and code. */
    public static Typeface mono(Context c) {
        return load(c, "fonts/mono.ttf", Typeface.MONOSPACE);
    }

    /** Inter at 400 / 500 / 600 / 700. */
    public static Typeface inter(Context c, int weight) {
        String file = weight >= 700 ? "fonts/inter-bold.ttf" : weight >= 600 ? "fonts/inter-semibold.ttf"
                : weight >= 500 ? "fonts/inter-medium.ttf" : "fonts/inter-regular.ttf";
        Typeface fallback = Typeface.create(weight >= 600 ? "sans-serif-medium" : "sans-serif",
                weight >= 700 ? Typeface.BOLD : Typeface.NORMAL);
        return load(c, file, fallback);
    }

    private static synchronized Typeface load(Context c, String asset, Typeface fallback) {
        Typeface t = cache.get(asset);
        if (t != null) return t;
        try {
            t = Typeface.createFromAsset(c.getApplicationContext().getAssets(), asset);
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null) t = fallback;
        cache.put(asset, t);
        return t;
    }
}
