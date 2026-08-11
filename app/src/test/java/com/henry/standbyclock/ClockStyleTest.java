package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ClockStyleTest {
    @Test
    public void cyclingWrapsInBothDirections() {
        assertEquals(ClockStyle.CALLIGRAPHY, ClockStyle.PHOSPHOR_DIAL.next());
        assertEquals(ClockStyle.PHOSPHOR_DIAL, ClockStyle.CALLIGRAPHY.next());
        assertEquals(ClockStyle.CALLIGRAPHY, ClockStyle.PHOSPHOR_DIAL.previous());
        assertEquals(ClockStyle.PHOSPHOR_DIAL, ClockStyle.CALLIGRAPHY.previous());
    }

    @Test
    public void storedKeysRoundTrip() {
        for (ClockStyle style : ClockStyle.values()) {
            assertEquals(style, ClockStyle.fromKey(style.key()));
        }
    }

    @Test
    public void unknownOrMissingKeyFallsBackToDefault() {
        assertEquals(ClockStyle.PHOSPHOR_DIAL, ClockStyle.fromKey(null));
        assertEquals(ClockStyle.PHOSPHOR_DIAL, ClockStyle.fromKey("removed_style"));
    }
}
