package com.omnideck.mobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.mock.MockBridge;
import com.omnideck.mobile.mock.MockOllama;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * What you can do to a conversation on the Comms tab, end to end: message
 * actions (edit & resend with images, regenerate, share, delete), a reply
 * that fails mid-stream and its Retry, the context warning, incognito, a
 * cold start, and /open's app picker — each against the mock servers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CommsActionsTest extends Harness {

    private ChatMessage last(String role) {
        return engine().conversation().lastOfRole(role);
    }

    private String lastNotice() {
        ChatMessage n = last(ChatMessage.NOTICE);
        return n == null ? "" : n.content;
    }

    private List<ChatMessage> messages() {
        return engine().conversation().messages;
    }

    private void chatAndWait(String text) {
        int before = messages().size();
        submit(text);
        waitFor("reply to " + text, () -> !engine().isBusy() && messages().size() >= before + 2);
    }

    /** Long-presses the message showing {@code text}; returns its actions sheet. */
    private AlertDialog actionsFor(String text) {
        // The message body (long-pressable), not the chat title that may repeat the first question.
        TextView v = null;
        String q = text.toLowerCase(Locale.US);
        for (View x : views()) {
            if (x instanceof TextView && x.isShown() && x.isLongClickable()
                    && ((TextView) x).getText().toString().toLowerCase(Locale.US).contains(q)) {
                v = (TextView) x;
                break;
            }
        }
        assertNotNull("on screen: " + text, v);
        v.performLongClick();
        idle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(d);
        assertTrue(d.isShowing());
        return d;
    }

    private static TextView row(Dialog d, String title) {
        List<View> all = new ArrayList<>();
        collect(d.getWindow().getDecorView(), all);
        for (View v : all) {
            if (v instanceof TextView && title.contentEquals(((TextView) v).getText())) return (TextView) v;
        }
        return null;
    }

    private static boolean dialogShows(Dialog d, String text) {
        List<View> all = new ArrayList<>();
        collect(d.getWindow().getDecorView(), all);
        String q = text.toLowerCase(Locale.US);
        for (View v : all) {
            if (v instanceof TextView && ((TextView) v).getText().toString().toLowerCase(Locale.US).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Engine.reset(), then drops what the old engine's in-flight requests post
     * back to the shared main looper after its executors shut down (in a real
     * process death they die with it).
     */
    static void killProcess() throws InterruptedException {
        Engine.reset();
        long end = System.currentTimeMillis() + 400;
        while (System.currentTimeMillis() < end) {
            try {
                idle();
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // A stale callback from the dead engine.
            }
            Thread.sleep(10);
        }
    }

    private void useVisionModel() {
        submit("/model llava");
        waitFor("llava with vision", () -> "llava:7b".equals(engine().currentModel())
                && Boolean.TRUE.equals(engine().supportsVision("llava:7b")));
    }

    // ------------------------------------------------------------------
    // Message actions
    // ------------------------------------------------------------------

    @Test
    public void editAndResendKeepsTheImages() throws Exception {
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false));
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        useVisionModel();
        act.comms().addAttachment(MockBridge.PNG_1PX, null);
        idle();
        assertEquals(1, act.comms().pendingImages().size());
        assertNotNull(button("Remove image"));
        chatAndWait("what is in this photo?");
        assertEquals(1, last(ChatMessage.USER).images.size());
        assertTrue("sent images leave the composer", act.comms().pendingImages().isEmpty());

        AlertDialog d = actionsFor("what is in this photo?");
        assertNotNull(row(d, "Edit & resend"));
        assertNull("Regenerate belongs to replies", row(d, "Regenerate"));
        CommsFlowTest.clickDialogRow(d, "Edit & resend");
        assertEquals("what is in this photo?", composer().getText().toString());
        assertEquals("the photo comes back too", Collections.singletonList(MockBridge.PNG_1PX),
                act.comms().pendingImages());
        assertNull("the message is gone", last(ChatMessage.USER));
        assertNull("and so is its reply", last(ChatMessage.ASSISTANT));
        assertNotNull(button("Remove image"));
        shoot("comms-dark-edit-resend");

        composer().setText("what colour is it?");
        idle();
        click("Send");
        waitFor("reply", () -> !engine().isBusy() && last(ChatMessage.ASSISTANT) != null);
        JSONArray sent = ollama.lastChatRequest().getJSONArray("messages");
        JSONObject user = sent.getJSONObject(sent.length() - 1);
        assertEquals("what colour is it?", user.getString("content"));
        assertEquals(MockBridge.PNG_1PX, user.getJSONArray("images").getString(0));
    }

    @Test
    public void regenerateShareAndDelete() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("first question");
        chatAndWait("second question");
        int n = messages().size();

        ollama.replier = (r, text) -> MockOllama.words("A fresh answer to " + text + ".");
        AlertDialog d = actionsFor("You said: second question");
        shootDialog("comms-light-actions");
        CommsFlowTest.clickDialogRow(d, "Regenerate");
        waitFor("regenerated", () -> !engine().isBusy()
                && last(ChatMessage.ASSISTANT).content.startsWith("A fresh answer"));
        assertEquals(n, messages().size());
        assertTrue(shows("A fresh answer to second question."));
        assertFalse(shows("You said: second question"));

        // Regenerate is only offered on the latest reply; Share hands the text to other apps.
        d = actionsFor("You said: first question");
        assertNull(row(d, "Regenerate"));
        CommsFlowTest.clickDialogRow(d, "Share");
        Intent chooser = shadowOf(act).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT).startsWith("You said: first question."));

        // Delete removes just that message, from the screen and the saved chat.
        d = actionsFor("You said: first question");
        CommsFlowTest.clickDialogRow(d, "Delete");
        assertEquals(n - 1, messages().size());
        assertFalse(shows("You said: first question"));
        assertTrue("the question stays", shows("first question"));
    }

    // ------------------------------------------------------------------
    // A failed reply
    // ------------------------------------------------------------------

    @Test
    public void aFailedReplyExplainsItselfAndRetries() throws Exception {
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        ollama.midStreamError = "llama runner process has terminated: out of memory";
        submit("tell me something");
        waitFor("failed", () -> !engine().isBusy() && last(ChatMessage.ASSISTANT) != null
                && last(ChatMessage.ASSISTANT).error);
        ChatMessage failed = last(ChatMessage.ASSISTANT);
        assertTrue("the partial text is kept", failed.content.startsWith("You said:"));
        assertTrue(failed.stats, failed.stats.contains("out of memory"));
        assertEquals(1, engine().telemetry.errors);
        assertTrue("a plain-language reason", shows("ran out of memory"));
        assertTrue("the raw error under it", shows("llama runner process has terminated"));
        assertNotNull(button("Retry this reply"));
        assertNotNull("the fix for running out of memory", button("Open Settings to lower the context size"));
        advance(300);
        shoot("comms-cyber-failed");

        // The link is still up (checkHealth ran), so Retry answers again.
        assertEquals(Engine.State.ONLINE, engine().state());
        ollama.midStreamError = null;
        click("Retry this reply");
        waitFor("recovered", () -> !engine().isBusy() && !last(ChatMessage.ASSISTANT).error
                && last(ChatMessage.ASSISTANT).content.startsWith("You said: tell me something"));
        assertEquals(2, messages().size());
        assertNull(button("Retry this reply"));
        assertFalse(shows("ran out of memory"));
    }

    @Test
    public void aMissingModelOffersToDownloadIt() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        // The model is deleted on the PC behind the phone's back.
        ollama.clearModels();
        ollama.addModel(new MockOllama.Model("qwen3:8b", 5225388164L, "8.2B", "Q4_K_M", true));
        submit("still there?");
        waitFor("failed", () -> !engine().isWorking() && last(ChatMessage.ASSISTANT) != null
                && last(ChatMessage.ASSISTANT).error);
        ChatMessage failed = last(ChatMessage.ASSISTANT);
        assertEquals(com.omnideck.mobile.core.ReplyError.MODEL_MISSING, failed.errorKind);
        assertTrue(shows("llama3.2:3b isn't installed on the PC"));
        View pull = button("Download llama3.2:3b onto the PC");
        assertNotNull(pull);
        assertTrue(((TextView) pull).getText().toString().contains("/pull llama3.2:3b"));
        advance(300);
        shoot("comms-light-model-missing");
        pull.performClick();
        waitFor("downloaded", () -> lastNotice().contains("**llama3.2:3b** is downloaded"));
        assertTrue(ollama.hasModel("llama3.2:3b"));
    }

    @Test
    public void aTextOnlyModelOffersAVisionModel() throws Exception {
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false));
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        java.util.concurrent.atomic.AtomicBoolean known = new java.util.concurrent.atomic.AtomicBoolean();
        engine().fetchDetails("llava:7b", (d, err) -> known.set(true));
        waitFor("llava details", known::get);
        // A reply the PC refused because the model can't see the image.
        ChatMessage q = new ChatMessage(ChatMessage.USER, "what is this?");
        q.images.add(MockBridge.PNG_1PX);
        ChatMessage f = new ChatMessage(ChatMessage.ASSISTANT, "");
        f.model = "llama3.2:3b";
        f.error = true;
        f.errorKind = com.omnideck.mobile.core.ReplyError.NO_VISION;
        f.stats = "llama3.2:3b can't see images. Switch to a vision model (llava, gemma3, qwen2.5vl…) or send the "
                + "message again without the image. · image input is not supported (HTTP 400)";
        messages().add(q);
        messages().add(f);
        act.onConversationReplaced();
        idle();
        assertTrue(shows("can't see images"));
        assertTrue(shows("image input is not supported"));
        click("Switch to a model that can see images");
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(d.isShowing());
        assertTrue(dialogShows(d, "llava:7b"));
        CommsFlowTest.clickDialogRow(d, "llava:7b");
        assertEquals("llava:7b", engine().currentModel());
    }

    @Test
    public void stopAlsoStopsACompaction() throws Exception {
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("one");
        chatAndWait("two");
        ollama.tokenDelayMs = 80;
        submit("/compact");
        waitFor("compacting", () -> engine().isWorking());
        assertFalse("no reply streams", engine().isBusy());
        assertNotNull("the send button is Stop while OMNI works", button("Stop"));
        click("Stop");
        waitFor("stopped", () -> !engine().isWorking());
        assertTrue(lastNotice().contains("Compaction stopped"));
        assertNotNull(button("Voice input"));
    }

    @Test
    public void theArchiveNamesEachChatsModel() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("remember the milk");
        click("Chat history");
        waitFor("archive", () -> shows("remember the milk") && shows("2 messages"));
        assertTrue("the chat's model, in its own case", shows("llama3.2:3b · 2 messages"));
        advance(300);
        shoot("comms-light-archive");
    }

    @Test
    public void aDroppedConnectionSaysSo() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        ollama.midStreamError = "connection reset by peer";
        submit("are you there");
        waitFor("failed", () -> !engine().isBusy() && last(ChatMessage.ASSISTANT) != null
                && last(ChatMessage.ASSISTANT).error);
        // The plain words from the reply's footer (ReplyError), the raw error under them.
        assertTrue(shows("connection to the PC dropped"));
        assertTrue(shows("connection reset by peer"));
        ollama.midStreamError = null;
        chatAndWait("and now?");
        assertTrue("the next send works", last(ChatMessage.ASSISTANT).content.startsWith("You said: and now?"));
        assertNull("Retry is only offered on the latest reply", button("Retry this reply"));
    }

    @Test
    public void aMessageTypedOfflineSendsWhenTheLinkIsBack() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        int port = ollama.port();
        ollama.stop();
        waitFor("link lost", () -> engine().state() != Engine.State.ONLINE);
        submit("are you back?");
        assertTrue(act.comms().waitingForLink());
        assertTrue(shows("Waiting for your AI"));
        assertEquals("kept in the composer", "are you back?", composer().getText().toString());
        advance(300);
        shoot("comms-dark-waiting");
        ollama = MockOllama.start("127.0.0.1", port);
        waitFor("sent once the link is back", () -> last(ChatMessage.USER) != null
                && "are you back?".equals(last(ChatMessage.USER).content));
        waitFor("reply", () -> !engine().isBusy() && last(ChatMessage.ASSISTANT) != null);
        assertFalse(act.comms().waitingForLink());
        assertEquals("", composer().getText().toString());

        // Cancel (or emptying the composer) means it isn't sent.
        ollama.stop();
        waitFor("link lost again", () -> engine().state() != Engine.State.ONLINE);
        submit("never mind");
        assertTrue(act.comms().waitingForLink());
        click("Don't send when the link is back");
        assertFalse(act.comms().waitingForLink());
        ollama = MockOllama.start("127.0.0.1", port);
        waitFor("back", () -> engine().state() == Engine.State.ONLINE && !engine().models().isEmpty());
        advance(500);
        assertEquals("are you back?", last(ChatMessage.USER).content);
        assertEquals("never mind", composer().getText().toString());
    }

    // ------------------------------------------------------------------
    // Context window
    // ------------------------------------------------------------------

    @Test
    public void aNearlyFullContextOffersCompact() throws Exception {
        // The warning follows the Engine's estimate of this chat (about 4 characters a token, plus
        // framing): with num_ctx 60, one exchange of ~150 characters fills it to ~80%.
        prefs().edit().putInt("num_ctx", 60).commit();
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("one");
        assertFalse("a short exchange fits", act.comms().contextWarningShown());
        chatAndWait("two, a longer question with a few more words in it");
        assertTrue(engine().contextFill() >= 0.8);
        assertTrue(act.comms().contextWarningShown());
        assertTrue(shows("% full") || shows("Context full"));
        advance(300);
        shoot("comms-light-context");
        chatAndWait("three");
        click("Compact the chat");
        assertFalse(act.comms().contextWarningShown());
        waitFor("compacted", () -> lastNotice().contains("Compacted"));
        assertEquals(ChatMessage.SYSTEM, messages().get(0).role);
        assertFalse(act.comms().contextWarningShown());
    }

    // ------------------------------------------------------------------
    // Incognito and cold start
    // ------------------------------------------------------------------

    @Test
    public void incognitoChatsLeaveNoFiles() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        File dir = new File(act.getFilesDir(), "chats");
        submit("/incognito on");
        waitFor("incognito", () -> engine().settings.incognito());
        chatAndWait("secret plans");
        String id = engine().conversation().id;
        final File f = new File(dir, id + ".json");
        advance(500);
        assertFalse("nothing written while incognito", f.exists());
        assertTrue("the header says so", shows("incognito"));

        // Turning it off closes the private chat without ever writing it…
        submit("/incognito off");
        waitFor("incognito off", () -> !engine().settings.incognito());
        advance(500);
        assertFalse("the private chat is never written", f.exists());
        assertFalse("a fresh chat is open", id.equals(engine().conversation().id));
        // …and the next chat is saved normally.
        chatAndWait("public plans");
        final File g = new File(dir, engine().conversation().id + ".json");
        waitFor("saved once it's off", g::exists);
    }

    @Test
    public void coldStartRestoresTheChatAndTheDraft() throws Exception {
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("remember this");
        String id = engine().conversation().id;
        final File f = new File(new File(act.getFilesDir(), "chats"), id + ".json");
        waitFor("chat saved", f::exists);
        composer().setText("half-typed thought");
        idle();
        ctl.pause().stop();
        ctl.destroy();
        ctl = null;
        // Android kills the process in the background…
        killProcess();
        // …and starts it again later.
        ctl = Robolectric.buildActivity(MainActivity.class).setup();
        act = ctl.get();
        idle();
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        assertEquals("the open chat comes back", id, engine().conversation().id);
        waitFor("chat on screen", () -> shows("You said: remember this"));
        assertEquals("the draft comes back", "half-typed thought", composer().getText().toString());
    }

    // ------------------------------------------------------------------
    // /open through LaunchBridge
    // ------------------------------------------------------------------

    @Test
    public void openAsksWhichAppWhenSeveralMatch() throws Exception {
        withBridge(true);
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        submit("/open code");
        waitFor("picker", () -> ShadowAlertDialog.getLatestAlertDialog() != null
                && ShadowAlertDialog.getLatestAlertDialog().isShowing());
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialogShows(d, "Which app?"));
        assertNotNull(row(d, "Visual Studio Code"));
        assertTrue("paths help tell them apart", dialogShows(d, "VSCodium.exe"));
        shootDialog("comms-light-open-picker");
        CommsFlowTest.clickDialogRow(d, "VSCodium");
        assertFalse(d.isShowing());
        waitFor("opened", () -> lastNotice().contains("Opened **VSCodium**"));
        assertEquals("launched by id", Collections.singletonList("app-vscodium"), bridge.launchedIds);

        submit("/open ghost");
        waitFor("no candidates", () -> lastNotice().contains("No app on the PC matches “ghost”"));
        submit("/open nothing");
        waitFor("error", () -> lastNotice().contains("Couldn't open “nothing”"));
        assertEquals(1, bridge.launchedIds.size());
    }

    @Test
    public void openConfirmationSurvivesARecreate() throws Exception {
        withBridge(true);
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        bridge.delayMs = 700;
        submit("/open spotify");
        MainActivity old = act;
        // The phone flips to dark mode (or the user changes Appearance) mid-request.
        ctl.recreate();
        act = ctl.get();
        assertNotSame(old, act);
        waitFor("confirm", () -> ShadowAlertDialog.getLatestAlertDialog() != null
                && ShadowAlertDialog.getLatestAlertDialog().isShowing());
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertSame("shown on the live activity", act, ((ContextWrapper) d.getContext()).getBaseContext());
        assertTrue(dialogShows(d, "Open Spotify on the PC?"));
        bridge.delayMs = 0;
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("opened", () -> lastNotice().contains("Opened **Spotify**"));
    }

    // ------------------------------------------------------------------
    // Screenshots → "Ask about this"
    // ------------------------------------------------------------------

    @Test
    public void askAboutAPcScreenshot() throws Exception {
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false));
        withBridge(true);
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        useVisionModel();
        submit("/shot");
        waitFor("screenshot notice", () -> last(ChatMessage.NOTICE) != null
                && last(ChatMessage.NOTICE).image.length() > 0);
        waitFor("ask chip", () -> button("Ask OMNI about this image") != null);
        advance(300);
        shoot("comms-cyber-screenshot-ask");
        click("Ask OMNI about this image");
        waitFor("attached", () -> act.comms().pendingImages().size() == 1);
        assertEquals("What's on my PC screen?", composer().getText().toString());
        click("Send");
        waitFor("reply", () -> !engine().isBusy() && last(ChatMessage.ASSISTANT) != null);
        JSONArray sent = ollama.lastChatRequest().getJSONArray("messages");
        JSONObject user = sent.getJSONObject(sent.length() - 1);
        assertEquals(1, user.getJSONArray("images").length());
        assertEquals("What's on my PC screen?", user.getString("content"));
    }
}
