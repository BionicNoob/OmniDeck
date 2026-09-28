package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One desktop tool LaunchBridge offers (/desk/capabilities): its name, what
 * it does, and its arguments — read from a JSON-schema-like description
 * when the bridge gives one ({"type":"object","properties":{…},"required":[…]},
 * a {name: type} map, or a list), else from "{arg}" hints in the description
 * and a few well-known tools. Lets the PC screen build simple forms instead
 * of asking for raw JSON. Plain Java.
 */
public final class BridgeTool {
    public static final String POWER = "power";
    public static final String MEDIA = "media";
    public static final String INFO = "info";
    public static final String OTHER = "other";

    private static final String[] SCHEMA_KEYS = {"parameters", "params", "input_schema", "inputSchema", "schema",
            "args", "arguments"};
    private static final Pattern HINT = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final String[] DESTRUCTIVE = {"shutdown", "shut_down", "poweroff", "power_off", "restart",
            "reboot", "sleep", "suspend", "hibernate", "logoff", "log_off", "logout", "log_out", "signout",
            "sign_out", "kill", "terminate", "end_process", "delete", "remove", "format", "uninstall", "wipe",
            "empty_trash", "empty_recycle"};

    /** One argument of a tool. */
    public static final class Param {
        public final String name;
        /** JSON-schema type: "string", "integer", "number", "boolean", "array" or "object". */
        public final String type;
        public final String description;
        public final boolean required;
        /** Allowed values ("enum"); empty when any value goes. */
        public final List<String> choices;
        /** The default as text; "" when none. */
        public final String defaultValue;
        /** Bounds for numbers; NaN when not given. */
        public final double min;
        public final double max;

        public Param(String name, String type, String description, boolean required, List<String> choices,
                     String defaultValue, double min, double max) {
            this.name = name;
            this.type = normalizeType(type);
            this.description = description == null ? "" : description;
            this.required = required;
            this.choices = Collections.unmodifiableList(choices == null ? new ArrayList<String>() : choices);
            this.defaultValue = defaultValue == null ? "" : defaultValue;
            this.min = min;
            this.max = max;
        }

        /** "Level" from "level", "Window title" from "window_title". */
        public String label() {
            return humanize(name);
        }

        public boolean numeric() {
            return "integer".equals(type) || "number".equals(type);
        }
    }

    public final String name;
    public final String description;
    public final List<Param> params;

    public BridgeTool(String name, String description, List<Param> params) {
        this.name = name;
        this.description = description == null ? "" : description.trim();
        this.params = Collections.unmodifiableList(params == null ? new ArrayList<Param>() : params);
    }

    /** "Lock screen" from "lock_screen". */
    public String label() {
        return humanize(name);
    }

    /** The description without "{arg}" hints ("Set master volume {level}" → "Set master volume"). */
    public String summary() {
        return description.replaceAll("\\s*\\{[A-Za-z_][A-Za-z0-9_]*\\}", "").trim();
    }

    /** Shuts down, restarts, sleeps, signs out, kills or deletes something: ask before running it. */
    public boolean destructive() {
        String n = name.toLowerCase(Locale.US);
        for (String d : DESTRUCTIVE) {
            if (n.contains(d)) return true;
        }
        return false;
    }

    /** {@link #POWER} (lock, sleep, restart…), {@link #MEDIA}, {@link #INFO} (read-only) or {@link #OTHER}. */
    public String category() {
        String n = name.toLowerCase(Locale.US);
        if (n.contains("lock") || n.contains("shutdown") || n.contains("shut_down") || n.contains("restart")
                || n.contains("reboot") || n.contains("sleep") || n.contains("hibernate") || n.contains("suspend")
                || n.contains("logoff") || n.contains("log_off") || n.contains("sign_out") || n.contains("power")
                || n.contains("wake")) {
            return POWER;
        }
        if (n.contains("media") || n.contains("play") || n.contains("pause") || n.contains("next_track")
                || n.contains("previous") || n.contains("prev_track") || n.contains("mute") || n.contains("volume")) {
            return MEDIA;
        }
        if (n.startsWith("get_") || n.startsWith("list_") || n.startsWith("read_") || n.endsWith("_info")
                || n.endsWith("_status") || n.startsWith("screenshot")) {
            return INFO;
        }
        return OTHER;
    }

    /** An args object with every default filled in (the starting point of a form or the JSON runner). */
    public JSONObject template() {
        JSONObject o = new JSONObject();
        try {
            for (Param p : params) {
                if (p.defaultValue.length() == 0) continue;
                if ("boolean".equals(p.type)) o.put(p.name, Boolean.parseBoolean(p.defaultValue));
                else if (p.numeric()) o.put(p.name, parseNumber(p.defaultValue));
                else o.put(p.name, p.defaultValue);
            }
        } catch (JSONException ignored) {
            // Keys are plain names; can't happen.
        }
        return o;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Every tool in a /desk/capabilities reply ({"tools": [...]}); unnamed entries are skipped. */
    public static List<BridgeTool> parseAll(JSONObject capabilities) {
        List<BridgeTool> out = new ArrayList<BridgeTool>();
        JSONArray tools = capabilities == null ? null : capabilities.optJSONArray("tools");
        if (tools == null) return out;
        for (int i = 0; i < tools.length(); i++) {
            BridgeTool t = parse(tools.opt(i));
            if (t != null) out.add(t);
        }
        return out;
    }

    /** One entry: a bare name, or {name, description, parameters…}. Null when it has no name. */
    public static BridgeTool parse(Object entry) {
        if (entry instanceof String) {
            String n = ((String) entry).trim();
            return n.length() == 0 ? null : new BridgeTool(n, "", known(n));
        }
        if (!(entry instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) entry;
        String name = OllamaClient.str(o, "name").trim();
        if (name.length() == 0) return null;
        String desc = OllamaClient.str(o, "description");
        if (desc.length() == 0) desc = OllamaClient.str(o, "desc");
        List<Param> params = null;
        for (String k : SCHEMA_KEYS) {
            if (o.has(k) && !o.isNull(k)) {
                params = params(o.opt(k));
                break;
            }
        }
        if (params == null) params = known(name);
        if (params.isEmpty()) params = hints(desc);
        return new BridgeTool(name, desc, params);
    }

    /** Params from a JSON schema, a {name: type-or-schema} map, or a list of names / param objects. */
    static List<Param> params(Object schema) {
        List<Param> out = new ArrayList<Param>();
        if (schema instanceof JSONObject) {
            JSONObject s = (JSONObject) schema;
            JSONObject props = s.optJSONObject("properties");
            List<String> required = strings(s.optJSONArray("required"));
            JSONObject fields = props != null ? props : s;
            Iterator<?> keys = fields.keys();
            while (keys.hasNext()) {
                String k = String.valueOf(keys.next());
                if (props == null && (k.equals("type") || k.equals("required") || k.equals("additionalProperties")
                        || k.equals("$schema") || k.equals("title") || k.equals("description"))) {
                    continue; // an empty object schema: no properties
                }
                Object v = fields.opt(k);
                out.add(v instanceof JSONObject ? param(k, (JSONObject) v, required.contains(k))
                        : new Param(k, String.valueOf(v), "", required.contains(k), null, "", Double.NaN, Double.NaN));
            }
        } else if (schema instanceof JSONArray) {
            JSONArray a = (JSONArray) schema;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject) {
                    String n = OllamaClient.str((JSONObject) v, "name");
                    if (n.length() > 0) out.add(param(n, (JSONObject) v, ((JSONObject) v).optBoolean("required")));
                } else if (v != null && String.valueOf(v).trim().length() > 0) {
                    out.add(new Param(String.valueOf(v).trim(), "string", "", false, null, "", Double.NaN, Double.NaN));
                }
            }
        }
        return out;
    }

    private static Param param(String name, JSONObject p, boolean required) {
        String type = OllamaClient.str(p, "type");
        if (type.length() == 0 && p.opt("type") instanceof JSONArray) type = p.optJSONArray("type").optString(0);
        List<String> choices = strings(p.optJSONArray("enum"));
        String def = p.has("default") && !p.isNull("default") ? String.valueOf(p.opt("default")) : "";
        return new Param(name, type, OllamaClient.str(p, "description"), required || p.optBoolean("required"),
                choices, def, p.optDouble("minimum", Double.NaN), p.optDouble("maximum", Double.NaN));
    }

    /** Arguments of well-known LaunchBridge tools, for bridges that don't describe them. */
    static List<Param> known(String tool) {
        List<Param> out = new ArrayList<Param>();
        if ("set_volume".equals(tool)) {
            out.add(new Param("level", "integer", "Volume, 0–100", true, null, "", 0, 100));
        } else if ("screenshot".equals(tool)) {
            out.add(new Param("save", "boolean", "Also save the image on the PC", false, null, "false",
                    Double.NaN, Double.NaN));
        } else if ("set_clipboard".equals(tool)) {
            out.add(new Param("text", "string", "Text to put on the PC's clipboard", true, null, "",
                    Double.NaN, Double.NaN));
        }
        return out;
    }

    /** "{level}"-style hints in a description, as text arguments. */
    static List<Param> hints(String description) {
        List<Param> out = new ArrayList<Param>();
        Matcher m = HINT.matcher(description == null ? "" : description);
        while (m.find()) out.add(new Param(m.group(1), "string", "", true, null, "", Double.NaN, Double.NaN));
        return out;
    }

    /** The first tool with one of these exact names, or null. */
    public static BridgeTool find(List<BridgeTool> tools, String... names) {
        for (String n : names) {
            for (BridgeTool t : tools) {
                if (t.name.equalsIgnoreCase(n)) return t;
            }
        }
        return null;
    }

    /** The tool that locks the PC (lock_screen, lock_workstation, lock…), or null when the bridge has none. */
    public static BridgeTool lockTool(List<BridgeTool> tools) {
        BridgeTool t = find(tools, "lock_screen", "lock_workstation", "lock_pc", "lock_computer", "lock");
        if (t != null) return t;
        for (BridgeTool c : tools) {
            String n = c.name.toLowerCase(Locale.US);
            if (n.contains("lock") && !n.contains("unlock") && !n.contains("clock") && !n.contains("block")) return c;
        }
        return null;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<String>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v != null) out.add(String.valueOf(v));
        }
        return out;
    }

    static String normalizeType(String t) {
        String s = t == null ? "" : t.trim().toLowerCase(Locale.US);
        if (s.equals("int") || s.equals("integer") || s.equals("long")) return "integer";
        if (s.equals("float") || s.equals("double") || s.equals("number")) return "number";
        if (s.equals("bool") || s.equals("boolean")) return "boolean";
        if (s.equals("array") || s.equals("list")) return "array";
        if (s.equals("object") || s.equals("dict")) return "object";
        return "string";
    }

    private static Object parseNumber(String v) {
        double d;
        try {
            d = Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return v;
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.valueOf((long) d);
        return Double.valueOf(d);
    }

    static String humanize(String id) {
        String s = id.replace('_', ' ').replace('-', ' ').trim();
        if (s.length() == 0) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
