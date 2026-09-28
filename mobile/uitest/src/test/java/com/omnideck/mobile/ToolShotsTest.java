package com.omnideck.mobile;

import android.app.AlertDialog;
import android.view.View;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.ToolCall;
import com.omnideck.mobile.mock.MockOllama;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Screenshots of JARVIS acting on the PC in Cyber, Light and Dark: the
 * approval sheet, the action log while it asks, and a finished log with
 * done / declined / failed rows and one row opened
 * (build/screens/tools-*.png).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ToolShotsTest extends Harness {

    private ChatMessage reply() {
        return engine().conversation().lastOfRole(ChatMessage.ASSISTANT);
    }

    /** One status request that makes four calls: a read, two changes (allowed, declined) and a failure. */
    private void script() throws Exception {
        withBridge(true);
        bridge.rich = true;
        bridge.failing.add("list_processes");
        ollama.script = req -> {
            JSONArray msgs = req.optJSONArray("messages");
            JSONObject last = msgs.optJSONObject(msgs.length() - 1);
            if ("tool".equals(last.optString("role"))) {
                return MockOllama.Turn.text("**ATLAS-PC** is at 23% CPU with 8.1 of 16 GB of memory in use. I set the "
                        + "volume to **40%**. I left the screen unlocked, as you asked, and the process list isn't "
                        + "available right now.");
            }
            try {
                return new MockOllama.Turn()
                        .call("get_system_info", new JSONObject())
                        .call("set_volume", new JSONObject().put("level", 40))
                        .call("lock_screen", new JSONObject())
                        .call("list_processes", new JSONObject());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private void run(String theme) throws Exception {
        script();
        launch(theme, MainActivity.TAB_COMMS);
        waitOnline();
        submit("Status report, and set the volume to 40.");
        waitFor("asks to set the volume", () -> act.comms().approvalDialog() != null);
        AlertDialog d = act.comms().approvalDialog();
        advance(300);
        shootDialog("tools-" + theme + "-approval");
        // The chat under the sheet: the log says what it's waiting for.
        shoot("tools-" + theme + "-asking");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("asks to lock", () -> act.comms().approvalDialog() != null
                && act.comms().approvalDialog() != d);
        act.comms().approvalDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        waitFor("answered", () -> !engine().isWorking() && reply() != null && !reply().streaming);
        List<ToolCall> t = reply().tools;
        assertEquals(4, t.size());
        assertEquals(ToolCall.DONE, t.get(0).state);
        assertEquals(ToolCall.DONE, t.get(1).state);
        assertEquals(ToolCall.DECLINED, t.get(2).state);
        assertEquals(ToolCall.FAILED, t.get(3).state);
        assertEquals(40, bridge.volume);
        View row = null;
        for (View v : views()) {
            CharSequence c = v.getContentDescription();
            if (c != null && c.toString().startsWith("Set volume → 40%, Done")) row = v;
        }
        assertNotNull(row);
        row.performClick();
        idle();
        assertTrue(shows("Arguments"));
        advance(300);
        shoot("tools-" + theme + "-log");
    }

    @Test
    public void cyber() throws Exception {
        run("cyber");
    }

    @Test
    public void light() throws Exception {
        run("light");
    }

    @Test
    public void dark() throws Exception {
        run("dark");
    }

    @Test
    public void aScreenshotInTheReplyCyber() throws Exception {
        withBridge(true);
        bridge.rich = true;
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false).caps("tools"));
        ollama.script = new MockOllama.ToolScript().on("screen", "screenshot", new JSONObject());
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        submit("/model llava");
        waitFor("llava", () -> "llava:7b".equals(engine().currentModel())
                && Boolean.TRUE.equals(engine().supportsTools("llava:7b")));
        submit("What's on my screen?");
        waitFor("answered", () -> !engine().isWorking() && reply() != null && !reply().streaming);
        assertTrue(reply().tools.get(0).image.length() > 0);
        advance(300);
        shoot("tools-cyber-screenshot");
    }

    @Test
    public void destructiveApprovalCyber() throws Exception {
        java.util.List<String> ran = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        com.sun.net.httpserver.HttpServer pc = ToolCallingTest.destructiveBridge(ran);
        try {
            prefs().edit().putInt("bridge_port", pc.getAddress().getPort()).putString("bridge_token", "tok").commit();
            ollama.script = new MockOllama.ToolScript().on("shut", "shutdown_pc", new JSONObject());
            launch("cyber", MainActivity.TAB_COMMS);
            waitOnline();
            submit("Shut the PC down for the night");
            waitFor("asks", () -> act.comms().approvalDialog() != null);
            advance(300);
            shootDialog("tools-cyber-destructive");
            act.comms().approvalDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            waitFor("answered", () -> !engine().isWorking());
            assertTrue(ran.isEmpty());
        } finally {
            pc.stop(0);
        }
    }
}
