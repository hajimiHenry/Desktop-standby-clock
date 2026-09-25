package com.henry.standbyclock;

import java.time.LocalTime;

/**
 * 久坐提醒的纯规则：到了该站起来活动的时间吗？
 *
 * <p>和 {@link BedtimeSchedule} 一样是无状态纯函数集合，状态存在 StandbyService 里。
 * 逻辑：在用户设定的工作时段内，每隔一段可调间隔提醒一次站起来活动，
 * 工作时段的结束时间自动跟随就寝时间。
 */
final class SedentaryReminder {
    /** 默认提醒间隔：45 分钟。 */
    static final int DEFAULT_INTERVAL_MINUTES = 45;
    /** 最短间隔：15 分钟。 */
    static final int MIN_INTERVAL_MINUTES = 15;
    /** 最长间隔：120 分钟。 */
    static final int MAX_INTERVAL_MINUTES = 120;
    /** 默认开始时间：09:00。 */
    static final int DEFAULT_START_HOUR = 9;
    static final int DEFAULT_START_MINUTE = 0;
    /** 同 BedtimeSchedule，提示音最多响两次。 */
    static final int MAX_SOUND_PLAYS = 2;
    /** 两次提示音之间的间隔，和就寝提醒一致。 */
    static final long SOUND_REPEAT_MS = 5L * 60L * 1_000L;

    private SedentaryReminder() {}

    /**
     * 判断此刻该不该弹出久坐提醒。四个条件全部满足才激活：
     * <ol>
     *   <li>功能开着</li>
     *   <li>没有提醒正在显示</li>
     *   <li>当前时刻在工作时段内（从 startHour:startMinute 到 endHour:endMinute）</li>
     *   <li>距上次按掉（或首次启动）已过一个完整间隔</li>
     * </ol>
     *
     * @param now            当前 LocalTime，传入以便测试
     * @param intervalMs     提醒间隔毫秒数
     * @param startHour      工作时段起始小时
     * @param startMinute    工作时段起始分钟
     * @param endHour        工作时段结束小时（通常 = 就寝时间）
     * @param endMinute      工作时段结束分钟
     * @param lastDismissedMs 上次按掉的时间戳，首次启动时用服务创建的时刻
     */
    static boolean shouldActivate(
            LocalTime now,
            boolean enabled,
            boolean alreadyActive,
            long lastDismissedMs,
            long nowMs,
            long intervalMs,
            int startHour,
            int startMinute,
            int endHour,
            int endMinute) {
        if (!enabled || alreadyActive) {
            return false;
        }
        if (!isWithinActiveHours(now, startHour, startMinute, endHour, endMinute)) {
            return false;
        }
        return nowMs - lastDismissedMs >= intervalMs;
    }

    /**
     * 判断当前时刻是否在工作时段内。
     * 支持跨午夜：比如 startHour=09:00, endHour=01:00 表示从早 9 点到凌晨 1 点。
     */
    static boolean isWithinActiveHours(
            LocalTime now,
            int startHour,
            int startMinute,
            int endHour,
            int endMinute) {
        int nowMinutes = now.getHour() * 60 + now.getMinute();
        int startMinutes = startHour * 60 + startMinute;
        int endMinutes = endHour * 60 + endMinute;
        if (endMinutes > startMinutes) {
            // 不跨午夜：09:00 - 23:30
            return nowMinutes >= startMinutes && nowMinutes < endMinutes;
        } else if (endMinutes < startMinutes) {
            // 跨午夜：09:00 - 01:00
            return nowMinutes >= startMinutes || nowMinutes < endMinutes;
        } else {
            // 起止相同，无有效时段
            return false;
        }
    }

    /** 间隔值钳位到合法范围。 */
    static int clampInterval(int minutes) {
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, minutes));
    }

    /**
     * 判断此刻该不该播放提示音。逻辑和就寝提醒完全一致：
     * 开关开着、提醒正在显示、还没响够次数、且已到下次响铃时刻。
     */
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
