package com.henry.standbyclock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yeelight 局域网控制协议的同步客户端（只用到查询开关状态和翻转开关两个功能）。
 *
 * <p>协议本身很简单：连到灯的 TCP 端口（默认 55443），发一行 JSON-RPC 请求，
 * 读一行 JSON 响应。请求必须以 \r\n 结尾，灯才认。整个过程走局域网直连，
 * 不经过小米云，所以断网也能用、延迟也低。前提是灯的"局域网控制"开关要在
 * 米家 App 里手动打开。
 *
 * <p>所有方法都是阻塞的，调用方必须放到子线程（MainActivity 里用的是单线程
 * ExecutorService）。这里没有引入 JSON 库，响应用正则直接匹配——协议返回的结构
 * 极其固定，为这两个命令引一个解析库不划算。
 */
final class YeelightClient {
    /** 连接和读取都用 3 秒超时。灯在局域网内，超过 3 秒基本就是掉线了。 */
    private static final int TIMEOUT_MS = 3_000;
    /** 匹配 {"id":1,"result":["on"]} 里的 on / off。 */
    private static final Pattern POWER_RESULT = Pattern.compile(
            "\\\"result\\\"\\s*:\\s*\\[\\s*\\\"(on|off)\\\"");
    /** 匹配命令被接受时返回的 {"result":["ok"]}。 */
    private static final Pattern OK_RESULT = Pattern.compile(
            "\\\"result\\\"\\s*:\\s*\\[\\s*\\\"ok\\\"");

    enum PowerState {
        ON,
        OFF
    }

    private final String host;
    private final int port;
    /** 请求序号，协议要求每个请求带唯一 id。用原子类是因为可能被多个线程调用。 */
    private final AtomicInteger nextRequestId = new AtomicInteger(1);

    YeelightClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** 查询灯当前的开关状态。 */
    PowerState getPower() throws IOException {
        return parsePowerResponse(request("get_prop", "[\"power\"]"));
    }

    /**
     * 翻转开关，并返回翻转后的实际状态。
     *
     * <p>为什么翻转完还要再查一次：toggle 命令只回 "ok"，不告诉你现在是开是关。
     * 与其在本地猜（可能和实际不同步，比如有人同时用墙上开关按了一下），
     * 不如多花一次往返读回真实状态，保证界面显示的一定是灯的真实状态。
     */
    PowerState toggle() throws IOException {
        String response = request("toggle", "[]");
        if (!OK_RESULT.matcher(response).find()) {
            throw new IOException("Yeelight rejected toggle: " + response);
        }
        return getPower();
    }

    /** 建连、发一行请求、读一行响应，然后关连接。每次调用都是一条新连接。 */
    private String request(String method, String paramsJson) throws IOException {
        int requestId = nextRequestId.getAndIncrement();
        String payload = buildRequest(requestId, method, paramsJson);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            // connect 的超时只管建连；这个管建连之后的读取，两个都要设。
            socket.setSoTimeout(TIMEOUT_MS);

            OutputStreamWriter writer = new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(payload);
            writer.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String response = reader.readLine();
            if (response == null || response.isEmpty()) {
                throw new IOException("Yeelight returned no response");
            }
            // 灯拒绝命令时回的是 {"error":{...}} 而不是 result，要单独识别，
            // 否则下面的正则匹配不到会报一个误导性的"格式非法"。
            if (response.contains("\"error\"")) {
                throw new IOException("Yeelight error: " + response);
            }
            return response;
        }
    }

    /**
     * 拼一条 JSON-RPC 请求。
     *
     * <p>结尾的 \r\n 是协议强制要求的分隔符，少了灯不会响应。用 Locale.US 是防止
     * 在某些区域设置下数字格式化出非 ASCII 字符（比如阿拉伯数字变体）。
     */
    static String buildRequest(int id, String method, String paramsJson) {
        return String.format(
                Locale.US,
                "{\"id\":%d,\"method\":\"%s\",\"params\":%s}\r\n",
                id,
                method,
                paramsJson);
    }

    /** 从响应里提取开关状态，匹配不到说明返回格式不对，直接当失败处理。 */
    static PowerState parsePowerResponse(String response) throws IOException {
        Matcher matcher = POWER_RESULT.matcher(response);
        if (!matcher.find()) {
            throw new IOException("Invalid Yeelight power response: " + response);
        }
        return "on".equals(matcher.group(1)) ? PowerState.ON : PowerState.OFF;
    }
}
