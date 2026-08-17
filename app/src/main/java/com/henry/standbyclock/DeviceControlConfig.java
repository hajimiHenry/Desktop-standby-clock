package com.henry.standbyclock;

/**
 * 设备控制相关的配置常量，全部来自编译期生成的 BuildConfig。
 *
 * <p>数据是怎么进来的：仓库里有个 standby-clock.properties.example 模板，用户把它
 * 复制成 standby-clock.properties 并填上自己家的灯 IP、电脑 MAC、VPS 地址；
 * app/build.gradle 在编译时读这个文件，生成 BuildConfig 里的常量。真实的
 * standby-clock.properties 被 .gitignore 排除，所以私人的 IP / 地址不会进仓库。
 *
 * <p>没有这个文件也能正常编译——BuildConfig 里的值会是空串，下面几个 isXxxConfigured()
 * 就返回 false，界面上对应功能显示 "NOT CONFIGURED" 而不是崩溃或空白。
 */
final class DeviceControlConfig {
    /** Yeelight 吸顶灯在局域网里的 IP 和端口。 */
    static final String YEELIGHT_HOST = BuildConfig.YEELIGHT_HOST;
    static final int YEELIGHT_PORT = BuildConfig.YEELIGHT_PORT;

    /** 要被网络唤醒的台式机 MAC 地址。 */
    static final String DESKTOP_MAC = BuildConfig.DESKTOP_MAC;
    /** 发魔术包用的定向广播地址（一般是网段广播地址，如 192.168.1.255）与端口。 */
    static final String WOL_BROADCAST_HOST = BuildConfig.WOL_BROADCAST_HOST;
    static final int WOL_PORT = BuildConfig.WOL_PORT;

    /** 自建 VPS 上流量查询服务的地址。 */
    static final String TRAFFIC_STATUS_URL = BuildConfig.TRAFFIC_STATUS_URL;
    /** 流量看板上两栏的标题，允许用户自定义，留空则用这里的默认值。 */
    static final String TRAFFIC_HOST_LABEL = displayLabel(
            BuildConfig.TRAFFIC_HOST_LABEL, "HOSTVDS");
    static final String TRAFFIC_SUBSCRIPTION_LABEL = displayLabel(
            BuildConfig.TRAFFIC_SUBSCRIPTION_LABEL, "SUBSCRIPTION");

    private DeviceControlConfig() {
    }

    static boolean isYeelightConfigured() {
        return hasText(YEELIGHT_HOST);
    }

    /** 网络唤醒要 MAC 和广播地址两者齐全才算配好。 */
    static boolean isWakeOnLanConfigured() {
        return hasText(DESKTOP_MAC) && hasText(WOL_BROADCAST_HOST);
    }

    static boolean isTrafficStatusConfigured() {
        return hasText(TRAFFIC_STATUS_URL);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 整理用户自定义的标题：空则用默认值，并且截断到 18 个字符。
     * 截断是因为标题直接画在屏幕固定宽度的栏位里，太长会画出边界跟旁边一栏糊在一起。
     */
    private static String displayLabel(String configured, String fallback) {
        String value = hasText(configured) ? configured.trim() : fallback;
        return value.length() <= 18 ? value : value.substring(0, 18);
    }
}
