package com.omnideck.mobile.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The app's one dialog look, used by {@link Ui#pick}, {@link Ui#confirm} and
 * {@link Ui#prompt} and open to any screen ({@link Ui#sheet}):
 * <ul>
 * <li>Cyber: navy glass with a cyan edge, grid, corner brackets and a cap
 * band behind the header (status dot, Orbitron micro-caps eyebrow, dashed
 * data rail).</li>
 * <li>Light / Dark: the flat card with a hairline under the header.</li>
 * </ul>
 * A header (eyebrow + title), a {@link #body} that scrolls once the sheet
 * reaches its maximum height, and a footer of themed buttons (never the
 * platform's ALL-CAPS Material buttons). The dialog is an {@link AlertDialog}
 * whose {@code getButton(BUTTON_POSITIVE / NEGATIVE / NEUTRAL)} return the
 * themed buttons, so code written for the stock dialogs keeps working.
 */
public final class Sheet {
    /** The dialog (an AlertDialog; getButton() returns the themed buttons). */
    public final AlertDialog dialog;
    /** Content column (18dp side padding by default); scrolls when tall. */
    public final LinearLayout body;

    private final Ui ui;
    private final Theme t;
    private final SheetDialog d;
    private final LinearLayout card;
    private final LinearLayout header;
    private final TextView eyebrow;
    private final TextView title;
    private final Widgets.StatusDot dot;
    private final LinearLayout footer;
    private final View footerLine;
    private final Ui.UiButton[] buttons = new Ui.UiButton[3];
    private View focusOnShow;
    private Runnable onDismiss;
    private int capPx = -1;

    private static final int POSITIVE = 0, NEGATIVE = 1, NEUTRAL = 2;

    Sheet(Ui ui, String eyebrowText, CharSequence titleText) {
        this.ui = ui;
        this.t = ui.t;
        Context c = ui.c;
        card = ui.vbox();
        card.setClickable(true);
        if (!t.isDark) card.setElevation(ui.dp(8));

        header = ui.hbox();
        header.setGravity(Gravity.TOP);
        header.setPadding(ui.dp(18), ui.dp(15), ui.dp(18), ui.dp(14));
        LinearLayout heads = ui.vbox();
        LinearLayout kicker = ui.hbox();
        if (t.hud) {
            dot = new Widgets.StatusDot(c);
            dot.setColor(t.accent);
            dot.setFade(true);
            dot.setCoreFraction(1f);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ui.dp(5), ui.dp(5));
            dl.rightMargin = ui.dp(8);
            kicker.addView(dot, dl);
        } else {
            dot = null;
        }
        eyebrow = ui.label(eyebrowText == null ? "" : eyebrowText);
        kicker.addView(eyebrow, Ui.wrap());
        if (t.hud) {
            Widgets.Rail rail = new Widgets.Rail(c, Theme.alpha(t.accent, 0x8C));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(6));
            rl.leftMargin = ui.dp(10);
            kicker.addView(rail, rl);
        }
        heads.addView(kicker, Ui.fillW());
        title = ui.text(titleText == null ? "" : titleText, 17, t.inkStrong, t.bodySemi);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.12f);
        title.setPadding(0, ui.dp(7), 0, 0);
        title.setVisibility(titleText == null || titleText.length() == 0 ? View.GONE : View.VISIBLE);
        heads.addView(title, Ui.fillW());
        header.addView(heads, Ui.weight(1));
        card.addView(header, Ui.fillW());
        if (!t.hud) {
            View rule = new View(c);
            rule.setBackgroundColor(t.hair);
            card.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, ui.dp(1))));
        }

        int screenH = c.getResources().getDisplayMetrics().heightPixels;
        int max = Math.max(ui.dp(96), Math.min((int) (screenH * 0.62f), screenH - ui.dp(240)));
        ScrollView sv = new CappedScroll(c, max);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        body = ui.vbox();
        body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(4));
        sv.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sv, Ui.fillW());

        footerLine = new View(c);
        footerLine.setBackgroundColor(t.hud ? t.hair : t.hairSoft);
        footerLine.setVisibility(View.GONE);
        card.addView(footerLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, ui.dp(1))));
        footer = ui.hbox();
        footer.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(16));
        card.addView(footer, Ui.fillW());

        card.setBackground(background(ui.dp(64)));
        if (t.hud) {
            // The cap band sits exactly behind the header, whatever its height.
            header.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int top, int r, int b, int ol, int ot, int or, int ob) {
                    int h = b - top;
                    if (h > 0 && h != capPx) card.setBackground(background(h));
                }
            });
        }

        FrameLayout frame = new FrameLayout(c);
        // Room around the card for Light's shadow; the card itself is screen − 32dp wide.
        frame.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        frame.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        d = new SheetDialog(c, frame, this);
        dialog = d;
        d.setTitle(titleText != null && titleText.length() > 0 ? titleText : eyebrowText);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                if (onDismiss != null) onDismiss.run();
            }
        });
    }

    private Drawable background(int cap) {
        capPx = cap;
        Panel.Builder b = Panel.builder().fill(Theme.flatten(t.hud ? t.surface2 : t.surface, t.bg))
                .edge(t.hud ? t.edgeStrong : t.edge, Math.max(1, ui.dp(1))).radius(ui.dp(t.radius + 2))
                .highlight(t.panelHi);
        if (t.hud) {
            b.grid(ui.dp(22), t.gridColor).bloom(t.bloomColor)
                    .brackets(ui.dp(12), ui.dp(1.3f), t.bracketColor).bracketInset(ui.dp(6))
                    .cap(cap, t.cap, Theme.alpha(t.accent, 0x29));
        }
        return b.build();
    }

    /** The eyebrow's color (e.g. the danger ink for a destructive confirm). */
    public Sheet eyebrowColor(int color) {
        eyebrow.setTextColor(color);
        if (dot != null) dot.setColor(color);
        return this;
    }

    /** A paragraph of body text. */
    public TextView message(CharSequence text) {
        TextView m = ui.text(text, 14.5f, t.dim, t.body);
        m.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams lp = Ui.fillW();
        if (body.getChildCount() > 0) lp.topMargin = ui.dp(10);
        body.addView(m, lp);
        return m;
    }

    /** The main action (style {@link Ui#PRIMARY} or {@link Ui#DANGER}); closes the sheet, then runs. */
    public Button positive(String label, int style, Runnable r) {
        return button(POSITIVE, label, style, true, r);
    }

    /**
     * The main action. With {@code autoDismiss} false the sheet stays open
     * and {@code r} decides (call {@link #dismiss()}) — e.g. to validate input.
     */
    public Button positive(String label, int style, boolean autoDismiss, Runnable r) {
        return button(POSITIVE, label, style, autoDismiss, r);
    }

    /** Cancel / Close (secondary style); closes the sheet, then runs {@code r} (may be null). */
    public Button negative(String label, Runnable r) {
        return button(NEGATIVE, label, Ui.SECONDARY, true, r);
    }

    /** A side action on the left of the footer (ghost style); closes the sheet, then runs. */
    public Button neutral(String label, Runnable r) {
        return button(NEUTRAL, label, Ui.GHOST, true, r);
    }

    private Button button(int slot, String label, int style, final boolean autoDismiss, final Runnable r) {
        Ui.UiButton b = new Ui.UiButton(ui.c);
        ui.styleButton(b, label, 0, style, false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                if (autoDismiss) dismiss();
                if (r != null) r.run();
            }
        });
        buttons[slot] = b;
        return b;
    }

    /** A hairline between the body and the footer (lists). */
    public Sheet footerRule(boolean on) {
        footerLine.setVisibility(on ? View.VISIBLE : View.GONE);
        return this;
    }

    /** An × at the header's right that closes the sheet. */
    public Sheet closeButton(String description) {
        View x = ui.iconButton(IconDrawable.CLOSE, description, t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
        lp.setMargins(ui.dp(6), -ui.dp(8), -ui.dp(10), 0);
        header.addView(x, lp);
        return this;
    }

    /** Focus this field and raise the keyboard when the sheet opens. */
    public Sheet showKeyboard(View field) {
        focusOnShow = field;
        return this;
    }

    public Sheet onDismiss(Runnable r) {
        onDismiss = r;
        return this;
    }

    public boolean isShowing() {
        return d.isShowing();
    }

    public void dismiss() {
        if (d.isShowing()) d.dismiss();
    }

    /**
     * Lays out the footer and shows the sheet. Does nothing when the
     * activity is finishing or gone (a callback that outlived its screen).
     */
    public Sheet show() {
        layoutFooter();
        if (!ui.canShowDialogs()) return this;
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(((t.scrim >>> 24) & 0xFF) / 255f);
            if (ui.reduceMotion) w.setWindowAnimations(0);
            if (focusOnShow != null) {
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
        d.show();
        if (w != null) {
            int screenW = ui.c.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screenW - ui.dp(8), ui.dp(484)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (focusOnShow != null) focusOnShow.requestFocus();
        return this;
    }

    /** Two actions share the row equally; otherwise the side action sits left, the rest right. */
    private void layoutFooter() {
        footer.removeAllViews();
        Ui.UiButton pos = buttons[POSITIVE], neg = buttons[NEGATIVE], neu = buttons[NEUTRAL];
        if (pos == null && neg == null && neu == null) {
            footer.setVisibility(View.GONE);
            return;
        }
        footer.setVisibility(View.VISIBLE);
        if (pos != null && neg != null && neu == null) {
            footer.addView(neg, Ui.weight(1));
            LinearLayout.LayoutParams pl = Ui.weight(1);
            pl.leftMargin = ui.dp(10);
            footer.addView(pos, pl);
            return;
        }
        if (neu != null) {
            LinearLayout.LayoutParams nl = Ui.wrap();
            nl.leftMargin = -ui.dp(4);
            footer.addView(neu, nl);
        }
        footer.addView(ui.flexSpace());
        if (neg != null) {
            neg.setMinWidth(ui.dp(96));
            footer.addView(neg, Ui.wrap());
        }
        if (pos != null) {
            pos.setMinWidth(ui.dp(96));
            LinearLayout.LayoutParams pl = Ui.wrap();
            pl.leftMargin = ui.dp(10);
            footer.addView(pos, pl);
        }
    }

    /** An AlertDialog with the sheet's own layout in place of the platform's alert layout. */
    static final class SheetDialog extends AlertDialog {
        private final View content;
        private final Sheet owner;

        SheetDialog(Context c, View content, Sheet owner) {
            super(c, 0);
            this.content = content;
            this.owner = owner;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            setContentView(content);
        }

        @Override
        public Button getButton(int whichButton) {
            switch (whichButton) {
                case BUTTON_POSITIVE:
                    return owner.buttons[POSITIVE];
                case BUTTON_NEGATIVE:
                    return owner.buttons[NEGATIVE];
                case BUTTON_NEUTRAL:
                    return owner.buttons[NEUTRAL];
                default:
                    return null;
            }
        }

        @Override
        public ListView getListView() {
            return null;
        }
    }

    /** A ScrollView that never grows taller than {@code maxPx}. */
    static final class CappedScroll extends ScrollView {
        private final int maxPx;

        CappedScroll(Context c, int maxPx) {
            super(c);
            this.maxPx = maxPx;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(heightSpec);
            int size = MeasureSpec.getSize(heightSpec);
            int cap = mode == MeasureSpec.UNSPECIFIED ? maxPx : Math.min(size, maxPx);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }
}
