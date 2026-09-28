package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One tool call the AI made while answering: which PC tool, with which
 * arguments, and what happened (asked, ran, declined, failed). A reply that
 * used tools keeps its calls in {@link ChatMessage#tools}; the calls of one
 * model response form a "round", and {@link #at} marks where that round sat
 * in the reply's text so the exchange can be replayed to the model exactly
 * ({@link Conversation#toRequestMessages}). Plain Java.
 */
public final class ToolCall {
    /** Waiting for its turn (several calls in one round run one after another). */
    public static final String QUEUED = "queued";
    /** Waiting for the user's OK. */
    public static final String ASKING = "asking";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    /** Not run: the user said no, wasn't there to answer, or the reply was stopped. */
    public static final String DECLINED = "declined";
    public static final String FAILED = "failed";

    /** The call id Ollama gave ("" when none). */
    public String id = "";
    public final String name;
    /** The arguments as the model sent them. */
    public JSONObject args;
    /** Set when the model's arguments weren't a JSON object (the call then fails without running). */
    public String argsError = "";
    public String state = QUEUED;
    /** What the model is told: the tool's result, an error, or why it didn't run. */
    public String result = "";
    /** A base64 JPEG/PNG the tool returned (a screenshot); "" when none. */
    public String image = "";
    /** The model response (1, 2, …) within the reply that asked for this call. */
    public int round;
    /** Length of the reply's text when this call's round ended: the text before it belongs to that round. */
    public int at;
    /** What the action log shows ("Set volume → 40%"). */
    public String label = "";
    /** How long it ran, in ms (0 while unknown). */
    public long ms;

    public ToolCall(String name, JSONObject args) {
        this.name = name == null ? "" : name.trim();
        this.args = args == null ? new JSONObject() : args;
    }

    /** True once it can't change any more (done, declined or failed). */
    public boolean isFinal() {
        return DONE.equals(state) || DECLINED.equals(state) || FAILED.equals(state);
    }

    /** The tool message's content: the result, or a line that says why there is none. */
    public String resultForModel() {
        if (result.length() > 0) return result;
        if (DONE.equals(state)) return "Done.";
        if (DECLINED.equals(state)) return "Not run.";
        if (FAILED.equals(state)) return "The tool failed.";
        return "No result yet.";
    }

    // ------------------------------------------------------------------
    // Parsing what Ollama sends ("message": {"tool_calls": [...]})
    // ------------------------------------------------------------------

    /**
     * The calls in a message's "tool_calls" array. Accepts Ollama's shape
     * ({"id"?, "function": {"name", "arguments": {...}}}), arguments sent as
     * a JSON string, and a flat {"name", "arguments"} entry. Entries without
     * a name are skipped.
     */
    public static List<ToolCall> parseAll(JSONArray calls) {
        List<ToolCall> out = new ArrayList<ToolCall>();
        if (calls == null) return out;
        for (int i = 0; i < calls.length(); i++) {
            ToolCall c = parse(calls.optJSONObject(i));
            if (c != null) out.add(c);
        }
        return out;
    }

    /** One entry of a "tool_calls" array, or null when it names no tool. */
    public static ToolCall parse(JSONObject entry) {
        if (entry == null) return null;
        JSONObject fn = entry.optJSONObject("function");
        JSONObject src = fn != null ? fn : entry;
        String name = OllamaClient.str(src, "name").trim();
        if (name.length() == 0) return null;
        Object raw = src.opt("arguments");
        if (raw == null) raw = src.opt("parameters");
        JSONObject args = null;
        String err = "";
        if (raw == null || raw == JSONObject.NULL) {
            args = new JSONObject();
        } else if (raw instanceof JSONObject) {
            args = (JSONObject) raw;
        } else {
            String s = String.valueOf(raw).trim();
            if (s.length() == 0) {
                args = new JSONObject();
            } else {
                try {
                    args = new JSONObject(s);
                } catch (JSONException e) {
                    err = Fmt.ellipsize(s, 200);
                }
            }
        }
        ToolCall c = new ToolCall(name, args);
        c.argsError = err;
        c.id = OllamaClient.str(entry, "id");
        return c;
    }

    /** The call as it goes back to the model in the assistant message's "tool_calls". */
    public JSONObject toRequest() {
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", args);
            JSONObject o = new JSONObject();
            if (id.length() > 0) o.put("id", id);
            o.put("function", fn);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // Saving with the chat
    // ------------------------------------------------------------------

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        if (id.length() > 0) o.put("id", id);
        o.put("name", name);
        o.put("args", args);
        if (argsError.length() > 0) o.put("args_error", argsError);
        o.put("state", state);
        if (result.length() > 0) o.put("result", result);
        if (image.length() > 0) o.put("image", image);
        o.put("round", round);
        o.put("at", at);
        if (label.length() > 0) o.put("label", label);
        if (ms > 0) o.put("ms", ms);
        return o;
    }

    /**
     * A saved call. One saved mid-flight (the app closed before the PC
     * answered) is settled: it can never finish now.
     */
    public static ToolCall fromJson(JSONObject o) {
        JSONObject args = o.optJSONObject("args");
        ToolCall c = new ToolCall(OllamaClient.str(o, "name"), args);
        c.id = OllamaClient.str(o, "id");
        c.argsError = OllamaClient.str(o, "args_error");
        String st = OllamaClient.str(o, "state");
        c.state = st.length() > 0 ? st : DONE;
        c.result = OllamaClient.str(o, "result");
        c.image = OllamaClient.str(o, "image");
        c.round = Math.max(1, o.optInt("round", 1));
        c.at = Math.max(0, o.optInt("at", 0));
        c.label = OllamaClient.str(o, "label");
        c.ms = Math.max(0, o.optLong("ms", 0));
        if (RUNNING.equals(c.state)) {
            c.state = FAILED;
            if (c.result.length() == 0) c.result = "No result: the app closed before the PC answered.";
        } else if (!c.isFinal()) {
            c.state = DECLINED;
            if (c.result.length() == 0) c.result = "Not run: the reply was interrupted.";
        }
        return c;
    }
}
