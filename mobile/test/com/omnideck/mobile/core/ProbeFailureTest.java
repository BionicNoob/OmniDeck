package com.omnideck.mobile.core;

import org.junit.Test;

import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A typed address that doesn't work says why, in words that point at the fix. */
public class ProbeFailureTest {

    @Test
    public void explainsTheCommonFailures() {
        assertTrue(OllamaClient.explainFailure(new ConnectException("Connection refused"), "192.168.1.20", 11434, false)
                .contains("OLLAMA_HOST=0.0.0.0"));
        assertTrue(OllamaClient.explainFailure(new SocketTimeoutException("timeout"), "192.168.1.20", 11434, false)
                .contains("asleep"));
        assertTrue(OllamaClient.explainFailure(new UnknownHostException("atlas"), "atlas", 11434, false)
                .contains("check the address"));
        assertTrue(OllamaClient.explainFailure(new javax.net.ssl.SSLHandshakeException("bad cert"), "ai.example.com",
                443, true).contains("certificate"));
        assertTrue(OllamaClient.explainFailure(new java.io.IOException("weird"), "h", 1, false).contains("weird"));
    }

    @Test
    public void aClosedPortIsExplainedAndSomethingElseIsNotOllama() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        OllamaClient.Probe p = OllamaClient.probeDetailed("127.0.0.1", port, false, null, 1500);
        assertNull(p.server);
        assertTrue(p.failure, p.failure.contains("refused port " + port));

        // A web server that isn't Ollama.
        com.sun.net.httpserver.HttpServer web = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 4);
        web.createContext("/", ex -> {
            byte[] b = "<html>router</html>".getBytes("UTF-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        web.start();
        try {
            OllamaClient.Probe q = OllamaClient.probeDetailed("127.0.0.1", web.getAddress().getPort(), false, null, 1500);
            assertNull(q.server);
            assertTrue(q.failure, q.failure.contains("isn't Ollama"));
        } finally {
            web.stop(0);
        }
    }
}
