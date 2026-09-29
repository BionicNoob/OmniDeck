package com.omnideck.mobile.screens;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.MainActivity;
import com.omnideck.mobile.SetupGuide;
import com.omnideck.mobile.Settings;
import com.omnideck.mobile.core.BridgeClient;
import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.core.HostPort;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.ServerInfo;
import com.omnideck.mobile.core.Telemetry;
import com.omnideck.mobile.core.WakeOnLan;
import com.omnideck.mobile.ui.Backdrop;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.SettingsKit;
import com.omnideck.mobile.ui.SettingsWidgets;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SETTINGS — the full-screen configuration deck behind the top-bar gear:
 * appearance, the link to the AI, models and routing, runner performance,
 * sampling, persona and memory, voice, notifications, the PC bridge (with
 * Wake-on-LAN), what the AI may do on the PC, privacy and about.
 * <p>
 * Every control saves the moment it changes (text fields on Done, on focus
 * loss and when the screen closes — only what the user actually edited) and
 * reads the live value back whenever the screen is shown. A sticky header
 * carries a section index that tracks the scroll position and jumps to a
 * section on tap; each card's cap shows a one-glance summary of its state.
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
    /** CPU threads: the stepper's top and the most a typed-in value may ask for. */
    static final int MAX_THREADS = 256;
    /** After Test voice, when to look again whether the phone could speak (the engine starts async). */
    static final int[] VOICE_RECHECK_MS = {1500, 4000};
    /** Notice key: the line reports invalid input (dropped once the input is fixed). */
    private static final String INVALID = "invalid";

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

    private final SettingsKit kit;
    /** True while {@link #refresh()} reads everything back (fields drop edits that couldn't be saved). */
    private boolean fullRefresh;
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
    private SettingsKit.Line hudLine;
    private Section secAppearance;

    // Connection
    private Section secConnection;
    private Widgets.StatusDot linkDot;
    private TextView linkState, linkDetail;
    private TextView[] linkGrid;
    private SettingsKit.Line addressLine;
    private TextView autoBtn, scanBtn;
    private LinearLayout foundBox;
    private SettingsWidgets.SecretField apiKeyBox;
    private SettingsKit.Notice apiKeyNotice;

    // AI model
    private Section secModel;
    private SettingsKit.Select modelSelect, deepSelect;
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
    private SettingsWidgets.EditTracker promptEdits;
    private TextView promptCount, promptSave, promptClear;
    private LinearLayout factsBox;
    private List<String> shownFacts;
    private Widgets.Toggle assistantContext;

    // Voice
    private Section secVoice;
    private Widgets.Toggle readAloud, handsFree;
    private SettingsWidgets.Slider rateSlider;
    private TextView rateValue;
    private TextView voiceHint;
    private SettingsKit.Notice voiceNotice;
    private View voiceFix;
    private Boolean voiceShownAvailable;
    private final Runnable voiceRecheck = new Runnable() {
        @Override
        public void run() {
            if (isShown()) refreshVoice();
        }
    };

    // Notifications
    private Section secNotify;
    private Widgets.Toggle notifyToggle;
    private SettingsKit.Notice notifyNotice;
    private View notifyFix;

    // PC bridge
    private Section secBridge;
    private Widgets.StatusDot bridgeDot;
    private TextView bridgeState, bridgeDetail;
    private SettingsKit.Line hostLine;
    private EditText hostField, portField, macField;
    private SettingsWidgets.EditTracker hostEdits, portEdits, macEdits;
    private SettingsKit.Notice hostNotice, pairStatus, wolStatus;
    private SettingsWidgets.SecretField tokenBox;
    private TextView pairNowBtn, pairAgainBtn, forgetBtn, wakeBtn;
    private boolean pairing, checking, waking;

    // PC tools (what the AI may do on the PC)
    private Section secTools;
    private Widgets.Toggle aiTools, confirmActions;
    private LinearLayout readiness;
    private SettingsKit.Check toolModel, toolBridge;
    /** Models whose capabilities were asked for while this page showed (asked once each). */
    private final Set<String> toolsAsked = new HashSet<String>();
    /** What those answers said about tool calling (the Engine keeps its own copy per link). */
    private final Map<String, Boolean> toolSupport = new HashMap<String, Boolean>();

    // Privacy
    private Section secPrivacy;
    private Widgets.Toggle incognito;
    private SettingsKit.Line incognitoLine, historyLine;
    private TextView clearBtn;
    private int savedChats = -1;
    private boolean leavingIncognito;

    // About
    private Section secAbout;
    private TextView[] aboutGrid;

    public SettingsScreen(MainActivity a) {
        super(a);
        kit = new SettingsKit(ui, new Runnable() {
            @Override
            public void run() {
                saved();
            }
        });
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
        buildNotifications();
        buildBridge();
        buildTools();
        buildPrivacy();
        buildAbout();

        String creditLine = "OMNI-DECK Mobile · companion for OMNI-DECK";
        TextView credit = ui.text(t.hud ? creditLine.toUpperCase(Locale.US) : creditLine, t.hud ? 9 : 11.5f, t.dim,
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
        // Back from a system dialog (the notification permission) or another app: re-read what it changed.
        root.getViewTreeObserver().addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                if (hasFocus && isShown()) {
                    refreshNotifications();
                    refreshVoice();
                }
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
        savedMark.setTextColor(t.dim);
        savedMark.setCompoundDrawablePadding(ui.dp(5));
        setSavedIcon(t.dim);
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
            savedMark.setTextColor(t.dim);
            setSavedIcon(t.dim);
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
        TextView status = ui.readout("", t.hud ? 10 : 11, t.dim);
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

    /**
     * Opens the page on a section by its title (e.g. "Performance", "Connection"),
     * once it's laid out. Returns false when there's no such section.
     */
    public boolean showSection(String title) {
        view();
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).name.equalsIgnoreCase(title)) {
                final int index = i;
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        jumpTo(index);
                    }
                });
                return true;
            }
        }
        return false;
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

    /** A card's cap summary. Cyber sets it in caps, keeping unit symbols ("44 ms") lower-case. */
    private void setStatus(Section s, String text, int color) {
        s.status.setText(t.hud ? t.labelUnits(text) : text);
        s.status.setTextColor(color);
    }

    private static String grouped(int v) {
        return String.format(Locale.US, "%,d", v);
    }

    /** A sentence in the body face around an identifier set in mono, in its own case (host, model tag). */
    private CharSequence sentence(String before, String ident, String after) {
        SpannableStringBuilder sb = new SpannableStringBuilder(before);
        sb.append(ui.mono(ident));
        sb.append(after);
        return sb;
    }

    /** Mono readout words in the theme's label case around an identifier kept in its own case (model tags). */
    private CharSequence withIdent(String before, String ident, String after) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(t.hud ? t.labelUnits(before) : before);
        sb.append(ident);
        sb.append(t.hud ? t.labelUnits(after) : after);
        return sb;
    }

    /** An editable one-line field in mono (addresses, MACs) that commits on Done and on focus loss. */
    private EditText monoField(String hint, int inputType, String description, final Runnable commit) {
        final EditText f = ui.field("", hint, inputType);
        f.setTypeface(t.mono);
        f.setSingleLine(true);
        f.setImeOptions(EditorInfo.IME_ACTION_DONE);
        f.setContentDescription(description);
        f.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                commit.run();
                SettingsWidgets.parkFocus(f);
                return true;
            }
        });
        f.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                if (!has) commit.run();
            }
        });
        return f;
    }

    private void openSystemScreen(Intent i, String fallback) {
        try {
            a.startActivity(i);
        } catch (RuntimeException ex) {
            ui.toast(fallback);
        }
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
        kit.sep(body);
        reduceMotion = kit.toggleRow(body, "Reduce motion", "Stills the AI core, pulses, scan line and boot sequence.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.settings.setReduceMotion(on);
                        ui.reduceMotion = on;
                        a.updateScanLine();
                        a.onStateChanged(); // re-evaluates the status dots' pulse
                        refreshAppearance();
                    }
                }, null);
        kit.sep(body);
        SettingsKit.Line[] hl = new SettingsKit.Line[1];
        hudEffects = kit.toggleRow(body, "HUD effects", "", new Widgets.Toggle.OnChange() {
            @Override
            public void changed(boolean on) {
                e.settings.setHudEffects(on);
                a.updateScanLine();
                applyBackdrop();
                refreshAppearance();
            }
        }, hl);
        hudLine = hl[0];
        kit.sep(body);
        haptics = kit.toggleRow(body, "Haptics", "A light tick when you tap controls.", new Widgets.Toggle.OnChange() {
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
        SettingsKit.bind(reduceMotion, e.settings.reduceMotion());
        SettingsKit.bind(hudEffects, e.settings.hudEffects());
        SettingsKit.bind(haptics, e.settings.haptics());
        kit.setSub(hudLine, t.hud ? "Hairline grid, top bloom and the slow scan line."
                : "Grid, bloom and scan line · Cyber only.");
        hudLine.row.setAlpha(t.hud ? 1f : 0.6f);
        String name = pref.equals(Settings.THEME_SYSTEM) ? "System · " + t.name : THEME_NAMES[themeIndex(pref)];
        setStatus(secAppearance, name, t.dim);
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
        linkGrid = kit.readoutGrid(body, new String[]{"Host", "Ollama", "Latency", "Models"});
        body.addView(ui.space(1, 6));
        addressLine = kit.line("AI address", "", kit.smallButton("Change", IconDrawable.EDIT, Ui.SECONDARY,
                "Change address", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.promptServerAddress();
                    }
                }));
        body.addView(addressLine.row, Ui.fillW());
        LinearLayout btns = ui.hbox();
        btns.setPadding(0, ui.dp(2), 0, 0);
        autoBtn = kit.bigButton("Auto-detect", IconDrawable.WIFI, Ui.SECONDARY, "Auto-detect",
                new View.OnClickListener() {
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
        scanBtn = kit.bigButton("Scan now", IconDrawable.SCAN, Ui.SECONDARY, "Scan now", new View.OnClickListener() {
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
        TextView aiGuide = ui.button("PC setup guide", IconDrawable.DOC, Ui.GHOST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SetupGuide.show(a, SetupGuide.AI);
            }
        });
        aiGuide.setContentDescription("PC setup guide");
        body.addView(aiGuide, ui.margins(Ui.wrap(), 0, 6, 0, 0));
        foundBox = ui.vbox();
        foundBox.setVisibility(View.GONE);
        body.addView(foundBox, Ui.fillW());

        View rule = kit.sep(body);
        ((LinearLayout.LayoutParams) rule.getLayoutParams()).topMargin = ui.dp(14);
        kit.heading(body, "API key", "For Ollama behind a reverse proxy — https:// addresses work too. The key is "
                + "only ever sent to the address you typed in.");
        apiKeyBox = new SettingsWidgets.SecretField(ui, "API key", "Not set", true);
        apiKeyBox.field.setContentDescription("API key");
        apiKeyBox.setOnCommit(new SettingsWidgets.SecretField.OnCommit() {
            @Override
            public void commit() {
                commitApiKey();
            }
        });
        body.addView(apiKeyBox, Ui.fillW());
        apiKeyNotice = kit.notice(body);
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
        linkDetail.setText(kit.idents(e.stateDetail()));
        boolean on = s == Engine.State.ONLINE && srv != null;
        String last = e.settings.lastHost();
        kit.setReadout(linkGrid[0], on ? srv.label() : last.length() > 0 ? last + ":" + e.settings.lastPort() : "—",
                on);
        kit.setReadout(linkGrid[1], on && srv.version.length() > 0 ? srv.version : "—", on);
        double lat = e.telemetry.latencyMs.last();
        kit.setReadout(linkGrid[2], on && !Double.isNaN(lat) ? Math.round(lat) + " ms" : "—", on);
        int loaded = 0;
        for (ModelInfo m : e.models()) {
            if (e.isLoaded(m.name)) loaded++;
        }
        kit.setReadout(linkGrid[3], on ? e.models().size() + " · " + loaded + " loaded" : "—", on);
        String manual = e.settings.server();
        kit.setSub(addressLine, manual.length() > 0 ? sentence("Manual · ", manual, "")
                : "Auto-detect · finds Ollama on this Wi-Fi network.");
        SettingsKit.enable(autoBtn, manual.length() > 0 || s == Engine.State.OFFLINE);
        kit.relabel(scanBtn, e.isScanning() ? "Scanning…" : "Scan now");
        SettingsKit.enable(scanBtn, !e.isScanning());
        setStatus(secConnection, s == Engine.State.ONLINE && !Double.isNaN(lat) ? "Online · " + Math.round(lat) + " ms"
                : st, c);
        showSaved(apiKeyBox.field, apiKeyBox.edits, e.settings.apiKey(), null);
        if (e.settings.apiKey().length() > 0 && manual.length() == 0) {
            kit.show(apiKeyNotice, "Not in use: the key only goes to an address you typed in. Set the AI address "
                    + "above to your proxy.", t.warn);
        } else {
            kit.hide(apiKeyNotice);
        }
        renderFound();
    }

    private void commitApiKey() {
        SettingsWidgets.EditTracker ed = apiKeyBox.edits;
        if (!ed.edited()) return;
        String v = ed.text();
        ed.settled();
        if (v.equals(e.settings.apiKey())) return;
        boolean inUse = e.settings.server().length() > 0;
        // The key only goes to a typed-in address: reconnect only when there is one to reconnect to.
        if (inUse) e.setApiKey(v);
        else e.settings.setApiKey(v);
        e.log("info", v.length() == 0 ? "API key removed" : inUse ? "API key saved · reconnecting" : "API key saved");
        saved();
        refreshConnection();
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
                row.addView(kit.smallButton("Use", 0, Ui.SECONDARY, "Use " + s.label(), new View.OnClickListener() {
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
        kit.heading(body, "Default model", "Every reply goes here unless Deep mode takes over.");
        modelSelect = kit.select(body, "Default model", new Runnable() {
            @Override
            public void run() {
                pickModel(false);
            }
        });
        kit.heading(body, "Deep-mode model", "Handles Deep mode — and hard prompts in Auto.");
        deepSelect = kit.select(body, "Deep-mode model", new Runnable() {
            @Override
            public void run() {
                pickModel(true);
            }
        });
        kit.heading(body, "Response mode", null);
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
        kit.sep(body);
        keepLoaded = kit.toggleRow(body, "Keep model loaded",
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
            kit.setValue(modelSelect, online ? "No models installed" : "Not chosen yet", false);
        } else {
            kit.setValue(modelSelect, cur, true);
        }
        kit.setTags(modelSelect, stateChips(cur, online, false));
        String deepPref = e.settings.deepModel();
        String deep = e.resolveInstalled(deepPref);
        if (deepPref.length() == 0) {
            kit.setValue(deepSelect, "Same as default", false);
            kit.setTags(deepSelect);
        } else {
            kit.setValue(deepSelect, deep != null ? deep : deepPref, true);
            kit.setTags(deepSelect, stateChips(deep != null ? deep : deepPref, online,
                    deep == null && online && !ms.isEmpty()));
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
        SettingsKit.bind(keepLoaded, e.settings.keepLoaded());
        String modeName = mi == 1 ? "Fast" : mi == 2 ? "Deep" : "Auto";
        if (!online) setStatus(secModel, modeName + " · offline", t.warn);
        else setStatus(secModel, modeName + " · " + ms.size() + (ms.size() == 1 ? " model" : " models"), t.dim);
    }

    /** The select's chips: "Offline", "Not installed", "Loaded" and the parameter size (mono). */
    private TextView[] stateChips(String model, boolean online, boolean missing) {
        if (!online) return new TextView[]{ui.chip("Offline", t.warn)};
        if (missing) return new TextView[]{ui.chip("Not installed", t.warn)};
        if (model == null || model.length() == 0) return new TextView[0];
        TextView loaded = e.isLoaded(model) ? ui.chip("Loaded", t.ok) : null;
        TextView size = null;
        for (ModelInfo m : e.models()) {
            if (m.name.equals(model) && m.parameterSize.length() > 0) size = ui.chip(m.parameterSize, t.dim, true);
        }
        return new TextView[]{loaded, size};
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
                ui.rounded(t.input, t.edge, 7), null));
        IconDrawable pen = new IconDrawable(IconDrawable.EDIT, t.dim, t.dim, ui.dp(13));
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
        SettingsKit.Line ctx = kit.line("Context window", "How much of the chat the model sees at once.", ctxReadout);
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
        kit.sep(body);
        threads = new SettingsWidgets.Stepper(ui, "CPU threads", new Runnable() {
            @Override
            public void run() {
                promptNumber("CPU threads", "0 = let Ollama decide", e.settings.numThread(), new NumberResult() {
                    @Override
                    public void onNumber(int v) {
                        if (v > MAX_THREADS) ui.toast("Capped at " + MAX_THREADS + " threads.");
                        setThreads(Math.min(v, MAX_THREADS));
                    }
                });
            }
        }).range(0, MAX_THREADS, 1).format(new SettingsWidgets.Stepper.Format() {
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
        SettingsKit.Line th = kit.line("CPU threads", "Auto lets Ollama decide.", threads);
        body.addView(th.row, Ui.fillW());
        kit.sep(body);
        TextView n = ui.dim("Changing either one reloads the model on the PC with your next message.", 12);
        kit.note(body, n);
        loadedNote = ui.readout("", t.hud ? 10 : 11, t.dim);
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
        CharSequence loaded;
        if (e.state() != Engine.State.ONLINE) {
            loaded = t.hud ? t.labelUnits("Offline · applies when the AI reconnects")
                    : "Offline · applies when the AI reconnects";
        } else if (r != null) {
            loaded = withIdent("Loaded now · ", cur, r.contextLength > 0 ? " · " + grouped(r.contextLength) + " ctx" : "");
        } else if (cur.length() > 0) {
            loaded = withIdent("", cur, " isn't loaded yet");
        } else {
            loaded = t.hud ? "NO MODEL LOADED" : "No model loaded";
        }
        loadedNote.setText(loaded);
        String ctxS = ctx == 0 ? "auto" : ctx % 1024 == 0 ? (ctx / 1024) + "K" : grouped(ctx);
        int th = e.settings.numThread();
        setStatus(secPerf, "Ctx " + ctxS + " · Threads " + (th == 0 ? "auto" : String.valueOf(th)),
                ctx == 0 && th == 0 ? t.dim : (t.hud ? t.engagedInk : t.dim));
    }

    // ------------------------------------------------------------------
    // 5 · Generation
    // ------------------------------------------------------------------

    private void buildGeneration() {
        secGen = section("Generation", "Generation");
        LinearLayout body = secGen.body;
        TextView[] th = kit.sliderHead(body, "Temperature", "Lower is focused and repeatable; higher is more creative.",
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
        kit.scaleRow(body, "0.0", "1.0", "2.0");
        kit.sep(body);
        TextView[] ph = kit.sliderHead(body, "Top-p", "Samples only from the most likely words that add up to p.",
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
        kit.scaleRow(body, "0.05", null, "1.00");
        kit.sep(body);
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
        SettingsKit.Line mt = kit.line("Max reply length", "In tokens. No limit lets the model decide.", maxTokens);
        body.addView(mt.row, Ui.fillW());
        kit.sep(body);
        LinearLayout foot = ui.hbox();
        foot.setPadding(0, ui.dp(10), 0, 0);
        TextView fn = ui.dim("Sampling applies to the next message and never reloads the model.", 12);
        foot.addView(fn, Ui.weight(1));
        foot.addView(kit.smallButton("Reset", IconDrawable.REFRESH, Ui.GHOST, "Reset generation",
                new View.OnClickListener() {
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
        lp.leftMargin = -ui.dp(9);
        lp.rightMargin = -ui.dp(9);
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
        int set = t.data;
        tempValue.setText(temp < 0 ? def : String.format(Locale.US, "%.2f", temp));
        tempValue.setTextColor(temp < 0 ? t.dim : set);
        tempReset.setVisibility(temp < 0 ? View.GONE : View.VISIBLE);
        topPValue.setText(topP < 0 ? def : String.format(Locale.US, "%.2f", topP));
        topPValue.setTextColor(topP < 0 ? t.dim : set);
        topPReset.setVisibility(topP < 0 ? View.GONE : View.VISIBLE);
        boolean custom = temp >= 0 || topP >= 0 || e.settings.maxTokens() > 0;
        if (!custom) {
            setStatus(secGen, "Model defaults", t.dim);
        } else {
            StringBuilder sb = new StringBuilder();
            if (temp >= 0) sb.append("Temp ").append(String.format(Locale.US, "%.2f", temp));
            if (topP >= 0) {
                sb.append(sb.length() > 0 ? " · " : "").append("P ").append(String.format(Locale.US, "%.2f", topP));
            }
            if (e.settings.maxTokens() > 0) {
                sb.append(sb.length() > 0 ? " · " : "").append("Max ").append(e.settings.maxTokens());
            }
            setStatus(secGen, sb.toString(), t.hud ? t.engagedInk : t.dim);
        }
    }

    // ------------------------------------------------------------------
    // 6 · Persona
    // ------------------------------------------------------------------

    private void buildPersona() {
        secPersona = section("Persona", "Persona");
        LinearLayout body = secPersona.body;
        kit.heading(body, "System prompt", "Standing instructions sent with every message.");
        promptField = ui.field("", "e.g. You are OMNI, my concise, dry-witted assistant. Prefer short answers.",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        promptField.setGravity(Gravity.TOP | Gravity.START);
        promptField.setMinLines(4);
        promptField.setMaxLines(10);
        promptField.setVerticalScrollBarEnabled(true);
        promptField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        promptField.setLineSpacing(0, 1.2f);
        promptField.setContentDescription("System prompt");
        promptEdits = new SettingsWidgets.EditTracker(promptField);
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
        promptCount = ui.readout("", t.hud ? 10 : 11, t.dim);
        actions.addView(promptCount, Ui.weight(1));
        promptClear = kit.smallButton("Clear", 0, Ui.GHOST, "Clear system prompt", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptField.setText("");
                commitPrompt();
            }
        });
        actions.addView(promptClear);
        promptSave = kit.smallButton("Save", IconDrawable.CHECK, Ui.PRIMARY, "Save system prompt",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        commitPrompt();
                        SettingsWidgets.parkFocus(promptField);
                    }
                });
        // The gap belongs to Save, so a lone Clear link ends on the content edge.
        LinearLayout.LayoutParams sp = Ui.wrap();
        sp.leftMargin = ui.dp(6);
        actions.addView(promptSave, sp);
        body.addView(actions, Ui.fillW());
        kit.sep(body);
        assistantContext = kit.toggleRow(body, "Assistant context", "Tells the model it is OMNI, today's date, and "
                        + "to keep spoken answers short and plain.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.settings.setAssistantContext(on);
                        e.log("info", "Assistant context · " + (on ? "ON" : "OFF"));
                    }
                }, null);
        kit.sep(body);
        kit.heading(body, "Memory", "Facts OMNI always knows about you, sent with every message.");
        factsBox = ui.vbox();
        body.addView(factsBox, Ui.fillW());
        LinearLayout add = ui.hbox();
        add.setPadding(0, ui.dp(8), 0, 0);
        add.addView(kit.bigButton("Add fact", IconDrawable.PLUS, Ui.SECONDARY, "Add fact", new View.OnClickListener() {
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
        promptCount.setTextColor(dirty ? t.warn : t.dim);
        // Save only shows while there's something to save; Clear only when there's text.
        promptSave.setVisibility(dirty ? View.VISIBLE : View.GONE);
        promptClear.setVisibility(cur.length() > 0 ? View.VISIBLE : View.GONE);
    }

    /** Saves the prompt the user typed (never a stale copy: see {@link SettingsWidgets.EditTracker}). */
    private void commitPrompt() {
        if (promptField == null) return;
        if (promptEdits.edited()) {
            String v = promptEdits.text();
            promptEdits.settled();
            if (!v.equals(e.settings.systemPrompt().trim())) {
                e.settings.setSystemPrompt(v);
                e.log("info", v.length() == 0 ? "System prompt cleared" : "System prompt updated");
                saved();
            }
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
            TextView num = ui.readout(String.format(Locale.US, "%02d", i + 1), 11, t.hud ? t.accent : t.dim);
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
            ImageView x = ui.iconButton(IconDrawable.CLOSE, "Forget fact " + (i + 1), t.dim, new View.OnClickListener() {
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
        setStatus(secPersona, s, prompt || n > 0 ? (t.hud ? t.accent : t.dim) : t.dim);
    }

    // ------------------------------------------------------------------
    // 7 · Voice
    // ------------------------------------------------------------------

    private void buildVoice() {
        secVoice = section("Voice", "Voice");
        LinearLayout body = secVoice.body;
        readAloud = kit.toggleRow(body, "Read replies aloud", "Speaks each reply as it streams, using Android's voice.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.setReadAloud(on);
                        refreshVoice();
                    }
                }, null);
        kit.sep(body);
        handsFree = kit.toggleRow(body, "Hands-free conversation", "After each spoken reply, OMNI listens again — "
                        + "talk without touching the phone. The headset in Comms is the same switch.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.settings.setHandsFree(on);
                        e.log("info", "Hands-free conversation · " + (on ? "ON" : "OFF"));
                        refreshVoice();
                    }
                }, null);
        kit.sep(body);
        TextView[] rh = kit.sliderHead(body, "Speech rate", null, null);
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
        kit.scaleRow(body, "0.5×", null, "2.0×");
        LinearLayout row = ui.hbox();
        row.setPadding(0, ui.dp(10), 0, 0);
        row.addView(kit.bigButton("Test voice", IconDrawable.PLAY, Ui.SECONDARY, "Test voice",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        testVoice();
                    }
                }));
        voiceHint = ui.dim("", 12);
        voiceHint.setPadding(ui.dp(12), 0, 0, 0);
        row.addView(voiceHint, Ui.weight(1));
        body.addView(row, Ui.fillW());
        voiceNotice = kit.notice(body);
        LinearLayout fix = ui.hbox();
        fix.setPadding(ui.dp(23), ui.dp(6), 0, 0);
        fix.addView(kit.smallButton("Voice settings", 0, Ui.SECONDARY, "Open voice settings",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSystemScreen(new Intent("com.android.settings.TTS_SETTINGS"),
                                "Open Android Settings › Accessibility › Text-to-speech output.");
                    }
                }));
        fix.setVisibility(View.GONE);
        body.addView(fix, Ui.fillW());
        voiceFix = fix;
    }

    private void testVoice() {
        e.speakNow(TEST_LINE);
        // The text-to-speech engine starts in the background: look again once it has had a moment.
        voiceHint.removeCallbacks(voiceRecheck);
        for (int ms : VOICE_RECHECK_MS) voiceHint.postDelayed(voiceRecheck, ms);
        refreshVoice();
    }

    private void refreshVoice() {
        boolean on = e.settings.readAloud();
        SettingsKit.bind(readAloud, on);
        boolean hf = e.settings.handsFree();
        SettingsKit.bind(handsFree, hf);
        float rate = e.settings.speechRate();
        if (!rateSlider.isPressed()) rateSlider.bind(rate, false);
        rateValue.setText(String.format(Locale.US, "%.2f×", rate));
        boolean available = e.speechAvailable();
        voiceShownAvailable = available;
        if (available) {
            kit.hide(voiceNotice);
            voiceFix.setVisibility(View.GONE);
        } else {
            kit.show(voiceNotice, "This phone has no working text-to-speech voice, so OMNI can't speak. Install or "
                    + "turn on a voice in Android's text-to-speech settings, then test again.", t.warn);
            voiceFix.setVisibility(View.VISIBLE);
        }
        voiceHint.setText(a.isSpeaking() ? "Speaking…" : "Plays a line at the current rate.");
        String s = (on ? "Read-aloud on" : "Read-aloud off") + (hf ? " · hands-free" : "") + " · "
                + String.format(Locale.US, "%.2f×", rate);
        if (!available) setStatus(secVoice, "No voice · " + (on ? "read-aloud on" : "read-aloud off"), t.warn);
        else setStatus(secVoice, s, on || hf ? (t.hud ? t.engagedInk : t.ok) : t.dim);
    }

    // ------------------------------------------------------------------
    // 8 · Notifications
    // ------------------------------------------------------------------

    private void buildNotifications() {
        secNotify = section("Notifications", "Notifications");
        LinearLayout body = secNotify.body;
        notifyToggle = kit.toggleRow(body, "Background notifications", "When a reply, a model download or a timer "
                        + "finishes while OmniDeck is in the background.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.settings.setNotifications(on);
                        e.log("info", "Background notifications · " + (on ? "ON" : "OFF"));
                        // Android 13+ asks for permission the first time (a no-op once granted).
                        if (on) a.ensureNotificationPermission();
                        refreshNotifications();
                    }
                }, null);
        notifyNotice = kit.notice(body);
        LinearLayout fix = ui.hbox();
        fix.setPadding(ui.dp(23), ui.dp(8), 0, ui.dp(2));
        fix.addView(kit.smallButton("Open Android settings", IconDrawable.SETTINGS, Ui.SECONDARY,
                "Open notification settings", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openNotificationSettings();
                    }
                }));
        fix.setVisibility(View.GONE);
        body.addView(fix, Ui.fillW());
        notifyFix = fix;
    }

    private void openNotificationSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            // Settings.ACTION_APP_NOTIFICATION_SETTINGS / EXTRA_APP_PACKAGE (API 26).
            i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                    .putExtra("android.provider.extra.APP_PACKAGE", a.getPackageName());
        } else {
            i = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                    Uri.fromParts("package", a.getPackageName(), null));
        }
        openSystemScreen(i, "Open Android Settings › Apps › OmniDeck › Notifications.");
    }

    private void refreshNotifications() {
        if (notifyToggle == null) return;
        boolean on = e.settings.notifications();
        SettingsKit.bind(notifyToggle, on);
        String why = on ? e.notificationsBlocked() : "";
        if (why.length() > 0) {
            kit.show(notifyNotice, why + " Until then OmniDeck can't tell you when something finishes in the "
                    + "background.", t.warn);
            notifyFix.setVisibility(View.VISIBLE);
        } else {
            kit.hide(notifyNotice);
            notifyFix.setVisibility(View.GONE);
        }
        setStatus(secNotify, !on ? "Off" : why.length() > 0 ? "Blocked" : "On",
                !on ? t.dim : why.length() > 0 ? t.warn : t.ok);
    }

    // ------------------------------------------------------------------
    // 9 · PC bridge
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
        status.addView(kit.smallButton("Check", IconDrawable.REFRESH, Ui.SECONDARY, "Check bridge",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        commitBridgeHost();
                        commitPort();
                        checkBridge(true);
                    }
                }));
        body.addView(status, Ui.fillW());
        kit.sep(body);

        // Address : port — where LaunchBridge listens.
        hostLine = kit.heading(body, "Bridge address", "");
        LinearLayout addr = ui.hbox();
        hostField = monoField("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "Bridge address", new Runnable() {
            @Override
            public void run() {
                commitBridgeHost();
            }
        });
        hostEdits = new SettingsWidgets.EditTracker(hostField);
        addr.addView(hostField, Ui.weight(1));
        TextView colon = ui.readout(":", 16, t.dim);
        colon.setPadding(ui.dp(7), 0, ui.dp(7), 0);
        addr.addView(colon);
        portField = ui.numberField("", String.valueOf(BridgeClient.DEFAULT_PORT));
        portField.setTypeface(t.mono);
        portField.setContentDescription("Bridge port");
        portField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        portField.setSingleLine(true);
        portField.setMinWidth(ui.dp(88));
        portField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                commitPort();
                SettingsWidgets.parkFocus(portField);
                return true;
            }
        });
        portField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                if (!has) commitPort();
            }
        });
        portEdits = new SettingsWidgets.EditTracker(portField);
        addr.addView(portField, new LinearLayout.LayoutParams(ui.dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(addr, Ui.fillW());
        hostNotice = kit.notice(body);
        View rule = kit.sep(body);
        ((LinearLayout.LayoutParams) rule.getLayoutParams()).topMargin = ui.dp(14);

        kit.heading(body, "Pairing token", "Lets this phone control the PC. Pair now fetches one for you.");
        tokenBox = new SettingsWidgets.SecretField(ui, "token", "Not paired", true);
        tokenBox.field.setContentDescription("Bridge token");
        tokenBox.setOnCommit(new SettingsWidgets.SecretField.OnCommit() {
            @Override
            public void commit() {
                commitToken();
            }
        });
        body.addView(tokenBox, Ui.fillW());

        LinearLayout btns = ui.hbox();
        btns.setPadding(0, ui.dp(12), 0, 0);
        View.OnClickListener pairClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pair();
            }
        };
        // Unpaired, pairing is the one thing to do (primary); re-pairing is maintenance (secondary).
        pairNowBtn = kit.bigButton("Pair now", IconDrawable.LINK, Ui.PRIMARY, "Pair now", pairClick);
        btns.addView(pairNowBtn);
        pairAgainBtn = kit.bigButton("Pair again", IconDrawable.LINK, Ui.SECONDARY, "Pair again", pairClick);
        btns.addView(pairAgainBtn);
        forgetBtn = kit.bigButton("Forget pairing", IconDrawable.CLOSE, Ui.DANGER, "Forget pairing",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        forgetPairing();
                    }
                });
        LinearLayout.LayoutParams fp = Ui.wrap();
        fp.leftMargin = ui.dp(8);
        btns.addView(forgetBtn, fp);
        body.addView(btns, Ui.fillW());
        TextView bridgeGuide = ui.button("Bridge setup guide", IconDrawable.DOC, Ui.GHOST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SetupGuide.show(a, SetupGuide.BRIDGE);
            }
        });
        bridgeGuide.setContentDescription("Bridge setup guide");
        body.addView(bridgeGuide, ui.margins(Ui.wrap(), 0, 6, 0, 0));
        pairStatus = kit.notice(body);

        View rule2 = kit.sep(body);
        ((LinearLayout.LayoutParams) rule2.getLayoutParams()).topMargin = ui.dp(14);
        kit.heading(body, "Wake-on-LAN", "Wakes the PC from sleep. The phone learns the PC's MAC address once "
                + "paired; Wake-on-LAN must be on in the PC's BIOS and network adapter.");
        LinearLayout wol = ui.hbox();
        macField = monoField("AA:BB:CC:DD:EE:FF", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, "PC MAC address", new Runnable() {
            @Override
            public void run() {
                commitMac();
            }
        });
        macEdits = new SettingsWidgets.EditTracker(macField);
        wol.addView(macField, Ui.weight(1));
        wol.addView(ui.space(8, 1));
        wakeBtn = kit.bigButton("Wake PC", IconDrawable.POWER, Ui.SECONDARY, "Wake PC", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wakePc();
            }
        });
        wol.addView(wakeBtn);
        body.addView(wol, Ui.fillW());
        wolStatus = kit.notice(body);
    }

    /** The bridge's (paired, reachable) state; a status line is dropped once this changes under it. */
    private String bridgeKey() {
        return e.bridgePaired() + "/" + e.bridgeOnline() + "/" + e.bridgeHost() + ":" + e.settings.bridgePort();
    }

    private void showPairStatus(String s, int color) {
        kit.show(pairStatus, s, color);
        pairStatus.key = bridgeKey();
    }

    private void commitPort() {
        if (!portEdits.edited()) return;
        String raw = portEdits.text();
        int cur = e.settings.bridgePort();
        int v;
        try {
            v = raw.length() == 0 ? BridgeClient.DEFAULT_PORT : Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            v = -1;
        }
        if (v <= 0 || v >= 65536) {
            ui.toast("Ports run from 1 to 65535.");
            portEdits.bind(String.valueOf(cur));
            return;
        }
        portEdits.bind(String.valueOf(v));
        if (v != cur) {
            e.settings.setBridgePort(v);
            e.log("info", "PC bridge port · " + v);
            saved();
            a.onStateChanged(); // the PC tab follows
            checkBridge(false);
        }
        refreshBridge();
    }

    /**
     * Saves the typed bridge address: blank = the PC running the AI. A
     * "host:port" also sets the port. The token stays bound to the PC that
     * issued it, so a different PC shows as not paired until paired again.
     */
    private void commitBridgeHost() {
        if (!hostEdits.edited()) {
            if (INVALID.equals(hostNotice.key)) kit.hide(hostNotice);
            return;
        }
        String raw = hostEdits.text();
        String host = "";
        int port = e.settings.bridgePort();
        if (raw.length() > 0) {
            HostPort hp = HostPort.parse(raw, port);
            if (hp == null || hp.https) {
                // Kept in the field and not saved, with the reason under it.
                kit.show(hostNotice, hp == null ? "That isn't an address — type the PC's IP or name, e.g. "
                        + "192.168.1.20." : "LaunchBridge speaks plain http on your network: leave out https://.",
                        t.danger);
                hostNotice.key = INVALID;
                return;
            }
            host = hp.host;
            port = hp.port;
        }
        kit.hide(hostNotice);
        hostEdits.bind(host);
        boolean changed = false;
        if (!host.equals(e.settings.bridgeHost())) {
            e.settings.setBridgeHost(host);
            e.log("info", host.length() == 0 ? "PC bridge · same PC as the AI" : "PC bridge address · " + host);
            changed = true;
        }
        if (port != e.settings.bridgePort()) {
            e.settings.setBridgePort(port);
            portEdits.bind(String.valueOf(port));
            changed = true;
        }
        if (changed) {
            saved();
            a.onStateChanged(); // the PC tab follows the new address
            checkBridge(false);
        }
        refreshBridge();
    }

    /** Saves the typed token bound to the bridge in use ("" unpairs); only a real edit is saved. */
    private void commitToken() {
        SettingsWidgets.EditTracker ed = tokenBox.edits;
        if (!ed.edited()) return;
        String v = ed.text();
        ed.settled();
        if (v.equals(e.settings.bridgeToken())) return;
        e.setBridgeToken(v);
        e.log(v.length() == 0 ? "warn" : "info", v.length() == 0 ? "PC bridge pairing removed" : "PC bridge token set");
        saved();
        refreshBridge();
    }

    private void commitMac() {
        if (!macEdits.edited()) {
            // Back to the saved value: nothing to save, and nothing invalid on show any more.
            if (INVALID.equals(wolStatus.key)) kit.hide(wolStatus);
            return;
        }
        String raw = macEdits.text();
        String mac = "";
        if (raw.length() > 0) {
            mac = WakeOnLan.normalize(raw);
            if (mac == null) {
                // Kept in the field (one typo shouldn't cost the whole address) and not saved.
                kit.show(wolStatus, "“" + raw + "” isn't a MAC address — it looks like AA:BB:CC:DD:EE:FF.", t.danger);
                wolStatus.key = INVALID;
                return;
            }
        }
        macEdits.bind(mac);
        if (INVALID.equals(wolStatus.key)) kit.hide(wolStatus);
        if (!mac.equals(e.settings.pcMac())) {
            e.settings.setPcMac(mac);
            e.log("info", mac.length() == 0 ? "Wake-on-LAN · MAC cleared" : "Wake-on-LAN · MAC " + mac);
            saved();
        }
        refreshBridge();
    }

    private void wakePc() {
        commitMac();
        if (waking || macEdits.edited()) return; // an invalid MAC is on show
        waking = true;
        SettingsKit.enable(wakeBtn, false);
        kit.show(wolStatus, "Sending the wake-up packet…", t.dim);
        e.wakePc(new Engine.Callback<String>() {
            @Override
            public void done(String v, String error) {
                waking = false;
                if (!isBuilt()) return;
                if (error != null) kit.show(wolStatus, error, t.warn);
                else kit.show(wolStatus, v, t.ok);
                refreshBridge();
            }
        });
    }

    private void pair() {
        if (pairing) return;
        commitBridgeHost();
        if (hostEdits.edited()) return; // the typed address is invalid: its notice says why
        commitPort();
        String host = e.bridgeHost();
        pairing = true;
        refreshBridge();
        showPairStatus("Pairing with LaunchBridge" + (host.length() > 0 ? " at " + host + ":"
                + e.settings.bridgePort() : "") + "…", t.dim);
        e.bridgePair(new Engine.Callback<String>() {
            @Override
            public void done(String token, String error) {
                pairing = false;
                if (!isBuilt()) return;
                if (error != null) {
                    showPairStatus("Pairing failed — " + error, t.danger);
                } else {
                    tokenBox.edits.bind(token);
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
                        e.setBridgeToken("");
                        tokenBox.edits.bind("");
                        e.log("warn", "PC bridge pairing removed");
                        showPairStatus("Pairing removed.", t.dim);
                        saved();
                        refreshBridge();
                    }
                });
    }

    /** Asks LaunchBridge if it's there (no auth needed) and updates the status line. */
    private void checkBridge(final boolean fromUser) {
        String host = e.bridgeHost();
        if (host.length() == 0) {
            if (fromUser) showPairStatus("Enter the bridge address above, or connect to your AI — the bridge "
                    + "usually runs on the same PC.", t.warn);
            return;
        }
        if (fromUser) {
            checking = true;
            showPairStatus("Checking " + host + ":" + e.settings.bridgePort() + "…", t.dim);
        }
        e.bridgeHealth(new Engine.Callback<org.json.JSONObject>() {
            @Override
            public void done(org.json.JSONObject v, String error) {
                if (!isBuilt()) return;
                if (fromUser) {
                    checking = false;
                    if (error != null) {
                        showPairStatus("No answer — " + error, t.danger);
                    } else {
                        int apps = v.optInt("apps_indexed", -1);
                        showPairStatus("LaunchBridge is online" + (apps >= 0 ? " · " + apps + " apps indexed" : "")
                                + (e.bridgePaired() ? "." : ". Pair to control the PC."), t.ok);
                    }
                }
                refreshBridge();
                refreshTools();
            }
        });
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
            c = t.dim;
        }
        bridgeState.setText(t.hud ? s.toUpperCase(Locale.US) : s);
        bridgeState.setTextColor(c);
        bridgeDot.setColor(c);
        bridgeDot.setPulsing(isShown() && paired && Boolean.TRUE.equals(online) && !e.settings.reduceMotion());
        String host = e.bridgeHost();
        String other = e.settings.bridgeTokenHost();
        if (host.length() == 0) {
            bridgeDetail.setText("Enter the PC's address below, or connect to your AI — the bridge usually runs on "
                    + "the same PC.");
        } else if (!paired && e.settings.bridgeToken().length() > 0 && other.length() > 0) {
            SpannableStringBuilder sb = new SpannableStringBuilder("Paired with the bridge at ");
            sb.append(ui.mono(other)).append(", not ").append(ui.mono(host)).append(" — pair again for this PC.");
            bridgeDetail.setText(sb);
        } else {
            bridgeDetail.setText(sentence("LaunchBridge at ", host + ":" + e.settings.bridgePort(), ""));
        }
        String aiHost = aiHost();
        kit.setSub(hostLine, aiHost.length() > 0
                ? sentence("Leave empty to use the PC running your AI (", aiHost, "). The port is 8765 unless you "
                + "changed it.") : "Leave empty to use the PC running your AI. The port is 8765 unless you changed it.");
        hostField.setHint(aiHost.length() > 0 ? aiHost : "PC address");
        showSaved(hostField, hostEdits, e.settings.bridgeHost(), hostNotice);
        showSaved(portField, portEdits, String.valueOf(e.settings.bridgePort()), null);
        showSaved(tokenBox.field, tokenBox.edits, e.settings.bridgeToken(), null);
        showSaved(macField, macEdits, e.settings.pcMac(), wolStatus);
        pairNowBtn.setVisibility(paired ? View.GONE : View.VISIBLE);
        pairAgainBtn.setVisibility(paired ? View.VISIBLE : View.GONE);
        TextView pb = paired ? pairAgainBtn : pairNowBtn;
        SettingsKit.enable(pb, !pairing);
        kit.relabel(pb, pairing ? "Pairing…" : paired ? "Pair again" : "Pair now");
        // Nothing to forget until paired: no disabled red button next to Pair now.
        forgetBtn.setVisibility(paired ? View.VISIBLE : View.GONE);
        SettingsKit.enable(wakeBtn, !waking);
        // A result line describes the bridge as it was; once that changes, it goes.
        if (pairStatus.isShowing() && !pairing && !checking && !bridgeKey().equals(pairStatus.key)) {
            kit.hide(pairStatus);
        }
        setStatus(secBridge, paired ? (Boolean.FALSE.equals(online) ? "Paired · unreachable" : "Paired") : "Not paired",
                paired ? (Boolean.FALSE.equals(online) ? t.warn : t.ok) : t.dim);
    }

    /** The PC running the AI (the bridge's default host): the live link, else the last one. */
    private String aiHost() {
        ServerInfo srv = e.server();
        return srv != null ? srv.host : e.settings.lastHost();
    }

    // ------------------------------------------------------------------
    // 10 · PC tools (the AI acting on the PC)
    // ------------------------------------------------------------------

    private void buildTools() {
        secTools = section("PC tools", "PC tools");
        LinearLayout body = secTools.body;
        aiTools = kit.toggleRow(body, "Let OMNI use PC tools", "In chat, OMNI can check the PC's vitals, set the "
                        + "volume, open apps and more. Needs a model that supports tools and a paired PC bridge.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        // Through the Engine so the tool list is read when tools come on.
                        e.setAiTools(on);
                        e.log("info", "PC tools for OMNI · " + (on ? "ON" : "OFF"));
                        refreshTools();
                    }
                }, null);
        kit.sep(body);
        confirmActions = kit.toggleRow(body, "Ask before PC actions", "OMNI checks with you before anything changes "
                        + "on the PC — opening apps, the volume, the clipboard, locking it.",
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        e.settings.setConfirmPcActions(on);
                        e.log(on ? "info" : "warn", "Ask before PC actions · " + (on ? "ON" : "OFF"));
                        refreshTools();
                    }
                }, null);
        TextView head = ui.label("Readiness");
        head.setPadding(0, ui.dp(12), 0, ui.dp(8));
        body.addView(head, Ui.fillW());
        readiness = ui.vbox();
        readiness.setBackground(SettingsWidgets.well(ui));
        readiness.setPadding(0, ui.dp(3), 0, ui.dp(3));
        toolModel = kit.check(readiness, "Model");
        toolBridge = kit.check(readiness, "Bridge");
        body.addView(readiness, Ui.fillW());
    }

    private void refreshTools() {
        if (aiTools == null) return;
        boolean on = e.settings.aiTools();
        boolean ask = e.settings.confirmPcActions();
        SettingsKit.bind(aiTools, on);
        SettingsKit.bind(confirmActions, ask);
        // Model: can it call tools? (/api/show capabilities, fetched once per model while shown)
        boolean modelOk = false;
        String model = e.currentModel();
        if (e.state() != Engine.State.ONLINE) {
            kit.setCheck(toolModel, "Connect to your AI to check its model.", t.dim);
        } else if (model.length() == 0) {
            kit.setCheck(toolModel, "No model chosen yet.", t.warn);
        } else {
            OllamaClient.ModelDetails d = e.details(model);
            Boolean tools = d != null ? Boolean.valueOf(d.supports("tools")) : toolSupport.get(model);
            if (tools == null) {
                kit.setCheck(toolModel, sentence("", model, " · checking what it can do…"), t.dim);
                askCapabilities(model);
            } else if (tools) {
                modelOk = true;
                kit.setCheck(toolModel, sentence("", model, " can call tools."), t.ok);
            } else {
                kit.setCheck(toolModel, sentence("", model, " can't call tools — choose one that can "
                        + "(qwen3, llama3.1…)."), t.warn);
            }
        }
        boolean paired = e.bridgePaired();
        Boolean online = e.bridgeOnline();
        boolean bridgeOk = paired && !Boolean.FALSE.equals(online);
        if (!paired) kit.setCheck(toolBridge, "Not paired — pair it in PC bridge above.", t.warn);
        else if (Boolean.FALSE.equals(online)) kit.setCheck(toolBridge, "Paired, but the PC isn't answering.", t.warn);
        else kit.setCheck(toolBridge, Boolean.TRUE.equals(online) ? "Paired · online." : "Paired.", t.ok);
        readiness.setAlpha(on ? 1f : 0.55f);
        String s = on ? (ask ? "On · asks first" : "On · acts directly") : "Off";
        setStatus(secTools, s, !on ? t.dim : modelOk && bridgeOk ? (ask ? t.ok : t.engagedInk) : t.warn);
    }

    private void askCapabilities(final String model) {
        if (!isShown() || !toolsAsked.add(model)) return;
        e.fetchDetails(model, new Engine.Callback<OllamaClient.ModelDetails>() {
            @Override
            public void done(OllamaClient.ModelDetails d, String error) {
                if (!isBuilt()) return;
                if (d == null) {
                    if (model.equals(e.currentModel())) {
                        kit.setCheck(toolModel, sentence("", model, " — couldn't read what it can do."), t.dim);
                    }
                    return;
                }
                toolSupport.put(model, d.supports("tools"));
                refreshTools();
            }
        });
    }

    // ------------------------------------------------------------------
    // 11 · Privacy & data
    // ------------------------------------------------------------------

    private void buildPrivacy() {
        secPrivacy = section("Privacy & data", "Privacy");
        LinearLayout body = secPrivacy.body;
        SettingsKit.Line[] il = new SettingsKit.Line[1];
        incognito = kit.toggleRow(body, "Incognito", "", new Widgets.Toggle.OnChange() {
            @Override
            public void changed(boolean on) {
                if (on) e.settings.setIncognito(true);
                else leaveIncognito();
                e.log(on ? "warn" : "info", "Incognito · " + (on ? "ON" : "OFF"));
                refreshPrivacy();
            }
        }, il);
        incognitoLine = il[0];
        kit.sep(body);
        kit.navRow(body, "Export current chat", "Share this conversation as Markdown.", IconDrawable.SHARE,
                new Runnable() {
                    @Override
                    public void run() {
                        a.commander().exportChat();
                    }
                });
        kit.sep(body);
        historyLine = kit.line("Saved chats", "Counting…", null);
        body.addView(historyLine.row, Ui.fillW());
        clearBtn = kit.bigButton("Clear all chat history", IconDrawable.TRASH, Ui.DANGER, "Clear all chat history",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmClearHistory();
                    }
                });
        body.addView(clearBtn, Ui.fillW());
    }

    /**
     * Incognito off: whatever was said while it was on is never written. The
     * open chat may hold such messages, so it is closed WITHOUT saving (new
     * chat while incognito is still on — Engine.save skips it), then saving
     * resumes. An empty chat has nothing to hide and stays.
     */
    private void leaveIncognito() {
        boolean close = !e.conversation().isEmpty();
        leavingIncognito = true;
        try {
            if (close) e.newChat();
        } finally {
            leavingIncognito = false;
        }
        e.settings.setIncognito(false);
        if (close) ui.toast("Incognito off · the open chat was closed without saving.");
    }

    private void confirmClearHistory() {
        boolean open = !e.conversation().isEmpty();
        String msg;
        if (savedChats > 0) {
            msg = "Deletes " + (savedChats == 1 ? "the saved chat" : "all " + savedChats + " saved chats")
                    + " on this phone and clears the open chat. This can't be undone.";
        } else if (savedChats == 0 && open) {
            msg = "Clears the open chat. Nothing else is saved on this phone.";
        } else {
            msg = "Deletes every saved chat on this phone and clears the open chat. This can't be undone.";
        }
        ui.confirm("Clear all chat history?", msg, "Delete all", new Runnable() {
            @Override
            public void run() {
                clearHistory();
            }
        });
    }

    /** Deletes every saved chat, then starts a fresh one, so the open chat goes too (saved or not). */
    private void clearHistory() {
        final boolean open = !e.conversation().isEmpty();
        e.listChats(new Engine.Callback<List<ConversationStore.Entry>>() {
            @Override
            public void done(List<ConversationStore.Entry> entries, String error) {
                int n = entries == null ? 0 : entries.size();
                if (entries != null) {
                    for (ConversationStore.Entry en : entries) e.deleteChat(en.id);
                }
                e.newChat();
                e.log("warn", "Chat history cleared · " + n + (n == 1 ? " chat" : " chats"));
                ui.toast(n > 0 ? "Deleted " + n + (n == 1 ? " chat." : " chats.")
                        : open ? "Cleared the open chat." : "No saved chats to clear.");
                savedChats = 0;
                saved();
                if (!isBuilt()) return;
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
                    kit.setSub(historyLine, e.settings.incognito() && !e.conversation().isEmpty()
                            ? "None saved — the open chat is incognito." : "None on this phone yet.");
                } else {
                    kit.setSub(historyLine, savedChats + (savedChats == 1 ? " chat · " : " chats · ") + grouped(msgs)
                            + (msgs == 1 ? " message" : " messages") + " stored on this phone.");
                }
                refreshPrivacy();
            }
        });
    }

    private void refreshPrivacy() {
        boolean inc = e.settings.incognito();
        SettingsKit.bind(incognito, inc);
        kit.setSub(incognitoLine, inc ? "On: nothing is saved. Turning it off closes the open chat without saving it."
                : "New chats and replies aren't saved on this phone while it's on.");
        incognitoLine.title.setTextColor(inc ? t.engagedInk : t.ink);
        SettingsKit.enable(clearBtn, savedChats != 0 || !e.conversation().isEmpty());
        setStatus(secPrivacy, inc ? "Incognito" : "Saving chats", inc ? t.engagedInk : t.dim);
    }

    // ------------------------------------------------------------------
    // 12 · About
    // ------------------------------------------------------------------

    private void buildAbout() {
        secAbout = section("About", "About");
        LinearLayout body = secAbout.body;
        LinearLayout id = ui.hbox();
        id.setPadding(0, ui.dp(10), 0, ui.dp(4));
        ImageView logo = new ImageView(a);
        logo.setImageDrawable(new IconDrawable(IconDrawable.LOGO, t.accent, t.logoCore, ui.dp(38)));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
        aboutGrid = kit.readoutGrid(body, new String[]{"App", "Ollama", "Android", "AI link"});
        body.addView(ui.space(1, 6));
        kit.navRow(body, "Command reference", "Every slash command, listed in Comms.", IconDrawable.TERMINAL,
                new Runnable() {
                    @Override
                    public void run() {
                        runInComms("/help");
                    }
                });
        kit.sep(body);
        kit.navRow(body, "Diagnostics", "Connection report: network, address, bridge.", IconDrawable.ACTIVITY,
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
        kit.setReadout(aboutGrid[0], "v" + v, true);
        ServerInfo srv = e.server();
        boolean on = e.state() == Engine.State.ONLINE && srv != null;
        kit.setReadout(aboutGrid[1], on && srv.version.length() > 0 ? srv.version : "—", on);
        kit.setReadout(aboutGrid[2], Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT, true);
        kit.setReadout(aboutGrid[3], on ? srv.label() : "Offline", on);
        setStatus(secAbout, "Build " + v, t.dim);
    }

    // ------------------------------------------------------------------
    // Refresh / lifecycle
    // ------------------------------------------------------------------

    /**
     * Shows a field's saved value — unless the user is typing in it, or (on
     * a partial refresh) it holds an edit that couldn't be saved, which stays
     * next to the notice explaining why. A full refresh (the page shown
     * again) drops such an edit and its notice.
     */
    private void showSaved(EditText f, SettingsWidgets.EditTracker ed, String saved, SettingsKit.Notice invalid) {
        if (f.hasFocus()) return;
        if (ed.edited() && !fullRefresh) return;
        ed.bind(saved);
        if (invalid != null && INVALID.equals(invalid.key)) kit.hide(invalid);
    }

    /** Reads every control back from the live settings and engine state. */
    private void refresh() {
        fullRefresh = true;
        try {
            refreshAll();
        } finally {
            fullRefresh = false;
        }
    }

    private void refreshAll() {
        refreshAppearance();
        refreshConnection();
        refreshModel();
        refreshPerformance();
        refreshGeneration();
        showSaved(promptField, promptEdits, e.settings.systemPrompt(), null);
        updatePromptState();
        refreshFacts();
        SettingsKit.bind(assistantContext, e.settings.assistantContext());
        refreshPersonaStatus();
        refreshVoice();
        refreshNotifications();
        refreshBridge();
        refreshTools();
        refreshPrivacy();
        refreshAbout();
    }

    /**
     * Saves what the user typed into the text fields (the page is closing or
     * the app is leaving the screen). Only real edits are written — a field
     * still showing the value it was filled with never overwrites a change
     * made elsewhere since. The bridge address and port go first, so a token
     * typed at the same time is bound to the right PC.
     */
    private void commitFields() {
        if (promptField == null) return;
        commitBridgeHost();
        commitPort();
        commitToken();
        commitMac();
        commitApiKey();
        commitPrompt();
    }

    @Override
    protected void onShow() {
        shownFacts = null;
        toolsAsked.clear();
        toolSupport.clear();
        // Result lines belong to the visit that produced them.
        if (!pairing) kit.hide(pairStatus);
        if (!waking) kit.hide(wolStatus);
        kit.hide(hostNotice);
        refresh();
        countChats();
        if (e.bridgeHost().length() > 0) checkBridge(false);
    }

    @Override
    protected void onHide() {
        commitFields();
        View f = a.getCurrentFocus();
        if (f != null) SettingsWidgets.parkFocus(f);
        column.requestFocus();
        stopGlides();
        linkDot.setPulsing(false);
        bridgeDot.setPulsing(false);
        voiceHint.removeCallbacks(voiceRecheck);
        savedMark.removeCallbacks(savedReset);
        savedReset.run();
    }

    @Override
    public void onActivityStop() {
        // A hidden Settings page has no edits in flight, and its fields may be
        // stale (a /system command, the PC tab's Forget pairing): commit only
        // while it is on screen.
        if (isShown()) commitFields();
        if (linkDot != null) {
            stopGlides();
            linkDot.setPulsing(false);
            bridgeDot.setPulsing(false);
            voiceHint.removeCallbacks(voiceRecheck);
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
        refreshTools();
        refreshAbout();
    }

    @Override
    public void onTelemetry() {
        if (isShown()) refreshConnection();
    }

    @Override
    public void onLog(Telemetry.Event ev) {
        // A missing text-to-speech voice is reported through the log (and a toast).
        if (isShown() && voiceShownAvailable != null && voiceShownAvailable != e.speechAvailable()) refreshVoice();
    }

    @Override
    public void onSpeechChanged(boolean speaking) {
        if (isBuilt()) voiceHint.setText(speaking ? "Speaking…" : "Plays a line at the current rate.");
    }

    @Override
    public void onConversationReplaced() {
        // Mid-way through turning incognito off the setting is still on: the switch refreshes after.
        if (isBuilt() && !leavingIncognito) refreshPrivacy();
    }

    @Override
    public void onMessageAdded(com.omnideck.mobile.core.ChatMessage m) {
        if (isShown()) refreshPrivacy();
    }

    @Override
    public boolean onBack() {
        return false;
    }

    @Override
    public void onDestroy() {
        if (voiceHint != null) voiceHint.removeCallbacks(voiceRecheck);
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
