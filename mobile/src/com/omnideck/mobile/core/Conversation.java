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
    /** The model this chat was last used with ("" = none yet); reopening the chat switches back to it. */
    public String model = "";
    public final List<ChatMessage> messages = new ArrayList<ChatMessage>();

    public Conversation() {
        this("c" + ChatMessage.newId());
    }

    public Conversation(String id) {
        this.id = id;
        this.created = System.currentTimeMillis();
        this.updated = created;
    }

    /** True when any message carries images (the model must support vision), a tool's screenshot included. */
    public boolean hasImages() {
        for (ChatMessage m : messages) {
            if (!m.images.isEmpty()) return true;
            for (ToolCall c : m.tools) {
                if (c.image.length() > 0) return true;
            }
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
     * when given. Images are included.
     */
    public JSONArray toRequestMessages(String systemPrompt, ChatMessage stopBefore) {
        return toRequestMessages(systemPrompt, stopBefore, true);
    }

    /**
     * As above; with {@code includeImages} false (a model that can't see
     * images — Ollama rejects the whole request otherwise) every image is left
     * out and its message says so in a short marker, so the model still knows
     * an image was shared.
     */
    public JSONArray toRequestMessages(String systemPrompt, ChatMessage stopBefore, boolean includeImages) {
        return toRequestMessages(systemPrompt, stopBefore, includeImages, true);
    }

    /**
     * As above. With {@code toolCalls} (the model can call tools) a reply that
     * used PC tools goes out as the real exchange: tool calls and "tool"
     * results. Without, it is folded into one assistant message that lists
     * the actions, for a model whose template has no place for tool messages.
     */
    public JSONArray toRequestMessages(String systemPrompt, ChatMessage stopBefore, boolean includeImages,
                                       boolean toolCalls) {
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
                if (m.isAssistant() && !m.tools.isEmpty()) {
                    if (toolCalls) putToolRounds(arr, m, includeImages);
                    else arr.put(new JSONObject().put("role", ChatMessage.ASSISTANT).put("content", foldTools(m)));
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                if (m.images.isEmpty()) {
                    o.put("content", m.content);
                } else if (includeImages) {
                    o.put("content", m.content);
                    JSONArray imgs = new JSONArray();
                    for (String img : m.images) imgs.put(img);
                    o.put("images", imgs);
                } else {
                    String marker = imageMarker(m.images.size());
                    o.put("content", m.content.length() > 0 ? m.content + "\n\n" + marker : marker);
                }
                arr.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return arr;
    }

    /**
     * A reply that used tools, as the model saw it: per round, the assistant
     * message with its text and "tool_calls", then one "tool" message per
     * call (its result, and a screenshot as "images" for vision models); then
     * the text written after the last round.
     */
    static void putToolRounds(JSONArray arr, ChatMessage m, boolean includeImages) throws JSONException {
        String text = m.content;
        int prev = 0;
        int rounds = 0;
        for (ToolCall c : m.tools) rounds = Math.max(rounds, c.round);
        for (int r = 1; r <= rounds; r++) {
            List<ToolCall> calls = new ArrayList<ToolCall>();
            for (ToolCall c : m.tools) {
                if (c.round == r) calls.add(c);
            }
            if (calls.isEmpty()) continue;
            int at = Math.max(prev, Math.min(text.length(), calls.get(0).at));
            JSONObject a = new JSONObject();
            a.put("role", ChatMessage.ASSISTANT);
            a.put("content", text.substring(prev, at).trim());
            JSONArray tc = new JSONArray();
            for (ToolCall c : calls) tc.put(c.toRequest());
            a.put("tool_calls", tc);
            arr.put(a);
            for (ToolCall c : calls) {
                JSONObject t = new JSONObject();
                t.put("role", "tool");
                String result = c.resultForModel();
                if (c.image.length() > 0) {
                    if (includeImages) {
                        t.put("images", new JSONArray().put(c.image));
                    } else {
                        result = result + "\n\n[The tool returned an image. The current model can't see images, "
                                + "so it wasn't sent.]";
                    }
                }
                t.put("content", result);
                t.put("tool_name", c.name);
                if (c.id.length() > 0) t.put("tool_call_id", c.id);
                arr.put(t);
            }
            prev = at;
        }
        String rest = text.substring(Math.min(prev, text.length())).trim();
        if (rest.length() > 0) {
            JSONObject a = new JSONObject();
            a.put("role", ChatMessage.ASSISTANT);
            a.put("content", rest);
            arr.put(a);
        }
    }

    /** A reply that used tools as plain text: "[PC actions: Set volume → 40% (done: …)]" then the reply. */
    static String foldTools(ChatMessage m) {
        StringBuilder sb = new StringBuilder("[PC actions: ");
        for (int i = 0; i < m.tools.size(); i++) {
            ToolCall c = m.tools.get(i);
            if (i > 0) sb.append("; ");
            sb.append(c.label.length() > 0 ? c.label : c.name).append(" (").append(c.state);
            if (ToolCall.DONE.equals(c.state) || ToolCall.FAILED.equals(c.state)) {
                sb.append(": ").append(Fmt.ellipsize(c.resultForModel(), 300));
            }
            sb.append(')');
        }
        sb.append(']');
        String text = m.content.trim();
        return text.length() > 0 ? sb + "\n\n" + text : sb.toString();
    }

    /** Rough context cost of one image for vision models (LLaVA-style: 576 patches). */
    public static final int IMAGE_TOKENS = 576;

    /**
     * Rough size in tokens of the next request's prompt: about four
     * characters per token, a few tokens of framing per message, and a fixed
     * cost per image when images are sent. Good enough to warn before the
     * context window fills up (Ollama then silently drops the oldest turns).
     */
    public int estimateTokens(String systemPrompt, boolean includeImages) {
        long chars = systemPrompt == null ? 0 : systemPrompt.trim().length();
        long tokens = chars > 0 ? 4 : 0;
        for (ChatMessage m : messages) {
            if (!m.sentToModel()) continue;
            chars += m.content.length();
            tokens += 4;
            if (includeImages) tokens += (long) IMAGE_TOKENS * m.images.size();
            for (ToolCall c : m.tools) {
                // The call (name + arguments) and its result message.
                chars += c.name.length() + c.args.toString().length() + c.resultForModel().length();
                tokens += 8;
                if (includeImages && c.image.length() > 0) tokens += IMAGE_TOKENS;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens + (chars + 3) / 4);
    }

    /** Stands in for images a text-only model isn't sent. */
    public static String imageMarker(int count) {
        return count == 1 ? "[An image was attached here. The current model can't see images, so it wasn't sent.]"
                : "[" + count + " images were attached here. The current model can't see images, so they weren't sent.]";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("created", created);
        o.put("updated", updated);
        if (model.length() > 0) o.put("model", model);
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
        c.model = OllamaClient.str(o, "model");
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
            sb.append("**").append(who).append(":**\n\n");
            for (ToolCall c : m.tools) {
                sb.append("> PC action: ").append(c.label.length() > 0 ? c.label : c.name).append(" (")
                        .append(c.state).append(")\n");
            }
            if (!m.tools.isEmpty()) sb.append('\n');
            sb.append(m.content.trim()).append("\n\n");
        }
        return sb.toString().trim() + "\n";
    }
}
