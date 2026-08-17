package com.henry.standbyclock;

/**
 * 可选的表盘样式（"表面"）。目前有两种：P3 琥珀色磷光指针盘、书法风格数字钟。
 *
 * <p>注意 key 字段：它会被写进 SharedPreferences 持久化保存，重启后靠它恢复用户
 * 上次选的表盘。所以 key 的字符串值一旦发布就不能再改，改了老用户的设置会失效
 * （失效后会走 {@link #fromKey} 的兜底逻辑，退回 PHOSPHOR_DIAL）。
 * 枚举名和 label 倒是可以随便改。
 */
public enum ClockStyle {
    PHOSPHOR_DIAL("phosphor_dial", "PHOSPHOR DIAL", "P3 amber phosphor analog clock"),
    CALLIGRAPHY("calligraphy", "CALLIGRAPHY", "Calligraphic digital clock");

    /** 持久化用的稳定标识，不可更改。 */
    private final String key;
    /** 设置界面上显示的名字。 */
    private final String label;
    /** 无障碍朗读用的描述（设置成 View 的 contentDescription）。 */
    private final String description;

    ClockStyle(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /**
     * 下一个表盘，到末尾会绕回第一个（循环切换）。
     *
     * <p>floorMod 而不是 % ：Java 的 % 对负数会返回负值，floorMod 保证结果永远落在
     * [0, length) 区间内，这样 {@link #previous()} 里的 ordinal()-1 在 ordinal 为 0 时
     * 也能正确绕到最后一个。
     */
    public ClockStyle next() {
        ClockStyle[] styles = values();
        return styles[Math.floorMod(ordinal() + 1, styles.length)];
    }

    /** 上一个表盘，到开头会绕回最后一个。 */
    public ClockStyle previous() {
        ClockStyle[] styles = values();
        return styles[Math.floorMod(ordinal() - 1, styles.length)];
    }

    /**
     * 从持久化的 key 还原成枚举。
     *
     * <p>找不到（key 为 null、或者是某个已经被删掉的旧样式）时返回默认表盘，
     * 保证升级／降级后应用不会崩溃。
     */
    public static ClockStyle fromKey(String key) {
        for (ClockStyle style : values()) {
            if (style.key.equals(key)) {
                return style;
            }
        }
        return PHOSPHOR_DIAL;
    }
}
