package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One entry in a conversation. Roles "user", "assistant" and "system" are
 * sent to the model; "notice" entries (command output, errors, tips) are
 * shown in the chat but never sent.
 */
public final class ChatMessage {
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String SYSTEM = "system";
    public static final String NOTICE = "notice";

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1000);

    public final String id;
    public final String role;
    public String content;
    public String thinking = "";
    public long time;
    public String model = "";
    public String stats = "";
    /** Notices: "info" | "ok" | "warn" | "error". */
    public String tone = "info";
    public boolean error;
    /** For failed replies: the {@link ReplyError} kind (e.g. "model_missing"), "" when unknown. */
    public String errorKind = "";
    public boolean stopped;
    /** Base64 PNG/JPEG shown under the text (screenshots from the PC). */
    public String image = "";
    /** Base64 images attached by the user; sent to vision models. */
    public List<String> images = new ArrayList<String>();
    /** Time to first token in ms (assistant replies), -1 if unknown. */
    public long ttftMs = -1;

    // Live state while a reply streams in (not persisted).
    public transient boolean streaming;
    public transient long startedAt;

    public ChatMessage(String role, String content) {
        this(newId(), role, content, System.currentTimeMillis());
    }

    private ChatMessage(String id, String role, String content, long time) {
        this.id = id;
        this.role = role;
        this.content = content == null ? "" : content;
        this.time = time;
    }

    public static String newId() {
        return Long.toString(SEQ.incrementAndGet(), 36);
    }

    public static ChatMessage notice(String text, String tone) {
        ChatMessage m = new ChatMessage(NOTICE, text);
        m.tone = tone == null ? "info" : tone;
        return m;
    }

    public boolean isUser() {
        return USER.equals(role);
    }

    public boolean isAssistant() {
        return ASSISTANT.equals(role);
    }

    public boolean isNotice() {
        return NOTICE.equals(role);
    }

    public boolean isSystem() {
        return SYSTEM.equals(role);
    }

    /** Whether this entry belongs in the model's context. */
    public boolean sentToModel() {
        if (isNotice()) return false;
        if (isAssistant() && content.trim().length() == 0) return false;
        return true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("role", role);
        o.put("content", content);
        if (thinking.length() > 0) o.put("thinking", thinking);
        o.put("time", time);
        if (model.length() > 0) o.put("model", model);
        if (stats.length() > 0) o.put("stats", stats);
        if (!"info".equals(tone)) o.put("tone", tone);
        if (error) o.put("error", true);
        if (errorKind.length() > 0) o.put("error_kind", errorKind);
        if (stopped) o.put("stopped", true);
        if (image.length() > 0) o.put("image", image);
        if (!images.isEmpty()) {
            JSONArray a = new JSONArray();
            for (String img : images) a.put(img);
            o.put("images", a);
        }
        if (ttftMs >= 0) o.put("ttft", ttftMs);
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) {
        String id = OllamaClient.str(o, "id");
        ChatMessage m = new ChatMessage(id.length() > 0 ? id : newId(), o.optString("role", NOTICE),
                OllamaClient.str(o, "content"), o.optLong("time", System.currentTimeMillis()));
        m.thinking = OllamaClient.str(o, "thinking");
        m.model = OllamaClient.str(o, "model");
        m.stats = OllamaClient.str(o, "stats");
        String tone = OllamaClient.str(o, "tone");
        m.tone = tone.length() > 0 ? tone : "info";
        m.error = o.optBoolean("error", false);
        m.errorKind = OllamaClient.str(o, "error_kind");
        m.stopped = o.optBoolean("stopped", false);
        m.image = OllamaClient.str(o, "image");
        JSONArray a = o.optJSONArray("images");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                String img = a.optString(i, "");
                if (img.length() > 0) m.images.add(img);
            }
        }
        m.ttftMs = o.optLong("ttft", -1);
        return m;
    }
}
