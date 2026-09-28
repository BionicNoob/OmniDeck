package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Client for OMNI-DECK's LaunchBridge — the PC helper the web app uses for
 * /open, /vol, /sys, /shot and the clipboard. Same API the web app calls:
 * GET /health, POST /pair, GET /apps, POST /launch, POST /desk/run with the
 * X-Bridge-Token header.
 *
 * Out of the box LaunchBridge only listens on 127.0.0.1, so from the phone
 * these calls work once the PC side exposes it on the network.
 */
public final class BridgeClient {
    public static final int DEFAULT_PORT = 8765;

    static final int CONNECT_TIMEOUT_MS = 3500;
    static final int READ_TIMEOUT_MS = 20000;
    static final int DESK_TIMEOUT_MS = 90000;

    /** An error with a message fit to show the user as-is. */
    public static final class BridgeException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int code;

        public BridgeException(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    private final String host;
    private final int port;
    private final String token;
    private final String base;
    private final String unpairedHint;

    public BridgeClient(String host, int port, String token) {
        this(host, port, token, null);
    }

    /**
     * {@code unpairedHint}, when set, is the error for calls that need a token
     * while none is sent — e.g. the phone is paired with a different PC.
     */
    public BridgeClient(String host, int port, String token, String unpairedHint) {
        this.host = host;
        this.port = port;
        this.token = token == null ? "" : token.trim();
        this.base = Http.baseUrl(host, port);
        this.unpairedHint = unpairedHint;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String where() {
        return Http.hostPort(host, port);
    }

    public boolean paired() {
        return token.length() > 0;
    }

    /** GET /health (no auth). */
    public JSONObject health() throws BridgeException {
        return call("GET", "/health", null, false, 3500);
    }

    /** POST /pair (no auth) and returns the new token. */
    public String pair() throws BridgeException {
        JSONObject o = call("POST", "/pair", new JSONObject(), false, 6000);
        String t = OllamaClient.str(o, "token");
        if (t.length() == 0) {
            String m = OllamaClient.str(o, "message");
            throw new BridgeException(m.length() > 0 ? m : "The bridge didn't return a token.", 200);
        }
        return t;
    }

    /** GET /apps?q=&limit= → matches. */
    public JSONArray apps(String query, int limit) throws BridgeException {
        String q;
        try {
            q = java.net.URLEncoder.encode(query == null ? "" : query, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            q = "";
        }
        JSONObject o = call("GET", "/apps?q=" + q + "&limit=" + limit, null, true, READ_TIMEOUT_MS);
        JSONArray a = o.optJSONArray("matches");
        return a == null ? new JSONArray() : a;
    }

    /** POST /launch {query, dry_run} — resolves (dry run) or opens an app by name. */
    public JSONObject launchQuery(String query, boolean dryRun) throws BridgeException {
        JSONObject b = new JSONObject();
        try {
            b.put("query", query);
            if (dryRun) b.put("dry_run", true);
        } catch (JSONException e) {
            throw new BridgeException(e.getMessage(), 0);
        }
        return call("POST", "/launch", b, true, READ_TIMEOUT_MS);
    }

    /** POST /launch {app_id} — opens exactly this app. */
    public JSONObject launchId(String appId) throws BridgeException {
        JSONObject b = new JSONObject();
        try {
            b.put("app_id", appId);
        } catch (JSONException e) {
            throw new BridgeException(e.getMessage(), 0);
        }
        return call("POST", "/launch", b, true, READ_TIMEOUT_MS);
    }

    /** GET /desk/capabilities. */
    public JSONObject deskCapabilities() throws BridgeException {
        return call("GET", "/desk/capabilities", null, true, 5000);
    }

    /**
     * POST /desk/run {tool, args} and returns "result" (any JSON value).
     * Tools the web app uses: get_system_info, get_volume, set_volume
     * {level}, screenshot {save}, get_clipboard.
     */
    public Object deskRun(String tool, JSONObject args) throws BridgeException {
        JSONObject b = new JSONObject();
        try {
            b.put("tool", tool);
            b.put("args", args == null ? new JSONObject() : args);
        } catch (JSONException e) {
            throw new BridgeException(e.getMessage(), 0);
        }
        JSONObject r = call("POST", "/desk/run", b, true, DESK_TIMEOUT_MS);
        if (r.has("ok") && !r.optBoolean("ok", true)) {
            String err = OllamaClient.str(r, "error");
            throw new BridgeException(err.length() > 0 ? err : tool + " failed.", 200);
        }
        return r.opt("result");
    }

    private JSONObject call(String method, String path, JSONObject body, boolean auth, int readTimeoutMs)
            throws BridgeException {
        if (auth && !paired()) {
            throw new BridgeException(unpairedHint != null ? unpairedHint
                    : "Not paired with the PC bridge yet — run /pair first.", 401);
        }
        Map<String, String> headers = new HashMap<String, String>();
        if (auth) headers.put("X-Bridge-Token", token);
        Http.Response r;
        try {
            if ("GET".equals(method)) {
                r = Http.get(base + path, CONNECT_TIMEOUT_MS, readTimeoutMs, headers);
            } else {
                r = Http.postJson(base + path, body == null ? "{}" : body.toString(), CONNECT_TIMEOUT_MS,
                        readTimeoutMs, headers);
            }
        } catch (Http.TooLargeException e) {
            throw new BridgeException("The PC bridge's reply was too large for the phone (over "
                    + Math.max(1, e.limit >> 20) + " MB).", 0);
        } catch (IOException e) {
            throw new BridgeException("Can't reach the PC bridge (LaunchBridge) at " + where()
                    + ". On the PC it has to listen on the network, not just 127.0.0.1.", 0);
        }
        JSONObject data = null;
        try {
            data = new JSONObject(r.body);
        } catch (JSONException ignored) {
        }
        if (r.code == 401 || r.code == 403) {
            throw new BridgeException("The PC bridge rejected the token — run /pair again.", r.code);
        }
        if (r.code == 404 || r.code == 405) {
            throw new BridgeException("The PC bridge doesn't support " + path
                    + " — update LaunchBridge on the PC.", r.code);
        }
        if (!r.ok()) {
            String m = data == null ? "" : firstNonEmpty(OllamaClient.str(data, "detail"),
                    OllamaClient.str(data, "message"), OllamaClient.str(data, "error"));
            throw new BridgeException(m.length() > 0 ? m : "Bridge returned HTTP " + r.code + ".", r.code);
        }
        if (data == null) {
            if (r.code == 204) return new JSONObject();
            // LaunchBridge always answers JSON: this is some other server on the port.
            throw new BridgeException("The PC bridge at " + where() + " sent a reply this app can't read — is "
                    + "something other than LaunchBridge using that port?", r.code);
        }
        return data;
    }

    private static String firstNonEmpty(String... s) {
        for (String x : s) {
            if (x != null && x.length() > 0) return x;
        }
        return "";
    }
}
