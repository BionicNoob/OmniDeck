package com.omnideck.mobile;

import android.view.View;
import android.widget.ScrollView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.mock.MockOllama;
import com.omnideck.mobile.ui.CoreView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.net.ServerSocket;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The COMMAND tab (mission control) against the mock Ollama / LaunchBridge:
 * live status, quick actions, telemetry, PC vitals, the system log and the
 * offline guidance. Screenshots → build/screens/command-{theme}-{state}.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CommandScreenTest extends Harness {

    /** Launches on the Command tab with a paired PC bridge and waits for the link and PC vitals. */
    private void online(String theme) throws Exception {
        withBridge(true);
        launch(theme, MainActivity.TAB_COMMAND);
        waitOnline();
        waitFor("PC vitals", () -> engine().lastVitals() != null);
        advance(1500);
    }

    private ScrollView pageScroller() {
        for (View v : views()) {
            if (v instanceof ScrollView && v.isShown()) return (ScrollView) v;
        }
        throw new AssertionError("no scrolling page");
    }

    /** Scrolls the Command page so the view described as {@code desc} (or text) sits near the top. */
    private void scrollToY(int dp) {
        ScrollView sv = pageScroller();
        sv.scrollTo(0, Math.round(dp * act.getResources().getDisplayMetrics().density));
        idle();
    }

    private CoreView core() {
        for (View v : views()) {
            if (v instanceof CoreView && v.isShown()) return (CoreView) v;
        }
        throw new AssertionError("no AI core");
    }

    /** Fresh dashboard, then (after two chats, so telemetry has data) the lower cards. */
    private void shots(String theme) throws Exception {
        shoot("command-" + theme + "-online");
        chat("Status report, please.", 1);
        chat("And the PC?", 2);
        advance(600);
        scrollToY(560);
        shoot("command-" + theme + "-online-2");
        scrollToY(1300);
        shoot("command-" + theme + "-online-3");
        scrollToY(0);
    }

    /** Sends a message from Comms, waits for the reply, returns to Command. */
    private void chat(String text, int replies) {
        tab(MainActivity.TAB_COMMS);
        submit(text);
        waitFor("reply " + replies, () -> !engine().isBusy() && engine().telemetry.replies == replies);
        tab(MainActivity.TAB_COMMAND);
        waitFor("model shows as loaded", () -> engine().isLoaded("llama3.2:3b"));
    }

    /** A longer streamed reply so a mid-reply screenshot has something to show. */
    private void longReplies() {
        ollama.replier = (req, last) -> MockOllama.words("Systems check complete. The link to the PC is stable, "
                + "the model is warm in GPU memory and replies are streaming at full speed. Latency is low, the "
                + "context window has plenty of room left and no errors were logged this session. Standing by for "
                + "your next instruction.");
    }

    // ------------------------------------------------------------------
    // Online dashboard, per theme
    // ------------------------------------------------------------------

    @Test
    public void cyberOnlineDashboard() throws Exception {
        online("cyber");
        // The headline says what the AI is doing; the top bar already says ONLINE and names the address.
        assertTrue(shows("STANDING BY"));
        assertNotNull(textView("OLLAMA 0.12.6"));
        assertEquals("OLLAMA 0.12.6", textView("OLLAMA 0.12.6").getText().toString());
        assertTrue(shows("llama3.2:3b"));
        assertTrue(shows("AUTO"));
        assertTrue(shows("IDLE"));
        assertEquals(CoreView.IDLE, core().mode());
        assertTrue("core animates while shown", core().isAnimating());
        // PC vitals from the mock bridge: 12% CPU, 8.1 / 16 GB RAM, 210 GB free.
        assertTrue(shows("12%"));
        assertTrue(shows("8.1 / 16 GB"));
        assertTrue(shows("210 GB free"));
        assertNotNull(button("Warm model"));
        shots("cyber");
        // Leaving the tab stops the animation loop.
        tab(MainActivity.TAB_MODELS);
        tab(MainActivity.TAB_COMMAND);
        assertTrue(core().isAnimating());
        act.select(MainActivity.TAB_PC, false);
        idle();
        for (View v : views()) {
            if (v instanceof CoreView) assertFalse(((CoreView) v).isAnimating());
        }
    }

    @Test
    public void lightOnlineDashboard() throws Exception {
        online("light");
        assertTrue(shows("Standing by"));
        assertTrue(shows("Ollama 0.12.6"));
        assertTrue(shows("llama3.2:3b"));
        shots("light");
    }

    @Test
    public void darkOnlineDashboard() throws Exception {
        online("dark");
        assertTrue(shows("Standing by"));
        assertTrue(shows("llama3.2:3b"));
        shots("dark");
    }

    // ------------------------------------------------------------------
    // Functionality
    // ------------------------------------------------------------------

    @Test
    public void telemetryFillsAfterAChat() throws Exception {
        online("cyber");
        assertEquals(0, engine().telemetry.replies);
        assertTrue("placeholder before the first reply", shows("Measured on each reply"));
        tab(MainActivity.TAB_COMMS);
        submit("Status report, please.");
        waitFor("reply", () -> !engine().isBusy() && engine().telemetry.replies == 1);
        tab(MainActivity.TAB_COMMAND);
        advance(400);
        double tps = engine().telemetry.tokensPerSec.last();
        assertFalse(Double.isNaN(tps));
        // Throughput tile shows the measured tok/s, first-token and context tiles have numbers.
        assertTrue(shows(Fmt.oneDecimal(tps)));
        assertFalse(shows("Measured on each reply"));
        assertFalse(shows("Time until the reply starts"));
        assertTrue(shows("%"));
        assertTrue(shows("best " + Fmt.oneDecimal(engine().telemetry.tokensPerSec.max())));
        assertTrue(shows("Last reply ·"));
        // The reply was logged.
        assertTrue(shows("Reply · llama3.2:3b"));
        scrollToY(560);
        shoot("command-cyber-telemetry");
    }

    @Test
    public void warmQuickActionLoadsTheModel() throws Exception {
        online("cyber");
        assertFalse(ollama.isLoaded("llama3.2:3b"));
        assertTrue(shows("Nothing in memory"));
        click("Warm model");
        waitFor("model loaded on the server", () -> ollama.isLoaded("llama3.2:3b"));
        waitFor("engine sees it loaded", () -> engine().isLoaded("llama3.2:3b"));
        advance(300);
        assertFalse(shows("Nothing in memory"));
        assertTrue("VRAM split from /api/ps", shows("50% GPU / 50% CPU"));
        assertTrue(shows("Model online · llama3.2:3b"));
        // Unload frees it again.
        click("Unload model");
        waitFor("model unloaded", () -> !ollama.isLoaded("llama3.2:3b"));
        waitFor("engine sees it unloaded", () -> !engine().isLoaded("llama3.2:3b"));
        advance(300);
        assertTrue(shows("Nothing in memory"));
    }

    @Test
    public void quickActionsNavigateAndToggle() throws Exception {
        online("cyber");
        boolean before = engine().settings.readAloud();
        click("Read aloud");
        assertEquals(!before, engine().settings.readAloud());
        click("Read aloud");
        assertEquals(before, engine().settings.readAloud());
        click("Open Models");
        assertEquals(MainActivity.TAB_MODELS, act.currentTab());
        tab(MainActivity.TAB_COMMAND);
        click("Open PC");
        assertEquals(MainActivity.TAB_PC, act.currentTab());
        tab(MainActivity.TAB_COMMAND);
        click("AI core");
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        tab(MainActivity.TAB_COMMAND);
        // Mode chip cycles auto → fast → deep → auto.
        assertEquals(Settings.MODE_AUTO, engine().mode());
        click("Change mode");
        assertEquals(Settings.MODE_FAST, engine().mode());
        assertTrue(shows("FAST"));
        click("Change mode");
        assertEquals(Settings.MODE_DEEP, engine().mode());
        click("Change mode");
        assertEquals(Settings.MODE_AUTO, engine().mode());
        // Summarize needs a conversation first: it only toasts and stays here.
        click("Summarize chat");
        assertEquals(MainActivity.TAB_COMMAND, act.currentTab());
        click("New chat");
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
    }

    @Test
    public void systemLogShowsLinkEstablished() throws Exception {
        online("dark");
        assertTrue(shows("Link established"));
        int before = engine().telemetry.events().size();
        engine().log("warn", "Test event from the harness");
        idle();
        assertTrue("onLog appends live", shows("Test event from the harness"));
        assertEquals(before + 1, engine().telemetry.events().size());
        click("Copy system log");
    }

    @Test
    public void offlineGuidanceThenScanAgainReconnects() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        prefs().edit().putString("server", "127.0.0.1:" + port).commit();
        launch("cyber", MainActivity.TAB_COMMAND);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        advance(1500);
        assertEquals(CoreView.OFFLINE, core().mode());
        // A typed address on a closed port: the card says exactly that, and how to fix it.
        assertTrue(shows("YOUR AI REFUSED THE CONNECTION"));
        assertTrue(shows("refused port " + port));
        assertTrue(shows("OLLAMA_HOST=0.0.0.0"));
        assertNotNull(button("Scan again"));
        assertNotNull(button("Enter address"));
        shoot("command-cyber-offline");
        scrollToY(420);
        shoot("command-cyber-offline-2");
        scrollToY(0);
        // AI actions are disabled while offline: tapping Warm doesn't reach the server.
        click("Warm model");
        assertFalse(ollama.isLoaded("llama3.2:3b"));

        // A socket that accepts but never answers keeps the probe waiting (~3 s),
        // long enough to see the scanning state.
        try (ServerSocket blackHole = new ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            click("Scan again");
            assertEquals(Engine.State.SEARCHING, engine().state());
            assertEquals(CoreView.SCANNING, core().mode());
            advance(1200);
            assertTrue(shows("SCANNING FOR YOUR AI"));
            assertTrue(shows("SCANNING NETWORK"));
            shoot("command-cyber-scanning");
            waitFor("scan gives up", () -> engine().state() == Engine.State.OFFLINE);
        }

        // Bring the AI up on that port, then Scan again.
        ollama.stop();
        ollama = MockOllama.start("127.0.0.1", port);
        ollama.tokenDelayMs = 2;
        click("Scan again");
        waitOnline();
        advance(400);
        assertFalse(shows("OLLAMA_HOST=0.0.0.0"));
        assertTrue(shows("STANDING BY"));
        assertEquals(CoreView.IDLE, core().mode());
    }

    /** Points the app at a closed port and launches: the AI can't be found. */
    private void launchOffline(String theme) throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        prefs().edit().putString("server", "127.0.0.1:" + port).commit();
        launch(theme, MainActivity.TAB_COMMAND);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        advance(1500);
    }

    @Test
    public void lightOffline() throws Exception {
        launchOffline("light");
        assertTrue(shows("Your AI refused the connection"));
        assertTrue(shows("OLLAMA_HOST=0.0.0.0"));
        shoot("command-light-offline");
    }

    @Test
    public void darkOffline() throws Exception {
        launchOffline("dark");
        assertTrue(shows("AI offline"));
        scrollToY(420);
        shoot("command-dark-offline");
    }

    /** A 360dp-wide phone: tiles, readouts and the offline buttons still fit. */
    @Test
    @Config(qualifiers = "w360dp-h760dp-xhdpi")
    public void narrowPhone() throws Exception {
        online("cyber");
        tab(MainActivity.TAB_MODELS);
        engine().settings.setReadAloud(true);
        tab(MainActivity.TAB_COMMAND);
        advance(300);
        shoot("command-cyber-narrow");
        engine().settings.setReadAloud(false);
    }

    @Test
    public void cyberStreaming() throws Exception {
        streaming("cyber");
    }

    @Test
    public void lightStreaming() throws Exception {
        streaming("light");
    }

    private void streaming(String theme) throws Exception {
        online(theme);
        longReplies();
        ollama.tokenDelayMs = 120;
        tab(MainActivity.TAB_COMMS);
        submit("Give me a full status report on every system.");
        waitFor("mid-reply", () -> {
            ChatMessage m = engine().streamingMessage();
            return m != null && m.content.length() > 60;
        });
        tab(MainActivity.TAB_COMMAND);
        advance(700);
        assertEquals(CoreView.STREAMING, core().mode());
        assertTrue(shows("Generating"));
        shoot("command-" + theme + "-streaming");
        waitFor("reply done", () -> !engine().isBusy());
        advance(300);
        assertEquals(CoreView.IDLE, core().mode());
    }

    @Test
    public void thinkingState() throws Exception {
        online("cyber");
        ollama.firstTokenDelayMs = 4000;
        tab(MainActivity.TAB_COMMS);
        submit("Think about this one.");
        waitFor("busy", () -> engine().isBusy());
        tab(MainActivity.TAB_COMMAND);
        advance(1000);
        assertEquals(CoreView.THINKING, core().mode());
        assertTrue(shows("THINKING"));
        shoot("command-cyber-thinking");
        engine().stop();
        waitFor("stopped", () -> !engine().isBusy());
    }

    @Test
    public void reduceMotionRendersStatically() throws Exception {
        prefs().edit().putBoolean("reduce_motion", true).commit();
        online("cyber");
        assertFalse(core().isAnimating());
        assertEquals(CoreView.IDLE, core().mode());
    }

    @Test
    public void unpairedPcShowsConnectRow() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        scrollToY(1300);
        assertTrue(shows("Connect your PC"));
        shoot("command-light-unpaired");
        click("PC vitals");
        assertEquals(MainActivity.TAB_PC, act.currentTab());
    }

    // ------------------------------------------------------------------
    // API 23 (minSdk)
    // ------------------------------------------------------------------

    @Test
    @Config(sdk = 23)
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void worksOnApi23() throws Exception {
        withBridge(true);
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(400);
        assertTrue(shows("STANDING BY"));
        assertTrue(shows("QUICK ACTIONS"));
        assertTrue(shows("llama3.2:3b"));
        click("Warm model");
        waitFor("model loaded", () -> ollama.isLoaded("llama3.2:3b"));
        waitFor("vitals", () -> engine().lastVitals() != null);
        advance(300);
        assertTrue(shows("12%"));
        List<View> all = views();
        assertTrue(all.size() > 50);
    }
}
