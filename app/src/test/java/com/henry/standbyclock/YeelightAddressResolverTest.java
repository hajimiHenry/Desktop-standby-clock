package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** MAC 规范化和 Linux 邻居表解析测试，不需要 root 或真实局域网。 */
public final class YeelightAddressResolverTest {
    @Test
    public void normalizesCommonMacFormats() {
        assertEquals(
                "b4:60:ed:03:d1:d2",
                YeelightAddressResolver.normalizeMac("B4-60-ED-03-D1-D2"));
        assertNull(YeelightAddressResolver.normalizeMac("not-a-mac"));
        assertNull(YeelightAddressResolver.normalizeMac(""));
    }

    @Test
    public void findsMacRegardlessOfCaseAndNeighborState() {
        String table = "192.168.1.8 dev wlan0 lladdr aa:bb:cc:dd:ee:ff STALE\n"
                + "192.168.1.9 dev wlan0 lladdr B4:60:ED:03:D1:D2 DELAY\n";

        assertEquals(
                "192.168.1.9",
                YeelightAddressResolver.findNeighborIp(table, "b4:60:ed:03:d1:d2"));
    }

    @Test
    public void prefersReachableEntryOverStaleDuplicate() {
        String table = "192.168.1.10 dev wlan0 lladdr b4:60:ed:03:d1:d2 STALE\n"
                + "192.168.1.9 dev wlan0 lladdr b4:60:ed:03:d1:d2 REACHABLE\n";

        assertEquals(
                "192.168.1.9",
                YeelightAddressResolver.findNeighborIp(table, "b4:60:ed:03:d1:d2"));
    }

    @Test
    public void ignoresFailedAndIncompleteEntries() {
        String table = "192.168.1.9 dev wlan0 FAILED\n"
                + "192.168.1.10 dev wlan0 lladdr b4:60:ed:03:d1:d2 INCOMPLETE\n";

        assertNull(YeelightAddressResolver.findNeighborIp(table, "b4:60:ed:03:d1:d2"));
    }
}
