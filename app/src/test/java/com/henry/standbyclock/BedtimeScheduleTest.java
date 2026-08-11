package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;

public final class BedtimeScheduleTest {
    private static final LocalDateTime BEFORE = LocalDateTime.of(2026, 8, 5, 23, 29);
    private static final LocalDateTime AT_BEDTIME = LocalDateTime.of(2026, 8, 5, 23, 30);

    @Test
    public void activatesAtConfiguredTime() {
        assertFalse(BedtimeSchedule.shouldActivate(BEFORE, true, false, "", 23, 30));
        assertTrue(BedtimeSchedule.shouldActivate(AT_BEDTIME, true, false, "", 23, 30));
    }

    @Test
    public void acknowledgedDateDoesNotReactivate() {
        assertFalse(BedtimeSchedule.shouldActivate(
                AT_BEDTIME, true, false, "2026-08-05", 23, 30));
    }

    @Test
    public void disabledOrActiveScheduleDoesNotStartAgain() {
        assertFalse(BedtimeSchedule.shouldActivate(AT_BEDTIME, false, false, "", 23, 30));
        assertFalse(BedtimeSchedule.shouldActivate(AT_BEDTIME, true, true, "", 23, 30));
    }

    @Test
    public void timeAdjustmentWrapsAcrossMidnight() {
        assertEquals(15, BedtimeSchedule.adjustMinutes(23, 45, 30));
        assertEquals(23 * 60 + 45, BedtimeSchedule.adjustMinutes(0, 15, -30));
    }

    @Test
    public void soundPlaysOnlyWhenEnabledActiveAndDue() {
        assertTrue(BedtimeSchedule.shouldPlaySound(true, true, 0, 1_000L, 1_000L));
        assertFalse(BedtimeSchedule.shouldPlaySound(false, true, 0, 1_000L, 1_000L));
        assertFalse(BedtimeSchedule.shouldPlaySound(true, false, 0, 1_000L, 1_000L));
        assertFalse(BedtimeSchedule.shouldPlaySound(true, true, 0, 1_001L, 1_000L));
    }

    @Test
    public void soundStopsAfterSecondPlay() {
        assertTrue(BedtimeSchedule.shouldPlaySound(true, true, 1, 1_000L, 2_000L));
        assertFalse(BedtimeSchedule.shouldPlaySound(true, true, 2, 1_000L, 2_000L));
    }
}
