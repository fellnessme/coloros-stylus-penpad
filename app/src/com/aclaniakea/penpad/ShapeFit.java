package com.aclaniakea.penpad;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能笔的形状识别。全部阈值按 dp 换算（乘 density），
 * 否则 420dpi 设备上按 px 算根本点不中。
 */
public final class ShapeFit {

    public static final int NONE = 0;
    public static final int LINE = 1;
    public static final int CIRCLE = 2;
    public static final int RECT = 3;
    public static final int TRIANGLE = 4;
    public static final int ARROW = 5;

    private ShapeFit() {}

    /**
     * 尝试识别，成功则就地改写 stroke（smartShape / geom / pts），返回形状类型。
     * 失败返回 NONE，且不改动 stroke。
     */
    public static int fit(Stroke s, float density) {
        if (s == null || s.count() < 4) return NONE;
        if (s.tool == Stroke.HIGHLIGHT || s.tool == Stroke.LASER) return NONE;
        distUnit = density;

        List<float[]> raw = new ArrayList<>();
        for (int i = 0; i < s.count(); i++) {
            float[] p = s.pts.get(i);
            if (raw.isEmpty()) {
                raw.add(new float[]{p[0], p[1]});
            } else {
                float[] last = raw.get(raw.size() - 1);
                if (dist(last[0], last[1], p[0], p[1]) > 1.5f * density) {
                    raw.add(new float[]{p[0], p[1]});
                }
            }
        }
        if (raw.size() < 3) return NONE;

        float[] first = raw.get(0);
        float[] last = raw.get(raw.size() - 1);
        float gap = dist(first[0], first[1], last[0], last[1]);
        boolean closed = gap < 30f * density && raw.size() > 8;

        List<float[]> simple = simplify(raw, 2.5f * density);

        if (!closed) {
            // 直线
            if (simple.size() == 2) {
                float dev = maxDeviation(raw, simple.get(0), simple.get(1));
                if (dev < 3f * density) {
                    applyLine(s, simple.get(0), simple.get(1));
                    return LINE;
                }
            }
            // 箭头：2 段折线，夹角很小
            if (simple.size() == 3) {
                float[] a = simple.get(0), b = simple.get(1), c = simple.get(2);
                float l1 = dist(a[0], a[1], b[0], b[1]);
                float l2 = dist(b[0], b[1], c[0], c[1]);
                float ang = angleBetween(a, b, c);
                float longer = Math.max(l1, l2);
                float shorter = Math.min(l1, l2);
                if (longer > 20f * density && shorter > longer * 0.15f && ang < 30f) {
                    applyArrow(s, longer == l1 ? a : c, b, longer == l1 ? c : a);
                    return ARROW;
                }
            }
            return NONE;
        }

        // 圆
        List<float[]> ring = new ArrayList<>(raw);
        float[] c = fitCircle(ring);
        if (c != null) {
            float maxDev = 0f, minDev = Float.MAX_VALUE;
            for (int i = 0; i < ring.size(); i++) {
                float[] p = ring.get(i);
                float d = Math.abs(dist(p[0], p[1], c[0], c[1]) - c[2]);
                if (d > maxDev) maxDev = d;
                if (d < minDev) minDev = d;
            }
            if (c[2] > 12f * density && maxDev < c[2] * 0.10f) {
                applyCircle(s, c[0], c[1], c[2]);
                return CIRCLE;
            }
        }

        // 多边形
        List<float[]> closedPts = new ArrayList<>(raw);
        closedPts.add(new float[]{first[0], first[1]});
        List<float[]> poly = simplify(closedPts, 3f * density);
        if (poly.size() >= 2) {
            float[] p0 = poly.get(0);
            float[] pn = poly.get(poly.size() - 1);
            if (dist(p0[0], p0[1], pn[0], pn[1]) < 12f * density) poly.remove(poly.size() - 1);
        }
        if (poly.size() == 4) {
            if (!isRoughRect(poly)) return NONE;
            applyPolygon(s, poly);
            return RECT;
        }
        if (poly.size() == 3) {
            applyPolygon(s, poly);
            return TRIANGLE;
        }
        return NONE;
    }

    // ------------------------------------------------------------------ 落地
    private static void applyLine(Stroke s, float[] a, float[] b) {
        s.smartShape = LINE;
        s.geom = new float[]{a[0], a[1], b[0], b[1]};
        s.closed = false;
        rebuild(s, new float[][]{{a[0], a[1]}, {b[0], b[1]}});
    }

    private static void applyArrow(Stroke s, float[] tail, float[] tip, float[] headPoint) {
        // tip 是折点（笔尖），tail 是起点，headPoint 只用来判断尾翼方向
        s.smartShape = ARROW;
        s.geom = new float[]{tail[0], tail[1], tip[0], tip[1]};
        float len = dist(tail[0], tail[1], tip[0], tip[1]);
        float head = Math.min(len * 0.28f, 60f * distUnit);
        double a = Math.atan2(tip[1] - tail[1], tip[0] - tail[0]);
        float[] l = {tip[0] - head * (float) Math.cos(a - 0.5), tip[1] - head * (float) Math.sin(a - 0.5)};
        float[] r = {tip[0] - head * (float) Math.cos(a + 0.5), tip[1] - head * (float) Math.sin(a + 0.5)};
        s.closed = false;
        rebuild(s, new float[][]{
                {tail[0], tail[1]}, {tip[0], tip[1]},
                {l[0], l[1]}, {tip[0], tip[1]}, {r[0], r[1]}
        });
    }

    private static void applyCircle(Stroke s, float cx, float cy, float r) {
        s.smartShape = CIRCLE;
        s.geom = new float[]{cx, cy, r};
        s.closed = true;
        int n = 64;
        float[][] pts = new float[n + 1][];
        for (int i = 0; i <= n; i++) {
            double a = i * 2 * Math.PI / n;
            pts[i] = new float[]{(float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a))};
        }
        rebuild(s, pts);
    }

    private static void applyPolygon(Stroke s, List<float[]> corners) {
        s.smartShape = corners.size() == 4 ? RECT : TRIANGLE;
        s.geom = new float[corners.size() * 2];
        for (int i = 0; i < corners.size(); i++) {
            s.geom[i * 2] = corners.get(i)[0];
            s.geom[i * 2 + 1] = corners.get(i)[1];
        }
        s.closed = true;
        float[][] pts = new float[corners.size() + 1][];
        for (int i = 0; i < corners.size(); i++) {
            pts[i] = new float[]{corners.get(i)[0], corners.get(i)[1]};
        }
        pts[corners.size()] = new float[]{corners.get(0)[0], corners.get(0)[1]};
        rebuild(s, pts);
    }

    private static void rebuild(Stroke s, float[][] pts) {
        long t = s.pts.isEmpty() ? System.currentTimeMillis() : s.time(0);
        long t2 = s.pts.isEmpty() ? t : s.time(s.count() - 1);
        s.pts.clear();
        for (float[] p : pts) s.pts.add(Stroke.point(p[0], p[1], 0.85f, 0f, 0f, 0f, t));
        s.tStart = t;
        s.tEnd = t2;
        s.invalidateBounds();
    }

    private static float distUnit = 1f;

    // ------------------------------------------------------------------ 几何工具
    static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static float angleBetween(float[] a, float[] b, float[] c) {
        // 顶点在 b，返回 a-b-c 的夹角（度）
        double a1 = Math.atan2(a[1] - b[1], a[0] - b[0]);
        double a2 = Math.atan2(c[1] - b[1], c[0] - b[0]);
        double d = Math.abs(a1 - a2);
        if (d > Math.PI) d = 2 * Math.PI - d;
        return (float) Math.toDegrees(d);
    }

    static float maxDeviation(List<float[]> pts, float[] a, float[] b) {
        float max = 0f;
        for (int i = 0; i < pts.size(); i++) {
            float d = pointSegmentDistance(pts.get(i)[0], pts.get(i)[1], a[0], a[1], b[0], b[1]);
            if (d > max) max = d;
        }
        return max;
    }

    static float pointSegmentDistance(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        float len2 = dx * dx + dy * dy;
        if (len2 <= 0.0001f) return dist(px, py, x1, y1);
        float t = ((px - x1) * dx + (py - y1) * dy) / len2;
        t = Math.max(0f, Math.min(1f, t));
        return dist(px, py, x1 + t * dx, y1 + t * dy);
    }

    /** Ramer-Douglas-Peucker */
    static List<float[]> simplify(List<float[]> pts, float eps) {
        if (pts.size() < 3) return new ArrayList<>(pts);
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        rdp(pts, 0, pts.size() - 1, eps, keep);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    private static void rdp(List<float[]> pts, int i0, int i1, float eps, boolean[] keep) {
        if (i1 <= i0 + 1) return;
        float max = -1f;
        int idx = -1;
        float[] a = pts.get(i0), b = pts.get(i1);
        for (int i = i0 + 1; i < i1; i++) {
            float[] p = pts.get(i);
            float d = pointSegmentDistance(p[0], p[1], a[0], a[1], b[0], b[1]);
            if (d > max) { max = d; idx = i; }
        }
        if (max > eps && idx > 0) {
            keep[idx] = true;
            rdp(pts, i0, idx, eps, keep);
            rdp(pts, idx, i1, eps, keep);
        }
    }

    /** 最小二乘（Kasa）拟合圆：返回 {cx, cy, r}，失败 null */
    static float[] fitCircle(List<float[]> pts) {
        int n = pts.size();
        if (n < 6) return null;
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0, sxz = 0, syz = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            double x = pts.get(i)[0], y = pts.get(i)[1];
            double z = x * x + y * y;
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y;
            sxz += x * z; syz += y * z; sz += z;
        }
        double a11 = 2 * (sxx - sx * sx / n);
        double a12 = 2 * (sxy - sx * sy / n);
        double a22 = 2 * (syy - sy * sy / n);
        double b1 = sxz - sx * sz / n;
        double b2 = syz - sy * sz / n;
        double det = a11 * a22 - a12 * a12;
        if (Math.abs(det) < 1e-6) return null;
        double cx = (b1 * a22 - b2 * a12) / det;
        double cy = (a11 * b2 - a12 * b1) / det;
        if (Double.isNaN(cx) || Double.isNaN(cy) || Double.isInfinite(cx) || Double.isInfinite(cy)) return null;
        double r = 0;
        for (int i = 0; i < n; i++) r += dist((float) cx, (float) cy, pts.get(i)[0], pts.get(i)[1]);
        r /= n;
        if (r <= 0) return null;
        return new float[]{(float) cx, (float) cy, (float) r};
    }

    static void setDensity(float d) { distUnit = d; }

    /** 四个角都接近直角才算矩形（90°±25°） */
    private static boolean isRoughRect(List<float[]> poly) {
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            float[] prev = poly.get((i - 1 + n) % n);
            float[] cur = poly.get(i);
            float[] next = poly.get((i + 1) % n);
            float ang = angleBetween(prev, cur, next);
            if (Math.abs(ang - 90f) > 25f) return false;
        }
        return true;
    }
}
