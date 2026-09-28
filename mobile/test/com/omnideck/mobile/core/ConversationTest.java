package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConversationTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Conversation withImages() {
        Conversation c = new Conversation();
        ChatMessage u = new ChatMessage(ChatMessage.USER, "What is in this photo?");
        u.images.add("AAAA");
        c.messages.add(u);
        c.messages.add(new ChatMessage(ChatMessage.ASSISTANT, "A cat on a sofa."));
        ChatMessage two = new ChatMessage(ChatMessage.USER, "");
        two.images.add("BBBB");
        two.images.add("CCCC");
        c.messages.add(two);
        c.messages.add(ChatMessage.notice("not sent", "info"));
        c.messages.add(new ChatMessage(ChatMessage.USER, "And now?"));
        return c;
    }

    @Test
    public void imagesGoOnlyToModelsThatCanSeeThem() throws Exception {
        Conversation c = withImages();
        JSONArray vision = c.toRequestMessages("Be brief.", null, true);
        assertEquals(5, vision.length()); // system + 4 (the notice is never sent)
        assertEquals("AAAA", vision.getJSONObject(1).getJSONArray("images").getString(0));
        assertEquals(2, vision.getJSONObject(3).getJSONArray("images").length());
        assertEquals("the two-arg form keeps images", vision.toString(), c.toRequestMessages("Be brief.", null).toString());

        JSONArray text = c.toRequestMessages("Be brief.", null, false);
        assertEquals(5, text.length());
        for (int i = 0; i < text.length(); i++) {
            assertFalse("no images for a text-only model", text.getJSONObject(i).has("images"));
        }
        JSONObject first = text.getJSONObject(1);
        assertTrue(first.getString("content").startsWith("What is in this photo?\n\n[An image was attached here."));
        assertEquals(Conversation.imageMarker(2), text.getJSONObject(3).getString("content"));
        assertEquals("And now?", text.getJSONObject(4).getString("content"));
        assertTrue(c.hasImages());
    }

    @Test
    public void estimatesThePromptSize() {
        Conversation c = new Conversation();
        assertEquals(0, c.estimateTokens("", true));
        c.messages.add(new ChatMessage(ChatMessage.USER, "12345678")); // 8 chars ≈ 2 tokens + 4 framing
        assertEquals(6, c.estimateTokens(null, true));
        assertEquals("a system prompt adds its text and framing", 6 + 4 + 1, c.estimateTokens("abcd", true));
        c.messages.add(ChatMessage.notice("notices are never sent, whatever their length", "info"));
        assertEquals(6, c.estimateTokens(null, true));
        ChatMessage img = new ChatMessage(ChatMessage.USER, "");
        img.images.add("AAAA");
        c.messages.add(img);
        assertEquals(6 + 4 + Conversation.IMAGE_TOKENS, c.estimateTokens(null, true));
        assertEquals("text-only models get no images", 6 + 4, c.estimateTokens(null, false));
    }

    @Test
    public void chatModelAndErrorKindArePersisted() throws Exception {
        ConversationStore store = new ConversationStore(tmp.newFolder("chats"));
        Conversation c = withImages();
        c.model = "qwen3:8b";
        ChatMessage failed = new ChatMessage(ChatMessage.ASSISTANT, "");
        failed.error = true;
        failed.errorKind = ReplyError.MODEL_MISSING;
        failed.stats = "gone";
        c.messages.add(failed);
        store.save(c);
        Conversation back = store.load(c.id);
        assertEquals("qwen3:8b", back.model);
        assertEquals(ReplyError.MODEL_MISSING, back.messages.get(back.messages.size() - 1).errorKind);
        assertEquals(2, back.messages.get(2).images.size());
        List<ConversationStore.Entry> list = store.list();
        assertEquals("qwen3:8b", list.get(0).model);

        // Chats saved before models were remembered load with "".
        Conversation old = Conversation.fromJson(new JSONObject("{\"id\":\"c1\",\"messages\":[]}"));
        assertEquals("", old.model);
        assertFalse(new JSONObject(old.toJson().toString()).has("model"));
    }
}
