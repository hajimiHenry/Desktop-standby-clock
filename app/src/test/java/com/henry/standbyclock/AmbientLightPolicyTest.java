package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AmbientLightPolicyTest {
    @Test
    public void sustainedDarknessBlacksOutAfterConfirmation() {
        AmbientLightPolicy policy = new AmbientLightPolicy();

        assertEquals(AmbientLightPolicy.Change.NONE, policy.updateLux(1f, 1_000L));
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(20_999L));
        assertFalse(policy.isBlackout());
        assertEquals(AmbientLightPolicy.Change.BLACKOUT, policy.evaluate(21_000L));
        assertTrue(policy.isBlackout());
    }

    @Test
    public void sustainedBrightnessShowsAndRemainsVisible() {
        AmbientLightPolicy policy = blackedOutPolicy();

        assertEquals(AmbientLightPolicy.Change.NONE, policy.updateLux(30f, 30_000L));
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(32_999L));
        assertEquals(AmbientLightPolicy.Change.SHOW, policy.evaluate(33_000L));
        assertFalse(policy.isBlackout());
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(300_000L));
        assertFalse(policy.isBlackout());
    }

    @Test
    public void intermediateLightCancelsPendingTransition() {
        AmbientLightPolicy policy = new AmbientLightPolicy();

        policy.updateLux(1f, 1_000L);
        policy.updateLux(8f, 10_000L);
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(30_000L));
        assertFalse(policy.isBlackout());
    }

    @Test
    public void briefOcclusionDoesNotBlackOut() {
        AmbientLightPolicy policy = new AmbientLightPolicy();

        policy.updateLux(0f, 1_000L);
        policy.updateLux(50f, 5_000L);
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(25_000L));
        assertFalse(policy.isBlackout());
    }

    @Test
    public void hysteresisKeepsTheCurrentStateBetweenThresholds() {
        AmbientLightPolicy policy = blackedOutPolicy();

        policy.updateLux(10f, 30_000L);
        assertEquals(AmbientLightPolicy.Change.NONE, policy.evaluate(100_000L));
        assertTrue(policy.isBlackout());
    }

    @Test
    public void exposesRemainingConfirmationDelay() {
        AmbientLightPolicy policy = new AmbientLightPolicy();

        policy.updateLux(2f, 5_000L);
        assertEquals(15_000L, policy.nextEvaluationDelayMs(10_000L));
    }

    private static AmbientLightPolicy blackedOutPolicy() {
        AmbientLightPolicy policy = new AmbientLightPolicy();
        policy.updateLux(1f, 1_000L);
        policy.evaluate(21_000L);
        return policy;
    }
}
