package com.henry.standbyclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用更新后自动把时钟拉起来的广播接收器。
 *
 * <p>这台手机是专职时钟，重启后不该还要人手动点图标。注册的两个广播见
 * AndroidManifest：BOOT_COMPLETED（开机完成）和 MY_PACKAGE_REPLACED（应用被覆盖安装）。
 *
 * <p>注意这只是保底手段之一。在已 root 的 MIUI 上，更可靠的是 Magisk 的
 * service.d 脚本（scripts/standby-clock-service.sh），因为 MIUI 每次开机都会把
 * 自启动权限重置掉，可能压根不会发这个广播给我们。
 */
public final class PersistenceReceiver extends BroadcastReceiver {
    private static final String TAG = "StandbyPersistence";

    @Override
    public void onReceive(Context context, Intent intent) {
        // 只处理关心的两个广播，其它一律忽略（防止被别的 Intent 误触发）。
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        // 先把后台服务拉起来（环境光监测 + 睡眠提醒都在里面）。
        context.startForegroundService(new Intent(context, StandbyService.class));

        // onReceive 跑在主线程且只有约 10 秒寿命，而下面要执行 su 命令并等它返回，
        // 属于阻塞操作，不能直接在这里做。goAsync() 申请延长接收器的存活时间，
        // 换到子线程执行，最后必须调用 pendingResult.finish() 释放，否则系统会报
        // "BroadcastReceiver leaked" 甚至 ANR。
        PendingResult pendingResult = goAsync();
        Thread restoreThread = new Thread(() -> {
            int exitCode = RootShell.bringClockToFront(context.getPackageName());
            Log.i(TAG, "Persistent clock restore action=" + action + " exit=" + exitCode);
            if (exitCode != 0) {
                // root 方式失败（没 root / su 被拒），退回普通启动方式。
                // 在新版 Android 上后台启动 Activity 可能被系统拦截，所以只是兜底，
                // 不保证成功——因此要捕获异常，别让接收器线程崩掉。
                try {
                    Intent launchIntent = new Intent(context, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    context.startActivity(launchIntent);
                } catch (RuntimeException exception) {
                    Log.e(TAG, "Fallback clock restore failed", exception);
                }
            }
            pendingResult.finish();
        }, "clock-persistence");
        restoreThread.start();
    }
}
