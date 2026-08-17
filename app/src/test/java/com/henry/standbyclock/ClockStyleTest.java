package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 表盘枚举的测试：循环切换、以及持久化 key 的往返转换。 */
public final class ClockStyleTest {
    /** 只有两个表盘，所以往前往后切都会立刻绕回另一个。 */
    @Test
    public void cyclingWrapsInBothDirections() {
        assertEquals(ClockStyle.CALLIGRAPHY, ClockStyle.PHOSPHOR_DIAL.next());
        assertEquals(ClockStyle.PHOSPHOR_DIAL, ClockStyle.CALLIGRAPHY.next());
        assertEquals(ClockStyle.CALLIGRAPHY, ClockStyle.PHOSPHOR_DIAL.previous());
        assertEquals(ClockStyle.PHOSPHOR_DIAL, ClockStyle.CALLIGRAPHY.previous());
    }

    /** 每个表盘存成 key 再读回来，必须还是自己——这是设置能被记住的前提。 */
    @Test
    public void storedKeysRoundTrip() {
        for (ClockStyle style : ClockStyle.values()) {
            assertEquals(style, ClockStyle.fromKey(style.key()));
        }
    }

    /**
     * 兜底行为：首次启动（key 为 null）、或读到某个已被删除的旧表盘 key，
     * 都要安全地退回默认表盘，而不是崩溃。
     */
    @Test
    public void unknownOrMissingKeyFallsBackToDefault() {
        assertEquals(ClockStyle.PHOSPHOR_DIAL, ClockStyle.fromKey(null));
        assertEquals(ClockStyle.PHOSPHOR_DIAL, ClockStyle.fromKey("removed_style"));
    }
}
