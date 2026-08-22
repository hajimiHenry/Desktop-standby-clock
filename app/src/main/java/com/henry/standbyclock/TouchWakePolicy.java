package com.henry.standbyclock;

/**
 * 轻触点亮的计时逻辑（纯逻辑，不碰 Android API，方便单测）。
 *
 * <p>要解决的问题：房间暗下来后屏幕会全黑（见 {@link AmbientLightPolicy}），而熄屏时
 * 界面对触摸完全没反应——晚上回到家想看一眼时间，或者想右滑打开面板去开吸顶灯，都只能
 * 先去按墙上的实体开关，等环境光把屏幕唤醒。这里给熄屏状态开一个"临时窗口"：碰一下屏幕
 * 就点亮一小会儿，期间手势照常可用，没人再碰就自动黑回去。
 *
 * <p>这个窗口只<em>压制</em>熄屏，并不改变环境光状态机的判断：窗口到期后屏幕直接回到
 * {@link AmbientLightPolicy} 认定的状态。如果用户在窗口里真把灯打开了，环境光那边会
 * 自己确认变亮并接管，屏幕就一直亮着了——这正是设计想要的收尾方式。
 */
final class TouchWakePolicy {
    /** 一次轻触点亮多久：20 秒，够看一眼时间，也够右滑进设备控制把灯打开。 */
    static final long WAKE_DURATION_MS = 20_000L;

    /** 点亮窗口的截止时刻，0 表示当前没有窗口。 */
    private long wakeUntilMs;

    /**
     * 记一次触摸，窗口从现在起重新计满。
     *
     * @param nowMs 使用 SystemClock.elapsedRealtime()（单调递增、休眠也计时），
     *              理由同 AmbientLightPolicy：不能用墙上时钟，否则改系统时间会把计时算乱
     */
    void noteTouch(long nowMs) {
        wakeUntilMs = nowMs + WAKE_DURATION_MS;
    }

    /** 当前是否还在点亮窗口内。 */
    boolean isAwake(long nowMs) {
        return wakeUntilMs > 0L && nowMs < wakeUntilMs;
    }

    /**
     * 距离窗口到期还剩多少毫秒，供外部安排定时回调。
     *
     * @return -1 表示当前没有窗口，不用安排定时器
     */
    long remainingMs(long nowMs) {
        if (!isAwake(nowMs)) {
            return -1L;
        }
        return wakeUntilMs - nowMs;
    }

    /** 立刻结束窗口。环境光自己变亮、或界面退到后台时调用。 */
    void clear() {
        wakeUntilMs = 0L;
    }
}
