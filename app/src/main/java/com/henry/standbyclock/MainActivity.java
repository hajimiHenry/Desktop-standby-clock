package com.henry.standbyclock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用唯一的界面。职责有三块：
 *
 * <ol>
 *   <li><b>做一块纯粹的显示屏</b>：全屏沉浸、常亮、隐藏状态栏导航栏、锁屏上也显示、
 *       屏蔽返回键，让这台手机看起来不像手机。</li>
 *   <li><b>转发用户操作</b>：ClockView 只负责画和识别点了哪儿，实际动作（改就寝时间、
 *       开灯、唤醒电脑）由这里执行——需要联网的丢子线程，属于后台状态的发给
 *       StandbyService。</li>
 *   <li><b>管表盘轮换</b>：定时自动切换表盘，并把选择持久化。</li>
 * </ol>
 *
 * <p>为什么状态要分给 StandbyService 管：Activity 会被系统随时销毁重建，
 * 而环境光监测和睡眠提醒必须一直活着。所以这里只保存 UI 相关的偏好，
 * 真正的后台状态在 Service 里，两者通过广播同步。
 */
public final class MainActivity extends Activity {
    private static final String TAG = "StandbyClock";
    private static final String CLOCK_PREFERENCES = "clock_preferences";
    private static final String KEY_CLOCK_STYLE = "clock_style";
    private static final String KEY_AUTO_STYLE_SWITCH_ENABLED = "auto_style_switch_enabled";
    /** 下次自动切表盘的绝对时间戳，存起来是为了熄屏／重启后倒计时能接着走。 */
    private static final String KEY_NEXT_AUTO_STYLE_SWITCH_AT = "next_auto_style_switch_at";
    /**
     * 自动切换被拒绝时的重试间隔。切换会被拒绝是因为上一次切换动画还没播完
     * （见 ClockView.animateClockStyle），过 500ms 再试一次即可。
     */
    private static final long STYLE_SWITCH_RETRY_MS = 500L;
    /**
     * 轻触点亮时用的固定亮度。比睡眠提醒的 0.10 稍亮一点——够看清表盘、也够操作面板，
     * 但在漆黑的房间里仍然不刺眼。不交给系统自动亮度是因为刚从 0 亮度覆盖恢复时，
     * 自动亮度往往要缓慢爬升，摸黑点一下屏幕却要等它亮起来，手感很差。
     */
    private static final float TOUCH_WAKE_BRIGHTNESS = 0.15f;

    private final YeelightAddressResolver yeelightAddressResolver =
            new YeelightAddressResolver(
                    DeviceControlConfig.YEELIGHT_HOST, DeviceControlConfig.YEELIGHT_MAC);
    private final TrafficStatusClient trafficStatusClient = new TrafficStatusClient(
            DeviceControlConfig.TRAFFIC_STATUS_URL);
    /**
     * 所有联网操作（控灯、发唤醒包、查流量）都排在这一条线程上。
     * 用单线程而不是线程池：这些操作都由用户手动触发、频率极低，
     * 串行执行反而天然避免了并发发多个请求。
     */
    private final ExecutorService deviceControlExecutor = Executors.newSingleThreadExecutor();

    private ClockView clockView;
    private SharedPreferences clockPreferences;
    /** 自动切表盘的定时器，跑在主线程。 */
    private final Handler autoStyleHandler = new Handler(Looper.getMainLooper());
    private boolean statusReceiverRegistered;

    /** 以下两个是 Service 广播过来的状态副本，用于决定屏幕亮度。 */
    private boolean displayBlackout;
    private boolean bedtimeReminderActive;

    /**
     * 轻触点亮：熄屏时碰一下屏幕，临时把显示放出来一小会儿。
     *
     * <p>这个状态<em>不</em>交给 Service 管，故意留在界面里。它纯粹是一次触摸引发的
     * 瞬时 UI 状态：只有界面在前台时才可能产生，界面没了它也就没有意义；而且要跟手，
     * 走一圈 Intent 加广播反而慢。Service 那边的 displayBlackout 保持不变，
     * 这里只是在最终合成亮度时把它压住。
     */
    private final TouchWakePolicy touchWakePolicy = new TouchWakePolicy();
    /** 点亮窗口的到期定时器，跑在主线程。 */
    private final Handler touchWakeHandler = new Handler(Looper.getMainLooper());
    private final Runnable touchWakeExpiry = this::expireTouchWake;

    private boolean autoStyleSwitchEnabled;

    /**
     * 三个"请求进行中"标记，防止用户连点导致重复发请求。
     * 只在主线程读写，所以不用加锁。
     */
    private boolean lightRequestInFlight;
    private boolean wolRequestInFlight;
    private boolean trafficRequestInFlight;

    private final Runnable autoStyleSwitch = this::handleAutoStyleSwitch;

    /** 接收 StandbyService 广播来的熄屏状态和睡眠提醒状态。 */
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (StandbyService.ACTION_DISPLAY_MODE.equals(intent.getAction())) {
                applyBlackout(intent.getBooleanExtra(StandbyService.EXTRA_BLACKOUT, false));
            } else if (StandbyService.ACTION_BEDTIME_STATE.equals(intent.getAction())) {
                bedtimeReminderActive = intent.getBooleanExtra(
                        StandbyService.EXTRA_BEDTIME_ACTIVE, false);
                // 先记下有没有浮层开着：睡眠提醒会强行盖掉浮层，
                // 得在那之前把状态取出来，好在下面通知 Service 浮层已经关了。
                boolean overlayWasVisible = clockView.isSettingsVisible()
                        || clockView.isDeviceMenuVisible();
                clockView.setBedtimeState(
                        intent.getBooleanExtra(StandbyService.EXTRA_BEDTIME_ENABLED, true),
                        intent.getIntExtra(StandbyService.EXTRA_BEDTIME_HOUR, 23),
                        intent.getIntExtra(StandbyService.EXTRA_BEDTIME_MINUTE, 30),
                        bedtimeReminderActive,
                        intent.getBooleanExtra(StandbyService.EXTRA_BEDTIME_SNOOZED, false),
                        intent.getBooleanExtra(
                                StandbyService.EXTRA_BEDTIME_SOUND_ENABLED, true));
                // 浮层被提醒挤掉了，要告诉 Service 一声。因为浮层打开期间 Service 会
                // 暂时压住自动熄屏（用户正在操作，不能黑屏），现在得解除这个压制。
                if (bedtimeReminderActive && overlayWasVisible) {
                    sendSettingsOpen(false);
                }
                applyDisplayState();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- 把窗口调教成一块专职显示屏 ---
        Window window = getWindow();
        // 永不自动息屏。这是时钟的根本前提。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 系统栏涂成纯黑，跟表盘背景无缝衔接（沉浸模式下偶尔被划出来时也不突兀）。
        window.setStatusBarColor(0xFF000000);
        window.setNavigationBarColor(0xFF000000);

        WindowManager.LayoutParams attributes = window.getAttributes();
        // 允许内容延伸到刘海／挖孔区域，否则横屏时两侧会留黑边。
        attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        // 关掉窗口进出动画，配合下面的 overridePendingTransition(0, 0)。
        // 时钟被重新拉起时不该有淡入闪动，要像一直没动过一样。
        attributes.windowAnimations = 0;
        attributes.rotationAnimation =
                WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
        window.setAttributes(attributes);

        // 锁屏之上直接显示，并且能主动点亮屏幕——这样重启后无需解锁就能看到时钟。
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        clockPreferences = getSharedPreferences(CLOCK_PREFERENCES, MODE_PRIVATE);
        autoStyleSwitchEnabled = clockPreferences.getBoolean(
                KEY_AUTO_STYLE_SWITCH_ENABLED, true);

        // 整个界面只有这一个 View，没有 XML 布局，所有内容都是它在 onDraw 里画出来的。
        clockView = new ClockView(this);
        clockView.setClockStyle(
                ClockStyle.fromKey(clockPreferences.getString(KEY_CLOCK_STYLE, null)));
        clockView.setAutoStyleSwitchEnabled(autoStyleSwitchEnabled);
        // 上下滑动切表盘。第二个参数 true 表示手动切换要重置自动轮换的倒计时——
        // 刚手动选了一个，不该几秒后就被自动切走。
        clockView.setOnStyleSwipeListener(next -> applyClockStyle(
                next ? clockView.getClockStyle().next() : clockView.getClockStyle().previous(),
                true,
                next));
        // 左右滑动打开面板时，顺便触发一次数据刷新：面板一打开就该显示最新状态。
        clockView.setOnDeviceMenuVisibilityListener(visible -> {
            sendSettingsOpen(visible);
            if (visible) {
                if (clockView.isTrafficMenuVisible()) {
                    refreshTrafficStatus();
                } else {
                    clockView.setDesktopWakeStatus(
                            DeviceControlConfig.isWakeOnLanConfigured()
                                    ? "READY"
                                    : "NOT CONFIGURED");
                    refreshCeilingLight();
                }
            }
        });
        // 长按打开设置浮层。
        clockView.setOnLongClickListener(view -> {
            sendSettingsOpen(clockView.toggleSettings());
            return true;
        });
        clockView.setOnClickListener(view -> handleClockTap());
        setContentView(clockView);
        enterImmersiveMode();
        startStandbyService();
        // 去掉 Activity 启动过渡动画，理由同上面的 windowAnimations = 0。
        overridePendingTransition(0, 0);
    }

    /**
     * 时钟任务已经在前台时又被重新拉起会走这里（比如 root 脚本执行 am start，
     * 或用户点了桌面图标）。此时不重建界面，只把沉浸模式和服务重新确认一遍。
     */

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        enterImmersiveMode();
        startStandbyService();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(StandbyService.ACTION_DISPLAY_MODE);
        filter.addAction(StandbyService.ACTION_BEDTIME_STATE);
        // RECEIVER_NOT_EXPORTED：只接收本应用发的广播，别的应用发不进来。
        // Android 13 起注册运行时广播必须显式声明导出与否。
        ContextCompat.registerReceiver(
                this,
                statusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        statusReceiverRegistered = true;
        // 刚注册好接收器，主动问 Service 要一次当前状态。否则得干等到下次状态变化，
        // 期间界面显示的可能是过期的（比如息屏前是熄屏状态，回来却画着表盘）。
        sendServiceAction(StandbyService.ACTION_REQUEST_STATUS);
        resumeAutoStyleSwitch();
    }

    private void applyBlackout(boolean blackout) {
        // 环境光自己把屏幕点亮了（多半是用户真去开了灯），临时窗口就没有存在意义了，
        // 撤掉它，免得到期回调白跑一次、或者在环境重新变暗时莫名其妙地续上一段。
        if (!blackout) {
            cancelTouchWake();
        }
        displayBlackout = blackout;
        applyDisplayState();
    }

    /**
     * 所有触摸的总入口。在事件分发给 ClockView <em>之前</em>先把屏幕点亮，
     * 这样同一个手势里后续的滑动、长按判断看到的已经是"没熄屏"的状态——摸黑回到家
     * 直接一次右滑就能划进设备控制去开灯，不用先点一下唤醒、再重新划一次。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        // 按下时点亮，抬手时再续满一次：这样长按几秒打开设置之后，
        // 剩下的窗口仍然是完整的 20 秒，不会刚点开就黑掉。
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            noteTouchWake();
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * 记一次触摸并重排到期回调。
     *
     * <p>屏幕本来就亮着时这里基本是空转：applyDisplayState 会发现亮度没变而不去动窗口，
     * 到期回调跑起来同样什么都不改。所以不必先判断"是不是熄屏中"再决定要不要记——
     * 无条件记录反而让规则更简单：<b>最后一次触摸之后的 20 秒内不进入熄屏</b>。
     */
    private void noteTouchWake() {
        long nowMs = SystemClock.elapsedRealtime();
        // 只在熄屏状态下打日志，否则每次触摸都会刷屏。
        if (displayBlackout && !touchWakePolicy.isAwake(nowMs)) {
            Log.i(TAG, "touch_wake=ON");
        }
        touchWakePolicy.noteTouch(nowMs);
        touchWakeHandler.removeCallbacks(touchWakeExpiry);
        touchWakeHandler.postDelayed(touchWakeExpiry, TouchWakePolicy.WAKE_DURATION_MS);
        applyDisplayState();
    }

    /** 点亮窗口到期：把显示交还给环境光状态机，该黑就黑。 */
    private void expireTouchWake() {
        if (displayBlackout) {
            Log.i(TAG, "touch_wake=OFF");
        }
        touchWakePolicy.clear();
        applyDisplayState();
    }

    /** 立刻结束点亮窗口并撤掉定时器。 */
    private void cancelTouchWake() {
        touchWakeHandler.removeCallbacks(touchWakeExpiry);
        touchWakePolicy.clear();
    }

    /**
     * 根据熄屏 / 睡眠提醒 / 轻触点亮三个状态，决定屏幕该多亮。四档：
     * 真正熄屏时亮度压到 0 并画纯黑（OLED 像素完全不发光）；
     * 睡眠提醒时用 10% 的极暗亮度（夜里看得见但不刺眼）；
     * 环境仍然是暗的、只是被轻触临时点亮时用 {@link #TOUCH_WAKE_BRIGHTNESS}；
     * 其余情况交还给系统自动亮度。
     *
     * <p>三者都成立时的优先级是"提醒 &gt; 轻触 &gt; 熄屏"——提醒本来就是要在漆黑的
     * 房间里叫醒你的，而用户刚碰过屏幕就更不该黑着。
     */
    private void applyDisplayState() {
        boolean touchAwake = touchWakePolicy.isAwake(SystemClock.elapsedRealtime());
        boolean effectiveBlackout =
                displayBlackout && !bedtimeReminderActive && !touchAwake;
        clockView.setBlackout(effectiveBlackout);
        Window window = getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        float brightness;
        if (effectiveBlackout) {
            brightness = 0f;
        } else if (bedtimeReminderActive) {
            brightness = 0.10f;
        } else if (displayBlackout) {
            brightness = TOUCH_WAKE_BRIGHTNESS;
        } else {
            brightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        // 亮度没变就不要调 setAttributes：这个方法现在被每一次触摸驱动，
        // 而 setAttributes 是一次跨进程的窗口更新，白发没有意义。
        if (attributes.screenBrightness != brightness) {
            attributes.screenBrightness = brightness;
            window.setAttributes(attributes);
        }
    }

    /**
     * 处理一次点击。ClockView 只负责把"点在哪个坐标"翻译成一个语义化的动作枚举，
     * 具体做什么在这里决定——这样按钮位置的调整不会牵扯到业务逻辑。
     */
    private void handleClockTap() {
        ClockView.UiAction action = clockView.resolveTapAction();
        switch (action) {
            case BEDTIME_DONE:
                // DONE 和 +15 MIN 的语义都是"别亮着了"，得把这次点击顺带开出来的
                // 点亮窗口撤掉，否则提醒收起后屏幕还要多亮 20 秒才黑，正好拧着来。
                cancelTouchWake();
                sendServiceAction(StandbyService.ACTION_BEDTIME_DONE);
                break;
            case BEDTIME_SNOOZE:
                cancelTouchWake();
                sendServiceAction(StandbyService.ACTION_BEDTIME_SNOOZE);
                break;
            case BEDTIME_MINUS_15:
                sendServiceActionWithDelta(StandbyService.ACTION_ADJUST_BEDTIME, -15);
                break;
            case BEDTIME_PLUS_15:
                sendServiceActionWithDelta(StandbyService.ACTION_ADJUST_BEDTIME, 15);
                break;
            case TOGGLE_BEDTIME:
                sendServiceAction(StandbyService.ACTION_TOGGLE_BEDTIME);
                break;
            case TOGGLE_BEDTIME_SOUND:
                sendServiceAction(StandbyService.ACTION_TOGGLE_BEDTIME_SOUND);
                break;
            case TOGGLE_AUTO_STYLE_SWITCH:
                setAutoStyleSwitchEnabled(!autoStyleSwitchEnabled);
                break;
            case PREVIOUS_STYLE:
                applyClockStyle(clockView.getClockStyle().previous(), true, false);
                break;
            case NEXT_STYLE:
                applyClockStyle(clockView.getClockStyle().next(), true, true);
                break;
            case CLOSE_SETTINGS:
                sendSettingsOpen(false);
                break;
            case TOGGLE_CEILING_LIGHT:
                toggleCeilingLight();
                break;
            case WAKE_DESKTOP:
                wakeDesktop();
                break;
            case CLOSE_DEVICE_MENU:
                sendSettingsOpen(false);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void refreshCeilingLight() {
        runCeilingLightRequest(false);
    }

    private void toggleCeilingLight() {
        runCeilingLightRequest(true);
    }

    /**
     * 控灯的统一入口。三种请求（控灯、唤醒电脑、查流量）都是同一套模式：
     * 先查配置齐不齐 → 查有没有正在进行的请求 → 界面立刻显示"进行中"给用户反馈 →
     * 丢到子线程执行 → 结果切回主线程更新界面。
     *
     * @param toggle true = 翻转开关，false = 只查询当前状态（打开面板时用）
     */
    private void runCeilingLightRequest(boolean toggle) {
        if (!DeviceControlConfig.isYeelightConfigured()) {
            clockView.setCeilingLightStatus("NOT CONFIGURED");
            return;
        }
        if (lightRequestInFlight) {
            return;
        }
        lightRequestInFlight = true;
        clockView.setCeilingLightStatus(toggle ? "SWITCHING..." : "CHECKING...");
        deviceControlExecutor.execute(() -> {
            String status;
            // 捕获所有异常而不只是 IOException：灯离线只是个小功能失灵，
            // 界面显示 OFFLINE 就够了，绝不能让时钟主体崩掉。
            try {
                YeelightClient.PowerState state = toggle
                        ? toggleCeilingLightSafely()
                        : queryCeilingLightWithRecovery();
                status = state == YeelightClient.PowerState.ON ? "ON" : "OFF";
            } catch (Exception exception) {
                Log.w(TAG, "Unable to control Yeelight ceiling light", exception);
                status = "OFFLINE";
            }
            // lambda 捕获的变量必须是 final 或事实 final，status 上面被重新赋过值，
            // 所以要转存一个新变量才能带进下面的 lambda。
            String completedStatus = status;
            runOnUiThread(() -> {
                lightRequestInFlight = false;
                clockView.setCeilingLightStatus(completedStatus);
            });
        });
    }

    /** 状态查询没有副作用，地址失效时可以安全地按 MAC 刷新地址并重试一次。 */
    private YeelightClient.PowerState queryCeilingLightWithRecovery() throws IOException {
        YeelightClient client = clientForResolvedAddress(false);
        try {
            return client.getPower();
        } catch (IOException firstFailure) {
            yeelightAddressResolver.invalidate();
            YeelightClient recoveredClient = clientForResolvedAddress(true);
            return recoveredClient.getPower();
        }
    }

    /**
     * toggle 有副作用，绝不能在响应丢失时盲目重发，否则可能开完又关。
     * 因此先用只读查询验证地址；只有验证阶段允许重新解析，toggle 本身只发送一次。
     */
    private YeelightClient.PowerState toggleCeilingLightSafely() throws IOException {
        YeelightClient client = clientForResolvedAddress(false);
        try {
            client.getPower();
        } catch (IOException firstFailure) {
            yeelightAddressResolver.invalidate();
            client = clientForResolvedAddress(true);
            client.getPower();
        }

        try {
            return client.toggle();
        } catch (IOException toggleFailure) {
            // 下次操作重新按 MAC 解析，但本次不能重发有副作用的命令。
            yeelightAddressResolver.invalidate();
            throw toggleFailure;
        }
    }

    private YeelightClient clientForResolvedAddress(boolean forceRefresh) throws IOException {
        String host = yeelightAddressResolver.resolve(forceRefresh);
        Log.i(TAG, "yeelight_host=" + host + " forced_refresh=" + forceRefresh);
        return new YeelightClient(host, DeviceControlConfig.YEELIGHT_PORT);
    }

    /**
     * 发网络唤醒包开机台式机。注意只能报告"包已发出"，无法知道对方是否真的开机了——
     * WOL 是单向的 UDP 广播，没有任何回执。
     */
    private void wakeDesktop() {
        if (!DeviceControlConfig.isWakeOnLanConfigured()) {
            clockView.setDesktopWakeStatus("NOT CONFIGURED");
            return;
        }
        if (wolRequestInFlight) {
            return;
        }
        wolRequestInFlight = true;
        clockView.setDesktopWakeStatus("SENDING...");
        deviceControlExecutor.execute(() -> {
            String status;
            try {
                WakeOnLanSender.send(
                        DeviceControlConfig.DESKTOP_MAC,
                        DeviceControlConfig.WOL_BROADCAST_HOST,
                        DeviceControlConfig.WOL_PORT);
                status = "PACKET SENT";
            } catch (Exception exception) {
                Log.w(TAG, "Unable to send Wake-on-LAN packet", exception);
                status = "SEND FAILED";
            }
            String completedStatus = status;
            runOnUiThread(() -> {
                wolRequestInFlight = false;
                clockView.setDesktopWakeStatus(completedStatus);
            });
        });
    }

    private void refreshTrafficStatus() {
        if (!DeviceControlConfig.isTrafficStatusConfigured()) {
            clockView.setTrafficNotConfigured();
            return;
        }
        if (trafficRequestInFlight) {
            return;
        }
        trafficRequestInFlight = true;
        clockView.setTrafficLoading();
        deviceControlExecutor.execute(() -> {
            // 用 null 表示失败：出错时走 setTrafficOffline()，它会保留上次成功的
            // 数字并打上 STALE 标记，而不是把屏幕清空。
            TrafficStatusFormatting.Display display = null;
            try {
                display = TrafficStatusFormatting.format(trafficStatusClient.fetch());
            } catch (Exception exception) {
                Log.w(TAG, "Unable to refresh traffic status", exception);
            }
            TrafficStatusFormatting.Display completedDisplay = display;
            runOnUiThread(() -> {
                trafficRequestInFlight = false;
                if (completedDisplay == null) {
                    clockView.setTrafficOffline();
                } else {
                    clockView.setTrafficDisplay(completedDisplay);
                }
            });
        });
    }

    /**
     * 切换表盘并持久化。
     *
     * @param resetAutoSwitchCountdown 是否重置自动轮换倒计时。用户手动切时传 true，
     *                                 自动轮换自己触发时传 false（它会自己重新计时）
     * @param next                     切换方向，只影响动画往哪个方向推
     * @return false 表示这次切换没做成——上一次动画还在播。调用方需要稍后重试
     */
    private boolean applyClockStyle(
            ClockStyle style, boolean resetAutoSwitchCountdown, boolean next) {
        // 熄屏状态下没必要播动画（反正是黑的），直接换掉即可。
        if (clockView.isBlackout()) {
            clockView.setClockStyle(style);
        } else if (!clockView.animateClockStyle(style, next)) {
            return false;
        }
        clockPreferences.edit().putString(KEY_CLOCK_STYLE, style.key()).apply();
        if (resetAutoSwitchCountdown && autoStyleSwitchEnabled) {
            resetAutoStyleSwitchCountdown(System.currentTimeMillis());
        }
        return true;
    }

    private void setAutoStyleSwitchEnabled(boolean enabled) {
        autoStyleSwitchEnabled = enabled;
        clockView.setAutoStyleSwitchEnabled(enabled);
        autoStyleHandler.removeCallbacks(autoStyleSwitch);
        SharedPreferences.Editor editor = clockPreferences.edit()
                .putBoolean(KEY_AUTO_STYLE_SWITCH_ENABLED, enabled);
        if (enabled) {
            editor.apply();
            resetAutoStyleSwitchCountdown(System.currentTimeMillis());
        } else {
            editor.remove(KEY_NEXT_AUTO_STYLE_SWITCH_AT).apply();
        }
    }

    /**
     * 界面回到前台时恢复自动轮换。
     *
     * <p>倒计时用的是持久化的绝对时间戳而不是"还剩多少分钟"，因为 Activity 在后台时
     * Handler 定时器已经被取消了。回来时对照存的时间戳分三种情况：
     *
     * <ul>
     *   <li>已经过点了 → 立刻切一次，然后重新计时</li>
     *   <li>存的时间戳不可信（超过一个完整间隔，说明系统时钟被改过）→ 重新计时</li>
     *   <li>正常的将来时刻 → 接着原来的倒计时走完剩余部分</li>
     * </ul>
     */
    private void resumeAutoStyleSwitch() {
        autoStyleHandler.removeCallbacks(autoStyleSwitch);
        if (!autoStyleSwitchEnabled) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        long nextSwitchAtMs = clockPreferences.getLong(KEY_NEXT_AUTO_STYLE_SWITCH_AT, 0L);
        if (ClockStyleSwitching.isDue(nextSwitchAtMs, nowMs)) {
            if (applyClockStyle(clockView.getClockStyle().next(), false, true)) {
                resetAutoStyleSwitchCountdown(nowMs);
            } else {
                // 有动画在播，切不了，过 500ms 再试。
                autoStyleHandler.postDelayed(autoStyleSwitch, STYLE_SWITCH_RETRY_MS);
            }
        } else if (!ClockStyleSwitching.isUsableFutureTime(nextSwitchAtMs, nowMs)) {
            resetAutoStyleSwitchCountdown(nowMs);
        } else {
            scheduleAutoStyleSwitch(nextSwitchAtMs, nowMs);
        }
    }

    /** 倒计时到点时的回调：切下一个表盘，然后重新开始计时。 */

    private void handleAutoStyleSwitch() {
        if (!autoStyleSwitchEnabled) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (applyClockStyle(clockView.getClockStyle().next(), false, true)) {
            resetAutoStyleSwitchCountdown(nowMs);
        } else {
            autoStyleHandler.postDelayed(autoStyleSwitch, STYLE_SWITCH_RETRY_MS);
        }
    }

    /** 重新开始一个完整的轮换周期：算出下次时间、存盘、安排定时器。 */
    private void resetAutoStyleSwitchCountdown(long nowMs) {
        long nextSwitchAtMs = ClockStyleSwitching.nextAutoSwitchAt(nowMs);
        clockPreferences.edit()
                .putLong(KEY_NEXT_AUTO_STYLE_SWITCH_AT, nextSwitchAtMs)
                .apply();
        scheduleAutoStyleSwitch(nextSwitchAtMs, nowMs);
    }

    private void scheduleAutoStyleSwitch(long nextSwitchAtMs, long nowMs) {
        // 先取消旧的，防止重复排期导致一次到点切两下。
        // 延迟至少 1ms：postDelayed 传 0 或负数会立即执行，可能造成递归重入。
        autoStyleHandler.removeCallbacks(autoStyleSwitch);
        autoStyleHandler.postDelayed(autoStyleSwitch, Math.max(1L, nextSwitchAtMs - nowMs));
    }

    /**
     * 界面不可见时收尾：停掉定时器、关掉所有浮层、注销广播接收器。
     *
     * <p>关浮层是有意为之——下次回到前台应该是干净的表盘，而不是几小时前忘了关的
     * 设置界面。
     */
    @Override
    protected void onStop() {
        autoStyleHandler.removeCallbacks(autoStyleSwitch);
        // 界面都退到后台了，点亮窗口没有意义；留着它下次回到前台还会压住熄屏。
        cancelTouchWake();
        boolean overlayWasVisible = clockView.isSettingsVisible()
                || clockView.isDeviceMenuVisible();
        if (clockView.isSettingsVisible()) {
            clockView.closeSettings();
        }
        if (clockView.isDeviceMenuVisible()) {
            clockView.setDeviceMenuVisible(false);
        }
        if (overlayWasVisible) {
            sendSettingsOpen(false);
        }
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver);
            statusReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        deviceControlExecutor.shutdownNow();
        super.onDestroy();
    }

    // --- 以下四个方法都是给 StandbyService 发指令 ---
    // 统一用 startForegroundService + Intent 的 action 来传，而不是 bindService：
    // 这些都是"发完就不管"的单向通知，不需要拿返回值，也就不必维护绑定的生命周期。
    // 服务已经在跑时，重复调用只会走一次 onStartCommand，不会重建服务。

    private void startStandbyService() {
        Intent intent = new Intent(this, StandbyService.class);
        startForegroundService(intent);
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, StandbyService.class).setAction(action);
        startForegroundService(intent);
    }

    private void sendServiceActionWithDelta(String action, int deltaMinutes) {
        Intent intent = new Intent(this, StandbyService.class)
                .setAction(action)
                .putExtra(StandbyService.EXTRA_BEDTIME_DELTA_MINUTES, deltaMinutes);
        startForegroundService(intent);
    }

    /**
     * 告诉 Service 浮层开着还是关着。Service 收到后会在浮层打开期间压住自动熄屏——
     * 用户正在操作设置界面，这时候黑屏就太傻了。
     */
    private void sendSettingsOpen(boolean open) {
        Intent intent = new Intent(this, StandbyService.class)
                .setAction(StandbyService.ACTION_SET_SETTINGS_OPEN)
                .putExtra(StandbyService.EXTRA_SETTINGS_OPEN, open);
        startForegroundService(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockView.start();
        enterImmersiveMode();
    }

    @Override
    protected void onPause() {
        clockView.stop();
        super.onPause();
    }

    /**
     * 重新拿到焦点时再进一次沉浸模式。
     *
     * <p>必须有这个：用户从屏幕边缘划出系统栏后，或者任何弹窗消失后，
     * 系统栏不会自己再藏回去，得手动重新申请。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    /** 故意留空：这台手机是专职时钟，返回键不该退出，得让时钟一直在前台。 */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // This phone is a dedicated clock. Keep the single clock task in front.
    }

    /**
     * 进入全屏沉浸模式，藏掉状态栏和导航栏。
     *
     * <p>这里两套 API 都调了：新的 WindowInsetsController（Android 11+）和旧的
     * setSystemUiVisibility（已废弃）。看着冗余，但在 MIUI 这类深度定制系统上
     * 单用新 API 有时藏不干净，两套一起下才稳，所以保留并加了 @SuppressWarnings。
     *
     * <p>BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 的意思是：用户从边缘划入时系统栏
     * 临时浮现，过几秒自动消失，不会把界面顶开。
     */
    @SuppressWarnings("deprecation")
    private void enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
