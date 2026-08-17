package com.henry.standbyclock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 从自建 VPS 的流量查询服务拉取剩余流量，并把 JSON 解析成 Java 对象。
 *
 * <p>为什么中间要隔一层 VPS：查 HostVDS 余量需要账号 Cookie，查机场订阅需要订阅链接，
 * 这两样都是敏感凭证，不能塞进 APK（反编译就能拿到）。所以凭证只放在 VPS 上，
 * 手机这端只访问一个自己的只读接口。服务端实现见 vps/traffic-status/app.py。
 *
 * <p>本类只负责"取数据 + 解析"，把数字变成屏幕上的文字是 TrafficStatusFormatting 的活。
 * 网络请求是阻塞的，必须在子线程调用。
 */
final class TrafficStatusClient {
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    /** 读超时给得比连接超时长：服务端可能正在回源查上游，慢一些是正常的。 */
    private static final int READ_TIMEOUT_MS = 12_000;
    /**
     * 响应体大小上限 64KB。正常响应只有几百字节，设上限是为了防御性——万一地址被
     * 改指到一个巨大的文件，不至于把内存吃满。
     */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /**
     * 单个流量来源的状态快照，字段和服务端 JSON 一一对应。
     *
     * <p>数值类型用 Double 而不是 double：服务端在拿不到数据时会传 null
     * （比如无限流量套餐就没有"剩余"这一说），需要能区分"是 0"和"没有值"。
     */
    static final class Provider {
        /** 服务端给的状态码：ok / auth_expired / not_configured / upstream_error 等。 */
        final String status;
        /** true 表示这次没取到新数据，展示的是上次成功的缓存值。 */
        final boolean stale;
        /** 是否为无限流量套餐，是的话 total / remaining 都无意义。 */
        final boolean unlimited;
        /** 单位字符串，HostVDS 用 GB（1000 进制），机场用 GiB（1024 进制），不能混。 */
        final String unit;
        final Double usedGb;
        final Double totalGb;
        final Double remainingGb;
        /** 超出套餐的部分。用完了才会大于 0，此时 remaining 是 0。 */
        final Double overGb;
        /** HostVDS 的下次流量重置日期（ISO 格式）。 */
        final String resetAt;
        /** 机场订阅的到期日期（ISO 格式）。 */
        final String expireAt;

        Provider(
                String status,
                boolean stale,
                boolean unlimited,
                String unit,
                Double usedGb,
                Double totalGb,
                Double remainingGb,
                Double overGb,
                String resetAt,
                String expireAt) {
            this.status = status;
            this.stale = stale;
            this.unlimited = unlimited;
            this.unit = unit;
            this.usedGb = usedGb;
            this.totalGb = totalGb;
            this.remainingGb = remainingGb;
            this.overGb = overGb;
            this.resetAt = resetAt;
            this.expireAt = expireAt;
        }

        /**
         * 有没有可展示的用量数据。无限套餐算有（显示 UNLIMITED），
         * 拿不到剩余量则算没有，界面会改为显示错误状态。
         */
        boolean hasUsage() {
            return unlimited || remainingGb != null;
        }
    }

    /** 一次查询的完整结果：两个来源 + 服务端的数据更新时间。 */
    static final class Snapshot {
        final Provider hostvds;
        final Provider sanmao;
        /** 服务端生成这份数据的 UTC 时间戳（ISO 8601）。 */
        final String updatedAt;

        Snapshot(Provider hostvds, Provider sanmao, String updatedAt) {
            this.hostvds = hostvds;
            this.sanmao = sanmao;
            this.updatedAt = updatedAt;
        }
    }

    private final String endpoint;

    TrafficStatusClient(String endpoint) {
        this.endpoint = endpoint;
    }

    /** 发起一次 GET 请求并解析结果。阻塞方法，只能在子线程调用。 */
    Snapshot fetch() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        // 关掉缓存：要的就是最新余量，拿到旧数据没意义。
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Traffic endpoint returned HTTP " + responseCode);
            }
            try (InputStream inputStream = connection.getInputStream()) {
                return parse(readLimited(inputStream));
            }
        } finally {
            // 无论成功失败都要断开，否则连接会滞留在连接池里。
            connection.disconnect();
        }
    }

    /** 解析服务端 JSON。拆成独立静态方法是为了能用固定字符串直接单测。 */
    static Snapshot parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject providers = root.getJSONObject("providers");
        return new Snapshot(
                parseProvider(providers.optJSONObject("hostvds")),
                parseProvider(providers.optJSONObject("sanmao")),
                nullableString(root, "updated_at"));
    }

    /**
     * 解析单个来源。整段用 optXxx 而非 getXxx，是为了容忍服务端少给某些字段
     * （比如无限套餐没有 total_gb），少一个字段不该让整次解析失败。
     *
     * @param provider 为 null 表示服务端连这个来源都没返回，构造一个"不可用"占位
     */
    private static Provider parseProvider(JSONObject provider) throws JSONException {
        if (provider == null) {
            return new Provider(
                    "unavailable", false, false, "GB",
                    null, null, null, null, null, null);
        }
        return new Provider(
                provider.optString("status", "unavailable"),
                provider.optBoolean("stale", false),
                provider.optBoolean("unlimited", false),
                provider.optString("unit", "GB"),
                nullableDouble(provider, "used_gb"),
                nullableDouble(provider, "total_gb"),
                nullableDouble(provider, "remaining_gb"),
                nullableDouble(provider, "over_gb"),
                nullableString(provider, "reset_at"),
                nullableString(provider, "expire_at"));
    }

    /**
     * 读一个可能不存在的数字字段。
     *
     * <p>要同时判 has 和 isNull：JSON 里的 null 和"键不存在"在 org.json 里是两回事，
     * 只查 has 的话遇到显式 null 会拿到 JSONObject.NULL 而不是 Java 的 null。
     */
    private static Double nullableDouble(JSONObject object, String key) throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.getDouble(key);
    }

    /** 同上，字符串版。顺带把空白串也统一归一成 null，省得上层到处判空串。 */
    private static String nullableString(JSONObject object, String key) throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        String value = object.getString(key).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 带大小上限地读完整个响应体。
     *
     * <p>边读边累加计数，一超限就抛异常并停止读取，这样即便对面无限往回灌数据
     * 也不会把内存撑爆。
     */
    private static String readLimited(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("Traffic endpoint response is too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
