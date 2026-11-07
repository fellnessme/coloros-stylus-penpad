package com.aclaniakea.penpad;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 无障碍常驻看门狗。
 *
 * ColorOS 会在清后台 / 息屏后把 enabled_accessibility_services 重置，
 * 导致笔键失灵、需要反复手动去设置里打开。这里用 AlarmManager 每 15 分钟
 * 自检一次并把本服务写回（需要一次性 adb 授予 WRITE_SECURE_SETTINGS）。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "PenPadKey";
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    @Override public void onReceive(Context c, Intent intent) {
        Log.i(TAG, "keepalive tick");
        try {
            if (!AccessibilityHelper.isEnabled(c)) {
                boolean ok = AccessibilityHelper.ensureEnabled(c);
                Log.i(TAG, "keepalive re-enable -> " + ok);
            }
        } catch (Throwable t) {
            Log.w(TAG, "keepalive fail", t);
        }
        schedule(c);   // 链条自我续期
    }

    /** 安排下一次自检（幂等，重复调用只会刷新同一 PendingIntent）。 */
    public static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, KeepAliveReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, 1001, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, pi);
        } catch (Throwable t) {
            Log.w(TAG, "schedule fail", t);
        }
    }
}
