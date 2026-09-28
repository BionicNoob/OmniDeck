package com.omnideck.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.omnideck.mobile.core.Http;
import com.omnideck.mobile.core.LanScanner;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Network plumbing. Finds the phone's local subnets and routes traffic for
 * LAN addresses over the network that actually has them. That matters when
 * the Wi-Fi has no internet: Android then sends ordinary traffic over mobile
 * data, where 192.168.x.x doesn't exist.
 */
final class Net {
    private static final class Route {
        final Network network;
        final int address;
        final int prefix;

        Route(Network network, int address, int prefix) {
            this.network = network;
            this.address = address;
            this.prefix = prefix;
        }

        boolean contains(int ip) {
            int mask = prefix <= 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
            return (ip & mask) == (address & mask);
        }
    }

    private static volatile List<Route> routes = Collections.emptyList();
    private static volatile boolean installed;

    private Net() {}

    /** Installs the routing hooks into the core networking code (once). */
    static synchronized void install() {
        if (installed) return;
        installed = true;
        Http.opener = new Http.Opener() {
            @Override
            public HttpURLConnection open(URL url) throws IOException {
                Network n = networkFor(url.getHost());
                if (n != null) {
                    try {
                        return (HttpURLConnection) n.openConnection(url, Proxy.NO_PROXY);
                    } catch (RuntimeException e) {
                        // Network went away; fall back to default routing.
                    }
                }
                return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            }
        };
        LanScanner.binder = new LanScanner.SocketBinder() {
            @Override
            public void bind(Socket socket, String host) throws IOException {
                Network n = networkFor(host);
                if (n != null) {
                    try {
                        n.bindSocket(socket);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        };
    }

    /**
     * Pins a UDP socket (Wake-on-LAN broadcasts) to the phone's Wi-Fi /
     * Ethernet network, so the broadcast leaves on the LAN even while Android
     * prefers mobile data. No-op when no LAN network is known.
     */
    static void bindToLan(DatagramSocket socket) {
        List<Route> r = routes;
        if (r.isEmpty()) return;
        try {
            r.get(0).network.bindSocket(socket);
        } catch (IOException ignored) {
            // The network went away: default routing.
        } catch (RuntimeException ignored) {
        }
    }

    static Network networkFor(String host) {
        Integer ip = LanScanner.parseIp(host);
        if (ip == null || ((ip >>> 24) & 0xFF) == 127) return null;
        for (Route r : routes) {
            if (r.contains(ip)) return r.network;
        }
        return null;
    }

    /**
     * Re-reads the phone's LAN networks and returns every private IPv4 subnet
     * to scan (Wi-Fi/Ethernet first, then hotspot/tethering interfaces).
     */
    static List<LanScanner.Subnet> refresh(Context c) {
        List<LanScanner.Subnet> subnets = new ArrayList<LanScanner.Subnet>();
        List<Route> found = new ArrayList<Route>();
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && !nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                        int ip = LanScanner.ipToInt(a.getAddress());
                        LanScanner.Subnet s = new LanScanner.Subnet(lp.getInterfaceName(), ip, la.getPrefixLength());
                        subnets.add(s);
                        found.add(new Route(n, ip, s.prefix));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Missing permission or a flaky OEM implementation: fall back below.
        }
        routes = found;
        for (LanScanner.Subnet s : LanScanner.interfaceSubnets()) {
            boolean dup = false;
            for (LanScanner.Subnet t : subnets) dup |= t.address == s.address;
            if (!dup) subnets.add(s);
        }
        LanScanner.sortSubnets(subnets);
        return subnets;
    }

    static String describe(List<LanScanner.Subnet> subnets) {
        if (subnets.isEmpty()) return "no Wi-Fi network";
        StringBuilder sb = new StringBuilder();
        for (LanScanner.Subnet s : subnets) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.addressString()).append('/').append(s.prefix);
        }
        return sb.toString();
    }
}
