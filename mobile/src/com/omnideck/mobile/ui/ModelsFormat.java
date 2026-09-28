package com.omnideck.mobile.ui;

import com.omnideck.mobile.core.Fmt;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text helpers for the model bay: Ollama timestamps, relative ages,
 * token counts, capability names and download stats. Pure Java (no views).
 */
public final class ModelsFormat {
    private ModelsFormat() {}

    private static final Pattern ISO = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?\\s*(Z|[+-]\\d{2}:?\\d{2})?$");

    /**
     * Parses Ollama's RFC 3339 timestamps ("2024-05-01T12:34:56.123456789-07:00",
     * "2026-09-01T10:00:00Z"). Returns epoch millis, or -1 when unreadable.
     */
    public static long parseTime(String iso) {
        if (iso == null) return -1;
        Matcher m = ISO.matcher(iso.trim());
        if (!m.matches()) return -1;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        long ms = c.getTimeInMillis();
        String frac = m.group(7);
        if (frac != null) {
            String three = (frac + "00").substring(0, 3);
            ms += Integer.parseInt(three);
        }
        String zone = m.group(8);
        if (zone != null && !"Z".equals(zone)) {
            int sign = zone.charAt(0) == '-' ? -1 : 1;
            String digits = zone.substring(1).replace(":", "");
            int hh = Integer.parseInt(digits.substring(0, 2));
            int mm = Integer.parseInt(digits.substring(2, 4));
            ms -= sign * (hh * 60L + mm) * 60000L;
        }
        return ms;
    }

    /** "just now", "12m ago", "5h ago", "3d ago", "2w ago", "4mo ago", "1y ago"; "" when unknown. */
    public static String ago(long thenMs, long nowMs) {
        if (thenMs <= 0) return "";
        long s = Math.max(0, (nowMs - thenMs) / 1000);
        if (s < 60) return "just now";
        long min = s / 60;
        if (min < 60) return min + "m ago";
        long h = min / 60;
        if (h < 24) return h + "h ago";
        long d = h / 24;
        if (d < 14) return d + "d ago";
        if (d < 56) return (d / 7) + "w ago";
        if (d < 365) return Math.max(2, d / 30) + "mo ago";
        return (d / 365) + "y ago";
    }

    /** Relative age of an Ollama timestamp ("" when unreadable). */
    public static String ago(String iso, long nowMs) {
        return ago(parseTime(iso), nowMs);
    }

    /** 131072 → "131,072". */
    public static String grouped(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** 131072 → "128K", 8192 → "8K", 4000 → "4K", 512 → "512". */
    public static String compactTokens(long n) {
        if (n <= 0) return "";
        if (n >= 1024 && n % 1024 == 0) return (n / 1024) + "K";
        if (n >= 1000) return Math.round(n / 1000.0) + "K";
        return String.valueOf(n);
    }

    /**
     * The chip label for an Ollama capability, or null for ones not worth a
     * chip ("completion" is every chat model's baseline).
     */
    public static String capability(String cap) {
        if (cap == null) return null;
        String c = cap.trim().toLowerCase(Locale.US);
        if (c.length() == 0 || "completion".equals(c)) return null;
        if ("vision".equals(c)) return "Vision";
        if ("thinking".equals(c)) return "Thinking";
        if ("tools".equals(c)) return "Tools";
        if ("embedding".equals(c)) return "Embedding";
        if ("insert".equals(c)) return "Code fill";
        return Character.toUpperCase(c.charAt(0)) + c.substring(1);
    }

    /** "llama3.2:3b" → {"llama3.2", ":3b"}; names without a tag get an empty tag. */
    public static String[] splitTag(String name) {
        if (name == null) return new String[]{"", ""};
        int slash = name.lastIndexOf('/');
        int colon = name.indexOf(':', Math.max(0, slash));
        if (colon <= 0) return new String[]{name, ""};
        return new String[]{name.substring(0, colon), name.substring(colon)};
    }

    /**
     * Is this a plausible Ollama model reference ("qwen3", "qwen3:8b",
     * "hf.co/user/repo:Q4_K_M")? Rejects spaces and shell-ish characters.
     */
    public static boolean validName(String s) {
        return s != null && s.length() > 0 && s.length() <= 200 && s.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]*");
    }

    /** "450 KB / 1.0 MB" (or just the done part while the total is unknown). */
    public static String transferred(long done, long total) {
        if (total <= 0) return done > 0 ? Fmt.bytes(done) : "";
        return Fmt.bytes(Math.min(done, total)) + " / " + Fmt.bytes(total);
    }

    /** "2.1 MB/s", or "" when there's no rate yet. */
    public static String rate(double bytesPerSec) {
        if (bytesPerSec < 1) return "";
        return Fmt.bytes((long) bytesPerSec) + "/s";
    }

    /** "ETA 1m 5s", or "" when unknown. */
    public static String eta(long seconds) {
        if (seconds < 0) return "";
        return seconds < 1 ? "ETA <1s" : "ETA " + Fmt.duration(seconds);
    }

    /** Ollama's pull status, made readable: "pulling 6a0746a1ec1a" → "Pulling layer 6a0746a1ec1a". */
    public static String pullStatus(String status) {
        if (status == null || status.length() == 0) return "Starting…";
        String s = status.trim();
        if (s.startsWith("pulling ") && !s.startsWith("pulling manifest")) {
            return "Pulling layer " + s.substring(8).trim();
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
