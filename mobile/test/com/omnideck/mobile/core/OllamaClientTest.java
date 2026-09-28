package com.omnideck.mobile.core;

import com.omnideck.mobile.mock.MockOllama;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OllamaClientTest {
    private MockOllama mock;
    private OllamaClient client;

    @Before
    public void setUp() throws Exception {
        mock = MockOllama.start("127.0.0.1", 0);
        client = new OllamaClient("127.0.0.1", mock.port());
    }

    @After
    public void tearDown() {
        mock.stop();
    }

    /** Collects a streamed reply. */
    static final class Recorder implements OllamaClient.ChatListener {
        final StringBuffer content = new StringBuffer();
        final StringBuffer thinking = new StringBuffer();
        final List<String> contentChunks = Collections.synchronizedList(new ArrayList<String>());
        final AtomicLong firstContentAt = new AtomicLong();
        final AtomicLong endAt = new AtomicLong();
        final AtomicReference<ChatStats> stats = new AtomicReference<ChatStats>();
        final AtomicReference<String> error = new AtomicReference<String>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch firstToken = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        int endCalls;

        @Override
        public void onThinking(String delta) {
            thinking.append(delta);
        }

        @Override
        public void onContent(String delta) {
            firstContentAt.compareAndSet(0, System.nanoTime());
            content.append(delta);
            contentChunks.add(delta);
            firstToken.countDown();
        }

        @Override
        public synchronized void onDone(ChatStats s) {
            endCalls++;
            stats.set(s);
            endAt.set(System.nanoTime());
            finished.countDown();
        }

        @Override
        public synchronized void onError(String message, boolean wasCancelled) {
            endCalls++;
            error.set(message);
            cancelled.set(wasCancelled);
            endAt.set(System.nanoTime());
            finished.countDown();
        }
    }

    private static JSONArray userOnly(String text) throws Exception {
        return new JSONArray().put(new JSONObject().put("role", "user").put("content", text));
    }

    @Test
    public void probeAcceptsOllama() {
        ServerInfo s = OllamaClient.probe("127.0.0.1", mock.port(), 2000);
        assertNotNull(s);
        assertEquals("0.12.6", s.version);
        assertEquals("127.0.0.1", s.host);
        assertTrue(s.latencyMs >= 0);
    }

    @Test
    public void probeRejectsOtherHttpServers() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        other.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] b = "<html>router login</html>".getBytes("UTF-8");
                ex.sendResponseHeaders(200, b.length);
                OutputStream os = ex.getResponseBody();
                os.write(b);
                os.close();
            }
        });
        other.start();
        try {
            assertNull(OllamaClient.probe("127.0.0.1", other.getAddress().getPort(), 2000));
        } finally {
            other.stop(0);
        }
    }

    @Test
    public void probeClosedPortReturnsNull() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        long t0 = System.currentTimeMillis();
        assertNull(OllamaClient.probe("127.0.0.1", port, 1500));
        assertTrue("closed port should fail fast", System.currentTimeMillis() - t0 < 1500);
    }

    @Test
    public void listsModelsRunningAndCapabilities() throws Exception {
        List<ModelInfo> models = client.listModels();
        assertEquals(2, models.size());
        assertEquals("llama3.2:3b", models.get(0).name);
        assertEquals("3.2B", models.get(0).parameterSize);
        assertEquals("Q4_K_M", models.get(0).quantization);
        assertTrue(models.get(0).describe().contains("1.9 GB"));

        assertTrue(client.listRunning().isEmpty());
        long ms = client.loadModel("llama3.2:3b", -1, new JSONObject().put("num_ctx", 4096));
        assertTrue(ms >= 0);
        List<ModelInfo> running = client.listRunning();
        assertEquals(1, running.size());
        assertTrue(running.get(0).loaded);
        assertEquals(4096, running.get(0).contextLength);

        client.loadModel("llama3.2:3b", 0, null);
        assertTrue(client.listRunning().isEmpty());

        OllamaClient.ModelDetails d = client.show("qwen3:8b");
        assertTrue(d.supports("thinking"));
        assertEquals(131072, d.contextLength);
        assertFalse(client.show("llama3.2:3b").supports("thinking"));
        try {
            client.show("nope:1b");
            fail("expected an error for an unknown model");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not found"));
        }
    }

    @Test
    public void chatStreamsTokensIncrementally() throws Exception {
        mock.tokenDelayMs = 80;
        Recorder r = new Recorder();
        long t0 = System.nanoTime();
        client.chat(OllamaClient.chatBody("llama3.2:3b", userOnly("hello there"), null, -1, null),
                new Cancellable(), r);
        assertEquals(1, r.endCalls);
        assertNull(r.error.get());
        String expected = "You said: hello there. **Streaming** works over the `network`.";
        assertEquals(expected, r.content.toString());
        assertTrue("reply should arrive in several chunks", r.contentChunks.size() >= 8);
        long firstMs = (r.firstContentAt.get() - t0) / 1000000L;
        long endMs = (r.endAt.get() - t0) / 1000000L;
        // The first token must show up long before the reply finishes.
        assertTrue("first token at " + firstMs + "ms, end at " + endMs + "ms", endMs - firstMs >= 400);
        ChatStats s = r.stats.get();
        assertNotNull(s);
        assertEquals(r.contentChunks.size(), s.evalTokens);
        assertEquals("stop", s.doneReason);
        assertTrue(s.tokensPerSecond() > 0);
        assertTrue(s.reloaded());
        assertTrue(s.summary(), s.summary().contains("tok/s"));
    }

    @Test
    public void chatSendsHistorySystemPromptAndRunnerOptions() throws Exception {
        Conversation c = new Conversation();
        c.messages.add(new ChatMessage(ChatMessage.USER, "first question"));
        ChatMessage a = new ChatMessage(ChatMessage.ASSISTANT, "first answer");
        c.messages.add(a);
        c.messages.add(ChatMessage.notice("Switched model", "info"));
        c.messages.add(new ChatMessage(ChatMessage.ASSISTANT, "   "));
        c.messages.add(new ChatMessage(ChatMessage.USER, "second question"));
        JSONObject opts = new JSONObject().put("num_ctx", 8192).put("num_thread", 6);
        JSONArray msgs = c.toRequestMessages("You are OMNI.", null);
        Recorder r = new Recorder();
        client.chat(OllamaClient.chatBody("qwen3:8b", msgs, Boolean.FALSE, -1, opts), new Cancellable(), r);
        assertNull(r.error.get());

        JSONObject req = mock.lastChatRequest();
        assertEquals("qwen3:8b", req.getString("model"));
        assertTrue(req.getBoolean("stream"));
        assertEquals(-1, req.getInt("keep_alive"));
        assertEquals(Boolean.FALSE, req.get("think"));
        assertEquals(8192, req.getJSONObject("options").getInt("num_ctx"));
        assertEquals(6, req.getJSONObject("options").getInt("num_thread"));
        JSONArray sent = req.getJSONArray("messages");
        assertEquals(4, sent.length());
        assertEquals("system", sent.getJSONObject(0).getString("role"));
        assertEquals("You are OMNI.", sent.getJSONObject(0).getString("content"));
        assertEquals("first question", sent.getJSONObject(1).getString("content"));
        assertEquals("assistant", sent.getJSONObject(2).getString("role"));
        assertEquals("second question", sent.getJSONObject(3).getString("content"));
        // think:false on a thinking model → no thinking tokens.
        assertEquals("", r.thinking.toString());
    }

    @Test
    public void chatSeparatesThinkingField() throws Exception {
        Recorder r = new Recorder();
        client.chat(OllamaClient.chatBody("qwen3:8b", userOnly("why?"), Boolean.TRUE, -1, null), new Cancellable(), r);
        assertNull(r.error.get());
        assertEquals("Let me think about \"why?\" carefully.", r.thinking.toString());
        assertTrue(r.content.toString().startsWith("You said: why?"));
    }

    @Test
    public void chatSplitsInlineThinkTagsAcrossChunks() throws Exception {
        mock.rawContentChunks = Arrays.asList("<thi", "nk>pondering", " deeply</th", "ink>\n\nThe answer", " is 42.");
        Recorder r = new Recorder();
        client.chat(OllamaClient.chatBody("llama3.2:3b", userOnly("q"), null, -1, null), new Cancellable(), r);
        assertNull(r.error.get());
        assertEquals("pondering deeply", r.thinking.toString());
        assertEquals("\n\nThe answer is 42.", r.content.toString());
    }

    @Test
    public void cancelStopsTheStreamQuickly() throws Exception {
        mock.tokenDelayMs = 50;
        mock.replier = new MockOllama.Replier() {
            @Override
            public List<String> reply(JSONObject request, String lastUserText) {
                List<String> t = new ArrayList<String>();
                for (int i = 0; i < 400; i++) t.add("word" + i + " ");
                return t;
            }
        };
        final Cancellable cancel = new Cancellable();
        final Recorder r = new Recorder();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.chat(OllamaClient.chatBody("llama3.2:3b", userOnly("long"), null, -1, null), cancel, r);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        t.start();
        assertTrue(r.firstToken.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        cancel.cancel();
        assertTrue("cancel should end the call", r.finished.await(3, TimeUnit.SECONDS));
        long ms = (System.nanoTime() - t0) / 1000000L;
        assertTrue("took " + ms + "ms", ms < 1500);
        assertTrue(r.cancelled.get());
        assertEquals(1, r.endCalls);
        assertTrue(r.content.length() > 0);
        t.join(3000);
        // The server sees the client go away (that's what makes Ollama stop generating).
        long deadline = System.currentTimeMillis() + 3000;
        while (mock.clientDisconnects.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, mock.clientDisconnects.get());
    }

    @Test
    public void unknownModelReportsOllamasError() throws Exception {
        Recorder r = new Recorder();
        client.chat(OllamaClient.chatBody("ghost:7b", userOnly("hi"), null, -1, null), new Cancellable(), r);
        assertNotNull(r.error.get());
        assertTrue(r.error.get(), r.error.get().contains("not found"));
        assertFalse(r.cancelled.get());
    }

    @Test
    public void midStreamErrorLineIsReported() throws Exception {
        mock.midStreamError = "llama runner process has terminated: out of memory";
        Recorder r = new Recorder();
        client.chat(OllamaClient.chatBody("llama3.2:3b", userOnly("hi"), null, -1, null), new Cancellable(), r);
        assertEquals("llama runner process has terminated: out of memory", r.error.get());
        assertTrue(r.content.length() > 0);
        assertEquals(1, r.endCalls);
    }

    @Test
    public void unreachableServerGivesReadableError() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        Recorder r = new Recorder();
        new OllamaClient("127.0.0.1", port).chat(OllamaClient.chatBody("x", userOnly("hi"), null, -1, null),
                new Cancellable(), r);
        assertTrue(r.error.get(), r.error.get().contains("Can't connect"));
    }

    @Test
    public void pullReportsProgressAndInstallsTheModel() throws Exception {
        final List<Long> progress = Collections.synchronizedList(new ArrayList<Long>());
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicReference<String> err = new AtomicReference<String>();
        client.pull("tinyllama", new Cancellable(), new OllamaClient.PullListener() {
            @Override
            public void onProgress(String status, long completed, long total) {
                if (total > 0) progress.add(completed * 100 / total);
            }

            @Override
            public void onDone() {
                done.set(true);
            }

            @Override
            public void onError(String message, boolean cancelled) {
                err.set(message);
            }
        });
        assertNull(err.get());
        assertTrue(done.get());
        assertEquals(Arrays.asList(0L, 25L, 50L, 75L, 100L), progress);
        boolean found = false;
        for (ModelInfo m : client.listModels()) found |= m.name.equals("tinyllama:latest");
        assertTrue(found);

        client.pull("missing-model", new Cancellable(), new OllamaClient.PullListener() {
            @Override
            public void onProgress(String status, long completed, long total) {
            }

            @Override
            public void onDone() {
                fail("should not succeed");
            }

            @Override
            public void onError(String message, boolean cancelled) {
                err.set(message);
            }
        });
        assertTrue(err.get(), err.get().contains("file does not exist"));
    }

    @Test
    public void strTreatsJsonNullAsEmpty() throws Exception {
        JSONObject o = new JSONObject("{\"a\":null,\"b\":\"x\",\"c\":3}");
        assertEquals("", OllamaClient.str(o, "a"));
        assertEquals("x", OllamaClient.str(o, "b"));
        assertEquals("3", OllamaClient.str(o, "c"));
        assertEquals("", OllamaClient.str(o, "missing"));
    }
}
