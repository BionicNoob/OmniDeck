package com.omnideck.mobile.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Slash commands. Names follow OMNI-DECK's own SLASH_COMMANDS list so the
 * same muscle memory works on the phone.
 */
public final class Commands {
    public static final int GROUP_CHAT = 0;
    public static final int GROUP_MODEL = 1;
    public static final int GROUP_PC = 2;
    public static final int GROUP_APP = 3;
    /** Features that live inside the OMNI-DECK web app on the PC. */
    public static final int GROUP_PC_APP_ONLY = 4;

    public static final class Cmd {
        public final String name;
        public final String usage;
        public final String desc;
        public final int group;
        public final String[] aliases;
        /** Whether the command takes an argument (suggestions insert a trailing space). */
        public final boolean takesArg;

        Cmd(String name, String usage, String desc, int group, String... aliases) {
            this.name = name;
            this.usage = usage;
            this.desc = desc;
            this.group = group;
            this.aliases = aliases;
            this.takesArg = usage.indexOf(' ') > 0;
        }

        public boolean matches(String n) {
            if (name.equals(n)) return true;
            for (String a : aliases) {
                if (a.equals(n)) return true;
            }
            return false;
        }
    }

    public static final List<Cmd> ALL;

    static {
        List<Cmd> l = new ArrayList<Cmd>();
        // Chat
        l.add(new Cmd("/help", "/help", "List every command.", GROUP_CHAT, "/?", "/commands"));
        l.add(new Cmd("/reset", "/reset", "Start a fresh conversation.", GROUP_CHAT, "/new", "/clear"));
        l.add(new Cmd("/stop", "/stop", "Stop the reply that's streaming.", GROUP_CHAT));
        l.add(new Cmd("/regen", "/regen", "Regenerate the last reply.", GROUP_CHAT, "/retry"));
        l.add(new Cmd("/system", "/system <prompt | clear>", "Set, show or clear the system prompt.", GROUP_CHAT,
                "/persona"));
        l.add(new Cmd("/summarize", "/summarize", "Summarize this chat.", GROUP_CHAT));
        l.add(new Cmd("/compact", "/compact", "Shrink the chat to free up context.", GROUP_CHAT));
        l.add(new Cmd("/history", "/history <name>", "Open a saved chat (no name = list).", GROUP_CHAT,
                "/chats"));
        l.add(new Cmd("/export", "/export", "Share this chat as text.", GROUP_CHAT));
        l.add(new Cmd("/rename", "/rename <title>", "Rename this chat.", GROUP_CHAT));
        l.add(new Cmd("/incognito", "/incognito <on|off>", "Pause saving chats on this phone.", GROUP_CHAT));
        l.add(new Cmd("/remember", "/remember <fact>", "Save a fact the AI should always know.", GROUP_CHAT));
        l.add(new Cmd("/facts", "/facts", "List remembered facts.", GROUP_CHAT));
        l.add(new Cmd("/forget", "/forget <text | #>", "Delete a remembered fact.", GROUP_CHAT));
        // Model control
        l.add(new Cmd("/model", "/model <name>", "Switch model (no name = picker).", GROUP_MODEL));
        l.add(new Cmd("/models", "/models", "List models installed on the PC.", GROUP_MODEL, "/list"));
        l.add(new Cmd("/ps", "/ps", "Show which models are loaded in memory.", GROUP_MODEL, "/loaded"));
        l.add(new Cmd("/warm", "/warm", "Preload the model so the next reply is instant.", GROUP_MODEL,
                "/load"));
        l.add(new Cmd("/unload", "/unload <name>", "Free the model's memory on the PC.", GROUP_MODEL,
                "/release"));
        l.add(new Cmd("/pull", "/pull <name | stop>", "Download a model onto the PC.", GROUP_MODEL));
        l.add(new Cmd("/deep", "/deep <model>", "Deep-thinking mode (optionally set the deep model).",
                GROUP_MODEL));
        l.add(new Cmd("/fast", "/fast", "Fast mode: no thinking, main model.", GROUP_MODEL));
        l.add(new Cmd("/auto", "/auto", "Pick fast or deep automatically.", GROUP_MODEL));
        l.add(new Cmd("/bench", "/bench", "Benchmark the model's speed.", GROUP_MODEL));
        // PC control (LaunchBridge)
        l.add(new Cmd("/open", "/open <app>", "Open an app on the PC.", GROUP_PC));
        l.add(new Cmd("/vol", "/vol <0-100>", "Read or set the PC volume.", GROUP_PC, "/volume"));
        l.add(new Cmd("/sys", "/sys", "Show the PC's CPU, RAM, disk and battery.", GROUP_PC));
        l.add(new Cmd("/shot", "/shot [save]", "Screenshot the PC into the chat.", GROUP_PC, "/screenshot"));
        l.add(new Cmd("/pcclip", "/pcclip", "Paste the PC's clipboard into the composer.", GROUP_PC));
        l.add(new Cmd("/wol", "/wol", "Wake the sleeping PC over the network (Wake-on-LAN).", GROUP_PC, "/wakepc"));
        l.add(new Cmd("/lock", "/lock", "Lock the PC's screen.", GROUP_PC, "/lockpc"));
        l.add(new Cmd("/pair", "/pair", "Pair with the PC bridge (LaunchBridge).", GROUP_PC));
        l.add(new Cmd("/desk", "/desk", "PC bridge / desktop control status.", GROUP_PC));
        // App
        l.add(new Cmd("/server", "/server <ip[:port] | auto>", "Show or set the AI's address.", GROUP_APP,
                "/host"));
        l.add(new Cmd("/scan", "/scan", "Search the network for the AI again.", GROUP_APP, "/rescan"));
        l.add(new Cmd("/appearance", "/appearance <cyber|light|dark|system>", "Switch the theme.", GROUP_APP,
                "/theme"));
        l.add(new Cmd("/voice", "/voice", "Speak a message (voice input).", GROUP_APP, "/talk"));
        l.add(new Cmd("/mute", "/mute", "Toggle reading replies aloud.", GROUP_APP, "/speak"));
        l.add(new Cmd("/timer", "/timer <5m|90s|1h> <message>", "Start a timer (no args = list, cancel).",
                GROUP_APP));
        l.add(new Cmd("/clip", "/clip", "Paste the phone's clipboard into the composer.", GROUP_APP));
        l.add(new Cmd("/settings", "/settings", "Open settings.", GROUP_APP, "/config"));
        l.add(new Cmd("/debug", "/debug", "Connection diagnostics.", GROUP_APP, "/diag"));
        // Only inside the web app on the PC.
        l.add(new Cmd("/web", "/web <query>", "Web search (runs in OMNI-DECK on the PC).", GROUP_PC_APP_ONLY));
        l.add(new Cmd("/investigate", "/investigate <target>", "OSINT lookup (PC app only).", GROUP_PC_APP_ONLY,
                "/osint"));
        l.add(new Cmd("/offline", "/offline <enable|disable>", "Airgap mode (PC app only).", GROUP_PC_APP_ONLY));
        l.add(new Cmd("/task", "/task <text>", "Task manager (PC app only).", GROUP_PC_APP_ONLY));
        l.add(new Cmd("/backup", "/backup", "Backup and restore (PC app only).", GROUP_PC_APP_ONLY));
        l.add(new Cmd("/nuke", "/nuke", "Wipe PC app data (PC app only).", GROUP_PC_APP_ONLY));
        l.add(new Cmd("/wake", "/wake [on|off]", "Wake word (PC app only).", GROUP_PC_APP_ONLY));
        ALL = Collections.unmodifiableList(l);
    }

    private Commands() {}

    public static final class Parsed {
        /** Lower-cased, including the slash, e.g. "/model". */
        public final String name;
        /** Everything after the name, trimmed ("" when none). */
        public final String arg;
        /** The registered command, or null when unknown. */
        public final Cmd cmd;

        Parsed(String name, String arg, Cmd cmd) {
            this.name = name;
            this.arg = arg;
            this.cmd = cmd;
        }
    }

    /** Parses "/cmd args"; returns null for normal text (or a lone "/"). */
    public static Parsed parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() < 2 || t.charAt(0) != '/' || Character.isWhitespace(t.charAt(1))) return null;
        // "//text" escapes a message that starts with a slash.
        if (t.charAt(1) == '/') return null;
        int sp = 0;
        while (sp < t.length() && !Character.isWhitespace(t.charAt(sp))) sp++;
        String name = t.substring(0, sp).toLowerCase(Locale.US);
        String arg = sp < t.length() ? t.substring(sp).trim() : "";
        return new Parsed(name, arg, find(name));
    }

    public static Cmd find(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.US);
        for (Cmd c : ALL) {
            if (c.matches(n)) return c;
        }
        return null;
    }

    /**
     * Suggestions for what's typed in the composer: prefix matches first,
     * then substring matches. Empty once a space follows the command name.
     */
    public static List<Cmd> suggest(String typed, int max) {
        List<Cmd> out = new ArrayList<Cmd>();
        if (typed == null || !typed.startsWith("/") || typed.startsWith("//")) return out;
        for (int i = 0; i < typed.length(); i++) {
            if (Character.isWhitespace(typed.charAt(i))) return out;
        }
        String q = typed.toLowerCase(Locale.US);
        for (Cmd c : ALL) {
            if (out.size() >= max) break;
            if (c.group == GROUP_PC_APP_ONLY) continue;
            if (c.name.startsWith(q)) out.add(c);
        }
        // Looser matches only once there's enough typed to mean something:
        // names containing 2+ letters, descriptions containing 3+.
        String body = q.substring(1);
        if (body.length() >= 2) {
            for (Cmd c : ALL) {
                if (out.size() >= max) break;
                if (c.group == GROUP_PC_APP_ONLY || out.contains(c)) continue;
                boolean alias = false;
                for (String a : c.aliases) alias |= a.startsWith(q);
                if (alias || c.name.contains(body)
                        || (body.length() >= 3 && c.desc.toLowerCase(Locale.US).contains(body))) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Text for /help. */
    public static String helpText() {
        String[] titles = {"CHAT", "MODEL CONTROL", "PC CONTROL (needs LaunchBridge on the network)", "APP",
                "PC APP ONLY (run these in OMNI-DECK on the PC)"};
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < titles.length; g++) {
            sb.append("**").append(titles[g]).append("**\n");
            for (Cmd c : ALL) {
                if (c.group != g) continue;
                sb.append("`").append(c.usage).append("` — ").append(c.desc).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Start a message with `//` to send text that begins with a slash.");
        return sb.toString();
    }
}
