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

public final class ClockView extends View {
    // --- Color Palette: Authentic P3 Amber CRT Phosphor Spectrum ---
    private static final int BACKGROUND = Color.BLACK;
    private static final int P3_AMBER = Color.rgb(255, 176, 0);       // Classic P3 Phosphor Amber (#FFB000)
    private static final int P3_HOT_CORE = Color.rgb(255, 243, 209);    // Incandescent center trace (#FFF3D1)
    private static final int P3_GLOW = Color.rgb(255, 110, 0);         // Outer phosphor halo (#FF6E00)
    private static final int P3_DIM = Color.rgb(120, 68, 0);           // Raster grid / dim status (#784400)
    private static final int P3_DATE_ACCENT = Color.rgb(255, 145, 0);  // High contrast date accent (#FF9100)

    // --- Color Palette: Calligraphic Face (warm ink on paper black) ---
    private static final int INK = Color.rgb(232, 224, 208);           // Primary stroke (#E8E0D0)
    private static final int INK_SECONDARY = Color.rgb(160, 152, 128); // Seconds (#A09880)
    private static final int INK_LABEL = Color.rgb(85, 80, 64);        // Unit label (#555040)
    private static final int INK_RULE = Color.rgb(58, 53, 40);         // Hairline rule (#3A3528)
    private static final int INK_DATE = Color.rgb(106, 96, 80);        // Date line (#6A6050)
    private static final int INK_HALO = Color.rgb(200, 170, 120);      // Faint stage vignette

    private static final long DIGIT_TRANSITION_MS = 450L;
    private static final long SECOND_TRANSITION_MS = 350L;
    private static final long SEPARATOR_TRANSITION_MS = 400L;
    private static final long STYLE_TRANSITION_MS = 380L;
    private static final float STYLE_TRANSITION_DISTANCE = 0.12f;
    private static final long DEVICE_MENU_TRANSITION_MS = 380L;
    private static final float DEVICE_MENU_TRANSITION_DISTANCE = 0.14f;
    private static final long DEVICE_STATUS_TRANSITION_MS = 180L;
    private static final long DEVICE_BUTTON_PULSE_MS = 220L;
    private static final int DEVICE_BUTTON_NONE = 0;
    private static final int DEVICE_BUTTON_LIGHT = 1;
    private static final int DEVICE_BUTTON_DESKTOP = 2;
    private static final int SEPARATOR_DIM_ALPHA = 77;   // opacity 0.3
    private static final int SEPARATOR_BRIGHT_ALPHA = 153; // opacity 0.6

    // Digit slots of the calligraphic face, in render order: H H M M S S
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

    // Pixel shifting for OLED burn-in protection
    private static final float[][] PIXEL_SHIFT = {
            {0f, 0f}, {4f, 0f}, {4f, 4f}, {0f, 4f},
            {-4f, 4f}, {-4f, 0f}, {-4f, -4f}, {0f, -4f}, {4f, -4f}
    };

    // --- Paint declarations for multi-layer CRT rendering ---
    private final Paint tickGlowPaint = strokePaint(P3_GLOW, 45);
    private final Paint tickPaint = strokePaint(P3_AMBER, 220);
    private final Paint tickCorePaint = strokePaint(P3_HOT_CORE, 255);

    private final Paint widgetTextPaint = textPaint(P3_DATE_ACCENT, Paint.Align.LEFT);

    private final Paint handGlowPaint = fillPaint(P3_GLOW, 50);
    private final Paint handGradientPaint = fillPaint(P3_AMBER, 255);
    private final Paint handCoreGradientPaint = strokePaint(P3_HOT_CORE, 255);

    private final Paint secondHandGlowPaint = strokePaint(P3_GLOW, 75);
    private final Paint secondHandPaint = strokePaint(P3_AMBER, 255);
    private final Paint secondHandCorePaint = strokePaint(P3_HOT_CORE, 255);

    private final Paint hubGlowPaint = fillPaint(P3_GLOW, 90);
    private final Paint hubPaint = fillPaint(P3_AMBER, 255);
    private final Paint hubCorePaint = fillPaint(P3_HOT_CORE, 255);
    private final Paint hubDotPaint = fillPaint(BACKGROUND, 255);

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

    private final Path handPath = new Path();

    // Per-slot digit transition state for the calligraphic face
    private final char[] slotDigits = new char[SLOT_COUNT];
    private final char[] slotPreviousDigits = new char[SLOT_COUNT];
    private final long[] slotTransitionStart = new long[SLOT_COUNT];

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable animationTick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            scheduleNextFrame();
        }
    };

    private boolean running;
    private boolean blackout;
    private boolean bedtimeEnabled = true;
    private boolean bedtimeSoundEnabled = true;
    private boolean bedtimeReminderActive;
    private boolean bedtimeSnoozed;
    private boolean settingsVisible;
    private boolean deviceMenuVisible;
    private boolean trafficMenuSelected;
    private boolean trafficDataLoaded;
    private TrafficStatusFormatting.Display trafficDisplay =
            TrafficStatusFormatting.Display.initial();
    private String ceilingLightStatus = "READY";
    private String desktopWakeStatus = "READY";
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
    private float lastTouchX;
    private float lastTouchY;
    private float touchDownX;
    private float touchDownY;
    private final float minimumStyleSwipeDistance;
    private boolean styleSwipeConsumed;
    private boolean autoStyleSwitchEnabled = true;
    private OnStyleSwipeListener onStyleSwipeListener;
    private OnDeviceMenuVisibilityListener onDeviceMenuVisibilityListener;

    private ClockStyle clockStyle = ClockStyle.PHOSPHOR_DIAL;
    private ClockStyle outgoingClockStyle;
    private long styleTransitionStartMs;
    private boolean styleTransitionToNext;
    private int separatorAlphaFrom = SEPARATOR_DIM_ALPHA;
    private int separatorAlphaTo = SEPARATOR_DIM_ALPHA;
    private long separatorTransitionStart;
    private int separatorParity = -1;
    private RadialGradient haloGradient;
    private int haloGradientWidth;
    private int haloGradientHeight;
    private float ruleGradientWidth;
    private float ruleGradientCenterX;
    private final char[] glyphBuffer = new char[1];
    private final Rect textBounds = new Rect();

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
        CLOSE_DEVICE_MENU
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
        minimumStyleSwipeDistance =
                ViewConfiguration.get(context).getScaledTouchSlop() * 4f;

        Typeface terminalTypeface = font(context, R.font.vt323_regular, Typeface.MONOSPACE);

        widgetTextPaint.setTypeface(terminalTypeface);
        reminderTitlePaint.setTypeface(terminalTypeface);
        reminderBodyPaint.setTypeface(terminalTypeface);
        reminderActionPaint.setTypeface(terminalTypeface);
        reminderMutedPaint.setTypeface(terminalTypeface);

        // The calligraphic face uses the reference typefaces: Cormorant Light Italic for the
        // digits, Cormorant Italic for the date, and JetBrains Mono Light for the unit label.
        Typeface digitTypeface = font(context, R.font.cormorant_light_italic,
                Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        Typeface dateTypeface = font(context, R.font.cormorant_italic, digitTypeface);
        Typeface labelTypeface = font(context, R.font.jetbrains_mono_light, Typeface.MONOSPACE);

        inkDigitPaint.setTypeface(digitTypeface);
        inkSeparatorPaint.setTypeface(digitTypeface);
        inkSecondPaint.setTypeface(digitTypeface);
        inkDatePaint.setTypeface(dateTypeface);
        inkDatePaint.setLetterSpacing(0.25f);
        inkLabelPaint.setTypeface(labelTypeface);
        inkLabelPaint.setLetterSpacing(0.30f);
        inkSeparatorPaint.setAlpha(SEPARATOR_DIM_ALPHA);

        handCoreGradientPaint.setStrokeCap(Paint.Cap.BUTT);
    }

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

    public boolean animateClockStyle(ClockStyle style, boolean next) {
        if (style == null || clockStyle == style || outgoingClockStyle != null) {
            return false;
        }
        outgoingClockStyle = clockStyle;
        clockStyle = style;
        styleTransitionToNext = next;
        styleTransitionStartMs = SystemClock.uptimeMillis();
        setContentDescription(style.description());
        if (style == ClockStyle.CALLIGRAPHY) {
            resetCalligraphicState();
        }
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

    /** Drops in-flight digit transitions so a re-shown face does not animate from stale values. */
    private void resetCalligraphicState() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            slotDigits[i] = '\0';
            slotPreviousDigits[i] = '\0';
            slotTransitionStart[i] = 0L;
        }
        separatorParity = -1;
    }

    public void setBlackout(boolean blackout) {
        if (this.blackout == blackout) {
            return;
        }
        this.blackout = blackout;
        if (blackout) {
            deviceMenuVisible = false;
            deviceMenuTransitioning = false;
        }
        handler.removeCallbacks(animationTick);
        if (!blackout) {
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

    public boolean toggleSettings() {
        if (!bedtimeReminderActive && !deviceMenuTransitioning) {
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

    public boolean setDeviceMenuVisible(boolean visible) {
        if (visible && (blackout || bedtimeReminderActive || settingsVisible)) {
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

    private boolean animateDeviceMenuVisibility(boolean visible, boolean swipeLeft) {
        if (visible && (blackout || bedtimeReminderActive || settingsVisible)) {
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

    public UiAction resolveTapAction() {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return UiAction.NONE;
        }
        float x = lastTouchX / width;
        float y = lastTouchY / height;

        if (bedtimeReminderActive) {
            if (y >= 0.52f && y <= 0.68f) {
                return x < 0.5f ? UiAction.BEDTIME_DONE : UiAction.BEDTIME_SNOOZE;
            }
            return UiAction.NONE;
        }
        if (deviceMenuTransitioning) {
            return UiAction.NONE;
        }
        if (deviceMenuVisible) {
            if (trafficMenuSelected) {
                animateDeviceMenuVisibility(false, !deviceMenuTransitionSwipeLeft);
                return UiAction.CLOSE_DEVICE_MENU;
            }
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
            animateDeviceMenuVisibility(false, !deviceMenuTransitionSwipeLeft);
            return UiAction.CLOSE_DEVICE_MENU;
        }
        if (settingsVisible) {
            if (y >= 0.300f && y <= 0.395f) {
                if (x < 0.43f) {
                    return UiAction.PREVIOUS_STYLE;
                }
                if (x > 0.57f) {
                    return UiAction.NEXT_STYLE;
                }
            }
            if (y > 0.395f && y <= 0.475f) {
                return UiAction.TOGGLE_AUTO_STYLE_SWITCH;
            }
            if (y >= 0.500f && y <= 0.600f) {
                if (x < 0.43f) {
                    return UiAction.BEDTIME_MINUS_15;
                }
                if (x > 0.57f) {
                    return UiAction.BEDTIME_PLUS_15;
                }
            }
            if (y > 0.600f && y <= 0.675f) {
                return UiAction.TOGGLE_BEDTIME;
            }
            if (y > 0.675f && y <= 0.750f) {
                return UiAction.TOGGLE_BEDTIME_SOUND;
            }
            if (y >= 0.755f && y <= 0.845f) {
                settingsVisible = false;
                invalidate();
                return UiAction.CLOSE_SETTINGS;
            }
            return UiAction.NONE;
        }
        return UiAction.NONE;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // Non-swipe sequences are delegated to View.onTouchEvent, which invokes performClick and
    // preserves the platform's click/long-click state machine. Calling performClick here would
    // dispatch an ordinary tap twice.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
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
            float threshold = Math.max(minimumStyleSwipeDistance, getHeight() * 0.10f);
            float deltaX = event.getX() - touchDownX;
            float deltaY = event.getY() - touchDownY;
            ClockStyleSwitching.HorizontalSwipeDirection horizontalDirection =
                    ClockStyleSwitching.resolveHorizontalSwipe(deltaX, deltaY, threshold);
            if (horizontalDirection != ClockStyleSwitching.HorizontalSwipeDirection.NONE
                    && canSwipeDeviceMenu()) {
                boolean visible = !deviceMenuVisible;
                boolean swipeLeft = horizontalDirection
                        == ClockStyleSwitching.HorizontalSwipeDirection.LEFT;
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
        if (styleSwipeConsumed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                styleSwipeConsumed = false;
                setPressed(false);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean canSwipeClockStyle() {
        return !blackout
                && !bedtimeReminderActive
                && !settingsVisible
                && !deviceMenuVisible
                && !deviceMenuTransitioning
                && outgoingClockStyle == null;
    }

    private boolean canSwipeDeviceMenu() {
        return !blackout
                && !bedtimeReminderActive
                && !settingsVisible
                && !deviceMenuTransitioning
                && outgoingClockStyle == null;
    }

    private void cancelDefaultTouchHandling(MotionEvent source) {
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);
        cancel.recycle();
        setPressed(false);
    }

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

    public void stop() {
        running = false;
        handler.removeCallbacks(animationTick);
    }

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

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BACKGROUND);
        if (blackout && !bedtimeReminderActive) {
            return;
        }

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int shiftIndex = (now.getMinute() / 5) % PIXEL_SHIFT.length;

        if (deviceMenuTransitioning && !bedtimeReminderActive && !settingsVisible) {
            drawDeviceMenuTransition(canvas, now, shiftIndex, width, height);
            return;
        }

        canvas.save();
        canvas.translate(PIXEL_SHIFT[shiftIndex][0], PIXEL_SHIFT[shiftIndex][1]);
        drawClockFaces(canvas, now, width, height);
        canvas.restore();

        if (bedtimeReminderActive) {
            drawBedtimeReminder(canvas, now, width, height);
        } else if (settingsVisible) {
            drawSettings(canvas, width, height);
        } else if (deviceMenuVisible) {
            drawUtilityMenu(canvas, width, height);
        }

        // Settings and reminder overlays retain the CRT treatment independently of the face.
        if (bedtimeReminderActive || settingsVisible || deviceMenuVisible) {
            drawScanlines(canvas, width, height);
        }

    }

    private void drawDeviceMenuTransition(
            Canvas canvas,
            LocalDateTime now,
            int shiftIndex,
            int width,
            int height) {
        long nowMs = SystemClock.uptimeMillis();
        float progress = transitionProgress(
                deviceMenuTransitionStartMs, DEVICE_MENU_TRANSITION_MS, nowMs);
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
        postInvalidateOnAnimation();
    }

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

    private void drawUtilityMenu(Canvas canvas, int width, int height) {
        if (trafficMenuSelected) {
            drawTrafficMenu(canvas, width, height);
        } else {
            drawDeviceMenu(canvas, width, height);
        }
    }

    private void drawClockFaces(
            Canvas canvas, LocalDateTime now, int width, int height) {
        if (outgoingClockStyle == null) {
            drawClockStyleLayer(canvas, clockStyle, now, width, height, 0f, 255);
            return;
        }

        long nowMs = SystemClock.uptimeMillis();
        float progress = transitionProgress(styleTransitionStartMs, STYLE_TRANSITION_MS, nowMs);
        if (progress >= 1f) {
            ClockStyle completedOutgoingStyle = outgoingClockStyle;
            outgoingClockStyle = null;
            if (completedOutgoingStyle == ClockStyle.CALLIGRAPHY) {
                resetCalligraphicState();
            }
            drawClockStyleLayer(canvas, clockStyle, now, width, height, 0f, 255);
            return;
        }

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
            drawSymmetricFillDial(canvas, width, height);
            drawRightInfoWidget(canvas, now, width, height);
            drawPhosphorSharpHands(canvas, now, width, height);
            drawScanlines(canvas, width, height);
        }
        canvas.restoreToCount(layer);
    }

    private void drawBedtimeReminder(
            Canvas canvas, LocalDateTime now, int width, int height) {
        canvas.drawRect(0f, 0f, width, height, reminderDimPaint);

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

        drawFocusBrackets(canvas, width * 0.415f, height * 0.588f,
                width * 0.135f, height * 0.080f, true);
        drawFocusBrackets(canvas, width * 0.585f, height * 0.588f,
                width * 0.150f, height * 0.080f, false);
        canvas.restore();
    }

    private void drawSettings(Canvas canvas, int width, int height) {
        canvas.drawRect(0f, 0f, width, height, overlayPaint);

        float left = width * 0.25f;
        float right = width * 0.75f;
        reminderLinePaint.setStrokeWidth(Math.max(2f, height * 0.0022f));
        canvas.drawLine(left, height * 0.175f, right, height * 0.175f, reminderLinePaint);
        canvas.drawLine(left, height * 0.885f, right, height * 0.885f, reminderLinePaint);

        reminderTitlePaint.setTextSize(height * 0.058f);
        canvas.drawText("CLOCK SETTINGS", width * 0.5f, height * 0.245f,
                reminderTitlePaint);

        reminderMutedPaint.setTextSize(height * 0.028f);
        canvas.drawText("CLOCK STYLE", width * 0.5f, height * 0.295f, reminderMutedPaint);

        reminderActionPaint.setTextSize(height * 0.042f);
        reminderBodyPaint.setTextSize(height * 0.047f);
        canvas.drawText("[<]", width * 0.365f, height * 0.360f, reminderActionPaint);
        canvas.drawText(clockStyle.label(), width * 0.5f, height * 0.362f, reminderBodyPaint);
        canvas.drawText("[>]", width * 0.635f, height * 0.360f, reminderActionPaint);

        reminderMutedPaint.setTextSize(height * 0.034f);
        reminderActionPaint.setTextSize(height * 0.039f);
        canvas.drawText("AUTO SWITCH", width * 0.455f, height * 0.440f,
                reminderMutedPaint);
        canvas.drawText(autoStyleSwitchEnabled ? "ON / 1H" : "OFF", width * 0.570f,
                height * 0.440f, reminderActionPaint);

        reminderMutedPaint.setTextSize(height * 0.028f);
        canvas.drawText("BEDTIME", width * 0.5f, height * 0.495f, reminderMutedPaint);

        reminderActionPaint.setTextSize(height * 0.042f);
        reminderBodyPaint.setTextSize(height * 0.058f);
        canvas.drawText("[-15]", width * 0.365f, height * 0.562f, reminderActionPaint);
        canvas.drawText(
                String.format(Locale.US, "%02d:%02d", bedtimeHour, bedtimeMinute),
                width * 0.5f,
                height * 0.567f,
                reminderBodyPaint);
        canvas.drawText("[+15]", width * 0.635f, height * 0.562f, reminderActionPaint);

        reminderMutedPaint.setTextSize(height * 0.034f);
        reminderActionPaint.setTextSize(height * 0.039f);
        canvas.drawText("ENABLED", width * 0.455f, height * 0.645f, reminderMutedPaint);
        canvas.drawText(bedtimeEnabled ? "ON" : "OFF", width * 0.555f,
                height * 0.645f, reminderActionPaint);

        canvas.drawText("SOUND", width * 0.455f, height * 0.715f, reminderMutedPaint);
        canvas.drawText(bedtimeSoundEnabled ? "ON" : "OFF", width * 0.555f,
                height * 0.715f, reminderActionPaint);

        reminderActionPaint.setTextSize(height * 0.036f);
        canvas.drawText("[ CLOSE ]", width * 0.5f, height * 0.805f,
                reminderActionPaint);

        if (bedtimeSnoozed) {
            reminderMutedPaint.setTextSize(height * 0.024f);
            canvas.drawText("SNOOZED FOR THIS SESSION", width * 0.5f,
                    height * 0.850f, reminderMutedPaint);
        }
    }

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

        if (buttonPulseAnimating || lightStatusAnimating || desktopStatusAnimating) {
            postInvalidateOnAnimation();
        }
    }

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
        reminderBodyPaint.setAlpha(255);
        return true;
    }

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

    private void drawCalligraphicFace(Canvas canvas, LocalDateTime now, int width, int height) {
        long nowMs = System.currentTimeMillis();
        boolean animating = false;

        // Proportions follow the reference sheet, expressed relative to the digit size.
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

        // Cormorant has very tall vertical metrics, so the block is laid out and centred on
        // the numerals' inked extent rather than on font ascent and descent.
        String date = formatCalligraphicDate(now.toLocalDate());
        inkDatePaint.getTextBounds(date, 0, date.length(), textBounds);
        float dateHeight = textBounds.height();
        float dateOffset = -textBounds.top;

        float blockHeight = slotHeight + rowGap + secondSlotHeight
                + ruleGapAbove + ruleGapBelow + dateHeight;
        // The first slot is taller than its digits so the slide has room; that head space is
        // not ink, so it must not count towards vertical centring.
        float headSpace = (slotHeight - digitHeight(inkDigitPaint)) * 0.5f;
        float top = (height - blockHeight - headSpace) * 0.5f;
        float centerX = width * 0.5f;

        drawStageHalo(canvas, width, height);

        // --- Hours and minutes ---
        String hours = String.format(Locale.US, "%02d", now.getHour());
        String minutes = String.format(Locale.US, "%02d", now.getMinute());
        float timeCenterY = top + slotHeight * 0.5f;
        float timeBaseline = digitBaseline(inkDigitPaint, timeCenterY);
        float timeLeft = centerX - (slotWidth * 4f + separatorWidth) * 0.5f;

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

        // --- Seconds with unit label ---
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

        // --- Hairline rule and date ---
        float ruleY = top + slotHeight + rowGap + secondSlotHeight + ruleGapAbove;
        drawInkRule(canvas, centerX, ruleY, digitSize * 1.25f,
                Math.max(1f, height * 0.0012f));

        canvas.drawText(date, centerX, ruleY + ruleGapBelow + dateOffset, inkDatePaint);

        // Digit transitions need animation frames; the face otherwise redraws once per second.
        if (animating && running && !blackout) {
            postInvalidateOnAnimation();
        }
    }

    private static String formatCalligraphicDate(LocalDate date) {
        return LONG_WEEKDAYS[date.getDayOfWeek().getValue() - 1]
                + " — " + date.getDayOfMonth()
                + " " + MONTHS[date.getMonthValue() - 1]
                + " " + date.getYear();
    }

    /**
     * Draws one digit slot, cross-fading the outgoing glyph upwards and the incoming glyph up
     * from below. Returns true while the transition is still running.
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

        canvas.save();
        canvas.clipRect(
                centerX - slotWidth * 0.5f,
                centerY - slotHeight * 0.5f,
                centerX + slotWidth * 0.5f,
                centerY + slotHeight * 0.5f);

        if (eased < 1f && slotPreviousDigits[slot] != '\0') {
            paint.setAlpha(Math.round(255f * (1f - eased)));
            drawGlyph(canvas, slotPreviousDigits[slot], centerX,
                    baseline - slotHeight * 0.5f * eased, paint);
        }
        paint.setAlpha(Math.round(255f * eased));
        drawGlyph(canvas, value, centerX,
                baseline + slotHeight * 0.5f * (1f - eased), paint);
        paint.setAlpha(255);
        canvas.restore();

        return progress < 1f;
    }

    /** Inked height of a numeral, ignoring the font's ascent and descent. */
    private float digitHeight(Paint paint) {
        paint.getTextBounds("0", 0, 1, textBounds);
        return textBounds.height();
    }

    /** Baseline that puts a numeral's inked box on the given centre line. */
    private float digitBaseline(Paint paint, float centerY) {
        paint.getTextBounds("0", 0, 1, textBounds);
        return centerY - (textBounds.top + textBounds.bottom) * 0.5f;
    }

    private void drawGlyph(Canvas canvas, char value, float x, float y, Paint paint) {
        glyphBuffer[0] = value;
        canvas.drawText(glyphBuffer, 0, 1, x, y, paint);
    }

    /** Fades the colon between its dim and bright states on every second boundary. */
    private boolean drawSeparator(
            Canvas canvas, int second, float centerX, float baseline, long nowMs) {
        int parity = second % 2;
        if (parity != separatorParity) {
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

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static float transitionProgress(long startMs, long durationMs, long nowMs) {
        if (durationMs <= 0L) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, (nowMs - startMs) / (float) durationMs));
    }

    /** cubic-bezier(0.4, 0, 0.15, 1), solved for y at the given x. */
    private static float ease(float progress) {
        if (progress <= 0f) {
            return 0f;
        }
        if (progress >= 1f) {
            return 1f;
        }
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
        return bezier(t, 0f, 1f);
    }

    private static float bezier(float t, float control1, float control2) {
        float inverse = 1f - t;
        return 3f * inverse * inverse * t * control1
                + 3f * inverse * t * t * control2
                + t * t * t;
    }

    private void drawSymmetricFillDial(Canvas canvas, int width, int height) {
        float centerX = width * 0.5f;
        float centerY = height * 0.5f;

        float outerRadiusX = width * 0.450f;
        float outerRadiusY = height * 0.435f;
        double exponent = 4.4;

        for (int minute = 0; minute < 60; minute++) {
            double angle = Math.toRadians(minute * 6.0 - 90.0);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double denominator = Math.pow(
                    Math.pow(Math.abs(cos) / outerRadiusX, exponent)
                            + Math.pow(Math.abs(sin) / outerRadiusY, exponent),
                    1.0 / exponent);
            double radius = 1.0 / denominator;
            float outerX = centerX + (float) (cos * radius);
            float outerY = centerY + (float) (sin * radius);

            boolean fiveMinute = minute % 5 == 0;
            boolean cardinal = minute % 15 == 0;

            float innerScale;
            if (cardinal) {
                innerScale = 0.50f;
            } else if (fiveMinute) {
                innerScale = 0.65f;
            } else {
                innerScale = 0.82f;
            }

            float innerX = centerX + (outerX - centerX) * innerScale;
            float innerY = centerY + (outerY - centerY) * innerScale;

            float strokeWidth = height * (cardinal ? 0.0075f : (fiveMinute ? 0.0055f : 0.0028f));

            // Pass 1: Glow
            tickGlowPaint.setStrokeWidth(strokeWidth * 3.2f);
            tickGlowPaint.setAlpha(fiveMinute ? 55 : 22);
            canvas.drawLine(innerX, innerY, outerX, outerY, tickGlowPaint);

            // Pass 2: Vector line
            tickPaint.setStrokeWidth(strokeWidth);
            tickPaint.setAlpha(fiveMinute ? 240 : 135);
            canvas.drawLine(innerX, innerY, outerX, outerY, tickPaint);

            // Pass 3: Core trace for major ticks
            if (fiveMinute) {
                tickCorePaint.setStrokeWidth(Math.max(1.5f, strokeWidth * 0.35f));
                canvas.drawLine(innerX, innerY, outerX, outerY, tickCorePaint);
            }
        }
    }

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

    private void drawPhosphorSharpHands(Canvas canvas, LocalDateTime now, int width, int height) {
        float centerX = width * 0.5f;
        float centerY = height * 0.5f;

        // Discrete 1-second step ticking (jump step per second)
        int second = now.getSecond();
        double minute = now.getMinute() + second / 60.0;
        double hour = (now.getHour() % 12) + minute / 60.0;

        float hourLength = Math.min(width * 0.145f, height * 0.260f);
        float minuteLength = Math.min(width * 0.205f, height * 0.355f);
        float secondLength = Math.min(width * 0.215f, height * 0.370f);

        float hourWidth = Math.max(22f, height * 0.034f);
        float minuteWidth = Math.max(16f, height * 0.025f);

        // Hour Hand: Sharp Angular Sword with Smooth Phosphor Gradient
        drawSharpGradientHand(canvas, centerX, centerY, hour * 30.0, hourLength, hourWidth);

        // Minute Hand: Sharp Angular Sword with Smooth Phosphor Gradient
        drawSharpGradientHand(canvas, centerX, centerY, minute * 6.0, minuteLength, minuteWidth);

        // Second Hand: Sharp Precision Vector Needle (Discrete Step per Second)
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

        // Sharp Geometric Center Hub
        float hubRadius = Math.max(10f, height * 0.0125f);
        canvas.drawCircle(centerX, centerY, hubRadius * 1.6f, hubGlowPaint);
        canvas.drawCircle(centerX, centerY, hubRadius, hubPaint);
        canvas.drawCircle(centerX, centerY, hubRadius * 0.50f, hubCorePaint);
        canvas.drawCircle(centerX, centerY, hubRadius * 0.22f, hubDotPaint);
    }

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

        // 1. Smooth Linear Phosphor Gradient along the hand body
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

        // 2. Smooth Blended Filament Gradient along center axis (no harsh white breaks!)
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

        // Pass 1: Outer Translucent Phosphor Bloom
        buildSharpHandPath(length + width * 0.08f, width * 1.30f);
        handGlowPaint.setAlpha(38);
        canvas.drawPath(handPath, handGlowPaint);

        // Pass 2: Main Cold Sharp Angular Body with Linear Gradient
        buildSharpHandPath(length, width);
        canvas.drawPath(handPath, handGradientPaint);

        // Pass 3: Softly Blended Inner Gradient Trace Line
        handCoreGradientPaint.setStrokeWidth(Math.max(2.0f, width * 0.28f));
        handCoreGradientPaint.setStrokeCap(Paint.Cap.BUTT);
        canvas.drawLine(0f, -width * 0.05f, 0f, -length * 0.92f, handCoreGradientPaint);

        canvas.restore();
    }

    private void buildSharpHandPath(float length, float width) {
        float halfWidth = width * 0.5f;
        float rootWidth = width * 0.24f;
        float shoulderY = -length * 0.22f;
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

    private void drawScanlines(Canvas canvas, int width, int height) {
        scanlinePaint.setStrokeWidth(1.2f);
        for (int y = 0; y < height; y += 4) {
            canvas.drawLine(0f, y, width, y, scanlinePaint);
        }
    }

    private static Typeface font(Context context, int fontResource, Typeface fallback) {
        Typeface typeface = ResourcesCompat.getFont(context, fontResource);
        return typeface == null ? fallback : typeface;
    }

    private static Paint strokePaint(int color, int alpha) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        return paint;
    }

    private static Paint fillPaint(int color, int alpha) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    private static Paint textPaint(int color, Paint.Align align) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(color);
        paint.setTextAlign(align);
        return paint;
    }
}
