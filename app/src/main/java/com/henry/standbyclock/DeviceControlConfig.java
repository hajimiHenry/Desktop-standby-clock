package com.henry.standbyclock;

/** Fixed devices on Henry's trusted home LAN. */
final class DeviceControlConfig {
    static final String YEELIGHT_HOST = "192.168.1.10";
    static final int YEELIGHT_PORT = 55443;

    static final String DESKTOP_MAC = "8C:32:23:49:15:3F";
    static final String WOL_BROADCAST_HOST = "192.168.1.255";
    static final int WOL_PORT = 9;

    private DeviceControlConfig() {
    }
}
