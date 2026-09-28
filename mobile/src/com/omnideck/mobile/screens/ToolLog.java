package com.omnideck.mobile.screens;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.core.ToolCall;
import com.omnideck.mobile.core.ToolKit;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.Panel;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The action log inside a reply that used PC tools: a small instrument
 * panel with one row per call — its icon, what it did ("Set volume → 40%"),
 * and its state (queued, asking, running, done, declined, failed) as a
 * status dot, a word and how long it took. Tapping a row shows the tool id,
 * arguments and result in mono. Rebuilt only when something changed.
 */
final class ToolLog extends LinearLayout {
    static final int CAP_DP = 30;

    private final Ui ui;
    private final Theme t;
    private final boolean still;
    private final TextView capTitle;
    private final TextView capMeta;
    private final LinearLayout rows;
    /** Indexes of the calls whose details are open. */
    private final Set<Integer> open = new HashSet<Integer>();
    private ChatMessage m;
    private String pc = "";
    private String shown = "";

    ToolLog(Context c, Ui ui, Theme t, boolean reduceMotion) {
        super(c);
        this.ui = ui;
        this.t = t;
        this.still = reduceMotion;
        setOrientation(VERTICAL);

        LinearLayout cap = ui.hbox();
        cap.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        if (t.hud) {
            Widgets.StatusDot dot = new Widgets.StatusDot(c);
            dot.setColor(t.accent);
            dot.setCoreFraction(1f);
            dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams dl = new LayoutParams(ui.dp(5), ui.dp(5));
            dl.rightMargin = ui.dp(8);
            cap.addView(dot, dl);
        }
        capTitle = ui.label("PC actions");
        cap.addView(capTitle, Ui.wrap());
        if (t.hud) {
            Widgets.Rail rail = new Widgets.Rail(c, Theme.alpha(t.accent, 0x8C));
            LayoutParams rl = new LayoutParams(ui.dp(28), ui.dp(6));
            rl.leftMargin = ui.dp(9);
            cap.addView(rail, rl);
        }
        View gap = new View(c);
        cap.addView(gap, new LayoutParams(0, 1, 1));
        capMeta = ui.readout("", 10.5f, t.dim);
        capMeta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        capMeta.setMaxWidth(ui.dp(170));
        cap.addView(capMeta, Ui.wrap());
        addView(cap, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(CAP_DP)));

        rows = ui.vbox();
        rows.setPadding(0, ui.dp(3), 0, ui.dp(3));
        addView(rows, Ui.fillW());

        Panel.Builder b = Panel.builder().fill(t.surface).edge(t.edge, Math.max(1, ui.dp(1))).radius(ui.dp(10))
                .highlight(t.panelHi).cap(ui.dp(CAP_DP), t.cap, t.hud ? Theme.alpha(t.accent, 0x29) : t.edge);
        if (t.hud) b.brackets(ui.dp(7), ui.dp(1.2f), t.bracketColor).bracketInset(ui.dp(3));
        setBackground(b.build());
        if (t.cardElevation > 0) setElevation(ui.dp(t.cardElevation));
    }

    /** The panel spans the reply's full width, however short the rows are. */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.AT_MOST) {
            widthSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec), MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    /** Shows {@code msg}'s calls; {@code pcName} labels the panel. Cheap when nothing changed. */
    void bind(ChatMessage msg, String pcName) {
        m = msg;
        pc = pcName == null ? "" : pcName;
        String sig = signature();
        if (sig.equals(shown)) return;
        shown = sig;
        int done = 0;
        for (ToolCall c : m.tools) {
            if (ToolCall.DONE.equals(c.state)) done++;
        }
        String meta = pc.length() > 0 ? pc : "";
        capMeta.setText(meta);
        capMeta.setVisibility(meta.length() > 0 ? VISIBLE : GONE);
        capTitle.setContentDescription("PC actions, " + done + " of " + m.tools.size() + " done");
        rows.removeAllViews();
        for (int i = 0; i < m.tools.size(); i++) {
            if (i > 0) {
                View hair = new View(getContext());
                hair.setBackgroundColor(t.hairSoft);
                LayoutParams hp = new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(0.7f)));
                hp.leftMargin = ui.dp(12);
                hp.rightMargin = ui.dp(12);
                rows.addView(hair, hp);
            }
            rows.addView(row(i, m.tools.get(i)), Ui.fillW());
            if (open.contains(i)) rows.addView(details(m.tools.get(i)), Ui.fillW());
        }
    }

    /** Everything a row shows, so a flush that changed nothing here costs nothing. */
    private String signature() {
        StringBuilder sb = new StringBuilder(pc).append('|').append(open);
        for (ToolCall c : m.tools) {
            sb.append('|').append(c.label).append('/').append(c.state).append('/').append(c.ms).append('/')
                    .append(c.result.length()).append('/').append(c.image.length());
        }
        return sb.toString();
    }

    private View row(final int index, ToolCall c) {
        LinearLayout r = ui.hbox();
        r.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        r.setMinimumHeight(ui.dp(38));
        r.setBackground(ui.pressableRow(0));

        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(new IconDrawable(iconFor(c.name), t.dim, t.dim, ui.dp(16)));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(icon, new LayoutParams(ui.dp(16), ui.dp(16)));

        TextView label = ui.text(c.label.length() > 0 ? c.label : c.name, 13.5f, t.ink, t.bodyMedium);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        r.addView(label, Ui.weight(1));

        int color = stateColor(c.state);
        Widgets.StatusDot dot = new Widgets.StatusDot(getContext());
        dot.setColor(color);
        dot.setFade(true);
        dot.setCoreFraction(1f);
        dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        boolean live = ToolCall.ASKING.equals(c.state) || ToolCall.RUNNING.equals(c.state);
        dot.setPulsing(live && !still);
        LayoutParams dl = new LayoutParams(ui.dp(6), ui.dp(6));
        dl.rightMargin = ui.dp(6);
        r.addView(dot, dl);

        String word = stateWord(c.state);
        TextView state = ui.text(t.hud ? word.toUpperCase(Locale.US) : word, t.hud ? 9 : 11.5f, color,
                t.hud ? t.labelFace : t.bodySemi);
        if (t.hud) state.setLetterSpacing(0.12f);
        state.setSingleLine(true);
        r.addView(state, Ui.wrap());

        if (c.isFinal() && c.ms > 0) {
            TextView ms = ui.readout(Fmt.seconds(c.ms), 10.5f, t.dim);
            ms.setPadding(ui.dp(8), 0, 0, 0);
            r.addView(ms, Ui.wrap());
        }

        boolean isOpen = open.contains(index);
        r.setContentDescription((c.label.length() > 0 ? c.label : c.name) + ", " + word
                + (isOpen ? ", details shown" : ""));
        r.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.tick(v);
                if (!open.remove(index)) open.add(index);
                if (m != null) bind(m, pc);
            }
        });
        return r;
    }

    /** Tool id, arguments and result, in mono, under the row. */
    private View details(ToolCall c) {
        LinearLayout d = ui.vbox();
        // Lined up with the row's label (12 + 16 + 10dp).
        d.setPadding(ui.dp(38), 0, ui.dp(12), ui.dp(10));
        d.addView(line("Tool", c.name, 2), Ui.fillW());
        d.addView(line("Arguments", ToolKit.prettyArgs(c.args), 8), Ui.fillW());
        String result = c.result.length() > 0 ? pretty(c.result) : c.isFinal() ? "none" : "…";
        d.addView(line("Result", result, 14), Ui.fillW());
        return d;
    }

    private View line(String key, String value, int maxLines) {
        LinearLayout b = ui.vbox();
        b.setPadding(0, ui.dp(6), 0, 0);
        TextView k = ui.label(key);
        b.addView(k, Ui.wrap());
        TextView v = ui.text(value, 12, t.dim, t.mono);
        v.setLineSpacing(0, 1.15f);
        v.setPadding(0, ui.dp(3), 0, 0);
        v.setMaxLines(maxLines);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTextIsSelectable(false);
        b.addView(v, Ui.fillW());
        return b;
    }

    /** A JSON result indented for reading; anything else as it is. */
    static String pretty(String s) {
        String x = s.trim();
        try {
            if (x.startsWith("{")) return new JSONObject(x).toString(2);
            if (x.startsWith("[")) return new JSONArray(x).toString(2);
        } catch (JSONException ignored) {
            // Not JSON after all.
        }
        return s;
    }

    private int stateColor(String state) {
        if (ToolCall.DONE.equals(state)) return t.ok;
        if (ToolCall.FAILED.equals(state)) return t.danger;
        if (ToolCall.ASKING.equals(state)) return t.warn;
        if (ToolCall.RUNNING.equals(state)) return t.id == Theme.DARK ? t.data : t.accent;
        return t.dim; // queued, declined
    }

    static String stateWord(String state) {
        if (ToolCall.ASKING.equals(state)) return "Asking";
        if (ToolCall.RUNNING.equals(state)) return "Running";
        if (ToolCall.DONE.equals(state)) return "Done";
        if (ToolCall.DECLINED.equals(state)) return "Declined";
        if (ToolCall.FAILED.equals(state)) return "Failed";
        return "Queued";
    }

    /** A line icon for a tool, from its name. */
    static int iconFor(String tool) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.US);
        if (n.equals(ToolKit.OPEN_APP) || n.contains("launch") || n.contains("_app") || n.startsWith("app")) {
            return IconDrawable.APPS;
        }
        if (n.contains("volume") || n.contains("mute") || n.contains("sound") || n.contains("audio")) {
            return IconDrawable.SPEAKER;
        }
        if (n.contains("screenshot") || n.contains("capture")) return IconDrawable.CAMERA;
        if (n.contains("clipboard")) return IconDrawable.CLIPBOARD;
        if (n.contains("lock") || n.contains("shutdown") || n.contains("shut_down") || n.contains("restart")
                || n.contains("reboot") || n.contains("sleep") || n.contains("power") || n.contains("hibernate")
                || n.contains("logoff") || n.contains("log_off") || n.contains("logout") || n.contains("sign_out")) {
            return IconDrawable.POWER;
        }
        if (n.contains("system") || n.contains("cpu") || n.contains("info") || n.contains("status")) return IconDrawable.CPU;
        if (n.contains("process") || n.contains("task")) return IconDrawable.ACTIVITY;
        if (n.contains("media") || n.contains("play") || n.contains("pause") || n.contains("track")) {
            return IconDrawable.PLAY_LINE;
        }
        if (n.contains("file") || n.contains("read") || n.contains("doc")) return IconDrawable.DOC;
        if (n.contains("url") || n.contains("browser") || n.contains("link")) return IconDrawable.LINK;
        return IconDrawable.TERMINAL;
    }
}
