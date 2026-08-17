package com.henry.standbyclock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 把 {@link TrafficStatusClient} 拿到的原始数字，翻译成流量看板上直接能画的文字。
 *
 * <p>全部输出大写英文短句（如 "802.83 GB LEFT"），是为了配合表盘那套 CRT 终端风格的
 * 等宽字体。所有方法都是纯函数，不碰网络也不碰 View，因此能直接单测各种展示状态，
 * 见 TrafficStatusClientTest。
 */
final class TrafficStatusFormatting {
    /** 日期显示成 "AUG 20" 这种短格式，屏幕上那一行位置很窄。 */
    private static final DateTimeFormatter MONTH_DAY =
            DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter CLOCK_TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.US);

    /**
     * 看板一屏所需的全部文字。左右各一栏（HostVDS / 机场订阅），每栏三行，
     * 外加底部一行页脚。ClockView 拿到后按固定坐标画上去，自己不做任何判断。
     */
    static final class Display {
        /** 第一行，主数字，如 "802.83 GB LEFT"。 */
        final String hostRemaining;
        /** 第二行，明细，如 "197.17 / 1000 GB"。 */
        final String hostDetail;
        /** 第三行，附加信息，如重置日期或错误原因。 */
        final String hostMeta;
        final String sanmaoRemaining;
        final String sanmaoDetail;
        final String sanmaoMeta;
        /** 底部页脚，通常是数据更新时间。 */
        final String footer;

        Display(
                String hostRemaining,
                String hostDetail,
                String hostMeta,
                String sanmaoRemaining,
                String sanmaoDetail,
                String sanmaoMeta,
                String footer) {
            this.hostRemaining = hostRemaining;
            this.hostDetail = hostDetail;
            this.hostMeta = hostMeta;
            this.sanmaoRemaining = sanmaoRemaining;
            this.sanmaoDetail = sanmaoDetail;
            this.sanmaoMeta = sanmaoMeta;
            this.footer = footer;
        }

        /** 冷启动状态：还没发起过任何请求。 */
        static Display initial() {
            return new Display(
                    "NOT LOADED", "", "", "NOT LOADED", "", "", "OPEN TO REFRESH");
        }

        /** 请求进行中。 */
        static Display loading() {
            return new Display(
                    "CHECKING...", "", "", "CHECKING...", "", "", "CONTACTING VPS");
        }

        /** 用户没配 VPS 地址。页脚直接告诉他该去复制哪个配置模板。 */
        static Display notConfigured() {
            return new Display(
                    "NOT CONFIGURED", "", "",
                    "NOT CONFIGURED", "", "",
                    "COPY STANDBY-CLOCK.PROPERTIES.EXAMPLE");
        }
    }

    /** 单栏的三行文字，只是 format 过程中的中间结果。 */
    private static final class ProviderDisplay {
        final String remaining;
        final String detail;
        final String meta;

        ProviderDisplay(String remaining, String detail, String meta) {
            this.remaining = remaining;
            this.detail = detail;
            this.meta = meta;
        }
    }

    private TrafficStatusFormatting() {
    }

    /** 请求成功时：把两栏数据分别格式化后拼成一屏。 */
    static Display format(TrafficStatusClient.Snapshot snapshot) {
        ProviderDisplay host = formatProvider(snapshot.hostvds, true);
        ProviderDisplay sanmao = formatProvider(snapshot.sanmao, false);
        return new Display(
                host.remaining,
                host.detail,
                host.meta,
                sanmao.remaining,
                sanmao.detail,
                sanmao.meta,
                formatUpdatedAt(snapshot.updatedAt));
    }

    /**
     * 请求失败时该显示什么。
     *
     * <p>分两种情况：如果之前从来没成功过（当前显示的还是 NOT LOADED / CHECKING 占位），
     * 那就老实显示 OFFLINE；如果之前成功过，则<em>保留旧数字</em>，只在旁边打上 STALE
     * 标记。因为对用户来说"十分钟前还剩 800GB"远比一个 OFFLINE 有用。
     */
    static Display offline(Display previous) {
        if (previous == null
                || (isPlaceholder(previous.hostRemaining)
                && isPlaceholder(previous.sanmaoRemaining))) {
            return new Display("OFFLINE", "", "", "OFFLINE", "", "", "VPS UNREACHABLE");
        }
        return new Display(
                previous.hostRemaining,
                previous.hostDetail,
                appendStale(previous.hostMeta),
                previous.sanmaoRemaining,
                previous.sanmaoDetail,
                appendStale(previous.sanmaoMeta),
                "STALE · VPS UNREACHABLE");
    }

    /** 这两个文案是占位符，不是真实数据，判断"之前有没有成功过"时要排除掉。 */
    private static boolean isPlaceholder(String value) {
        return "NOT LOADED".equals(value) || "CHECKING...".equals(value);
    }

    /**
     * 格式化单栏的三行文字。
     *
     * @param hostvds true = HostVDS 栏，false = 机场订阅栏。两者唯一的区别是第三行：
     *                前者显示"下次重置日"，后者显示"订阅到期日"
     */
    private static ProviderDisplay formatProvider(
            TrafficStatusClient.Provider provider, boolean hostvds) {
        String failure = failureLabel(provider.status, provider.stale);
        // 连剩余量都没有，说明这个来源整个挂了，主数字位置直接显示失败原因。
        if (!provider.hasUsage()) {
            return new ProviderDisplay(failure, "", "");
        }

        String unit = provider.unit == null
                ? "GB"
                : provider.unit.toUpperCase(Locale.US);
        String remaining = provider.unlimited
                ? "UNLIMITED"
                : formatNumber(provider.remainingGb) + " " + unit + " LEFT";

        // 明细行分三种情况：超量了优先显示超了多少，其次显示"已用 / 总量"，
        // 数据不全就留空（留空比显示 "-- / --" 干净）。
        String detail;
        if (provider.overGb != null && provider.overGb > 0) {
            detail = "OVER " + formatNumber(provider.overGb) + " " + unit;
        } else if (provider.usedGb != null && provider.totalGb != null) {
            detail = formatNumber(provider.usedGb) + " / "
                    + formatNumber(provider.totalGb) + " " + unit;
        } else {
            detail = "";
        }

        // 第三行：一切正常就显示日期，否则把失败原因摆在这儿。
        // 这种情况出现在"有旧缓存数据可显示、但本次刷新失败了"——主数字还是旧值，
        // 但第三行会说明数据为什么是旧的。
        String schedule = hostvds
                ? formatDate("RESET", provider.resetAt)
                : formatDate("EXPIRES", provider.expireAt);
        String meta = "ok".equals(provider.status) ? schedule : failure;
        return new ProviderDisplay(remaining, detail, meta);
    }

    /**
     * 把服务端的状态码翻译成人能看懂的短提示，并且是可操作的——
     * 比如 COOKIE EXPIRED 就明确告诉用户该去 VPS 上重新设置 Cookie 了。
     */
    private static String failureLabel(String status, boolean stale) {
        String label;
        switch (status == null ? "" : status) {
            case "auth_expired":
                label = "COOKIE EXPIRED";
                break;
            case "not_configured":
                label = "COOKIE REQUIRED";
                break;
            case "missing_userinfo":
            case "invalid_response":
                label = "DATA INVALID";
                break;
            default:
                label = "OFFLINE";
                break;
        }
        return stale ? "STALE · " + label : label;
    }

    /** 给一行文字打上 STALE 前缀，已经打过的不重复打。 */
    private static String appendStale(String meta) {
        if (meta == null || meta.isEmpty()) {
            return "STALE";
        }
        return meta.startsWith("STALE") ? meta : "STALE · " + meta;
    }

    /**
     * 把 ISO 日期变成 "RESET AUG 20" 这样。
     * 解析失败返回空串——日期只是锦上添花，格式不对时宁可不显示这一行，
     * 也不要因为一个次要字段让整屏报错。
     */
    private static String formatDate(String prefix, String isoDate) {
        if (isoDate == null) {
            return "";
        }
        try {
            return prefix + " " + MONTH_DAY.format(LocalDate.parse(isoDate)).toUpperCase(Locale.US);
        } catch (DateTimeParseException ignored) {
            return "";
        }
    }

    /** 把服务端的 UTC 时间戳转成本机时区的 "UPDATED 20:15"。 */
    private static String formatUpdatedAt(String updatedAt) {
        if (updatedAt == null) {
            return "UPDATED RECENTLY";
        }
        try {
            return "UPDATED " + CLOCK_TIME.format(
                    Instant.parse(updatedAt).atZone(ZoneId.systemDefault()));
        } catch (DateTimeParseException ignored) {
            return "UPDATED RECENTLY";
        }
    }

    /**
     * 数字格式化：整数不带小数点，其余保留两位。
     *
     * <p>那个 0.0005 的判断是在做"四舍五入到两位后是否恰好为整数"的检测——
     * 比如 1000.0001 应该显示成 "1000" 而不是 "1000.00"，看着更干净。
     * 直接比较 value == Math.rint(value) 会因为浮点误差漏掉这类情况。
     */
    private static String formatNumber(Double value) {
        if (value == null) {
            return "--";
        }
        if (Math.abs(value - Math.rint(value)) < 0.0005d) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.2f", value);
    }
}
