package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeviceMenuMotionTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void leftOpeningMovesClockLeftAndMenuInFromRight() {
        DeviceMenuMotion.Frame frame = DeviceMenuMotion.resolve(true, true, 0.25f);

        assertTrue(frame.clockOffsetFactor < 0f);
        assertTrue(frame.menuOffsetFactor > 0f);
        assertEquals(0.75f, frame.clockAlpha, EPSILON);
        assertEquals(0.25f, frame.menuAlpha, EPSILON);
    }

    @Test
    public void rightOpeningMirrorsHorizontalMotion() {
        DeviceMenuMotion.Frame frame = DeviceMenuMotion.resolve(true, false, 0.25f);

        assertTrue(frame.clockOffsetFactor > 0f);
        assertTrue(frame.menuOffsetFactor < 0f);
    }

    @Test
    public void closingMakesMenuOutgoingAndClockIncoming() {
        DeviceMenuMotion.Frame frame = DeviceMenuMotion.resolve(false, true, 0.75f);

        assertTrue(frame.menuOffsetFactor < 0f);
        assertTrue(frame.clockOffsetFactor > 0f);
        assertEquals(0.75f, frame.clockAlpha, EPSILON);
        assertEquals(0.25f, frame.menuAlpha, EPSILON);
    }
}
