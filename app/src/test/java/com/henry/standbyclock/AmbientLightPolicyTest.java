package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 环境光状态机的测试。因为 AmbientLightPolicy 是纯逻辑（时间戳靠参数传入而不是
 * 读系统时钟），这里可以直接"伪造时间"来验证各种时序，不用真的等 20 秒。
 */
public final class AmbientLightPolicyTest {
    /** 持续黑暗要满 20 秒才熄屏，且边界是"恰好 20 秒"就算数。 */
    @Test
    public void sustainedDarknessBlacksOutAfterConfirmation() {
        AmbientLightPolicy policy = new AmbientLightPolicy();

        assertEquals(AmbientLightPolicy.Change.NONE, policy.updateLux(1f, 1_000L));
        // 1000ms 时进入黑暗区间，20999ms 时才过去 19.999 秒，还差 1 毫秒。
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(20_999L));
        assertFalse(policy.isBlackout());
        // 正好满 20 秒，触发熄屏。
        assertEquals(AmbientLightPolicy.Change.BLACKOUT, policy.evaluate(21_000L));
        assertTrue(policy.isBlackout());
    }

    /** 变亮只要确认 3 秒；而且亮起来之后要能一直保持，不会自己又熄回去。 */
    @Test
    public void sustainedBrightnessShowsAndRemainsVisible() {
        AmbientLightPolicy policy = blackedOutPolicy();

        assertEquals(AmbientLightPolicy.Change.NONE, policy.updateLux(30f, 30_000L));
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(32_999L));
        assertEquals(AmbientLightPolicy.Change.SHOW, policy.evaluate(33_000L));
        assertFalse(policy.isBlackout());
        // 再过 4 分半也不该有任何变化——这一条防的是状态机自己乱跳。
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(300_000L));
        assertFalse(policy.isBlackout());
    }

    /** 光线回到中间缓冲区（8 lux）时，正在计时的熄屏应当被取消。 */
    @Test
    public void intermediateLightCancelsPendingTransition() {
        AmbientLightPolicy policy = new AmbientLightPolicy();

        policy.updateLux(1f, 1_000L);
        policy.updateLux(8f, 10_000L);
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(30_000L));
        assertFalse(policy.isBlackout());
    }

    /**
     * 最重要的一条：短暂遮挡不该熄屏。
     * 模拟有人从传感器前走过——瞬间掉到 0 lux，4 秒后恢复到 50 lux。
     * 因为没撑满 20 秒确认期，屏幕应当始终亮着。
     */
    @Test
    public void briefOcclusionDoesNotBlackOut() {
        AmbientLightPolicy policy = new AmbientLightPolicy();

        policy.updateLux(0f, 1_000L);
        policy.updateLux(50f, 5_000L);
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(25_000L));
        assertFalse(policy.isBlackout());
    }

    /**
     * 迟滞的作用：10 lux 处在 3~15 的缓冲区内，既不够亮也不够暗，
     * 此时应当维持原状（这里是保持熄屏），而不是来回切换。
     */
    @Test
    public void hysteresisKeepsTheCurrentStateBetweenThresholds() {
        AmbientLightPolicy policy = blackedOutPolicy();

        policy.updateLux(10f, 30_000L);
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(100_000L));
        assertTrue(policy.isBlackout());
    }

    /** 剩余确认时间要能正确报出来，服务靠它安排定时回调：20 秒 - 已过的 5 秒 = 15 秒。 */
    @Test
    public void exposesRemainingConfirmationDelay() {
        AmbientLightPolicy policy = new AmbientLightPolicy();

        policy.updateLux(2f, 5_000L);
        assertEquals(15_000L, policy.nextEvaluationDelayMs(10_000L));
    }

    /** 测试夹具：造一个已经处于熄屏状态的状态机。 */
    private static AmbientLightPolicy blackedOutPolicy() {
        AmbientLightPolicy policy = new AmbientLightPolicy();
        policy.updateLux(1f, 1_000L);
        policy.evaluate(21_000L);
        return policy;
    }
}
