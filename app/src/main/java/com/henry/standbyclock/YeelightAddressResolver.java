package com.henry.standbyclock;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 用灯的固定 MAC 解析当前局域网 IP，并在内存中缓存最近一次成功结果。
 *
 * <p>Android 应用不能拿 MAC 直接建立 TCP 连接，所以这里借助专用手机已有的 root
 * 权限读取 Linux 邻居表。邻居表没有目标时才分批 ping 当前 Wi-Fi 的 /24 网段来刷新；
 * 这是故障恢复路径，不会在每次开关灯时执行。
 */
final class YeelightAddressResolver {
    private static final Pattern MAC_PATTERN = Pattern.compile(
            "^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");

    private static final String READ_NEIGHBORS_COMMAND = "ip neigh show dev wlan0";

    /**
     * 每批并行探测 64 个地址，最多四批。比 254 个进程一起启动温和，也比逐个等超时快。
     * 只支持 /24：MAC 邻居发现本来就只能跨同一二层网段，而家庭网络通常正是 /24。
     */
    private static final String REFRESH_NEIGHBORS_COMMAND =
            "network=$(ip -4 route show dev wlan0 scope link); network=${network%% *}; "
            + "case \"$network\" in *.0/24) prefix=${network%.0/24} ;; *) exit 2 ;; esac; "
            + "start=1; while [ \"$start\" -le 254 ]; do "
            + "end=$((start + 63)); [ \"$end\" -gt 254 ] && end=254; "
            + "last=$start; while [ \"$last\" -le \"$end\" ]; do "
            + "ping -c 1 -W 1 \"$prefix.$last\" >/dev/null 2>&1 & last=$((last + 1)); "
            + "done; wait; start=$((end + 1)); done; "
            + READ_NEIGHBORS_COMMAND;

    private final String fallbackHost;
    private final String macAddress;
    private String cachedHost;

    YeelightAddressResolver(String fallbackHost, String macAddress) {
        this.fallbackHost = fallbackHost == null ? "" : fallbackHost.trim();
        this.macAddress = normalizeMac(macAddress);
    }

    /**
     * @param forceRefresh true 时主动刷新邻居表，用于已缓存地址连接失败后的自愈
     */
    synchronized String resolve(boolean forceRefresh) throws IOException {
        if (!forceRefresh && cachedHost != null) {
            return cachedHost;
        }

        if (macAddress != null) {
            try {
                String host = findNeighborIp(runRoot(READ_NEIGHBORS_COMMAND), macAddress);
                if (host == null || forceRefresh) {
                    String refreshedHost = findNeighborIp(
                            runRoot(REFRESH_NEIGHBORS_COMMAND), macAddress);
                    // 刷新命令失败时保留刷新前仍然有效的邻居记录，不退回错误的旧配置。
                    if (refreshedHost != null) {
                        host = refreshedHost;
                    }
                }
                if (host != null) {
                    cachedHost = host;
                    return host;
                }
            } catch (IOException rootFailure) {
                // 没有 root 的普通设备仍可沿用静态 IP；两者都没有时才向上报告失败。
                if (fallbackHost.isEmpty()) {
                    throw rootFailure;
                }
            }
        }

        if (!fallbackHost.isEmpty()) {
            cachedHost = fallbackHost;
            return fallbackHost;
        }
        throw new IOException("Yeelight address could not be resolved");
    }

    synchronized void invalidate() {
        cachedHost = null;
    }

    private static String runRoot(String command) throws IOException {
        try {
            RootShell.Result result = RootShell.capture(command);
            return result.exitCode == 0 ? result.output : "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving Yeelight address", exception);
        }
    }

    /** 从 ip neigh 的输出中找 MAC；忽略 FAILED/INCOMPLETE 等不可用记录。 */
    static String findNeighborIp(String neighborTable, String expectedMac) {
        String normalizedExpected = normalizeMac(expectedMac);
        if (normalizedExpected == null || neighborTable == null) {
            return null;
        }
        String fallback = null;
        for (String line : neighborTable.split("\\R")) {
            String lower = line.trim().toLowerCase(Locale.US);
            if (lower.isEmpty() || lower.contains(" failed") || lower.contains(" incomplete")) {
                continue;
            }
            String[] fields = lower.split("\\s+");
            if (fields.length < 5 || !IPV4_PATTERN.matcher(fields[0]).matches()) {
                continue;
            }
            for (int index = 1; index + 1 < fields.length; index++) {
                if ("lladdr".equals(fields[index])
                        && normalizedExpected.equals(normalizeMac(fields[index + 1]))) {
                    // 刷新后新地址通常是 REACHABLE，优先于残留的 STALE 旧记录。
                    if (lower.endsWith(" reachable")) {
                        return fields[0];
                    }
                    fallback = fields[0];
                }
            }
        }
        return fallback;
    }

    static String normalizeMac(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replace('-', ':').toLowerCase(Locale.US);
        return MAC_PATTERN.matcher(normalized).matches() ? normalized : null;
    }
}
