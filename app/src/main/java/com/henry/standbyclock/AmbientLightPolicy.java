package com.henry.standbyclock;

/** Pure hysteresis and confirmation rules for ambient-light display automation. */
final class AmbientLightPolicy {
    static final float DARK_THRESHOLD_LUX = 3f;
    static final float BRIGHT_THRESHOLD_LUX = 15f;
    static final long DARK_CONFIRMATION_MS = 20_000L;
    static final long BRIGHT_CONFIRMATION_MS = 3_000L;

    enum Change {
        NONE,
        BLACKOUT,
        SHOW
    }

    private enum Zone {
        DARK,
        INTERMEDIATE,
        BRIGHT
    }

    private boolean blackout;
    private Zone currentZone = Zone.INTERMEDIATE;
    private long candidateSinceMs = -1L;

    Change updateLux(float lux, long nowMs) {
        if (!Float.isFinite(lux) || lux < 0f) {
            return Change.NONE;
        }

        Zone nextZone;
        if (lux <= DARK_THRESHOLD_LUX) {
            nextZone = Zone.DARK;
        } else if (lux >= BRIGHT_THRESHOLD_LUX) {
            nextZone = Zone.BRIGHT;
        } else {
            nextZone = Zone.INTERMEDIATE;
        }

        if (nextZone != currentZone) {
            currentZone = nextZone;
            candidateSinceMs = nextZone == Zone.INTERMEDIATE ? -1L : nowMs;
        }
        return evaluate(nowMs);
    }

    Change evaluate(long nowMs) {
        if (candidateSinceMs < 0L) {
            return Change.NONE;
        }
        if (!blackout && currentZone == Zone.DARK
                && nowMs - candidateSinceMs >= DARK_CONFIRMATION_MS) {
            blackout = true;
            candidateSinceMs = -1L;
            return Change.BLACKOUT;
        }
        if (blackout && currentZone == Zone.BRIGHT
                && nowMs - candidateSinceMs >= BRIGHT_CONFIRMATION_MS) {
            blackout = false;
            candidateSinceMs = -1L;
            return Change.SHOW;
        }
        if ((!blackout && currentZone == Zone.BRIGHT)
                || (blackout && currentZone == Zone.DARK)) {
            candidateSinceMs = -1L;
        }
        return Change.NONE;
    }

    long nextEvaluationDelayMs(long nowMs) {
        if (candidateSinceMs < 0L) {
            return -1L;
        }
        long confirmationMs;
        if (!blackout && currentZone == Zone.DARK) {
            confirmationMs = DARK_CONFIRMATION_MS;
        } else if (blackout && currentZone == Zone.BRIGHT) {
            confirmationMs = BRIGHT_CONFIRMATION_MS;
        } else {
            return -1L;
        }
        return Math.max(1L, confirmationMs - (nowMs - candidateSinceMs));
    }

    boolean isBlackout() {
        return blackout;
    }
}
