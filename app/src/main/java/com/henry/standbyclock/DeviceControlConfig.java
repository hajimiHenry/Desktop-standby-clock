package com.henry.standbyclock;

/** Build-time settings loaded from the ignored standby-clock.properties file. */
final class DeviceControlConfig {
    static final String YEELIGHT_HOST = BuildConfig.YEELIGHT_HOST;
    static final int YEELIGHT_PORT = BuildConfig.YEELIGHT_PORT;

    static final String DESKTOP_MAC = BuildConfig.DESKTOP_MAC;
    static final String WOL_BROADCAST_HOST = BuildConfig.WOL_BROADCAST_HOST;
    static final int WOL_PORT = BuildConfig.WOL_PORT;

    static final String TRAFFIC_STATUS_URL = BuildConfig.TRAFFIC_STATUS_URL;
    static final String TRAFFIC_HOST_LABEL = displayLabel(
            BuildConfig.TRAFFIC_HOST_LABEL, "HOSTVDS");
    static final String TRAFFIC_SUBSCRIPTION_LABEL = displayLabel(
            BuildConfig.TRAFFIC_SUBSCRIPTION_LABEL, "SUBSCRIPTION");

    private DeviceControlConfig() {
    }

    static boolean isYeelightConfigured() {
        return hasText(YEELIGHT_HOST);
    }

    static boolean isWakeOnLanConfigured() {
        return hasText(DESKTOP_MAC) && hasText(WOL_BROADCAST_HOST);
    }

    static boolean isTrafficStatusConfigured() {
        return hasText(TRAFFIC_STATUS_URL);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String displayLabel(String configured, String fallback) {
        String value = hasText(configured) ? configured.trim() : fallback;
        return value.length() <= 18 ? value : value.substring(0, 18);
    }
}
