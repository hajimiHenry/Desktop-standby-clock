package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** MAC 规范化和 Linux 邻居表解析测试，不需要 root 或真实局域网。 */
public final class YeelightAddressResolverTest {
    @Test
    public void normalizesCommonMacFormats() {
        assertEquals(
                "de:ad:be:ef:12:34",
                YeelightAddressResolver.normalizeMac("DE-AD-BE-EF-12-34"));
        assertNull(YeelightAddressResolver.normalizeMac("not-a-mac"));
        assertNull(YeelightAddressResolver.normalizeMac(""));
    }

    @Test
    public void findsMacRegardlessOfCaseAndNeighborState() {
        String table = "192.168.1.8 dev wlan0 lladdr aa:bb:cc:dd:ee:ff STALE\n"
                + "192.168.1.9 dev wlan0 lladdr DE:AD:BE:EF:12:34 DELAY\n";

        assertEquals(
                "192.168.1.9",
                YeelightAddressResolver.findNeighborIp(table, "de:ad:be:ef:12:34"));
    }

    @Test
    public void prefersReachableEntryOverStaleDuplicate() {
        String table = "192.168.1.10 dev wlan0 lladdr de:ad:be:ef:12:34 STALE\n"
                + "192.168.1.9 dev wlan0 lladdr de:ad:be:ef:12:34 REACHABLE\n";

        assertEquals(
                "192.168.1.9",
                YeelightAddressResolver.findNeighborIp(table, "de:ad:be:ef:12:34"));
    }

    /**
     * 解析器实际执行的是 `ip neigh show dev wlan0`。带上 dev 参数后 ip 不会再重复
     * 打印设备名，每行只剩 4 段。这两条用例锁住这个真实格式：守卫原先要求至少 5 段，
     * 线上每一行都被跳过，而当时所有用例喂的都是 6 段输出，测试照样全绿。
     */
    @Test
    public void findsMacInDeviceScopedTableFormat() {
        String table = "192.168.1.8 lladdr 00:11:22:33:44:55 STALE\n"
                + "192.168.1.7 lladdr de:ad:be:ef:12:34 STALE\n";

        assertEquals(
                "192.168.1.7",
                YeelightAddressResolver.findNeighborIp(table, "de:ad:be:ef:12:34"));
    }

    @Test
    public void prefersReachableEntryInDeviceScopedTableFormat() {
        String table = "192.168.1.10 lladdr de:ad:be:ef:12:34 STALE\n"
                + "192.168.1.7 lladdr de:ad:be:ef:12:34 REACHABLE\n";

        assertEquals(
                "192.168.1.7",
                YeelightAddressResolver.findNeighborIp(table, "de:ad:be:ef:12:34"));
    }

    @Test
    public void ignoresFailedAndIncompleteEntries() {
        String table = "192.168.1.9 dev wlan0 FAILED\n"
                + "192.168.1.10 dev wlan0 lladdr de:ad:be:ef:12:34 INCOMPLETE\n";

        assertNull(YeelightAddressResolver.findNeighborIp(table, "de:ad:be:ef:12:34"));
    }
}
