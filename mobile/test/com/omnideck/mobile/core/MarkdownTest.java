package com.omnideck.mobile.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarkdownTest {

    private static Markdown.Span only(Markdown.Result r, int type) {
        Markdown.Span found = null;
        for (Markdown.Span s : r.spans) {
            if (s.type == type) {
                if (found != null) throw new AssertionError("more than one span of type " + type + ": " + r.spans);
                found = s;
            }
        }
        if (found == null) throw new AssertionError("no span of type " + type + " in " + r.spans);
        return found;
    }

    private static String covered(Markdown.Result r, Markdown.Span s) {
        return r.text.substring(s.start, s.end);
    }

    @Test
    public void inlineStyles() {
        Markdown.Result r = Markdown.parse("Use **bold**, *italic*, `code` and ~~old~~ text.");
        assertEquals("Use bold, italic, code and old text.", r.text);
        assertEquals("bold", covered(r, only(r, Markdown.BOLD)));
        assertEquals("italic", covered(r, only(r, Markdown.ITALIC)));
        assertEquals("code", covered(r, only(r, Markdown.CODE)));
        assertEquals("old", covered(r, only(r, Markdown.STRIKE)));
    }

    @Test
    public void nestedAndUnderscoreStyles() {
        Markdown.Result r = Markdown.parse("**bold with `code` inside** and __also bold__ and _it_");
        assertEquals("bold with code inside and also bold and it", r.text);
        assertEquals(2, countType(r, Markdown.BOLD));
        assertEquals("code", covered(r, only(r, Markdown.CODE)));
        assertEquals("it", covered(r, only(r, Markdown.ITALIC)));
    }

    @Test
    public void snakeCaseAndLoneStarsStayLiteral() {
        Markdown.Result r = Markdown.parse("call my_var_name and 2 * 3 * 4 then snake_case_here");
        assertEquals("call my_var_name and 2 * 3 * 4 then snake_case_here", r.text);
        assertTrue(r.spans.isEmpty());
    }

    @Test
    public void codeBlocksKeepTheirContentVerbatim() {
        Markdown.Result r = Markdown.parse("Here:\n```python\nx = **not bold**\nprint(x)\n```\nDone.");
        assertEquals("Here:\nx = **not bold**\nprint(x)\nDone.", r.text);
        assertEquals("x = **not bold**\nprint(x)", covered(r, only(r, Markdown.CODE_BLOCK)));
        assertFalse(r.has(Markdown.BOLD));
    }

    @Test
    public void unclosedFenceWhileStreamingIsStillCode() {
        Markdown.Result r = Markdown.parse("Sure:\n```js\nconst a = 1;\nconst b");
        assertEquals("Sure:\nconst a = 1;\nconst b", r.text);
        assertEquals("const a = 1;\nconst b", covered(r, only(r, Markdown.CODE_BLOCK)));
    }

    @Test
    public void blocks() {
        Markdown.Result r = Markdown.parse("# Title\n## Sub\n- one\n* two\n1. first\n> quoted **x**\n---\n- [x] done");
        assertEquals("Title\nSub\n• one\n• two\n1. first\nquoted x\n────────────\n☑ done", r.text);
        assertEquals("Title", covered(r, only(r, Markdown.H1)));
        assertEquals("Sub", covered(r, only(r, Markdown.H2)));
        assertEquals("quoted x", covered(r, only(r, Markdown.QUOTE)));
        assertEquals(1, countType(r, Markdown.RULE));
        assertEquals(3, countType(r, Markdown.BULLET));
    }

    @Test
    public void linksAndBareUrls() {
        Markdown.Result r = Markdown.parse("See [the docs](https://ollama.com/docs) or https://github.com/ollama/ollama.");
        assertEquals("See the docs or https://github.com/ollama/ollama.", r.text);
        int links = 0;
        for (Markdown.Span s : r.spans) {
            if (s.type != Markdown.LINK) continue;
            links++;
            if (covered(r, s).equals("the docs")) assertEquals("https://ollama.com/docs", s.url);
            else assertEquals("https://github.com/ollama/ollama", covered(r, s));
        }
        assertEquals(2, links);
    }

    @Test
    public void tablesAreMonospaced() {
        Markdown.Result r = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |");
        assertEquals(3, countType(r, Markdown.TABLE));
        assertTrue(r.text.contains("| a | b |"));
    }

    @Test
    public void escapesAndBlankLines() {
        Markdown.Result r = Markdown.parse("\\*not italic\\*\n\n\n\nnext");
        assertEquals("*not italic*\n\nnext", r.text);
        assertTrue(r.spans.isEmpty());
    }

    @Test
    public void emptyAndNull() {
        assertEquals("", Markdown.parse("").text);
        assertEquals("", Markdown.parse(null).text);
    }

    @Test
    public void spansStayInBounds() {
        String[] samples = {"**a", "`", "```", "[x](", "*", "__", "~~~", "# ", "- ", "> ", "|", "a\n\n```\n\n", "**x**\n\n"};
        for (String s : samples) {
            Markdown.Result r = Markdown.parse(s);
            for (Markdown.Span sp : r.spans) {
                assertTrue(s + " -> " + sp, sp.start >= 0 && sp.end <= r.text.length() && sp.start < sp.end);
            }
        }
    }

    private static int countType(Markdown.Result r, int type) {
        int n = 0;
        for (Markdown.Span s : r.spans) {
            if (s.type == type) n++;
        }
        return n;
    }
}
