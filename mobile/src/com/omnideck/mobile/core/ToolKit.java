package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The plain-Java side of letting the AI act on the PC: the "tools" array
 * sent to Ollama (built from LaunchBridge's catalog plus the built-in
 * open_app), how risky a call is (read-only, a change, destructive), the
 * labels the action log and the approval sheet show, the text a tool's
 * result becomes for the model, and the /tools report.
 */
public final class ToolKit {
    /** The built-in tool that opens an app through LaunchBridge's /launch (dry run first). */
    public static final String OPEN_APP = "open_app";
    /** Tool rounds per user message; after that the model must answer without tools. */
    public static final int MAX_ROUNDS = 5;
    /** A tool result is cut to about this many characters before it goes to the model. */
    public static final int RESULT_MAX = 4000;

    /** Reads something; runs without asking. */
    public static final int READ = 0;
    /** Changes something on the PC; asks first while "Ask before PC actions" is on. */
    public static final int CHANGE = 1;
    /** Shuts down, restarts, signs out, kills or deletes: always asks. */
    public static final int DESTRUCTIVE = 2;

    public static final String DECLINED_RESULT = "The user declined.";
    public static final String UNAVAILABLE_RESULT = "The user wasn't available to approve this action, so it was not done.";
    public static final String STOPPED_RESULT = "Not run: the user stopped the reply.";
    public static final String LIMIT_PROMPT = "You have used the maximum number of PC actions for this message. Don't "
            + "call any more tools: answer now with what you have.";

    private static final String[][] KNOWN = {
            {"get_system_info", "Read the PC's CPU load, memory, disk space, battery and uptime."},
            {"get_volume", "Read the PC's master volume (0-100)."},
            {"set_volume", "Set the PC's master volume (0-100)."},
            {"screenshot", "Capture the PC's screen as an image."},
            {"get_clipboard", "Read the text on the PC's clipboard."},
            {"set_clipboard", "Put text on the PC's clipboard."},
            {"lock_screen", "Lock the PC's screen."},
            {"list_processes", "List the programs using the most CPU on the PC."},
    };

    private ToolKit() {}

    // ------------------------------------------------------------------
    // The "tools" array
    // ------------------------------------------------------------------

    /** What the model reads about a tool: the bridge's description (without "{arg}" hints), or a known one. */
    public static String description(BridgeTool t) {
        String s = t.summary();
        if (s.length() > 0) return s;
        for (String[] k : KNOWN) {
            if (k[0].equalsIgnoreCase(t.name)) return k[1];
        }
        return t.label() + " on the PC.";
    }

    /** A JSON schema for a tool's arguments: {"type":"object","properties":{…},"required":[…]}. */
    public static JSONObject parameters(List<BridgeTool.Param> params) {
        try {
            JSONObject props = new JSONObject();
            JSONArray required = new JSONArray();
            for (BridgeTool.Param p : params) {
                JSONObject s = new JSONObject();
                s.put("type", p.type);
                if ("array".equals(p.type)) s.put("items", new JSONObject().put("type", "string"));
                s.put("description", p.description.length() > 0 ? p.description : p.label());
                if (!p.choices.isEmpty()) {
                    JSONArray e = new JSONArray();
                    for (String c : p.choices) e.put(c);
                    s.put("enum", e);
                }
                if (!Double.isNaN(p.min)) s.put("minimum", number(p.min));
                if (!Double.isNaN(p.max)) s.put("maximum", number(p.max));
                props.put(p.name, s);
                if (p.required) required.put(p.name);
            }
            JSONObject o = new JSONObject();
            o.put("type", "object");
            o.put("properties", props);
            if (required.length() > 0) o.put("required", required);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {"type":"function","function":{"name","description","parameters"}}. */
    public static JSONObject function(String name, String description, JSONObject parameters) {
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("description", description);
            fn.put("parameters", parameters);
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One bridge tool as an entry of the "tools" array. */
    public static JSONObject schema(BridgeTool t) {
        return function(t.name, description(t), parameters(t.params));
    }

    /** The built-in open_app: resolves the name with a dry run, then launches one match (after approval). */
    public static JSONObject openAppSchema() {
        try {
            JSONObject props = new JSONObject();
            props.put("query", new JSONObject().put("type", "string")
                    .put("description", "The app's name as the user said it, e.g. \"spotify\" or \"visual studio code\"."));
            props.put("app_id", new JSONObject().put("type", "string")
                    .put("description", "Only when an earlier open_app result listed several apps: the app_id of the "
                            + "one the user picked."));
            JSONObject params = new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("query"));
            return function(OPEN_APP, "Open (launch) an app on the user's PC by name. If several installed apps match, "
                    + "the result lists them with their app_id and nothing is opened: ask the user which one they "
                    + "mean, then call open_app again with that app_id.", params);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The "tools" array for a chat request: every bridge tool, plus open_app unless the bridge has its own. */
    public static JSONArray toolsArray(List<BridgeTool> catalog, boolean withOpenApp) {
        JSONArray a = new JSONArray();
        List<String> seen = new ArrayList<String>();
        for (BridgeTool t : catalog) {
            String k = t.name.toLowerCase(Locale.US);
            if (t.name.length() == 0 || seen.contains(k)) continue;
            seen.add(k);
            a.put(schema(t));
        }
        if (withOpenApp && !seen.contains(OPEN_APP)) a.put(openAppSchema());
        return a;
    }

    /** The tool with this name (any case), or null. */
    public static BridgeTool find(List<BridgeTool> catalog, String name) {
        if (catalog == null || name == null) return null;
        for (BridgeTool t : catalog) {
            if (t.name.equalsIgnoreCase(name.trim())) return t;
        }
        return null;
    }

    /** Required arguments the call left out ("level, text"), or null when none are missing. */
    public static String missingArgs(BridgeTool t, JSONObject args) {
        if (t == null) return null;
        StringBuilder sb = new StringBuilder();
        for (BridgeTool.Param p : t.params) {
            if (!p.required) continue;
            if (args != null && args.has(p.name) && !args.isNull(p.name)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.name);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------
    // Risk
    // ------------------------------------------------------------------

    /**
     * {@link #READ} for tools that only look (get_*, list_*, read*, *_info,
     * *_status, a screenshot that isn't saved), {@link #DESTRUCTIVE} for
     * shutdown / restart / sleep / sign-out / kill / delete and the like,
     * else {@link #CHANGE}. {@code t} may be null (e.g. open_app).
     */
    public static int risk(BridgeTool t, String name, JSONObject args) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.US);
        BridgeTool probe = t != null ? t : new BridgeTool(n, "", null);
        if (probe.destructive()) return DESTRUCTIVE;
        if (OPEN_APP.equals(n)) return CHANGE;
        if (n.startsWith("screenshot") || n.startsWith("capture_screen")) {
            return args != null && args.optBoolean("save", false) ? CHANGE : READ;
        }
        if (n.startsWith("get_") || n.startsWith("list_") || n.startsWith("read") || n.startsWith("query_")
                || n.startsWith("search_") || n.endsWith("_info") || n.endsWith("_status") || n.equals("info")) {
            return READ;
        }
        return CHANGE;
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    private static final String[] ACRONYMS = {"pc", "cpu", "gpu", "ram", "url", "id", "os", "usb", "ip", "dns", "vpn",
            "ui", "hdmi", "tv"};

    /** A tool's name in words, with acronyms in capitals: "Shutdown PC", "Get CPU load". */
    static String words(BridgeTool t, String name) {
        String s = t != null ? t.label() : BridgeTool.humanize(name == null ? "" : name);
        String[] w = s.split(" ");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < w.length; i++) {
            if (i > 0) sb.append(' ');
            String x = w[i];
            for (String a : ACRONYMS) {
                if (x.equalsIgnoreCase(a)) {
                    x = a.toUpperCase(Locale.US);
                    break;
                }
            }
            sb.append(x);
        }
        return sb.toString();
    }

    /** The action log's line: "Set volume → 40%", "Get system info", "Set clipboard → “ssh omni@…”". */
    public static String label(BridgeTool t, String name, JSONObject args) {
        String base = words(t, name);
        if (OPEN_APP.equalsIgnoreCase(name)) {
            String q = OllamaClient.str(args, "query").trim();
            if (q.length() == 0) q = OllamaClient.str(args, "app_id").trim();
            return q.length() > 0 ? "Open " + Fmt.ellipsize(q, 40) : "Open app";
        }
        String a = argsSummary(t, name, args);
        return a.length() > 0 ? base + " → " + a : base;
    }

    /**
     * The approval sheet's phrase ("OMNI wants to … on ATLAS-PC"): "set volume
     * to 40%", "lock screen", "kill process “chrome.exe”". {@code label} is the
     * call's current action-log label (open_app knows the app's real name).
     */
    public static String phrase(BridgeTool t, String name, JSONObject args, String label) {
        if (OPEN_APP.equalsIgnoreCase(name)) return lowerFirst(label != null && label.length() > 0 ? label : "open an app");
        String base = lowerFirst(words(t, name));
        String a = argsSummary(t, name, args);
        if (a.length() == 0) return base;
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        return n.startsWith("set_") && a.indexOf(", ") < 0 ? base + " to " + a : base + " " + a;
    }

    /** The key arguments in a few words: numbers, quoted text, switched-on flags (in the tool's own order). */
    public static String argsSummary(BridgeTool t, String name, JSONObject args) {
        if (args == null || args.length() == 0) return "";
        List<String> keys = new ArrayList<String>();
        if (t != null) {
            for (BridgeTool.Param p : t.params) {
                if (args.has(p.name)) keys.add(p.name);
            }
        }
        Iterator<?> it = args.keys();
        while (it.hasNext()) {
            String k = String.valueOf(it.next());
            if (!keys.contains(k)) keys.add(k);
        }
        List<String> parts = new ArrayList<String>();
        for (String k : keys) {
            String v = value(name, k, args.opt(k));
            if (v.length() > 0) parts.add(v);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size() && i < 3; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        if (parts.size() > 3) sb.append(", …");
        return sb.toString();
    }

    private static String value(String tool, String key, Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof Boolean) return (Boolean) v ? BridgeTool.humanize(key).toLowerCase(Locale.US) : "";
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            String s = d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
            return percentish(tool, key) ? s + "%" : s;
        }
        if (v instanceof JSONObject || v instanceof JSONArray) return Fmt.ellipsize(v.toString(), 28);
        String s = String.valueOf(v).trim();
        if (s.length() == 0) return "";
        // A number sent as text still reads as a number ("40" → 40%).
        if (s.matches("-?\\d+(\\.\\d+)?")) return percentish(tool, key) ? s + "%" : s;
        return "“" + Fmt.ellipsize(s, 28) + "”";
    }

    private static boolean percentish(String tool, String key) {
        String k = key.toLowerCase(Locale.US);
        String n = tool == null ? "" : tool.toLowerCase(Locale.US);
        return k.equals("level") && (n.contains("volume") || n.contains("brightness"))
                || k.equals("volume") || k.equals("percent") || k.equals("percentage") || k.equals("brightness");
    }

    static String lowerFirst(String s) {
        if (s == null || s.length() == 0) return "";
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1))) return s; // "PC …", "USB …"
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** The arguments as indented JSON for the details view ("none" when there are none). */
    public static String prettyArgs(JSONObject args) {
        if (args == null || args.length() == 0) return "none";
        try {
            return args.toString(2);
        } catch (JSONException e) {
            return args.toString();
        }
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    /**
     * What the model is told a tool returned: text as is, JSON compact with
     * any embedded image replaced by "[image attached]", "Done." for an empty
     * result, cut to about {@link #RESULT_MAX} characters.
     */
    public static String resultText(Object r) {
        if (r == null || r == JSONObject.NULL) return "Done.";
        String s;
        if (r instanceof JSONObject || r instanceof JSONArray) {
            s = String.valueOf(stripImages(r));
        } else {
            s = String.valueOf(r).trim();
            if (isImageData(s)) return "[image attached]";
        }
        if (s.length() == 0 || s.equals("{}") || s.equals("[]")) return "Done.";
        return truncate(s, RESULT_MAX);
    }

    /**
     * open_app found several apps: the list the model gets, with the ids to
     * call it again with (nothing was opened).
     */
    public static String candidatesText(String query, JSONArray candidates) {
        StringBuilder sb = new StringBuilder("Several apps on the PC match \"").append(query).append("\": ");
        int n = 0;
        for (int i = 0; i < candidates.length() && n < 12; i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c == null) continue;
            if (n++ > 0) sb.append("; ");
            sb.append(OllamaClient.str(c, "name")).append(" (app_id: ").append(OllamaClient.str(c, "id"));
            String path = OllamaClient.str(c, "path");
            if (path.length() > 0) sb.append(", ").append(path);
            sb.append(')');
        }
        sb.append(". Nothing was opened. Ask the user which one they mean, then call open_app again with its app_id.");
        return truncate(sb.toString(), RESULT_MAX);
    }

    /** {@code s} cut to {@code max} characters, saying how long it was. */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "… [cut: " + s.length() + " characters in all]";
    }

    /** A copy of a JSON value with base64 images / data URLs replaced by a short marker. */
    static Object stripImages(Object v) {
        try {
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                JSONObject out = new JSONObject();
                Iterator<?> it = o.keys();
                while (it.hasNext()) {
                    String k = String.valueOf(it.next());
                    out.put(k, stripImages(o.opt(k)));
                }
                return out;
            }
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                JSONArray out = new JSONArray();
                for (int i = 0; i < a.length(); i++) out.put(stripImages(a.opt(i)));
                return out;
            }
        } catch (JSONException e) {
            return v;
        }
        if (v instanceof String && isImageData((String) v)) return "[image attached]";
        return v;
    }

    /** A data: URL or a long base64 run (a screenshot), not text worth sending the model. */
    static boolean isImageData(String s) {
        String t = s.trim();
        if (t.startsWith("data:image")) return true;
        if (t.length() < 200) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+'
                    || c == '/' || c == '=' || c == '\n' || c == '\r';
            if (!ok) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // What the model is told
    // ------------------------------------------------------------------

    /** The system-prompt addition sent only while tools are offered. {@code pc} may be "". */
    public static String systemPrompt(String pc) {
        String where = pc == null || pc.trim().length() == 0 ? "the user's PC" : "the user's PC (" + pc.trim() + ")";
        return "You can act on " + where + " through the tools you have been given. When the user asks you to do "
                + "something on the PC, or asks about its current state, call the matching tool instead of saying you "
                + "did it: never claim an action or make up a result. Some actions need the user's approval; if one is "
                + "declined or fails, say so plainly. After using tools, briefly tell the user what you did and what "
                + "happened.";
    }

    // ------------------------------------------------------------------
    // /tools
    // ------------------------------------------------------------------

    /** What /tools reports on. */
    public static final class Status {
        public boolean enabled;
        public boolean paired;
        /** "Ask before PC actions". */
        public boolean confirm;
        public String model = "";
        /** Whether the model has the "tools" capability; null when unknown. */
        public Boolean modelTools;
        /** The bridge's tools; null when unknown. */
        public List<BridgeTool> catalog;
        public String catalogError = "";
        public String pc = "";

        public boolean active() {
            return enabled && paired && Boolean.TRUE.equals(modelTools) && catalog != null;
        }
    }

    /** The /tools notice (Markdown): active or not and why, then the tools OMNI can use. */
    public static String report(Status s) {
        StringBuilder sb = new StringBuilder();
        String pc = s.pc == null || s.pc.length() == 0 ? "the PC" : "**" + s.pc + "**";
        if (s.active()) {
            sb.append("**AI tools · active** — OMNI can act on ").append(pc).append(" with `").append(s.model)
                    .append("`. ");
            sb.append(s.confirm ? "Read-only tools run right away; anything that changes the PC asks you first. "
                    : "Tools run right away (Ask before PC actions is off). ");
            sb.append("Shutting down, restarting or deleting always asks.");
        } else {
            sb.append("**AI tools · off** — OMNI can't act on the PC right now:");
            if (!s.enabled) sb.append("\n• Tool calling is turned off. `/tools on` turns it on.");
            if (!s.paired) sb.append("\n• The PC bridge isn't paired. Run `/pair` (LaunchBridge must listen on the network).");
            if (s.model == null || s.model.length() == 0) {
                sb.append("\n• No model is selected.");
            } else if (Boolean.FALSE.equals(s.modelTools)) {
                sb.append("\n• `").append(s.model).append("` can't call tools. Pick a model with the *tools* capability "
                        + "(qwen3, llama3.1 or newer, mistral…) in Models.");
            } else if (s.modelTools == null) {
                sb.append("\n• Couldn't check whether `").append(s.model).append("` can call tools (the AI isn't "
                        + "connected).");
            }
            if (s.paired && s.catalog == null) {
                sb.append("\n• Couldn't read the PC's tool list")
                        .append(s.catalogError != null && s.catalogError.length() > 0 ? ": " + s.catalogError : ".");
            }
        }
        if (s.catalog != null) {
            sb.append("\n\n**Tools** (").append(s.catalog.size() + (find(s.catalog, OPEN_APP) == null ? 1 : 0))
                    .append(")");
            for (BridgeTool t : s.catalog) sb.append("\n• `").append(t.name).append("` — ").append(description(t))
                    .append(" · ").append(policy(risk(t, t.name, null), s.confirm));
            if (find(s.catalog, OPEN_APP) == null) {
                sb.append("\n• `").append(OPEN_APP).append("` — Open an app on the PC by name · ")
                        .append(policy(CHANGE, s.confirm));
            }
        }
        if (s.active()) sb.append("\n\n`/tools off` turns tool calling off.");
        return sb.toString();
    }

    private static String policy(int risk, boolean confirm) {
        if (risk == DESTRUCTIVE) return "always asks";
        if (risk == READ) return "read-only";
        return confirm ? "asks first" : "runs right away";
    }

    private static Object number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.valueOf((long) d);
        return Double.valueOf(d);
    }
}
