package com.aclaniakea.penpad;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * 笔画对象。
 *
 * pts 里每个点是 float[7]：
 *   [0] x      画布像素
 *   [1] y
 *   [2] pressure 0..1
 *   [3] size     触点面积（防误触判定用）
 *   [4] tiltX
 *   [5] tiltY
 *   [6] time     事件时间（毫秒），钢笔速度、时间回溯都用它
 */
public class Stroke {

    // ---- 笔型 ----
    public static final int PEN       = 0;   // 圆珠笔
    public static final int FOUNTAIN  = 1;   // 钢笔
    public static final int PENCIL    = 2;   // 铅笔
    public static final int HIGHLIGHT = 3;   // 荧光笔
    public static final int SMART     = 4;   // 智能笔（识别失败时按 PEN 渲染）
    public static final int LASER     = 5;   // 激光笔（不进笔画列表）
    public static final int RULER     = 6;   // 尺子吸附后的笔画（渲染同 PEN）
    // ---- 非笔画工具 ----
    public static final int TAPE      = 7;   // 胶带橡皮（整笔擦）
    public static final int ERASER    = 8;   // 像素橡皮
    public static final int LASSO     = 9;   // 套索

    public long id;
    public long tStart, tEnd;
    /** 演示笔淡出起点（uptimeMillis）；0 表示不是演示笔或不淡出 */
    public long expireAt;
    public int tool = PEN;
    public int color = 0xFF111111;
    public float baseWidth = 3f;       // dp
    public float maxWidth = 9f;        // dp
    public boolean closed;

    /** 智能笔识别结果，见 ShapeFit */
    public int smartShape = ShapeFit.NONE;
    /** 智能笔几何数据：直线{x1,y1,x2,y2} 圆{cx,cy,r} 多边形{每个角点 x,y} */
    public float[] geom;

    public final List<float[]> pts = new ArrayList<>();

    /** Jetpack Ink 的不可变笔画：非 null 时优先用它渲染（笔迹质量远高于自绘） */
    public transient androidx.ink.strokes.Stroke inkStroke;

    private RectF mBounds;

    public static float[] point(float x, float y, float pressure, float size, float tx, float ty, long time) {
        return new float[]{x, y, pressure, size, tx, ty, time};
    }

    public int count() { return pts.size(); }

    public void add(float x, float y, float pressure, float size, float tx, float ty, long time) {
        pts.add(point(x, y, pressure, size, tx, ty, time));
        mBounds = null;
        pReal = -1;
    }

    public void addPoint(float[] p) {
        pts.add(p);
        mBounds = null;
        pReal = -1;
    }

    private int pReal = -1;

    /**
     * 判据：这段笔画里有真实变化的压感吗？
     * 部分机型（尤其是第三方笔）只上报恒定 1.0，此时渲染改用速度合成笔锋，
     * 否则所有笔型看起来都一样粗。
     */
    public boolean hasRealPressure() {
        if (pReal >= 0) return pReal == 1;
        int n = pts.size();
        if (n < 10) return true;
        float mn = 2f, mx = -1f;
        for (int i = 0; i < n; i++) {
            float p = pts.get(i)[2];
            if (p <= 0f || p > 1f) continue;
            if (p < mn) mn = p;
            if (p > mx) mx = p;
        }
        pReal = (mx >= 0f && (mx - mn) >= 0.06f) ? 1 : 0;
        return pReal == 1;
    }

    public float x(int i) { return pts.get(i)[0]; }
    public float y(int i) { return pts.get(i)[1]; }
    public float pressure(int i) { return pts.get(i)[2]; }
    public long time(int i) { return (long) pts.get(i)[6]; }

    public RectF bounds() {
        if (mBounds != null) return mBounds;
        RectF r = new RectF();
        if (pts.isEmpty()) { mBounds = r; return r; }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            float[] p = pts.get(i);
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
        }
        float pad = Math.max(baseWidth, maxWidth) * 0.6f + 6f;
        r.set(minX - pad, minY - pad, maxX + pad, maxY + pad);
        mBounds = r;
        return r;
    }

    public void invalidateBounds() { mBounds = null; }

    public Stroke copy() {
        Stroke s = new Stroke();
        s.id = id;
        s.tStart = tStart;
        s.tEnd = tEnd;
        s.expireAt = expireAt;
        s.tool = tool;
        s.color = color;
        s.baseWidth = baseWidth;
        s.maxWidth = maxWidth;
        s.closed = closed;
        s.smartShape = smartShape;
        s.geom = geom == null ? null : geom.clone();
        for (int i = 0; i < pts.size(); i++) s.pts.add(pts.get(i).clone());
        return s;
    }

    public void translate(float dx, float dy) {
        for (int i = 0; i < pts.size(); i++) {
            float[] p = pts.get(i);
            p[0] += dx;
            p[1] += dy;
        }
        if (geom != null) {
            for (int i = 0; i + 1 < geom.length; i += 2) {
                geom[i] += dx;
                geom[i + 1] += dy;
            }
        }
        mBounds = null;
    }

    /** 某点应当画多宽（含压感，未乘 density） */
    public float widthAt(int i) {
        float p = pressure(i);
        if (p <= 0f) p = 1f;
        if (p > 1f) p = 1f;
        return baseWidth + (maxWidth - baseWidth) * p;
    }

    public boolean isHighlight() { return tool == HIGHLIGHT; }
}
