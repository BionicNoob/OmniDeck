package com.omnideck.mobile.core;

/** An Ollama server that answered a probe. */
public final class ServerInfo {
    public final String host;
    public final int port;
    /** Reached over TLS (an https:// address typed by the user). */
    public final boolean https;
    public final String version;
    public final long latencyMs;

    public ServerInfo(String host, int port, String version, long latencyMs) {
        this(host, port, false, version, latencyMs);
    }

    public ServerInfo(String host, int port, boolean https, String version, long latencyMs) {
        this.host = host;
        this.port = port;
        this.https = https;
        this.version = version == null ? "" : version;
        this.latencyMs = latencyMs;
    }

    public String baseUrl() {
        return Http.baseUrl(https, host, port);
    }

    /**
     * "192.168.1.20" when on the default port, else "192.168.1.20:8080";
     * https servers show their scheme ("https://ai.example.com").
     */
    public String label() {
        return new HostPort(host, port, https).label(OllamaClient.DEFAULT_PORT);
    }

    @Override
    public String toString() {
        return label() + (version.length() > 0 ? " (Ollama " + version + ")" : "");
    }
}
