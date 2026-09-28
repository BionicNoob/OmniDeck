package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A chat thread: an ordered list of messages plus a title. */
public final class Conversation {
    public final String id;
    public String title = "";
    public long created;
    public long updated;
    public final List<ChatMessage> messages = new ArrayList<ChatMessage>();

    public Conversation() {
        this("c" + ChatMessage.newId());
    }

    public Conversation(String id) {
        this.id = id;
        this.created = System.currentTimeMillis();
        this.updated = created;
    }

    /** True when any message carries images (the model must support vision). */
    public boolean hasImages() {
        for (ChatMessage m : messages) {
            if (!m.images.isEmpty()) return true;
        }
        return false;
    }

    /** True when nothing worth saving is in it (only notices, or empty). */
    public boolean isEmpty() {
        for (ChatMessage m : messages) {
            if (!m.isNotice()) return false;
        }
        return true;
    }

    public ChatMessage find(String messageId) {
        for (ChatMessage m : messages) {
            if (m.id.equals(messageId)) return m;
        }
        return null;
    }

    public int indexOf(String messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id.equals(messageId)) return i;
        }
        return -1;
    }

    public ChatMessage lastOfRole(String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role.equals(role)) return messages.get(i);
        }
        return null;
    }

    /** Sets the title from the first user message if there isn't one yet. */
    public void autoTitle() {
        if (title.length() > 0) return;
        for (ChatMessage m : messages) {
            if (m.isUser() && m.content.trim().length() > 0) {
                title = Fmt.ellipsize(m.content, 48);
                return;
            }
        }
    }

    /**
     * The messages array for /api/chat: an optional system prompt, then every
     * entry that belongs in context, up to (not including) {@code stopBefore}
     * when given.
     */
    public JSONArray toRequestMessages(String systemPrompt, ChatMessage stopBefore) {
        JSONArray arr = new JSONArray();
        try {
            if (systemPrompt != null && systemPrompt.trim().length() > 0) {
                JSONObject s = new JSONObject();
                s.put("role", "system");
                s.put("content", systemPrompt.trim());
                arr.put(s);
            }
            for (ChatMessage m : messages) {
                if (m == stopBefore) break;
                if (!m.sentToModel()) continue;
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                o.put("content", m.content);
                if (!m.images.isEmpty()) {
                    JSONArray imgs = new JSONArray();
                    for (String img : m.images) imgs.put(img);
                    o.put("images", imgs);
                }
                arr.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return arr;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("created", created);
        o.put("updated", updated);
        JSONArray arr = new JSONArray();
        for (ChatMessage m : messages) {
            if (m.streaming) continue;
            arr.put(m.toJson());
        }
        o.put("messages", arr);
        return o;
    }

    public static Conversation fromJson(JSONObject o) {
        String id = OllamaClient.str(o, "id");
        Conversation c = new Conversation(id.length() > 0 ? id : "c" + ChatMessage.newId());
        c.title = OllamaClient.str(o, "title");
        c.created = o.optLong("created", System.currentTimeMillis());
        c.updated = o.optLong("updated", c.created);
        JSONArray arr = o.optJSONArray("messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) c.messages.add(ChatMessage.fromJson(m));
            }
        }
        return c;
    }

    /** Plain-text/Markdown transcript for sharing. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title.length() > 0 ? title : "OMNI-DECK chat").append("\n\n");
        for (ChatMessage m : messages) {
            if (m.isNotice()) continue;
            String who = m.isUser() ? "You" : m.isSystem() ? "Context" : (m.model.length() > 0 ? m.model : "AI");
            sb.append("**").append(who).append(":**\n\n").append(m.content.trim()).append("\n\n");
        }
        return sb.toString().trim() + "\n";
    }
}
