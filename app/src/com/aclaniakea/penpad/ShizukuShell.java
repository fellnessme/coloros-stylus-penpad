package com.aclaniakea.penpad;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;

/**
 * 通过 Shizuku 以 shell（uid 2000）身份启动进程。
 *
 * Shizuku 家族有很多分支，包名各不相同，但 provider 协议和 Binder 键名基本一致：
 *   provider authority = &lt;manager 包名&gt;.shizuku
 *   Bundle 里的键     = moe.shizuku.privileged.api.intent.extra.BINDER
 *
 * 所以这里不写死包名，而是逐个试候选 authority。
 * 如果某个分支连键名也改了，把它的键名加到 BINDER_KEYS 里即可。
 */
public final class ShizukuShell {

    private static final String TAG = "PenPadShizuku";

    /** 候选 manager 包（官方 / ShizukuX / 其他常见分支） */
    private static final String[] AUTH_CANDIDATES = {
            "moe.shizuku.privileged.api.shizuku",   // 官方 Shizuku
            "xyz.shizuku.extra.api.shizuku",        // ShizukuX
            "af.shizuku.privileged.api.shizuku",    // 早期分支
    };

    /** 候选 Binder 键名 */
    private static final String[] BINDER_KEYS = {
            "moe.shizuku.privileged.api.intent.extra.BINDER",
            "rikka.shizuku.intent.extra.BINDER",
            "xyz.shizuku.extra.api.intent.extra.BINDER",
            "af.shizuku.privileged.api.intent.extra.BINDER",
    };

    private static IShizukuService cached;

    private ShizukuShell() { }

    /** 取到 IShizukuService；Shizuku 没在跑时返回 null。 */
    public static synchronized IShizukuService service(Context c) {
        if (cached != null) {
            try {
                if (cached.asBinder().pingBinder()) return cached;
            } catch (Throwable ignored) { }
            cached = null;
        }
        for (String auth : AUTH_CANDIDATES) {
            try {
                Bundle b = c.getContentResolver().call(
                        Uri.parse("content://" + auth), "getBinder", null, null);
                if (b == null) continue;
                for (String key : BINDER_KEYS) {
                    IBinder binder = b.getBinder(key);
                    if (binder != null && binder.pingBinder()) {
                        cached = IShizukuService.Stub.asInterface(binder);
                        Log.i(TAG, "bound via " + auth + " key=" + key);
                        return cached;
                    }
                }
            } catch (Throwable t) {
                Log.d(TAG, "try " + auth + " failed: " + t);
            }
        }
        Log.w(TAG, "no shizuku provider found (is Shizuku running?)");
        return null;
    }

    public static boolean available(Context c) {
        return service(c) != null;
    }

    /** 本应用是否已在 Shizuku 里被授权。 */
    public static boolean hasPermission(Context c) {
        try {
            IShizukuService s = service(c);
            return s != null && s.checkSelfPermission();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹授权请求（用户要在 Shizuku 里点允许）。 */
    public static void requestPermission(Context c, int code) {
        try {
            IShizukuService s = service(c);
            if (s != null) s.requestPermission(code);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed: " + t);
        }
    }

    /** 以 shell 身份起一个进程；失败返回 null。 */
    public static IRemoteProcess exec(Context c, String... argv) {
        try {
            IShizukuService s = service(c);
            if (s == null) return null;
            if (!s.checkSelfPermission()) {
                Log.w(TAG, "no shizuku permission");
                return null;
            }
            return s.newProcess(argv, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "exec failed: " + t);
            return null;
        }
    }

    public static int serviceUid(Context c) {
        try {
            IShizukuService s = service(c);
            return s == null ? -1 : s.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }
}
