package com.omnideck.mobile;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.mock.MockBridge;
import com.omnideck.mobile.mock.MockOllama;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

/**
 * Drives the real MainActivity + Engine (Robolectric = the Android framework
 * on the JVM) against a mock Ollama server over real HTTP.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 34})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityUiTest {
    private MockOllama ollama;
    private MockBridge bridge;
    private ActivityController<MainActivity> ctl;
    private MainActivity act;

    @Before
    public void setUp() throws Exception {
        Engine.reset();
        // Sweep a small loopback "LAN" instead of the build machine's network.
        Engine.testSubnets = java.util.Collections.singletonList(
                new com.omnideck.mobile.core.LanScanner.Subnet("wlan0",
                        com.omnideck.mobile.core.LanScanner.parseIp("127.0.3.1"), 28));
        ollama = MockOllama.start("127.0.0.1", 0);
        ollama.tokenDelayMs = 5;
        prefs().edit().putString("server", "127.0.0.1:" + ollama.port()).commit();
    }

    @After
    public void tearDown() {
        if (ctl != null) {
            try {
                ctl.pause().stop().destroy();
            } catch (RuntimeException ignored) {
            }
        }
        Engine.reset();
        if (ollama != null) ollama.stop();
        if (bridge != null) bridge.stop();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static SharedPreferences prefs() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("omnideck", Context.MODE_PRIVATE);
    }

    private void launch() {
        ctl = Robolectric.buildActivity(MainActivity.class).setup();
        act = ctl.get();
    }

    private Engine engine() {
        return Engine.get(act);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    }

    private static void waitFor(String what, BooleanSupplier cond) {
        long end = System.currentTimeMillis() + 20000;
        while (!cond.getAsBoolean()) {
            idle();
            if (System.currentTimeMillis() > end) fail("timed out waiting for " + what);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        idle();
    }

    private void waitOnline() {
        waitFor("ONLINE", () -> engine().state() == Engine.State.ONLINE && !engine().models().isEmpty());
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private List<View> views() {
        List<View> out = new ArrayList<>();
        collect(act.getWindow().getDecorView(), out);
        return out;
    }

    private boolean screenShows(String text) {
        for (View v : views()) {
            if (v instanceof TextView && v.isShown() && ((TextView) v).getText().toString().contains(text)) return true;
        }
        return false;
    }

    private TextView textView(String contains) {
        for (View v : views()) {
            if (v instanceof TextView && ((TextView) v).getText().toString().contains(contains)) return (TextView) v;
        }
        return null;
    }

    /** Case-insensitive: labels are upper-case in the cyber theme, sentence case in light. */
    private boolean screenShowsIgnoreCase(String text) {
        String t = text.toLowerCase(java.util.Locale.US);
        for (View v : views()) {
            if (v instanceof TextView && v.isShown()
                    && ((TextView) v).getText().toString().toLowerCase(java.util.Locale.US).contains(t)) return true;
        }
        return false;
    }

    private TextView textViewIgnoreCase(String contains) {
        String t = contains.toLowerCase(java.util.Locale.US);
        for (View v : views()) {
            if (v instanceof TextView && ((TextView) v).getText().toString().toLowerCase(java.util.Locale.US).contains(t)) {
                return (TextView) v;
            }
        }
        return null;
    }

    private boolean cyber() {
        return act.getWindow().getDecorView().getRootView() != null
                && com.omnideck.mobile.ui.Palette.wantsDark(act, engine().settings.theme());
    }

    private EditText input() {
        for (View v : views()) {
            if (v instanceof EditText) return (EditText) v;
        }
        throw new AssertionError("no composer");
    }

    private View button(String description) {
        for (View v : views()) {
            if (description.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        }
        return null;
    }

    private void submit(String text) {
        input().setText(text);
        idle();
        View send = button("Send");
        assertNotNull("send button", send);
        send.performClick();
        idle();
    }

    private void chatAndWait(String text) {
        int before = engine().conversation().messages.size();
        submit(text);
        waitFor("reply to " + text, () -> !engine().isBusy()
                && engine().conversation().messages.size() >= before + 2);
    }

    private ChatMessage last(String role) {
        return engine().conversation().lastOfRole(role);
    }

    private String lastNotice() {
        ChatMessage n = last(ChatMessage.NOTICE);
        return n == null ? "" : n.content;
    }

    private void command(String text, String expectInNotice) {
        submit(text);
        waitFor(text + " → " + expectInNotice, () -> lastNotice().contains(expectInNotice));
        String plain = expectInNotice.replace("**", "").replace("`", "");
        waitFor("screen shows " + plain, () -> screenShows(plain));
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    public void connectsAndStreamsAReply() throws Exception {
        prefs().edit().putString("theme", "cyber").commit();
        launch();
        assertTrue(cyber());
        waitOnline();
        assertTrue(screenShows("ONLINE"));
        assertTrue(screenShows("127.0.0.1:" + ollama.port()));
        assertEquals("llama3.2:3b", engine().currentModel());
        assertTrue("model chip", screenShows("llama3.2:3b"));
        assertTrue("empty state", screenShows("OMNI-DECK is ready to take your command"));

        // Stream slowly enough to watch the UI mid-reply.
        ollama.tokenDelayMs = 60;
        input().setText("hello phone");
        idle();
        button("Send").performClick();
        idle();
        waitFor("streaming started", () -> engine().isBusy());
        assertNotNull("send turns into stop while streaming", button("Stop"));
        // Partial words on screen with the streaming cursor, before the reply is complete.
        final String[] partial = new String[1];
        waitFor("partial text on screen", () -> {
            TextView t = textView("You said");
            if (t == null || !t.isShown()) return false;
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
        assertTrue(screenShows("You said: hello phone. Streaming works over the network."));
        assertFalse(textView("You said").getText().toString().contains("▌"));
        assertNotNull(button("Send"));
        assertEquals("input cleared", "", input().getText().toString());

        JSONObject req = ollama.lastChatRequest();
        assertEquals("llama3.2:3b", req.getString("model"));
        assertEquals(-1, req.getInt("keep_alive"));
        assertEquals(8192, req.getJSONObject("options").getInt("num_ctx"));
        assertFalse("no num_thread unless configured", req.getJSONObject("options").has("num_thread"));
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
        assertTrue(screenShows("You said: and again"));
    }

    @Test
    public void slashCommandsControlTheAi() throws Exception {
        launch();
        waitOnline();

        // Suggestions appear while typing a command.
        input().setText("/mo");
        idle();
        assertTrue(screenShows("/model"));
        assertTrue(screenShows("/models"));
        input().setText("");
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
        assertTrue("thoughts toggle", screenShowsIgnoreCase("THOUGHTS"));
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

        // A message starting with "//" is sent as text, not run as a command.
        chatAndWait("//etc/hosts is a file");
        assertEquals("/etc/hosts is a file", last(ChatMessage.USER).content);

        command("/reset", "");
        waitFor("fresh chat", () -> engine().conversation().messages.isEmpty());
        assertTrue(screenShows("OMNI-DECK is ready to take your command"));
    }

    @Test
    public void stopRegenerateCompactAndPull() throws Exception {
        prefs().edit().putString("theme", "light").commit();
        launch();
        assertFalse(cyber());
        waitOnline();
        ollama.tokenDelayMs = 40;
        ollama.replier = (req, text) -> {
            List<String> t = new ArrayList<>();
            for (int i = 0; i < 300; i++) t.add("word" + i + " ");
            return t;
        };
        submit("tell me a long story");
        waitFor("some words", () -> screenShows("word3"));
        button("Stop").performClick();
        waitFor("stopped", () -> !engine().isBusy());
        ChatMessage stopped = last(ChatMessage.ASSISTANT);
        assertTrue(stopped.stopped);
        assertTrue(stopped.content.startsWith("word0 "));
        assertTrue(screenShows("stopped"));

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
        assertTrue(screenShows(cyber() ? "CONTEXT // SUMMARY" : "Earlier conversation (summary)"));
        chatAndWait("after compact");
        JSONArray sent = ollama.lastChatRequest().getJSONArray("messages");
        assertEquals("system", sent.getJSONObject(0).getString("role"));

        command("/pull tinyllama", "is downloaded");
        waitFor("model list refreshed", () -> engine().resolveInstalled("tinyllama") != null);
        command("/pull missing-model", "failed");
    }

    @Test
    public void offlineThenFoundWhenTheAiStarts() throws Exception {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        prefs().edit().putString("server", "127.0.0.1:" + port).commit();
        launch();
        waitFor("OFFLINE", () -> engine().state() == Engine.State.OFFLINE);
        assertTrue(screenShows("OFFLINE"));
        assertTrue(screenShowsIgnoreCase("AI NOT FOUND") || screenShows("Can't find your AI on this network"));
        assertTrue(screenShows("OLLAMA_HOST=0.0.0.0"));
        // Sending while offline keeps the text in the composer.
        input().setText("are you there?");
        button("Send").performClick();
        idle();
        assertEquals("are you there?", input().getText().toString());

        MockOllama late = MockOllama.start("127.0.0.1", port);
        try {
            TextView scan = textViewIgnoreCase("SCAN AGAIN");
            assertNotNull(scan);
            scan.performClick();
            waitOnline();
            assertTrue(screenShows("ONLINE"));
            assertFalse(screenShows("OLLAMA_HOST=0.0.0.0"));
            button("Send").performClick();
            waitFor("reply", () -> last(ChatMessage.ASSISTANT) != null && !engine().isBusy());
            assertTrue(screenShows("You said: are you there?"));
        } finally {
            late.stop();
        }
    }

    @Test
    public void reconnectsWhenTheServerComesBack() throws Exception {
        launch();
        waitOnline();
        int port = ollama.port();
        ollama.stop();
        // Health checks run every 10 s (on Robolectric's clock) and need two misses.
        waitFor("connection loss noticed", () -> engine().state() != Engine.State.ONLINE);
        ollama = MockOllama.start("127.0.0.1", port);
        waitFor("back online", () -> engine().state() == Engine.State.ONLINE);
        chatAndWait("still there?");
        assertTrue(screenShows("You said: still there?"));
    }

    @Test
    public void pcControlThroughLaunchBridge() throws Exception {
        bridge = MockBridge.start("127.0.0.1", 0);
        prefs().edit().putInt("bridge_port", bridge.port()).commit();
        launch();
        waitOnline();

        command("/vol", "run /pair");
        command("/pair", "Paired with LaunchBridge");
        assertEquals(bridge.token, engine().settings.bridgeToken());
        command("/vol 55", "Volume set to 55%");
        assertEquals(55, bridge.volume);
        command("/vol", "Volume is 55%");
        command("/sys", "cpu: 12%");
        command("/desk", "Paired: yes");

        submit("/shot");
        waitFor("screenshot", () -> last(ChatMessage.NOTICE).image.length() > 0);
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
        waitFor("clipboard pasted", () -> input().getText().toString().contains("text from the PC clipboard"));
        input().setText("");

        submit("/open spotify");
        waitFor("confirm dialog", () -> ShadowAlertDialog.getLatestAlertDialog() != null
                && ShadowAlertDialog.getLatestAlertDialog().isShowing());
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("opened", () -> lastNotice().contains("Opened **Spotify**"));
        assertEquals(1, bridge.launched.size());
    }

    @Test
    public void dialogsThemesRotationAndMessageActions() throws Exception {
        launch();
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

        // Menu, model picker, history, settings and connection dialogs open without crashing.
        View menu = button("Menu");
        assertNotNull(menu);
        menu.performClick();
        idle();
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();

        submit("/model");
        idle();
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();

        submit("/history");
        waitFor("history dialog", () -> ShadowDialog.getLatestDialog() != null && ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();

        submit("/settings");
        idle();
        AlertDialog settings = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(settings.isShowing());
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("Saved", ShadowToast.getTextOfLatestToast());

        submit("/server");
        idle();
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        ShadowAlertDialog.getLatestAlertDialog().dismiss();

        // Rotation keeps the activity (configChanges) and the chat.
        Configuration land = new Configuration(act.getResources().getConfiguration());
        land.orientation = Configuration.ORIENTATION_LANDSCAPE;
        ctl.configurationChange(land);
        idle();
        assertTrue(screenShows("You said: copy me"));

        // Theme switch recreates the activity in the light palette; the chat survives.
        submit("/appearance light");
        idle();
        assertEquals("light", engine().settings.theme());
        ctl.recreate();
        act = ctl.get();
        waitFor("chat restored", () -> screenShows("You said: copy me"));
        assertTrue("light theme labels", screenShows("You · "));
        submit("/appearance cyber");
        idle();
        ctl.recreate();
        act = ctl.get();
        waitFor("chat restored", () -> screenShows("You said: copy me"));
        assertTrue("cyber theme labels", screenShows("OPERATOR // YOU"));

        // History survives a new chat and can be reopened by name.
        submit("/reset");
        waitFor("empty", () -> engine().conversation().messages.isEmpty());
        submit("/history copy");
        waitFor("reopened", () -> engine().conversation().messages.size() >= 2);
        assertTrue(screenShows("You said: copy me"));
    }
}
