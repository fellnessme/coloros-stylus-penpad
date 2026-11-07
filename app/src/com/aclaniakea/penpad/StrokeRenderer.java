package com.aclaniakea.penpad;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * 把 Stroke 画到 Canvas 上。
 *
 * 各笔型的差异是肉眼可辨的：
 *  PEN      圆珠笔：等压感，笔画结实，边缘略软，宽度随压力小幅变化
 *  FOUNTAIN 钢笔：强压感笔锋，起收笔明显收尖，速度越快越细
 *  PENCIL   铅笔：三遍颗粒叠加，半透明，压感影响颗粒浓淡
 *  HIGHLIGHT 荧光笔：等宽高透明，垫在所有笔画下面
 *  SMART    智能笔：识别后的几何线，比圆珠笔更硬
 *  LASER    演示笔：红色笔迹 + 外发光，由 DrawView 控制淡出
 */
public class StrokeRenderer {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    public StrokeRenderer(float density) {
        this.density = density <= 0 ? 2.625f : density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setDither(false);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setMaskFilter(new BlurMaskFilter(6f * this.density, BlurMaskFilter.Blur.NORMAL));
    }

    public void draw(Canvas c, Stroke s) {
        draw(c, s, 1f);
    }

    public void draw(Canvas c, Stroke s, float alphaScale) {
        if (s == null || s.pts.isEmpty()) return;
        // 有 Jetpack Ink 笔画时优先用它（质量远高于自绘）
        if (s.inkStroke != null) {
            InkEngine.drawStroke(c, s.inkStroke, alphaScale);
            return;
        }
        switch (s.tool) {
            case Stroke.HIGHLIGHT:
                drawUniform(c, s, s.baseWidth * density, 84, alphaScale);
                break;
            case Stroke.PENCIL:
                drawPencil(c, s, alphaScale);
                break;
            case Stroke.FOUNTAIN:
                drawInk(c, s, Style.FOUNTAIN, alphaScale);
                break;
            case Stroke.LASER:
                drawLaser(c, s, alphaScale);
                break;
            case Stroke.SMART:
                drawInk(c, s, Style.SMART, alphaScale);
                break;
            default:
                drawInk(c, s, Style.PEN, alphaScale);
                break;
        }
    }

    private enum Style { PEN, FOUNTAIN, SMART }

    // ------------------------------------------------------------ 等宽（荧光笔）
    private void drawUniform(Canvas c, Stroke s, float widthPx, int alpha, float alphaScale) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, widthPx));
        paint.setColor(dropAlphaToAlpha(s.color, (int) (alpha * alphaScale)));
        paint.setXfermode(null);
        if (s.count() == 1) {
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(s.x(0), s.y(0), Math.max(1f, widthPx / 2f), paint);
            return;
        }
        smoothPath(s, 0);
        c.drawPath(path, paint);
    }

    // ------------------------------------------------------------ 变宽墨迹
    private void drawInk(Canvas c, Stroke s, Style style, float alphaScale) {
        int n = s.count();
        int baseAlpha = style == Style.SMART ? 255 : (style == Style.FOUNTAIN ? 240 : 255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setXfermode(null);
        paint.setColor(s.color);
        paint.setAlpha((int) (baseAlpha * alphaScale));

        if (n == 1) {
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(s.x(0), s.y(0), Math.max(0.9f, widthFor(s, 0, style) / 2f), paint);
            return;
        }
        float prevX = s.x(0), prevY = s.y(0);
        for (int i = 1; i < n; i++) {
            float x = s.x(i), y = s.y(i);
            float w = widthFor(s, i, style);
            paint.setStrokeWidth(Math.max(0.7f, w));
            c.drawLine(prevX, prevY, x, y, paint);
            prevX = x;
            prevY = y;
        }
        // 起笔点补一个圆点，避免线帽缺口
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(s.x(0), s.y(0), Math.max(0.6f, widthFor(s, 0, style) / 2f), paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    /** 有效压力：真实压感 + 阈值兜底（设备只报 1.0 时改用速度合成），保证笔锋可见。 */
    private float effectivePressure(Stroke s, int i) {
        float p = s.pressure(i);
        if (p <= 0f) p = 0.5f;
        if (p > 1f) p = 1f;
        if (!s.hasRealPressure()) {
            // 压感恒定的设备：慢写粗、快写细
            p = 0.35f + 0.65f * speedFactor(s, i);
        }
        return p;
    }

    private float widthFor(Stroke s, int i, Style style) {
        float p = effectivePressure(s, i);
        float base = Math.max(0.8f, s.baseWidth) * density;
        float max = Math.max(s.baseWidth, s.maxWidth) * density;
        float w;
        switch (style) {
            case FOUNTAIN: {
                // 钢笔：入笔极细、重按很粗，再叠加速度与起收笔渐变，笔锋要一眼可辨
                w = base * (0.12f + 3.00f * p);
                w *= taper(s, i);
                w *= (0.72f + 0.40f * speedFactor(s, i));
                break;
            }
            case SMART:
                w = base * (1.02f + 0.30f * p);
                break;
            default: {   // PEN
                w = base * (0.60f + 0.95f * p);
                if (w > max * 1.05f) w = max * 1.05f;
                break;
            }
        }
        return Math.max(0.7f, w);
    }

    /** 起笔/收笔渐细，让钢笔有明显的笔锋 */
    private float taper(Stroke s, int i) {
        int n = s.count();
        if (n < 6) return 1f;
        int edge = Math.max(3, n / 12);
        if (i < edge) return 0.42f + 0.58f * (i / (float) edge);
        if (i > n - 1 - edge) return 0.5f + 0.5f * ((n - 1 - i) / (float) edge);
        return 1f;
    }

    /** 速度系数：0.55 .. 1.10（速度越快越细） */
    private float speedFactor(Stroke s, int i) {
        if (i < 1) return 1f;
        float dx = s.x(i) - s.x(i - 1), dy = s.y(i) - s.y(i - 1);
        float len = (float) Math.sqrt(dx * dx + dy * dy) / density;
        long dt = s.time(i) - s.time(i - 1);
        if (dt <= 0) dt = 8;
        if (dt > 120) dt = 120;
        float v = len / dt;                  // dp/ms
        float f = 1f / (1f + 4.5f * v);
        if (f < 0.55f) f = 0.55f;
        if (f > 1.10f) f = 1.10f;
        return f;
    }

    // ------------------------------------------------------------ 演示笔
    private void drawLaser(Canvas c, Stroke s, float alphaScale) {
        int a = (int) (235 * alphaScale);
        if (a <= 0) return;
        int n = s.count();
        int core = 0xFFFF3B30;
        glow.setColor(core);
        glow.setAlpha((int) (a * 0.55f));
        paint.setColor(core);
        paint.setAlpha(a);
        paint.setStyle(Paint.Style.STROKE);
        float w = Math.max(3f, s.baseWidth * density * 0.75f);
        if (n == 1) {
            glow.setStyle(Paint.Style.FILL);
            c.drawCircle(s.x(0), s.y(0), w * 1.6f, glow);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(s.x(0), s.y(0), w * 0.6f, paint);
            paint.setStyle(Paint.Style.STROKE);
            return;
        }
        smoothPath(s, 0);
        glow.setStrokeWidth(w * 2.2f);
        c.drawPath(path, glow);
        paint.setStrokeWidth(w);
        c.drawPath(path, paint);
    }

    // ------------------------------------------------------------ 铅笔颗粒
    private void drawPencil(Canvas c, Stroke s, float alphaScale) {
        int n = s.count();
        paint.setStyle(Paint.Style.STROKE);
        paint.setXfermode(null);
        int base = s.color;
        for (int pass = 0; pass < 3; pass++) {
            float jitterAmp = (pass == 0 ? 0f : 1.8f) * density;
            // 铅笔用固定细线 + 多遍抖动，和钢笔的粗细变化形成明显对比
            float w = Math.max(1f, s.baseWidth * density * (pass == 0 ? 0.62f : 0.40f));
            paint.setStrokeWidth(w);
            paint.setColor(base);
            paint.setAlpha((int) ((pass == 0 ? 165 : 70) * alphaScale));
            if (n == 1) {
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(s.x(0), s.y(0), Math.max(1f, w / 2f), paint);
                paint.setStyle(Paint.Style.STROKE);
                continue;
            }
            path.reset();
            for (int i = 0; i < n; i++) {
                float jx = 0f, jy = 0f;
                if (jitterAmp > 0f) {
                    int h = hash(i, pass);
                    jx = ((h % 11) - 5) / 5f * jitterAmp;
                    jy = (((h / 11) % 11) - 5) / 5f * jitterAmp;
                }
                float x = s.x(i) + jx, y = s.y(i) + jy;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            c.drawPath(path, paint);
        }
        paint.setAlpha(255);
    }

    /** 中点二次贝塞尔平滑，避免大宽度下的折角 */
    private void smoothPath(Stroke s, int from) {
        int n = s.count();
        path.reset();
        path.moveTo(s.x(0), s.y(0));
        if (n == 2) {
            path.lineTo(s.x(1), s.y(1));
            return;
        }
        for (int i = 1; i < n - 1; i++) {
            float mx = (s.x(i) + s.x(i + 1)) / 2f;
            float my = (s.y(i) + s.y(i + 1)) / 2f;
            path.quadTo(s.x(i), s.y(i), mx, my);
        }
        path.lineTo(s.x(n - 1), s.y(n - 1));
    }

    private static int hash(int i, int pass) {
        int h = i * 374761393 + pass * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return Math.abs(h ^ (h >> 16));
    }

    private static int dropAlphaToAlpha(int color, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
