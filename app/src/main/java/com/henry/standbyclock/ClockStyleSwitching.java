package com.henry.standbyclock;

/**
 * 表盘切换的纯规则集合：手势方向判定 + 自动轮换的倒计时计算。
 *
 * <p>为什么单独抽一个类：这里面全是不依赖 Android API 的纯函数（只吃 float / long，
 * 吐枚举 / boolean），所以能在普通 JVM 单元测试里直接跑，不用起模拟器。
 * ClockView 负责收触摸事件，MainActivity 负责定时器，两边都调用这里的规则，
 * 保证判定逻辑只有一份。对应测试见 ClockStyleSwitchingTest。
 */
final class ClockStyleSwitching {
    /** 自动轮换表盘的间隔：1 小时。 */
    static final long AUTO_SWITCH_INTERVAL_MS = 60L * 60L * 1000L;

    /**
     * 方向"压倒性"比例。要判定为竖划，竖直位移必须超过水平位移的 1.25 倍
     * （横划反之）。这是为了过滤斜着划的手势——斜划意图不明确，宁可不响应，
     * 也别把用户想开菜单的动作错认成切表盘。
     */
    private static final float VERTICAL_DOMINANCE_RATIO = 1.25f;

    /** 竖直手势结果：上划切下一个表盘，下划切上一个。 */
    enum SwipeDirection {
        NONE,
        PREVIOUS,
        NEXT
    }

    /** 水平手势结果：左划开流量面板，右划开设备控制面板。 */
    enum HorizontalSwipeDirection {
        NONE,
        LEFT,
        RIGHT
    }

    private ClockStyleSwitching() {
    }

    /**
     * 判定竖划方向。
     *
     * @param deltaX          手指相对按下点的水平位移（像素）
     * @param deltaY          竖直位移，屏幕坐标系向下为正
     * @param minimumDistance 最小触发距离，低于它认为是误触
     * @return 两个条件都满足才算数：位移够长，且竖直方向明显压过水平方向
     */
    static SwipeDirection resolveSwipe(float deltaX, float deltaY, float minimumDistance) {
        float verticalDistance = Math.abs(deltaY);
        if (verticalDistance < minimumDistance
                || verticalDistance <= Math.abs(deltaX) * VERTICAL_DOMINANCE_RATIO) {
            return SwipeDirection.NONE;
        }
        // deltaY < 0 表示手指往上移动（屏幕坐标 Y 轴向下），上划 = 下一个表盘。
        return deltaY < 0f ? SwipeDirection.NEXT : SwipeDirection.PREVIOUS;
    }

    /** 判定横划方向，规则与 {@link #resolveSwipe} 对称，只是 X / Y 互换。 */
    static HorizontalSwipeDirection resolveHorizontalSwipe(
            float deltaX, float deltaY, float minimumDistance) {
        float horizontalDistance = Math.abs(deltaX);
        if (horizontalDistance < minimumDistance
                || horizontalDistance <= Math.abs(deltaY) * VERTICAL_DOMINANCE_RATIO) {
            return HorizontalSwipeDirection.NONE;
        }
        return deltaX < 0f ? HorizontalSwipeDirection.LEFT : HorizontalSwipeDirection.RIGHT;
    }

    /**
     * 横划打开的是哪个面板：左划 = 流量看板，右划 = 设备控制。
     * 两个面板共用同一套滑入动画，只有内容不同。
     */
    static boolean opensTrafficDashboard(HorizontalSwipeDirection direction) {
        return direction == HorizontalSwipeDirection.LEFT;
    }

    /** 从当前时刻算出下一次自动切换的绝对时间戳。 */
    static long nextAutoSwitchAt(long nowMs) {
        return nowMs + AUTO_SWITCH_INTERVAL_MS;
    }

    /**
     * 是否已到自动切换时间。
     *
     * <p>要求 nextSwitchAtMs > 0：0 表示"没有安排过"（自动切换关闭时会把这个键删掉，
     * 读出来就是默认值 0），不能当成"1970 年就该切了"。
     */
    static boolean isDue(long nextSwitchAtMs, long nowMs) {
        return nextSwitchAtMs > 0L && nowMs >= nextSwitchAtMs;
    }

    /**
     * 存下来的下次切换时间还能不能接着用。
     *
     * <p>必须落在"现在"到"现在 + 一个完整间隔"之间。上界这层检查是防系统时钟被
     * 往回调（或者用户手动改了时间）导致存了个几年后的时间戳，那样表盘就再也不会
     * 自动轮换了；不合格就重新计时。
     */
    static boolean isUsableFutureTime(long nextSwitchAtMs, long nowMs) {
        return nextSwitchAtMs > nowMs
                && nextSwitchAtMs - nowMs <= AUTO_SWITCH_INTERVAL_MS;
    }
}
