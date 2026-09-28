package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wake-on-LAN: the "magic packet" that wakes a sleeping PC (6 × 0xFF, then
 * its MAC address 16 times, sent as a UDP broadcast), MAC address parsing,
 * and spotting the PC's MAC in LaunchBridge's get_system_info result. Plain
 * Java; the Android layer sends the packet.
 */
public final class WakeOnLan {
    /** UDP port magic packets go to ("discard"; port 7 is the other common one). */
    public static final int PORT = 9;
    public static final int PACKET_BYTES = 102;

    private static final Pattern COLONS = Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}");
    private static final Pattern DOTS = Pattern.compile("([0-9A-Fa-f]{4}\\.){2}[0-9A-Fa-f]{4}");
    private static final Pattern PLAIN = Pattern.compile("[0-9A-Fa-f]{12}");
    /** MAC-looking text inside longer strings (separated forms only; bare hex is too ambiguous). */
    private static final Pattern IN_TEXT = Pattern.compile(
            "(?<![0-9A-Fa-f:-])((?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2})(?![0-9A-Fa-f:-])");

    private WakeOnLan() {}

    /**
     * Parses "AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff", "aabb.ccdd.eeff" or
     * "AABBCCDDEEFF". Null when malformed, or not a single network card's
     * address (all zeros, broadcast or multicast).
     */
    public static byte[] parseMac(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!COLONS.matcher(t).matches() && !DOTS.matcher(t).matches() && !PLAIN.matcher(t).matches()) return null;
        String hex = t.replaceAll("[:.\\-]", "");
        byte[] mac = new byte[6];
        for (int i = 0; i < 6; i++) mac[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        boolean zero = true;
        for (byte b : mac) zero &= b == 0;
        if (zero || (mac[0] & 0x01) != 0) return null; // unset, or a group (multicast / broadcast) address
        return mac;
    }

    /** "AA:BB:CC:DD:EE:FF". */
    public static String format(byte[] mac) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", mac[i] & 0xFF));
        }
        return sb.toString();
    }

    /** The address in "AA:BB:CC:DD:EE:FF" form, or null when it isn't a valid MAC. */
    public static String normalize(String s) {
        byte[] mac = parseMac(s);
        return mac == null ? null : format(mac);
    }

    /** The magic packet for {@code mac}: 6 × 0xFF followed by the address 16 times. */
    public static byte[] packet(byte[] mac) {
        if (mac == null || mac.length != 6) throw new IllegalArgumentException("A MAC address has 6 bytes");
        byte[] p = new byte[PACKET_BYTES];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int r = 0; r < 16; r++) System.arraycopy(mac, 0, p, 6 + r * 6, 6);
        return p;
    }

    /** The subnet's broadcast address, e.g. 192.168.1.255 for 192.168.1.37/24. */
    public static String broadcast(int address, int prefix) {
        int mask = prefix <= 0 ? 0 : (int) (0xFFFFFFFFL << (32 - Math.min(32, prefix)));
        return LanScanner.ipToString((address & mask) | ~mask);
    }

    /**
     * The PC's MAC address in a get_system_info result (a JSON object, array
     * or text): values under keys that name a MAC first ("mac",
     * "mac_address", "macAddress"…), else the first MAC-looking text. Null
     * when there is none.
     */
    public static String findMac(Object result) {
        String keyed = underMacKey(result, 0);
        if (keyed != null) return keyed;
        return inText(result == null ? "" : String.valueOf(result));
    }

    private static String underMacKey(Object v, int depth) {
        if (depth > 6 || v == null) return null;
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Iterator<?> keys = o.keys();
            while (keys.hasNext()) {
                String k = String.valueOf(keys.next());
                if (!k.toLowerCase(Locale.US).contains("mac")) continue;
                String found = firstMac(o.opt(k));
                if (found != null) return found;
            }
            keys = o.keys();
            while (keys.hasNext()) {
                String found = underMacKey(o.opt(String.valueOf(keys.next())), depth + 1);
                if (found != null) return found;
            }
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                String found = underMacKey(a.opt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** A MAC stored as the value itself, or the first one in an array of them. */
    private static String firstMac(Object v) {
        if (v instanceof String) {
            String n = normalize((String) v);
            return n != null ? n : inText((String) v);
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                String n = firstMac(a.opt(i));
                if (n != null) return n;
            }
        }
        return null;
    }

    private static String inText(String s) {
        Matcher m = IN_TEXT.matcher(s);
        while (m.find()) {
            String n = normalize(m.group(1));
            if (n != null) return n;
        }
        return null;
    }
}
