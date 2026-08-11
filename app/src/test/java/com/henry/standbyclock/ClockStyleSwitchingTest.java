package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClockStyleSwitchingTest {
    @Test
    public void upwardSwipeSelectsNextStyle() {
        assertEquals(
                ClockStyleSwitching.SwipeDirection.NEXT,
                ClockStyleSwitching.resolveSwipe(8f, -120f, 80f));
    }

    @Test
    public void downwardSwipeSelectsPreviousStyle() {
        assertEquals(
                ClockStyleSwitching.SwipeDirection.PREVIOUS,
                ClockStyleSwitching.resolveSwipe(-8f, 120f, 80f));
    }

    @Test
    public void shortOrMostlyHorizontalMovementIsIgnored() {
        assertEquals(
                ClockStyleSwitching.SwipeDirection.NONE,
                ClockStyleSwitching.resolveSwipe(0f, -79f, 80f));
        assertEquals(
                ClockStyleSwitching.SwipeDirection.NONE,
                ClockStyleSwitching.resolveSwipe(100f, -100f, 80f));
    }

    @Test
    public void horizontalSwipesAreDetectedInBothDirections() {
        assertEquals(
                ClockStyleSwitching.HorizontalSwipeDirection.LEFT,
                ClockStyleSwitching.resolveHorizontalSwipe(-120f, 8f, 80f));
        assertEquals(
                ClockStyleSwitching.HorizontalSwipeDirection.RIGHT,
                ClockStyleSwitching.resolveHorizontalSwipe(120f, -8f, 80f));
    }

    @Test
    public void shortOrMostlyVerticalMovementIsNotAHorizontalSwipe() {
        assertEquals(
                ClockStyleSwitching.HorizontalSwipeDirection.NONE,
                ClockStyleSwitching.resolveHorizontalSwipe(79f, 0f, 80f));
        assertEquals(
                ClockStyleSwitching.HorizontalSwipeDirection.NONE,
                ClockStyleSwitching.resolveHorizontalSwipe(100f, -100f, 80f));
    }

    @Test
    public void automaticSwitchRunsOneHourAfterCountdownReset() {
        long nowMs = 1_000_000L;
        assertEquals(
                nowMs + 60L * 60L * 1000L,
                ClockStyleSwitching.nextAutoSwitchAt(nowMs));
    }

    @Test
    public void dueTimeIncludesExactBoundary() {
        long nextSwitchAtMs = 5_000L;
        assertFalse(ClockStyleSwitching.isDue(nextSwitchAtMs, 4_999L));
        assertTrue(ClockStyleSwitching.isDue(nextSwitchAtMs, 5_000L));
        assertTrue(ClockStyleSwitching.isDue(nextSwitchAtMs, 9_000L));
    }

    @Test
    public void onlyFutureTimesWithinOneIntervalAreReused() {
        long nowMs = 10_000L;
        assertTrue(ClockStyleSwitching.isUsableFutureTime(
                nowMs + ClockStyleSwitching.AUTO_SWITCH_INTERVAL_MS, nowMs));
        assertFalse(ClockStyleSwitching.isUsableFutureTime(nowMs, nowMs));
        assertFalse(ClockStyleSwitching.isUsableFutureTime(
                nowMs + ClockStyleSwitching.AUTO_SWITCH_INTERVAL_MS + 1L, nowMs));
    }
}
