package com.omnideck.mobile.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;

/**
 * View pieces shared by the model bay's cards and its spec-sheet dialog:
 * model names with a dimmed tag, state and capability chips, meters and
 * the one-line spec / residency summaries. All colors come from the theme.
 */
public final class ModelsKit {
    public final Ui ui;
    public final Theme t;

    public ModelsKit(Ui ui) {
        this.ui = ui;
        this.t = ui.t;
    }

    /** State colors: the active model needs a hue even in Dark (monochrome accent), so it uses data ink. */
    public int activeColor() {
        return t.data;
    }

    /** The deep model's color for fills and edges (amber: "engaged"). */
    public int deepColor() {
        return t.engaged;
    }

    /** The deep model's color for text and icons (Light's amber fill is too pale to read as ink). */
    public int deepInk() {
        return t.engagedInk;
    }

    public int loadedColor() {
        return t.ok;
    }

    /** "llama3.2" in ink with ":3b" dimmed, for a view set in {@code t.mono}. */
    public CharSequence name(String name, int ink) {
        String[] p = ModelsFormat.splitTag(name);
        SpannableStringBuilder sb = new SpannableStringBuilder(p[0]);
        if (p[1].length() > 0) {
            int s = sb.length();
            sb.append(p[1]);
            sb.setSpan(new ForegroundColorSpan(t.dim), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (ink != 0) sb.setSpan(new ForegroundColorSpan(ink), 0, p[0].length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * {@link #name} as an identifier span ({@link Ui#mono}), for views set in
     * another face: dialog titles, sentences that mention a model.
     */
    public CharSequence monoName(String name, int ink) {
        SpannableStringBuilder sb = new SpannableStringBuilder(ui.mono(name));
        String[] p = ModelsFormat.splitTag(name);
        if (ink != 0) sb.setSpan(new ForegroundColorSpan(ink), 0, p[0].length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (p[1].length() > 0) {
            sb.setSpan(new ForegroundColorSpan(t.dim), p[0].length(), sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /**
     * {@code text} with every occurrence of each identifier (model tags,
     * addresses) set as a {@link Ui#mono} span, in its own case — for
     * sentences like "There's no model called qwen9 in the Ollama library".
     */
    public CharSequence withIdents(String text, String... idents) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int from = 0;
        while (true) {
            int best = -1;
            String hit = null;
            for (String id : idents) {
                if (id == null || id.length() == 0) continue;
                int at = text.indexOf(id, from);
                if (at >= 0 && (best < 0 || at < best || (at == best && id.length() > hit.length()))) {
                    best = at;
                    hit = id;
                }
            }
            if (hit == null) break;
            sb.append(text, from, best).append(ui.mono(hit));
            from = best + hit.length();
        }
        sb.append(text, from, text.length());
        return sb;
    }

    /** {@code before}, the model name as an identifier, then {@code after}: "Delete " llava:7b "?". */
    public CharSequence withName(String before, String name, String after) {
        SpannableStringBuilder sb = new SpannableStringBuilder(before == null ? "" : before);
        sb.append(ui.mono(name));
        if (after != null) sb.append(after);
        return sb;
    }

    /** "llama · 3.2B · Q4_K_M · 1.9 GB · 3w ago" (details fill gaps the tags list leaves). */
    public String meta(ModelInfo m, OllamaClient.ModelDetails d, long now) {
        StringBuilder sb = new StringBuilder();
        String family = m.family.length() > 0 ? m.family : d != null ? d.family : "";
        String params = m.parameterSize.length() > 0 ? m.parameterSize : d != null ? d.parameterSize : "";
        String quant = m.quantization.length() > 0 ? m.quantization : d != null ? d.quantization : "";
        append(sb, family);
        append(sb, params);
        append(sb, quant);
        if (m.size > 0) append(sb, Fmt.bytes(m.size));
        append(sb, ModelsFormat.ago(m.modifiedAt, now));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.length() == 0) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(s);
    }

    /** A theme status chip; Cyber upper-cases it like every HUD label. */
    public TextView chip(String text, int color) {
        TextView c = ui.chip(text, color);
        c.setSingleLine(true);
        return c;
    }

    /** A status chip tinted {@code color} with its text in {@code ink} (amber fills need a darker ink). */
    public TextView chip(String text, int color, int ink) {
        TextView c = chip(text, color);
        c.setTextColor(ink);
        return c;
    }

    /**
     * Adds the state chips (ACTIVE / DEEP / LOADED) then the capability chips
     * to {@code into}. While details are on their way, two faint placeholder
     * chips stand in for the capabilities. When Ollama doesn't report them (an
     * older version, or the read failed), {@code embedding} — the Engine's
     * verdict, which falls back to the name — still earns its chip.
     */
    public void addChips(ViewGroup into, OllamaClient.ModelDetails d, boolean active, boolean deep, boolean loaded,
                         boolean detailsPending, boolean embedding) {
        if (active) into.addView(chip("Active", activeColor()));
        if (deep) into.addView(chip("Deep", deepColor(), deepInk()));
        if (loaded) into.addView(chip("Loaded", loadedColor()));
        if (d != null && !d.capabilities.isEmpty()) {
            for (String cap : d.capabilities) {
                String label = ModelsFormat.capability(cap);
                if (label != null) into.addView(chip(label, t.dim));
            }
        } else if (detailsPending) {
            into.addView(placeholderChip(58));
            into.addView(placeholderChip(44));
        } else if (embedding) {
            into.addView(chip(ModelsFormat.capability("embedding"), t.dim));
        }
    }

    /** A faint rounded bar the size of a chip (capabilities still loading). */
    public View placeholderChip(int widthDp) {
        View v = new View(ui.c);
        v.setBackground(ui.rounded(Theme.alpha(t.ink, t.isDark ? 0x1C : 0x10), t.hud ? t.hair : t.hairSoft,
                t.hud ? 4 : 6));
        v.setLayoutParams(new ViewGroup.LayoutParams(ui.dp(widthDp), ui.dp(t.hud ? 17 : 20)));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** Meter track: a faint line in the theme's ink (accent-tinted in Cyber). */
    public int meterTrack() {
        return t.hud ? Theme.alpha(t.accent, 0x24) : Theme.alpha(t.ink, t.isDark ? 0x1C : 0x14);
    }

    public Widgets.Meter meter(int bar) {
        Widgets.Meter m = new Widgets.Meter(ui.c, meterTrack(), bar);
        m.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return m;
    }

    /** GPU share of a loaded model (0..1), or -1 when unknown. */
    public static float gpuFraction(ModelInfo run) {
        if (run == null || run.size <= 0) return -1;
        return Math.max(0f, Math.min(1f, run.sizeVram / (float) run.size));
    }

    /** "1.0 GB VRAM · 50% GPU" for a loaded model. */
    public String residency(ModelInfo run) {
        if (run == null) return "";
        float g = gpuFraction(run);
        StringBuilder sb = new StringBuilder();
        if (run.sizeVram > 0) sb.append(Fmt.bytes(run.sizeVram)).append(" VRAM");
        else if (run.size > 0) sb.append(Fmt.bytes(run.size)).append(" RAM");
        if (g >= 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(g >= 0.995f ? "100% GPU" : g <= 0.005f ? "CPU only" : Math.round(g * 100) + "% GPU");
        }
        return sb.toString();
    }

    /**
     * "ctx 8,192 · stays loaded" / "ctx 8,192 · unloads in 4m". Telemetry
     * keeps its case in every theme, like the spec line above it.
     */
    public String residencyDetail(ModelInfo run, long now) {
        if (run == null) return "";
        StringBuilder sb = new StringBuilder();
        if (run.contextLength > 0) sb.append("ctx ").append(ModelsFormat.grouped(run.contextLength));
        String exp = run.expiresAt == null ? "" : run.expiresAt;
        if (exp.startsWith("2") && exp.compareTo("2200") > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("stays loaded");
        } else {
            long at = ModelsFormat.parseTime(exp);
            if (at > now) {
                if (sb.length() > 0) sb.append(" · ");
                long secs = (at - now) / 1000;
                sb.append("unloads in ").append(secs >= 3600 ? (secs / 3600) + "h" : secs >= 60 ? (secs / 60) + "m"
                        : secs + "s");
            }
        }
        return sb.toString();
    }

    /** Greys out a button (or any control) and stops it taking taps. */
    public static void disable(View v) {
        v.setEnabled(false);
        v.setClickable(false);
        v.setAlpha(0.45f);
    }

    /** A pressable background: rounded ripple over {@code content}. */
    public Drawable pressable(Drawable content, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 0x2E)), content,
                ui.rounded(0xFFFFFFFF, 0, radiusDp));
    }
}
