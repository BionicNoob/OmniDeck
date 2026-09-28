package com.omnideck.mobile.core;

import com.omnideck.mobile.mock.MockOllama;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Typed addresses keep https://, and the API key reaches exactly the server it's meant for. */
public class AddressAuthTest {
    private MockOllama mock;

    @Before
    public void setUp() throws Exception {
        mock = MockOllama.start("127.0.0.1", 0);
        mock.tokenDelayMs = 0;
    }

    @After
    public void tearDown() {
        mock.stop();
    }

    @Test
    public void parsesSchemesAndDefaultPorts() {
        HostPort a = HostPort.parse("https://ai.example.com", 11434);
        assertTrue(a.https);
        assertEquals("ai.example.com", a.host);
        assertEquals(443, a.port);
        assertEquals("https://ai.example.com:443", a.baseUrl());
        assertEquals("https://ai.example.com", a.label(11434));

        HostPort b = HostPort.parse("HTTPS://ai.example.com:8443/ollama/", 11434);
        assertTrue(b.https);
        assertEquals(8443, b.port);
        assertEquals("https://ai.example.com:8443", b.label(11434));

        HostPort c = HostPort.parse("http://192.168.1.20", 11434);
        assertFalse(c.https);
        assertEquals("http keeps Ollama's port", 11434, c.port);
        assertEquals("http://192.168.1.20:11434", c.baseUrl());
        assertEquals("192.168.1.20", c.label(11434));

        HostPort d = HostPort.parse("pc.lan:8080", 11434);
        assertFalse(d.https);
        assertEquals("pc.lan:8080", d.label(11434));

        HostPort v6 = HostPort.parse("https://[fe80::1]", 11434);
        assertTrue(v6.https);
        assertEquals(443, v6.port);
        assertEquals("https://[fe80::1]", v6.label(11434));
        assertEquals("https://[fe80::1]:443", v6.baseUrl());

        assertNull(HostPort.parse("https://", 11434));
        assertNull(HostPort.parse("https://host:99999", 11434));
        assertNull(HostPort.parse("   ", 11434));

        assertTrue(a.matches("AI.example.com", 443, true));
        assertFalse("scheme matters", a.matches("ai.example.com", 443, false));
        assertFalse("port matters", a.matches("ai.example.com", 8443, true));
    }

    @Test
    public void serverInfoShowsAndUsesTheScheme() {
        ServerInfo tls = new ServerInfo("ai.example.com", 443, true, "0.12.6", 40);
        assertEquals("https://ai.example.com", tls.label());
        assertEquals("https://ai.example.com:443", tls.baseUrl());
        ServerInfo lan = new ServerInfo("192.168.1.20", 11434, "0.12.6", 4);
        assertFalse(lan.https);
        assertEquals("192.168.1.20", lan.label());
        assertEquals("http://192.168.1.20:11434", lan.baseUrl());
        assertEquals("192.168.1.20:8080", new ServerInfo("192.168.1.20", 8080, "", 1).label());
        OllamaClient c = new OllamaClient("ai.example.com", 443, true, " k ");
        assertEquals("https://ai.example.com:443", c.baseUrl());
        assertTrue(c.https());
        assertTrue(c.hasApiKey());
        assertFalse(new OllamaClient("h", 1, false, "  ").hasApiKey());
        assertFalse(new OllamaClient("h", 1).hasApiKey());
    }

    @Test
    public void apiKeyIsSentAsBearerOnEveryRequest() throws Exception {
        mock.requiredKey = "s3cret";
        OllamaClient keyed = new OllamaClient("127.0.0.1", mock.port(), false, "s3cret");
        assertEquals("0.12.6", keyed.version(2000));
        assertEquals(2, keyed.listModels().size());
        assertTrue(keyed.show("llama3.2:3b").supports("completion"));
        OllamaClientTest.Recorder r = new OllamaClientTest.Recorder();
        keyed.chat(OllamaClient.chatBody("llama3.2:3b", new org.json.JSONArray().put(
                new org.json.JSONObject().put("role", "user").put("content", "hi")), null, null, null),
                new Cancellable(), r);
        assertNull(r.error.get());
        assertNotNull(r.stats.get());
        synchronized (mock.authSeen) {
            for (String seen : mock.authSeen) assertTrue(seen, seen.endsWith(" Bearer s3cret"));
        }

        try {
            new OllamaClient("127.0.0.1", mock.port()).listModels();
            fail("no key, no models");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("401"));
        }
        assertEquals(ReplyError.UNAUTHORIZED, ReplyError.explain("unauthorized (HTTP 401)", "m", false).kind);
    }

    @Test
    public void probesTellARefusedServerFromAnEmptyAddress() throws Exception {
        mock.requiredKey = "s3cret";
        OllamaClient.Probe refused = OllamaClient.probeDetailed("127.0.0.1", mock.port(), false, null, 2000);
        assertNull(refused.server);
        assertEquals(401, refused.code);
        assertTrue(refused.refused());
        OllamaClient.Probe ok = OllamaClient.probeDetailed("127.0.0.1", mock.port(), false, "s3cret", 2000);
        assertNotNull(ok.server);
        assertEquals("0.12.6", ok.server.version);
        assertFalse(ok.refused());

        // The plain probe (what a LAN scan uses) never carries a key.
        mock.requiredKey = null;
        mock.authSeen.clear();
        assertNotNull(OllamaClient.probe("127.0.0.1", mock.port(), 2000));
        List<String> scan = new ArrayList<String>();
        scan.add("127.0.0.1");
        assertEquals(1, LanScanner.scan(scan, mock.port(), 2, 500, 2000, new Cancellable(), true, null).size());
        synchronized (mock.authSeen) {
            for (String seen : mock.authSeen) assertFalse(seen, seen.contains("Bearer"));
        }
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int dead = ss.getLocalPort();
        ss.close();
        OllamaClient.Probe nothing = OllamaClient.probeDetailed("127.0.0.1", dead, false, "k", 500);
        assertEquals(-1, nothing.code);
        assertFalse(nothing.refused());
    }
}
