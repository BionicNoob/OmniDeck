package com.omnideck.mobile.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * View-building kit shared by every screen, so they all speak the same
 * design language: text styles, HUD micro-caps labels, themed cards,
 * buttons, chips, toggles, inputs and dialogs.
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
        TextView v = text(t.hud ? s.toUpperCase(java.util.Locale.US) : s, sp, t.inkStrong, t.display);
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
     * on the right. Returns the card; add content after the header.
     */
    public LinearLayout capCard(String title, View right) {
        LinearLayout l = vbox();
        l.setBackground(panel(true, 34));
        if (t.cardElevation > 0) l.setElevation(dp(t.cardElevation));
        LinearLayout head = hbox();
        head.setPadding(dp(14), 0, dp(10), 0);
        head.setMinimumHeight(dp(34));
        if (t.hud) {
            View tick = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setColor(t.accent);
            g.setCornerRadius(dp(1));
            tick.setBackground(g);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(3), dp(10));
            tl.rightMargin = dp(8);
            head.addView(tick, tl);
        }
        TextView tv = label(title);
        head.addView(tv, weight(1));
        if (right != null) head.addView(right);
        l.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        return l;
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

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    public void tick(View v) {
        if (haptics && v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Button with optional leading icon (0 = none). */
    public TextView button(String label, int icon, int style, final View.OnClickListener l) {
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
                fg = t.hud ? t.accent : t.accent;
                fill = 0;
                stroke = 0;
                break;
            default:
                fg = t.ink;
                fill = t.chip;
                stroke = t.hud ? t.hair : t.edge;
                break;
        }
        TextView b = text(t.hud ? label.toUpperCase(java.util.Locale.US) : label, t.hud ? 11 : 13.5f, fg,
                t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) b.setLetterSpacing(0.1f);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(icon != 0 ? 12 : 16), dp(10), dp(16), dp(10));
        b.setMinHeight(dp(40));
        if (icon != 0) {
            IconDrawable d = new IconDrawable(icon, fg, fg, dp(16));
            d.setBounds(0, 0, dp(16), dp(16));
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(7));
        }
        Drawable bg = rounded(fill, stroke, t.hud ? 8 : 8);
        b.setBackground(pressable(bg, Theme.alpha(style == PRIMARY ? t.onAccent : t.accent, 0x33)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tick(v);
                if (l != null) l.onClick(v);
            }
        });
        return b;
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
        TextView v = text(t.hud ? s.toUpperCase(java.util.Locale.US) : s, t.hud ? 9 : 11, color,
                t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) v.setLetterSpacing(0.1f);
        v.setPadding(dp(7), dp(3), dp(7), dp(3));
        v.setBackground(rounded(Theme.alpha(color, t.isDark ? 0x1F : 0x17), Theme.alpha(color, 0x59), t.hud ? 4 : 6));
        return v;
    }

    public Widgets.Toggle toggle(boolean on, Widgets.Toggle.OnChange l) {
        Widgets.Toggle tg;
        if (t.hud) {
            tg = new Widgets.Toggle(c, t.accent, Theme.alpha(0xFF78A0C8, 0x1F), t.inkStrong, t.hair);
            tg.setKnobColors(t.dim, t.onAccent);
        } else if (t.isDark) {
            // Monochrome, like the web Dark theme: light track + dark knob when on.
            tg = new Widgets.Toggle(c, t.accent, 0xFF313944, 0xFF8F99A8, 0);
            tg.setKnobColors(0xFF8F99A8, t.onAccent);
        } else {
            tg = new Widgets.Toggle(c, t.accent, 0xFFD0D7DE, 0xFFFFFFFF, 0);
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
        e.setBackground(rounded(t.input, t.hud ? t.edge : t.edge, 8));
        return e;
    }

    public EditText numberField(String value, String hint) {
        EditText e = field(value, hint, InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setMinWidth(dp(96));
        return e;
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    /** A row in a pick dialog. */
    public static final class Row {
        public final CharSequence title;
        public final String detail;
        public final boolean highlight;
        public final Runnable onClick;
        public final Runnable onLongClick;

        public Row(CharSequence title, String detail, boolean highlight, Runnable onClick, Runnable onLongClick) {
            this.title = title;
            this.detail = detail;
            this.highlight = highlight;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }
    }

    public interface TextResult {
        void onText(String text);
    }

    public AlertDialog pick(String title, List<Row> rows, String neutral, final Runnable onNeutral) {
        ScrollView sv = new ScrollView(c);
        LinearLayout box = vbox();
        box.setPadding(0, dp(6), 0, dp(6));
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(c).setTitle(title).setView(sv).setNegativeButton("Close", null);
        if (neutral != null) {
            b.setNeutralButton(neutral, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    if (onNeutral != null) onNeutral.run();
                }
            });
        }
        final AlertDialog dlg = b.create();
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        for (final Row r : rows) {
            LinearLayout row = vbox();
            row.setPadding(dp(22), dp(11), dp(22), dp(11));
            if (tv.resourceId != 0) row.setBackground(c.getDrawable(tv.resourceId));
            TextView tt = text(r.title, 15, r.highlight ? t.accent : t.ink, t.bodyMedium);
            row.addView(tt);
            if (r.detail != null && r.detail.length() > 0) {
                TextView d = dim(r.detail, 12);
                d.setPadding(0, dp(3), 0, 0);
                row.addView(d);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    if (r.onClick != null) r.onClick.run();
                }
            });
            if (r.onLongClick != null) {
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        dlg.dismiss();
                        r.onLongClick.run();
                        return true;
                    }
                });
            }
            box.addView(row);
        }
        if (rows.isEmpty()) {
            TextView e = dim("Nothing here yet.", 14);
            e.setPadding(dp(22), dp(12), dp(22), dp(12));
            box.addView(e);
        }
        dlg.show();
        return dlg;
    }

    public AlertDialog confirm(String title, String message, String yes, final Runnable onYes) {
        return new AlertDialog.Builder(c).setTitle(title).setMessage(message)
                .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onYes != null) onYes.run();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    public AlertDialog prompt(String title, String hint, String value, int inputType, final TextResult r) {
        final EditText et = field(value, hint, inputType);
        et.setSelection(et.getText().length());
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);
        return new AlertDialog.Builder(c).setTitle(title).setView(wrap)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        r.onText(et.getText().toString());
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    public void toast(String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
