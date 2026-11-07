package com.aclaniakea.penpad;

import android.content.Context;
import android.content.SharedPreferences;

/** 偏好：每种工具独立记忆颜色/笔宽（§9.5）。 */
public final class Prefs {
    private static final String NAME = "penpad2";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean penOnly(Context c) {
        return sp(c).getBoolean("pen_only", false);
    }

    public static void setPenOnly(Context c, boolean v) {
        sp(c).edit().putBoolean("pen_only", v).apply();
    }

    public static boolean palmReject(Context c) {
        return sp(c).getBoolean("palm", true);
    }

    public static void setPalmReject(Context c, boolean v) {
        sp(c).edit().putBoolean("palm", v).apply();
    }

    public static int color(Context c, int tool) {
        int def = defaultColor(tool);
        return sp(c).getInt("c_" + tool, def);
    }

    public static void setColor(Context c, int tool, int color) {
        sp(c).edit().putInt("c_" + tool, color).apply();
    }

    public static float width(Context c, int tool) {
        return sp(c).getFloat("w_" + tool, defaultWidth(tool));
    }

    public static void setWidth(Context c, int tool, float w) {
        sp(c).edit().putFloat("w_" + tool, w).apply();
    }

    public static int lastTool(Context c) {
        return sp(c).getInt("tool", Stroke.PEN);
    }

    public static void setLastTool(Context c, int t) {
        sp(c).edit().putInt("tool", t).apply();
    }

    public static int barX(Context c) {
        return sp(c).getInt("bar_x", -1);
    }

    public static int barY(Context c) {
        return sp(c).getInt("bar_y", -1);
    }

    public static void setBarPos(Context c, int x, int y) {
        sp(c).edit().putInt("bar_x", x).putInt("bar_y", y).apply();
    }

    // 尺子设置
    public static float rulerAlpha(Context c) {
        return sp(c).getFloat("ruler_alpha", 0.30f);
    }

    public static void setRulerAlpha(Context c, float v) {
        sp(c).edit().putFloat("ruler_alpha", v).apply();
    }

    public static float rulerThicknessDp(Context c) {
        return sp(c).getFloat("ruler_thick", 34f);
    }

    public static void setRulerThicknessDp(Context c, float v) {
        sp(c).edit().putFloat("ruler_thick", v).apply();
    }

    /** 尺子长度相对屏幕长边的比例（0.30 .. 1.30） */
    public static float rulerLengthRatio(Context c) {
        return sp(c).getFloat("ruler_len", 1.02f);
    }

    public static void setRulerLengthRatio(Context c, float v) {
        sp(c).edit().putFloat("ruler_len", v).apply();
    }

    /** 系统笔开关被 ColorOS 重置后是否自动写回（需 WRITE_SECURE_SETTINGS 授权） */
    public static boolean autoPenFix(Context c) {
        return sp(c).getBoolean("auto_pen_fix", true);
    }

    public static void setAutoPenFix(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_pen_fix", v).apply();
    }

    public static int defaultColor(int tool) {
        switch (tool) {
            case Stroke.HIGHLIGHT: return 0xFFFFCC00;
            case Stroke.LASER: return 0xFFFF3B30;
            case Stroke.SMART: return 0xFF0A84FF;
            default: return 0xFF111111;
        }
    }

    public static float defaultWidth(int tool) {
        switch (tool) {
            case Stroke.HIGHLIGHT: return 14f;
            case Stroke.LASER: return 8f;
            case Stroke.ERASER: return 14f;
            default: return 3f;
        }
    }
}
