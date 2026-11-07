package com.aclaniakea.penpad;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * 无障碍常驻：借助 WRITE_SECURE_SETTINGS（用 adb 授权一次）自我维持开启状态。
 * ColorOS 重启后会把 enabled_accessibility_services 清掉，这里在开机 / 服务启动时写回。
 */
public final class AccessibilityHelper {

    private static final String TAG = "PenPadKey";

    private AccessibilityHelper() { }

    private static String component(Context c) {
        return new ComponentName(c, PenKeyService.class).flattenToString();
    }

    public static boolean canWrite(Context c) {
        return c.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** 把本应用的无障碍服务写进系统设置并打开总开关。返回是否成功。 */
    public static boolean ensureEnabled(Context c) {
        try {
            String self = component(c);
            String cur = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            if (!cur.contains(self)) {
                String next = cur.isEmpty() ? self : cur + ":" + self;
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
                Log.i(TAG, "accessibility list updated -> " + next);
            }
            if (Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(c.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i(TAG, "accessibility_enabled -> 1");
            }
            return true;
        } catch (SecurityException se) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS, skip self-heal", se);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "ensureEnabled fail", e);
            return false;
        }
    }

    // ------------------------------------------------------------ 系统笔开关
    /** ColorOS 的全局手写笔开关；重启/清后台会被重置成 0，导致笔完全写不出字。
     *  注意：它在 Settings.Global 命名空间，不是 Secure（旧代码写错了地方）。 */
    private static final String PEN_KEY = "settings_enable_oppo_pencil";

    /** 笔上滑手势开关（截图/全局批注入口），ColorOS 同样会重置成 0 */
    private static final String SWIPE_KEY = "ipe_pencil_swipe_function";

    public static int penState(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), PEN_KEY, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 需要时把系统笔开关写回 1。返回是否真的做了修复。 */
    public static boolean ensurePen(Context c) {
        boolean fixed = false;
        try {
            if (penState(c) != 1) {
                Settings.Global.putInt(c.getContentResolver(), PEN_KEY, 1);
                Log.i(TAG, "settings_enable_oppo_pencil -> 1");
                fixed = true;
            }
            // 笔上滑手势（截图/全局批注入口），同样会被系统重置
            int sw = Settings.Global.getInt(c.getContentResolver(), SWIPE_KEY, -1);
            if (sw != 1) {
                Settings.Global.putInt(c.getContentResolver(), SWIPE_KEY, 1);
                Log.i(TAG, "ipe_pencil_swipe_function -> 1 (was " + sw + ")");
                fixed = true;
            }
        } catch (SecurityException se) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS, cannot fix pen switch", se);
        } catch (Exception e) {
            Log.w(TAG, "ensurePen fail", e);
        }
        return fixed;
    }

    /** 一次性修好「系统笔开关 + 无障碍常驻」。返回笔开关是否被修复。 */
    public static boolean ensureAll(Context c) {
        boolean pen = ensurePen(c);
        ensureEnabled(c);
        return pen;
    }

    public static boolean isEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }
}
