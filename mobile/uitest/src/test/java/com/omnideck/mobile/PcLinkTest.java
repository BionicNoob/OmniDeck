package com.omnideck.mobile;

import android.app.AlertDialog;

import com.omnideck.mobile.mock.MockBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The PC link: its own address (so PC control works before Ollama is
 * reachable), pointing an unreachable bridge elsewhere or back at the AI's
 * PC, a pairing that belongs to another PC, forgetting the pairing, and the
 * linking placeholders.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PcLinkTest extends PcBaseTest {

    @Test
    public void pcControlWorksBeforeTheAiIsReachable() throws Exception {
        richBridge(false);
        // No AI address, and none found on the network.
        prefs().edit().putString("server", "").putString("last_host", "").commit();
        launch("dark", MainActivity.TAB_PC);
        waitFor("no address", () -> shows("No PC address"));
        assertTrue(shows("No address yet"));

        click("Enter PC address");
        AlertDialog d = latestAlert();
        dialogField(d, "PC address").setText("not an address at all");
        positive(d);
        assertTrue("a bad address keeps the sheet open", d.isShowing());
        assertTrue(dialogShows(d, "isn't an address"));
        dialogField(d, "PC address").setText("127.0.0.1:" + bridge.port());
        positive(d);
        assertEquals("127.0.0.1", engine().settings.bridgeHost());
        assertEquals(bridge.port(), engine().settings.bridgePort());

        waitFor("bridge found", () -> shows("Pair this phone") && shows("127.0.0.1:" + bridge.port()));
        click("Pair with PC");
        waitPaired();
        assertNotEquals("still no AI — the PC works anyway", Engine.State.ONLINE, engine().state());
    }

    @Test
    public void anUnreachableBridgeCanBePointedElsewhere() throws Exception {
        bridge = MockBridge.start("127.0.0.1", 0);
        bridge.rich = true;
        prefs().edit().putInt("bridge_port", closedPort()).putString("bridge_token", bridge.token).commit();
        openPc("light");
        waitFor("unreachable", () -> shows("Bridge unreachable") && shows("the PC your AI runs on"));
        click("Change PC address");
        AlertDialog d = latestAlert();
        dialogField(d, "PC address").setText("127.0.0.1:" + bridge.port());
        positive(d);
        waitPaired();
        assertEquals(bridge.port(), engine().settings.bridgePort());
        assertFalse(shows("Bridge unreachable"));
    }

    @Test
    public void theBridgeCanFollowTheAiAgain() throws Exception {
        richBridge(true);
        // The token belongs to the AI's PC; the bridge address points at a PC where nothing listens.
        prefs().edit().putString("bridge_host", "127.0.0.9").putString("bridge_token_host", "127.0.0.1").commit();
        openPc("cyber");
        waitFor("unreachable", () -> shows("Bridge unreachable") && shows("the address you set"));
        assertTrue(shows("127.0.0.9:" + bridge.port()));

        click("PC link options");
        AlertDialog menu = latestAlert();
        assertTrue(dialogShows(menu, "Set by you"));
        pickRow(menu, "PC address");
        AlertDialog d = latestAlert();
        assertEquals("127.0.0.9:" + bridge.port(), dialogField(d, "PC address").getText().toString());
        neutral(d); // "Use AI's PC"
        assertEquals("", engine().settings.bridgeHost());
        waitPaired();
    }

    @Test
    public void aPairingForAnotherPcIsExplained() throws Exception {
        richBridge(true);
        prefs().edit().putString("bridge_token_host", "192.168.9.9").commit();
        openPc("dark");
        waitFor("other PC", () -> shows("Other PC") && shows("paired with the PC at 192.168.9.9"));
        assertNotNull(button("Pair with this PC"));
        click("Pair with this PC");
        waitPaired();
        assertEquals("127.0.0.1", engine().settings.bridgeTokenHost());
    }

    @Test
    public void forgettingThePairingUnpairsThisPhone() throws Exception {
        richBridge(true);
        openPc("light");
        waitPaired();
        click("PC link options");
        pickRow(latestAlert(), "Forget pairing");
        AlertDialog c = latestAlert();
        assertTrue(dialogShows(c, "Forget pairing?"));
        positive(c);
        assertEquals("", engine().settings.bridgeToken());
        assertEquals("", engine().settings.bridgeTokenHost());
        waitFor("pair card", () -> shows("Pair this phone") && shows("Not paired"));
        assertFalse(shows("8.1 / 16 GB"));
    }

    @Test
    public void wakeOnLanCanBeSetUpFromTheMenu() throws Exception {
        richBridge(true);
        openPc("cyber");
        waitPaired();
        click("PC link options");
        AlertDialog menu = latestAlert();
        assertTrue(dialogShows(menu, "Not set up"));
        pickRow(menu, "Wake-on-LAN");
        AlertDialog d = latestAlert();
        dialogField(d, "MAC address").setText("3C:7C:3F:12:AB:CD");
        positive(d);
        assertEquals("3C:7C:3F:12:AB:CD", engine().settings.pcMac());
        waitFor("status", () -> shows("WoL · 3C:7C:3F:12:AB:CD"));
    }

    @Test
    public void linkingShowsPlaceholdersAndWhereItsLooking() throws Exception {
        richBridge(true);
        bridge.delayMs = 2500;
        openPc("light");
        advance(300);
        assertNotNull(button("Linking to the PC"));
        assertTrue(shows("Contacting LaunchBridge at 127.0.0.1:" + bridge.port()));
        bridge.delayMs = 0;
        waitPaired();
        assertNull(button("Linking to the PC"));
        assertFalse(shows("Contacting LaunchBridge"));
    }
}
