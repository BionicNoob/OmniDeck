package com.omnideck.mobile.core;

import com.omnideck.mobile.mock.MockBridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BridgeClientTest {
    private MockBridge bridge;

    @Before
    public void setUp() throws Exception {
        bridge = MockBridge.start("127.0.0.1", 0);
    }

    @After
    public void tearDown() {
        bridge.stop();
    }

    @Test
    public void pairsAndRunsDesktopTools() throws Exception {
        BridgeClient unpaired = new BridgeClient("127.0.0.1", bridge.port(), "");
        assertEquals(42, unpaired.health().getInt("apps_indexed"));
        String token = unpaired.pair();
        assertEquals(bridge.token, token);

        BridgeClient b = new BridgeClient("127.0.0.1", bridge.port(), token);
        assertEquals("Volume is 35%", b.deskRun("get_volume", null));
        assertEquals("Volume set to 70%", b.deskRun("set_volume", new JSONObject().put("level", 70)));
        assertEquals(70, bridge.volume);
        Object sys = b.deskRun("get_system_info", null);
        assertTrue(sys instanceof JSONObject);
        assertEquals("12%", ((JSONObject) sys).getString("cpu"));
        assertEquals("text from the PC clipboard", b.deskRun("get_clipboard", null));
        JSONObject shot = (JSONObject) b.deskRun("screenshot", new JSONObject().put("save", false));
        assertTrue(shot.getString("image").startsWith("data:image/png;base64,"));
        try {
            b.deskRun("format_disk", null);
            fail("unknown tools must fail");
        } catch (BridgeClient.BridgeException e) {
            assertTrue(e.getMessage().contains("Unknown tool"));
        }
    }

    @Test
    public void launchesAppsWithDryRunAndChoices() throws Exception {
        BridgeClient b = new BridgeClient("127.0.0.1", bridge.port(), bridge.token);
        JSONObject dry = b.launchQuery("spotify", true);
        JSONObject target = dry.getJSONObject("would_launch");
        assertEquals("Spotify", target.getString("name"));
        assertTrue(bridge.launched.isEmpty());
        JSONObject done = b.launchId(target.getString("id"));
        assertEquals("Spotify", done.getJSONObject("app").getString("name"));
        assertEquals(1, bridge.launched.size());

        JSONObject choice = b.launchQuery("code", true);
        assertTrue(choice.getBoolean("needs_choice"));
        assertEquals(2, choice.getJSONArray("candidates").length());

        JSONArray apps = b.apps("", 10);
        assertEquals(2, apps.length());
        try {
            b.launchQuery("nothing at all", false);
            fail();
        } catch (BridgeClient.BridgeException e) {
            // 404 here means "no app", but the client can't tell it from a
            // missing route; either way the user gets a readable message.
            assertTrue(e.getMessage().length() > 0);
        }
    }

    @Test
    public void wrongTokenAndUnpairedCallsExplainWhatToDo() throws Exception {
        try {
            new BridgeClient("127.0.0.1", bridge.port(), "wrong").deskRun("get_volume", null);
            fail();
        } catch (BridgeClient.BridgeException e) {
            assertEquals(401, e.code);
            assertTrue(e.getMessage().contains("/pair"));
        }
        try {
            new BridgeClient("127.0.0.1", bridge.port(), "").deskRun("get_volume", null);
            fail();
        } catch (BridgeClient.BridgeException e) {
            assertTrue(e.getMessage().contains("/pair"));
        }
    }

    @Test
    public void unreachableBridgeSaysSo() throws Exception {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        try {
            new BridgeClient("127.0.0.1", port, "t").health();
            fail();
        } catch (BridgeClient.BridgeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Can't reach"));
        }
    }
}
