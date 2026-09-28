package com.omnideck.mobile.core;

import com.omnideck.mobile.mock.MockOllama;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LanScannerTest {

    private static LanScanner.Subnet subnet(String ip, int prefix) {
        return new LanScanner.Subnet("wlan0", LanScanner.parseIp(ip), prefix);
    }

    @Test
    public void candidatesCoverTheOwnSlash24AndSkipSelfNetworkAndBroadcast() {
        List<String> hosts = LanScanner.candidateHosts(Collections.singletonList(subnet("192.168.1.37", 24)), 1024);
        assertEquals(253, hosts.size());
        assertEquals("192.168.1.38", hosts.get(0));
        assertEquals("192.168.1.36", hosts.get(1));
        assertTrue(hosts.contains("192.168.1.1"));
        assertTrue(hosts.contains("192.168.1.254"));
        assertFalse(hosts.contains("192.168.1.37"));
        assertFalse(hosts.contains("192.168.1.0"));
        assertFalse(hosts.contains("192.168.1.255"));
    }

    @Test
    public void widerSubnetsScanOwnSlash24FirstAndAreCapped() {
        List<String> hosts = LanScanner.candidateHosts(Collections.singletonList(subnet("10.20.30.40", 16)), 1024);
        // A /16 is swept as the surrounding /22: 1022 hosts minus the phone itself.
        assertEquals(1021, hosts.size());
        for (int i = 0; i < 253; i++) {
            assertTrue(hosts.get(i), hosts.get(i).startsWith("10.20.30."));
        }
        assertTrue(hosts.contains("10.20.31.5"));
        assertFalse(hosts.contains("10.20.30.40"));
    }

    @Test
    public void badPrefixFallsBackToSlash24() {
        LanScanner.Subnet s = new LanScanner.Subnet("wlan0", LanScanner.parseIp("192.168.0.10"), 64);
        assertEquals(24, s.prefix);
    }

    @Test
    public void ipHelpers() {
        assertEquals("192.168.1.20", LanScanner.ipToString(LanScanner.parseIp("192.168.1.20")));
        assertNull(LanScanner.parseIp("192.168.1"));
        assertNull(LanScanner.parseIp("192.168.1.256"));
        assertNull(LanScanner.parseIp("my-pc.local"));
        assertTrue(LanScanner.isLocalLiteral("192.168.4.2"));
        assertTrue(LanScanner.isLocalLiteral("10.0.0.3"));
        assertTrue(LanScanner.isLocalLiteral("172.20.1.1"));
        assertFalse(LanScanner.isLocalLiteral("172.40.1.1"));
        assertFalse(LanScanner.isLocalLiteral("100.101.1.2"));
        assertFalse(LanScanner.isLocalLiteral("8.8.8.8"));
        assertTrue(LanScanner.skipInterface("rmnet_data0"));
        assertTrue(LanScanner.skipInterface("tun0"));
        assertFalse(LanScanner.skipInterface("wlan0"));
    }

    @Test
    public void scanFindsTheServerOnItsSubnet() throws Exception {
        // 127.0.0.0/8 is all loopback on Linux, so a /24 of it behaves like a
        // LAN where exactly one host runs Ollama.
        MockOllama mock = MockOllama.start("127.0.0.57", 0);
        try {
            List<String> hosts = LanScanner.candidateHosts(
                    Collections.singletonList(new LanScanner.Subnet("lo", LanScanner.parseIp("127.0.0.1"), 24)), 1024);
            assertTrue(hosts.contains("127.0.0.57"));
            final List<ServerInfo> seen = Collections.synchronizedList(new ArrayList<ServerInfo>());
            long t0 = System.currentTimeMillis();
            List<ServerInfo> found = LanScanner.scan(hosts, mock.port(), 64, 400, 1500, new Cancellable(), false,
                    new LanScanner.Listener() {
                        @Override
                        public void onFound(ServerInfo server) {
                            seen.add(server);
                        }
                    });
            long ms = System.currentTimeMillis() - t0;
            assertEquals(1, found.size());
            assertEquals("127.0.0.57", found.get(0).host);
            assertEquals(mock.port(), found.get(0).port);
            assertEquals("0.12.6", found.get(0).version);
            assertEquals(1, seen.size());
            assertTrue("full sweep took " + ms + "ms", ms < 5000);

            t0 = System.currentTimeMillis();
            List<ServerInfo> first = LanScanner.scan(hosts, mock.port(), 64, 400, 1500, new Cancellable(), true, null);
            assertEquals(1, first.size());
            assertTrue(System.currentTimeMillis() - t0 < 3000);
        } finally {
            mock.stop();
        }
    }

    @Test
    public void scanWithNothingListeningFindsNothing() throws Exception {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        List<String> hosts = new ArrayList<String>();
        for (int i = 1; i <= 20; i++) hosts.add("127.0.1." + i);
        assertTrue(LanScanner.scan(hosts, port, 16, 300, 1000, new Cancellable(), true, null).isEmpty());
    }

    @Test
    public void cancelledScanReturnsImmediately() {
        Cancellable c = new Cancellable();
        c.cancel();
        List<String> hosts = new ArrayList<String>();
        for (int i = 1; i <= 200; i++) hosts.add("127.0.2." + i);
        long t0 = System.currentTimeMillis();
        assertTrue(LanScanner.scan(hosts, 9, 8, 300, 1000, c, false, null).isEmpty());
        assertTrue(System.currentTimeMillis() - t0 < 1000);
    }

    @Test
    public void interfaceSubnetsNeverThrows() {
        assertNotNull(LanScanner.interfaceSubnets());
    }
}
