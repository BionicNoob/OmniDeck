package com.omnideck.mobile.core;

/**
 * What the voice reads of a reply: whole sentences, never code, no Markdown
 * symbols. Replies stream in, so {@link #next} is called with the growing
 * text and the offset already handled; the code-fence state is worked out
 * from the whole text each time, so a code block that opened in an earlier
 * chunk stays silent in the later ones. Plain Java (JVM-tested).
 */
public final class SpeechText {
    /** Said once where a fenced code block starts; the code itself is never read. */
    public static final String CODE = "(code)";

    private SpeechText() {}

    /** One step of reading a streaming reply. */
    public static final class Chunk {
        /** Text to speak now ("" when nothing new is speakable yet). */
        public final String speak;
        /** Offset in the reply up to which everything is handled (spoken or skipped). */
        public final int end;

        Chunk(String speak, int end) {
            this.speak = speak;
            this.end = end;
        }
    }

    /**
     * The next speakable part of {@code text} after offset {@code from}:
     * complete sentences and lines only — unless {@code done}, when the rest
     * is read too. Lines inside ``` / ~~~ fences are skipped (a code block is
     * announced once as {@link #CODE}); a line that may still turn into a fence
     * waits for more text.
     */
    public static Chunk next(String text, int from, boolean done) {
        String t = text == null ? "" : text;
        int n = t.length();
        int i = Math.max(0, Math.min(from, n));
        boolean fence = inFence(t, i);
        StringBuilder speak = new StringBuilder();
        int end = i;
        int seg = i; // start of prose not yet handed out
        while (i < n) {
            boolean lineStart = i == 0 || t.charAt(i - 1) == '\n';
            int nl = t.indexOf('\n', i);
            boolean complete = nl >= 0;
            int lineEnd = complete ? nl : n;
            if (fence && !lineStart) {
                // Resumed mid-line inside code (read-aloud switched on mid-reply): drop the rest of the line.
                if (!complete && !done) break;
                i = end = seg = complete ? nl + 1 : n;
                continue;
            }
            if (lineStart) {
                boolean fenceLine = fenceMarkerEnd(t, i, lineEnd) > 0;
                if (fenceLine || fence || (!complete && !done && couldBecomeFence(t, i, n))) {
                    if (!complete && !done) break; // wait for the whole line
                    if (fenceLine) {
                        if (!fence) speak.append(' ').append(CODE).append(' ');
                        fence = !fence;
                    }
                    i = end = seg = complete ? nl + 1 : n;
                    continue;
                }
            }
            char c = t.charAt(i++);
            boolean boundary = c == '\n'
                    || ((c == '.' || c == '!' || c == '?' || c == ':' || c == ';')
                    && i < n && Character.isWhitespace(t.charAt(i)));
            if (boundary) {
                speak.append(t, seg, i);
                end = seg = i;
            }
        }
        if (done && seg < n && !fence) {
            speak.append(t, seg, n);
        }
        if (done) end = n;
        return new Chunk(clean(speak.toString()), end);
    }

    /** Everything speakable in a finished text (announcements, reading one message aloud). */
    public static String speakable(String text) {
        return next(text, 0, true).speak;
    }

    /** True when offset {@code pos} lies inside a fenced code block. */
    public static boolean inFence(String text, int pos) {
        boolean in = false;
        int n = Math.min(pos, text.length());
        int i = 0;
        while (i < n) {
            int nl = text.indexOf('\n', i);
            int lineEnd = nl < 0 ? text.length() : nl;
            int marker = fenceMarkerEnd(text, i, lineEnd);
            if (marker > 0 && marker <= pos) in = !in;
            if (nl < 0) break;
            i = nl + 1;
        }
        return in;
    }

    /**
     * End of the ``` or ~~~ fence marker opening the line [start, lineEnd)
     * (any indentation), or -1 when the line isn't a fence line.
     */
    static int fenceMarkerEnd(String t, int start, int lineEnd) {
        int i = start;
        while (i < lineEnd && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
        if (i + 3 > lineEnd) return -1;
        char c = t.charAt(i);
        if ((c != '`' && c != '~') || t.charAt(i + 1) != c || t.charAt(i + 2) != c) return -1;
        return i + 3;
    }

    /** A partial last line that is still only indentation and/or the start of a fence marker. */
    private static boolean couldBecomeFence(String t, int start, int end) {
        int i = start;
        while (i < end && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
        if (i == end) return true;
        char c = t.charAt(i);
        if (c != '`' && c != '~') return false;
        for (int k = i; k < end; k++) {
            if (t.charAt(k) != c) return false;
        }
        return end - i < 3;
    }

    /**
     * Offset just past the last sentence end at or after {@code from} (a
     * newline, or . ! ? : ; followed by whitespace), or -1 when there is none.
     */
    public static int lastSentenceEnd(String t, int from) {
        int end = -1;
        for (int i = from; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean boundary = c == '\n'
                    || ((c == '.' || c == '!' || c == '?' || c == ':' || c == ';')
                    && i + 1 < t.length() && Character.isWhitespace(t.charAt(i + 1)));
            if (boundary) end = i + 1;
        }
        return end;
    }

    /** Strips Markdown so the voice reads prose, not symbols. */
    public static String clean(String s) {
        String t = s.replaceAll("!?\\[([^\\]]+)\\]\\([^)]*\\)", "$1");   // [text](url) → text
        t = t.replaceAll("https?://\\S+", "a link");
        t = t.replaceAll("</?[A-Za-z][A-Za-z0-9]*[^<>\\n]{0,40}>", " ");  // <br>, <sub>…
        t = t.replaceAll("(?m)^[ \\t|:-]*-{3,}[ \\t|:-]*$", " ");         // rules, table separators
        t = t.replaceAll("(?m)^[ \\t]*[-+*][ \\t]+", "");                  // list bullets
        t = t.replaceAll("(?<=\\w)_(?=\\w)", " ");                         // snake_case → words
        t = t.replaceAll("[*_`#>|~]+", "");
        t = t.replaceAll("\\s+", " ");
        return t.trim();
    }
}
