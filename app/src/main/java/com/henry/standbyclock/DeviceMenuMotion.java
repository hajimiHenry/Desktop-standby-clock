package com.henry.standbyclock;

/**
 * 设备控制面板"推入 / 推出"转场动画的纯几何规则。
 *
 * <p>效果是常见的横向推屏：旧画面往一侧滑出并淡出，新画面同时从相反一侧滑入并淡入，
 * 两者始终朝同一方向移动，看起来像一整条横向的胶片被推着走。
 *
 * <p>这里只输出<em>无量纲的比例</em>（-1 ~ 1 的偏移系数、0 ~ 1 的透明度），
 * 不含任何像素值。ClockView 拿到后再乘以屏幕宽度和 255。好处是这套规则可以脱离
 * Android 单测，见 DeviceMenuMotionTest。
 */
final class DeviceMenuMotion {
    /** 某一动画帧上，时钟层和菜单层各自的位移系数与透明度。 */
    static final class Frame {
        /** 时钟层的水平偏移系数，乘以位移距离得到实际像素；负值向左。 */
        final float clockOffsetFactor;
        /** 菜单层的水平偏移系数，与时钟层方向相反。 */
        final float menuOffsetFactor;
        /** 时钟层不透明度，0 ~ 1。 */
        final float clockAlpha;
        /** 菜单层不透明度，0 ~ 1。 */
        final float menuAlpha;

        Frame(
                float clockOffsetFactor,
                float menuOffsetFactor,
                float clockAlpha,
                float menuAlpha) {
            this.clockOffsetFactor = clockOffsetFactor;
            this.menuOffsetFactor = menuOffsetFactor;
            this.clockAlpha = clockAlpha;
            this.menuAlpha = menuAlpha;
        }
    }

    private DeviceMenuMotion() {
    }

    /**
     * 算出当前帧两层的位置和透明度。
     *
     * @param opening      true = 正在打开菜单，false = 正在关闭
     * @param swipeLeft    手指是往左划的吗，决定整体推进方向（跟手）
     * @param easedProgress 已经过缓动函数处理的进度，0 = 刚开始，1 = 结束
     */
    static Frame resolve(boolean opening, boolean swipeLeft, float easedProgress) {
        // 容错夹紧，防止调用方传进超出范围的进度导致画面飞出屏幕。
        float progress = Math.max(0f, Math.min(1f, easedProgress));

        // "离场那一层"往哪走：左划时向左（-1），右划时向右（+1）。
        // 入场层取相反方向，两层就有了推着走的连贯感。
        float outgoingDirection = swipeLeft ? -1f : 1f;

        if (opening) {
            // 打开：时钟是离场层（从 0 滑到 ±1、alpha 1→0），菜单是入场层
            // （从 ∓1 滑到 0、alpha 0→1）。
            return new Frame(
                    outgoingDirection * progress,
                    -outgoingDirection * (1f - progress),
                    1f - progress,
                    progress);
        }
        // 关闭：两者角色对调，菜单变成离场层，时钟滑回来。
        return new Frame(
                -outgoingDirection * (1f - progress),
                outgoingDirection * progress,
                progress,
                1f - progress);
    }
}
