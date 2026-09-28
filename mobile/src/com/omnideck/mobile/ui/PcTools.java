package com.omnideck.mobile.ui;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omnideck.mobile.core.BridgeTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The PC tab's view of LaunchBridge's desktop tools: the order and names of
 * the tool runner's groups, the runner's rows (what a tool does, its id,
 * whether it asks first), and which tools put the whole PC to sleep,
 * restart it or shut it down (for the power strip — a tool that restarts
 * Explorer or a service must never pass for "Restart PC").
 */
public final class PcTools {
    /** Runner groups, top to bottom. */
    public static final String[] ORDER = {BridgeTool.POWER, BridgeTool.MEDIA, BridgeTool.INFO, BridgeTool.OTHER};

    /** Words that make a power verb about the whole machine ("restart_pc", "system_sleep"). */
    private static final String[] MACHINE = {"pc", "computer", "system", "machine", "windows", "workstation", "os",
            "host", "device"};

    private final Ui ui;
    private final Theme t;
    private final PcKit kit;

    public PcTools(Ui ui, PcKit kit) {
        this.ui = ui;
        this.t = ui.t;
        this.kit = kit;
    }

    /** Words that are acronyms in a humanized id ("Restart pc" → "Restart PC", "Open url" → "Open URL"). */
    private static final String[] ACRONYMS = {"pc", "url", "uri", "cpu", "gpu", "ram", "os", "id", "ip", "mac",
            "usb", "dns", "api", "ui", "vpn", "ssh", "pdf", "json", "html", "hdmi", "led", "rgb", "tts", "ocr"};

    /** A humanized tool or argument name with its acronyms in capitals. */
    public static String words(String label) {
        if (label == null || label.length() == 0) return label == null ? "" : label;
        String[] parts = label.split(" ", -1);
        StringBuilder sb = new StringBuilder(label.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            String p = parts[i];
            String low = p.toLowerCase(Locale.US);
            boolean acro = false;
            for (String a : ACRONYMS) acro |= a.equals(low);
            sb.append(acro ? p.toUpperCase(Locale.US) : p);
        }
        return sb.toString();
    }

    public static String groupTitle(String category) {
        if (BridgeTool.POWER.equals(category)) return "Power";
        if (BridgeTool.MEDIA.equals(category)) return "Media & sound";
        if (BridgeTool.INFO.equals(category)) return "Information";
        return "Other tools";
    }

    public static int groupIcon(String category) {
        if (BridgeTool.POWER.equals(category)) return IconDrawable.POWER;
        if (BridgeTool.MEDIA.equals(category)) return IconDrawable.SPEAKER;
        if (BridgeTool.INFO.equals(category)) return IconDrawable.INFO;
        return IconDrawable.TERMINAL;
    }

    /** The tools of one category, in the bridge's order. */
    public static List<BridgeTool> inCategory(List<BridgeTool> tools, String category) {
        List<BridgeTool> out = new ArrayList<BridgeTool>();
        for (BridgeTool b : tools) {
            if (category.equals(b.category())) out.add(b);
        }
        return out;
    }

    public static boolean has(List<BridgeTool> tools, String name) {
        return tools != null && BridgeTool.find(tools, name) != null;
    }

    /** The tool that puts the PC to sleep (sleep, suspend, hibernate), or null. */
    public static BridgeTool sleepTool(List<BridgeTool> tools) {
        return power(tools, new String[]{"sleep", "sleep_pc", "suspend", "suspend_pc", "hibernate", "hibernate_pc",
                "sleep_computer", "system_sleep"}, new String[]{"sleep", "suspend", "hibernate"});
    }

    /** The tool that restarts the PC (restart, reboot), or null. */
    public static BridgeTool restartTool(List<BridgeTool> tools) {
        return power(tools, new String[]{"restart", "restart_pc", "reboot", "reboot_pc", "restart_computer",
                "system_restart"}, new String[]{"restart", "reboot"});
    }

    /** The tool that shuts the PC down, or null. */
    public static BridgeTool shutdownTool(List<BridgeTool> tools) {
        return power(tools, new String[]{"shutdown", "shut_down", "shutdown_pc", "shut_down_pc", "power_off",
                "poweroff", "shutdown_computer", "system_shutdown", "turn_off_pc"},
                new String[]{"shutdown", "shut_down", "power_off", "poweroff"});
    }

    /**
     * An exact well-known name, else a name with the verb and a word for the
     * whole machine ("reboot_computer") — never "cancel_shutdown",
     * "get_sleep_timeout" or "restart_explorer".
     */
    static BridgeTool power(List<BridgeTool> tools, String[] exact, String[] verbs) {
        if (tools == null) return null;
        BridgeTool hit = BridgeTool.find(tools, exact);
        if (hit != null) return hit;
        for (BridgeTool c : tools) {
            String n = c.name.toLowerCase(Locale.US);
            if (n.contains("cancel") || n.contains("abort") || n.startsWith("get_") || n.startsWith("is_")
                    || n.startsWith("list_") || n.startsWith("set_")) {
                continue;
            }
            boolean verb = false;
            for (String v : verbs) verb |= n.contains(v);
            if (!verb) continue;
            for (String part : n.split("[_\\-.\\s]+")) {
                for (String m : MACHINE) {
                    if (part.equals(m)) return c;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Runner rows
    // ------------------------------------------------------------------

    /** A group heading: icon, micro-caps title, a mono count at the right. */
    public View groupHeader(String category, int count) {
        LinearLayout h = ui.hbox();
        h.setMinimumHeight(ui.dp(28));
        h.addView(kit.icon(groupIcon(category), t.hud ? t.accent : t.label, 14),
                new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)));
        TextView l = ui.label(groupTitle(category));
        l.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        h.addView(l, Ui.weight(1));
        h.addView(ui.readout(String.valueOf(count), 11, t.dim), Ui.wrap());
        return h;
    }

    /**
     * One tool: its name in words, its id in mono and what it does; a run
     * glyph at the right (a warning mark for tools that ask first).
     */
    public View row(BridgeTool tool, View.OnClickListener l) {
        LinearLayout row = ui.hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        row.setMinimumHeight(ui.dp(52));
        row.setBackground(ui.pressableRow(0));
        LinearLayout texts = ui.vbox();
        TextView title = ui.text(words(tool.label()), 14.5f, t.ink, t.bodyMedium);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title, Ui.fillW());
        SpannableStringBuilder sub = new SpannableStringBuilder(ui.mono(tool.name));
        String summary = tool.summary();
        if (summary.length() > 0) sub.append("  ·  ").append(summary);
        TextView s = ui.text(sub, 12, t.dim, t.body);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setLineSpacing(0, 1.15f);
        s.setPadding(0, ui.dp(3), 0, 0);
        texts.addView(s, Ui.fillW());
        texts.setPadding(0, 0, ui.dp(10), 0);
        row.addView(texts, Ui.weight(1));
        boolean asks = tool.destructive();
        row.addView(kit.icon(asks ? IconDrawable.WARN : IconDrawable.PLAY, asks ? t.warn : t.hud ? t.accent : t.data,
                asks ? 16 : 14), new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        row.setContentDescription("Run " + tool.name);
        row.setOnClickListener(l);
        return row;
    }
}
