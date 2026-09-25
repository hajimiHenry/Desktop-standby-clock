package com.henry.standbyclock;

/**
 * 吸顶灯的场景预设：一个预设 = 一组固定的色温 + 亮度。
 *
 * <p>数值依据（2026-09-25 对 ceil26 实测）：这盏灯色温只接受 2700~6500K，
 * 2600 和 6600 都会被拒（invalid params），所以"休息"直接取下限 2700K。
 * "学习"取 4000K：比平时用的 4200K 左右再暖一点点，又不会暖到发黄影响看字。
 */
enum LightPreset {
    /** 晚上学习：中性偏暖、全亮。 */
    STUDY("STUDY", 4000, 100),
    /** 休息：最暖、约四分之一亮度。 */
    REST("REST", 2700, 25);

    /** 面板上显示的名字，同时作为执行成功后的状态文字。 */
    final String label;
    /** 色温，单位 K。 */
    final int colorTemperature;
    /** 亮度百分比，1~100。 */
    final int brightness;

    LightPreset(String label, int colorTemperature, int brightness) {
        this.label = label;
        this.colorTemperature = colorTemperature;
        this.brightness = brightness;
    }
}
