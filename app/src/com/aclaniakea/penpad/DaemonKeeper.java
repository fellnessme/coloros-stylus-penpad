package com.aclaniakea.penpad;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import moe.shizuku.server.IRemoteProcess;

/**
 * 笔键守护管理：借 Shizuku 的 shell 身份运行 penkeyd 脚本，
 * 并保证它一直活着。
 *
 * 策略：
 *   · 每 30 秒检查一次进程是否还活着，死了立刻重启
 *   · 每 5 分钟无条件重启一次（防 getevent 卡死但进程还在的情况——
 *     今天早上就是这个症状：进程活着但收不到按键）
 */
public final class DaemonKeeper {

    private static final String TAG = "PenPadDaemon";

    private static final String SCRIPT = "/data/local/tmp/penkeyd.sh";
    private static final long CHECK_MS = 30_000L;
    private static final long FORCE_RESTART_MS = 5 * 60_000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static IRemoteProcess proc;
    private static long lastForceRestart = 0L;
    private static boolean running = false;
    private static Context appCtx;

    private DaemonKeeper() { }

    public static synchronized void start(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
        if (running) return;
        running = true;
        H.removeCallbacks(tick);
        H.post(tick);
        Log.i(TAG, "keeper started");
    }

    public static synchronized void stop() {
        running = false;
        H.removeCallbacks(tick);
        killProc();
    }

    public static boolean isDaemonAlive() {
        try {
            return proc != null && proc.alive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                if (appCtx == null) return;
                if (!ShizukuShell.hasPermission(appCtx)) {
                    // 没授权就试着要一次，然后等下一轮
                    ShizukuShell.requestPermission(appCtx, 1);
                    Log.w(TAG, "no shizuku permission yet");
                } else {
                    boolean alive = isDaemonAlive();
                    long now = System.currentTimeMillis();
                    boolean tooLong = lastForceRestart == 0
                            || now - lastForceRestart > FORCE_RESTART_MS;
                    if (!alive || tooLong) {
                        Log.i(TAG, "restart daemon (alive=" + alive + ", tooLong=" + tooLong + ")");
                        spawn();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "tick error: " + t);
            }
            H.postDelayed(this, CHECK_MS);
        }
    };

    private static void spawn() {
        killProc();
        try {
            // 先确保脚本在（首次安装时可能没有）
            ShizukuShell.exec(appCtx, "sh", "-c",
                    "cp -f /data/local/tmp/penkeyd.sh /data/local/tmp/penkeyd.sh 2>/dev/null; " +
                    "chmod 755 " + SCRIPT);
            proc = ShizukuShell.exec(appCtx, "sh", SCRIPT);
            lastForceRestart = System.currentTimeMillis();
            Log.i(TAG, "daemon spawned: " + proc);
        } catch (Throwable t) {
            Log.w(TAG, "spawn failed: " + t);
        }
    }

    private static void killProc() {
        if (proc != null) {
            try { proc.destroy(); } catch (Throwable ignored) { }
            proc = null;
        }
    }
}
