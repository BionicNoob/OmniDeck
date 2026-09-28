package com.omnideck.mobile.ui;

import android.content.Context;
import android.graphics.Typeface;

/** The two bundled OFL fonts (converted from the ones OllamaChat.html embeds), cached. */
public final class Fonts {
    private static Typeface title;
    private static Typeface mono;

    private Fonts() {}

    public static synchronized Typeface title(Context c) {
        if (title == null) title = load(c, "fonts/title.ttf", Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return title;
    }

    public static synchronized Typeface mono(Context c) {
        if (mono == null) mono = load(c, "fonts/mono.ttf", Typeface.MONOSPACE);
        return mono;
    }

    private static Typeface load(Context c, String asset, Typeface fallback) {
        try {
            Typeface t = Typeface.createFromAsset(c.getApplicationContext().getAssets(), asset);
            return t != null ? t : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
