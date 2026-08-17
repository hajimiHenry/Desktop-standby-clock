package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;

/** 睡眠提醒规则的测试，重点在各种时间边界和"同一天不重复提醒"。 */
public final class BedtimeScheduleTest {
    /** 就寝时间设为 23:30，这两个常量分别是它的前一分钟和正好那一刻。 */
    private static final LocalDateTime BEFORE = LocalDateTime.of(2026, 8, 5, 23, 29);
    private static final LocalDateTime AT_BEDTIME = LocalDateTime.of(2026, 8, 5, 23, 30);

    /** 差一分钟不弹，到点就弹。 */
    @Test
    public void activatesAtConfiguredTime() {
        assertFalse(BedtimeSchedule.shouldActivate(BEFORE, true, false, "", 23, 30));
        assertTrue(BedtimeSchedule.shouldActivate(AT_BEDTIME, true, false, "", 23, 30));
    }

    /** 今天已经点过 DONE 了（已确认日期 = 当天），当天就不该再弹第二次。 */
    @Test
    public void acknowledgedDateDoesNotReactivate() {
        assertFalse(BedtimeSchedule.shouldActivate(
                AT_BEDTIME, true, false, "2026-08-05", 23, 30));
    }

    /** 功能关着、或提醒已经在显示中，都不该再次激活。 */
    @Test
    public void disabledOrActiveScheduleDoesNotStartAgain() {
        assertFalse(BedtimeSchedule.shouldActivate(AT_BEDTIME, false, false, "", 23, 30));
        assertFalse(BedtimeSchedule.shouldActivate(AT_BEDTIME, true, true, "", 23, 30));
    }

    /**
     * 时间加减要能跨午夜双向绕回：
     * 23:45 加 30 分钟 → 次日 00:15（即当天第 15 分钟）；
     * 00:15 减 30 分钟 → 前一天 23:45。后者中间结果为负，考验的正是 floorMod。
     */
    @Test
    public void timeAdjustmentWrapsAcrossMidnight() {
        assertEquals(15, BedtimeSchedule.adjustMinutes(23, 45, 30));
        assertEquals(23 * 60 + 45, BedtimeSchedule.adjustMinutes(0, 15, -30));
    }

    /** 四个条件（开关、显示中、次数、到点）逐个验证，缺一个都不该响。 */
    @Test
    public void soundPlaysOnlyWhenEnabledActiveAndDue() {
        assertTrue(BedtimeSchedule.shouldPlaySound(true, true, 0, 1_000L, 1_000L));
        assertFalse(BedtimeSchedule.shouldPlaySound(false, true, 0, 1_000L, 1_000L));
        assertFalse(BedtimeSchedule.shouldPlaySound(true, false, 0, 1_000L, 1_000L));
        assertFalse(BedtimeSchedule.shouldPlaySound(true, true, 0, 1_001L, 1_000L));
    }

    /** 响满两次就停：已响 1 次还能再响，已响 2 次就不再响了。 */
    @Test
    public void soundStopsAfterSecondPlay() {
        assertTrue(BedtimeSchedule.shouldPlaySound(true, true, 1, 1_000L, 2_000L));
        assertFalse(BedtimeSchedule.shouldPlaySound(true, true, 2, 1_000L, 2_000L));
    }
}
