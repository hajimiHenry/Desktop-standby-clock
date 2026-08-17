package com.henry.standbyclock;

/**
 * 环境光自动熄屏的决策状态机（纯逻辑，不碰传感器 API，方便单测）。
 *
 * <p>要解决的问题：房间关灯了，屏幕应该全黑（OLED 纯黑不发光，既不刺眼也省电）；
 * 开灯了，屏幕要立刻亮回来。难点在于光线传感器读数抖动很大——有人从旁边走过挡一下、
 * 云飘过去，都会瞬间掉到很暗。所以这里用了两道防抖：
 *
 * <ol>
 *   <li><b>迟滞（hysteresis）</b>：熄屏和亮屏用两个不同的阈值（3 lux / 15 lux），
 *       中间 3~15 lux 是"缓冲区"，处在缓冲区时维持当前状态不变。如果只用单一阈值，
 *       读数在阈值附近来回抖动会导致屏幕疯狂闪烁。</li>
 *   <li><b>持续确认（confirmation）</b>：进入某个区间后还得<em>稳定待够时间</em>才真正切换。
 *       熄屏要确认 20 秒（宁可慢一点，也别因为有人挡了一下就黑屏）；
 *       亮屏只要 3 秒（用户开灯了希望马上能看见时间，响应要快）。</li>
 * </ol>
 *
 * <p>典型用法（见 StandbyService）：传感器来数据时调 {@link #updateLux}；由于确认期内
 * 传感器可能不再上报新数据，还要按 {@link #nextEvaluationDelayMs} 返回的延迟安排一次
 * 定时回调，到点调 {@link #evaluate} 补一次判断。
 */
final class AmbientLightPolicy {
    /** 低于等于 3 lux 视为"暗"（约等于关灯后的房间）。 */
    static final float DARK_THRESHOLD_LUX = 3f;
    /** 高于等于 15 lux 视为"亮"。与上面留出的间隔就是迟滞缓冲区。 */
    static final float BRIGHT_THRESHOLD_LUX = 15f;
    /** 熄屏需要持续确认的时长：20 秒，故意设得长，避免误熄。 */
    static final long DARK_CONFIRMATION_MS = 20_000L;
    /** 亮屏需要持续确认的时长：3 秒，故意设得短，开灯要跟手。 */
    static final long BRIGHT_CONFIRMATION_MS = 3_000L;

    /** 一次判断的结论：无变化 / 该熄屏了 / 该亮屏了。 */
    enum Change {
        NONE,
        BLACKOUT,
        SHOW
    }

    /** 当前光照落在哪个区间。INTERMEDIATE 就是上面说的迟滞缓冲区。 */
    private enum Zone {
        DARK,
        INTERMEDIATE,
        BRIGHT
    }

    /** 当前是否处于熄屏状态。 */
    private boolean blackout;
    /** 最近一次读数所属区间。 */
    private Zone currentZone = Zone.INTERMEDIATE;
    /**
     * 进入当前区间的时间戳，用来算"已经稳定了多久"。
     * -1 表示当前没有待确认的切换（比如处在缓冲区，或者刚切换完）。
     */
    private long candidateSinceMs = -1L;

    /**
     * 喂一个新的光照读数。
     *
     * @param nowMs 使用 SystemClock.elapsedRealtime()（单调递增、休眠也计时），
     *              不能用墙上时钟，否则用户改系统时间会把确认计时算乱。
     */
    Change updateLux(float lux, long nowMs) {
        // 传感器偶尔会吐 NaN / 负值，直接丢弃，别污染状态机。
        if (!Float.isFinite(lux) || lux < 0f) {
            return Change.NONE;
        }

        Zone nextZone;
        if (lux <= DARK_THRESHOLD_LUX) {
            nextZone = Zone.DARK;
        } else if (lux >= BRIGHT_THRESHOLD_LUX) {
            nextZone = Zone.BRIGHT;
        } else {
            nextZone = Zone.INTERMEDIATE;
        }

        // 只有跨区间时才重置计时器：同一区间内持续上报不影响"已稳定多久"。
        // 进入缓冲区则彻底取消待确认的切换（-1）——这正是"短暂遮挡不熄屏"的关键。
        if (nextZone != currentZone) {
            currentZone = nextZone;
            candidateSinceMs = nextZone == Zone.INTERMEDIATE ? -1L : nowMs;
        }
        return evaluate(nowMs);
    }

    /**
     * 在不喂新读数的情况下重新判断一次，看确认时间够了没。
     * 传感器安静时靠外部定时器调用它来推进状态机。
     */
    Change evaluate(long nowMs) {
        if (candidateSinceMs < 0L) {
            return Change.NONE;
        }
        // 亮着 + 持续够暗够久 → 熄屏。
        if (!blackout && currentZone == Zone.DARK
                && nowMs - candidateSinceMs >= DARK_CONFIRMATION_MS) {
            blackout = true;
            candidateSinceMs = -1L;
            return Change.BLACKOUT;
        }
        // 熄着 + 持续够亮够久 → 亮屏。
        if (blackout && currentZone == Zone.BRIGHT
                && nowMs - candidateSinceMs >= BRIGHT_CONFIRMATION_MS) {
            blackout = false;
            candidateSinceMs = -1L;
            return Change.SHOW;
        }
        // 走到这里说明区间和当前状态"同向"（已经亮着又很亮 / 已经黑着又很暗），
        // 没有任何要切换的东西，清掉计时器免得留着无意义的待确认状态。
        if ((!blackout && currentZone == Zone.BRIGHT)
                || (blackout && currentZone == Zone.DARK)) {
            candidateSinceMs = -1L;
        }
        return Change.NONE;
    }

    /**
     * 距离下一次有意义的判断还差多少毫秒，供外部安排定时回调。
     *
     * @return -1 表示当前没有待确认的切换，不用安排定时器
     */
    long nextEvaluationDelayMs(long nowMs) {
        if (candidateSinceMs < 0L) {
            return -1L;
        }
        long confirmationMs;
        if (!blackout && currentZone == Zone.DARK) {
            confirmationMs = DARK_CONFIRMATION_MS;
        } else if (blackout && currentZone == Zone.BRIGHT) {
            confirmationMs = BRIGHT_CONFIRMATION_MS;
        } else {
            return -1L;
        }
        // 至少返回 1ms：剩余时间算出来 <= 0 时也要真正发起一次回调，不能返回 0 让
        // 调用方误以为"不用安排"。
        return Math.max(1L, confirmationMs - (nowMs - candidateSinceMs));
    }

    boolean isBlackout() {
        return blackout;
    }
}
