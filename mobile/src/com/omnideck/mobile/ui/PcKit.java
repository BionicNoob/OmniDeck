package com.omnideck.mobile.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Building blocks and formatting for the PC tab: recessed instrument tiles,
 * readouts with small units, stat columns, app monograms, a segmented control,
 * quiet heading buttons and power keys, plus parsers for LaunchBridge results
 * (volume level, clipboard text, screenshot images, pretty-printed tool output).
 */
public final class PcKit {
    private final Ui ui;
    private final Theme t;

    public PcKit(Ui ui) {
        this.ui = ui;
        this.t = ui.t;
    }

    // ------------------------------------------------------------------
    // Surfaces
    // ------------------------------------------------------------------

    /** A recessed "well" inside a card (tiles, text boxes, the screen viewport). */
    public Drawable well() {
        if (t.hud) {
            return Panel.builder().fill(Theme.alpha(t.surface2, 0xB3)).edge(t.hair, Math.max(1, ui.dp(1)))
                    .radius(ui.dp(8)).build();
        }
        return ui.rounded(t.surface2, t.isDark ? t.hair : t.edge, 8);
    }

    /** The quiet control surface (flat tint, hairline edge) with a press ripple. */
    public Drawable pressable(float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 0x33)),
                ui.rounded(t.chip, t.hud ? t.hair : t.edge, radiusDp), null);
    }

    /** An instrument tile: micro-caps label, a big readout, an optional meter and a detail line. */
    public static final class Tile {
        public final LinearLayout root;
        public final TextView label;
        public final TextView value;
        public final Widgets.Meter meter;
        public final TextView detail;

        Tile(LinearLayout root, TextView label, TextView value, Widgets.Meter meter, TextView detail) {
            this.root = root;
            this.label = label;
            this.value = value;
            this.meter = meter;
            this.detail = detail;
        }
    }

    public Tile tile(String label, boolean withMeter) {
        LinearLayout box = ui.vbox();
        box.setBackground(well());
        box.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(11));
        TextView l = ui.label(label);
        box.addView(l, Ui.fillW());
        TextView v = ui.readout("—", 24, t.inkStrong);
        v.setPadding(0, ui.dp(8), 0, 0);
        box.addView(v, Ui.fillW());
        Widgets.Meter m = null;
        if (withMeter) {
            m = new Widgets.Meter(ui.c, meterTrack(), t.data);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4));
            mp.topMargin = ui.dp(9);
            box.addView(m, mp);
        }
        TextView dl = ui.readout("", 11, t.dim);
        dl.setEllipsize(TextUtils.TruncateAt.END);
        dl.setPadding(0, ui.dp(7), 0, 0);
        box.addView(dl, Ui.fillW());
        return new Tile(box, l, v, m, dl);
    }

    /** Track color for meters and gauges. */
    public int meterTrack() {
        return Theme.alpha(t.data, t.isDark && !t.hud ? 0x2E : 0x24);
    }

    /** "51" large + "%" small and dim — the telemetry readout style. */
    public CharSequence readout(String number, String unit) {
        if (unit == null || unit.length() == 0) return number;
        SpannableString s = new SpannableString(number + unit);
        int n = number.length();
        s.setSpan(new RelativeSizeSpan(0.58f), n, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(t.dim), n, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** A label-over-value stat column (header card: BRIDGE / VERSION / APPS), captions in the micro-caps ink. */
    public LinearLayout stat(String label, TextView value) {
        LinearLayout col = ui.vbox();
        TextView l = ui.label(label);
        col.addView(l, Ui.fillW());
        value.setPadding(0, ui.dp(5), 0, 0);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(value, Ui.fillW());
        return col;
    }

    /** A small section heading inside a card: icon + micro-caps + optional right view. */
    public LinearLayout section(int icon, String title, View right) {
        LinearLayout row = ui.hbox();
        row.setMinimumHeight(ui.dp(32));
        ImageView iv = icon(icon, t.hud ? t.accent : t.label, 16);
        row.addView(iv, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView l = ui.label(title);
        l.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        row.addView(l, Ui.weight(1));
        if (right != null) row.addView(right);
        return row;
    }

    public ImageView icon(int kind, int color, float sizeDp) {
        ImageView iv = new ImageView(ui.c);
        iv.setImageDrawable(new IconDrawable(kind, color, color, ui.dp(sizeDp)));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return iv;
    }

    /** A rounded square with an app's initials (launcher rows): mono in Cyber, Inter elsewhere. */
    public TextView monogram(String name) {
        String in = initials(name);
        TextView m = ui.text(in, t.hud ? 14 : 13, t.hud ? t.accent : t.label, t.hud ? t.mono : t.bodySemi);
        m.setGravity(Gravity.CENTER);
        m.setBackground(ui.rounded(Theme.alpha(t.data, t.isDark ? 0x1A : 0x14), Theme.alpha(t.data, 0x40),
                t.hud ? 6 : 8));
        return m;
    }

    /**
     * Up to two initials: "VS" for Visual Studio Code, "D" for Discord. A
     * leading acronym keeps its own letters ("OB" for OBS Studio, "VL" for VLC
     * media player) rather than mixing in the next word ("OS" reads as
     * "operating system").
     */
    static String initials(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) return "?";
        String[] w = n.split("[\\s_\\-.]+");
        String first = w.length > 0 ? w[0] : "";
        if (first.length() >= 2 && Character.isUpperCase(first.charAt(0)) && Character.isUpperCase(first.charAt(1))
                && Character.isLetter(first.charAt(1))) {
            return first.substring(0, 2);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : w) {
            if (s.length() > 0 && Character.isLetterOrDigit(s.charAt(0))) sb.append(s.charAt(0));
            if (sb.length() == 2) break;
        }
        if (sb.length() == 0) sb.append(n.charAt(0));
        return sb.toString().toUpperCase(Locale.US);
    }

    /** Receives taps on a segmented control. */
    public interface OnSegment {
        void onSegment(int index);
    }

    /** A hairline-framed row of equal segments ("MUTE · 25 · 50 · 75 · MAX"). */
    public LinearLayout segmented(String[] labels, final OnSegment l) {
        LinearLayout row = ui.hbox();
        row.setBackground(ui.rounded(t.hud ? Theme.alpha(t.surface2, 0x99) : t.chip, t.hud ? t.hair : t.edge, 8));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            if (i > 0) {
                View sep = new View(ui.c);
                sep.setBackgroundColor(t.hud ? t.hair : t.edge);
                row.addView(sep, new LinearLayout.LayoutParams(Math.max(1, ui.dp(1)), ui.dp(18)));
            }
            TextView b = ui.text(t.hud ? labels[i].toUpperCase(Locale.US) : labels[i], t.hud ? 10 : 12.5f, t.ink,
                    t.hud ? t.labelFace : t.bodySemi);
            if (t.hud) b.setLetterSpacing(0.1f);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setMinHeight(ui.dp(36));
            b.setPadding(ui.dp(4), 0, ui.dp(4), 0);
            b.setContentDescription(labels[i]);
            b.setBackground(ripple());
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    l.onSegment(idx);
                }
            });
            row.addView(b, new LinearLayout.LayoutParams(0, ui.dp(36), 1));
        }
        return row;
    }

    /** Dims a {@link #segmented} row and marks its segments disabled (callers also ignore taps). */
    public static void setSegmentsEnabled(LinearLayout row, boolean on) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v instanceof TextView) v.setEnabled(on);
        }
        row.setAlpha(on ? 1f : 0.45f);
    }

    private Drawable ripple() {
        TypedValue tv = new TypedValue();
        ui.c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId != 0 ? ui.c.getDrawable(tv.resourceId) : null;
    }

    /** Line icons next to other line icons: Light and Dark use the stroked send / play glyphs. */
    int buttonIcon(int kind) {
        if (t.hud) return kind;
        if (kind == IconDrawable.SEND) return IconDrawable.SEND_LINE;
        if (kind == IconDrawable.PLAY) return IconDrawable.PLAY_LINE;
        return kind;
    }

    /**
     * A secondary action button whose icon and label stay centered together at
     * any width (for equal-width action rows). The label is child 1.
     */
    public LinearLayout action(String label, int icon, final View.OnClickListener l) {
        LinearLayout b = ui.hbox();
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(ui.dp(40));
        b.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        b.setBackground(pressable(8));
        b.addView(icon(buttonIcon(icon), t.ink, 16), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView tv = ui.text(t.hud ? label.toUpperCase(Locale.US) : label, t.hud ? 10 : 13.5f, t.ink,
                t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) tv.setLetterSpacing(0.08f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(ui.dp(7), 0, 0, 0);
        b.addView(tv, Ui.wrap());
        b.setContentDescription(label);
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                l.onClick(v);
            }
        });
        return b;
    }

    /**
     * A compact control for the right end of a section heading ("Capture",
     * "Fetch"): the quiet control strip look (flat tint, hairline, 8dp
     * radius, 32dp tall) with a leading icon, so it reads as a button and
     * never as another heading.
     */
    public TextView quiet(String label, int icon, View.OnClickListener l) {
        TextView b = ui.button(label, buttonIcon(icon), Ui.SECONDARY, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.hud ? 10 : 12.5f);
        b.setPadding(ui.dp(10), ui.dp(6), ui.dp(12), ui.dp(6));
        b.setMinHeight(ui.dp(32));
        b.setMinimumHeight(ui.dp(32));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setCompoundDrawablePadding(ui.dp(6));
        b.setContentDescription(label);
        return b;
    }

    /** A square icon button on the quiet control surface (sits in a row of 40dp buttons). */
    public ImageView iconKey(int kind, String description, View.OnClickListener l) {
        ImageView v = ui.iconButton(kind, description, t.ink, l);
        v.setImageDrawable(new IconDrawable(kind, t.ink, t.ink, ui.dp(18)));
        v.setBackground(pressable(8));
        v.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        return v;
    }

    /**
     * A console key: an icon over a short label on the quiet control
     * surface (the PC's power strip). Equal-width keys share a row.
     */
    public LinearLayout key(String label, int icon, String description, final View.OnClickListener l) {
        LinearLayout b = ui.vbox();
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(ui.dp(60));
        b.setPadding(ui.dp(3), ui.dp(10), ui.dp(3), ui.dp(9));
        b.setBackground(pressable(8));
        b.addView(icon(icon, t.hud ? t.accent : t.ink, 20), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        TextView tv = ui.text(t.hud ? label.toUpperCase(Locale.US) : label, t.hud ? 8.5f : 12f, t.ink,
                t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) tv.setLetterSpacing(0.06f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, ui.dp(7), 0, 0);
        b.addView(tv, Ui.fillW());
        b.setContentDescription(description);
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                l.onClick(v);
            }
        });
        return b;
    }

    /** Recolors a chip made by {@link Ui#chip} (status chips that change with the link state). */
    public void chipColor(TextView chip, int color) {
        chip.setTextColor(color);
        chip.setBackground(ui.rounded(Theme.alpha(color, t.isDark ? 0x1F : 0x17), Theme.alpha(color, 0x59),
                t.hud ? 4 : 6));
    }

    /** An inline notice (error / hint) on a soft tinted panel. */
    public TextView notice(String text, int color) {
        TextView v = ui.text(text, 12.5f, t.ink, t.body);
        v.setLineSpacing(0, 1.2f);
        v.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        v.setBackground(ui.rounded(Theme.alpha(color, t.isDark ? 0x1A : 0x12), Theme.alpha(color, 0x59), 6));
        return v;
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /** 8.1 → "8.1 GB", 476 → "476 GB", 1900 → "1.9 TB". */
    public static String gb(double v) {
        if (v < 0) return "—";
        if (v >= 1000) return trim(v / 1024) + " TB";
        return (v >= 100 ? String.valueOf(Math.round(v)) : trim(v)) + " GB";
    }

    static String trim(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Whole percent, or "—" when unknown. */
    public static String pct(double v) {
        return v < 0 ? "—" : String.valueOf(Math.round(v));
    }

    /** "just now", "8s ago", "3m ago", "2h ago". */
    public static String age(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 3) return "just now";
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }

    /** A time span for a chart axis: "10 s", "45 s", "2 min". */
    public static String span(long seconds) {
        return seconds < 60 ? seconds + " s" : (seconds / 60) + " min";
    }

    /** Digits at full size, letters (units) small and dim: "3d 4h" reads as an instrument value. */
    public CharSequence units(String s) {
        SpannableString sp = new SpannableString(s);
        int i = 0;
        while (i < s.length()) {
            int j = i;
            boolean digit = Character.isDigit(s.charAt(i));
            while (j < s.length() && Character.isDigit(s.charAt(j)) == digit) j++;
            if (!digit) {
                sp.setSpan(new RelativeSizeSpan(0.58f), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new ForegroundColorSpan(t.dim), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = j;
        }
        return sp;
    }

    private static final Pattern UP_D = Pattern.compile("(\\d+)\\s*(?:d|days?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UP_H = Pattern.compile("(\\d+)\\s*(?:h|hrs?|hours?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UP_M = Pattern.compile("(\\d+)\\s*(?:m|mins?|minutes?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UP_CLOCK = Pattern.compile("(\\d+):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern UP_SECS = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(?:s|sec|secs|seconds)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Uptime in a compact form: "3 days, 4:12:05", "76h 3m" or "273600" (seconds)
     * become "3d 4h"; anything unrecognized comes back as-is.
     */
    public static String compactUptime(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) return "";
        long minutes = -1;
        Matcher secs = UP_SECS.matcher(s);
        if (secs.find()) {
            minutes = (long) (Double.parseDouble(secs.group(1)) / 60);
        } else {
            long d = 0, h = 0, m = 0;
            boolean any = false;
            Matcher md = UP_D.matcher(s);
            if (md.find()) {
                d = Long.parseLong(md.group(1));
                any = true;
            }
            Matcher mc = UP_CLOCK.matcher(s);
            if (mc.find()) {
                h = Long.parseLong(mc.group(1));
                m = Long.parseLong(mc.group(2));
                any = true;
            } else {
                Matcher mh = UP_H.matcher(s);
                if (mh.find()) {
                    h = Long.parseLong(mh.group(1));
                    any = true;
                }
                Matcher mm = UP_M.matcher(s);
                if (mm.find()) {
                    m = Long.parseLong(mm.group(1));
                    any = true;
                }
            }
            if (any) minutes = d * 1440 + h * 60 + m;
        }
        if (minutes < 0) return s;
        long d = minutes / 1440, h = (minutes % 1440) / 60, m = minutes % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    // ------------------------------------------------------------------
    // LaunchBridge result parsing
    // ------------------------------------------------------------------

    /** A whole number of up to 3 digits (not part of a longer number). */
    private static final Pattern LEVEL = Pattern.compile("(?<![\\d.])(\\d{1,3})(?!\\d)(?:\\.\\d+)?");
    private static final Pattern PERCENT = Pattern.compile("(?<![\\d.])(\\d{1,3})(?:\\.\\d+)?\\s*%");

    /** A volume level (0..100) from get_volume / set_volume results, or -1. */
    public static int parseLevel(Object r) {
        if (r instanceof Number) return clampLevel(((Number) r).doubleValue());
        if (r instanceof JSONObject) {
            JSONObject o = (JSONObject) r;
            String[] keys = {"level", "volume", "percent", "value", "master"};
            for (String k : keys) {
                if (o.has(k)) {
                    int v = parseLevel(o.opt(k));
                    if (v >= 0) return v;
                }
            }
            return -1;
        }
        if (r instanceof String) {
            String s = ((String) r).trim();
            if (s.matches("0?\\.\\d+")) return clampLevel(Double.parseDouble(s) * 100);
            // Prefer "40%" over other numbers ("Device 2: volume 40%").
            Matcher p = PERCENT.matcher(s);
            while (p.find()) {
                int v = Integer.parseInt(p.group(1));
                if (v <= 100) return v;
            }
            Matcher m = LEVEL.matcher(s);
            while (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v <= 100) return v;
            }
        }
        return -1;
    }

    private static int clampLevel(double v) {
        if (v < 0) return -1;
        if (v <= 1 && v != Math.rint(v)) v *= 100;
        return (int) Math.round(Math.min(100, v));
    }

    /** True when a get_volume result says the output is muted. */
    public static boolean parseMuted(Object r) {
        if (r instanceof JSONObject) return ((JSONObject) r).optBoolean("muted", ((JSONObject) r).optBoolean("mute"));
        return r instanceof String && ((String) r).toLowerCase(Locale.US).contains("muted")
                && !((String) r).toLowerCase(Locale.US).contains("unmuted");
    }

    /** The text of a get_clipboard result. */
    public static String clipText(Object r) {
        if (r == null) return "";
        if (r instanceof String) return (String) r;
        if (r instanceof JSONObject) {
            JSONObject o = (JSONObject) r;
            String[] keys = {"text", "clipboard", "content", "value", "data"};
            for (String k : keys) {
                Object v = o.opt(k);
                if (v instanceof String) return (String) v;
            }
        }
        return pretty(r);
    }

    /** Tool output as readable text: strings as-is, JSON indented. */
    public static String pretty(Object r) {
        if (r == null) return "";
        try {
            if (r instanceof JSONObject) return ((JSONObject) r).toString(2);
            if (r instanceof JSONArray) return ((JSONArray) r).toString(2);
        } catch (JSONException ignored) {
            // fall through to toString()
        }
        return String.valueOf(r);
    }

    /** Base64 image data inside a screenshot result (data URL, raw base64, or a known key), or null. */
    public static String extractImage(Object r) {
        if (r instanceof String) return base64Image((String) r);
        if (r instanceof JSONObject) {
            JSONObject o = (JSONObject) r;
            String[] keys = {"image", "data_url", "dataUrl", "png", "jpeg", "base64", "data", "screenshot"};
            for (String k : keys) {
                Object v = o.opt(k);
                if (v instanceof String) {
                    String b = base64Image((String) v);
                    if (b != null) return b;
                }
            }
        }
        return null;
    }

    static String base64Image(String s) {
        if (s == null) return null;
        String x = s.trim();
        int i = x.indexOf("base64,");
        if (x.startsWith("data:image") && i > 0) return x.substring(i + 7);
        if (x.length() > 64 && x.matches("[A-Za-z0-9+/=\\r\\n]+")) return x;
        return null;
    }

    /** A screenshot result's non-image fields as "key: value" lines (e.g. where the PC saved it). */
    public static String imageMeta(Object r) {
        if (!(r instanceof JSONObject)) return "";
        StringBuilder sb = new StringBuilder();
        JSONObject o = (JSONObject) r;
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.opt(k);
            if (v instanceof String && base64Image((String) v) != null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(k.replace('_', ' ')).append(": ").append(v);
        }
        return sb.toString();
    }
}
