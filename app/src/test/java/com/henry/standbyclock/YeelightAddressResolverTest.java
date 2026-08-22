package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/** MAC 规范化和 Linux 邻居表解析测试，不需要 root 或真实局域网。 */
public final class YeelightAddressResolverTest {
    /** 下面这组解析优先级测试统一用的灯 MAC。 */
    private static final String MAC = "de:ad:be:ef:12:34";

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

    /** 存过地址时，冷启动直接拿来用，绝不能碰 root——这正是这次改动要保证的事。 */
    @Test
    public void storedAddressIsUsedWithoutTouchingRoot() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        MemoryStore store = new MemoryStore("192.168.1.42");
        YeelightAddressResolver resolver = new YeelightAddressResolver(
                "192.168.1.7", MAC, store, runner);

        assertEquals("192.168.1.42", resolver.resolve(false));
        assertEquals(0, runner.calls);
    }

    /** 没存过地址时退到配置里的静态 IP，同样不该动 root。 */
    @Test
    public void configuredHostIsUsedWhenNothingWasStored() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        MemoryStore store = new MemoryStore(null);
        YeelightAddressResolver resolver = new YeelightAddressResolver(
                "192.168.1.7", MAC, store, runner);

        assertEquals("192.168.1.7", resolver.resolve(false));
        assertEquals(0, runner.calls);
    }

    /** 存的地址格式不对（偏好被写坏）时不能直接信，要继续降级。 */
    @Test
    public void malformedStoredAddressIsIgnored() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        MemoryStore store = new MemoryStore("not-an-ip");
        YeelightAddressResolver resolver = new YeelightAddressResolver(
                "192.168.1.7", MAC, store, runner);

        assertEquals("192.168.1.7", resolver.resolve(false));
        assertEquals(0, runner.calls);
    }

    /** 连不上了才强制刷新：这时才允许走 root，并且要把新地址记下来。 */
    @Test
    public void forcedRefreshUsesRootAndStoresTheNewAddress() throws IOException {
        RecordingRunner runner = new RecordingRunner(
                "192.168.1.55 lladdr " + MAC + " REACHABLE");
        MemoryStore store = new MemoryStore("192.168.1.42");
        YeelightAddressResolver resolver = new YeelightAddressResolver(
                "192.168.1.7", MAC, store, runner);

        assertEquals("192.168.1.55", resolver.resolve(true));
        assertEquals("192.168.1.55", store.value);
        assertTrue(runner.calls > 0);
    }

    /** 一个已知地址都没有时，root 是唯一的出路，这时候用它是应该的。 */
    @Test
    public void rootIsUsedWhenNoAddressIsKnownAtAll() throws IOException {
        RecordingRunner runner = new RecordingRunner(
                "192.168.1.55 lladdr " + MAC + " REACHABLE");
        MemoryStore store = new MemoryStore(null);
        YeelightAddressResolver resolver = new YeelightAddressResolver("", MAC, store, runner);

        assertEquals("192.168.1.55", resolver.resolve(false));
        assertTrue(runner.calls > 0);
    }

    /** 测试替身：记录 root 被调用了几次，并返回预设的邻居表。 */
    private static final class RecordingRunner
            implements YeelightAddressResolver.RootCommandRunner {
        private final String output;
        private int calls;

        RecordingRunner() {
            this("");
        }

        RecordingRunner(String output) {
            this.output = output;
        }

        @Override
        public String run(String command) {
            calls++;
            return output;
        }
    }

    /** 测试替身：把"上次可用地址"放在内存里。 */
    private static final class MemoryStore implements YeelightAddressResolver.AddressStore {
        private String value;

        MemoryStore(String value) {
            this.value = value;
        }

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String host) {
            value = host;
        }
    }
}
