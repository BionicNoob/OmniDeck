package com.omnideck.mobile.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Small blocking HTTP helpers on top of HttpURLConnection. Everything the app
 * talks to lives on the local network, so requests never go through a proxy.
 */
public final class Http {
    public static final Charset UTF8 = Charset.forName("UTF-8");
    /** Largest reply body read into memory (a PC screenshot is the biggest thing fetched). */
    public static final int MAX_BODY = 8 * 1024 * 1024;

    private Http() {}

    /** A reply body over the reader's limit. Thrown instead of returning a cut-off body. */
    public static final class TooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int limit;

        public TooLargeException(int limit) {
            super("The reply is larger than " + (limit >= 1024 * 1024 ? (limit >> 20) + " MB" : limit + " bytes") + ".");
            this.limit = limit;
        }
    }

    /** Opens connections; the Android layer swaps in one that routes LAN hosts over Wi-Fi. */
    public interface Opener {
        HttpURLConnection open(URL url) throws IOException;
    }

    public static final Opener DIRECT = new Opener() {
        @Override
        public HttpURLConnection open(URL url) throws IOException {
            return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        }
    };

    public static volatile Opener opener = DIRECT;

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    public static HttpURLConnection open(String url, String method, int connectTimeoutMs, int readTimeoutMs,
                                         Map<String, String> headers) throws IOException {
        HttpURLConnection c = opener.open(new URL(url));
        c.setRequestMethod(method);
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json, application/x-ndjson, text/plain, */*");
        c.setRequestProperty("User-Agent", "OmniDeck-Mobile/1.0");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return c;
    }

    /** Writes a JSON body (fixed length, so the server sees Content-Length). */
    public static void writeJson(HttpURLConnection c, String json) throws IOException {
        byte[] bytes = json.getBytes(UTF8);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = c.getOutputStream();
        try {
            os.write(bytes);
            os.flush();
        } finally {
            os.close();
        }
    }

    public static Response get(String url, int connectTimeoutMs, int readTimeoutMs, Map<String, String> headers)
            throws IOException {
        HttpURLConnection c = open(url, "GET", connectTimeoutMs, readTimeoutMs, headers);
        try {
            int code = c.getResponseCode();
            return new Response(code, readBody(c, code));
        } finally {
            c.disconnect();
        }
    }

    public static Response postJson(String url, String json, int connectTimeoutMs, int readTimeoutMs,
                                    Map<String, String> headers) throws IOException {
        return sendJson("POST", url, json, connectTimeoutMs, readTimeoutMs, headers);
    }

    /** Any method with a JSON body (POST, DELETE, PUT). */
    public static Response sendJson(String method, String url, String json, int connectTimeoutMs, int readTimeoutMs,
                                    Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, method, connectTimeoutMs, readTimeoutMs, headers);
        try {
            writeJson(c, json);
            int code = c.getResponseCode();
            return new Response(code, readBody(c, code));
        } finally {
            c.disconnect();
        }
    }

    /** Reads the body for any status; error statuses come from the error stream. */
    public static String readBody(HttpURLConnection c, int code) throws IOException {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        try {
            return readAll(in, MAX_BODY);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Reads the whole stream as UTF-8; throws {@link TooLargeException} past {@code maxBytes}. */
    public static String readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new TooLargeException(maxBytes);
        }
        return new String(out.toByteArray(), UTF8);
    }

    /** "host" or "host:port" with IPv6 literals bracketed. */
    public static String hostPort(String host, int port) {
        String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return h + ":" + port;
    }

    public static String baseUrl(String host, int port) {
        return "http://" + hostPort(host, port);
    }
}
