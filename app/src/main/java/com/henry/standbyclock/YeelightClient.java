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

/** Small synchronous client for the Yeelight LAN TCP protocol. */
final class YeelightClient {
    private static final int TIMEOUT_MS = 3_000;
    private static final Pattern POWER_RESULT = Pattern.compile(
            "\\\"result\\\"\\s*:\\s*\\[\\s*\\\"(on|off)\\\"");
    private static final Pattern OK_RESULT = Pattern.compile(
            "\\\"result\\\"\\s*:\\s*\\[\\s*\\\"ok\\\"");

    enum PowerState {
        ON,
        OFF
    }

    private final String host;
    private final int port;
    private final AtomicInteger nextRequestId = new AtomicInteger(1);

    YeelightClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    PowerState getPower() throws IOException {
        return parsePowerResponse(request("get_prop", "[\"power\"]"));
    }

    PowerState toggle() throws IOException {
        String response = request("toggle", "[]");
        if (!OK_RESULT.matcher(response).find()) {
            throw new IOException("Yeelight rejected toggle: " + response);
        }
        return getPower();
    }

    private String request(String method, String paramsJson) throws IOException {
        int requestId = nextRequestId.getAndIncrement();
        String payload = buildRequest(requestId, method, paramsJson);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
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
            if (response.contains("\"error\"")) {
                throw new IOException("Yeelight error: " + response);
            }
            return response;
        }
    }

    static String buildRequest(int id, String method, String paramsJson) {
        return String.format(
                Locale.US,
                "{\"id\":%d,\"method\":\"%s\",\"params\":%s}\r\n",
                id,
                method,
                paramsJson);
    }

    static PowerState parsePowerResponse(String response) throws IOException {
        Matcher matcher = POWER_RESULT.matcher(response);
        if (!matcher.find()) {
            throw new IOException("Invalid Yeelight power response: " + response);
        }
        return "on".equals(matcher.group(1)) ? PowerState.ON : PowerState.OFF;
    }
}
