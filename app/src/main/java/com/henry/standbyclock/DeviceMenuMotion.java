package com.henry.standbyclock;

/** Pure direction and opacity rules for the device-menu transition. */
final class DeviceMenuMotion {
    static final class Frame {
        final float clockOffsetFactor;
        final float menuOffsetFactor;
        final float clockAlpha;
        final float menuAlpha;

        Frame(
                float clockOffsetFactor,
                float menuOffsetFactor,
                float clockAlpha,
                float menuAlpha) {
            this.clockOffsetFactor = clockOffsetFactor;
            this.menuOffsetFactor = menuOffsetFactor;
            this.clockAlpha = clockAlpha;
            this.menuAlpha = menuAlpha;
        }
    }

    private DeviceMenuMotion() {
    }

    static Frame resolve(boolean opening, boolean swipeLeft, float easedProgress) {
        float progress = Math.max(0f, Math.min(1f, easedProgress));
        float outgoingDirection = swipeLeft ? -1f : 1f;
        if (opening) {
            return new Frame(
                    outgoingDirection * progress,
                    -outgoingDirection * (1f - progress),
                    1f - progress,
                    progress);
        }
        return new Frame(
                -outgoingDirection * (1f - progress),
                outgoingDirection * progress,
                progress,
                1f - progress);
    }
}
