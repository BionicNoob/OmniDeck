package com.omnideck.mobile;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import com.omnideck.mobile.core.SpeechText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads replies aloud with the phone's text-to-speech, sentence by sentence
 * while they stream in — the "voice" of the command center. What is read
 * (no code, no Markdown) comes from {@link SpeechText}. When the phone has no
 * working engine or voice, the listener hears about it instead of speech
 * silently doing nothing. Main thread only.
 */
final class Speech implements TextToSpeech.OnInitListener {
    /** Hears when the phone turns out not to be able to speak. Main thread. */
    interface Listener {
        void onUnavailable(String reason);
    }

    static final String NO_ENGINE = "Read-aloud needs a text-to-speech engine, and none works on this phone. "
            + "Install or turn one on in Android Settings › Accessibility › Text-to-speech.";
    static final String NO_VOICE = "No text-to-speech voice is installed for your language. Add one in Android "
            + "Settings › Accessibility › Text-to-speech.";

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private String problem = "";
    private Listener listener;
    private float rate = 1f;
    /** Characters of the streaming reply already handled (spoken or skipped). */
    private final Map<String, Integer> spoken = new HashMap<String, Integer>();
    private String lastFed;
    private final List<String> pending = new ArrayList<String>();
    private int utterance;

    Speech(Context app) {
        this.app = app.getApplicationContext();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** False once text-to-speech turned out to be missing or broken on this phone. */
    boolean available() {
        return !failed;
    }

    /** Why the phone can't speak; "" while it can (or before that is known). */
    String problem() {
        return problem;
    }

    /** After a failure, lets the next request try the engine again (a voice may have been installed). */
    void retry() {
        if (!failed) return;
        failed = false;
        problem = "";
    }

    /** Starts the engine now — it takes a moment — so a missing voice is reported before the first reply. */
    void prepare() {
        ensure();
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
                tts = null;
                fail(NO_ENGINE);
            }
        }
    }

    @Override
    public void onInit(final int status) {
        // Some engines call back on a binder thread; everything here is main-thread state.
        main.post(new Runnable() {
            @Override
            public void run() {
                init(status);
            }
        });
    }

    private void init(int status) {
        if (tts == null || ready) return; // shut down meanwhile, or a duplicate callback
        if (status != TextToSpeech.SUCCESS) {
            fail(NO_ENGINE);
            return;
        }
        if (!pickLanguage()) {
            fail(NO_VOICE);
            return;
        }
        ready = true;
        tts.setSpeechRate(rate);
        for (String p : pending) queue(p);
        pending.clear();
    }

    /** The phone's language, else English: engines often ship English voices only. */
    private boolean pickLanguage() {
        Locale[] tries = {Locale.getDefault(), Locale.US, Locale.UK};
        for (Locale l : tries) {
            try {
                if (tts.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE) return true;
            } catch (RuntimeException ignored) {
                // Broken engine: try the next language, then give up.
            }
        }
        return false;
    }

    private void fail(String why) {
        pending.clear();
        ready = false;
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
            }
            tts = null;
        }
        if (failed) return;
        failed = true;
        problem = why;
        if (listener != null) listener.onUnavailable(why);
    }

    /**
     * Speaks the not-yet-spoken complete sentences of {@code text}; with
     * {@code done} also the trailing remainder.
     */
    void feed(String messageId, String text, boolean done) {
        if (lastFed != null && !lastFed.equals(messageId)) spoken.remove(lastFed);
        lastFed = messageId;
        Integer from = spoken.get(messageId);
        int start = from == null ? 0 : from;
        SpeechText.Chunk c = SpeechText.next(text, start, done);
        if (c.end > start) spoken.put(messageId, c.end);
        if (c.speak.length() == 0) return;
        ensure();
        if (failed) return;
        if (ready) queue(c.speak);
        else pending.add(c.speak);
    }

    /** Speaks a one-off line (status announcements, reading one message). */
    void say(String line) {
        String c = SpeechText.speakable(line);
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
        lastFed = messageId;
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
}
