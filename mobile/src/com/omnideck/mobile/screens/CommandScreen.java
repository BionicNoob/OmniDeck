package com.omnideck.mobile.screens;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.MainActivity;
import com.omnideck.mobile.Settings;
import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.core.HostPort;
import com.omnideck.mobile.core.LanScanner;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.ServerInfo;
import com.omnideck.mobile.core.Telemetry;
import com.omnideck.mobile.core.Vitals;
import com.omnideck.mobile.ui.CoreView;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.Panel;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * COMMAND — the home "mission control" tab. At a glance: the AI core (an
 * animated instrument that mirrors what the AI is doing), link status, the
 * active model and mode, one-tap quick actions, live telemetry, loaded
 * models, PC vitals from LaunchBridge and the system log. When the AI can't
 * be found it turns into a guided "get connected" panel.
 */
public final class CommandScreen extends Screen {
    static final int LOG_COLLAPSED = 12;
    static final int LOG_MAX = 40;
    static final long VITALS_MS = 10000;
    static final long PENDING_TIMEOUT_MS = 120000;

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final SimpleDateFormat shortClock = new SimpleDateFormat("HH:mm", Locale.US);

    private ScrollView scroll;

    // Hero
    private CoreView core;
    private Widgets.StatusDot coreDot;
    private TextView coreState;
    private TextView headline;
    private TextView detail;
    private LinearLayout modelChip;
    private TextView modelChipText;
    private ImageView modelChipDot;
    private LinearLayout modeChip;
    private TextView modeChipText;
    private ImageView modeChipIcon;
    private TextView liveLine;
    private String modelChipKey = "";
    private String modeChipKey = "";
    private TextView rdLink, rdLoaded, rdSpeed, rdUptime;

    // Offline guidance
    private LinearLayout offlineCard;
    private ImageView offlineIcon;
    private boolean offlineStyledSearching;
    private TextView offlineTitle;
    private TextView offlineDetail;
    private Widgets.Meter offlineMeter;
    private TextView scanAgainBtn;
    private boolean everOffline;

    // Quick actions
    private final List<Tile> tiles = new ArrayList<Tile>();
    private Tile readAloudTile;
    /** The action waiting for its chat notice to finish (warm, unload, bench, scan). */
    private Tile watchTile;
    private String watchId;
    private int watchFrom;

    // Telemetry
    private Metric latency, throughput, firstToken;
    private Widgets.Gauge ctxGauge;
    private TextView ctxPct, ctxCaption, ctxSide;
    private LinearLayout loadedBody;
    private TextView loadedSide;
    private String loadedSig = "";
    private TextView sesUptime, sesReplies, sesTokens, sesErrors, sesSide;

    // PC vitals
    private LinearLayout pcBody;
    private Widgets.StatusDot pcDot;
    private TextView pcSide;
    private Boolean pcShownPaired;
    private VitalRow cpuRow, ramRow, diskRow, batRow;
    private TextView pcFoot;
    private boolean vitalsInFlight;
    private String vitalsError;

    // System log
    private LinearLayout logList;
    private TextView logMore;
    private TextView logEmpty;
    private boolean logExpanded;

    // Live reply tracking (for the tok/s estimate and core pulses)
    private String liveId = "";
    private long liveFirstAt;
    private int liveLen;

    public CommandScreen(MainActivity a) {
        super(a);
    }

    // ------------------------------------------------------------------
    // Small view types
    // ------------------------------------------------------------------

    /** A single-line label that shrinks its text (down to a floor) to fit instead of clipping. */
    private static final class FitText extends TextView {
        private final float maxPx;
        private final float minPx;
        private final Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);

        FitText(Context c, float maxPx, float minPx) {
            super(c);
            this.maxPx = maxPx;
            this.minPx = minPx;
            setSingleLine(true);
            setIncludeFontPadding(false);
            setTextSize(TypedValue.COMPLEX_UNIT_PX, maxPx);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int avail = MeasureSpec.getSize(w) - getPaddingLeft() - getPaddingRight();
            if (MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && avail > 0) {
                probe.set(getPaint());
                String s = getText().toString();
                float size = maxPx;
                probe.setTextSize(size);
                while (size > minPx && probe.measureText(s) > avail) {
                    size = Math.max(minPx, size - 0.5f);
                    probe.setTextSize(size);
                }
                if (Math.abs(getTextSize() - size) > 0.01f) setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
            super.onMeasure(w, h);
        }
    }

    /** One quick-action tile: icon over a micro-caps label, with engaged / pending / disabled looks. */
    private final class Tile {
        final LinearLayout root;
        final ImageView icon;
        final FitText label;
        final Widgets.Meter busy;
        final boolean needsAi;
        int iconKind;
        boolean engaged;
        boolean enabled = true;
        boolean pending;
        long pendingSince;

        Tile(int iconKind, String text, String description, boolean needsAi) {
            this.iconKind = iconKind;
            this.needsAi = needsAi;
            root = ui.vbox();
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(ui.dp(4), ui.dp(11), ui.dp(4), 0);
            root.setContentDescription(description);
            root.setClickable(true);
            root.setFocusable(true);
            icon = new ImageView(a);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            root.addView(icon, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
            float max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, t.hud ? 8.5f : 11.5f,
                    a.getResources().getDisplayMetrics());
            label = new FitText(a, max, max * 0.78f);
            label.setText(t.hud ? text.toUpperCase(Locale.US) : text);
            label.setTypeface(t.hud ? t.labelFace : t.bodyMedium);
            if (t.hud) label.setLetterSpacing(0.08f);
            label.setGravity(Gravity.CENTER);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams llp = Ui.fillW();
            llp.topMargin = ui.dp(t.hud ? 8 : 7);
            root.addView(label, llp);
            busy = new Widgets.Meter(a, 0, t.hud ? t.accent : t.data);
            busy.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ui.dp(26), ui.dp(2));
            blp.topMargin = ui.dp(7);
            blp.bottomMargin = ui.dp(4);
            root.addView(busy, blp);
            render();
        }

        void render() {
            int fg = engaged ? t.engaged : t.hud ? t.accent : t.isDark ? t.ink : t.accent;
            icon.setImageDrawable(new IconDrawable(iconKind, fg, fg, ui.dp(22)));
            label.setTextColor(engaged ? t.engaged : t.ink);
            int fill = engaged ? Theme.alpha(t.engaged, t.isDark ? 0x1C : 0x14)
                    : t.hud ? t.chip : t.isDark ? t.chip : t.surface2;
            int edge = engaged ? Theme.alpha(t.engaged, 0x80) : t.hud ? t.hair : t.edge;
            Drawable bg = ui.rounded(fill, edge, t.hud ? 8 : 10);
            root.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 0x33)), bg, null));
            root.setAlpha(enabled ? 1f : 0.42f);
        }

        void setEngaged(boolean on) {
            if (engaged == on) return;
            engaged = on;
            render();
        }

        void setEnabled(boolean on) {
            if (enabled == on) return;
            enabled = on;
            root.setAlpha(on ? 1f : 0.42f);
        }

        void setPending(boolean on) {
            pending = on;
            pendingSince = on ? System.currentTimeMillis() : 0;
            busy.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (on) animateBusy(isShown());
            else busy.setIndeterminate(false);
        }

        /** The busy bar sweeps while the screen shows (and motion is allowed); otherwise it's a static line. */
        void animateBusy(boolean run) {
            if (run && !e.settings.reduceMotion()) busy.setIndeterminate(true);
            else busy.setFraction(1f);
        }
    }

    /** A telemetry tile: big mono readout + unit, a sparkline and a caption. */
    private static final class Metric {
        LinearLayout card;
        TextView value, unit, caption;
        Widgets.Sparkline spark;
    }

    /** One PC vitals line: micro-caps name, meter, mono value. */
    private static final class VitalRow {
        LinearLayout row;
        Widgets.Meter meter;
        TextView value;
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    @Override
    protected View build() {
        scroll = new ScrollView(a);
        LinearLayout col = ui.scrollColumn(scroll, 14, 12);
        col.addView(buildHero(), gap(Ui.fillW(), 0));
        offlineCard = buildOfflineCard();
        col.addView(offlineCard, gap(Ui.fillW(), 12));
        col.addView(buildQuickActions(), gap(Ui.fillW(), 12));
        col.addView(buildTelemetry(), gap(Ui.fillW(), 12));
        col.addView(buildLoaded(), gap(Ui.fillW(), 12));
        col.addView(buildSession(), gap(Ui.fillW(), 12));
        col.addView(buildPc(), gap(Ui.fillW(), 12));
        col.addView(buildLog(), gap(Ui.fillW(), 12));
        TextView foot = ui.label(t.hud ? "OMNI-DECK · Mobile " + a.appVersion() : "OMNI-DECK Mobile " + a.appVersion());
        foot.setTextColor(t.faint);
        foot.setGravity(Gravity.CENTER);
        col.addView(foot, gap(Ui.fillW(), 18));
        refreshAll();
        return scroll;
    }

    private LinearLayout.LayoutParams gap(LinearLayout.LayoutParams lp, float topDp) {
        lp.topMargin = ui.dp(topDp);
        return lp;
    }

    private LinearLayout plainCard() {
        LinearLayout l = ui.vbox();
        l.setBackground(ui.panel(true, 0));
        if (t.cardElevation > 0) l.setElevation(ui.dp(t.cardElevation));
        return l;
    }

    // --- Hero: the AI core ---------------------------------------------

    private View buildHero() {
        LinearLayout card = plainCard();
        card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(16));

        LinearLayout top = ui.hbox();
        top.addView(ui.label("AI core"), Ui.weight(1));
        coreDot = new Widgets.StatusDot(a);
        top.addView(coreDot, new LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)));
        coreState = ui.text("", t.hud ? 9.5f : 11, t.ok, t.labelFace);
        coreState.setLetterSpacing(t.hud ? 0.16f : 0.04f);
        coreState.setPadding(ui.dp(5), 0, 0, 0);
        top.addView(coreState);
        card.addView(top, Ui.fillW());

        FrameLayout stage = new FrameLayout(a);
        core = new CoreView(a, t, e.settings.hudEffects());
        core.setReduceMotion(e.settings.reduceMotion());
        core.setContentDescription("AI core");
        core.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                // Say what tap and hold do (TalkBack reads these as "double-tap to …").
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                        "Open Comms"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, "Talk to OMNI"));
            }
        });
        core.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                a.select(MainActivity.TAB_COMMS, true);
            }
        });
        core.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                ui.tick(v);
                a.commander().run("/voice");
                return true;
            }
        });
        stage.addView(core, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rdLink = readout(stage, "Link", Gravity.TOP | Gravity.START);
        rdSpeed = readout(stage, "Speed", Gravity.TOP | Gravity.END);
        rdLoaded = readout(stage, "Loaded", Gravity.BOTTOM | Gravity.START);
        rdUptime = readout(stage, "Uptime", Gravity.BOTTOM | Gravity.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(214));
        slp.topMargin = ui.dp(6);
        card.addView(stage, slp);

        headline = ui.text("", t.hud ? 11 : 15, t.ok, t.hud ? t.labelFace : t.bodySemi);
        headline.setLetterSpacing(t.hud ? 0.18f : 0f);
        headline.setGravity(Gravity.CENTER);
        headline.setSingleLine(true);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(headline, gap(Ui.fillW(), 10));

        detail = ui.text("", 12, t.dim, t.mono);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        detail.setLineSpacing(0, 1.2f);
        card.addView(detail, gap(Ui.fillW(), 6));

        LinearLayout chips = ui.hbox();
        chips.setGravity(Gravity.CENTER);
        modelChip = chip("Switch model");
        modelChipDot = new ImageView(a);
        modelChip.addView(modelChipDot, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        modelChipText = chipText();
        modelChip.addView(modelChipText, chipTextLp());
        ImageView caret = new ImageView(a);
        caret.setImageDrawable(new IconDrawable(IconDrawable.CHEVRON, t.dim, t.dim, ui.dp(14)));
        modelChip.addView(caret, new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)));
        modelChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                modelPicker();
            }
        });
        LinearLayout.LayoutParams mlp = Ui.wrap();
        chips.addView(modelChip, mlp);
        modeChip = chip("Change mode");
        modeChipIcon = new ImageView(a);
        modeChip.addView(modeChipIcon, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        modeChipText = chipText();
        modeChip.addView(modeChipText, chipTextLp());
        modeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                a.commander().cycleMode();
            }
        });
        LinearLayout.LayoutParams dlp = Ui.wrap();
        dlp.leftMargin = ui.dp(8);
        chips.addView(modeChip, dlp);
        card.addView(chips, gap(Ui.fillW(), 14));

        liveLine = ui.text("", t.hud ? 11 : 12.5f, t.faint, t.hud ? t.mono : t.body);
        liveLine.setGravity(Gravity.CENTER);
        liveLine.setSingleLine(true);
        liveLine.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(liveLine, gap(Ui.fillW(), 12));
        return card;
    }

    /** A corner readout over the core stage: micro-caps name over a mono value. */
    private TextView readout(FrameLayout stage, String name, int gravity) {
        LinearLayout box = ui.vbox();
        boolean end = (gravity & Gravity.END) == Gravity.END;
        box.setGravity(end ? Gravity.END : Gravity.START);
        TextView l = ui.label(name);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.hud ? 8 : 10);
        l.setTextColor(t.faint);
        box.addView(l, Ui.wrap());
        TextView v = ui.readout("—", t.hud ? 14 : 14.5f, t.ink);
        v.setPadding(0, ui.dp(4), 0, 0);
        box.addView(v, Ui.wrap());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(0, ui.dp(4), 0, ui.dp(4));
        stage.addView(box, lp);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    private LinearLayout chip(String description) {
        LinearLayout c = ui.hbox();
        c.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        c.setMinimumHeight(ui.dp(34));
        c.setContentDescription(description);
        c.setClickable(true);
        c.setFocusable(true);
        return c;
    }

    private TextView chipText() {
        TextView tv = ui.text("", t.hud ? 10.5f : 13, t.ink, t.hud ? t.mono : t.bodyMedium);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(ui.dp(170));
        return tv;
    }

    private LinearLayout.LayoutParams chipTextLp() {
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.leftMargin = ui.dp(7);
        lp.rightMargin = ui.dp(4);
        return lp;
    }

    private void styleChip(LinearLayout c, int color) {
        Drawable bg = ui.rounded(Theme.alpha(color, t.isDark ? 0x17 : 0x10), Theme.alpha(color, t.hud ? 0x59 : 0x4D),
                t.hud ? 6 : 17);
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 0x33)), bg, null));
    }

    // --- Offline guidance ----------------------------------------------

    private LinearLayout buildOfflineCard() {
        LinearLayout card = ui.vbox();
        offlineCard = card;
        if (t.cardElevation > 0) card.setElevation(ui.dp(t.cardElevation));
        card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16));

        LinearLayout head = ui.hbox();
        offlineIcon = new ImageView(a);
        head.addView(offlineIcon, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        offlineTitle = ui.text("", t.hud ? 12 : 16, t.inkStrong, t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) offlineTitle.setLetterSpacing(0.1f);
        offlineTitle.setPadding(ui.dp(10), 0, 0, 0);
        head.addView(offlineTitle, Ui.weight(1));
        card.addView(head, Ui.fillW());

        offlineDetail = ui.dim("", 13);
        card.addView(offlineDetail, gap(Ui.fillW(), 8));
        offlineMeter = new Widgets.Meter(a, Theme.alpha(t.warn, 0x26), t.warn);
        offlineMeter.setVisibility(View.GONE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(3));
        mlp.topMargin = ui.dp(10);
        card.addView(offlineMeter, mlp);

        View rule = ui.divider();
        card.addView(rule, gap(Ui.fillW(), 12));
        card.addView(step(1, "On the PC, start Ollama so it listens on the network, not only on the PC itself:"),
                gap(Ui.fillW(), 12));
        LinearLayout.LayoutParams clp = gap(Ui.fillW(), 8);
        clp.leftMargin = ui.dp(28);
        card.addView(codeLine("OLLAMA_HOST=0.0.0.0"), clp);
        card.addView(step(2, "Join this phone to the same Wi-Fi as the PC (guest networks usually block it)."),
                gap(Ui.fillW(), 12));
        card.addView(step(3, "Allow port 11434 through the PC's firewall for private networks."), gap(Ui.fillW(), 10));

        // Primary action full width, the manual fallback as a quiet link under it.
        scanAgainBtn = ui.button("Scan again", IconDrawable.SCAN, Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (e.isScanning()) {
                    ui.toast("Already scanning…");
                    return;
                }
                e.discover(true);
            }
        });
        scanAgainBtn.setContentDescription("Scan again");
        scanAgainBtn.setMinHeight(ui.dp(44));
        centerCompound(scanAgainBtn);
        card.addView(scanAgainBtn, gap(Ui.fillW(), 18));
        TextView addr = ui.button("Enter address", IconDrawable.EDIT, Ui.GHOST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.promptServerAddress();
            }
        });
        addr.setContentDescription("Enter address");
        if (t.id == Theme.DARK) addr.setTextColor(t.ink);
        LinearLayout.LayoutParams alp = Ui.wrap();
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        alp.topMargin = ui.dp(4);
        card.addView(addr, alp);
        styleOfflineCard(false);
        card.setVisibility(View.GONE);
        return card;
    }

    /**
     * Keeps a full-width button's leading icon next to its centered label
     * (a compound drawable otherwise hugs the left edge).
     */
    private void centerCompound(final TextView b) {
        b.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int top, int r, int bottom, int ol, int ot, int or, int ob) {
                Drawable icon = b.getCompoundDrawables()[0];
                float content = b.getPaint().measureText(b.getText().toString())
                        + (icon != null ? icon.getBounds().width() + b.getCompoundDrawablePadding() : 0);
                int pad = Math.max(ui.dp(12), Math.round((r - l - content) / 2f));
                if (Math.abs(b.getPaddingLeft() - pad) > 1) {
                    final int p = pad;
                    b.post(new Runnable() {
                        @Override
                        public void run() {
                            b.setPadding(p, b.getPaddingTop(), p, b.getPaddingBottom());
                        }
                    });
                }
            }
        });
    }

    /** Red while the AI is missing, amber while a scan runs. */
    private void styleOfflineCard(boolean searching) {
        int c = searching ? t.warn : t.danger;
        Panel.Builder b = Panel.builder().fill(t.surface).edge(Theme.alpha(c, t.hud ? 0x73 : 0x66),
                Math.max(1, ui.dp(1))).radius(ui.dp(t.radius)).highlight(t.panelHi);
        if (t.hud) b.brackets(ui.dp(10), ui.dp(1.2f), Theme.alpha(c, 0xB3)).bracketInset(ui.dp(5));
        offlineCard.setBackground(b.build());
        int icon = searching ? IconDrawable.SCAN : IconDrawable.WIFI;
        offlineIcon.setImageDrawable(new IconDrawable(icon, c, c, ui.dp(22)));
    }

    private View step(int n, String text) {
        LinearLayout row = ui.hbox();
        row.setGravity(Gravity.TOP);
        TextView num = ui.text(String.format(Locale.US, "%02d", n), 12, t.hud ? t.accent : t.label, t.mono);
        num.setPadding(0, ui.dp(2), ui.dp(10), 0);
        row.addView(num, Ui.wrap());
        TextView body = ui.body(text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        row.addView(body, Ui.weight(1));
        return row;
    }

    /** A copyable command snippet in the mono face. */
    private View codeLine(final String code) {
        LinearLayout row = ui.hbox();
        row.setPadding(ui.dp(12), ui.dp(2), ui.dp(4), ui.dp(2));
        row.setBackground(ui.rounded(t.codeBg, t.hud ? t.edge : t.edge, 8));
        TextView tv = ui.text(code, 13.5f, t.hud ? t.codeText : t.inlineCodeText, t.mono);
        row.addView(tv, Ui.weight(1));
        ImageView copy = ui.iconButton(IconDrawable.COPY, "Copy OLLAMA_HOST setting", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.copy("OLLAMA_HOST", code);
            }
        });
        row.addView(copy, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
        return row;
    }

    // --- Quick actions ---------------------------------------------------

    private View buildQuickActions() {
        LinearLayout card = ui.capCard("Quick actions", null);
        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        Tile talk = tile(IconDrawable.MIC, "Talk", "Talk", false, new Runnable() {
            @Override
            public void run() {
                a.commander().run("/voice");
            }
        });
        Tile newChat = tile(IconDrawable.PLUS, "New chat", "New chat", false, new Runnable() {
            @Override
            public void run() {
                e.newChat();
                a.select(MainActivity.TAB_COMMS, true);
            }
        });
        Tile summarize = tile(IconDrawable.DOC, "Summarize", "Summarize chat", true, new Runnable() {
            @Override
            public void run() {
                summarize();
            }
        });
        readAloudTile = tile(IconDrawable.SPEAKER_OFF, "Read aloud", "Read aloud", false, new Runnable() {
            @Override
            public void run() {
                boolean on = !e.settings.readAloud();
                e.setReadAloud(on);
                ui.toast(on ? "Reading replies aloud" : "Read-aloud off");
            }
        });
        final Tile[] warm = new Tile[1];
        warm[0] = tile(IconDrawable.BOLT, "Warm up", "Warm model", true, new Runnable() {
            @Override
            public void run() {
                String m = e.currentModel();
                if (m.length() == 0) {
                    ui.toast("No models installed on the PC yet.");
                    return;
                }
                watch(warm[0]);
                e.warm();
                ui.toast("Loading " + m + " into memory…");
            }
        });
        final Tile[] unload = new Tile[1];
        unload[0] = tile(IconDrawable.POWER, "Unload", "Unload model", true, new Runnable() {
            @Override
            public void run() {
                unload(unload[0]);
            }
        });
        final Tile[] bench = new Tile[1];
        bench[0] = tile(IconDrawable.ACTIVITY, "Benchmark", "Benchmark", true, new Runnable() {
            @Override
            public void run() {
                if (e.currentModel().length() == 0) {
                    ui.toast("No models installed on the PC yet.");
                    return;
                }
                watch(bench[0]);
                e.bench();
                ui.toast("Benchmarking " + e.currentModel() + "…");
            }
        });
        Tile models = tile(IconDrawable.NAV_MODELS, "Models", "Open Models", false, new Runnable() {
            @Override
            public void run() {
                a.select(MainActivity.TAB_MODELS, true);
            }
        });
        final Tile[] scan = new Tile[1];
        scan[0] = tile(IconDrawable.SCAN, "Scan", "Scan network", false, new Runnable() {
            @Override
            public void run() {
                if (e.isScanning()) {
                    ui.toast("Already scanning…");
                    return;
                }
                watch(scan[0]);
                e.discover(true);
                ui.toast("Scanning the network…");
            }
        });
        Tile pc = tile(IconDrawable.NAV_PC, "PC", "Open PC", false, new Runnable() {
            @Override
            public void run() {
                a.select(MainActivity.TAB_PC, true);
            }
        });
        Tile history = tile(IconDrawable.HISTORY, "History", "Chat history", false, new Runnable() {
            @Override
            public void run() {
                a.commander().run("/history");
            }
        });
        Tile diag = tile(IconDrawable.TERMINAL, "Diagnose", "Diagnostics", false, new Runnable() {
            @Override
            public void run() {
                a.commander().run("/debug");
                a.select(MainActivity.TAB_COMMS, true);
            }
        });
        Tile[][] grid = {{talk, newChat, summarize, readAloudTile}, {warm[0], unload[0], bench[0], models},
                {scan[0], pc, history, diag}};
        for (int r = 0; r < grid.length; r++) {
            LinearLayout row = ui.hbox();
            row.setBaselineAligned(false);
            for (int c = 0; c < grid[r].length; c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.dp(t.hud ? 70 : 72), 1);
                if (c > 0) lp.leftMargin = ui.dp(8);
                row.addView(grid[r][c].root, lp);
            }
            body.addView(row, gap(Ui.fillW(), r == 0 ? 0 : 8));
        }
        card.addView(body, Ui.fillW());
        return card;
    }

    private Tile tile(int icon, String label, String description, boolean needsAi, final Runnable action) {
        final Tile tl = new Tile(icon, label, description, needsAi);
        tl.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                if (tl.pending) {
                    ui.toast("Still working on it…");
                    return;
                }
                if (tl.needsAi && e.state() != Engine.State.ONLINE) {
                    ui.toast(e.state() == Engine.State.SEARCHING ? "Still looking for your AI…"
                            : "Your AI is offline. Scan again or enter its address first.");
                    return;
                }
                action.run();
            }
        });
        tiles.add(tl);
        return tl;
    }

    private void summarize() {
        int sent = 0;
        for (ChatMessage m : e.conversation().messages) {
            if (m.sentToModel()) sent++;
        }
        if (sent < 2) {
            ui.toast("Nothing to summarize yet. Start a conversation in Comms first.");
            return;
        }
        if (e.isBusy()) {
            ui.toast("Wait for the current reply to finish.");
            return;
        }
        e.summarize();
        a.select(MainActivity.TAB_COMMS, true);
    }

    private void unload(final Tile tl) {
        final List<String> loaded = new ArrayList<String>();
        for (ModelInfo m : e.models()) {
            if (e.isLoaded(m.name)) loaded.add(m.name);
        }
        if (loaded.isEmpty()) {
            ui.toast("No model is loaded in memory right now.");
            return;
        }
        if (loaded.size() == 1) {
            watch(tl);
            e.unload(loaded.get(0));
            ui.toast("Unloading " + loaded.get(0) + "…");
            return;
        }
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        for (final String name : loaded) {
            ModelInfo ri = e.runningInfo(name);
            String d = ri != null && ri.sizeVram > 0 ? Fmt.bytes(ri.sizeVram) + " in VRAM" : "loaded";
            rows.add(new Ui.Row(name, d, name.equals(e.currentModel()), new Runnable() {
                @Override
                public void run() {
                    watch(tl);
                    e.unload(name);
                    ui.toast("Unloading " + name + "…");
                }
            }, null));
        }
        ui.pick("Free memory on the PC", rows, null, null);
    }

    /** Marks a tile busy until the next chat notice it produces reaches a final tone. */
    private void watch(Tile tl) {
        if (watchTile != null && watchTile != tl) watchTile.setPending(false);
        watchTile = tl;
        watchId = null;
        watchFrom = e.conversation().messages.size();
        tl.setPending(true);
        // Some refusals ("already working on it") only toast: don't stay busy for them.
        scroll.removeCallbacks(watchCheck);
        scroll.postDelayed(watchCheck, 2500);
    }

    private final Runnable watchCheck = new Runnable() {
        @Override
        public void run() {
            if (watchTile == null || watchId != null) return;
            if (e.isScanning()) {
                scroll.postDelayed(this, 1000); // a full scan posts its notice when it ends
                return;
            }
            watchTile.setPending(false);
            watchTile = null;
        }
    };

    private void resolveWatch(ChatMessage m) {
        Tile tl = watchTile;
        watchTile = null;
        watchId = null;
        if (tl != null) tl.setPending(false);
        String text = m.content.replace("**", "").replace("`", "");
        String[] lines = text.split("\n");
        String first = lines[0].trim();
        if (first.startsWith("Benchmark")) {
            String gen = "";
            for (String l : lines) {
                if (l.startsWith("Generate:")) gen = l.substring(9).trim();
            }
            String model = first.replace("Benchmark —", "").replace("Benchmark", "").trim();
            if (gen.length() > 0) {
                e.log("ok", "Benchmark · " + model + " · " + gen);
                ui.toast("Benchmark · " + gen);
                return;
            }
        }
        ui.toast(Fmt.ellipsize(first, 110));
    }

    // --- Telemetry -----------------------------------------------------

    private View buildTelemetry() {
        LinearLayout box = ui.vbox();
        latency = metric("Latency", "ms");
        throughput = metric("Throughput", "tok/s");
        box.addView(pair(latency.card, throughput.card), Ui.fillW());
        firstToken = metric("First token", "s");
        box.addView(pair(firstToken.card, buildContextTile()), gap(Ui.fillW(), 12));
        return box;
    }

    private View pair(View left, View right) {
        LinearLayout row = ui.hbox();
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.leftMargin = ui.dp(12);
        row.addView(right, rp);
        return row;
    }

    private Metric metric(String title, String unit) {
        Metric m = new Metric();
        m.card = ui.capCard(title, null);
        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12));
        LinearLayout valueRow = ui.hbox();
        valueRow.setGravity(Gravity.BOTTOM);
        m.value = ui.readout("—", 24, t.inkStrong);
        valueRow.addView(m.value, Ui.wrap());
        m.unit = ui.readout(unit, 12, t.dim);
        m.unit.setPadding(ui.dp(5), 0, 0, ui.dp(3));
        valueRow.addView(m.unit, Ui.wrap());
        body.addView(valueRow, Ui.fillW());
        m.spark = new Widgets.Sparkline(a, t.data, t.hair);
        m.spark.setFloor(0);
        body.addView(m.spark, gap(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(34)), 10));
        m.caption = ui.text("", 11, t.faint, t.body);
        m.caption.setSingleLine(true);
        m.caption.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(m.caption, gap(Ui.fillW(), 7));
        m.card.addView(body, Ui.fillW());
        return m;
    }

    private View buildContextTile() {
        ctxSide = ui.readout("", 10.5f, t.faint);
        LinearLayout card = ui.capCard("Context", ctxSide);
        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12));
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout g = new FrameLayout(a);
        ctxGauge = new Widgets.Gauge(a, t.hud ? Theme.alpha(t.accent, 0x24) : t.isDark ? t.hair : t.edge, t.data);
        ctxGauge.setFraction(-1, false);
        g.addView(ctxGauge, new FrameLayout.LayoutParams(ui.dp(82), ui.dp(82), Gravity.CENTER));
        ctxPct = ui.readout("—", 17, t.inkStrong);
        ctxPct.setGravity(Gravity.CENTER);
        g.addView(ctxPct, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        body.addView(g, new LinearLayout.LayoutParams(ui.dp(82), ui.dp(76)));
        ctxCaption = ui.text("", 11, t.faint, t.body);
        ctxCaption.setGravity(Gravity.CENTER);
        ctxCaption.setSingleLine(true);
        ctxCaption.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(ctxCaption, gap(Ui.fillW(), 4));
        card.addView(body, Ui.fillW());
        return card;
    }

    private View buildLoaded() {
        loadedSide = ui.readout("", 10.5f, t.faint);
        LinearLayout card = ui.capCard("Loaded models", loadedSide);
        loadedBody = ui.cardBody();
        card.addView(loadedBody, Ui.fillW());
        return card;
    }

    private View buildSession() {
        sesSide = ui.readout("", 10.5f, t.faint);
        LinearLayout card = ui.capCard("Session", sesSide);
        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12));
        LinearLayout row = ui.hbox();
        row.setBaselineAligned(false);
        sesUptime = stat(row, "Uptime", false);
        sesReplies = stat(row, "Replies", true);
        sesTokens = stat(row, "Tokens out", true);
        sesErrors = stat(row, "Errors", true);
        body.addView(row, Ui.fillW());
        card.addView(body, Ui.fillW());
        return card;
    }

    private TextView stat(LinearLayout row, String name, boolean ruleBefore) {
        if (ruleBefore) {
            View rule = new View(a);
            rule.setBackgroundColor(t.hair);
            row.addView(rule, new LinearLayout.LayoutParams(Math.max(1, ui.dp(0.7f)), ui.dp(30)));
        }
        LinearLayout cell = ui.vbox();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView v = ui.readout("—", 17, t.inkStrong);
        cell.addView(v, Ui.wrap());
        TextView l = ui.label(name);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.hud ? 7.5f : 9.5f);
        l.setPadding(0, ui.dp(6), 0, 0);
        cell.addView(l, Ui.wrap());
        row.addView(cell, Ui.weight(1));
        return v;
    }

    // --- PC vitals ------------------------------------------------------

    private View buildPc() {
        LinearLayout side = ui.hbox();
        pcDot = new Widgets.StatusDot(a);
        side.addView(pcDot, new LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)));
        pcSide = ui.readout("", 10.5f, t.faint);
        pcSide.setPadding(ui.dp(4), 0, ui.dp(2), 0);
        side.addView(pcSide);
        ImageView chev = new ImageView(a);
        chev.setImageDrawable(new IconDrawable(IconDrawable.CHEVRON, t.dim, t.dim, ui.dp(16)));
        chev.setRotation(-90); // the kit's chevron points down; this one means "open"
        side.addView(chev, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        LinearLayout card = ui.capCard("PC vitals", side);
        card.setContentDescription("PC vitals");
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                a.select(MainActivity.TAB_PC, true);
            }
        });
        pcBody = ui.cardBody();
        card.addView(pcBody, Ui.fillW());
        return card;
    }

    private void buildPcBody(boolean paired) {
        pcBody.removeAllViews();
        pcShownPaired = paired;
        if (!paired) {
            cpuRow = ramRow = diskRow = batRow = null;
            pcFoot = null;
            LinearLayout row = ui.hbox();
            FrameLayout badge = new FrameLayout(a);
            badge.setBackground(ui.rounded(t.hud ? t.accentSoft : t.chip, t.hud ? t.edge : 0, 10));
            ImageView ic = new ImageView(a);
            ic.setImageDrawable(new IconDrawable(IconDrawable.NAV_PC, t.hud ? t.accent : t.isDark ? t.ink : t.accent,
                    0, ui.dp(22)));
            ic.setScaleType(ImageView.ScaleType.CENTER);
            badge.addView(ic, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(badge, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)));
            LinearLayout text = ui.vbox();
            text.setPadding(ui.dp(12), 0, ui.dp(6), 0);
            text.addView(ui.text("Connect your PC", 15, t.ink, t.bodySemi));
            TextView sub = ui.dim("LaunchBridge · pair to see CPU, RAM and disk here and control the PC.", 12.5f);
            sub.setPadding(0, ui.dp(4), 0, 0);
            text.addView(sub);
            row.addView(text, Ui.weight(1));
            pcBody.addView(row, Ui.fillW());
            return;
        }
        cpuRow = vitalRow("CPU");
        ramRow = vitalRow("RAM");
        diskRow = vitalRow("Disk");
        batRow = vitalRow("Battery");
        pcBody.addView(cpuRow.row, Ui.fillW());
        pcBody.addView(ramRow.row, gap(Ui.fillW(), 10));
        pcBody.addView(diskRow.row, gap(Ui.fillW(), 10));
        pcBody.addView(batRow.row, gap(Ui.fillW(), 10));
        batRow.row.setVisibility(View.GONE);
        pcFoot = ui.text("", 11, t.faint, t.body);
        pcFoot.setSingleLine(true);
        pcFoot.setEllipsize(TextUtils.TruncateAt.END);
        pcBody.addView(pcFoot, gap(Ui.fillW(), 12));
    }

    private VitalRow vitalRow(String name) {
        VitalRow r = new VitalRow();
        r.row = ui.hbox();
        TextView l = ui.label(name);
        r.row.addView(l, new LinearLayout.LayoutParams(ui.dp(t.hud ? 70 : 64), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.meter = new Widgets.Meter(a, t.hud ? Theme.alpha(t.accent, 0x1F) : t.isDark ? t.hair : t.chip, t.data);
        r.row.addView(r.meter, new LinearLayout.LayoutParams(0, ui.dp(5), 1));
        r.value = ui.readout("—", 12.5f, t.ink);
        r.value.setGravity(Gravity.END);
        r.row.addView(r.value, new LinearLayout.LayoutParams(ui.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    // --- System log -----------------------------------------------------

    private View buildLog() {
        TextView copy = ui.text("Copy", t.hud ? 9.5f : 12, t.hud ? t.accent : t.isDark ? t.ink : t.accent,
                t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) {
            copy.setText("COPY");
            copy.setLetterSpacing(0.14f);
        }
        IconDrawable cd = new IconDrawable(IconDrawable.COPY, copy.getCurrentTextColor(), 0, ui.dp(14));
        cd.setBounds(0, 0, ui.dp(14), ui.dp(14));
        copy.setCompoundDrawables(cd, null, null, null);
        copy.setCompoundDrawablePadding(ui.dp(5));
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(ui.dp(8), ui.dp(6), ui.dp(6), ui.dp(6));
        copy.setContentDescription("Copy system log");
        TypedValue tv = new TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) copy.setBackground(a.getDrawable(tv.resourceId));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                copyLog();
            }
        });
        LinearLayout card = ui.capCard("System log", copy);
        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(10));
        logList = ui.vbox();
        body.addView(logList, Ui.fillW());
        logEmpty = ui.dim("No events yet.", 13);
        logEmpty.setPadding(0, ui.dp(8), 0, ui.dp(4));
        body.addView(logEmpty, Ui.fillW());
        logMore = ui.text("", 12.5f, t.hud ? t.accent : t.isDark ? t.ink : t.accent, t.hud ? t.labelFace : t.bodyMedium);
        if (t.hud) logMore.setLetterSpacing(0.1f);
        logMore.setPadding(0, ui.dp(10), 0, ui.dp(2));
        logMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logExpanded = !logExpanded;
                renderLog();
            }
        });
        body.addView(logMore, Ui.wrap());
        card.addView(body, Ui.fillW());
        return card;
    }

    private View logRow(Telemetry.Event ev) {
        LinearLayout row = ui.hbox();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, ui.dp(5), 0, ui.dp(5));
        TextView time = ui.text(clock.format(new Date(ev.time)), 11, t.faint, t.mono);
        time.setPadding(0, ui.dp(1), 0, 0);
        row.addView(time, Ui.wrap());
        View dot = new View(a);
        dot.setBackground(ui.rounded(levelColor(ev.level), 0, 3));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ui.dp(6), ui.dp(6));
        dlp.setMargins(ui.dp(9), ui.dp(5), ui.dp(9), 0);
        row.addView(dot, dlp);
        TextView text = ui.text(ev.text, 12.5f, "error".equals(ev.level) ? t.danger : t.ink, t.body);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setLineSpacing(0, 1.15f);
        row.addView(text, Ui.weight(1));
        return row;
    }

    private int levelColor(String level) {
        if ("ok".equals(level)) return t.ok;
        if ("warn".equals(level)) return t.warn;
        if ("error".equals(level)) return t.danger;
        return t.hud ? t.accent : t.data;
    }

    private void renderLog() {
        if (logList == null) return;
        logList.removeAllViews();
        List<Telemetry.Event> evs = e.telemetry.events();
        int total = Math.min(evs.size(), LOG_MAX);
        int show = logExpanded ? total : Math.min(total, LOG_COLLAPSED);
        for (int i = 0; i < show; i++) {
            Telemetry.Event ev = evs.get(evs.size() - 1 - i);
            if (i > 0) logList.addView(hairline());
            logList.addView(logRow(ev), Ui.fillW());
        }
        logEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        updateLogMore(total);
    }

    private void updateLogMore(int total) {
        if (total > LOG_COLLAPSED) {
            logMore.setVisibility(View.VISIBLE);
            String s = logExpanded ? "Show less" : "Show " + (total - LOG_COLLAPSED) + " more";
            logMore.setText(t.hud ? s.toUpperCase(Locale.US) : s);
        } else {
            logMore.setVisibility(View.GONE);
        }
    }

    private View hairline() {
        View v = new View(a);
        v.setBackgroundColor(t.hairSoft);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(0.6f))));
        return v;
    }

    private void copyLog() {
        List<Telemetry.Event> evs = e.telemetry.events();
        if (evs.isEmpty()) {
            ui.toast("The log is empty.");
            return;
        }
        StringBuilder sb = new StringBuilder("OMNI-DECK system log\n");
        for (int i = evs.size() - 1; i >= 0; i--) {
            Telemetry.Event ev = evs.get(i);
            sb.append(clock.format(new Date(ev.time))).append("  ").append(ev.level.toUpperCase(Locale.US))
                    .append("  ").append(ev.text).append('\n');
        }
        a.copy("System log", sb.toString().trim());
    }

    // ------------------------------------------------------------------
    // Refresh
    // ------------------------------------------------------------------

    private void refreshAll() {
        refreshHero();
        refreshOffline();
        refreshTiles();
        refreshTelemetry();
        refreshLoaded();
        refreshSession();
        refreshPc();
        renderLog();
    }

    /** The core's mode from the Engine's state. */
    private int coreMode() {
        Engine.State s = e.state();
        if (s == Engine.State.OFFLINE) return CoreView.OFFLINE;
        if (s == Engine.State.SEARCHING) return CoreView.SCANNING;
        if (e.isBusy()) {
            ChatMessage m = e.streamingMessage();
            return m != null && m.content.length() > 0 ? CoreView.STREAMING : CoreView.THINKING;
        }
        if (e.speaking()) return CoreView.SPEAKING;
        return CoreView.IDLE;
    }

    private void refreshHero() {
        if (core == null) return;
        int mode = coreMode();
        core.setMode(mode);
        String state;
        int color;
        switch (mode) {
            case CoreView.OFFLINE:
                state = "Offline";
                color = t.danger;
                break;
            case CoreView.SCANNING:
                state = "Scanning";
                color = t.warn;
                break;
            case CoreView.THINKING:
                state = "Thinking";
                color = t.engaged;
                break;
            case CoreView.STREAMING:
                state = "Generating";
                color = t.hud ? t.accent : t.data;
                break;
            case CoreView.SPEAKING:
                state = "Speaking";
                color = t.hud ? t.accent : t.data;
                break;
            default:
                state = "Idle";
                color = t.ok;
                break;
        }
        coreState.setText(t.label(state));
        coreState.setTextColor(color);
        coreDot.setColor(color);
        coreDot.setPulsing(mode != CoreView.OFFLINE && mode != CoreView.IDLE && isShown()
                && !e.settings.reduceMotion());

        Engine.State s = e.state();
        ServerInfo srv = e.server();
        if (s == Engine.State.ONLINE && srv != null) {
            headline.setText(t.hud ? "LINK ESTABLISHED" : "Connected to your AI");
            headline.setTextColor(t.ok);
            String v = srv.version.length() > 0 ? "Ollama " + srv.version + " · " : "Ollama · ";
            detail.setText(t.hud ? (v + srv.label()).toUpperCase(Locale.US) : v + srv.label());
        } else if (s == Engine.State.SEARCHING) {
            headline.setText(t.hud ? "SCANNING NETWORK" : "Looking for your AI");
            headline.setTextColor(t.warn);
            // "Lost … reconnecting" is worth repeating; the generic "scanning…" line isn't.
            String sd = e.stateDetail();
            detail.setText(sd.startsWith("Lost") ? sd : "Port " + scanPort() + " · " + networks());
        } else {
            headline.setText(t.hud ? "NO LINK · AI OFFLINE" : "AI offline");
            headline.setTextColor(t.danger);
            detail.setText(e.stateDetail());
        }

        String model = e.currentModel();
        boolean hasModel = model.length() > 0;
        boolean loaded = hasModel && e.isLoaded(model);
        modelChipText.setText(hasModel ? model : "No model");
        int mc = s == Engine.State.ONLINE ? (t.hud ? t.accent : t.isDark ? t.ink : t.accent) : t.dim;
        int dotColor = loaded ? t.ok : t.dim;
        String modelKey = mc + "/" + dotColor;
        if (!modelKey.equals(modelChipKey)) {
            modelChipKey = modelKey;
            styleChip(modelChip, mc);
            modelChipDot.setImageDrawable(new IconDrawable(IconDrawable.BRAIN, dotColor, dotColor, ui.dp(16)));
        }
        String mode2 = e.mode();
        if (!mode2.equals(modeChipKey)) {
            modeChipKey = mode2;
            String ml = Settings.MODE_DEEP.equals(mode2) ? "Deep" : Settings.MODE_FAST.equals(mode2) ? "Fast" : "Auto";
            modeChipText.setText(t.hud ? ml.toUpperCase(Locale.US) : ml);
            int modeIcon = Settings.MODE_DEEP.equals(mode2) ? IconDrawable.BRAIN : Settings.MODE_FAST.equals(mode2)
                    ? IconDrawable.BOLT : IconDrawable.ACTIVITY;
            int modeColor = Settings.MODE_DEEP.equals(mode2) ? t.engaged
                    : t.hud ? t.accent : t.isDark ? t.ink : t.accent;
            modeChipIcon.setImageDrawable(new IconDrawable(modeIcon, modeColor, modeColor, ui.dp(16)));
            styleChip(modeChip, modeColor);
        }

        refreshLive();
        refreshReadouts();
    }

    /** The one-line "what's happening now" under the chips. */
    private void refreshLive() {
        if (liveLine == null) return;
        Engine.State s = e.state();
        String line;
        int color = t.faint;
        ChatMessage m = e.streamingMessage();
        long now = System.currentTimeMillis();
        if (s == Engine.State.OFFLINE) {
            line = "Commands, history and settings still work offline";
        } else if (s == Engine.State.SEARCHING) {
            line = "Probing for Ollama · usually a few seconds";
        } else if (m != null) {
            long secs = Math.max(0, (now - m.startedAt) / 1000);
            color = t.dim;
            if (m.content.length() == 0) {
                line = m.thinking.length() > 0 ? "Thinking · " + secs + "s"
                        : secs >= 2 ? "Loading model · " + secs + "s" : "Waiting for first token";
            } else {
                double tok = m.content.length() / 4.0;
                double el = liveFirstAt > 0 ? (now - liveFirstAt) / 1000.0 : 0;
                String rate = el > 0.4 ? "~" + Fmt.oneDecimal(tok / el) + " tok/s · " : "";
                line = "Generating · " + rate + Math.round(tok) + " tok";
            }
        } else if (e.speaking()) {
            line = "Speaking the reply aloud";
            color = t.dim;
        } else if (e.pulling() && e.pullState() != null) {
            Engine.PullState p = e.pullState();
            line = "Downloading " + p.name + (p.total > 0 ? " · " + p.percent() + "%" : " · " + p.status);
            color = t.dim;
        } else if (e.lastSpeed().length() > 0) {
            line = "Ready · last reply " + e.lastSpeed();
        } else {
            line = "Ready · tap the core to chat, hold it to talk";
        }
        liveLine.setText(t.hud ? line.toUpperCase(Locale.US) : line);
        liveLine.setTextColor(color);
    }

    /** The subnets being swept, e.g. "192.168.1.0/24". */
    private String networks() {
        List<LanScanner.Subnet> nets = e.subnets();
        if (nets.isEmpty()) return "this network";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nets.size() && i < 2; i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(nets.get(i).addressString()).append('/').append(nets.get(i).prefix);
        }
        return sb.toString();
    }

    private int scanPort() {
        HostPort hp = HostPort.parse(e.settings.server(), OllamaClient.DEFAULT_PORT);
        return hp != null ? hp.port : e.settings.lastPort() > 0 ? e.settings.lastPort() : OllamaClient.DEFAULT_PORT;
    }

    private void refreshReadouts() {
        if (rdLink == null) return;
        double lat = e.telemetry.latencyMs.last();
        boolean on = e.state() == Engine.State.ONLINE;
        rdLink.setText(on && !Double.isNaN(lat) ? Math.round(lat) + " ms" : on ? "—" : "down");
        rdLink.setTextColor(on ? t.ink : t.danger);
        int loaded = 0;
        for (ModelInfo m : e.models()) {
            if (e.isLoaded(m.name)) loaded++;
        }
        rdLoaded.setText(e.models().isEmpty() ? "—" : loaded + " / " + e.models().size());
        String speed;
        ChatMessage m = e.streamingMessage();
        double tps = e.telemetry.tokensPerSec.last();
        if (m != null && m.content.length() > 0 && liveFirstAt > 0) {
            double el = (System.currentTimeMillis() - liveFirstAt) / 1000.0;
            speed = el > 0.4 ? Fmt.oneDecimal(m.content.length() / 4.0 / el) + " t/s" : "—";
        } else {
            speed = Double.isNaN(tps) ? "—" : Fmt.oneDecimal(tps) + " t/s";
        }
        rdSpeed.setText(speed);
        long up = e.telemetry.uptimeMs(System.currentTimeMillis()) / 1000;
        rdUptime.setText(String.format(Locale.US, "%02d:%02d:%02d", up / 3600, (up / 60) % 60, up % 60));
    }

    private void refreshOffline() {
        if (offlineCard == null) return;
        Engine.State s = e.state();
        if (s == Engine.State.OFFLINE) everOffline = true;
        if (s == Engine.State.ONLINE) everOffline = false;
        boolean show = s == Engine.State.OFFLINE || (s == Engine.State.SEARCHING && everOffline);
        offlineCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            offlineMeter.setIndeterminate(false);
            return;
        }
        boolean searching = s == Engine.State.SEARCHING;
        if (offlineStyledSearching != searching) {
            offlineStyledSearching = searching;
            styleOfflineCard(searching);
        }
        offlineTitle.setText(t.hud ? (searching ? "SCANNING FOR YOUR AI" : "AI NOT FOUND ON THIS NETWORK")
                : searching ? "Scanning for your AI…" : "AI not found on this network");
        offlineDetail.setText(searching ? "Sweeping this network for an Ollama server. This takes up to 15 seconds."
                : e.stateDetail() + " Check these three things, then scan again:");
        offlineMeter.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (searching && isShown() && !e.settings.reduceMotion()) offlineMeter.setIndeterminate(true);
        else if (searching) offlineMeter.setFraction(0.5f);
        else offlineMeter.setIndeterminate(false);
        scanAgainBtn.setAlpha(searching ? 0.5f : 1f);
        scanAgainBtn.setText(t.hud ? (searching ? "SCANNING…" : "SCAN AGAIN") : searching ? "Scanning…" : "Scan again");
    }

    private void refreshTiles() {
        if (readAloudTile == null) return;
        boolean ra = e.settings.readAloud();
        int kind = ra ? IconDrawable.SPEAKER : IconDrawable.SPEAKER_OFF;
        if (readAloudTile.iconKind != kind || readAloudTile.engaged != ra) {
            readAloudTile.iconKind = kind;
            readAloudTile.engaged = ra;
            readAloudTile.render();
        }
        boolean online = e.state() == Engine.State.ONLINE;
        for (Tile tl : tiles) {
            if (tl.needsAi) tl.setEnabled(online);
            if (tl.pending && System.currentTimeMillis() - tl.pendingSince > PENDING_TIMEOUT_MS) {
                tl.setPending(false);
                if (tl == watchTile) watchTile = null;
            } else if (tl.pending) {
                tl.animateBusy(isShown());
            }
        }
    }

    private void refreshTelemetry() {
        if (latency == null) return;
        Telemetry tm = e.telemetry;
        // Latency
        double lat = tm.latencyMs.last();
        setMetric(latency, Double.isNaN(lat) ? null : String.valueOf(Math.round(lat)), tm.latencyMs.toArray());
        latency.caption.setText(Double.isNaN(lat) ? "Waiting for the first ping"
                : "avg " + Math.round(tm.latencyMs.average()) + " · peak " + Math.round(tm.latencyMs.max()) + " ms");
        // Throughput
        double tps = tm.tokensPerSec.last();
        setMetric(throughput, Double.isNaN(tps) ? null : Fmt.oneDecimal(tps), tm.tokensPerSec.toArray());
        throughput.caption.setText(Double.isNaN(tps) ? "Send a message to measure"
                : "avg " + Fmt.oneDecimal(tm.tokensPerSec.average()) + " · best "
                + Fmt.oneDecimal(tm.tokensPerSec.max()) + " tok/s");
        // First token
        double tt = tm.ttftMs.last();
        setMetric(firstToken, Double.isNaN(tt) ? null : String.format(Locale.US, "%.2f", tt / 1000.0),
                tm.ttftMs.toArray());
        firstToken.caption.setText(Double.isNaN(tt) ? "Time until the reply starts"
                : "avg " + String.format(Locale.US, "%.2fs", tm.ttftMs.average() / 1000.0)
                + (tm.lastLoadMs > 500 ? " · load " + Fmt.seconds(tm.lastLoadMs) : " · model warm"));
        // Context fill
        double cf = tm.contextFill.last();
        int ctx = contextSize();
        ctxSide.setText(ctx > 0 ? ctxLabel(ctx) : "");
        if (Double.isNaN(cf)) {
            ctxGauge.setFraction(-1, false);
            ctxPct.setText("—");
            ctxCaption.setText("Filled by the last reply");
        } else {
            ctxGauge.setFraction((float) cf, !e.settings.reduceMotion());
            ctxPct.setText(cf > 0 && cf < 0.01 ? "<1%" : Math.round(cf * 100) + "%");
            ctxCaption.setText(ctx > 0 ? "~" + compact(Math.round(cf * ctx)) + " of " + ctxLabel(ctx) + " tokens"
                    : "of the context window");
        }
        ctxGauge.setValueColor(cf > 0.85 ? t.danger : cf > 0.65 ? t.warn : t.data);
    }

    /** Shows a readout (null = no data yet: a faint dash, no unit) and its trace. */
    private void setMetric(Metric m, String value, double[] series) {
        m.value.setText(value == null ? "—" : value);
        m.value.setTextColor(value == null ? t.faint : t.inkStrong);
        m.unit.setVisibility(value == null ? View.INVISIBLE : View.VISIBLE);
        // A single sample reads better as a level line than as a lone dot.
        m.spark.setData(series.length == 1 ? new double[]{series[0], series[0]} : series);
    }

    /** The context window replies use: the setting, else the loaded model's, else Ollama's default. */
    private int contextSize() {
        if (e.settings.numCtx() > 0) return e.settings.numCtx();
        ModelInfo ri = e.runningInfo(e.currentModel());
        if (ri != null && ri.contextLength > 0) return ri.contextLength;
        return e.telemetry.contextFill.size() > 0 ? 8192 : 0;
    }

    /** Context sizes the way models advertise them: 8192 → "8K", 131072 → "128K". */
    private static String ctxLabel(int n) {
        return n >= 1024 && n % 1024 == 0 ? (n / 1024) + "K" : compact(n);
    }

    private static String plural(int n, String word) {
        return n + " " + (n == 1 ? word : word.endsWith("y") ? word.substring(0, word.length() - 1) + "ies" : word + "s");
    }

    private static String compact(long n) {
        if (n >= 1000000) return Fmt.oneDecimal(n / 1000000.0) + "M";
        if (n >= 10000) return Math.round(n / 1000.0) + "k";
        if (n >= 1000) return Fmt.oneDecimal(n / 1000.0) + "k";
        return String.valueOf(n);
    }

    private void refreshLoaded() {
        if (loadedBody == null) return;
        Engine.State s = e.state();
        List<ModelInfo> loaded = new ArrayList<ModelInfo>();
        StringBuilder sig = new StringBuilder(s.name()).append('|').append(t.id);
        for (ModelInfo m : e.models()) {
            if (!e.isLoaded(m.name)) continue;
            ModelInfo ri = e.runningInfo(m.name);
            ModelInfo use = ri != null ? ri : m;
            loaded.add(use);
            sig.append(use.name).append(':').append(use.sizeVram).append(':').append(use.contextLength).append(':')
                    .append(use.expiresAt).append(';');
        }
        sig.append('#').append(e.models().size());
        loadedSide.setText(e.models().isEmpty() ? "" : loaded.size() + " / " + e.models().size());
        if (sig.toString().equals(loadedSig)) return;
        loadedSig = sig.toString();
        loadedBody.removeAllViews();
        if (s != Engine.State.ONLINE) {
            loadedBody.addView(ui.dim("Not connected. Loaded models appear here once your AI is online.", 13),
                    Ui.fillW());
            return;
        }
        if (e.models().isEmpty()) {
            loadedBody.addView(ui.dim("No models installed on the PC yet. Get one from the Models tab.", 13),
                    Ui.fillW());
            return;
        }
        if (loaded.isEmpty()) {
            LinearLayout row = ui.hbox();
            TextView msg = ui.dim("Nothing in memory. Warm a model so replies start instantly.", 13);
            row.addView(msg, Ui.weight(1));
            TextView warm = ui.button("Warm up", IconDrawable.BOLT, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (Tile tl : tiles) {
                        if ("Warm model".contentEquals(tl.root.getContentDescription())) {
                            tl.root.performClick();
                            return;
                        }
                    }
                }
            });
            warm.setContentDescription("Warm up model");
            LinearLayout.LayoutParams wlp = Ui.wrap();
            wlp.leftMargin = ui.dp(12);
            row.addView(warm, wlp);
            loadedBody.addView(row, Ui.fillW());
            return;
        }
        for (int i = 0; i < loaded.size(); i++) {
            ModelInfo m = loaded.get(i);
            if (i > 0) loadedBody.addView(hairline(), gap(Ui.fillW(), 12));
            LinearLayout top = ui.hbox();
            TextView name = ui.text(m.name, 13.5f, t.ink, t.mono);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(name, Ui.weight(1));
            if (m.name.equals(e.currentModel())) {
                TextView active = ui.chip("Active", t.hud ? t.accent : t.data);
                LinearLayout.LayoutParams alp = Ui.wrap();
                alp.leftMargin = ui.dp(8);
                top.addView(active, alp);
            }
            TextView size = ui.readout(m.sizeVram > 0 ? Fmt.bytes(m.sizeVram) : m.size > 0 ? Fmt.bytes(m.size) : "",
                    12.5f, t.dim);
            size.setPadding(ui.dp(10), 0, 0, 0);
            top.addView(size);
            loadedBody.addView(top, gap(Ui.fillW(), i == 0 ? 2 : 12));
            Widgets.Meter meter = new Widgets.Meter(a, t.hud ? Theme.alpha(t.accent, 0x1F) : t.isDark ? t.hair : t.chip,
                    t.data);
            float gpu = m.size > 0 && m.sizeVram > 0 ? Math.min(1f, (float) m.sizeVram / m.size) : 0f;
            meter.setFraction(gpu);
            loadedBody.addView(meter, gap(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ui.dp(5)), 9));
            StringBuilder sub = new StringBuilder();
            if (m.size > 0) {
                int pct = Math.round(gpu * 100);
                sub.append(pct >= 100 ? "100% GPU" : pct <= 0 ? "CPU only" : pct + "% GPU / " + (100 - pct) + "% CPU");
            }
            if (m.contextLength > 0) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append("ctx ").append(String.format(Locale.US, "%,d", m.contextLength));
            }
            if (m.expiresAt.startsWith("2") && m.expiresAt.compareTo("2200") > 0) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append("stays loaded");
            }
            TextView st = ui.text(sub.toString(), 11.5f, t.faint, t.body);
            loadedBody.addView(st, gap(Ui.fillW(), 7));
        }
    }

    private void refreshSession() {
        if (sesUptime == null) return;
        Telemetry tm = e.telemetry;
        long now = System.currentTimeMillis();
        sesUptime.setText(Fmt.duration(tm.uptimeMs(now) / 1000));
        sesReplies.setText(String.valueOf(tm.replies));
        sesTokens.setText(compact(tm.tokensOut));
        sesErrors.setText(String.valueOf(tm.errors));
        sesErrors.setTextColor(tm.errors > 0 ? t.danger : t.inkStrong);
        sesSide.setText("since " + shortClock.format(new Date(tm.sessionStart)));
    }

    private void refreshPc() {
        if (pcBody == null) return;
        boolean paired = e.bridgePaired();
        if (pcShownPaired == null || pcShownPaired != paired) buildPcBody(paired);
        if (!paired) {
            pcDot.setVisibility(View.GONE);
            pcSide.setText(t.hud ? "NOT PAIRED" : "Not paired");
            return;
        }
        pcDot.setVisibility(View.VISIBLE);
        Vitals v = e.lastVitals();
        boolean live = vitalsError == null && v != null;
        pcDot.setColor(vitalsError != null ? t.danger : v != null ? t.ok : t.warn);
        pcDot.setPulsing(false);
        pcSide.setText(vitalsError != null ? (t.hud ? "UNREACHABLE" : "Unreachable")
                : v == null ? (t.hud ? "READING" : "Reading…") : t.hud ? "LIVE" : "Live");
        if (v == null) {
            for (VitalRow r : new VitalRow[]{cpuRow, ramRow, diskRow}) {
                if (vitalsError == null && isShown() && !e.settings.reduceMotion()) r.meter.setIndeterminate(true);
                else r.meter.setFraction(0);
                r.value.setText("—");
            }
            pcFoot.setText(vitalsError != null ? "Can't reach LaunchBridge on the PC. Is it running?"
                    : "Reading vitals from the PC…");
            pcFoot.setTextColor(vitalsError != null ? t.danger : t.faint);
            return;
        }
        setVital(cpuRow, v.cpuPercent, v.cpuPercent >= 0 ? Math.round(v.cpuPercent) + "%" : "—");
        String ram = v.ramUsedGb >= 0 && v.ramTotalGb > 0
                ? Fmt.oneDecimal(v.ramUsedGb) + " / " + Math.round(v.ramTotalGb) + " GB"
                : v.ramPercent >= 0 ? Math.round(v.ramPercent) + "%" : "—";
        setVital(ramRow, v.ramPercent, ram);
        String disk = v.diskFreeGb >= 0 ? Math.round(v.diskFreeGb) + " GB free"
                : v.diskPercent >= 0 ? Math.round(v.diskPercent) + "% used" : "—";
        setVital(diskRow, v.diskPercent, disk);
        if (v.batteryPercent >= 0) {
            batRow.row.setVisibility(View.VISIBLE);
            setVital(batRow, v.batteryPercent, Math.round(v.batteryPercent) + "%");
            batRow.meter.setBarColor(v.batteryPercent < 20 ? t.danger : t.ok);
        } else {
            batRow.row.setVisibility(View.GONE);
        }
        StringBuilder foot = new StringBuilder();
        if (v.host.length() > 0) foot.append(v.host);
        if (v.power.length() > 0) foot.append(foot.length() > 0 ? " · " : "").append(v.power);
        long ago = Math.max(0, (System.currentTimeMillis() - e.lastVitalsAt()) / 1000);
        foot.append(foot.length() > 0 ? " · " : "").append(live ? "updated " + (ago < 2 ? "just now" : ago + "s ago")
                : "last seen " + ago + "s ago");
        pcFoot.setText(foot.toString());
        pcFoot.setTextColor(vitalsError != null ? t.danger : t.faint);
    }

    private void setVital(VitalRow r, double pct, String text) {
        if (pct >= 0) {
            r.meter.setFraction((float) (pct / 100.0));
            r.meter.setBarColor(pct >= 90 ? t.danger : pct >= 75 ? t.warn : t.data);
        } else {
            r.meter.setFraction(0);
        }
        r.value.setText(text);
    }

    // ------------------------------------------------------------------
    // Polling (only while shown)
    // ------------------------------------------------------------------

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!isShown()) return;
            refreshHero();
            refreshTiles();
            refreshSession();
            if (e.bridgePaired() && e.lastVitals() != null) refreshPc();
            scroll.postDelayed(this, e.isBusy() ? 500 : 1000);
        }
    };

    private final Runnable vitalsPoll = new Runnable() {
        @Override
        public void run() {
            if (!isShown()) return;
            fetchVitals();
            scroll.postDelayed(this, VITALS_MS);
        }
    };

    private void fetchVitals() {
        if (!e.bridgePaired() || vitalsInFlight) return;
        vitalsInFlight = true;
        e.bridgeVitals(new Engine.Callback<Vitals>() {
            @Override
            public void done(Vitals v, String error) {
                vitalsInFlight = false;
                vitalsError = v == null ? (error == null ? "failed" : error) : null;
                refreshPc();
            }
        });
    }

    private void startLive() {
        if (scroll == null) return;
        core.setReduceMotion(e.settings.reduceMotion());
        core.setRunning(true);
        scroll.removeCallbacks(ticker);
        scroll.removeCallbacks(vitalsPoll);
        scroll.post(ticker);
        scroll.post(vitalsPoll);
    }

    private void stopLive() {
        if (scroll == null) return;
        core.setRunning(false);
        scroll.removeCallbacks(ticker);
        scroll.removeCallbacks(vitalsPoll);
        // Park every other animator too; refreshAll() restores them on the next show.
        coreDot.setPulsing(false);
        pcDot.setPulsing(false);
        offlineMeter.setIndeterminate(false);
        for (Tile tl : tiles) {
            if (tl.pending) tl.animateBusy(false);
        }
        for (VitalRow r : new VitalRow[]{cpuRow, ramRow, diskRow}) {
            if (r != null) r.meter.setIndeterminate(false);
        }
    }

    @Override
    protected void onShow() {
        refreshAll();
        startLive();
    }

    @Override
    protected void onHide() {
        stopLive();
    }

    @Override
    public void onActivityStart() {
        if (!isShown()) return;
        refreshAll();
        startLive();
    }

    @Override
    public void onActivityStop() {
        stopLive();
    }

    @Override
    public void onDestroy() {
        stopLive();
    }

    // ------------------------------------------------------------------
    // Engine events
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged() {
        refreshHero();
        refreshOffline();
        refreshTiles();
        refreshLoaded();
        refreshPc();
        refreshTelemetry();
    }

    @Override
    public void onTelemetry() {
        refreshTelemetry();
        refreshSession();
        refreshReadouts();
        refreshPc();
    }

    @Override
    public void onLog(Telemetry.Event ev) {
        if (logList == null) return;
        // Newest first: insert at the top and trim, instead of re-rendering everything.
        int cap = logExpanded ? LOG_MAX : LOG_COLLAPSED;
        if (logList.getChildCount() > 0) logList.addView(hairline(), 0);
        logList.addView(logRow(ev), 0, Ui.fillW());
        while (logList.getChildCount() > cap * 2 - 1) logList.removeViewAt(logList.getChildCount() - 1);
        int total = Math.min(e.telemetry.events().size(), LOG_MAX);
        logEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        updateLogMore(total);
    }

    @Override
    public void onBusyChanged() {
        if (!e.isBusy()) {
            liveId = "";
            liveFirstAt = 0;
            liveLen = 0;
        }
        refreshHero();
    }

    @Override
    public void onMessageAdded(ChatMessage m) {
        if (watchTile != null && watchId == null && m.isNotice()
                && e.conversation().messages.size() > watchFrom) {
            watchId = m.id;
            if (!"info".equals(m.tone)) resolveWatch(m);
        }
    }

    @Override
    public void onMessageChanged(ChatMessage m) {
        if (watchId != null && watchId.equals(m.id) && !"info".equals(m.tone)) resolveWatch(m);
        if (!m.isAssistant() || !m.streaming) return;
        if (!m.id.equals(liveId)) {
            liveId = m.id;
            liveFirstAt = 0;
            liveLen = 0;
        }
        int len = m.content.length();
        if (len > liveLen) {
            if (liveFirstAt == 0) liveFirstAt = System.currentTimeMillis();
            liveLen = len;
            if (core != null) core.pulse();
        }
        if (core != null && core.mode() != coreMode()) refreshHero();
    }

    @Override
    public void onConversationReplaced() {
        if (watchTile != null && watchId == null) {
            watchTile.setPending(false);
            watchTile = null;
        }
    }

    @Override
    public void onPull() {
        refreshLive();
    }

    // ------------------------------------------------------------------
    // Model picker
    // ------------------------------------------------------------------

    private void modelPicker() {
        List<ModelInfo> ms = e.models();
        if (e.state() != Engine.State.ONLINE || ms.isEmpty()) {
            ui.toast(e.state() != Engine.State.ONLINE ? "Connect to your AI to switch models."
                    : "No models installed yet.");
            a.select(MainActivity.TAB_MODELS, true);
            return;
        }
        String cur = e.currentModel();
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        for (final ModelInfo m : ms) {
            boolean loaded = e.isLoaded(m.name);
            String d = m.describe() + (loaded ? (m.describe().length() > 0 ? " · " : "") + "loaded" : "");
            rows.add(new Ui.Row(m.name, d, m.name.equals(cur), new Runnable() {
                @Override
                public void run() {
                    e.setModel(m.name);
                    ui.toast("Model: " + m.name);
                }
            }, null));
        }
        ui.pick("Switch model", rows, "Manage models", new Runnable() {
            @Override
            public void run() {
                a.select(MainActivity.TAB_MODELS, true);
            }
        });
    }
}
