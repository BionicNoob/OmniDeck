package com.omnideck.mobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.ToolCall;
import com.omnideck.mobile.core.ToolKit;
import com.omnideck.mobile.mock.MockOllama;
import com.omnideck.mobile.screens.CommsScreen;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JARVIS acting on the PC, end to end: the model (MockOllama) calls
 * LaunchBridge tools (MockBridge), the phone asks before changes, runs
 * them, feeds the results back and shows an action log in the reply.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class ToolCallingTest extends Harness {

    /** A paired PC bridge with the full tool set, and a model that uses it. */
    private MockOllama.ToolScript tools() throws Exception {
        return tools(true);
    }

    /**
     * {@code rich}: MockBridge's full tool catalog. Its plain mode (bare tool
     * names) is the one whose /launch resolves app names in a dry run.
     */
    private MockOllama.ToolScript tools(boolean rich) throws Exception {
        withBridge(true);
        bridge.rich = rich;
        MockOllama.ToolScript s = new MockOllama.ToolScript()
                .on("volume", "set_volume", new JSONObject().put("level", 40))
                .on("system", "get_system_info", new JSONObject())
                .on("screen", "screenshot", new JSONObject())
                .on("lock", "lock_screen", new JSONObject())
                .on("open code", "open_app", new JSONObject().put("query", "code"))
                .on("open spotify", "open_app", new JSONObject().put("query", "spotify"));
        ollama.script = s;
        return s;
    }

    private ChatMessage reply() {
        return engine().conversation().lastOfRole(ChatMessage.ASSISTANT);
    }

    private AlertDialog waitForApproval() {
        waitFor("approval sheet", () -> act.comms().approvalDialog() != null);
        return act.comms().approvalDialog();
    }

    private static List<String> texts(Dialog d) {
        List<View> all = new ArrayList<>();
        collect(d.getWindow().getDecorView(), all);
        List<String> out = new ArrayList<>();
        for (View v : all) {
            if (v instanceof TextView) out.add(((TextView) v).getText().toString());
        }
        return out;
    }

    private static boolean dialogShows(Dialog d, String text) {
        for (String s : texts(d)) {
            if (s.toLowerCase(Locale.US).contains(text.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    /** Taps the view described as {@code description} inside a dialog. */
    static void clickIn(Dialog d, String description) {
        List<View> all = new ArrayList<>();
        collect(d.getWindow().getDecorView(), all);
        for (View v : all) {
            if (description.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) {
                v.performClick();
                idle();
                return;
            }
        }
        throw new AssertionError("no view described as " + description + " in the dialog");
    }

    /** The action-log row whose description starts with {@code label}. */
    private View logRow(String label) {
        for (View v : views()) {
            CharSequence d = v.getContentDescription();
            if (v.isShown() && d != null && d.toString().startsWith(label)) return v;
        }
        return null;
    }

    private void waitReplyDone() {
        waitFor("reply finished", () -> !engine().isWorking() && reply() != null && !reply().streaming);
    }

    private static JSONObject message(JSONObject req, int i) throws Exception {
        return req.getJSONArray("messages").getJSONObject(i);
    }

    // ------------------------------------------------------------------
    // Asking, allowing, denying
    // ------------------------------------------------------------------

    @Test
    public void allowRunsTheToolAndTheAnswerComesWithAnActionLog() throws Exception {
        tools();
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        waitFor("tools ready", () -> engine().toolsReady(engine().currentModel()));
        assertTrue("the header says OMNI can act on the PC", shows("PC tools"));

        submit("Set the PC volume to 40");
        AlertDialog d = waitForApproval();
        assertTrue(texts(d).toString(), dialogShows(d, "OMNI wants to set volume to 40% on"));
        assertTrue("the tool id in mono", dialogShows(d, "set_volume"));
        assertTrue(dialogShows(d, "\"level\": 40"));
        assertTrue(dialogShows(d, "Allow for this chat"));
        assertEquals("nothing ran yet", 35, bridge.volume);
        ToolCall call = reply().tools.get(0);
        assertEquals(ToolCall.ASKING, call.state);
        assertEquals("Set volume → 40%", call.label);
        assertTrue(engine().isWorking());
        assertNotNull(logRow("Set volume → 40%, Asking"));
        assertTrue("the header says what's happening", shows("awaiting approval"));

        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitReplyDone();
        assertFalse(d.isShowing());
        assertEquals(40, bridge.volume);
        assertEquals(ToolCall.DONE, call.state);
        assertTrue(call.result, call.result.contains("Volume set to 40%"));
        assertTrue(call.ms >= 0);
        ChatMessage r = reply();
        assertTrue(r.content, r.content.startsWith("Done: "));
        assertTrue(r.content.contains("Volume set to 40%"));
        assertNotNull("the log row says done", logRow("Set volume → 40%, Done"));
        assertTrue(shows("PC actions"));

        // What the model saw: the tools and the note, then its own call and the result.
        assertEquals(2, ollama.chatRequests.size());
        JSONObject first = ollama.chatRequests.get(0);
        assertTrue(first.getJSONArray("tools").length() >= 8);
        assertTrue(ollama.toolsSeen.get(0).toString().contains("\"open_app\""));
        String sys = message(first, 0).getString("content");
        assertTrue(sys, sys.contains("You can act on the user's PC"));
        JSONObject second = ollama.chatRequests.get(1);
        JSONArray m = second.getJSONArray("messages");
        assertEquals(4, m.length());
        assertEquals("set_volume", m.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0)
                .getJSONObject("function").getString("name"));
        assertEquals("tool", m.getJSONObject(3).getString("role"));
        assertEquals("set_volume", m.getJSONObject(3).getString("tool_name"));
        assertTrue(m.getJSONObject(3).getString("content").contains("Volume set to 40%"));
        assertTrue("still offered for the next round", second.has("tools"));

        // The action log opens for the details.
        logRow("Set volume → 40%, Done").performClick();
        idle();
        assertTrue(shows("Arguments"));
        assertTrue(shows("\"level\": 40"));
        assertNotNull(logRow("Set volume → 40%, Done, details shown"));
    }

    @Test
    public void denyingTellsTheModel() throws Exception {
        tools();
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Set the PC volume to 40");
        AlertDialog d = waitForApproval();
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        waitReplyDone();
        assertEquals(35, bridge.volume);
        assertFalse(bridge.ranTools.contains("set_volume"));
        ToolCall call = reply().tools.get(0);
        assertEquals(ToolCall.DECLINED, call.state);
        assertEquals(ToolKit.DECLINED_RESULT, call.result);
        JSONArray m = ollama.lastChatRequest().getJSONArray("messages");
        assertEquals(ToolKit.DECLINED_RESULT, m.getJSONObject(m.length() - 1).getString("content"));
        assertEquals("Done: The user declined..", reply().content);
        assertNotNull(logRow("Set volume → 40%, Declined"));

        // Closing the sheet any other way is a no too.
        submit("Set the PC volume to 40 again");
        d = waitForApproval();
        d.dismiss();
        waitReplyDone();
        assertEquals(ToolCall.DECLINED, reply().tools.get(0).state);
        assertEquals(35, bridge.volume);
    }

    @Test
    public void readOnlyToolsRunWithoutAsking() throws Exception {
        tools();
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("How is the system doing?");
        waitReplyDone();
        assertNull("no question for a read", act.comms().approvalDialog());
        ToolCall call = reply().tools.get(0);
        assertEquals("get_system_info", call.name);
        assertEquals(ToolCall.DONE, call.state);
        assertTrue(call.result, call.result.contains("ATLAS-PC"));
        assertTrue(bridge.ranTools.contains("get_system_info"));
        assertTrue(reply().content.contains("Windows 11"));
        assertNotNull(logRow("Get system info, Done"));
    }

    @Test
    public void allowForThisChatStopsAskingUntilANewChat() throws Exception {
        tools();
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Set the PC volume to 40");
        AlertDialog d = waitForApproval();
        clickIn(d, "Allow for this chat");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitReplyDone();
        assertEquals(40, bridge.volume);

        bridge.volume = 10;
        submit("Volume to 40 once more");
        waitReplyDone();
        assertNull("allowed for this chat: no question", act.comms().approvalDialog());
        assertEquals(40, bridge.volume);

        engine().newChat();
        idle();
        bridge.volume = 10;
        submit("And the volume in a new chat");
        waitForApproval().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        waitReplyDone();
        assertEquals("a new chat asks again", 10, bridge.volume);
    }

    @Test
    public void confirmationsOffRunChangesRightAway() throws Exception {
        tools();
        prefs().edit().putBoolean("confirm_pc_actions", false).commit();
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Lock the PC");
        waitReplyDone();
        assertNull(act.comms().approvalDialog());
        assertTrue(bridge.ranTools.contains("lock_screen"));
        assertEquals(ToolCall.DONE, reply().tools.get(0).state);
    }

    @Test
    public void destructiveToolsAlwaysAsk() throws Exception {
        // A PC whose bridge can shut it down.
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        HttpServer pc = destructiveBridge(ran);
        try {
            prefs().edit().putInt("bridge_port", pc.getAddress().getPort()).putString("bridge_token", "tok")
                    .putBoolean("confirm_pc_actions", false).commit();
            ollama.script = new MockOllama.ToolScript().on("shut", "shutdown_pc", new JSONObject());
            launch("cyber", MainActivity.TAB_COMMS);
            waitOnline();
            submit("Shut the PC down for the night");
            AlertDialog d = waitForApproval();
            assertTrue(dialogShows(d, "Destructive"));
            assertTrue(dialogShows(d, "OMNI wants to shutdown PC on"));
            assertTrue(dialogShows(d, "asks every time"));
            assertFalse("never allowed for the whole chat", dialogShows(d, "Allow for this chat"));
            assertTrue(ran.isEmpty());
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            waitReplyDone();
            assertEquals(Collections.singletonList("shutdown_pc"), ran);
            assertEquals(ToolCall.DONE, reply().tools.get(0).state);

            // And again: allowed once is allowed once.
            submit("Shut it down again");
            waitForApproval().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            waitReplyDone();
            assertEquals(1, ran.size());
        } finally {
            pc.stop(0);
        }
    }

    /** A minimal LaunchBridge with a destructive tool: shutdown_pc (and get_volume). */
    static HttpServer destructiveBridge(List<String> ran) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        s.createContext("/", (HttpExchange ex) -> {
            String path = ex.getRequestURI().getPath();
            String body;
            if (path.equals("/health")) {
                body = "{\"ok\":true}";
            } else if (!"tok".equals(ex.getRequestHeaders().getFirst("X-Bridge-Token"))) {
                body = null;
            } else if (path.equals("/desk/capabilities")) {
                body = "{\"tools\":[{\"name\":\"shutdown_pc\",\"description\":\"Shut the PC down\"},"
                        + "{\"name\":\"get_volume\",\"description\":\"Master volume\"}]}";
            } else if (path.equals("/desk/run")) {
                String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String tool = req.contains("shutdown_pc") ? "shutdown_pc" : "get_volume";
                ran.add(tool);
                body = "{\"ok\":true,\"result\":\"" + (tool.equals("shutdown_pc") ? "Shutting down" : "Volume is 20%")
                        + "\"}";
            } else {
                body = "{\"detail\":\"Not Found\"}";
            }
            byte[] b = (body == null ? "{\"detail\":\"bad token\"}" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(body == null ? 401 : 200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        s.start();
        return s;
    }

    // ------------------------------------------------------------------
    // When tools are not offered
    // ------------------------------------------------------------------

    @Test
    public void toolsNeedTheSwitchAPairedBridgeAndAModelThatCanCallThem() throws Exception {
        tools();
        ollama.addModel(new MockOllama.Model("gemma3:4b", 3_300_000_000L, "4.3B", "Q4_K_M", false));
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();

        // Switched off.
        submit("/tools off");
        waitFor("off", () -> !engine().settings.aiTools());
        submit("Set the PC volume to 40");
        waitReplyDone();
        assertNull(ollama.lastTools());
        assertTrue(reply().tools.isEmpty());
        assertTrue(reply().content.startsWith("I can't do that from here"));
        assertFalse("no note about tools", message(ollama.lastChatRequest(), 0).optString("content")
                .contains("act on the user's PC"));
        assertFalse(shows("PC tools"));

        // On, but the model can't call tools.
        submit("/tools on");
        waitFor("on", () -> engine().settings.aiTools());
        submit("/model gemma3");
        waitFor("gemma", () -> "gemma3:4b".equals(engine().currentModel())
                && Boolean.FALSE.equals(engine().supportsTools("gemma3:4b")));
        submit("Set the PC volume to 40");
        waitReplyDone();
        assertEquals("gemma3:4b", ollama.lastChatRequest().getString("model"));
        assertNull(ollama.lastTools());

        // A model that can, but the bridge isn't paired.
        submit("/model llama3.2");
        waitFor("llama", () -> "llama3.2:3b".equals(engine().currentModel()));
        engine().setBridgeToken("");
        submit("Set the PC volume to 40");
        waitReplyDone();
        assertNull(ollama.lastTools());
        assertEquals(35, bridge.volume);

        // Everything in place: tools go out.
        engine().setBridgeToken(bridge.token);
        submit("How is the system doing?");
        waitReplyDone();
        assertNotNull(ollama.toolsSeen.get(ollama.toolsSeen.size() - 2));
        assertTrue(ollama.lastToolNames().contains("get_system_info"));
    }

    @Test
    public void theToolsCommandSaysWhatOmniCanDo() throws Exception {
        tools();
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("/tools");
        waitFor("report", () -> lastNotice().contains("AI tools"));
        String r = lastNotice();
        assertTrue(r, r.contains("**AI tools · active**"));
        assertTrue(r.contains("`llama3.2:3b`"));
        assertTrue(r.contains("`set_volume` — Set master volume · asks first"));
        assertTrue(r.contains("`get_system_info` — CPU, memory, disk, battery · read-only"));
        assertTrue(r.contains("`open_app`"));
        assertTrue(shows("AI tools · active"));

        engine().setBridgeToken("");
        submit("/tools");
        waitFor("report", () -> lastNotice().contains("AI tools · off"));
        assertTrue(lastNotice().contains("Run `/pair`"));
        submit("/tools maybe");
        waitFor("usage", () -> lastNotice().startsWith("Usage: `/tools`"));
    }

    private String lastNotice() {
        ChatMessage n = engine().conversation().lastOfRole(ChatMessage.NOTICE);
        return n == null ? "" : n.content;
    }

    // ------------------------------------------------------------------
    // Guards: rounds, stop, nobody there
    // ------------------------------------------------------------------

    @Test
    public void afterFiveRoundsTheModelMustAnswer() throws Exception {
        withBridge(true);
        bridge.rich = true;
        // A model that would check the volume forever.
        ollama.script = req -> req.has("tools") ? new MockOllama.Turn().call("get_volume", null)
                : MockOllama.Turn.text("I checked five times; it's 35%.");
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Keep checking the volume");
        waitReplyDone();
        assertEquals(6, ollama.chatRequests.size());
        for (int i = 0; i < 5; i++) assertNotNull("round " + (i + 1) + " offers tools", ollama.toolsSeen.get(i));
        assertNull("the sixth doesn't", ollama.toolsSeen.get(5));
        String sys = message(ollama.chatRequests.get(5), 0).getString("content");
        assertTrue(sys, sys.contains(ToolKit.LIMIT_PROMPT));
        ChatMessage r = reply();
        assertEquals(5, r.tools.size());
        assertEquals(5, r.tools.get(4).round);
        assertEquals("I checked five times; it's 35%.", r.content);
        assertFalse(r.error);
    }

    @Test
    public void stopEndsTheLoopWhileAsking() throws Exception {
        tools();
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Set the PC volume to 40");
        AlertDialog d = waitForApproval();
        assertNotNull("Stop shows while OMNI works", button("Stop"));
        click("Stop");
        waitFor("stopped", () -> !engine().isWorking());
        assertFalse("the question is withdrawn", d.isShowing());
        ChatMessage r = reply();
        assertTrue(r.stopped);
        assertEquals(ToolCall.DECLINED, r.tools.get(0).state);
        assertEquals(ToolKit.STOPPED_RESULT, r.tools.get(0).result);
        advance(500);
        assertEquals(1, ollama.chatRequests.size());
        assertEquals(35, bridge.volume);
    }

    @Test
    public void stopEndsTheLoopWhileAToolRuns() throws Exception {
        tools();
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        bridge.delayMs = 1200;
        submit("How is the system doing?");
        waitFor("running", () -> reply() != null && !reply().tools.isEmpty()
                && ToolCall.RUNNING.equals(reply().tools.get(0).state));
        assertTrue(shows("acting on PC"));
        engine().stop();
        idle();
        assertFalse("stopped at once", engine().isWorking());
        assertTrue(reply().stopped);
        // The PC still answers: the log shows what happened; the model isn't asked again.
        waitFor("tool finished", () -> ToolCall.DONE.equals(reply().tools.get(0).state));
        advance(300);
        assertEquals(1, ollama.chatRequests.size());
    }

    @Test
    public void theQuestionSurvivesARecreate() throws Exception {
        tools();
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Set the PC volume to 40");
        waitForApproval();
        MainActivity old = act;
        // The phone switches to dark mode (or the user changes Appearance) while OMNI asks.
        ctl.recreate();
        act = ctl.get();
        idle();
        assertTrue(old != act);
        AlertDialog d = waitForApproval();
        assertTrue("shown again on the new screen", dialogShows(d, "OMNI wants to set volume to 40%"));
        assertEquals(ToolCall.ASKING, reply().tools.get(0).state);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitReplyDone();
        assertEquals(40, bridge.volume);
    }

    @Test
    public void stopWhileReadingThePcsToolList() throws Exception {
        tools();
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        // No tool list read yet, and a slow bridge: the reply waits for it.
        assertNull(engine().toolCatalog());
        bridge.delayMs = 1500;
        int before = ollama.chatRequests.size();
        submit("Set the PC volume to 40");
        waitFor("working", () -> engine().isWorking());
        engine().stop();
        idle();
        assertFalse("stops at once", engine().isWorking());
        assertTrue(reply().stopped);
        Thread.sleep(1800);
        advance(200);
        assertEquals("nothing went to the model", before, ollama.chatRequests.size());
    }

    @Test
    public void aLateResultDoesNotBringBackADeletedChat() throws Exception {
        tools();
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        bridge.delayMs = 1200;
        submit("How is the system doing?");
        waitFor("running", () -> reply() != null && !reply().tools.isEmpty()
                && ToolCall.RUNNING.equals(reply().tools.get(0).state));
        String id = engine().conversation().id;
        java.io.File f = new java.io.File(new java.io.File(act.getFilesDir(), "chats"), id + ".json");
        waitFor("saved", f::exists);
        engine().deleteChat(id);
        waitFor("deleted", () -> !f.exists());
        Thread.sleep(1600);
        advance(500);
        assertTrue("the bridge did answer", bridge.ranTools.contains("get_system_info"));
        assertFalse("the chat stays deleted", f.exists());
        assertFalse(engine().isWorking());
    }

    @Test
    public void nobodyToAskMeansNo() throws Exception {
        tools();
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        // The app goes to the background (e.g. a spoken question, then the screen turned off).
        ctl.pause().stop();
        idle();
        assertTrue(engine().send("Set the PC volume to 40"));
        waitFor("reply", () -> !engine().isWorking() && reply() != null && !reply().streaming);
        ToolCall call = reply().tools.get(0);
        assertEquals(ToolCall.DECLINED, call.state);
        assertEquals(ToolKit.UNAVAILABLE_RESULT, call.result);
        assertEquals(35, bridge.volume);
        assertTrue(reply().content, reply().content.contains("wasn't available to approve"));
        ctl.start().resume();
        idle();
    }

    // ------------------------------------------------------------------
    // open_app and screenshots
    // ------------------------------------------------------------------

    @Test
    public void openAppWithSeveralMatchesAsksTheModelToChoose() throws Exception {
        tools(false);
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Open code on my PC");
        waitReplyDone();
        assertNull("finding apps changes nothing: no question", act.comms().approvalDialog());
        ToolCall call = reply().tools.get(0);
        assertEquals(ToolKit.OPEN_APP, call.name);
        assertEquals(ToolCall.DONE, call.state);
        assertEquals("Find “code” → 2 matches", call.label);
        assertTrue(call.result, call.result.contains("Visual Studio Code (app_id: app-vscode"));
        assertTrue(call.result.contains("VSCodium (app_id: app-vscodium"));
        assertTrue(bridge.launchedIds.isEmpty());
        assertTrue(reply().content.contains("Several apps on the PC match"));

        // One match: asked with the app's name, then opened by id.
        submit("Open spotify please");
        AlertDialog d = waitForApproval();
        assertTrue(dialogShows(d, "OMNI wants to open Spotify on"));
        assertTrue(dialogShows(d, "C:\\Apps\\app.exe"));
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitReplyDone();
        assertEquals(Collections.singletonList("app-spotify"), bridge.launchedIds);
        assertEquals("Open Spotify", reply().tools.get(0).label);
        assertEquals("Opened Spotify on the PC.", reply().tools.get(0).result);
    }

    @Test
    public void aScreenshotReachesAVisionModelAndTheChat() throws Exception {
        tools();
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false).caps("tools"));
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("/model llava");
        waitFor("llava", () -> "llava:7b".equals(engine().currentModel())
                && Boolean.TRUE.equals(engine().supportsVision("llava:7b"))
                && Boolean.TRUE.equals(engine().supportsTools("llava:7b")));
        submit("What's on my screen right now?");
        waitReplyDone();
        ToolCall call = reply().tools.get(0);
        assertEquals("screenshot", call.name);
        assertTrue("kept for the chat", call.image.length() > 100);
        assertTrue(call.result, call.result.contains("[image attached]"));
        JSONArray m = ollama.lastChatRequest().getJSONArray("messages");
        JSONObject toolMsg = m.getJSONObject(m.length() - 1);
        assertEquals("tool", toolMsg.getString("role"));
        assertEquals(call.image, toolMsg.getJSONArray("images").getString(0));
        assertTrue(reply().content.startsWith("I can see your screen."));
        waitFor("screenshot in the reply", () -> {
            for (View v : views()) {
                if (v instanceof ImageView && v.isShown() && "Image".contentEquals(
                        v.getContentDescription() == null ? "" : v.getContentDescription())) return true;
            }
            return false;
        });

        // A text-only tools model gets a note instead of the image.
        submit("/model llama3.2");
        waitFor("llama", () -> "llama3.2:3b".equals(engine().currentModel()));
        submit("And now what's on the screen?");
        waitReplyDone();
        JSONArray m2 = ollama.lastChatRequest().getJSONArray("messages");
        JSONObject t2 = m2.getJSONObject(m2.length() - 1);
        assertFalse(t2.has("images"));
        assertTrue(t2.getString("content").contains("can't see images"));
    }

    @Test
    public void aSpokenRequestHearsTheQuestionAndOnlyTheAnswer() throws Exception {
        org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(Locale.getDefault());
        org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(Locale.US);
        withBridge(true);
        bridge.rich = true;
        ollama.script = req -> {
            JSONArray msgs = req.optJSONArray("messages");
            JSONObject last = msgs.optJSONObject(msgs.length() - 1);
            if ("tool".equals(last.optString("role"))) return MockOllama.Turn.text("The volume is at 40 percent now.");
            try {
                return new MockOllama.Turn().call("set_volume", new JSONObject().put("level", 40));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        click("Voice input");
        org.robolectric.shadows.ShadowActivity.IntentForResult r;
        do {
            r = org.robolectric.Shadows.shadowOf(act).getNextStartedActivityForResult();
        } while (r != null && !android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(r.intent.getAction()));
        assertNotNull("the recognizer opened", r);
        act.onActivityResult(r.requestCode, android.app.Activity.RESULT_OK, new android.content.Intent()
                .putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS,
                        new ArrayList<>(Collections.singletonList("turn the volume up to forty"))));
        idle();
        AlertDialog d = waitForApproval();
        android.speech.tts.TextToSpeech tts = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance();
        assertNotNull(tts);
        org.robolectric.Shadows.shadowOf(tts).getOnInitListener().onInit(android.speech.tts.TextToSpeech.SUCCESS);
        idle();
        String asked = org.robolectric.Shadows.shadowOf(tts).getLastSpokenText();
        assertNotNull("a spoken request hears the question", asked);
        assertTrue(asked, asked.contains("wants to set volume to 40"));
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitReplyDone();
        advance(300);
        String said = org.robolectric.Shadows.shadowOf(tts).getLastSpokenText();
        assertEquals("the answer, never the tool's JSON", "The volume is at 40 percent now.", said);
        assertEquals(40, bridge.volume);
    }

    @Test
    public void handsFreeApprovesByVoice() throws Exception {
        org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(Locale.getDefault());
        org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(Locale.US);
        tools();
        prefs().edit().putBoolean("hands_free", true).commit();
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        act.comms().submitVoice("set the volume to 40");
        waitForApproval();
        // Robolectric's voice never reports speaking: the phone listens once the wait for it runs out.
        advance(CommsScreen.SPEECH_START_WAIT_MS + 1000);
        org.robolectric.shadows.ShadowActivity.IntentForResult r;
        do {
            r = org.robolectric.Shadows.shadowOf(act).getNextStartedActivityForResult();
        } while (r != null && !android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(r.intent.getAction()));
        assertNotNull("listening for the answer", r);
        act.onActivityResult(r.requestCode, android.app.Activity.RESULT_OK, new android.content.Intent()
                .putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS,
                        new ArrayList<>(Collections.singletonList("yes go ahead"))));
        waitReplyDone();
        assertEquals(40, bridge.volume);
        assertNull(act.comms().approvalDialog());
        assertEquals(ToolCall.DONE, reply().tools.get(0).state);
    }

    @Test
    public void spokenAnswersAreRead() {
        assertEquals(1, CommsScreen.yesOrNo("Yes please"));
        assertEquals(1, CommsScreen.yesOrNo("sure, go ahead"));
        assertEquals(1, CommsScreen.yesOrNo("OK"));
        assertEquals(-1, CommsScreen.yesOrNo("no"));
        assertEquals(-1, CommsScreen.yesOrNo("Don't do it"));
        assertEquals(-1, CommsScreen.yesOrNo("not now, thanks"));
        assertEquals(0, CommsScreen.yesOrNo("what was that?"));
        assertEquals(0, CommsScreen.yesOrNo("nobody knows"));
    }

    @Test
    public void aRunawayResponseRunsAtMostEightCalls() throws Exception {
        withBridge(true);
        bridge.rich = true;
        ollama.script = req -> {
            JSONArray msgs = req.optJSONArray("messages");
            JSONObject last = msgs.optJSONObject(msgs.length() - 1);
            if ("tool".equals(last.optString("role"))) return MockOllama.Turn.text("Checked.");
            MockOllama.Turn t = new MockOllama.Turn();
            for (int i = 0; i < 10; i++) t.call("get_volume", null);
            return t;
        };
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Check the volume a lot");
        waitReplyDone();
        List<ToolCall> calls = reply().tools;
        assertEquals(10, calls.size());
        int ran = 0;
        for (ToolCall c : calls) {
            if (ToolCall.DONE.equals(c.state)) ran++;
        }
        assertEquals(ToolKit.MAX_CALLS_PER_ROUND, ran);
        assertEquals(ToolKit.TOO_MANY_RESULT, calls.get(9).result);
        assertEquals(ToolKit.MAX_CALLS_PER_ROUND, Collections.frequency(bridge.ranTools, "get_volume"));
    }

    @Test
    public void callsTheBridgeDoesNotHaveFailCleanly() throws Exception {
        tools();
        ollama.script = req -> {
            JSONArray msgs = req.optJSONArray("messages");
            JSONObject last = msgs.optJSONObject(msgs.length() - 1);
            if ("tool".equals(last.optString("role"))) return MockOllama.Turn.text("Sorry: " + last.optString("content"));
            return new MockOllama.Turn().call("format_disk_c", null);
        };
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        submit("Do something odd");
        waitReplyDone();
        ToolCall call = reply().tools.get(0);
        assertEquals(ToolCall.FAILED, call.state);
        assertTrue(call.result, call.result.startsWith("There is no tool called \"format_disk_c\""));
        assertTrue(call.result.contains("set_volume"));
        assertTrue(reply().content.startsWith("Sorry: There is no tool"));
        assertNull(act.comms().approvalDialog());
    }
}
