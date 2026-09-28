package com.omnideck.mobile.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpeechTextTest {

    /** Streams {@code chunks} the way the engine does (growing text, offset kept) and returns every spoken piece. */
    private static List<String> stream(String... chunks) {
        List<String> said = new ArrayList<String>();
        StringBuilder text = new StringBuilder();
        int at = 0;
        for (int i = 0; i < chunks.length; i++) {
            text.append(chunks[i]);
            SpeechText.Chunk c = SpeechText.next(text.toString(), at, false);
            assertTrue("offsets only move forward", c.end >= at);
            at = c.end;
            if (c.speak.length() > 0) said.add(c.speak);
        }
        SpeechText.Chunk last = SpeechText.next(text.toString(), at, true);
        assertEquals(text.length(), last.end);
        if (last.speak.length() > 0) said.add(last.speak);
        return said;
    }

    private static String joined(List<String> said) {
        StringBuilder sb = new StringBuilder();
        for (String s : said) sb.append(sb.length() > 0 ? " | " : "").append(s);
        return sb.toString();
    }

    @Test
    public void codeThatArrivesInLaterChunksIsNeverRead() {
        List<String> said = stream("Here is the fix:\n```python\nx = 1\n", "y = 2\n", "print(x. y)\n",
                "```\nThat's it. Enjoy", " the code.");
        String all = joined(said);
        assertEquals("Here is the fix: (code) | That's it. | Enjoy the code.", all);
        assertFalse(all, all.contains("y = 2"));
        assertFalse(all, all.contains("print"));
    }

    @Test
    public void fenceMarkersSplitAcrossChunksWaitForTheWholeLine() {
        List<String> said = stream("Look:\n`", "``", "js\nlet a = 1;\n``", "`\nDone.");
        assertEquals("Look: | (code) | Done.", joined(said));
        // Tilde fences and indented fences (inside list items) count too.
        assertEquals("Steps: (code) After.", SpeechText.speakable("Steps:\n  ~~~\n  rm -rf build\n  ~~~\nAfter."));
    }

    @Test
    public void unclosedCodeAtTheEndStaysSilent() {
        assertEquals("Intro: (code)", SpeechText.speakable("Intro:\n```\nsecret = 42\nmore()"));
        assertTrue(SpeechText.inFence("a\n```\ncode", 8));
        assertFalse(SpeechText.inFence("a\n```\ncode\n```\nb", 15));
        assertFalse("the opening line itself isn't inside yet", SpeechText.inFence("a\n```\ncode", 3));
    }

    @Test
    public void onlyCompleteSentencesAreReadWhileStreaming() {
        SpeechText.Chunk c = SpeechText.next("Hello there. How are", 0, false);
        assertEquals("Hello there.", c.speak);
        assertEquals("Hello there.".length(), c.end);
        SpeechText.Chunk rest = SpeechText.next("Hello there. How are you?", c.end, true);
        assertEquals("How are you?", rest.speak);
        assertEquals("", SpeechText.next("no boundary yet", 0, false).speak);
        assertEquals(0, SpeechText.next("no boundary yet", 0, false).end);
    }

    @Test
    public void resumingMidLineInsideCodeSkipsTheRestOfTheLine() {
        String t = "Run:\n```\nls -la /tmp\n```\nOk.";
        int mid = t.indexOf("-la");
        SpeechText.Chunk c = SpeechText.next(t, mid, true);
        assertEquals("Ok.", c.speak);
    }

    @Test
    public void cleansMarkdownLinksListsAndSymbols() {
        assertEquals("See the docs for more.", SpeechText.clean("See [the docs](https://ollama.com/docs) for more."));
        assertEquals("Visit a link now", SpeechText.clean("Visit https://example.com/a?b=1 now"));
        assertEquals("First Second", SpeechText.clean("- First\n* Second"));
        assertEquals("Title Bold and italic", SpeechText.clean("## Title\n**Bold** and _italic_"));
        assertEquals("Run get system info", SpeechText.clean("Run `get_system_info`"));
        assertEquals("a b c d", SpeechText.clean("| a | b |\n|---|:---:|\n| c | d |"));
        assertEquals("line one line two", SpeechText.clean("line one<br>line two"));
        assertEquals("", SpeechText.clean("---"));
    }

    @Test
    public void legacySentenceEnds() {
        assertEquals(4, SpeechText.lastSentenceEnd("Hi.\nOk", 0));
        assertEquals(-1, SpeechText.lastSentenceEnd("3.14 is pi", 0));
        assertEquals(3, SpeechText.lastSentenceEnd("Hi! there", 0));
    }
}
