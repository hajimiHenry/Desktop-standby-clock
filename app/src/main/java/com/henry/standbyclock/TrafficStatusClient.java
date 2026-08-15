package com.henry.standbyclock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class TrafficStatusClient {
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    static final class Provider {
        final String status;
        final boolean stale;
        final boolean unlimited;
        final String unit;
        final Double usedGb;
        final Double totalGb;
        final Double remainingGb;
        final Double overGb;
        final String resetAt;
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

        boolean hasUsage() {
            return unlimited || remainingGb != null;
        }
    }

    static final class Snapshot {
        final Provider hostvds;
        final Provider sanmao;
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

    Snapshot fetch() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
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
            connection.disconnect();
        }
    }

    static Snapshot parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject providers = root.getJSONObject("providers");
        return new Snapshot(
                parseProvider(providers.optJSONObject("hostvds")),
                parseProvider(providers.optJSONObject("sanmao")),
                nullableString(root, "updated_at"));
    }

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

    private static Double nullableDouble(JSONObject object, String key) throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.getDouble(key);
    }

    private static String nullableString(JSONObject object, String key) throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        String value = object.getString(key).trim();
        return value.isEmpty() ? null : value;
    }

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
