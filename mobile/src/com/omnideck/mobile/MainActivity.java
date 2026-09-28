package com.omnideck.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.TextUtils;
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
import com.omnideck.mobile.screens.CommandScreen;
import com.omnideck.mobile.screens.CommsScreen;
import com.omnideck.mobile.screens.ModelsScreen;
import com.omnideck.mobile.screens.PcScreen;
import com.omnideck.mobile.screens.Screen;
import com.omnideck.mobile.screens.SettingsScreen;
import com.omnideck.mobile.ui.Backdrop;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.Panel;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shell: HUD top bar (link status), the page area, and the command bar
 * (COMMAND · COMMS · MODELS · PC). Owns theming, navigation, activity
 * results (voice input, image picking) and forwards Engine events to pages.
 */
public final class MainActivity extends Activity implements Engine.Listener {
    public static final int TAB_COMMAND = 0;
    public static final int TAB_COMMS = 1;
    public static final int TAB_MODELS = 2;
    public static final int TAB_PC = 3;
    public static final String[] TAB_NAMES = {"Command", "Comms", "Models", "PC"};
    static final int[] TAB_ICONS = {IconDrawable.NAV_COMMAND, IconDrawable.NAV_COMMS, IconDrawable.NAV_MODELS,
            IconDrawable.NAV_PC};

    private Engine engine;
    private Theme theme;
    private Ui ui;
    private String themePref;
    private int themeId;
    private Commander commander;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout content;
    private final Screen[] tabs = new Screen[4];
    private SettingsScreen settingsScreen;
    private boolean settingsOpen;
    private int tab = -1;

    // Top bar
    private TextView subtitle;
    private LinearLayout pill;
    private Widgets.StatusDot dot;
    private TextView pillText;
    private TextView latencyText;
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

    // Activity results
    public interface ResultHandler {
        void onResult(int resultCode, Intent data);
    }

    public interface TextResult {
        void onText(String text);
    }

    public interface ImageResult {
        void onImage(String base64, android.graphics.Bitmap preview);
    }

    private final Map<Integer, ResultHandler> results = new HashMap<Integer, ResultHandler>();
    private int nextRequest = 7100;

    // ------------------------------------------------------------------
    // Accessors for screens
    // ------------------------------------------------------------------

    public Engine engine() {
        return engine;
    }

    public Ui ui() {
        return ui;
    }

    public Theme theme() {
        return theme;
    }

    public Commander commander() {
        return commander;
    }

    public int currentTab() {
        return settingsOpen ? -1 : tab;
    }

    public CommsScreen comms() {
        return (CommsScreen) tabScreen(TAB_COMMS);
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
        handleShareIntent(getIntent());
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
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        intent.setAction(Intent.ACTION_MAIN);
        if (shared != null && shared.length() > 0) {
            select(TAB_COMMS, false);
            comms().onInsertText(shared.toString());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.setListener(this);
        if (!themePref.equals(engine.settings.theme()) || Theme.resolve(this, engine.settings.theme()) != themeId) {
            recreate();
            return;
        }
        ui.haptics = engine.settings.haptics();
        engine.setVisible(true);
        for (Screen s : built()) s.onActivityStart();
        updateScanLine();
    }

    @Override
    protected void onStop() {
        super.onStop();
        for (Screen s : built()) s.onActivityStop();
        engine.setVisible(false);
        if (scanLine != null) scanLine.stop();
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
        w.setStatusBarColor(opaque(theme.topbar, theme.bg));
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (!theme.isDark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!theme.isDark && Build.VERSION.SDK_INT >= 27) {
            flags |= 0x10; // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR (API 27)
            w.setNavigationBarColor(opaque(theme.nav, theme.bg));
        } else {
            w.setNavigationBarColor(theme.isDark ? opaque(theme.nav, theme.bg) : 0xFF000000);
        }
        decor.setSystemUiVisibility(flags);
    }

    /** Blends a translucent color over a base so system bars get a solid color. */
    static int opaque(int c, int base) {
        int a = (c >>> 24) & 0xFF;
        if (a == 0xFF) return c;
        int r = (((c >> 16) & 0xFF) * a + ((base >> 16) & 0xFF) * (255 - a)) / 255;
        int g = (((c >> 8) & 0xFF) * a + ((base >> 8) & 0xFF) * (255 - a)) / 255;
        int b = ((c & 0xFF) * a + (base & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** "system" | "cyber" | "light" | "dark". Recreates the screen when the look changes. */
    public void applyTheme(String pref) {
        engine.settings.setTheme(pref);
        engine.log("info", "Appearance · " + pref.toUpperCase(Locale.US));
        if (Theme.resolve(this, pref) != themeId) {
            for (Screen s : built()) s.onActivityStop();
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
        logo.setImageDrawable(new IconDrawable(IconDrawable.LOGO, theme.hud ? theme.accent : theme.accent,
                theme.hud ? theme.inkStrong : theme.accent2, ui.dp(28)));
        bar.addView(logo, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));

        LinearLayout titles = ui.vbox();
        titles.setPadding(ui.dp(11), 0, ui.dp(8), 0);
        TextView title = ui.text("OMNI-DECK", theme.hud ? 15 : 17, theme.inkStrong, theme.display);
        title.setLetterSpacing(theme.hud ? 0.14f : -0.01f);
        titles.addView(title);
        subtitle = ui.label("");
        subtitle.setPadding(0, ui.dp(4), 0, 0);
        titles.addView(subtitle);
        bar.addView(titles, Ui.weight(1));

        pill = ui.hbox();
        pill.setPadding(ui.dp(9), ui.dp(6), ui.dp(11), ui.dp(6));
        dot = new Widgets.StatusDot(this);
        pill.addView(dot, new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)));
        pillText = ui.text("", theme.hud ? 10 : 12, theme.ok, theme.hud ? theme.labelFace : theme.bodySemi);
        pillText.setLetterSpacing(theme.hud ? 0.12f : 0.02f);
        pillText.setPadding(ui.dp(5), 0, 0, 0);
        pill.addView(pillText);
        latencyText = ui.readout("", 11, theme.dim);
        latencyText.setPadding(ui.dp(7), 0, 0, 0);
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
            badge.setBackground(ui.rounded(theme.hud ? theme.accent : theme.accent2, 0, 4));
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
    }

    public void setBadge(int tabIndex, boolean on) {
        if (navBadges[tabIndex] != null) navBadges[tabIndex].setVisibility(on ? View.VISIBLE : View.GONE);
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
        }
        updateNav();
    }

    // ------------------------------------------------------------------
    // Engine.Listener → pages
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged() {
        Engine.State s = engine.state();
        int c = s == Engine.State.ONLINE ? theme.ok : s == Engine.State.SEARCHING ? theme.warn : theme.danger;
        dot.setColor(c);
        dot.setPulsing(s != Engine.State.OFFLINE && !engine.settings.reduceMotion());
        pillText.setText(s == Engine.State.ONLINE ? "ONLINE" : s == Engine.State.SEARCHING ? "SCANNING" : "OFFLINE");
        pillText.setTextColor(c);
        pill.setBackground(ui.rounded(Theme.alpha(c, theme.isDark ? 0x1A : 0x14), Theme.alpha(c, 0x66),
                theme.hud ? 6 : 16));
        ServerInfo srv = engine.server();
        if (s == Engine.State.ONLINE && srv != null) {
            subtitle.setText(theme.label("Link · " + srv.label()));
        } else if (s == Engine.State.SEARCHING) {
            subtitle.setText(theme.label("Scanning network…"));
        } else {
            subtitle.setText(theme.label("No link"));
        }
        if (s != Engine.State.ONLINE) latencyText.setText("");
        if (lastState != s) {
            if (s == Engine.State.ONLINE && srv != null) {
                showBanner(IconDrawable.WIFI, theme.ok, "Link established · Ollama "
                        + (srv.version.length() > 0 ? srv.version + " " : "") + "@ " + srv.label());
            } else if (s == Engine.State.OFFLINE && lastState == Engine.State.ONLINE) {
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
            latencyText.setText(Math.round(lat) + "ms");
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

    public boolean startForResult(Intent intent, ResultHandler h) {
        int code = nextRequest++;
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
        if (h != null) h.onResult(resultCode, data);
    }

    /** Opens the phone's speech recognizer; the recognized text goes to {@code r}. */
    public void startVoice(String prompt, final TextResult r) {
        engine.speechStop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt == null ? "Speak to OMNI" : prompt);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        boolean ok = startForResult(i, new ResultHandler() {
            @Override
            public void onResult(int resultCode, Intent data) {
                if (resultCode != RESULT_OK || data == null) return;
                ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (res != null && !res.isEmpty() && res.get(0).trim().length() > 0) r.onText(res.get(0).trim());
            }
        });
        if (!ok) ui.toast("This phone has no speech recognizer (install or enable Google voice typing).");
    }

    /** Opens the photo picker and returns a downscaled base64 JPEG. */
    public void pickImage(final ImageResult r) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        boolean ok = startForResult(Intent.createChooser(i, "Attach an image"), new ResultHandler() {
            @Override
            public void onResult(int resultCode, Intent data) {
                if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
                final Uri uri = data.getData();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final ImageUtil.Encoded enc = ImageUtil.encode(getContentResolver(), uri);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (enc == null) ui.toast("Couldn't read that image.");
                                else r.onImage(enc.base64, enc.preview);
                            }
                        });
                    }
                }, "omni-image").start();
            }
        });
        if (!ok) ui.toast("No app to pick images with.");
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
                    .append(" at ").append(srv.label()).append(".\n\n");
            int loaded = 0;
            for (ModelInfo m : engine.models()) {
                if (engine.isLoaded(m.name)) loaded++;
            }
            sb.append(engine.models().size()).append(" models installed, ").append(loaded).append(" loaded.\n");
            double lat = engine.telemetry.latencyMs.last();
            if (!Double.isNaN(lat)) sb.append("Latency ").append(Math.round(lat)).append(" ms.\n");
        } else {
            sb.append(engine.stateDetail()).append("\n\n");
        }
        String manual = engine.settings.server();
        sb.append(manual.length() > 0 ? "Address: " + manual + " (set manually)" : "Address: auto-detect");
        sb.append("\nPhone network: ").append(Net.describe(engine.subnets()));
        sb.append("\nPC bridge: port ").append(engine.settings.bridgePort())
                .append(engine.bridgePaired() ? ", paired" : ", not paired");
        new AlertDialog.Builder(this)
                .setTitle(s == Engine.State.ONLINE ? "Connected" : s == Engine.State.SEARCHING ? "Searching…" : "Not connected")
                .setMessage(sb.toString())
                .setPositiveButton("Rescan", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        engine.discover(true);
                    }
                })
                .setNeutralButton("Set address", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        promptServerAddress();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
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
