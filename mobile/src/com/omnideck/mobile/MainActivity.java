package com.omnideck.mobile;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.HostPort;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.ServerInfo;
import com.omnideck.mobile.core.Telemetry;
import com.omnideck.mobile.core.ToolApproval;
import com.omnideck.mobile.screens.CommandScreen;
import com.omnideck.mobile.screens.CommsScreen;
import com.omnideck.mobile.screens.ModelsScreen;
import com.omnideck.mobile.screens.PcScreen;
import com.omnideck.mobile.screens.Screen;
import com.omnideck.mobile.screens.SettingsScreen;
import com.omnideck.mobile.ui.Backdrop;
import com.omnideck.mobile.ui.BootOverlay;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.Panel;
import com.omnideck.mobile.ui.Sheet;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shell: HUD top bar (link status), the page area, and the command bar
 * (COMMAND · COMMS · MODELS · PC). Owns theming, navigation, activity
 * results (voice input, image picking), what other apps and launcher
 * shortcuts hand in, and forwards Engine events to pages.
 */
public final class MainActivity extends Activity implements Engine.Listener, Theme.Host {
    public static final int TAB_COMMAND = 0;
    public static final int TAB_COMMS = 1;
    public static final int TAB_MODELS = 2;
    public static final int TAB_PC = 3;
    /** Tests turn the cold-start boot sequence off. */
    public static boolean skipBoot;
    private static boolean bootShown;
    private static boolean shortcutsPublished;

    public static final String[] TAB_NAMES = {"Command", "Comms", "Models", "PC"};
    static final int[] TAB_ICONS = {IconDrawable.NAV_COMMAND, IconDrawable.NAV_COMMS, IconDrawable.NAV_MODELS,
            IconDrawable.NAV_PC};

    /** Launcher shortcut (intent extra) → what to do on arrival. */
    public static final String EXTRA_SHORTCUT = "com.omnideck.mobile.SHORTCUT";
    public static final String SHORTCUT_TALK = "talk";
    public static final String SHORTCUT_NEW_CHAT = "new_chat";
    public static final String SHORTCUT_SCREENSHOT = "screenshot";
    public static final String SHORTCUT_COMMAND = "command";

    /**
     * Fixed request codes: a voice or photo result that arrives after the
     * activity was recreated (process death, a dark-mode switch) still finds
     * its way to the chat, even though the in-memory callback is gone.
     */
    static final int REQ_VOICE = 7101;
    static final int REQ_IMAGE = 7102;
    static final int REQ_NOTIFY = 7103;
    private static final String PERM_NOTIFY = "android.permission.POST_NOTIFICATIONS";
    /** Shared files are read up to this many characters. */
    static final int SHARED_TEXT_MAX = 32000;
    /** Up to this many images per message (the composer's limit). */
    static final int MAX_IMAGES = 4;

    private Engine engine;
    private Theme theme;
    private Ui ui;
    private String themePref;
    private int themeId;
    private Commander commander;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;

    private FrameLayout content;
    private final Screen[] tabs = new Screen[4];
    private SettingsScreen settingsScreen;
    private boolean settingsOpen;
    private int tab = -1;

    // Top bar
    private TextView subtitle;
    private TextView subtitleAddr;
    private LinearLayout pill;
    private Widgets.StatusDot dot;
    private TextView pillText;
    private TextView latencyText;
    private ImageView stopSpeech;
    // Command bar
    private final View[] navItems = new View[4];
    private final ImageView[] navIcons = new ImageView[4];
    private final TextView[] navLabels = new TextView[4];
    private final View[] navMarks = new View[4];
    private final View[] navBadges = new View[4];
    // Overlays
    private Widgets.ScanLine scanLine;
    private TextView banner;
    private Engine.State lastState;
    private boolean speaking;

    // Activity results
    public interface ResultHandler {
        void onResult(int resultCode, Intent data);
    }

    public interface TextResult {
        void onText(String text);
    }

    public interface ImageResult {
        void onImage(String base64, Bitmap preview);
    }

    private final Map<Integer, ResultHandler> results = new HashMap<Integer, ResultHandler>();
    private int nextRequest = 7200;

    // ------------------------------------------------------------------
    // Accessors for screens
    // ------------------------------------------------------------------

    public Engine engine() {
        return engine;
    }

    public Ui ui() {
        return ui;
    }

    @Override
    public Theme theme() {
        return theme;
    }

    public Commander commander() {
        return commander;
    }

    public int currentTab() {
        return settingsOpen ? -1 : tab;
    }

    public boolean settingsOpen() {
        return settingsOpen;
    }

    public CommsScreen comms() {
        return (CommsScreen) tabScreen(TAB_COMMS);
    }

    /** True while the phone reads something aloud (as last polled). */
    public boolean isSpeaking() {
        return speaking;
    }

    private Screen tabScreen(int i) {
        if (tabs[i] == null) {
            switch (i) {
                case TAB_COMMAND:
                    tabs[i] = new CommandScreen(this);
                    break;
                case TAB_COMMS:
                    tabs[i] = new CommsScreen(this);
                    break;
                case TAB_MODELS:
                    tabs[i] = new ModelsScreen(this);
                    break;
                default:
                    tabs[i] = new PcScreen(this);
                    break;
            }
        }
        return tabs[i];
    }

    private List<Screen> built() {
        List<Screen> out = new ArrayList<Screen>(5);
        for (Screen s : tabs) {
            if (s != null && s.isBuilt()) out.add(s);
        }
        if (settingsScreen != null && settingsScreen.isBuilt()) out.add(settingsScreen);
        return out;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        engine = Engine.get(this);
        themePref = engine.settings.theme();
        theme = Theme.forPref(this, themePref);
        themeId = theme.id;
        setTheme(theme.themeRes);
        super.onCreate(savedInstanceState);
        ui = new Ui(this, theme);
        ui.haptics = engine.settings.haptics();
        ui.reduceMotion = engine.settings.reduceMotion();
        commander = new Commander(this);
        styleSystemBars();
        setContentView(buildShell());
        engine.setListener(this);
        lastState = engine.state();
        int start = savedInstanceState != null ? savedInstanceState.getInt("tab", engine.settings.lastTab())
                : engine.settings.lastTab();
        select(Math.max(0, Math.min(3, start)), false);
        if (savedInstanceState != null && savedInstanceState.getBoolean("settings", false)) openSettings();
        onStateChanged();
        onTelemetry();
        // A restored activity (process death) or a relaunch from Recents gets the
        // task's original intent again: a share or shortcut must not be replayed.
        if (savedInstanceState == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleIntent(getIntent());
        }
        publishShortcuts();
        if (!bootShown && savedInstanceState == null && !skipBoot && !engine.settings.reduceMotion()) {
            bootShown = true;
            new BootOverlay(this, theme).play((ViewGroup) findViewById(android.R.id.content));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putBoolean("settings", settingsOpen);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.setListener(this);
        if (!themePref.equals(engine.settings.theme()) || Theme.resolve(this, engine.settings.theme()) != themeId) {
            recreate();
            return;
        }
        started = true;
        ui.haptics = engine.settings.haptics();
        ui.reduceMotion = engine.settings.reduceMotion();
        engine.setVisible(true);
        for (Screen s : built()) s.dispatchActivityStart();
        updateScanLine();
        updateDot();
        handler.removeCallbacks(speechPoll);
        handler.post(speechPoll);
        // A PC action still waiting for approval (the activity was recreated meanwhile): ask again.
        ToolApproval pending = engine.pendingApproval();
        if (pending != null && !comms().showToolApproval(pending)) pending.unavailable();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        for (Screen s : built()) s.dispatchActivityStop();
        engine.setVisible(false);
        if (scanLine != null) scanLine.stop();
        // No pulse (and no frames) while nothing is on screen; onStart brings it back.
        dot.setPulsing(false);
        handler.removeCallbacks(speechPoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        for (Screen s : built()) s.onDestroy();
        if (engine.listener() == this) engine.setListener(null);
    }

    @Override
    public void onBackPressed() {
        if (settingsOpen) {
            if (settingsScreen != null && settingsScreen.onBack()) return;
            closeSettings();
            return;
        }
        if (tab >= 0 && tabs[tab] != null && tabs[tab].onBack()) return;
        if (tab != TAB_COMMAND) {
            select(TAB_COMMAND, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // Theme
    // ------------------------------------------------------------------

    private void styleSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(Theme.flatten(theme.topbar, theme.bg));
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (!theme.isDark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!theme.isDark && Build.VERSION.SDK_INT >= 27) {
            flags |= 0x10; // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR (API 27)
            w.setNavigationBarColor(Theme.flatten(theme.nav, theme.bg));
        } else {
            w.setNavigationBarColor(theme.isDark ? Theme.flatten(theme.nav, theme.bg) : 0xFF000000);
        }
        decor.setSystemUiVisibility(flags);
    }

    /** "system" | "cyber" | "light" | "dark". Recreates the screen when the look changes. */
    public void applyTheme(String pref) {
        engine.settings.setTheme(pref);
        engine.log("info", "Appearance · " + pref.toUpperCase(Locale.US));
        if (Theme.resolve(this, pref) != themeId) {
            for (Screen s : built()) s.dispatchActivityStop();
            recreate();
        } else {
            themePref = pref;
            ui.toast(Settings.THEME_SYSTEM.equals(pref) ? "Theme follows the phone" : "Theme: " + pref);
        }
    }

    public void updateScanLine() {
        if (scanLine == null) return;
        boolean on = theme.hud && engine.settings.hudEffects() && !engine.settings.reduceMotion();
        scanLine.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) scanLine.start();
        else scanLine.stop();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private View buildShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Backdrop(theme, theme.hud && engine.settings.hudEffects() ? ui.dp(22) : 0));
        LinearLayout column = ui.vbox();
        column.addView(buildTopBar(), Ui.fillW());
        content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        column.addView(buildNav(), Ui.fillW());
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        scanLine = new Widgets.ScanLine(this, theme.scanColor);
        root.addView(scanLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        banner = ui.text("", 12, theme.inkStrong, theme.hud ? theme.labelFace : theme.bodySemi);
        if (theme.hud) banner.setLetterSpacing(0.08f);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(ui.dp(14), ui.dp(10), ui.dp(16), ui.dp(10));
        banner.setCompoundDrawablePadding(ui.dp(10));
        banner.setMaxLines(2);
        banner.setEllipsize(TextUtils.TruncateAt.END);
        banner.setVisibility(View.GONE);
        banner.setElevation(ui.dp(6));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        blp.setMargins(ui.dp(12), ui.dp(66), ui.dp(12), 0);
        root.addView(banner, blp);
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = ui.hbox();
        bar.setPadding(ui.dp(14), ui.dp(9), ui.dp(4), ui.dp(9));
        bar.setMinimumHeight(ui.dp(58));
        if (theme.hud) {
            bar.setBackground(Panel.builder().fill(theme.topbar).build());
        } else {
            bar.setBackgroundColor(theme.topbar);
        }
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(new IconDrawable(IconDrawable.LOGO, theme.accent, theme.logoCore, ui.dp(28)));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(logo, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));

        LinearLayout titles = ui.vbox();
        titles.setPadding(ui.dp(11), 0, ui.dp(8), 0);
        TextView title = ui.text("OMNI-DECK", theme.hud ? 15 : 17, theme.inkStrong, theme.display);
        title.setLetterSpacing(theme.hud ? 0.14f : -0.01f);
        titles.addView(title);
        // "LINK ·" in micro-caps, then the address in mono: digits stay legible
        // and a long address loses its middle, never the port.
        LinearLayout sub = ui.hbox();
        sub.setPadding(0, ui.dp(4), 0, 0);
        subtitle = ui.label("");
        sub.addView(subtitle, Ui.wrap());
        subtitleAddr = ui.readout("", 10.5f, theme.dim);
        subtitleAddr.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        subtitleAddr.setPadding(ui.dp(5), 0, 0, 0);
        sub.addView(subtitleAddr, Ui.weight(1));
        titles.addView(sub, Ui.fillW());
        bar.addView(titles, Ui.weight(1));

        stopSpeech = ui.iconButton(IconDrawable.STOP_CIRCLE, "Stop speaking", theme.accent, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSpeaking();
            }
        });
        stopSpeech.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        stopSpeech.setVisibility(View.GONE);
        bar.addView(stopSpeech);

        pill = ui.hbox();
        pill.setPadding(ui.dp(9), ui.dp(6), ui.dp(10), ui.dp(6));
        dot = new Widgets.StatusDot(this);
        pill.addView(dot, new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)));
        pillText = ui.text("", theme.hud ? 10 : 12, theme.ok, theme.hud ? theme.labelFace : theme.bodySemi);
        pillText.setLetterSpacing(theme.hud ? 0.12f : 0.02f);
        pillText.setPadding(ui.dp(5), 0, 0, 0);
        pill.addView(pillText);
        latencyText = ui.readout("", 11, theme.dim);
        latencyText.setPadding(ui.dp(7), 0, 0, 0);
        // A fixed-width readout (LAN latency is one or two digits): the pill doesn't jitter per sample.
        latencyText.setMinWidth(ui.dp(7) + Math.round(latencyText.getPaint().measureText("99 ms")));
        latencyText.setGravity(Gravity.END);
        pill.addView(latencyText);
        pill.setContentDescription("Connection status");
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                showConnection();
            }
        });
        bar.addView(pill);

        ImageView gear = ui.iconButton(IconDrawable.SETTINGS, "Settings", theme.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (settingsOpen) closeSettings();
                else openSettings();
            }
        });
        bar.addView(gear);

        LinearLayout wrap = ui.vbox();
        wrap.addView(bar, Ui.fillW());
        View line = new View(this);
        if (theme.hud) {
            // The web top bar's "accent seam": strongest at the left, fading out.
            line.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Theme.alpha(theme.accent, 0xA6), Theme.alpha(theme.accent, 0x38), 0}));
        } else {
            line.setBackgroundColor(theme.isDark ? theme.hair : theme.edgeStrong);
        }
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, ui.dp(theme.hud ? 1 : 1.5f))));
        if (!theme.isDark) {
            wrap.setBackgroundColor(theme.topbar);
            wrap.setElevation(ui.dp(2));
        }
        return wrap;
    }

    private View buildNav() {
        LinearLayout wrap = ui.vbox();
        View line = new View(this);
        line.setBackgroundColor(theme.hud ? theme.hair : theme.isDark ? theme.hair : theme.edge);
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1))));
        LinearLayout nav = ui.hbox();
        nav.setBackgroundColor(theme.nav);
        nav.setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(2));
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            FrameLayout item = new FrameLayout(this);
            LinearLayout col = ui.vbox();
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(0, ui.dp(8), 0, ui.dp(7));

            FrameLayout iconWrap = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageDrawable(new IconDrawable(TAB_ICONS[i], theme.dim, theme.dim, ui.dp(22)));
            navIcons[i] = icon;
            iconWrap.addView(icon, new FrameLayout.LayoutParams(ui.dp(58), ui.dp(30), Gravity.CENTER));
            View badge = new View(this);
            badge.setBackground(ui.rounded(theme.hud ? theme.accent : theme.id == Theme.DARK ? theme.data
                    : theme.accent, 0, 4));
            badge.setVisibility(View.GONE);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ui.dp(8), ui.dp(8), Gravity.TOP | Gravity.END);
            bl.setMargins(0, ui.dp(3), ui.dp(12), 0);
            iconWrap.addView(badge, bl);
            navBadges[i] = badge;
            col.addView(iconWrap, new LinearLayout.LayoutParams(ui.dp(58), ui.dp(30)));

            TextView label = ui.text(theme.hud ? TAB_NAMES[i].toUpperCase(Locale.US) : TAB_NAMES[i],
                    theme.hud ? 8.5f : 11.5f, theme.dim, theme.hud ? theme.labelFace : theme.bodySemi);
            label.setLetterSpacing(theme.hud ? 0.14f : 0.01f);
            label.setPadding(0, ui.dp(3), 0, 0);
            label.setGravity(Gravity.CENTER);
            navLabels[i] = label;
            col.addView(label, Ui.wrap());
            item.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            View mark = new View(this);
            if (theme.hud) {
                mark.setBackground(ui.rounded(theme.accent, 0, 1));
                FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(ui.dp(28), ui.dp(2), Gravity.TOP
                        | Gravity.CENTER_HORIZONTAL);
                item.addView(mark, ml);
            }
            navMarks[i] = mark;

            item.setContentDescription(TAB_NAMES[i]);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    if (settingsOpen) closeSettings();
                    select(idx, true);
                }
            });
            navItems[i] = item;
            nav.addView(item, Ui.weight(1));
        }
        wrap.addView(nav, Ui.fillW());
        return wrap;
    }

    private void updateNav() {
        for (int i = 0; i < 4; i++) {
            boolean on = i == tab && !settingsOpen;
            int color = on ? theme.accent : theme.dim;
            ((IconDrawable) navIcons[i].getDrawable()).setColors(color, color);
            navLabels[i].setTextColor(on ? (theme.hud ? theme.accent : theme.inkStrong) : theme.dim);
            if (theme.hud) {
                navMarks[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            } else {
                navIcons[i].setBackground(on ? ui.rounded(theme.accentSoft, 0, 15) : null);
            }
            navItems[i].setSelected(on);
        }
        updateSpeakingUi();
    }

    public void setBadge(int tabIndex, boolean on) {
        if (navBadges[tabIndex] == null) return;
        navBadges[tabIndex].setVisibility(on ? View.VISIBLE : View.GONE);
        // TalkBack: "Comms, new reply" (View.setStateDescription is API 30).
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                View.class.getMethod("setStateDescription", CharSequence.class)
                        .invoke(navItems[tabIndex], on ? "new reply" : null);
            } catch (Exception ignored) {
            }
        }
    }

    /** Whether a tab's "unread" dot is lit. */
    public boolean hasBadge(int tabIndex) {
        return navBadges[tabIndex] != null && navBadges[tabIndex].getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    public void select(int i, boolean animate) {
        if (i == tab && !settingsOpen) return;
        if (settingsOpen) closeSettings();
        if (tab >= 0 && tabs[tab] != null && tabs[tab].isBuilt()) {
            tabs[tab].view().setVisibility(View.GONE);
            tabs[tab].hide();
        }
        tab = i;
        Screen s = tabScreen(i);
        View v = s.view();
        if (v.getParent() == null) {
            content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        v.setVisibility(View.VISIBLE);
        if (animate && !engine.settings.reduceMotion()) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(140).start();
        } else {
            v.setAlpha(1f);
        }
        s.show();
        if (i == TAB_COMMS) setBadge(TAB_COMMS, false);
        engine.settings.setLastTab(i);
        updateNav();
    }

    /** Opens Settings on one section (its title, e.g. "Performance"). */
    public void openSettings(String section) {
        openSettings();
        if (settingsScreen != null) settingsScreen.showSection(section);
    }

    public void openSettings() {
        if (settingsOpen) return;
        if (settingsScreen == null) settingsScreen = new SettingsScreen(this);
        View v = settingsScreen.view();
        if (v.getParent() == null) {
            content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (tab >= 0 && tabs[tab] != null && tabs[tab].isBuilt()) {
            tabs[tab].view().setVisibility(View.GONE);
            tabs[tab].hide();
        }
        v.setVisibility(View.VISIBLE);
        v.bringToFront();
        settingsOpen = true;
        settingsScreen.show();
        updateNav();
    }

    public void closeSettings() {
        if (!settingsOpen) return;
        settingsOpen = false;
        settingsScreen.view().setVisibility(View.GONE);
        settingsScreen.hide();
        if (tab >= 0) {
            Screen s = tabScreen(tab);
            s.view().setVisibility(View.VISIBLE);
            s.show();
            // Back on the chat: a reply that finished while Settings was open has now been seen.
            if (tab == TAB_COMMS) setBadge(TAB_COMMS, false);
        }
        updateNav();
    }

    // ------------------------------------------------------------------
    // Engine.Listener → pages
    // ------------------------------------------------------------------

    private void updateDot() {
        Engine.State s = engine.state();
        dot.setPulsing(started && s != Engine.State.OFFLINE && !engine.settings.reduceMotion());
    }

    @Override
    public void onStateChanged() {
        Engine.State s = engine.state();
        int c = s == Engine.State.ONLINE ? theme.ok : s == Engine.State.SEARCHING ? theme.warn : theme.danger;
        dot.setColor(c);
        updateDot();
        pillText.setText(s == Engine.State.ONLINE ? "ONLINE" : s == Engine.State.SEARCHING ? "SCANNING" : "OFFLINE");
        pillText.setTextColor(c);
        pill.setBackground(ui.rounded(Theme.alpha(c, theme.isDark ? 0x1A : 0x14), Theme.alpha(c, 0x66),
                theme.hud ? 6 : 16));
        ServerInfo srv = engine.server();
        if (s == Engine.State.ONLINE && srv != null) {
            subtitle.setText(theme.label("Link ·"));
            subtitleAddr.setText(srv.label());
            subtitleAddr.setVisibility(View.VISIBLE);
        } else {
            subtitle.setText(theme.label(s == Engine.State.SEARCHING ? "Scanning network…" : "No link"));
            subtitleAddr.setText("");
            subtitleAddr.setVisibility(View.GONE);
        }
        if (s != Engine.State.ONLINE) latencyText.setText("");
        if (lastState != s) {
            if (s == Engine.State.ONLINE && srv != null) {
                showBanner(IconDrawable.WIFI, theme.ok, "Link established · Ollama "
                        + (srv.version.length() > 0 ? srv.version + " " : "") + "@ " + srv.label());
            } else if (lastState == Engine.State.ONLINE) {
                // The first step away from ONLINE (usually SEARCHING: "reconnecting…").
                showBanner(IconDrawable.WIFI, theme.danger, "Link lost — searching for your AI");
            }
            lastState = s;
        }
        for (Screen sc : built()) sc.onStateChanged();
    }

    @Override
    public void onTelemetry() {
        double lat = engine.telemetry.latencyMs.last();
        if (engine.state() == Engine.State.ONLINE && !Double.isNaN(lat)) {
            latencyText.setText(Math.round(lat) + " ms");
        }
        for (Screen sc : built()) sc.onTelemetry();
    }

    @Override
    public void onLog(Telemetry.Event e) {
        for (Screen sc : built()) sc.onLog(e);
    }

    @Override
    public void onPull() {
        for (Screen sc : built()) sc.onPull();
    }

    @Override
    public void onConversationReplaced() {
        for (Screen sc : built()) sc.onConversationReplaced();
    }

    @Override
    public void onMessageAdded(ChatMessage m) {
        for (Screen sc : built()) sc.onMessageAdded(m);
    }

    @Override
    public void onMessageChanged(ChatMessage m) {
        for (Screen sc : built()) sc.onMessageChanged(m);
        if (m.isAssistant() && !m.streaming && currentTab() != TAB_COMMS) {
            setBadge(TAB_COMMS, true);
        }
    }

    @Override
    public void onMessageRemoved(ChatMessage m) {
        for (Screen sc : built()) sc.onMessageRemoved(m);
    }

    @Override
    public void onBusyChanged() {
        for (Screen sc : built()) sc.onBusyChanged();
    }

    @Override
    public void onInsertText(String text) {
        select(TAB_COMMS, true);
        comms().onInsertText(text);
    }

    @Override
    public void onToast(String text) {
        ui.toast(text);
    }

    /** The AI wants to change something on the PC: the chat shows the approval sheet (whatever tab is open). */
    @Override
    public boolean onToolApproval(ToolApproval request) {
        if (!started || isFinishing() || isDestroyed()) return false;
        return comms().showToolApproval(request);
    }

    // ------------------------------------------------------------------
    // Speech: the "Stop speaking" control
    // ------------------------------------------------------------------

    /**
     * Polls whether the phone is speaking (a reply read aloud, a voice-turn
     * answer, "Read aloud" on a message): shows Stop speaking in the top bar
     * and tells the pages. Runs only while the app is in the foreground.
     */
    private final Runnable speechPoll = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            setSpeaking(speakingOverride != null ? speakingOverride : engine.speaking());
            handler.postDelayed(this, speaking ? 300 : 700);
        }
    };

    /** Tests: stands in for the TTS engine, whose Robolectric shadow never reports speaking. */
    Boolean speakingOverride;

    /** Silences the phone now (the Stop speaking controls). */
    public void stopSpeaking() {
        engine.speechStop();
        if (speakingOverride != null) speakingOverride = false;
        setSpeaking(false);
    }

    private void setSpeaking(boolean s) {
        if (s == speaking) return;
        speaking = s;
        updateSpeakingUi();
        for (Screen sc : built()) sc.onSpeechChanged(s);
    }

    /**
     * The chat header and the Command hero have their own Stop speaking
     * control, so the top bar's shows only on the other pages (one per page).
     */
    private void updateSpeakingUi() {
        if (stopSpeech == null) return;
        int tabNow = currentTab();
        boolean own = tabNow == TAB_COMMS || tabNow == TAB_COMMAND;
        stopSpeech.setVisibility(speaking && !own ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // HUD banner
    // ------------------------------------------------------------------

    private final Runnable hideBanner = new Runnable() {
        @Override
        public void run() {
            if (banner == null) return;
            banner.animate().alpha(0f).translationY(-ui.dp(8)).setDuration(220).withEndAction(new Runnable() {
                @Override
                public void run() {
                    banner.setVisibility(View.GONE);
                }
            }).start();
        }
    };

    /** A short HUD notification under the top bar. */
    public void showBanner(int icon, int color, String text) {
        if (banner == null) return;
        handler.removeCallbacks(hideBanner);
        banner.setText(theme.hud ? text.toUpperCase(Locale.US) : text);
        IconDrawable d = new IconDrawable(icon, color, color, ui.dp(18));
        d.setBounds(0, 0, ui.dp(18), ui.dp(18));
        banner.setCompoundDrawables(d, null, null, null);
        banner.setBackground(theme.hud
                ? Panel.builder().fill(theme.surface2).edge(Theme.alpha(color, 0x80), Math.max(1, ui.dp(1)))
                .radius(ui.dp(10)).brackets(ui.dp(7), ui.dp(1.3f), Theme.alpha(color, 0xB3)).build()
                : ui.rounded(theme.surface, theme.edge, 12));
        banner.setVisibility(View.VISIBLE);
        banner.animate().cancel();
        if (engine.settings.reduceMotion()) {
            banner.setAlpha(1f);
            banner.setTranslationY(0);
        } else {
            banner.setAlpha(0f);
            banner.setTranslationY(-ui.dp(8));
            banner.animate().alpha(1f).translationY(0).setDuration(200).start();
        }
        handler.postDelayed(hideBanner, 2800);
    }

    // ------------------------------------------------------------------
    // Activity results: voice input, image picking
    // ------------------------------------------------------------------

    /** Starts an activity for a result; {@code h} gets it (lost if the activity is recreated meanwhile). */
    public boolean startForResult(Intent intent, ResultHandler h) {
        return startForResult(intent, nextRequest++, h);
    }

    private boolean startForResult(Intent intent, int code, ResultHandler h) {
        results.put(code, h);
        try {
            startActivityForResult(intent, code);
            return true;
        } catch (ActivityNotFoundException e) {
            results.remove(code);
            return false;
        } catch (RuntimeException e) {
            results.remove(code);
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ResultHandler h = results.remove(requestCode);
        if (h != null) {
            h.onResult(resultCode, data);
            return;
        }
        // No callback: this is a new instance (recreated while the recognizer or
        // picker was open). Voice and photos both belong to the chat.
        if (requestCode == REQ_VOICE) {
            voiceResult(resultCode, data, new TextResult() {
                @Override
                public void onText(String text) {
                    select(TAB_COMMS, false);
                    comms().submitVoice(text);
                }
            });
        } else if (requestCode == REQ_IMAGE) {
            imageResult(resultCode, data, new ImageResult() {
                @Override
                public void onImage(String base64, Bitmap preview) {
                    select(TAB_COMMS, false);
                    comms().addAttachment(base64, preview);
                }
            });
        }
    }

    /** Opens the phone's speech recognizer; the recognized text goes to {@code r}. */
    public void startVoice(String prompt, final TextResult r) {
        engine.speechStop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt == null ? "Speak to OMNI" : prompt);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        boolean ok = startForResult(i, REQ_VOICE, new ResultHandler() {
            @Override
            public void onResult(int resultCode, Intent data) {
                voiceResult(resultCode, data, r);
            }
        });
        if (!ok) ui.toast("This phone has no speech recognizer (install or enable Google voice typing).");
    }

    private static void voiceResult(int resultCode, Intent data, TextResult r) {
        if (resultCode != RESULT_OK || data == null) return;
        ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (res != null && !res.isEmpty() && res.get(0).trim().length() > 0) r.onText(res.get(0).trim());
    }

    /** Opens the photo picker and returns a downscaled base64 JPEG. */
    public void pickImage(final ImageResult r) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        boolean ok = startForResult(Intent.createChooser(i, "Attach an image"), REQ_IMAGE, new ResultHandler() {
            @Override
            public void onResult(int resultCode, Intent data) {
                imageResult(resultCode, data, r);
            }
        });
        if (!ok) ui.toast("No app to pick images with.");
    }

    private void imageResult(int resultCode, Intent data, ImageResult r) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        encodeImage(data.getData(), r);
    }

    /** Reads and downscales an image off the main thread (a picked photo, a share). */
    void encodeImage(final Uri uri, final ImageResult r) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ImageUtil.Encoded enc = ImageUtil.encode(getContentResolver(), uri);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (enc == null) ui.toast("Couldn't read that image.");
                        else r.onImage(enc.base64, enc.preview);
                    }
                });
            }
        }, "omni-image").start();
    }

    /**
     * Re-encodes a stored base64 image (a PC screenshot is a full-size PNG)
     * as a JPEG of at most {@code maxSide} px, off the main thread.
     */
    public void downscaleImage(final String base64, final int maxSide, final ImageResult r) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = null;
                Bitmap bmp = ImageUtil.decode(base64, maxSide);
                if (bmp != null) {
                    try {
                        int w = bmp.getWidth(), h = bmp.getHeight();
                        float scale = Math.min(1f, maxSide / (float) Math.max(w, h));
                        if (scale < 1f) {
                            Bitmap s = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * scale)),
                                    Math.max(1, Math.round(h * scale)), true);
                            if (s != bmp) bmp.recycle();
                            bmp = s;
                        }
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                        out = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                    } catch (RuntimeException e) {
                        out = null;
                    } catch (OutOfMemoryError e) {
                        out = null;
                    }
                }
                final String jpeg = out;
                final Bitmap preview = bmp;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (jpeg == null) ui.toast("Couldn't read that image.");
                        else r.onImage(jpeg, preview);
                    }
                });
            }
        }, "omni-image").start();
    }

    // ------------------------------------------------------------------
    // What other apps and launcher shortcuts hand in
    // ------------------------------------------------------------------

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        // A tapped notification names the tab it belongs to (reply → Comms, download → Models).
        int notifTab = intent.getIntExtra(Notifier.EXTRA_TAB, -1);
        if (notifTab >= 0) {
            intent.removeExtra(Notifier.EXTRA_TAB);
            if (settingsOpen) closeSettings();
            select(Math.max(0, Math.min(3, notifTab)), false);
            return;
        }
        String sc = intent.getStringExtra(EXTRA_SHORTCUT);
        if (sc != null) {
            intent.removeExtra(EXTRA_SHORTCUT);
            handleShortcut(sc);
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) handleShare(intent);
    }

    /** Text, text files and images shared from another app land in the chat composer. */
    private void handleShare(Intent intent) {
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        List<Uri> streams = new ArrayList<Uri>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Parcelable p : list) {
                    if (p instanceof Uri) streams.add((Uri) p);
                }
            }
        } else {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) streams.add((Uri) p);
        }
        String type = intent.getType() == null ? "" : intent.getType().toLowerCase(Locale.US);
        // Handled once: the intent object is kept and would otherwise fire again.
        intent.setAction(Intent.ACTION_MAIN);
        boolean any = text != null && text.length() > 0;
        if (!any && streams.isEmpty()) return;
        select(TAB_COMMS, false);
        if (any) comms().onInsertText(text.toString());
        int images = 0;
        int skipped = 0;
        for (Uri u : streams) {
            String t = getContentResolver().getType(u);
            t = t == null ? type : t.toLowerCase(Locale.US);
            if (t.startsWith("image/")) {
                if (images++ >= MAX_IMAGES) {
                    skipped++;
                    continue;
                }
                encodeImage(u, new ImageResult() {
                    @Override
                    public void onImage(String base64, Bitmap preview) {
                        comms().addAttachment(base64, preview);
                    }
                });
            } else if (t.startsWith("text/") && !any) {
                readSharedText(u);
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            ui.toast(images > MAX_IMAGES ? "Up to " + MAX_IMAGES + " images per message."
                    : "Only text and images can be shared into OMNI-DECK.");
        }
    }

    /** A shared text file: read (capped) off the main thread, then into the composer. */
    private void readSharedText(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = null;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in != null) {
                        Reader rd = new InputStreamReader(in, "UTF-8");
                        try {
                            StringBuilder sb = new StringBuilder();
                            char[] buf = new char[4096];
                            int n;
                            while (sb.length() < SHARED_TEXT_MAX && (n = rd.read(buf)) > 0) sb.append(buf, 0, n);
                            if (sb.length() > SHARED_TEXT_MAX) sb.setLength(SHARED_TEXT_MAX);
                            out = sb.toString();
                        } finally {
                            rd.close();
                        }
                    }
                } catch (Exception e) {
                    out = null;
                }
                final String text = out;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (text == null || text.trim().length() == 0) ui.toast("Couldn't read that file.");
                        else comms().onInsertText(text);
                    }
                });
            }
        }, "omni-share").start();
    }

    private void handleShortcut(String id) {
        if (SHORTCUT_TALK.equals(id)) {
            select(TAB_COMMS, false);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isDestroyed()) comms().talk();
                }
            });
        } else if (SHORTCUT_NEW_CHAT.equals(id)) {
            engine.newChat();
            select(TAB_COMMS, false);
            comms().focusComposer();
        } else if (SHORTCUT_SCREENSHOT.equals(id)) {
            select(TAB_COMMS, false);
            commander.run("/shot");
        } else if (SHORTCUT_COMMAND.equals(id)) {
            if (settingsOpen) closeSettings();
            select(TAB_COMMAND, false);
        }
    }

    /**
     * Long-press launcher shortcuts: Talk to OMNI, New chat, PC screenshot,
     * Command center. Published at runtime (API 25+) — the API-23 toolchain
     * can't compile a static shortcuts.xml.
     */
    private void publishShortcuts() {
        if (shortcutsPublished || Build.VERSION.SDK_INT < 25) return;
        shortcutsPublished = true;
        try {
            Object sm = getSystemService("shortcut");
            if (sm == null) return;
            Class<?> b = Class.forName("android.content.pm.ShortcutInfo$Builder");
            List<Object> list = new ArrayList<Object>();
            list.add(shortcut(b, SHORTCUT_TALK, "Talk", "Talk to OMNI", R.drawable.sc_talk));
            list.add(shortcut(b, SHORTCUT_NEW_CHAT, "New chat", "Start a new chat", R.drawable.sc_chat));
            list.add(shortcut(b, SHORTCUT_SCREENSHOT, "PC screenshot", "Capture the PC screen", R.drawable.sc_shot));
            list.add(shortcut(b, SHORTCUT_COMMAND, "Command", "Command center", R.drawable.sc_command));
            sm.getClass().getMethod("setDynamicShortcuts", List.class).invoke(sm, list);
        } catch (Exception e) {
            // Optional: some launchers don't support shortcuts, or it was rate-limited.
        } catch (LinkageError e) {
            // Same.
        }
    }

    private Object shortcut(Class<?> b, String id, String shortLabel, String longLabel, int icon) throws Exception {
        Object builder = b.getConstructor(Context.class, String.class).newInstance(this, id);
        b.getMethod("setShortLabel", CharSequence.class).invoke(builder, shortLabel);
        b.getMethod("setLongLabel", CharSequence.class).invoke(builder, longLabel);
        b.getMethod("setIcon", Icon.class).invoke(builder, Icon.createWithResource(this, icon));
        Intent i = new Intent(Intent.ACTION_VIEW).setClassName(getPackageName(), MainActivity.class.getName())
                .putExtra(EXTRA_SHORTCUT, id);
        b.getMethod("setIntent", Intent.class).invoke(builder, i);
        return b.getMethod("build").invoke(builder);
    }

    // ------------------------------------------------------------------
    // Notifications permission (Android 13+)
    // ------------------------------------------------------------------

    /** Asks for permission to post notifications (API 33+; a no-op when granted or on older phones). */
    public void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(PERM_NOTIFY) == PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{PERM_NOTIFY}, REQ_NOTIFY);
        } catch (RuntimeException ignored) {
        }
    }

    /** Called when the user sends a message: the first time, ask for notifications (if they're on). */
    public void onUserSent() {
        if (Build.VERSION.SDK_INT < 33 || !engine.settings.notifications()) return;
        SharedPreferences sp = getSharedPreferences("omnideck-shell", MODE_PRIVATE);
        if (sp.getBoolean("notify_asked", false)) return;
        sp.edit().putBoolean("notify_asked", true).apply();
        ensureNotificationPermission();
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    public String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            ui.toast("Copied");
        }
    }

    public void share(String subject, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(i, "Share"));
        } catch (RuntimeException e) {
            ui.toast("No app can share text.");
        }
    }

    /** Status pill → connection details, rescan, set address. */
    public void showConnection() {
        Engine.State s = engine.state();
        ServerInfo srv = engine.server();
        StringBuilder sb = new StringBuilder();
        if (s == Engine.State.ONLINE && srv != null) {
            sb.append("Connected to Ollama").append(srv.version.length() > 0 ? " " + srv.version : "")
                    .append(" at ").append(srv.label()).append(".\n");
            int loaded = 0;
            for (ModelInfo m : engine.models()) {
                if (engine.isLoaded(m.name)) loaded++;
            }
            sb.append(engine.models().size()).append(" models installed, ").append(loaded).append(" loaded.");
            double lat = engine.telemetry.latencyMs.last();
            if (!Double.isNaN(lat)) sb.append(" Latency ").append(Math.round(lat)).append(" ms.");
        } else {
            sb.append(engine.stateDetail());
        }
        Sheet sheet = ui.sheet("Link", s == Engine.State.ONLINE ? "Connected"
                : s == Engine.State.SEARCHING ? "Searching…" : "Not connected");
        sheet.eyebrowColor(s == Engine.State.ONLINE ? theme.ok : s == Engine.State.SEARCHING ? theme.warn
                : theme.danger);
        sheet.message(sb.toString());
        String manual = engine.settings.server();
        sheet.body.addView(detailRow("Address", manual.length() > 0 ? manual + " (manual)" : "auto-detect"));
        sheet.body.addView(detailRow("Phone network", Net.describe(engine.subnets())));
        sheet.body.addView(detailRow("PC bridge", "port " + engine.settings.bridgePort()
                + (engine.bridgePaired() ? " · paired" : " · not paired")));
        sheet.neutral("Set address", new Runnable() {
            @Override
            public void run() {
                promptServerAddress();
            }
        });
        sheet.negative("Close", null);
        sheet.positive("Rescan", Ui.PRIMARY, new Runnable() {
            @Override
            public void run() {
                engine.discover(true);
            }
        });
        sheet.show();
    }

    /** A spec-sheet line for dialogs: micro-caps key, mono value. */
    private View detailRow(String key, String value) {
        LinearLayout r = ui.hbox();
        r.setPadding(0, ui.dp(9), 0, 0);
        TextView k = ui.label(key);
        r.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
        TextView v = ui.text(value, 13, theme.ink, theme.mono);
        v.setGravity(Gravity.END);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        return r;
    }

    public void promptServerAddress() {
        ui.prompt("AI address", "e.g. 192.168.1.20 or 192.168.1.20:11434 — blank = auto-detect",
                engine.settings.server(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                new Ui.TextResult() {
                    @Override
                    public void onText(String t) {
                        String v = t.trim();
                        if (v.length() > 0 && HostPort.parse(v, OllamaClient.DEFAULT_PORT) == null) {
                            ui.toast("That isn't a valid address.");
                            return;
                        }
                        engine.setServer(v);
                    }
                });
    }
}
