package com.omnideck.mobile.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;

import java.util.Locale;

/**
 * The model spec sheet: a themed dialog with everything Ollama reports about
 * one model — context window, parameters, quantization, format, family, disk
 * and memory footprint, capabilities, license and runtime parameters — plus
 * the two actions you most likely want next (use it, load/unload it).
 */
public final class ModelsSheet {
    /** What the sheet's buttons do (implemented by the model bay). */
    public interface Actions {
        void use(String model);

        void openChat();

        void toggleLoad(String model);
    }

    private final Context c;
    private final Engine e;
    private final ModelsKit kit;
    private final Ui ui;
    private final Theme t;
    private final String model;
    private final Actions actions;
    private Dialog dialog;
    private LinearLayout content;

    private ModelsSheet(Context c, Engine e, ModelsKit kit, String model, Actions actions) {
        this.c = c;
        this.e = e;
        this.kit = kit;
        this.ui = kit.ui;
        this.t = kit.t;
        this.model = model;
        this.actions = actions;
    }

    /** Opens the sheet for {@code model}; details load in place if they aren't cached yet. */
    public static Dialog show(Context c, Engine e, ModelsKit kit, String model, Actions actions) {
        return new ModelsSheet(c, e, kit, model, actions).open();
    }

    private Dialog open() {
        dialog = new Dialog(c);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = ui.vbox();
        card.setBackground(sheetBackground());
        card.setClickable(true);
        card.addView(header(), Ui.fillW());
        View line = new View(c);
        line.setBackgroundColor(t.hud ? Theme.alpha(t.accent, 0x33) : t.hair);
        card.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1))));

        ScrollView sv = new CappedScroll(c, (int) (c.getResources().getDisplayMetrics().heightPixels * 0.6f));
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        content = ui.vbox();
        content.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(6));
        sv.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sv, Ui.fillW());
        View rule = new View(c);
        rule.setBackgroundColor(t.hud ? t.hair : t.hairSoft);
        card.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1))));
        card.addView(footer(), Ui.fillW());

        FrameLayout frame = new FrameLayout(c);
        frame.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        frame.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(frame);
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0));

        OllamaClient.ModelDetails d = e.details(model);
        fill(d, d == null ? null : "");
        if (d == null) {
            e.fetchDetails(model, new Engine.Callback<OllamaClient.ModelDetails>() {
                @Override
                public void done(OllamaClient.ModelDetails v, String error) {
                    if (dialog.isShowing()) fill(v, error);
                }
            });
        }
        dialog.show();
        if (w != null) {
            int screenW = c.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screenW - ui.dp(24), ui.dp(460)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private android.graphics.drawable.Drawable sheetBackground() {
        Panel.Builder b = Panel.builder().fill(opaque(t.hud ? t.surface2 : t.surface, t.bg))
                .edge(t.hud ? t.edgeStrong : t.edge, Math.max(1, ui.dp(1))).radius(ui.dp(t.radius + 2))
                .highlight(t.panelHi);
        if (t.hud) {
            b.grid(ui.dp(22), t.gridColor).bloom(t.bloomColor)
                    .brackets(ui.dp(12), ui.dp(1.3f), t.bracketColor).bracketInset(ui.dp(6));
        }
        return b.build();
    }

    /** Solid version of a translucent color over {@code base} (dialogs sit over a dim scrim). */
    static int opaque(int color, int base) {
        int a = (color >>> 24) & 0xFF;
        if (a == 0xFF) return color;
        int r = (((color >> 16) & 0xFF) * a + ((base >> 16) & 0xFF) * (255 - a)) / 255;
        int g = (((color >> 8) & 0xFF) * a + ((base >> 8) & 0xFF) * (255 - a)) / 255;
        int bl = ((color & 0xFF) * a + (base & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private View header() {
        LinearLayout h = ui.hbox();
        h.setGravity(Gravity.TOP);
        h.setPadding(ui.dp(18), ui.dp(16), ui.dp(6), ui.dp(14));
        LinearLayout titles = ui.vbox();
        TextView kicker = ui.label(t.hud ? "Model spec sheet" : "Model details");
        titles.addView(kicker);
        TextView name = ui.text(kit.name(model, t.inkStrong), 18, t.inkStrong, t.bodySemi);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, ui.dp(7), 0, 0);
        titles.addView(name);
        h.addView(titles, Ui.weight(1));
        View close = ui.iconButton(IconDrawable.CLOSE, "Close details", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        close.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        h.addView(close);
        return h;
    }

    private View footer() {
        LinearLayout f = ui.hbox();
        f.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(18));
        final boolean active = model.equals(e.currentModel());
        final boolean loaded = e.isLoaded(model);
        OllamaClient.ModelDetails d = e.details(model);
        boolean embedding = d != null && d.supports("embedding") && !d.supports("completion");
        TextView load = ui.button(loaded ? "Unload" : "Load", loaded ? IconDrawable.POWER : IconDrawable.BOLT,
                Ui.SECONDARY, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        actions.toggleLoad(model);
                    }
                });
        load.setContentDescription((loaded ? "Unload " : "Load ") + model);
        View loadWrap = kit.wide(load);
        if (embedding) ModelsKit.disable(loadWrap);
        f.addView(loadWrap, Ui.weight(1));
        f.addView(ui.space(10, 1));
        TextView use = ui.button(active ? "Open chat" : "Use model", active ? IconDrawable.NAV_COMMS
                : IconDrawable.CHECK, Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (active) actions.openChat();
                else actions.use(model);
            }
        });
        View useWrap = kit.wide(use);
        if (embedding) ModelsKit.disable(useWrap);
        f.addView(useWrap, Ui.weight(1));
        return f;
    }

    /** (Re)builds the body; {@code error} null = still loading, "" = loaded. */
    private void fill(OllamaClient.ModelDetails d, String error) {
        content.removeAllViews();
        ModelInfo m = find();
        ModelInfo run = e.runningInfo(model);
        long now = System.currentTimeMillis();
        String deep = e.resolveInstalled(e.settings.deepModel());

        ModelsFlow chips = new ModelsFlow(c, ui.dp(6), ui.dp(6));
        kit.addChips(chips, d, model.equals(e.currentModel()), model.equals(deep), run != null, error == null);
        if (chips.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = Ui.fillW();
            lp.bottomMargin = ui.dp(12);
            content.addView(chips, lp);
        }

        if (d == null && error == null) {
            LinearLayout wait = ui.hbox();
            wait.setPadding(0, ui.dp(2), 0, ui.dp(10));
            Widgets.Meter meter = kit.meter(t.data);
            if (e.settings.reduceMotion()) meter.setFraction(0.33f);
            else meter.setIndeterminate(true);
            wait.addView(meter, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(3)));
            TextView tx = ui.dim("Reading the model sheet from your PC…", 12.5f);
            tx.setPadding(ui.dp(10), 0, 0, 0);
            wait.addView(tx, Ui.weight(1));
            content.addView(wait, Ui.fillW());
        } else if (d == null) {
            TextView err = ui.text("Couldn't read details: " + error, 13, t.danger, t.body);
            err.setPadding(0, 0, 0, ui.dp(10));
            content.addView(err, Ui.fillW());
        }

        String family = pick(m == null ? "" : m.family, d == null ? "" : d.family);
        String params = pick(m == null ? "" : m.parameterSize, d == null ? "" : d.parameterSize);
        String quant = pick(m == null ? "" : m.quantization, d == null ? "" : d.quantization);
        String modified = m != null ? m.modifiedAt : d != null ? d.modifiedAt : "";

        row(content, "Context window", d == null ? "—" : d.contextLength > 0
                ? ModelsFormat.grouped(d.contextLength) + " tokens" : "Not reported", true);
        row(content, "Parameters", dash(params), false);
        row(content, "Quantization", dash(quant), false);
        row(content, "Format", dash(d == null ? "" : d.format.toUpperCase(Locale.US)), false);
        row(content, "Family", dash(family), false);
        row(content, "Size on disk", m != null && m.size > 0 ? Fmt.bytes(m.size) : "—", false);
        String age = ModelsFormat.ago(modified, now);
        row(content, "Modified", age.length() == 0 ? "—" : age + (modified.length() >= 10
                ? "  ·  " + modified.substring(0, 10) : ""), false);
        if (run != null) {
            row(content, "In memory", kit.residency(run), false);
            String detail = kit.residencyDetail(run, now);
            if (detail.length() > 0) row(content, "Session", detail, false);
        } else {
            row(content, "In memory", "Not loaded", false);
        }

        if (d != null && d.license.length() > 0) {
            section(content, "License");
            TextView lic = ui.dim(d.license, 13);
            content.addView(lic, Ui.fillW());
        }
        if (d != null && d.parameters.length() > 0) {
            section(content, "Runtime parameters");
            TextView code = ui.text(d.parameters, 12, t.codeText, t.mono);
            code.setLineSpacing(0, 1.25f);
            code.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            code.setBackground(ui.rounded(t.codeBg, t.hud ? t.hair : t.edge, 8));
            content.addView(code, Ui.fillW());
        }
        content.addView(ui.space(1, 8));
    }

    private ModelInfo find() {
        for (ModelInfo m : e.models()) {
            if (m.name.equals(model)) return m;
        }
        return null;
    }

    private static String pick(String a, String b) {
        return a != null && a.length() > 0 ? a : b == null ? "" : b;
    }

    private static String dash(String s) {
        return s == null || s.length() == 0 ? "—" : s;
    }

    private void section(LinearLayout into, String title) {
        TextView l = ui.label(title);
        l.setPadding(0, ui.dp(18), 0, ui.dp(8));
        into.addView(l, Ui.fillW());
    }

    /** One spec-sheet line: micro-caps key on the left, mono value on the right. */
    private void row(LinearLayout into, String key, String value, boolean emphasis) {
        LinearLayout r = ui.hbox();
        r.setPadding(0, ui.dp(9), 0, ui.dp(9));
        TextView k = ui.label(key);
        r.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
        TextView v = ui.text(value, emphasis ? 14.5f : 13.5f, emphasis ? t.inkStrong : t.ink, t.mono);
        v.setGravity(Gravity.END);
        v.setMaxLines(3);
        v.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        if (into.getChildCount() > 0 && !(into.getChildAt(into.getChildCount() - 1) instanceof ModelsFlow)) {
            View hair = new View(c);
            hair.setBackgroundColor(t.hairSoft);
            into.addView(hair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, ui.dp(0.7f))));
        }
        into.addView(r, Ui.fillW());
    }

    /** A ScrollView that never grows taller than {@code maxPx}. */
    private static final class CappedScroll extends ScrollView {
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
