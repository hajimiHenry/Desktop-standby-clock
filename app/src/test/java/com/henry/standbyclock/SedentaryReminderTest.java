package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalTime;

/** 久坐提醒规则的测试，覆盖启用/禁用、间隔判定、工作时段和提示音逻辑。 */
public final class SedentaryReminderTest {
    /** 默认 45 分钟间隔的毫秒数。 */
    private static final long INTERVAL_MS = 45L * 60L * 1_000L;
    /** 测试用的工作时段：09:00 - 23:30。 */
    private static final int START_H = 9, START_M = 0;
    private static final int END_H = 23, END_M = 30;

    /** 在工作时段内、过了间隔，应该激活。 */
    @Test
    public void activatesAfterInterval() {
        long now = 100_000_000L;
        assertTrue(SedentaryReminder.shouldActivate(
                LocalTime.of(14, 0),
                true, false, now - INTERVAL_MS, now, INTERVAL_MS,
                START_H, START_M, END_H, END_M));
    }

    /** 差一毫秒还不到间隔，不该激活。 */
    @Test
    public void doesNotActivateBeforeInterval() {
        long now = 100_000_000L;
        assertFalse(SedentaryReminder.shouldActivate(
                LocalTime.of(14, 0),
                true, false, now - INTERVAL_MS + 1, now, INTERVAL_MS,
                START_H, START_M, END_H, END_M));
    }

    /** 功能关着，到了时间也不激活。 */
    @Test
    public void disabledDoesNotActivate() {
        long now = 100_000_000L;
        assertFalse(SedentaryReminder.shouldActivate(
                LocalTime.of(14, 0),
                false, false, now - INTERVAL_MS, now, INTERVAL_MS,
                START_H, START_M, END_H, END_M));
    }

    /** 提醒已经在显示了，不要重复激活。 */
    @Test
    public void alreadyActiveDoesNotReactivate() {
        long now = 100_000_000L;
        assertFalse(SedentaryReminder.shouldActivate(
                LocalTime.of(14, 0),
                true, true, now - INTERVAL_MS, now, INTERVAL_MS,
                START_H, START_M, END_H, END_M));
    }

    /** 不在工作时段内（太早），不激活。 */
    @Test
    public void doesNotActivateBeforeStartTime() {
        long now = 100_000_000L;
        assertFalse(SedentaryReminder.shouldActivate(
                LocalTime.of(8, 59),
                true, false, now - INTERVAL_MS, now, INTERVAL_MS,
                START_H, START_M, END_H, END_M));
    }

    /** 不在工作时段内（过了结束时间），不激活。 */
    @Test
    public void doesNotActivateAfterEndTime() {
        long now = 100_000_000L;
        assertFalse(SedentaryReminder.shouldActivate(
                LocalTime.of(23, 30),
                true, false, now - INTERVAL_MS, now, INTERVAL_MS,
                START_H, START_M, END_H, END_M));
    }

    // --- 工作时段判定 ---

    /** 不跨午夜：09:00 - 23:30，14:00 在范围内。 */
    @Test
    public void withinActiveHoursNormal() {
        assertTrue(SedentaryReminder.isWithinActiveHours(
                LocalTime.of(14, 0), 9, 0, 23, 30));
    }

    /** 不跨午夜：09:00 - 23:30，08:59 不在范围内。 */
    @Test
    public void beforeActiveHoursNormal() {
        assertFalse(SedentaryReminder.isWithinActiveHours(
                LocalTime.of(8, 59), 9, 0, 23, 30));
    }

    /** 跨午夜：09:00 - 01:00，23:30 在范围内。 */
    @Test
    public void withinActiveHoursCrossMidnight() {
        assertTrue(SedentaryReminder.isWithinActiveHours(
                LocalTime.of(23, 30), 9, 0, 1, 0));
    }

    /** 跨午夜：09:00 - 01:00，00:30 在范围内。 */
    @Test
    public void withinActiveHoursAfterMidnight() {
        assertTrue(SedentaryReminder.isWithinActiveHours(
                LocalTime.of(0, 30), 9, 0, 1, 0));
    }

    /** 跨午夜：09:00 - 01:00，05:00 不在范围内。 */
    @Test
    public void outsideActiveHoursCrossMidnight() {
        assertFalse(SedentaryReminder.isWithinActiveHours(
                LocalTime.of(5, 0), 9, 0, 1, 0));
    }

    /** 起止相同时无有效时段。 */
    @Test
    public void sameStartEndMeansNoActiveHours() {
        assertFalse(SedentaryReminder.isWithinActiveHours(
                LocalTime.of(9, 0), 9, 0, 9, 0));
    }

    // --- 间隔钳位 ---

    @Test
    public void clampIntervalWithinRange() {
        assertEquals(45, SedentaryReminder.clampInterval(45));
    }

    @Test
    public void clampIntervalBelowMin() {
        assertEquals(SedentaryReminder.MIN_INTERVAL_MINUTES,
                SedentaryReminder.clampInterval(10));
    }

    @Test
    public void clampIntervalAboveMax() {
        assertEquals(SedentaryReminder.MAX_INTERVAL_MINUTES,
                SedentaryReminder.clampInterval(200));
    }

    // --- 提示音 ---

    /** 提示音：四个条件全满足才播放。 */
    @Test
    public void soundPlaysWhenAllConditionsMet() {
        assertTrue(SedentaryReminder.shouldPlaySound(true, true, 0, 1_000L, 1_000L));
    }

    /** 提示音：任一条件不满足就不播。 */
    @Test
    public void soundDoesNotPlayWhenDisabled() {
        assertFalse(SedentaryReminder.shouldPlaySound(false, true, 0, 1_000L, 1_000L));
    }

    @Test
    public void soundDoesNotPlayWhenInactive() {
        assertFalse(SedentaryReminder.shouldPlaySound(true, false, 0, 1_000L, 1_000L));
    }

    @Test
    public void soundDoesNotPlayBeforeScheduledTime() {
        assertFalse(SedentaryReminder.shouldPlaySound(true, true, 0, 1_001L, 1_000L));
    }

    /** 响满两次就停。 */
    @Test
    public void soundStopsAfterMaxPlays() {
        assertTrue(SedentaryReminder.shouldPlaySound(true, true, 1, 1_000L, 2_000L));
        assertFalse(SedentaryReminder.shouldPlaySound(true, true, 2, 1_000L, 2_000L));
    }
}
