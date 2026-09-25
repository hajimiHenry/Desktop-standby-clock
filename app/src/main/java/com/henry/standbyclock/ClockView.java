package com.henry.standbyclock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.content.res.ResourcesCompat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 整个应用唯一的 View，屏幕上的一切都由它在 {@link #onDraw} 里用 Canvas 画出来——
 * 没有 XML 布局，没有子 View。
 *
 * <p>为什么全部手绘：两种表盘都要求做到系统控件给不了的效果（CRT 磷光的多层辉光叠加、
 * 书法数字逐位翻滚），而且全屏每秒重绘时，一个自绘 View 远比一棵控件树省电。
 *
 * <p>它画这么几样东西，按优先级从高到低盖在一起：
 * <ol>
 *   <li>睡眠提醒（最高，会盖掉一切）</li>
 *   <li>设置浮层 / 设备控制面板 / 流量看板</li>
 *   <li>表盘本身：磷光指针盘或书法数字钟</li>
 * </ol>
 *
 * <p>职责边界：本类只管"画"和"识别点在哪儿"，不做任何业务决策。点击被翻译成
 * {@link UiAction} 交给 MainActivity 去执行；手势方向的判定规则在 ClockStyleSwitching 里。
 *
 * <p>刷新策略是省电的关键：平时每秒只重绘一次（对齐到整秒），只有动画进行中才按帧
 * 重绘；熄屏时完全停止重绘。
 */
public final class ClockView extends View {
    // --- 配色：还原 P3 型琥珀色 CRT 荧光粉的光谱 ---
    // 老式单色显示器的字符不是单一颜色，而是中心炽白、外圈橙红的渐变。
    // 下面几个颜色分别对应这个渐变的各层，叠在一起才有"发光"的观感。
    private static final int BACKGROUND = Color.BLACK;
    private static final int P3_AMBER = Color.rgb(255, 176, 0);       // Classic P3 Phosphor Amber (#FFB000)
    private static final int P3_HOT_CORE = Color.rgb(255, 243, 209);    // Incandescent center trace (#FFF3D1)
    private static final int P3_GLOW = Color.rgb(255, 110, 0);         // Outer phosphor halo (#FF6E00)
    private static final int P3_DIM = Color.rgb(120, 68, 0);           // Raster grid / dim status (#784400)
    private static final int P3_DATE_ACCENT = Color.rgb(255, 145, 0);  // High contrast date accent (#FF9100)

    // --- 配色：书法表盘（暖白墨色落在纸黑底上）---
    // 与上面那套刻意相反：这一套追求安静克制，靠不同灰度区分主次而不是靠发光。
    private static final int INK = Color.rgb(232, 224, 208);           // Primary stroke (#E8E0D0)
    private static final int INK_SECONDARY = Color.rgb(160, 152, 128); // Seconds (#A09880)
    private static final int INK_LABEL = Color.rgb(85, 80, 64);        // Unit label (#555040)
    private static final int INK_RULE = Color.rgb(58, 53, 40);         // Hairline rule (#3A3528)
    private static final int INK_DATE = Color.rgb(106, 96, 80);        // Date line (#6A6050)
    private static final int INK_HALO = Color.rgb(200, 170, 120);      // Faint stage vignette

    // --- 各种动画时长（毫秒）与位移距离 ---
    /** 书法表盘上时分数字翻滚一次的时长。 */
    private static final long DIGIT_TRANSITION_MS = 450L;
    /** 秒数翻滚略快一些：它每秒都在动，太慢会显得拖沓。 */
    private static final long SECOND_TRANSITION_MS = 350L;
    /** 冒号明暗渐变的时长。 */
    private static final long SEPARATOR_TRANSITION_MS = 400L;
    /** 切换表盘的转场时长。 */
    private static final long STYLE_TRANSITION_MS = 380L;
    /** 切表盘时画面竖向滑动的距离，屏幕高度的 12%。只是轻微位移，点到为止。 */
    private static final float STYLE_TRANSITION_DISTANCE = 0.12f;
    /** 设备面板推入推出的时长与横向位移距离（屏幕宽度的 14%）。 */
    private static final long DEVICE_MENU_TRANSITION_MS = 380L;
    private static final float DEVICE_MENU_TRANSITION_DISTANCE = 0.14f;
    /** 面板上状态文字变化时的交叉淡入淡出时长，很短，只求不生硬。 */
    private static final long DEVICE_STATUS_TRANSITION_MS = 180L;
    /** 按钮被点中时闪一下的时长，给用户即时反馈。 */
    private static final long DEVICE_BUTTON_PULSE_MS = 220L;

    /** 当前哪个按钮在闪。用 int 常量而非枚举，纯粹因为只在绘制热路径里比较。 */
    private static final int DEVICE_BUTTON_NONE = 0;
    private static final int DEVICE_BUTTON_LIGHT = 1;
    private static final int DEVICE_BUTTON_DESKTOP = 2;

    /** 冒号明暗两态的不透明度。77/255≈0.3，153/255≈0.6，靠这个呼吸感表示秒在走。 */
    private static final int SEPARATOR_DIM_ALPHA = 77;   // opacity 0.3
    private static final int SEPARATOR_BRIGHT_ALPHA = 153; // opacity 0.6

    /** 书法表盘的数字格位数，绘制顺序为 时 时 分 分 秒 秒。每格独立翻滚。 */
    private static final int SLOT_COUNT = 6;

    private static final String[] WEEKDAYS = {
            "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"
    };

    private static final String[] LONG_WEEKDAYS = {
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
    };

    private static final String[] MONTHS = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
    };

    /**
     * OLED 防烧屏的像素偏移表。
     *
     * <p>OLED 屏幕长期显示同一画面会永久残留暗影（烧屏），而时钟恰恰是最容易烧屏的
     * 场景——指针中心、刻度这些位置几个月都不变。做法是每 5 分钟把整个画面平移几像素，
     * 走遍这 9 个位置（中心 + 周围 8 个方向）后循环，让每个像素的负担摊开。
     * 偏移只有 4 像素，肉眼基本察觉不到。
     */
    private static final float[][] PIXEL_SHIFT = {
            {0f, 0f}, {4f, 0f}, {4f, 4f}, {0f, 4f},
            {-4f, 4f}, {-4f, 0f}, {-4f, -4f}, {0f, -4f}, {4f, -4f}
    };

    // --- 画笔声明：CRT 效果靠同一形状用不同画笔叠画多层来实现 ---
    // 典型的三层结构：最底下一层很粗很淡的辉光（模拟荧光粉的光晕扩散），
    // 中间一层正常粗细的主体，最上面一层极细的高亮"芯"（模拟电子束最强处）。
    // 三层叠加出来的过渡，比单纯给一条线加阴影自然得多。

    /** 刻度线：辉光层 / 主体层 / 芯层。 */
    private final Paint tickGlowPaint = strokePaint(P3_GLOW, 45);
    private final Paint tickPaint = strokePaint(P3_AMBER, 220);
    private final Paint tickCorePaint = strokePaint(P3_HOT_CORE, 255);

    private final Paint widgetTextPaint = textPaint(P3_DATE_ACCENT, Paint.Align.LEFT);

    /** 时针分针（剑形），同样是三层。 */
    private final Paint handGlowPaint = fillPaint(P3_GLOW, 50);
    private final Paint handGradientPaint = fillPaint(P3_AMBER, 255);
    private final Paint handCoreGradientPaint = strokePaint(P3_HOT_CORE, 255);

    /** 秒针（细直线），三层。 */
    private final Paint secondHandGlowPaint = strokePaint(P3_GLOW, 75);
    private final Paint secondHandPaint = strokePaint(P3_AMBER, 255);
    private final Paint secondHandCorePaint = strokePaint(P3_HOT_CORE, 255);

    /** 中心轴：辉光 / 主体 / 芯，外加最中间一个黑点作为镂空。 */
    private final Paint hubGlowPaint = fillPaint(P3_GLOW, 90);
    private final Paint hubPaint = fillPaint(P3_AMBER, 255);
    private final Paint hubCorePaint = fillPaint(P3_HOT_CORE, 255);
    private final Paint hubDotPaint = fillPaint(BACKGROUND, 255);

    /** 扫描线：每隔几像素叠一条几乎透明的黑线，模拟 CRT 的行栅格。 */
    private final Paint scanlinePaint = strokePaint(Color.argb(16, 0, 0, 0), 255);

    private final Paint inkDigitPaint = textPaint(INK, Paint.Align.CENTER);
    private final Paint inkSeparatorPaint = textPaint(INK, Paint.Align.CENTER);
    private final Paint inkSecondPaint = textPaint(INK_SECONDARY, Paint.Align.CENTER);
    private final Paint inkLabelPaint = textPaint(INK_LABEL, Paint.Align.LEFT);
    private final Paint inkDatePaint = textPaint(INK_DATE, Paint.Align.CENTER);
    private final Paint inkRulePaint = fillPaint(INK_RULE, 255);
    private final Paint inkHaloPaint = fillPaint(INK_HALO, 255);

    private final Paint overlayPaint = fillPaint(BACKGROUND, 244);
    private final Paint reminderDimPaint = fillPaint(BACKGROUND, 188);
    private final Paint reminderPanelPaint = fillPaint(BACKGROUND, 255);
    private final Paint reminderLinePaint = strokePaint(P3_GLOW, 220);
    private final Paint reminderTitlePaint = textPaint(P3_AMBER, Paint.Align.CENTER);
    private final Paint reminderBodyPaint = textPaint(P3_DATE_ACCENT, Paint.Align.CENTER);
    private final Paint reminderActionPaint = textPaint(P3_AMBER, Paint.Align.CENTER);
    private final Paint reminderMutedPaint = textPaint(P3_DIM, Paint.Align.CENTER);

    /** 指针形状复用同一个 Path 对象，避免每帧 new 一个造成 GC 抖动。 */
    private final Path handPath = new Path();

    // 书法表盘每个数字格位的翻滚状态：当前值、上一个值、本次翻滚的起始时刻。
    // 三个数组按下标一一对应，下标含义见 SLOT_COUNT。'\0' 表示还没有值。
    private final char[] slotDigits = new char[SLOT_COUNT];
    private final char[] slotPreviousDigits = new char[SLOT_COUNT];
    private final long[] slotTransitionStart = new long[SLOT_COUNT];

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 每秒一次的重绘任务：画一帧，然后把自己排到下一个整秒。 */
    private final Runnable animationTick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            scheduleNextFrame();
        }
    };

    /** 界面在前台、正在走秒。false 时停止一切重绘。 */
    private boolean running;
    /** 环境光触发的熄屏状态，为 true 时 onDraw 只涂黑然后直接返回。 */
    private boolean blackout;
    private boolean bedtimeEnabled = true;
    private boolean bedtimeSoundEnabled = true;
    private boolean bedtimeReminderActive;
    private boolean bedtimeSnoozed;
    private boolean sedentaryEnabled = true;
    private boolean sedentarySoundEnabled = true;
    private boolean sedentaryReminderActive;
    private int sedentaryIntervalMinutes = SedentaryReminder.DEFAULT_INTERVAL_MINUTES;
    private int sedentaryStartHour = SedentaryReminder.DEFAULT_START_HOUR;
    private int sedentaryStartMinute = SedentaryReminder.DEFAULT_START_MINUTE;
    /** 设置面板当前页签：0 = 时钟，1 = 提醒。 */
    private int settingsPage;
    /** 设置浮层是否显示（长按打开）。 */
    private boolean settingsVisible;
    /** 侧滑面板是否显示。 */
    private boolean deviceMenuVisible;
    /** 侧滑面板显示的是哪个：true = 流量看板（左滑），false = 设备控制（右滑）。 */
    private boolean trafficMenuSelected;
    /** 流量数据至少成功加载过一次，用于区分"加载中"和"真的没有数据"。 */
    private boolean trafficDataLoaded;
    private TrafficStatusFormatting.Display trafficDisplay =
            TrafficStatusFormatting.Display.initial();
    private String ceilingLightStatus = "READY";
    private String desktopWakeStatus = "READY";
    // 状态文字变化时，旧文字要淡出、新文字要淡入，所以得留着上一个值和起始时刻。
    // 动画播完后这两个 previous 会被置回 null。
    private String previousCeilingLightStatus;
    private String previousDesktopWakeStatus;
    private long ceilingLightStatusTransitionStartMs;
    private long desktopWakeStatusTransitionStartMs;
    private boolean deviceMenuTransitioning;
    private boolean deviceMenuTransitionOpening;
    private boolean deviceMenuTransitionSwipeLeft;
    private long deviceMenuTransitionStartMs;
    private int activeDeviceButton = DEVICE_BUTTON_NONE;
    private long deviceButtonPulseStartMs;
    private int bedtimeHour = 23;
    private int bedtimeMinute = 30;
    /** 最近一次触摸的坐标，resolveTapAction 靠它判断点在哪个按钮上。 */
    private float lastTouchX;
    private float lastTouchY;
    /** 手指按下时的坐标，用来算滑动位移。 */
    private float touchDownX;
    private float touchDownY;
    /** 触发滑动的最小距离，取系统触摸阈值的 4 倍——宁可迟钝也别误触。 */
    private final float minimumStyleSwipeDistance;
    /** 本次触摸序列已经被判定为滑动，后续事件不再当作点击处理。 */
    private boolean styleSwipeConsumed;
    private boolean autoStyleSwitchEnabled = true;
    private OnStyleSwipeListener onStyleSwipeListener;
    private OnDeviceMenuVisibilityListener onDeviceMenuVisibilityListener;

    private ClockStyle clockStyle = ClockStyle.PHOSPHOR_DIAL;
    /**
     * 正在淡出的旧表盘。非 null 就表示转场动画进行中，这期间两个表盘会同时被画出来，
     * 也用它来拒绝新的切换请求（见 animateClockStyle）。
     */
    private ClockStyle outgoingClockStyle;
    private long styleTransitionStartMs;
    private boolean styleTransitionToNext;
    private int separatorAlphaFrom = SEPARATOR_DIM_ALPHA;
    private int separatorAlphaTo = SEPARATOR_DIM_ALPHA;
    private long separatorTransitionStart;
    /** 上次冒号的奇偶态，-1 表示尚未初始化。秒数奇偶变化时触发一次明暗渐变。 */
    private int separatorParity = -1;

    // 渐变对象创建开销不小，缓存起来，只有尺寸变了才重建。
    // 后面几个 width/height/centerX 字段就是用来检测"尺寸变没变"的。
    private RadialGradient haloGradient;
    private int haloGradientWidth;
    private int haloGradientHeight;
    private float ruleGradientWidth;
    private float ruleGradientCenterX;

    /** 画单个字符时复用的缓冲区，避免每帧为一个字符 new 一个 String。 */
    private final char[] glyphBuffer = new char[1];
    /** 量文字尺寸时复用的矩形，同样是为了避免在绘制热路径里分配对象。 */
    private final Rect textBounds = new Rect();

    /**
     * 点击被翻译成的语义动作。ClockView 只负责判断"点在哪个区域 = 哪个动作"，
     * 具体怎么执行由 MainActivity 决定。
     */
    public enum UiAction {
        NONE,
        BEDTIME_DONE,
        BEDTIME_SNOOZE,
        BEDTIME_MINUS_15,
        BEDTIME_PLUS_15,
        TOGGLE_BEDTIME,
        TOGGLE_BEDTIME_SOUND,
        TOGGLE_AUTO_STYLE_SWITCH,
        PREVIOUS_STYLE,
        NEXT_STYLE,
        CLOSE_SETTINGS,
        TOGGLE_CEILING_LIGHT,
        WAKE_DESKTOP,
        CLOSE_DEVICE_MENU,
        SEDENTARY_DONE,
        TOGGLE_SEDENTARY,
        TOGGLE_SEDENTARY_SOUND,
        SEDENTARY_INTERVAL_MINUS,
        SEDENTARY_INTERVAL_PLUS,
        SEDENTARY_START_MINUS,
        SEDENTARY_START_PLUS
    }

    public interface OnStyleSwipeListener {
        void onStyleSwipe(boolean next);
    }

    public interface OnDeviceMenuVisibilityListener {
        void onDeviceMenuVisibilityChanged(boolean visible);
    }

    public ClockView(Context context) {
        super(context);
        setBackgroundColor(BACKGROUND);
        setKeepScreenOn(true);
        setContentDescription(clockStyle.description());
        // 取系统触摸阈值的 4 倍：这是个每秒都在动的时钟，用户擦屏幕、随手碰一下都很常见，
        // 门槛太低会误切表盘。
        minimumStyleSwipeDistance =
                ViewConfiguration.get(context).getScaledTouchSlop() * 4f;

        // VT323 是复刻 DEC 终端的点阵字体，磷光表盘和所有浮层的文字都用它。
        // font() 在字体加载失败时会退回系统等宽字体，保证不会崩。
        Typeface terminalTypeface = font(context, R.font.vt323_regular, Typeface.MONOSPACE);

        widgetTextPaint.setTypeface(terminalTypeface);
        reminderTitlePaint.setTypeface(terminalTypeface);
        reminderBodyPaint.setTypeface(terminalTypeface);
        reminderActionPaint.setTypeface(terminalTypeface);
        reminderMutedPaint.setTypeface(terminalTypeface);

        // 书法表盘用三种字体分工：数字用 Cormorant Light Italic（细体斜衬线，
        // 就是那个"书法"感的来源），日期用 Cormorant Italic 稍粗一档，
        // "SEC"单位标签用 JetBrains Mono Light 等宽体形成对比。
        Typeface digitTypeface = font(context, R.font.cormorant_light_italic,
                Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        Typeface dateTypeface = font(context, R.font.cormorant_italic, digitTypeface);
        Typeface labelTypeface = font(context, R.font.jetbrains_mono_light, Typeface.MONOSPACE);

        inkDigitPaint.setTypeface(digitTypeface);
        inkSeparatorPaint.setTypeface(digitTypeface);
        inkSecondPaint.setTypeface(digitTypeface);
        inkDatePaint.setTypeface(dateTypeface);
        // 日期和单位标签都加大了字距。这两行字号很小，拉开间距才透气、才好认。
        inkDatePaint.setLetterSpacing(0.25f);
        inkLabelPaint.setTypeface(labelTypeface);
        inkLabelPaint.setLetterSpacing(0.30f);
        inkSeparatorPaint.setAlpha(SEPARATOR_DIM_ALPHA);

        // 指针芯线用平头（BUTT）而不是默认的圆头，否则线的两端会鼓出小圆点，
        // 破坏剑尖的锐利感。
        handCoreGradientPaint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 直接换表盘，不播动画。用于初始化和熄屏时的切换。 */
    public void setClockStyle(ClockStyle style) {
        if (style == null) {
            return;
        }
        outgoingClockStyle = null;
        clockStyle = style;
        setContentDescription(style.description());
        resetCalligraphicState();
        invalidate();
    }

    /**
     * 带转场动画地换表盘。
     *
     * @param next true = 往"下一个"方向切，只影响动画推进方向
     * @return false 表示没切成：目标就是当前表盘，或者上一次转场还没播完。
     *         调用方（MainActivity）收到 false 会稍后重试
     */
    public boolean animateClockStyle(ClockStyle style, boolean next) {
        if (style == null || clockStyle == style || outgoingClockStyle != null) {
            return false;
        }
        outgoingClockStyle = clockStyle;
        clockStyle = style;
        styleTransitionToNext = next;
        styleTransitionStartMs = SystemClock.uptimeMillis();
        setContentDescription(style.description());
        // 书法盘要清掉翻滚状态，否则它一露面就会从几小时前的旧数字翻到现在，很怪。
        if (style == ClockStyle.CALLIGRAPHY) {
            resetCalligraphicState();
        }
        // postInvalidateOnAnimation 会把重绘对齐到屏幕刷新信号（VSYNC），
        // 动画期间用它比 invalidate() 更平滑。
        postInvalidateOnAnimation();
        return true;
    }

    public ClockStyle getClockStyle() {
        return clockStyle;
    }

    public void setOnStyleSwipeListener(OnStyleSwipeListener listener) {
        onStyleSwipeListener = listener;
    }

    public void setOnDeviceMenuVisibilityListener(OnDeviceMenuVisibilityListener listener) {
        onDeviceMenuVisibilityListener = listener;
    }

    public void setAutoStyleSwitchEnabled(boolean enabled) {
        if (autoStyleSwitchEnabled == enabled) {
            return;
        }
        autoStyleSwitchEnabled = enabled;
        invalidate();
    }

    /**
     * 清空书法盘的翻滚状态。
     *
     * <p>作用是让重新露面的表盘不要从"过期的旧数字"开始翻。比如熄屏两小时后亮起，
     * 不清的话六个数字会集体从两小时前的值哗啦啦翻到现在。清空后各格是 '\0'，
     * 第一帧直接显示当前值，不播动画。
     */
    private void resetCalligraphicState() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            slotDigits[i] = '\0';
            slotPreviousDigits[i] = '\0';
            slotTransitionStart[i] = 0L;
        }
        separatorParity = -1;
    }

    /**
     * 进入 / 退出熄屏。这是最主要的省电开关：熄屏后彻底停掉每秒重绘，
     * onDraw 只涂一次黑就返回，OLED 像素完全不发光。
     */
    public void setBlackout(boolean blackout) {
        if (this.blackout == blackout) {
            return;
        }
        this.blackout = blackout;
        if (blackout) {
            // 黑屏了就没有面板可言，顺手收掉，免得亮起来时残留一个半开的面板。
            deviceMenuVisible = false;
            deviceMenuTransitioning = false;
        }
        handler.removeCallbacks(animationTick);
        if (!blackout) {
            // 恢复显示：清掉过期的翻滚状态（理由见 resetCalligraphicState），
            // 并且只有界面确实在前台时才重启走秒循环。
            resetCalligraphicState();
            if (running) {
                scheduleNextFrame();
            }
        }
        invalidate();
    }

    public boolean isBlackout() {
        return blackout;
    }

    public void setBedtimeState(
            boolean enabled,
            int hour,
            int minute,
            boolean active,
            boolean snoozed,
            boolean soundEnabled) {
        bedtimeEnabled = enabled;
        bedtimeHour = hour;
        bedtimeMinute = minute;
        bedtimeReminderActive = active;
        bedtimeSnoozed = snoozed;
        bedtimeSoundEnabled = soundEnabled;
        // 睡眠提醒优先级最高，弹出时把所有浮层挤掉。
        if (active) {
            settingsVisible = false;
            deviceMenuVisible = false;
            deviceMenuTransitioning = false;
        }
        invalidate();
    }

    public boolean isBedtimeReminderActive() {
        return bedtimeReminderActive;
    }

    /** 接收 Service 广播来的久坐提醒状态。 */
    public void setSedentaryState(boolean enabled, boolean active, boolean soundEnabled,
                                  int intervalMinutes, int startHour, int startMinute) {
        sedentaryEnabled = enabled;
        sedentaryReminderActive = active;
        sedentarySoundEnabled = soundEnabled;
        sedentaryIntervalMinutes = intervalMinutes;
        sedentaryStartHour = startHour;
        sedentaryStartMinute = startMinute;
        // 久坐提醒弹出时也要收掉所有浮层，理由同 setBedtimeState。
        if (active) {
            settingsVisible = false;
            deviceMenuVisible = false;
            deviceMenuTransitioning = false;
        }
        invalidate();
    }

    public boolean isSedentaryReminderActive() {
        return sedentaryReminderActive;
    }

    /** 长按切换设置浮层。提醒正在显示、或面板正在做转场动画时忽略请求。 */
    public boolean toggleSettings() {
        if (!bedtimeReminderActive && !sedentaryReminderActive && !deviceMenuTransitioning) {
            deviceMenuVisible = false;
            settingsVisible = !settingsVisible;
            invalidate();
        }
        return settingsVisible;
    }

    public boolean isSettingsVisible() {
        return settingsVisible;
    }

    public void closeSettings() {
        if (!settingsVisible) {
            return;
        }
        settingsVisible = false;
        invalidate();
    }

    /**
     * 直接开关设备面板，不播动画（Activity 进入后台时用它强制收起）。
     * 熄屏、提醒中、设置浮层开着这三种情况下不允许打开。
     */
    public boolean setDeviceMenuVisible(boolean visible) {
        if (visible && (blackout || bedtimeReminderActive || sedentaryReminderActive
                || settingsVisible)) {
            return false;
        }
        if (deviceMenuVisible == visible && !deviceMenuTransitioning) {
            return false;
        }
        deviceMenuTransitioning = false;
        deviceMenuVisible = visible;
        if (visible) {
            settingsVisible = false;
        }
        invalidate();
        return true;
    }

    public boolean isDeviceMenuVisible() {
        return deviceMenuVisible || deviceMenuTransitioning;
    }

    public boolean isTrafficMenuVisible() {
        return deviceMenuVisible && trafficMenuSelected;
    }

    /**
     * 显示"加载中"。只在从没成功加载过时才显示——已经有数据了就让旧数字继续挂着，
     * 每次刷新都闪一下 CHECKING... 反而烦人。
     */
    public void setTrafficLoading() {
        if (!trafficDataLoaded) {
            trafficDisplay = TrafficStatusFormatting.Display.loading();
            invalidate();
        }
    }

    public void setTrafficDisplay(TrafficStatusFormatting.Display display) {
        if (display == null) {
            return;
        }
        trafficDisplay = display;
        trafficDataLoaded = true;
        invalidate();
    }

    public void setTrafficOffline() {
        trafficDisplay = TrafficStatusFormatting.offline(trafficDisplay);
        invalidate();
    }

    public void setTrafficNotConfigured() {
        trafficDisplay = TrafficStatusFormatting.Display.notConfigured();
        trafficDataLoaded = true;
        invalidate();
    }

    /**
     * 更新吸顶灯状态文字，并触发一次交叉淡入淡出动画。
     * 文字没变就直接返回，不然重复刷新会让文字一直在原地抖。
     */
    public void setCeilingLightStatus(String status) {
        String nextStatus = status == null ? "UNKNOWN" : status;
        if (nextStatus.equals(ceilingLightStatus)) {
            return;
        }
        previousCeilingLightStatus = ceilingLightStatus;
        ceilingLightStatus = nextStatus;
        ceilingLightStatusTransitionStartMs = SystemClock.uptimeMillis();
        invalidate();
    }

    public void setDesktopWakeStatus(String status) {
        String nextStatus = status == null ? "UNKNOWN" : status;
        if (nextStatus.equals(desktopWakeStatus)) {
            return;
        }
        previousDesktopWakeStatus = desktopWakeStatus;
        desktopWakeStatus = nextStatus;
        desktopWakeStatusTransitionStartMs = SystemClock.uptimeMillis();
        invalidate();
    }

    /**
     * 带推入 / 推出动画地开关面板。
     *
     * @param swipeLeft 手指划的方向，决定画面往哪边推，做到跟手
     * @return false 表示请求被拒（条件不满足或已有动画在播）
     */
    private boolean animateDeviceMenuVisibility(boolean visible, boolean swipeLeft) {
        if (visible && (blackout || bedtimeReminderActive || sedentaryReminderActive
                || settingsVisible)) {
            return false;
        }
        if (deviceMenuTransitioning || deviceMenuVisible == visible) {
            return false;
        }
        deviceMenuVisible = visible;
        deviceMenuTransitionOpening = visible;
        deviceMenuTransitionSwipeLeft = swipeLeft;
        deviceMenuTransitionStartMs = SystemClock.uptimeMillis();
        deviceMenuTransitioning = true;
        if (visible) {
            settingsVisible = false;
        }
        postInvalidateOnAnimation();
        return true;
    }

    private void startDeviceButtonPulse(int button) {
        activeDeviceButton = button;
        deviceButtonPulseStartMs = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /**
     * 把"点在哪个坐标"翻译成语义动作。
     *
     * <p>因为界面全是手绘的，没有真实控件，所以命中判定只能靠一堆写死的比例区间。
     * 坐标先除以宽高归一化成 0~1 的比例，这样同一套数字在任何屏幕尺寸上都成立。
     * 下面这些魔数必须和对应的 drawXxx 方法里的绘制坐标保持一致——改了按钮位置，
     * 这里也要同步改，否则就会出现"看得见点不着"。
     *
     * <p>判定顺序即优先级，和绘制时的叠加顺序一致：提醒 > 面板 > 设置浮层 > 表盘。
     */
    public UiAction resolveTapAction() {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return UiAction.NONE;
        }
        float x = lastTouchX / width;
        float y = lastTouchY / height;

        // 就寝提醒界面：中间那一条区域里，左半边是 DONE，右半边是 +15 分钟。
        if (bedtimeReminderActive) {
            if (y >= 0.52f && y <= 0.68f) {
                return x < 0.5f ? UiAction.BEDTIME_DONE : UiAction.BEDTIME_SNOOZE;
            }
            return UiAction.NONE;
        }
        // 久坐提醒界面：点面板下半部的任意位置都算按了 OK。
        if (sedentaryReminderActive) {
            if (y >= 0.48f && y <= 0.68f) {
                return UiAction.SEDENTARY_DONE;
            }
            return UiAction.NONE;
        }
        // 转场动画期间一律不响应，免得点到还没停稳的按钮。
        if (deviceMenuTransitioning) {
            return UiAction.NONE;
        }
        if (deviceMenuVisible) {
            // 流量看板是纯展示的，点哪儿都是关闭。
            if (trafficMenuSelected) {
                animateDeviceMenuVisibility(false, !deviceMenuTransitionSwipeLeft);
                return UiAction.CLOSE_DEVICE_MENU;
            }
            // 设备控制面板：中间高度上左右两个按钮。
            if (y >= 0.31f && y <= 0.72f) {
                if (x >= 0.14f && x <= 0.47f) {
                    startDeviceButtonPulse(DEVICE_BUTTON_LIGHT);
                    return UiAction.TOGGLE_CEILING_LIGHT;
                }
                if (x >= 0.53f && x <= 0.86f) {
                    startDeviceButtonPulse(DEVICE_BUTTON_DESKTOP);
                    return UiAction.WAKE_DESKTOP;
                }
            }
            // 没点中任何按钮 = 点了空白处 = 关闭面板。
            // 取反 swipeLeft，让关闭动画沿着当初打开的反方向退回去。
            animateDeviceMenuVisibility(false, !deviceMenuTransitionSwipeLeft);
            return UiAction.CLOSE_DEVICE_MENU;
        }
        // 设置浮层（分页）：顶部页签栏切换 CLOCK / REMINDERS 两页。
        if (settingsVisible) {
            // 页签栏
            if (y >= 0.255f && y <= 0.310f) {
                int newPage = x < 0.5f ? 0 : 1;
                if (newPage != settingsPage) {
                    settingsPage = newPage;
                    invalidate();
                }
                return UiAction.NONE;
            }
            // 关闭按钮（两页共享）
            if (y >= 0.760f && y <= 0.840f) {
                settingsVisible = false;
                invalidate();
                return UiAction.CLOSE_SETTINGS;
            }
            if (settingsPage == 0) {
                // ── CLOCK 页：表盘选择 + 自动轮换 ──
                if (y >= 0.340f && y <= 0.430f) {
                    if (x < 0.43f) return UiAction.PREVIOUS_STYLE;
                    if (x > 0.57f) return UiAction.NEXT_STYLE;
                }
                if (y > 0.430f && y <= 0.500f) {
                    return UiAction.TOGGLE_AUTO_STYLE_SWITCH;
                }
            } else {
                // ── REMINDERS 页 ──
                // 就寝时间调节
                if (y >= 0.350f && y <= 0.420f) {
                    if (x < 0.43f) return UiAction.BEDTIME_MINUS_15;
                    if (x > 0.57f) return UiAction.BEDTIME_PLUS_15;
                }
                // 就寝 ENABLED（左半）/ SOUND（右半）
                if (y > 0.420f && y <= 0.480f) {
                    return x < 0.5f ? UiAction.TOGGLE_BEDTIME : UiAction.TOGGLE_BEDTIME_SOUND;
                }
                // 久坐间隔调节
                if (y >= 0.520f && y <= 0.575f) {
                    if (x < 0.43f) return UiAction.SEDENTARY_INTERVAL_MINUS;
                    if (x > 0.57f) return UiAction.SEDENTARY_INTERVAL_PLUS;
                }
                // 久坐开始时间调节
                if (y > 0.575f && y <= 0.630f) {
                    if (x < 0.43f) return UiAction.SEDENTARY_START_MINUS;
                    if (x > 0.57f) return UiAction.SEDENTARY_START_PLUS;
                }
                // 久坐 ENABLED（左半）/ SOUND（右半）
                if (y > 0.630f && y <= 0.690f) {
                    return x < 0.5f ? UiAction.TOGGLE_SEDENTARY
                            : UiAction.TOGGLE_SEDENTARY_SOUND;
                }
            }
            return UiAction.NONE;
        }
        return UiAction.NONE;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /**
     * 触摸处理。核心思路是"滑动优先"：手指一动就先看够不够格算滑动，
     * 够了就自己消费掉；不够就把事件交还给 View 基类，让平台自己那套点击 / 长按
     * 状态机去处理。
     *
     * <p>不在这里直接调 performClick 的原因见下方原注释——基类的 onTouchEvent
     * 已经会调它了，自己再调一次会导致一次普通点击被响应两遍。
     */
    // Non-swipe sequences are delegated to View.onTouchEvent, which invokes performClick and
    // preserves the platform's click/long-click state machine. Calling performClick here would
    // dispatch an ordinary tap twice.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        // 转场动画期间吞掉所有触摸，防止连划导致状态错乱。
        if (deviceMenuTransitioning && !styleSwipeConsumed) {
            setPressed(false);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            lastTouchX = touchDownX;
            lastTouchY = touchDownY;
            styleSwipeConsumed = false;
        } else if (action == MotionEvent.ACTION_MOVE && !styleSwipeConsumed) {
            // 门槛取"系统阈值的 4 倍"和"屏幕高度的 10%"里较大的那个，
            // 保证在大屏上也需要划足够长的距离，手感才一致。
            float threshold = Math.max(minimumStyleSwipeDistance, getHeight() * 0.10f);
            float deltaX = event.getX() - touchDownX;
            float deltaY = event.getY() - touchDownY;
            // 先判横划（开面板），再判竖划（切表盘）。两者用的是同一套
            // "位移够长 + 方向压倒性"规则，只是 X / Y 互换，所以不会互相误判。
            ClockStyleSwitching.HorizontalSwipeDirection horizontalDirection =
                    ClockStyleSwitching.resolveHorizontalSwipe(deltaX, deltaY, threshold);
            if (horizontalDirection != ClockStyleSwitching.HorizontalSwipeDirection.NONE
                    && canSwipeDeviceMenu()) {
                boolean visible = !deviceMenuVisible;
                boolean swipeLeft = horizontalDirection
                        == ClockStyleSwitching.HorizontalSwipeDirection.LEFT;
                // 左划开流量看板，右划开设备控制。只在打开时决定内容，
                // 关闭时保持原样，这样关闭动画播的还是刚才那个面板。
                if (visible) {
                    trafficMenuSelected =
                            ClockStyleSwitching.opensTrafficDashboard(horizontalDirection);
                }
                if (!animateDeviceMenuVisibility(visible, swipeLeft)) {
                    return true;
                }
                styleSwipeConsumed = true;
                cancelDefaultTouchHandling(event);
                if (onDeviceMenuVisibilityListener != null) {
                    onDeviceMenuVisibilityListener.onDeviceMenuVisibilityChanged(visible);
                }
                return true;
            }
            ClockStyleSwitching.SwipeDirection direction = ClockStyleSwitching.resolveSwipe(
                    deltaX,
                    deltaY,
                    threshold);
            if (direction != ClockStyleSwitching.SwipeDirection.NONE && canSwipeClockStyle()) {
                styleSwipeConsumed = true;
                cancelDefaultTouchHandling(event);
                if (onStyleSwipeListener != null) {
                    onStyleSwipeListener.onStyleSwipe(
                            direction == ClockStyleSwitching.SwipeDirection.NEXT);
                }
                return true;
            }
        }
        // 已经判成滑动了：吞掉后续所有事件直到手指抬起，标记同时复位。
        // 不吞的话手指抬起会被当成一次点击，滑完还顺带触发个按钮。
        if (styleSwipeConsumed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                styleSwipeConsumed = false;
                setPressed(false);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** 什么情况下允许滑动切表盘：没熄屏、没提醒、没浮层、也没有动画在播。 */
    private boolean canSwipeClockStyle() {
        return !blackout
                && !bedtimeReminderActive
                && !sedentaryReminderActive
                && !settingsVisible
                && !deviceMenuVisible
                && !deviceMenuTransitioning
                && outgoingClockStyle == null;
    }

    /** 同上，但少一个条件：面板已经开着时也允许横划（那是要关掉它）。 */
    private boolean canSwipeDeviceMenu() {
        return !blackout
                && !bedtimeReminderActive
                && !sedentaryReminderActive
                && !settingsVisible
                && !deviceMenuTransitioning
                && outgoingClockStyle == null;
    }

    /**
     * 手势被判成滑动后，伪造一个 ACTION_CANCEL 事件喂给基类。
     *
     * <p>为什么必须这么做：手指按下时基类已经启动了长按计时器、也把 View 置成按下态。
     * 如果不明确告诉它"这次交互取消了"，滑动结束后可能冒出一个长按事件，
     * 按下的高亮效果也会残留。recycle() 是把事件对象还给系统的对象池。
     */
    private void cancelDefaultTouchHandling(MotionEvent source) {
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);
        cancel.recycle();
        setPressed(false);
    }

    /** 界面回到前台，开始走秒。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        invalidate();
        if (!blackout) {
            scheduleNextFrame();
        }
    }

    /** 界面进入后台，停止一切重绘。 */
    public void stop() {
        running = false;
        handler.removeCallbacks(animationTick);
    }

    /**
     * 安排下一次走秒重绘，并且<em>对齐到整秒</em>。
     *
     * <p>这是本类最关键的省电设计。如果简单地每隔 1000ms 重绘一次，起始时刻的偏移
     * 会一直留着（比如永远在 .37 秒时刷新），秒针跳动就和真实秒不同步，长期还会漂移。
     * 这里用 1000 - (当前毫秒 % 1000) 精确算出距离下一个整秒还有多久，
     * 每次都重新校准，永不累积误差。
     *
     * <p>额外加的 15ms 是安全余量：Handler 的回调时机只是"不早于"，
     * 稍微晚一点点能确保醒来时确实已经跨过整秒，不会读到上一秒的时间。
     *
     * <p>注意这里一秒才画一帧，不是 60 帧。只有动画进行中的部分会另行调用
     * postInvalidateOnAnimation 提高到逐帧。
     */
    private void scheduleNextFrame() {
        handler.removeCallbacks(animationTick);
        if (!running || blackout) {
            return;
        }
        // Discrete 1-second step ticking: schedule top of next second
        long nowMs = System.currentTimeMillis();
        long delay = 1000L - (nowMs % 1000L) + 15L;
        handler.postDelayed(animationTick, delay);
    }

    /** View 被移出窗口时务必停掉定时器，否则 Handler 会持有引用造成内存泄漏。 */
    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    /**
     * 总绘制入口，决定这一帧画什么、按什么顺序叠。
     *
     * <p>流程是：涂黑底 → （熄屏则到此为止）→ 应用防烧屏偏移 → 画表盘 →
     * 按优先级在上面盖一层浮层 → 给浮层补扫描线。
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BACKGROUND);
        // 熄屏：涂完黑就走，一个像素都不多画。但两种提醒可以穿透熄屏——
        // 它们本来就是要在暗房里引起注意的。
        if (blackout && !bedtimeReminderActive && !sedentaryReminderActive) {
            return;
        }

        int width = getWidth();
        int height = getHeight();
        // 布局还没完成时宽高为 0，此时画什么都没意义。
        if (width <= 0 || height <= 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        // 每 5 分钟换一个防烧屏偏移位置，走完 9 个位置后循环。
        int shiftIndex = (now.getMinute() / 5) % PIXEL_SHIFT.length;

        // 面板转场有自己一整套双层绘制逻辑，走单独的分支。
        if (deviceMenuTransitioning && !bedtimeReminderActive
                && !sedentaryReminderActive && !settingsVisible) {
            drawDeviceMenuTransition(canvas, now, shiftIndex, width, height);
            return;
        }

        // save / translate / restore：把画布整体平移几像素来实现防烧屏，
        // 画完必须 restore 复位，否则偏移会累积到后面的浮层上。
        canvas.save();
        canvas.translate(PIXEL_SHIFT[shiftIndex][0], PIXEL_SHIFT[shiftIndex][1]);
        drawClockFaces(canvas, now, width, height);
        canvas.restore();

        // 浮层四选一，if-else 的顺序就是优先级。
        if (bedtimeReminderActive) {
            drawBedtimeReminder(canvas, now, width, height);
        } else if (sedentaryReminderActive) {
            drawSedentaryReminder(canvas, now, width, height);
        } else if (settingsVisible) {
            drawSettings(canvas, width, height);
        } else if (deviceMenuVisible) {
            drawUtilityMenu(canvas, width, height);
        }

        // 浮层也要盖一层扫描线才像同一块 CRT 屏幕。单独补是因为书法表盘本身
        // 没有扫描线（那是磷光盘专属的），但它上面的浮层仍然要有。
        if (bedtimeReminderActive || sedentaryReminderActive
                || settingsVisible || deviceMenuVisible) {
            drawScanlines(canvas, width, height);
        }

    }

    /**
     * 面板转场动画的绘制：时钟层和菜单层同时画，各自带位移和透明度。
     * 位移和透明度的比例由 DeviceMenuMotion 算，这里只负责把比例变成像素并画出来。
     */

    private void drawDeviceMenuTransition(
            Canvas canvas,
            LocalDateTime now,
            int shiftIndex,
            int width,
            int height) {
        long nowMs = SystemClock.uptimeMillis();
        float progress = transitionProgress(
                deviceMenuTransitionStartMs, DEVICE_MENU_TRANSITION_MS, nowMs);
        // 动画播完：清标记，画一帧静止的最终状态，不再请求下一帧。
        if (progress >= 1f) {
            deviceMenuTransitioning = false;
            drawClockScreenLayer(canvas, now, shiftIndex, width, height, 0f, 255);
            if (deviceMenuVisible) {
                drawDeviceMenuLayer(canvas, width, height, 0f, 255);
            }
            return;
        }

        float eased = ease(progress);
        float travel = width * DEVICE_MENU_TRANSITION_DISTANCE;
        DeviceMenuMotion.Frame frame = DeviceMenuMotion.resolve(
                deviceMenuTransitionOpening, deviceMenuTransitionSwipeLeft, eased);
        float clockOffset = frame.clockOffsetFactor * travel;
        float menuOffset = frame.menuOffsetFactor * travel;
        int clockAlpha = Math.round(255f * frame.clockAlpha);
        int menuAlpha = Math.round(255f * frame.menuAlpha);

        drawClockScreenLayer(
                canvas, now, shiftIndex, width, height, clockOffset, clockAlpha);
        drawDeviceMenuLayer(canvas, width, height, menuOffset, menuAlpha);
        // 动画还没完，请求下一帧。这是动画能持续下去的驱动力。
        postInvalidateOnAnimation();
    }

    /**
     * 把时钟画到一个独立的透明图层上。
     *
     * <p>用 saveLayerAlpha 而不是给每支画笔单独设 alpha：表盘是几十次绘制叠出来的，
     * 逐笔设 alpha 会让重叠处的颜色算错（半透明叠半透明会变得更不透明）。
     * 单开一层、整层统一施加透明度，才能得到正确的淡入淡出效果。代价是多一次
     * 离屏缓冲，但只在转场那 380ms 内发生，可以接受。
     */
    private void drawClockScreenLayer(
            Canvas canvas,
            LocalDateTime now,
            int shiftIndex,
            int width,
            int height,
            float offsetX,
            int alpha) {
        int layer = canvas.saveLayerAlpha(0f, 0f, width, height, alpha);
        canvas.translate(offsetX, 0f);
        canvas.save();
        canvas.translate(PIXEL_SHIFT[shiftIndex][0], PIXEL_SHIFT[shiftIndex][1]);
        drawClockFaces(canvas, now, width, height);
        canvas.restore();
        canvas.restoreToCount(layer);
    }

    private void drawDeviceMenuLayer(
            Canvas canvas, int width, int height, float offsetX, int alpha) {
        int layer = canvas.saveLayerAlpha(0f, 0f, width, height, alpha);
        canvas.translate(offsetX, 0f);
        drawUtilityMenu(canvas, width, height);
        drawScanlines(canvas, width, height);
        canvas.restoreToCount(layer);
    }

    /** 侧滑面板画哪一个，取决于当初是左划还是右划打开的。 */
    private void drawUtilityMenu(Canvas canvas, int width, int height) {
        if (trafficMenuSelected) {
            drawTrafficMenu(canvas, width, height);
        } else {
            drawDeviceMenu(canvas, width, height);
        }
    }

    /**
     * 画表盘。没有转场时就画当前这一个；转场期间两个表盘同时画，
     * 一个往上滑出淡出，另一个从下滑入淡入。
     */
    private void drawClockFaces(
            Canvas canvas, LocalDateTime now, int width, int height) {
        // 常态：一个表盘，全不透明，无位移。
        if (outgoingClockStyle == null) {
            drawClockStyleLayer(canvas, clockStyle, now, width, height, 0f, 255);
            return;
        }

        long nowMs = SystemClock.uptimeMillis();
        float progress = transitionProgress(styleTransitionStartMs, STYLE_TRANSITION_MS, nowMs);
        if (progress >= 1f) {
            ClockStyle completedOutgoingStyle = outgoingClockStyle;
            outgoingClockStyle = null;
            // 如果刚淡出的是书法盘，清掉它的翻滚状态。下次它再上场时才不会
            // 从这一刻的旧数字开始翻。
            if (completedOutgoingStyle == ClockStyle.CALLIGRAPHY) {
                resetCalligraphicState();
            }
            drawClockStyleLayer(canvas, clockStyle, now, width, height, 0f, 255);
            return;
        }

        // 竖向版的推屏：和 DeviceMenuMotion 同样的思路，只是这里方向是上下，
        // 且逻辑简单到没必要单独抽类，就地算了。
        float eased = ease(progress);
        float travel = height * STYLE_TRANSITION_DISTANCE;
        float outgoingDirection = styleTransitionToNext ? -1f : 1f;
        float outgoingOffset = outgoingDirection * travel * eased;
        float incomingOffset = -outgoingDirection * travel * (1f - eased);
        int outgoingAlpha = Math.round(255f * (1f - eased));
        int incomingAlpha = Math.round(255f * eased);

        drawClockStyleLayer(
                canvas, outgoingClockStyle, now, width, height,
                outgoingOffset, outgoingAlpha);
        drawClockStyleLayer(
                canvas, clockStyle, now, width, height,
                incomingOffset, incomingAlpha);
        postInvalidateOnAnimation();
    }

    private void drawClockStyleLayer(
            Canvas canvas,
            ClockStyle style,
            LocalDateTime now,
            int width,
            int height,
            float offsetY,
            int alpha) {
        int layer = canvas.saveLayerAlpha(0f, 0f, width, height, alpha);
        canvas.translate(0f, offsetY);
        if (style == ClockStyle.CALLIGRAPHY) {
            drawCalligraphicFace(canvas, now, width, height);
        } else {
            // 磷光盘分四步叠出来，顺序不能乱：刻度在最底下，
            // 然后是日期小组件，再是指针，最后扫描线盖在所有东西之上。
            drawSymmetricFillDial(canvas, width, height);
            drawRightInfoWidget(canvas, now, width, height);
            drawPhosphorSharpHands(canvas, now, width, height);
            drawScanlines(canvas, width, height);
        }
        canvas.restoreToCount(layer);
    }

    /**
     * 画睡眠提醒。半透明黑幕压暗背后的表盘，中间一块实心面板，
     * 上面是标题、就寝时间，下面是 DONE 和 +15 MIN 两个按钮。
     */

    private void drawBedtimeReminder(
            Canvas canvas, LocalDateTime now, int width, int height) {
        canvas.drawRect(0f, 0f, width, height, reminderDimPaint);

        // 提醒可能整晚挂着不动，比表盘更容易烧屏，所以偏移换成每分钟一次
        // （表盘是每 5 分钟）。
        int shiftIndex = now.getMinute() % PIXEL_SHIFT.length;
        canvas.save();
        canvas.translate(PIXEL_SHIFT[shiftIndex][0], PIXEL_SHIFT[shiftIndex][1]);

        float left = width * 0.27f;
        float right = width * 0.73f;
        canvas.drawRect(
                width * 0.245f,
                height * 0.305f,
                width * 0.755f,
                height * 0.705f,
                reminderPanelPaint);
        reminderLinePaint.setStrokeWidth(Math.max(2f, height * 0.0022f));
        reminderLinePaint.setAlpha(225);
        canvas.drawLine(left, height * 0.335f, right, height * 0.335f, reminderLinePaint);
        canvas.drawLine(left, height * 0.675f, right, height * 0.675f, reminderLinePaint);

        // 标题加一层橙色柔光（无偏移的阴影），模仿 CRT 亮字的光晕。
        // 用完必须 clearShadowLayer，因为画笔是复用的，不清会污染后面的文字。
        reminderTitlePaint.setTextSize(height * 0.084f);
        reminderTitlePaint.setShadowLayer(
                height * 0.012f, 0f, 0f, Color.argb(125, 255, 110, 0));
        canvas.drawText("TIME TO REST", width * 0.5f, height * 0.445f,
                reminderTitlePaint);
        reminderTitlePaint.clearShadowLayer();

        reminderBodyPaint.setTextSize(height * 0.046f);
        canvas.drawText(
                String.format(Locale.US, "BEDTIME %02d:%02d", bedtimeHour, bedtimeMinute),
                width * 0.5f,
                height * 0.515f,
                reminderBodyPaint);

        reminderActionPaint.setTextSize(height * 0.049f);
        reminderMutedPaint.setTextSize(height * 0.047f);
        float actionY = height * 0.605f;
        canvas.drawText("DONE", width * 0.415f, actionY, reminderActionPaint);
        canvas.drawText("+15 MIN", width * 0.585f, actionY, reminderMutedPaint);

        // 两个按钮的取景框。DONE 传 true 画成高亮（默认选项），
        // +15 MIN 传 false 画成暗色。
        drawFocusBrackets(canvas, width * 0.415f, height * 0.588f,
                width * 0.135f, height * 0.080f, true);
        drawFocusBrackets(canvas, width * 0.585f, height * 0.588f,
                width * 0.150f, height * 0.080f, false);
        canvas.restore();
    }

    /**
     * 画久坐提醒。和睡眠提醒结构一致，但更简洁：只有一个 OK 按钮。
     * 标题、正文、按钮的坐标和字号与睡眠提醒相近，保持视觉一致性。
     */
    private void drawSedentaryReminder(
            Canvas canvas, LocalDateTime now, int width, int height) {
        canvas.drawRect(0f, 0f, width, height, reminderDimPaint);

        // 防烧屏偏移，同就寝提醒：每分钟换一个位置。
        int shiftIndex = now.getMinute() % PIXEL_SHIFT.length;
        canvas.save();
        canvas.translate(PIXEL_SHIFT[shiftIndex][0], PIXEL_SHIFT[shiftIndex][1]);

        float left = width * 0.27f;
        float right = width * 0.73f;
        canvas.drawRect(
                width * 0.245f,
                height * 0.305f,
                width * 0.755f,
                height * 0.705f,
                reminderPanelPaint);
        reminderLinePaint.setStrokeWidth(Math.max(2f, height * 0.0022f));
        reminderLinePaint.setAlpha(225);
        canvas.drawLine(left, height * 0.335f, right, height * 0.335f, reminderLinePaint);
        canvas.drawLine(left, height * 0.675f, right, height * 0.675f, reminderLinePaint);

        reminderTitlePaint.setTextSize(height * 0.084f);
        reminderTitlePaint.setShadowLayer(
                height * 0.012f, 0f, 0f, Color.argb(125, 255, 110, 0));
        canvas.drawText("TIME TO MOVE", width * 0.5f, height * 0.445f,
                reminderTitlePaint);
        reminderTitlePaint.clearShadowLayer();

        reminderBodyPaint.setTextSize(height * 0.042f);
        canvas.drawText("STAND UP AND STRETCH",
                width * 0.5f, height * 0.515f, reminderBodyPaint);

        reminderActionPaint.setTextSize(height * 0.052f);
        canvas.drawText("OK", width * 0.5f, height * 0.605f, reminderActionPaint);

        drawFocusBrackets(canvas, width * 0.5f, height * 0.588f,
                width * 0.12f, height * 0.080f, true);
        canvas.restore();
    }

    /**
     * 画设置浮层（分页）。所有坐标都是屏幕宽高的比例，必须和 resolveTapAction 里的
     * 命中区间对得上，否则会出现看得见点不着的按钮。
     *
     * <p>两个页签：CLOCK（表盘 + 自动轮换）、REMINDERS（就寝 + 久坐的全部控件）。
     */
    private void drawSettings(Canvas canvas, int width, int height) {
        canvas.drawRect(0f, 0f, width, height, overlayPaint);

        float left = width * 0.25f;
        float right = width * 0.75f;
        reminderLinePaint.setStrokeWidth(Math.max(2f, height * 0.0022f));
        canvas.drawLine(left, height * 0.155f, right, height * 0.155f, reminderLinePaint);
        canvas.drawLine(left, height * 0.875f, right, height * 0.875f, reminderLinePaint);

        // ── 标题 ──
        reminderTitlePaint.setTextSize(height * 0.048f);
        canvas.drawText("SETTINGS", width * 0.5f, height * 0.215f, reminderTitlePaint);

        // ── 页签栏 ──
        float tabY = height * 0.278f;
        float tabClockX = width * 0.38f;
        float tabRemindersX = width * 0.62f;
        reminderActionPaint.setTextSize(height * 0.032f);
        reminderMutedPaint.setTextSize(height * 0.032f);
        // 当前页签用 action 色高亮，非当前页签用 muted 色
        canvas.drawText("CLOCK", tabClockX, tabY,
                settingsPage == 0 ? reminderActionPaint : reminderMutedPaint);
        canvas.drawText("REMINDERS", tabRemindersX, tabY,
                settingsPage == 1 ? reminderActionPaint : reminderMutedPaint);
        // 活动页签下方的短横线
        float tabLineY = height * 0.295f;
        float tabLineHalf = width * 0.06f;
        float activeTabX = settingsPage == 0 ? tabClockX : tabRemindersX;
        canvas.drawLine(activeTabX - tabLineHalf, tabLineY,
                activeTabX + tabLineHalf, tabLineY, reminderLinePaint);

        if (settingsPage == 0) {
            drawSettingsClockPage(canvas, width, height);
        } else {
            drawSettingsRemindersPage(canvas, width, height);
        }

        // ── 关闭按钮（两页共享）──
        reminderActionPaint.setTextSize(height * 0.034f);
        canvas.drawText("[ CLOSE ]", width * 0.5f, height * 0.800f, reminderActionPaint);

        if (bedtimeSnoozed) {
            reminderMutedPaint.setTextSize(height * 0.022f);
            canvas.drawText("SNOOZED FOR THIS SESSION", width * 0.5f,
                    height * 0.845f, reminderMutedPaint);
        }
    }

    /** CLOCK 页签内容：表盘选择 + 自动轮换。 */
    private void drawSettingsClockPage(Canvas canvas, int width, int height) {
        // ── 表盘选择 ──
        reminderMutedPaint.setTextSize(height * 0.026f);
        canvas.drawText("CLOCK STYLE", width * 0.5f, height * 0.350f, reminderMutedPaint);

        reminderActionPaint.setTextSize(height * 0.039f);
        reminderBodyPaint.setTextSize(height * 0.044f);
        canvas.drawText("[<]", width * 0.365f, height * 0.405f, reminderActionPaint);
        canvas.drawText(clockStyle.label(), width * 0.5f, height * 0.407f,
                reminderBodyPaint);
        canvas.drawText("[>]", width * 0.635f, height * 0.405f, reminderActionPaint);

        // ── 自动轮换 ──
        reminderMutedPaint.setTextSize(height * 0.031f);
        reminderActionPaint.setTextSize(height * 0.036f);
        canvas.drawText("AUTO SWITCH", width * 0.455f, height * 0.475f,
                reminderMutedPaint);
        canvas.drawText(autoStyleSwitchEnabled ? "ON / 1H" : "OFF", width * 0.570f,
                height * 0.475f, reminderActionPaint);
    }

    /** REMINDERS 页签内容：就寝和久坐提醒的全部控件。 */
    private void drawSettingsRemindersPage(Canvas canvas, int width, int height) {
        // ── 就寝提醒 ──
        reminderMutedPaint.setTextSize(height * 0.026f);
        canvas.drawText("BEDTIME", width * 0.5f, height * 0.340f, reminderMutedPaint);

        // 时间调节
        reminderActionPaint.setTextSize(height * 0.036f);
        reminderBodyPaint.setTextSize(height * 0.048f);
        canvas.drawText("[-15]", width * 0.355f, height * 0.393f, reminderActionPaint);
        canvas.drawText(
                String.format(Locale.US, "%02d:%02d", bedtimeHour, bedtimeMinute),
                width * 0.5f, height * 0.396f, reminderBodyPaint);
        canvas.drawText("[+15]", width * 0.645f, height * 0.393f, reminderActionPaint);

        // ENABLED + SOUND 合一行
        reminderMutedPaint.setTextSize(height * 0.028f);
        reminderActionPaint.setTextSize(height * 0.032f);
        float toggleY = height * 0.453f;
        canvas.drawText("ENABLED", width * 0.365f, toggleY, reminderMutedPaint);
        canvas.drawText(bedtimeEnabled ? "ON" : "OFF", width * 0.455f, toggleY,
                reminderActionPaint);
        canvas.drawText("SOUND", width * 0.565f, toggleY, reminderMutedPaint);
        canvas.drawText(bedtimeSoundEnabled ? "ON" : "OFF", width * 0.640f, toggleY,
                reminderActionPaint);

        // ── 久坐提醒 ──
        reminderMutedPaint.setTextSize(height * 0.026f);
        canvas.drawText("SEDENTARY", width * 0.5f, height * 0.510f, reminderMutedPaint);

        // 间隔调节
        reminderActionPaint.setTextSize(height * 0.036f);
        reminderBodyPaint.setTextSize(height * 0.042f);
        reminderMutedPaint.setTextSize(height * 0.024f);
        float intervalY = height * 0.555f;
        canvas.drawText("EVERY", width * 0.315f, intervalY, reminderMutedPaint);
        canvas.drawText("[-]", width * 0.380f, intervalY, reminderActionPaint);
        canvas.drawText(sedentaryIntervalMinutes + " MIN", width * 0.5f, intervalY,
                reminderBodyPaint);
        canvas.drawText("[+]", width * 0.620f, intervalY, reminderActionPaint);

        // 开始时间调节
        float startY = height * 0.610f;
        canvas.drawText("FROM", width * 0.320f, startY, reminderMutedPaint);
        canvas.drawText("[-]", width * 0.380f, startY, reminderActionPaint);
        canvas.drawText(
                String.format(Locale.US, "%02d:%02d",
                        sedentaryStartHour, sedentaryStartMinute),
                width * 0.5f, startY, reminderBodyPaint);
        canvas.drawText("[+]", width * 0.620f, startY, reminderActionPaint);

        // ENABLED + SOUND 合一行
        reminderMutedPaint.setTextSize(height * 0.028f);
        reminderActionPaint.setTextSize(height * 0.032f);
        float sedToggleY = height * 0.665f;
        canvas.drawText("ENABLED", width * 0.365f, sedToggleY, reminderMutedPaint);
        canvas.drawText(sedentaryEnabled ? "ON" : "OFF", width * 0.455f, sedToggleY,
                reminderActionPaint);
        canvas.drawText("SOUND", width * 0.565f, sedToggleY, reminderMutedPaint);
        canvas.drawText(sedentarySoundEnabled ? "ON" : "OFF", width * 0.640f, sedToggleY,
                reminderActionPaint);
    }

    /** 画设备控制面板：左右两个大按钮，分别控灯和唤醒电脑，下面各带一行状态文字。 */
    private void drawDeviceMenu(Canvas canvas, int width, int height) {
        canvas.drawRect(0f, 0f, width, height, overlayPaint);

        float left = width * 0.17f;
        float right = width * 0.83f;
        reminderLinePaint.setStrokeWidth(Math.max(2f, height * 0.0022f));
        canvas.drawLine(left, height * 0.175f, right, height * 0.175f, reminderLinePaint);
        canvas.drawLine(left, height * 0.825f, right, height * 0.825f, reminderLinePaint);

        reminderTitlePaint.setTextSize(height * 0.064f);
        canvas.drawText("DEVICE CONTROL", width * 0.5f, height * 0.255f,
                reminderTitlePaint);

        float buttonCenterY = height * 0.515f;
        float buttonWidth = width * 0.29f;
        float buttonHeight = height * 0.34f;
        float lightCenterX = width * 0.305f;
        float desktopCenterX = width * 0.695f;
        long nowMs = SystemClock.uptimeMillis();
        float buttonPulseProgress = transitionProgress(
                deviceButtonPulseStartMs, DEVICE_BUTTON_PULSE_MS, nowMs);
        boolean buttonPulseAnimating = activeDeviceButton != DEVICE_BUTTON_NONE
                && buttonPulseProgress < 1f;
        drawDeviceButtonBrackets(
                canvas, lightCenterX, buttonCenterY, buttonWidth, buttonHeight,
                activeDeviceButton == DEVICE_BUTTON_LIGHT && buttonPulseAnimating,
                buttonPulseProgress);
        drawDeviceButtonBrackets(
                canvas, desktopCenterX, buttonCenterY, buttonWidth, buttonHeight,
                activeDeviceButton == DEVICE_BUTTON_DESKTOP && buttonPulseAnimating,
                buttonPulseProgress);
        // 闪烁播完了就清掉标记，下一帧不再进入闪烁分支。
        if (!buttonPulseAnimating) {
            activeDeviceButton = DEVICE_BUTTON_NONE;
        }

        reminderActionPaint.setTextSize(height * 0.055f);
        canvas.drawText("CEILING LIGHT", lightCenterX, height * 0.475f,
                reminderActionPaint);
        canvas.drawText("WAKE DESKTOP", desktopCenterX, height * 0.475f,
                reminderActionPaint);

        reminderBodyPaint.setTextSize(height * 0.047f);
        boolean lightStatusAnimating = drawDeviceStatusTransition(
                canvas,
                previousCeilingLightStatus,
                ceilingLightStatus,
                ceilingLightStatusTransitionStartMs,
                lightCenterX,
                height * 0.575f,
                height,
                nowMs);
        boolean desktopStatusAnimating = drawDeviceStatusTransition(
                canvas,
                previousDesktopWakeStatus,
                desktopWakeStatus,
                desktopWakeStatusTransitionStartMs,
                desktopCenterX,
                height * 0.575f,
                height,
                nowMs);
        if (!lightStatusAnimating) {
            previousCeilingLightStatus = null;
        }
        if (!desktopStatusAnimating) {
            previousDesktopWakeStatus = null;
        }

        reminderMutedPaint.setTextSize(height * 0.028f);
        canvas.drawText("SWIPE OR TAP OUTSIDE TO CLOSE", width * 0.5f,
                height * 0.775f, reminderMutedPaint);

        // 这个面板上可能同时有三个动画在跑（按钮闪烁 + 两处状态文字切换），
        // 任意一个没播完就继续请求下一帧；全播完就停下来，不再空耗。
        if (buttonPulseAnimating || lightStatusAnimating || desktopStatusAnimating) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * 画流量看板：左右两栏分别是 HostVDS 和机场订阅，每栏三行文字。
     * 内容已经由 TrafficStatusFormatting 排好，这里只管往固定位置放，不做任何判断。
     */
    private void drawTrafficMenu(Canvas canvas, int width, int height) {
        canvas.drawRect(0f, 0f, width, height, overlayPaint);

        float left = width * 0.17f;
        float right = width * 0.83f;
        reminderLinePaint.setStrokeWidth(Math.max(2f, height * 0.0022f));
        canvas.drawLine(left, height * 0.175f, right, height * 0.175f, reminderLinePaint);
        canvas.drawLine(left, height * 0.825f, right, height * 0.825f, reminderLinePaint);

        reminderTitlePaint.setTextSize(height * 0.064f);
        canvas.drawText("TRAFFIC STATUS", width * 0.5f, height * 0.255f,
                reminderTitlePaint);

        float panelCenterY = height * 0.515f;
        float panelWidth = width * 0.31f;
        float panelHeight = height * 0.40f;
        float hostCenterX = width * 0.305f;
        float sanmaoCenterX = width * 0.695f;
        drawFocusBrackets(
                canvas, hostCenterX, panelCenterY, panelWidth, panelHeight, true);
        drawFocusBrackets(
                canvas, sanmaoCenterX, panelCenterY, panelWidth, panelHeight, true);

        reminderActionPaint.setTextSize(height * 0.050f);
        canvas.drawText(
                DeviceControlConfig.TRAFFIC_HOST_LABEL,
                hostCenterX,
                height * 0.405f,
                reminderActionPaint);
        canvas.drawText(
                DeviceControlConfig.TRAFFIC_SUBSCRIPTION_LABEL,
                sanmaoCenterX,
                height * 0.405f,
                reminderActionPaint);

        reminderBodyPaint.setTextSize(height * 0.054f);
        canvas.drawText(
                trafficDisplay.hostRemaining,
                hostCenterX,
                height * 0.510f,
                reminderBodyPaint);
        canvas.drawText(
                trafficDisplay.sanmaoRemaining,
                sanmaoCenterX,
                height * 0.510f,
                reminderBodyPaint);

        reminderMutedPaint.setTextSize(height * 0.032f);
        canvas.drawText(
                trafficDisplay.hostDetail,
                hostCenterX,
                height * 0.585f,
                reminderMutedPaint);
        canvas.drawText(
                trafficDisplay.sanmaoDetail,
                sanmaoCenterX,
                height * 0.585f,
                reminderMutedPaint);

        reminderMutedPaint.setTextSize(height * 0.027f);
        canvas.drawText(
                trafficDisplay.hostMeta,
                hostCenterX,
                height * 0.645f,
                reminderMutedPaint);
        canvas.drawText(
                trafficDisplay.sanmaoMeta,
                sanmaoCenterX,
                height * 0.645f,
                reminderMutedPaint);

        reminderMutedPaint.setTextSize(height * 0.026f);
        canvas.drawText(
                "SWIPE OR TAP TO CLOSE   ·   " + trafficDisplay.footer,
                width * 0.5f,
                height * 0.775f,
                reminderMutedPaint);
    }

    /**
     * 画按钮的取景框，被点中时会"闪一下"。
     *
     * <p>闪烁效果用 sin(π × 进度) 得到：进度 0 时为 0，进度 0.5 时到峰值 1，
     * 进度 1 时回到 0。一条平滑的往返曲线，正好是"亮起来又暗回去"，
     * 比线性插值再反向拼接自然得多。峰值处光晕最强、边框略微内缩。
     */
    private void drawDeviceButtonBrackets(
            Canvas canvas,
            float centerX,
            float centerY,
            float boxWidth,
            float boxHeight,
            boolean pulsing,
            float progress) {
        float pulse = pulsing ? (float) Math.sin(Math.PI * ease(progress)) : 0f;
        if (pulse > 0f) {
            reminderLinePaint.setShadowLayer(
                    getHeight() * 0.022f * pulse, 0f, 0f, P3_GLOW);
        }
        drawFocusBrackets(
                canvas,
                centerX,
                centerY,
                boxWidth * (1f - 0.035f * pulse),
                boxHeight * (1f - 0.035f * pulse),
                true);
        reminderLinePaint.clearShadowLayer();
    }

    /**
     * 画一行会交叉淡入淡出的状态文字：旧文字向上淡出，新文字从下方淡入。
     *
     * @return true 表示动画还在进行，调用方据此决定要不要请求下一帧
     */
    private boolean drawDeviceStatusTransition(
            Canvas canvas,
            String previousStatus,
            String currentStatus,
            long transitionStartMs,
            float centerX,
            float baseline,
            int height,
            long nowMs) {
        float progress = transitionProgress(
                transitionStartMs, DEVICE_STATUS_TRANSITION_MS, nowMs);
        // 没有旧文字（首次显示）或动画已结束：直接画当前文字。
        if (previousStatus == null || progress >= 1f) {
            canvas.drawText(currentStatus, centerX, baseline, reminderBodyPaint);
            return false;
        }

        float eased = ease(progress);
        float travel = height * 0.014f;
        reminderBodyPaint.setAlpha(Math.round(255f * (1f - eased)));
        canvas.drawText(
                previousStatus, centerX, baseline - travel * eased, reminderBodyPaint);
        reminderBodyPaint.setAlpha(Math.round(255f * eased));
        canvas.drawText(
                currentStatus, centerX, baseline + travel * (1f - eased), reminderBodyPaint);
        // 画笔是复用的，用完必须把 alpha 复位，否则会影响后续所有用它画的东西。
        reminderBodyPaint.setAlpha(255);
        return true;
    }

    /**
     * 画四个角的"取景框"括号（类似相机对焦框），用来框住按钮或数据栏。
     * 只画四角不画完整边框，是这套终端 UI 的统一视觉语言。
     *
     * @param selected true 用高亮橙色画，false 用暗淡的琥珀色
     */
    private void drawFocusBrackets(
            Canvas canvas,
            float centerX,
            float centerY,
            float boxWidth,
            float boxHeight,
            boolean selected) {
        Paint paint = selected ? reminderLinePaint : tickPaint;
        paint.setAlpha(selected ? 255 : 100);
        paint.setStrokeWidth(Math.max(2f, getHeight() * 0.0025f));
        float left = centerX - boxWidth / 2f;
        float right = centerX + boxWidth / 2f;
        float top = centerY - boxHeight / 2f;
        float bottom = centerY + boxHeight / 2f;
        // 每个角画两条短线（一横一竖），共八条。长度取框高的 28%。
        float corner = boxHeight * 0.28f;
        canvas.drawLine(left, top, left + corner, top, paint);
        canvas.drawLine(left, top, left, top + corner, paint);
        canvas.drawLine(right, top, right - corner, top, paint);
        canvas.drawLine(right, top, right, top + corner, paint);
        canvas.drawLine(left, bottom, left + corner, bottom, paint);
        canvas.drawLine(left, bottom, left, bottom - corner, paint);
        canvas.drawLine(right, bottom, right - corner, bottom, paint);
        canvas.drawLine(right, bottom, right, bottom - corner, paint);
        paint.setAlpha(255);
    }

    /**
     * 画书法数字表盘。
     *
     * <p>版面从上到下是：大号时分（中间一个冒号）、小一号的秒数配 "SEC" 标签、
     * 一条中间实两端虚的细线、最下面一行日期。
     *
     * <p>整个版面的尺寸都从 digitSize 一个基准值推导出来（下面那一堆 0.708、1.167
     * 之类的系数），这样换任何屏幕尺寸时比例关系都保持不变。
     */
    private void drawCalligraphicFace(Canvas canvas, LocalDateTime now, int width, int height) {
        long nowMs = System.currentTimeMillis();
        // 只要有任何一格还在翻滚，就要继续请求下一帧。
        boolean animating = false;

        // 比例取自设计稿，全部相对于数字大小表达。
        // 同时受高度和宽度约束，取较小者，保证竖屏横屏都不会超出边界。
        float digitSize = Math.min(height * 0.32f, width * 0.17f);
        float slotWidth = digitSize * 0.708f;
        float slotHeight = digitSize * 1.167f;
        float separatorWidth = digitSize * 0.292f;
        float secondSize = digitSize * 0.417f;
        float secondSlotWidth = secondSize * 0.80f;
        float secondSlotHeight = secondSize * 1.20f;
        float rowGap = digitSize * 0.042f;
        float ruleGapAbove = digitSize * 0.208f;
        float ruleGapBelow = digitSize * 0.167f;

        inkDigitPaint.setTextSize(digitSize);
        inkSeparatorPaint.setTextSize(digitSize * 0.75f);
        inkSecondPaint.setTextSize(secondSize);
        inkLabelPaint.setTextSize(digitSize * 0.104f);
        inkDatePaint.setTextSize(digitSize * 0.167f);

        // Cormorant 这个字体的行高定义得非常夸张（为了容纳花体的上下伸出部分），
        // 直接按字体的 ascent/descent 来排版，数字会明显偏上、整体不居中。
        // 所以下面全部改用"墨迹实际占的高度"（getTextBounds 量出来的）来计算。
        String date = formatCalligraphicDate(now.toLocalDate());
        inkDatePaint.getTextBounds(date, 0, date.length(), textBounds);
        float dateHeight = textBounds.height();
        float dateOffset = -textBounds.top;

        float blockHeight = slotHeight + rowGap + secondSlotHeight
                + ruleGapAbove + ruleGapBelow + dateHeight;
        // 每个数字格位都比数字本身高，多出来的空间是留给翻滚动画滑动用的。
        // 这部分是空白不是墨迹，垂直居中时要把它减掉，否则整块会偏下。
        float headSpace = (slotHeight - digitHeight(inkDigitPaint)) * 0.5f;
        float top = (height - blockHeight - headSpace) * 0.5f;
        float centerX = width * 0.5f;

        drawStageHalo(canvas, width, height);

        // --- 时和分 ---
        String hours = String.format(Locale.US, "%02d", now.getHour());
        String minutes = String.format(Locale.US, "%02d", now.getMinute());
        float timeCenterY = top + slotHeight * 0.5f;
        float timeBaseline = digitBaseline(inkDigitPaint, timeCenterY);
        float timeLeft = centerX - (slotWidth * 4f + separatorWidth) * 0.5f;

        // 四个格位：0-1 是小时，2-3 是分钟。下标 >= 2 的要额外加上冒号的宽度，
        // 因为冒号夹在中间占了位置。
        for (int i = 0; i < 4; i++) {
            char value = i < 2 ? hours.charAt(i) : minutes.charAt(i - 2);
            float slotCenterX =
                    timeLeft + slotWidth * (i + 0.5f) + (i >= 2 ? separatorWidth : 0f);
            animating |= drawInkSlot(canvas, i, value, slotCenterX, timeCenterY,
                    slotWidth, slotHeight, timeBaseline, inkDigitPaint,
                    DIGIT_TRANSITION_MS, nowMs);
        }

        animating |= drawSeparator(
                canvas,
                now.getSecond(),
                timeLeft + slotWidth * 2f + separatorWidth * 0.5f,
                timeBaseline,
                nowMs);

        // --- 秒数，右边带 "SEC" 单位标签 ---
        String seconds = String.format(Locale.US, "%02d", now.getSecond());
        float secondCenterY = top + slotHeight + rowGap + secondSlotHeight * 0.5f;
        float secondBaseline = digitBaseline(inkSecondPaint, secondCenterY);
        float labelGap = secondSize * 0.20f;
        float labelWidth = inkLabelPaint.measureText("SEC");
        float secondLeft =
                centerX - (secondSlotWidth * 2f + labelGap + labelWidth) * 0.5f;

        for (int i = 0; i < 2; i++) {
            animating |= drawInkSlot(canvas, 4 + i, seconds.charAt(i),
                    secondLeft + secondSlotWidth * (i + 0.5f), secondCenterY,
                    secondSlotWidth, secondSlotHeight, secondBaseline, inkSecondPaint,
                    SECOND_TRANSITION_MS, nowMs);
        }

        canvas.drawText("SEC", secondLeft + secondSlotWidth * 2f + labelGap,
                secondBaseline, inkLabelPaint);

        // --- 细分隔线和日期 ---
        float ruleY = top + slotHeight + rowGap + secondSlotHeight + ruleGapAbove;
        drawInkRule(canvas, centerX, ruleY, digitSize * 1.25f,
                Math.max(1f, height * 0.0012f));

        canvas.drawText(date, centerX, ruleY + ruleGapBelow + dateOffset, inkDatePaint);

        // 有数字在翻滚时才提高到逐帧重绘，否则这个表盘每秒只画一次。
        // 三个条件缺一不可，尤其 !blackout——熄屏了绝不能还在请求下一帧。
        if (animating && running && !blackout) {
            postInvalidateOnAnimation();
        }
    }

    /** 日期格式化成 "SATURDAY — 15 AUG 2026"。 */
    private static String formatCalligraphicDate(LocalDate date) {
        return LONG_WEEKDAYS[date.getDayOfWeek().getValue() - 1]
                + " — " + date.getDayOfMonth()
                + " " + MONTHS[date.getMonthValue() - 1]
                + " " + date.getYear();
    }

    /**
     * 画一个数字格位，带翻滚动画：旧数字向上滑出并淡出，新数字从下方滑入并淡入，
     * 就像老式翻页钟。
     *
     * <p>翻滚的触发是隐式的——发现传入的字符和记录的不一样，就自动开始一次动画。
     * 所以调用方只管每帧传当前值即可，不需要显式通知"数字变了"。
     *
     * @return true 表示这一格还在翻滚中
     */
    private boolean drawInkSlot(
            Canvas canvas,
            int slot,
            char value,
            float centerX,
            float centerY,
            float slotWidth,
            float slotHeight,
            float baseline,
            Paint paint,
            long durationMs,
            long nowMs) {
        if (slotDigits[slot] != value) {
            slotPreviousDigits[slot] = slotDigits[slot];
            slotDigits[slot] = value;
            slotTransitionStart[slot] = nowMs;
        }

        float progress = transitionProgress(slotTransitionStart[slot], durationMs, nowMs);
        float eased = ease(progress);

        // 裁剪出这一格的矩形范围，滑动中的数字超出边界的部分会被切掉，
        // 形成"从一个窗口里翻过去"的效果。没有这个裁剪，数字会飘到隔壁格子上。
        canvas.save();
        canvas.clipRect(
                centerX - slotWidth * 0.5f,
                centerY - slotHeight * 0.5f,
                centerX + slotWidth * 0.5f,
                centerY + slotHeight * 0.5f);

        // 画旧数字（向上滑 + 淡出）。'\0' 表示这一格还没有过值（刚初始化），
        // 此时不该画旧数字，直接让新数字显示出来。
        if (eased < 1f && slotPreviousDigits[slot] != '\0') {
            paint.setAlpha(Math.round(255f * (1f - eased)));
            drawGlyph(canvas, slotPreviousDigits[slot], centerX,
                    baseline - slotHeight * 0.5f * eased, paint);
        }
        // 画新数字（从下方滑上来 + 淡入）。
        paint.setAlpha(Math.round(255f * eased));
        drawGlyph(canvas, value, centerX,
                baseline + slotHeight * 0.5f * (1f - eased), paint);
        paint.setAlpha(255);
        canvas.restore();

        return progress < 1f;
    }

    /**
     * 数字墨迹的实际高度，不含字体的上下留白。
     * 用 "0" 来量是因为所有数字等高，随便取一个即可。
     */
    private float digitHeight(Paint paint) {
        paint.getTextBounds("0", 0, 1, textBounds);
        return textBounds.height();
    }

    /**
     * 算出让数字墨迹正好落在指定中心线上的基线位置。
     *
     * <p>Canvas 画文字是按基线定位的，而基线不在字的中间。textBounds 的 top 是负数、
     * bottom 通常接近 0，两者取平均就是墨迹中心相对基线的偏移，反过来减掉即可。
     */
    private float digitBaseline(Paint paint, float centerY) {
        paint.getTextBounds("0", 0, 1, textBounds);
        return centerY - (textBounds.top + textBounds.bottom) * 0.5f;
    }

    /** 画单个字符。走 char[] 重载而不是 String，避免每帧为每个数字新建字符串。 */
    private void drawGlyph(Canvas canvas, char value, float x, float y, Paint paint) {
        glyphBuffer[0] = value;
        canvas.drawText(glyphBuffer, 0, 1, x, y, paint);
    }

    /**
     * 画中间的冒号，每逢秒变化就在明暗两态之间渐变一次，形成呼吸般的走秒提示。
     * 用秒数的奇偶来判断该亮还是该暗。
     */
    private boolean drawSeparator(
            Canvas canvas, int second, float centerX, float baseline, long nowMs) {
        int parity = second % 2;
        if (parity != separatorParity) {
            // 起点取"此刻的实际透明度"而不是上一个目标值。这样即使上一次渐变
            // 还没播完就要换方向，也能从当前位置平滑接上，不会突然跳一下。
            separatorAlphaFrom = separatorAlpha(nowMs);
            separatorAlphaTo = parity == 0 ? SEPARATOR_BRIGHT_ALPHA : SEPARATOR_DIM_ALPHA;
            separatorTransitionStart = nowMs;
            separatorParity = parity;
        }
        inkSeparatorPaint.setAlpha(separatorAlpha(nowMs));
        canvas.drawText(":", centerX, baseline, inkSeparatorPaint);
        return transitionProgress(separatorTransitionStart, SEPARATOR_TRANSITION_MS, nowMs) < 1f;
    }

    private int separatorAlpha(long nowMs) {
        float eased = ease(
                transitionProgress(separatorTransitionStart, SEPARATOR_TRANSITION_MS, nowMs));
        return Math.round(separatorAlphaFrom + (separatorAlphaTo - separatorAlphaFrom) * eased);
    }

    /**
     * 画一层极淡的中心径向光晕，像舞台追光，让书法数字不至于孤零零浮在纯黑上。
     * 透明度只有 8/255，几乎看不见，但去掉后画面会明显发死。
     *
     * <p>渐变对象创建开销大，缓存下来，只在尺寸变化（如旋转屏幕）时重建。
     */
    private void drawStageHalo(Canvas canvas, int width, int height) {
        if (haloGradient == null || haloGradientWidth != width || haloGradientHeight != height) {
            haloGradient = new RadialGradient(
                    width * 0.5f,
                    height * 0.5f,
                    Math.max(width, height) * 0.35f,
                    withAlpha(INK_HALO, 8),
                    withAlpha(INK_HALO, 0),
                    Shader.TileMode.CLAMP);
            haloGradientWidth = width;
            haloGradientHeight = height;
            inkHaloPaint.setShader(haloGradient);
        }
        canvas.drawRect(0f, 0f, width, height, inkHaloPaint);
    }

    /**
     * 画秒数和日期之间那条细分隔线。
     *
     * <p>用的是"中间不透明、两端完全透明"的横向线性渐变，所以线两头是自然消隐的，
     * 没有生硬的断口。同样做了尺寸缓存。
     */
    private void drawInkRule(
            Canvas canvas, float centerX, float y, float ruleWidth, float ruleHeight) {
        float left = centerX - ruleWidth * 0.5f;
        float right = centerX + ruleWidth * 0.5f;
        if (ruleGradientWidth != ruleWidth || ruleGradientCenterX != centerX) {
            inkRulePaint.setShader(new LinearGradient(
                    left, 0f, right, 0f,
                    new int[] {withAlpha(INK_RULE, 0), INK_RULE, withAlpha(INK_RULE, 0)},
                    new float[] {0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            ruleGradientWidth = ruleWidth;
            ruleGradientCenterX = centerX;
        }
        canvas.drawRect(left, y, right, y + ruleHeight, inkRulePaint);
    }

    /** 保持 RGB 不变、只换透明度，用来生成渐变的两端颜色。 */
    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * 算动画进度，结果夹在 0~1 之间。返回 1 就表示这个动画已经结束了，
     * 各处都靠这个判断该不该继续请求下一帧。
     */
    private static float transitionProgress(long startMs, long durationMs, long nowMs) {
        if (durationMs <= 0L) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, (nowMs - startMs) / (float) durationMs));
    }

    /**
     * 缓动函数：把线性进度变成"起步快、收尾慢"的曲线，动画才不生硬。
     * 等价于 CSS 的 cubic-bezier(0.4, 0, 0.15, 1)。
     *
     * <p>贝塞尔曲线是参数方程，给定 x 求 y 没有简单的解析解，所以这里用二分法：
     * 迭代 8 次逼近，精度约 1/256，对动画来说远远够了，也比真解方程快得多。
     */
    private static float ease(float progress) {
        if (progress <= 0f) {
            return 0f;
        }
        if (progress >= 1f) {
            return 1f;
        }
        // 二分查找：找到使 x(t) ≈ progress 的参数 t。
        float low = 0f;
        float high = 1f;
        float t = progress;
        for (int i = 0; i < 8; i++) {
            if (bezier(t, 0.4f, 0.15f) < progress) {
                low = t;
            } else {
                high = t;
            }
            t = (low + high) * 0.5f;
        }
        // 拿到 t 后代入 y 方向的控制点（0 和 1）求出最终的缓动值。
        return bezier(t, 0f, 1f);
    }

    /** 三次贝塞尔曲线求值，起点固定为 0、终点固定为 1，只需传两个控制点。 */
    private static float bezier(float t, float control1, float control2) {
        float inverse = 1f - t;
        return 3f * inverse * inverse * t * control1
                + 3f * inverse * t * t * control2
                + t * t * t;
    }

    /**
     * 画磷光表盘的 60 根刻度线。
     *
     * <p>刻度不是排在圆上，而是排在一个"超椭圆"（也叫 Lamé 曲线）上——形状介于椭圆
     * 和圆角矩形之间。这样刻度盘能把横屏的矩形空间填满，而不是在两侧留下大片空白。
     * 指数 exponent 控制形状：等于 2 就是普通椭圆，越大越接近圆角矩形，
     * 这里取 4.4，是填满屏幕和保持圆盘观感之间的折中。
     *
     * <p>三种刻度长短粗细不同：整点（每 15 分）最长最粗，5 分刻度次之，
     * 分钟刻度最细最短。
     */
    private void drawSymmetricFillDial(Canvas canvas, int width, int height) {
        float centerX = width * 0.5f;
        float centerY = height * 0.5f;

        float outerRadiusX = width * 0.450f;
        float outerRadiusY = height * 0.435f;
        double exponent = 4.4;

        for (int minute = 0; minute < 60; minute++) {
            // 每分钟 6 度；减 90 度是把 0 分从三点钟方向挪到十二点钟方向。
            double angle = Math.toRadians(minute * 6.0 - 90.0);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            // 超椭圆的极坐标公式：解 |x/a|^n + |y/b|^n = 1 得到该角度上的半径。
            double denominator = Math.pow(
                    Math.pow(Math.abs(cos) / outerRadiusX, exponent)
                            + Math.pow(Math.abs(sin) / outerRadiusY, exponent),
                    1.0 / exponent);
            double radius = 1.0 / denominator;
            float outerX = centerX + (float) (cos * radius);
            float outerY = centerY + (float) (sin * radius);

            boolean fiveMinute = minute % 5 == 0;
            boolean cardinal = minute % 15 == 0;

            // 内端点的位置比例。数值越小刻度越长（越往圆心伸）。
            // 12 根主刻度统一使用同一个比例，让它们的内端点整齐地落在
            // 同一条超椭圆内圈上；整点刻度仍通过更粗的线宽和更强的辉光
            // 保留主次层次，但不再单独向圆心伸出，避免这组亮刻度参差不齐。
            float innerScale = fiveMinute ? 0.65f : 0.82f;

            float innerX = centerX + (outerX - centerX) * innerScale;
            float innerY = centerY + (outerY - centerY) * innerScale;

            float strokeWidth = height * (cardinal ? 0.0075f : (fiveMinute ? 0.0055f : 0.0028f));

            // 三层叠画，这就是"发光"观感的来源，顺序不能颠倒。
            // 第一层：辉光。线宽 3.2 倍、透明度很低，铺在最底下当光晕。
            tickGlowPaint.setStrokeWidth(strokeWidth * 3.2f);
            tickGlowPaint.setAlpha(fiveMinute ? 55 : 22);
            canvas.drawLine(innerX, innerY, outerX, outerY, tickGlowPaint);

            // 第二层：主体线条。
            tickPaint.setStrokeWidth(strokeWidth);
            tickPaint.setAlpha(fiveMinute ? 240 : 135);
            canvas.drawLine(innerX, innerY, outerX, outerY, tickPaint);

            // 第三层：极细的炽白芯线，只有主刻度才有——细刻度太窄，加了反而糊成一团。
            if (fiveMinute) {
                tickCorePaint.setStrokeWidth(Math.max(1.5f, strokeWidth * 0.35f));
                canvas.drawLine(innerX, innerY, outerX, outerY, tickCorePaint);
            }
        }
    }

    /**
     * 在指针盘右侧画一行小字，内容是"星期 日期   时:分"。
     * 有了它，不用读指针也能一眼确认准确时间。
     */
    private void drawRightInfoWidget(Canvas canvas, LocalDateTime now, int width, int height) {
        float fontSize = height * 0.045f;
        widgetTextPaint.setTextSize(fontSize);

        float baseline = height * 0.512f;

        LocalDate today = now.toLocalDate();
        String dayOfWeek = WEEKDAYS[today.getDayOfWeek().getValue() - 1];
        int dayOfMonth = today.getDayOfMonth();

        int hour = now.getHour();
        int minute = now.getMinute();
        String timeStr = String.format(Locale.US, "%02d:%02d", hour, minute);

        String widgetText = dayOfWeek + " " + dayOfMonth + "   " + timeStr;
        canvas.drawText(widgetText, width * 0.565f, baseline, widgetTextPaint);
    }

    /**
     * 画三根指针和中心轴。
     *
     * <p>秒针是"跳秒"的（每秒跳一格），时针分针则是连续的——这也是真实机械钟的做法：
     * 分针会随着秒数缓缓推进，而不是整分钟才跳一下。
     */
    private void drawPhosphorSharpHands(Canvas canvas, LocalDateTime now, int width, int height) {
        float centerX = width * 0.5f;
        float centerY = height * 0.5f;

        // 分针带上秒的小数部分、时针带上分的小数部分，这样它们是平滑推进的。
        // 秒针则直接用整秒，保持跳秒。取模 12 是因为表盘是 12 小时制。
        int second = now.getSecond();
        double minute = now.getMinute() + second / 60.0;
        double hour = (now.getHour() % 12) + minute / 60.0;

        float hourLength = Math.min(width * 0.145f, height * 0.260f);
        float minuteLength = Math.min(width * 0.205f, height * 0.355f);
        float secondLength = Math.min(width * 0.215f, height * 0.370f);

        float hourWidth = Math.max(22f, height * 0.034f);
        float minuteWidth = Math.max(16f, height * 0.025f);

        // 时针：剑形，每小时 30 度。
        drawSharpGradientHand(canvas, centerX, centerY, hour * 30.0, hourLength, hourWidth);

        // 分针：同样是剑形，每分钟 6 度。
        drawSharpGradientHand(canvas, centerX, centerY, minute * 6.0, minuteLength, minuteWidth);

        // 秒针：一根细直线，不用剑形。带一小截尾巴伸到中心轴的另一侧，
        // 这是机械表的经典造型，也让指针在视觉上更平衡。
        double secondAngle = Math.toRadians(second * 6.0 - 90.0);
        float secEndX = centerX + (float) Math.cos(secondAngle) * secondLength;
        float secEndY = centerY + (float) Math.sin(secondAngle) * secondLength;
        float secTailLen = height * 0.088f;
        float secTailX = centerX - (float) Math.cos(secondAngle) * secTailLen;
        float secTailY = centerY - (float) Math.sin(secondAngle) * secTailLen;

        secondHandGlowPaint.setStrokeWidth(Math.max(7f, height * 0.008f));
        secondHandGlowPaint.setStrokeCap(Paint.Cap.BUTT);
        canvas.drawLine(secTailX, secTailY, secEndX, secEndY, secondHandGlowPaint);

        secondHandPaint.setStrokeWidth(Math.max(3.0f, height * 0.0032f));
        secondHandPaint.setStrokeCap(Paint.Cap.BUTT);
        canvas.drawLine(secTailX, secTailY, secEndX, secEndY, secondHandPaint);

        secondHandCorePaint.setStrokeWidth(Math.max(1.5f, height * 0.0016f));
        secondHandCorePaint.setStrokeCap(Paint.Cap.BUTT);
        canvas.drawLine(secTailX, secTailY, secEndX, secEndY, secondHandCorePaint);

        // 中心轴：由外到内四个同心圆——辉光、主体、炽白芯，最后一个黑点做镂空，
        // 看起来像真表针的固定螺丝。
        float hubRadius = Math.max(10f, height * 0.0125f);
        canvas.drawCircle(centerX, centerY, hubRadius * 1.6f, hubGlowPaint);
        canvas.drawCircle(centerX, centerY, hubRadius, hubPaint);
        canvas.drawCircle(centerX, centerY, hubRadius * 0.50f, hubCorePaint);
        canvas.drawCircle(centerX, centerY, hubRadius * 0.22f, hubDotPaint);
    }

    /**
     * 画一根剑形指针（时针或分针）。
     *
     * <p>做法是先把画布原点平移到表盘中心再旋转，之后就可以按"指针竖直朝上"
     * 这个简单坐标系来画了——不用自己算旋转后每个顶点的坐标。
     * 画完 restore 复位。
     */
    private void drawSharpGradientHand(
            Canvas canvas,
            float centerX,
            float centerY,
            double degrees,
            float length,
            float width) {
        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.rotate((float) degrees);

        // 1. 指针主体沿长度方向的颜色渐变：根部深橙 → 中段琥珀 → 上部近白 → 尖端亮琥珀。
        //    模拟荧光粉受电子束轰击的能量分布，比纯色实心自然得多。
        LinearGradient bodyGradient = new LinearGradient(
                0f, width * 0.1f,
                0f, -length,
                new int[] {
                    Color.rgb(255, 100, 0),   // Root: Deep Phosphor Warm Orange
                    Color.rgb(255, 176, 0),   // Mid Body: P3 Phosphor Amber
                    Color.rgb(255, 232, 160), // Upper Energy Core Gradient
                    Color.rgb(255, 185, 20)   // Sharp Tip: High energy Amber Point
                },
                new float[] { 0.0f, 0.30f, 0.75f, 1.0f },
                Shader.TileMode.CLAMP
        );
        handGradientPaint.setShader(bodyGradient);

        // 2. 中轴上那道"灯丝"的渐变。两端 alpha 为 0，所以是从透明渐入又渐出，
        //    不会在指针根部和尖端留下生硬的白色断口。
        LinearGradient coreGradient = new LinearGradient(
                0f, 0f,
                0f, -length,
                new int[] {
                    Color.argb(0, 255, 120, 0),
                    Color.argb(190, 255, 225, 150),
                    Color.argb(240, 255, 243, 200),
                    Color.argb(0, 255, 176, 0)
                },
                new float[] { 0.0f, 0.25f, 0.75f, 1.0f },
                Shader.TileMode.CLAMP
        );
        handCoreGradientPaint.setShader(coreGradient);

        // 依然是三层结构。第一层：略微放大一圈的半透明轮廓，充当外围光晕。
        buildSharpHandPath(length + width * 0.08f, width * 1.30f);
        handGlowPaint.setAlpha(38);
        canvas.drawPath(handPath, handGlowPaint);

        // 第二层：带渐变填充的指针主体。
        buildSharpHandPath(length, width);
        canvas.drawPath(handPath, handGradientPaint);

        // 第三层：中轴上那道柔和的灯丝线。
        handCoreGradientPaint.setStrokeWidth(Math.max(2.0f, width * 0.28f));
        handCoreGradientPaint.setStrokeCap(Paint.Cap.BUTT);
        canvas.drawLine(0f, -width * 0.05f, 0f, -length * 0.92f, handCoreGradientPaint);

        canvas.restore();
    }

    /**
     * 构造剑形指针的轮廓路径。坐标系约定：原点在表盘中心，指针朝上（Y 轴负方向）。
     *
     * <p>七个顶点勾出的形状是：根部收窄 → 向外扩到最宽的"肩部" → 一路收窄 →
     * 汇聚成尖锐的剑尖，然后对称地绕回来。
     *
     * <p>复用同一个 Path 对象并每次 reset()，避免每帧分配新对象。
     */
    private void buildSharpHandPath(float length, float width) {
        float halfWidth = width * 0.5f;
        float rootWidth = width * 0.24f;
        // 肩部（最宽处）在全长 22% 的位置。
        float shoulderY = -length * 0.22f;
        // 根部略微越过中心点，这样指针盖住中心轴，不会露出缝隙。
        float backY = width * 0.15f;

        handPath.reset();
        handPath.moveTo(-rootWidth, backY);
        handPath.lineTo(-halfWidth, shoulderY);
        handPath.lineTo(-halfWidth * 0.32f, -length * 0.90f);
        handPath.lineTo(0f, -length); // Sharp razor tip!
        handPath.lineTo(halfWidth * 0.32f, -length * 0.90f);
        handPath.lineTo(halfWidth, shoulderY);
        handPath.lineTo(rootWidth, backY);
        handPath.close();
    }

    /**
     * 画 CRT 扫描线：每隔 4 像素叠一条几乎全透明的黑线（alpha 只有 16）。
     * 单独看几乎看不见，但整屏铺下来就有了老显示器的行栅格质感。
     */
    private void drawScanlines(Canvas canvas, int width, int height) {
        scanlinePaint.setStrokeWidth(1.2f);
        for (int y = 0; y < height; y += 4) {
            canvas.drawLine(0f, y, width, y, scanlinePaint);
        }
    }

    /** 加载字体，失败时退回给定的系统字体，保证不会因为缺字体崩溃。 */
    private static Typeface font(Context context, int fontResource, Typeface fallback) {
        Typeface typeface = ResourcesCompat.getFont(context, fontResource);
        return typeface == null ? fallback : typeface;
    }

    // --- 以下三个是画笔工厂方法，把重复的初始化收敛到一处 ---

    /** 描边画笔，默认圆头，用于刻度和指针线条。 */
    private static Paint strokePaint(int color, int alpha) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        return paint;
    }

    /** 填充画笔，用于实心形状（指针主体、中心轴、遮罩层）。 */
    private static Paint fillPaint(int color, int alpha) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    /**
     * 文字画笔。除抗锯齿外还开了 SUBPIXEL_TEXT_FLAG（次像素定位），
     * 让文字在动画中做小数级位移时边缘不会抖。
     */
    private static Paint textPaint(int color, Paint.Align align) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(color);
        paint.setTextAlign(align);
        return paint;
    }
}
