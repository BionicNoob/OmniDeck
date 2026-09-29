package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to an Ollama server over its HTTP API (the same endpoints the
 * OMNI-DECK web app uses): /api/tags, /api/ps, /api/show, /api/chat
 * (streamed NDJSON) and /api/pull. All methods block; call them off the UI
 * thread.
 */
public final class OllamaClient {
    public static final int DEFAULT_PORT = 11434;
    public static final String BANNER = "Ollama is running";

    static final int CONNECT_TIMEOUT_MS = 4000;
    static final int QUICK_READ_TIMEOUT_MS = 8000;
    static final int LOAD_READ_TIMEOUT_MS = 5 * 60 * 1000;
    static final int STREAM_READ_TIMEOUT_MS = 10 * 60 * 1000;

    private final String host;
    private final int port;
    private final boolean https;
    private final String base;
    /** Sent with every request: the API key, when there is one; else null. */
    private final Map<String, String> headers;

    public OllamaClient(String host, int port) {
        this(host, port, false, null);
    }

    /**
     * {@code https} for a server behind TLS; {@code apiKey} (may be null or
     * "") is sent as "Authorization: Bearer …" — only give it for the server
     * the user configured, never for hosts found by scanning.
     */
    public OllamaClient(String host, int port, boolean https, String apiKey) {
        this.host = host;
        this.port = port;
        this.https = https;
        this.base = Http.baseUrl(https, host, port);
        this.headers = authHeaders(apiKey);
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean https() {
        return https;
    }

    public String baseUrl() {
        return base;
    }

    /** True when requests carry an API key. */
    public boolean hasApiKey() {
        return headers != null;
    }

    static Map<String, String> authHeaders(String apiKey) {
        if (apiKey == null || apiKey.trim().length() == 0) return null;
        Map<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + apiKey.trim());
        return h;
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * Returns server info when an Ollama server answers at host:port (plain
     * HTTP, no API key — what a network scan uses), or null when nothing (or
     * something that isn't Ollama) is there.
     */
    public static ServerInfo probe(String host, int port, int timeoutMs) {
        return probeDetailed(host, port, false, null, timeoutMs).server;
    }

    /** What a probe found: the server (null if none) and the HTTP status of its first request. */
    public static final class Probe {
        public final ServerInfo server;
        /** HTTP status of GET / (-1 when nothing answered). 401/403: the server wants an API key. */
        public final int code;
        /** Why a typed address didn't work, in plain words (null when it did, or when unknown). */
        public final String failure;

        Probe(ServerInfo server, int code) {
            this(server, code, null);
        }

        Probe(ServerInfo server, int code, String failure) {
            this.server = server;
            this.code = code;
            this.failure = failure;
        }

        /** True when a server answered but refused the request (missing or wrong API key). */
        public boolean refused() {
            return server == null && (code == 401 || code == 403);
        }
    }

    /** Probes a server the user configured: over https when asked, with the API key when given. */
    public static Probe probeDetailed(String host, int port, boolean https, String apiKey, int timeoutMs) {
        String base = Http.baseUrl(https, host, port);
        Map<String, String> auth = authHeaders(apiKey);
        long t0 = System.nanoTime();
        boolean isOllama = false;
        long latency = -1;
        int code;
        try {
            Http.Response r = Http.get(base + "/", timeoutMs, timeoutMs, auth);
            latency = (System.nanoTime() - t0) / 1000000L;
            code = r.code;
            isOllama = r.ok() && r.body.contains(BANNER);
        } catch (IOException e) {
            return new Probe(null, -1, explainFailure(e, host, port, https));
        }
        String version = "";
        try {
            Http.Response v = Http.get(base + "/api/version", timeoutMs, timeoutMs, auth);
            if (v.ok()) {
                JSONObject o = new JSONObject(v.body);
                version = str(o, "version");
                // Behind a proxy that rewrites "/", a valid /api/version is
                // just as good a signature.
                if (version.length() > 0) isOllama = true;
            }
        } catch (IOException ignored) {
        } catch (JSONException ignored) {
        }
        String notOllama = isOllama || code == 401 || code == 403 ? null
                : "Something answered at " + host + ":" + port + " (HTTP " + code + "), but it isn't Ollama — "
                + "check the port (Ollama uses 11434).";
        return new Probe(isOllama ? new ServerInfo(host, port, https, version, latency) : null, code, notOllama);
    }

    /** Why a connection to a typed address failed, and what usually fixes it. */
    static String explainFailure(IOException e, String host, int port, boolean https) {
        String where = host + ":" + port;
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.ConnectException && msg.toLowerCase(java.util.Locale.US).contains("refused")) {
            return "The PC at " + host + " refused port " + port + ": Ollama is probably only listening on the PC "
                    + "itself, or isn't running. Set OLLAMA_HOST=0.0.0.0 and restart it (see /setup).";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return where + " didn't answer in time: the PC may be asleep or on another network, or a firewall "
                    + "blocks port " + port + ".";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "\"" + host + "\" isn't a name this network knows — check the address.";
        }
        if (e instanceof java.net.NoRouteToHostException) {
            return "No route to " + host + ": the phone and the PC seem to be on different networks.";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "The secure (https) connection to " + host + " failed" + (msg.length() > 0 ? " (" + msg + ")" : "")
                    + ". Check the proxy's certificate" + (https ? ", or use http:// on your home network." : ".");
        }
        return "Couldn't reach " + where + (msg.length() > 0 ? ": " + msg : "") + ".";
    }

    public String version(int timeoutMs) throws IOException {
        Http.Response r = Http.get(base + "/api/version", timeoutMs, timeoutMs, headers);
        if (!r.ok()) throw new IOException(errorMessage(r.body, r.code));
        try {
            return str(new JSONObject(r.body), "version");
        } catch (JSONException e) {
            throw new IOException("Bad /api/version reply");
        }
    }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------

    /** Installed models (GET /api/tags). */
    public List<ModelInfo> listModels() throws IOException {
        Http.Response r = Http.get(base + "/api/tags", CONNECT_TIMEOUT_MS, QUICK_READ_TIMEOUT_MS, headers);
        if (!r.ok()) throw new IOException(errorMessage(r.body, r.code));
        List<ModelInfo> out = new ArrayList<ModelInfo>();
        try {
            JSONArray arr = new JSONObject(r.body).optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    ModelInfo m = ModelInfo.fromTags(o);
                    if (m.name.length() > 0) out.add(m);
                }
            }
        } catch (JSONException e) {
            throw new IOException("Bad /api/tags reply");
        }
        return out;
    }

    /** Models currently loaded in memory (GET /api/ps). */
    public List<ModelInfo> listRunning() throws IOException {
        Http.Response r = Http.get(base + "/api/ps", CONNECT_TIMEOUT_MS, QUICK_READ_TIMEOUT_MS, headers);
        if (!r.ok()) throw new IOException(errorMessage(r.body, r.code));
        List<ModelInfo> out = new ArrayList<ModelInfo>();
        try {
            JSONArray arr = new JSONObject(r.body).optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    ModelInfo m = ModelInfo.fromTags(o);
                    m.loaded = true;
                    m.sizeVram = o.optLong("size_vram", 0);
                    m.contextLength = o.optInt("context_length", 0);
                    m.expiresAt = str(o, "expires_at");
                    out.add(m);
                }
            }
        } catch (JSONException e) {
            throw new IOException("Bad /api/ps reply");
        }
        return out;
    }

    /** What /api/show says about one model. */
    public static final class ModelDetails {
        public final List<String> capabilities;
        public final int contextLength;
        public final String family;
        public final String parameterSize;
        public final String quantization;
        public final String format;
        public final String license;
        public final String parameters;
        public final String modifiedAt;

        ModelDetails(List<String> capabilities, int contextLength, String family, String parameterSize,
                     String quantization, String format, String license, String parameters, String modifiedAt) {
            this.capabilities = Collections.unmodifiableList(capabilities);
            this.contextLength = contextLength;
            this.family = family;
            this.parameterSize = parameterSize;
            this.quantization = quantization;
            this.format = format;
            this.license = license;
            this.parameters = parameters;
            this.modifiedAt = modifiedAt;
        }

        public boolean supports(String capability) {
            return capabilities.contains(capability);
        }
    }

    public ModelDetails show(String model) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        Http.Response r = Http.postJson(base + "/api/show", body.toString(), CONNECT_TIMEOUT_MS,
                QUICK_READ_TIMEOUT_MS, headers);
        if (!r.ok()) throw new IOException(errorMessage(r.body, r.code));
        try {
            return parseShow(new JSONObject(r.body));
        } catch (JSONException e) {
            throw new IOException("Bad /api/show reply");
        }
    }

    static ModelDetails parseShow(JSONObject o) {
        List<String> caps = new ArrayList<String>();
        JSONArray arr = o.optJSONArray("capabilities");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) caps.add(arr.optString(i, ""));
        }
        int ctx = 0;
        JSONObject info = o.optJSONObject("model_info");
        if (info != null) {
            JSONArray keys = info.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.optString(i, "");
                    if (k.endsWith(".context_length")) {
                        ctx = info.optInt(k, 0);
                        break;
                    }
                }
            }
        }
        JSONObject d = o.optJSONObject("details");
        String license = str(o, "license").trim();
        int nl = license.indexOf('\n');
        if (nl > 0) license = license.substring(0, nl).trim();
        if (license.length() > 80) license = license.substring(0, 80) + "…";
        return new ModelDetails(caps, ctx,
                d == null ? "" : str(d, "family"),
                d == null ? "" : str(d, "parameter_size"),
                d == null ? "" : str(d, "quantization_level"),
                d == null ? "" : str(d, "format"),
                license, str(o, "parameters").trim(), str(o, "modified_at"));
    }

    /** Deletes a model from the PC (DELETE /api/delete). */
    public void deleteModel(String model) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        Http.Response r = Http.sendJson("DELETE", base + "/api/delete", body.toString(), CONNECT_TIMEOUT_MS,
                QUICK_READ_TIMEOUT_MS, headers);
        if (!r.ok()) throw new IOException(errorMessage(r.body, r.code));
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    public interface ChatListener {
        void onThinking(String delta);

        void onContent(String delta);

        /**
         * The model asked to call tools ("message": {"tool_calls": […]}). May
         * come in any chunk, including the final one, and more than once;
         * always before onDone.
         */
        void onToolCalls(List<ToolCall> calls);

        void onDone(ChatStats stats);

        /** Called once when the reply fails or is cancelled (never after onDone). */
        void onError(String message, boolean cancelled);
    }

    /**
     * Builds a /api/chat body. {@code think} may be null (not sent), a
     * Boolean or a level string; {@code keepAlive} is a number of seconds
     * (-1 = forever, 0 = unload right away) or a duration string.
     */
    public static JSONObject chatBody(String model, JSONArray messages, Object think, Object keepAlive,
                                      JSONObject options) {
        return chatBody(model, messages, think, keepAlive, options, null);
    }

    /** As above, offering the model {@code tools} (a "tools" array; null or empty = none). */
    public static JSONObject chatBody(String model, JSONArray messages, Object think, Object keepAlive,
                                      JSONObject options, JSONArray tools) {
        JSONObject b = new JSONObject();
        try {
            b.put("model", model);
            b.put("messages", messages);
            b.put("stream", true);
            if (keepAlive != null) b.put("keep_alive", keepAlive);
            if (think != null) b.put("think", think);
            if (options != null && options.length() > 0) b.put("options", options);
            if (tools != null && tools.length() > 0) b.put("tools", tools);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return b;
    }

    /**
     * Streams a reply. Blocks until the reply finishes, fails or is
     * cancelled; exactly one of onDone/onError is called at the end.
     */
    public void chat(JSONObject body, Cancellable cancel, final ChatListener l) {
        HttpURLConnection conn = null;
        final ThinkSplitter splitter = new ThinkSplitter();
        ThinkSplitter.Sink sink = new ThinkSplitter.Sink() {
            @Override
            public void content(String s) {
                l.onContent(s);
            }

            @Override
            public void thinking(String s) {
                l.onThinking(s);
            }
        };
        try {
            body.put("stream", true);
            conn = Http.open(base + "/api/chat", "POST", CONNECT_TIMEOUT_MS, STREAM_READ_TIMEOUT_MS, headers);
            cancel.attach(conn);
            if (cancel.isCancelled()) {
                l.onError("Stopped.", true);
                return;
            }
            Http.writeJson(conn, body.toString());
            int code = conn.getResponseCode();
            if (code >= 400) {
                l.onError(errorMessage(Http.readBody(conn, code), code), false);
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), Http.UTF8), 4096);
            String line;
            while ((line = br.readLine()) != null) {
                if (cancel.isCancelled()) break;
                line = line.trim();
                if (line.length() == 0) continue;
                JSONObject o;
                try {
                    o = new JSONObject(line);
                } catch (JSONException e) {
                    continue;
                }
                String err = str(o, "error");
                if (err.length() > 0) {
                    splitter.flush(sink);
                    l.onError(err, false);
                    return;
                }
                JSONObject msg = o.optJSONObject("message");
                if (msg != null) {
                    String th = str(msg, "thinking");
                    if (th.length() > 0) l.onThinking(th);
                    String ct = str(msg, "content");
                    if (ct.length() > 0) splitter.feed(ct, sink);
                    // Streamed (done: false) or with the final line, as older servers send them.
                    List<ToolCall> calls = ToolCall.parseAll(msg.optJSONArray("tool_calls"));
                    if (!calls.isEmpty()) l.onToolCalls(calls);
                }
                if (o.optBoolean("done", false)) {
                    splitter.flush(sink);
                    l.onDone(ChatStats.fromFinal(o));
                    return;
                }
            }
            splitter.flush(sink);
            if (cancel.isCancelled()) l.onError("Stopped.", true);
            else l.onError("The connection closed before the reply finished.", false);
        } catch (IOException e) {
            splitter.flush(sink);
            if (cancel.isCancelled()) l.onError("Stopped.", true);
            else l.onError(describe(e, host, port), false);
        } catch (JSONException e) {
            l.onError("Couldn't build the request: " + e.getMessage(), false);
        } catch (RuntimeException e) {
            if (cancel.isCancelled()) l.onError("Stopped.", true);
            else l.onError("Unexpected error: " + e, false);
        } finally {
            cancel.detach();
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Loads a model into memory (or unloads it with keepAlive 0) without
     * generating anything. Returns the elapsed milliseconds.
     */
    public long loadModel(String model, Object keepAlive, JSONObject options) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("model", model);
            b.put("messages", new JSONArray());
            b.put("stream", false);
            b.put("keep_alive", keepAlive);
            if (options != null && options.length() > 0) b.put("options", options);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        long t0 = System.nanoTime();
        Http.Response r = Http.postJson(base + "/api/chat", b.toString(), CONNECT_TIMEOUT_MS, LOAD_READ_TIMEOUT_MS,
                headers);
        if (!r.ok()) throw new IOException(errorMessage(r.body, r.code));
        return (System.nanoTime() - t0) / 1000000L;
    }

    /**
     * Loads or unloads an embedding-only model (they refuse /api/chat): an
     * /api/embed call with no input and a keep_alive. Returns elapsed ms.
     */
    public long loadEmbedModel(String model, Object keepAlive) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("model", model);
            b.put("input", new JSONArray());
            b.put("keep_alive", keepAlive);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        long t0 = System.nanoTime();
        Http.Response r = Http.postJson(base + "/api/embed", b.toString(), CONNECT_TIMEOUT_MS, LOAD_READ_TIMEOUT_MS,
                headers);
        if (!r.ok()) throw new IOException(errorMessage(r.body, r.code));
        return (System.nanoTime() - t0) / 1000000L;
    }

    // ------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------

    public interface PullListener {
        void onProgress(String status, long completed, long total);

        void onDone();

        void onError(String message, boolean cancelled);
    }

    /** Downloads a model on the PC (POST /api/pull), reporting progress. */
    public void pull(String model, Cancellable cancel, PullListener l) {
        HttpURLConnection conn = null;
        try {
            JSONObject b = new JSONObject();
            b.put("model", model);
            b.put("stream", true);
            conn = Http.open(base + "/api/pull", "POST", CONNECT_TIMEOUT_MS, STREAM_READ_TIMEOUT_MS, headers);
            cancel.attach(conn);
            Http.writeJson(conn, b.toString());
            int code = conn.getResponseCode();
            if (code >= 400) {
                l.onError(errorMessage(Http.readBody(conn, code), code), false);
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), Http.UTF8));
            String line;
            while ((line = br.readLine()) != null) {
                if (cancel.isCancelled()) break;
                line = line.trim();
                if (line.length() == 0) continue;
                JSONObject o;
                try {
                    o = new JSONObject(line);
                } catch (JSONException e) {
                    continue;
                }
                String err = str(o, "error");
                if (err.length() > 0) {
                    l.onError(err, false);
                    return;
                }
                String status = str(o, "status");
                if ("success".equals(status)) {
                    l.onDone();
                    return;
                }
                l.onProgress(status, o.optLong("completed", 0), o.optLong("total", 0));
            }
            if (cancel.isCancelled()) l.onError("Download stopped.", true);
            else l.onError("The connection closed before the download finished.", false);
        } catch (IOException e) {
            if (cancel.isCancelled()) l.onError("Download stopped.", true);
            else l.onError(describe(e, host, port), false);
        } catch (JSONException e) {
            l.onError(e.getMessage(), false);
        } finally {
            cancel.detach();
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** String value, "" when missing or JSON null (Android's optString gives "null"). */
    public static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        Object v = o.opt(key);
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    static String errorMessage(String body, int code) {
        String msg = "";
        try {
            msg = str(new JSONObject(body), "error");
        } catch (JSONException ignored) {
        }
        if (msg.length() == 0) {
            msg = body == null ? "" : body.trim();
            if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        }
        return msg.length() > 0 ? msg + " (HTTP " + code + ")" : "HTTP " + code;
    }

    public static String describe(IOException e, String host, int port) {
        String where = Http.hostPort(host, port);
        if (e instanceof ConnectException) return "Can't connect to the AI at " + where + " (connection refused).";
        if (e instanceof NoRouteToHostException) return "No route to " + where + " — is the phone on the same network?";
        if (e instanceof SocketTimeoutException) return "The AI at " + where + " stopped responding (timed out).";
        if (e instanceof UnknownHostException) return "Unknown host " + host + ".";
        String m = e.getMessage();
        return "Connection to " + where + " failed" + (m != null && m.length() > 0 ? ": " + m : ".");
    }
}
