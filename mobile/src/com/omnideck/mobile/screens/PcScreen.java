package com.omnideck.mobile.screens;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
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
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.Vitals;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.PcFlow;
import com.omnideck.mobile.ui.PcKit;
import com.omnideck.mobile.ui.PcLink;
import com.omnideck.mobile.ui.PcSlider;
import com.omnideck.mobile.ui.PcTrace;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PC — remote control of the user's PC through OMNI-DECK's LaunchBridge:
 * the link status (reachable / paired, with guidance for each), live vitals
 * (CPU trace, memory, disk, power, uptime), volume, screen capture, the PC
 * clipboard, an app launcher with search and recents, and a raw tool runner.
 * Vitals poll every 5 s only while the tab is on screen.
 */
public final class PcScreen extends Screen {
    static final long POLL_MS = 5000;
    static final long RETRY_MS = 15000;
    static final int HISTORY = 36;
    static final int APP_LIMIT = 30;
    static final int APPS_SHOWN = 6;
    static final int RECENT_MAX = 6;
    static final String PREFS = "omnideck_pc";

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
    private boolean stopped;
    private boolean destroyed;

    // Link
    private int state = CHECKING;
    private boolean checking;
    private boolean freshShow;
    private String host = "";
    private JSONObject health;
    private long healthMs = -1;
    private String linkError = "";
    private long nextRetryAt;
    private boolean pairing;
    /** The token the bridge last refused ("" = none); a new token (from any pairing) clears the state. */
    private String rejectedToken = "";

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
    private TextView linkingHint;
    // Guidance / pairing
    private LinearLayout noHostCard;
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
    private TextView vitalsAge;
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
    private TextView vitalsError;
    private LinearLayout rawBox;
    private TextView rawText;
    private final double[] cpuHistory = new double[HISTORY];
    private int cpuCount;
    private boolean vitalsBusy;
    private long vitalsAskedAt;
    private long vitalsAt;
    private String vitalsErr;

    // Volume
    private TextView volValue;
    private PcSlider slider;
    private TextView muteSeg;
    private TextView volNote;
    /** The level on screen (the latest one asked for). */
    private int volLevel = -1;
    /** The level the PC last reported. */
    private int volConfirmed = -1;
    private int preMute = -1;
    private boolean volBusy;
    private int volPending = -1;

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
    private LinearLayout appList;
    private TextView appsMore;
    private TextView appsState;
    private Widgets.Meter appsMeter;
    private JSONArray apps;
    private String appsQuery = "";
    private boolean appsLoaded;
    private boolean appsExpanded;
    private int appsSeq;

    // Tool runner
    private ImageView toolsChevron;
    private LinearLayout toolsBody;
    private PcFlow toolsFlow;
    private TextView toolsState;
    private boolean toolsOpen;
    private boolean toolsBusy;
    private List<String> tools;

    private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM);

    public PcScreen(MainActivity a) {
        super(a);
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    @Override
    protected View build() {
        kit = new PcKit(ui);
        FrameLayout root = new FrameLayout(a);
        scroll = new ScrollView(a);
        LinearLayout col = ui.scrollColumn(scroll, 14, 12);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // Keep the launcher's search field from grabbing focus when the tab opens.
        col.setFocusableInTouchMode(true);
        col.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);

        col.addView(buildHeader(), Ui.fillW());
        linkingHint = ui.dim("", 13);
        linkingHint.setGravity(Gravity.CENTER);
        linkingHint.setPadding(ui.dp(16), ui.dp(22), ui.dp(16), 0);
        col.addView(linkingHint, Ui.fillW());
        noHostCard = buildNoHost();
        col.addView(noHostCard, gap());
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

        host = currentHost();
        showState(host.length() == 0 ? NO_HOST : CHECKING);
        return root;
    }

    private LinearLayout.LayoutParams gap() {
        return ui.margins(Ui.fillW(), 0, 12, 0, 0);
    }

    // --- PC LINK header -------------------------------------------------

    private View buildHeader() {
        stateChip = ui.chip("", t.faint);
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
                refreshLink();
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
        card.addView(body, Ui.fillW());
        return card;
    }

    // --- Guidance cards ---------------------------------------------------

    private LinearLayout buildNoHost() {
        LinearLayout card = ui.capCard("No AI link", null);
        LinearLayout body = ui.cardBody();
        body.addView(ui.body("LaunchBridge runs on the same PC as your AI. Link up with Ollama first — OMNI-DECK "
                + "finds it on your Wi-Fi automatically, or you can enter its address."), Ui.fillW());
        LinearLayout buttons = ui.hbox();
        TextView conn = ui.button("Connection", IconDrawable.WIFI, Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.showConnection();
            }
        });
        buttons.addView(conn, Ui.wrap());
        buttons.addView(ui.space(10, 1));
        buttons.addView(settingsButton(), Ui.wrap());
        body.addView(buttons, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        card.addView(body, Ui.fillW());
        return card;
    }

    private TextView settingsButton() {
        TextView b = ui.button("Settings", IconDrawable.SETTINGS, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.openSettings();
            }
        });
        b.setContentDescription("Bridge settings");
        return b;
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
        downError = ui.readout("", 11, t.faint);
        downError.setSingleLine(false);
        downError.setMaxLines(3);
        downError.setEllipsize(TextUtils.TruncateAt.END);
        downError.setLineSpacing(0, 1.2f);
        body.addView(downError, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        LinearLayout buttons = ui.hbox();
        TextView retry = ui.button("Retry", IconDrawable.REFRESH, Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLink();
            }
        });
        retry.setContentDescription("Retry");
        buttons.addView(retry, Ui.wrap());
        buttons.addView(ui.space(10, 1));
        buttons.addView(settingsButton(), Ui.wrap());
        body.addView(buttons, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        downRetry = ui.label("");
        downRetry.setTextColor(t.faint);
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
        unlocks.setTextColor(t.faint);
        body.addView(unlocks, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        PcFlow chips = new PcFlow(a, ui.dp(6), ui.dp(6));
        String[] caps = {"Vitals", "Volume", "Screen capture", "Clipboard", "App launcher", "Desktop tools"};
        for (String c : caps) chips.addView(ui.chip(c, t.data));
        body.addView(chips, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        pairBtn = ui.button("Pair with PC", 0, Ui.PRIMARY, new View.OnClickListener() {
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
        note.setTextColor(t.faint);
        body.addView(note, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        card.addView(body, Ui.fillW());
        return card;
    }

    // --- Vitals -------------------------------------------------------------

    private View buildVitals() {
        vitalsAge = ui.label("");
        vitalsAge.setTextColor(t.faint);
        LinearLayout card = ui.capCard("Vitals", vitalsAge);
        LinearLayout body = ui.cardBody();
        vitalsError = kit.notice("", t.warn);
        vitalsError.setVisibility(View.GONE);
        body.addView(vitalsError, ui.margins(Ui.fillW(), 0, 0, 0, 10));
        sysLine = ui.readout("", 11.5f, t.dim);
        sysLine.setEllipsize(TextUtils.TruncateAt.END);
        sysLine.setVisibility(View.GONE);
        body.addView(sysLine, ui.margins(Ui.fillW(), 0, 0, 0, 10));

        // CPU: gauge with the value inside, trace beside it.
        LinearLayout cpu = ui.hbox();
        cpu.setBackground(kit.well());
        cpu.setPadding(ui.dp(10), ui.dp(10), ui.dp(12), ui.dp(10));
        FrameLayout g = new FrameLayout(a);
        cpuGauge = new Widgets.Gauge(a, kit.meterTrack(), t.data);
        g.addView(cpuGauge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout inner = ui.vbox();
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        cpuValue = ui.readout("—", 21, t.inkStrong);
        cpuValue.setGravity(Gravity.CENTER);
        inner.addView(cpuValue, Ui.wrap());
        TextView cpuTag = ui.label("CPU");
        cpuTag.setTextColor(t.faint);
        cpuTag.setPadding(0, ui.dp(3), 0, 0);
        inner.addView(cpuTag, Ui.wrap());
        g.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        cpu.addView(g, new LinearLayout.LayoutParams(ui.dp(92), ui.dp(92)));
        LinearLayout trace = ui.vbox();
        trace.setPadding(ui.dp(12), 0, 0, 0);
        LinearLayout th = ui.hbox();
        th.addView(ui.label("Processor load"), Ui.weight(1));
        cpuWindow = ui.label("");
        cpuWindow.setTextColor(t.faint);
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
        body.addView(cpu, Ui.fillW());

        ramTile = kit.tile("Memory", true);
        diskTile = kit.tile("Disk", true);
        powerTile = kit.tile("Power", true);
        uptimeTile = kit.tile("Uptime", true);
        uptimeTile.meter.setVisibility(View.INVISIBLE); // keeps the detail lines of the row aligned
        body.addView(tileRow(ramTile, diskTile), ui.margins(Ui.fillW(), 0, 10, 0, 0));
        body.addView(tileRow(powerTile, uptimeTile), ui.margins(Ui.fillW(), 0, 10, 0, 0));

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
        body.addView(rawBox, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        card.addView(body, Ui.fillW());
        return card;
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
        LinearLayout card = ui.capCard("Controls", null);
        LinearLayout body = ui.cardBody();

        // Volume
        volValue = ui.readout("—", 16, t.inkStrong);
        body.addView(kit.section(IconDrawable.SPEAKER, "Volume", volValue), Ui.fillW());
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
        body.addView(slider, ui.margins(Ui.fillW(), 0, 4, 0, 0));
        LinearLayout seg = kit.segmented(new String[]{"Mute", "25", "50", "75", "Max"}, new PcKit.OnSegment() {
            @Override
            public void onSegment(int index) {
                if (index == 0) toggleMute();
                else setVolume(index == 4 ? 100 : index * 25);
            }
        });
        muteSeg = (TextView) seg.getChildAt(0);
        body.addView(seg, ui.margins(Ui.fillW(), 0, 4, 0, 0));
        volNote = ui.dim("", 12);
        volNote.setVisibility(View.GONE);
        body.addView(volNote, ui.margins(Ui.fillW(), 0, 8, 0, 0));

        body.addView(sectionRule(), ui.margins(Ui.fillW(), 0, 18, 0, 12));

        // Screen capture
        captureBtn = sectionAction("Capture", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                capture();
            }
        });
        captureBtn.setContentDescription("Capture screen");
        body.addView(kit.section(IconDrawable.CAMERA, "Screen", captureBtn), Ui.fillW());
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
        body.addView(shotFrame, ui.margins(Ui.fillW(), 0, 6, 0, 0));
        shotMeta = ui.readout("", 11, t.faint);
        shotMeta.setEllipsize(TextUtils.TruncateAt.END);
        shotMeta.setVisibility(View.GONE);
        body.addView(shotMeta, ui.margins(Ui.fillW(), 0, 8, 0, 0));
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
        body.addView(shotActions, ui.margins(Ui.fillW(), 0, 10, 0, 0));

        body.addView(sectionRule(), ui.margins(Ui.fillW(), 0, 18, 0, 12));

        // Clipboard
        fetchBtn = sectionAction("Fetch", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchClipboard();
            }
        });
        fetchBtn.setContentDescription("Get PC clipboard");
        body.addView(kit.section(IconDrawable.CLIPBOARD, "PC clipboard", fetchBtn), Ui.fillW());
        clipText = ui.readout("", 12.5f, t.dim);
        clipText.setSingleLine(false);
        clipText.setMaxLines(8);
        clipText.setEllipsize(TextUtils.TruncateAt.END);
        clipText.setLineSpacing(0, 1.25f);
        clipText.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11));
        clipText.setBackground(kit.well());
        clipHint("Fetch the text that's on the PC clipboard right now.", t.dim);
        body.addView(clipText, ui.margins(Ui.fillW(), 0, 6, 0, 0));
        clipMeta = ui.readout("", 11, t.faint);
        clipMeta.setVisibility(View.GONE);
        body.addView(clipMeta, ui.margins(Ui.fillW(), 0, 8, 0, 0));
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
        body.addView(clipActions, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        pushBtn = ui.button("Send phone clipboard to PC", IconDrawable.SHARE, Ui.GHOST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pushClipboard();
            }
        });
        pushBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pushBtn.setVisibility(View.GONE);
        body.addView(pushBtn, ui.margins(Ui.fillW(), 0, 6, 0, 0));
        card.addView(body, Ui.fillW());
        return card;
    }

    private View sectionRule() {
        View v = ui.divider();
        if (t.hud) v.setBackgroundColor(Theme.alpha(t.accent, 0x2E));
        return v;
    }

    /** A text action at the right end of a section heading, flush with the content edge. */
    private TextView sectionAction(String label, View.OnClickListener l) {
        TextView b = ui.button(label, 0, Ui.GHOST, l);
        b.setPadding(ui.dp(14), ui.dp(8), ui.dp(2), ui.dp(8));
        b.setMinHeight(ui.dp(36));
        return b;
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
        appsCount = ui.label("");
        appsCount.setTextColor(t.faint);
        LinearLayout card = ui.capCard("Launcher", appsCount);
        LinearLayout body = ui.cardBody();

        LinearLayout box = ui.hbox();
        box.setBackground(ui.rounded(t.input, t.hud ? t.edge : t.edge, 8));
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
        clearSearch = ui.iconButton(IconDrawable.CLOSE, "Clear search", t.faint, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
            }
        });
        clearSearch.setVisibility(View.GONE);
        box.addView(clearSearch, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        body.addView(box, Ui.fillW());

        recentBox = ui.vbox();
        TextView rl = ui.label("Recent");
        rl.setTextColor(t.faint);
        recentBox.addView(rl, Ui.fillW());
        recentFlow = new PcFlow(a, ui.dp(6), ui.dp(6));
        recentBox.addView(recentFlow, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        body.addView(recentBox, ui.margins(Ui.fillW(), 0, 14, 0, 0));

        LinearLayout lh = ui.hbox();
        listLabel = ui.label("");
        listLabel.setTextColor(t.faint);
        lh.addView(listLabel, Ui.weight(1));
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
        appsMore = ui.text("", t.hud ? 10.5f : 13, t.accent, t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) appsMore.setLetterSpacing(0.1f);
        appsMore.setPadding(0, ui.dp(12), 0, ui.dp(2));
        appsMore.setVisibility(View.GONE);
        appsMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                appsExpanded = !appsExpanded;
                renderApps();
            }
        });
        body.addView(appsMore, Ui.fillW());
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
        toolsChevron = new ImageView(a);
        toolsChevron.setImageDrawable(new IconDrawable(IconDrawable.CHEVRON, t.dim, t.dim, ui.dp(18)));
        toolsChevron.setScaleType(ImageView.ScaleType.CENTER);
        toolsChevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout card = ui.capCard("Advanced · Tool runner", toolsChevron);
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
        toolsChevron.getLayoutParams().width = ui.dp(34);
        LinearLayout body = ui.cardBody();
        body.addView(ui.dim("Run any desktop tool LaunchBridge exposes, with your own JSON arguments.", 13),
                Ui.fillW());
        toolsBody = ui.vbox();
        toolsBody.setVisibility(View.GONE);
        toolsState = ui.dim("", 12.5f);
        toolsState.setVisibility(View.GONE);
        toolsBody.addView(toolsState, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        toolsFlow = new PcFlow(a, ui.dp(7), ui.dp(7));
        toolsBody.addView(toolsFlow, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        TextView note = ui.dim("Tools run on the PC with LaunchBridge's permissions. Tap one to set its arguments.",
                12);
        note.setTextColor(t.faint);
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
        refreshLink();
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
            refreshLink();
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
        String h = currentHost();
        if (!h.equals(host)) {
            host = h;
            cpuCount = 0;
            vitalsAt = 0;
            if (h.length() == 0) showState(NO_HOST);
            else if (live()) refreshLink();
            else showState(CHECKING);
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
            if (state == DOWN && !checking && now >= nextRetryAt) refreshLink();
            updateAges();
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * The pulsing dot and the progress sweeps run only while the tab is visible;
     * with Reduce motion on, a busy meter holds a steady full bar instead.
     */
    private void syncMotion() {
        boolean on = live();
        boolean motion = !e.settings.reduceMotion();
        dot.setPulsing(on && motion && (state == PAIRED || state == CHECKING || checking));
        busyMeter(checkMeter, checking, on, motion);
        busyMeter(pairMeter, pairing, on, motion);
        busyMeter(shotMeter, shotBusy, on, motion);
        busyMeter(appsMeter, appsMeter.getVisibility() == View.VISIBLE, on, motion);
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

    private String currentHost() {
        return e.server() != null ? e.server().host : e.settings.lastHost();
    }

    private String address() {
        return host.length() == 0 ? "" : (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":"
                + e.settings.bridgePort();
    }

    /** Asks LaunchBridge's /health whether it's there, then routes to the right state. */
    private void refreshLink() {
        host = currentHost();
        if (host.length() == 0) {
            showState(NO_HOST);
            return;
        }
        if (checking) return;
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
                checking = false;
                if (destroyed) return;
                if (!checkedHost.equals(host)) {
                    refreshLink(); // the AI (and so the PC) moved while we were asking
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (error != null) {
                    health = null;
                    healthMs = -1;
                    linkError = error;
                    nextRetryAt = now + RETRY_MS;
                    showState(DOWN);
                    return;
                }
                health = v;
                healthMs = now - started;
                linkError = "";
                if (!e.bridgePaired()) showState(UNPAIRED);
                else if (e.settings.bridgeToken().equals(rejectedToken)) showState(REJECTED);
                else showState(PAIRED);
            }
        });
    }

    private void showState(int s) {
        boolean entered = s != state;
        state = s;
        noHostCard.setVisibility(s == NO_HOST ? View.VISIBLE : View.GONE);
        downCard.setVisibility(s == DOWN ? View.VISIBLE : View.GONE);
        pairCard.setVisibility(s == UNPAIRED || s == REJECTED ? View.VISIBLE : View.GONE);
        pairedBox.setVisibility(s == PAIRED ? View.VISIBLE : View.GONE);
        linkingHint.setVisibility(s == CHECKING ? View.VISIBLE : View.GONE);
        if (s == CHECKING) {
            linkingHint.setText(host.length() == 0 ? "Looking for your PC…"
                    : "Contacting LaunchBridge at " + address() + "…");
        }
        if (s == DOWN) {
            downLead.setText("OMNI-DECK can't reach LaunchBridge at " + address() + ". Check on the PC that:");
            // "Can't reach…" only repeats the checklist; other errors (HTTP 500, bad JSON…) are worth showing.
            boolean useful = linkError.length() > 0 && !linkError.startsWith("Can't reach");
            downError.setText(linkError);
            downError.setVisibility(useful ? View.VISIBLE : View.GONE);
        }
        if (s == UNPAIRED) {
            pairLead.setText("LaunchBridge is online at " + address() + ". Pair once to give this phone its own "
                    + "token — then it can read vitals, set the volume, capture the screen and open apps.");
        } else if (s == REJECTED) {
            pairLead.setText("The PC bridge no longer accepts this phone's token — LaunchBridge was reset or "
                    + "re-installed. Pair again to restore control.");
        }
        if (s == UNPAIRED || s == REJECTED) {
            setPairButton(s == REJECTED ? "Pair again" : "Pair with PC");
        }
        updateHeader();
        updateAges();
        syncMotion();
        if (s == PAIRED && (entered || freshShow)) {
            freshShow = false;
            loadPairedData();
        }
    }

    private void setPairButton(String label) {
        pairBtn.setText(t.hud ? label.toUpperCase(Locale.US) : label);
        pairBtn.setContentDescription(label);
    }

    private void loadPairedData() {
        pollVitals();
        loadVolume();
        if (tools == null && !toolsBusy) loadCapabilities();
        if (!appsLoaded) searchApps(search.getText().toString().trim());
    }

    private void updateHeader() {
        Vitals v = e.lastVitals();
        String name = v != null && v.host.length() > 0 ? v.host : "Your PC";
        hostTitle.setText(t.hud ? name.toUpperCase(Locale.US) : name);
        String addr = address();
        hostAddr.setText(addr.length() == 0 ? "No AI link yet" : addr);
        int color;
        String chip;
        String link;
        switch (state) {
            case NO_HOST:
                color = t.faint;
                chip = "No link";
                link = "—";
                break;
            case DOWN:
                color = t.danger;
                chip = "Offline";
                link = "Offline";
                break;
            case UNPAIRED:
                color = t.warn;
                chip = "Not paired";
                link = "Online";
                break;
            case REJECTED:
                color = t.warn;
                chip = "Re-pair";
                link = "Online";
                break;
            case PAIRED:
                color = t.ok;
                chip = "Paired";
                link = "Online";
                break;
            default:
                color = t.warn;
                chip = "Linking";
                link = "Checking";
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
        statLink.setText(link);
        statLink.setTextColor(state == DOWN ? t.danger : state == PAIRED || state == UNPAIRED || state == REJECTED
                ? t.ok : t.ink);
        statLatency.setText(healthMs >= 0 && state != DOWN ? healthMs + " ms" : "—");
        String ver = health == null ? "" : firstNonEmpty(OllamaClient.str(health, "version"),
                OllamaClient.str(health, "bridge_version"));
        statVersion.setText(ver.length() > 0 ? (ver.matches("\\d.*") ? "v" + ver : ver) : "—");
        int n = appsIndexed();
        statApps.setText(n >= 0 ? String.valueOf(n) : "—");
        appsCount.setText(t.label(n >= 0 ? n + " indexed" : ""));
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
            downRetry.setText(t.label(checking ? "Retrying…" : "Auto-retry in " + s + "s"));
        }
        if (state == PAIRED) {
            String txt;
            int color = t.faint;
            if (vitalsAt == 0) {
                txt = vitalsErr != null ? "No data" : "Reading…";
            } else if (vitalsErr != null) {
                txt = "Stale · " + PcKit.age(now - vitalsAt);
                color = t.warn;
            } else {
                txt = "Live · " + PcKit.age(now - vitalsAt);
            }
            vitalsAge.setText(t.label(txt));
            vitalsAge.setTextColor(color);
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
        boolean auth = low.contains("rejected the token") || low.contains("not paired");
        if (auth && !token.equals(e.settings.bridgeToken())) return true;
        if (low.contains("rejected the token")) {
            rejectedToken = token;
            showState(REJECTED);
            return true;
        }
        if (low.contains("not paired")) {
            showState(UNPAIRED);
            return true;
        }
        if (low.contains("can't reach the pc bridge")) {
            linkError = error;
            health = null;
            nextRetryAt = SystemClock.elapsedRealtime() + RETRY_MS;
            showState(DOWN);
            return true;
        }
        return false;
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
                    setPairButton(state == REJECTED ? "Pair again" : "Pair with PC");
                    pairError.setText("Pairing failed — " + error);
                    pairError.setVisibility(View.VISIBLE);
                    if (pairCard.getVisibility() != View.VISIBLE) ui.toast("Pairing failed — " + error);
                    return;
                }
                ui.toast("Paired with your PC");
                showState(PAIRED);
            }
        });
    }

    private void linkMenu() {
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        rows.add(new Ui.Row("Refresh link", "Check LaunchBridge again now", false, new Runnable() {
            @Override
            public void run() {
                refreshLink();
            }
        }, null));
        if (state == PAIRED || state == REJECTED) {
            rows.add(new Ui.Row("Pair again", "Get a fresh token from the bridge", false, new Runnable() {
                @Override
                public void run() {
                    pair();
                }
            }, null));
        }
        if (e.bridgePaired()) {
            rows.add(new Ui.Row("Forget pairing", "Remove this phone's token", false, new Runnable() {
                @Override
                public void run() {
                    ui.confirm("Forget pairing?", "This phone will lose PC control until you pair again.", "Forget",
                            new Runnable() {
                                @Override
                                public void run() {
                                    e.settings.setBridgeToken("");
                                    e.log("info", "PC bridge pairing removed");
                                    refreshLink();
                                }
                            });
                }
            }, null));
        }
        if (host.length() > 0) {
            rows.add(new Ui.Row("Copy bridge address", address(), false, new Runnable() {
                @Override
                public void run() {
                    a.copy("Bridge address", address());
                }
            }, null));
        }
        rows.add(new Ui.Row("Bridge settings", "Port, pairing and connection", false, new Runnable() {
            @Override
            public void run() {
                a.openSettings();
            }
        }, null));
        ui.pick("PC link", rows, null, null);
    }

    // ------------------------------------------------------------------
    // Vitals
    // ------------------------------------------------------------------

    private void pollVitals() {
        final String tok = e.settings.bridgeToken();
        if (vitalsBusy) return;
        vitalsBusy = true;
        vitalsAskedAt = SystemClock.elapsedRealtime();
        e.bridgeVitals(new Engine.Callback<Vitals>() {
            @Override
            public void done(Vitals v, String error) {
                vitalsBusy = false;
                if (destroyed) return;
                if (error != null) {
                    if (linkFailure(error, tok)) return;
                    vitalsErr = error;
                    vitalsError.setText("Couldn't read vitals — " + error);
                    vitalsError.setVisibility(View.VISIBLE);
                    updateAges();
                    return;
                }
                vitalsErr = null;
                vitalsAt = SystemClock.elapsedRealtime();
                vitalsError.setVisibility(View.GONE);
                renderVitals(v);
                updateHeader();
                updateAges();
            }
        });
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
            long span = cpuCount * POLL_MS / 1000;
            cpuStats.setText("avg " + PcKit.pct(sum / cpuCount) + "%  ·  peak " + PcKit.pct(peak) + "%");
            cpuWindow.setText(t.label(span < 60 ? span + "s" : span / 60 + "m"));
        } else {
            cpuStats.setText("Load not reported");
        }

        // Memory
        ramTile.value.setText(v.ramPercent < 0 ? "—" : kit.readout(PcKit.pct(v.ramPercent), "%"));
        ramTile.meter.setBarColor(level(v.ramPercent, 85, 95));
        ramTile.meter.setFraction((float) Math.max(0, v.ramPercent) / 100f);
        ramTile.detail.setText(v.ramUsedGb >= 0 && v.ramTotalGb > 0
                ? PcKit.gb(v.ramUsedGb).replace(" GB", "") + " / " + PcKit.gb(v.ramTotalGb)
                : v.ramTotalGb > 0 ? PcKit.gb(v.ramTotalGb) + " total" : v.ramPercent >= 0 ? "in use" : "not reported");

        // Disk
        diskTile.value.setText(v.diskPercent < 0 ? "—" : kit.readout(PcKit.pct(v.diskPercent), "%"));
        diskTile.meter.setBarColor(level(v.diskPercent, 90, 97));
        diskTile.meter.setFraction((float) Math.max(0, v.diskPercent) / 100f);
        String disk;
        if (v.diskFreeGb >= 0 && v.diskTotalGb > 0) {
            disk = PcKit.gb(v.diskFreeGb).replace(" GB", "") + " of " + PcKit.gb(v.diskTotalGb) + " free";
        }
        else if (v.diskFreeGb >= 0) disk = PcKit.gb(v.diskFreeGb) + " free";
        else disk = v.diskPercent >= 0 ? "used" : "not reported";
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
    // Volume
    // ------------------------------------------------------------------

    private void loadVolume() {
        final String tok = e.settings.bridgeToken();
        if (volBusy) return;
        volBusy = true;
        e.bridgeRun("get_volume", null, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                volBusy = false;
                if (destroyed) return;
                if (error != null) {
                    if (linkFailure(error, tok)) return;
                    showVolNote("Couldn't read the volume — " + error);
                    return;
                }
                int lvl = PcKit.parseLevel(r);
                if (lvl < 0) {
                    showVolNote("The bridge didn't report a level: " + PcKit.pretty(r));
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
        String m = lvl == 0 ? "Unmute" : "Mute";
        muteSeg.setText(t.hud ? m.toUpperCase(Locale.US) : m);
        muteSeg.setContentDescription(m);
        muteSeg.setTextColor(lvl == 0 ? t.engaged : t.ink);
    }

    private void toggleMute() {
        if (volLevel > 0) {
            preMute = volLevel;
            setVolume(0);
        } else {
            setVolume(preMute > 0 ? preMute : 30);
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
        JSONObject args = new JSONObject();
        try {
            args.put("level", level);
        } catch (JSONException ignored) {
            // "level" is a constant key; can't fail
        }
        e.bridgeRun("set_volume", args, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                volBusy = false;
                if (destroyed) return;
                if (error != null) {
                    volPending = -1;
                    if (linkFailure(error, tok)) return;
                    ui.toast("Couldn't set the volume — " + error);
                    if (volConfirmed >= 0) showVolume(volConfirmed);
                    return;
                }
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
        JSONObject args = new JSONObject();
        try {
            args.put("save", false);
        } catch (JSONException ignored) {
            // constant key
        }
        e.bridgeRun("screenshot", args, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed) return;
                if (error != null) {
                    shotDone();
                    if (linkFailure(error, tok)) return;
                    shotFailed("Capture failed", error);
                    return;
                }
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
                        shotDone();
                        if (destroyed) return;
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
                                + timeFormat.format(new Date()) + "  ·  tap to expand");
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

    private void showFullscreen() {
        if (shotBitmap == null) return;
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(0xFF000000);
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(shotBitmap);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription("PC screenshot, tap to close");
        f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView hint = ui.readout(shotMeta.getText().toString().replace("tap to expand", "tap to close"), 11,
                0xB3FFFFFF);
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
        e.bridgeRun("get_clipboard", null, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                clipBusy = false;
                if (destroyed) return;
                fetchBtn.setEnabled(true);
                fetchBtn.setAlpha(1f);
                if (error != null) {
                    showClip(null);
                    if (linkFailure(error, tok)) return;
                    clipHint("Couldn't read the clipboard — " + error, t.danger);
                    return;
                }
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
            clipMeta.setText(text.length() + (text.length() == 1 ? " char" : " chars") + "  ·  " + lines
                    + (lines == 1 ? " line" : " lines") + "  ·  " + timeFormat.format(new Date()));
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
        e.bridgeRun("set_clipboard", args, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed) return;
                if (error != null) {
                    if (!linkFailure(error, tok)) ui.toast("Couldn't set the PC clipboard — " + error);
                    return;
                }
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
                    appsMore.setVisibility(View.GONE);
                    appsState.setText("Couldn't search the PC's apps — " + error);
                    appsState.setTextColor(t.danger);
                    appsState.setVisibility(View.VISIBLE);
                    return;
                }
                appsLoaded = true;
                appsQuery = q;
                apps = r;
                renderApps();
            }
        });
    }

    private void renderApps() {
        appList.removeAllViews();
        int total = apps == null ? 0 : apps.length();
        int limit = appsExpanded ? total : Math.min(total, APPS_SHOWN);
        boolean query = appsQuery.length() > 0;
        listLabel.setText(t.label(query ? total + (total == 1 ? " match" : " matches")
                + (total >= APP_LIMIT ? "+" : "") : "On this PC"));
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
                    + "LaunchBridge and rebuild its app index.");
            appsState.setTextColor(t.dim);
            appsState.setVisibility(View.VISIBLE);
        } else {
            appsState.setVisibility(View.GONE);
        }
        if (total > APPS_SHOWN) {
            String label = appsExpanded ? "Show fewer" : "Show all " + total;
            appsMore.setText(t.hud ? label.toUpperCase(Locale.US) : label);
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
        android.util.TypedValue tv = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) row.setBackground(a.getDrawable(tv.resourceId));
        row.addView(kit.monogram(name), new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        LinearLayout texts = ui.vbox();
        texts.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        TextView n = ui.text(name.length() > 0 ? name : "Unnamed app", 14.5f, t.ink, t.bodyMedium);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(n, Ui.fillW());
        if (hint.length() > 0) {
            TextView h = ui.readout(hint, 11, t.faint);
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
        String id = OllamaClient.str(app, "id");
        ui.toast("Opening " + name + "…");
        link.launch(host, e.settings.bridgePort(), e.settings.bridgeToken(), id, new PcLink.Result<JSONObject>() {
            @Override
            public void done(JSONObject r, String error) {
                if (destroyed) return;
                if (error != null) {
                    if (linkFailure(error, tok)) return;
                    e.log("warn", "PC · couldn't open " + name);
                    ui.toast("Couldn't open " + name + " — " + error);
                    return;
                }
                JSONObject opened = r.optJSONObject("app");
                String shown = opened != null && OllamaClient.str(opened, "name").length() > 0
                        ? OllamaClient.str(opened, "name") : name;
                e.log("ok", "PC · opened " + shown);
                ui.toast("Opened " + shown + " on the PC");
                addRecent(app);
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

    private void addRecent(JSONObject app) {
        String id = OllamaClient.str(app, "id");
        JSONArray old = recents();
        JSONArray out = new JSONArray();
        try {
            out.put(new JSONObject().put("id", id).put("name", OllamaClient.str(app, "name"))
                    .put("path", OllamaClient.str(app, "path")));
        } catch (JSONException ignored) {
            return;
        }
        for (int i = 0; i < old.length() && out.length() < RECENT_MAX; i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !id.equals(OllamaClient.str(o, "id"))) out.put(o);
        }
        prefs().edit().putString("recent_apps", out.toString()).apply();
        renderRecents();
    }

    private void renderRecents() {
        recentFlow.removeAllViews();
        JSONArray r = recents();
        for (int i = 0; i < r.length(); i++) {
            final JSONObject app = r.optJSONObject(i);
            if (app == null) continue;
            String name = OllamaClient.str(app, "name");
            TextView c = ui.chip(name, t.hud ? t.accent : t.data);
            c.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
            c.setContentDescription("Open " + name + " again");
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    confirmLaunch(app);
                }
            });
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
        if (toolsOpen && tools == null && !toolsBusy) loadCapabilities();
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

    private void loadCapabilities() {
        final String tok = e.settings.bridgeToken();
        toolsBusy = true;
        toolsState.setText("Asking the bridge which tools it offers…");
        toolsState.setTextColor(t.dim);
        toolsState.setVisibility(View.VISIBLE);
        e.bridgeCapabilities(new Engine.Callback<List<String>>() {
            @Override
            public void done(List<String> list, String error) {
                toolsBusy = false;
                if (destroyed) return;
                if (error != null) {
                    if (linkFailure(error, tok)) return;
                    toolsState.setText("Couldn't list the tools — " + error);
                    toolsState.setTextColor(t.danger);
                    return;
                }
                tools = list;
                renderTools();
            }
        });
    }

    private void renderTools() {
        toolsFlow.removeAllViews();
        for (final String tool : tools) {
            TextView c = ui.chip(tool, t.hud ? t.accent : t.data);
            c.setTypeface(t.mono);
            c.setLetterSpacing(0);
            c.setAllCaps(false);
            c.setText(tool);
            c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
            c.setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7));
            c.setContentDescription("Run " + tool);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    promptArgs(tool, PcKit.argsTemplate(tool));
                }
            });
            toolsFlow.addView(c);
        }
        if (tools.isEmpty()) {
            toolsState.setText("The bridge doesn't list any desktop tools. Update LaunchBridge on the PC to get them.");
            toolsState.setTextColor(t.dim);
            toolsState.setVisibility(View.VISIBLE);
        } else {
            toolsState.setVisibility(View.GONE);
        }
        pushBtn.setVisibility(tools.contains("set_clipboard") ? View.VISIBLE : View.GONE);
    }

    /** Asks for a tool's JSON arguments; the dialog stays open until they parse. */
    private void promptArgs(final String tool, String initial) {
        LinearLayout box = ui.vbox();
        box.setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0);
        TextView lbl = ui.label("Arguments · JSON");
        box.addView(lbl, Ui.fillW());
        final EditText field = ui.field(initial, "{}", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTypeface(t.mono);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        field.setMinLines(3);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setContentDescription("Tool arguments");
        field.setSelection(field.getText().length());
        box.addView(field, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        final TextView err = ui.dim("", 12);
        err.setTextColor(t.danger);
        err.setVisibility(View.GONE);
        box.addView(err, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Run " + tool).setView(box)
                .setPositiveButton("Run", null).setNegativeButton("Cancel", null).create();
        dlg.show();
        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String raw = field.getText().toString().trim();
                JSONObject args;
                try {
                    args = raw.length() == 0 ? new JSONObject() : new JSONObject(raw);
                } catch (JSONException ex) {
                    err.setText("That isn't a JSON object — " + ex.getMessage());
                    err.setVisibility(View.VISIBLE);
                    return;
                }
                dlg.dismiss();
                runTool(tool, args);
            }
        });
    }

    private void runTool(final String tool, final JSONObject args) {
        final String tok = e.settings.bridgeToken();
        toolsState.setText("Running " + tool + "…");
        toolsState.setTextColor(t.dim);
        toolsState.setVisibility(View.VISIBLE);
        final long started = SystemClock.elapsedRealtime();
        e.bridgeRun(tool, args, new Engine.Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (destroyed) return;
                toolsState.setVisibility(View.GONE);
                if (error != null && linkFailure(error, tok)) return;
                showResult(tool, args, r, error, SystemClock.elapsedRealtime() - started);
            }
        });
    }

    private void showResult(final String tool, final JSONObject args, Object r, String error, long ms) {
        LinearLayout box = ui.vbox();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        TextView meta = ui.readout((error == null ? "OK" : "FAILED") + "  ·  " + ms + " ms", 11.5f,
                error == null ? t.ok : t.danger);
        box.addView(meta, Ui.fillW());
        final String img = error == null ? PcKit.extractImage(r) : null;
        final String text = error != null ? error : img != null ? PcKit.imageMeta(r) : PcKit.pretty(r);
        if (img != null) {
            final ImageView iv = new ImageView(a);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setContentDescription(tool + " image");
            box.addView(iv, ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ui.dp(160)), 0, 10, 0, 0));
            link.decode(img, 1200, new PcLink.Result<PcLink.Decoded>() {
                @Override
                public void done(PcLink.Decoded d, String err) {
                    if (d != null) iv.setImageBitmap(d.bitmap);
                }
            });
        }
        final String shown = text.length() > 20000 ? text.substring(0, 20000) + "\n…" : text;
        ScrollView sv = new ScrollView(a);
        TextView out = ui.readout(shown.length() > 0 ? shown : "(no output)", 12.5f, t.ink);
        out.setSingleLine(false);
        out.setLineSpacing(0, 1.25f);
        out.setTextIsSelectable(true);
        out.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        sv.addView(out);
        sv.setBackground(kit.well());
        box.addView(sv, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        new AlertDialog.Builder(a).setTitle(tool).setView(box)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        a.copy(tool + " result", text);
                    }
                })
                .setNeutralButton("Run again", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        promptArgs(tool, args.toString());
                    }
                })
                .setNegativeButton("Close", null).show();
    }
}
