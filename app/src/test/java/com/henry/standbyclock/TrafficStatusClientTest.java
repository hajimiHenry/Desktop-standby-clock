package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 流量看板的解析和展示测试。用写死的 JSON 字符串喂进去，
 * 所以不需要真的连 VPS 就能验证完整链路（解析 → 格式化 → 屏幕文字）。
 */
public final class TrafficStatusClientTest {
    /** 没配置时要有明确提示，而且页脚直接告诉用户去复制哪个配置模板。 */
    @Test
    public void missingLocalConfigHasAnExplicitDashboardState() {
        TrafficStatusFormatting.Display display =
                TrafficStatusFormatting.Display.notConfigured();

        assertEquals("NOT CONFIGURED", display.hostRemaining);
        assertEquals("NOT CONFIGURED", display.sanmaoRemaining);
        assertEquals("COPY STANDBY-CLOCK.PROPERTIES.EXAMPLE", display.footer);
    }

    /**
     * 解析服务端返回的完整 JSON。注意两个来源的单位不同：
     * HostVDS 用 GB（1000 进制），机场用 GiB（1024 进制），解析时不能混。
     * 机场那一栏还是"已超量"的场景：remaining 为 0、over 为 9.439。
     */
    @Test
    public void parsesBridgeResponse() throws Exception {
        TrafficStatusClient.Snapshot snapshot = TrafficStatusClient.parse(
                "{\"providers\":{"
                        + "\"hostvds\":{\"status\":\"ok\",\"stale\":false,"
                        + "\"unit\":\"GB\",\"used_gb\":197.173,\"total_gb\":1000,"
                        + "\"remaining_gb\":802.827,\"over_gb\":0,"
                        + "\"reset_at\":\"2026-08-20\"},"
                        + "\"sanmao\":{\"status\":\"ok\",\"stale\":false,"
                        + "\"unit\":\"GiB\",\"used_gb\":509.439,\"total_gb\":500,"
                        + "\"remaining_gb\":0,\"over_gb\":9.439,"
                        + "\"expire_at\":\"2027-04-25\"}},"
                        + "\"updated_at\":\"2026-08-15T12:22:55Z\"}");

        assertEquals(802.827d, snapshot.hostvds.remainingGb, 0.0001d);
        assertEquals(9.439d, snapshot.sanmao.overGb, 0.0001d);
        assertEquals("GiB", snapshot.sanmao.unit);
        assertFalse(snapshot.hostvds.stale);
        assertTrue(snapshot.hostvds.hasUsage());
    }

    /**
     * 混合场景的展示效果：HostVDS 那栏认证过期了但仍有缓存数字，
     * 所以主数字照常显示，第三行打上 "STALE · COOKIE EXPIRED"；
     * 机场那栏正常，但已超量，明细行显示超出多少而不是"已用/总量"。
     */
    @Test
    public void formatsUsageAndProviderFailure() throws Exception {
        TrafficStatusClient.Snapshot snapshot = TrafficStatusClient.parse(
                "{\"providers\":{"
                        + "\"hostvds\":{\"status\":\"auth_expired\",\"stale\":true,"
                        + "\"unit\":\"GB\",\"remaining_gb\":802.827,"
                        + "\"used_gb\":197.173,\"total_gb\":1000},"
                        + "\"sanmao\":{\"status\":\"ok\",\"unit\":\"GiB\","
                        + "\"remaining_gb\":0,\"used_gb\":509.439,"
                        + "\"total_gb\":500,\"over_gb\":9.439,"
                        + "\"expire_at\":\"2027-04-25\"}},"
                        + "\"updated_at\":\"2026-08-15T12:22:55Z\"}");

        TrafficStatusFormatting.Display display = TrafficStatusFormatting.format(snapshot);

        assertEquals("802.83 GB LEFT", display.hostRemaining);
        assertEquals("STALE · COOKIE EXPIRED", display.hostMeta);
        assertEquals("0 GIB LEFT", display.sanmaoRemaining);
        assertEquals("OVER 9.44 GIB", display.sanmaoDetail);
        assertEquals("EXPIRES APR 25", display.sanmaoMeta);
    }

    /**
     * 从没成功过就失败时，"加载中"这个占位不该被当成有效数据保留下来，
     * 而要老实换成 OFFLINE。（相对地，有过真实数据时会保留旧数字并标 STALE。）
     */
    @Test
    public void firstNetworkFailureReplacesLoadingState() {
        TrafficStatusFormatting.Display display = TrafficStatusFormatting.offline(
                TrafficStatusFormatting.Display.loading());

        assertEquals("OFFLINE", display.hostRemaining);
        assertEquals("OFFLINE", display.sanmaoRemaining);
        assertEquals("VPS UNREACHABLE", display.footer);
    }
}
