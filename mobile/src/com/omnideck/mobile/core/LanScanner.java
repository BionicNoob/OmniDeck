package com.omnideck.mobile.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds Ollama servers on the local network: a fast parallel TCP sweep of
 * the phone's own subnet(s), then an HTTP probe of anything that accepted
 * the connection. Blocking; run it off the UI thread.
 */
public final class LanScanner {

    /** One IPv4 network the phone is attached to. */
    public static final class Subnet {
        public final String iface;
        public final int address;
        public final int prefix;

        public Subnet(String iface, int address, int prefix) {
            this.iface = iface == null ? "" : iface;
            this.address = address;
            this.prefix = prefix < 8 || prefix > 30 ? 24 : prefix;
        }

        public String addressString() {
            return ipToString(address);
        }

        @Override
        public String toString() {
            return iface + " " + ipToString(address) + "/" + prefix;
        }
    }

    public interface Listener {
        /** Called from a worker thread for every server found. */
        void onFound(ServerInfo server);
    }

    public static final int MAX_HOSTS = 1024;

    /** Lets the Android layer pin a socket to the right network before it connects. */
    public interface SocketBinder {
        void bind(Socket socket, String host) throws java.io.IOException;
    }

    public static volatile SocketBinder binder = null;

    private LanScanner() {}

    // ------------------------------------------------------------------
    // Subnets
    // ------------------------------------------------------------------

    private static final String[] SKIP_PREFIXES = {
            "lo", "rmnet", "r_rmnet", "rev_rmnet", "ccmni", "pdp", "v4-", "clat", "dummy", "tun", "ppp", "ipsec",
            "wwan", "radio", "sit", "ip6", "gre", "ifb", "veth", "docker", "virbr"
    };
    private static final String[] PREFERRED_PREFIXES = {
            "wlan", "eth", "swlan", "ap", "softap", "wl", "en", "rndis", "usb", "bt-pan", "br"
    };

    /** True for interfaces that are cellular, VPN or loopback. */
    public static boolean skipInterface(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        for (String p : SKIP_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    static int interfaceRank(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        for (int i = 0; i < PREFERRED_PREFIXES.length; i++) {
            if (n.startsWith(PREFERRED_PREFIXES[i])) return i;
        }
        return PREFERRED_PREFIXES.length;
    }

    /** Private IPv4 subnets from java.net.NetworkInterface (Wi-Fi/Ethernet/hotspot first). */
    public static List<Subnet> interfaceSubnets() {
        List<Subnet> out = new ArrayList<Subnet>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            if (e == null) return out;
            for (NetworkInterface ni : Collections.list(e)) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || skipInterface(ni.getName())) continue;
                } catch (Exception ex) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        out.add(new Subnet(ni.getName(), ipToInt(a.getAddress()), ia.getNetworkPrefixLength()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        sortSubnets(out);
        return out;
    }

    public static void sortSubnets(List<Subnet> list) {
        Collections.sort(list, new java.util.Comparator<Subnet>() {
            @Override
            public int compare(Subnet a, Subnet b) {
                return interfaceRank(a.iface) - interfaceRank(b.iface);
            }
        });
    }

    /**
     * Hosts to try, in order: the phone's own /24 first (the PC is almost
     * always there), then the rest of a wider subnet up to MAX_HOSTS. The
     * phone's own address, network and broadcast addresses are skipped.
     */
    public static List<String> candidateHosts(List<Subnet> subnets, int maxHosts) {
        Set<String> out = new LinkedHashSet<String>();
        Set<Integer> self = new java.util.HashSet<Integer>();
        for (Subnet s : subnets) self.add(s.address);
        // Pass 1: every subnet's own /24.
        for (Subnet s : subnets) {
            int prefix = Math.max(s.prefix, 24);
            addRange(out, s.address, prefix, self, maxHosts);
        }
        // Pass 2: the wider subnet, capped (a /16 would be 65k hosts).
        for (Subnet s : subnets) {
            if (s.prefix < 24) {
                int prefix = Math.max(s.prefix, 22);
                addRange(out, s.address, prefix, self, maxHosts);
            }
        }
        return new ArrayList<String>(out);
    }

    private static void addRange(Set<String> out, int addr, int prefix, Set<Integer> self, int maxHosts) {
        int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
        int network = addr & mask;
        int broadcast = network | ~mask;
        // Walk outward from the phone's own address so near neighbours come first.
        long lo = (network & 0xFFFFFFFFL) + 1, hi = (broadcast & 0xFFFFFFFFL) - 1;
        long me = addr & 0xFFFFFFFFL;
        for (long d = 0; d <= hi - lo; d++) {
            if (out.size() >= maxHosts) return;
            long up = me + d, down = me - d;
            if (up >= lo && up <= hi && !self.contains((int) up)) out.add(ipToString((int) up));
            if (d > 0 && down >= lo && down <= hi && !self.contains((int) down)) out.add(ipToString((int) down));
            if (up > hi && down < lo) return;
        }
    }

    // ------------------------------------------------------------------
    // Scan
    // ------------------------------------------------------------------

    /**
     * Sweeps {@code hosts} on {@code port}. With {@code stopAtFirst} it
     * returns as soon as one server is confirmed. Never throws.
     */
    public static List<ServerInfo> scan(List<String> hosts, final int port, int threads, final int connectTimeoutMs,
                                        final int probeTimeoutMs, final Cancellable cancel,
                                        final boolean stopAtFirst, final Listener listener) {
        final List<ServerInfo> found = Collections.synchronizedList(new ArrayList<ServerInfo>());
        if (hosts.isEmpty()) return new ArrayList<ServerInfo>();
        int n = Math.max(1, Math.min(threads, hosts.size()));
        ExecutorService pool = Executors.newFixedThreadPool(n, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "omni-scan-" + count.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        final CountDownLatch all = new CountDownLatch(hosts.size());
        final CountDownLatch first = new CountDownLatch(1);
        for (final String host : hosts) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (cancel != null && cancel.isCancelled()) return;
                        if (stopAtFirst && first.getCount() == 0) return;
                        if (!portOpen(host, port, connectTimeoutMs)) return;
                        ServerInfo s = OllamaClient.probe(host, port, probeTimeoutMs);
                        if (s != null) {
                            found.add(s);
                            if (listener != null) listener.onFound(s);
                            first.countDown();
                        }
                    } finally {
                        all.countDown();
                    }
                }
            });
        }
        pool.shutdown();
        try {
            // Wait for either the first hit (when asked) or the whole sweep,
            // checking for cancellation along the way.
            while (true) {
                if (cancel != null && cancel.isCancelled()) break;
                if (stopAtFirst && first.await(50, TimeUnit.MILLISECONDS)) break;
                if (!stopAtFirst && all.await(50, TimeUnit.MILLISECONDS)) break;
                if (all.getCount() == 0) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (stopAtFirst || (cancel != null && cancel.isCancelled())) pool.shutdownNow();
        synchronized (found) {
            return new ArrayList<ServerInfo>(found);
        }
    }

    public static boolean portOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            SocketBinder b = binder;
            if (b != null) b.bind(s, host);
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // IPv4 helpers
    // ------------------------------------------------------------------

    public static int ipToInt(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    public static String ipToString(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "." + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    /** Parses dotted IPv4, or returns null. */
    public static Integer parseIp(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("\\.");
        if (p.length != 4) return null;
        int v = 0;
        for (String part : p) {
            if (part.length() == 0 || part.length() > 3) return null;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return null;
            }
            int x = Integer.parseInt(part);
            if (x > 255) return null;
            v = (v << 8) | x;
        }
        return v;
    }

    /** True for 10/8, 172.16/12, 192.168/16, 169.254/16 and 127/8 literals. */
    public static boolean isLocalLiteral(String host) {
        Integer ip = parseIp(host);
        if (ip == null) return false;
        int a = (ip >>> 24) & 0xFF, b = (ip >>> 16) & 0xFF;
        return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                || (a == 169 && b == 254);
    }
}
