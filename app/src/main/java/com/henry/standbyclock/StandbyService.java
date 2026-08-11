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

/** Keeps ambient-light display automation and the bedtime reminder alive. */
public final class StandbyService extends Service implements SensorEventListener {
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
    static final String ACTION_SET_SETTINGS_OPEN =
            "com.henry.standbyclock.action.SET_SETTINGS_OPEN";
    static final String ACTION_DISPLAY_MODE =
            "com.henry.standbyclock.action.DISPLAY_MODE";
    static final String ACTION_BEDTIME_STATE =
            "com.henry.standbyclock.action.BEDTIME_STATE";
    static final String EXTRA_BLACKOUT = "blackout";
    static final String EXTRA_BEDTIME_ACTIVE = "bedtime_active";
    static final String EXTRA_BEDTIME_ENABLED = "bedtime_enabled";
    static final String EXTRA_BEDTIME_HOUR = "bedtime_hour";
    static final String EXTRA_BEDTIME_MINUTE = "bedtime_minute";
    static final String EXTRA_BEDTIME_SNOOZED = "bedtime_snoozed";
    static final String EXTRA_BEDTIME_SOUND_ENABLED = "bedtime_sound_enabled";
    static final String EXTRA_BEDTIME_DELTA_MINUTES = "bedtime_delta_minutes";
    static final String EXTRA_SETTINGS_OPEN = "settings_open";

    private static final String TAG = "StandbyService";
    private static final String CHANNEL_ID = "standby_monitoring";
    private static final String PREFERENCES = "standby_preferences";
    private static final String LEGACY_PREFERENCES = "gaze_preferences";
    private static final int NOTIFICATION_ID = 41;
    private static final long BEDTIME_CHECK_INTERVAL_MS = 15_000L;
    private static final long BEDTIME_SNOOZE_MS = 15L * 60L * 1_000L;
    private static final long BEDTIME_SOUND_REPEAT_MS = 5L * 60L * 1_000L;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AmbientLightPolicy ambientLightPolicy = new AmbientLightPolicy();
    private final Runnable ambientEvaluation = this::evaluatePendingAmbientTransition;
    private final Runnable bedtimeCheck = new Runnable() {
        @Override
        public void run() {
            evaluateBedtimeSchedule();
            mainHandler.postDelayed(this, BEDTIME_CHECK_INTERVAL_MS);
        }
    };

    private SharedPreferences preferences;
    private SensorManager sensorManager;
    private Sensor ambientLightSensor;
    private PowerManager.WakeLock wakeLock;
    private ExecutorService screenExecutor;
    private boolean displayBlackout;
    private boolean bedtimeEnabled;
    private boolean bedtimeReminderActive;
    private int bedtimeHour;
    private int bedtimeMinute;
    private long bedtimeSnoozeUntil;
    private boolean bedtimeSoundEnabled;
    private int bedtimeSoundPlayCount;
    private long bedtimeNextSoundAt;
    private String bedtimeReminderDate = "";
    private String bedtimeAcknowledgedDate = "";
    private boolean settingsDisplayHeld;
    private long lastLightLogMs;

    @SuppressLint("WakelockTimeout")
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        migrateLegacyStateAndRemoveCalibration();
        loadBedtimeState();

        screenExecutor = Executors.newSingleThreadExecutor();
        PowerManager powerManager = getSystemService(PowerManager.class);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":ambient-monitor");
        wakeLock.acquire();

        registerAmbientLightSensor();
        mainHandler.post(bedtimeCheck);
        Log.i(TAG, "Standby monitoring service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_REQUEST_STATUS:
                    broadcastDisplayMode();
                    broadcastBedtimeState();
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
        long nowMs = SystemClock.elapsedRealtime();
        if (lastLightLogMs == 0L || nowMs - lastLightLogMs >= LIGHT_LOG_INTERVAL_MS) {
            lastLightLogMs = nowMs;
            Log.i(TAG, "ambient_lux=" + lux);
        }
        handleAmbientChange(ambientLightPolicy.updateLux(lux, nowMs));
        scheduleAmbientEvaluation(nowMs);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Lux thresholds and temporal confirmation tolerate normal sensor accuracy changes.
    }

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
        Intent intent = new Intent(ACTION_DISPLAY_MODE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_BLACKOUT, displayBlackout);
        sendBroadcast(intent);
    }

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

    private synchronized void evaluateBedtimeSchedule() {
        if (!bedtimeEnabled) {
            return;
        }
        if (bedtimeReminderActive) {
            updateEffectiveDisplayMode();
            playBedtimeSoundIfDue(System.currentTimeMillis());
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (bedtimeSnoozeUntil > 0L) {
            if (nowMs < bedtimeSnoozeUntil) {
                return;
            }
            bedtimeSnoozeUntil = 0L;
            String reminderDate = bedtimeReminderDate.isEmpty()
                    ? LocalDateTime.now().toLocalDate().toString()
                    : bedtimeReminderDate;
            activateBedtimeReminder(reminderDate);
            return;
        }

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

    private synchronized void activateBedtimeReminder(String reminderDate) {
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

    private synchronized void adjustBedtime(int deltaMinutes) {
        if (deltaMinutes == 0) {
            return;
        }
        int adjusted = BedtimeSchedule.adjustMinutes(
                bedtimeHour, bedtimeMinute, deltaMinutes);
        bedtimeHour = adjusted / 60;
        bedtimeMinute = adjusted % 60;
        bedtimeSnoozeUntil = 0L;
        persistBedtimeState();
        broadcastBedtimeState();
        evaluateBedtimeSchedule();
    }

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

    private synchronized void playBedtimeSoundIfDue(long nowMs) {
        if (!BedtimeSchedule.shouldPlaySound(
                bedtimeSoundEnabled,
                bedtimeReminderActive,
                bedtimeSoundPlayCount,
                bedtimeNextSoundAt,
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
        bedtimeNextSoundAt = bedtimeSoundPlayCount < BedtimeSchedule.MAX_SOUND_PLAYS
                ? nowMs + BEDTIME_SOUND_REPEAT_MS
                : 0L;
        persistBedtimeState();
        Log.i(TAG, "bedtime_sound=PLAY count=" + bedtimeSoundPlayCount);
    }

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

    private void migrateLegacyStateAndRemoveCalibration() {
        SharedPreferences legacy = getSharedPreferences(LEGACY_PREFERENCES, MODE_PRIVATE);
        boolean safeToClearLegacy = true;
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

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Clock monitoring",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Ambient-light display automation and bedtime reminders");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
