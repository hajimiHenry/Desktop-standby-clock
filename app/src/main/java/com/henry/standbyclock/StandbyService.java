package com.henry.standbyclock;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻前台服务：管环境光自动熄屏和睡眠提醒这两件必须一直活着的事。
 *
 * <p>为什么要独立成服务而不是放在 Activity 里：Activity 随时可能被系统回收重建，
 * 但"关灯 20 秒后熄屏""23:30 弹提醒"这些计时不能中断。做成前台服务（带常驻通知）
 * 后，系统基本不会杀它，onStartCommand 也返回 START_STICKY 让它被杀后自动重启。
 *
 * <p>与界面的通信是单向双通道的：
 * <ul>
 *   <li>界面 → 服务：Activity 用 Intent 的 action 发指令（ACTION_ 开头的常量）</li>
 *   <li>服务 → 界面：sendBroadcast 广播状态（ACTION_DISPLAY_MODE / ACTION_BEDTIME_STATE）</li>
 * </ul>
 * 不用 bindService 是因为界面可能根本不在（比如熄屏时），服务不该依赖它存在。
 *
 * <p>所有状态都会即时写进 SharedPreferences，这样服务被杀重启后能接着原来的状态跑。
 */
public final class StandbyService extends Service implements SensorEventListener {
    // --- 界面发给服务的指令 ---
    /** 界面刚起来，问服务要一次当前完整状态。 */
    static final String ACTION_REQUEST_STATUS =
            "com.henry.standbyclock.action.REQUEST_STANDBY_STATUS";
    static final String ACTION_BEDTIME_DONE =
            "com.henry.standbyclock.action.BEDTIME_DONE";
    static final String ACTION_BEDTIME_SNOOZE =
            "com.henry.standbyclock.action.BEDTIME_SNOOZE";
    static final String ACTION_ADJUST_BEDTIME =
            "com.henry.standbyclock.action.ADJUST_BEDTIME";
    static final String ACTION_TOGGLE_BEDTIME =
            "com.henry.standbyclock.action.TOGGLE_BEDTIME";
    static final String ACTION_TOGGLE_BEDTIME_SOUND =
            "com.henry.standbyclock.action.TOGGLE_BEDTIME_SOUND";
    static final String ACTION_SEDENTARY_DONE =
            "com.henry.standbyclock.action.SEDENTARY_DONE";
    static final String ACTION_TOGGLE_SEDENTARY =
            "com.henry.standbyclock.action.TOGGLE_SEDENTARY";
    static final String ACTION_TOGGLE_SEDENTARY_SOUND =
            "com.henry.standbyclock.action.TOGGLE_SEDENTARY_SOUND";
    static final String ACTION_ADJUST_SEDENTARY_INTERVAL =
            "com.henry.standbyclock.action.ADJUST_SEDENTARY_INTERVAL";
    static final String ACTION_ADJUST_SEDENTARY_START =
            "com.henry.standbyclock.action.ADJUST_SEDENTARY_START";
    /** 浮层开 / 关，服务据此临时压住自动熄屏。 */
    static final String ACTION_SET_SETTINGS_OPEN =
            "com.henry.standbyclock.action.SET_SETTINGS_OPEN";

    // --- 服务广播给界面的状态 ---
    static final String ACTION_DISPLAY_MODE =
            "com.henry.standbyclock.action.DISPLAY_MODE";
    static final String ACTION_BEDTIME_STATE =
            "com.henry.standbyclock.action.BEDTIME_STATE";
    static final String ACTION_SEDENTARY_STATE =
            "com.henry.standbyclock.action.SEDENTARY_STATE";
    static final String EXTRA_BLACKOUT = "blackout";
    static final String EXTRA_BEDTIME_ACTIVE = "bedtime_active";
    static final String EXTRA_BEDTIME_ENABLED = "bedtime_enabled";
    static final String EXTRA_BEDTIME_HOUR = "bedtime_hour";
    static final String EXTRA_BEDTIME_MINUTE = "bedtime_minute";
    static final String EXTRA_BEDTIME_SNOOZED = "bedtime_snoozed";
    static final String EXTRA_BEDTIME_SOUND_ENABLED = "bedtime_sound_enabled";
    static final String EXTRA_BEDTIME_DELTA_MINUTES = "bedtime_delta_minutes";
    static final String EXTRA_SEDENTARY_ACTIVE = "sedentary_active";
    static final String EXTRA_SEDENTARY_ENABLED = "sedentary_enabled";
    static final String EXTRA_SEDENTARY_SOUND_ENABLED = "sedentary_sound_enabled";
    static final String EXTRA_SEDENTARY_INTERVAL_MINUTES = "sedentary_interval_minutes";
    static final String EXTRA_SEDENTARY_START_HOUR = "sedentary_start_hour";
    static final String EXTRA_SEDENTARY_START_MINUTE = "sedentary_start_minute";
    static final String EXTRA_DELTA_MINUTES = "delta_minutes";
    static final String EXTRA_SETTINGS_OPEN = "settings_open";

    private static final String TAG = "StandbyService";
    private static final String CHANNEL_ID = "standby_monitoring";
    private static final String PREFERENCES = "standby_preferences";
    /** 早期版本用的偏好文件名，现在只用于一次性迁移，见 migrateLegacyStateAndRemoveCalibration。 */
    private static final String LEGACY_PREFERENCES = "gaze_preferences";
    private static final int NOTIFICATION_ID = 41;
    /** 睡眠提醒的轮询间隔。15 秒足够精确，又不至于频繁唤醒 CPU。 */
    private static final long BEDTIME_CHECK_INTERVAL_MS = 15_000L;
    /** 点"+15 MIN"推迟的时长。 */
    private static final long BEDTIME_SNOOZE_MS = 15L * 60L * 1_000L;
    /** 两次提示音之间的间隔。 */
    private static final long BEDTIME_SOUND_REPEAT_MS = 5L * 60L * 1_000L;
    /**
     * 光照读数写日志的最小间隔。传感器每秒能报好几次，不限流的话日志会被刷爆，
     * 排查问题时反而没法看。
     */
    private static final long LIGHT_LOG_INTERVAL_MS = 60_000L;
    private static final String KEY_BEDTIME_ENABLED = "bedtime_enabled";
    private static final String KEY_BEDTIME_HOUR = "bedtime_hour";
    private static final String KEY_BEDTIME_MINUTE = "bedtime_minute";
    private static final String KEY_BEDTIME_ACTIVE = "bedtime_active";
    private static final String KEY_BEDTIME_REMINDER_DATE = "bedtime_reminder_date";
    private static final String KEY_BEDTIME_ACKNOWLEDGED_DATE = "bedtime_acknowledged_date";
    private static final String KEY_BEDTIME_SNOOZE_UNTIL = "bedtime_snooze_until";
    private static final String KEY_BEDTIME_SOUND_ENABLED = "bedtime_sound_enabled";
    private static final String KEY_BEDTIME_SOUND_PLAY_COUNT = "bedtime_sound_play_count";
    private static final String KEY_BEDTIME_NEXT_SOUND_AT = "bedtime_next_sound_at";
    private static final String KEY_SEDENTARY_ENABLED = "sedentary_enabled";
    private static final String KEY_SEDENTARY_ACTIVE = "sedentary_active";
    private static final String KEY_SEDENTARY_LAST_DISMISSED = "sedentary_last_dismissed";
    private static final String KEY_SEDENTARY_SOUND_ENABLED = "sedentary_sound_enabled";
    private static final String KEY_SEDENTARY_SOUND_PLAY_COUNT = "sedentary_sound_play_count";
    private static final String KEY_SEDENTARY_NEXT_SOUND_AT = "sedentary_next_sound_at";
    private static final String KEY_SEDENTARY_INTERVAL_MINUTES = "sedentary_interval_minutes";
    private static final String KEY_SEDENTARY_START_HOUR = "sedentary_start_hour";
    private static final String KEY_SEDENTARY_START_MINUTE = "sedentary_start_minute";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 环境光的决策状态机，纯逻辑部分都在那个类里。 */
    private final AmbientLightPolicy ambientLightPolicy = new AmbientLightPolicy();
    /** 确认期内传感器可能不再上报，靠这个定时回调推进状态机。 */
    private final Runnable ambientEvaluation = this::evaluatePendingAmbientTransition;
    /** 睡眠提醒的轮询任务，跑完自己重新排下一次，形成 15 秒一轮的循环。 */
    private final Runnable bedtimeCheck = new Runnable() {
        @Override
        public void run() {
            evaluateBedtimeSchedule();
            evaluateSedentarySchedule();
            mainHandler.postDelayed(this, BEDTIME_CHECK_INTERVAL_MS);
        }
    };

    private SharedPreferences preferences;
    private SensorManager sensorManager;
    private Sensor ambientLightSensor;
    /** 部分唤醒锁，保证 CPU 不休眠，计时和传感器回调才不会被冻住。 */
    private PowerManager.WakeLock wakeLock;
    /** 用来跑 root 命令（把时钟拉回前台），阻塞操作不能放主线程。 */
    private ExecutorService screenExecutor;

    /** 当前是否处于熄屏。注意这是"最终生效值"，不等于 policy 的原始判断，见 updateEffectiveDisplayMode。 */
    private boolean displayBlackout;

    // --- 睡眠提醒的状态，全部会持久化到 SharedPreferences ---
    private boolean bedtimeEnabled;
    /** 提醒是否正在屏幕上显示。 */
    private boolean bedtimeReminderActive;
    private int bedtimeHour;
    private int bedtimeMinute;
    /** 推迟到这个时间戳之前不再提醒。0 表示没在推迟状态。 */
    private long bedtimeSnoozeUntil;
    private boolean bedtimeSoundEnabled;
    /** 本次提醒已经响了几次，上限见 BedtimeSchedule.MAX_SOUND_PLAYS。 */
    private int bedtimeSoundPlayCount;
    private long bedtimeNextSoundAt;
    /** 本次提醒属于哪一天（yyyy-MM-dd）。推迟后重新激活时要沿用这个日期。 */
    private String bedtimeReminderDate = "";
    /** 用户点过"DONE"的日期，用来保证同一天不再重复提醒。 */
    private String bedtimeAcknowledgedDate = "";

    // --- 久坐提醒的状态 ---
    private boolean sedentaryEnabled;
    private boolean sedentaryReminderActive;
    /** 上次按掉久坐提醒的时间戳。首次启动时初始化为当前时刻，让第一次提醒在间隔时长之后。 */
    private long sedentaryLastDismissedMs;
    private boolean sedentarySoundEnabled;
    private int sedentarySoundPlayCount;
    private long sedentaryNextSoundAt;
    /** 可调的提醒间隔（分钟），默认 45，范围 15-120。 */
    private int sedentaryIntervalMinutes;
    /** 工作时段起始时间，默认 09:00。结束时间跟随就寝设置。 */
    private int sedentaryStartHour;
    private int sedentaryStartMinute;

    /** 界面上有浮层开着，此时暂不熄屏。 */
    private boolean settingsDisplayHeld;
    private long lastLightLogMs;

    /**
     * 服务创建。
     *
     * <p>@SuppressLint("WakelockTimeout")：Lint 会警告不带超时的唤醒锁有耗电风险，
     * 这里是有意的——设备一直插着电当时钟用，CPU 必须常醒着才能持续监测光线。
     * 锁在 onDestroy 里释放。
     */
    @SuppressLint("WakelockTimeout")
    @Override
    public void onCreate() {
        super.onCreate();
        // 前台服务必须在创建后 5 秒内调 startForeground 挂出通知，否则系统直接杀掉。
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        migrateLegacyStateAndRemoveCalibration();
        loadBedtimeState();
        loadSedentaryState();

        screenExecutor = Executors.newSingleThreadExecutor();
        PowerManager powerManager = getSystemService(PowerManager.class);
        // PARTIAL_WAKE_LOCK 只保持 CPU 运行，不管屏幕亮不亮（屏幕由 Activity 那边控制）。
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":ambient-monitor");
        wakeLock.acquire();

        registerAmbientLightSensor();
        mainHandler.post(bedtimeCheck);
        Log.i(TAG, "Standby monitoring service started");
    }

    /**
     * 处理界面发来的指令。
     *
     * <p>intent 可能为 null——服务被系统杀掉后自动重启时就是这样，此时什么都不用做，
     * onCreate 已经把状态从磁盘恢复好了。
     *
     * @return START_STICKY，意思是"被杀掉后请重启我"，这正是常驻时钟需要的
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_REQUEST_STATUS:
                    broadcastDisplayMode();
                    broadcastBedtimeState();
                    broadcastSedentaryState();
                    break;
                case ACTION_BEDTIME_DONE:
                    acknowledgeBedtime();
                    break;
                case ACTION_BEDTIME_SNOOZE:
                    snoozeBedtime();
                    break;
                case ACTION_ADJUST_BEDTIME:
                    adjustBedtime(intent.getIntExtra(EXTRA_BEDTIME_DELTA_MINUTES, 0));
                    break;
                case ACTION_TOGGLE_BEDTIME:
                    toggleBedtimeEnabled();
                    break;
                case ACTION_TOGGLE_BEDTIME_SOUND:
                    toggleBedtimeSound();
                    break;
                case ACTION_SEDENTARY_DONE:
                    acknowledgeSedentary();
                    break;
                case ACTION_TOGGLE_SEDENTARY:
                    toggleSedentaryEnabled();
                    break;
                case ACTION_TOGGLE_SEDENTARY_SOUND:
                    toggleSedentarySound();
                    break;
                case ACTION_ADJUST_SEDENTARY_INTERVAL:
                    adjustSedentaryInterval(
                            intent.getIntExtra(EXTRA_DELTA_MINUTES, 0));
                    break;
                case ACTION_ADJUST_SEDENTARY_START:
                    adjustSedentaryStart(
                            intent.getIntExtra(EXTRA_DELTA_MINUTES, 0));
                    break;
                case ACTION_SET_SETTINGS_OPEN:
                    settingsDisplayHeld = intent.getBooleanExtra(EXTRA_SETTINGS_OPEN, false);
                    updateEffectiveDisplayMode();
                    break;
                default:
                    break;
            }
        }
        return START_STICKY;
    }

    /**
     * 注册光线传感器。
     *
     * <p>优先要"唤醒型"传感器（第二个参数 true）：这种传感器在 CPU 休眠时也能主动
     * 把系统叫醒上报数据。拿不到就退而求其次用普通的。两个都没有（设备无光线传感器）
     * 则记一条错误日志后放弃，屏幕保持常亮——功能降级，但时钟本身照常工作。
     */
    private void registerAmbientLightSensor() {
        sensorManager = getSystemService(SensorManager.class);
        ambientLightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT, true);
        if (ambientLightSensor == null) {
            ambientLightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
        if (ambientLightSensor == null) {
            Log.e(TAG, "No ambient light sensor; display will remain visible");
            return;
        }
        // SENSOR_DELAY_NORMAL 约 200ms 一次，对"关灯了没"这种判断绰绰有余，
        // 更高的频率只会白耗电。
        boolean registered = sensorManager.registerListener(
                this, ambientLightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        Log.i(TAG, "ambient_sensor=" + ambientLightSensor.getName()
                + " wake_up=" + ambientLightSensor.isWakeUpSensor()
                + " registered=" + registered);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_LIGHT || event.values.length == 0) {
            return;
        }
        float lux = event.values[0];
        // 用 elapsedRealtime（开机以来的单调时间）而不是墙上时钟，
        // 这样用户改系统时间不会打乱确认计时。
        long nowMs = SystemClock.elapsedRealtime();
        // 限流打日志，方便事后排查"为什么该熄屏时没熄"。
        if (lastLightLogMs == 0L || nowMs - lastLightLogMs >= LIGHT_LOG_INTERVAL_MS) {
            lastLightLogMs = nowMs;
            Log.i(TAG, "ambient_lux=" + lux);
        }
        handleAmbientChange(ambientLightPolicy.updateLux(lux, nowMs));
        // 每次喂完数据都重排定时器：传感器可能就此安静下去，
        // 得靠定时回调把确认期走完。
        scheduleAmbientEvaluation(nowMs);
    }

    /** 传感器精度变化，这里不关心——阈值配合持续确认本来就容得下精度抖动。 */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Lux thresholds and temporal confirmation tolerate normal sensor accuracy changes.
    }

    /** 定时回调：传感器没新数据时，靠它把"已经稳定够久了吗"再判断一次。 */
    private void evaluatePendingAmbientTransition() {
        long nowMs = SystemClock.elapsedRealtime();
        handleAmbientChange(ambientLightPolicy.evaluate(nowMs));
        scheduleAmbientEvaluation(nowMs);
    }

    private void handleAmbientChange(AmbientLightPolicy.Change change) {
        if (change == AmbientLightPolicy.Change.NONE) {
            return;
        }
        Log.i(TAG, "ambient_state=" + (ambientLightPolicy.isBlackout() ? "DARK" : "BRIGHT"));
        updateEffectiveDisplayMode();
    }

    private void scheduleAmbientEvaluation(long nowMs) {
        mainHandler.removeCallbacks(ambientEvaluation);
        long delayMs = ambientLightPolicy.nextEvaluationDelayMs(nowMs);
        if (delayMs >= 0L) {
            mainHandler.postDelayed(ambientEvaluation, delayMs);
        }
    }

    /**
     * 算出最终该不该熄屏，变了才广播。
     *
     * <p>光线只是三个条件之一：光线判断要熄屏，<em>并且</em>没有睡眠提醒在显示，
     * <em>并且</em>没有浮层开着。后两个是"压制"条件——提醒本来就要在暗房里被看到，
     * 用户操作浮层时黑屏也很荒唐。
     *
     * <p>提前 return 是重要的：这个方法会被多处频繁调用，不去重的话每次传感器
     * 回调都要广播一遍。
     */
    private void updateEffectiveDisplayMode() {
        boolean nextBlackout = ambientLightPolicy.isBlackout()
                && !bedtimeReminderActive
                && !settingsDisplayHeld;
        if (displayBlackout == nextBlackout) {
            return;
        }
        displayBlackout = nextBlackout;
        Log.i(TAG, "display_mode=" + (displayBlackout ? "BLACKOUT" : "VISIBLE"));
        broadcastDisplayMode();
    }

    private void broadcastDisplayMode() {
        // setPackage 限定只有本应用能收到，防止把状态泄露给其它应用。
        Intent intent = new Intent(ACTION_DISPLAY_MODE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_BLACKOUT, displayBlackout);
        sendBroadcast(intent);
    }

    /** 从磁盘恢复全部睡眠提醒状态。默认就寝时间是 23:30。 */
    private void loadBedtimeState() {
        bedtimeEnabled = preferences.getBoolean(KEY_BEDTIME_ENABLED, true);
        bedtimeHour = preferences.getInt(KEY_BEDTIME_HOUR, 23);
        bedtimeMinute = preferences.getInt(KEY_BEDTIME_MINUTE, 30);
        bedtimeReminderActive = preferences.getBoolean(KEY_BEDTIME_ACTIVE, false);
        bedtimeReminderDate = preferences.getString(KEY_BEDTIME_REMINDER_DATE, "");
        bedtimeAcknowledgedDate =
                preferences.getString(KEY_BEDTIME_ACKNOWLEDGED_DATE, "");
        bedtimeSnoozeUntil = preferences.getLong(KEY_BEDTIME_SNOOZE_UNTIL, 0L);
        bedtimeSoundEnabled = preferences.getBoolean(KEY_BEDTIME_SOUND_ENABLED, true);
        bedtimeSoundPlayCount = preferences.getInt(KEY_BEDTIME_SOUND_PLAY_COUNT, 0);
        bedtimeNextSoundAt = preferences.getLong(KEY_BEDTIME_NEXT_SOUND_AT, 0L);
    }

    /**
     * 每 15 秒跑一次的睡眠提醒主循环，按优先级分三种情况处理。
     *
     * <p>这一组改状态的方法都加了 synchronized：它们既可能被定时器（主线程）调用，
     * 也可能被 onStartCommand 里的用户指令调用，加锁避免两边同时改状态改乱。
     */
    private synchronized void evaluateBedtimeSchedule() {
        if (!bedtimeEnabled) {
            return;
        }
        // 情况一：提醒已经在显示了，只需维持显示状态并看看该不该再响一声。
        if (bedtimeReminderActive) {
            updateEffectiveDisplayMode();
            playBedtimeSoundIfDue(System.currentTimeMillis());
            return;
        }

        long nowMs = System.currentTimeMillis();
        // 情况二：处在"推迟 15 分钟"期间。没到点就什么都不做，到点了重新弹出来。
        if (bedtimeSnoozeUntil > 0L) {
            if (nowMs < bedtimeSnoozeUntil) {
                return;
            }
            bedtimeSnoozeUntil = 0L;
            // 沿用原来那次提醒的日期，而不是取"今天"。因为推迟很可能跨过午夜
            // （23:50 推迟 15 分钟就到了第二天），取今天的话会让昨晚这次提醒
            // 被记成新的一天，用户可能当晚被提醒两次。
            String reminderDate = bedtimeReminderDate.isEmpty()
                    ? LocalDateTime.now().toLocalDate().toString()
                    : bedtimeReminderDate;
            activateBedtimeReminder(reminderDate);
            return;
        }

        // 情况三：正常判断今天到点了没。

        LocalDateTime now = LocalDateTime.now();
        if (BedtimeSchedule.shouldActivate(
                now,
                bedtimeEnabled,
                bedtimeReminderActive,
                bedtimeAcknowledgedDate,
                bedtimeHour,
                bedtimeMinute)) {
            activateBedtimeReminder(now.toLocalDate().toString());
        }
    }

    /** 激活提醒：重置响铃计数、存盘、通知界面，并立刻响第一声。 */
    private synchronized void activateBedtimeReminder(String reminderDate) {
        // 就寝提醒优先级更高，久坐提醒正在显示的话先收掉。
        if (sedentaryReminderActive) {
            sedentaryReminderActive = false;
            sedentaryLastDismissedMs = System.currentTimeMillis();
            persistSedentaryState();
            broadcastSedentaryState();
        }
        bedtimeReminderActive = true;
        bedtimeReminderDate = reminderDate;
        bedtimeSnoozeUntil = 0L;
        bedtimeSoundPlayCount = 0;
        bedtimeNextSoundAt = System.currentTimeMillis();
        persistBedtimeState();
        updateEffectiveDisplayMode();
        broadcastBedtimeState();
        playBedtimeSoundIfDue(System.currentTimeMillis());
        Log.i(TAG, "bedtime_reminder=ACTIVE date=" + reminderDate);
    }

    /**
     * 用户点了"DONE"。把这次提醒的日期记进 acknowledgedDate，
     * 这样今天剩下的时间里不会再弹（判断逻辑见 BedtimeSchedule.shouldActivate）。
     */
    private synchronized void acknowledgeBedtime() {
        if (!bedtimeReminderActive) {
            return;
        }
        bedtimeAcknowledgedDate = bedtimeReminderDate.isEmpty()
                ? LocalDateTime.now().toLocalDate().toString()
                : bedtimeReminderDate;
        bedtimeReminderActive = false;
        bedtimeSnoozeUntil = 0L;
        bedtimeSoundPlayCount = 0;
        bedtimeNextSoundAt = 0L;
        persistBedtimeState();
        broadcastBedtimeState();
        updateEffectiveDisplayMode();
        Log.i(TAG, "bedtime_reminder=DONE date=" + bedtimeAcknowledgedDate);
    }

    /** 用户点了"+15 MIN"：收起提醒，15 分钟后原样再来一次。 */
    private synchronized void snoozeBedtime() {
        if (!bedtimeReminderActive) {
            return;
        }
        bedtimeReminderActive = false;
        bedtimeSnoozeUntil = System.currentTimeMillis() + BEDTIME_SNOOZE_MS;
        bedtimeSoundPlayCount = 0;
        bedtimeNextSoundAt = 0L;
        persistBedtimeState();
        broadcastBedtimeState();
        updateEffectiveDisplayMode();
        Log.i(TAG, "bedtime_reminder=SNOOZED until=" + bedtimeSnoozeUntil);
    }

    /**
     * 在设置里加减就寝时间。改完立刻重新判断一次：如果新时间已经过了，
     * 提醒应该马上弹出来，而不是等下一轮 15 秒轮询。
     */
    private synchronized void adjustBedtime(int deltaMinutes) {
        if (deltaMinutes == 0) {
            return;
        }
        // adjustMinutes 返回的是"当天第几分钟"，这里拆回小时和分钟。
        int adjusted = BedtimeSchedule.adjustMinutes(
                bedtimeHour, bedtimeMinute, deltaMinutes);
        bedtimeHour = adjusted / 60;
        bedtimeMinute = adjusted % 60;
        // 改了时间就作废之前的推迟，否则新设的时间会被旧的推迟挡住。
        bedtimeSnoozeUntil = 0L;
        persistBedtimeState();
        broadcastBedtimeState();
        evaluateBedtimeSchedule();
    }

    /** 总开关。关掉时要把正在显示的提醒和推迟状态一并清干净。 */
    private synchronized void toggleBedtimeEnabled() {
        bedtimeEnabled = !bedtimeEnabled;
        if (!bedtimeEnabled) {
            bedtimeReminderActive = false;
            bedtimeSnoozeUntil = 0L;
            bedtimeSoundPlayCount = 0;
            bedtimeNextSoundAt = 0L;
        }
        persistBedtimeState();
        broadcastBedtimeState();
        updateEffectiveDisplayMode();
        if (bedtimeEnabled) {
            evaluateBedtimeSchedule();
        }
    }

    private synchronized void toggleBedtimeSound() {
        bedtimeSoundEnabled = !bedtimeSoundEnabled;
        persistBedtimeState();
        broadcastBedtimeState();
    }

    /**
     * 该响就响一声提示音（一段 CRT 风格的短提示音，资源见 res/raw）。
     *
     * <p>每次都新建 MediaPlayer 并在播完后 release：这个音效几个小时才响一次，
     * 常驻一个播放器实例白占内存和音频焦点，不如用完就扔。
     */
    private synchronized void playBedtimeSoundIfDue(long nowMs) {
        if (!BedtimeSchedule.shouldPlaySound(
                bedtimeSoundEnabled,
                bedtimeReminderActive,
                bedtimeSoundPlayCount,
                bedtimeNextSoundAt,
                nowMs)) {
            return;
        }

        // USAGE_MEDIA + CONTENT_TYPE_SONIFICATION：走媒体音量通道播放一段提示音效。
        // 这样勿扰模式下也能响，而且跟着媒体音量走，用户能自己调大小。
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        MediaPlayer player = MediaPlayer.create(
                this, R.raw.bedtime_crt_chime, attributes, 0);
        if (player == null) {
            Log.e(TAG, "Unable to create bedtime sound player");
            return;
        }
        player.setOnCompletionListener(MediaPlayer::release);
        player.setOnErrorListener((failedPlayer, what, extra) -> {
            failedPlayer.release();
            Log.e(TAG, "Bedtime sound playback failed: what=" + what + " extra=" + extra);
            return true;
        });
        player.start();

        bedtimeSoundPlayCount++;
        // 还没响够就排下一次（5 分钟后），响够了置 0 表示不再有下一次。
        bedtimeNextSoundAt = bedtimeSoundPlayCount < BedtimeSchedule.MAX_SOUND_PLAYS
                ? nowMs + BEDTIME_SOUND_REPEAT_MS
                : 0L;
        persistBedtimeState();
        Log.i(TAG, "bedtime_sound=PLAY count=" + bedtimeSoundPlayCount);
    }

    /**
     * 把全部睡眠提醒状态写盘。每次状态变化后都调，因为服务随时可能被杀，
     * 重启后要能原样接上。用 apply() 异步写，不阻塞主线程。
     */
    private void persistBedtimeState() {
        preferences.edit()
                .putBoolean(KEY_BEDTIME_ENABLED, bedtimeEnabled)
                .putInt(KEY_BEDTIME_HOUR, bedtimeHour)
                .putInt(KEY_BEDTIME_MINUTE, bedtimeMinute)
                .putBoolean(KEY_BEDTIME_ACTIVE, bedtimeReminderActive)
                .putString(KEY_BEDTIME_REMINDER_DATE, bedtimeReminderDate)
                .putString(KEY_BEDTIME_ACKNOWLEDGED_DATE, bedtimeAcknowledgedDate)
                .putLong(KEY_BEDTIME_SNOOZE_UNTIL, bedtimeSnoozeUntil)
                .putBoolean(KEY_BEDTIME_SOUND_ENABLED, bedtimeSoundEnabled)
                .putInt(KEY_BEDTIME_SOUND_PLAY_COUNT, bedtimeSoundPlayCount)
                .putLong(KEY_BEDTIME_NEXT_SOUND_AT, bedtimeNextSoundAt)
                .apply();
    }

    private void broadcastBedtimeState() {
        Intent intent = new Intent(ACTION_BEDTIME_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_BEDTIME_ACTIVE, bedtimeReminderActive)
                .putExtra(EXTRA_BEDTIME_ENABLED, bedtimeEnabled)
                .putExtra(EXTRA_BEDTIME_HOUR, bedtimeHour)
                .putExtra(EXTRA_BEDTIME_MINUTE, bedtimeMinute)
                .putExtra(EXTRA_BEDTIME_SOUND_ENABLED, bedtimeSoundEnabled)
                .putExtra(EXTRA_BEDTIME_SNOOZED,
                        bedtimeSnoozeUntil > System.currentTimeMillis());
        sendBroadcast(intent);
    }

    // ── 久坐提醒 ──────────────────────────────────────────────────────

    /** 从磁盘恢复久坐提醒状态。首次运行时 lastDismissed 取当前时刻，让第一次提醒 45 分钟后才来。 */
    private void loadSedentaryState() {
        sedentaryEnabled = preferences.getBoolean(KEY_SEDENTARY_ENABLED, true);
        sedentarySoundEnabled = preferences.getBoolean(KEY_SEDENTARY_SOUND_ENABLED, true);
        sedentaryReminderActive = preferences.getBoolean(KEY_SEDENTARY_ACTIVE, false);
        sedentaryLastDismissedMs = preferences.getLong(
                KEY_SEDENTARY_LAST_DISMISSED, System.currentTimeMillis());
        sedentarySoundPlayCount = preferences.getInt(KEY_SEDENTARY_SOUND_PLAY_COUNT, 0);
        sedentaryNextSoundAt = preferences.getLong(KEY_SEDENTARY_NEXT_SOUND_AT, 0L);
        sedentaryIntervalMinutes = preferences.getInt(
                KEY_SEDENTARY_INTERVAL_MINUTES,
                SedentaryReminder.DEFAULT_INTERVAL_MINUTES);
        sedentaryStartHour = preferences.getInt(
                KEY_SEDENTARY_START_HOUR, SedentaryReminder.DEFAULT_START_HOUR);
        sedentaryStartMinute = preferences.getInt(
                KEY_SEDENTARY_START_MINUTE, SedentaryReminder.DEFAULT_START_MINUTE);
    }

    /**
     * 久坐提醒的主判断，搭在就寝提醒同一个 15 秒轮询里。
     * 就寝提醒正在显示时不激活——就寝更重要，而且两个提醒叠在一起也看不了。
     */
    private synchronized void evaluateSedentarySchedule() {
        if (!sedentaryEnabled || bedtimeReminderActive) {
            return;
        }
        // 已经在显示了，只需看看该不该再响一声。
        if (sedentaryReminderActive) {
            playSedentarySoundIfDue(System.currentTimeMillis());
            return;
        }
        long nowMs = System.currentTimeMillis();
        long intervalMs = sedentaryIntervalMinutes * 60L * 1_000L;
        if (SedentaryReminder.shouldActivate(
                java.time.LocalTime.now(),
                sedentaryEnabled, sedentaryReminderActive,
                sedentaryLastDismissedMs, nowMs, intervalMs,
                sedentaryStartHour, sedentaryStartMinute,
                bedtimeHour, bedtimeMinute)) {
            activateSedentaryReminder();
        }
    }

    /** 激活久坐提醒：重置响铃计数、存盘、通知界面，立刻响第一声。 */
    private synchronized void activateSedentaryReminder() {
        sedentaryReminderActive = true;
        sedentarySoundPlayCount = 0;
        sedentaryNextSoundAt = System.currentTimeMillis();
        persistSedentaryState();
        broadcastSedentaryState();
        playSedentarySoundIfDue(System.currentTimeMillis());
        Log.i(TAG, "sedentary_reminder=ACTIVE");
    }

    /** 用户按了 OK，收起提醒并重置计时器。 */
    private synchronized void acknowledgeSedentary() {
        if (!sedentaryReminderActive) {
            return;
        }
        sedentaryReminderActive = false;
        sedentaryLastDismissedMs = System.currentTimeMillis();
        sedentarySoundPlayCount = 0;
        sedentaryNextSoundAt = 0L;
        persistSedentaryState();
        broadcastSedentaryState();
        Log.i(TAG, "sedentary_reminder=DONE");
    }

    /** 总开关。关掉时清除正在显示的提醒。 */
    private synchronized void toggleSedentaryEnabled() {
        sedentaryEnabled = !sedentaryEnabled;
        if (sedentaryEnabled) {
            // 刚打开时从现在开始计时，不立刻弹
            sedentaryLastDismissedMs = System.currentTimeMillis();
        } else {
            sedentaryReminderActive = false;
            sedentarySoundPlayCount = 0;
            sedentaryNextSoundAt = 0L;
        }
        persistSedentaryState();
        broadcastSedentaryState();
    }

    private synchronized void toggleSedentarySound() {
        sedentarySoundEnabled = !sedentarySoundEnabled;
        persistSedentaryState();
        broadcastSedentaryState();
    }

    /** 调整久坐提醒间隔，±15 分钟为一步，钳位到 15-120 分钟。 */
    private synchronized void adjustSedentaryInterval(int deltaMinutes) {
        sedentaryIntervalMinutes = SedentaryReminder.clampInterval(
                sedentaryIntervalMinutes + deltaMinutes);
        persistSedentaryState();
        broadcastSedentaryState();
    }

    /** 调整久坐提醒开始时间，复用 BedtimeSchedule 的循环取模逻辑。 */
    private synchronized void adjustSedentaryStart(int deltaMinutes) {
        int adjusted = BedtimeSchedule.adjustMinutes(
                sedentaryStartHour, sedentaryStartMinute, deltaMinutes);
        sedentaryStartHour = adjusted / 60;
        sedentaryStartMinute = adjusted % 60;
        persistSedentaryState();
        broadcastSedentaryState();
    }

    /** 复用就寝提醒同一段提示音。播放逻辑也完全一致：最多两次，间隔 5 分钟。 */
    private synchronized void playSedentarySoundIfDue(long nowMs) {
        if (!SedentaryReminder.shouldPlaySound(
                sedentarySoundEnabled,
                sedentaryReminderActive,
                sedentarySoundPlayCount,
                sedentaryNextSoundAt,
                nowMs)) {
            return;
        }

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        MediaPlayer player = MediaPlayer.create(
                this, R.raw.bedtime_crt_chime, attributes, 0);
        if (player == null) {
            Log.e(TAG, "Unable to create sedentary sound player");
            return;
        }
        player.setOnCompletionListener(MediaPlayer::release);
        player.setOnErrorListener((failedPlayer, what, extra) -> {
            failedPlayer.release();
            Log.e(TAG, "Sedentary sound playback failed: what=" + what + " extra=" + extra);
            return true;
        });
        player.start();

        sedentarySoundPlayCount++;
        sedentaryNextSoundAt = sedentarySoundPlayCount < SedentaryReminder.MAX_SOUND_PLAYS
                ? nowMs + SedentaryReminder.SOUND_REPEAT_MS
                : 0L;
        persistSedentaryState();
        Log.i(TAG, "sedentary_sound=PLAY count=" + sedentarySoundPlayCount);
    }

    private void persistSedentaryState() {
        preferences.edit()
                .putBoolean(KEY_SEDENTARY_ENABLED, sedentaryEnabled)
                .putBoolean(KEY_SEDENTARY_ACTIVE, sedentaryReminderActive)
                .putLong(KEY_SEDENTARY_LAST_DISMISSED, sedentaryLastDismissedMs)
                .putBoolean(KEY_SEDENTARY_SOUND_ENABLED, sedentarySoundEnabled)
                .putInt(KEY_SEDENTARY_SOUND_PLAY_COUNT, sedentarySoundPlayCount)
                .putLong(KEY_SEDENTARY_NEXT_SOUND_AT, sedentaryNextSoundAt)
                .putInt(KEY_SEDENTARY_INTERVAL_MINUTES, sedentaryIntervalMinutes)
                .putInt(KEY_SEDENTARY_START_HOUR, sedentaryStartHour)
                .putInt(KEY_SEDENTARY_START_MINUTE, sedentaryStartMinute)
                .apply();
    }

    private void broadcastSedentaryState() {
        Intent intent = new Intent(ACTION_SEDENTARY_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_SEDENTARY_ACTIVE, sedentaryReminderActive)
                .putExtra(EXTRA_SEDENTARY_ENABLED, sedentaryEnabled)
                .putExtra(EXTRA_SEDENTARY_SOUND_ENABLED, sedentarySoundEnabled)
                .putExtra(EXTRA_SEDENTARY_INTERVAL_MINUTES, sedentaryIntervalMinutes)
                .putExtra(EXTRA_SEDENTARY_START_HOUR, sedentaryStartHour)
                .putExtra(EXTRA_SEDENTARY_START_MINUTE, sedentaryStartMinute);
        sendBroadcast(intent);
    }

    /**
     * 一次性迁移：把老版本遗留的偏好搬到新文件，并删掉早已废弃的功能数据。
     *
     * <p>这个项目早期做过一版"注视唤醒"（gaze）功能，用前置摄像头判断有没有人在看，
     * 后来被更省电可靠的环境光方案取代了。这里负责把当时存在 gaze_preferences 里的
     * 睡眠提醒设置搬过来，并删掉 gaze_calibration 目录里的人脸标定数据。
     *
     * <p>迁移用 commit()（同步）而不是 apply()（异步）：必须确认新数据真的落盘了
     * 才敢删旧数据，否则中途断电就两头空。commit 返回 false 说明写失败，
     * 这时保留旧数据下次再试。
     */
    private void migrateLegacyStateAndRemoveCalibration() {
        SharedPreferences legacy = getSharedPreferences(LEGACY_PREFERENCES, MODE_PRIVATE);
        boolean safeToClearLegacy = true;
        // 只在"新文件还没有数据、旧文件有数据"时迁移，保证只发生一次，
        // 不会用旧数据覆盖用户后来改过的新设置。
        if (!preferences.contains(KEY_BEDTIME_ENABLED) && legacy.contains(KEY_BEDTIME_ENABLED)) {
            safeToClearLegacy = preferences.edit()
                    .putBoolean(KEY_BEDTIME_ENABLED,
                            legacy.getBoolean(KEY_BEDTIME_ENABLED, true))
                    .putInt(KEY_BEDTIME_HOUR, legacy.getInt(KEY_BEDTIME_HOUR, 23))
                    .putInt(KEY_BEDTIME_MINUTE, legacy.getInt(KEY_BEDTIME_MINUTE, 30))
                    .putBoolean(KEY_BEDTIME_ACTIVE,
                            legacy.getBoolean(KEY_BEDTIME_ACTIVE, false))
                    .putString(KEY_BEDTIME_REMINDER_DATE,
                            legacy.getString(KEY_BEDTIME_REMINDER_DATE, ""))
                    .putString(KEY_BEDTIME_ACKNOWLEDGED_DATE,
                            legacy.getString(KEY_BEDTIME_ACKNOWLEDGED_DATE, ""))
                    .putLong(KEY_BEDTIME_SNOOZE_UNTIL,
                            legacy.getLong(KEY_BEDTIME_SNOOZE_UNTIL, 0L))
                    .putBoolean(KEY_BEDTIME_SOUND_ENABLED,
                            legacy.getBoolean(KEY_BEDTIME_SOUND_ENABLED, true))
                    .putInt(KEY_BEDTIME_SOUND_PLAY_COUNT,
                            legacy.getInt(KEY_BEDTIME_SOUND_PLAY_COUNT, 0))
                    .putLong(KEY_BEDTIME_NEXT_SOUND_AT,
                            legacy.getLong(KEY_BEDTIME_NEXT_SOUND_AT, 0L))
                    .commit();
            if (safeToClearLegacy) {
                Log.i(TAG, "Migrated bedtime settings from legacy gaze preferences");
            } else {
                Log.e(TAG, "Unable to persist migrated bedtime settings; keeping legacy data");
            }
        }
        if (safeToClearLegacy) {
            legacy.edit().clear().apply();
        }

        File calibrationDirectory = new File(getFilesDir(), "gaze_calibration");
        if (calibrationDirectory.exists() && !deleteRecursively(calibrationDirectory)) {
            Log.w(TAG, "Unable to fully remove legacy gaze calibration data");
        }
    }

    /** 递归删除目录。listFiles 返回 null 说明不是目录（或读不了），直接删自己。 */
    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return file.delete();
    }

    /**
     * 用户在最近任务里划掉了时钟，这里负责把它拉回来——这台手机是专职时钟，
     * 划掉基本都是误操作。用 root 执行 am start 实现，没 root 就只能作罢。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "Clock task removed; restoring the existing clock task");
        if (screenExecutor != null && !screenExecutor.isShutdown()) {
            screenExecutor.execute(() -> {
                int exitCode = RootShell.bringClockToFront(getPackageName());
                Log.i(TAG, "Restore clock task exit=" + exitCode);
            });
        }
        super.onTaskRemoved(rootIntent);
    }

    /**
     * 建通知渠道。IMPORTANCE_LOW 表示不出声、不弹横幅——这条通知只是前台服务的
     * 法定要求，不该打扰用户。
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Clock monitoring",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Ambient-light display automation and bedtime reminders");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    /**
     * 构建前台服务的常驻通知。点它能回到时钟界面。
     * FLAG_IMMUTABLE 是 Android 12 起的强制要求（PendingIntent 必须声明可变性）。
     */
    private Notification createNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Desktop Standby Clock monitoring")
                .setContentText("Ambient-light display automation is active")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 服务销毁，把占用的系统资源逐个还回去：定时器、传感器监听、线程池、唤醒锁。
     * 唤醒锁尤其不能漏——不释放的话 CPU 会一直醒着狂耗电。
     */
    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(ambientEvaluation);
        mainHandler.removeCallbacks(bedtimeCheck);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (screenExecutor != null) {
            screenExecutor.shutdownNow();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        Log.i(TAG, "Standby monitoring service stopped");
        super.onDestroy();
    }

    /** 返回 null：这是个纯粹的启动型服务，不支持绑定，通信全走 Intent 和广播。 */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
