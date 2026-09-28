package com.omnideck.mobile.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for an Ollama server that reproduces the shapes of the real API
 * (GET /, /api/version, /api/tags, /api/ps, POST /api/show, /api/chat with
 * chunked NDJSON streaming, /api/pull). Used by the JVM and Robolectric
 * tests; also runnable on its own: {@code java MockOllama [port]}.
 */
public final class MockOllama {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Model {
        final String name;
        final long size;
        final String params;
        final String quant;
        final boolean thinking;

        public Model(String name, long size, String params, String quant, boolean thinking) {
            this.name = name;
            this.size = size;
            this.params = params;
            this.quant = quant;
            this.thinking = thinking;
        }
    }

    /** Produces the reply tokens for a request (default: echo the last user message). */
    public interface Replier {
        List<String> reply(JSONObject request, String lastUserText);
    }

    private final HttpServer server;
    private final Map<String, Model> models = new LinkedHashMap<String, Model>();
    private final Set<String> loaded = Collections.synchronizedSet(new LinkedHashSet<String>());
    private final Map<String, Integer> loadedCtx = Collections.synchronizedMap(new LinkedHashMap<String, Integer>());
    public final List<JSONObject> chatRequests = Collections.synchronizedList(new ArrayList<JSONObject>());
    public final AtomicInteger clientDisconnects = new AtomicInteger();
    public volatile long tokenDelayMs = 15;
    public volatile long firstTokenDelayMs = 0;
    public volatile String midStreamError = null;
    public volatile List<String> rawContentChunks = null;
    public volatile Replier replier = null;
    public volatile String version = "0.12.6";

    public MockOllama(InetAddress bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    route(ex);
                } catch (JSONException e) {
                    sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                } catch (RuntimeException e) {
                    sendJson(ex, 500, "{\"error\":\"mock failure\"}");
                } finally {
                    ex.close();
                }
            }
        });
        addModel(new Model("llama3.2:3b", 2019393189L, "3.2B", "Q4_K_M", false));
        addModel(new Model("qwen3:8b", 5225388164L, "8.2B", "Q4_K_M", true));
    }

    public static MockOllama start(String bindHost, int port) throws IOException {
        MockOllama m = new MockOllama(InetAddress.getByName(bindHost), port);
        m.server.start();
        return m;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    public synchronized void addModel(Model m) {
        models.put(m.name, m);
    }

    public synchronized void clearModels() {
        models.clear();
    }

    public boolean isLoaded(String name) {
        return loaded.contains(name);
    }

    public JSONObject lastChatRequest() {
        synchronized (chatRequests) {
            return chatRequests.isEmpty() ? null : chatRequests.get(chatRequests.size() - 1);
        }
    }

    private void route(HttpExchange ex) throws IOException, JSONException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if ("GET".equals(method) && "/".equals(path)) {
            send(ex, 200, "text/plain; charset=utf-8", "Ollama is running");
        } else if ("GET".equals(method) && "/api/version".equals(path)) {
            sendJson(ex, 200, new JSONObject().put("version", version).toString());
        } else if ("GET".equals(method) && "/api/tags".equals(path)) {
            JSONArray arr = new JSONArray();
            synchronized (this) {
                for (Model m : models.values()) arr.put(modelJson(m));
            }
            sendJson(ex, 200, new JSONObject().put("models", arr).toString());
        } else if ("GET".equals(method) && "/api/ps".equals(path)) {
            JSONArray arr = new JSONArray();
            synchronized (this) {
                for (String n : new ArrayList<String>(loaded)) {
                    Model m = models.get(n);
                    if (m == null) continue;
                    JSONObject o = modelJson(m);
                    o.put("size_vram", m.size / 2);
                    o.put("expires_at", "2318-01-01T00:00:00Z");
                    Integer ctx = loadedCtx.get(n);
                    o.put("context_length", ctx == null ? 8192 : ctx);
                    arr.put(o);
                }
            }
            sendJson(ex, 200, new JSONObject().put("models", arr).toString());
        } else if ("POST".equals(method) && "/api/show".equals(path)) {
            JSONObject req = new JSONObject(readBody(ex));
            Model m = model(req.optString("model", ""));
            if (m == null) {
                sendJson(ex, 404, "{\"error\":\"model '" + req.optString("model") + "' not found\"}");
                return;
            }
            JSONArray caps = new JSONArray().put("completion");
            if (m.thinking) caps.put("thinking");
            JSONObject info = new JSONObject().put("general.architecture", "llama").put("llama.context_length", 131072);
            sendJson(ex, 200, new JSONObject().put("capabilities", caps).put("model_info", info).toString());
        } else if ("POST".equals(method) && "/api/chat".equals(path)) {
            chat(ex, new JSONObject(readBody(ex)));
        } else if ("POST".equals(method) && "/api/pull".equals(path)) {
            pull(ex, new JSONObject(readBody(ex)));
        } else {
            send(ex, 404, "text/plain", "404 page not found");
        }
    }

    private synchronized Model model(String name) {
        Model m = models.get(name);
        if (m == null && name.indexOf(':') < 0) m = models.get(name + ":latest");
        return m;
    }

    private static JSONObject modelJson(Model m) throws JSONException {
        JSONObject d = new JSONObject().put("format", "gguf").put("family", "llama")
                .put("parameter_size", m.params).put("quantization_level", m.quant);
        return new JSONObject().put("name", m.name).put("model", m.name).put("size", m.size)
                .put("digest", "sha256:" + Integer.toHexString(m.name.hashCode())).put("details", d)
                .put("modified_at", "2026-09-01T10:00:00Z");
    }

    private void chat(HttpExchange ex, JSONObject req) throws IOException, JSONException {
        chatRequests.add(req);
        String name = req.optString("model", "");
        Model m = model(name);
        if (m == null) {
            sendJson(ex, 404, "{\"error\":\"model \\\"" + name + "\\\" not found, try pulling it first\"}");
            return;
        }
        JSONArray msgs = req.optJSONArray("messages");
        Object keepAlive = req.opt("keep_alive");
        boolean unload = keepAlive != null && "0".equals(String.valueOf(keepAlive));
        if (msgs == null || msgs.length() == 0) {
            // Load / unload request.
            if (unload) {
                loaded.remove(m.name);
            } else {
                loaded.add(m.name);
                JSONObject opts = req.optJSONObject("options");
                if (opts != null && opts.has("num_ctx")) loadedCtx.put(m.name, opts.optInt("num_ctx"));
            }
            sendJson(ex, 200, new JSONObject().put("model", m.name).put("created_at", now())
                    .put("message", new JSONObject().put("role", "assistant").put("content", ""))
                    .put("done_reason", unload ? "unload" : "load").put("done", true).toString());
            return;
        }
        boolean wasLoaded = loaded.contains(m.name);
        loaded.add(m.name);
        String lastUser = "";
        for (int i = msgs.length() - 1; i >= 0; i--) {
            JSONObject o = msgs.optJSONObject(i);
            if (o != null && "user".equals(o.optString("role"))) {
                lastUser = o.optString("content", "");
                break;
            }
        }
        boolean stream = req.optBoolean("stream", true);
        List<String> tokens = replier != null ? replier.reply(req, lastUser) : defaultReply(lastUser);
        boolean think = m.thinking && !(req.has("think") && Boolean.FALSE.equals(req.opt("think")));
        List<String> thinkTokens = think ? words("Let me think about \"" + lastUser + "\" carefully.")
                : Collections.<String>emptyList();

        if (!stream) {
            StringBuilder all = new StringBuilder();
            for (String t : tokens) all.append(t);
            JSONObject msg = new JSONObject().put("role", "assistant").put("content", all.toString());
            JSONObject fin = finalLine(m, tokens.size(), wasLoaded).put("message", msg);
            sendJson(ex, 200, fin.toString());
            return;
        }

        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        try {
            sleep(firstTokenDelayMs);
            for (String t : thinkTokens) {
                writeLine(os, new JSONObject().put("model", m.name).put("created_at", now())
                        .put("message", new JSONObject().put("role", "assistant").put("content", "").put("thinking", t))
                        .put("done", false));
                sleep(tokenDelayMs);
            }
            List<String> chunks = rawContentChunks != null ? rawContentChunks : tokens;
            int i = 0;
            for (String t : chunks) {
                if (midStreamError != null && i == 2) {
                    writeLine(os, new JSONObject().put("error", midStreamError));
                    return;
                }
                writeLine(os, new JSONObject().put("model", m.name).put("created_at", now())
                        .put("message", new JSONObject().put("role", "assistant").put("content", t))
                        .put("done", false));
                sleep(tokenDelayMs);
                i++;
            }
            JSONObject fin = finalLine(m, chunks.size(), wasLoaded)
                    .put("message", new JSONObject().put("role", "assistant").put("content", ""));
            writeLine(os, fin);
        } catch (IOException e) {
            clientDisconnects.incrementAndGet();
        } finally {
            try { os.close(); } catch (IOException ignored) { }
        }
    }

    private JSONObject finalLine(Model m, int evalCount, boolean wasLoaded) throws JSONException {
        long evalNs = Math.max(1, evalCount) * 25000000L;
        long loadNs = wasLoaded ? 12000000L : 2400000000L;
        return new JSONObject().put("model", m.name).put("created_at", now()).put("done", true)
                .put("done_reason", "stop").put("total_duration", evalNs + loadNs + 90000000L)
                .put("load_duration", loadNs).put("prompt_eval_count", 26).put("prompt_eval_duration", 90000000L)
                .put("eval_count", evalCount).put("eval_duration", evalNs);
    }

    private void pull(HttpExchange ex, JSONObject req) throws IOException, JSONException {
        final String name = req.optString("model", req.optString("name", ""));
        if (name.startsWith("missing")) {
            sendJson(ex, 500, "{\"error\":\"pull model manifest: file does not exist\"}");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        try {
            writeLine(os, new JSONObject().put("status", "pulling manifest"));
            long total = 1000000;
            for (long done = 0; done <= total; done += 250000) {
                writeLine(os, new JSONObject().put("status", "pulling 6a0746a1ec1a").put("digest", "sha256:6a0746a1ec1a")
                        .put("total", total).put("completed", done));
                sleep(tokenDelayMs);
            }
            writeLine(os, new JSONObject().put("status", "verifying sha256 digest"));
            writeLine(os, new JSONObject().put("status", "writing manifest"));
            addModel(new Model(name.indexOf(':') < 0 ? name + ":latest" : name, total, "1B", "Q8_0", false));
            writeLine(os, new JSONObject().put("status", "success"));
        } catch (IOException e) {
            clientDisconnects.incrementAndGet();
        } finally {
            try { os.close(); } catch (IOException ignored) { }
        }
    }

    public static List<String> defaultReply(String lastUser) {
        return words("You said: " + lastUser + ". **Streaming** works over the `network`.");
    }

    /** Splits text into word tokens that keep their trailing spaces, like a tokenizer would. */
    public static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < text.length()) {
            int j = i;
            while (j < text.length() && text.charAt(j) != ' ') j++;
            while (j < text.length() && text.charAt(j) == ' ') j++;
            out.add(text.substring(i, j));
            i = j;
        }
        return out;
    }

    private static void writeLine(OutputStream os, JSONObject o) throws IOException {
        os.write((o.toString() + "\n").getBytes(UTF8));
        os.flush();
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(UTF8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        OutputStream os = ex.getResponseBody();
        os.write(b);
        os.close();
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, "application/json; charset=utf-8", json);
    }

    static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        String s = new String(out.toByteArray(), UTF8);
        return s.length() == 0 ? "{}" : s;
    }

    private static String now() {
        return "2026-09-28T12:00:00.000000Z";
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 11434;
        MockOllama m = MockOllama.start("0.0.0.0", port);
        m.tokenDelayMs = 60;
        System.out.println("Mock Ollama listening on port " + m.port());
    }
}
