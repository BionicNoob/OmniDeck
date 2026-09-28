package com.omnideck.mobile;

import android.app.AlertDialog;
import android.view.View;
import android.widget.TextView;

import com.omnideck.mobile.core.BridgeTool;
import com.omnideck.mobile.ui.PcTools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The PC LINK card's power strip: Wake-on-LAN while the bridge is away
 * (asking for the MAC when it's missing), and Lock / Sleep / Restart / Shut
 * down only when the bridge has a tool for them, always asking first.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PcPowerTest extends PcBaseTest {

    @Test
    public void wakeAsksForTheMacThenSendsTheMagicPacket() throws Exception {
        int port = closedPort();
        prefs().edit().putInt("bridge_port", port).commit();
        openPc("cyber");
        waitFor("unreachable, wake offered", () -> shows("Bridge unreachable") && button("Wake PC") != null);
        assertTrue(shows("Set up Wake-on-LAN"));

        click("Wake PC");
        AlertDialog d = latestAlert();
        assertTrue(dialogShows(d, "MAC address"));
        dialogField(d, "MAC address").setText("not a mac");
        positive(d);
        assertTrue("a bad MAC keeps the sheet open", d.isShowing());
        assertTrue(dialogShows(d, "isn't a MAC address"));

        try (DatagramSocket listener = new DatagramSocket(0)) {
            listener.setSoTimeout(5000);
            Engine.testWolPort = listener.getLocalPort();
            dialogField(d, "MAC address").setText("3c-7c-3f-12-ab-cd");
            positive(d);
            assertFalse(d.isShowing());
            waitFor("sent", () -> shows("Wake-up packet sent to 3C:7C:3F:12:AB:CD"));
            byte[] buf = new byte[256];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            listener.receive(p); // the subnet broadcast (127.0.3.15 in tests) loops back here
            assertEquals(102, p.getLength());
            assertEquals((byte) 0x3C, buf[6]);
        } finally {
            Engine.testWolPort = 0;
        }
        assertEquals("3C:7C:3F:12:AB:CD", engine().settings.pcMac());
        assertTrue(shows("WoL · 3C:7C:3F:12:AB:CD"));
    }

    @Test
    public void theMacIsLearnedFromTheSystemInfo() throws Exception {
        richBridge(true);
        bridge.mac = "3c:7c:3f:12:ab:cd";
        openPc("dark");
        waitPaired();
        waitFor("WoL ready", () -> shows("WoL · 3C:7C:3F:12:AB:CD"));
        assertEquals("3C:7C:3F:12:AB:CD", engine().settings.pcMac());
    }

    @Test
    public void lockSleepRestartAndShutDownComeFromTheBridgeAndAskFirst() throws Exception {
        richBridge(true);
        bridge.power = true;
        openPc("dark");
        waitPaired();
        waitFor("keys", () -> button("Lock PC") != null && button("Shut down PC") != null);
        assertNotNull(button("Sleep PC"));
        assertNotNull(button("Restart PC"));
        assertNull("no Wake while the PC is up", button("Wake PC"));

        // Shut down asks first, with the danger button; Cancel leaves the PC alone.
        click("Shut down PC");
        AlertDialog c = latestAlert();
        assertTrue(dialogShows(c, "Shut down the PC?"));
        assertTrue(dialogShows(c, "Unsaved work"));
        negative(c);
        advance(300);
        assertFalse(bridge.ranTools.contains("shutdown_pc"));

        click("Restart PC");
        positive(latestAlert());
        waitFor("restart ran", () -> bridge.ranTools.contains("restart_pc"));
        waitFor("what the PC said", () -> shows("Restarting in 5 s"));

        // Lock asks first while "Ask before PC actions" is on.
        click("Lock PC");
        AlertDialog lock = latestAlert();
        assertTrue(dialogShows(lock, "Lock the PC?"));
        positive(lock);
        waitFor("locked", () -> bridge.ranTools.contains("lock_screen") && shows("Workstation locked"));

        click("Sleep PC");
        positive(latestAlert());
        waitFor("sleep ran", () -> bridge.ranTools.contains("sleep_pc"));
    }

    @Test
    public void onlyWhatTheBridgeOffersBecomesAKey() throws Exception {
        richBridge(true); // lock_screen, no sleep / restart / shut down
        openPc("light");
        waitPaired();
        waitFor("lock", () -> button("Lock PC") != null);
        assertNull(button("Sleep PC"));
        assertNull(button("Restart PC"));
        assertNull(button("Shut down PC"));
        // One or two controls are wide buttons with their label beside the icon.
        View lock = button("Lock PC");
        assertTrue(lock.getWidth() > act.getResources().getDisplayMetrics().density * 200);
    }

    @Test
    public void aPlainBridgeHasNoPowerStrip() throws Exception {
        withBridge(true); // plain names, no lock tool
        openPc("cyber");
        waitFor("vitals", () -> shows("8.1 / 16 GB"));
        advance(500);
        assertNull(button("Lock PC"));
        assertFalse(shows("Set up Wake-on-LAN"));
        assertNull("no media keys without media tools", button("Next track"));
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    public void fourKeysFitA360dpPhone() throws Exception {
        richBridge(true);
        bridge.power = true;
        openPc("cyber");
        waitPaired();
        waitFor("keys", () -> button("Shut down PC") != null);
        advance(300);
        String[] keys = {"Lock PC", "Sleep PC", "Restart PC", "Shut down PC"};
        for (String k : keys) {
            View v = button(k);
            TextView label = null;
            List<View> all = new ArrayList<>();
            collect(v, all);
            for (View x : all) {
                if (x instanceof TextView) label = (TextView) x;
            }
            assertNotNull(k, label);
            assertTrue(k + " fits", label.getLayout() != null && label.getLayout().getEllipsisCount(0) == 0);
        }
    }

    @Test
    public void onlyWholeMachineToolsCountAsPowerKeys() throws Exception {
        List<BridgeTool> tools = BridgeTool.parseAll(new JSONObject().put("tools", new JSONArray()
                .put("restart_explorer").put("cancel_shutdown").put("get_sleep_timeout").put("display_sleep")
                .put("restart_service").put("reboot_computer").put("system_sleep").put("shutdown")));
        assertEquals("reboot_computer", PcTools.restartTool(tools).name);
        assertEquals("system_sleep", PcTools.sleepTool(tools).name);
        assertEquals("shutdown", PcTools.shutdownTool(tools).name);
        List<BridgeTool> none = BridgeTool.parseAll(new JSONObject().put("tools", new JSONArray()
                .put("restart_explorer").put("cancel_shutdown").put("get_sleep_timeout").put("display_sleep")));
        assertNull(PcTools.restartTool(none));
        assertNull(PcTools.sleepTool(none));
        assertNull(PcTools.shutdownTool(none));
    }
}
