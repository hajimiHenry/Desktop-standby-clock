package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 轻触点亮窗口的测试。和 AmbientLightPolicy 一样，时间戳是参数而不是系统时钟，
 * 所以这里直接"伪造时间"验证时序，不用真的等 20 秒。
 */
public final class TouchWakePolicyTest {
    /** 没碰过屏幕时不该有点亮窗口——否则开机第一秒就会压住熄屏。 */
    @Test
    public void staysAsleepUntilTouched() {
        TouchWakePolicy policy = new TouchWakePolicy();

        assertFalse(policy.isAwake(0L));
        assertFalse(policy.isAwake(60_000L));
        assertEquals(-1L, policy.remainingMs(60_000L));
    }

    /** 触摸后 20 秒内保持点亮，边界是"差 1 毫秒还亮着，正好到点就灭"。 */
    @Test
    public void touchOpensWindowUntilItExpires() {
        TouchWakePolicy policy = new TouchWakePolicy();

        policy.noteTouch(1_000L);
        assertTrue(policy.isAwake(1_000L));
        assertTrue(policy.isAwake(20_999L));
        assertFalse(policy.isAwake(21_000L));
        assertFalse(policy.isAwake(21_001L));
    }

    /** 窗口内再碰一下要重新计满，而不是接着原来的倒计时走。 */
    @Test
    public void repeatedTouchExtendsWindow() {
        TouchWakePolicy policy = new TouchWakePolicy();

        policy.noteTouch(1_000L);
        policy.noteTouch(15_000L);
        // 按第一次触摸算 21_000L 就该灭了，按第二次算要撑到 35_000L。
        assertTrue(policy.isAwake(21_000L));
        assertTrue(policy.isAwake(34_999L));
        assertFalse(policy.isAwake(35_000L));
    }

    /** 剩余时间要能正确报出来，界面靠它安排到期回调：20 秒 - 已过的 5 秒 = 15 秒。 */
    @Test
    public void exposesRemainingWindow() {
        TouchWakePolicy policy = new TouchWakePolicy();

        policy.noteTouch(5_000L);
        assertEquals(15_000L, policy.remainingMs(10_000L));
        assertEquals(-1L, policy.remainingMs(25_000L));
    }

    /** clear 立刻结束窗口：环境光自己变亮、或界面退到后台时走这条路。 */
    @Test
    public void clearEndsWindowImmediately() {
        TouchWakePolicy policy = new TouchWakePolicy();

        policy.noteTouch(1_000L);
        policy.clear();
        assertFalse(policy.isAwake(1_001L));
        assertEquals(-1L, policy.remainingMs(1_001L));
    }
}
