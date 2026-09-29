package com.omnideck.mobile;

import com.omnideck.mobile.mock.MockOllama;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * After Wake-on-LAN the app keeps looking for the PC and says so; and the
 * bridge token never rides an https AI route over plain http.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class WakeAndGuardTest extends Harness {

    private static int closedPort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Test
    public void afterAWakeUpPacketTheAppWatchesForThePcAndConnectsWhenItIsUp() throws Exception {
        int port = closedPort();
        prefs().edit().putString("server", "127.0.0.1:" + port).putString("pc_mac", "3C:7C:3F:12:AB:CD").commit();
        launch("cyber", MainActivity.TAB_COMMAND);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        assertFalse(engine().isWaking());
        AtomicReference<String> r = new AtomicReference<>();
        try (DatagramSocket listener = new DatagramSocket(0)) {
            Engine.testWolPort = listener.getLocalPort();
            engine().wakePc((x, e) -> r.set(e == null ? x : "error: " + e));
            waitFor("sent", () -> r.get() != null);
        } finally {
            Engine.testWolPort = 0;
        }
        assertTrue(r.get(), r.get().startsWith("Wake-up packet sent"));
        assertTrue(engine().isWaking());
        advance(1500);
        assertTrue(shows("WAKING YOUR PC"));
        shoot("command-cyber-waking");

        // The PC boots: Ollama comes up on that port and the watch finds it without a tap.
        ollama.stop();
        ollama = MockOllama.start("127.0.0.1", port);
        waitOnline();
        assertFalse(engine().isWaking());
        advance(400);
        assertFalse(shows("WAKING YOUR PC"));
    }

    @Test
    public void theBridgeTokenIsNotSentAlongAnHttpsAiRoute() throws Exception {
        withBridge(true);
        int port = closedPort();
        prefs().edit().putString("server", "https://127.0.0.1:" + port)
                .putString("bridge_token_host", "127.0.0.1").commit();
        engineSettingsLast("127.0.0.1", port);
        launch("dark", MainActivity.TAB_COMMAND);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        assertFalse("not paired along an https route", engine().bridgePaired());
        AtomicReference<String> err = new AtomicReference<>();
        engine().bridgePair((t, e) -> err.set(e));
        waitFor("refused", () -> err.get() != null);
        assertTrue(err.get(), err.get().contains("Settings › PC bridge"));
        assertTrue("nothing was asked of the bridge", bridge.pairs.get() == 0);

        // The bridge's own address (LAN/VPN) is used as-is: the token goes there.
        engine().settings.setBridgeHost("127.0.0.1");
        assertTrue(engine().bridgePaired());
        assertEquals(bridge.token, engine().settings.bridgeToken());
    }

    @Test
    public void anUnboundTokenIsNotTiedToTheHttpsHostItWasNeverSentTo() throws Exception {
        withBridge(true); // a token typed in Settings: not bound to any PC yet
        int port = closedPort();
        prefs().edit().putString("server", "https://127.0.0.1:" + port).commit();
        engineSettingsLast("127.0.0.1", port);
        launch("light", MainActivity.TAB_COMMAND);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        AtomicReference<String> r = new AtomicReference<>();
        engine().lockPc((x, e) -> r.set(e != null ? e : x));
        waitFor("bridge answered", () -> r.get() != null);
        assertEquals("still unbound: it binds to the bridge address the user enters next", "",
                engine().settings.bridgeTokenHost());
        engine().settings.setBridgeHost("127.0.0.1");
        assertTrue(engine().bridgePaired());
    }

    @Test
    public void anEmptyChatSuggestsPcRequestsOnlyWhenOmniCanActOnThePc() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        advance(800);
        assertFalse("no bridge: no PC suggestions", shows("Set the volume to 30"));
    }

    @Test
    public void pcSuggestionsAppearWithAPairedBridgeAndAToolModel() throws Exception {
        withBridge(true);
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        engine().fetchDetails(engine().currentModel(), (d, e) -> { });
        waitFor("PC suggestions", () -> {
            advance(100);
            return shows("Set the volume to 30");
        });
        advance(1500);
        shoot("comms-cyber-empty-pc");
        assertFalse("llama3.2 can't see a screenshot", shows("What's on my screen?"));

        // A model with vision gets the screen question too.
        ollama.addModel(new MockOllama.Model("llama3.2-vision:11b", 7_800_000_000L, "11B", "Q4_K_M", false));
        engine().refreshModels(null);
        waitFor("listed", () -> engine().models().size() > 1 && engine().resolveInstalled("llama3.2-vision:11b") != null);
        engine().setModel("llama3.2-vision:11b");
        engine().fetchDetails("llama3.2-vision:11b", (d, e) -> { });
        waitFor("screen question", () -> {
            advance(100);
            return shows("What's on my screen?");
        });
    }

    private static void engineSettingsLast(String host, int port) {
        prefs().edit().putString("last_host", host).putInt("last_port", port).putBoolean("last_https", true).commit();
    }
}
