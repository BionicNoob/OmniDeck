package com.omnideck.mobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.widget.TextView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.ToolCall;
import com.omnideck.mobile.mock.MockOllama;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The safety promises of AI tool calling: nothing opens on the PC before the
 * user says yes (even on a bridge that ignores dry runs), the question names
 * the app that will really open, and switching PC tools off mid-reply stops
 * everything that hasn't run.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class ToolSafetyTest extends Harness {

    private void tools(MockOllama.ToolScript s) throws Exception {
        withBridge(true);
        ollama.script = s;
    }

    private ChatMessage reply() {
        return engine().conversation().lastOfRole(ChatMessage.ASSISTANT);
    }

    private AlertDialog waitForApproval() {
        waitFor("approval sheet", () -> act.comms().approvalDialog() != null);
        return act.comms().approvalDialog();
    }

    private void waitReplyDone() {
        waitFor("reply finished", () -> !engine().isWorking() && reply() != null && !reply().streaming);
    }

    private static boolean dialogShows(Dialog d, String text) {
        List<View> all = new ArrayList<>();
        collect(d.getWindow().getDecorView(), all);
        for (View v : all) {
            if (v instanceof TextView && ((TextView) v).getText().toString().toLowerCase(Locale.US)
                    .contains(text.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    @Test
    public void nothingOpensBeforeTheUserSaysYesEvenOnABridgeWithoutDryRuns() throws Exception {
        tools(new MockOllama.ToolScript().on("open spotify", "open_app", new JSONObject().put("query", "spotify")));
        bridge.ignoreDryRun = true; // an older LaunchBridge: any /launch query opens the app
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("open spotify on my PC");
        AlertDialog d = waitForApproval();
        assertTrue(dialogShows(d, "open Spotify"));
        assertTrue("nothing opened while the question is up", bridge.launched.isEmpty());
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        waitReplyDone();
        assertTrue("declined: still nothing opened", bridge.launched.isEmpty());
        assertEquals(ToolCall.DECLINED, reply().tools.get(0).state);
    }

    @Test
    public void anAppIdTheBridgeDoesNotListIsRefusedWithoutAsking() throws Exception {
        tools(new MockOllama.ToolScript().on("open notes", "open_app",
                new JSONObject().put("app_id", "app-evil").put("query", "Notes")));
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        submit("open notes");
        waitReplyDone();
        assertNull("no question for an app that doesn't exist", act.comms().approvalDialog());
        ToolCall c = reply().tools.get(0);
        assertEquals(ToolCall.FAILED, c.state);
        assertTrue(c.result, c.result.contains("app-evil"));
        assertTrue(bridge.launchedIds.isEmpty());
    }

    @Test
    public void theQuestionNamesTheAppThatWillReallyOpen() throws Exception {
        // The model says "Calculator" but the id is Spotify's: the user is asked about Spotify.
        tools(new MockOllama.ToolScript().on("calculator", "open_app",
                new JSONObject().put("app_id", "app-spotify").put("query", "Calculator")));
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        submit("open the calculator");
        AlertDialog d = waitForApproval();
        assertTrue(dialogShows(d, "open Spotify"));
        assertFalse(dialogShows(d, "Calculator"));
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitReplyDone();
        assertEquals(java.util.Collections.singletonList("app-spotify"), bridge.launchedIds);
    }

    @Test
    public void switchingToolsOffMidReplyDeclinesTheQuestionAndEndsWithoutTools() throws Exception {
        tools(new MockOllama.ToolScript().on("volume", "set_volume", new JSONObject().put("level", 90)));
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        int before = bridge.volume;
        submit("set the volume to 90");
        waitForApproval();
        engine().setAiTools(false);
        waitReplyDone();
        assertNull(act.comms().approvalDialog());
        assertEquals("the PC wasn't touched", before, bridge.volume);
        assertEquals(ToolCall.DECLINED, reply().tools.get(0).state);
        assertFalse("the last request offered no tools", ollama.lastChatRequest().has("tools"));
    }

    @Test
    public void autoModeKeepsPcRequestsOnAToolCapableModel() throws Exception {
        // The main model (llama3.2) can call tools; the deep model (deepseek-r1) can't.
        tools(new MockOllama.ToolScript().on("volume", "set_volume", new JSONObject().put("level", 40)));
        ollama.addModel(new MockOllama.Model("deepseek-r1:8b", 5_000_000_000L, "8B", "Q4_K_M", true)
                .caps("completion", "thinking"));
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        engine().setModel("llama3.2:3b");
        engine().settings.setDeepModel("deepseek-r1:8b");
        engine().settings.setMode(Settings.MODE_AUTO);
        engine().fetchDetails("llama3.2:3b", (d, e) -> { });
        engine().fetchDetails("deepseek-r1:8b", (d, e) -> { });
        waitFor("capabilities", () -> engine().details("llama3.2:3b") != null
                && engine().details("deepseek-r1:8b") != null);
        assertEquals(Boolean.TRUE, engine().supportsTools("llama3.2:3b"));
        assertEquals(Boolean.FALSE, engine().supportsTools("deepseek-r1:8b"));
        // "analyze … in detail" makes Auto pick the deep model — but not one that can't use the
        // PC's tools while they're on…
        assertEquals("llama3.2:3b", engine().routeModel("analyze my PC's volume in detail", false));
        // …with PC tools off, depth wins again.
        engine().setAiTools(false);
        assertEquals("deepseek-r1:8b", engine().routeModel("analyze my PC's volume in detail", false));
    }
}
