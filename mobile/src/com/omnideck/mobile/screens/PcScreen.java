package com.omnideck.mobile.screens;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.MainActivity;
import com.omnideck.mobile.core.BridgeTool;
import com.omnideck.mobile.core.HostPort;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.Vitals;
import com.omnideck.mobile.core.WakeOnLan;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.PcFlow;
import com.omnideck.mobile.ui.PcKit;
import com.omnideck.mobile.ui.PcLink;
import com.omnideck.mobile.ui.PcSkeleton;
import com.omnideck.mobile.ui.PcSlider;
import com.omnideck.mobile.ui.PcToolForm;
import com.omnideck.mobile.ui.PcTools;
import com.omnideck.mobile.ui.PcTrace;
import com.omnideck.mobile.ui.Sheet;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PC — remote control of the user's PC through OMNI-DECK's LaunchBridge:
 * the link (its own address, reachable / paired, with guidance for each
 * state), a power strip (wake over the network, lock, sleep, restart, shut
 * down — whatever the PC offers), live vitals (CPU trace, memory, disk,
 * power, uptime), volume, screen capture, the PC clipboard, an app launcher
 * with search and recents, and a tool runner that builds a form for any
 * desktop tool the bridge describes. Vitals poll every 5 s only while the
 * tab is on screen.
 */
public final class PcScreen extends Screen {
    static final long POLL_MS = 5000;
    static final long RETRY_MS = 15000;
    static final int HISTORY = 36;
    static final int APP_LIMIT = 30;
    static final int APPS_SHOWN = 6;
    static final int RECENT_MAX = 6;
    /** Recent apps kept across all PCs (each tagged with its PC). */
    static final int RECENT_KEEP = 24;
    static final String PREFS = "omnideck_pc";
    /** The screenshot lightbox is black in every theme, like any photo viewer. */
    private static final int LIGHTBOX = 0xFF000000;
    private static final int LIGHTBOX_INK = 0xB3FFFFFF;

    // Link states
    static final int CHECKING = 0;
    static final int NO_HOST = 1;
    static final int DOWN = 2;
    static final int UNPAIRED = 3;
    static final int PAIRED = 4;
    static final int REJECTED = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PcLink link = new PcLink();
    private PcKit kit;
    private PcTools toolKit;
    private boolean stopped;
    private boolean destroyed;

    // Link
    private int state = CHECKING;
    private boolean checking;
    private boolean freshShow;
    /** An explicit refresh asked for while a check was running: run it once that one answers. */
    private boolean refreshAgain;
    private String host = "";
    private JSONObject health;
    private long healthMs = -1;
    private String linkError = "";
    private long nextRetryAt;
    private boolean pairing;
    /** The token the bridge last refused ("" = none); cleared as soon as the bridge accepts it again. */
    private String rejectedToken = "";
    /** Bumped whenever the PC behind the tab changes (address, pairing): answers about the old one are dropped. */
    private int gen;
    /** The latest vitals from this PC (its name for the header). */
    private Vitals pcVitals;

    // Views: layout
    private ScrollView scroll;
    // Header
    private Widgets.StatusDot dot;
    private TextView hostTitle;
    private TextView hostAddr;
    private TextView stateChip;
    private Widgets.Meter checkMeter;
    private TextView statLink;
    private TextView statLatency;
    private TextView statVersion;
    private TextView statApps;
    private TextView linkNote;
    // Power strip (in the header card)
    private LinearLayout powerBox;
    private LinearLayout powerKeys;
    private TextView wolStatus;
    private TextView powerNote;
    /** The MAC the strip shows (the Engine learns it from system info): re-render when it changes. */
    private String wolShown = "";
    // Guidance / pairing
    private LinearLayout noHostCard;
    private LinearLayout skeletonBox;
    private final List<PcSkeleton> skeletons = new ArrayList<PcSkeleton>();
    private LinearLayout downCard;
    private TextView downLead;
    private TextView downError;
    private TextView downRetry;
    private LinearLayout pairCard;
    private TextView pairLead;
    private TextView pairBtn;
    private Widgets.Meter pairMeter;
    private TextView pairError;
    // Paired content
    private LinearLayout pairedBox;

    // Vitals
    private LinearLayout vitalsCard;
    private TextView vitalsStatus;
    private Ui.LiveTag vitalsLive;
    private TextView vitalsError;
    private LinearLayout vitalsFail;
    private TextView vitalsRemedy;
    private LinearLayout instruments;
    private TextView sysLine;
    private Widgets.Gauge cpuGauge;
    private TextView cpuValue;
    private TextView cpuStats;
    private TextView cpuWindow;
    private PcTrace cpuTrace;
    private PcKit.Tile ramTile;
    private PcKit.Tile diskTile;
    private PcKit.Tile powerTile;
    private PcKit.Tile uptimeTile;
    private LinearLayout rawBox;
    private TextView rawText;
    private final double[] cpuHistory = new double[HISTORY];
    private int cpuCount;
    private boolean vitalsBusy;
    private long vitalsAskedAt;
    private long vitalsAt;
    private String vitalsErr;

    // Controls
    private LinearLayout controlsCard;
    private LinearLayout volumeSection;
    private View screenRule;
    private LinearLayout screenSection;
    private View clipRule;
    private LinearLayout clipSection;
    private LinearLayout clipRead;

    // Volume
    private TextView volValue;
    private PcSlider slider;
    private LinearLayout presets;
    private TextView muteSeg;
    private TextView volNote;
    /** The level on screen (the latest one asked for); -1 while unknown. */
    private int volLevel = -1;
    /** The level the PC last reported. */
    private int volConfirmed = -1;
    private int preMute = -1;
    private boolean volBusy;
    private int volPending = -1;
    /** The first volume read for this PC has finished (either way): the presets can be used. */
    private boolean volKnown;

    // Screen capture
    private TextView captureBtn;
    private ImageView shotImage;
    private LinearLayout shotEmpty;
    private TextView shotEmptyTitle;
    private TextView shotEmptyText;
    private LinearLayout shotBusyBox;
    private Widgets.Meter shotMeter;
    private TextView shotMeta;
    private LinearLayout shotActions;
    private ShotFrame shotFrame;
    private boolean shotBusy;
    private String shotB64;
    private Bitmap shotBitmap;

    // Clipboard
    private TextView fetchBtn;
    private TextView clipText;
    private TextView clipMeta;
    private LinearLayout clipActions;
    private TextView pushBtn;
    private String clipValue;
    private boolean clipBusy;

    // Launcher
    private TextView appsCount;
    private EditText search;
    private ImageView clearSearch;
    private LinearLayout recentBox;
    private PcFlow recentFlow;
    private TextView listLabel;
    private TextView listCount;
    private LinearLayout appList;
    private LinearLayout appsMore;
    private TextView appsMoreText;
    private ImageView appsMoreChevron;
    private TextView appsHint;
    private TextView appsState;
    private Widgets.Meter appsMeter;
    private JSONArray apps;
    private String appsQuery = "";
    private boolean appsLoaded;
    private boolean appsExpanded;
    private int appsSeq;
    /** The bridge's apps_indexed when the list was loaded: a rebuilt index reloads it. */
    private int appsIndexedAtLoad = -1;

    // Tool runner
    private ImageView toolsChevron;
    private TextView toolsCount;
    private LinearLayout toolsBody;
    private LinearLayout toolsList;
    private TextView toolsState;
    private boolean toolsOpen;
    private boolean toolsBusy;
    private List<BridgeTool> tools;

    public PcScreen(MainActivity a) {
        super(a);
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    @Override
    protected View build() {
        kit = new PcKit(ui);
        toolKit = new PcTools(ui, kit);
        FrameLayout root = new FrameLayout(a);
        scroll = new ScrollView(a);
        LinearLayout col = ui.scrollColumn(scroll, 14, 12);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // Keep the launcher's search field from grabbing focus when the tab opens.
        col.setFocusableInTouchMode(true);
        col.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);

        col.addView(buildHeader(), Ui.fillW());
        noHostCard = buildNoHost();
        col.addView(noHostCard, gap());
        skeletonBox = buildSkeletons();
        col.addView(skeletonBox, gap());
        downCard = buildDown();
        col.addView(downCard, gap());
        pairCard = buildPair();
        col.addView(pairCard, gap());

        pairedBox = ui.vbox();
        pairedBox.addView(buildVitals(), gap());
        pairedBox.addView(buildControls(), gap());
        pairedBox.addView(buildLauncher(), gap());
        pairedBox.addView(buildTools(), gap());
        col.addView(pairedBox, Ui.fillW());

        host = e.bridgeHost();
        showState(host.length() == 0 ? NO_HOST : CHECKING);
        return root;
    }

    private LinearLayout.LayoutParams gap() {
        return ui.margins(Ui.fillW(), 0, 12, 0, 0);
    }

    /** A 1dp hairline between the sub-sections of a card. */
    private View rule() {
        View v = new View(a);
        v.setBackgroundColor(t.hud ? Theme.alpha(t.accent, 0x2E) : t.hairSoft);
        return v;
    }

    private LinearLayout.LayoutParams ruleParams(float top, float bottom) {
        return ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1))),
                0, top, 0, bottom);
    }

    // --- PC LINK header -------------------------------------------------

    private View buildHeader() {
        stateChip = ui.chip("", t.dim);
        LinearLayout card = ui.capCard("PC link", stateChip);
        LinearLayout body = ui.cardBody();

        LinearLayout row = ui.hbox();
        FrameLayout ring = new FrameLayout(a);
        ring.setBackground(ui.rounded(Theme.alpha(t.data, t.isDark ? 0x14 : 0x0F), Theme.alpha(t.data, 0x4D), 10));
        ring.addView(kit.icon(IconDrawable.NAV_PC, t.hud ? t.accent : t.data, 22),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dot = new Widgets.StatusDot(a);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ui.dp(12), ui.dp(12), Gravity.TOP | Gravity.END);
        dlp.setMargins(0, ui.dp(3), ui.dp(3), 0);
        ring.addView(dot, dlp);
        row.addView(ring, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));

        LinearLayout titles = ui.vbox();
        titles.setPadding(ui.dp(12), 0, ui.dp(4), 0);
        hostTitle = ui.title("Your PC", t.hud ? 13.5f : 17);
        hostTitle.setSingleLine(true);
        hostTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(hostTitle, Ui.fillW());
        hostAddr = ui.readout("", 12, t.dim);
        hostAddr.setEllipsize(TextUtils.TruncateAt.END);
        hostAddr.setPadding(0, ui.dp(6), 0, 0);
        titles.addView(hostAddr, Ui.fillW());
        row.addView(titles, Ui.weight(1));
        row.addView(ui.iconButton(IconDrawable.REFRESH, "Refresh PC link", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLink(true);
            }
        }));
        row.addView(ui.iconButton(IconDrawable.MENU, "PC link options", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                linkMenu();
            }
        }));
        body.addView(row, Ui.fillW());

        // A hairline under the title that doubles as the "linking…" sweep.
        checkMeter = new Widgets.Meter(a, t.hud ? Theme.alpha(t.accent, 0x2E) : t.hair, t.data);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, ui.dp(1.5f)));
        mlp.topMargin = ui.dp(12);
        body.addView(checkMeter, mlp);

        LinearLayout stats = ui.hbox();
        stats.setGravity(Gravity.TOP);
        statLink = ui.readout("—", 13, t.ink);
        statLatency = ui.readout("—", 13, t.ink);
        statVersion = ui.readout("—", 13, t.ink);
        statApps = ui.readout("—", 13, t.ink);
        stats.addView(kit.stat("Bridge", statLink), Ui.weight(1.1f));
        stats.addView(kit.stat("Latency", statLatency), Ui.weight(1));
        stats.addView(kit.stat("Version", statVersion), Ui.weight(1));
        stats.addView(kit.stat("Apps", statApps), Ui.weight(0.8f));
        body.addView(stats, ui.margins(Ui.fillW(), 0, 12, 0, 0));

        linkNote = ui.dim("", 12.5f);
        linkNote.setVisibility(View.GONE);
        body.addView(linkNote, ui.margins(Ui.fillW(), 0, 12, 0, 0));

        powerBox = buildPower();
        body.addView(powerBox, Ui.fillW());
        card.addView(body, Ui.fillW());
        return card;
    }

    /** The power strip: wake over the network while the PC is away, lock / sleep / restart / shut down once paired. */
    private LinearLayout buildPower() {
        LinearLayout box = ui.vbox();
        box.addView(rule(), ruleParams(14, 10));
        LinearLayout head = ui.hbox();
        head.setMinimumHeight(ui.dp(26));
        head.addView(ui.label("Power"), Ui.weight(1));
        wolStatus = ui.readout("", 11, t.dim);
        wolStatus.setEllipsize(TextUtils.TruncateAt.END);
        wolStatus.setPadding(ui.dp(8), ui.dp(4), 0, ui.dp(4));
        wolStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                promptMac(false);
            }
        });
        head.addView(wolStatus, Ui.wrap());
        box.addView(head, Ui.fillW());
        powerKeys = ui.hbox();
        powerKeys.setBaselineAligned(false);
        box.addView(powerKeys, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        powerNote = ui.dim("", 12.5f);
        powerNote.setVisibility(View.GONE);
        box.addView(powerNote, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        box.setVisibility(View.GONE);
        return box;
    }

    // --- Guidance cards ---------------------------------------------------

    private LinearLayout buildNoHost() {
        LinearLayout card = ui.capCard("No PC address", null);
        LinearLayout body = ui.cardBody();
        body.addView(ui.body("LaunchBridge usually runs on the same PC as your AI, so OMNI-DECK uses the AI's "
                + "address once it's linked. Enter the PC's address to control it right away — even before "
                + "Ollama is reachable."), Ui.fillW());
        LinearLayout buttons = ui.hbox();
        TextView enter = ui.button("Enter address", IconDrawable.EDIT, Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptAddress();
            }
        });
        enter.setContentDescription("Enter PC address");
        buttons.addView(enter, Ui.wrap());
        buttons.addView(ui.space(10, 1));
        TextView find = ui.button("Find AI", IconDrawable.WIFI, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.showConnection();
            }
        });
        find.setContentDescription("Find AI");
        buttons.addView(find, Ui.wrap());
        body.addView(buttons, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        card.addView(body, Ui.fillW());
        return card;
    }

    /** Card-shaped placeholders (Vitals, Controls) while the first check runs. */
    private LinearLayout buildSkeletons() {
        LinearLayout box = ui.vbox();
        int bar = Theme.alpha(t.ink, t.isDark ? 0x12 : 0x0D);
        int well = Theme.alpha(t.ink, t.isDark ? 0x09 : 0x06);
        int sheen = Theme.alpha(t.hud ? t.accent : t.ink, t.isDark ? 0x14 : 0x0F);
        for (int i = 0; i < 2; i++) {
            PcSkeleton sk = new PcSkeleton(a, i == 0 ? PcSkeleton.VITALS : PcSkeleton.CONTROLS, bar, well, sheen,
                    t.hud ? 13 : 0);
            sk.setBackground(ui.panel(true, 34));
            if (t.cardElevation > 0) sk.setElevation(ui.dp(t.cardElevation));
            sk.setAlpha(i == 0 ? 1f : 0.7f);
            skeletons.add(sk);
            box.addView(sk, i == 0 ? Ui.fillW() : ui.margins(Ui.fillW(), 0, 12, 0, 0));
        }
        box.setContentDescription("Linking to the PC");
        return box;
    }

    private LinearLayout buildDown() {
        LinearLayout card = ui.capCard("Bridge unreachable", null);
        LinearLayout body = ui.cardBody();
        downLead = ui.body("");
        body.addView(downLead, Ui.fillW());
        String[] steps = {
                "LaunchBridge is running on the PC — start it from OMNI-DECK or its tray icon.",
                "It listens on the network (0.0.0.0), not only on 127.0.0.1.",
                "This phone and the PC are on the same Wi-Fi network.",
                "The PC firewall allows the bridge port on private networks."};
        LinearLayout list = ui.vbox();
        for (int i = 0; i < steps.length; i++) {
            LinearLayout r = ui.hbox();
            r.setGravity(Gravity.TOP);
            TextView n = ui.readout(String.format(Locale.US, "%02d", i + 1), 12, t.hud ? t.accent : t.data);
            n.setPadding(0, ui.dp(1), ui.dp(10), 0);
            r.addView(n, Ui.wrap());
            TextView s = ui.dim(steps[i], 13.5f);
            s.setTextColor(t.ink);
            r.addView(s, Ui.weight(1));
            list.addView(r, ui.margins(Ui.fillW(), 0, i == 0 ? 0 : 9, 0, 0));
        }
        list.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(12));
        list.setBackground(kit.well());
        body.addView(list, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        downError = ui.readout("", 11, t.dim);
        downError.setSingleLine(false);
        downError.setMaxLines(3);
        downError.setEllipsize(TextUtils.TruncateAt.END);
        downError.setLineSpacing(0, 1.2f);
        body.addView(downError, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        LinearLayout buttons = ui.hbox();
        TextView retry = ui.button("Retry", IconDrawable.REFRESH, Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLink(true);
            }
        });
        retry.setContentDescription("Retry");
        buttons.addView(retry, Ui.wrap());
        buttons.addView(ui.space(10, 1));
        TextView addr = ui.button("Address", IconDrawable.EDIT, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptAddress();
            }
        });
        addr.setContentDescription("Change PC address");
        buttons.addView(addr, Ui.wrap());
        buttons.addView(ui.flexSpace());
        buttons.addView(kit.iconKey(IconDrawable.SETTINGS, "Bridge settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.openSettings();
            }
        }));
        body.addView(buttons, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        downRetry = ui.readout("", 11, t.dim);
        body.addView(downRetry, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        card.addView(body, Ui.fillW());
        return card;
    }

    private LinearLayout buildPair() {
        LinearLayout card = ui.capCard("Pair this phone", null);
        LinearLayout body = ui.cardBody();
        pairLead = ui.body("");
        body.addView(pairLead, Ui.fillW());
        TextView unlocks = ui.label("Pairing unlocks");
        body.addView(unlocks, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        PcFlow chips = new PcFlow(a, ui.dp(6), ui.dp(6));
        String[] caps = {"Vitals", "Power", "Volume", "Screen capture", "Clipboard", "App launcher", "Desktop tools"};
        for (String c : caps) chips.addView(ui.chip(c, t.data));
        body.addView(chips, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        pairBtn = ui.button("Pair with PC", IconDrawable.LINK, Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pair();
            }
        });
        pairBtn.setContentDescription("Pair with PC");
        body.addView(pairBtn, ui.margins(Ui.fillW(), 0, 16, 0, 0));
        pairMeter = new Widgets.Meter(a, kit.meterTrack(), t.data);
        pairMeter.setVisibility(View.GONE);
        body.addView(pairMeter, ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(2)), 0, 10, 0, 0));
        pairError = kit.notice("", t.danger);
        pairError.setVisibility(View.GONE);
        body.addView(pairError, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        TextView note = ui.dim("The token stays on this phone. You can forget it any time from the PC link menu.",
                12);
        body.addView(note, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        card.addView(body, Ui.fillW());
        return card;
    }

    // --- Vitals -------------------------------------------------------------

    private View buildVitals() {
        LinearLayout meta = ui.hbox();
        vitalsStatus = ui.readout("", 10.5f, t.dim);
        meta.addView(vitalsStatus, Ui.wrap());
        vitalsLive = ui.liveTag();
        vitalsLive.setVisibility(View.GONE);
        meta.addView(vitalsLive, Ui.wrap());
        vitalsCard = ui.capCard("Vitals", meta);
        LinearLayout body = ui.cardBody();
        vitalsError = kit.notice("", t.warn);
        vitalsError.setVisibility(View.GONE);
        body.addView(vitalsError, ui.margins(Ui.fillW(), 0, 0, 0, 10));

        // No vitals yet and they fail: a compact block with the remedy, not an empty instrument grid.
        vitalsFail = ui.vbox();
        vitalsRemedy = ui.dim("", 13);
        vitalsFail.addView(vitalsRemedy, Ui.fillW());
        TextView retry = ui.button("Retry", IconDrawable.REFRESH, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pollVitals();
            }
        });
        retry.setContentDescription("Retry vitals");
        vitalsFail.addView(retry, ui.margins(Ui.wrap(), 0, 12, 0, 0));
        vitalsFail.setVisibility(View.GONE);
        body.addView(vitalsFail, Ui.fillW());

        instruments = ui.vbox();
        sysLine = ui.readout("", 11.5f, t.dim);
        sysLine.setEllipsize(TextUtils.TruncateAt.END);
        sysLine.setVisibility(View.GONE);
        instruments.addView(sysLine, ui.margins(Ui.fillW(), 0, 0, 0, 10));

        // CPU: gauge with the value inside, trace beside it.
        LinearLayout cpu = ui.hbox();
        cpu.setBackground(kit.well());
        cpu.setPadding(ui.dp(10), ui.dp(10), ui.dp(12), ui.dp(10));
        FrameLayout g = new FrameLayout(a);
        cpuGauge = new Widgets.Gauge(a, kit.meterTrack(), t.data);
        cpuGauge.setEmptyLabel(""); // the centre already reads "—"
        g.addView(cpuGauge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout inner = ui.vbox();
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        cpuValue = ui.readout("—", 21, t.inkStrong);
        cpuValue.setGravity(Gravity.CENTER);
        inner.addView(cpuValue, Ui.wrap());
        TextView cpuTag = ui.label("CPU");
        cpuTag.setPadding(0, ui.dp(3), 0, 0);
        inner.addView(cpuTag, Ui.wrap());
        g.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        cpu.addView(g, new LinearLayout.LayoutParams(ui.dp(92), ui.dp(92)));
        LinearLayout trace = ui.vbox();
        trace.setPadding(ui.dp(12), 0, 0, 0);
        LinearLayout th = ui.hbox();
        th.addView(ui.label("Processor load"), Ui.weight(1));
        cpuWindow = ui.readout("", 10.5f, t.dim);
        cpuWindow.setPadding(ui.dp(6), 0, 0, 0);
        th.addView(cpuWindow, Ui.wrap());
        trace.addView(th, Ui.fillW());
        cpuTrace = new PcTrace(a, t.data, t.hud ? Theme.alpha(t.accent, 0x33) : t.hair, HISTORY);
        cpuTrace.setContentDescription("CPU load trace");
        trace.addView(cpuTrace, ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(46)), 0, 10, 0, 0));
        cpuStats = ui.readout("", 11, t.dim);
        cpuStats.setEllipsize(TextUtils.TruncateAt.END);
        trace.addView(cpuStats, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        cpu.addView(trace, Ui.weight(1));
        instruments.addView(cpu, Ui.fillW());

        ramTile = kit.tile("Memory", true);
        diskTile = kit.tile("Disk", true);
        powerTile = kit.tile("Power", true);
        uptimeTile = kit.tile("Uptime", true);
        uptimeTile.meter.setVisibility(View.INVISIBLE); // keeps the detail lines of the row aligned
        instruments.addView(tileRow(ramTile, diskTile), ui.margins(Ui.fillW(), 0, 10, 0, 0));
        instruments.addView(tileRow(powerTile, uptimeTile), ui.margins(Ui.fillW(), 0, 10, 0, 0));

        rawBox = ui.vbox();
        rawBox.setVisibility(View.GONE);
        TextView rawTitle = ui.dim("The PC sent system info this app can't chart — here it is as sent:", 12.5f);
        rawBox.addView(rawTitle, Ui.fillW());
        rawText = ui.readout("", 12, t.ink);
        rawText.setSingleLine(false);
        rawText.setLineSpacing(0, 1.25f);
        rawText.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        rawText.setBackground(kit.well());
        rawText.setTextIsSelectable(true);
        rawBox.addView(rawText, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        instruments.addView(rawBox, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        body.addView(instruments, Ui.fillW());
        vitalsCard.addView(body, Ui.fillW());
        return vitalsCard;
    }

    private LinearLayout tileRow(PcKit.Tile left, PcKit.Tile right) {
        LinearLayout row = ui.hbox();
        row.setGravity(Gravity.TOP);
        row.setBaselineAligned(false);
        // Every tile has the same rows (label, value, meter, detail), so they line up at equal heights.
        row.addView(left.root, Ui.weight(1));
        row.addView(ui.space(10, 1));
        row.addView(right.root, Ui.weight(1));
        return row;
    }

    // --- Controls -----------------------------------------------------------

    private View buildControls() {
        controlsCard = ui.capCard("Controls", null);
        LinearLayout body = ui.cardBody();

        // Volume
        volumeSection = ui.vbox();
        volValue = ui.readout("—", 16, t.inkStrong);
        volumeSection.addView(kit.section(IconDrawable.SPEAKER, "Volume", volValue), Ui.fillW());
        slider = new PcSlider(a, t);
        slider.setEnabled(false);
        slider.setListener(new PcSlider.Listener() {
            @Override
            public void onMove(int value) {
                volValue.setText(kit.readout(String.valueOf(value), "%"));
            }

            @Override
            public void onCommit(int value) {
                setVolume(value);
            }
        });
        volumeSection.addView(slider, ui.margins(Ui.fillW(), 0, 4, 0, 0));
        presets = kit.segmented(new String[]{"Mute", "25", "50", "75", "Max"}, new PcKit.OnSegment() {
            @Override
            public void onSegment(int index) {
                if (!volKnown) return; // the first read for this PC is still out
                if (index == 0) toggleMute();
                else setVolume(index == 4 ? 100 : index * 25);
            }
        });
        muteSeg = (TextView) presets.getChildAt(0);
        volumeSection.addView(presets, ui.margins(Ui.fillW(), 0, 4, 0, 0));
        volNote = ui.dim("", 12);
        volNote.setVisibility(View.GONE);
        volumeSection.addView(volNote, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        body.addView(volumeSection, Ui.fillW());
        syncVolumeControls();

        screenRule = rule();
        body.addView(screenRule, ruleParams(16, 16));

        // Screen capture
        screenSection = ui.vbox();
        captureBtn = kit.quiet("Capture", IconDrawable.CAMERA, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                capture();
            }
        });
        captureBtn.setContentDescription("Capture screen");
        screenSection.addView(kit.section(IconDrawable.NAV_PC, "Screen", captureBtn), Ui.fillW());
        shotFrame = new ShotFrame(a);
        shotFrame.setBackground(kit.well());
        shotFrame.setClipToOutline(true);
        shotImage = new ImageView(a);
        shotImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shotImage.setContentDescription("PC screenshot — tap to expand");
        shotImage.setVisibility(View.GONE);
        shotImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFullscreen();
            }
        });
        int inset = ui.dp(1);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        ilp.setMargins(inset, inset, inset, inset);
        shotFrame.addView(shotImage, ilp);
        shotEmpty = ui.vbox();
        shotEmpty.setGravity(Gravity.CENTER);
        shotEmpty.setClickable(true);
        shotEmpty.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                capture();
            }
        });
        shotEmpty.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        shotEmpty.addView(kit.icon(IconDrawable.CAMERA, t.faint, 26), new LinearLayout.LayoutParams(ui.dp(28),
                ui.dp(28)));
        shotEmptyTitle = ui.text("No capture yet", 14, t.ink, t.bodyMedium);
        shotEmptyTitle.setGravity(Gravity.CENTER);
        shotEmptyTitle.setPadding(0, ui.dp(10), 0, ui.dp(5));
        shotEmpty.addView(shotEmptyTitle, Ui.wrap());
        shotEmptyText = ui.dim("Tap to see what's on the PC screen right now.", 12.5f);
        shotEmptyText.setGravity(Gravity.CENTER);
        shotEmpty.addView(shotEmptyText, Ui.wrap());
        shotFrame.addView(shotEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        shotBusyBox = ui.vbox();
        shotBusyBox.setGravity(Gravity.CENTER);
        TextView busyText = ui.label("Capturing screen");
        busyText.setGravity(Gravity.CENTER);
        shotBusyBox.addView(busyText, Ui.wrap());
        shotMeter = new Widgets.Meter(a, kit.meterTrack(), t.data);
        shotBusyBox.addView(shotMeter, ui.margins(new LinearLayout.LayoutParams(ui.dp(120), ui.dp(3)), 0, 12, 0, 0));
        shotBusyBox.setBackgroundColor(Theme.alpha(t.hud ? t.bg : t.surface, 0xB3));
        shotBusyBox.setVisibility(View.GONE);
        shotFrame.addView(shotBusyBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        screenSection.addView(shotFrame, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        shotMeta = ui.readout("", 11, t.dim);
        shotMeta.setEllipsize(TextUtils.TruncateAt.END);
        shotMeta.setVisibility(View.GONE);
        screenSection.addView(shotMeta, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        shotActions = ui.hbox();
        shotActions.addView(kit.action("Ask AI", IconDrawable.BRAIN, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askAi();
            }
        }), Ui.weight(1));
        shotActions.addView(ui.space(8, 1));
        shotActions.addView(kit.action("Save", IconDrawable.DOWNLOAD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveShot(false);
            }
        }), Ui.weight(1));
        if (Build.VERSION.SDK_INT >= 29) {
            shotActions.addView(ui.space(8, 1));
            shotActions.addView(kit.action("Share", IconDrawable.SHARE, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveShot(true);
                }
            }), Ui.weight(1));
        }
        shotActions.setVisibility(View.GONE);
        screenSection.addView(shotActions, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        body.addView(screenSection, Ui.fillW());

        clipRule = rule();
        body.addView(clipRule, ruleParams(16, 16));

        // Clipboard
        clipSection = ui.vbox();
        fetchBtn = kit.quiet("Fetch", IconDrawable.DOWNLOAD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchClipboard();
            }
        });
        fetchBtn.setContentDescription("Get PC clipboard");
        clipSection.addView(kit.section(IconDrawable.CLIPBOARD, "PC clipboard", fetchBtn), Ui.fillW());
        clipRead = ui.vbox();
        clipText = ui.readout("", 12.5f, t.dim);
        clipText.setSingleLine(false);
        clipText.setMaxLines(8);
        clipText.setEllipsize(TextUtils.TruncateAt.END);
        clipText.setLineSpacing(0, 1.25f);
        clipText.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11));
        clipText.setBackground(kit.well());
        clipHint("Fetch the text that's on the PC clipboard right now.", t.dim);
        clipRead.addView(clipText, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        clipMeta = ui.readout("", 11, t.dim);
        clipMeta.setVisibility(View.GONE);
        clipRead.addView(clipMeta, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        clipActions = ui.hbox();
        clipActions.addView(kit.action("Copy", IconDrawable.COPY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (clipValue != null) a.copy("PC clipboard", clipValue);
            }
        }), Ui.weight(1));
        clipActions.addView(ui.space(8, 1));
        clipActions.addView(kit.action("Send to chat", IconDrawable.SEND, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (clipValue != null) a.onInsertText(clipValue);
            }
        }), Ui.weight(1));
        clipActions.setVisibility(View.GONE);
        clipRead.addView(clipActions, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        clipSection.addView(clipRead, Ui.fillW());
        pushBtn = ui.button("Send phone clipboard", IconDrawable.UPLOAD, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pushClipboard();
            }
        });
        pushBtn.setContentDescription("Send phone clipboard to PC");
        pushBtn.setVisibility(View.GONE);
        clipSection.addView(pushBtn, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        body.addView(clipSection, Ui.fillW());
        controlsCard.addView(body, Ui.fillW());
        return controlsCard;
    }

    /** Keeps the screenshot viewport at the image's aspect ratio (a low strip until one arrives). */
    private static final class ShotFrame extends FrameLayout {
        /** Height / width: a low strip while empty, the image's ratio once a capture arrives. */
        float aspect = 0.4f;

        ShotFrame(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            int height = Math.round(width * Math.max(0.3f, Math.min(1.1f, aspect)));
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    // --- Launcher -------------------------------------------------------------

    private View buildLauncher() {
        appsCount = ui.readout("", 10.5f, t.dim);
        LinearLayout card = ui.capCard("Launcher", appsCount);
        LinearLayout body = ui.cardBody();

        LinearLayout box = ui.hbox();
        box.setBackground(ui.rounded(t.input, t.edge, 8));
        box.setPadding(ui.dp(12), 0, ui.dp(2), 0);
        box.addView(kit.icon(IconDrawable.SEARCH, t.faint, 18), new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        search = ui.field("", "Search apps on your PC",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setContentDescription("Search apps");
        search.setPadding(ui.dp(10), ui.dp(11), ui.dp(6), ui.dp(11));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                handler.removeCallbacks(searchRun);
                handler.postDelayed(searchRun, 280);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                handler.removeCallbacks(searchRun);
                searchRun.run();
                return true;
            }
        });
        box.addView(search, Ui.weight(1));
        clearSearch = ui.iconButton(IconDrawable.CLOSE, "Clear search", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
            }
        });
        clearSearch.setVisibility(View.GONE);
        box.addView(clearSearch, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        body.addView(box, Ui.fillW());

        recentBox = ui.vbox();
        recentBox.addView(ui.label("Recent"), Ui.fillW());
        recentFlow = new PcFlow(a, ui.dp(6), ui.dp(6));
        recentBox.addView(recentFlow, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        body.addView(recentBox, ui.margins(Ui.fillW(), 0, 14, 0, 0));

        LinearLayout lh = ui.hbox();
        listLabel = ui.label("");
        lh.addView(listLabel, Ui.weight(1));
        listCount = ui.readout("", 11, t.dim);
        lh.addView(listCount, Ui.wrap());
        body.addView(lh, ui.margins(Ui.fillW(), 0, 16, 0, 2));
        appsMeter = new Widgets.Meter(a, kit.meterTrack(), t.data);
        appsMeter.setVisibility(View.INVISIBLE);
        body.addView(appsMeter, ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(2)), 0, 4, 0, 0));
        appList = ui.vbox();
        body.addView(appList, Ui.fillW());
        appsState = ui.dim("", 13);
        appsState.setVisibility(View.GONE);
        appsState.setPadding(0, ui.dp(10), 0, ui.dp(4));
        body.addView(appsState, Ui.fillW());
        appsHint = ui.dim("", 12.5f);
        appsHint.setVisibility(View.GONE);
        body.addView(appsHint, ui.margins(Ui.fillW(), 0, 10, 0, 0));

        // "Show 3 more ⌄" / "Show fewer ⌃": a list footer, in the link ink so it reads as a control.
        int linkInk = t.hud ? t.accent : t.link;
        appsMore = ui.hbox();
        appsMore.setGravity(Gravity.CENTER);
        appsMore.setMinimumHeight(ui.dp(42));
        appsMore.setBackground(ui.pressableRow(0));
        appsMoreText = ui.text("", t.hud ? 10.5f : 13, linkInk, t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) appsMoreText.setLetterSpacing(0.1f);
        appsMore.addView(appsMoreText, Ui.wrap());
        appsMoreChevron = kit.icon(IconDrawable.CHEVRON, linkInk, 16);
        appsMore.addView(appsMoreChevron, ui.margins(new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)), 6, 0, 0, 0));
        appsMore.setVisibility(View.GONE);
        appsMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                appsExpanded = !appsExpanded;
                renderApps();
            }
        });
        body.addView(appsMore, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        card.addView(body, Ui.fillW());
        renderRecents();
        return card;
    }

    private final Runnable searchRun = new Runnable() {
        @Override
        public void run() {
            String q = search.getText().toString().trim();
            if (appsLoaded && q.equals(appsQuery)) return;
            appsExpanded = false;
            searchApps(q);
        }
    };

    // --- Tool runner ----------------------------------------------------------

    private View buildTools() {
        LinearLayout right = ui.hbox();
        toolsCount = ui.readout("", 10.5f, t.dim);
        right.addView(toolsCount, Ui.wrap());
        toolsChevron = new ImageView(a);
        toolsChevron.setImageDrawable(new IconDrawable(IconDrawable.CHEVRON, t.dim, t.dim, ui.dp(18)));
        toolsChevron.setScaleType(ImageView.ScaleType.CENTER);
        toolsChevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        right.addView(toolsChevron, new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        LinearLayout card = ui.capCard("Tool runner", right);
        View head = card.getChildAt(0);
        head.setClickable(true);
        head.setContentDescription("Tool runner");
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                toggleTools();
            }
        });
        LinearLayout body = ui.cardBody();
        body.addView(ui.dim("Every desktop tool LaunchBridge offers, with what it does. Tap one to fill in its "
                + "settings and run it.", 13), Ui.fillW());
        toolsBody = ui.vbox();
        toolsBody.setVisibility(View.GONE);
        toolsState = ui.dim("", 12.5f);
        toolsState.setVisibility(View.GONE);
        toolsBody.addView(toolsState, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        toolsList = ui.vbox();
        toolsBody.addView(toolsList, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        TextView note = ui.dim("Tools run on the PC with LaunchBridge's permissions. Ones with a warning mark ask "
                + "before they run.", 12);
        toolsBody.addView(note, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        body.addView(toolsBody, Ui.fillW());
        card.addView(body, Ui.fillW());
        return card;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private boolean live() {
        return isShown() && !stopped && !destroyed;
    }

    @Override
    protected void onShow() {
        freshShow = true;
        refreshLink(false);
        startTicker();
    }

    @Override
    protected void onHide() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(searchRun);
        syncMotion();
    }

    @Override
    public void onActivityStart() {
        stopped = false;
        if (isShown()) {
            freshShow = true;
            refreshLink(false);
            startTicker();
        }
    }

    @Override
    public void onActivityStop() {
        stopped = true;
        handler.removeCallbacks(ticker);
        syncMotion();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onStateChanged() {
        if (!isBuilt()) return;
        String h = e.bridgeHost();
        if (!h.equals(host)) {
            // Another PC (the AI moved, or the bridge address changed): nothing of the old one carries over.
            host = h;
            invalidatePc(true);
            if (h.length() == 0) showState(NO_HOST);
            else if (live()) refreshLink(false);
            else showState(CHECKING);
            return;
        }
        // Pairing changed elsewhere (Settings, /pair, another tab): check again.
        boolean paired = e.bridgePaired();
        if (live() && !checking && ((paired && state == UNPAIRED) || (!paired && (state == PAIRED
                || state == REJECTED)))) {
            refreshLink(false);
        }
    }

    private void startTicker() {
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1000);
    }

    /** Once a second while visible: ages, vitals polling, auto-retry. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!live()) return;
            long now = SystemClock.elapsedRealtime();
            if (state == PAIRED && !vitalsBusy && now - vitalsAskedAt >= POLL_MS) pollVitals();
            // Unreachable: try again every 15 s; a refused token: see whether the bridge takes it again.
            if ((state == DOWN || state == REJECTED) && !checking && now >= nextRetryAt) refreshLink(false);
            updateAges();
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * The pulsing dot, the progress sweeps and the skeleton shimmer run only
     * while the tab is visible; with Reduce motion on, a busy meter holds a
     * steady full bar instead.
     */
    private void syncMotion() {
        boolean on = live();
        boolean motion = !e.settings.reduceMotion();
        dot.setPulsing(on && motion && (state == PAIRED || state == CHECKING || checking));
        busyMeter(checkMeter, checking, on, motion);
        busyMeter(pairMeter, pairing, on, motion);
        busyMeter(shotMeter, shotBusy, on, motion);
        busyMeter(appsMeter, appsMeter.getVisibility() == View.VISIBLE, on, motion);
        for (PcSkeleton s : skeletons) s.setShimmer(on && motion && state == CHECKING);
        ui.setCapLive(vitalsCard, on && state == PAIRED && vitalsAt > 0 && vitalsErr == null);
    }

    private static void busyMeter(Widgets.Meter m, boolean busy, boolean on, boolean motion) {
        if (busy && on && motion) {
            m.setIndeterminate(true);
        } else {
            m.setFraction(busy && !motion ? 1f : 0f); // also stops any sweep
        }
    }

    // ------------------------------------------------------------------
    // Link state
    // ------------------------------------------------------------------

    private String address() {
        return host.length() == 0 ? "" : (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":"
                + e.settings.bridgePort();
    }

    /** The PC the AI runs on ("" before the first link). */
    private String aiHost() {
        return e.server() != null ? e.server().host : e.settings.lastHost();
    }

    private boolean customAddress() {
        return e.settings.bridgeHost().length() > 0;
    }

    /** A token is saved, but for another PC than the one the tab points at. */
    private boolean pairedElsewhere() {
        return e.settings.bridgeToken().length() > 0 && !e.bridgePaired();
    }

    /**
     * Asks LaunchBridge's /health whether it's there, then routes to the right
     * state. {@code explicit} (Refresh, Retry): reload everything the tab
     * shows, not just the parts that are missing.
     */
    private void refreshLink(final boolean explicit) {
        String h = e.bridgeHost();
        if (!h.equals(host)) {
            host = h;
            invalidatePc(true);
        }
        if (host.length() == 0) {
            showState(NO_HOST);
            return;
        }
        if (checking) {
            refreshAgain |= explicit;
            return;
        }
        checking = true;
        final String checkedHost = host;
        if (state == NO_HOST) {
            showState(CHECKING);
        } else {
            updateHeader();
            syncMotion();
        }
        final long started = SystemClock.elapsedRealtime();
        e.bridgeHealth(new Engine.Callback<JSONObject>() {
            @Override
            public void done(JSONObject v, String error) {
                if (destroyed) {
                    checking = false;
                    return;
                }
                if (!checkedHost.equals(e.bridgeHost())) {
                    checking = false;
                    refreshLink(explicit); // the PC moved while we were asking
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (error != null) {
                    checking = false;
                    health = null;
                    healthMs = -1;
                    linkError = error;
                    showState(DOWN);
                    afterCheck();
                    return;
                }
                health = v;
                healthMs = now - started;
                linkError = "";
                String tok = e.settings.bridgeToken();
                if (!e.bridgePaired()) {
                    checking = false;
                    showState(UNPAIRED);
                    afterCheck();
                } else if (tok.equals(rejectedToken)) {
                    verifyToken(tok);
                } else {
                    checking = false;
                    showState(PAIRED, explicit);
                    afterCheck();
                }
            }
        });
    }

    /**
     * /health needs no token, so a token the bridge refused earlier is tried
     * again with a cheap authenticated call: accepted → paired again
     * (LaunchBridge restarted, or a new token took effect); refused → still
     * asks to pair.
     */
    private void verifyToken(final String tok) {
        final int g = gen;
        e.bridgeTools(new Engine.Callback<List<BridgeTool>>() {
            @Override
            public void done(List<BridgeTool> list, String error) {
                checking = false;
                if (destroyed || g != gen) return;
                if (error != null && linkFailure(error, tok)) {
                    afterCheck();
                    return;
                }
                // Anything but a refusal means the token got through.
                rejectedToken = "";
                if (list != null) {
                    tools = list;
                    renderTools();
                }
                showState(PAIRED, true);
                afterCheck();
            }
        });
    }

    private void afterCheck() {
        if (!refreshAgain) return;
        refreshAgain = false;
        refreshLink(true);
    }

    private void showState(int s) {
        showState(s, false);
    }

    private void showState(int s, boolean reload) {
        boolean entered = s != state;
        state = s;
        noHostCard.setVisibility(s == NO_HOST ? View.VISIBLE : View.GONE);
        skeletonBox.setVisibility(s == CHECKING ? View.VISIBLE : View.GONE);
        downCard.setVisibility(s == DOWN ? View.VISIBLE : View.GONE);
        pairCard.setVisibility(s == UNPAIRED || s == REJECTED ? View.VISIBLE : View.GONE);
        pairedBox.setVisibility(s == PAIRED ? View.VISIBLE : View.GONE);
        linkNote.setVisibility(s == CHECKING && host.length() > 0 ? View.VISIBLE : View.GONE);
        if (s == CHECKING) linkNote.setText(identText("Contacting LaunchBridge at ", address(), "…"));
        if (s == DOWN || s == REJECTED) nextRetryAt = SystemClock.elapsedRealtime() + RETRY_MS;
        if (s == DOWN) {
            downLead.setText(identText("OMNI-DECK can't reach LaunchBridge at ", address(), customAddress()
                    ? " — the address you set. If that PC is on, check that:"
                    : " — the PC your AI runs on. If it's on, check that:"));
            // "Can't reach…" only repeats the checklist; other errors (HTTP 500, bad JSON…) are worth showing.
            boolean useful = linkError.length() > 0 && !linkError.startsWith("Can't reach");
            downError.setText(linkError);
            downError.setVisibility(useful ? View.VISIBLE : View.GONE);
        }
        if (s == UNPAIRED) {
            if (pairedElsewhere()) {
                SpannableStringBuilder sb = new SpannableStringBuilder("This phone is paired with the PC at ");
                sb.append(ui.mono(e.settings.bridgeTokenHost())).append(", not this one. Pair with ")
                        .append(ui.mono(address())).append(" to control it here — that replaces the other pairing.");
                pairLead.setText(sb);
            } else {
                pairLead.setText(identText("LaunchBridge is online at ", address(), ". Pair once to give this phone "
                        + "its own token — then it can read vitals, set the volume, capture the screen and open "
                        + "apps."));
            }
        } else if (s == REJECTED) {
            pairLead.setText("The PC bridge no longer accepts this phone's token — LaunchBridge was reset or "
                    + "re-installed. Pair again to restore control.");
        }
        if (s == UNPAIRED || s == REJECTED) setPairButton(pairLabel());
        updateHeader();
        renderPower();
        updateAges();
        syncMotion();
        if (s == PAIRED && (entered || freshShow || reload)) {
            freshShow = false;
            loadPairedData(reload);
        }
    }

    /** "…at " + mono address + "…". */
    private CharSequence identText(String before, String ident, String after) {
        SpannableStringBuilder sb = new SpannableStringBuilder(before);
        sb.append(ui.mono(ident)).append(after);
        return sb;
    }

    private String pairLabel() {
        return state == REJECTED ? "Pair again" : pairedElsewhere() ? "Pair with this PC" : "Pair with PC";
    }

    private void setPairButton(String label) {
        pairBtn.setText(t.hud ? label.toUpperCase(Locale.US) : label);
        pairBtn.setContentDescription(label);
    }

    /**
     * Loads what the paired view shows. Without {@code force} only missing
     * parts load (plus the app list when the bridge's index changed); with
     * it (Refresh, a token accepted again) everything reloads.
     */
    private void loadPairedData(boolean force) {
        pollVitals();
        if (tools != null && !PcTools.has(tools, "get_volume")) {
            volKnown = true; // nothing to read; the presets can still set a level
            syncVolumeControls();
        } else {
            loadVolume();
        }
        if ((force || tools == null) && !toolsBusy) loadTools();
        String q = search.getText().toString().trim();
        int idx = appsIndexed();
        boolean indexMoved = appsLoaded && q.length() == 0 && idx >= 0 && idx != appsIndexedAtLoad;
        if (!appsLoaded || force || indexMoved) searchApps(q);
    }

    /**
     * Forgets everything about the PC on screen — vitals, volume, capture,
     * clipboard, apps, tools — and drops answers still on their way, so
     * another PC (or a new pairing) never shows or acts on the old one's data.
     */
    private void invalidatePc(boolean hostChanged) {
        gen++;
        appsSeq++;
        if (hostChanged) {
            health = null;
            healthMs = -1;
            linkError = "";
            rejectedToken = "";
        }
        pcVitals = null;
        // Vitals
        cpuCount = 0;
        vitalsAt = 0;
        vitalsErr = null;
        vitalsBusy = false;
        vitalsAskedAt = 0;
        clearVitals();
        // Volume
        volBusy = false;
        volPending = -1;
        volConfirmed = -1;
        volLevel = -1;
        preMute = -1;
        volKnown = false;
        slider.setValue(-1);
        volValue.setText("—");
        setMuteLabel(false);
        volNote.setVisibility(View.GONE);
        syncVolumeControls();
        // Screen
        shotBusy = false;
        shotB64 = null;
        shotBitmap = null;
        shotBusyBox.setVisibility(View.GONE);
        captureBtn.setEnabled(true);
        captureBtn.setAlpha(1f);
        resetShot();
        // Clipboard
        clipBusy = false;
        fetchBtn.setEnabled(true);
        fetchBtn.setAlpha(1f);
        showClip(null);
        // Launcher
        appsLoaded = false;
        apps = null;
        appsQuery = "";
        appsExpanded = false;
        appsIndexedAtLoad = -1;
        appList.removeAllViews();
        listLabel.setText("");
        listCount.setText("");
        appsMore.setVisibility(View.GONE);
        appsHint.setVisibility(View.GONE);
        appsState.setVisibility(View.GONE);
        appsMeter.setVisibility(View.INVISIBLE);
        renderRecents();
        // Tools and power
        tools = null;
        toolsBusy = false;
        toolsList.removeAllViews();
        toolsCount.setText("");
        toolsState.setVisibility(View.GONE);
        powerNote.setVisibility(View.GONE);
        updateControlsForTools();
        renderPower();
        syncMotion();
    }

    private void updateHeader() {
        String name = pcVitals != null && pcVitals.host.length() > 0 ? pcVitals.host : null;
        // A PC's name is an identifier (shown as the PC reports it); the placeholder follows the theme.
        hostTitle.setText(name != null ? name : t.hud ? "YOUR PC" : "Your PC");
        String addr = address();
        hostAddr.setText(addr.length() == 0 ? "No address yet" : addr);
        int color;
        String chip;
        String linkWord;
        switch (state) {
            case NO_HOST:
                color = t.dim;
                chip = "No address";
                linkWord = "—";
                break;
            case DOWN:
                color = t.danger;
                chip = "Offline";
                linkWord = "Offline";
                break;
            case UNPAIRED:
                color = t.warn;
                chip = pairedElsewhere() ? "Other PC" : "Not paired";
                linkWord = "Online";
                break;
            case REJECTED:
                color = t.warn;
                chip = "Re-pair";
                linkWord = "Online";
                break;
            case PAIRED:
                color = t.ok;
                chip = "Paired";
                linkWord = "Online";
                break;
            default:
                color = t.warn;
                chip = "Linking";
                linkWord = "Checking";
                break;
        }
        if (checking && state != PAIRED) {
            color = t.warn;
            chip = "Linking";
        }
        stateChip.setText(t.hud ? chip.toUpperCase(Locale.US) : chip);
        kit.chipColor(stateChip, color);
        dot.setColor(color);
        dot.setContentDescription("PC link: " + chip);
        statLink.setText(linkWord);
        statLink.setTextColor(state == DOWN ? t.danger : state == PAIRED || state == UNPAIRED || state == REJECTED
                ? t.ok : t.ink);
        statLatency.setText(healthMs >= 0 && state != DOWN ? healthMs + " ms" : "—");
        String ver = health == null ? "" : firstNonEmpty(OllamaClient.str(health, "version"),
                OllamaClient.str(health, "bridge_version"));
        statVersion.setText(ver.length() > 0 ? (ver.matches("\\d.*") ? "v" + ver : ver) : "—");
        int n = appsIndexed();
        statApps.setText(n >= 0 ? String.valueOf(n) : "—");
        String indexed = n >= 0 ? n + " indexed" : "";
        appsCount.setText(t.hud ? t.labelUnits(indexed) : indexed);
    }

    private int appsIndexed() {
        if (health == null) return -1;
        String[] keys = {"apps_indexed", "apps", "app_count", "indexed"};
        for (String k : keys) {
            Object o = health.opt(k);
            if (o instanceof Number) return ((Number) o).intValue();
        }
        return -1;
    }

    private static String firstNonEmpty(String a1, String a2) {
        return a1 != null && a1.length() > 0 ? a1 : a2 == null ? "" : a2;
    }

    private void updateAges() {
        long now = SystemClock.elapsedRealtime();
        if (state == DOWN) {
            long s = Math.max(0, (nextRetryAt - now + 999) / 1000);
            String txt = checking ? "Retrying…" : "Auto-retry in " + s + "s";
            downRetry.setText(t.hud ? t.labelUnits(txt) : txt);
        }
        if (state == PAIRED) updateVitalsMeta(now);
    }

    /** The Vitals cap: "Reading…", "Error", or the shared live tag ("Live · 2s", "Paused · 40s"). */
    private void updateVitalsMeta(long now) {
        if (vitalsAt == 0) {
            boolean failed = vitalsErr != null;
            String s = failed ? "Error" : "Reading…";
            vitalsStatus.setText(t.hud ? t.labelUnits(s) : s);
            vitalsStatus.setTextColor(failed ? t.warn : t.dim);
            vitalsStatus.setVisibility(View.VISIBLE);
            vitalsLive.setVisibility(View.GONE);
        } else {
            vitalsStatus.setVisibility(View.GONE);
            vitalsLive.setVisibility(View.VISIBLE);
            long age = now - vitalsAt;
            vitalsLive.update(vitalsErr == null && age < POLL_MS * 3, age);
        }
    }

    /**
     * Reacts to link-level failures from any bridge call made with {@code token}.
     * Returns true when the error was handled here (bridge gone, token rejected,
     * not paired — or an auth failure for a token that has since been replaced,
     * which is simply dropped) and the caller should stop.
     */
    private boolean linkFailure(String error, String token) {
        if (error == null) return false;
        String low = error.toLowerCase(Locale.US);
        boolean elsewhere = low.contains("paired with the pc bridge at");
        boolean auth = low.contains("rejected the token") || low.contains("not paired") || elsewhere;
        if (auth && !token.equals(e.settings.bridgeToken())) return true;
        if (low.contains("rejected the token")) {
            rejectedToken = token;
            showState(REJECTED);
            return true;
        }
        if (low.contains("not paired") || elsewhere) {
            showState(UNPAIRED);
            return true;
        }
        if (low.contains("can't reach the pc bridge")) {
            linkError = error;
            health = null;
            showState(DOWN);
            return true;
        }
        return false;
    }

    /** An authenticated call went through: the bridge takes this token (again). */
    private void accepted(String token) {
        if (token.equals(rejectedToken)) rejectedToken = "";
    }

    private void pair() {
        if (pairing) return;
        pairing = true;
        setPairButton("Pairing…");
        pairBtn.setEnabled(false);
        pairError.setVisibility(View.GONE);
        pairMeter.setVisibility(View.VISIBLE);
        syncMotion();
        e.bridgePair(new Engine.Callback<String>() {
            @Override
            public void done(String token, String error) {
                pairing = false;
                if (destroyed) return;
                pairBtn.setEnabled(true);
                pairMeter.setVisibility(View.GONE);
                syncMotion();
                if (error != null) {
                    setPairButton(pairLabel());
                    pairError.setText("Pairing failed — " + error);
                    pairError.setVisibility(View.VISIBLE);
                    if (pairCard.getVisibility() != View.VISIBLE) ui.toast("Pairing failed — " + error);
                    return;
                }
                ui.toast("Paired with your PC");
                rejectedToken = "";
                // A new token (maybe for another PC): start from a clean slate.
                invalidatePc(false);
                showState(PAIRED, true);
            }
        });
    }

    private void linkMenu() {
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        rows.add(new Ui.Row("Refresh link", "Check LaunchBridge again now", false, new Runnable() {
            @Override
            public void run() {
                refreshLink(true);
            }
        }, null).icon(IconDrawable.REFRESH));
        String addr = address();
        String where = customAddress() ? "Set by you · " + addr
                : addr.length() > 0 ? "Same PC as the AI · " + addr : "Not set — the AI's PC once it's linked";
        rows.add(new Ui.Row("PC address", where, false, new Runnable() {
            @Override
            public void run() {
                promptAddress();
            }
        }, null).icon(IconDrawable.EDIT));
        if (state == PAIRED || state == REJECTED) {
            rows.add(new Ui.Row("Pair again", "Get a fresh token from the bridge", false, new Runnable() {
                @Override
                public void run() {
                    pair();
                }
            }, null).icon(IconDrawable.LINK));
        }
        if (e.settings.bridgeToken().length() > 0) {
            rows.add(new Ui.Row("Forget pairing", "Remove this phone's token", false, new Runnable() {
                @Override
                public void run() {
                    ui.confirm("Forget pairing?", "This phone will lose PC control until you pair again.", "Forget",
                            new Runnable() {
                                @Override
                                public void run() {
                                    e.setBridgeToken("");
                                    e.log("info", "PC bridge pairing removed");
                                    refreshLink(true);
                                }
                            });
                }
            }, null).icon(IconDrawable.TRASH).danger());
        }
        String mac = WakeOnLan.normalize(e.settings.pcMac());
        rows.add(new Ui.Row("Wake-on-LAN", mac != null ? "PC's MAC " + mac : "Not set up — needs the PC's MAC address",
                false, new Runnable() {
                    @Override
                    public void run() {
                        promptMac(false);
                    }
                }, null).icon(IconDrawable.POWER));
        if (host.length() > 0) {
            rows.add(new Ui.Row("Copy bridge address", addr, false, new Runnable() {
                @Override
                public void run() {
                    a.copy("Bridge address", address());
                }
            }, null).icon(IconDrawable.COPY));
        }
        rows.add(new Ui.Row("Bridge settings", "Port, pairing and connection", false, new Runnable() {
            @Override
            public void run() {
                a.openSettings();
            }
        }, null).icon(IconDrawable.SETTINGS));
        String name = pcVitals != null && pcVitals.host.length() > 0 ? pcVitals.host : "Your PC";
        ui.pick("PC link", name, rows, null, null);
    }

    // ------------------------------------------------------------------
    // PC address and Wake-on-LAN
    // ------------------------------------------------------------------

    /**
     * Where LaunchBridge runs: blank follows the AI's PC; an address (with an
     * optional :port) makes PC control work even before Ollama is reachable.
     */
    private void promptAddress() {
        final Sheet s = ui.sheet("PC link", "PC address");
        String ai = aiHost();
        SpannableStringBuilder msg = new SpannableStringBuilder("The PC's address on your network, where "
                + "LaunchBridge runs.");
        if (ai.length() > 0) {
            msg.append(" Leave it blank to use the PC your AI runs on (").append(ui.mono(ai)).append(").");
        } else {
            msg.append(" Leave it blank to use the AI's PC once it's linked.");
        }
        s.message(msg);
        String custom = e.settings.bridgeHost();
        int port = e.settings.bridgePort();
        String value = custom.length() == 0 ? "" : (custom.indexOf(':') >= 0 ? "[" + custom + "]" : custom)
                + (port != com.omnideck.mobile.core.BridgeClient.DEFAULT_PORT ? ":" + port : "");
        final EditText field = ui.field(value, "192.168.1.20 or atlas-pc.local",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setTypeface(t.mono);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setContentDescription("PC address");
        field.setSelection(field.getText().length());
        s.body.addView(field, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        final TextView err = ui.text("", 12.5f, t.danger, t.body);
        err.setVisibility(View.GONE);
        s.body.addView(err, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        TextView note = ui.dim("Add :port when LaunchBridge doesn't listen on " + port + ".", 12.5f);
        s.body.addView(note, ui.margins(Ui.fillW(), 0, 8, 0, 4));
        if (custom.length() > 0) {
            s.neutral("Use AI's PC", new Runnable() {
                @Override
                public void run() {
                    applyAddress("", -1);
                }
            });
        }
        s.negative("Cancel", null);
        final android.widget.Button save = s.positive("Save", Ui.PRIMARY, false, new Runnable() {
            @Override
            public void run() {
                String v = field.getText().toString().trim();
                if (v.length() == 0) {
                    s.dismiss();
                    applyAddress("", -1);
                    return;
                }
                HostPort hp = HostPort.parse(v, e.settings.bridgePort());
                String why = hp == null ? "That isn't an address — try 192.168.1.20 or atlas-pc.local."
                        : hp.https ? "LaunchBridge speaks plain http on your network — enter just the address." : null;
                if (why != null) {
                    err.setText(why);
                    err.setVisibility(View.VISIBLE);
                    return;
                }
                s.dismiss();
                applyAddress(hp.host, hp.port);
            }
        });
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    save.performClick();
                    return true;
                }
                return false;
            }
        });
        s.showKeyboard(field);
        s.show();
    }

    private void applyAddress(String h, int port) {
        e.settings.setBridgeHost(h);
        if (port > 0 && port != e.settings.bridgePort()) e.settings.setBridgePort(port);
        e.log("info", h.length() > 0 ? "PC bridge address set to " + h : "PC bridge follows the AI's address again");
        // A new address (or port) is a new PC as far as this tab knows.
        host = e.bridgeHost();
        invalidatePc(true);
        if (host.length() == 0) {
            showState(NO_HOST);
            return;
        }
        showState(CHECKING);
        refreshLink(true);
    }

    /** The PC's MAC for Wake-on-LAN: typed here, or learned from its system info once paired. */
    private void promptMac(final boolean thenWake) {
        final Sheet s = ui.sheet("Wake-on-LAN", "The PC's MAC address");
        s.message("Wake-on-LAN wakes a sleeping PC over the network. It needs the MAC address of the PC's network "
                + "adapter — on the PC, run getmac or ipconfig /all. Once the bridge is paired, the phone also "
                + "learns it from the PC's system info.");
        final String current = e.settings.pcMac();
        final EditText field = ui.field(current, "AA:BB:CC:DD:EE:FF", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        field.setTypeface(t.mono);
        field.setSingleLine(true);
        field.setContentDescription("MAC address");
        field.setSelection(field.getText().length());
        s.body.addView(field, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        final TextView err = ui.text("", 12.5f, t.danger, t.body);
        err.setVisibility(View.GONE);
        s.body.addView(err, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        TextView note = ui.dim("The PC also needs Wake-on-LAN turned on in its BIOS and network adapter settings.",
                12.5f);
        s.body.addView(note, ui.margins(Ui.fillW(), 0, 8, 0, 4));
        if (current.length() > 0) {
            s.neutral("Clear", new Runnable() {
                @Override
                public void run() {
                    e.settings.setPcMac("");
                    renderPower();
                }
            });
        }
        s.negative("Cancel", null);
        s.positive(thenWake ? "Save & wake" : "Save", Ui.PRIMARY, false, new Runnable() {
            @Override
            public void run() {
                String n = WakeOnLan.normalize(field.getText().toString());
                if (n == null) {
                    err.setText("That isn't a MAC address — it looks like AA:BB:CC:DD:EE:FF.");
                    err.setVisibility(View.VISIBLE);
                    return;
                }
                e.settings.setPcMac(n);
                s.dismiss();
                renderPower();
                if (thenWake) wake();
            }
        });
        s.showKeyboard(field);
        s.show();
    }

    // ------------------------------------------------------------------
    // Power
    // ------------------------------------------------------------------

    /** One control on the power strip. */
    private static final class PowerKey {
        final String label;
        final int icon;
        final String description;
        final View.OnClickListener onClick;

        PowerKey(String label, int icon, String description, View.OnClickListener onClick) {
            this.label = label;
            this.icon = icon;
            this.description = description;
            this.onClick = onClick;
        }
    }

    /**
     * What the power strip offers right now: Wake while the PC is away, the
     * PC's own power tools once paired. One or two controls are wide
     * icon-and-label buttons; three or four share the row as console keys.
     */
    private void renderPower() {
        powerKeys.removeAllViews();
        String mac = WakeOnLan.normalize(e.settings.pcMac());
        boolean away = state == DOWN || (state == NO_HOST && mac != null);
        List<PowerKey> keys = new ArrayList<PowerKey>();
        if (away) {
            keys.add(new PowerKey("Wake PC", IconDrawable.POWER, "Wake PC", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    wake();
                }
            }));
        } else if (state == PAIRED && tools != null) {
            final BridgeTool sleep = PcTools.sleepTool(tools);
            final BridgeTool restart = PcTools.restartTool(tools);
            final BridgeTool shutdown = PcTools.shutdownTool(tools);
            if (BridgeTool.lockTool(tools) != null) {
                keys.add(new PowerKey("Lock", IconDrawable.LOCK, "Lock PC", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        lock();
                    }
                }));
            }
            if (sleep != null) {
                keys.add(new PowerKey("Sleep", IconDrawable.SLEEP, "Sleep PC", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        powerTool(sleep, "Sleep");
                    }
                }));
            }
            if (restart != null) {
                keys.add(new PowerKey("Restart", IconDrawable.RESTART, "Restart PC", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        powerTool(restart, "Restart");
                    }
                }));
            }
            if (shutdown != null) {
                keys.add(new PowerKey("Shut down", IconDrawable.POWER, "Shut down PC", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        powerTool(shutdown, "Shut down");
                    }
                }));
            }
        }
        boolean wide = keys.size() <= 2;
        for (int i = 0; i < keys.size(); i++) {
            PowerKey k = keys.get(i);
            View v = wide ? kit.action(k.label, k.icon, k.onClick) : kit.key(k.label, k.icon, k.description, k.onClick);
            v.setContentDescription(k.description);
            if (i > 0) powerKeys.addView(ui.space(8, 1));
            powerKeys.addView(v, Ui.weight(1));
        }
        wolShown = e.settings.pcMac();
        String wol = mac != null ? "WoL · " + mac : "Set up Wake-on-LAN";
        wolStatus.setText(wol);
        wolStatus.setTextColor(mac != null ? t.dim : t.hud ? t.accent : t.link);
        wolStatus.setContentDescription(mac != null ? "Wake-on-LAN: " + mac : "Set up Wake-on-LAN");
        powerBox.setVisibility(keys.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void powerNote(String s, int color) {
        powerNote.setText(s);
        powerNote.setTextColor(color);
        powerNote.setVisibility(View.VISIBLE);
    }

    private void wake() {
        if (WakeOnLan.parseMac(e.settings.pcMac()) == null) {
            promptMac(true);
            return;
        }
        powerNote("Sending the wake-up packet…", t.dim);
        e.wakePc(new Engine.Callback<String>() {
            @Override
            public void done(String v, String error) {
                if (destroyed) return;
                if (error != null) {
                    powerNote(error, t.danger);
                    return;
                }
                powerNote(v, t.dim);
                // A waking PC starts LaunchBridge within seconds: look again soon.
                if (state == DOWN) nextRetryAt = Math.min(nextRetryAt, SystemClock.elapsedRealtime() + 8000);
            }
        });
    }

    private void lock() {
        Runnable go = new Runnable() {
            @Override
            public void run() {
                final String tok = e.settings.bridgeToken();
                final int g = gen;
                powerNote("Locking the PC…", t.dim);
                e.lockPc(new Engine.Callback<String>() {
                    @Override
                    public void done(String said, String error) {
                        if (destroyed || g != gen) return;
                        if (error != null) {
                            if (linkFailure(error, tok)) return;
                            powerNote("Couldn't lock the PC — " + error, t.danger);
                            return;
                        }
                        accepted(tok);
                        powerNote(said + " · " + ui.clock(System.currentTimeMillis(), false), t.dim);
                    }
                });
            }
        };
        if (e.settings.confirmPcActions()) {
            ui.confirm("Lock the PC?", "The PC's screen locks right away.", "Lock", false, go);
        } else {
            go.run();
        }
    }

    /** Sleep / restart / shut down: always asks first (a tool with settings asks through its form). */
    private void powerTool(final BridgeTool tool, final String verb) {
        if (!tool.params.isEmpty()) {
            openTool(tool, null, false);
            return;
        }
        boolean wol = WakeOnLan.parseMac(e.settings.pcMac()) != null;
        String wakeHint = wol ? " You can wake it from here with Wake-on-LAN."
                : " Waking it takes someone at the PC (Wake-on-LAN isn't set up).";
        String title;
        String msg;
        if ("Sleep".equals(verb)) {
            title = "Put the PC to sleep?";
            msg = "LaunchBridge sleeps with it." + wakeHint;
        } else if ("Restart".equals(verb)) {
            title = "Restart the PC?";
            msg = "Unsaved work on the PC may be lost. The bridge is back once Windows has started LaunchBridge again.";
        } else {
            title = "Shut down the PC?";
            msg = "Unsaved work on the PC may be lost." + wakeHint;
        }
        ui.confirm(title, msg, verb, true, new Runnable() {
            @Override
            public void run() {
                runPowerTool(tool, verb);
            }
        });
    }

    private void runPowerTool(BridgeTool tool, final String verb) {
        final String tok = e.settings.bridgeToken();
        final int g = gen;
        powerNote(verb + " — sending…", t.dim);
        e.bridgeRun(tool.name, tool.template(), new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed || g != gen) return;
                if (error != null) {
                    if (linkFailure(error, tok)) return;
                    powerNote("Couldn't " + verb.toLowerCase(Locale.US) + " the PC — " + error, t.danger);
                    return;
                }
                accepted(tok);
                String said = r instanceof String ? ((String) r).trim() : "";
                powerNote((said.length() > 0 && said.length() <= 120 ? said : verb + " sent to the PC") + " · "
                        + ui.clock(System.currentTimeMillis(), false), t.dim);
            }
        });
    }

    // ------------------------------------------------------------------
    // Vitals
    // ------------------------------------------------------------------

    private void pollVitals() {
        final String tok = e.settings.bridgeToken();
        if (vitalsBusy) return;
        vitalsBusy = true;
        vitalsAskedAt = SystemClock.elapsedRealtime();
        final int g = gen;
        e.bridgeVitals(new Engine.Callback<Vitals>() {
            @Override
            public void done(Vitals v, String error) {
                if (destroyed || g != gen) return;
                vitalsBusy = false;
                if (error != null) {
                    if (linkFailure(error, tok)) return;
                    showVitalsError(error);
                    return;
                }
                accepted(tok);
                vitalsAt = SystemClock.elapsedRealtime();
                pcVitals = v;
                clearVitalsError();
                renderVitals(v);
                if (!wolShown.equals(e.settings.pcMac())) renderPower(); // Wake-on-LAN learned the MAC
                updateHeader();
                updateAges();
                syncMotion();
            }
        });
    }

    /**
     * No vitals yet: a compact block (what went wrong, what to change, Retry)
     * instead of an empty instrument grid. Earlier vitals: kept, dimmed, with
     * their age in the cap.
     */
    private void showVitalsError(String error) {
        vitalsErr = error;
        boolean had = vitalsAt > 0;
        vitalsError.setText("Couldn't read vitals — " + error);
        vitalsError.setVisibility(View.VISIBLE);
        vitalsRemedy.setText(remedy(error));
        vitalsFail.setVisibility(had ? View.GONE : View.VISIBLE);
        instruments.setVisibility(had ? View.VISIBLE : View.GONE);
        instruments.setAlpha(had ? 0.5f : 1f);
        updateAges();
        syncMotion();
    }

    private void clearVitalsError() {
        vitalsErr = null;
        vitalsError.setVisibility(View.GONE);
        vitalsFail.setVisibility(View.GONE);
        instruments.setVisibility(View.VISIBLE);
        instruments.setAlpha(1f);
    }

    /** What to change on the PC for a vitals error. */
    static String remedy(String error) {
        String l = error == null ? "" : error.toLowerCase(Locale.US);
        if (l.contains("doesn't support") || l.contains("unknown tool") || l.contains("not found")) {
            return "This bridge doesn't offer system info — update LaunchBridge on the PC, then retry.";
        }
        if (l.contains("denied") || l.contains("not allowed") || l.contains("permission") || l.contains("forbidden")) {
            return "Allow system info in LaunchBridge on the PC, then retry.";
        }
        return "Check LaunchBridge on the PC, then retry.";
    }

    /** The instruments with nothing on them (a new PC). */
    private void clearVitals() {
        clearVitalsError();
        sysLine.setVisibility(View.GONE);
        cpuGauge.setFraction(-1, false);
        cpuValue.setText("—");
        cpuTrace.setColor(t.data);
        cpuTrace.setData(new double[0]);
        cpuStats.setText("");
        cpuWindow.setText("");
        PcKit.Tile[] all = {ramTile, diskTile, powerTile, uptimeTile};
        for (PcKit.Tile tile : all) {
            tile.value.setText("—");
            tile.detail.setText("");
            if (tile != uptimeTile) {
                tile.meter.setFraction(0);
                tile.meter.setVisibility(View.VISIBLE);
            }
        }
        powerTile.label.setText(t.label("Power"));
        rawBox.setVisibility(View.GONE);
    }

    private void renderVitals(Vitals v) {
        boolean animate = !e.settings.reduceMotion();
        sysLine.setText(v.os);
        sysLine.setVisibility(v.os.length() == 0 ? View.GONE : View.VISIBLE);

        // CPU
        if (v.cpuPercent >= 0) {
            if (cpuCount == HISTORY) System.arraycopy(cpuHistory, 1, cpuHistory, 0, HISTORY - 1);
            else cpuCount++;
            cpuHistory[cpuCount - 1] = v.cpuPercent;
        }
        int cpuColor = level(v.cpuPercent, 85, 95);
        cpuGauge.setValueColor(cpuColor);
        cpuGauge.setFraction(v.cpuPercent < 0 ? -1 : (float) (v.cpuPercent / 100), animate);
        cpuValue.setText(v.cpuPercent < 0 ? "—" : kit.readout(PcKit.pct(v.cpuPercent), "%"));
        double[] trace = new double[cpuCount];
        System.arraycopy(cpuHistory, 0, trace, 0, cpuCount);
        cpuTrace.setColor(cpuColor);
        cpuTrace.setData(trace);
        if (cpuCount > 0) {
            double sum = 0, peak = 0;
            for (double d : trace) {
                sum += d;
                peak = Math.max(peak, d);
            }
            cpuStats.setText("avg " + PcKit.pct(sum / cpuCount) + "%  ·  peak " + PcKit.pct(peak) + "%");
            cpuWindow.setText(PcKit.span(cpuCount * POLL_MS / 1000));
        } else {
            cpuStats.setText("Load not reported");
            cpuWindow.setText("");
        }

        // Memory
        ramTile.value.setText(v.ramPercent < 0 ? "—" : kit.readout(PcKit.pct(v.ramPercent), "%"));
        ramTile.meter.setBarColor(level(v.ramPercent, 85, 95));
        ramTile.meter.setFraction((float) Math.max(0, v.ramPercent) / 100f);
        // An unknown share draws no bar at all (an empty track would read as 0% used).
        ramTile.meter.setVisibility(v.ramPercent < 0 ? View.INVISIBLE : View.VISIBLE);
        ramTile.detail.setText(v.ramUsedGb >= 0 && v.ramTotalGb > 0
                ? PcKit.gb(v.ramUsedGb).replace(" GB", "") + " / " + PcKit.gb(v.ramTotalGb)
                : v.ramTotalGb > 0 ? PcKit.gb(v.ramTotalGb) + " total" : v.ramPercent >= 0 ? "in use" : "not reported");

        // Disk
        diskTile.value.setText(v.diskPercent < 0 ? "—" : kit.readout(PcKit.pct(v.diskPercent), "%"));
        diskTile.meter.setBarColor(level(v.diskPercent, 90, 97));
        diskTile.meter.setFraction((float) Math.max(0, v.diskPercent) / 100f);
        diskTile.meter.setVisibility(v.diskPercent < 0 ? View.INVISIBLE : View.VISIBLE);
        String disk;
        if (v.diskFreeGb >= 0 && v.diskTotalGb > 0) {
            disk = PcKit.gb(v.diskFreeGb).replace(" GB", "") + " of " + PcKit.gb(v.diskTotalGb) + " free";
        } else if (v.diskFreeGb >= 0) {
            disk = PcKit.gb(v.diskFreeGb) + " free";
        } else {
            disk = v.diskPercent >= 0 ? "used" : "not reported";
        }
        diskTile.detail.setText(disk);

        // Power / battery
        if (v.batteryPercent >= 0) {
            powerTile.label.setText(t.label("Battery"));
            powerTile.value.setText(kit.readout(PcKit.pct(v.batteryPercent), "%"));
            powerTile.meter.setVisibility(View.VISIBLE);
            powerTile.meter.setBarColor(v.batteryPercent < 15 ? t.danger : v.batteryPercent < 30 ? t.warn
                    : "Charging".equals(v.power) ? t.ok : t.data);
            powerTile.meter.setFraction((float) v.batteryPercent / 100f);
            powerTile.detail.setText(v.power.length() > 0 ? v.power : "Battery");
        } else {
            powerTile.label.setText(t.label("Power"));
            boolean ac = v.power.startsWith("AC");
            powerTile.value.setText(v.power.length() == 0 ? "—" : ac ? "AC" : v.power);
            powerTile.meter.setVisibility(View.INVISIBLE);
            powerTile.detail.setText(ac ? "mains · no battery" : v.power.length() == 0 ? "not reported" : "");
        }

        // Uptime
        String up = PcKit.compactUptime(v.uptime);
        uptimeTile.value.setText(up.length() == 0 ? "—" : kit.units(up));
        uptimeTile.detail.setText(up.length() == 0 ? "not reported" : "since last boot");

        boolean unparsed = !v.hasAny() && v.raw.length() > 0;
        rawBox.setVisibility(unparsed ? View.VISIBLE : View.GONE);
        if (unparsed) rawText.setText(v.raw);
    }

    /** Data color, amber past {@code warn}%, red past {@code danger}%. */
    private int level(double pct, double warn, double danger) {
        if (pct >= danger) return t.danger;
        if (pct >= warn) return t.warn;
        return t.data;
    }

    // ------------------------------------------------------------------
    // Controls: which sections the PC offers
    // ------------------------------------------------------------------

    /** Shows only the controls the bridge has tools for (all of them while the list is unknown). */
    private void updateControlsForTools() {
        boolean known = tools != null;
        boolean vol = !known || PcTools.has(tools, "get_volume") || PcTools.has(tools, "set_volume");
        boolean shot = !known || PcTools.has(tools, "screenshot");
        boolean read = !known || PcTools.has(tools, "get_clipboard");
        boolean push = known && PcTools.has(tools, "set_clipboard");
        volumeSection.setVisibility(vol ? View.VISIBLE : View.GONE);
        screenSection.setVisibility(shot ? View.VISIBLE : View.GONE);
        screenRule.setVisibility(vol && shot ? View.VISIBLE : View.GONE);
        fetchBtn.setVisibility(read ? View.VISIBLE : View.GONE);
        clipRead.setVisibility(read ? View.VISIBLE : View.GONE);
        pushBtn.setVisibility(push ? View.VISIBLE : View.GONE);
        clipSection.setVisibility(read || push ? View.VISIBLE : View.GONE);
        clipRule.setVisibility((read || push) && (vol || shot) ? View.VISIBLE : View.GONE);
        controlsCard.setVisibility(vol || shot || read || push ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // Volume
    // ------------------------------------------------------------------

    /** Presets and slider work once the first read for this PC is back (either way). */
    private void syncVolumeControls() {
        PcKit.setSegmentsEnabled(presets, volKnown);
        slider.setEnabled(volKnown);
    }

    private void loadVolume() {
        final String tok = e.settings.bridgeToken();
        if (volBusy) return;
        volBusy = true;
        final int g = gen;
        e.bridgeRun("get_volume", null, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed || g != gen) return;
                volBusy = false;
                if (error != null) {
                    if (linkFailure(error, tok)) {
                        // A change queued behind this read is for a session that just ended: drop it.
                        volPending = -1;
                        return;
                    }
                    volKnown = true;
                    syncVolumeControls();
                    showVolNote("Couldn't read the volume — " + error);
                    // A level picked while the read was out still goes to the PC.
                    int p = volPending;
                    volPending = -1;
                    if (p >= 0) setVolume(p);
                    return;
                }
                accepted(tok);
                volKnown = true;
                syncVolumeControls();
                int lvl = PcKit.parseLevel(r);
                if (lvl < 0) {
                    showVolNote("The bridge didn't report a level: " + PcKit.pretty(r));
                    int p = volPending;
                    volPending = -1;
                    if (p >= 0) setVolume(p);
                    return;
                }
                if (PcKit.parseMuted(r) && lvl > 0) {
                    preMute = lvl;
                    lvl = 0;
                }
                volConfirmed = lvl;
                volNote.setVisibility(View.GONE);
                if (volPending < 0) showVolume(lvl);
                runPendingVolume();
            }
        });
    }

    private void showVolNote(String s) {
        volNote.setText(s);
        volNote.setVisibility(View.VISIBLE);
    }

    /** Shows a level (the latest requested one, or what the PC reported). */
    private void showVolume(int lvl) {
        volLevel = lvl;
        slider.setEnabled(true);
        slider.setValue(lvl);
        if (!slider.isDragging()) volValue.setText(kit.readout(String.valueOf(lvl), "%"));
        setMuteLabel(lvl == 0);
    }

    private void setMuteLabel(boolean muted) {
        String m = muted ? "Unmute" : "Mute";
        muteSeg.setText(t.hud ? m.toUpperCase(Locale.US) : m);
        muteSeg.setContentDescription(m);
        muteSeg.setTextColor(muted ? t.engagedInk : t.ink);
    }

    /** Mute (also when the level is unknown), or back to the level before muting. */
    private void toggleMute() {
        if (volLevel == 0) {
            setVolume(preMute > 0 ? preMute : 30);
        } else {
            if (volLevel > 0) preMute = volLevel;
            setVolume(0);
        }
    }

    /**
     * Shows the new level right away and tells the PC. While a request is in
     * flight, further changes collapse into one follow-up with the latest level;
     * a failure puts back what the PC last confirmed.
     */
    private void setVolume(final int level) {
        final String tok = e.settings.bridgeToken();
        showVolume(level);
        if (volBusy) {
            volPending = level;
            return;
        }
        volBusy = true;
        final int g = gen;
        JSONObject args = new JSONObject();
        try {
            args.put("level", level);
        } catch (JSONException ignored) {
            // "level" is a constant key; can't fail
        }
        e.bridgeRun("set_volume", args, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed || g != gen) return;
                volBusy = false;
                if (error != null) {
                    volPending = -1;
                    if (linkFailure(error, tok)) return;
                    ui.toast("Couldn't set the volume — " + error);
                    if (volConfirmed >= 0) showVolume(volConfirmed);
                    return;
                }
                accepted(tok);
                int got = PcKit.parseLevel(r);
                volConfirmed = got >= 0 ? got : level;
                volNote.setVisibility(View.GONE);
                if (volPending < 0) showVolume(volConfirmed);
                runPendingVolume();
            }
        });
    }

    private void runPendingVolume() {
        if (volPending < 0) return;
        int next = volPending;
        volPending = -1;
        if (next != volConfirmed) setVolume(next);
        else showVolume(next);
    }

    // ------------------------------------------------------------------
    // Screen capture
    // ------------------------------------------------------------------

    private void capture() {
        final String tok = e.settings.bridgeToken();
        if (shotBusy) return;
        shotBusy = true;
        shotBusyBox.setVisibility(View.VISIBLE);
        captureBtn.setEnabled(false);
        captureBtn.setAlpha(0.5f);
        syncMotion();
        final int g = gen;
        JSONObject args = new JSONObject();
        try {
            args.put("save", false);
        } catch (JSONException ignored) {
            // constant key
        }
        e.bridgeRun("screenshot", args, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed || g != gen) return;
                if (error != null) {
                    shotDone();
                    if (linkFailure(error, tok)) return;
                    shotFailed("Capture failed", error);
                    return;
                }
                accepted(tok);
                final String b64 = PcKit.extractImage(r);
                if (b64 == null) {
                    shotDone();
                    String meta = PcKit.imageMeta(r);
                    shotFailed("No image came back", meta.length() > 0 ? meta : PcKit.pretty(r));
                    return;
                }
                link.decode(b64, 1600, new PcLink.Result<PcLink.Decoded>() {
                    @Override
                    public void done(PcLink.Decoded d, String err) {
                        if (destroyed || g != gen) return;
                        shotDone();
                        if (err != null) {
                            shotFailed("Couldn't show the capture", err);
                            return;
                        }
                        shotB64 = b64;
                        shotBitmap = d.bitmap;
                        shotFrame.aspect = d.width > 0 ? d.height / (float) d.width : 9f / 16f;
                        shotEmpty.setClickable(false);
                        shotImage.setImageBitmap(d.bitmap);
                        shotImage.setVisibility(View.VISIBLE);
                        shotEmpty.setVisibility(View.GONE);
                        shotMeta.setText(d.width + " × " + d.height + "  ·  " + d.format + "  ·  "
                                + ui.clock(System.currentTimeMillis(), true) + "  ·  tap to expand");
                        shotMeta.setVisibility(View.VISIBLE);
                        shotActions.setVisibility(View.VISIBLE);
                        shotFrame.requestLayout();
                        e.log("ok", "PC · screen captured");
                    }
                });
            }
        });
    }

    private void shotDone() {
        shotBusy = false;
        shotBusyBox.setVisibility(View.GONE);
        captureBtn.setEnabled(true);
        captureBtn.setAlpha(1f);
        syncMotion();
    }

    private void shotFailed(String title, String detail) {
        ui.toast(title);
        if (shotB64 != null) return; // keep showing the previous capture
        shotEmptyTitle.setText(title);
        shotEmptyTitle.setTextColor(t.danger);
        shotEmptyText.setText(detail);
    }

    /** Back to "No capture yet" (another PC). */
    private void resetShot() {
        shotImage.setImageDrawable(null);
        shotImage.setVisibility(View.GONE);
        shotEmpty.setVisibility(View.VISIBLE);
        shotEmpty.setClickable(true);
        shotEmptyTitle.setText("No capture yet");
        shotEmptyTitle.setTextColor(t.ink);
        shotEmptyText.setText("Tap to see what's on the PC screen right now.");
        shotMeta.setVisibility(View.GONE);
        shotActions.setVisibility(View.GONE);
        shotFrame.aspect = 0.4f;
        shotFrame.requestLayout();
    }

    private void showFullscreen() {
        if (shotBitmap == null) return;
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(LIGHTBOX);
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(shotBitmap);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription("PC screenshot, tap to close");
        f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView hint = ui.readout(shotMeta.getText().toString().replace("tap to expand", "tap to close"), 11,
                LIGHTBOX_INK);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        hl.bottomMargin = ui.dp(24);
        f.addView(hint, hl);
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        d.setContentView(f);
        d.show();
    }

    /** Saves the capture to the phone; with {@code share}, then opens the share sheet (Android 10+). */
    private void saveShot(final boolean share) {
        if (shotB64 == null) return;
        link.save(a, shotB64, new PcLink.Result<PcLink.Saved>() {
            @Override
            public void done(PcLink.Saved s, String error) {
                if (destroyed) return;
                if (error != null) {
                    ui.toast("Couldn't save the screenshot — " + error);
                    return;
                }
                if (!share || s.uri == null) {
                    ui.toast("Saved to " + s.where);
                    return;
                }
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("image/*");
                i.putExtra(Intent.EXTRA_STREAM, s.uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    a.startActivity(Intent.createChooser(i, "Share screenshot"));
                } catch (RuntimeException ex) {
                    ui.toast("Saved to " + s.where + " — no app to share it with.");
                }
            }
        });
    }

    /** Sends the capture to the AI (vision models) with the user's question. */
    private void askAi() {
        if (shotB64 == null) return;
        String model = e.currentModel();
        if (Boolean.FALSE.equals(e.supportsVision(model))) {
            ui.toast(model + " can't see images — pick a vision model (llava, gemma3, qwen2.5vl…) in Models.");
            return;
        }
        if (e.state() != Engine.State.ONLINE) {
            ui.toast("Your AI isn't connected right now.");
            return;
        }
        ui.prompt("Ask OMNI about this screen", "Your question", "What's on my PC screen right now?",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, new Ui.TextResult() {
                    @Override
                    public void onText(final String q) {
                        final String question = q.trim().length() > 0 ? q.trim() : "What's on my PC screen right now?";
                        link.jpeg(shotB64, 1280, new PcLink.Result<String>() {
                            @Override
                            public void done(String jpeg, String error) {
                                if (destroyed) return;
                                if (error != null) {
                                    ui.toast(error);
                                    return;
                                }
                                List<String> images = new ArrayList<String>();
                                images.add(jpeg);
                                if (e.send(question, images)) a.select(MainActivity.TAB_COMMS, true);
                            }
                        });
                    }
                });
    }

    // ------------------------------------------------------------------
    // Clipboard
    // ------------------------------------------------------------------

    private void fetchClipboard() {
        final String tok = e.settings.bridgeToken();
        if (clipBusy) return;
        clipBusy = true;
        fetchBtn.setEnabled(false);
        fetchBtn.setAlpha(0.5f);
        clipHint("Reading the PC clipboard…", t.dim);
        final int g = gen;
        e.bridgeRun("get_clipboard", null, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed || g != gen) return;
                clipBusy = false;
                fetchBtn.setEnabled(true);
                fetchBtn.setAlpha(1f);
                if (error != null) {
                    showClip(null);
                    if (linkFailure(error, tok)) return;
                    clipHint("Couldn't read the clipboard — " + error, t.danger);
                    return;
                }
                accepted(tok);
                showClip(PcKit.clipText(r));
            }
        });
    }

    private void showClip(String text) {
        clipValue = text;
        boolean has = text != null && text.length() > 0;
        if (text == null) {
            clipHint("Fetch the text that's on the PC clipboard right now.", t.dim);
        } else if (!has) {
            clipHint("The PC clipboard is empty (or holds something other than text).", t.dim);
        } else {
            clipText.setText(text);
            clipText.setTextColor(t.ink);
            clipText.setTypeface(t.mono);
            clipText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
        }
        clipActions.setVisibility(has ? View.VISIBLE : View.GONE);
        if (text != null) {
            int lines = has ? text.split("\n", -1).length : 0;
            clipMeta.setText(lines + (lines == 1 ? " line" : " lines") + "  ·  " + text.length()
                    + (text.length() == 1 ? " char" : " chars") + "  ·  " + ui.clock(System.currentTimeMillis(), true));
        }
        clipMeta.setVisibility(text != null ? View.VISIBLE : View.GONE);
    }

    /** A status line in the clipboard well (reading text, not clipboard content). */
    private void clipHint(String s, int color) {
        clipText.setText(s);
        clipText.setTextColor(color);
        clipText.setTypeface(t.body);
        clipText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
    }

    private void pushClipboard() {
        final String tok = e.settings.bridgeToken();
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = cm == null ? null : cm.getPrimaryClip();
        CharSequence cs = cd != null && cd.getItemCount() > 0 ? cd.getItemAt(0).coerceToText(a) : null;
        final String text = cs == null ? "" : cs.toString();
        if (text.length() == 0) {
            ui.toast("The phone clipboard is empty.");
            return;
        }
        JSONObject args = new JSONObject();
        try {
            args.put("text", text);
        } catch (JSONException ignored) {
            // constant key
        }
        final int g = gen;
        e.bridgeRun("set_clipboard", args, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed || g != gen) return;
                if (error != null) {
                    if (!linkFailure(error, tok)) ui.toast("Couldn't set the PC clipboard — " + error);
                    return;
                }
                accepted(tok);
                ui.toast("Sent " + text.length() + " characters to the PC clipboard");
            }
        });
    }

    // ------------------------------------------------------------------
    // Launcher
    // ------------------------------------------------------------------

    private void searchApps(final String q) {
        final String tok = e.settings.bridgeToken();
        final int seq = ++appsSeq;
        appsMeter.setVisibility(View.VISIBLE);
        syncMotion();
        if (!appsLoaded) {
            appsState.setText("Loading the PC's app index…");
            appsState.setTextColor(t.dim);
            appsState.setVisibility(View.VISIBLE);
        }
        final int idxAsked = appsIndexed();
        e.bridgeApps(q, APP_LIMIT, new Engine.Callback<JSONArray>() {
            @Override
            public void done(JSONArray r, String error) {
                if (destroyed || seq != appsSeq) return;
                appsMeter.setVisibility(View.INVISIBLE);
                syncMotion();
                if (error != null) {
                    if (linkFailure(error, tok)) return;
                    appsLoaded = false;
                    apps = null;
                    appList.removeAllViews();
                    listLabel.setText("");
                    listCount.setText("");
                    appsMore.setVisibility(View.GONE);
                    appsHint.setVisibility(View.GONE);
                    appsState.setText("Couldn't search the PC's apps — " + error);
                    appsState.setTextColor(t.danger);
                    appsState.setVisibility(View.VISIBLE);
                    return;
                }
                accepted(tok);
                appsLoaded = true;
                appsQuery = q;
                apps = r;
                appsIndexedAtLoad = idxAsked;
                renderApps();
            }
        });
    }

    private void renderApps() {
        appList.removeAllViews();
        int total = apps == null ? 0 : apps.length();
        int limit = appsExpanded ? total : Math.min(total, APPS_SHOWN);
        boolean query = appsQuery.length() > 0;
        int indexed = appsIndexed();
        listLabel.setText(t.label(query ? total + (total == 1 ? " match" : " matches")
                + (total >= APP_LIMIT ? "+" : "") : "On this PC"));
        boolean partial = !query && total > 0 && indexed > total;
        listCount.setText(partial ? total + " of " + indexed : "");
        for (int i = 0; i < limit; i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            if (appList.getChildCount() > 0) {
                View sep = new View(a);
                sep.setBackgroundColor(t.hairSoft);
                appList.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(1, ui.dp(0.7f))));
            }
            appList.addView(appRow(app), Ui.fillW());
        }
        if (total == 0) {
            appsState.setText(query ? "No app on the PC matches “" + appsQuery + "”. Try part of the name — "
                    + "“code”, “spot”, “chrome”."
                    : "LaunchBridge hasn't indexed any apps yet. On the PC, open "
                    + "LaunchBridge and rebuild its app index, then refresh.");
            appsState.setTextColor(t.dim);
            appsState.setVisibility(View.VISIBLE);
        } else {
            appsState.setVisibility(View.GONE);
        }
        // Not everything the index holds is listed: say so, and how to reach the rest.
        boolean more = total > APPS_SHOWN;
        String hint = null;
        if (partial && (appsExpanded || !more)) {
            hint = "The bridge listed " + total + " of the " + indexed + " indexed apps — search to find the rest.";
        } else if (query && total >= APP_LIMIT && (appsExpanded || !more)) {
            hint = "Showing the first " + APP_LIMIT + " matches — type more of the name to narrow it down.";
        }
        appsHint.setText(hint == null ? "" : hint);
        appsHint.setVisibility(hint == null ? View.GONE : View.VISIBLE);
        if (more) {
            String label = appsExpanded ? "Show fewer" : "Show " + (total - APPS_SHOWN) + " more";
            appsMoreText.setText(t.hud ? label.toUpperCase(Locale.US) : label);
            appsMore.setContentDescription(label);
            appsMoreChevron.setRotation(appsExpanded ? 180f : 0f);
            appsMore.setVisibility(View.VISIBLE);
        } else {
            appsMore.setVisibility(View.GONE);
        }
    }

    private View appRow(final JSONObject app) {
        final String name = OllamaClient.str(app, "name");
        String path = OllamaClient.str(app, "path");
        String source = firstNonEmpty(OllamaClient.str(app, "source"), OllamaClient.str(app, "kind"));
        String hint = path.length() > 0 ? path : source;
        LinearLayout row = ui.hbox();
        row.setPadding(0, ui.dp(9), 0, ui.dp(9));
        row.setMinimumHeight(ui.dp(52));
        row.setBackground(ui.pressableRow(0));
        row.addView(kit.monogram(name), new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        LinearLayout texts = ui.vbox();
        texts.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        TextView n = ui.text(name.length() > 0 ? name : "Unnamed app", 14.5f, t.ink, t.bodyMedium);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(n, Ui.fillW());
        if (hint.length() > 0) {
            TextView h = ui.readout(hint, 11, t.dim);
            h.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            h.setPadding(0, ui.dp(4), 0, 0);
            texts.addView(h, Ui.fillW());
        }
        row.addView(texts, Ui.weight(1));
        row.addView(kit.icon(IconDrawable.PLAY, t.hud ? t.accent : t.data, 16), new LinearLayout.LayoutParams(ui.dp(28),
                ui.dp(28)));
        row.setContentDescription("Open " + name);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                confirmLaunch(app);
            }
        });
        return row;
    }

    private void confirmLaunch(final JSONObject app) {
        final String name = OllamaClient.str(app, "name");
        String path = OllamaClient.str(app, "path");
        ui.confirm("Open " + name + " on the PC?", path.length() > 0 ? path : "LaunchBridge will start it on your PC.",
                "Open", new Runnable() {
                    @Override
                    public void run() {
                        launch(app);
                    }
                });
    }

    private void launch(final JSONObject app) {
        final String tok = e.settings.bridgeToken();
        final String name = OllamaClient.str(app, "name");
        final int g = gen;
        String id = OllamaClient.str(app, "id");
        ui.toast("Opening " + name + "…");
        // The token only ever goes to the PC that issued it (the Engine's rule for every bridge call).
        String token = e.bridgePaired() ? tok : "";
        link.launch(e.bridgeHost(), e.settings.bridgePort(), token, id, new PcLink.Result<JSONObject>() {
            @Override
            public void done(JSONObject r, String error) {
                if (destroyed) return;
                if (error != null) {
                    if (g == gen && linkFailure(error, tok)) return;
                    e.log("warn", "PC · couldn't open " + name);
                    ui.toast("Couldn't open " + name + " — " + error);
                    return;
                }
                if (g == gen) accepted(tok);
                JSONObject opened = r.optJSONObject("app");
                String shown = opened != null && OllamaClient.str(opened, "name").length() > 0
                        ? OllamaClient.str(opened, "name") : name;
                e.log("ok", "PC · opened " + shown);
                ui.toast("Opened " + shown + " on the PC");
                if (g == gen) addRecent(app);
            }
        });
    }

    private SharedPreferences prefs() {
        return a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private JSONArray recents() {
        try {
            return new JSONArray(prefs().getString("recent_apps", "[]"));
        } catch (JSONException ex) {
            return new JSONArray();
        }
    }

    /** Recent apps belong to the PC they were opened on (an app id means nothing to another PC). */
    private boolean forThisPc(JSONObject o) {
        String h = OllamaClient.str(o, "host");
        return h.length() == 0 || HostPort.sameHost(h, host);
    }

    private void addRecent(JSONObject app) {
        String id = OllamaClient.str(app, "id");
        JSONArray old = recents();
        JSONArray out = new JSONArray();
        try {
            out.put(new JSONObject().put("id", id).put("name", OllamaClient.str(app, "name"))
                    .put("path", OllamaClient.str(app, "path")).put("host", host));
        } catch (JSONException ignored) {
            return;
        }
        int mine = 1;
        for (int i = 0; i < old.length() && out.length() < RECENT_KEEP; i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            boolean same = forThisPc(o);
            if (same && id.equals(OllamaClient.str(o, "id"))) continue;
            if (same && mine >= RECENT_MAX) continue;
            if (same) mine++;
            out.put(o);
        }
        prefs().edit().putString("recent_apps", out.toString()).apply();
        renderRecents();
    }

    private void renderRecents() {
        recentFlow.removeAllViews();
        JSONArray r = recents();
        for (int i = 0; i < r.length() && recentFlow.getChildCount() < RECENT_MAX; i++) {
            final JSONObject app = r.optJSONObject(i);
            if (app == null || !forThisPc(app)) continue;
            String name = OllamaClient.str(app, "name");
            TextView c = ui.actionChip(name, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmLaunch(app);
                }
            });
            c.setContentDescription("Open " + name + " again");
            recentFlow.addView(c);
        }
        recentBox.setVisibility(recentFlow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // Tool runner
    // ------------------------------------------------------------------

    private void toggleTools() {
        toolsOpen = !toolsOpen;
        toolsBody.setVisibility(toolsOpen ? View.VISIBLE : View.GONE);
        float rot = toolsOpen ? 180f : 0f;
        if (e.settings.reduceMotion()) toolsChevron.setRotation(rot);
        else toolsChevron.animate().rotation(rot).setDuration(160).start();
        if (toolsOpen && tools == null && !toolsBusy) loadTools();
        if (toolsOpen) {
            final View card = (View) toolsBody.getParent().getParent();
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.smoothScrollTo(0, Math.max(0, pairedBox.getTop() + card.getTop() - ui.dp(12)));
                }
            });
        }
    }

    private void loadTools() {
        final String tok = e.settings.bridgeToken();
        toolsBusy = true;
        final int g = gen;
        if (tools == null) {
            toolsState.setText("Asking the bridge which tools it offers…");
            toolsState.setTextColor(t.dim);
            toolsState.setVisibility(View.VISIBLE);
        }
        e.bridgeTools(new Engine.Callback<List<BridgeTool>>() {
            @Override
            public void done(List<BridgeTool> list, String error) {
                if (destroyed || g != gen) return;
                toolsBusy = false;
                if (error != null) {
                    if (linkFailure(error, tok)) return;
                    toolsState.setText("Couldn't list the tools — " + error);
                    toolsState.setTextColor(t.danger);
                    toolsState.setVisibility(View.VISIBLE);
                    return;
                }
                accepted(tok);
                tools = list;
                renderTools();
            }
        });
    }

    /** The runner's list, grouped (Power, Media & sound, Information, Other), plus what depends on the tools. */
    private void renderTools() {
        toolsList.removeAllViews();
        int n = tools == null ? 0 : tools.size();
        String count = tools == null ? "" : n + (n == 1 ? " tool" : " tools");
        toolsCount.setText(t.hud ? t.labelUnits(count) : count);
        if (tools != null) {
            for (String cat : PcTools.ORDER) {
                List<BridgeTool> group = PcTools.inCategory(tools, cat);
                if (group.isEmpty()) continue;
                toolsList.addView(toolKit.groupHeader(cat, group.size()),
                        ui.margins(Ui.fillW(), 0, toolsList.getChildCount() == 0 ? 4 : 18, 0, 2));
                for (int i = 0; i < group.size(); i++) {
                    final BridgeTool tool = group.get(i);
                    if (i > 0) {
                        View sep = new View(a);
                        sep.setBackgroundColor(t.hairSoft);
                        toolsList.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                Math.max(1, ui.dp(0.7f))));
                    }
                    toolsList.addView(toolKit.row(tool, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ui.tick(v);
                            openTool(tool, null, false);
                        }
                    }), Ui.fillW());
                }
            }
            if (n == 0) {
                toolsState.setText("The bridge doesn't list any desktop tools. Update LaunchBridge on the PC to get "
                        + "them.");
                toolsState.setTextColor(t.dim);
                toolsState.setVisibility(View.VISIBLE);
            } else {
                toolsState.setVisibility(View.GONE);
            }
        }
        updateControlsForTools();
        renderPower();
    }

    /** The tool's sheet: a form from its arguments (or raw JSON), then run. */
    private void openTool(final BridgeTool tool, JSONObject initial, boolean json) {
        PcToolForm.show(ui, kit, tool, initial, json, new PcToolForm.OnRun() {
            @Override
            public void run(JSONObject args, boolean fromJson) {
                runTool(tool, args, fromJson);
            }
        });
    }

    private void runTool(final BridgeTool tool, final JSONObject args, final boolean fromJson) {
        final String tok = e.settings.bridgeToken();
        final int g = gen;
        toolsState.setText("Running " + tool.name + "…");
        toolsState.setTextColor(t.dim);
        toolsState.setVisibility(View.VISIBLE);
        final long started = SystemClock.elapsedRealtime();
        e.bridgeRun(tool.name, args, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed || g != gen) return;
                toolsState.setVisibility(View.GONE);
                if (error != null && linkFailure(error, tok)) return;
                if (error == null) {
                    accepted(tok);
                    // The runner changed something the tab shows: read it again.
                    if (tool.name.contains("volume") || tool.name.contains("mute")) loadVolume();
                }
                if (!ui.canShowDialogs()) return;
                showResult(tool, args, fromJson, r, error, SystemClock.elapsedRealtime() - started);
            }
        });
    }

    private void showResult(final BridgeTool tool, final JSONObject args, final boolean fromJson, Object r,
                            String error, long ms) {
        Sheet s = ui.sheet(error == null ? "Tool result" : "Tool failed", tool.name);
        if (error != null) s.eyebrowColor(t.danger);
        TextView meta = ui.readout((error == null ? "OK" : "Failed") + "  ·  " + ms + " ms", 11.5f,
                error == null ? t.ok : t.danger);
        s.body.addView(meta, Ui.fillW());
        final String img = error == null ? PcKit.extractImage(r) : null;
        final String text = error != null ? error : img != null ? PcKit.imageMeta(r) : PcKit.pretty(r);
        if (img != null) {
            final ImageView iv = new ImageView(a);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setContentDescription(tool.name + " image");
            iv.setBackground(kit.well());
            s.body.addView(iv, ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ui.dp(160)), 0, 10, 0, 0));
            link.decode(img, 1200, new PcLink.Result<PcLink.Decoded>() {
                @Override
                public void done(PcLink.Decoded d, String err) {
                    if (d != null) iv.setImageBitmap(d.bitmap);
                }
            });
        }
        String shown = text.length() > 20000 ? text.substring(0, 20000) + "\n…" : text;
        TextView out = ui.readout(shown.length() > 0 ? shown : "(no output)", 12.5f, t.ink);
        out.setSingleLine(false);
        out.setLineSpacing(0, 1.25f);
        out.setTextIsSelectable(true);
        out.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        out.setBackground(kit.well());
        s.body.addView(out, ui.margins(Ui.fillW(), 0, 10, 0, 4));
        s.neutral("Run again", new Runnable() {
            @Override
            public void run() {
                openTool(tool, args, fromJson);
            }
        });
        s.negative("Close", null);
        s.positive("Copy", Ui.PRIMARY, new Runnable() {
            @Override
            public void run() {
                a.copy(tool.name + " result", text);
            }
        });
        s.show();
    }
}
