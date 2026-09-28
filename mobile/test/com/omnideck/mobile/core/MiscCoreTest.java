package com.omnideck.mobile.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MiscCoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---------------- Commands ----------------

    @Test
    public void parsesCommandsAndAliases() {
        Commands.Parsed p = Commands.parse("  /Model   llama3.2:3b  ");
        assertNotNull(p);
        assertEquals("/model", p.name);
        assertEquals("llama3.2:3b", p.arg);
        assertEquals("/model", p.cmd.name);

        assertEquals("/reset", Commands.parse("/new").cmd.name);
        assertEquals("/reset", Commands.parse("/clear").cmd.name);
        assertEquals("/appearance", Commands.parse("/theme light").cmd.name);
        assertEquals("light", Commands.parse("/theme light").arg);
        assertNull(Commands.parse("/zzz").cmd);
        assertNull(Commands.parse("hello"));
        assertNull(Commands.parse("/"));
        assertNull(Commands.parse("/ spaced"));
        assertNull(Commands.parse("//escaped slash"));
        assertEquals("/timer", Commands.parse("/timer 5m tea\nand more").name);
        assertEquals("5m tea\nand more", Commands.parse("/timer 5m tea\nand more").arg);
    }

    @Test
    public void suggestions() {
        List<Commands.Cmd> s = Commands.suggest("/mo", 6);
        assertEquals("/model", s.get(0).name);
        assertEquals("/models", s.get(1).name);
        // A single letter only prefix-matches (no noise from descriptions).
        for (Commands.Cmd c : Commands.suggest("/m", 20)) assertTrue(c.name, c.name.startsWith("/m"));
        // Aliases count: "/new" and "/clear" find /reset.
        assertEquals("/reset", Commands.suggest("/new", 6).get(0).name);
        assertTrue(Commands.suggest("/model llama", 6).isEmpty());
        assertTrue(Commands.suggest("hello", 6).isEmpty());
        assertEquals(6, Commands.suggest("/", 6).size());
        // PC-app-only commands are not suggested.
        for (Commands.Cmd c : Commands.suggest("/w", 20)) assertFalse(c.name.equals("/web"));
        // Substring match on description.
        boolean vol = false;
        for (Commands.Cmd c : Commands.suggest("/volume", 6)) vol |= c.name.equals("/vol");
        assertTrue(vol);
    }

    @Test
    public void helpListsEveryCommand() {
        String help = Commands.helpText();
        for (Commands.Cmd c : Commands.ALL) assertTrue(c.name, help.contains(c.usage));
        List<String> names = new ArrayList<String>();
        for (Commands.Cmd c : Commands.ALL) {
            assertFalse("duplicate " + c.name, names.contains(c.name));
            names.add(c.name);
        }
    }

    // ---------------- ThinkSplitter ----------------

    private static String[] split(String... chunks) {
        final StringBuilder content = new StringBuilder(), thinking = new StringBuilder();
        ThinkSplitter.Sink sink = new ThinkSplitter.Sink() {
            @Override
            public void content(String s) {
                content.append(s);
            }

            @Override
            public void thinking(String s) {
                thinking.append(s);
            }
        };
        ThinkSplitter t = new ThinkSplitter();
        for (String c : chunks) t.feed(c, sink);
        t.flush(sink);
        return new String[]{content.toString(), thinking.toString()};
    }

    @Test
    public void thinkSplitterHandlesSplitTags() {
        String[] r = split("<", "think", ">a", "b</", "think>", "answer <b>bold</b>");
        assertEquals("answer <b>bold</b>", r[0]);
        assertEquals("ab", r[1]);
        r = split("plain text with < and <th");
        assertEquals("plain text with < and <th", r[0]);
        assertEquals("", r[1]);
        r = split("<think>unfinished thought");
        assertEquals("", r[0]);
        assertEquals("unfinished thought", r[1]);
        assertEquals(4, ThinkSplitter.partialTagSuffix("abc<thi", "<think>"));
        assertEquals(0, ThinkSplitter.partialTagSuffix("abc", "<think>"));
    }

    // ---------------- Fmt ----------------

    @Test
    public void durationsAndSizes() {
        assertEquals(300, Fmt.parseDuration("5m"));
        assertEquals(90, Fmt.parseDuration("90s"));
        assertEquals(3600, Fmt.parseDuration("1h"));
        assertEquals(5400, Fmt.parseDuration("1h30m"));
        assertEquals(150, Fmt.parseDuration("2.5m"));
        assertEquals(600, Fmt.parseDuration("10"));
        assertEquals(-1, Fmt.parseDuration("tea"));
        assertEquals(-1, Fmt.parseDuration("5x"));
        assertEquals(-1, Fmt.parseDuration(""));
        assertEquals("1.9 GB", Fmt.bytes(2019393189L));
        assertEquals("512 B", Fmt.bytes(512));
        assertEquals("1m 30s", Fmt.duration(90));
        assertEquals("1h 0m 5s", Fmt.duration(3605));
        assertEquals("abc…", Fmt.ellipsize("abcdefgh", 4));
    }

    // ---------------- Conversations ----------------

    @Test
    public void conversationRoundTripsThroughTheStore() throws Exception {
        ConversationStore store = new ConversationStore(tmp.newFolder("chats"));
        Conversation c = new Conversation();
        c.messages.add(new ChatMessage(ChatMessage.USER, "What is Ollama?\nTell me."));
        ChatMessage a = new ChatMessage(ChatMessage.ASSISTANT, "A local LLM runner.");
        a.thinking = "hmm";
        a.model = "llama3.2:3b";
        a.stats = "12 tok";
        c.messages.add(a);
        ChatMessage n = ChatMessage.notice("Model switched", "ok");
        c.messages.add(n);
        c.autoTitle();
        assertEquals("What is Ollama? Tell me.", c.title);
        store.save(c);

        Conversation back = store.load(c.id);
        assertNotNull(back);
        assertEquals(3, back.messages.size());
        assertEquals("hmm", back.messages.get(1).thinking);
        assertEquals("llama3.2:3b", back.messages.get(1).model);
        assertEquals("ok", back.messages.get(2).tone);
        assertTrue(back.messages.get(2).isNotice());
        assertEquals(a.id, back.messages.get(1).id);

        Conversation empty = new Conversation();
        empty.messages.add(ChatMessage.notice("hi", "info"));
        store.save(empty);
        List<ConversationStore.Entry> list = store.list();
        assertEquals("empty chats are hidden", 1, list.size());
        assertEquals(2, list.get(0).count);
        assertTrue(store.delete(c.id));
        assertNull(store.load(c.id));
        assertTrue(c.toMarkdown().contains("**llama3.2:3b:**"));
    }
}
