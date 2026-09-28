package com.omnideck.mobile.core;

/** An Ollama server that answered a probe. */
public final class ServerInfo {
    public final String host;
    public final int port;
    public final String version;
    public final long latencyMs;

    public ServerInfo(String host, int port, String version, long latencyMs) {
        this.host = host;
        this.port = port;
        this.version = version == null ? "" : version;
        this.latencyMs = latencyMs;
    }

    public String baseUrl() {
        return Http.baseUrl(host, port);
    }

    /** "192.168.1.20" when on the default port, else "192.168.1.20:8080". */
    public String label() {
        return port == OllamaClient.DEFAULT_PORT ? host : Http.hostPort(host, port);
    }

    @Override
    public String toString() {
        return label() + (version.length() > 0 ? " (Ollama " + version + ")" : "");
    }
}
