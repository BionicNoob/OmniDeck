package com.omnideck.mobile.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Row and field builders for the Settings deck, drawn from the theme's
 * tokens: title / subtitle rows with a trailing control, switch rows,
 * tappable nav rows, headings, hairline separators, compact and full
 * buttons, recessed readout tiles, select boxes (value, state chips and a
 * chevron), slider headers and scales, info notes, one-line status
 * {@link Notice}s and the {@link Check} lines of a readiness list.
 * <p>
 * Information text uses {@code t.dim} / {@code t.label}; {@code t.faint} is
 * kept for decoration and placeholders.
 */
public final class SettingsKit {
    public final Ui ui;
    public final Theme t;
    private final Context c;
    private final Runnable onSaved;

    /** {@code onSaved} runs after a switch row changes (the header's "Saved" flash). */
    public SettingsKit(Ui ui, Runnable onSaved) {
        this.ui = ui;
        this.t = ui.t;
        this.c = ui.c;
        this.onSaved = onSaved;
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /** A settings row's views (title, subtitle) so they can be updated. */
    public static final class Line {
        public final LinearLayout row;
        public final TextView title;
        public final TextView sub;

        Line(LinearLayout row, TextView title, TextView sub) {
            this.row = row;
            this.title = title;
            this.sub = sub;
        }
    }

    /** Title + subtitle on the left, an optional control on the right. */
    public Line line(String title, CharSequence subtitle, View control) {
        LinearLayout row = ui.hbox();
        row.setPadding(0, ui.dp(11), 0, ui.dp(11));
        row.setMinimumHeight(ui.dp(52));
        LinearLayout text = ui.vbox();
        TextView tt = ui.text(title, 14.5f, t.ink, t.bodyMedium);
        text.addView(tt);
        TextView st = ui.dim(subtitle == null ? "" : subtitle, 12);
        st.setPadding(0, ui.dp(3), ui.dp(10), 0);
        st.setVisibility(subtitle == null || subtitle.length() == 0 ? View.GONE : View.VISIBLE);
        text.addView(st);
        row.addView(text, Ui.weight(1));
        if (control != null) row.addView(control);
        return new Line(row, tt, st);
    }

    public void setSub(Line l, CharSequence s) {
        l.sub.setText(s);
        l.sub.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** A setting row with a switch; tapping anywhere on the row flips it. */
    public Widgets.Toggle toggleRow(LinearLayout body, String title, String sub, final Widgets.Toggle.OnChange l,
                                    Line[] out) {
        final Widgets.Toggle tg = ui.toggle(false, new Widgets.Toggle.OnChange() {
            @Override
            public void changed(boolean on) {
                l.changed(on);
                if (onSaved != null) onSaved.run();
            }
        });
        tg.setContentDescription(title);
        Line ln = line(title, sub, tg);
        ln.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                tg.performClick();
            }
        });
        body.addView(ln.row, Ui.fillW());
        if (out != null) out[0] = ln;
        return tg;
    }

    /** A tappable row that opens something (trailing icon). */
    public Line navRow(LinearLayout body, String title, String sub, int icon, final Runnable r) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new IconDrawable(icon, t.hud ? t.accent : t.dim, t.hud ? t.accent : t.dim, ui.dp(18)));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        Line ln = line(title, sub, iv);
        ln.row.setContentDescription(title);
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) ln.row.setBackground(c.getDrawable(tv.resourceId));
        ln.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                r.run();
            }
        });
        body.addView(ln.row, Ui.fillW());
        return ln;
    }

    /** Title + subtitle over a full-width control. */
    public Line heading(LinearLayout body, String title, CharSequence sub) {
        Line ln = line(title, sub, null);
        ln.row.setPadding(0, ui.dp(12), 0, ui.dp(9));
        ln.row.setMinimumHeight(0);
        body.addView(ln.row, Ui.fillW());
        return ln;
    }

    /** A hairline between rows. */
    public View sep(LinearLayout body) {
        View v = new View(c);
        v.setBackgroundColor(t.hud ? t.hairSoft : t.isDark ? t.hairSoft : t.hair);
        body.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(0.7f))));
        return v;
    }

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    /**
     * A compact button for rows. Ghost links keep the kit's 4dp inset, so a
     * right-aligned link ends on the content edge like the switches do.
     */
    public TextView smallButton(String label, int icon, int style, String desc, View.OnClickListener l) {
        TextView b = ui.button(label, icon, style, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.hud ? 10 : 13);
        boolean ghost = style == Ui.GHOST;
        b.setPadding(ui.dp(ghost ? 4 : icon != 0 ? 10 : 12), ui.dp(7), ui.dp(ghost ? 4 : 12), ui.dp(7));
        b.setMinHeight(ui.dp(34));
        b.setMinimumHeight(ui.dp(34));
        b.setContentDescription(desc);
        return b;
    }

    /** A full-size button (icon and label stay centred together when it's stretched). */
    public TextView bigButton(String label, int icon, int style, String desc, View.OnClickListener l) {
        TextView b = ui.button(label, icon, style, l);
        b.setContentDescription(desc);
        return b;
    }

    /** Relabels a {@link Ui#button} in its theme's case. */
    public void relabel(TextView b, String s) {
        b.setText(t.hud ? s.toUpperCase(Locale.US) : s);
    }

    public static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.45f);
    }

    // ------------------------------------------------------------------
    // Readouts and selects
    // ------------------------------------------------------------------

    /** Recessed tiles (the PC tab's well) of a micro-caps label over a mono value, two per row. */
    public TextView[] readoutGrid(LinearLayout body, String[] labels) {
        TextView[] out = new TextView[labels.length];
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 2 == 0) {
                row = ui.hbox();
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams lp = Ui.fillW();
                lp.topMargin = ui.dp(8);
                body.addView(row, lp);
            }
            LinearLayout cell = ui.vbox();
            cell.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(10));
            cell.setBackground(SettingsWidgets.well(ui));
            cell.addView(ui.label(labels[i]));
            TextView v = ui.readout("—", t.hud ? 13 : 13.5f, t.ink);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setPadding(0, ui.dp(6), 0, 0);
            cell.addView(v, Ui.fillW());
            LinearLayout.LayoutParams clp = Ui.weight(1);
            if (i % 2 == 0) clp.rightMargin = ui.dp(8);
            row.addView(cell, clp);
            out[i] = v;
        }
        return out;
    }

    /** A readout tile's value: live data in ink, a placeholder or missing state in dim. */
    public void setReadout(TextView v, String text, boolean live) {
        v.setText(text);
        v.setTextColor(live ? t.ink : t.dim);
    }

    /** A full-width "select" field: value on the left, state chips and a chevron on the right. */
    public static final class Select {
        public final LinearLayout box;
        public final TextView value;
        public final LinearLayout tags;

        Select(LinearLayout box, TextView value, LinearLayout tags) {
            this.box = box;
            this.value = value;
            this.tags = tags;
        }
    }

    public Select select(LinearLayout body, String desc, final Runnable onTap) {
        LinearLayout box = ui.hbox();
        box.setPadding(ui.dp(12), 0, ui.dp(10), 0);
        box.setMinimumHeight(ui.dp(44));
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 0x33)),
                ui.rounded(t.input, t.edge, 8), null));
        TextView v = ui.text("", 14, t.ink, t.mono);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(v, Ui.weight(1));
        LinearLayout tags = ui.hbox();
        tags.setPadding(ui.dp(8), 0, ui.dp(6), 0);
        box.addView(tags);
        ImageView chev = new ImageView(c);
        chev.setImageDrawable(new IconDrawable(IconDrawable.CHEVRON, t.dim, t.dim, ui.dp(14)));
        chev.setScaleType(ImageView.ScaleType.CENTER);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(chev, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        box.setContentDescription(desc);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ui.tick(view);
                onTap.run();
            }
        });
        body.addView(box, Ui.fillW());
        return new Select(box, v, tags);
    }

    /** The select's value: an identifier in mono ink, or a placeholder ("Same as default") in body dim. */
    public void setValue(Select s, String text, boolean ident) {
        s.value.setText(text);
        s.value.setTypeface(ident ? t.mono : t.body);
        s.value.setTextColor(ident ? t.ink : t.dim);
    }

    /** Replaces the select's state chips (null entries are skipped). */
    public void setTags(Select s, TextView... chips) {
        s.tags.removeAllViews();
        for (TextView chip : chips) {
            if (chip == null) continue;
            LinearLayout.LayoutParams lp = Ui.wrap();
            if (s.tags.getChildCount() > 0) lp.leftMargin = ui.dp(5);
            s.tags.addView(chip, lp);
        }
        s.tags.setVisibility(s.tags.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // Sliders
    // ------------------------------------------------------------------

    public TextView valueReadout() {
        TextView v = ui.readout("", t.hud ? 13 : 13.5f, t.id == Theme.DARK ? t.data : t.accent);
        v.setPadding(ui.dp(8), 0, 0, 0);
        return v;
    }

    /** A slider header: title + live mono value + an optional "Reset" link. Returns {value, reset}. */
    public TextView[] sliderHead(LinearLayout body, final String title, String sub, final Runnable onReset) {
        LinearLayout head = ui.hbox();
        head.setPadding(0, ui.dp(12), 0, 0);
        TextView tt = ui.text(title, 14.5f, t.ink, t.bodyMedium);
        head.addView(tt, Ui.weight(1));
        TextView reset = null;
        if (onReset != null) {
            reset = ui.text(t.hud ? "RESET" : "Reset", t.hud ? 9.5f : 12.5f, t.accent,
                    t.hud ? t.labelFace : t.bodySemi);
            if (t.hud) reset.setLetterSpacing(0.1f);
            reset.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
            reset.setContentDescription("Reset " + title.toLowerCase(Locale.US));
            reset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    onReset.run();
                    if (onSaved != null) onSaved.run();
                }
            });
            head.addView(reset);
        }
        TextView value = valueReadout();
        value.setMinWidth(ui.dp(52));
        value.setGravity(Gravity.END);
        head.addView(value);
        body.addView(head, Ui.fillW());
        if (sub != null) {
            TextView st = ui.dim(sub, 12);
            st.setPadding(0, ui.dp(3), 0, 0);
            body.addView(st, Ui.fillW());
        }
        return new TextView[]{value, reset};
    }

    /** Scale labels under a slider, aligned with the track ends. */
    public void scaleRow(LinearLayout body, String left, String mid, String right) {
        LinearLayout row = ui.hbox();
        row.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4));
        TextView l = ui.readout(left, 10, t.dim);
        row.addView(l, Ui.weight(1));
        if (mid != null) {
            TextView m = ui.readout(mid, 10, t.dim);
            m.setGravity(Gravity.CENTER);
            row.addView(m, Ui.weight(1));
        }
        TextView r = ui.readout(right, 10, t.dim);
        r.setGravity(Gravity.END);
        row.addView(r, Ui.weight(1));
        body.addView(row, Ui.fillW());
    }

    // ------------------------------------------------------------------
    // Identifiers in running text
    // ------------------------------------------------------------------

    /**
     * Addresses and MACs as they appear in sentences: MAC addresses, http(s)
     * URLs, IPv4 addresses (with a port) and host:port. MACs come first, so
     * "3C:7C:3F:12:AB:CD" isn't read as a host and a port.
     */
    private static final Pattern IDENT = Pattern.compile(
            "(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}"
                    + "|https?://[^\\s,;)]+"
                    + "|\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b"
                    + "|\\b[A-Za-z][A-Za-z0-9-]*(?:\\.[A-Za-z0-9-]+)*:\\d{2,5}\\b");

    /**
     * {@code s} with every address and MAC in it set in mono, in its own
     * case (the way the top bar and the PC tab show them), the words around
     * them unchanged.
     */
    public CharSequence idents(CharSequence s) {
        if (s == null || s.length() == 0) return s;
        Matcher m = IDENT.matcher(s);
        SpannableStringBuilder sb = null;
        int at = 0;
        while (m.find()) {
            int end = m.end();
            // A sentence's full stop isn't part of a URL.
            while (end > m.start() + 1 && ".".indexOf(s.charAt(end - 1)) >= 0) end--;
            if (sb == null) sb = new SpannableStringBuilder();
            sb.append(s.subSequence(at, m.start()));
            sb.append(ui.mono(s.subSequence(m.start(), end).toString()));
            at = end;
        }
        if (sb == null) return s;
        sb.append(s.subSequence(at, s.length()));
        return sb;
    }

    // ------------------------------------------------------------------
    // Notes, notices and checks
    // ------------------------------------------------------------------

    /** An info icon beside a sentence of dim text. */
    public void note(LinearLayout body, TextView text) {
        LinearLayout row = ui.hbox();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, ui.dp(10), 0, 0);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new IconDrawable(IconDrawable.INFO, t.faint, t.faint, ui.dp(15)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        ilp.rightMargin = ui.dp(8);
        ilp.topMargin = ui.dp(1);
        row.addView(iv, ilp);
        row.addView(text, Ui.weight(1));
        body.addView(row, Ui.fillW());
    }

    /** A one-line status under a control: an icon and a sentence in a status ink; hidden until shown. */
    public static final class Notice {
        public final LinearLayout row;
        public final ImageView icon;
        public final TextView text;
        /** Free for the owner: what the notice was about when shown (to drop it once that changes). */
        public Object key;

        Notice(LinearLayout row, ImageView icon, TextView text) {
            this.row = row;
            this.icon = icon;
            this.text = text;
        }

        public boolean isShowing() {
            return row.getVisibility() == View.VISIBLE;
        }
    }

    public Notice notice(LinearLayout body) {
        LinearLayout row = ui.hbox();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, ui.dp(10), 0, 0);
        ImageView iv = new ImageView(c);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        ilp.rightMargin = ui.dp(8);
        ilp.topMargin = ui.dp(1);
        row.addView(iv, ilp);
        TextView tv = ui.dim("", 12.5f);
        row.addView(tv, Ui.weight(1));
        row.setVisibility(View.GONE);
        body.addView(row, Ui.fillW());
        return new Notice(row, iv, tv);
    }

    /**
     * Shows a notice. {@code color} is t.ok / t.warn / t.danger / t.dim; the
     * icon follows it (check, alert triangle, info). Addresses and MACs in
     * the text are set in mono.
     */
    public void show(Notice n, CharSequence text, int color) {
        int icon = color == t.ok ? IconDrawable.CHECK : color == t.warn || color == t.danger ? IconDrawable.WARN
                : IconDrawable.INFO;
        n.icon.setImageDrawable(new IconDrawable(icon, color, color, ui.dp(15)));
        n.text.setText(idents(text));
        n.text.setTextColor(color == t.dim ? t.dim : color);
        n.row.setVisibility(View.VISIBLE);
    }

    public void hide(Notice n) {
        n.row.setVisibility(View.GONE);
        n.key = null;
    }

    /** One line of a readiness list: a status dot, a micro-caps label and a value. */
    public static final class Check {
        public final LinearLayout row;
        public final Widgets.StatusDot dot;
        public final TextView value;

        Check(LinearLayout row, Widgets.StatusDot dot, TextView value) {
            this.row = row;
            this.dot = dot;
            this.value = value;
        }
    }

    public Check check(LinearLayout body, String label) {
        LinearLayout row = ui.hbox();
        row.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9));
        Widgets.StatusDot dot = new Widgets.StatusDot(c);
        dot.setCoreFraction(1f);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ui.dp(7), ui.dp(7));
        dl.rightMargin = ui.dp(10);
        row.addView(dot, dl);
        TextView l = ui.label(label);
        row.addView(l, new LinearLayout.LayoutParams(ui.dp(t.hud ? 70 : 64), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = ui.text("", 13, t.ink, t.body);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(v, Ui.weight(1));
        body.addView(row, Ui.fillW());
        return new Check(row, dot, v);
    }

    public void setCheck(Check k, CharSequence value, int color) {
        k.dot.setColor(color);
        k.value.setText(value);
        k.row.setContentDescription(value);
    }
}
