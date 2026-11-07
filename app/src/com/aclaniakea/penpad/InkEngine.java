package com.aclaniakea.penpad;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.util.Log;

import androidx.ink.brush.Brush;
import androidx.ink.brush.BrushFamily;
import androidx.ink.brush.InputToolType;
import androidx.ink.brush.SelfOverlap;
import androidx.ink.brush.StockBrushes;
import androidx.ink.nativeloader.NativeLoader;
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer;
import androidx.ink.strokes.InProgressStroke;
import androidx.ink.strokes.ImmutableStrokeInputBatch;
import androidx.ink.strokes.MutableStrokeInputBatch;

/**
 * Jetpack Ink 封装：负责加载原生库、挑选笔刷、把触摸点喂给 InProgressStroke、渲染。
 *
 * 单位约定：所有坐标与笔宽都用「像素」，调用方负责把 dp 乘 density。
 */
public final class InkEngine {

    private static final String TAG = "PenPad";
    private static final Matrix IDENTITY = new Matrix();

    private static volatile boolean sReady = false;
    private static volatile String sError = null;
    private static volatile CanvasStrokeRenderer sRenderer = null;
    private static ImmutableStrokeInputBatch sEmpty = null;

    private static final float EPSILON = 0.1f;

    private InkEngine() { }

    public static synchronized boolean init() {
        if (sReady) return true;
        try {
            NativeLoader.INSTANCE.load();
            sRenderer = CanvasStrokeRenderer.create(true);
            sEmpty = new MutableStrokeInputBatch().toImmutable();
            sReady = sRenderer != null;
            Log.i(TAG, "ink init ok, renderer=" + sRenderer);
        } catch (Throwable t) {
            sError = t.getClass().getSimpleName() + ": " + t.getMessage();
            sReady = false;
            Log.e(TAG, "ink init FAILED " + sError, t);
        }
        return sReady;
    }

    public static boolean isReady() { return sReady; }

    public static String error() { return sError; }

    public static CanvasStrokeRenderer renderer() { return sRenderer; }

    // ------------------------------------------------------------------ 笔刷
    /**
     * 按工具挑笔刷。
     * @param tool  Stroke.PEN / FOUNTAIN / PENCIL / HIGHLIGHT / SMART
     * @param color ARGB（含 alpha）
     * @param sizePx 笔宽（像素）
     */
    public static Brush brushFor(int tool, int color, float sizePx) {
        BrushFamily fam;
        switch (tool) {
            case Stroke.FOUNTAIN:
                fam = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.LATEST);
                break;
            case Stroke.PENCIL:
                fam = StockBrushes.marker(StockBrushes.MarkerVersion.LATEST);
                break;
            case Stroke.HIGHLIGHT:
                fam = StockBrushes.highlighter(SelfOverlap.ACCUMULATE,
                        StockBrushes.HighlighterVersion.LATEST);
                break;
            case Stroke.SMART:
            case Stroke.PEN:
            default:
                fam = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.LATEST);
                break;
        }
        float size = Math.max(0.6f, sizePx);
        return Brush.createWithColorIntArgb(fam, color, size, EPSILON);
    }

    // ------------------------------------------------------------------ 实时笔画
    public static final class Live {
        final InProgressStroke stroke = new InProgressStroke();
        final MutableStrokeInputBatch batch = new MutableStrokeInputBatch();
        Brush brush;
        long t0;
        int tool;
        boolean ok;

        public Brush brush() { return brush; }
        public boolean isOk() { return ok; }
    }

    /** 起笔。返回 null 表示 Ink 不可用，调用方应回退到自己的渲染。 */
    public static Live begin(int tool, int colorArgb, float sizePx,
                             float x, float y, float pressure, float tiltRad, long timeMs) {
        if (!sReady && !init()) return null;
        try {
            Live l = new Live();
            l.tool = tool;
            l.brush = brushFor(tool, colorArgb, sizePx);
            l.t0 = timeMs;
            l.stroke.start(l.brush);
            l.batch.clear();
            l.batch.add(InputToolType.STYLUS, x, y, 0L,
                    clamp01(pressure), tiltRad, 0f);
            l.stroke.enqueueInputs(l.batch.toImmutable(), sEmpty);
            l.stroke.updateShape(0L);
            l.ok = true;
            return l;
        } catch (Throwable t) {
            Log.e(TAG, "ink begin failed", t);
            sError = t.toString();
            return null;
        }
    }

    /** 加点。dt 由内部按 t0 计算。 */
    public static void add(Live l, float x, float y, float pressure, float tiltRad, long timeMs) {
        if (l == null || !l.ok) return;
        try {
            l.batch.clear();
            l.batch.add(InputToolType.STYLUS, x, y, Math.max(0L, timeMs - l.t0),
                    clamp01(pressure), tiltRad, 0f);
            l.stroke.enqueueInputs(l.batch.toImmutable(), sEmpty);
            l.stroke.updateShape(0L);
        } catch (Throwable t) {
            Log.e(TAG, "ink add failed", t);
            l.ok = false;
        }
    }

    /** 收笔，返回可复用的不可变笔画；失败返回 null。 */
    public static androidx.ink.strokes.Stroke finish(Live l) {
        if (l == null || !l.ok) return null;
        try {
            l.stroke.finishInput();
            return l.stroke.toImmutable();
        } catch (Throwable t) {
            Log.e(TAG, "ink finish failed", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 渲染
    public static void drawLive(Canvas c, Live l, float alpha) {
        if (c == null || l == null || !l.ok || sRenderer == null) return;
        try {
            sRenderer.draw(c, l.stroke, IDENTITY, alpha);
        } catch (Throwable t) {
            Log.e(TAG, "ink drawLive failed", t);
        }
    }

    public static void drawStroke(Canvas c, androidx.ink.strokes.Stroke s, float alpha) {
        if (c == null || s == null || sRenderer == null) return;
        try {
            sRenderer.draw(c, s, IDENTITY, alpha);
        } catch (Throwable t) {
            Log.e(TAG, "ink drawStroke failed", t);
        }
    }

    private static float clamp01(float p) {
        if (p <= 0f) return 0.5f;
        return p > 1f ? 1f : p;
    }
}
