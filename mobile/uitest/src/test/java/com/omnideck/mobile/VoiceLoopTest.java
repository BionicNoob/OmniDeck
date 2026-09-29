package com.omnideck.mobile;

import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.View;

import com.omnideck.mobile.core.ChatMessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowTextToSpeech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * The voice loop: a spoken question gets a spoken answer (even with
 * read-aloud off), hands-free listens again once the answer has been said,
 * and Stop speaking is on screen whenever the phone talks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class VoiceLoopTest extends Harness {

    /** Robolectric's TTS reports no installed voices; Speech (rightly) refuses to talk without one. */
    @Before
    public void installVoice() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.getDefault());
        ShadowTextToSpeech.addLanguageAvailability(Locale.US);
    }


    private ChatMessage last(String role) {
        return engine().conversation().lastOfRole(role);
    }

    /** The next speech recognizer the app opened (skipping others, e.g. the notifications prompt), or null. */
    private ShadowActivity.IntentForResult nextRecognizer() {
        ShadowActivity.IntentForResult r;
        while ((r = shadowOf(act).getNextStartedActivityForResult()) != null) {
            if (RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(r.intent.getAction())) return r;
        }
        return null;
    }

    /** The recognizer the app just opened; answers it with {@code heard}. */
    private void answerRecognizer(String heard) {
        ShadowActivity.IntentForResult r = nextRecognizer();
        assertNotNull("the recognizer was opened", r);
        Intent data = new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,
                new ArrayList<>(Collections.singletonList(heard)));
        act.onActivityResult(r.requestCode, Activity.RESULT_OK, data);
        idle();
    }

    private void waitReplyTo(String question) {
        waitFor("reply to " + question, () -> !engine().isBusy() && last(ChatMessage.ASSISTANT) != null
                && last(ChatMessage.ASSISTANT).content.startsWith("You said: " + question));
    }

    /** Robolectric's TTS needs its init callback run by hand. */
    private TextToSpeech ttsReady() {
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        assertNotNull("the phone's voice was started", tts);
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        idle();
        return tts;
    }

    @Test
    public void aSpokenQuestionGetsASpokenAnswer() throws Exception {
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        assertFalse("read-aloud is off", engine().settings.readAloud());
        click("Voice input");
        answerRecognizer("what is the status");
        assertEquals("what is the status", last(ChatMessage.USER).content);
        waitReplyTo("what is the status");
        String sys = ollama.lastChatRequest().getJSONArray("messages").getJSONObject(0).getString("content");
        assertTrue("the model is told the answer will be heard: " + sys, sys.contains("read aloud"));
        TextToSpeech tts = ttsReady();
        String spoken = shadowOf(tts).getLastSpokenText();
        assertNotNull(spoken);
        assertTrue(spoken, spoken.startsWith("You said: what is the status. Streaming works over the network"));

        // A typed question stays silent while read-aloud is off.
        shadowOf(tts).clearLastSpokenText();
        submit("typed question");
        waitReplyTo("typed question");
        assertFalse(ollama.lastChatRequest().getJSONArray("messages").getJSONObject(0).getString("content")
                .contains("read aloud"));
        advance(500);
        assertNull(shadowOf(tts).getLastSpokenText());
    }

    @Test
    public void theCommandTabsTalkTileIsAVoiceTurnToo() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        act.commander().run("/voice");
        idle();
        answerRecognizer("hello from the core");
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        waitReplyTo("hello from the core");
        TextToSpeech tts = ttsReady();
        assertTrue(shadowOf(tts).getLastSpokenText().startsWith("You said: hello from the core"));
    }

    @Test
    public void handsFreeListensAgainAfterTheSpokenReply() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        assertFalse(engine().settings.handsFree());
        click("Hands-free conversation");
        assertTrue(engine().settings.handsFree());
        assertTrue("selected state for TalkBack", button("Hands-free conversation").isSelected());
        // Turning it on opens the mic right away.
        answerRecognizer("first");
        waitReplyTo("first");
        ttsReady();
        assertTrue(act.comms().relistenPending());
        // Robolectric's TTS never reports "speaking", so after the start-up wait the mic opens again.
        advance(6500);
        assertFalse(act.comms().relistenPending());
        answerRecognizer("second");
        waitReplyTo("second");
        assertEquals(4, engine().conversation().messages.size());

        // Off again: no more listening.
        click("Hands-free conversation");
        assertFalse(engine().settings.handsFree());
        advance(7000);
        assertNull(nextRecognizer());
    }

    @Test
    public void stopSpeakingIsOnScreenWhileThePhoneTalks() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        assertNull(button("Stop speaking"));
        act.speakingOverride = true; // stands in for the TTS engine
        advance(800);
        assertTrue(act.isSpeaking());
        // Command: the AI core's own Stop (the top bar's stays hidden).
        assertEquals("one Stop control on Command", 1, stopControls());
        // Other pages: the top bar's.
        tab(MainActivity.TAB_MODELS);
        assertEquals("one Stop control on Models", 1, stopControls());
        assertNotNull("in the top bar on other pages", button("Stop speaking"));
        shoot("shell-light-speaking");

        // On the chat, the header's speaker button turns into the control instead.
        tab(MainActivity.TAB_COMMS);
        assertEquals("one Stop control at a time", 1, stopControls());
        assertNull(button("Read replies aloud"));
        advance(300);
        shoot("comms-light-speaking");
        click("Stop speaking");
        advance(800);
        assertFalse(act.isSpeaking());
        assertNull(button("Stop speaking"));
        assertNotNull(button("Read replies aloud"));
    }

    private int stopControls() {
        int stops = 0;
        for (View v : views()) {
            if (v.isShown() && "Stop speaking".contentEquals(v.getContentDescription() == null ? ""
                    : v.getContentDescription())) stops++;
        }
        return stops;
    }
}
