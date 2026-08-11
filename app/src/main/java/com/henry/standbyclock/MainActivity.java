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
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "StandbyClock";
    private static final String CLOCK_PREFERENCES = "clock_preferences";
    private static final String KEY_CLOCK_STYLE = "clock_style";
    private static final String KEY_AUTO_STYLE_SWITCH_ENABLED = "auto_style_switch_enabled";
    private static final String KEY_NEXT_AUTO_STYLE_SWITCH_AT = "next_auto_style_switch_at";
    private static final long STYLE_SWITCH_RETRY_MS = 500L;
    private final YeelightClient yeelightClient = new YeelightClient(
            DeviceControlConfig.YEELIGHT_HOST, DeviceControlConfig.YEELIGHT_PORT);
    private final ExecutorService deviceControlExecutor = Executors.newSingleThreadExecutor();
    private ClockView clockView;
    private SharedPreferences clockPreferences;
    private final Handler autoStyleHandler = new Handler(Looper.getMainLooper());
    private boolean statusReceiverRegistered;
    private boolean displayBlackout;
    private boolean bedtimeReminderActive;
    private boolean autoStyleSwitchEnabled;
    private boolean lightRequestInFlight;
    private boolean wolRequestInFlight;
    private final Runnable autoStyleSwitch = this::handleAutoStyleSwitch;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (StandbyService.ACTION_DISPLAY_MODE.equals(intent.getAction())) {
                applyBlackout(intent.getBooleanExtra(StandbyService.EXTRA_BLACKOUT, false));
            } else if (StandbyService.ACTION_BEDTIME_STATE.equals(intent.getAction())) {
                bedtimeReminderActive = intent.getBooleanExtra(
                        StandbyService.EXTRA_BEDTIME_ACTIVE, false);
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

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(0xFF000000);
        window.setNavigationBarColor(0xFF000000);

        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        attributes.windowAnimations = 0;
        attributes.rotationAnimation =
                WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
        window.setAttributes(attributes);

        setShowWhenLocked(true);
        setTurnScreenOn(true);

        clockPreferences = getSharedPreferences(CLOCK_PREFERENCES, MODE_PRIVATE);
        autoStyleSwitchEnabled = clockPreferences.getBoolean(
                KEY_AUTO_STYLE_SWITCH_ENABLED, true);

        clockView = new ClockView(this);
        clockView.setClockStyle(
                ClockStyle.fromKey(clockPreferences.getString(KEY_CLOCK_STYLE, null)));
        clockView.setAutoStyleSwitchEnabled(autoStyleSwitchEnabled);
        clockView.setOnStyleSwipeListener(next -> applyClockStyle(
                next ? clockView.getClockStyle().next() : clockView.getClockStyle().previous(),
                true,
                next));
        clockView.setOnDeviceMenuVisibilityListener(visible -> {
            sendSettingsOpen(visible);
            if (visible) {
                clockView.setDesktopWakeStatus("READY");
                refreshCeilingLight();
            }
        });
        clockView.setOnLongClickListener(view -> {
            sendSettingsOpen(clockView.toggleSettings());
            return true;
        });
        clockView.setOnClickListener(view -> handleClockTap());
        setContentView(clockView);
        enterImmersiveMode();
        startStandbyService();
        overridePendingTransition(0, 0);
    }

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
        ContextCompat.registerReceiver(
                this,
                statusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        statusReceiverRegistered = true;
        sendServiceAction(StandbyService.ACTION_REQUEST_STATUS);
        resumeAutoStyleSwitch();
    }

    private void applyBlackout(boolean blackout) {
        displayBlackout = blackout;
        applyDisplayState();
    }

    private void applyDisplayState() {
        boolean effectiveBlackout = displayBlackout && !bedtimeReminderActive;
        clockView.setBlackout(effectiveBlackout);
        Window window = getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (effectiveBlackout) {
            attributes.screenBrightness = 0f;
        } else if (bedtimeReminderActive) {
            attributes.screenBrightness = 0.10f;
        } else {
            attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        window.setAttributes(attributes);
    }

    private void handleClockTap() {
        ClockView.UiAction action = clockView.resolveTapAction();
        switch (action) {
            case BEDTIME_DONE:
                sendServiceAction(StandbyService.ACTION_BEDTIME_DONE);
                break;
            case BEDTIME_SNOOZE:
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

    private void runCeilingLightRequest(boolean toggle) {
        if (lightRequestInFlight) {
            return;
        }
        lightRequestInFlight = true;
        clockView.setCeilingLightStatus(toggle ? "SWITCHING..." : "CHECKING...");
        deviceControlExecutor.execute(() -> {
            String status;
            try {
                YeelightClient.PowerState state = toggle
                        ? yeelightClient.toggle()
                        : yeelightClient.getPower();
                status = state == YeelightClient.PowerState.ON ? "ON" : "OFF";
            } catch (Exception exception) {
                Log.w(TAG, "Unable to control Yeelight ceiling light", exception);
                status = "OFFLINE";
            }
            String completedStatus = status;
            runOnUiThread(() -> {
                lightRequestInFlight = false;
                clockView.setCeilingLightStatus(completedStatus);
            });
        });
    }

    private void wakeDesktop() {
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

    private boolean applyClockStyle(
            ClockStyle style, boolean resetAutoSwitchCountdown, boolean next) {
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
                autoStyleHandler.postDelayed(autoStyleSwitch, STYLE_SWITCH_RETRY_MS);
            }
        } else if (!ClockStyleSwitching.isUsableFutureTime(nextSwitchAtMs, nowMs)) {
            resetAutoStyleSwitchCountdown(nowMs);
        } else {
            scheduleAutoStyleSwitch(nextSwitchAtMs, nowMs);
        }
    }

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

    private void resetAutoStyleSwitchCountdown(long nowMs) {
        long nextSwitchAtMs = ClockStyleSwitching.nextAutoSwitchAt(nowMs);
        clockPreferences.edit()
                .putLong(KEY_NEXT_AUTO_STYLE_SWITCH_AT, nextSwitchAtMs)
                .apply();
        scheduleAutoStyleSwitch(nextSwitchAtMs, nowMs);
    }

    private void scheduleAutoStyleSwitch(long nextSwitchAtMs, long nowMs) {
        autoStyleHandler.removeCallbacks(autoStyleSwitch);
        autoStyleHandler.postDelayed(autoStyleSwitch, Math.max(1L, nextSwitchAtMs - nowMs));
    }

    @Override
    protected void onStop() {
        autoStyleHandler.removeCallbacks(autoStyleSwitch);
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // This phone is a dedicated clock. Keep the single clock task in front.
    }

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
