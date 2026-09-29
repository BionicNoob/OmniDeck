package com.omnideck.mobile;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omnideck.mobile.core.Vitals;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.Sheet;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;

import java.util.Locale;

/**
 * The one-time PC setup, step by step, with copyable commands for Windows,
 * macOS and Linux:
 * <ul>
 * <li>{@link #AI}: let Ollama answer the network (OLLAMA_HOST) and open the firewall.</li>
 * <li>{@link #BRIDGE}: LaunchBridge only listens on 127.0.0.1:8765, so a
 * LAN port ({@link #FORWARD_PORT}) is forwarded to it, and the phone uses that port.</li>
 * </ul>
 * Opened from the Command tab's "AI not found" card, the PC tab, Settings and /setup.
 */
public final class SetupGuide {
    public static final int AI = 0;
    public static final int BRIDGE = 1;
    /** The LAN port forwarded to LaunchBridge's 127.0.0.1:8765. */
    public static final int FORWARD_PORT = 8766;
    static final String[] OS = {"Windows", "macOS", "Linux"};

    private final MainActivity a;
    private final Ui ui;
    private final Theme t;
    private final int topic;
    private int os;
    private LinearLayout steps;
    private final TextView[] tabs = new TextView[OS.length];

    private SetupGuide(MainActivity a, int topic) {
        this.a = a;
        this.ui = a.ui();
        this.t = a.theme();
        this.topic = topic;
        this.os = guessOs(a.engine().lastVitals());
    }

    /** Shows the guide for {@code topic} ({@link #AI} or {@link #BRIDGE}). */
    public static void show(MainActivity a, int topic) {
        new SetupGuide(a, topic).open();
    }

    /** The PC's OS from its last system info (Windows when unknown: OMNI-DECK's home). */
    static int guessOs(Vitals v) {
        String s = v == null ? "" : v.os.toLowerCase(Locale.US);
        if (s.contains("mac") || s.contains("darwin")) return 1;
        if (s.contains("linux") || s.contains("ubuntu") || s.contains("debian") || s.contains("fedora")) return 2;
        return 0;
    }

    private void open() {
        final Engine e = a.engine();
        Sheet s = ui.sheet("PC setup · one time", topic == AI ? "Let your phone reach Ollama"
                : "Let your phone reach LaunchBridge");
        s.message(topic == AI
                ? "Ollama only answers the PC itself until it's told to listen on the network. Run these on the PC "
                + "that runs Ollama:"
                : "LaunchBridge listens only on the PC itself (127.0.0.1:8765). Forward a network port to it, "
                + "then use that port here:");
        LinearLayout osRow = ui.hbox();
        for (int i = 0; i < OS.length; i++) {
            final int which = i;
            tabs[i] = ui.actionChip(OS[i], false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    os = which;
                    render();
                }
            });
            tabs[i].setContentDescription("Setup for " + OS[i]);
            osRow.addView(tabs[i], ui.margins(Ui.wrap(), 0, 0, 8, 0));
        }
        s.body.addView(osRow, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        steps = ui.vbox();
        s.body.addView(steps, Ui.fillW());
        render();
        TextView note = ui.dim(topic == AI
                ? "Only do this on a network you trust — Ollama has no password."
                : "The bridge still needs its pairing token, so the port alone doesn't let anyone in.", 12);
        s.body.addView(note, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        s.negative("Close", null);
        if (topic == AI) {
            s.positive("Scan again", Ui.PRIMARY, new Runnable() {
                @Override
                public void run() {
                    e.discover(true);
                }
            });
        } else {
            s.positive("Pair now", Ui.PRIMARY, new Runnable() {
                @Override
                public void run() {
                    e.bridgePair();
                }
            });
        }
        s.show();
    }

    private void render() {
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == os;
            tabs[i].setTextColor(on ? t.onAccent : t.ink);
            tabs[i].setBackground(on ? ui.rounded(t.accent, 0, 8) : ui.rounded(t.chip, t.edge, 8));
            tabs[i].setSelected(on);
        }
        steps.removeAllViews();
        int port = a.engine().scanPort();
        if (topic == AI) {
            if (os == 0) {
                step(1, "Allow network connections (Command Prompt or PowerShell):", "setx OLLAMA_HOST 0.0.0.0");
                step(2, "Quit Ollama from its tray icon, then start it again.", null);
                step(3, "Open the firewall for this network (Command Prompt as administrator):",
                        "netsh advfirewall firewall add rule name=\"Ollama LAN\" dir=in action=allow protocol=TCP "
                                + "localport=" + port + " profile=private");
            } else if (os == 1) {
                step(1, "Allow network connections (Terminal):", "launchctl setenv OLLAMA_HOST \"0.0.0.0\"");
                step(2, "Quit Ollama from the menu bar, then open it again.", null);
                step(3, "If the macOS firewall is on: System Settings › Network › Firewall › Options, and allow "
                        + "Ollama.", null);
            } else {
                step(1, "Open Ollama's service settings (Terminal):", "sudo systemctl edit ollama");
                step(2, "Add these lines, save and close:", "[Service]\nEnvironment=\"OLLAMA_HOST=0.0.0.0\"");
                step(3, "Restart it:", "sudo systemctl restart ollama");
                step(4, "If a firewall (ufw) is on:", "sudo ufw allow " + port + "/tcp");
            }
        } else {
            if (os == 0) {
                step(1, "Forward port " + FORWARD_PORT + " to LaunchBridge (Command Prompt as administrator):",
                        "netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=" + FORWARD_PORT
                                + " connectaddress=127.0.0.1 connectport=8765");
                step(2, "Open the firewall for it:", "netsh advfirewall firewall add rule name=\"LaunchBridge LAN\" "
                        + "dir=in action=allow protocol=TCP localport=" + FORWARD_PORT + " profile=private");
            } else {
                step(1, "Forward port " + FORWARD_PORT + " to LaunchBridge (keep it running, e.g. in a login item):",
                        "socat TCP-LISTEN:" + FORWARD_PORT + ",fork,reuseaddr TCP:127.0.0.1:8765");
                if (os == 2) step(2, "If a firewall (ufw) is on:", "sudo ufw allow " + FORWARD_PORT + "/tcp");
            }
            usePortRow(os == 0 || os == 2 ? 3 : 2);
        }
    }

    /** "Use port 8766 on this phone" — one tap instead of typing it in Settings. */
    private void usePortRow(int n) {
        final Engine e = a.engine();
        boolean using = e.settings.bridgePort() == FORWARD_PORT;
        step(n, using ? "This phone already uses port " + FORWARD_PORT + "."
                : "Then use port " + FORWARD_PORT + " on this phone:", null);
        if (using) return;
        TextView b = ui.button("Use port " + FORWARD_PORT, IconDrawable.LINK, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.settings.setBridgePort(FORWARD_PORT);
                e.log("info", "PC bridge port · " + FORWARD_PORT);
                ui.toast("The PC bridge is now reached on port " + FORWARD_PORT + ".");
                render();
            }
        });
        steps.addView(b, ui.margins(Ui.wrap(), 28, 8, 0, 0));
    }

    /** A numbered step and, optionally, a copyable command under it. */
    private void step(int n, String text, final String command) {
        LinearLayout row = ui.hbox();
        row.setGravity(Gravity.TOP);
        TextView num = ui.text(String.format(Locale.US, "%02d", n), 12, t.hud ? t.accent : t.label, t.mono);
        num.setPadding(0, ui.dp(2), ui.dp(10), 0);
        row.addView(num, Ui.wrap());
        TextView body = ui.body(text);
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        row.addView(body, Ui.weight(1));
        steps.addView(row, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        if (command == null) return;
        LinearLayout code = ui.hbox();
        code.setGravity(Gravity.CENTER_VERTICAL);
        code.setPadding(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6));
        code.setBackground(ui.rounded(t.codeBg, t.edge, 8));
        TextView tv = ui.text(command, 12.5f, t.hud ? t.codeText : t.inlineCodeText, t.mono);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(0, 1.15f);
        code.addView(tv, Ui.weight(1));
        ImageView copy = ui.iconButton(IconDrawable.COPY, "Copy command", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.copy("Command", command);
            }
        });
        code.addView(copy, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
        steps.addView(code, ui.margins(Ui.fillW(), 28, 8, 0, 0));
    }
}
