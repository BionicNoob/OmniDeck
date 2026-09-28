package com.omnideck.mobile.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;

import com.omnideck.mobile.R;
import com.omnideck.mobile.Settings;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Design tokens for OMNI-DECK's three looks, taken from the updated
 * OllamaChat.html (see docs/design-spec.md):
 * <ul>
 * <li><b>Cyber</b> — the "Mainframe HUD": deep navy glass, sky-cyan accent,
 * hairline grid, corner brackets, scan line, Orbitron micro-caps.</li>
 * <li><b>Light</b> — the "modern" theme: white cards, slate-blue accent, Inter.</li>
 * <li><b>Dark</b> — the modern layout on the dark layer (#0F1216).</li>
 * </ul>
 * "System" (the default, like the web app) follows the phone: Light or Dark.
 */
public final class Theme {
    public static final int CYBER = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    public final int id;
    public final String name;
    /** Dark surfaces (light status-bar icons). */
    public final boolean isDark;
    /** Cyber HUD decoration (grid, brackets, bloom, scan line, glow). */
    public final boolean hud;
    public final int themeRes;

    // Surfaces
    public int bg, bgTop, surface, surface2, cap, input, chip, scrim, nav, topbar;
    // Lines
    public int hair, hairSoft, edge, edgeStrong;
    // Ink
    public int ink, inkStrong, dim, faint, label;
    // Accent
    public int accent, accentHover, accentSoft, onAccent, accent2;
    // Status
    public int ok, warn, danger;
    // Chat
    public int userFill, userStroke, userText, userLabel;
    public int aiFill, aiStroke, aiText, aiLabel, aiBar;
    public int noticeFill, noticeStroke, noticeText;
    public int codeBg, codeText, codeHeader, inlineCodeBg, inlineCodeText, link;
    public int thinkFill, thinkStroke, thinkText;
    // Effects
    public int gridColor, bloomColor, bracketColor, scanColor, glow;
    /** Chart/meter ink (Dark's accent is monochrome, so data gets its own blue). */
    public int data;
    /** Top-edge highlight on cards (the web's inset 0 1px 0 rgba(255,255,255,.09)). */
    public int panelHi;
    /** The chat composer card. */
    public int composerFill, composerEdge;
    /** "Engaged" (switched on) state color — the web app's amber. For fills and edges. */
    public int engaged;
    /** Text and icons in the engaged state (Light's amber is too pale to read as ink). */
    public int engagedInk;
    /** The arc-reactor logo's core (never red: red means danger). */
    public int logoCore;
    /** Switch in the off state: track, outline, knob (the on state uses the accent). */
    public int toggleOff, toggleOffEdge, toggleKnobOff;
    // Shape (dp)
    public float radius, radiusSm, radiusBubble;
    // Type
    public Typeface display, labelFace, body, bodyMedium, bodySemi, bodyBold, mono;
    public float bodySp, labelSp, labelTracking;
    public boolean labelsUpper;
    /** Elevation for cards in dp (Light gets a soft shadow). */
    public float cardElevation;

    private Theme(int id, Context c) {
        this.id = id;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (id == CYBER) {
            name = "Cyber";
            isDark = true;
            hud = true;
            themeRes = R.style.Theme_Omni_Cyber;
            bg = 0xFF050A12;           // home: radial #0a1622 → #050a12 → #030509
            bgTop = 0xFF0A1622;
            surface = 0xDB09101A;      // --hud-surface rgba(9,16,26,.86)
            surface2 = 0xF005090F;     // --hud-surface-deep rgba(5,9,15,.94)
            cap = 0xB8161E2A;          // --hud-cap rgba(22,30,42,.72)
            input = 0xB803070C;        // recessed slot rgba(3,7,12,.72)
            chip = 0x0F78A0C8;         // flat raised tint rgba(120,160,200,.06)
            scrim = 0xAD02050A;        // backdrop rgba(2,5,10,.68)
            nav = 0xFF05090F;          // --ws-surface
            topbar = 0xFF05090F;
            hair = 0x2E7896B2;         // --hud-hair rgba(120,150,178,.18)
            hairSoft = 0x177896B2;     // --hud-hair-soft
            edge = 0x3838BDF8;         // home-widget edge rgba(56,189,248,.22)
            edgeStrong = 0x6138BDF8;   // --hud-edge-strong
            ink = 0xFFE2EFFA;          // --hud-ink-strong
            inkStrong = 0xFFF4F9FF;
            dim = 0xFF8FA9C2;          // --hud-ink
            faint = 0xFF6C8CA6;        // --text-dim
            label = 0xFF7FA9C6;        // --hud-accent-soft (steel-cyan micro-caps)
            accent = 0xFF38BDF8;       // --hud-accent
            accentHover = 0xFF7DD3FC;
            accentSoft = 0x2438BDF8;   // rgba(56,189,248,.14)
            onAccent = 0xFF03101A;
            accent2 = 0xFF4DD8FF;      // home dashboard blue (--ws-accent)
            ok = 0xFF22E58A;
            warn = 0xFFFFB700;         // --neon-amber
            danger = 0xFFFF3B5C;       // --hud-danger
            engaged = 0xFFFFB700;
            engagedInk = 0xFFFFB700;
            logoCore = 0xFFF4F9FF;     // ink-strong core, like the launcher icon
            toggleOff = 0x4D6E6E92;    // .hub-switch off: rgba(110,110,146,.3), hairline, steel knob
            toggleOffEdge = 0x2E7896B2;
            toggleKnobOff = 0xFF6C8CA6;
            userFill = 0xFF2C465E;     // chat: user bubble #2C465E, ink #EAF2F8
            userStroke = 0;
            userText = 0xFFEAF2F8;
            userLabel = 0xB3EAF2F8;
            aiFill = 0;                // chat: AI replies sit on the surface, no bubble
            aiStroke = 0;
            aiText = 0xFFECECFE;       // --text-main
            aiLabel = 0xFF38BDF8;
            aiBar = 0x8038BDF8;
            noticeFill = 0x1FFFB700;   // system message: amber .12 + dashed .55
            noticeStroke = 0x8CFFB700;
            noticeText = 0xFFECECFE;
            codeBg = 0xFF090910;       // --bg-input
            codeText = 0xFFBFE6FF;
            codeHeader = 0xFF7FA9C6;
            inlineCodeBg = 0x2238BDF8;
            inlineCodeText = 0xFF93DDFD;
            link = 0xFF38BDF8;
            thinkFill = 0x8005090F;
            thinkStroke = 0x2E7896B2;
            thinkText = 0xFF8FA9C2;
            gridColor = 0x0738BDF8;    // rgba(56,189,248,.028), 22px
            bloomColor = 0x1A38BDF8;   // rgba(56,189,248,.10)
            bracketColor = 0x8C38BDF8; // rgba(56,189,248,.55)
            scanColor = 0x8038BDF8;    // scan line peak rgba(56,189,248,.5)
            glow = 0xE638BDF8;         // status-dot glow .9
            data = 0xFF38BDF8;
            panelHi = 0x17FFFFFF;
            composerFill = 0xEB020206; // rgba(2,2,6,.92)
            composerEdge = 0x736E6E92; // rgba(110,110,146,.45)
            radius = 12;               // --hud-radius
            radiusSm = 7;
            radiusBubble = 16;
            display = Fonts.title(app);
            labelFace = Fonts.titleMedium(app);
            body = Fonts.inter(app, 400);
            bodyMedium = Fonts.inter(app, 500);
            bodySemi = Fonts.inter(app, 600);
            bodyBold = Fonts.inter(app, 700);
            mono = Fonts.mono(app);
            bodySp = 15f;
            labelSp = 9.5f;
            labelTracking = 0.16f;
            labelsUpper = true;
            cardElevation = 0;
        } else if (id == LIGHT) {
            name = "Light";
            isDark = false;
            hud = false;
            themeRes = R.style.Theme_Omni_Light;
            bg = 0xFFF5F5F5;           // home: radial #FFFFFF → #F5F5F5 → #EDEDED
            bgTop = 0xFFFFFFFF;
            surface = 0xFFFFFFFF;      // --bg-panel
            surface2 = 0xFFF6F8FA;     // home widget
            cap = 0xFFEBEDF0;          // widget title cap
            input = 0xFFF5F5F5;        // --bg-input
            chip = 0xFFF0F0F0;         // --modern-accent-soft
            scrim = 0x66000000;
            nav = 0xFFFFFFFF;
            topbar = 0xFFFFFFFF;       // top bar #FFFFFF + 1.5px border + shadow
            hair = 0x1A171717;         // --modern-border-soft
            hairSoft = 0x0F171717;
            edge = 0xFFD0D7DE;         // widget / --ws-edge
            edgeStrong = 0x38171717;   // --modern-border rgba(23,23,23,.22)
            ink = 0xFF171717;          // --text-main
            inkStrong = 0xFF0A0A0A;
            dim = 0xFF666666;          // --text-dim
            faint = 0xFF6A737D;        // 4.8:1 on cards: small data text stays readable
            label = 0xFF57606A;        // widget title / --ws-ink
            accent = 0xFF4A6D8C;       // --modern-accent
            accentHover = 0xFF3D5B76;  // --modern-accent-hover
            accentSoft = 0x1A4A6D8C;
            onAccent = 0xFFFFFFFF;
            accent2 = 0xFFDC5B52;      // --neon-red
            ok = 0xFF1E7A43;           // --ok-ink
            warn = 0xFF8A5E0B;         // --warn-ink
            danger = 0xFFB8362E;       // --danger-ink
            engaged = 0xFFC08A2E;
            engagedInk = 0xFF8A5E0B;   // --warn-ink, 5.7:1 on white
            logoCore = 0xFF4A6D8C;     // monochrome slate, like the boot emblem
            toggleOff = 0xFFF6F8FA;    // outlined off state: 3:1 outline, slate knob
            toggleOffEdge = 0xFF8C959F;
            toggleKnobOff = 0xFF6A737D;
            userFill = 0xFF4A6D8C;     // user bubble = accent, white ink
            userStroke = 0;
            userText = 0xFFFFFFFF;
            userLabel = 0xCCFFFFFF;
            aiFill = 0;                // AI replies: no bubble
            aiStroke = 0;
            aiText = 0xFF171717;
            aiLabel = 0xFF57606A;
            aiBar = 0;
            noticeFill = 0x1FC08A2E;
            noticeStroke = 0x8CC08A2E;
            noticeText = 0xFF171717;
            codeBg = 0xFFF6F8FA;
            codeText = 0xFF171717;
            codeHeader = 0xFF57606A;
            inlineCodeBg = 0x14171717;
            inlineCodeText = 0xFF3D5B76;
            link = 0xFF3D5B76;
            thinkFill = 0xFFF6F8FA;
            thinkStroke = 0xFFD0D7DE;
            thinkText = 0xFF57606A;
            gridColor = 0;
            bloomColor = 0;
            bracketColor = 0;
            scanColor = 0;
            glow = 0;
            data = 0xFF4A6D8C;
            panelHi = 0;
            composerFill = 0xD9FFFFFF; // rgba(255,255,255,.85)
            composerEdge = 0x38171717; // 1.5px --modern-border
            radius = 12;
            radiusSm = 8;
            radiusBubble = 16;
            display = Fonts.inter(app, 700);
            labelFace = Fonts.inter(app, 600);
            body = Fonts.inter(app, 400);
            bodyMedium = Fonts.inter(app, 500);
            bodySemi = Fonts.inter(app, 600);
            bodyBold = Fonts.inter(app, 700);
            mono = Fonts.mono(app);
            bodySp = 15f;
            labelSp = 11f;
            labelTracking = 0.04f;
            labelsUpper = true;
            cardElevation = 1.5f;
        } else {
            name = "Dark";
            isDark = true;
            hud = false;
            themeRes = R.style.Theme_Omni_Dark;
            bg = 0xFF0F1216;           // --bg-base
            bgTop = 0xFF161A1F;        // home: radial #161A1F → #1C2128 → #20262E
            surface = 0xFF1C2128;      // home widget
            surface2 = 0xFF161A1F;     // --bg-panel
            cap = 0xFF20262E;          // widget title cap
            input = 0xFF11151A;        // --bg-input
            chip = 0xFF232A33;         // --modern-accent-soft
            scrim = 0x99000000;
            nav = 0xFF161A1F;
            topbar = 0xFF161A1F;
            hair = 0x21FFFFFF;         // --modern-border rgba(255,255,255,.13)
            hairSoft = 0x12FFFFFF;     // --modern-border-soft
            edge = 0xFF3C4450;         // widget border
            edgeStrong = 0xFF4A5462;
            ink = 0xFFE6EAF0;          // --text-main
            inkStrong = 0xFFFFFFFF;
            dim = 0xFF8F99A8;          // --text-dim
            faint = 0xFF848E9D;        // 4.9:1 on cards, 4.6:1 on caps
            label = 0xFFB7C0CC;        // widget title
            accent = 0xFFE6EAF0;       // monochrome accent (--modern-accent)
            accentHover = 0xFFFFFFFF;
            accentSoft = 0xFF232A33;
            onAccent = 0xFF0F1216;
            accent2 = 0xFF7EA6CC;      // --neon-cyan
            ok = 0xFF5CCB8C;           // --ok-ink
            warn = 0xFFE3B253;         // --warn-ink
            danger = 0xFFFF8A80;       // --danger-ink
            engaged = 0xFFD9A441;
            engagedInk = 0xFFD9A441;
            logoCore = 0xFF7EA6CC;     // --neon-cyan
            toggleOff = 0xFF313944;    // monochrome: slate track, steel knob, light track when on
            toggleOffEdge = 0xFF4A5462;
            toggleKnobOff = 0xFF8F99A8;
            userFill = 0xFF2B323D;     // user bubble #2B323D, ink #E6EAF0
            userStroke = 0;
            userText = 0xFFE6EAF0;
            userLabel = 0xB3E6EAF0;
            aiFill = 0;                // AI replies: no bubble
            aiStroke = 0;
            aiText = 0xFFE6EAF0;
            aiLabel = 0xFF9AA4B2;
            aiBar = 0;
            noticeFill = 0x1FD9A441;
            noticeStroke = 0x80D9A441;
            noticeText = 0xFFE6EAF0;
            codeBg = 0xFF11151A;
            codeText = 0xFFE6EAF0;
            codeHeader = 0xFF9AA4B2;
            inlineCodeBg = 0x1FFFFFFF;
            inlineCodeText = 0xFFB9CFE4;
            link = 0xFF7EA6CC;
            thinkFill = 0xFF161A1F;
            thinkStroke = 0x21FFFFFF;
            thinkText = 0xFF9AA4B2;
            gridColor = 0;
            bloomColor = 0;
            bracketColor = 0;
            scanColor = 0;
            glow = 0;
            data = 0xFF7EA6CC;
            panelHi = 0;
            composerFill = 0xD9161A1F; // rgba(22,26,31,.85)
            composerEdge = 0x21FFFFFF;
            radius = 12;
            radiusSm = 8;
            radiusBubble = 16;
            display = Fonts.inter(app, 700);
            labelFace = Fonts.inter(app, 600);
            body = Fonts.inter(app, 400);
            bodyMedium = Fonts.inter(app, 500);
            bodySemi = Fonts.inter(app, 600);
            bodyBold = Fonts.inter(app, 700);
            mono = Fonts.mono(app);
            bodySp = 15f;
            labelSp = 11f;
            labelTracking = 0.04f;
            labelsUpper = true;
            cardElevation = 0;
        }
    }

    /** True when the phone is in dark mode (Android 10+; older phones count as dark). */
    public static boolean phoneIsDark(Context c) {
        if (Build.VERSION.SDK_INT < 29) return true;
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_NO;
    }

    /** Resolves a theme preference ("system", "cyber", "light", "dark") to a theme id. */
    public static int resolve(Context c, String pref) {
        if (Settings.THEME_CYBER.equals(pref)) return CYBER;
        if (Settings.THEME_LIGHT.equals(pref)) return LIGHT;
        if (Settings.THEME_DARK.equals(pref)) return DARK;
        return phoneIsDark(c) ? DARK : LIGHT;
    }

    public static Theme forPref(Context c, String pref) {
        return new Theme(resolve(c, pref), c);
    }

    public static Theme of(Context c, int id) {
        return new Theme(id, c);
    }

    /** A color with a new alpha (0..255). */
    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    /** A translucent color blended over {@code base}: the solid color it shows as (system bars, dialogs). */
    public static int flatten(int color, int base) {
        int a = (color >>> 24) & 0xFF;
        if (a == 0xFF) return color;
        int r = (((color >> 16) & 0xFF) * a + ((base >> 16) & 0xFF) * (255 - a)) / 255;
        int g = (((color >> 8) & 0xFF) * a + ((base >> 8) & 0xFF) * (255 - a)) / 255;
        int b = ((color & 0xFF) * a + (base & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public String label(String s) {
        return labelsUpper ? s.toUpperCase(java.util.Locale.US) : s;
    }

    /** A number with a unit symbol: "2.7s", "44 ms", "40.0 tok/s", "10 tok". */
    private static final Pattern UNIT = Pattern.compile(
            "\\d+(?:[.,]\\d+)?\\s?(?:tok/s|t/s|tok|ms|min|s|m|h)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Like {@link #label} for readouts that mix words and measurements: the
     * words go upper-case in Cyber, unit symbols stay lower-case ("FIRST TOKEN
     * 0.4s", "ONLINE · 44 ms"), since "MS" or "S" would be the wrong symbol.
     */
    public String labelUnits(String s) {
        if (!labelsUpper || s == null) return s;
        StringBuilder out = new StringBuilder(s.length());
        Matcher m = UNIT.matcher(s);
        int at = 0;
        while (m.find()) {
            out.append(s.substring(at, m.start()).toUpperCase(Locale.US));
            out.append(m.group().toLowerCase(Locale.US));
            at = m.end();
        }
        out.append(s.substring(at).toUpperCase(Locale.US));
        return out.toString();
    }

    /** Something that knows the active theme (the activity); lets drawn widgets pick fonts and inks. */
    public interface Host {
        Theme theme();
    }

    /** The active theme of the activity behind {@code c}, or null (unwraps dialog/theme wrappers). */
    public static Theme from(Context c) {
        for (int i = 0; c != null && i < 8; i++) {
            if (c instanceof Host) return ((Host) c).theme();
            if (!(c instanceof ContextWrapper)) break;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
