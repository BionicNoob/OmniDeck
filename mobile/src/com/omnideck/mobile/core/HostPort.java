package com.omnideck.mobile.core;

import java.util.Locale;

/**
 * Parses user-typed addresses: "192.168.1.20", "pc.lan:11434", "http://host:port/", "[fe80::1]:11434",
 * "https://ai.example.com". An explicit https:// is kept (for Ollama behind a TLS reverse proxy); its
 * port defaults to 443, every other address's to the caller's default.
 */
public final class HostPort {
    public static final int HTTPS_PORT = 443;

    public final String host;
    public final int port;
    /** True when the address was typed with https:// (TLS). */
    public final boolean https;

    public HostPort(String host, int port) {
        this(host, port, false);
    }

    public HostPort(String host, int port, boolean https) {
        this.host = host;
        this.port = port;
        this.https = https;
    }

    /** Returns null for blank or malformed input. */
    public static HostPort parse(String input, int defaultPort) {
        if (input == null) return null;
        String s = input.trim();
        if (s.length() == 0) return null;
        String lower = s.toLowerCase(Locale.US);
        boolean https = false;
        if (lower.startsWith("http://")) {
            s = s.substring(7);
        } else if (lower.startsWith("https://")) {
            s = s.substring(8);
            https = true;
        }
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.length() == 0) return null;
        String host;
        int port = https ? HTTPS_PORT : defaultPort;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) return null;
            host = s.substring(1, close);
            String rest = s.substring(close + 1);
            if (rest.startsWith(":")) {
                Integer p = parsePort(rest.substring(1));
                if (p == null) return null;
                port = p;
            } else if (rest.length() > 0) {
                return null;
            }
        } else {
            int colon = s.lastIndexOf(':');
            if (colon >= 0 && s.indexOf(':') == colon) {
                host = s.substring(0, colon);
                Integer p = parsePort(s.substring(colon + 1));
                if (p == null) return null;
                port = p;
            } else if (colon >= 0) {
                host = s; // bare IPv6
            } else {
                host = s;
            }
        }
        if (host.length() == 0 || host.indexOf(' ') >= 0) return null;
        return new HostPort(host, port, https);
    }

    private static Integer parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * How to show the address: "host" on the default port, else "host:port";
     * https addresses keep their scheme ("https://host", port shown unless 443).
     */
    public String label(int defaultPort) {
        if (https) return "https://" + (port == HTTPS_PORT ? bracketed(host) : Http.hostPort(host, port));
        return port == defaultPort ? host : Http.hostPort(host, port);
    }

    /** "http://host:port" or "https://host:port". */
    public String baseUrl() {
        return Http.baseUrl(https, host, port);
    }

    /** True when this is the same server (scheme, host and port) as {@code host:port}. */
    public boolean matches(String otherHost, int otherPort, boolean otherHttps) {
        return https == otherHttps && port == otherPort && sameHost(host, otherHost);
    }

    /** Same machine address: case-insensitive, IPv6 brackets ignored, "" never matches. */
    public static boolean sameHost(String a, String b) {
        String x = bare(a), y = bare(b);
        return x.length() > 0 && x.equalsIgnoreCase(y);
    }

    private static String bare(String h) {
        String s = h == null ? "" : h.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        return s;
    }

    static String bracketed(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
