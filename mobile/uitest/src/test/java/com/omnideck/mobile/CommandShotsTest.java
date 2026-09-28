package com.omnideck.mobile;

import android.view.View;
import android.widget.ScrollView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.mock.MockOllama;
import com.omnideck.mobile.ui.CoreView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.net.ServerSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The COMMAND tab's states in every theme, for review:
 * build/screens/command-{theme}-{speaking|reasoning|scanning|narrow|streaming|pc|pc-2}.png
 * (CommandScreenTest shoots online, offline, the Cyber scanning and narrow views
 * and Cyber/Light streaming).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CommandShotsTest extends Harness {

    private void scrollToY(int dp) {
        for (View v : views()) {
            if (v instanceof ScrollView && v.isShown()) {
                v.scrollTo(0, Math.round(dp * act.getResources().getDisplayMetrics().density));
                idle();
                return;
            }
        }
        throw new AssertionError("no scrolling page");
    }

    private CoreView core() {
        for (View v : views()) {
            if (v instanceof CoreView && v.isShown()) return (CoreView) v;
        }
        throw new AssertionError("no AI core");
    }

    // --- Speaking --------------------------------------------------------

    private void speaking(String theme) throws Exception {
        launch(theme, MainActivity.TAB_COMMAND);
        waitOnline();
        advance(400);
        act.speakingOverride = true; // stands in for the TTS engine
        advance(900);
        assertEquals(CoreView.SPEAKING, core().mode());
        assertTrue(shows("Speaking the reply aloud"));
        shoot("command-" + theme + "-speaking");
        act.stopSpeaking();
        idle();
    }

    @Test
    public void cyberSpeaking() throws Exception {
        speaking("cyber");
    }

    @Test
    public void lightSpeaking() throws Exception {
        speaking("light");
    }

    @Test
    public void darkSpeaking() throws Exception {
        speaking("dark");
    }

    // --- Scanning (after the AI went missing) ------------------------------

    private void scanning(String theme) throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        prefs().edit().putString("server", "127.0.0.1:" + port).commit();
        launch(theme, MainActivity.TAB_COMMAND);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        advance(1500);
        // A socket that accepts but never answers keeps the probe waiting long enough to see it.
        try (ServerSocket blackHole = new ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            click("Scan again");
            advance(1200);
            assertEquals(Engine.State.SEARCHING, engine().state());
            shoot("command-" + theme + "-scanning");
            waitFor("scan gives up", () -> engine().state() == Engine.State.OFFLINE);
        }
    }

    @Test
    public void lightScanning() throws Exception {
        scanning("light");
    }

    @Test
    public void darkScanning() throws Exception {
        scanning("dark");
    }

    // --- Narrow phone (360dp) ---------------------------------------------

    private void narrow(String theme) throws Exception {
        withBridge(true);
        prefs().edit().putString("pc_mac", "3c:7c:3f:12:ab:cd").putBoolean("read_aloud", true).commit();
        launch(theme, MainActivity.TAB_COMMAND);
        waitOnline();
        waitFor("PC vitals", () -> engine().lastVitals() != null);
        advance(600);
        shoot("command-" + theme + "-narrow");
        scrollToY(520);
        shoot("command-" + theme + "-narrow-2");
    }

    @Test
    @Config(qualifiers = "w360dp-h760dp-xhdpi")
    public void cyberNarrow() throws Exception {
        narrow("cyber");
    }

    @Test
    @Config(qualifiers = "w360dp-h760dp-xhdpi")
    public void lightNarrow() throws Exception {
        narrow("light");
    }

    @Test
    @Config(qualifiers = "w360dp-h760dp-xhdpi")
    public void darkNarrow() throws Exception {
        narrow("dark");
    }

    // --- Streaming ----------------------------------------------------------

    @Test
    public void darkStreaming() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        ollama.replier = (req, last) -> MockOllama.words("Systems check complete. The link to the PC is stable, "
                + "the model is warm in GPU memory and replies are streaming at full speed. Latency is low and "
                + "no errors were logged this session.");
        ollama.tokenDelayMs = 120;
        engine().send("Give me a full status report on every system.");
        waitFor("mid-reply", () -> {
            ChatMessage m = engine().streamingMessage();
            return m != null && m.content.length() > 60;
        });
        advance(700);
        assertEquals(CoreView.STREAMING, core().mode());
        shoot("command-dark-streaming");
        waitFor("reply done", () -> !engine().isBusy());
    }

    // --- Reasoning on the deep model -------------------------------------------

    private void reasoning(String theme) throws Exception {
        launch(theme, MainActivity.TAB_COMMAND);
        waitOnline();
        engine().setDeepModel("qwen3:8b");
        engine().setMode(Settings.MODE_DEEP);
        ollama.tokenDelayMs = 300;
        engine().send("Prove that there are infinitely many primes.");
        waitFor("reasoning", () -> {
            ChatMessage m = engine().streamingMessage();
            return m != null && m.thinking.length() > 12 && m.content.length() == 0;
        });
        advance(300);
        assertEquals(CoreView.THINKING, core().mode());
        assertTrue(shows("Reasoning · deep model"));
        assertTrue(shows("tok of reasoning"));
        shoot("command-" + theme + "-reasoning");
        engine().stop();
        waitFor("stopped", () -> !engine().isBusy());
    }

    @Test
    public void cyberReasoning() throws Exception {
        reasoning("cyber");
    }

    @Test
    public void lightReasoning() throws Exception {
        reasoning("light");
    }

    @Test
    public void darkReasoning() throws Exception {
        reasoning("dark");
    }

    // --- A fully set-up PC: the power strip and rich vitals -------------------

    private void pc(String theme) throws Exception {
        withBridge(true);
        bridge.rich = true;
        prefs().edit().putString("pc_mac", "3c:7c:3f:12:ab:cd").commit();
        launch(theme, MainActivity.TAB_COMMAND);
        waitOnline();
        waitFor("PC vitals", () -> engine().lastVitals() != null);
        advance(600);
        assertNotNull(button("Wake PC"));
        assertNotNull(button("Lock PC"));
        scrollToY(430);
        shoot("command-" + theme + "-pc");
        scrollToY(1250);
        shoot("command-" + theme + "-pc-2");
    }

    @Test
    public void cyberPc() throws Exception {
        pc("cyber");
    }

    @Test
    public void lightPc() throws Exception {
        pc("light");
    }

    @Test
    public void darkPc() throws Exception {
        pc("dark");
    }
}
