package com.omnideck.mobile.core;

import java.util.Locale;

/** Parses user-typed addresses: "192.168.1.20", "pc.lan:11434", "http://host:port/", "[fe80::1]:11434". */
public final class HostPort {
    public final String host;
    public final int port;

    public HostPort(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Returns null for blank or malformed input. */
    public static HostPort parse(String input, int defaultPort) {
        if (input == null) return null;
        String s = input.trim();
        if (s.length() == 0) return null;
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("http://")) s = s.substring(7);
        else if (lower.startsWith("https://")) s = s.substring(8);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.length() == 0) return null;
        String host;
        int port = defaultPort;
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
        return new HostPort(host, port);
    }

    private static Integer parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String label(int defaultPort) {
        return port == defaultPort ? host : Http.hostPort(host, port);
    }
}
