package com.henry.standbyclock;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 睡眠提醒的纯规则：什么时候该弹提醒、时间加减怎么绕天、提示音该不该响。
 *
 * <p>和 {@link AmbientLightPolicy} 一样是无状态纯函数集合，状态本身存在
 * StandbyService 里（并持久化到 SharedPreferences）。这么拆是为了能脱离 Android
 * 直接单测各种时间边界，见 BedtimeScheduleTest。
 */
final class BedtimeSchedule {
    /** 一天的总分钟数，用于时间加减的取模绕天。 */
    static final int MINUTES_PER_DAY = 24 * 60;
    /** 同一次提醒最多响几次提示音。响两次还没理，就不再打扰了。 */
    static final int MAX_SOUND_PLAYS = 2;

    private BedtimeSchedule() {}

    /**
     * 判断此刻该不该激活睡眠提醒。四个条件全部满足才激活：
     *
     * <ol>
     *   <li>功能开着</li>
     *   <li>提醒还没在显示中（避免重复激活、重置计数）</li>
     *   <li>今天还没被用户点过"DONE"——acknowledgedDate 存的是已确认的日期字符串，
     *       等于今天就说明今晚已经处理过了，不再骚扰</li>
     *   <li>当前时间已经到达或超过设定的就寝时刻</li>
     * </ol>
     *
     * <p>注意第 4 条用的是"不早于"而不是"恰好等于"：服务每 15 秒才轮询一次，
     * 精确匹配某一分钟会漏掉；而且开机时如果已经过了就寝点，也应该立刻补上提醒。
     *
     * @param acknowledgedDate 已确认日期，格式为 ISO 的 yyyy-MM-dd，没有则传空串
     */
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

    /**
     * 把就寝时间加减若干分钟，返回"当天第几分钟"（0 ~ 1439）。
     *
     * <p>用 floorMod 而不是 %：设 23:45 再 +30 分钟应该绕到次日 00:15，
     * 而 00:15 再 -30 分钟应该绕回 23:45。后者中间结果是负数，普通取模会得到负值，
     * floorMod 才能正确绕回去。调用方再用 /60 和 %60 拆回小时和分钟。
     */
    static int adjustMinutes(int hour, int minute, int deltaMinutes) {
        return Math.floorMod(hour * 60 + minute + deltaMinutes, MINUTES_PER_DAY);
    }

    /**
     * 判断此刻该不该播放提示音：开关开着、提醒正在显示、还没响够次数、且已到下次
     * 响铃时刻（第一次是立即，第二次隔 5 分钟，间隔由调用方设定）。
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
