package com.henry.standbyclock;

/** Selectable clock faces. The stored key is persisted, so it must stay stable. */
public enum ClockStyle {
    PHOSPHOR_DIAL("phosphor_dial", "PHOSPHOR DIAL", "P3 amber phosphor analog clock"),
    CALLIGRAPHY("calligraphy", "CALLIGRAPHY", "Calligraphic digital clock");

    private final String key;
    private final String label;
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

    public ClockStyle next() {
        ClockStyle[] styles = values();
        return styles[Math.floorMod(ordinal() + 1, styles.length)];
    }

    public ClockStyle previous() {
        ClockStyle[] styles = values();
        return styles[Math.floorMod(ordinal() - 1, styles.length)];
    }

    public static ClockStyle fromKey(String key) {
        for (ClockStyle style : values()) {
            if (style.key.equals(key)) {
                return style;
            }
        }
        return PHOSPHOR_DIAL;
    }
}
