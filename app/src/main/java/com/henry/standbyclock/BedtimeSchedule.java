package com.henry.standbyclock;

import java.time.LocalDateTime;
import java.time.LocalTime;

final class BedtimeSchedule {
    static final int MINUTES_PER_DAY = 24 * 60;
    static final int MAX_SOUND_PLAYS = 2;

    private BedtimeSchedule() {}

    static boolean shouldActivate(
            LocalDateTime now,
            boolean enabled,
            boolean alreadyActive,
            String acknowledgedDate,
            int hour,
            int minute) {
        if (!enabled || alreadyActive) {
            return false;
        }
        String today = now.toLocalDate().toString();
        if (today.equals(acknowledgedDate)) {
            return false;
        }
        return !now.toLocalTime().isBefore(LocalTime.of(hour, minute));
    }

    static int adjustMinutes(int hour, int minute, int deltaMinutes) {
        return Math.floorMod(hour * 60 + minute + deltaMinutes, MINUTES_PER_DAY);
    }

    static boolean shouldPlaySound(
            boolean soundEnabled,
            boolean reminderActive,
            int playCount,
            long nextSoundAtMs,
            long nowMs) {
        return soundEnabled
                && reminderActive
                && playCount < MAX_SOUND_PLAYS
                && nowMs >= nextSoundAtMs;
    }
}
