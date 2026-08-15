package com.henry.standbyclock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

final class TrafficStatusFormatting {
    private static final DateTimeFormatter MONTH_DAY =
            DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter CLOCK_TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.US);

    static final class Display {
        final String hostRemaining;
        final String hostDetail;
        final String hostMeta;
        final String sanmaoRemaining;
        final String sanmaoDetail;
        final String sanmaoMeta;
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

        static Display initial() {
            return new Display(
                    "NOT LOADED", "", "", "NOT LOADED", "", "", "OPEN TO REFRESH");
        }

        static Display loading() {
            return new Display(
                    "CHECKING...", "", "", "CHECKING...", "", "", "CONTACTING VPS");
        }

        static Display notConfigured() {
            return new Display(
                    "NOT CONFIGURED", "", "",
                    "NOT CONFIGURED", "", "",
                    "COPY STANDBY-CLOCK.PROPERTIES.EXAMPLE");
        }
    }

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

    private static boolean isPlaceholder(String value) {
        return "NOT LOADED".equals(value) || "CHECKING...".equals(value);
    }

    private static ProviderDisplay formatProvider(
            TrafficStatusClient.Provider provider, boolean hostvds) {
        String failure = failureLabel(provider.status, provider.stale);
        if (!provider.hasUsage()) {
            return new ProviderDisplay(failure, "", "");
        }

        String unit = provider.unit == null
                ? "GB"
                : provider.unit.toUpperCase(Locale.US);
        String remaining = provider.unlimited
                ? "UNLIMITED"
                : formatNumber(provider.remainingGb) + " " + unit + " LEFT";
        String detail;
        if (provider.overGb != null && provider.overGb > 0) {
            detail = "OVER " + formatNumber(provider.overGb) + " " + unit;
        } else if (provider.usedGb != null && provider.totalGb != null) {
            detail = formatNumber(provider.usedGb) + " / "
                    + formatNumber(provider.totalGb) + " " + unit;
        } else {
            detail = "";
        }

        String schedule = hostvds
                ? formatDate("RESET", provider.resetAt)
                : formatDate("EXPIRES", provider.expireAt);
        String meta = "ok".equals(provider.status) ? schedule : failure;
        return new ProviderDisplay(remaining, detail, meta);
    }

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

    private static String appendStale(String meta) {
        if (meta == null || meta.isEmpty()) {
            return "STALE";
        }
        return meta.startsWith("STALE") ? meta : "STALE · " + meta;
    }

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
