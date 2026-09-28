package com.omnideck.mobile.ui;

import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;

import java.util.Locale;

/**
 * The model spec sheet, on the app's shared dialog chrome ({@link Sheet}):
 * everything Ollama reports about one model — context window, parameters,
 * quantization, format, family, disk and memory footprint, capabilities,
 * license and runtime parameters — plus the two actions you most likely want
 * next. Load / Unload keeps the sheet open and it follows along live (the
 * model bay calls {@link #refresh()} on every change); "Use model" switches
 * and closes it.
 */
public final class ModelsSheet {
    /** What the sheet's buttons do, and what's running (implemented by the model bay). */
    public interface Actions {
        void use(String model);

        void openChat();

        void toggleLoad(String model);

        /** The operation running on {@code model} ("load", "unload" or "delete"), or null. */
        String busy(String model);
    }

    private static final String OP_LOAD = ModelsOps.LOAD;
    private static final String OP_UNLOAD = ModelsOps.UNLOAD;
    private static final String OP_DELETE = ModelsOps.DELETE;

    private final Engine e;
    private final ModelsKit kit;
    private final Ui ui;
    private final Theme t;
    private final String model;
    private final Actions actions;
    private final boolean embedding;
    private Sheet sheet;
    private LinearLayout content;
    /** Chat models: Load / Unload. Embedding models: Unload (only while one is resident). */
    private Button loadBtn;
    /** Chat models: Use model / Open chat. */
    private Button useBtn;
    private OllamaClient.ModelDetails details;
    /** null while the details are on their way, "" once read, else why they couldn't be. */
    private String error;
    private String key = "";

    private ModelsSheet(Engine e, ModelsKit kit, String model, Actions actions) {
        this.e = e;
        this.kit = kit;
        this.ui = kit.ui;
        this.t = kit.t;
        this.model = model;
        this.actions = actions;
        this.embedding = e.isEmbeddingOnly(model);
    }

    /** Opens the sheet for {@code model}; details load in place if they aren't cached yet. */
    public static ModelsSheet show(Engine e, ModelsKit kit, String model, Actions actions) {
        ModelsSheet s = new ModelsSheet(e, kit, model, actions);
        s.open();
        return s;
    }

    public String model() {
        return model;
    }

    public AlertDialog dialog() {
        return sheet.dialog;
    }

    public boolean isShowing() {
        return sheet != null && sheet.isShowing();
    }

    public void dismiss() {
        if (sheet != null) sheet.dismiss();
    }

    private void open() {
        sheet = ui.sheet(t.hud ? "Model spec sheet" : "Model details", kit.monoName(model, t.inkStrong));
        sheet.closeButton("Close details");
        sheet.footerRule(true);   // the spec list scrolls under the actions
        content = ui.vbox();
        sheet.body.addView(content, Ui.fillW());
        buildFooter();

        details = e.details(model);
        error = details == null ? null : "";
        refresh();
        if (details == null) {
            e.fetchDetails(model, new Engine.Callback<OllamaClient.ModelDetails>() {
                @Override
                public void done(OllamaClient.ModelDetails v, String err) {
                    details = v;
                    error = v != null ? "" : err == null ? "No details." : err;
                    if (isShowing()) refresh();
                }
            });
        }
        sheet.show();
    }

    private void buildFooter() {
        if (embedding) {
            // Embedding models can't chat, and Ollama loads them on demand: the only
            // action worth a button is releasing one that another app left resident.
            if (e.isLoaded(model)) {
                loadBtn = sheet.neutral("Unload", null);
                keepOpenOnTap(loadBtn);
            }
            sheet.negative("Close", null);
            return;
        }
        loadBtn = sheet.negative("Load", null);
        keepOpenOnTap(loadBtn);
        useBtn = sheet.positive("Use model", Ui.PRIMARY, new Runnable() {
            @Override
            public void run() {
                if (model.equals(e.currentModel())) actions.openChat();
                else actions.use(model);
            }
        });
    }

    /** Load / Unload runs in place: the sheet stays up and shows the model coming and going. */
    private void keepOpenOnTap(Button b) {
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                if (actions.busy(model) == null) actions.toggleLoad(model);
            }
        });
    }

    /** Re-reads the model's state (memory, activity, busy) and redraws what changed. */
    public void refresh() {
        if (sheet == null) return;
        ModelInfo m = find();
        if (m == null && !e.models().isEmpty()) {
            // Deleted (here or on the PC): nothing left to describe.
            dismiss();
            return;
        }
        ModelInfo run = e.runningInfo(model);
        String op = actions.busy(model);
        boolean active = model.equals(e.currentModel());
        boolean deep = model.equals(e.resolveInstalled(e.settings.deepModel()));
        String k = System.identityHashCode(details) + "|" + error + "|" + op + "|" + active + "|" + deep + "|"
                + (run == null ? "-" : run.sizeVram + "/" + run.size + "/" + run.contextLength + "/" + run.expiresAt)
                + "|" + (m == null ? "-" : m.size + m.modifiedAt);
        if (!k.equals(key)) {
            key = k;
            fill(m, run, op, active, deep);
        }
        bindFooter(run != null, op, active);
    }

    private void bindFooter(boolean loaded, String op, boolean active) {
        boolean chat = !embedding && !e.isEmbeddingOnly(model);
        if (loadBtn != null) {
            String label = OP_LOAD.equals(op) ? "Loading…" : OP_UNLOAD.equals(op) ? "Releasing…"
                    : loaded || embedding ? "Unload" : "Load";
            setLabel(loadBtn, label);
            loadBtn.setContentDescription((loaded ? "Unload " : "Load ") + model);
            setEnabled(loadBtn, op == null && (chat || loaded));
        }
        if (useBtn != null) {
            setLabel(useBtn, active ? "Open chat" : "Use model");
            useBtn.setContentDescription(active ? "Open chat with " + model : "Use " + model);
            setEnabled(useBtn, chat && !OP_DELETE.equals(op));
        }
    }

    /** Relabels a footer button the way the kit styles one (Cyber: HUD caps). */
    private void setLabel(Button b, String label) {
        b.setText(t.hud ? label.toUpperCase(Locale.US) : label);
    }

    private static void setEnabled(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.45f);
    }

    /** (Re)builds the body. */
    private void fill(ModelInfo m, ModelInfo run, String op, boolean active, boolean deep) {
        content.removeAllViews();
        OllamaClient.ModelDetails d = details;
        long now = System.currentTimeMillis();

        ModelsFlow chips = new ModelsFlow(ui.c, ui.dp(6), ui.dp(6));
        kit.addChips(chips, d, active, deep, run != null, error == null, e.isEmbeddingOnly(model));
        if (chips.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = Ui.fillW();
            lp.bottomMargin = ui.dp(10);
            content.addView(chips, lp);
        }

        if (d == null && error == null) {
            LinearLayout wait = ui.hbox();
            wait.setPadding(0, ui.dp(2), 0, ui.dp(10));
            Widgets.Meter meter = kit.meter(t.data);
            if (ui.reduceMotion) meter.setFraction(0.33f);
            else meter.setIndeterminate(true);
            wait.addView(meter, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(3)));
            TextView tx = ui.dim("Reading the model sheet from your PC…", 12.5f);
            tx.setPadding(ui.dp(10), 0, 0, 0);
            wait.addView(tx, Ui.weight(1));
            content.addView(wait, Ui.fillW());
        } else if (d == null) {
            TextView err = ui.dim("Couldn't read the rest from Ollama: " + error, 13);
            err.setTextColor(t.danger);
            err.setPadding(0, 0, 0, ui.dp(10));
            content.addView(err, Ui.fillW());
        }
        if (embedding || e.isEmbeddingOnly(model)) {
            TextView note = ui.dim("An embedding model: it turns text into vectors for search and memory apps. "
                    + "It can't chat, and Ollama loads it when an app asks.", 13);
            note.setPadding(0, 0, 0, ui.dp(6));
            content.addView(note, Ui.fillW());
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
        if (op != null) {
            row(content, "In memory", OP_LOAD.equals(op) ? "Loading…" : OP_UNLOAD.equals(op) ? "Releasing…"
                    : "Deleting…", false);
        } else if (run != null) {
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
        content.addView(ui.space(1, 10));
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
        r.setTag(ROW);
        View last = into.getChildCount() > 0 ? into.getChildAt(into.getChildCount() - 1) : null;
        if (last != null && last.getTag() == ROW) {
            View hair = new View(ui.c);
            hair.setBackgroundColor(t.hairSoft);
            into.addView(hair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, ui.dp(0.7f))));
        }
        into.addView(r, Ui.fillW());
    }

    /** Marks spec rows, so hairlines go between rows only. */
    private static final Object ROW = new Object();
}
