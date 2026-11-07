package com.aclaniakea.penpad;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 笔画对象模型画布。
 *
 * 渲染两层：cache 位图（已提交笔画） + active 笔画（每帧实时画，不进 cache）。
 * 只有撤销/删除/时间回溯才重建 cache；正常书写只做局部 patch。
 */
public class DrawView extends View {

    public interface Listener {
        void onSelectionChanged(boolean has, int count);
        void onHistoryChanged(boolean canUndo, boolean canRedo);
        void onStrokeCountChanged(int count);
    }

    private static final long PEN_HOVER_HOLD_MS = 1500L;
    /** 演示笔：停笔后保持多久才开始淡出 */
    private static final long LASER_HOLD_MS = 3000L;   // 停笔 3 秒后开始淡出
    /** 演示笔淡出时长 */
    private static final long LASER_FADE_MS = 900L;
    private static final int MAX_HISTORY = 40;

    private final float density;
    private final Scene scene = new Scene();
    private final StrokeRenderer renderer;

    private Bitmap cache;
    private Canvas cacheCanvas;
    private final Paint clearPaint = new Paint();

    private final Deque<Op> undoStack = new ArrayDeque<>();
    private final Deque<Op> redoStack = new ArrayDeque<>();

    private int tool = Stroke.PEN;
    private int[] colors = new int[10];
    private float[] widths = new float[10];

    private boolean penOnly = false;
    private boolean palmReject = true;
    private long lastPenTime = 0L;

    private Stroke active;
    /** Jetpack Ink 的实时笔画（null = 用自绘回退） */
    private InkEngine.Live inkLive;
    /** 当前笔的倾斜角（弧度），Ink 用它算笔锋 */
    private float curTilt = 0f;
    private int activePointer = -1;
    private long filterT = -1;

    // 橡皮
    private final float eraserRadius;
    private Ops.RemoveStrokes eraseBatch;
    private Ops.SplitStrokes splitBatch;
    private boolean eraserVisible;
    private float eraserX, eraserY;
    private boolean eraseStarted;
    private float lastEraseX, lastEraseY;

    // 激光
    private final List<Stroke> laserStrokes = new ArrayList<>();
    private final List<float[]> laserPts = new ArrayList<>();
    private long laserLastMs;
    private boolean laserTicking;

    // 套索
    private final List<float[]> lassoPts = new ArrayList<>();
    private final List<Stroke> selection = new ArrayList<>();
    private final RectF selBounds = new RectF();
    private boolean selectionDragging;
    private float dragLastX, dragLastY, dragTotalX, dragTotalY;

    private Ruler ruler;
    private int rulerDragMode;   // 0 无 1 中心移动 2 手柄旋转/缩放 3 尺身拖动

    // 双指操作尺子：平移 / 旋转 / 捏合缩放
    private boolean rulerTwoMode;
    private float twoCx, twoCy, twoAngle, twoDist, twoRulerAngle, twoLen;

    private Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean rebuilding, rebuildPending;

    private final Paint uiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DrawView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        renderer = new StrokeRenderer(density);
        ruler = new Ruler(density);
        eraserRadius = 18f * density;
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        uiPaint.setStyle(Paint.Style.STROKE);
        DashPathEffect dash = new DashPathEffect(new float[]{8f * density, 6f * density}, 0);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(1.5f * density);
        dashPaint.setColor(0xFF0A84FF);
        dashPaint.setPathEffect(dash);
        setFocusable(false);

        for (int i = 0; i < colors.length; i++) {
            colors[i] = 0xFF111111;
            widths[i] = 3f;
        }
        colors[Stroke.HIGHLIGHT] = 0xFFFFCC00;
        widths[Stroke.HIGHLIGHT] = 14f;
        colors[Stroke.LASER] = 0xFFFF3B30;
        widths[Stroke.LASER] = 8f;
        colors[Stroke.SMART] = 0xFF0A84FF;
        widths[Stroke.SMART] = 3f;
    }

    // ---------------------------------------------------------------- 对外接口
    public void setListener(Listener l) { listener = l; }

    public void setPenOnly(boolean v) { penOnly = v; }

    public void setPalmReject(boolean v) { palmReject = v; }

    public void setTool(int t) {
        tool = t;
        if (t != Stroke.LASSO) clearSelection();
        if (t != Stroke.ERASER && t != Stroke.TAPE) eraserVisible = false;
        invalidate();
    }

    public int getTool() { return tool; }

    public void setColor(int color) {
        colors[tool] = color;
        if (!selection.isEmpty()) recolorSelection(color);
        invalidate();
    }

    public int getColor() { return colors[tool]; }

    public void setWidth(float w) {
        widths[tool] = Math.max(1f, Math.min(24f, w));
        invalidate();
    }

    public float currentWidth() { return widths[tool]; }

    public void setPalette(int[] cs, float[] ws) {
        for (int i = 0; i < cs.length && i < colors.length; i++) colors[i] = cs[i];
        for (int i = 0; i < ws.length && i < widths.length; i++) widths[i] = ws[i];
    }

    public boolean isRulerVisible() { return ruler.visible; }

    public void toggleRuler() {
        ruler.visible = !ruler.visible;
        if (ruler.visible) {
            ruler.layout(getWidth(), getHeight());
            ruler.setLength(Math.max(getWidth(), getHeight()) * Prefs.rulerLengthRatio(getContext()));
            if (tool == Stroke.LASER || tool == Stroke.LASSO) setTool(Stroke.PEN);
        }
        invalidate();
    }

    public Ruler ruler() { return ruler; }

    public void setRulerAlpha(float a) {
        ruler.setAlpha(a);
        invalidate();
    }

    public void setRulerThicknessDp(float dp) {
        ruler.setThicknessDp(dp);
        invalidate();
    }

    /** 长度按「屏幕长边 × 比例」设置，保证任意角度都能铺满屏幕 */
    public void setRulerLengthRatio(float ratio) {
        float base = Math.max(getWidth(), getHeight());
        ruler.setLength(base * ratio);
        invalidate();
    }

    public void resetRulerAngle() {
        ruler.angle = 0f;
        invalidate();
    }

    public Scene scene() { return scene; }

    public int strokeCount() { return scene.strokes.size(); }

    public long timeMin() { return scene.firstT; }

    public long timeMax() { return scene.lastT; }

    public boolean canUndo() { return !undoStack.isEmpty(); }

    public boolean canRedo() { return !redoStack.isEmpty(); }

    // ---------------------------------------------------------------- 缓存
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        if (cache != null) cache.recycle();
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        cacheCanvas = new Canvas(cache);
        ruler.layout(w, h);
        rebuildNow();
    }

    private void rebuildNow() {
        if (cacheCanvas == null) return;
        cacheCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        drawAll(cacheCanvas, scene.view(filterT), null);
        invalidate();
    }

    /** 量小同步，量大丢后台（带“重建中”提示） */
    private void rebuildSmart() {
        if (scene.strokes.size() <= 350) {
            rebuildNow();
        } else {
            rebuildAsync();
        }
    }

    private void rebuildAsync() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (rebuilding) { rebuildPending = true; return; }
        rebuilding = true;
        final List<Stroke> snapshot = new ArrayList<>(scene.view(filterT));
        final int w = getWidth(), h = getHeight();
        new Thread(() -> {
            final Bitmap target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(target);
            StrokeRenderer r = new StrokeRenderer(density);
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < snapshot.size(); i++) {
                    Stroke s = snapshot.get(i);
                    boolean hl = s.isHighlight();
                    if ((pass == 0) == hl) r.draw(c, s);
                }
            }
            post(() -> {
                if (cache != null) cache.recycle();
                cache = target;
                cacheCanvas = new Canvas(cache);
                rebuilding = false;
                invalidate();
                if (rebuildPending) { rebuildPending = false; rebuildSmart(); }
            });
        }).start();
        invalidate();
    }

    /** 只重画某块区域（橡皮局部擦除、新笔画提交都用它） */
    private void patchRegion(RectF r) {
        if (cacheCanvas == null || r == null) return;
        cacheCanvas.drawRect(r, clearPaint);
        cacheCanvas.save();
        cacheCanvas.clipRect(r);
        drawAll(cacheCanvas, scene.view(filterT), r);
        cacheCanvas.restore();
        invalidate();
    }

    private void drawAll(Canvas c, List<Stroke> list, RectF only) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < list.size(); i++) {
                Stroke s = list.get(i);
                if ((pass == 0) != s.isHighlight()) continue;
                if (only != null && !RectF.intersects(s.bounds(), only)) continue;
                renderer.draw(c, s);
            }
        }
    }

    // ---------------------------------------------------------------- onDraw
    @Override
    protected void onDraw(Canvas canvas) {
        try {
            renderFrame(canvas);
        } catch (Throwable t) {
            android.util.Log.e("PenPad", "onDraw error", t);
        }
    }

    private void renderFrame(Canvas canvas) {
        if (cache != null) canvas.drawBitmap(cache, 0f, 0f, null);
        if (inkLive != null) {
            InkEngine.drawLive(canvas, inkLive, 1f);
        } else {
            renderer.draw(canvas, active);
        }
        drawLasers(canvas);
        drawSelection(canvas);
        drawLassoPath(canvas);
        ruler.draw(canvas);
        if (eraserVisible) {
            uiPaint.setStyle(Paint.Style.STROKE);
            uiPaint.setStrokeWidth(1.5f * density);
            uiPaint.setColor(0xAA000000);
            canvas.drawCircle(eraserX, eraserY, eraserRadius, uiPaint);
            uiPaint.setColor(0x33FFFFFF);
            uiPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(eraserX, eraserY, eraserRadius, uiPaint);
        }
        if (rebuilding) {
            uiPaint.setStyle(Paint.Style.FILL);
            uiPaint.setColor(0x66000000);
            float r = 8f * density;
            canvas.drawCircle(getWidth() / 2f, 40f * density, r, uiPaint);
            uiPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(getWidth() / 2f, 40f * density, r * 0.45f, uiPaint);
        }
    }

    private void drawSelection(Canvas canvas) {
        if (selection.isEmpty()) return;
        uiPaint.setStyle(Paint.Style.STROKE);
        uiPaint.setStrokeWidth(1.2f * density);
        uiPaint.setColor(0xFF0A84FF);
        canvas.drawRect(selBounds, dashPaint);
    }

    private void drawLassoPath(Canvas canvas) {
        if (lassoPts.size() < 2) return;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(lassoPts.get(0)[0], lassoPts.get(0)[1]);
        for (int i = 1; i < lassoPts.size(); i++) p.lineTo(lassoPts.get(i)[0], lassoPts.get(i)[1]);
        canvas.drawPath(p, dashPaint);
    }

    // ---------------------------------------------------------------- 演示笔
    private void drawLasers(Canvas canvas) {
        if (laserStrokes.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        // 演示笔还在书写中：所有已留下的演示笔迹持续存活，书写期间不消失
        boolean writing = active != null && active.tool == Stroke.LASER;
        if (writing) {
            long keep = now + LASER_HOLD_MS;
            for (int i = 0; i < laserStrokes.size(); i++) {
                Stroke o = laserStrokes.get(i);
                if (o != active && o.expireAt < keep) o.expireAt = keep;
            }
        }
        for (int i = 0; i < laserStrokes.size(); i++) {
            Stroke s = laserStrokes.get(i);
            float a = 1f;
            if (!writing && s.expireAt > 0 && now > s.expireAt) {
                a = 1f - (now - s.expireAt) / (float) LASER_FADE_MS;
            }
            if (a <= 0f) continue;
            renderer.draw(canvas, s, a);
        }
    }

    private void ensureLaserTicker() {
        if (laserTicking) return;
        laserTicking = true;
        handler.postDelayed(laserTick, 120);
    }

    private final Runnable laserTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            for (int i = laserStrokes.size() - 1; i >= 0; i--) {
                Stroke s = laserStrokes.get(i);
                if (s.expireAt > 0 && now > s.expireAt + LASER_FADE_MS) laserStrokes.remove(i);
            }
            if (laserStrokes.isEmpty()) {
                laserTicking = false;
                invalidate();
                return;
            }
            invalidate();
            handler.postDelayed(this, 120);
        }
    };

    // ---------------------------------------------------------------- 输入
    @Override
    public boolean onHoverEvent(MotionEvent e) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            lastPenTime = SystemClock.uptimeMillis();
        }
        return super.onHoverEvent(e);
    }

    private boolean penRecentlyPresent() {
        return (SystemClock.uptimeMillis() - lastPenTime) < PEN_HOVER_HOLD_MS;
    }

    private boolean shouldAccept(MotionEvent e, int idx) {
        int t = e.getToolType(idx);
        if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
            lastPenTime = SystemClock.uptimeMillis();
            return true;
        }
        // 笔被系统切成「鼠标/指针模式」时也要能写
        if (t == MotionEvent.TOOL_TYPE_MOUSE) {
            lastPenTime = SystemClock.uptimeMillis();
            return true;
        }
        if (penOnly) return false;
        if (penRecentlyPresent()) return false;
        if (palmReject && isPalm(e, idx)) return false;
        return true;
    }

    /** 手掌判定：优先用接触面直径（mm），退化到 size 归一值。 */
    private boolean isPalm(MotionEvent e, int idx) {
        float majorPx = e.getTouchMajor(idx);
        if (majorPx > 0f) {
            float mm = majorPx / getResources().getDisplayMetrics().densityDpi * 25.4f;
            return mm >= 9f;
        }
        return false;   // 部分设备 size 恒为 1.0，不做兜底判定，避免误杀手指
    }

    /** 最近一次触摸（含笔/手指）时间，供笔键防误触判断用。 */
    public static volatile long lastTouchAt = 0L;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        lastTouchAt = SystemClock.uptimeMillis();
        try {
            return dispatchTouch(e);
        } catch (Throwable t) {
            android.util.Log.e("PenPad", "touch pipeline error", t);
            try { cancelGesture(); } catch (Throwable ignored) { }
            activePointer = -1;
            invalidate();
            return true;
        }
    }

    private boolean dispatchTouch(MotionEvent e) {
        final int action = e.getActionMasked();

        // 尺子双指：平移 / 旋转 / 捏合缩放
        if (ruler.visible) {
            if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() >= 2) {
                if (active != null) finishStroke();
                startRulerTwoFinger(e);
                invalidate();
                return true;
            }
            if (rulerTwoMode) {
                if (action == MotionEvent.ACTION_MOVE) {
                    updateRulerTwoFinger(e);
                    invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_POINTER_UP) {
                    if (e.getPointerCount() <= 2) {
                        rulerTwoMode = false;
                        rulerDragMode = 0;
                    } else {
                        startRulerTwoFinger(e);
                    }
                    invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    rulerTwoMode = false;
                    rulerDragMode = 0;
                    activePointer = -1;
                    invalidate();
                    return true;
                }
            }
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                Integer ok = shouldAccept(e, 0) ? 1 : null;
                if (ok == null) { activePointer = -1; return true; }
                activePointer = e.getPointerId(0);
                handleDown(e, 0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activePointer < 0) return true;
                int idx = e.findPointerIndex(activePointer);
                if (idx < 0) return true;
                handleMove(e, idx);
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (activePointer < 0) return true;
                int idx = e.findPointerIndex(activePointer);
                handleUp(e, Math.max(0, idx));
                activePointer = -1;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelGesture();
                activePointer = -1;
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    private long evTime(MotionEvent e, int idx, int hist) {
        return hist >= 0 ? e.getHistoricalEventTime(hist) : e.getEventTime();
    }

    // ---------------------------------------------------------------- 尺子双指手势
    private void startRulerTwoFinger(MotionEvent e) {
        float x0 = e.getX(0), y0 = e.getY(0);
        float x1 = e.getX(1), y1 = e.getY(1);
        twoCx = (x0 + x1) / 2f;
        twoCy = (y0 + y1) / 2f;
        twoAngle = (float) Math.atan2(y1 - y0, x1 - x0);
        twoDist = Math.max(1f, ShapeFit.dist(x0, y0, x1, y1));
        twoRulerAngle = ruler.angle;
        twoLen = ruler.length;
        rulerTwoMode = true;
        rulerDragMode = 0;
        eraserVisible = false;
    }

    private void updateRulerTwoFinger(MotionEvent e) {
        float x0 = e.getX(0), y0 = e.getY(0);
        float x1 = e.getX(1), y1 = e.getY(1);
        ruler.cx = (x0 + x1) / 2f;
        ruler.cy = (y0 + y1) / 2f;
        float ang = (float) Math.atan2(y1 - y0, x1 - x0);
        float dist = Math.max(1f, ShapeFit.dist(x0, y0, x1, y1));
        ruler.angle = twoRulerAngle + normalizeAngle(ang - twoAngle);
        float ratio = dist / twoDist;
        if (ratio < 0.15f) ratio = 0.15f;
        if (ratio > 6f) ratio = 6f;
        ruler.setLength(twoLen * ratio);
    }

    private static float normalizeAngle(float a) {
        while (a > Math.PI) a -= (float) (Math.PI * 2);
        while (a < -Math.PI) a += (float) (Math.PI * 2);
        return a;
    }

    private void handleDown(MotionEvent e, int idx) {
        float x = e.getX(idx), y = e.getY(idx);
        long t = e.getEventTime();
        android.util.Log.i("PenPad", "down tool=" + e.getToolType(idx) + " pressure=" + e.getPressure(idx) + " axisP=" + e.getAxisValue(MotionEvent.AXIS_PRESSURE, idx) + " tool=" + tool);
        eraseStarted = false;
        // 尺子：手柄优先；手指按在尺身上可拖动（笔不触发，避免挡住书写）
        if (ruler.visible) {
            if (ruler.hitCenterKnob(x, y)) { rulerDragMode = 1; return; }
            if (ruler.hitEndKnob(x, y)) { rulerDragMode = 2; return; }
            int tt = e.getToolType(idx);
            if (tt != MotionEvent.TOOL_TYPE_STYLUS && tt != MotionEvent.TOOL_TYPE_ERASER
                    && ruler.bodyContains(x, y)) {
                rulerDragMode = 3;
                dragLastX = x;
                dragLastY = y;
                return;
            }
        }
        switch (tool) {
            case Stroke.TAPE:
                eraseBatch = new Ops.RemoveStrokes();
                eraserVisible = true;
                eraserX = x; eraserY = y;
                lastEraseX = x; lastEraseY = y;
                eraseStarted = true;
                eraseDirty.setEmpty();
                eraseTapeAt(x, y);
                if (!eraseDirty.isEmpty()) patchRegion(new RectF(eraseDirty));
                break;
            case Stroke.ERASER:
                splitBatch = new Ops.SplitStrokes();
                eraserVisible = true;
                eraserX = x; eraserY = y;
                lastEraseX = x; lastEraseY = y;
                eraseStarted = true;
                erasePixelsAt(x, y);
                break;
            case Stroke.LASSO:
                lassoDown(x, y);
                break;
            default:
                // 笔尾橡皮：无论当前选的是哪种笔，笔尾一碰就是擦除
                if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER) {
                    splitBatch = new Ops.SplitStrokes();
                    eraserVisible = true;
                    eraserX = x; eraserY = y;
                    lastEraseX = x; lastEraseY = y;
                    eraseStarted = true;
                    eraseFromPenTail = true;
                    erasePixelsAt(x, y);
                    break;
                }
                if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_MOUSE
                        && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    splitBatch = new Ops.SplitStrokes();
                    eraserVisible = true;
                    eraserX = x; eraserY = y;
                    lastEraseX = x; lastEraseY = y;
                    eraseStarted = true;
                    eraseFromPenTail = true;
                    erasePixelsAt(x, y);
                    break;
                }
                curTilt = e.getAxisValue(MotionEvent.AXIS_TILT, idx);
                startStroke(x, y, pressOf(e, idx), e.getSize(idx), curTilt, 0f, t);
                break;
        }
    }

    /** 笔尾橡皮擦除中：即使当前工具是笔，也要走橡皮的 move/up 分支 */
    private boolean eraseFromPenTail;

    /** 统一取压感：getPressure 不靠谱时退回 AXIS_PRESSURE */
    private float pressOf(MotionEvent e, int idx) {
        float p = e.getPressure(idx);
        if (p <= 0f) p = e.getAxisValue(MotionEvent.AXIS_PRESSURE, idx);
        if (p <= 0f) p = 0.5f;
        if (p > 1f) p = 1f;
        return p;
    }

    private void handleMove(MotionEvent e, int idx) {
        float x = e.getX(idx), y = e.getY(idx);
        if (rulerDragMode == 1) {
            ruler.moveCenter(x, y);
            return;
        }
        if (rulerDragMode == 2) {
            ruler.dragHandle(x, y);
            return;
        }
        if (rulerDragMode == 3) {
            ruler.translate(x - dragLastX, y - dragLastY);
            dragLastX = x;
            dragLastY = y;
            return;
        }
        if (eraseFromPenTail) {
            eraserX = x; eraserY = y;
            if (eraseStarted) erasePixelsSweep(lastEraseX, lastEraseY, x, y);
            else erasePixelsAt(x, y);
            lastEraseX = x; lastEraseY = y;
            return;
        }
        switch (tool) {
            case Stroke.TAPE:
                eraserX = x; eraserY = y;
                if (eraseStarted) eraseTapeSweep(lastEraseX, lastEraseY, x, y);
                lastEraseX = x; lastEraseY = y;
                break;
            case Stroke.ERASER:
                eraserX = x; eraserY = y;
                if (eraseStarted) erasePixelsSweep(lastEraseX, lastEraseY, x, y);
                else erasePixelsAt(x, y);
                lastEraseX = x; lastEraseY = y;
                break;
            case Stroke.LASSO:
                lassoMove(x, y);
                break;
            default: {
                if (active == null) break;
                curTilt = e.getAxisValue(MotionEvent.AXIS_TILT, idx);
                // 历史采样数用 getHistorySize()（无参）；getHistoricalSize(idx) 要的是
                // 「历史索引」，传指针索引会抛 IllegalArgumentException: historyPos out of range
                int hist = e.getHistorySize();
                for (int h = 0; h < hist; h++) {
                    try {
                        addStrokePoint(active, e.getHistoricalX(idx, h), e.getHistoricalY(idx, h),
                                clampP(e.getHistoricalPressure(idx, h)), e.getHistoricalSize(idx, h));
                    } catch (IllegalArgumentException ignored) {
                        // 个别事件历史批次不一致，跳过这一帧
                    }
                }
                addStrokePoint(active, x, y, pressOf(e, idx), e.getSize(idx));
                break;
            }
        }
    }

    private void handleUp(MotionEvent e, int idx) {
        if (rulerDragMode != 0) { rulerDragMode = 0; return; }
        eraseStarted = false;
        if (eraseFromPenTail) {
            eraseFromPenTail = false;
            eraserVisible = false;
            if (splitBatch != null && !splitBatch.isEmpty()) push(splitBatch);
            splitBatch = null;
            return;
        }
        switch (tool) {
            case Stroke.TAPE:
                eraserVisible = false;
                if (eraseBatch != null && !eraseBatch.isEmpty()) push(eraseBatch);
                eraseBatch = null;
                break;
            case Stroke.ERASER:
                eraserVisible = false;
                if (splitBatch != null && !splitBatch.isEmpty()) push(splitBatch);
                splitBatch = null;
                break;
            case Stroke.LASSO:
                lassoUp();
                break;
            default:
                finishStroke();
                break;
        }
    }

    private void cancelGesture() {
        active = null;
        eraseBatch = null;
        splitBatch = null;
        lassoPts.clear();
        eraserVisible = false;
        rulerDragMode = 0;
        rulerTwoMode = false;
        eraseStarted = false;
        selectionDragging = false;
    }

    // ---------------------------------------------------------------- 画笔画
    private static float clampP(float p) {
        if (p <= 0f) return 0.5f;
        return p > 1f ? 1f : p;
    }

    private void startStroke(float x, float y, float pressure, float size, float tilt, float tiltY, long t) {
        active = new Stroke();
        active.tool = tool;
        active.color = colors[tool];
        active.baseWidth = widths[tool];
        active.maxWidth = Math.max(widths[tool], widths[tool] * 3f);
        active.tStart = t;
        active.tEnd = t;
        addStrokePoint(active, x, y, pressure, size);
        // 交给 Jetpack Ink 生成高质量笔迹（不可用时自动回退到自绘）
        int inkColor = colors[tool];
        if (tool == Stroke.HIGHLIGHT) inkColor = (inkColor & 0x00FFFFFF) | 0x55000000;
        inkLive = InkEngine.begin(tool, inkColor, Math.max(1f, widths[tool] * density),
                x, y, pressure, tilt, t);
    }

    private void addStrokePoint(Stroke s, float x, float y, float pressure, float size) {
        float sx = x, sy = y;
        if (ruler.visible && tool != Stroke.LASER && tool != Stroke.LASSO
                && tool != Stroke.TAPE && tool != Stroke.ERASER) {
            float[] sp = ruler.snap(x, y, 22f * density);
            if (sp != null) { sx = sp[0]; sy = sp[1]; }
        }
        long now = SystemClock.uptimeMillis();
        s.add(sx, sy, pressure, size, 0f, 0f, now);
        if (inkLive != null) InkEngine.add(inkLive, sx, sy, pressure, curTilt, now);
    }

    private void finishStroke() {
        Stroke s = active;
        active = null;
        if (s == null || s.count() == 0) return;
        if (s.count() == 1) {
            s.tEnd = s.tStart;
        } else {
            s.tEnd = s.time(s.count() - 1);
        }
        if (s.tool == Stroke.LASER) {
            // 演示笔：不进场景、不进历史，保留 LASER_HOLD_MS 后淡出
            inkLive = null;
            s.tEnd = SystemClock.uptimeMillis();
            s.expireAt = s.tEnd + LASER_HOLD_MS;
            // 还在演示续写时，已有演示笔迹一并续命，书写期间整片笔迹都不消失
            for (int i = 0; i < laserStrokes.size(); i++) {
                Stroke old = laserStrokes.get(i);
                if (old.expireAt < s.expireAt) old.expireAt = s.expireAt;
            }
            laserStrokes.add(s);
            ensureLaserTicker();
            invalidate();
            return;
        }
        if (s.tool == Stroke.SMART) {
            int shape = ShapeFit.fit(s, density);
            if (shape != ShapeFit.NONE) {
                s.closed = shape == ShapeFit.CIRCLE || shape == ShapeFit.RECT || shape == ShapeFit.TRIANGLE;
            }
        }
        if (inkLive != null) {
            androidx.ink.strokes.Stroke ink = InkEngine.finish(inkLive);
            if (ink != null) s.inkStroke = ink;
            inkLive = null;
        }
        scene.add(s);
        push(new Ops.AddStroke(s));
        RectF b = new RectF(s.bounds());
        if (ruler.visible) b.union(s.bounds());
        patchRegion(b);
        if (listener != null) listener.onStrokeCountChanged(scene.strokes.size());
    }

    // ---------------------------------------------------------------- 橡皮
    /** 笔画在绘制时的最大半宽（px）：钢笔有 2.3 倍笔锋、铅笔多遍抖动，必须按最宽处判定，否则边缘残留。 */
    private float halfWidthOf(Stroke s) {
        float base = Math.max(0.8f, s.baseWidth) * density;
        float f;
        switch (s.tool) {
            case Stroke.HIGHLIGHT: f = 0.5f; break;
            case Stroke.FOUNTAIN: f = 1.18f; break;
            case Stroke.LASER: f = 0.55f; break;
            case Stroke.PENCIL: f = 0.72f; break;
            default: f = 0.72f; break;
        }
        return base * f + 2.2f * density;
    }

    private void eraseTapeAt(float x, float y) {
        float tol = 20f * density;
        List<Stroke> hit = new ArrayList<>();
        for (int i = 0; i < scene.strokes.size(); i++) {
            Stroke s = scene.strokes.get(i);
            if (hitStroke(s, x, y, tol + halfWidthOf(s))) hit.add(s);
        }
        if (hit.isEmpty()) return;
        for (int i = 0; i < hit.size(); i++) {
            Stroke s = hit.get(i);
            eraseDirty.union(s.bounds());
            eraseBatch.capture(scene, s);
        }
    }

    private boolean hitStroke(Stroke s, float x, float y, float tol) {
        if (s.pts.isEmpty()) return false;
        RectF b = s.bounds();
        if (!(x >= b.left - tol && x <= b.right + tol && y >= b.top - tol && y <= b.bottom + tol)) return false;
        if (s.count() == 1) return ShapeFit.dist(s.x(0), s.y(0), x, y) < tol;
        for (int i = 1; i < s.count(); i++) {
            if (ShapeFit.pointSegmentDistance(x, y, s.x(i - 1), s.y(i - 1), s.x(i), s.y(i)) < tol) return true;
        }
        return false;
    }

    private void eraseTapeSweep(float x0, float y0, float x1, float y1) {
        eraseDirty.setEmpty();
        float tol = 20f * density;
        float dist = ShapeFit.dist(x0, y0, x1, y1);
        int n = Math.max(1, (int) Math.ceil(dist / Math.max(2f, tol * 0.7f)));
        for (int i = 1; i <= n; i++) {
            float t = i / (float) n;
            eraseTapeAt(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t);
        }
        if (!eraseDirty.isEmpty()) patchRegion(new RectF(eraseDirty));
    }

    private final RectF eraseDirty = new RectF();

    private void erasePixelsAt(float x, float y) {
        eraseDirty.setEmpty();
        eraseCircleCore(x, y);
        if (!eraseDirty.isEmpty()) patchRegion(new RectF(eraseDirty));
    }

    /** 沿手指移动轨迹连续擦除，避免快速滑动时漏擦 */
    private void erasePixelsSweep(float x0, float y0, float x1, float y1) {
        eraseDirty.setEmpty();
        float dist = ShapeFit.dist(x0, y0, x1, y1);
        int n = Math.max(1, (int) Math.ceil(dist / Math.max(2.5f, eraserRadius * 0.45f)));
        for (int i = 1; i <= n; i++) {
            float t = i / (float) n;
            eraseCircleCore(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t);
        }
        if (!eraseDirty.isEmpty()) patchRegion(new RectF(eraseDirty));
    }

    /**
     * 擦一个圆：按「点到橡皮圈的直线距离 + 笔画自身半宽」判定，
     * 并对穿过橡皮圈但两端点都在圈外的线段做采样补判，确保粗笔、荧光笔也能整段擦掉。
     */
    private void eraseCircleCore(float x, float y) {
        float r = eraserRadius;
        RectF circle = new RectF(x - r, y - r, x + r, y + r);
        List<Stroke> snapshot = new ArrayList<>(scene.strokes);
        for (int k = 0; k < snapshot.size(); k++) {
            Stroke s = snapshot.get(k);
            if (s.pts.isEmpty()) continue;
            float hw = halfWidthOf(s);
            float rr = r + hw;
            RectF probe = new RectF(circle);
            probe.inset(-hw, -hw);
            if (!RectF.intersects(s.bounds(), probe)) continue;

            int n = s.count();
            boolean[] hit = new boolean[n];
            for (int i = 0; i < n; i++) {
                if (ShapeFit.dist(s.x(i), s.y(i), x, y) <= rr) hit[i] = true;
            }
            for (int i = 1; i < n; i++) {
                if (hit[i - 1] && hit[i]) continue;
                float segLen = ShapeFit.dist(s.x(i - 1), s.y(i - 1), s.x(i), s.y(i));
                int steps = (int) Math.ceil(segLen / Math.max(2f, r * 0.5f));
                boolean cross = false;
                for (int t = 1; t < steps; t++) {
                    float f = t / (float) steps;
                    float px = s.x(i - 1) + (s.x(i) - s.x(i - 1)) * f;
                    float py = s.y(i - 1) + (s.y(i) - s.y(i - 1)) * f;
                    if (ShapeFit.dist(px, py, x, y) <= rr) { cross = true; break; }
                }
                if (cross) { hit[i - 1] = true; hit[i] = true; }
            }
            boolean any = false;
            for (int i = 0; i < n; i++) if (hit[i]) { any = true; break; }
            if (!any) continue;

            List<List<float[]>> groups = new ArrayList<>();
            List<float[]> cur = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (hit[i]) {
                    if (!cur.isEmpty()) { groups.add(cur); cur = new ArrayList<>(); }
                } else {
                    cur.add(s.pts.get(i));
                }
            }
            if (!cur.isEmpty()) groups.add(cur);

            eraseDirty.union(s.bounds());
            eraseDirty.union(circle);

            int index = scene.indexOf(s);
            scene.remove(s);
            List<Stroke> frags = new ArrayList<>();
            for (int g = 0; g < groups.size(); g++) {
                List<float[]> grp = groups.get(g);
                if (grp.size() < 2) continue;
                Stroke f = s.copy();
                f.id = 0;
                f.pts.clear();
                f.pts.addAll(grp);
                f.smartShape = ShapeFit.NONE;
                f.geom = null;
                f.invalidateBounds();
                frags.add(f);
                scene.addAt(index + frags.size() - 1, f);
            }
            splitBatch.add(s, index, frags);
        }
    }

    // ---------------------------------------------------------------- 套索
    private void lassoDown(float x, float y) {
        if (!selection.isEmpty() && selBounds.contains(x, y)) {
            selectionDragging = true;
            dragLastX = x;
            dragLastY = y;
            dragTotalX = 0;
            dragTotalY = 0;
            return;
        }
        clearSelection();
        lassoPts.clear();
        lassoPts.add(new float[]{x, y});
    }

    private void lassoMove(float x, float y) {
        if (selectionDragging) {
            float dx = x - dragLastX, dy = y - dragLastY;
            dragLastX = x;
            dragLastY = y;
            dragTotalX += dx;
            dragTotalY += dy;
            for (int i = 0; i < selection.size(); i++) selection.get(i).translate(dx, dy);
            selBounds.offset(dx, dy);
            invalidate();
            return;
        }
        if (lassoPts.isEmpty()) return;
        float[] last = lassoPts.get(lassoPts.size() - 1);
        if (ShapeFit.dist(last[0], last[1], x, y) > 3f * density) lassoPts.add(new float[]{x, y});
    }

    private void lassoUp() {
        if (selectionDragging) {
            selectionDragging = false;
            if (Math.abs(dragTotalX) > 1f || Math.abs(dragTotalY) > 1f) {
                push(new Ops.MoveStrokes(selection, dragTotalX, dragTotalY));
                patchRegion(new RectF(selBounds));
            }
            return;
        }
        if (lassoPts.size() < 3) { lassoPts.clear(); return; }
        List<Stroke> hits = new ArrayList<>();
        RectF bounds = new RectF();
        for (int i = 0; i < scene.strokes.size(); i++) {
            Stroke s = scene.strokes.get(i);
            if (s.count() == 0) continue;
            int inside = 0;
            for (int k = 0; k < s.count(); k++) {
                if (pointInPoly(s.x(k), s.y(k), lassoPts)) inside++;
            }
            if (inside > s.count() * 0.6f) {
                hits.add(s);
                bounds.union(s.bounds());
            }
        }
        lassoPts.clear();
        selection.clear();
        selection.addAll(hits);
        if (!hits.isEmpty()) selBounds.set(bounds);
        if (listener != null) listener.onSelectionChanged(!selection.isEmpty(), selection.size());
        invalidate();
    }

    private boolean pointInPoly(float x, float y, List<float[]> poly) {
        boolean in = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            float xi = poly.get(i)[0], yi = poly.get(i)[1];
            float xj = poly.get(j)[0], yj = poly.get(j)[1];
            boolean cross = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (cross) in = !in;
        }
        return in;
    }

    public boolean hasSelection() { return !selection.isEmpty(); }

    public int selectionCount() { return selection.size(); }

    public RectF selectionBounds() { return new RectF(selBounds); }

    public void clearSelection() {
        if (selection.isEmpty()) return;
        selection.clear();
        lassoPts.clear();
        if (listener != null) listener.onSelectionChanged(false, 0);
        invalidate();
    }

    public void deleteSelection() {
        if (selection.isEmpty()) return;
        Ops.RemoveStrokes op = new Ops.RemoveStrokes();
        RectF dirty = new RectF(selBounds);
        for (int i = 0; i < selection.size(); i++) op.capture(scene, selection.get(i));
        selection.clear();
        push(op);
        patchRegion(dirty);
        if (listener != null) {
            listener.onSelectionChanged(false, 0);
            listener.onStrokeCountChanged(scene.strokes.size());
        }
    }

    public void duplicateSelection() {
        if (selection.isEmpty()) return;
        Ops.DuplicateStrokes op = new Ops.DuplicateStrokes();
        op.build(selection, 30f * density, 30f * density);
        List<Stroke> copies = op.copies();
        for (int i = 0; i < copies.size(); i++) scene.add(copies.get(i));
        push(op);
        RectF dirty = new RectF(selBounds);
        dirty.offset(30f * density, 30f * density);
        dirty.union(selBounds);
        patchRegion(dirty);
        selection.clear();
        selection.addAll(copies);
        selBounds.offset(30f * density, 30f * density);
        if (listener != null) listener.onSelectionChanged(true, selection.size());
    }

    public void recolorSelection(int color) {
        if (selection.isEmpty()) return;
        boolean same = true;
        for (int i = 0; i < selection.size(); i++) if (selection.get(i).color != color) { same = false; break; }
        if (same) return;
        Ops.RecolorStrokes op = new Ops.RecolorStrokes(selection, color);
        op.redo(scene);
        push(op);
        patchRegion(new RectF(selBounds));
    }

    // ---------------------------------------------------------------- 历史
    private void push(Op op) {
        undoStack.push(op);
        while (undoStack.size() > MAX_HISTORY) undoStack.removeLast();
        redoStack.clear();
        notifyHistory();
    }

    private void notifyHistory() {
        if (listener != null) listener.onHistoryChanged(canUndo(), canRedo());
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        Op op = undoStack.pop();
        op.undo(scene);
        redoStack.push(op);
        scene.recomputeTime();
        rebuildSmart();
        notifyHistory();
        if (listener != null) listener.onStrokeCountChanged(scene.strokes.size());
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        Op op = redoStack.pop();
        op.redo(scene);
        undoStack.push(op);
        scene.recomputeTime();
        rebuildSmart();
        notifyHistory();
        if (listener != null) listener.onStrokeCountChanged(scene.strokes.size());
    }

    public void clearAll() {
        if (scene.strokes.isEmpty()) return;
        Ops.ClearAll op = new Ops.ClearAll(scene);
        push(op);
        scene.clear();
        rebuildNow();
        clearSelection();
        if (listener != null) listener.onStrokeCountChanged(0);
    }

    public void setTimeFilter(long t) {
        filterT = t;
        rebuildSmart();
    }

    public long getTimeFilter() { return filterT; }

    // ---------------------------------------------------------------- 导出
    public Bitmap renderToBitmap() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        // 导出用全部笔画，不套时间回溯过滤，否则拖过滑块后导出会是空白
        drawAll(c, scene.strokes, null);
        if (active != null) renderer.draw(c, active);
        android.util.Log.i("PenPad", "export " + w + "x" + h + " strokes=" + scene.strokes.size());
        return out;
    }
}
