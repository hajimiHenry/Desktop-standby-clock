package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TrafficStatusClientTest {
    @Test
    public void missingLocalConfigHasAnExplicitDashboardState() {
        TrafficStatusFormatting.Display display =
                TrafficStatusFormatting.Display.notConfigured();

        assertEquals("NOT CONFIGURED", display.hostRemaining);
        assertEquals("NOT CONFIGURED", display.sanmaoRemaining);
        assertEquals("COPY STANDBY-CLOCK.PROPERTIES.EXAMPLE", display.footer);
    }

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

    @Test
    public void firstNetworkFailureReplacesLoadingState() {
        TrafficStatusFormatting.Display display = TrafficStatusFormatting.offline(
                TrafficStatusFormatting.Display.loading());

        assertEquals("OFFLINE", display.hostRemaining);
        assertEquals("OFFLINE", display.sanmaoRemaining);
        assertEquals("VPS UNREACHABLE", display.footer);
    }
}
