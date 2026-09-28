package com.omnideck.mobile.screens;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.MainActivity;
import com.omnideck.mobile.Settings;
import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.ServerInfo;
import com.omnideck.mobile.ui.Backdrop;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.SettingsWidgets;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SETTINGS — the full-screen configuration deck behind the top-bar gear:
 * appearance, the link to the AI, models and routing, runner performance,
 * sampling, persona and memory, voice, the PC bridge, privacy and about.
 * <p>
 * Every control saves the moment it changes (text fields on Save, on focus
 * loss and when the screen closes) and reads the live value back whenever
 * the screen is shown. A sticky header carries a section index that tracks
 * the scroll position and jumps to a section on tap; each card's cap shows a
 * one-glance summary of its state.
 */
public final class SettingsScreen extends Screen {
    static final int[] CTX_PRESETS = {0, 2048, 4096, 8192, 16384, 32768};
    static final String[] CTX_LABELS = {"Auto", "2K", "4K", "8K", "16K", "32K"};
    static final int[] TOKEN_LADDER = {0, 128, 256, 512, 1024, 2048, 4096, 8192, 16384};
    static final String[] THEME_PREFS = {Settings.THEME_CYBER, Settings.THEME_LIGHT, Settings.THEME_DARK,
            Settings.THEME_SYSTEM};
    static final String[] THEME_NAMES = {"Cyber", "Light", "Dark", "System"};
    static final String[] MODES = {Settings.MODE_AUTO, Settings.MODE_FAST, Settings.MODE_DEEP};
    static final String TEST_LINE = "OMNI online. All systems nominal.";
    /** Ollama's own defaults, marked on the sliders while "Default" is in effect. */
    static final float TEMP_DEFAULT = 0.8f;
    static final float TOP_P_DEFAULT = 0.9f;

    /** One card of the page, with its entry in the section index. */
    private static final class Section {
        final String name;
        final LinearLayout card;
        final LinearLayout body;
        final TextView status;
        TextView tabText;
        View tabMark;

        Section(String name, LinearLayout card, LinearLayout body, TextView status) {
            this.name = name;
            this.card = card;
            this.body = body;
            this.status = status;
        }
    }

    /** A settings row's views (title, subtitle) so they can be updated. */
    private static final class Line {
        final LinearLayout row;
        final TextView title;
        final TextView sub;

        Line(LinearLayout row, TextView title, TextView sub) {
            this.row = row;
            this.title = title;
            this.sub = sub;
        }
    }

    /** A full-width "select" field: value on the left, detail + chevron on the right. */
    private static final class Select {
        final LinearLayout box;
        final TextView value;
        final TextView detail;

        Select(LinearLayout box, TextView value, TextView detail) {
            this.box = box;
            this.value = value;
            this.detail = detail;
        }
    }

    private final List<Section> sections = new ArrayList<Section>();
    private ScrollView scroll;
    private LinearLayout column;
    private HorizontalScrollView stripScroll;
    private LinearLayout strip;
    private TextView savedMark;
    private int activeSection = -1;
    /** Section chosen from the index; holds the highlight until the user scrolls by hand. */
    private int pinnedSection = -1;
    private ValueAnimator pageAnim, stripAnim;

    // Appearance
    private final SettingsWidgets.ThemePreview[] themeCards = new SettingsWidgets.ThemePreview[4];
    private final TextView[] themeLabels = new TextView[4];
    private TextView themeNote;
    private Widgets.Toggle reduceMotion, hudEffects, haptics;
    private Line hudLine;
    private Section secAppearance;

    // Connection
    private Section secConnection;
    private Widgets.StatusDot linkDot;
    private TextView linkState, linkDetail;
    private TextView[] linkGrid;
    private Line addressLine;
    private TextView autoBtn, scanBtn;
    private LinearLayout foundBox;

    // AI model
    private Section secModel;
    private Select modelSelect, deepSelect;
    private SettingsWidgets.Segmented modeSeg;
    private TextView modeNote;
    private Widgets.Toggle keepLoaded;

    // Performance
    private Section secPerf;
    private SettingsWidgets.Segmented ctxSeg;
    private TextView ctxReadout;
    private SettingsWidgets.Stepper threads;
    private TextView loadedNote;

    // Generation
    private Section secGen;
    private SettingsWidgets.Slider tempSlider, topPSlider;
    private TextView tempValue, topPValue;
    private TextView tempReset, topPReset;
    private SettingsWidgets.Stepper maxTokens;

    // Persona
    private Section secPersona;
    private EditText promptField;
    private TextView promptCount, promptSave, promptClear;
    private LinearLayout factsBox;
    private List<String> shownFacts;

    // Voice
    private Section secVoice;
    private Widgets.Toggle readAloud;
    private SettingsWidgets.Slider rateSlider;
    private TextView rateValue;

    // PC bridge
    private Section secBridge;
    private Widgets.StatusDot bridgeDot;
    private TextView bridgeState, bridgeDetail, pairStatus;
    private EditText portField, tokenField;
    private ImageView eyeBtn;
    private boolean tokenVisible;
    private TextView pairBtn, forgetBtn;
    private boolean pairing;

    // Privacy
    private Section secPrivacy;
    private Widgets.Toggle incognito;
    private Line historyLine;
    private TextView clearBtn;
    private int savedChats = -1;

    // About
    private Section secAbout;
    private TextView[] aboutGrid;

    public SettingsScreen(MainActivity a) {
        super(a);
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    @Override
    protected View build() {
        LinearLayout root = ui.vbox();
        root.addView(buildHeader(), Ui.fillW());
        stripScroll = new HorizontalScrollView(a);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        stripScroll.setHorizontalFadingEdgeEnabled(true);
        stripScroll.setFadingEdgeLength(ui.dp(28));
        stripScroll.setBackgroundColor(headerFill());
        strip = ui.hbox();
        strip.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        stripScroll.addView(strip);
        root.addView(stripScroll, Ui.fillW());
        View line = ui.divider();
        if (t.hud) line.setBackgroundColor(t.edge);
        root.addView(line);

        scroll = new ScrollView(a);
        column = ui.scrollColumn(scroll, 14, 14);
        // The column takes initial focus so no text field pops the keyboard on open.
        column.setFocusableInTouchMode(true);
        column.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        buildAppearance();
        buildConnection();
        buildModel();
        buildPerformance();
        buildGeneration();
        buildPersona();
        buildVoice();
        buildBridge();
        buildPrivacy();
        buildAbout();

        String creditLine = "OMNI-DECK Mobile · companion for OMNI-DECK";
        TextView credit = ui.text(t.hud ? creditLine.toUpperCase(Locale.US) : creditLine, t.hud ? 9 : 11.5f, t.faint,
                t.hud ? t.labelFace : t.body);
        if (t.hud) credit.setLetterSpacing(0.12f);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, ui.dp(6), 0, ui.dp(10));
        column.addView(credit, Ui.fillW());

        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                updateActiveSection();
            }
        });
        scroll.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    // A finger on the page takes over from any jump in progress.
                    pinnedSection = -1;
                    if (pageAnim != null) pageAnim.cancel();
                }
                return false;
            }
        });
        setActiveSection(0);
        refresh();
        return root;
    }

    private int headerFill() {
        return t.hud ? Theme.alpha(t.surface2, 0x99) : t.surface;
    }

    private View buildHeader() {
        LinearLayout h = ui.hbox();
        h.setPadding(ui.dp(4), ui.dp(4), ui.dp(14), ui.dp(2));
        h.setBackgroundColor(headerFill());
        h.addView(ui.iconButton(IconDrawable.BACK, "Close settings", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.closeSettings();
            }
        }));
        LinearLayout titles = ui.vbox();
        titles.setPadding(ui.dp(4), 0, ui.dp(8), 0);
        titles.addView(ui.title("Settings", t.hud ? 14 : 18));
        TextView sub = ui.label(t.hud ? "System configuration" : "App, AI and PC preferences");
        sub.setPadding(0, ui.dp(4), 0, 0);
        titles.addView(sub);
        h.addView(titles, Ui.weight(1));
        savedMark = ui.label("Auto-save");
        savedMark.setTextColor(t.faint);
        savedMark.setCompoundDrawablePadding(ui.dp(5));
        setSavedIcon(t.faint);
        h.addView(savedMark);
        return h;
    }

    private void setSavedIcon(int color) {
        IconDrawable d = new IconDrawable(IconDrawable.CHECK, color, color, ui.dp(12));
        d.stroke(2.4f);
        d.setBounds(0, 0, ui.dp(12), ui.dp(12));
        savedMark.setCompoundDrawables(d, null, null, null);
    }

    private final Runnable savedReset = new Runnable() {
        @Override
        public void run() {
            savedMark.setText(t.label("Auto-save"));
            savedMark.setTextColor(t.faint);
            setSavedIcon(t.faint);
        }
    };

    /** Flashes "Saved" in the header after a change is persisted. */
    private void saved() {
        if (savedMark == null) return;
        savedMark.setText(t.label("Saved"));
        savedMark.setTextColor(t.ok);
        setSavedIcon(t.ok);
        savedMark.removeCallbacks(savedReset);
        savedMark.postDelayed(savedReset, 1600);
    }

    // ------------------------------------------------------------------
    // Section scaffolding
    // ------------------------------------------------------------------

    private Section section(String title, String tabName) {
        TextView status = ui.readout("", t.hud ? 10 : 11, t.faint);
        if (t.hud) status.setLetterSpacing(0.06f);
        status.setPadding(ui.dp(8), 0, ui.dp(4), 0);
        // The summary gives way before the section title does.
        status.setMaxWidth(ui.dp(180));
        status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout card = ui.capCard(title, status);
        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(14));
        card.addView(body, Ui.fillW());
        LinearLayout.LayoutParams lp = Ui.fillW();
        lp.bottomMargin = ui.dp(12);
        column.addView(card, lp);
        final Section s = new Section(title, card, body, status);
        final int index = sections.size();
        sections.add(s);

        LinearLayout tab = ui.vbox();
        tab.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView tt = ui.text(t.hud ? tabName.toUpperCase(Locale.US) : tabName, t.hud ? 9.5f : 12.5f, t.dim,
                t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) tt.setLetterSpacing(0.14f);
        tt.setSingleLine(true);
        tt.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(8));
        tab.addView(tt, Ui.wrap());
        View mark = new View(a);
        mark.setBackground(ui.rounded(t.hud ? t.accent : t.isDark ? t.inkStrong : t.accent, 0, 1));
        mark.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(2));
        mlp.leftMargin = ui.dp(8);
        mlp.rightMargin = ui.dp(8);
        tab.addView(mark, mlp);
        tab.setContentDescription("Jump to " + tabName);
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                jumpTo(index);
            }
        });
        s.tabText = tt;
        s.tabMark = mark;
        strip.addView(tab, Ui.wrap());
        return s;
    }

    /** Scrolls a section to the top and pins its tab until the user scrolls by hand. */
    private void jumpTo(int index) {
        Section s = sections.get(index);
        pinnedSection = index;
        setActiveSection(index);
        int y = Math.max(0, s.card.getTop() - ui.dp(12));
        View content = scroll.getChildAt(0);
        if (content != null) y = Math.min(y, Math.max(0, content.getHeight() - scroll.getHeight()));
        pageAnim = glide(pageAnim, scroll, false, y);
    }

    /**
     * Eases a scroller to {@code to} (a calm decelerating glide; instant with
     * Reduce motion). Driven by an animator rather than smoothScrollTo so the
     * duration and curve match the rest of the app.
     */
    private ValueAnimator glide(ValueAnimator running, final View scroller, final boolean horizontal, int to) {
        if (running != null) running.cancel();
        int from = horizontal ? scroller.getScrollX() : scroller.getScrollY();
        if (e.settings.reduceMotion() || Math.abs(to - from) < 2 || !isShown()) {
            if (horizontal) scroller.scrollTo(to, 0);
            else scroller.scrollTo(0, to);
            return null;
        }
        ValueAnimator va = ValueAnimator.ofInt(from, to);
        va.setDuration(Math.min(520, 220 + Math.abs(to - from) / 8));
        va.setInterpolator(new DecelerateInterpolator(1.6f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                int v = (Integer) anim.getAnimatedValue();
                if (horizontal) scroller.scrollTo(v, 0);
                else scroller.scrollTo(0, v);
            }
        });
        va.start();
        return va;
    }

    private void stopGlides() {
        if (pageAnim != null) pageAnim.cancel();
        if (stripAnim != null) stripAnim.cancel();
        pageAnim = null;
        stripAnim = null;
    }

    private void updateActiveSection() {
        if (pinnedSection >= 0) return;
        int y = scroll.getScrollY() + ui.dp(48);
        int idx = 0;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).card.getTop() <= y) idx = i;
        }
        View content = scroll.getChildAt(0);
        if (content != null && scroll.getScrollY() > 0
                && scroll.getScrollY() + scroll.getHeight() >= content.getHeight() - ui.dp(2)) {
            // At the very bottom the last cards can't reach the top: pick the last one in view.
            for (int i = idx; i < sections.size(); i++) {
                if (sections.get(i).card.getTop() < scroll.getScrollY() + scroll.getHeight() / 2) idx = i;
            }
        }
        setActiveSection(idx);
    }

    private void setActiveSection(int idx) {
        if (idx == activeSection) return;
        activeSection = idx;
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            boolean on = i == idx;
            s.tabText.setTextColor(on ? (t.hud ? t.accent : t.inkStrong) : t.dim);
            s.tabMark.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            s.tabText.setSelected(on);
        }
        final View tab = (View) sections.get(idx).tabText.getParent();
        stripScroll.post(new Runnable() {
            @Override
            public void run() {
                // Keep the active tab centred in the index.
                int max = Math.max(0, strip.getWidth() - stripScroll.getWidth());
                int target = tab.getLeft() - (stripScroll.getWidth() - tab.getWidth()) / 2;
                stripAnim = glide(stripAnim, stripScroll, true, Math.max(0, Math.min(max, target)));
            }
        });
    }

    private void setStatus(Section s, String text, int color) {
        s.status.setText(t.hud ? text.toUpperCase(Locale.US) : text);
        s.status.setTextColor(color);
    }

    /** Reports a switch's on/off state to TalkBack (Widgets.Toggle draws its own track). */
    private static void describeAsSwitch(View v) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(host instanceof Widgets.Toggle && ((Widgets.Toggle) host).isChecked());
            }
        });
    }

    // ------------------------------------------------------------------
    // Row helpers
    // ------------------------------------------------------------------

    private Line line(String title, String subtitle, View control) {
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

    private void setSub(Line l, String s) {
        l.sub.setText(s);
        l.sub.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** A setting row with a switch; tapping anywhere on the row flips it. */
    private Widgets.Toggle toggleRow(LinearLayout body, String title, String sub, final Widgets.Toggle.OnChange l,
                                     Line[] out) {
        final Widgets.Toggle tg = ui.toggle(false, new Widgets.Toggle.OnChange() {
            @Override
            public void changed(boolean on) {
                l.changed(on);
                saved();
            }
        });
        tg.setContentDescription(title);
        describeAsSwitch(tg);
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
    private Line navRow(LinearLayout body, String title, String sub, int icon, final Runnable r) {
        ImageView iv = new ImageView(a);
        iv.setImageDrawable(new IconDrawable(icon, t.hud ? t.accent : t.dim, t.hud ? t.accent : t.dim, ui.dp(18)));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        Line ln = line(title, sub, iv);
        ln.row.setContentDescription(title);
        TypedValue tv = new TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) ln.row.setBackground(a.getDrawable(tv.resourceId));
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
    private Line heading(LinearLayout body, String title, String sub) {
        Line ln = line(title, sub, null);
        ln.row.setPadding(0, ui.dp(12), 0, ui.dp(9));
        ln.row.setMinimumHeight(0);
        body.addView(ln.row, Ui.fillW());
        return ln;
    }

    private void sep(LinearLayout body) {
        View v = new View(a);
        v.setBackgroundColor(t.hud ? t.hairSoft : t.isDark ? t.hairSoft : t.hair);
        body.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(0.7f))));
    }

    private TextView smallButton(String label, int icon, int style, String desc, View.OnClickListener l) {
        TextView b = ui.button(label, icon, style, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.hud ? 10 : 13);
        b.setPadding(ui.dp(icon != 0 ? 10 : 12), ui.dp(7), ui.dp(12), ui.dp(7));
        b.setMinHeight(ui.dp(34));
        b.setMinimumHeight(ui.dp(34));
        b.setContentDescription(desc);
        return b;
    }

    private TextView bigButton(String label, int icon, int style, String desc, View.OnClickListener l) {
        TextView b = ui.button(label, icon, style, l);
        b.setContentDescription(desc);
        return b;
    }

    /** Relabels a {@link Ui#button} in its theme's case. */
    private void buttonText(TextView b, String s) {
        b.setText(t.hud ? s.toUpperCase(Locale.US) : s);
    }

    private void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.45f);
    }

    /** Recessed tiles of micro-caps label over a mono value, two per row. */
    private TextView[] readoutGrid(LinearLayout body, String[] labels) {
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
            cell.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(9));
            cell.setBackground(ui.rounded(t.input, t.hud ? t.hairSoft : t.isDark ? t.hairSoft : 0, 7));
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

    private Select select(LinearLayout body, String desc, final Runnable onTap) {
        LinearLayout box = ui.hbox();
        box.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        box.setMinimumHeight(ui.dp(44));
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 0x33)),
                ui.rounded(t.input, t.hud ? t.edge : t.edge, 8), null));
        TextView v = ui.text("", 14, t.ink, t.mono);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(v, Ui.weight(1));
        TextView d = ui.text("", t.hud ? 9 : 11.5f, t.faint, t.hud ? t.labelFace : t.bodyMedium);
        if (t.hud) d.setLetterSpacing(0.1f);
        d.setSingleLine(true);
        d.setPadding(ui.dp(8), 0, ui.dp(2), 0);
        box.addView(d);
        ImageView chev = new ImageView(a);
        chev.setImageDrawable(new IconDrawable(IconDrawable.DOWN, t.dim, t.dim, ui.dp(16)));
        chev.setScaleType(ImageView.ScaleType.CENTER);
        box.addView(chev, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        box.setContentDescription(desc);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ui.tick(view);
                onTap.run();
            }
        });
        body.addView(box, Ui.fillW());
        return new Select(box, v, d);
    }

    private TextView valueReadout() {
        TextView v = ui.readout("", t.hud ? 13 : 13.5f, t.accent);
        v.setPadding(ui.dp(8), 0, 0, 0);
        return v;
    }

    /** A slider header: title + live mono value + a "Default" reset link. */
    private TextView[] sliderHead(LinearLayout body, String title, String sub, final Runnable onReset) {
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
                    saved();
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
    private void scaleRow(LinearLayout body, String left, String mid, String right) {
        LinearLayout row = ui.hbox();
        row.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4));
        TextView l = ui.readout(left, 10, t.faint);
        row.addView(l, Ui.weight(1));
        if (mid != null) {
            TextView m = ui.readout(mid, 10, t.faint);
            m.setGravity(Gravity.CENTER);
            row.addView(m, Ui.weight(1));
        }
        TextView r = ui.readout(right, 10, t.faint);
        r.setGravity(Gravity.END);
        row.addView(r, Ui.weight(1));
        body.addView(row, Ui.fillW());
    }

    private void note(LinearLayout body, TextView text) {
        LinearLayout row = ui.hbox();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, ui.dp(10), 0, 0);
        ImageView iv = new ImageView(a);
        iv.setImageDrawable(new IconDrawable(IconDrawable.INFO, t.faint, t.faint, ui.dp(15)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        ilp.rightMargin = ui.dp(8);
        ilp.topMargin = ui.dp(1);
        row.addView(iv, ilp);
        row.addView(text, Ui.weight(1));
        body.addView(row, Ui.fillW());
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private static String grouped(int v) {
        return String.format(Locale.US, "%,d", v);
    }

    // ------------------------------------------------------------------
    // 1 · Appearance
    // ------------------------------------------------------------------

    private void buildAppearance() {
        secAppearance = section("Appearance", "Appearance");
        LinearLayout body = secAppearance.body;
        LinearLayout grid = ui.hbox();
        grid.setGravity(Gravity.TOP);
        grid.setPadding(0, ui.dp(8), 0, 0);
        Theme cyber = Theme.of(a, Theme.CYBER), light = Theme.of(a, Theme.LIGHT), dark = Theme.of(a, Theme.DARK);
        Theme[][] looks = {{cyber, null}, {light, null}, {dark, null}, {light, dark}};
        for (int i = 0; i < 4; i++) {
            final String pref = THEME_PREFS[i];
            LinearLayout cell = ui.vbox();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            SettingsWidgets.ThemePreview pv = new SettingsWidgets.ThemePreview(a, t, looks[i][0], looks[i][1]);
            themeCards[i] = pv;
            cell.addView(pv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(112)));
            TextView name = ui.text(t.hud ? THEME_NAMES[i].toUpperCase(Locale.US) : THEME_NAMES[i],
                    t.hud ? 9.5f : 12.5f, t.dim, t.hud ? t.labelFace : t.bodySemi);
            if (t.hud) name.setLetterSpacing(0.12f);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, ui.dp(7), 0, 0);
            themeLabels[i] = name;
            cell.addView(name, Ui.fillW());
            cell.setContentDescription("Theme: " + THEME_NAMES[i]);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    chooseTheme(pref);
                }
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.leftMargin = i == 0 ? 0 : ui.dp(4);
            lp.rightMargin = i == 3 ? 0 : ui.dp(4);
            grid.addView(cell, lp);
        }
        body.addView(grid, Ui.fillW());
        themeNote = ui.dim("", 12.5f);
        themeNote.setPadding(0, ui.dp(12), 0, ui.dp(6));
        body.addView(themeNote, Ui.fillW());
        sep(body);
        reduceMotion = toggleRow(body, "Reduce motion", "Stills the AI core, pulses, scan line and boot sequence.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.settings.setReduceMotion(on);
                        a.updateScanLine();
                        a.onStateChanged(); // re-evaluates the status dots' pulse
                        refreshAppearance();
                    }
                }, null);
        sep(body);
        Line[] hl = new Line[1];
        hudEffects = toggleRow(body, "HUD effects", "", new Widgets.Toggle.OnChange() {
            @Override
            public void changed(boolean on) {
                e.settings.setHudEffects(on);
                a.updateScanLine();
                applyBackdrop();
                refreshAppearance();
            }
        }, hl);
        hudLine = hl[0];
        sep(body);
        haptics = toggleRow(body, "Haptics", "A light tick when you tap controls.", new Widgets.Toggle.OnChange() {
            @Override
            public void changed(boolean on) {
                e.settings.setHaptics(on);
                ui.haptics = on;
                refreshAppearance();
            }
        }, null);
    }

    private void chooseTheme(String pref) {
        if (pref.equals(e.settings.theme())) return;
        commitFields();
        saved();
        // Recreates the activity when the look changes; otherwise just saves the choice.
        a.applyTheme(pref);
        refreshAppearance();
    }

    /** Swaps the shell backdrop's grid on or off without recreating the activity. */
    private void applyBackdrop() {
        View v = view();
        ViewParent p = v.getParent();
        while (p instanceof View) {
            View pv = (View) p;
            if (pv.getBackground() instanceof Backdrop) {
                pv.setBackground(new Backdrop(t, t.hud && e.settings.hudEffects() ? ui.dp(22) : 0));
                return;
            }
            p = pv.getParent();
        }
    }

    private void refreshAppearance() {
        String pref = e.settings.theme();
        for (int i = 0; i < 4; i++) {
            boolean on = THEME_PREFS[i].equals(pref);
            themeCards[i].setChosen(on);
            themeLabels[i].setTextColor(on ? (t.hud ? t.accent : t.inkStrong) : t.dim);
            ((View) themeLabels[i].getParent()).setSelected(on);
        }
        String note;
        if (Settings.THEME_CYBER.equals(pref)) {
            note = "Mainframe HUD — navy glass, sky-cyan accent and Orbitron micro-caps.";
        } else if (Settings.THEME_LIGHT.equals(pref)) {
            note = "Modern — white cards, slate-blue accent and Inter throughout.";
        } else if (Settings.THEME_DARK.equals(pref)) {
            note = "Modern on the dark layer — graphite cards and a monochrome accent.";
        } else {
            note = "Follows the phone's light or dark mode. Showing " + (t.isDark ? "Dark" : "Light") + " right now.";
        }
        themeNote.setText(note);
        reduceMotion.setChecked(e.settings.reduceMotion(), false);
        hudEffects.setChecked(e.settings.hudEffects(), false);
        haptics.setChecked(e.settings.haptics(), false);
        setSub(hudLine, t.hud ? "Hairline grid, top bloom and the slow scan line." : "Grid, bloom and scan line · Cyber only.");
        hudLine.row.setAlpha(t.hud ? 1f : 0.6f);
        String name = pref.equals(Settings.THEME_SYSTEM) ? "System · " + t.name : THEME_NAMES[themeIndex(pref)];
        setStatus(secAppearance, name, t.faint);
    }

    private static int themeIndex(String pref) {
        for (int i = 0; i < THEME_PREFS.length; i++) {
            if (THEME_PREFS[i].equals(pref)) return i;
        }
        return 3;
    }

    // ------------------------------------------------------------------
    // 2 · Connection
    // ------------------------------------------------------------------

    private void buildConnection() {
        secConnection = section("Connection", "Connection");
        LinearLayout body = secConnection.body;
        LinearLayout link = ui.hbox();
        link.setPadding(0, ui.dp(8), 0, ui.dp(2));
        linkDot = new Widgets.StatusDot(a);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16));
        dlp.rightMargin = ui.dp(8);
        link.addView(linkDot, dlp);
        LinearLayout lt = ui.vbox();
        linkState = ui.text("", t.hud ? 11 : 14.5f, t.ok, t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) linkState.setLetterSpacing(0.12f);
        lt.addView(linkState);
        linkDetail = ui.dim("", 12.5f);
        linkDetail.setPadding(0, ui.dp(3), 0, 0);
        lt.addView(linkDetail);
        link.addView(lt, Ui.weight(1));
        body.addView(link, Ui.fillW());
        linkGrid = readoutGrid(body, new String[]{"Host", "Ollama", "Latency", "Models"});
        View gap = ui.space(1, 6);
        body.addView(gap);
        addressLine = line("AI address", "", smallButton("Change", IconDrawable.EDIT, Ui.SECONDARY,
                "Change address", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.promptServerAddress();
                    }
                }));
        body.addView(addressLine.row, Ui.fillW());
        LinearLayout btns = ui.hbox();
        btns.setPadding(0, ui.dp(2), 0, 0);
        autoBtn = bigButton("Auto-detect", IconDrawable.WIFI, Ui.SECONDARY, "Auto-detect", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // setServer("") clears the manual address and starts discovery itself
                // (a discover(true) right after would be a no-op while that scan runs).
                e.setServer("");
                saved();
                ui.toast("Searching the network for your AI…");
                refreshConnection();
            }
        });
        btns.addView(autoBtn, Ui.weight(1));
        btns.addView(ui.space(8, 1));
        scanBtn = bigButton("Scan now", IconDrawable.SCAN, Ui.SECONDARY, "Scan now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (e.isScanning()) {
                    ui.toast("Already scanning…");
                    return;
                }
                e.discover(true);
                refreshConnection();
            }
        });
        btns.addView(scanBtn, Ui.weight(1));
        body.addView(btns, Ui.fillW());
        foundBox = ui.vbox();
        foundBox.setVisibility(View.GONE);
        body.addView(foundBox, Ui.fillW());
    }

    private void refreshConnection() {
        Engine.State s = e.state();
        ServerInfo srv = e.server();
        int c = s == Engine.State.ONLINE ? t.ok : s == Engine.State.SEARCHING ? t.warn : t.danger;
        linkDot.setColor(c);
        linkDot.setPulsing(isShown() && s != Engine.State.OFFLINE && !e.settings.reduceMotion());
        String st = s == Engine.State.ONLINE ? "Online" : s == Engine.State.SEARCHING ? "Scanning" : "Offline";
        linkState.setText(t.hud ? "LINK // " + st.toUpperCase(Locale.US) : st);
        linkState.setTextColor(c);
        linkDetail.setText(e.stateDetail());
        boolean on = s == Engine.State.ONLINE && srv != null;
        linkGrid[0].setText(on ? srv.label() : e.settings.lastHost().length() > 0
                ? e.settings.lastHost() + ":" + e.settings.lastPort() : "—");
        linkGrid[0].setTextColor(on ? t.ink : t.faint);
        linkGrid[1].setText(on && srv.version.length() > 0 ? srv.version : "—");
        linkGrid[1].setTextColor(on ? t.ink : t.faint);
        double lat = e.telemetry.latencyMs.last();
        linkGrid[2].setText(on && !Double.isNaN(lat) ? Math.round(lat) + " ms" : "—");
        linkGrid[2].setTextColor(on ? t.ink : t.faint);
        int loaded = 0;
        for (ModelInfo m : e.models()) {
            if (e.isLoaded(m.name)) loaded++;
        }
        linkGrid[3].setText(on ? e.models().size() + " · " + loaded + " loaded" : "—");
        linkGrid[3].setTextColor(on ? t.ink : t.faint);
        String manual = e.settings.server();
        setSub(addressLine, manual.length() > 0 ? "Manual · " + manual
                : "Auto-detect · finds Ollama on this Wi-Fi network.");
        enable(autoBtn, manual.length() > 0 || s == Engine.State.OFFLINE);
        buttonText(scanBtn, e.isScanning() ? "Scanning…" : "Scan now");
        enable(scanBtn, !e.isScanning());
        setStatus(secConnection, s == Engine.State.ONLINE && !Double.isNaN(lat) ? "Online · " + Math.round(lat) + " ms"
                : st, c);
        renderFound();
    }

    /** Other AI servers the last full scan found, each one tap away. */
    private void renderFound() {
        foundBox.removeAllViews();
        List<ServerInfo> found = e.lastScan();
        ServerInfo cur = e.server();
        int others = 0;
        for (ServerInfo s : found) {
            if (cur == null || !(cur.host.equals(s.host) && cur.port == s.port)) others++;
        }
        if (others == 0) {
            foundBox.setVisibility(View.GONE);
            return;
        }
        TextView head = ui.label("Found on the last scan");
        head.setPadding(0, ui.dp(14), 0, ui.dp(4));
        foundBox.addView(head);
        for (final ServerInfo s : found) {
            boolean connected = cur != null && cur.host.equals(s.host) && cur.port == s.port;
            LinearLayout row = ui.hbox();
            row.setPadding(0, ui.dp(6), 0, ui.dp(6));
            LinearLayout text = ui.vbox();
            text.addView(ui.readout(s.label(), 13.5f, t.ink));
            TextView v = ui.dim("Ollama " + (s.version.length() > 0 ? s.version : "?"), 12);
            v.setPadding(0, ui.dp(3), 0, 0);
            text.addView(v);
            row.addView(text, Ui.weight(1));
            if (connected) {
                row.addView(ui.chip("Connected", t.ok));
            } else {
                row.addView(smallButton("Use", 0, Ui.SECONDARY, "Use " + s.label(), new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        e.setServer(s.label());
                        saved();
                    }
                }));
            }
            foundBox.addView(row, Ui.fillW());
        }
        foundBox.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // 3 · AI model
    // ------------------------------------------------------------------

    private void buildModel() {
        secModel = section("AI model", "AI model");
        LinearLayout body = secModel.body;
        heading(body, "Default model", "Every reply goes here unless Deep mode takes over.");
        modelSelect = select(body, "Default model", new Runnable() {
            @Override
            public void run() {
                pickModel(false);
            }
        });
        heading(body, "Deep-mode model", "Handles Deep mode — and hard prompts in Auto.");
        deepSelect = select(body, "Deep-mode model", new Runnable() {
            @Override
            public void run() {
                pickModel(true);
            }
        });
        heading(body, "Response mode", null);
        modeSeg = new SettingsWidgets.Segmented(ui, "Mode", new String[]{"Auto", "Fast", "Deep"});
        modeSeg.setOnSelect(new SettingsWidgets.Segmented.OnSelect() {
            @Override
            public void selected(int index) {
                e.setMode(MODES[index]);
                saved();
                refreshModel();
            }
        });
        body.addView(modeSeg, Ui.fillW());
        modeNote = ui.dim("", 12);
        modeNote.setPadding(0, ui.dp(8), 0, ui.dp(8));
        body.addView(modeNote, Ui.fillW());
        sep(body);
        keepLoaded = toggleRow(body, "Keep model loaded",
                "Holds the model in the PC's memory between messages. Off: it unloads after 5 minutes idle.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.settings.setKeepLoaded(on);
                        e.log("info", "Keep model loaded · " + (on ? "ON" : "OFF"));
                    }
                }, null);
    }

    private void pickModel(final boolean deep) {
        List<ModelInfo> ms = e.models();
        if (e.state() != Engine.State.ONLINE || ms.isEmpty()) {
            ui.toast(e.state() == Engine.State.ONLINE ? "No models on the PC yet — pull one from the Models tab."
                    : "Connect to your AI to choose from its models.");
            return;
        }
        String cur = deep ? e.resolveInstalled(e.settings.deepModel()) : e.currentModel();
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        if (deep) {
            rows.add(new Ui.Row("Same as default", "Deep mode uses the default model with thinking on.",
                    cur == null, new Runnable() {
                @Override
                public void run() {
                    e.setDeepModel("");
                    saved();
                    refreshModel();
                }
            }, null));
        }
        for (final ModelInfo m : ms) {
            StringBuilder detail = new StringBuilder(m.describe());
            if (e.isLoaded(m.name)) detail.append(" · loaded");
            if (Boolean.TRUE.equals(e.supportsThinking(m.name))) detail.append(" · thinks");
            rows.add(new Ui.Row(m.name, detail.toString(), m.name.equals(cur), new Runnable() {
                @Override
                public void run() {
                    if (deep) e.setDeepModel(m.name);
                    else e.setModel(m.name);
                    saved();
                    refreshModel();
                }
            }, null));
        }
        ui.pick(deep ? "Deep-mode model" : "Default model", rows, "Models tab", new Runnable() {
            @Override
            public void run() {
                commitFields();
                a.select(MainActivity.TAB_MODELS, true);
            }
        });
    }

    private void refreshModel() {
        boolean online = e.state() == Engine.State.ONLINE;
        List<ModelInfo> ms = e.models();
        String cur = e.currentModel();
        if (cur.length() == 0) {
            modelSelect.value.setText(online ? "No models installed" : "Not chosen yet");
            modelSelect.value.setTextColor(t.faint);
        } else {
            modelSelect.value.setText(cur);
            modelSelect.value.setTextColor(t.ink);
        }
        modelSelect.detail.setText(detailFor(cur, online));
        modelSelect.detail.setTextColor(e.isLoaded(cur) ? t.ok : online ? t.faint : t.warn);
        String deepPref = e.settings.deepModel();
        String deep = e.resolveInstalled(deepPref);
        if (deepPref.length() == 0) {
            deepSelect.value.setText("Same as default");
            deepSelect.value.setTextColor(t.dim);
            deepSelect.detail.setText("");
        } else {
            deepSelect.value.setText(deep != null ? deep : deepPref);
            deepSelect.value.setTextColor(t.ink);
            String d = deep == null && online && !ms.isEmpty() ? "Not installed" : detailFor(deep != null ? deep : deepPref,
                    online);
            deepSelect.detail.setText(t.hud ? d.toUpperCase(Locale.US) : d);
            deepSelect.detail.setTextColor(deep == null && online && !ms.isEmpty() ? t.warn
                    : e.isLoaded(deep == null ? "" : deep) ? t.ok : online ? t.faint : t.warn);
        }
        String mode = e.mode();
        int mi = Settings.MODE_FAST.equals(mode) ? 1 : Settings.MODE_DEEP.equals(mode) ? 2 : 0;
        modeSeg.setSelectedIndex(mi);
        boolean hasDeep = deep != null;
        if (mi == 1) {
            modeNote.setText("Default model with thinking off — the quickest replies.");
        } else if (mi == 2) {
            modeNote.setText(hasDeep ? "Every message goes to " + deep + " with thinking on."
                    : "Every message uses the default model with thinking on.");
        } else {
            modeNote.setText(hasDeep ? "Hard prompts (proofs, debugging, analysis) go to " + deep
                    + "; everything else stays fast." : "Routes hard prompts to the deep-mode model — choose one above.");
        }
        keepLoaded.setChecked(e.settings.keepLoaded(), false);
        String modeName = mi == 1 ? "Fast" : mi == 2 ? "Deep" : "Auto";
        if (!online) setStatus(secModel, modeName + " · offline", t.warn);
        else setStatus(secModel, modeName + " · " + ms.size() + (ms.size() == 1 ? " model" : " models"), t.faint);
    }

    private String detailFor(String model, boolean online) {
        if (!online) return t.hud ? "OFFLINE" : "Offline";
        if (model == null || model.length() == 0) return "";
        String d = e.isLoaded(model) ? "Loaded" : "";
        for (ModelInfo m : e.models()) {
            if (m.name.equals(model) && m.parameterSize.length() > 0) {
                d = d.length() > 0 ? d + " · " + m.parameterSize : m.parameterSize;
            }
        }
        return t.hud ? d.toUpperCase(Locale.US) : d;
    }

    // ------------------------------------------------------------------
    // 4 · Performance
    // ------------------------------------------------------------------

    private void buildPerformance() {
        secPerf = section("Performance", "Performance");
        LinearLayout body = secPerf.body;
        ctxReadout = ui.readout("", t.hud ? 13 : 13.5f, t.accent);
        ctxReadout.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        ctxReadout.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 0x33)),
                ui.rounded(t.input, t.hud ? t.edge : t.edge, 7), null));
        IconDrawable pen = new IconDrawable(IconDrawable.EDIT, t.faint, t.faint, ui.dp(13));
        pen.setBounds(0, 0, ui.dp(13), ui.dp(13));
        ctxReadout.setCompoundDrawables(null, null, pen, null);
        ctxReadout.setCompoundDrawablePadding(ui.dp(7));
        ctxReadout.setContentDescription("Custom context window");
        ctxReadout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                promptNumber("Context window", "Tokens, e.g. 12288 — 0 = auto", e.settings.numCtx(), new NumberResult() {
                    @Override
                    public void onNumber(int v) {
                        if (v != 0 && v < 512) {
                            ui.toast("Use at least 512 tokens (or 0 for auto).");
                            return;
                        }
                        setCtx(Math.min(v, 1048576));
                    }
                });
            }
        });
        Line ctx = line("Context window", "How much of the chat the model sees at once.", ctxReadout);
        ctx.row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        body.addView(ctx.row, Ui.fillW());
        ctxSeg = new SettingsWidgets.Segmented(ui, "Context window", CTX_LABELS);
        ctxSeg.setOnSelect(new SettingsWidgets.Segmented.OnSelect() {
            @Override
            public void selected(int index) {
                setCtx(CTX_PRESETS[index]);
            }
        });
        LinearLayout.LayoutParams slp = Ui.fillW();
        slp.bottomMargin = ui.dp(12);
        body.addView(ctxSeg, slp);
        sep(body);
        threads = new SettingsWidgets.Stepper(ui, "CPU threads", new Runnable() {
            @Override
            public void run() {
                promptNumber("CPU threads", "0 = let Ollama decide", e.settings.numThread(), new NumberResult() {
                    @Override
                    public void onNumber(int v) {
                        setThreads(Math.min(v, 256));
                    }
                });
            }
        }).range(0, 64, 1).format(new SettingsWidgets.Stepper.Format() {
            @Override
            public String format(int v) {
                return v == 0 ? (t.hud ? "AUTO" : "Auto") : String.valueOf(v);
            }
        });
        threads.setOnStep(new SettingsWidgets.Stepper.OnStep() {
            @Override
            public void stepped(int value) {
                setThreads(value);
            }
        });
        Line th = line("CPU threads", "Auto lets Ollama decide.", threads);
        body.addView(th.row, Ui.fillW());
        sep(body);
        TextView n = ui.dim("Changing either one reloads the model on the PC with your next message.", 12);
        note(body, n);
        loadedNote = ui.readout("", t.hud ? 10 : 11, t.faint);
        loadedNote.setPadding(ui.dp(23), ui.dp(8), 0, 0);
        loadedNote.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(loadedNote, Ui.fillW());
    }

    private void setCtx(int v) {
        e.settings.setNumCtx(v);
        e.log("info", "Context window · " + (v == 0 ? "auto" : grouped(v)));
        saved();
        refreshPerformance();
    }

    private void setThreads(int v) {
        e.settings.setNumThread(v);
        saved();
        refreshPerformance();
    }

    private void refreshPerformance() {
        int ctx = e.settings.numCtx();
        int idx = -1;
        for (int i = 0; i < CTX_PRESETS.length; i++) {
            if (CTX_PRESETS[i] == ctx) idx = i;
        }
        ctxSeg.setSelectedIndex(idx);
        ctxReadout.setText(ctx == 0 ? (t.hud ? "AUTO" : "Auto") : grouped(ctx));
        ctxReadout.setTextColor(ctx == 0 ? t.dim : t.accent);
        threads.bind(e.settings.numThread());
        String cur = e.currentModel();
        ModelInfo r = cur.length() > 0 ? e.runningInfo(cur) : null;
        String loaded;
        if (e.state() != Engine.State.ONLINE) {
            loaded = "Offline · applies when the AI reconnects";
        } else if (r != null) {
            loaded = "Loaded now · " + cur + (r.contextLength > 0 ? " · " + grouped(r.contextLength) + " ctx" : "");
        } else {
            loaded = cur.length() > 0 ? cur + " isn't loaded yet" : "No model loaded";
        }
        loadedNote.setText(t.hud ? loaded.toUpperCase(Locale.US) : loaded);
        String ctxS = ctx == 0 ? "auto" : ctx % 1024 == 0 ? (ctx / 1024) + "K" : grouped(ctx);
        int th = e.settings.numThread();
        setStatus(secPerf, "Ctx " + ctxS + " · Threads " + (th == 0 ? "auto" : String.valueOf(th)),
                ctx == 0 && th == 0 ? t.faint : (t.hud ? t.engaged : t.faint));
    }

    // ------------------------------------------------------------------
    // 5 · Generation
    // ------------------------------------------------------------------

    private void buildGeneration() {
        secGen = section("Generation", "Generation");
        LinearLayout body = secGen.body;
        TextView[] th = sliderHead(body, "Temperature", "Lower is focused and repeatable; higher is more creative.",
                new Runnable() {
                    @Override
                    public void run() {
                        e.settings.setTemperature(-1f);
                        refreshGeneration();
                    }
                });
        tempValue = th[0];
        tempReset = th[1];
        tempSlider = new SettingsWidgets.Slider(a, t, 0f, 2f, 0.05f, 8);
        tempSlider.setMarker(TEMP_DEFAULT);
        tempSlider.setContentDescription("Temperature");
        tempSlider.setOnValue(new SettingsWidgets.Slider.OnValue() {
            @Override
            public void changed(float value, boolean done) {
                e.settings.setTemperature(value);
                showGenValues();
                if (done) saved();
            }
        });
        body.addView(tempSlider, sliderLp());
        scaleRow(body, "0.0", "1.0", "2.0");
        sep(body);
        TextView[] ph = sliderHead(body, "Top-p", "Samples only from the most likely words that add up to p.",
                new Runnable() {
                    @Override
                    public void run() {
                        e.settings.setTopP(-1f);
                        refreshGeneration();
                    }
                });
        topPValue = ph[0];
        topPReset = ph[1];
        topPSlider = new SettingsWidgets.Slider(a, t, 0.05f, 1f, 0.05f, 19);
        topPSlider.setMarker(TOP_P_DEFAULT);
        topPSlider.setContentDescription("Top-p");
        topPSlider.setOnValue(new SettingsWidgets.Slider.OnValue() {
            @Override
            public void changed(float value, boolean done) {
                e.settings.setTopP(value);
                showGenValues();
                if (done) saved();
            }
        });
        body.addView(topPSlider, sliderLp());
        scaleRow(body, "0.05", null, "1.00");
        sep(body);
        maxTokens = new SettingsWidgets.Stepper(ui, "max reply tokens", new Runnable() {
            @Override
            public void run() {
                promptNumber("Max reply tokens", "0 = no limit", e.settings.maxTokens(), new NumberResult() {
                    @Override
                    public void onNumber(int v) {
                        setMaxTokens(Math.min(v, 131072));
                    }
                });
            }
        }).ladder(TOKEN_LADDER).format(new SettingsWidgets.Stepper.Format() {
            @Override
            public String format(int v) {
                return v == 0 ? (t.hud ? "NO LIMIT" : "No limit") : String.valueOf(v);
            }
        });
        maxTokens.setOnStep(new SettingsWidgets.Stepper.OnStep() {
            @Override
            public void stepped(int value) {
                setMaxTokens(value);
            }
        });
        Line mt = line("Max reply length", "In tokens. No limit lets the model decide.", maxTokens);
        body.addView(mt.row, Ui.fillW());
        sep(body);
        LinearLayout foot = ui.hbox();
        foot.setPadding(0, ui.dp(10), 0, 0);
        TextView fn = ui.dim("Sampling applies to the next message and never reloads the model.", 12);
        foot.addView(fn, Ui.weight(1));
        foot.addView(smallButton("Reset", IconDrawable.REFRESH, Ui.GHOST, "Reset generation", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.settings.setTemperature(-1f);
                e.settings.setTopP(-1f);
                e.settings.setMaxTokens(0);
                saved();
                refreshGeneration();
            }
        }));
        body.addView(foot, Ui.fillW());
    }

    private LinearLayout.LayoutParams sliderLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(40));
        lp.topMargin = ui.dp(6);
        lp.leftMargin = -ui.dp(8);
        lp.rightMargin = -ui.dp(8);
        return lp;
    }

    private void setMaxTokens(int v) {
        e.settings.setMaxTokens(v);
        saved();
        refreshGeneration();
    }

    private void refreshGeneration() {
        float temp = e.settings.temperature();
        tempSlider.bind(temp < 0 ? TEMP_DEFAULT : temp, temp < 0);
        float topP = e.settings.topP();
        topPSlider.bind(topP < 0 ? TOP_P_DEFAULT : topP, topP < 0);
        maxTokens.bind(e.settings.maxTokens());
        showGenValues();
    }

    /** Live readouts + cap summary for the sampling settings. */
    private void showGenValues() {
        float temp = e.settings.temperature(), topP = e.settings.topP();
        String def = t.hud ? "DEFAULT" : "Default";
        tempValue.setText(temp < 0 ? def : String.format(Locale.US, "%.2f", temp));
        tempValue.setTextColor(temp < 0 ? t.faint : t.accent);
        tempReset.setVisibility(temp < 0 ? View.GONE : View.VISIBLE);
        topPValue.setText(topP < 0 ? def : String.format(Locale.US, "%.2f", topP));
        topPValue.setTextColor(topP < 0 ? t.faint : t.accent);
        topPReset.setVisibility(topP < 0 ? View.GONE : View.VISIBLE);
        boolean custom = temp >= 0 || topP >= 0 || e.settings.maxTokens() > 0;
        if (!custom) {
            setStatus(secGen, "Model defaults", t.faint);
        } else {
            StringBuilder sb = new StringBuilder();
            if (temp >= 0) sb.append("Temp ").append(String.format(Locale.US, "%.2f", temp));
            if (topP >= 0) {
                sb.append(sb.length() > 0 ? " · " : "").append("P ").append(String.format(Locale.US, "%.2f", topP));
            }
            if (e.settings.maxTokens() > 0) {
                sb.append(sb.length() > 0 ? " · " : "").append("Max ").append(e.settings.maxTokens());
            }
            setStatus(secGen, sb.toString(), t.hud ? t.engaged : t.faint);
        }
    }

    // ------------------------------------------------------------------
    // 6 · Persona
    // ------------------------------------------------------------------

    private void buildPersona() {
        secPersona = section("Persona", "Persona");
        LinearLayout body = secPersona.body;
        heading(body, "System prompt", "Standing instructions sent with every message.");
        promptField = ui.field("", "e.g. You are OMNI, my concise, dry-witted assistant. Prefer short answers.",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        promptField.setGravity(Gravity.TOP | Gravity.START);
        promptField.setMinLines(4);
        promptField.setMaxLines(10);
        promptField.setVerticalScrollBarEnabled(true);
        promptField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        promptField.setLineSpacing(0, 1.2f);
        promptField.setContentDescription("System prompt");
        promptField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updatePromptState();
            }
        });
        promptField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                if (!has) commitPrompt();
            }
        });
        body.addView(promptField, Ui.fillW());
        LinearLayout actions = ui.hbox();
        actions.setPadding(0, ui.dp(8), 0, ui.dp(8));
        promptCount = ui.readout("", t.hud ? 10 : 11, t.faint);
        actions.addView(promptCount, Ui.weight(1));
        promptClear = smallButton("Clear", 0, Ui.GHOST, "Clear system prompt", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptField.setText("");
                commitPrompt();
            }
        });
        actions.addView(promptClear);
        actions.addView(ui.space(6, 1));
        promptSave = smallButton("Save", IconDrawable.CHECK, Ui.PRIMARY, "Save system prompt", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commitPrompt();
                promptField.clearFocus();
                column.requestFocus();
                hideKeyboard(promptField);
            }
        });
        actions.addView(promptSave);
        body.addView(actions, Ui.fillW());
        sep(body);
        heading(body, "Memory", "Facts OMNI always knows about you, sent with every message.");
        factsBox = ui.vbox();
        body.addView(factsBox, Ui.fillW());
        LinearLayout add = ui.hbox();
        add.setPadding(0, ui.dp(8), 0, 0);
        add.addView(bigButton("Add fact", IconDrawable.PLUS, Ui.SECONDARY, "Add fact", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addFact();
            }
        }));
        body.addView(add, Ui.fillW());
    }

    private void updatePromptState() {
        String cur = promptField.getText().toString();
        boolean dirty = !cur.trim().equals(e.settings.systemPrompt().trim());
        int n = cur.trim().length();
        String count = n == 0 ? "Not set" : grouped(n) + (n == 1 ? " char" : " chars");
        if (dirty) count += " · unsaved";
        promptCount.setText(t.hud ? count.toUpperCase(Locale.US) : count);
        promptCount.setTextColor(dirty ? t.warn : t.faint);
        // Save only shows while there's something to save; Clear only when there's text.
        promptSave.setVisibility(dirty ? View.VISIBLE : View.GONE);
        promptClear.setVisibility(cur.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void commitPrompt() {
        if (promptField == null) return;
        String v = promptField.getText().toString().trim();
        if (!v.equals(e.settings.systemPrompt().trim())) {
            e.settings.setSystemPrompt(v);
            e.log("info", v.length() == 0 ? "System prompt cleared" : "System prompt updated");
            saved();
        }
        updatePromptState();
        refreshPersonaStatus();
    }

    private void addFact() {
        ui.prompt("Add a fact", "e.g. My name is Sam and I run Windows 11", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, new Ui.TextResult() {
                    @Override
                    public void onText(String text) {
                        String f = text.trim();
                        if (f.length() == 0) return;
                        List<String> facts = e.settings.facts();
                        facts.add(f);
                        e.settings.setFacts(facts);
                        e.log("info", "Memory · fact added");
                        saved();
                        refreshFacts();
                    }
                });
    }

    private void editFact(final int index) {
        List<String> facts = e.settings.facts();
        if (index >= facts.size()) return;
        ui.prompt("Edit fact", "Fact", facts.get(index), InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, new Ui.TextResult() {
            @Override
            public void onText(String text) {
                List<String> f = e.settings.facts();
                if (index >= f.size()) return;
                if (text.trim().length() == 0) f.remove(index);
                else f.set(index, text.trim());
                e.settings.setFacts(f);
                saved();
                refreshFacts();
            }
        });
    }

    private void removeFact(int index) {
        List<String> f = e.settings.facts();
        if (index >= f.size()) return;
        f.remove(index);
        e.settings.setFacts(f);
        e.log("info", "Memory · fact removed");
        saved();
        refreshFacts();
    }

    private void refreshFacts() {
        List<String> facts = e.settings.facts();
        if (facts.equals(shownFacts)) return;
        shownFacts = facts;
        factsBox.removeAllViews();
        if (facts.isEmpty()) {
            TextView none = ui.dim("Nothing remembered yet. Add your name, projects or preferences and OMNI "
                    + "will keep them in mind.", 12.5f);
            none.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(11));
            none.setBackground(ui.rounded(0, t.hud ? t.hair : t.edge, 8));
            factsBox.addView(none, Ui.fillW());
        }
        for (int i = 0; i < facts.size(); i++) {
            final int idx = i;
            LinearLayout row = ui.hbox();
            row.setPadding(0, ui.dp(2), 0, ui.dp(2));
            TextView num = ui.readout(String.format(Locale.US, "%02d", i + 1), 11, t.hud ? t.accent : t.faint);
            num.setPadding(0, 0, ui.dp(10), 0);
            row.addView(num);
            TextView text = ui.text(facts.get(i), 14, t.ink, t.body);
            text.setLineSpacing(0, 1.2f);
            text.setPadding(0, ui.dp(8), ui.dp(4), ui.dp(8));
            text.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editFact(idx);
                }
            });
            row.addView(text, Ui.weight(1));
            ImageView x = ui.iconButton(IconDrawable.CLOSE, "Forget fact " + (i + 1), t.faint, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeFact(idx);
                }
            });
            x.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
            row.addView(x);
            if (i > 0) {
                View rule = new View(a);
                rule.setBackgroundColor(t.hud ? t.hairSoft : t.isDark ? t.hairSoft : t.hair);
                factsBox.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(1, ui.dp(0.7f))));
            }
            factsBox.addView(row, Ui.fillW());
        }
        refreshPersonaStatus();
    }

    private void refreshPersonaStatus() {
        int n = e.settings.facts().size();
        boolean prompt = e.settings.systemPrompt().trim().length() > 0;
        String s = (prompt ? "Prompt set" : "No prompt") + " · " + n + (n == 1 ? " fact" : " facts");
        setStatus(secPersona, s, prompt || n > 0 ? (t.hud ? t.accent : t.faint) : t.faint);
    }

    // ------------------------------------------------------------------
    // 7 · Voice
    // ------------------------------------------------------------------

    private void buildVoice() {
        secVoice = section("Voice", "Voice");
        LinearLayout body = secVoice.body;
        readAloud = toggleRow(body, "Read replies aloud", "Speaks each reply as it streams, using Android's voice.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.setReadAloud(on);
                        refreshVoice();
                    }
                }, null);
        sep(body);
        TextView[] rh = sliderHead(body, "Speech rate", null, null);
        rateValue = rh[0];
        rateSlider = new SettingsWidgets.Slider(a, t, 0.5f, 2f, 0.05f, 6);
        rateSlider.setMarker(1f);
        rateSlider.setContentDescription("Speech rate");
        rateSlider.setOnValue(new SettingsWidgets.Slider.OnValue() {
            @Override
            public void changed(float value, boolean done) {
                e.setSpeechRate(value);
                rateValue.setText(String.format(Locale.US, "%.2f×", value));
                if (done) {
                    saved();
                    refreshVoice();
                }
            }
        });
        body.addView(rateSlider, sliderLp());
        scaleRow(body, "0.5×", null, "2.0×");
        LinearLayout row = ui.hbox();
        row.setPadding(0, ui.dp(10), 0, 0);
        row.addView(bigButton("Test voice", IconDrawable.PLAY, Ui.SECONDARY, "Test voice", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.speakNow(TEST_LINE);
            }
        }));
        TextView hint = ui.dim("Plays a line at the current rate.", 12);
        hint.setPadding(ui.dp(12), 0, 0, 0);
        row.addView(hint, Ui.weight(1));
        body.addView(row, Ui.fillW());
    }

    private void refreshVoice() {
        boolean on = e.settings.readAloud();
        readAloud.setChecked(on, false);
        float rate = e.settings.speechRate();
        rateSlider.bind(rate, false);
        rateValue.setText(String.format(Locale.US, "%.2f×", rate));
        setStatus(secVoice, (on ? "Read-aloud on" : "Read-aloud off") + " · " + String.format(Locale.US, "%.2f×", rate),
                on ? (t.hud ? t.engaged : t.ok) : t.faint);
    }

    // ------------------------------------------------------------------
    // 8 · PC bridge
    // ------------------------------------------------------------------

    private void buildBridge() {
        secBridge = section("PC bridge", "PC bridge");
        LinearLayout body = secBridge.body;
        LinearLayout status = ui.hbox();
        status.setPadding(0, ui.dp(8), 0, ui.dp(10));
        bridgeDot = new Widgets.StatusDot(a);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16));
        dlp.rightMargin = ui.dp(8);
        status.addView(bridgeDot, dlp);
        LinearLayout st = ui.vbox();
        bridgeState = ui.text("", t.hud ? 11 : 14.5f, t.ink, t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) bridgeState.setLetterSpacing(0.12f);
        st.addView(bridgeState);
        bridgeDetail = ui.dim("", 12.5f);
        bridgeDetail.setPadding(0, ui.dp(3), 0, 0);
        st.addView(bridgeDetail);
        status.addView(st, Ui.weight(1));
        status.addView(smallButton("Check", IconDrawable.REFRESH, Ui.SECONDARY, "Check bridge",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        checkBridge(true);
                    }
                }));
        body.addView(status, Ui.fillW());
        sep(body);

        portField = ui.numberField("", String.valueOf(com.omnideck.mobile.core.BridgeClient.DEFAULT_PORT));
        portField.setTypeface(t.mono);
        portField.setContentDescription("Bridge port");
        portField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        portField.setSingleLine(true);
        portField.setMinWidth(ui.dp(92));
        portField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                commitPort();
                portField.clearFocus();
                column.requestFocus();
                hideKeyboard(v);
                return true;
            }
        });
        portField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                if (!has) commitPort();
            }
        });
        Line port = line("Port", "LaunchBridge's port on the PC.", portField);
        body.addView(port.row, Ui.fillW());
        sep(body);
        heading(body, "Pairing token", "Lets this phone control the PC. Pair now fetches one for you.");
        LinearLayout tokenBox = ui.hbox();
        tokenBox.setBackground(ui.rounded(t.input, t.edge, 8));
        tokenField = new EditText(a);
        tokenField.setBackground(null);
        tokenField.setTextColor(t.ink);
        tokenField.setHintTextColor(t.faint);
        tokenField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tokenField.setHint("Not paired");
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tokenField.setTypeface(t.mono);
        tokenField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        tokenField.setPadding(ui.dp(12), ui.dp(10), ui.dp(4), ui.dp(10));
        tokenField.setContentDescription("Bridge token");
        tokenField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                if (!has) commitToken();
            }
        });
        tokenField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                commitToken();
                tokenField.clearFocus();
                column.requestFocus();
                hideKeyboard(v);
                return true;
            }
        });
        tokenBox.addView(tokenField, Ui.weight(1));
        eyeBtn = new ImageView(a);
        eyeBtn.setScaleType(ImageView.ScaleType.CENTER);
        TypedValue tv = new TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) eyeBtn.setBackground(a.getDrawable(tv.resourceId));
        eyeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                setTokenVisible(!tokenVisible);
            }
        });
        tokenBox.addView(eyeBtn, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        ImageView paste = ui.iconButton(IconDrawable.CLIPBOARD, "Paste token", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteToken();
            }
        });
        tokenBox.addView(paste, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        body.addView(tokenBox, Ui.fillW());
        setTokenVisible(false);

        LinearLayout btns = ui.hbox();
        btns.setPadding(0, ui.dp(12), 0, 0);
        pairBtn = bigButton("Pair now", IconDrawable.LINK, Ui.PRIMARY, "Pair now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pair();
            }
        });
        btns.addView(pairBtn);
        btns.addView(ui.space(8, 1));
        forgetBtn = bigButton("Forget pairing", 0, Ui.DANGER, "Forget pairing", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forgetPairing();
            }
        });
        btns.addView(forgetBtn);
        body.addView(btns, Ui.fillW());
        pairStatus = ui.dim("", 12.5f);
        pairStatus.setPadding(0, ui.dp(10), 0, 0);
        pairStatus.setVisibility(View.GONE);
        body.addView(pairStatus, Ui.fillW());
    }

    private void setTokenVisible(boolean on) {
        tokenVisible = on;
        int sel = tokenField.getSelectionEnd();
        tokenField.setTransformationMethod(on ? null : PasswordTransformationMethod.getInstance());
        tokenField.setTypeface(t.mono);
        if (sel >= 0) tokenField.setSelection(Math.min(sel, tokenField.getText().length()));
        eyeBtn.setImageDrawable(new SettingsWidgets.Glyph(on ? SettingsWidgets.Glyph.EYE_OFF : SettingsWidgets.Glyph.EYE,
                t.dim, ui.dp(20)));
        eyeBtn.setContentDescription(on ? "Hide token" : "Show token");
    }

    private void pasteToken() {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence s = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(a) : null;
        if (s == null || s.toString().trim().length() == 0) {
            ui.toast("The clipboard is empty.");
            return;
        }
        tokenField.setText(s.toString().trim());
        commitToken();
        ui.toast("Token pasted");
    }

    private void commitPort() {
        String raw = portField.getText().toString().trim();
        int cur = e.settings.bridgePort();
        int v;
        try {
            v = raw.length() == 0 ? com.omnideck.mobile.core.BridgeClient.DEFAULT_PORT : Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            v = -1;
        }
        if (v <= 0 || v >= 65536) {
            ui.toast("Ports run from 1 to 65535.");
            portField.setText(String.valueOf(cur));
            return;
        }
        if (v != cur) {
            e.settings.setBridgePort(v);
            e.log("info", "PC bridge port · " + v);
            saved();
            checkBridge(false);
        }
        portField.setText(String.valueOf(v));
        refreshBridge();
    }

    private void commitToken() {
        String v = tokenField.getText().toString().trim();
        if (!v.equals(e.settings.bridgeToken())) {
            e.settings.setBridgeToken(v);
            saved();
            refreshBridge();
        }
    }

    private void pair() {
        if (pairing) return;
        commitPort();
        pairing = true;
        enable(pairBtn, false);
        showPairStatus("Pairing with LaunchBridge…", t.dim);
        e.bridgePair(new Engine.Callback<String>() {
            @Override
            public void done(String token, String error) {
                pairing = false;
                if (error != null) {
                    showPairStatus("Pairing failed — " + error, t.danger);
                } else {
                    tokenField.setText(token);
                    showPairStatus("Paired. The PC tab and /open, /vol, /sys and /shot now control your PC.", t.ok);
                    saved();
                }
                refreshBridge();
            }
        });
    }

    private void forgetPairing() {
        ui.confirm("Forget pairing?", "This phone stops controlling the PC until you pair again.", "Forget",
                new Runnable() {
                    @Override
                    public void run() {
                        e.settings.setBridgeToken("");
                        tokenField.setText("");
                        e.log("warn", "PC bridge pairing removed");
                        showPairStatus("Pairing removed.", t.faint);
                        saved();
                        refreshBridge();
                    }
                });
    }

    private void showPairStatus(String s, int color) {
        pairStatus.setText(s);
        pairStatus.setTextColor(color);
        pairStatus.setVisibility(View.VISIBLE);
    }

    /** Asks LaunchBridge if it's there (no auth needed) and updates the status line. */
    private void checkBridge(final boolean fromUser) {
        String host = bridgeHost();
        if (host.length() == 0) {
            if (fromUser) showPairStatus("Connect to your AI first — the bridge runs on the same PC.", t.warn);
            return;
        }
        if (fromUser) showPairStatus("Checking " + host + ":" + e.settings.bridgePort() + "…", t.dim);
        e.bridgeHealth(new Engine.Callback<org.json.JSONObject>() {
            @Override
            public void done(org.json.JSONObject v, String error) {
                if (fromUser) {
                    if (error != null) {
                        showPairStatus("No answer — " + error, t.danger);
                    } else {
                        int apps = v.optInt("apps_indexed", -1);
                        showPairStatus("LaunchBridge is online" + (apps >= 0 ? " · " + apps + " apps indexed" : "")
                                + (e.bridgePaired() ? "." : ". Pair to control the PC."), t.ok);
                    }
                }
                refreshBridge();
            }
        });
    }

    private String bridgeHost() {
        ServerInfo srv = e.server();
        return srv != null ? srv.host : e.settings.lastHost();
    }

    private void refreshBridge() {
        boolean paired = e.bridgePaired();
        Boolean online = e.bridgeOnline();
        String s;
        int c;
        if (paired && Boolean.TRUE.equals(online)) {
            s = "Paired · online";
            c = t.ok;
        } else if (paired && Boolean.FALSE.equals(online)) {
            s = "Paired · unreachable";
            c = t.warn;
        } else if (paired) {
            s = "Paired";
            c = t.ok;
        } else if (Boolean.TRUE.equals(online)) {
            s = "Online · not paired";
            c = t.warn;
        } else if (Boolean.FALSE.equals(online)) {
            s = "Unreachable";
            c = t.danger;
        } else {
            s = "Not paired";
            c = t.faint;
        }
        bridgeState.setText(t.hud ? s.toUpperCase(Locale.US) : s);
        bridgeState.setTextColor(c);
        bridgeDot.setColor(c);
        bridgeDot.setPulsing(isShown() && paired && Boolean.TRUE.equals(online) && !e.settings.reduceMotion());
        String host = bridgeHost();
        bridgeDetail.setText(host.length() > 0 ? "LaunchBridge at " + host + ":" + e.settings.bridgePort()
                : "Runs on the same PC as your AI — connect to it first.");
        if (!portField.hasFocus()) portField.setText(String.valueOf(e.settings.bridgePort()));
        if (!tokenField.hasFocus() && !tokenField.getText().toString().equals(e.settings.bridgeToken())) {
            tokenField.setText(e.settings.bridgeToken());
        }
        enable(pairBtn, !pairing);
        buttonText(pairBtn, pairing ? "Pairing…" : paired ? "Pair again" : "Pair now");
        enable(forgetBtn, paired);
        setStatus(secBridge, paired ? "Paired" : "Not paired", paired ? t.ok : t.faint);
    }

    // ------------------------------------------------------------------
    // 9 · Privacy & data
    // ------------------------------------------------------------------

    private void buildPrivacy() {
        secPrivacy = section("Privacy & data", "Privacy");
        LinearLayout body = secPrivacy.body;
        incognito = toggleRow(body, "Incognito", "New chats and replies aren't saved on this phone while it's on.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.settings.setIncognito(on);
                        if (!on) e.save();
                        e.log(on ? "warn" : "info", "Incognito · " + (on ? "ON" : "OFF"));
                        refreshPrivacy();
                    }
                }, null);
        sep(body);
        navRow(body, "Export current chat", "Share this conversation as Markdown.", IconDrawable.SHARE, new Runnable() {
            @Override
            public void run() {
                a.commander().exportChat();
            }
        });
        sep(body);
        historyLine = line("Saved chats", "Counting…", null);
        body.addView(historyLine.row, Ui.fillW());
        clearBtn = bigButton("Clear all chat history", IconDrawable.TRASH, Ui.DANGER, "Clear all chat history",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmClearHistory();
                    }
                });
        body.addView(clearBtn, Ui.fillW());
    }

    private void confirmClearHistory() {
        String what = savedChats > 0 ? "all " + savedChats + (savedChats == 1 ? " saved chat" : " saved chats")
                : "every saved chat";
        ui.confirm("Clear all chat history?", "Deletes " + what + " on this phone, including the open one. "
                + "This can't be undone.", "Delete all", new Runnable() {
            @Override
            public void run() {
                clearHistory();
            }
        });
    }

    private void clearHistory() {
        e.listChats(new Engine.Callback<List<ConversationStore.Entry>>() {
            @Override
            public void done(List<ConversationStore.Entry> entries, String error) {
                int n = entries == null ? 0 : entries.size();
                if (entries != null) {
                    for (ConversationStore.Entry en : entries) e.deleteChat(en.id);
                }
                e.newChat();
                e.log("warn", "Chat history cleared · " + n + (n == 1 ? " chat" : " chats"));
                ui.toast(n == 0 ? "No saved chats to clear." : "Deleted " + n + (n == 1 ? " chat." : " chats."));
                savedChats = 0;
                saved();
                refreshPrivacy();
                countChats(); // queued after the deletes on the same disk thread
            }
        });
    }

    /** Counts saved chats off the main thread. */
    private void countChats() {
        e.listChats(new Engine.Callback<List<ConversationStore.Entry>>() {
            @Override
            public void done(List<ConversationStore.Entry> entries, String error) {
                savedChats = entries == null ? 0 : entries.size();
                int msgs = 0;
                if (entries != null) {
                    for (ConversationStore.Entry en : entries) msgs += en.count;
                }
                if (savedChats == 0) {
                    setSub(historyLine, "None on this phone yet.");
                } else {
                    setSub(historyLine, savedChats + (savedChats == 1 ? " chat · " : " chats · ") + grouped(msgs)
                            + (msgs == 1 ? " message" : " messages") + " stored on this phone.");
                }
                refreshPrivacy();
            }
        });
    }

    private void refreshPrivacy() {
        boolean inc = e.settings.incognito();
        incognito.setChecked(inc, false);
        enable(clearBtn, savedChats != 0);
        setStatus(secPrivacy, inc ? "Incognito" : "Saving chats", inc ? t.engaged : t.faint);
    }

    // ------------------------------------------------------------------
    // 10 · About
    // ------------------------------------------------------------------

    private void buildAbout() {
        secAbout = section("About", "About");
        LinearLayout body = secAbout.body;
        LinearLayout id = ui.hbox();
        id.setPadding(0, ui.dp(10), 0, ui.dp(4));
        ImageView logo = new ImageView(a);
        logo.setImageDrawable(new IconDrawable(IconDrawable.LOGO, t.accent, t.hud ? t.inkStrong : t.accent2, ui.dp(38)));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38));
        llp.rightMargin = ui.dp(12);
        id.addView(logo, llp);
        LinearLayout names = ui.vbox();
        TextView name = ui.text(t.hud ? "OMNI-DECK MOBILE" : "OMNI-DECK Mobile", t.hud ? 13 : 16, t.inkStrong, t.display);
        name.setLetterSpacing(t.hud ? 0.12f : -0.01f);
        names.addView(name);
        TextView tag = ui.dim("Command center for your personal AI and PC.", 12.5f);
        tag.setPadding(0, ui.dp(4), 0, 0);
        names.addView(tag);
        id.addView(names, Ui.weight(1));
        body.addView(id, Ui.fillW());
        aboutGrid = readoutGrid(body, new String[]{"App", "Ollama", "Android", "AI link"});
        View gap = ui.space(1, 6);
        body.addView(gap);
        navRow(body, "Command reference", "Every slash command, listed in Comms.", IconDrawable.TERMINAL, new Runnable() {
            @Override
            public void run() {
                runInComms("/help");
            }
        });
        sep(body);
        navRow(body, "Diagnostics", "Connection report: network, address, bridge.", IconDrawable.ACTIVITY,
                new Runnable() {
                    @Override
                    public void run() {
                        runInComms("/debug");
                    }
                });
    }

    /** Closes settings and runs a slash command in the Comms channel. */
    private void runInComms(String cmd) {
        commitFields();
        a.closeSettings();
        a.select(MainActivity.TAB_COMMS, true);
        a.commander().run(cmd);
    }

    private void refreshAbout() {
        String v = a.appVersion();
        aboutGrid[0].setText("v" + v);
        ServerInfo srv = e.server();
        boolean on = e.state() == Engine.State.ONLINE && srv != null;
        aboutGrid[1].setText(on && srv.version.length() > 0 ? srv.version : "—");
        aboutGrid[1].setTextColor(on ? t.ink : t.faint);
        aboutGrid[2].setText(Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT);
        aboutGrid[3].setText(on ? srv.label() : "Offline");
        aboutGrid[3].setTextColor(on ? t.ink : t.faint);
        setStatus(secAbout, "Build " + v, t.faint);
    }

    // ------------------------------------------------------------------
    // Refresh / lifecycle
    // ------------------------------------------------------------------

    /** Reads every control back from the live settings and engine state. */
    private void refresh() {
        refreshAppearance();
        refreshConnection();
        refreshModel();
        refreshPerformance();
        refreshGeneration();
        if (!promptField.hasFocus()) {
            String sp = e.settings.systemPrompt();
            if (!sp.equals(promptField.getText().toString())) promptField.setText(sp);
        }
        updatePromptState();
        refreshFacts();
        refreshPersonaStatus();
        refreshVoice();
        refreshBridge();
        refreshPrivacy();
        refreshAbout();
    }

    /** Saves text fields that save on focus loss (the screen is closing or the activity is going away). */
    private void commitFields() {
        if (promptField == null) return;
        commitPrompt();
        String port = portField.getText().toString().trim();
        if (!port.equals(String.valueOf(e.settings.bridgePort()))) commitPort();
        commitToken();
    }

    @Override
    protected void onShow() {
        shownFacts = null;
        refresh();
        countChats();
        if (bridgeHost().length() > 0) checkBridge(false);
    }

    @Override
    protected void onHide() {
        commitFields();
        View f = a.getCurrentFocus();
        if (f != null) hideKeyboard(f);
        column.requestFocus();
        stopGlides();
        linkDot.setPulsing(false);
        bridgeDot.setPulsing(false);
        savedMark.removeCallbacks(savedReset);
        savedReset.run();
    }

    @Override
    public void onActivityStop() {
        commitFields();
        if (linkDot != null) {
            stopGlides();
            linkDot.setPulsing(false);
            bridgeDot.setPulsing(false);
        }
    }

    @Override
    public void onActivityStart() {
        if (isShown()) refresh();
    }

    @Override
    public void onStateChanged() {
        if (!isBuilt()) return;
        refreshConnection();
        refreshModel();
        refreshPerformance();
        refreshVoice();
        refreshBridge();
        refreshAbout();
    }

    @Override
    public void onTelemetry() {
        if (isShown()) refreshConnection();
    }

    @Override
    public boolean onBack() {
        return false;
    }

    // ------------------------------------------------------------------
    // Number prompt
    // ------------------------------------------------------------------

    private interface NumberResult {
        void onNumber(int v);
    }

    private void promptNumber(String title, String hint, int value, final NumberResult r) {
        ui.prompt(title, hint, value > 0 ? String.valueOf(value) : "", InputType.TYPE_CLASS_NUMBER, new Ui.TextResult() {
            @Override
            public void onText(String text) {
                String s = text.trim().replace(",", "");
                if (s.length() == 0) s = "0";
                try {
                    int v = Integer.parseInt(s);
                    if (v < 0) throw new NumberFormatException();
                    r.onNumber(v);
                } catch (NumberFormatException ex) {
                    ui.toast("Enter a whole number.");
                }
            }
        });
    }
}
