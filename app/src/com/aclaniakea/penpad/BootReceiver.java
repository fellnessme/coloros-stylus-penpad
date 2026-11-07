package com.aclaniakea.penpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机后把无障碍服务写回系统（ColorOS 重启会清掉），保证笔键一直可用。 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "PenPadKey";

    @Override public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        Log.i(TAG, "boot receiver: " + a);
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            AccessibilityHelper.ensureEnabled(c);
        }
    }
}
