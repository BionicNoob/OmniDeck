package com.omnideck.mobile.core;

import com.omnideck.mobile.mock.MockBridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Wake-on-LAN packets and MAC addresses, and the bridge's tool catalog. */
public class PcControlTest {

    // ---------------- Wake-on-LAN ----------------

    @Test
    public void parsesMacAddressesInTheUsualForms() {
        byte[] want = {0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E};
        assertArrayEquals(want, WakeOnLan.parseMac("00:1A:2B:3C:4D:5E"));
        assertArrayEquals(want, WakeOnLan.parseMac("00-1a-2b-3c-4d-5e"));
        assertArrayEquals(want, WakeOnLan.parseMac("001a.2b3c.4d5e"));
        assertArrayEquals(want, WakeOnLan.parseMac(" 001A2B3C4D5E "));
        assertEquals("00:1A:2B:3C:4D:5E", WakeOnLan.normalize("001a.2b3c.4d5e"));

        assertNull("groups of odd sizes", WakeOnLan.parseMac("0:1A:2B:3C:4D:5EF"));
        assertNull("too short", WakeOnLan.parseMac("00:1A:2B:3C:4D"));
        assertNull("not hex", WakeOnLan.parseMac("00:1A:2B:3C:4D:ZZ"));
        assertNull("unset", WakeOnLan.parseMac("00:00:00:00:00:00"));
        assertNull("broadcast", WakeOnLan.parseMac("FF:FF:FF:FF:FF:FF"));
        assertNull("multicast", WakeOnLan.parseMac("01:00:5E:00:00:FB"));
        assertNull(WakeOnLan.parseMac(""));
        assertNull(WakeOnLan.parseMac(null));
    }

    @Test
    public void magicPacketIsSixFFsThenSixteenCopies() {
        byte[] mac = WakeOnLan.parseMac("AA:BB:CC:DD:EE:0F".replace("AA", "A8"));
        byte[] p = WakeOnLan.packet(mac);
        assertEquals(102, p.length);
        for (int i = 0; i < 6; i++) assertEquals((byte) 0xFF, p[i]);
        for (int r = 0; r < 16; r++) {
            for (int i = 0; i < 6; i++) assertEquals(mac[i], p[6 + r * 6 + i]);
        }
        assertEquals("A8:BB:CC:DD:EE:0F", WakeOnLan.format(mac));
    }

    @Test
    public void broadcastAddresses() {
        assertEquals("192.168.1.255", WakeOnLan.broadcast(LanScanner.parseIp("192.168.1.37"), 24));
        assertEquals("10.20.255.255", WakeOnLan.broadcast(LanScanner.parseIp("10.20.30.40"), 16));
        assertEquals("172.16.0.15", WakeOnLan.broadcast(LanScanner.parseIp("172.16.0.3"), 28));
    }

    @Test
    public void findsThePcsMacInSystemInfo() throws Exception {
        JSONObject flat = new JSONObject().put("hostname", "ATLAS-PC").put("mac_address", "3c-7c-3f-12-ab-cd");
        assertEquals("3C:7C:3F:12:AB:CD", WakeOnLan.findMac(flat));
        JSONObject nested = new JSONObject().put("cpu", "12%").put("network", new JSONObject()
                .put("adapters", new JSONArray()
                        .put(new JSONObject().put("name", "Loopback").put("mac", "00:00:00:00:00:00"))
                        .put(new JSONObject().put("name", "Ethernet").put("macAddress", "00:1a:2b:3c:4d:5e"))));
        assertEquals("00:1A:2B:3C:4D:5E", WakeOnLan.findMac(nested));
        assertEquals("00:1A:2B:3C:4D:5E", WakeOnLan.findMac(
                "Host: ATLAS-PC\nEthernet: 192.168.1.20 (00:1A:2B:3C:4D:5E)\nCPU: 12%"));
        assertEquals("a list of MACs", "00:1A:2B:3C:4D:5E", WakeOnLan.findMac(new JSONObject()
                .put("macs", new JSONArray().put("junk").put("00-1A-2B-3C-4D-5E"))));
        assertNull(WakeOnLan.findMac(new JSONObject().put("cpu", "12%").put("uptime", "3 days, 4:12:05")));
        assertNull("times aren't MACs", WakeOnLan.findMac("up 12:30:45:10:20:33:11"));
        assertNull(WakeOnLan.findMac(null));
    }

    // ---------------- Bridge tools ----------------

    @Test
    public void readsJsonSchemaParameters() throws Exception {
        JSONObject entry = new JSONObject().put("name", "set_brightness").put("description", "Screen brightness")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("level", new JSONObject().put("type", "integer").put("minimum", 0)
                                        .put("maximum", 100).put("description", "Percent").put("default", 70))
                                .put("display", new JSONObject().put("type", "string")
                                        .put("enum", new JSONArray().put("main").put("all"))))
                        .put("required", new JSONArray().put("level")));
        BridgeTool t = BridgeTool.parse(entry);
        assertEquals("Set brightness", t.label());
        assertEquals("Screen brightness", t.description);
        assertEquals(2, t.params.size());
        BridgeTool.Param level = t.params.get(0).name.equals("level") ? t.params.get(0) : t.params.get(1);
        BridgeTool.Param display = level == t.params.get(0) ? t.params.get(1) : t.params.get(0);
        assertEquals("integer", level.type);
        assertTrue(level.required);
        assertTrue(level.numeric());
        assertEquals(0, level.min, 0);
        assertEquals(100, level.max, 0);
        assertEquals("Percent", level.description);
        assertEquals(70L, t.template().get("level"));
        assertFalse(display.required);
        assertEquals(2, display.choices.size());
        assertEquals("Display", display.label());
    }

    @Test
    public void readsSimpleParameterFormsAndHints() throws Exception {
        BridgeTool map = BridgeTool.parse(new JSONObject().put("name", "type_text")
                .put("args", new JSONObject().put("text", "str").put("delay_ms", "int")));
        assertEquals(2, map.params.size());
        for (BridgeTool.Param p : map.params) {
            assertEquals(p.name.equals("text") ? "string" : "integer", p.type);
        }
        BridgeTool list = BridgeTool.parse(new JSONObject().put("name", "notify")
                .put("params", new JSONArray().put("title").put(new JSONObject().put("name", "body")
                        .put("type", "string").put("required", true))));
        assertEquals(2, list.params.size());
        assertTrue(list.params.get(1).required);

        // Only "{arg}" hints in the description (MockBridge's rich catalog style).
        BridgeTool hinted = BridgeTool.parse(new JSONObject().put("name", "open_url")
                .put("description", "Open a web page {url}"));
        assertEquals(1, hinted.params.size());
        assertEquals("url", hinted.params.get(0).name);
        assertEquals("Open a web page", hinted.summary());

        // Well-known tools get real types even without a schema.
        BridgeTool vol = BridgeTool.parse(new JSONObject().put("name", "set_volume")
                .put("description", "Set master volume {level}"));
        assertEquals("integer", vol.params.get(0).type);
        assertEquals(100, vol.params.get(0).max, 0);
        BridgeTool shot = BridgeTool.parse("screenshot");
        assertEquals("boolean", shot.params.get(0).type);
        assertEquals(Boolean.FALSE, shot.template().get("save"));
        assertTrue(BridgeTool.parse("lock_screen").params.isEmpty());
        assertNull(BridgeTool.parse(new JSONObject().put("description", "no name")));
        assertNull(BridgeTool.parse(42));
    }

    @Test
    public void categoriesDestructiveToolsAndTheLockTool() throws Exception {
        assertTrue(BridgeTool.parse("shutdown").destructive());
        assertTrue(BridgeTool.parse("restart_pc").destructive());
        assertTrue(BridgeTool.parse("kill_process").destructive());
        assertFalse(BridgeTool.parse("lock_screen").destructive());
        assertFalse(BridgeTool.parse("get_volume").destructive());
        assertEquals(BridgeTool.POWER, BridgeTool.parse("lock_screen").category());
        assertEquals(BridgeTool.POWER, BridgeTool.parse("sleep").category());
        assertEquals(BridgeTool.MEDIA, BridgeTool.parse("media_play_pause").category());
        assertEquals(BridgeTool.MEDIA, BridgeTool.parse("set_volume").category());
        assertEquals(BridgeTool.INFO, BridgeTool.parse("get_system_info").category());
        assertEquals(BridgeTool.INFO, BridgeTool.parse("list_processes").category());
        assertEquals(BridgeTool.OTHER, BridgeTool.parse("open_url").category());

        JSONObject caps = new JSONObject().put("tools", new JSONArray().put("get_clock").put("unlock_door")
                .put(new JSONObject().put("name", "lock_workstation").put("description", "Lock it")));
        List<BridgeTool> tools = BridgeTool.parseAll(caps);
        assertEquals(3, tools.size());
        assertEquals("lock_workstation", BridgeTool.lockTool(tools).name);
        assertNull(BridgeTool.lockTool(BridgeTool.parseAll(new JSONObject().put("tools",
                new JSONArray().put("get_clock").put("unlock_door").put("block_site")))));
        assertEquals("get_clock", BridgeTool.find(tools, "GET_CLOCK").name);
        assertTrue(BridgeTool.parseAll(new JSONObject()).isEmpty());
        assertTrue(BridgeTool.parseAll(null).isEmpty());
    }

    @Test
    public void readsTheMockBridgesCatalogs() throws Exception {
        MockBridge b = MockBridge.start("127.0.0.1", 0);
        try {
            BridgeClient c = new BridgeClient("127.0.0.1", b.port(), b.token);
            List<BridgeTool> plain = BridgeTool.parseAll(c.deskCapabilities());
            assertEquals(5, plain.size());
            assertEquals("get_system_info", plain.get(0).name);
            assertNull("the plain catalog has no lock tool", BridgeTool.lockTool(plain));
            b.rich = true;
            List<BridgeTool> rich = BridgeTool.parseAll(c.deskCapabilities());
            assertEquals(8, rich.size());
            BridgeTool lock = BridgeTool.lockTool(rich);
            assertNotNull(lock);
            assertEquals("Lock the workstation", lock.description);
            assertEquals("Workstation locked", c.deskRun(lock.name, null));
            assertEquals("level", BridgeTool.find(rich, "set_volume").params.get(0).name);
        } finally {
            b.stop();
        }
    }
}
