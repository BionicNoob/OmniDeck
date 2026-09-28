package com.omnideck.mobile.screens;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.MainActivity;
import com.omnideck.mobile.Settings;
import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.ReplyError;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.ModelsFlow;
import com.omnideck.mobile.ui.ModelsFormat;
import com.omnideck.mobile.ui.ModelsKit;
import com.omnideck.mobile.ui.ModelsList;
import com.omnideck.mobile.ui.ModelsOps;
import com.omnideck.mobile.ui.ModelsPullMemo;
import com.omnideck.mobile.ui.ModelsSheet;
import com.omnideck.mobile.ui.ModelsSkeleton;
import com.omnideck.mobile.ui.ModelsStorageBar;
import com.omnideck.mobile.ui.Panel;
import com.omnideck.mobile.ui.Sheet;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MODELS — the AI model bay. Every model installed on the PC as a card with
 * its spec line, capabilities and memory state; one-tap switching, loading
 * and unloading; the deep-mode model; a spec sheet per model; deletion with
 * confirmation; and a pull bay that downloads new models with live progress.
 * Offline, searching, loading and empty states each get their own guidance.
 */
public final class ModelsScreen extends Screen {
    /** Popular models offered as one-tap suggestions in the pull bay. */
    static final String[] SUGGESTIONS = {"llama3.2", "qwen3", "gemma3", "mistral", "deepseek-r1", "llava", "phi4",
            "nomic-embed-text"};
    /** The filter field appears once there are more models than this. */
    static final int FILTER_AFTER = 5;
    static final long POLL_MS = 20000;
    static final long HIGHLIGHT_MS = 5000;
    /** How long a "download complete" card stays up, from when it was first on screen, unless dismissed. */
    static final long SUCCESS_CARD_MS = 90000;

    private static final String OP_LOAD = ModelsOps.LOAD;
    private static final String OP_UNLOAD = ModelsOps.UNLOAD;
    private static final String OP_DELETE = ModelsOps.DELETE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ModelsKit kit;

    // Layout
    private ScrollView scroll;
    private LinearLayout column;
    private LinearLayout bay;
    // Offline / searching pane
    private LinearLayout statePane;
    private FrameLayout stateBadge;
    private ImageView stateIcon;
    private TextView stateTitle;
    private TextView stateDetail;
    private TextView stateHint;
    private Widgets.Meter stateMeter;
    private LinearLayout stateButtons;
    private String stateKey = "";

    // Summary
    private TextView versionText;
    private ImageView refreshBtn;
    private ObjectAnimator spin;
    private TextView statInstalled;
    private TextView statLoaded;
    private TextView statDisk;
    private ModelsStorageBar storage;
    private ModelsFlow legend;
    private String legendKey = "";
    private TextView activeValue;
    private TextView deepValue;
    private TextView modeTag;
    private EditText filter;

    // List
    private TextView listTitle;
    private ModelsList list;
    private LinearLayout skeletonBox;
    private final List<ModelsSkeleton> skeletons = new ArrayList<ModelsSkeleton>();
    private LinearLayout emptyCard;
    private TextView emptyTitle;
    private TextView emptyBody;
    private FrameLayout emptyAction;
    private String emptyKey = "";
    private final Map<String, Card> cards = new HashMap<String, Card>();

    // Transfer (pull progress)
    private LinearLayout transfer;
    private TextView tPercent;
    private Widgets.StatusDot tDot;
    private TextView tTitle;
    private TextView tStatus;
    private TextView tRaw;
    private Widgets.Meter tMeter;
    private LinearLayout tNums;
    private TextView tBytes;
    private TextView tRate;
    private LinearLayout tActions;
    private String tKey = "";

    // Pull bay
    private LinearLayout pullBay;
    private EditText pullField;
    private TextView pullBtn;
    private ModelsFlow suggestFlow;
    private String suggestKey = "";

    // State
    /** Model → operation in flight (load / unload / delete). */
    private final Map<String, String> busy = new HashMap<String, String>();
    /** Deleted models, hidden until the next list no longer has them. */
    private final Set<String> deleted = new HashSet<String>();
    private final LinkedList<String> detailQueue = new LinkedList<String>();
    private final Set<String> detailFailed = new HashSet<String>();
    private boolean fetchingDetails;
    private boolean refreshing;
    /** A model list has arrived since this server came online (distinguishes "loading" from "empty"). */
    private boolean listed;
    private Engine.State lastState;
    private String lastServer = "";
    private String pendingHighlight;
    private String highlight;
    /** The open spec sheet (kept in step with every change), or null. */
    private ModelsSheet sheet;

    public ModelsScreen(MainActivity a) {
        super(a);
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    @Override
    protected View build() {
        kit = new ModelsKit(ui);
        FrameLayout root = new FrameLayout(a);
        scroll = new ScrollView(a);
        column = ui.scrollColumn(scroll, 14, 12);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bay = ui.vbox();
        bay.addView(buildSummary(), Ui.fillW());
        transfer = buildTransfer();
        bay.addView(transfer, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        bay.addView(buildListHeader(), ui.margins(Ui.fillW(), 2, 20, 2, 10));
        list = new ModelsList(a);
        bay.addView(list, Ui.fillW());
        skeletonBox = buildSkeletons();
        bay.addView(skeletonBox, Ui.fillW());
        emptyCard = buildEmptyCard();
        bay.addView(emptyCard, Ui.fillW());
        pullBay = buildPullBay();
        bay.addView(pullBay, ui.margins(Ui.fillW(), 0, 10, 0, 0));
        column.addView(bay, Ui.fillW());

        statePane = buildStatePane();
        root.addView(statePane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        lastState = e.state();
        lastServer = serverKey();
        listed = !e.models().isEmpty();
        // A download that finished before this bay existed (another tab, or before a theme
        // change rebuilt it) still gets its "New" flag — once.
        Engine.PullState ps = e.pullState();
        if (ps != null && ps.done && ps.error == null && ModelsPullMemo.claim(ps)) pendingHighlight = ps.name;
        render();
        bindTransfer();
        return root;
    }

    private View buildSummary() {
        LinearLayout right = ui.hbox();
        versionText = ui.readout("", 10.5f, t.dim);
        versionText.setPadding(0, 0, ui.dp(2), 0);
        right.addView(versionText);
        ImageView pullShortcut = ui.iconButton(IconDrawable.DOWNLOAD, "Pull a model", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                focusPull();
            }
        });
        right.addView(pullShortcut, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(34)));
        refreshBtn = ui.iconButton(IconDrawable.REFRESH, "Refresh models", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });
        right.addView(refreshBtn, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(34)));
        LinearLayout card = ui.capCard("Model bay", right);

        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(14));
        LinearLayout stats = ui.hbox();
        statInstalled = statCell(stats, "Installed");
        stats.addView(verticalRule());
        statLoaded = statCell(stats, "Loaded");
        stats.addView(verticalRule());
        statDisk = statCell(stats, "On disk");
        body.addView(stats, Ui.fillW());

        storage = new ModelsStorageBar(a, kit.meterTrack(), ui.dp(2));
        body.addView(storage, ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(5)), 0, 16, 0, 0));
        legend = new ModelsFlow(a, 0, ui.dp(4));
        legend.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(legend, ui.margins(Ui.fillW(), 0, 8, 0, 0));

        LinearLayout slots = ui.hbox();
        slots.setGravity(Gravity.TOP);
        TextView[] out = new TextView[2];
        View active = slot("Active model", "Choose the active model", out, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickActive();
            }
        });
        activeValue = out[0];
        slots.addView(active, Ui.weight(1));
        slots.addView(ui.space(8, 1));
        View deep = slot("Deep model", "Choose the deep model", out, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDeep();
            }
        });
        deepValue = out[0];
        modeTag = out[1];
        slots.addView(deep, Ui.weight(1));
        body.addView(slots, ui.margins(Ui.fillW(), 0, 16, 0, 0));

        filter = ui.field("", "Filter installed models", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filter.setContentDescription("Filter models");
        filter.setSingleLine(true);
        filter.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        IconDrawable sd = new IconDrawable(IconDrawable.SEARCH, t.faint, 0, ui.dp(18));
        sd.setBounds(0, 0, ui.dp(18), ui.dp(18));
        filter.setCompoundDrawables(sd, null, null, null);
        filter.setCompoundDrawablePadding(ui.dp(8));
        filter.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
        filter.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                hideKeyboard(v);
                return true;
            }
        });
        filter.setVisibility(View.GONE);
        body.addView(filter, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        card.addView(body, Ui.fillW());
        return card;
    }

    private TextView statCell(LinearLayout row, String label) {
        LinearLayout cell = ui.vbox();
        TextView v = ui.readout("—", 24, t.inkStrong);
        v.setGravity(Gravity.CENTER);
        cell.addView(v, Ui.fillW());
        TextView l = ui.label(label);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, ui.dp(6), 0, 0);
        cell.addView(l, Ui.fillW());
        row.addView(cell, Ui.weight(1));
        return v;
    }

    private View verticalRule() {
        View v = new View(a);
        v.setBackgroundColor(t.hair);
        v.setLayoutParams(new LinearLayout.LayoutParams(Math.max(1, ui.dp(1)), ui.dp(34)));
        return v;
    }

    /**
     * Models that are only on disk. Opaque (the steel ink over the card), so
     * the legend's swatch and the bar's segments — drawn over different
     * backgrounds — come out the same color.
     */
    private int storedColor() {
        return Theme.flatten(Theme.alpha(t.dim, t.isDark ? 0x66 : 0x59), Theme.flatten(t.surface, t.bg));
    }

    /**
     * One legend key: a swatch and a small caps word. Smaller and dimmer than
     * the stat captions above it — it annotates the bar, it isn't a reading.
     */
    private View legendItem(int color, String text) {
        LinearLayout item = ui.hbox();
        View sw = new View(a);
        sw.setBackground(ui.rounded(color, 0, 1.5f));
        item.addView(sw, new LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)));
        TextView l = ui.text(t.label(text), t.hud ? 8.5f : 10, t.dim, t.labelFace);
        l.setLetterSpacing(t.labelTracking);
        l.setSingleLine(true);
        l.setPadding(ui.dp(6), 0, ui.dp(14), 0);
        item.addView(l);
        return item;
    }

    /** A recessed selector slot: micro-caps label, mono value, chevron. out[0] = value, out[1] = right tag. */
    private View slot(String label, String description, TextView[] out, final View.OnClickListener l) {
        LinearLayout s = ui.vbox();
        s.setPadding(ui.dp(11), ui.dp(9), ui.dp(8), ui.dp(10));
        s.setBackground(kit.pressable(ui.rounded(t.input, t.hud ? t.hair : t.edge, 8), 8));
        LinearLayout top = ui.hbox();
        TextView lab = ui.label(label);
        top.addView(lab, Ui.weight(1));
        TextView tag = ui.text("", 9.5f, t.dim, t.mono);
        // Cyber's wide caps need every dp of the label's room on a 360dp phone.
        tag.setPadding(ui.dp(t.hud ? 4 : 8), 0, ui.dp(2), 0);
        top.addView(tag);
        ImageView chev = new ImageView(a);
        chev.setImageDrawable(new IconDrawable(IconDrawable.CHEVRON, t.faint, 0, ui.dp(14)));
        top.addView(chev, new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)));
        s.addView(top, Ui.fillW());
        TextView val = ui.text("", 13.5f, t.ink, t.mono);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        val.setPadding(0, ui.dp(7), ui.dp(4), 0);
        s.addView(val, Ui.fillW());
        s.setClickable(true);
        s.setContentDescription(description);
        s.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                l.onClick(v);
            }
        });
        out[0] = val;
        out[1] = tag;
        return s;
    }

    private View buildListHeader() {
        LinearLayout h = ui.hbox();
        listTitle = ui.label("Installed");
        h.addView(listTitle);
        View rail = new View(a);
        rail.setBackground(new Rail(t.hud ? Theme.alpha(t.accent, 0x4D) : t.hair, t.hud && e.settings.hudEffects(),
                ui.density));
        h.addView(rail, ui.margins(new LinearLayout.LayoutParams(0, Math.max(2, ui.dp(2)), 1), 10, 0, 0, 0));
        return h;
    }

    private LinearLayout buildSkeletons() {
        LinearLayout box = ui.vbox();
        int bar = Theme.alpha(t.ink, t.isDark ? 0x12 : 0x0D);
        int sheen = Theme.alpha(t.hud ? t.accent : t.ink, t.isDark ? 0x14 : 0x0F);
        for (int i = 0; i < 3; i++) {
            LinearLayout c = ui.card();
            c.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(14));
            ModelsSkeleton sk = new ModelsSkeleton(a, bar, sheen, i);
            skeletons.add(sk);
            c.addView(sk, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(128)));
            c.setAlpha(1f - i * 0.25f);
            box.addView(c, ui.margins(Ui.fillW(), 0, 0, 0, 10));
        }
        box.setContentDescription("Loading models");
        box.setVisibility(View.GONE);
        return box;
    }

    private LinearLayout buildEmptyCard() {
        LinearLayout c = ui.card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(ui.dp(22), ui.dp(24), ui.dp(22), ui.dp(22));
        ImageView icon = new ImageView(a);
        icon.setImageDrawable(new IconDrawable(IconDrawable.NAV_MODELS, t.data, t.data, ui.dp(30)));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setBackground(ui.rounded(Theme.alpha(t.data, t.isDark ? 0x1A : 0x12), Theme.alpha(t.data, 0x4D), 28));
        c.addView(icon, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        emptyTitle = ui.title("", t.hud ? 13 : 17);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, ui.dp(14), 0, 0);
        c.addView(emptyTitle, Ui.fillW());
        emptyBody = ui.dim("", 13.5f);
        emptyBody.setGravity(Gravity.CENTER);
        emptyBody.setPadding(0, ui.dp(8), 0, 0);
        c.addView(emptyBody, Ui.fillW());
        emptyAction = new FrameLayout(a);
        c.addView(emptyAction, ui.margins(Ui.wrap(), 0, 16, 0, 0));
        c.setVisibility(View.GONE);
        return c;
    }

    private LinearLayout buildTransfer() {
        LinearLayout right = ui.hbox();
        tPercent = ui.readout("", 13, t.data);
        tPercent.setPadding(0, 0, ui.dp(6), 0);
        right.addView(tPercent);
        LinearLayout card = ui.capCard("Model download", right);
        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14));
        LinearLayout head = ui.hbox();
        tDot = new Widgets.StatusDot(a);
        tDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(tDot, new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)));
        tTitle = ui.text("", 15, t.inkStrong, t.bodySemi);
        tTitle.setSingleLine(true);
        tTitle.setEllipsize(TextUtils.TruncateAt.END);
        tTitle.setPadding(ui.dp(8), 0, 0, 0);
        head.addView(tTitle, Ui.weight(1));
        body.addView(head, Ui.fillW());
        tStatus = ui.text("", 12, t.dim, t.mono);
        tStatus.setMaxLines(4);
        tStatus.setEllipsize(TextUtils.TruncateAt.END);
        tStatus.setPadding(ui.dp(22), ui.dp(5), 0, 0);
        body.addView(tStatus, Ui.fillW());
        tRaw = ui.text("", 11, t.dim, t.mono);
        tRaw.setMaxLines(2);
        tRaw.setEllipsize(TextUtils.TruncateAt.END);
        tRaw.setPadding(ui.dp(22), ui.dp(6), 0, 0);
        tRaw.setVisibility(View.GONE);
        body.addView(tRaw, Ui.fillW());
        tMeter = kit.meter(t.data);
        body.addView(tMeter, ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(5)), 0, 14, 0, 0));
        tNums = ui.hbox();
        tBytes = ui.text("", 11.5f, t.ink, t.mono);
        tNums.addView(tBytes, Ui.weight(1));
        tRate = ui.text("", 11.5f, t.dim, t.mono);
        tRate.setGravity(Gravity.END);
        tNums.addView(tRate);
        body.addView(tNums, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        tActions = ui.hbox();
        body.addView(tActions, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        card.addView(body, Ui.fillW());
        card.setVisibility(View.GONE);
        return card;
    }

    private LinearLayout buildPullBay() {
        TextView lib = ui.readout("ollama.com/library", 10, t.dim);
        lib.setPadding(0, 0, ui.dp(4), 0);
        LinearLayout card = ui.capCard("Pull a model", lib);
        LinearLayout body = ui.cardBody();
        body.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(16));
        LinearLayout row = ui.hbox();
        pullField = ui.field("", "Model, e.g. qwen3:8b", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        pullField.setTypeface(t.mono);
        pullField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        pullField.setSingleLine(true);
        pullField.setContentDescription("Model to pull");
        pullField.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        pullField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                startPull(pullField.getText().toString());
                return true;
            }
        });
        row.addView(pullField, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
        TextView pull = ui.button("Pull", IconDrawable.DOWNLOAD, Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPull(pullField.getText().toString());
            }
        });
        pull.setContentDescription("Pull model");
        pullBtn = pull;
        row.addView(pull, ui.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)),
                8, 0, 0, 0));
        body.addView(row, Ui.fillW());
        TextView sl = ui.label("Suggested");
        sl.setPadding(0, ui.dp(16), 0, ui.dp(9));
        body.addView(sl, Ui.fillW());
        suggestFlow = new ModelsFlow(a, ui.dp(7), ui.dp(7));
        body.addView(suggestFlow, Ui.fillW());
        TextView hintView = ui.dim(kit.withIdents("Add a tag for a specific size, like qwen3:14b. Downloads run on "
                + "your PC, so you can leave this screen.", "qwen3:14b"), 12.5f);
        hintView.setPadding(0, ui.dp(14), 0, 0);
        body.addView(hintView, Ui.fillW());
        card.addView(body, Ui.fillW());
        return card;
    }

    private LinearLayout buildStatePane() {
        LinearLayout p = ui.vbox();
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setPadding(ui.dp(30), ui.dp(24), ui.dp(30), ui.dp(24));
        stateBadge = new FrameLayout(a);
        stateIcon = new ImageView(a);
        stateIcon.setScaleType(ImageView.ScaleType.CENTER);
        stateBadge.addView(stateIcon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        p.addView(stateBadge, new LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)));
        stateTitle = ui.title("", t.hud ? 14 : 20);
        stateTitle.setGravity(Gravity.CENTER);
        stateTitle.setPadding(0, ui.dp(18), 0, 0);
        p.addView(stateTitle, Ui.fillW());
        stateDetail = ui.text("", 12, t.dim, t.mono);
        stateDetail.setGravity(Gravity.CENTER);
        stateDetail.setLineSpacing(0, 1.2f);
        stateDetail.setPadding(0, ui.dp(10), 0, 0);
        p.addView(stateDetail, Ui.fillW());
        stateMeter = kit.meter(t.data);
        p.addView(stateMeter, ui.margins(new LinearLayout.LayoutParams(ui.dp(132), ui.dp(3)), 0, 18, 0, 0));
        stateHint = ui.dim("", 14);
        stateHint.setGravity(Gravity.CENTER);
        stateHint.setPadding(0, ui.dp(16), 0, 0);
        p.addView(stateHint, Ui.fillW());
        stateButtons = ui.vbox();
        stateButtons.setGravity(Gravity.CENTER_HORIZONTAL);
        p.addView(stateButtons, ui.margins(Ui.fillW(), 0, 22, 0, 0));
        p.setVisibility(View.GONE);
        return p;
    }

    // ------------------------------------------------------------------
    // Model card
    // ------------------------------------------------------------------

    /** Views for one installed model. */
    private final class Card {
        final String name;
        final LinearLayout root;
        final TextView title;
        final TextView meta;
        final ModelsFlow chips;
        final LinearLayout resident;
        final Widgets.StatusDot residentDot;
        final TextView residentLabel;
        final TextView residentValue;
        final Widgets.Meter residentMeter;
        final TextView residentDetail;
        final LinearLayout actions;
        String bgKey = "";
        String chipKey = "";
        String actionKey = "";

        Card(final String n) {
            name = n;
            root = ui.vbox();
            root.setPadding(ui.dp(16), ui.dp(12), ui.dp(6), ui.dp(14));
            if (t.cardElevation > 0) root.setElevation(ui.dp(t.cardElevation));
            root.setForeground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 0x1F)), null,
                    ui.rounded(0xFFFFFFFF, 0, t.radius)));
            LinearLayout.LayoutParams lp = Ui.fillW();
            lp.bottomMargin = ui.dp(10);
            root.setLayoutParams(lp);

            LinearLayout top = ui.hbox();
            top.setGravity(Gravity.TOP);
            // The model tag is an identifier: Share Tech Mono in its own case, like everywhere else.
            title = ui.text("", 17, t.inkStrong, t.mono);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, ui.dp(7), 0, 0);
            top.addView(title, Ui.weight(1));
            ImageView more = ui.iconButton(IconDrawable.MENU, "More actions for " + n, t.dim, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showActions(n);
                }
            });
            top.addView(more, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(34)));
            root.addView(top, Ui.fillW());

            meta = ui.text("", 11.5f, t.dim, t.mono);
            meta.setMaxLines(2);
            meta.setLineSpacing(0, 1.15f);
            meta.setPadding(0, ui.dp(3), ui.dp(10), 0);
            root.addView(meta, Ui.fillW());

            chips = new ModelsFlow(a, ui.dp(6), ui.dp(6));
            root.addView(chips, ui.margins(Ui.fillW(), 0, 11, 10, 0));

            resident = ui.vbox();
            resident.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
            resident.setBackground(ui.rounded(t.input, t.hairSoft, 8));
            LinearLayout rrow = ui.hbox();
            residentDot = new Widgets.StatusDot(a);
            residentDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            rrow.addView(residentDot, new LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)));
            residentLabel = ui.label("");
            residentLabel.setPadding(ui.dp(6), 0, ui.dp(8), 0);
            rrow.addView(residentLabel, Ui.weight(1));
            residentValue = ui.text("", 11.5f, t.ink, t.mono);
            residentValue.setSingleLine(true);
            rrow.addView(residentValue);
            resident.addView(rrow, Ui.fillW());
            residentMeter = kit.meter(t.ok);
            resident.addView(residentMeter, ui.margins(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(3)), 0, 8, 0, 0));
            residentDetail = ui.text("", 10.5f, t.dim, t.mono);
            residentDetail.setSingleLine(true);
            residentDetail.setEllipsize(TextUtils.TruncateAt.END);
            residentDetail.setPadding(0, ui.dp(7), 0, 0);
            resident.addView(residentDetail, Ui.fillW());
            resident.setVisibility(View.GONE);
            root.addView(resident, ui.margins(Ui.fillW(), 0, 12, 10, 0));

            actions = ui.hbox();
            root.addView(actions, ui.margins(Ui.fillW(), 0, 14, 10, 0));

            root.setContentDescription(n);
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    openSheet(n);
                }
            });
            root.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showActions(n);
                    return true;
                }
            });
        }
    }

    /** Card background: the theme panel plus a signal bar for the active (or just-installed) model. */
    private Drawable cardBackground(boolean active, boolean flash) {
        int edge = flash ? Theme.alpha(t.ok, 0xB3)
                : active ? (t.hud || t.isDark ? t.edgeStrong : Theme.alpha(t.accent, 0x80)) : t.edge;
        Panel.Builder b = Panel.builder().fill(t.surface).edge(edge, Math.max(1, ui.dp(1))).radius(ui.dp(t.radius))
                .highlight(t.panelHi);
        if (t.hud) {
            b.grid(ui.dp(22), t.gridColor).bloom(active ? Theme.alpha(t.accent, 0x24) : t.bloomColor);
            int br = flash ? Theme.alpha(t.ok, 0xCC) : active ? Theme.alpha(t.accent, 0xCC) : t.bracketColor;
            b.brackets(ui.dp(10), ui.dp(1.2f), br).bracketInset(ui.dp(5));
        }
        int bar = flash ? t.ok : active ? kit.activeColor() : 0;
        return new SignalPanel(b.build(), bar, ui.dp(2.5f), ui.dp(18));
    }

    private void bind(Card c, ModelInfo m, String cur, String deep, long now) {
        boolean active = m.name.equals(cur);
        boolean isDeep = m.name.equals(deep);
        ModelInfo run = e.runningInfo(m.name);
        boolean loaded = run != null;
        String op = busy.get(m.name);
        OllamaClient.ModelDetails d = e.details(m.name);
        boolean flash = m.name.equals(highlight);
        boolean embedding = e.isEmbeddingOnly(m.name);

        String bgKey = (active ? "a" : "-") + (flash ? "f" : "-");
        if (!bgKey.equals(c.bgKey)) {
            c.root.setBackground(cardBackground(active, flash));
            c.bgKey = bgKey;
        }
        c.title.setText(kit.name(m.name, t.inkStrong));
        c.meta.setText(kit.meta(m, d, now));

        boolean pending = d == null && !detailFailed.contains(m.name);
        String chipKey = active + "|" + isDeep + "|" + loaded + "|" + flash + "|" + embedding + "|"
                + (d == null ? (pending ? "?" : "x") : d.capabilities.toString());
        if (!chipKey.equals(c.chipKey)) {
            c.chips.removeAllViews();
            if (flash) c.chips.addView(kit.chip("New", t.ok));
            kit.addChips(c.chips, d, active, isDeep, loaded, pending, embedding);
            c.chipKey = chipKey;
        }
        c.chips.setVisibility(c.chips.getChildCount() > 0 ? View.VISIBLE : View.GONE);

        boolean motion = motion();
        if (op != null) {
            boolean del = OP_DELETE.equals(op);
            c.resident.setVisibility(View.VISIBLE);
            c.residentDot.setColor(del ? t.danger : t.engaged);
            c.residentDot.setPulsing(motion);
            c.residentLabel.setText(t.label(del ? "Deleting from PC" : OP_LOAD.equals(op) ? "Loading into memory"
                    : "Releasing memory"));
            c.residentLabel.setTextColor(del ? t.danger : t.engagedInk);
            c.residentValue.setText("");
            c.residentMeter.setBarColor(del ? t.danger : t.engaged);
            if (motion) c.residentMeter.setIndeterminate(true);
            else c.residentMeter.setFraction(0);
            c.residentDetail.setText(t.hud ? "STANDBY…" : "Working…");
            c.residentDetail.setVisibility(View.VISIBLE);
        } else if (loaded) {
            c.resident.setVisibility(View.VISIBLE);
            c.residentDot.setColor(t.ok);
            c.residentDot.setPulsing(motion);
            c.residentLabel.setText(t.label("In memory"));
            c.residentLabel.setTextColor(t.label);
            c.residentValue.setText(kit.residency(run));
            c.residentMeter.setBarColor(t.ok);
            float g = ModelsKit.gpuFraction(run);
            c.residentMeter.setFraction(g < 0 ? 1f : g);
            String detail = kit.residencyDetail(run, now);
            c.residentDetail.setText(detail);
            c.residentDetail.setVisibility(detail.length() > 0 ? View.VISIBLE : View.GONE);
        } else {
            c.residentDot.setPulsing(false);
            c.residentMeter.setIndeterminate(false);
            c.resident.setVisibility(View.GONE);
        }
        c.root.setAlpha(OP_DELETE.equals(op) ? 0.6f : 1f);

        String actionKey = active + "|" + loaded + "|" + op + "|" + embedding;
        if (!actionKey.equals(c.actionKey)) {
            buildActions(c, active, loaded, op, embedding);
            c.actionKey = actionKey;
        }
    }

    private void buildActions(Card c, boolean active, boolean loaded, String op, boolean embedding) {
        final String n = c.name;
        c.actions.removeAllViews();
        if (embedding) {
            // Embedding models turn text into vectors for search and memory; they can't chat,
            // and Ollama loads them on demand — so the card offers the spec sheet instead
            // (plus Unload if another app has one resident).
            TextView details = ui.button("Details", IconDrawable.INFO, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSheet(n);
                }
            });
            details.setContentDescription("Details for " + n);
            if (!loaded && op == null) {
                c.actions.addView(details);
                TextView note = ui.dim("Embeddings for search & memory — not a chat model.", 12);
                note.setPadding(ui.dp(12), 0, 0, 0);
                c.actions.addView(note, Ui.weight(1));
                return;
            }
            TextView unload = ui.button(op != null ? "Releasing…" : "Unload", IconDrawable.POWER, Ui.SECONDARY,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleLoad(n);
                        }
                    });
            unload.setContentDescription("Unload " + n);
            if (op != null) {
                ModelsKit.disable(details);
                ModelsKit.disable(unload);
            }
            c.actions.addView(details, Ui.weight(1));
            c.actions.addView(ui.space(8, 1));
            c.actions.addView(unload, Ui.weight(1));
            return;
        }
        TextView primary;
        if (active) {
            primary = ui.button("Open chat", IconDrawable.NAV_COMMS, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.select(MainActivity.TAB_COMMS, true);
                }
            });
            primary.setContentDescription("Open chat with " + n);
        } else {
            primary = ui.button("Use model", IconDrawable.CHECK, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    use(n);
                }
            });
            primary.setContentDescription("Use " + n);
        }
        String loadLabel = OP_LOAD.equals(op) ? "Loading…" : OP_UNLOAD.equals(op) ? "Releasing…"
                : OP_DELETE.equals(op) ? "Deleting…" : loaded ? "Unload" : "Load";
        TextView load = ui.button(loadLabel, loaded && op == null ? IconDrawable.POWER : IconDrawable.BOLT,
                Ui.SECONDARY, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleLoad(n);
                    }
                });
        load.setContentDescription((loaded ? "Unload " : "Load ") + n);
        if (op != null) {
            ModelsKit.disable(primary);
            ModelsKit.disable(load);
        }
        c.actions.addView(primary, Ui.weight(1));
        c.actions.addView(ui.space(8, 1));
        c.actions.addView(load, Ui.weight(1));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private String serverKey() {
        return e.server() == null ? "" : e.server().label();
    }

    /** Animate only while the bay is really on screen (this tab, app in the foreground) and motion is on. */
    private boolean motion() {
        return isShown() && !e.settings.reduceMotion();
    }

    private String query() {
        return filter == null ? "" : filter.getText().toString().trim().toLowerCase(Locale.US);
    }

    /** Installed models minus ones we just deleted (the list refresh catches up a moment later). */
    private List<ModelInfo> installed() {
        List<ModelInfo> out = new ArrayList<ModelInfo>();
        Set<String> present = new HashSet<String>();
        for (ModelInfo m : e.models()) {
            present.add(m.name);
            if (!deleted.contains(m.name)) out.add(m);
        }
        deleted.retainAll(present);
        return out;
    }

    /**
     * The active model as the bay shows it. Right after a delete, the
     * Engine's list still has the deleted model until its refresh lands; with
     * no saved choice left to resolve, {@link Engine#currentModel()} would pick
     * — and save — that very model, so the bay doesn't ask it then.
     */
    private String activeModel(List<ModelInfo> all) {
        if (all.isEmpty()) return "";
        if (!deleted.isEmpty()) {
            String saved = e.resolveInstalled(e.settings.model());
            if (saved == null || deleted.contains(saved)) return "";
        }
        return e.currentModel();
    }

    private boolean matches(ModelInfo m, String q) {
        if (q.length() == 0) return true;
        if (m.name.toLowerCase(Locale.US).contains(q) || m.family.toLowerCase(Locale.US).contains(q)) return true;
        OllamaClient.ModelDetails d = e.details(m.name);
        if (d != null) {
            for (String cap : d.capabilities) {
                if (cap.toLowerCase(Locale.US).startsWith(q)) return true;
            }
        }
        return false;
    }

    /** Active first, then loaded, then by name. */
    private void sort(List<ModelInfo> ms, final String cur) {
        Collections.sort(ms, new Comparator<ModelInfo>() {
            @Override
            public int compare(ModelInfo x, ModelInfo y) {
                int rx = rank(x, cur), ry = rank(y, cur);
                if (rx != ry) return rx - ry;
                return x.name.compareToIgnoreCase(y.name);
            }
        });
    }

    private int rank(ModelInfo m, String cur) {
        if (m.name.equals(cur)) return 0;
        return e.isLoaded(m.name) ? 1 : 2;
    }

    /** Brings every view in line with the Engine's state. Cheap; called on every state change. */
    private void render() {
        if (bay == null) return;
        Engine.State s = e.state();
        boolean online = s == Engine.State.ONLINE;
        if (online && (lastState != Engine.State.ONLINE || !serverKey().equals(lastServer))) {
            // Just (re)connected: the list may still be on its way.
            listed = !e.models().isEmpty();
            detailFailed.clear();
            lastServer = serverKey();
            if (isShown()) refresh(false);
        }
        lastState = s;
        if (!online) {
            bay.setVisibility(View.GONE);
            scroll.setVisibility(View.GONE);
            statePane.setVisibility(View.VISIBLE);
            bindStatePane(s);
            setSkeletonsAnimating(false);
            return;
        }
        stateMeter.setIndeterminate(false);
        statePane.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        bay.setVisibility(View.VISIBLE);

        List<ModelInfo> all = installed();
        if (!all.isEmpty()) listed = true;
        String cur = activeModel(all);
        String deep = e.resolveInstalled(e.settings.deepModel());
        if (deep != null && deleted.contains(deep)) deep = null;
        long now = System.currentTimeMillis();
        sort(all, cur);
        bindSummary(all, cur, deep);

        String q = query();
        filter.setVisibility(all.size() > FILTER_AFTER || q.length() > 0 ? View.VISIBLE : View.GONE);
        List<ModelInfo> shown = new ArrayList<ModelInfo>();
        for (ModelInfo m : all) {
            if (matches(m, q)) shown.add(m);
        }

        boolean loading = all.isEmpty() && !listed;
        skeletonBox.setVisibility(loading ? View.VISIBLE : View.GONE);
        setSkeletonsAnimating(loading && motion());

        // Cards: reuse by name, drop the gone, keep the sorted order.
        Set<String> names = new HashSet<String>();
        List<View> order = new ArrayList<View>();
        for (ModelInfo m : shown) {
            names.add(m.name);
            Card c = cards.get(m.name);
            if (c == null) {
                c = new Card(m.name);
                cards.put(m.name, c);
            }
            bind(c, m, cur, deep, now);
            order.add(c.root);
        }
        for (Iterator<Map.Entry<String, Card>> it = cards.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Card> en = it.next();
            if (!names.contains(en.getKey())) {
                en.getValue().residentDot.setPulsing(false);
                en.getValue().residentMeter.setIndeterminate(false);
                it.remove();
            }
        }
        // Re-sorts by moving cards in place: they stay attached, so their animations keep running.
        list.setOrder(order);

        listTitle.setText(t.label(loading ? "Installed · reading…" : q.length() > 0
                ? "Installed · " + shown.size() + " of " + all.size() : "Installed · " + all.size()));
        bindEmpty(loading, all.size(), shown.size(), q);
        bindSuggestions(all);
        queueDetails(shown);

        // Flag the freshly pulled model — once the bay is really on screen, so the cue isn't missed.
        if (pendingHighlight != null && isShown()) {
            String r = exactInstalled(pendingHighlight);
            if (r != null) {
                pendingHighlight = null;
                highlight = r;
                handler.removeCallbacks(clearHighlight);
                handler.postDelayed(clearHighlight, HIGHLIGHT_MS);
                Card c = cards.get(r);
                ModelInfo m = find(r);
                if (c != null && m != null) {
                    c.bgKey = "";
                    bind(c, m, cur, deep, now);
                    scrollTo(c.root);
                }
            }
        }
        if (sheet != null) {
            if (sheet.isShowing()) sheet.refresh();
            else sheet = null;
        }
    }

    private final Runnable clearHighlight = new Runnable() {
        @Override
        public void run() {
            highlight = null;
            render();
        }
    };

    private ModelInfo find(String name) {
        for (ModelInfo m : e.models()) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    /**
     * The installed name for a pulled reference: exact, or with ":latest".
     * (Engine.resolveInstalled also takes unique partial matches, which would
     * point "qwen3" at an older "qwen3:8b" before the new model is listed.)
     */
    private String exactInstalled(String ref) {
        return e.resolveExact(ref);
    }

    private void bindSummary(List<ModelInfo> all, String cur, String deep) {
        String version = e.server() == null ? "" : e.server().version;
        versionText.setText(version.length() == 0 ? "" : t.hud ? "OLLAMA " + version : "Ollama " + version);
        boolean known = listed || !all.isEmpty();
        int loadedCount = 0;
        long disk = 0;
        long[] sizes = new long[all.size()];
        int[] colors = new int[all.size()];
        boolean anyActive = false, anyLoaded = false, anyStored = false;
        for (int i = 0; i < all.size(); i++) {
            ModelInfo m = all.get(i);
            boolean loaded = e.isLoaded(m.name);
            boolean active = m.name.equals(cur);
            if (loaded) loadedCount++;
            disk += Math.max(0, m.size);
            sizes[i] = Math.max(1, m.size);
            colors[i] = active ? kit.activeColor() : loaded ? kit.loadedColor() : storedColor();
            anyActive |= active;
            anyLoaded |= loaded && !active;
            anyStored |= !loaded && !active;
        }
        statInstalled.setText(known ? String.valueOf(all.size()) : "—");
        statLoaded.setText(known ? String.valueOf(loadedCount) : "—");
        statLoaded.setTextColor(loadedCount > 0 ? t.ok : t.inkStrong);
        statDisk.setText(known ? withUnit(disk > 0 ? Fmt.bytes(disk) : "0 GB") : "—");
        storage.setSegments(sizes, colors);
        storage.setContentDescription(all.size() + " models, " + Fmt.bytes(disk) + " on disk");
        bindLegend(anyActive, anyLoaded, anyStored);

        if (cur.length() > 0) {
            activeValue.setText(kit.name(cur, t.ink));
            activeValue.setTextColor(t.ink);
        } else {
            activeValue.setText(!known ? "Reading…" : all.isEmpty() ? "None installed" : "Not set");
            activeValue.setTextColor(t.dim);
        }
        if (deep != null) {
            deepValue.setText(kit.name(deep, t.ink));
            deepValue.setTextColor(t.ink);
        } else {
            deepValue.setText(known ? "Not set" : "Reading…");
            deepValue.setTextColor(t.dim);
        }
        String mode = e.mode();
        modeTag.setText((Settings.MODE_DEEP.equals(mode) ? "DEEP" : Settings.MODE_FAST.equals(mode) ? "FAST"
                : "AUTO"));
        modeTag.setTextColor(Settings.MODE_DEEP.equals(mode) ? kit.deepInk() : t.dim);
    }

    /** The storage bar's key lists only the states it actually draws (active+loaded shows as active). */
    private void bindLegend(boolean active, boolean loaded, boolean stored) {
        String key = (active ? "a" : "-") + (loaded ? "l" : "-") + (stored ? "s" : "-");
        if (key.equals(legendKey)) return;
        legendKey = key;
        legend.removeAllViews();
        if (active) legend.addView(legendItem(kit.activeColor(), "Active"));
        if (loaded) legend.addView(legendItem(kit.loadedColor(), "Loaded"));
        if (stored) legend.addView(legendItem(storedColor(), "Stored"));
        legend.setVisibility(legend.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** "7.2 GB" with a smaller, dimmer unit. */
    private CharSequence withUnit(String s) {
        int sp = s.lastIndexOf(' ');
        if (sp <= 0) return s;
        SpannableString out = new SpannableString(s);
        out.setSpan(new RelativeSizeSpan(0.55f), sp, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(t.dim), sp, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private void bindEmpty(boolean loading, int total, int shown, final String q) {
        String key;
        if (loading || shown > 0) key = "";
        else if (total == 0) key = "none";
        else key = "nomatch";
        emptyCard.setVisibility(key.length() == 0 ? View.GONE : View.VISIBLE);
        if (key.equals("nomatch")) emptyBody.setText("Nothing installed matches “" + q + "”.");
        if (key.equals(emptyKey)) return;
        emptyKey = key;
        emptyAction.removeAllViews();
        if ("none".equals(key)) {
            emptyTitle.setText(t.hud ? "NO MODELS INSTALLED" : "No models installed");
            emptyBody.setText(kit.withIdents("Your AI needs at least one model to think with. llama3.2 is a quick "
                    + "2 GB all-rounder; qwen3 reasons step by step; llava can see images. Pull one below.",
                    "llama3.2", "qwen3", "llava"));
            TextView b = ui.button("Pull llama3.2", IconDrawable.DOWNLOAD, Ui.PRIMARY, true,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pullField.setText("llama3.2");
                            startPull("llama3.2");
                        }
                    });
            emptyAction.addView(b);
        } else if ("nomatch".equals(key)) {
            emptyTitle.setText(t.hud ? "NO MATCHES" : "No matches");
            TextView b = ui.button("Clear filter", IconDrawable.CLOSE, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    filter.setText("");
                }
            });
            emptyAction.addView(b);
        }
    }

    private void bindSuggestions(List<ModelInfo> all) {
        Set<String> bases = new HashSet<String>();
        for (ModelInfo m : all) bases.add(ModelsFormat.splitTag(m.name)[0].toLowerCase(Locale.US));
        StringBuilder key = new StringBuilder();
        for (String s : SUGGESTIONS) key.append(bases.contains(s) ? '1' : '0');
        if (key.toString().equals(suggestKey)) return;
        suggestKey = key.toString();
        suggestFlow.removeAllViews();
        for (int i = 0; i < SUGGESTIONS.length; i++) {
            final String s = SUGGESTIONS[i];
            boolean have = key.charAt(i) == '1';
            TextView chip = ui.actionChip(s, true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pullField.setText(s);
                    pullField.setSelection(pullField.getText().length());
                    pullField.requestFocus();
                }
            });
            if (have) {
                // Already on the PC: quieter, with a check (pulling it again fetches any update).
                chip.setTextColor(t.dim);
                IconDrawable ok = new IconDrawable(IconDrawable.CHECK, t.ok, t.ok, ui.dp(13));
                ok.setBounds(0, 0, ui.dp(13), ui.dp(13));
                chip.setCompoundDrawables(ok, null, null, null);
                chip.setCompoundDrawablePadding(ui.dp(5));
                chip.setPadding(ui.dp(8), chip.getPaddingTop(), chip.getPaddingRight(), chip.getPaddingBottom());
            }
            chip.setContentDescription("Suggest " + s + (have ? ", installed" : ""));
            suggestFlow.addView(chip);
        }
    }

    private void bindStatePane(Engine.State s) {
        boolean searching = s == Engine.State.SEARCHING;
        int color = searching ? t.warn : t.danger;
        stateDetail.setText(e.stateDetail());
        if (searching && motion()) stateMeter.setIndeterminate(true);
        else stateMeter.setIndeterminate(false);
        stateMeter.setVisibility(searching ? View.VISIBLE : View.GONE);
        String key = searching ? "search" : "off";
        if (key.equals(stateKey)) return;
        stateKey = key;
        stateIcon.setImageDrawable(new IconDrawable(searching ? IconDrawable.SCAN : IconDrawable.WIFI, color, color,
                ui.dp(30)));
        stateBadge.setBackground(Panel.builder().fill(Theme.alpha(color, t.isDark ? 0x14 : 0x0F))
                .edge(Theme.alpha(color, 0x66), Math.max(1, ui.dp(1))).radius(ui.dp(38)).build());
        stateTitle.setText(t.hud ? (searching ? "Scanning for your AI" : "AI link offline")
                .toUpperCase(Locale.US) : searching ? "Searching for your AI…" : "Can't reach your AI");
        stateHint.setText(searching
                ? "The model bay reads your models straight from Ollama on your PC. This takes a few seconds."
                : "The model bay reads your models straight from Ollama on your PC. Make sure Ollama is running "
                + "and this phone is on the same Wi-Fi, then scan again — or check the link on the Command tab.");
        // Stacked, equal-width controls: fits 360dp phones even with Cyber's wide caps.
        stateButtons.removeAllViews();
        if (!searching) {
            TextView scan = ui.button("Scan network", IconDrawable.SCAN, Ui.PRIMARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    e.discover(true);
                }
            });
            stateButtons.addView(scan, new LinearLayout.LayoutParams(ui.dp(236), ui.dp(44)));
        }
        TextView addr = ui.button("Set address", IconDrawable.LINK, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.promptServerAddress();
            }
        });
        stateButtons.addView(addr, ui.margins(new LinearLayout.LayoutParams(ui.dp(236), ui.dp(44)),
                0, searching ? 0 : 10, 0, 0));
        TextView cmd = ui.button("Open Command", IconDrawable.NAV_COMMAND, Ui.GHOST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.select(MainActivity.TAB_COMMAND, true);
            }
        });
        stateButtons.addView(cmd, ui.margins(new LinearLayout.LayoutParams(ui.dp(236), ui.dp(44)),
                0, 6, 0, 0));
    }

    // ------------------------------------------------------------------
    // Pull progress
    // ------------------------------------------------------------------

    private void hideTransfer() {
        transfer.setVisibility(View.GONE);
        tMeter.setIndeterminate(false);
        tDot.setPulsing(false);
        ui.setCapLive(transfer, false);
        handler.removeCallbacks(autoDismiss);
    }

    /** Shows the latest download — running, finished or failed — unless the user closed its card. */
    private void bindTransfer() {
        if (transfer == null) return;
        pullBtn.setAlpha(e.pulling() ? 0.45f : 1f);
        Engine.PullState ps = e.pullState();
        if (ps == null || ModelsPullMemo.isDismissed(ps)) {
            hideTransfer();
            return;
        }
        boolean motion = motion();
        String key;
        String installed = null;
        if (!ps.done) {
            key = "run";
            tDot.setColor(t.data);
            tDot.setPulsing(motion);
            ui.setCapLive(transfer, motion);
            tTitle.setText(kit.withName("Downloading ", ps.name, null));
            status(ModelsFormat.pullStatus(ps.status), false, t.dim);
            tRaw.setVisibility(View.GONE);
            tMeter.setBarColor(t.data);
            if (ps.total > 0) {
                tMeter.setFraction(ps.completed / (float) ps.total);
                tPercent.setText(ps.percent() + "%");
                tBytes.setText(ModelsFormat.transferred(ps.completed, ps.total));
            } else {
                if (motion) tMeter.setIndeterminate(true);
                else tMeter.setFraction(0);
                tPercent.setText("");
                tBytes.setText(t.hud ? "HANDSHAKE…" : "Contacting the registry…");
            }
            String rate = ModelsFormat.rate(ps.bytesPerSec);
            String eta = ModelsFormat.eta(ps.etaSeconds());
            tRate.setText(rate + (rate.length() > 0 && eta.length() > 0 ? "  ·  " : "") + eta);
            tPercent.setTextColor(t.data);
        } else if (ps.error == null) {
            // Retire the card SUCCESS_CARD_MS after it was first on screen; a download that
            // finished while nobody was looking waits for its audience.
            if (isShown()) {
                long now = SystemClock.elapsedRealtime();
                long left = SUCCESS_CARD_MS - (now - ModelsPullMemo.seenAt(ps, now));
                if (left <= 0) {
                    ModelsPullMemo.dismiss(ps);
                    hideTransfer();
                    return;
                }
                handler.removeCallbacks(autoDismiss);
                handler.postDelayed(autoDismiss, left);
            }
            installed = exactInstalled(ps.name);
            boolean embed = installed != null && e.isEmbeddingOnly(installed);
            key = embed ? "ok-embed" : "ok";
            tDot.setColor(t.ok);
            tDot.setPulsing(false);
            ui.setCapLive(transfer, false);
            tTitle.setText(kit.withName("Installed ", installed != null ? installed : ps.name, null));
            status(embed ? "An embedding model — ready for search and memory apps (it can't chat)."
                    : "Verified and ready. Use it now or keep it for later.", true, t.dim);
            tRaw.setVisibility(View.GONE);
            tMeter.setBarColor(t.ok);
            tMeter.setFraction(1f);
            tPercent.setText("100%");
            tPercent.setTextColor(t.ok);
            tBytes.setText(ps.total > 0 ? Fmt.bytes(ps.total) + " downloaded" : "");
            tRate.setText("");
        } else {
            boolean stopped = "stopped".equals(ps.error);
            ReplyError why = stopped ? null : ReplyError.explainPull(ps.error, ps.name);
            boolean badName = why != null && ReplyError.NOT_IN_LIBRARY.equals(why.kind);
            key = stopped ? "stop" : badName ? "name" : "err";
            int color = stopped ? t.warn : t.danger;
            tDot.setColor(color);
            tDot.setPulsing(false);
            ui.setCapLive(transfer, false);
            tTitle.setText(kit.withName(stopped ? "Download stopped · " : "Download failed · ", ps.name, null));
            if (stopped) {
                status("Finished layers are kept on the PC — pulling again resumes.", true, t.dim);
                tRaw.setVisibility(View.GONE);
            } else {
                // The plain-language reason first; Ollama's own words below it, for the curious.
                String reason = ps.reason != null ? ps.reason : ReplyError.plain(why.message);
                status(kit.withIdents(reason, ps.name, "ollama.com/library"), true, t.ink);
                tRaw.setText(ps.error);
                tRaw.setVisibility(ps.error.length() > 0 && !ps.error.equals(reason) ? View.VISIBLE : View.GONE);
            }
            tMeter.setBarColor(color);
            tMeter.setFraction(ps.total > 0 ? ps.completed / (float) ps.total : 0);
            tPercent.setText(ps.total > 0 ? ps.percent() + "%" : "");
            tPercent.setTextColor(color);
            tBytes.setText(ModelsFormat.transferred(ps.completed, ps.total));
            tRate.setText("");
        }
        transfer.setVisibility(View.VISIBLE);
        // A failure before any bytes arrived has no progress worth showing.
        tMeter.setVisibility(ps.done && ps.error != null && ps.total <= 0 ? View.GONE : View.VISIBLE);
        tNums.setVisibility(tBytes.length() == 0 && tRate.length() == 0 ? View.GONE : View.VISIBLE);
        if (key.equals(tKey)) return;
        tKey = key;
        tActions.removeAllViews();
        final Engine.PullState fps = ps;
        if ("run".equals(key)) {
            TextView cancel = ui.button("Cancel", IconDrawable.CLOSE, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    e.pull("stop");
                }
            });
            cancel.setContentDescription("Cancel download");
            tActions.addView(cancel);
            return;
        }
        if ("ok".equals(key)) {
            TextView useNow = ui.button("Use now", IconDrawable.CHECK, Ui.PRIMARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String n = exactInstalled(fps.name);
                    if (n == null) {
                        ui.toast("Still reading the model list — try again in a moment.");
                        return;
                    }
                    use(n);
                    ModelsPullMemo.dismiss(fps);
                    bindTransfer();
                }
            });
            useNow.setContentDescription("Use the new model");
            tActions.addView(useNow);
        } else if ("ok-embed".equals(key)) {
            TextView details = ui.button("Details", IconDrawable.INFO, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String n = exactInstalled(fps.name);
                    if (n != null) openSheet(n);
                }
            });
            details.setContentDescription("Details for the new model");
            tActions.addView(details);
        } else if ("name".equals(key)) {
            // Nothing by that name in the library: retrying can't help, fixing the name can.
            TextView edit = ui.button("Edit name", IconDrawable.EDIT, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pullField.setText(fps.name);
                    pullField.selectAll();
                    focusPull();
                }
            });
            edit.setContentDescription("Edit the model name");
            tActions.addView(edit);
        } else {
            TextView again = ui.button("stop".equals(key) ? "Resume" : "Retry", IconDrawable.DOWNLOAD, Ui.SECONDARY,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startPull(fps.name);
                        }
                    });
            again.setContentDescription("stop".equals(key) ? "Resume download" : "Retry download");
            tActions.addView(again);
        }
        tActions.addView(ui.space(8, 1));
        TextView dismiss = ui.button("Dismiss", 0, Ui.GHOST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModelsPullMemo.dismiss(fps);
                bindTransfer();
            }
        });
        dismiss.setContentDescription("Dismiss download");
        tActions.addView(dismiss);
    }

    /** The transfer card's status line: telemetry in mono, sentences in the reading face. */
    private void status(CharSequence s, boolean prose, int color) {
        tStatus.setText(s);
        tStatus.setTypeface(prose ? t.body : t.mono);
        tStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, prose ? 13 : 12);
        tStatus.setLineSpacing(0, prose ? 1.2f : 1f);
        tStatus.setTextColor(color);
    }

    // ------------------------------------------------------------------
    // Engine events & lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged() {
        render();
        bindTransfer();
    }

    @Override
    public void onPull() {
        Engine.PullState ps = e.pullState();
        if (ps != null && ps.done && ps.error == null && ModelsPullMemo.claim(ps)) {
            pendingHighlight = ps.name;
            if (pullField != null && pullField.getText().toString().trim().equals(ps.name)) pullField.setText("");
        }
        bindTransfer();
    }

    /** A finished download's card retires on its own once it has been on screen a while. */
    private final Runnable autoDismiss = new Runnable() {
        @Override
        public void run() {
            Engine.PullState ps = e.pullState();
            if (ps != null && ps.done && ps.error == null && isShown()) {
                ModelsPullMemo.dismiss(ps);
                bindTransfer();
            }
        }
    };

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (!isShown()) return;
            if (e.state() == Engine.State.ONLINE && !refreshing) e.refreshModels(null);
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onShow() {
        render();
        bindTransfer();
        if (e.state() == Engine.State.ONLINE) refresh(false);
        handler.removeCallbacks(poller);
        handler.postDelayed(poller, POLL_MS);
    }

    @Override
    protected void onHide() {
        pause();
    }

    @Override
    public void onActivityStart() {
        if (isShown()) onShow();
    }

    @Override
    public void onActivityStop() {
        pause();
    }

    @Override
    public void onDestroy() {
        pause();
        handler.removeCallbacksAndMessages(null);
        if (sheet != null) sheet.dismiss();
    }

    /** Stops polling, timers and every animation (tab hidden or app in the background). */
    private void pause() {
        handler.removeCallbacks(poller);
        handler.removeCallbacks(autoDismiss);
        stopSpin();
        if (bay == null) return;
        setSkeletonsAnimating(false);
        stateMeter.setIndeterminate(false);
        tMeter.setIndeterminate(false);
        tDot.setPulsing(false);
        ui.setCapLive(transfer, false);
        for (Card c : cards.values()) {
            c.residentDot.setPulsing(false);
            if (busy.containsKey(c.name)) c.residentMeter.setFraction(0);
        }
    }

    @Override
    public boolean onBack() {
        if (filter != null && filter.getText().length() > 0) {
            filter.setText("");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Refresh & capabilities
    // ------------------------------------------------------------------

    /**
     * Re-reads installed and loaded models. {@code manual} (the refresh
     * button) spins the icon and also retries capability reads that failed.
     */
    private void refresh(boolean manual) {
        if (e.state() != Engine.State.ONLINE || refreshing) return;
        refreshing = true;
        if (manual) {
            detailFailed.clear();
            startSpin();
        }
        e.refreshModels(new Runnable() {
            @Override
            public void run() {
                refreshing = false;
                listed = true;
                stopSpin();
                render();
            }
        });
    }

    private void startSpin() {
        if (refreshBtn == null || e.settings.reduceMotion() || !isShown()) return;
        if (spin == null) {
            spin = ObjectAnimator.ofFloat(refreshBtn, "rotation", 0f, 360f);
            spin.setDuration(900);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
        }
        if (!spin.isRunning()) spin.start();
    }

    private void stopSpin() {
        if (spin != null) spin.cancel();
        if (refreshBtn != null) refreshBtn.setRotation(0f);
    }

    private void setSkeletonsAnimating(boolean on) {
        for (ModelsSkeleton s : skeletons) s.setAnimating(on);
    }

    /** Reads /api/show for each shown model, one at a time, so cards gain their capability chips. */
    private void queueDetails(List<ModelInfo> shown) {
        for (ModelInfo m : shown) {
            if (e.details(m.name) == null && !detailFailed.contains(m.name) && !detailQueue.contains(m.name)) {
                detailQueue.add(m.name);
            }
        }
        pumpDetails();
    }

    private void pumpDetails() {
        if (fetchingDetails || detailQueue.isEmpty() || e.state() != Engine.State.ONLINE) return;
        final String n = detailQueue.poll();
        if (e.details(n) != null) {
            pumpDetails();
            return;
        }
        fetchingDetails = true;
        e.fetchDetails(n, new Engine.Callback<OllamaClient.ModelDetails>() {
            @Override
            public void done(OllamaClient.ModelDetails v, String error) {
                fetchingDetails = false;
                if (v == null) detailFailed.add(n);
                render();
                pumpDetails();
            }
        });
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** "llava:7b is still loading — …" for a model with an operation in flight. */
    private String busyNote(String name) {
        String op = busy.get(name);
        String doing = OP_LOAD.equals(op) ? "loading into memory" : OP_UNLOAD.equals(op) ? "releasing its memory"
                : "being deleted";
        return name + " is still " + doing + " — try again when it's done.";
    }

    private void use(String name) {
        if (OP_DELETE.equals(busy.get(name))) {
            ui.toast(busyNote(name));
            return;
        }
        if (e.isEmbeddingOnly(name)) {
            ui.toast(name + " is an embedding model — it turns text into vectors and can't chat. "
                    + "Pick a chat model.");
            return;
        }
        if (name.equals(e.currentModel()) && !e.models().isEmpty() && name.equals(e.settings.model())) {
            ui.toast(name + " is already the active model.");
            return;
        }
        e.setModel(name);
        e.log("info", "Active model · " + name);
        ui.toast((t.hud ? "Active model → " : "Now using ") + name);
        scrollToTop();
    }

    private void toggleLoad(final String name) {
        if (busy.containsKey(name)) {
            ui.toast(busyNote(name));
            return;
        }
        final boolean unload = e.isLoaded(name);
        if (!unload && e.isEmbeddingOnly(name)) {
            ui.toast(name + " is an embedding model — Ollama loads it on demand.");
            return;
        }
        pinActive();
        busy.put(name, unload ? OP_UNLOAD : OP_LOAD);
        render();
        ModelsOps.setLoaded(e, name, unload, new ModelsOps.Done() {
            @Override
            public void done(long ms, String error) {
                // Engine.setLoaded already logged it and refreshed the model list.
                busy.remove(name);
                render();
                if (error != null) ui.toast((unload ? "Couldn't unload " : "Couldn't load ") + name + ": " + error);
            }
        });
    }

    /**
     * With no saved choice, the Engine treats a loaded model (else the first)
     * as active, so loading or unloading another model would silently move the
     * ACTIVE badge. Saving the model the bay shows as active keeps it put.
     */
    private void pinActive() {
        if (e.models().isEmpty() || e.resolveInstalled(e.settings.model()) != null) return;
        String cur = activeModel(installed());
        if (cur.length() > 0) e.setModel(cur);
    }

    private void toggleDeep(String name) {
        if (name == null) return;
        String deep = e.resolveInstalled(e.settings.deepModel());
        if (name.equals(deep)) {
            clearDeep();
            return;
        }
        e.setDeepModel(name);
        e.log("info", "Deep model · " + name);
        Boolean thinks = e.supportsThinking(name);
        ui.toast("Deep model: " + name + (Boolean.FALSE.equals(thinks)
                ? " (it can't think step by step — qwen3 or deepseek-r1 can)" : ""));
    }

    /** Clears the deep-model setting, whatever it names (even a model that has since left the list). */
    private void clearDeep() {
        e.setDeepModel("");
        e.log("info", "Deep model cleared");
        ui.toast("Deep model cleared — deep questions use the active model.");
    }

    /** Who takes over when the active model {@code gone} is deleted: a loaded chat model, else the first. */
    private String successorFor(String gone) {
        String first = null;
        for (ModelInfo m : e.models()) {
            String n = m.name;
            if (n.equals(gone) || deleted.contains(n) || OP_DELETE.equals(busy.get(n)) || e.isEmbeddingOnly(n)) {
                continue;
            }
            if (e.isLoaded(n)) return n;
            if (first == null) first = n;
        }
        return first;
    }

    private void confirmDelete(final String name) {
        if (busy.containsKey(name)) {
            ui.toast(busyNote(name));
            return;
        }
        ModelInfo m = find(name);
        Sheet s = ui.sheet("Confirm", kit.withName("Delete ", name, "?"));
        s.eyebrowColor(t.danger);
        SpannableStringBuilder msg = new SpannableStringBuilder("This removes ");
        msg.append(ui.mono(name)).append(" from your PC");
        if (m != null && m.size > 0) msg.append(" and frees ").append(Fmt.bytes(m.size));
        msg.append(". You can pull it again any time.");
        if (name.equals(activeModel(installed()))) {
            String next = successorFor(name);
            if (next != null) {
                msg.append("\n\nIt's your active model — replies will switch to ").append(ui.mono(next)).append(".");
            } else {
                msg.append("\n\nIt's your active model, and no other chat model is installed — pull one to keep "
                        + "chatting.");
            }
        }
        s.message(msg);
        s.negative("Cancel", null);
        s.positive("Delete", Ui.DANGER, new Runnable() {
            @Override
            public void run() {
                delete(name);
            }
        });
        s.show();
    }

    private void delete(final String name) {
        if (busy.containsKey(name)) {
            ui.toast(busyNote(name));
            return;
        }
        final boolean wasActive = name.equals(activeModel(installed()));
        final boolean wasDeep = name.equals(e.resolveInstalled(e.settings.deepModel()));
        final String deepSetting = e.settings.deepModel();
        busy.put(name, OP_DELETE);
        render();
        e.deleteModel(name, new Engine.Callback<Boolean>() {
            @Override
            public void done(Boolean ok, String error) {
                busy.remove(name);
                if (error != null) {
                    render();
                    ui.toast("Couldn't delete " + name + ": " + error);
                    return;
                }
                deleted.add(name);
                // The Engine's list keeps the deleted model until its refresh lands, and with the saved
                // choice cleared, currentModel() asked now would pick — and save — that very model.
                // So the successor is named first, before anything else asks.
                String next = wasActive ? successorFor(name) : null;
                if (next != null) {
                    e.setModel(next);
                    e.log("info", "Active model · " + next);
                }
                if (wasDeep) clearDeepAfterDelete(deepSetting);
                ui.toast(next != null ? "Deleted " + name + " · now using " + next : "Deleted " + name);
                render();
            }
        });
    }

    /**
     * Forgets a deleted deep model. Clearing notifies every screen, and they
     * ask the Engine for the active model: fine while a real model is saved,
     * but with none (the last chat model went) that waits for the list refresh,
     * or they'd be handed the deleted model.
     */
    private void clearDeepAfterDelete(final String deepSetting) {
        String saved = e.resolveInstalled(e.settings.model());
        if (saved != null && !deleted.contains(saved)) {
            e.setDeepModel("");
            return;
        }
        e.refreshModels(new Runnable() {
            @Override
            public void run() {
                if (e.settings.deepModel().equals(deepSetting)) e.setDeepModel("");
            }
        });
    }

    private void showActions(final String name) {
        String op = busy.get(name);
        final boolean active = name.equals(activeModel(installed()));
        final boolean loaded = e.isLoaded(name);
        final boolean isDeep = name.equals(e.resolveInstalled(e.settings.deepModel()));
        final boolean embedding = e.isEmbeddingOnly(name);
        ModelInfo m = find(name);
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        if (op != null) {
            // What's running, and why Load / Delete aren't offered until it's done.
            boolean del = OP_DELETE.equals(op);
            rows.add(new Ui.Row(del ? "Deleting from the PC…" : OP_LOAD.equals(op) ? "Loading into memory…"
                    : "Releasing memory…", del ? "It leaves the list when the PC is done"
                    : "Unload and Delete come back when it's done", false, null, null)
                    .icon(del ? IconDrawable.TRASH : OP_LOAD.equals(op) ? IconDrawable.BOLT : IconDrawable.POWER));
        }
        if (!OP_DELETE.equals(op)) {
            if (!active && !embedding) {
                rows.add(new Ui.Row("Use for chat", "Make it the active model", false, new Runnable() {
                    @Override
                    public void run() {
                        use(name);
                    }
                }, null).icon(IconDrawable.CHECK));
            }
            if (op == null && (!embedding || loaded)) {
                rows.add(new Ui.Row(loaded ? "Unload from memory" : "Load into memory", loaded
                        ? "Free its memory on the PC" : "Warm it up so the first reply is quick", false, new Runnable() {
                    @Override
                    public void run() {
                        toggleLoad(name);
                    }
                }, null).icon(loaded ? IconDrawable.POWER : IconDrawable.BOLT));
            }
            if (!embedding || isDeep) {
                rows.add(new Ui.Row(isDeep ? "Remove as deep model" : "Set as deep model",
                        "Hard questions in Auto mode go here, with thinking on", isDeep, new Runnable() {
                    @Override
                    public void run() {
                        toggleDeep(name);
                    }
                }, null).icon(IconDrawable.BRAIN));
            }
        }
        rows.add(new Ui.Row("Details", "Context window, family, license, parameters", false, new Runnable() {
            @Override
            public void run() {
                openSheet(name);
            }
        }, null).icon(IconDrawable.INFO));
        rows.add(new Ui.Row("Copy name", null, false, new Runnable() {
            @Override
            public void run() {
                a.copy("model", name);
            }
        }, null).icon(IconDrawable.COPY));
        if (op == null) {
            rows.add(new Ui.Row("Delete from PC…", m != null && m.size > 0 ? "Frees " + Fmt.bytes(m.size) + " on disk"
                    : null, false, new Runnable() {
                @Override
                public void run() {
                    confirmDelete(name);
                }
            }, null).icon(IconDrawable.TRASH).danger());
        }
        ui.pick("Model", ui.mono(name), rows, null, null);
    }

    private void openSheet(String name) {
        if (sheet != null) sheet.dismiss();
        sheet = ModelsSheet.show(e, kit, name, new ModelsSheet.Actions() {
            @Override
            public void use(String model) {
                ModelsScreen.this.use(model);
            }

            @Override
            public void openChat() {
                a.select(MainActivity.TAB_COMMS, true);
            }

            @Override
            public void toggleLoad(String model) {
                ModelsScreen.this.toggleLoad(model);
            }

            @Override
            public String busy(String model) {
                return busy.get(model);
            }
        });
    }

    private void pickActive() {
        pickModel("Active model", activeModel(installed()), false, new ModelChoice() {
            @Override
            public void chose(String name) {
                use(name);
            }
        });
    }

    private void pickDeep() {
        final String deep = e.resolveInstalled(e.settings.deepModel());
        pickModel("Deep model", deep, true, new ModelChoice() {
            @Override
            public void chose(String name) {
                if (!name.equals(deep)) toggleDeep(name);
            }
        });
    }

    private interface ModelChoice {
        void chose(String name);
    }

    private void pickModel(String title, String current, final boolean deep, final ModelChoice choice) {
        List<ModelInfo> ms = installed();
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        for (final ModelInfo m : ms) {
            if (e.isEmbeddingOnly(m.name) || OP_DELETE.equals(busy.get(m.name))) continue;
            String detail = m.describe();
            Boolean thinks = e.supportsThinking(m.name);
            if (deep && Boolean.TRUE.equals(thinks)) detail = "thinking · " + detail;
            if (e.isLoaded(m.name)) detail = "loaded · " + detail;
            rows.add(new Ui.Row(ui.mono(m.name), detail, m.name.equals(current), new Runnable() {
                @Override
                public void run() {
                    choice.chose(m.name);
                }
            }, null));
        }
        if (rows.isEmpty()) {
            ui.toast(ms.isEmpty() ? "No models installed yet — pull one below."
                    : "No chat models installed yet — pull one below.");
            focusPull();
            return;
        }
        if (deep) {
            // Clear works on the setting itself: the model it names may have left the list meanwhile.
            ui.pick("Model bay", title, rows, e.settings.deepModel().length() > 0 ? "Clear" : null, new Runnable() {
                @Override
                public void run() {
                    clearDeep();
                }
            });
        } else {
            ui.pick("Model bay", title, rows, null, null);
        }
    }

    /** Validates and starts a download, then brings the progress card into view. */
    private void startPull(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.length() == 0) {
            ui.toast("Type a model name, or tap a suggestion.");
            pullField.requestFocus();
            return;
        }
        if (!ModelsFormat.validName(name)) {
            ui.toast("“" + name + "” isn't a valid model name — try something like qwen3:8b.");
            return;
        }
        if (e.state() != Engine.State.ONLINE) {
            ui.toast("Your AI isn't connected yet.");
            return;
        }
        if (e.pulling()) {
            ui.toast("A download is already running — cancel it first.");
            return;
        }
        hideKeyboard(pullField);
        pullField.clearFocus();
        e.pull(name);
        bindTransfer();
        scrollTo(transfer);
    }

    private void focusPull() {
        scrollTo(pullBay);
        pullField.requestFocus();
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(pullField, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void scrollToTop() {
        if (e.settings.reduceMotion()) scroll.scrollTo(0, 0);
        else scroll.smoothScrollTo(0, 0);
    }

    /** Scrolls so {@code target} (a descendant of the column) sits near the top, after layout. */
    private void scrollTo(final View target) {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int y = 0;
                View v = target;
                while (v != null && v != column) {
                    y += v.getTop();
                    v = v.getParent() instanceof View ? (View) v.getParent() : null;
                }
                y = Math.max(0, y - ui.dp(12));
                if (e.settings.reduceMotion()) scroll.scrollTo(0, y);
                else scroll.smoothScrollTo(0, y);
            }
        });
    }

    // ------------------------------------------------------------------
    // Small drawables & helpers
    // ------------------------------------------------------------------

    /** A panel with a short state bar down its left edge (active / just-installed model). */
    private static final class SignalPanel extends Drawable {
        private final Panel inner;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float width;
        private final float inset;

        SignalPanel(Panel inner, int color, float width, float inset) {
            this.inner = inner;
            this.width = width;
            this.inset = inset;
            bar.setColor(color);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            inner.setBounds(b);
        }

        @Override
        public void draw(Canvas c) {
            inner.draw(c);
            if (bar.getColor() == 0) return;
            Rect b = getBounds();
            r.set(b.left, b.top + inset, b.left + width, b.bottom - inset);
            c.drawRoundRect(r, width, width, bar);
        }

        @Override
        public void getOutline(Outline outline) {
            inner.getOutline(outline);
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

    /** The section "data rail": dashed in the Cyber HUD, a plain hairline elsewhere. */
    private static final class Rail extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Rail(int color, boolean dashed, float density) {
            p.setColor(color);
            p.setStrokeWidth(Math.max(1f, density * 0.8f));
            p.setStyle(Paint.Style.STROKE);
            if (dashed) p.setPathEffect(new DashPathEffect(new float[]{4 * density, 3 * density}, 0));
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float y = b.exactCenterY();
            c.drawLine(b.left, y, b.right, y, p);
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** TextWatcher with only afterTextChanged to implement. */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
