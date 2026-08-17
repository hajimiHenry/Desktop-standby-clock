package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 面板转场动画的规则测试。这里验证的主要是"方向对不对、透明度对不对"，
 * 所以多数断言只检查正负号，不纠结具体数值。
 */
public final class DeviceMenuMotionTest {
    /** 浮点比较的容差。 */
    private static final float EPSILON = 0.0001f;

    /**
     * 左划打开：时钟往左走（负），菜单从右边进来（正），两层方向相反。
     * 进度 0.25 时时钟还剩 75% 不透明度，菜单已经有 25%。
     */
    @Test
    public void leftOpeningMovesClockLeftAndMenuInFromRight() {
        DeviceMenuMotion.Frame frame = DeviceMenuMotion.resolve(true, true, 0.25f);

        assertTrue(frame.clockOffsetFactor < 0f);
        assertTrue(frame.menuOffsetFactor > 0f);
        assertEquals(0.75f, frame.clockAlpha, EPSILON);
        assertEquals(0.25f, frame.menuAlpha, EPSILON);
    }

    /** 右划打开时方向完全镜像，正负号跟上一个用例相反。 */
    @Test
    public void rightOpeningMirrorsHorizontalMotion() {
        DeviceMenuMotion.Frame frame = DeviceMenuMotion.resolve(true, false, 0.25f);

        assertTrue(frame.clockOffsetFactor > 0f);
        assertTrue(frame.menuOffsetFactor < 0f);
    }

    /** 关闭时两层角色对调：菜单成了离场层，时钟滑回来。 */
    @Test
    public void closingMakesMenuOutgoingAndClockIncoming() {
        DeviceMenuMotion.Frame frame = DeviceMenuMotion.resolve(false, true, 0.75f);

        assertTrue(frame.menuOffsetFactor < 0f);
        assertTrue(frame.clockOffsetFactor > 0f);
        assertEquals(0.75f, frame.clockAlpha, EPSILON);
        assertEquals(0.25f, frame.menuAlpha, EPSILON);
    }
}
