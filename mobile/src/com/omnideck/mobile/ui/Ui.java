package com.omnideck.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.MetricAffectingSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * View-building kit shared by every screen, so they all speak the same
 * design language: text styles, HUD micro-caps labels, themed cards,
 * buttons, chips, toggles, inputs and dialogs (see {@link Sheet}).
 */
public final class Ui {
    public static final int PRIMARY = 0;
    public static final int SECONDARY = 1;
    public static final int GHOST = 2;
    public static final int DANGER = 3;

    public final Context c;
    public final Theme t;
    public final float density;
    /** Haptic ticks on buttons (Settings › Haptics). */
    public boolean haptics = true;
    /** Settings › Reduce motion: dialogs appear without animation, status dots hold still. */
    public boolean reduceMotion;

    public Ui(Context c, Theme t) {
        this.c = c;
        this.t = t;
        this.density = c.getResources().getDisplayMetrics().density;
    }

    public int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    public LinearLayout vbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout hbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams fillW() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, float l, float t2, float r, float b) {
        lp.setMargins(dp(l), dp(t2), dp(r), dp(b));
        return lp;
    }

    public View space(float wDp, float hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return v;
    }

    public View flexSpace() {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    public View divider() {
        View v = new View(c);
        v.setBackgroundColor(t.hair);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));
        return v;
    }

    /** A vertical scroller with a padded column inside; returns the column. */
    public LinearLayout scrollColumn(ScrollView holder, float padH, float padV) {
        LinearLayout col = vbox();
        col.setPadding(dp(padH), dp(padV), dp(padH), dp(padV + 8));
        holder.setFillViewport(true);
        holder.setClipToPadding(false);
        holder.setVerticalScrollBarEnabled(false);
        holder.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        holder.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    public TextView text(CharSequence s, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    /** Screen / panel title in the display face. */
    public TextView title(String s, float sp) {
        TextView v = text(t.hud ? s.toUpperCase(Locale.US) : s, sp, t.inkStrong, t.display);
        v.setLetterSpacing(t.hud ? 0.08f : -0.01f);
        return v;
    }

    /** HUD micro-caps label (Orbitron steel-cyan in Cyber; small caps Inter in Light/Dark). */
    public TextView label(String s) {
        TextView v = text(t.label(s), t.labelSp, t.label, t.labelFace);
        v.setLetterSpacing(t.labelTracking);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    public TextView body(CharSequence s) {
        TextView v = text(s, t.bodySp - 1, t.ink, t.body);
        v.setLineSpacing(0, 1.25f);
        return v;
    }

    public TextView dim(CharSequence s, float sp) {
        TextView v = text(s, sp, t.dim, t.body);
        v.setLineSpacing(0, 1.2f);
        return v;
    }

    /** Big telemetry numerals in the mono face. */
    public TextView readout(String s, float sp, int color) {
        TextView v = text(s, sp, color, t.mono);
        v.setSingleLine(true);
        return v;
    }

    /**
     * An identifier (model tag, address, tool id, /command) to embed in other
     * text: Share Tech Mono, original case, no tracking. Case-sensitive names
     * are never upper-cased or set in the display face.
     */
    public CharSequence mono(String s) {
        SpannableString sp = new SpannableString(s == null ? "" : s);
        sp.setSpan(new IdentSpan(t.mono, t.hud ? 1.14f : 1.04f), 0, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /**
     * A lone identifier — no spaces, and a ':' '/' '_' or '.' in it ("llava:7b",
     * "list_processes", "/help", "192.168.1.20") — as a {@link #mono} span;
     * anything else (or already styled text) is returned as is. Dialog titles
     * and pick rows go through this, so model tags look the same everywhere.
     */
    public CharSequence identOrText(CharSequence s) {
        if (s == null || s instanceof Spanned || s.length() == 0 || s.length() > 80) return s;
        String str = s.toString();
        for (int i = 0; i < str.length(); i++) {
            if (Character.isWhitespace(str.charAt(i))) return s;
        }
        boolean marked = str.indexOf(':') >= 0 || str.indexOf('/') >= 0 || str.indexOf('_') >= 0
                || (str.indexOf('.') > 0 && str.indexOf('.') < str.length() - 1);
        return marked ? mono(str) : s;
    }

    /** Micro-caps words followed by an identifier in mono: "OMNI // llama3.2:3b". */
    public CharSequence labelIdent(String words, String ident) {
        SpannableStringBuilder sb = new SpannableStringBuilder(t.label(words));
        sb.append(mono(ident));
        return sb;
    }

    /** A typeface span for identifiers inside tracked micro-caps text (drops the tracking). */
    public static final class IdentSpan extends MetricAffectingSpan {
        private final Typeface face;
        private final float scale;

        public IdentSpan(Typeface face, float scale) {
            this.face = face;
            this.scale = scale;
        }

        private void apply(TextPaint tp) {
            tp.setTypeface(face);
            tp.setLetterSpacing(0f);
            tp.setTextSize(tp.getTextSize() * scale);
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            apply(tp);
        }

        @Override
        public void updateMeasureState(TextPaint tp) {
            apply(tp);
        }
    }

    private SimpleDateFormat clockFormat;
    private String clockPattern = "";

    /**
     * A time of day in the phone's own 12/24-hour setting — the one clock
     * format for every timestamp in the app ("19:36", "7:36 PM", "19:36:04").
     */
    public String clock(long ms, boolean withSeconds) {
        boolean h24 = android.text.format.DateFormat.is24HourFormat(c);
        String p = h24 ? (withSeconds ? "HH:mm:ss" : "HH:mm") : (withSeconds ? "h:mm:ss a" : "h:mm a");
        if (clockFormat == null || !p.equals(clockPattern)) {
            clockFormat = new SimpleDateFormat(p, Locale.getDefault());
            clockPattern = p;
        }
        return clockFormat.format(new Date(ms));
    }

    // ------------------------------------------------------------------
    // Surfaces
    // ------------------------------------------------------------------

    /** Card background: HUD glass (Cyber) or a flat rounded card (Light/Dark). */
    public Drawable panel(boolean brackets, float capHeightDp) {
        Panel.Builder b = Panel.builder().fill(t.surface).edge(t.edge, Math.max(1, dp(1)))
                .radius(dp(t.radius)).highlight(t.panelHi);
        if (t.hud) {
            b.grid(dp(22), t.gridColor).bloom(t.bloomColor);
            if (brackets) b.brackets(dp(10), dp(1.2f), t.bracketColor).bracketInset(dp(5));
        }
        if (capHeightDp > 0) b.cap(dp(capHeightDp), t.cap, t.hud ? Theme.alpha(t.accent, 0x29) : t.edge);
        return b.build();
    }

    /** A padded card column. */
    public LinearLayout card() {
        LinearLayout l = vbox();
        l.setBackground(panel(true, 0));
        l.setPadding(dp(14), dp(12), dp(14), dp(14));
        if (t.cardElevation > 0) l.setElevation(dp(t.cardElevation));
        return l;
    }

    /**
     * A card with a header cap: micro-caps title on the left, optional view
     * on the right. Returns the card; add content after the header. In Cyber
     * a small status dot leads the title ({@link #setCapLive} makes it pulse).
     */
    public LinearLayout capCard(String title, View right) {
        LinearLayout l = vbox();
        l.setBackground(panel(true, 34));
        if (t.cardElevation > 0) l.setElevation(dp(t.cardElevation));
        LinearLayout head = hbox();
        head.setPadding(dp(14), 0, dp(14), 0);
        head.setMinimumHeight(dp(34));
        if (t.hud) {
            Widgets.StatusDot dot = new Widgets.StatusDot(c);
            dot.setColor(t.accent);
            dot.setFade(true);
            dot.setCoreFraction(1f);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(5), dp(5));
            dl.rightMargin = dp(8);
            head.addView(dot, dl);
        }
        TextView tv = label(title);
        head.addView(tv, weight(1));
        if (right != null) head.addView(right);
        l.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        return l;
    }

    /** The cap's status dot of a {@link #capCard} (Cyber only; null otherwise). */
    public static Widgets.StatusDot capDot(LinearLayout card) {
        if (card == null || card.getChildCount() == 0 || !(card.getChildAt(0) instanceof LinearLayout)) return null;
        LinearLayout head = (LinearLayout) card.getChildAt(0);
        return head.getChildCount() > 0 && head.getChildAt(0) instanceof Widgets.StatusDot
                ? (Widgets.StatusDot) head.getChildAt(0) : null;
    }

    /** Marks a capCard as a live feed: its cap dot breathes (unless motion is reduced). */
    public void setCapLive(LinearLayout card, boolean live) {
        Widgets.StatusDot d = capDot(card);
        if (d != null) d.setPulsing(live && !reduceMotion);
    }

    /** Content padding inside a capCard. */
    public LinearLayout cardBody() {
        LinearLayout b = vbox();
        b.setPadding(dp(14), dp(10), dp(14), dp(14));
        return b;
    }

    public GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private Drawable pressable(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    /** A ripple-on-press background for a tappable row or card (mask = the row's bounds). */
    public Drawable pressableRow(int fill) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, t.isDark ? 0x2E : 0x24)),
                fill == 0 ? null : rounded(fill, 0, 0), mask);
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    public void tick(View v) {
        if (haptics && v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /**
     * A leading button icon that slides right by {@link #dx} so it stays next
     * to the label when the button is stretched (see {@link UiButton}).
     */
    static final class ShiftedIcon extends Drawable {
        final Drawable inner;
        float dx;

        ShiftedIcon(Drawable inner) {
            this.inner = inner;
        }

        @Override
        public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(dx, 0);
            inner.setBounds(getBounds());
            inner.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override
        public int getIntrinsicWidth() {
            return inner.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return inner.getIntrinsicHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            inner.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The themed push button (never the platform's ALL-CAPS Material one).
     * Its padding is symmetric and the leading icon rides next to the label,
     * so icon and label stay centred as one group however wide the button is
     * stretched. The icon follows the text color.
     */
    static final class UiButton extends Button {
        private int iconColor;

        UiButton(Context c) {
            super(c, null, 0, 0);
            setAllCaps(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Drawable d = getCompoundDrawables()[0];
            if (d instanceof ShiftedIcon) {
                ShiftedIcon icon = (ShiftedIcon) d;
                Layout l = getLayout();
                float text = l != null && l.getLineCount() > 0 ? l.getLineWidth(0) : 0;
                float space = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
                icon.dx = Math.max(0, (space - text) / 2f);
                int col = getCurrentTextColor();
                if (col != iconColor && icon.inner instanceof IconDrawable) {
                    iconColor = col;
                    ((IconDrawable) icon.inner).setColors(col, col);
                }
            }
            super.onDraw(canvas);
        }
    }

    /** Button with optional leading icon (0 = none). */
    public TextView button(String label, int icon, int style, final View.OnClickListener l) {
        return button(label, icon, style, false, l);
    }

    /**
     * Button with optional leading icon (0 = none). {@code mono} sets the
     * label in Share Tech Mono in its original case (labels that are, or end
     * in, an identifier: "Pull llama3.2", "/pair"); otherwise Cyber shows
     * Orbitron caps and Light/Dark Inter semibold.
     */
    public TextView button(CharSequence label, int icon, int style, boolean mono, final View.OnClickListener l) {
        UiButton b = new UiButton(c);
        styleButton(b, label, icon, style, mono);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tick(v);
                if (l != null) l.onClick(v);
            }
        });
        return b;
    }

    /** Applies a button style to a {@link UiButton} (also used by {@link Sheet}). */
    void styleButton(UiButton b, CharSequence label, int icon, int style, boolean mono) {
        int fg, fill, stroke;
        switch (style) {
            case PRIMARY:
                fg = t.onAccent;
                fill = t.accent;
                stroke = 0;
                break;
            case DANGER:
                fg = t.danger;
                fill = Theme.alpha(t.danger, t.hud ? 0x1F : 0x14);
                stroke = Theme.alpha(t.danger, 0x80);
                break;
            case GHOST:
                fg = t.accent;
                fill = 0;
                stroke = 0;
                break;
            default:
                fg = t.ink;
                fill = t.chip;
                stroke = t.hud ? t.hair : t.edge;
                break;
        }
        boolean caps = t.hud && !mono && !(label instanceof Spanned);
        b.setText(caps ? label.toString().toUpperCase(Locale.US) : label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, mono ? 13 : t.hud ? 11 : 13.5f);
        b.setTextColor(fg);
        b.setTypeface(mono ? t.mono : t.hud ? t.labelFace : t.bodySemi);
        b.setLetterSpacing(caps ? 0.1f : 0f);
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        int pad = style == GHOST ? 4 : icon != 0 ? 14 : 16;
        b.setPadding(dp(pad), dp(10), dp(pad), dp(10));
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        if (icon != 0) {
            // Light and Dark pair line icons with a stroked dart / triangle; the filled ones stay for Cyber.
            int kind = !t.hud && icon == IconDrawable.SEND ? IconDrawable.SEND_LINE
                    : !t.hud && icon == IconDrawable.PLAY ? IconDrawable.PLAY_LINE : icon;
            ShiftedIcon d = new ShiftedIcon(new IconDrawable(kind, fg, fg, dp(16)));
            d.setBounds(0, 0, dp(16), dp(16));
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(7));
        } else {
            b.setCompoundDrawables(null, null, null, null);
        }
        Drawable bg = rounded(fill, stroke, 8);
        b.setBackground(pressable(bg, Theme.alpha(style == PRIMARY ? t.onAccent : t.accent, 0x33)));
    }

    /** Square icon-only button with a description for accessibility. */
    public ImageView iconButton(int kind, String description, int color, final View.OnClickListener l) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new IconDrawable(kind, color, color, dp(20)));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(description);
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) v.setBackground(c.getDrawable(tv.resourceId));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tick(view);
                if (l != null) l.onClick(view);
            }
        });
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return v;
    }

    /** A small status chip ("LOADED", "VISION", "8.2B"). */
    public TextView chip(String s, int color) {
        return chip(s, color, false);
    }

    /**
     * A small status chip. {@code mono} keeps the text's case and sets it in
     * Share Tech Mono — for identifiers ("llama3.2:3b", "8.2B", "Q4_K_M").
     * Status only: tappable suggestions use {@link #actionChip}.
     */
    public TextView chip(String s, int color, boolean mono) {
        boolean caps = t.hud && !mono;
        TextView v = text(caps ? s.toUpperCase(Locale.US) : s, mono ? 11.5f : t.hud ? 9 : 11, color,
                mono ? t.mono : t.hud ? t.labelFace : t.bodySemi);
        if (caps) v.setLetterSpacing(0.1f);
        v.setPadding(dp(7), dp(3), dp(7), dp(3));
        v.setBackground(rounded(Theme.alpha(color, t.isDark ? 0x1F : 0x17), Theme.alpha(color, 0x59), t.hud ? 4 : 6));
        return v;
    }

    /**
     * The one chip style for "tap to insert or run" suggestions: quiet
     * fill, hairline edge, ink text. {@code mono} for identifiers
     * (model tags, tool ids, /commands, in their own case), Inter for words.
     */
    public TextView actionChip(String s, boolean mono, final View.OnClickListener l) {
        TextView v = text(s, mono ? 13 : 12.5f, t.ink, mono ? t.mono : t.bodyMedium);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(11), dp(7), dp(11), dp(7));
        v.setMinHeight(dp(34));
        v.setBackground(pressable(rounded(t.chip, t.edge, 8), Theme.alpha(t.accent, 0x33)));
        v.setContentDescription(s);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tick(view);
                if (l != null) l.onClick(view);
            }
        });
        return v;
    }

    /** The theme's switch: Cyber's glowing hub switch, Light's outlined one, Dark's monochrome one. */
    public Widgets.Toggle toggle(boolean on, Widgets.Toggle.OnChange l) {
        Widgets.Toggle tg;
        if (t.hud) {
            tg = new Widgets.Toggle(c, Theme.alpha(t.accent, 0x6B), t.toggleOff, t.accent, 0);
            tg.setKnobColors(t.toggleKnobOff, t.accent);
            tg.setEdgeColors(t.toggleOffEdge, t.accent);
            tg.setKnobSizes(15f / 21f, 15f / 21f);
            tg.setGlow(Theme.alpha(t.accent, 0x47));
        } else {
            tg = new Widgets.Toggle(c, t.accent, t.toggleOff, t.onAccent, 0);
            tg.setKnobColors(t.toggleKnobOff, t.onAccent);
            tg.setEdgeColors(t.toggleOffEdge, Theme.alpha(t.accent, 0));
            tg.setEdgeWidth(t.isDark ? Math.max(1, dp(1)) : dp(1.5f));
            tg.setKnobSizes(0.5f, 0.74f);
        }
        tg.setChecked(on, false);
        tg.setOnChange(l);
        return tg;
    }

    /** Settings-style row: title + optional subtitle on the left, a control on the right. */
    public LinearLayout settingRow(String title, String subtitle, View control) {
        LinearLayout row = hbox();
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout text = vbox();
        TextView tt = text(title, 14.5f, t.ink, t.bodyMedium);
        text.addView(tt);
        if (subtitle != null && subtitle.length() > 0) {
            TextView st = dim(subtitle, 12);
            st.setPadding(0, dp(3), dp(8), 0);
            text.addView(st);
        }
        row.addView(text, weight(1));
        if (control != null) row.addView(control);
        return row;
    }

    public EditText field(String value, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(t.ink);
        e.setHintTextColor(t.faint);
        e.setTypeface(t.body);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        e.setInputType(inputType);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        // The edge lights up in the accent while the field has focus.
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(t.input, t.hud ? t.edgeStrong
                : t.id == Theme.DARK ? t.data : t.accent, 8));
        bg.addState(new int[0], rounded(t.input, t.edge, 8));
        e.setBackground(bg);
        return e;
    }

    public EditText numberField(String value, String hint) {
        EditText e = field(value, hint, InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setMinWidth(dp(96));
        return e;
    }

    /**
     * The "live feed" tag for cards whose numbers update on their own: a
     * breathing dot and a mono age readout ("Live · 2s"). Call
     * {@link LiveTag#update} whenever a sample arrives (and on a slow tick).
     */
    public LiveTag liveTag() {
        return new LiveTag(this);
    }

    /** See {@link #liveTag()}. */
    public static final class LiveTag extends LinearLayout {
        private final Ui ui;
        private final Widgets.StatusDot dot;
        private final TextView text;

        LiveTag(Ui ui) {
            super(ui.c);
            this.ui = ui;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            dot = new Widgets.StatusDot(ui.c);
            dot.setFade(true);
            dot.setCoreFraction(1f);
            dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams dl = new LayoutParams(ui.dp(6), ui.dp(6));
            dl.rightMargin = ui.dp(6);
            addView(dot, dl);
            text = ui.readout("", 10.5f, ui.t.dim);
            addView(text, wrap());
            update(false, -1);
        }

        /**
         * {@code live}: samples are arriving (green, breathing dot); otherwise
         * the feed is paused or stale (static dot). {@code ageMs} &lt; 0 hides the age.
         */
        public void update(boolean live, long ageMs) {
            Theme t = ui.t;
            dot.setColor(live ? t.ok : ageMs >= 0 ? t.warn : t.faint);
            dot.setPulsing(live && !ui.reduceMotion);
            String word = live ? "Live" : ageMs >= 0 ? "Paused" : "Offline";
            String s = ageMs < 0 ? word : word + " · " + age(ageMs);
            text.setText(t.hud ? t.labelUnits(s) : s);
            text.setTextColor(live ? t.dim : t.faint);
            setContentDescription(s);
        }

        /** "now", "8s", "3m", "2h". */
        public static String age(long ms) {
            long s = Math.max(0, ms) / 1000;
            if (s < 1) return "now";
            if (s < 60) return s + "s";
            if (s < 3600) return (s / 60) + "m";
            return (s / 3600) + "h";
        }

        public boolean isPulsing() {
            return dot.isPulsing();
        }
    }

    // ------------------------------------------------------------------
    // Dialogs (all built on Sheet)
    // ------------------------------------------------------------------

    /** A row in a pick dialog. */
    public static final class Row {
        public final CharSequence title;
        public final String detail;
        public final boolean highlight;
        public final Runnable onClick;
        public final Runnable onLongClick;
        /** Optional leading icon (IconDrawable kind, 0 = none). */
        public int icon;
        /** A destructive action: shown in the danger ink. */
        public boolean danger;

        public Row(CharSequence title, String detail, boolean highlight, Runnable onClick, Runnable onLongClick) {
            this.title = title;
            this.detail = detail;
            this.highlight = highlight;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }

        public Row icon(int kind) {
            icon = kind;
            return this;
        }

        public Row danger() {
            danger = true;
            return this;
        }
    }

    public interface TextResult {
        void onText(String text);
    }

    /**
     * A themed dialog: {@code eyebrow} is the micro-caps kicker, {@code title}
     * the headline (pass {@link #mono} spans for identifiers). Add content to
     * {@link Sheet#body}, actions with positive/negative/neutral, then show().
     */
    public Sheet sheet(String eyebrow, CharSequence title) {
        return new Sheet(this, eyebrow, title);
    }

    /** False when the dialog's activity is finishing or gone (async callbacks after a recreate). */
    public boolean canShowDialogs() {
        Context x = c;
        for (int i = 0; x != null && i < 8 && !(x instanceof Activity); i++) {
            if (!(x instanceof ContextWrapper)) break;
            x = ((ContextWrapper) x).getBaseContext();
        }
        if (x instanceof Activity) {
            Activity a = (Activity) x;
            return !a.isFinishing() && !a.isDestroyed();
        }
        return true;
    }

    /** A list of choices; tapping one closes the sheet and runs it. {@code neutral} adds a secondary action. */
    public AlertDialog pick(String title, List<Row> rows, String neutral, final Runnable onNeutral) {
        return pick("Select", title, rows, neutral, onNeutral);
    }

    /** {@link #pick(String, List, String, Runnable)} with its own eyebrow ("Message", "PC link"…). */
    public AlertDialog pick(String eyebrow, CharSequence title, List<Row> rows, String neutral,
                            final Runnable onNeutral) {
        final Sheet s = sheet(eyebrow, title);
        s.body.setPadding(0, dp(6), 0, dp(6));
        int hl = t.id == Theme.DARK ? t.data : t.accent;
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            if (i > 0) {
                View hair = new View(c);
                hair.setBackgroundColor(t.hairSoft);
                LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(1, dp(0.7f)));
                hp.leftMargin = dp(18);
                hp.rightMargin = dp(18);
                s.body.addView(hair, hp);
            }
            // The title stays a direct child of the tappable row (TalkBack reads them together).
            LinearLayout row = vbox();
            row.setPadding(dp(18), dp(12), dp(18), dp(12));
            row.setMinimumHeight(dp(48));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(pressableRow(r.highlight ? t.accentSoft : 0));
            int ink = r.danger ? t.danger : r.highlight ? hl : t.ink;
            TextView tt = text(identOrText(r.title), 15, ink, r.highlight ? t.bodySemi : t.bodyMedium);
            if (r.icon != 0 || r.highlight) {
                IconDrawable lead = r.icon != 0 ? new IconDrawable(r.icon, r.danger ? t.danger : t.dim,
                        r.danger ? t.danger : t.dim, dp(18)) : null;
                IconDrawable check = r.highlight ? new IconDrawable(IconDrawable.CHECK, hl, hl, dp(18)) : null;
                if (lead != null) lead.setBounds(0, 0, dp(18), dp(18));
                if (check != null) check.setBounds(0, 0, dp(18), dp(18));
                tt.setCompoundDrawables(lead, null, check, null);
                tt.setCompoundDrawablePadding(dp(12));
            }
            row.addView(tt, fillW());
            if (r.detail != null && r.detail.length() > 0) {
                TextView d = dim(r.detail, 12.5f);
                d.setPadding(r.icon != 0 ? dp(30) : 0, dp(3), 0, 0);
                row.addView(d, fillW());
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tick(v);
                    s.dismiss();
                    if (r.onClick != null) r.onClick.run();
                }
            });
            if (r.onLongClick != null) {
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        s.dismiss();
                        r.onLongClick.run();
                        return true;
                    }
                });
            }
            s.body.addView(row, fillW());
        }
        if (rows.isEmpty()) {
            TextView e = dim("Nothing here yet.", 14);
            e.setPadding(dp(18), dp(12), dp(18), dp(12));
            s.body.addView(e);
        }
        if (neutral != null) {
            s.neutral(neutral, new Runnable() {
                @Override
                public void run() {
                    if (onNeutral != null) onNeutral.run();
                }
            });
        }
        s.negative("Close", null);
        s.footerRule(true);
        return s.show().dialog;
    }

    /** Confirmation; destructive verbs (Delete, Remove, Forget, Clear…) get the danger button. */
    public AlertDialog confirm(String title, String message, String yes, final Runnable onYes) {
        return confirm(title, message, yes, isDestructive(yes), onYes);
    }

    public AlertDialog confirm(String title, String message, String yes, boolean danger, final Runnable onYes) {
        Sheet s = sheet("Confirm", title);
        if (danger) s.eyebrowColor(t.danger);
        if (message != null && message.length() > 0) s.message(message);
        s.negative("Cancel", null);
        s.positive(yes, danger ? DANGER : PRIMARY, onYes);
        return s.show().dialog;
    }

    static boolean isDestructive(String verb) {
        String v = verb == null ? "" : verb.trim().toLowerCase(Locale.US);
        return v.startsWith("delete") || v.startsWith("remove") || v.startsWith("forget") || v.startsWith("clear")
                || v.startsWith("erase") || v.startsWith("wipe") || v.startsWith("unpair") || v.startsWith("discard");
    }

    /** One-line (or multi-line, by input type) text entry; OK hands the text to {@code r}. */
    public AlertDialog prompt(String title, String hint, String value, int inputType, final TextResult r) {
        final Sheet s = sheet("Input", title);
        final EditText et = field(value, hint, inputType);
        et.setSelection(et.getText().length());
        boolean multi = (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (!multi) {
            et.setSingleLine(true);
            et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        } else {
            et.setMaxLines(6);
        }
        s.body.addView(et, fillW());
        s.negative("Cancel", null);
        final Button ok = s.positive("OK", PRIMARY, new Runnable() {
            @Override
            public void run() {
                r.onText(et.getText().toString());
            }
        });
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    ok.performClick();
                    return true;
                }
                return false;
            }
        });
        s.showKeyboard(et);
        return s.show().dialog;
    }

    public void toast(String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
