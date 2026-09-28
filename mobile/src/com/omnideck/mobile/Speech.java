package com.omnideck.mobile;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads replies aloud with the phone's text-to-speech, sentence by sentence
 * while they stream in — the "voice" of the command center. Main thread only.
 */
final class Speech implements TextToSpeech.OnInitListener {
    private final Context app;
    private TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private float rate = 1f;
    /** Characters already queued per message id. */
    private final Map<String, Integer> spoken = new HashMap<String, Integer>();
    private final List<String> pending = new ArrayList<String>();
    private int utterance;

    Speech(Context app) {
        this.app = app.getApplicationContext();
    }

    boolean available() {
        return !failed;
    }

    void setRate(float r) {
        rate = r;
        if (tts != null) tts.setSpeechRate(r);
    }

    private void ensure() {
        if (tts == null && !failed) {
            try {
                tts = new TextToSpeech(app, this);
            } catch (RuntimeException e) {
                failed = true;
            }
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            failed = true;
            pending.clear();
            return;
        }
        ready = true;
        try {
            tts.setLanguage(Locale.getDefault());
        } catch (RuntimeException ignored) {
        }
        tts.setSpeechRate(rate);
        for (String p : pending) queue(p);
        pending.clear();
    }

    /**
     * Speaks the not-yet-spoken complete sentences of {@code text}; with
     * {@code done} also the trailing remainder.
     */
    void feed(String messageId, String text, boolean done) {
        Integer from = spoken.get(messageId);
        int start = from == null ? 0 : from;
        if (start >= text.length()) return;
        int end = done ? text.length() : lastSentenceEnd(text, start);
        if (end <= start) return;
        spoken.put(messageId, end);
        String chunk = clean(text.substring(start, end));
        if (chunk.length() == 0) return;
        ensure();
        if (failed) return;
        if (ready) queue(chunk);
        else pending.add(chunk);
    }

    /** Speaks a one-off line (status announcements). */
    void say(String line) {
        String c = clean(line);
        if (c.length() == 0) return;
        ensure();
        if (failed) return;
        if (ready) queue(c);
        else pending.add(c);
    }

    private void queue(String chunk) {
        try {
            tts.speak(chunk, TextToSpeech.QUEUE_ADD, (Bundle) null, "omni-" + (++utterance));
        } catch (RuntimeException ignored) {
        }
    }

    boolean speaking() {
        try {
            return tts != null && ready && tts.isSpeaking();
        } catch (RuntimeException e) {
            return false;
        }
    }

    void stop() {
        pending.clear();
        if (tts != null) {
            try {
                tts.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Marks everything in this message as spoken (e.g. read-aloud turned on mid-reply). */
    void skip(String messageId, int upTo) {
        spoken.put(messageId, upTo);
    }

    void shutdown() {
        stop();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
            }
            tts = null;
        }
        ready = false;
    }

    static int lastSentenceEnd(String t, int from) {
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

    /** Strips Markdown and code so the voice reads prose, not symbols. */
    static String clean(String s) {
        String t = s.replaceAll("(?s)```.*?(```|$)", " (code) ");
        t = t.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("https?://\\S+", "a link");
        t = t.replaceAll("[*_`#>|~]+", "");
        t = t.replaceAll("^\\s*[-+]\\s+", "");
        t = t.replaceAll("\\s+", " ");
        return t.trim();
    }
}
