package com.aclaniakea.penpad;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * 屏幕直尺（2.0）。
 *
 * 交互：
 *  - 单指按住尺身拖动：移动整把尺子
 *  - 单指按住两端手柄：旋转 + 拉伸长度
 *  - 双指：整体平移、旋转、捏合缩放长度（由 DrawView 统一处理）
 *  - 手指点空白处：照常书写
 *
 * 透明度 / 尺身宽度可由尺子设置面板调节，长度默认铺满长边，可随意缩放。
 * 吸附范围严格限制在尺身实际覆盖范围内，不会出现“尺子没盖住却也被吸附”的情况。
 */
public class Ruler {

    public boolean visible = false;
    public float cx, cy;
    public float angle = 0f;        // 弧度
    public float length;            // px，尺身总长
    public float thickness;         // px，尺身宽度（垂直于刻度方向）
    public float alpha = 0.30f;     // 0.15 .. 0.95
    public final float density;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int screenW, screenH;

    public Ruler(float density) {
        this.density = density <= 0 ? 2.625f : density;
        thickness = 34f * this.density;
        fill.setStyle(Paint.Style.FILL);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * this.density);
        edge.setColor(0x66303A4A);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(1f * this.density);
        tick.setColor(0xFF37474F);
        text.setStyle(Paint.Style.FILL);
        text.setColor(0xFF37474F);
        text.setTextSize(10f * this.density);
        shadow.setStyle(Paint.Style.FILL);
        shadow.setColor(0x33000000);
        knob.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------ 布局
    public void layout(int w, int h) {
        screenW = w;
        screenH = h;
        if (length <= 0f) length = (float) Math.hypot(w, h) * 1.02f;
        if (cx <= 0f || cy <= 0f) {
            cx = w * 0.5f;
            cy = h * 0.52f;
        }
    }

    public void resetPosition() {
        if (screenW > 0) cx = screenW * 0.5f;
        if (screenH > 0) cy = screenH * 0.52f;
        length = (float) Math.hypot(screenW, screenH) * 1.02f;
        angle = 0f;
    }

    public float minLength() { return Math.max(screenW, screenH) * 0.30f; }

    public float maxLength() { return (float) Math.hypot(Math.max(screenW, 1), Math.max(screenH, 1)) * 1.30f; }

    public void setLength(float len) { length = clamp(len, minLength(), maxLength()); }

    public void setAlpha(float a) { alpha = clamp(a, 0.15f, 0.95f); }

    public void setThicknessDp(float dp) { thickness = clamp(dp, 22f, 120f) * density; }

    public float thicknessDp() { return thickness / density; }

    // ------------------------------------------------------------ 坐标换算
    private float along(float x, float y) {
        return (x - cx) * (float) Math.cos(angle) + (y - cy) * (float) Math.sin(angle);
    }

    private float across(float x, float y) {
        return -(x - cx) * (float) Math.sin(angle) + (y - cy) * (float) Math.cos(angle);
    }

    /** 点是否落在尺身上（含 8dp 余量，方便手指按住） */
    public boolean bodyContains(float x, float y) {
        if (!visible) return false;
        float pad = 8f * density;
        return Math.abs(along(x, y)) <= length / 2f + pad
                && Math.abs(across(x, y)) <= thickness / 2f + pad;
    }

    public float[] centerKnob() { return new float[]{cx, cy}; }

    public float[] endKnobA() {
        return new float[]{cx - (float) Math.cos(angle) * length / 2f,
                cy - (float) Math.sin(angle) * length / 2f};
    }

    public float[] endKnobB() {
        return new float[]{cx + (float) Math.cos(angle) * length / 2f,
                cy + (float) Math.sin(angle) * length / 2f};
    }

    public boolean hitCenterKnob(float x, float y) {
        if (!visible) return false;
        return ShapeFit.dist(x, y, cx, cy) < 26f * density;
    }

    public boolean hitEndKnob(float x, float y) {
        if (!visible) return false;
        float r = 30f * density;
        float[] a = endKnobA(), b = endKnobB();
        return ShapeFit.dist(x, y, a[0], a[1]) < r || ShapeFit.dist(x, y, b[0], b[1]) < r;
    }

    public void moveCenter(float x, float y) { cx = x; cy = y; }

    public void translate(float dx, float dy) { cx += dx; cy += dy; }

    public void rotateTo(float x, float y) {
        angle = (float) Math.atan2(y - cy, x - cx);
    }

    public void rotateBy(float dRad) { angle += dRad; }

    /** 拖动手柄：同时旋转并把长度拉到「手柄到中心」的两倍 */
    public void dragHandle(float x, float y) {
        rotateTo(x, y);
        setLength(ShapeFit.dist(x, y, cx, cy) * 2f);
    }

    /**
     * 把点吸附到尺身边缘。返回 null 表示不在吸附范围内（或压根不在尺身覆盖区内）。
     */
    public float[] snap(float x, float y, float snapDistPx) {
        if (!visible) return null;
        float al = along(x, y);
        float ac = across(x, y);
        // 只在实际尺身覆盖范围内吸附：长度方向 ±length/2，宽度方向贴边
        if (Math.abs(al) > length / 2f) return null;
        if (Math.abs(ac) > thickness / 2f + snapDistPx * 0.35f) return null;
        float sign = ac >= 0 ? 1f : -1f;
        float off = sign * thickness / 2f;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        return new float[]{cx + cos * al - sin * off, cy + sin * al + cos * off};
    }

    // ------------------------------------------------------------ 绘制
    public void draw(Canvas c) {
        if (!visible || length <= 0f) return;
        int a = (int) (alpha * 255f);
        c.save();
        c.translate(cx, cy);
        c.rotate((float) Math.toDegrees(angle));

        float halfL = length / 2f, halfT = thickness / 2f;
        float rad = 6f * density;

        // 投影（极轻，避免“贴纸感”）
        shadow.setAlpha(Math.min(26, a / 6));
        rect.set(-halfL + 1 * density, -halfT + 3 * density, halfL + 1 * density, halfT + 3 * density);
        c.drawRoundRect(rect, rad, rad, shadow);

        // 尺身：接近透明的薄片
        fill.setColor(0xFFF7FAFF);
        fill.setAlpha((int) (a * 0.55f));
        rect.set(-halfL, -halfT, halfL, halfT);
        c.drawRoundRect(rect, rad, rad, fill);
        edge.setColor(0xFF5B6B7B);
        edge.setAlpha(Math.min(150, a + 55));
        c.drawRoundRect(rect, rad, rad, edge);

        // 上沿基准线 + 单侧刻度（参考图样式：只在一边刻，干净）
        tick.setColor(0xFF455A64);
        tick.setAlpha(Math.min(200, a + 80));
        tick.setStrokeWidth(1f * density);
        c.drawLine(-halfL, -halfT, halfL, -halfT, tick);

        float step = 4f * density;
        int majorEvery = 5;
        text.setAlpha(Math.min(200, a + 85));
        int idx = 0;
        for (float x = -halfL; x <= halfL + 0.01f; x += step, idx++) {
            boolean major = idx % majorEvery == 0;
            float tickLen = (major ? 11f : 5f) * density;
            tick.setStrokeWidth((major ? 1.15f : 0.8f) * density);
            c.drawLine(x, -halfT, x, -halfT + tickLen, tick);
        }
        // 数字：每隔 1cm 标一次，紧贴上沿刻度下方
        float numStep = step * majorEvery * 2f;
        int n = 0;
        for (float x = -halfL; x <= halfL - numStep * 0.4f; x += numStep, n++) {
            String s = String.valueOf(n * 5);
            c.drawText(s, x + 2f * density, -halfT + 22f * density, text);
        }
        c.restore();

        // 角度指示：浮在尺身侧边（不压刻度、不被工具条遮住），旋转时实时变化
        float deg = (float) Math.toDegrees(angle) % 180f;
        if (deg < 0f) deg += 180f;
        String angTxt = Math.round(deg) + "°";
        text.setTextSize(13f * density);
        text.setColor(0xFF1F2933);
        text.setAlpha(245);
        float tw = text.measureText(angTxt);
        float bh = 26f * density, bw = tw + 24f * density;
        float off = halfT + 22f * density;
        float bcx = cx - (float) Math.sin(angle) * off;
        float bcy = cy + (float) Math.cos(angle) * off;
        if (bcy - bh / 2f < 4f * density) {
            bcx = cx + (float) Math.sin(angle) * off;
            bcy = cy - (float) Math.cos(angle) * off;
        }
        shadow.setColor(0x33000000);
        shadow.setAlpha(150);
        RectF badge = new RectF(bcx - bw / 2f, bcy - bh / 2f, bcx + bw / 2f, bcy + bh / 2f);
        c.drawRoundRect(badge, bh / 2f, bh / 2f, shadow);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(angTxt, bcx - tw / 2f, bcy - (fm.ascent + fm.descent) / 2f, text);

        // 手柄：中心（移动）+ 两端（旋转/缩放），外观做克制处理，不遮挡刻度
        float[] a1 = endKnobA(), b1 = endKnobB();
        int knobAlpha = Math.max(90, Math.min(180, a));
        knob.setStyle(Paint.Style.FILL);
        knob.setColor(0xFF0A84FF);
        knob.setAlpha(knobAlpha);
        c.drawCircle(cx, cy, 7f * density, knob);
        c.drawCircle(a1[0], a1[1], 8f * density, knob);
        c.drawCircle(b1[0], b1[1], 8f * density, knob);
        knob.setStyle(Paint.Style.STROKE);
        knob.setStrokeWidth(1.4f * density);
        knob.setColor(0xFFFFFFFF);
        knob.setAlpha(210);
        c.drawCircle(cx, cy, 3f * density, knob);
        c.drawCircle(a1[0], a1[1], 3.4f * density, knob);
        c.drawCircle(b1[0], b1[1], 3.4f * density, knob);
        knob.setStyle(Paint.Style.FILL);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
