package com.omnideck.mobile;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.mock.MockOllama;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * End-to-end chat flows on the Comms tab: streaming, slash commands, stop /
 * regenerate / compact / pull, reconnects, PC commands through LaunchBridge,
 * dialogs, rotation and theme switches. Runs on API 23 (minSdk) and 34.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 34})
@LooperMode(LooperMode.Mode.PAUSED)
public class CommsFlowTest extends Harness {

    private boolean cyber() {
        return act.theme().hud;
    }

    private ChatMessage last(String role) {
        return engine().conversation().lastOfRole(role);
    }

    private String lastNotice() {
        ChatMessage n = last(ChatMessage.NOTICE);
        return n == null ? "" : n.content;
    }

    private void chatAndWait(String text) {
        int before = engine().conversation().messages.size();
        submit(text);
        waitFor("reply to " + text, () -> !engine().isBusy()
                && engine().conversation().messages.size() >= before + 2);
    }

    private void command(String text, String expectInNotice) {
        submit(text);
        waitFor(text + " → " + expectInNotice, () -> lastNotice().contains(expectInNotice));
        String plain = expectInNotice.replace("**", "").replace("`", "");
        waitFor("screen shows " + plain, () -> shows(plain));
    }

    @Test
    public void connectsAndStreamsAReply() throws Exception {
        launch("cyber", MainActivity.TAB_COMMS);
        assertTrue(cyber());
        waitOnline();
        assertTrue(shows("ONLINE"));
        assertTrue(shows("127.0.0.1:" + ollama.port()));
        assertEquals("llama3.2:3b", engine().currentModel());
        assertTrue("model in the chat header", shows("llama3.2:3b"));
        assertTrue("empty state", shows("Comms channel open"));

        // Stream slowly enough to watch the UI mid-reply.
        ollama.tokenDelayMs = 60;
        composer().setText("hello phone");
        idle();
        click("Send");
        waitFor("streaming started", () -> engine().isBusy());
        assertNotNull("send turns into stop while streaming", button("Stop"));
        final String[] partial = new String[1];
        waitFor("partial text on screen", () -> {
            TextView t = textView("You said");
            if (t == null) return false;
            partial[0] = t.getText().toString();
            return true;
        });
        assertTrue(partial[0], partial[0].contains("▌"));
        assertFalse("seen mid-stream: " + partial[0], partial[0].contains("network"));
        waitFor("reply finished", () -> !engine().isBusy());

        ChatMessage reply = last(ChatMessage.ASSISTANT);
        assertEquals("You said: hello phone. **Streaming** works over the `network`.", reply.content);
        assertEquals("llama3.2:3b", reply.model);
        assertTrue(reply.stats, reply.stats.contains("tok/s"));
        // Markdown rendered: no asterisks/backticks on screen, cursor gone.
        assertTrue(shows("You said: hello phone. Streaming works over the network."));
        assertFalse(textView("You said").getText().toString().contains("▌"));
        assertNotNull("empty composer shows the mic", button("Voice input"));
        assertEquals("input cleared", "", composer().getText().toString());

        JSONObject req = ollama.lastChatRequest();
        assertEquals("llama3.2:3b", req.getString("model"));
        assertEquals(-1, req.getInt("keep_alive"));
        assertEquals(8192, req.getJSONObject("options").getInt("num_ctx"));
        assertFalse("no num_thread unless configured", req.getJSONObject("options").has("num_thread"));
        assertFalse("no temperature unless configured", req.getJSONObject("options").has("temperature"));
        assertFalse("think not sent to non-thinking models", req.has("think"));
        JSONArray msgs = req.getJSONArray("messages");
        assertEquals(1, msgs.length());
        assertEquals("hello phone", msgs.getJSONObject(0).getString("content"));

        // Second turn carries the history.
        ollama.tokenDelayMs = 2;
        chatAndWait("and again");
        JSONArray msgs2 = ollama.lastChatRequest().getJSONArray("messages");
        assertEquals(3, msgs2.length());
        assertEquals("assistant", msgs2.getJSONObject(1).getString("role"));
        assertTrue(shows("You said: and again"));

        // Telemetry recorded the replies.
        assertEquals(2, engine().telemetry.replies);
        assertTrue(engine().telemetry.tokensPerSec.size() >= 2);
    }

    @Test
    public void slashCommandsControlTheAi() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();

        // Suggestions appear while typing a command.
        composer().setText("/mo");
        idle();
        assertTrue(shows("/model"));
        assertTrue(shows("/models"));
        composer().setText("");
        idle();

        command("/help", "/model <name>");
        command("/models", "Models on the PC");
        assertTrue(lastNotice().contains("qwen3:8b"));
        command("/model qwen", "Model → **qwen3:8b**");
        assertEquals("qwen3:8b", engine().currentModel());
        waitFor("capabilities", () -> Boolean.TRUE.equals(engine().supportsThinking("qwen3:8b")));

        command("/system You are OMNI.", "System prompt set");
        command("/remember my name is Neo", "Remembered");
        chatAndWait("hi");
        JSONObject req = ollama.lastChatRequest();
        JSONObject sys = req.getJSONArray("messages").getJSONObject(0);
        assertEquals("system", sys.getString("role"));
        assertTrue(sys.getString("content").startsWith("You are OMNI."));
        assertTrue(sys.getString("content").contains("my name is Neo"));
        assertEquals("fast/auto mode sends think:false to thinking models", Boolean.FALSE, req.get("think"));

        command("/deep", "Deep mode");
        chatAndWait("why is the sky blue");
        assertEquals(Boolean.TRUE, ollama.lastChatRequest().get("think"));
        ChatMessage deepReply = last(ChatMessage.ASSISTANT);
        assertTrue(deepReply.thinking.contains("carefully"));
        assertTrue("thoughts toggle", shows("Thoughts"));
        command("/fast", "Fast mode");

        command("/warm", "is loaded and ready");
        command("/ps", "Loaded in memory");
        command("/unload", "Unloaded **qwen3:8b**");
        command("/facts", "my name is Neo");
        command("/forget 1", "Forgot");
        command("/timer 5m tea", "Timer set for 5m 0s");
        command("/timer cancel", "Cancelled 1 timer.");
        command("/zzz", "Unknown command");
        command("/web cats", "runs inside OMNI-DECK on the PC");
        command("/bench", "Benchmark — qwen3:8b");
        command("/debug", "Diagnostics");
        assertTrue(lastNotice().contains("Ollama 0.12.6"));
        submit("/rename Sky talk");
        waitFor("renamed", () -> "Sky talk".equals(engine().conversation().title));
        assertTrue("title in the chat header", shows("Sky talk"));

        // A message starting with "//" is sent as text, not run as a command.
        chatAndWait("//etc/hosts is a file");
        assertEquals("/etc/hosts is a file", last(ChatMessage.USER).content);

        // /mute toggles read-aloud.
        boolean was = engine().settings.readAloud();
        submit("/mute");
        waitFor("read-aloud toggled", () -> engine().settings.readAloud() != was);

        submit("/reset");
        waitFor("fresh chat", () -> engine().conversation().messages.isEmpty());
        assertTrue(shows("Talk to OMNI"));
    }

    @Test
    public void stopRegenerateCompactAndPull() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        assertFalse(cyber());
        waitOnline();
        ollama.tokenDelayMs = 40;
        ollama.replier = (r, text) -> {
            List<String> t = new ArrayList<>();
            for (int i = 0; i < 300; i++) t.add("word" + i + " ");
            return t;
        };
        submit("tell me a long story");
        waitFor("some words", () -> shows("word3"));
        click("Stop");
        waitFor("stopped", () -> !engine().isBusy());
        ChatMessage stopped = last(ChatMessage.ASSISTANT);
        assertTrue(stopped.stopped);
        assertTrue(stopped.content.startsWith("word0 "));
        assertTrue(shows("stopped"));

        ollama.replier = null;
        ollama.tokenDelayMs = 2;
        submit("/regen");
        waitFor("regenerated", () -> !engine().isBusy() && last(ChatMessage.ASSISTANT).content.startsWith("You said"));
        assertEquals(2, engine().conversation().messages.size());

        chatAndWait("second");
        chatAndWait("third");
        command("/compact", "Compacted");
        List<ChatMessage> ms = engine().conversation().messages;
        assertEquals(ChatMessage.SYSTEM, ms.get(0).role);
        assertTrue(ms.get(0).content.startsWith("Summary of the earlier conversation"));
        assertTrue(shows("Earlier conversation (summary)"));
        chatAndWait("after compact");
        JSONArray sent = ollama.lastChatRequest().getJSONArray("messages");
        assertEquals("system", sent.getJSONObject(0).getString("role"));

        command("/pull tinyllama", "is downloaded");
        waitFor("model list refreshed", () -> engine().resolveInstalled("tinyllama") != null);
        command("/pull missing-model", "failed");
    }

    @Test
    public void reconnectsWhenTheServerComesBack() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        int port = ollama.port();
        ollama.stop();
        // Health checks run every 10 s (on Robolectric's clock) and need two misses.
        waitFor("connection loss noticed", () -> engine().state() != Engine.State.ONLINE);
        ollama = MockOllama.start("127.0.0.1", port);
        waitFor("back online", () -> engine().state() == Engine.State.ONLINE);
        chatAndWait("still there?");
        assertTrue(shows("You said: still there?"));
    }

    @Test
    public void pcControlThroughLaunchBridge() throws Exception {
        withBridge(false);
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();

        command("/vol", "run /pair");
        command("/pair", "Paired with LaunchBridge");
        assertEquals(bridge.token, engine().settings.bridgeToken());
        command("/vol 55", "Volume set to 55%");
        assertEquals(55, bridge.volume);
        command("/vol", "Volume is 55%");
        command("/sys", "cpu");
        command("/desk", "Paired: yes");

        submit("/shot");
        waitFor("screenshot", () -> last(ChatMessage.NOTICE) != null && last(ChatMessage.NOTICE).image.length() > 0);
        waitFor("image shown", () -> {
            for (View v : views()) {
                if (v instanceof ImageView && v.isShown()
                        && ((ImageView) v).getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
                    return true;
                }
            }
            return false;
        });

        submit("/pcclip");
        waitFor("clipboard pasted", () -> composer().getText().toString().contains("text from the PC clipboard"));
        composer().setText("");

        submit("/open spotify");
        waitFor("confirm dialog", () -> ShadowAlertDialog.getLatestAlertDialog() != null
                && ShadowAlertDialog.getLatestAlertDialog().isShowing());
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("opened", () -> lastNotice().contains("Opened **Spotify**"));
        assertEquals(1, bridge.launched.size());
    }

    @Test
    public void historyActionsRotationAndThemes() throws Exception {
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("copy me");

        // Long-press the reply → actions → Copy text.
        TextView reply = textView("You said: copy me");
        assertNotNull(reply);
        reply.performLongClick();
        idle();
        AlertDialog actions = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(actions);
        shadowOf(actions).clickOnItem(0);
        idle();
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        assertTrue(cm.getPrimaryClip().getItemAt(0).getText().toString().startsWith("You said: copy me"));
        assertEquals("Copied", ShadowToast.getTextOfLatestToast());

        // History overlay opens from the header button and from /history.
        click("Chat history");
        waitFor("history overlay", () -> button("Close history") != null);
        click("Close history");
        submit("/history");
        waitFor("history overlay", () -> button("Close history") != null);
        click("Close history");

        // /settings opens the settings page; /model with no name opens the Models tab.
        submit("/settings");
        assertTrue(act.settingsOpen());
        act.closeSettings();
        idle();
        submit("/model");
        assertEquals(MainActivity.TAB_MODELS, act.currentTab());
        tab(MainActivity.TAB_COMMS);

        // /server shows the connection dialog.
        submit("/server");
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        ShadowAlertDialog.getLatestAlertDialog().dismiss();

        // Rotation keeps the activity (configChanges) and the chat.
        Configuration land = new Configuration(act.getResources().getConfiguration());
        land.orientation = Configuration.ORIENTATION_LANDSCAPE;
        ctl.configurationChange(land);
        idle();
        assertTrue(shows("You said: copy me"));

        // Theme switch recreates the activity in the light palette; the chat survives.
        submit("/appearance light");
        idle();
        assertEquals("light", engine().settings.theme());
        ctl.recreate();
        act = ctl.get();
        idle();
        tab(MainActivity.TAB_COMMS);
        waitFor("chat restored", () -> shows("You said: copy me"));
        assertTrue("light theme labels", shows("You · "));
        submit("/appearance cyber");
        idle();
        ctl.recreate();
        act = ctl.get();
        idle();
        tab(MainActivity.TAB_COMMS);
        waitFor("chat restored", () -> shows("You said: copy me"));
        assertTrue("cyber theme labels", shows("OPERATOR // YOU"));

        // History survives a new chat and can be reopened by name.
        submit("/reset");
        waitFor("empty", () -> engine().conversation().messages.isEmpty());
        submit("/history copy");
        waitFor("reopened", () -> engine().conversation().messages.size() >= 2);
        assertTrue(shows("You said: copy me"));
    }

    @Test
    public void imagesAreSentToVisionModels() throws Exception {
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false));
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("/model llava");
        waitFor("switched", () -> "llava:7b".equals(engine().currentModel()));
        List<String> imgs = new ArrayList<>();
        imgs.add(MockOllamaImages.PNG_1PX);
        waitFor("sent", () -> engine().send("what is this?", imgs));
        waitFor("reply", () -> !engine().isBusy() && last(ChatMessage.ASSISTANT) != null);
        JSONObject req = ollama.lastChatRequest();
        JSONObject user = req.getJSONArray("messages").getJSONObject(req.getJSONArray("messages").length() - 1);
        assertEquals(MockOllamaImages.PNG_1PX, user.getJSONArray("images").getString(0));
        assertEquals(1, last(ChatMessage.USER).images.size());
    }

    /** A 1x1 PNG, base64. */
    static final class MockOllamaImages {
        static final String PNG_1PX =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    }
}
