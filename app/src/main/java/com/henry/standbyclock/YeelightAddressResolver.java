package com.henry.standbyclock;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 解析吸顶灯当前的局域网 IP，必要时用它的固定 MAC 把变动的地址找回来。
 *
 * <p>解析按代价从低到高排成四级，逐级降级：
 *
 * <ol>
 *   <li><b>内存缓存</b>：同一个进程里已经解析过，直接用。</li>
 *   <li><b>上次可用的地址</b>：由 {@link AddressStore} 持久化，跨进程重启有效。</li>
 *   <li><b>配置里的静态 IP</b>：{@code standby-clock.properties} 写死的那个。</li>
 *   <li><b>root 读邻居表</b>：只有上面全都连不上（调用方传 {@code forceRefresh}），
 *       或者压根没有任何已知地址时才走到这里。</li>
 * </ol>
 *
 * <p>前三级都是纯 TCP 直连，第四级才需要 root。这个顺序很重要：灯的 IP 几个月才被
 * DHCP 换一次，而 root 那条路每执行一次就要 fork 一个 su 进程、跑一遍 shell，还会弹
 * 一条 Magisk 授权提示——为一件几个月才发生一次的事在每次冷启动都付这个代价并不划算。
 *
 * <p>（这里原先的实现是反着的：只要内存缓存为空就先掏 root 读邻居表，静态 IP 沦为
 * root 整条路径都失败后的备胎。于是应用每被系统回收重启一次，用户开一次设备面板就会
 * 看到一条授权提示。）
 *
 * <p>邻居表里没有目标时，才分批 ping 当前 Wi-Fi 的 /24 网段来刷新它。
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

    /**
     * 上次成功解析出的地址的持久化出口。
     *
     * <p>抽成接口而不是直接用 SharedPreferences，是为了让这个类保持"纯逻辑、可单测"，
     * 和 AmbientLightPolicy 一样不碰 Android API。界面层提供真实实现，测试里换成内存版。
     */
    interface AddressStore {
        /** 读上次记住的地址；没有则返回 null。 */
        String read();

        /** 记住一个新解析出来的地址。 */
        void write(String host);
    }

    /** 执行一条 root 命令并返回标准输出。同样是为了可测才抽成接口。 */
    interface RootCommandRunner {
        String run(String command) throws IOException;
    }

    /** 默认实现：走 RootShell，命令非 0 退出时返回空串（当作"没查到"处理）。 */
    static final RootCommandRunner DEFAULT_ROOT_RUNNER = command -> {
        try {
            RootShell.Result result = RootShell.capture(command);
            return result.exitCode == 0 ? result.output : "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving Yeelight address", exception);
        }
    };

    private final String fallbackHost;
    private final String macAddress;
    private final AddressStore store;
    private final RootCommandRunner rootRunner;
    private String cachedHost;

    YeelightAddressResolver(
            String fallbackHost,
            String macAddress,
            AddressStore store,
            RootCommandRunner rootRunner) {
        this.fallbackHost = fallbackHost == null ? "" : fallbackHost.trim();
        this.macAddress = normalizeMac(macAddress);
        this.store = store;
        this.rootRunner = rootRunner == null ? DEFAULT_ROOT_RUNNER : rootRunner;
    }

    /**
     * @param forceRefresh true 表示调用方刚刚连不上上一次给出的地址，要求撇开所有已知
     *                     地址、用 root 重新发现。这是唯一会主动动用 root 的入口
     */
    synchronized String resolve(boolean forceRefresh) throws IOException {
        // 非强制模式下逐级试探那三种不需要 root 的地址来源。
        if (!forceRefresh) {
            if (cachedHost != null) {
                return cachedHost;
            }
            String storedHost = store == null ? null : store.read();
            if (isIpv4(storedHost)) {
                cachedHost = storedHost.trim();
                return cachedHost;
            }
            if (!fallbackHost.isEmpty()) {
                cachedHost = fallbackHost;
                return fallbackHost;
            }
        }

        // 走到这里说明要么已知地址都连不上，要么一个已知地址都没有——该 root 出场了。
        if (macAddress != null) {
            try {
                String host = findNeighborIp(rootRunner.run(READ_NEIGHBORS_COMMAND), macAddress);
                if (host == null || forceRefresh) {
                    String refreshedHost = findNeighborIp(
                            rootRunner.run(REFRESH_NEIGHBORS_COMMAND), macAddress);
                    // 刷新命令失败时保留刷新前仍然有效的邻居记录，不退回错误的旧配置。
                    if (refreshedHost != null) {
                        host = refreshedHost;
                    }
                }
                if (host != null) {
                    remember(host);
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

    /**
     * 丢掉内存缓存，让下一次 resolve 重新决定用哪个地址。
     *
     * <p>故意不清持久化的那一份：它下一次仍然值得先试一把（灯多半只是临时离线），
     * 真的换了地址时会被 root 发现的新地址覆盖掉。
     */
    synchronized void invalidate() {
        cachedHost = null;
    }

    /** 记住一个刚解析成功的地址：内存和持久化各存一份。 */
    private void remember(String host) {
        cachedHost = host;
        if (store != null) {
            store.write(host);
        }
    }

    private static boolean isIpv4(String value) {
        return value != null && IPV4_PATTERN.matcher(value.trim()).matches();
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
            // 读取命令带了 dev wlan0，ip 就不再重复打印设备名，所以最短的有效行
            // 只有 4 段："IP lladdr MAC 状态"。这里原先要求至少 5 段，等于把每一
            // 行都跳过，按 MAC 恢复地址的整条路径因此静默失效，一直回退到配置里
            // 那个早就过期的 IP。
            if (fields.length < 4 || !IPV4_PATTERN.matcher(fields[0]).matches()) {
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
