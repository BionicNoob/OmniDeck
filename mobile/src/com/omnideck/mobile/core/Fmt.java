package com.omnideck.mobile.core;

import java.util.Locale;

/** Tiny formatting helpers. */
public final class Fmt {
    private Fmt() {}

    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            v /= 1024.0;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    public static String oneDecimal(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    /** 850 -> "0.9s", 12500 -> "12.5s", 95000 -> "1m 35s". */
    public static String seconds(long ms) {
        if (ms < 60000) return String.format(Locale.US, "%.1fs", ms / 1000.0);
        long s = ms / 1000;
        return (s / 60) + "m " + (s % 60) + "s";
    }

    /** 3725 -> "1h 2m 5s", 90 -> "1m 30s", 5 -> "5s". */
    public static String duration(long totalSeconds) {
        long h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60, s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (h > 0 || m > 0) sb.append(m).append("m ");
        sb.append(s).append('s');
        return sb.toString();
    }

    public static String ellipsize(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    /**
     * Parses durations like "90s", "5m", "1h", "1h30m", "2.5m" or a bare
     * number (minutes). Returns seconds, or -1 when it isn't a duration.
     */
    public static long parseDuration(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(Locale.US);
        if (t.length() == 0) return -1;
        if (t.matches("\\d+(\\.\\d+)?")) return Math.round(Double.parseDouble(t) * 60);
        if (!t.matches("(\\d+(\\.\\d+)?\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\\s*)+")) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)")
                .matcher(t);
        double total = 0;
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            char u = m.group(2).charAt(0);
            total += u == 'h' ? v * 3600 : u == 'm' ? v * 60 : v;
        }
        long secs = Math.round(total);
        return secs > 0 ? secs : -1;
    }
}
