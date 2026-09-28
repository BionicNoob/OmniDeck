package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PC vitals parsed from LaunchBridge's get_system_info result, which may be
 * a JSON object (any key naming) or a human-readable string. Every field is
 * optional; -1 / "" means unknown. Parsing is heuristic and never throws.
 */
public final class Vitals {
    public double cpuPercent = -1;
    public double ramPercent = -1;
    public double ramUsedGb = -1;
    public double ramTotalGb = -1;
    public double diskPercent = -1;
    public double diskFreeGb = -1;
    public double diskTotalGb = -1;
    public double batteryPercent = -1;
    public String power = "";
    public String host = "";
    public String os = "";
    public String uptime = "";
    /** The raw result, formatted as "key: value" lines, for anything not parsed. */
    public String raw = "";

    public boolean hasAny() {
        return cpuPercent >= 0 || ramPercent >= 0 || diskPercent >= 0 || diskFreeGb >= 0 || batteryPercent >= 0;
    }

    private static final Pattern NUM = Pattern.compile("(-?\\d+(?:[.,]\\d+)?)");
    private static final Pattern PCT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern PAIR = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(TB|GB|GiB|MB|MiB)?\\s*(?:/|of|out of)\\s*(\\d+(?:\\.\\d+)?)\\s*(TB|GB|GiB|MB|MiB)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(TB|GB|GiB|MB|MiB)", Pattern.CASE_INSENSITIVE);

    public static Vitals parse(Object result) {
        Vitals v = new Vitals();
        try {
            if (result instanceof JSONObject) {
                v.raw = flatten((JSONObject) result, "");
                v.fromText(v.raw);
            } else if (result != null) {
                v.raw = String.valueOf(result);
                v.fromText(v.raw);
            }
        } catch (RuntimeException ignored) {
            // Heuristics only: keep whatever was parsed.
        }
        return v;
    }

    /** "key: value" per line, nested objects prefixed ("memory used: 8 GB"). */
    static String flatten(JSONObject o, String prefix) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> keys = o.keys();
        while (keys.hasNext()) {
            String k = String.valueOf(keys.next());
            Object val = o.opt(k);
            String name = (prefix + " " + k.replace('_', ' ')).trim();
            if (val instanceof JSONObject) {
                String inner = flatten((JSONObject) val, name);
                if (inner.length() > 0) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(inner);
                }
            } else {
                if (sb.length() > 0) sb.append('\n');
                String s;
                if (val instanceof JSONArray) {
                    StringBuilder j = new StringBuilder();
                    JSONArray a = (JSONArray) val;
                    for (int i = 0; i < a.length(); i++) {
                        if (i > 0) j.append(", ");
                        j.append(a.opt(i));
                    }
                    s = j.toString();
                } else {
                    s = String.valueOf(val);
                }
                sb.append(name).append(": ").append(s);
            }
        }
        return sb.toString();
    }

    private void fromText(String text) {
        for (String line : text.split("[\\n;|]+")) {
            String l = line.trim();
            if (l.length() == 0) continue;
            int colon = l.indexOf(':');
            String key = (colon > 0 ? l.substring(0, colon) : l).toLowerCase(Locale.US);
            String val = colon > 0 ? l.substring(colon + 1).trim() : l;
            String all = l.toLowerCase(Locale.US);
            if (has(key, "cpu", "processor", "load") && cpuPercent < 0) {
                cpuPercent = percentOrNumber(val);
            } else if (has(key, "ram", "memory", "mem")) {
                applyUsage(val, key, 0);
            } else if (has(key, "disk", "storage", "drive", "c:")) {
                applyUsage(val, key, 1);
            } else if (has(key, "battery", "power", "charg", "plugged")) {
                Matcher m = PCT.matcher(val);
                if (m.find()) batteryPercent = clampPct(d(m.group(1)));
                else if (has(key, "percent", "level") || (key.equals("battery") && val.matches("\\d+(\\.\\d+)?"))) {
                    batteryPercent = percentOrNumber(val);
                }
                String lv = val.toLowerCase(Locale.US);
                boolean plugKey = has(key, "plug", "ac", "charging");
                if (lv.contains("discharg") || lv.contains("on battery") || (plugKey && lv.equals("false"))) {
                    power = "On battery";
                } else if (lv.contains("charging") && !lv.contains("not")) {
                    power = "Charging";
                } else if (lv.contains("ac power") || lv.equals("ac") || lv.contains("plugged in")
                        || (plugKey && lv.equals("true")) || lv.contains("no battery")) {
                    power = "AC power";
                }
            } else if (has(key, "host", "computer", "machine", "name") && host.length() == 0 && !all.contains("%")) {
                host = val;
            } else if (has(key, "os", "system", "platform", "windows") && os.length() == 0 && !val.matches("[\\d.\\s%]+")) {
                os = val;
            } else if (has(key, "uptime", "boot")) {
                uptime = val;
            }
        }
    }

    /** kind 0 = RAM, 1 = disk. Understands "8.1 / 16 GB", "52%", "210 GB free", "used 8 GB". */
    private void applyUsage(String val, String key, int kind) {
        Matcher pair = PAIR.matcher(val);
        Matcher pct = PCT.matcher(val);
        boolean free = key.contains("free") || key.contains("avail") || val.toLowerCase(Locale.US).contains("free");
        if (pair.find()) {
            double a = gb(d(pair.group(1)), pair.group(2) != null ? pair.group(2) : pair.group(4));
            double b = gb(d(pair.group(3)), pair.group(4));
            if (b > 0) {
                double used = free ? b - a : a;
                if (kind == 0) {
                    ramUsedGb = used;
                    ramTotalGb = b;
                    ramPercent = 100.0 * used / b;
                } else {
                    diskTotalGb = b;
                    diskFreeGb = b - used;
                    diskPercent = 100.0 * used / b;
                }
            }
            return;
        }
        if (pct.find()) {
            double p = d(pct.group(1));
            if (kind == 0) ramPercent = free ? 100 - p : p;
            else diskPercent = free ? 100 - p : p;
            return;
        }
        Matcher size = SIZE.matcher(val);
        if (size.find()) {
            double g = gb(d(size.group(1)), size.group(2));
            if (kind == 0) {
                if (key.contains("total")) ramTotalGb = g;
                else if (!free) ramUsedGb = g;
                if (ramTotalGb > 0 && ramUsedGb >= 0) ramPercent = 100.0 * ramUsedGb / ramTotalGb;
            } else {
                if (key.contains("total")) diskTotalGb = g;
                else if (free) diskFreeGb = g;
                if (diskTotalGb > 0 && diskFreeGb >= 0) diskPercent = 100.0 * (diskTotalGb - diskFreeGb) / diskTotalGb;
            }
        } else if (key.contains("percent")) {
            double p = percentOrNumber(val);
            if (p >= 0) {
                if (kind == 0) ramPercent = p;
                else diskPercent = p;
            }
        }
    }

    private static boolean has(String key, String... words) {
        for (String w : words) {
            if (key.contains(w)) return true;
        }
        return false;
    }

    private static double percentOrNumber(String val) {
        Matcher m = PCT.matcher(val);
        if (m.find()) return clampPct(d(m.group(1)));
        Matcher n = NUM.matcher(val);
        if (n.find()) {
            double x = d(n.group(1));
            return x >= 0 && x <= 1 && val.contains(".") ? clampPct(x * 100) : clampPct(x);
        }
        return -1;
    }

    private static double clampPct(double p) {
        return p < 0 ? -1 : Math.min(100, p);
    }

    private static double d(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static double gb(double v, String unit) {
        if (unit == null) return v;
        String u = unit.toUpperCase(Locale.US);
        if (u.startsWith("T")) return v * 1024;
        if (u.startsWith("M")) return v / 1024;
        return v;
    }
}
