package com.omnideck.mobile.core;

/**
 * Splits streamed content into answer text and "thinking" text for models
 * that inline their reasoning as {@code <think>…</think>} instead of using
 * Ollama's separate "thinking" field. Tags may arrive split across chunks.
 */
public final class ThinkSplitter {
    public interface Sink {
        void content(String s);

        void thinking(String s);
    }

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder pending = new StringBuilder();
    private boolean inThink;

    public void feed(String chunk, Sink sink) {
        if (chunk == null || chunk.length() == 0) return;
        pending.append(chunk);
        while (true) {
            String tag = inThink ? CLOSE : OPEN;
            int idx = pending.indexOf(tag);
            if (idx >= 0) {
                emit(pending.substring(0, idx), sink);
                pending.delete(0, idx + tag.length());
                inThink = !inThink;
                continue;
            }
            // Hold back a tail that could be the start of a split tag.
            int keep = partialTagSuffix(pending, tag);
            int emitLen = pending.length() - keep;
            if (emitLen > 0) {
                emit(pending.substring(0, emitLen), sink);
                pending.delete(0, emitLen);
            }
            return;
        }
    }

    /** Emits anything still held back (end of stream). */
    public void flush(Sink sink) {
        if (pending.length() > 0) {
            emit(pending.toString(), sink);
            pending.setLength(0);
        }
    }

    public boolean inThink() {
        return inThink;
    }

    private void emit(String s, Sink sink) {
        if (s.length() == 0) return;
        if (inThink) sink.thinking(s);
        else sink.content(s);
    }

    /** Length of the longest suffix of buf that is a proper prefix of tag. */
    static int partialTagSuffix(CharSequence buf, String tag) {
        int max = Math.min(tag.length() - 1, buf.length());
        for (int len = max; len > 0; len--) {
            boolean match = true;
            int start = buf.length() - len;
            for (int i = 0; i < len; i++) {
                if (buf.charAt(start + i) != tag.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) return len;
        }
        return 0;
    }
}
