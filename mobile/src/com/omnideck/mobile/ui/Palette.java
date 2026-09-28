package com.omnideck.mobile.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;

import com.omnideck.mobile.R;

/**
 * Colors, fonts and shape language for the two OMNI-DECK looks: "cyber"
 * (the web app's default dark theme: near-black, neon red/cyan, chamfered
 * corners, Share Tech Mono) and "light" (its "modern" theme: white, slate
 * blue, rounded corners, sans-serif).
 */
public final class Palette {
    public static final String AUTO = "auto";
    public static final String CYBER = "cyber";
    public static final String LIGHT = "light";

    public final boolean dark;
    public final boolean chamfer;
    public final int themeRes;

    public final int bg;          // chat area
    public final int surface;     // top bar / chrome
    public final int panel;       // cards, composer area
    public final int input;       // text field fill
    public final int text;
    public final int dim;
    public final int accent;      // primary action (cyber red / slate blue)
    public final int accent2;     // secondary accent (cyber cyan / slate blue)
    public final int ok;
    public final int warn;
    public final int danger;
    public final int edge;        // hairlines
    public final int inputEdge;

    public final int userFill, userStroke, userText, userLabel;
    public final int aiFill, aiStroke, aiBar, aiText;
    public final int noticeFill, noticeStroke, noticeText;
    public final int errorFill, errorStroke;
    public final int codeBg, codeText, inlineCodeBg, inlineCodeText, link;
    public final int sendBg, sendFg;
    public final int chipFill;

    public final Typeface titleFace;
    public final Typeface bodyFace;
    public final Typeface monoFace;
    public final float bodySp;

    private Palette(boolean dark, Context c) {
        this.dark = dark;
        this.chamfer = dark;
        if (dark) {
            themeRes = R.style.Theme_Omni_Cyber;
            bg = 0xFF010103;
            surface = 0xFF05090F;
            panel = 0xFF040408;
            input = 0xFF090910;
            text = 0xFFECECFE;
            dim = 0xFF6C8CA6;
            accent = 0xFFFF003C;
            accent2 = 0xFF00F0FF;
            ok = 0xFF00FF66;
            warn = 0xFFFFB700;
            danger = 0xFFFF003C;
            edge = 0x2E7896B2;
            inputEdge = 0x736E6E92;
            userFill = 0xF009141C;
            userStroke = 0x9900F0FF;
            userText = 0xFFFFFFFF;
            userLabel = 0xFF00F0FF;
            aiFill = 0x14FF003C;
            aiStroke = 0x00000000;
            aiBar = 0xFFFF003C;
            aiText = 0xFFFFFFFF;
            noticeFill = 0xF01A1408;
            noticeStroke = 0x99FFB700;
            noticeText = 0xFFFFFFFF;
            errorFill = 0xF01C0509;
            errorStroke = 0x99FF003C;
            codeBg = 0xFF0C0C16;
            codeText = 0xFFCFE9FF;
            inlineCodeBg = 0x2600F0FF;
            inlineCodeText = 0xFF7FF7FF;
            link = 0xFF00F0FF;
            sendBg = 0xFFFF003C;
            sendFg = 0xFF000000;
            chipFill = 0xFF090910;
            titleFace = Fonts.title(c);
            bodyFace = Fonts.mono(c);
            monoFace = Fonts.mono(c);
            bodySp = 14.5f;
        } else {
            themeRes = R.style.Theme_Omni_Light;
            bg = 0xFFFFFFFF;
            surface = 0xFFEDEFF2;
            panel = 0xFFFFFFFF;
            input = 0xFFF5F5F5;
            text = 0xFF171717;
            dim = 0xFF666666;
            accent = 0xFF4A6D8C;
            accent2 = 0xFF4A6D8C;
            ok = 0xFF1E7A43;
            warn = 0xFF8A5E0B;
            danger = 0xFFB8362E;
            edge = 0x1A171717;
            inputEdge = 0x38171717;
            userFill = 0xFF4A6D8C;
            userStroke = 0x00000000;
            userText = 0xFFFFFFFF;
            userLabel = 0xCCFFFFFF;
            aiFill = 0xFFF5F5F5;
            aiStroke = 0x17171717;
            aiBar = 0x00000000;
            aiText = 0xFF171717;
            noticeFill = 0x1FC08A2E;
            noticeStroke = 0x8CC08A2E;
            noticeText = 0xFF171717;
            errorFill = 0x14DC5B52;
            errorStroke = 0x8CDC5B52;
            codeBg = 0xFFF6F8FA;
            codeText = 0xFF171717;
            inlineCodeBg = 0x14171717;
            inlineCodeText = 0xFF3D5B76;
            link = 0xFF3D5B76;
            sendBg = 0xFF4A6D8C;
            sendFg = 0xFFFFFFFF;
            chipFill = 0xFFFFFFFF;
            titleFace = Typeface.create("sans-serif-medium", Typeface.BOLD);
            bodyFace = Typeface.create("sans-serif", Typeface.NORMAL);
            monoFace = Typeface.MONOSPACE;
            bodySp = 15f;
        }
    }

    /** Resolves "auto" against the phone's dark mode (cyber on phones without one). */
    public static boolean wantsDark(Context c, String pref) {
        if (CYBER.equals(pref)) return true;
        if (LIGHT.equals(pref)) return false;
        if (Build.VERSION.SDK_INT < 29) return true;
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_NO;
    }

    public static Palette forPref(Context c, String pref) {
        return new Palette(wantsDark(c, pref), c);
    }

    public int statusColor(String state) {
        if ("online".equals(state)) return ok;
        if ("searching".equals(state)) return warn;
        return danger;
    }
}
