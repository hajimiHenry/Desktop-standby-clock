package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 手势判定和自动轮换计时的测试。
 *
 * <p>参数含义统一是 (水平位移, 竖直位移, 最小距离)，坐标系向下为正，
 * 所以负的竖直位移 = 手指往上划。
 */
public final class ClockStyleSwitchingTest {
    /** 上划（deltaY = -120）切下一个表盘。 */
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

    /**
     * 两种该被忽略的情况：
     * 一是位移不够长（79 差 1 像素没到门槛 80）；
     * 二是斜着划——竖直 100 没超过水平 100 的 1.25 倍，方向不够明确，宁可不响应。
     */
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

    /** 左划开流量看板，右划开设备控制，这个映射固定下来。 */
    @Test
    public void leftSwipeOpensTrafficAndRightSwipeOpensDeviceControls() {
        assertTrue(ClockStyleSwitching.opensTrafficDashboard(
                ClockStyleSwitching.HorizontalSwipeDirection.LEFT));
        assertFalse(ClockStyleSwitching.opensTrafficDashboard(
                ClockStyleSwitching.HorizontalSwipeDirection.RIGHT));
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

    /** 重置倒计时后，下次切换时间应当正好是一小时后。 */
    @Test
    public void automaticSwitchRunsOneHourAfterCountdownReset() {
        long nowMs = 1_000_000L;
        assertEquals(
                nowMs + 60L * 60L * 1000L,
                ClockStyleSwitching.nextAutoSwitchAt(nowMs));
    }

    /** 到点判定包含边界本身：差 1 毫秒不算到，正好相等就算到。 */
    @Test
    public void dueTimeIncludesExactBoundary() {
        long nextSwitchAtMs = 5_000L;
        assertFalse(ClockStyleSwitching.isDue(nextSwitchAtMs, 4_999L));
        assertTrue(ClockStyleSwitching.isDue(nextSwitchAtMs, 5_000L));
        assertTrue(ClockStyleSwitching.isDue(nextSwitchAtMs, 9_000L));
    }

    /**
     * 存下来的时间戳只在"未来且不超过一个完整间隔"时才复用。
     * 超出上界那一条是防系统时钟被改乱后，倒计时永远等不到头。
     */
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
