package com.henry.standbyclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class PersistenceReceiver extends BroadcastReceiver {
    private static final String TAG = "StandbyPersistence";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        context.startForegroundService(new Intent(context, StandbyService.class));

        PendingResult pendingResult = goAsync();
        Thread restoreThread = new Thread(() -> {
            int exitCode = RootShell.bringClockToFront(context.getPackageName());
            Log.i(TAG, "Persistent clock restore action=" + action + " exit=" + exitCode);
            if (exitCode != 0) {
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
