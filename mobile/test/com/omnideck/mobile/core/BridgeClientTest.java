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

    /** A server on the bridge port that answers every request with {@code body} (HTTP 200). */
    private static com.sun.net.httpserver.HttpServer foreign(final String type, final byte[] body) throws Exception {
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 8);
        s.createContext("/", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
                ex.getResponseHeaders().set("Content-Type", type);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            }
        });
        s.start();
        return s;
    }

    @Test
    public void unreadableOrOversizedRepliesAreErrorsNotEmptySuccess() throws Exception {
        com.sun.net.httpserver.HttpServer html = foreign("text/html", "<html><body>Router login</body></html>"
                .getBytes(Http.UTF8));
        try {
            BridgeClient b = new BridgeClient("127.0.0.1", html.getAddress().getPort(), "t");
            try {
                b.health();
                fail("an HTML page is not a LaunchBridge reply");
            } catch (BridgeClient.BridgeException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("can't read"));
            }
            try {
                b.deskRun("screenshot", null);
                fail();
            } catch (BridgeClient.BridgeException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("can't read"));
            }
        } finally {
            html.stop(0);
        }

        byte[] huge = new byte[Http.MAX_BODY + 4096];
        java.util.Arrays.fill(huge, (byte) 'A');
        huge[0] = '"';
        com.sun.net.httpserver.HttpServer big = foreign("application/json", huge);
        try {
            new BridgeClient("127.0.0.1", big.getAddress().getPort(), "t").deskRun("screenshot", null);
            fail("a cut-off reply must not pass as a result");
        } catch (BridgeClient.BridgeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("too large"));
        } finally {
            big.stop(0);
        }
    }

    @Test
    public void unpairedHintExplainsWhyNoTokenIsSent() {
        BridgeClient b = new BridgeClient("10.0.0.9", bridge.port(), "", "Paired with another PC — run /pair.");
        assertEquals("10.0.0.9", b.host());
        assertEquals(bridge.port(), b.port());
        try {
            b.deskRun("get_volume", null);
            fail();
        } catch (BridgeClient.BridgeException e) {
            assertEquals("Paired with another PC — run /pair.", e.getMessage());
            assertEquals(401, e.code);
        }
    }

    @Test
    public void sameHostComparesMachineAddresses() {
        assertTrue(HostPort.sameHost("192.168.1.20", " 192.168.1.20 "));
        assertTrue(HostPort.sameHost("Atlas-PC.lan", "atlas-pc.LAN"));
        assertTrue(HostPort.sameHost("[fe80::1]", "fe80::1"));
        assertTrue(!HostPort.sameHost("192.168.1.20", "192.168.1.21"));
        assertTrue(!HostPort.sameHost("", ""));
        assertTrue(!HostPort.sameHost(null, "a"));
    }

    @Test
    public void readAllRefusesToCutOffABody() throws Exception {
        byte[] data = new byte[100];
        assertEquals(100, Http.readAll(new java.io.ByteArrayInputStream(data), 100).length());
        try {
            Http.readAll(new java.io.ByteArrayInputStream(data), 99);
            fail();
        } catch (Http.TooLargeException e) {
            assertEquals(99, e.limit);
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
