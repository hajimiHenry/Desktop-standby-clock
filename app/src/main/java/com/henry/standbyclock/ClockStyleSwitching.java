package com.henry.standbyclock;

/** Pure switching rules shared by the view, activity, and local unit tests. */
final class ClockStyleSwitching {
    static final long AUTO_SWITCH_INTERVAL_MS = 60L * 60L * 1000L;
    private static final float VERTICAL_DOMINANCE_RATIO = 1.25f;

    enum SwipeDirection {
        NONE,
        PREVIOUS,
        NEXT
    }

    enum HorizontalSwipeDirection {
        NONE,
        LEFT,
        RIGHT
    }

    private ClockStyleSwitching() {
    }

    static SwipeDirection resolveSwipe(float deltaX, float deltaY, float minimumDistance) {
        float verticalDistance = Math.abs(deltaY);
        if (verticalDistance < minimumDistance
                || verticalDistance <= Math.abs(deltaX) * VERTICAL_DOMINANCE_RATIO) {
            return SwipeDirection.NONE;
        }
        return deltaY < 0f ? SwipeDirection.NEXT : SwipeDirection.PREVIOUS;
    }

    static HorizontalSwipeDirection resolveHorizontalSwipe(
            float deltaX, float deltaY, float minimumDistance) {
        float horizontalDistance = Math.abs(deltaX);
        if (horizontalDistance < minimumDistance
                || horizontalDistance <= Math.abs(deltaY) * VERTICAL_DOMINANCE_RATIO) {
            return HorizontalSwipeDirection.NONE;
        }
        return deltaX < 0f ? HorizontalSwipeDirection.LEFT : HorizontalSwipeDirection.RIGHT;
    }

    static boolean opensTrafficDashboard(HorizontalSwipeDirection direction) {
        return direction == HorizontalSwipeDirection.LEFT;
    }

    static long nextAutoSwitchAt(long nowMs) {
        return nowMs + AUTO_SWITCH_INTERVAL_MS;
    }

    static boolean isDue(long nextSwitchAtMs, long nowMs) {
        return nextSwitchAtMs > 0L && nowMs >= nextSwitchAtMs;
    }

    static boolean isUsableFutureTime(long nextSwitchAtMs, long nowMs) {
        return nextSwitchAtMs > nowMs
                && nextSwitchAtMs - nowMs <= AUTO_SWITCH_INTERVAL_MS;
    }
}
