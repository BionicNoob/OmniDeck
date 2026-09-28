package com.omnideck.mobile;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;

import com.omnideck.mobile.core.Commands;
import com.omnideck.mobile.core.Conversation;
import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.core.HostPort;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.ui.Ui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs slash commands from anywhere in the app (the chat composer, the
 * command center's quick actions). Same command names as OMNI-DECK on the PC.
 */
public final class Commander {
    private final MainActivity act;
    private final Engine e;
    private final Ui ui;

    Commander(MainActivity act) {
        this.act = act;
        this.e = act.engine();
        this.ui = act.ui();
    }

    /** Parses and runs "/cmd args"; returns false when the text isn't a command. */
    public boolean run(String text) {
        Commands.Parsed p = Commands.parse(text);
        if (p == null) return false;
        run(p);
        return true;
    }

    /** Runs a parsed slash command. */
    public void run(Commands.Parsed pc) {
        if (pc == null) return;
        if (pc.cmd == null) {
            e.notice("Unknown command `" + pc.name + "`. Type `/help` for the list.", "warn");
            return;
        }
        String a = pc.arg;
        String name = pc.cmd.name;
        if (pc.cmd.group == Commands.GROUP_PC_APP_ONLY) {
            e.notice("`" + name + "` runs inside OMNI-DECK on the PC (it uses the PC app's own tools), so the "
                    + "phone can't run it yet.", "info");
            return;
        }
        if ("/help".equals(name)) {
            e.notice(Commands.helpText(), "info");
        } else if ("/reset".equals(name)) {
            e.newChat();
            ui.toast("New chat");
        } else if ("/stop".equals(name)) {
            if (e.isBusy()) e.stop();
            else ui.toast("Nothing is streaming.");
        } else if ("/regen".equals(name)) {
            e.regenerate();
        } else if ("/system".equals(name)) {
            if (a.length() == 0) {
                String sp = e.settings.systemPrompt();
                e.notice(sp.length() == 0 ? "No system prompt set. Set one with `/system <prompt>`."
                        : "**System prompt**\n" + sp + "\n\n`/system clear` removes it.", "info");
            } else if (a.equalsIgnoreCase("clear") || a.equalsIgnoreCase("off") || a.equalsIgnoreCase("none")) {
                e.settings.setSystemPrompt("");
                e.notice("System prompt cleared.", "ok");
            } else {
                e.settings.setSystemPrompt(a);
                e.notice("System prompt set. It applies to every message from now on.", "ok");
            }
        } else if ("/summarize".equals(name)) {
            e.summarize();
        } else if ("/compact".equals(name)) {
            e.compact();
        } else if ("/history".equals(name)) {
            if (a.length() == 0) {
                act.select(MainActivity.TAB_COMMS, true);
                act.comms().openHistory();
            } else {
                openChatByName(a);
            }
        } else if ("/export".equals(name)) {
            exportChat();
        } else if ("/incognito".equals(name)) {
            Boolean on = parseOnOff(a);
            if (on == null) {
                e.notice("Incognito is **" + (e.settings.incognito() ? "on" : "off")
                        + "**. Use `/incognito on` or `/incognito off`.", "info");
            } else {
                e.settings.setIncognito(on);
                if (!on) e.save();
                e.notice(on ? "Incognito on — chats aren't saved on this phone until you turn it off."
                        : "Incognito off — chats are saved again.", "ok");
            }
        } else if ("/remember".equals(name)) {
            if (a.length() == 0) e.notice("Usage: `/remember <fact>`", "info");
            else e.remember(a);
        } else if ("/facts".equals(name)) {
            e.listFacts();
        } else if ("/forget".equals(name)) {
            if (a.length() == 0) e.notice("Usage: `/forget <number or text>` — see `/facts`.", "info");
            else e.forget(a);
        } else if ("/model".equals(name)) {
            if (a.length() == 0) act.select(MainActivity.TAB_MODELS, true);
            else switchModel(a);
        } else if ("/models".equals(name)) {
            e.listModels();
        } else if ("/ps".equals(name)) {
            e.listRunning();
        } else if ("/warm".equals(name)) {
            e.warm();
        } else if ("/unload".equals(name)) {
            e.unload(a);
        } else if ("/pull".equals(name)) {
            e.pull(a);
        } else if ("/deep".equals(name)) {
            if (a.length() > 0) {
                String m = e.resolveInstalled(a);
                if (m == null) {
                    e.notice("No installed model matches “" + a + "”. See `/models`.", "warn");
                    return;
                }
                e.setDeepModel(m);
            }
            e.setMode(Settings.MODE_DEEP);
            String dm = e.resolveInstalled(e.settings.deepModel());
            String target = dm != null ? dm : e.currentModel();
            Boolean t = e.supportsThinking(target);
            e.notice("**Deep mode** — replies use **" + target + "**"
                    + (Boolean.FALSE.equals(t) ? " (this model can't think step by step; set a thinking model with "
                    + "`/deep <model>`)." : " with thinking on."), "ok");
        } else if ("/fast".equals(name)) {
            e.setMode(Settings.MODE_FAST);
            e.notice("**Fast mode** — thinking off, main model (" + e.currentModel() + ").", "ok");
        } else if ("/auto".equals(name)) {
            e.setMode(Settings.MODE_AUTO);
            String dm = e.settings.deepModel();
            e.notice("**Auto mode** — fast by default" + (dm.length() > 0 ? "; hard questions go to **" + dm
                    + "** with thinking on." : ". Set a deep model with `/deep <model>` to route hard questions to it."),
                    "ok");
        } else if ("/bench".equals(name)) {
            e.bench();
        } else if ("/open".equals(name)) {
            if (a.length() == 0) e.notice("Usage: `/open <app>` — e.g. `/open spotify`.", "info");
            else openApp(a);
        } else if ("/vol".equals(name)) {
            if (a.length() == 0) {
                e.bridgeTool("get_volume", null, "PC volume");
            } else {
                int level;
                try {
                    level = Integer.parseInt(a.replace("%", "").trim());
                } catch (NumberFormatException e) {
                    level = -1;
                }
                if (level < 0 || level > 100) {
                    e.notice("Usage: `/vol <0-100>` — or just `/vol` to read it.", "info");
                    return;
                }
                JSONObject args = new JSONObject();
                try {
                    args.put("level", level);
                } catch (JSONException ignored) {
                }
                e.bridgeTool("set_volume", args, "PC volume");
            }
        } else if ("/sys".equals(name)) {
            e.bridgeTool("get_system_info", null, "PC system");
        } else if ("/shot".equals(name)) {
            JSONObject args = new JSONObject();
            try {
                args.put("save", a.toLowerCase(Locale.US).contains("save"));
            } catch (JSONException ignored) {
            }
            e.bridgeTool("screenshot", args, "Screenshot");
        } else if ("/pcclip".equals(name)) {
            e.bridgeTool("get_clipboard", null, "PC clipboard");
        } else if ("/pair".equals(name)) {
            e.bridgePair();
        } else if ("/desk".equals(name)) {
            e.bridgeStatus();
        } else if ("/server".equals(name)) {
            if (a.length() == 0) {
                act.showConnection();
            } else if (a.equalsIgnoreCase("auto")) {
                e.setServer("");
                e.notice("Auto-detect on — searching the network for your AI.", "info");
            } else if (HostPort.parse(a, OllamaClient.DEFAULT_PORT) == null) {
                e.notice("That doesn't look like an address. Try `/server 192.168.1.20` or `/server 192.168.1.20:11434`.",
                        "warn");
            } else {
                e.setServer(a);
                e.notice("AI address set to `" + a + "` — connecting…", "info");
            }
        } else if ("/scan".equals(name)) {
            e.discover(true);
            ui.toast("Scanning the network…");
        } else if ("/appearance".equals(name)) {
            String t = a.toLowerCase(Locale.US).trim();
            String v = t.startsWith("cyber") || t.equals("neon") || t.equals("hud") ? Settings.THEME_CYBER
                    : t.startsWith("light") || t.equals("modern") ? Settings.THEME_LIGHT
                    : t.startsWith("dark") ? Settings.THEME_DARK
                    : t.equals("auto") || t.equals("system") ? Settings.THEME_SYSTEM : null;
            if (v == null) {
                e.notice("Usage: `/appearance cyber`, `light`, `dark` or `system` (follows the phone).", "info");
                return;
            }
            act.applyTheme(v);
        } else if ("/voice".equals(name)) {
            act.startVoice("Speak to OMNI", new MainActivity.TextResult() {
                @Override
                public void onText(String text) {
                    act.select(MainActivity.TAB_COMMS, true);
                    act.comms().submitText(text);
                }
            });
        } else if ("/mute".equals(name)) {
            Boolean on = parseOnOff(a);
            boolean speak = on != null ? !on : !e.settings.readAloud();
            if (a.equalsIgnoreCase("on")) speak = false;
            e.setReadAloud(speak);
            ui.toast(speak ? "Reading replies aloud" : "Read-aloud off");
        } else if ("/rename".equals(name)) {
            if (a.length() == 0) {
                ui.prompt("Rename chat", "Chat title", e.conversation().title, android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, new Ui.TextResult() {
                    @Override
                    public void onText(String text) {
                        if (text.trim().length() > 0) e.renameChat(text.trim());
                    }
                });
            } else {
                e.renameChat(a);
                ui.toast("Chat renamed");
            }
        } else if ("/timer".equals(name)) {
            e.timer(a);
        } else if ("/clip".equals(name)) {
            pastePhoneClipboard();
        } else if ("/settings".equals(name)) {
            act.openSettings();
        } else if ("/debug".equals(name)) {
            e.diagnostics(act.appVersion());
        } else {
            e.notice("`" + name + "` isn't available here.", "warn");
        }
    }

    private static Boolean parseOnOff(String a) {
        String t = a.trim().toLowerCase(Locale.US);
        if (t.equals("on") || t.equals("enable") || t.equals("enabled") || t.equals("true") || t.equals("yes")) {
            return Boolean.TRUE;
        }
        if (t.equals("off") || t.equals("disable") || t.equals("disabled") || t.equals("false") || t.equals("no")) {
            return Boolean.FALSE;
        }
        return null;
    }

    public void switchModel(String a) {
        String m = e.resolveInstalled(a);
        if (m == null) {
            List<ModelInfo> ms = e.models();
            StringBuilder sb = new StringBuilder();
            for (ModelInfo mi : ms) {
                if (sb.length() > 0) sb.append(", ");
                sb.append('`').append(mi.name).append('`');
            }
            e.notice("No single installed model matches “" + a + "”."
                    + (ms.isEmpty() ? " (not connected, or no models installed)" : " Installed: " + sb), "warn");
            return;
        }
        e.setModel(m);
        e.notice("Model → **" + m + "**", "ok");
    }

    public void pastePhoneClipboard() {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            ui.toast("The clipboard is empty.");
            return;
        }
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
        if (t == null || t.length() == 0) ui.toast("The clipboard is empty.");
        else act.onInsertText(t.toString());
    }

    public void exportChat() {
        Conversation c = e.conversation();
        if (c.isEmpty()) {
            ui.toast("Nothing to export yet.");
            return;
        }
        act.share(c.title.length() > 0 ? c.title : "OMNI-DECK chat", c.toMarkdown());
    }

    public void cycleMode() {
        String m = e.mode();
        String next = Settings.MODE_AUTO.equals(m) ? Settings.MODE_FAST
                : Settings.MODE_FAST.equals(m) ? Settings.MODE_DEEP : Settings.MODE_AUTO;
        e.setMode(next);
        ui.toast(Settings.MODE_DEEP.equals(next) ? "Deep: thinking on" : Settings.MODE_FAST.equals(next)
                ? "Fast: no thinking" : "Auto: fast, deep model for hard questions");
    }

    public void openApp(final String query) {
        e.bridgePreviewLaunch(query, new Engine.Callback<JSONObject>() {
            @Override
            public void done(JSONObject r, String error) {
                if (error != null) {
                    e.notice("Couldn't open “" + query + "”: " + error, "error");
                    return;
                }
                if (r.optBoolean("needs_choice", false)) {
                    JSONArray cands = r.optJSONArray("candidates");
                    if (cands == null || cands.length() == 0) {
                        e.notice("No app on the PC matches “" + query + "”.", "warn");
                        return;
                    }
                    List<Ui.Row> rows = new ArrayList<Ui.Row>();
                    for (int i = 0; i < cands.length(); i++) {
                        final JSONObject c = cands.optJSONObject(i);
                        if (c == null) continue;
                        final String nm = OllamaClient.str(c, "name");
                        rows.add(new Ui.Row(nm, OllamaClient.str(c, "path"), false, new Runnable() {
                            @Override
                            public void run() {
                                e.bridgeLaunch(OllamaClient.str(c, "id"), nm);
                            }
                        }, null));
                    }
                    ui.pick("Which app?", rows, null, null);
                    return;
                }
                final JSONObject target = r.optJSONObject("would_launch");
                if (target == null) {
                    JSONObject app = r.optJSONObject("app");
                    e.notice(app != null ? "Opened **" + OllamaClient.str(app, "name") + "** on the PC."
                            : "The bridge didn't say what it would open.", app != null ? "ok" : "warn");
                    return;
                }
                final String nm = OllamaClient.str(target, "name");
                String path = OllamaClient.str(target, "path");
                new AlertDialog.Builder(act)
                        .setTitle("Open " + nm + " on the PC?")
                        .setMessage(path.length() > 0 ? path : "LaunchBridge will start it on your PC.")
                        .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                e.bridgeLaunch(OllamaClient.str(target, "id"), nm);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    private void openChatByName(final String name) {
        e.listChats(new Engine.Callback<List<ConversationStore.Entry>>() {
            @Override
            public void done(List<ConversationStore.Entry> entries, String error) {
                String n = name.toLowerCase(Locale.US);
                for (ConversationStore.Entry en : entries) {
                    if (en.title.toLowerCase(Locale.US).contains(n)) {
                        e.openChat(en.id);
                        act.select(MainActivity.TAB_COMMS, true);
                        return;
                    }
                }
                e.notice("No saved chat matches “" + name + "”. `/history` lists them.", "warn");
            }
        });
    }
}
