package com.aclaniakea.penpad;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 顶部/底部工具条（§9.5 视觉规格）：
 * 56dp 高、28dp 圆角、三组分隔、44dp 点击区、26dp 线性图标。
 */
public class ToolBar extends LinearLayout {

    public interface Host {
        void onToolSelected(int tool);
        void onRulerToggle();
        void onRulerSettings();
        void onColorPicked(int color);
        void onCustomColorRequest();
        void onWidthChanged(float w);
        void onUndo();
        void onRedo();
        void onClear();
        void onSave();
        void onExit();
    }

    public static final int[] SWATCHES = {
            0xFF111111, 0xFFFF3B30, 0xFF0A84FF, 0xFF34C759, 0xFFFFCC00, 0xFFFFFFFF
    };

    private final float d;
    private final boolean night;
    private final Host host;

    private ToolButton btnPen, btnFountain, btnPencil, btnHighlight, btnTape, btnRuler;
    private ToolButton btnLasso, btnEraser, btnSmart, btnLaser;
    private ToolButton btnUndo, btnRedo, btnClear, btnSave, btnExit;
    private GripView gripView;
    private PaletteView palette;
    private WidthSliderView slider;

    private int currentTool = Stroke.PEN;

    public ToolBar(Context c, Host host) {
        super(c);
        this.host = host;
        d = getResources().getDisplayMetrics().density;
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * d);
        setPadding(pad, 0, pad, 0);
        setBackground(roundBg(night ? 0xF2202024 : 0xF2FFFFFF, 28 * d,
                night ? 0x33FFFFFF : 0x14000000));

        addView(grip());
        btnPen = toolBtn(R.drawable.ic_tool_pen, Stroke.PEN, false);
        addView(btnPen);
        btnFountain = toolBtn(R.drawable.ic_tool_fountain, Stroke.FOUNTAIN, false);
        addView(btnFountain);
        btnPencil = toolBtn(R.drawable.ic_tool_pencil, Stroke.PENCIL, false);
        addView(btnPencil);
        btnHighlight = toolBtn(R.drawable.ic_tool_highlight, Stroke.HIGHLIGHT, false);
        addView(btnHighlight);
        btnTape = toolBtn(R.drawable.ic_tool_tape, Stroke.TAPE, false);
        addView(btnTape);
        btnRuler = toolBtn(R.drawable.ic_tool_ruler, -1, true);
        btnRuler.setOnLongPress(() -> host.onRulerSettings());
        addView(btnRuler);

        addView(divider());
        btnLasso = toolBtn(R.drawable.ic_tool_lasso, Stroke.LASSO, false);
        addView(btnLasso);
        btnEraser = toolBtn(R.drawable.ic_tool_eraser, Stroke.ERASER, false);
        addView(btnEraser);
        btnSmart = toolBtn(R.drawable.ic_tool_smart, Stroke.SMART, false);
        addView(btnSmart);
        btnLaser = toolBtn(R.drawable.ic_tool_laser, Stroke.LASER, false);
        addView(btnLaser);

        addView(divider());
        palette = new PaletteView(getContext());
        palette.setOnPick(new PaletteView.OnPick() {
            @Override public void onColor(int color) { host.onColorPicked(color); }

            @Override public void onCustom() { host.onCustomColorRequest(); }
        });
        addView(palette, new LayoutParams((int) (7 * 32 * d), (int) (44 * d)));

        addView(divider());
        slider = new WidthSliderView(getContext());
        slider.setOnChange(new WidthSliderView.OnChange() {
            @Override public void onChange(float w) { host.onWidthChanged(w); }
        });
        addView(slider, new LayoutParams((int) (112 * d), (int) (48 * d)));

        addView(divider());
        btnUndo = opBtn(R.drawable.ic_undo);
        btnUndo.setOnTap(() -> host.onUndo());
        addView(btnUndo);
        btnRedo = opBtn(R.drawable.ic_redo);
        btnRedo.setOnTap(() -> host.onRedo());
        addView(btnRedo);
        btnClear = opBtn(R.drawable.ic_clear);
        btnClear.setOnTap(() -> host.onClear());
        addView(btnClear);

        addView(divider());
        btnSave = opBtn(R.drawable.ic_save);
        btnSave.setOnTap(() -> host.onSave());
        addView(btnSave);
        btnExit = opBtn(R.drawable.ic_exit);
        btnExit.setOnTap(() -> host.onExit());
        addView(btnExit);
    }

    private ToolButton opBtn(int iconRes) {
        ToolButton b = new ToolButton(getContext(), iconRes, night ? 0xFFE8E8E8 : 0xFF1A1A1A,
                night ? 0x1FFFFFFF : 0x14000000);
        b.setLayoutParams(new LayoutParams((int) (44 * d), (int) (44 * d)));
        return b;
    }

    public void setHistoryState(boolean canUndo, boolean canRedo) {
        if (btnUndo == null) return;
        btnUndo.setDim(!canUndo);
        btnRedo.setDim(!canRedo);
    }

    public View gripHandle() { return gripView; }

    public void setTool(int tool) {
        currentTool = tool;
        if (btnPen == null) return;
        btnPen.selected = tool == Stroke.PEN;
        btnFountain.selected = tool == Stroke.FOUNTAIN;
        btnPencil.selected = tool == Stroke.PENCIL;
        btnHighlight.selected = tool == Stroke.HIGHLIGHT;
        btnTape.selected = tool == Stroke.TAPE;
        btnLasso.selected = tool == Stroke.LASSO;
        btnEraser.selected = tool == Stroke.ERASER;
        btnSmart.selected = tool == Stroke.SMART;
        btnLaser.selected = tool == Stroke.LASER;
        invalidateAll();
    }

    public void setRulerOn(boolean on) {
        if (btnRuler == null) return;
        btnRuler.selected = on;
        btnRuler.invalidate();
    }

    public void setColorState(int color) {
        palette.setColor(color);
        slider.setColor(color);
    }

    public void setWidthState(float w) {
        slider.setValue(w);
    }

    public int getTool() { return currentTool; }

    private void invalidateAll() {
        invalidateOne(btnPen);
        invalidateOne(btnFountain);
        invalidateOne(btnPencil);
        invalidateOne(btnHighlight);
        invalidateOne(btnTape);
        invalidateOne(btnLasso);
        invalidateOne(btnEraser);
        invalidateOne(btnSmart);
        invalidateOne(btnLaser);
    }

    private static void invalidateOne(ToolButton b) {
        if (b != null) b.invalidate();
    }

    private View grip() {
        View v = new View(getContext());
        v.setBackground(null);
        LayoutParams lp = new LayoutParams((int) (20 * d), (int) (44 * d));
        v.setLayoutParams(lp);
        Drawable dr = getResources().getDrawable(R.drawable.ic_grip, null);
        final GripView gv = new GripView(getContext(), dr, night ? 0x66FFFFFF : 0x55000000);
        gv.setLayoutParams(lp);
        gripView = gv;
        return gv;
    }

    private View divider() {
        View v = new View(getContext());
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(night ? 0x33FFFFFF : 0x1F000000);
        gd.setCornerRadius(d);
        v.setBackground(gd);
        LayoutParams lp = new LayoutParams((int) (1.2f * d), (int) (24 * d));
        lp.leftMargin = (int) (4 * d);
        lp.rightMargin = (int) (4 * d);
        v.setLayoutParams(lp);
        return v;
    }

    private ToolButton toolBtn(int iconRes, final int tool, final boolean toggle) {
        ToolButton b = new ToolButton(getContext(), iconRes, night ? 0xFFE8E8E8 : 0xFF1A1A1A,
                night ? 0x1FFFFFFF : 0x14000000);
        LayoutParams lp = new LayoutParams((int) (44 * d), (int) (44 * d));
        b.setLayoutParams(lp);
        if (toggle) {
            b.setOnTap(() -> host.onRulerToggle());
        } else {
            b.setOnTap(() -> host.onToolSelected(tool));
        }
        return b;
    }

    static GradientDrawable roundBg(int color, float radius, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(radius);
        gd.setStroke(Math.max(1, (int) (1f)), strokeColor);
        return gd;
    }

    // ================================================================ 抓取点
    static class GripView extends View {
        private final Drawable icon;

        GripView(Context c, Drawable dr, int tint) {
            super(c);
            icon = dr.mutate();
            icon.setTint(tint);
        }

        @Override protected void onDraw(Canvas canvas) {
            int s = (int) (14 * getResources().getDisplayMetrics().density);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            icon.draw(canvas);
        }
    }

    // ================================================================ 图标按钮
    static class ToolButton extends View {
        private final Drawable icon;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float d;
        private final int bgColor;
        boolean selected;
        private boolean pressed;
        private boolean dim;
        private Runnable tap;

        void setDim(boolean v) {
            if (dim == v) return;
            dim = v;
            invalidate();
        }

        ToolButton(Context c, int iconRes, int tint, int bgColor) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            this.bgColor = bgColor;
            icon = getResources().getDrawable(iconRes, null).mutate();
            if (iconRes != R.drawable.ic_tool_pencil) icon.setTint(tint);
            bgPaint.setStyle(Paint.Style.FILL);
        }

        void setOnTap(Runnable r) { tap = r; }

        private Runnable longPress;
        private final Runnable longTick = new Runnable() {
            @Override public void run() {
                longFired = true;
                pressed = false;
                invalidate();
                if (longPress != null) longPress.run();
            }
        };
        private boolean longFired;
        private final Handler h = new Handler(Looper.getMainLooper());

        void setOnLongPress(Runnable r) { longPress = r; }

        @Override protected void onDraw(Canvas canvas) {
            if (selected || pressed) {
                float inset = 2 * d;
                rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
                bgPaint.setColor(selected ? bgColor : (bgColor & 0x00FFFFFF) | 0x0D000000);
                canvas.drawRoundRect(rect, 12 * d, 12 * d, bgPaint);
            }
            int s = (int) (26 * d);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            icon.setAlpha(dim ? 70 : (pressed ? 150 : 255));
            icon.draw(canvas);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    longFired = false;
                    invalidate();
                    if (longPress != null) h.postDelayed(longTick, 500);
                    return true;
                case MotionEvent.ACTION_UP:
                    h.removeCallbacks(longTick);
                    pressed = false;
                    invalidate();
                    if (!longFired && e.getX() >= 0 && e.getX() <= getWidth()
                            && e.getY() >= 0 && e.getY() <= getHeight()) {
                        performClick();
                        if (tap != null) tap.run();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(longTick);
                    pressed = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }

    // ================================================================ 调色盘
    static class PaletteView extends View {
        interface OnPick {
            void onColor(int color);
            void onCustom();
        }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private final Drawable rainbow;
        private int selected = 0;
        private OnPick pick;

        PaletteView(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * d);
            ring.setColor(0xFF0A84FF);
            rainbow = getResources().getDrawable(R.drawable.ic_tool_palette, null);
        }

        void setOnPick(OnPick p) { pick = p; }

        void setColor(int color) {
            selected = -1;
            for (int i = 0; i < SWATCHES.length; i++) if (SWATCHES[i] == color) selected = i;
            invalidate();
        }

        private float itemSize() { return 32 * d; }

        private float centerY() { return getHeight() / 2f; }

        @Override protected void onDraw(Canvas canvas) {
            float cy = centerY();
            for (int i = 0; i < SWATCHES.length; i++) {
                float cx = itemSize() * i + itemSize() / 2f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(SWATCHES[i]);
                canvas.drawCircle(cx, cy, 11 * d, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1 * d);
                p.setColor(0x33000000);
                canvas.drawCircle(cx, cy, 11 * d, p);
                if (selected == i) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * d);
                    p.setColor(0xFF0A84FF);
                    canvas.drawCircle(cx, cy, 14 * d, p);
                }
            }
            // 彩虹环
            float cx = itemSize() * SWATCHES.length + itemSize() / 2f;
            int s = (int) (24 * d);
            rainbow.setBounds((int) (cx - s / 2), (int) (cy - s / 2), (int) (cx + s / 2), (int) (cy + s / 2));
            rainbow.draw(canvas);
            if (selected == SWATCHES.length) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(0xFF0A84FF);
                canvas.drawCircle(cx, cy, 14 * d, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            int idx = (int) (e.getX() / itemSize());
            if (idx < SWATCHES.length) {
                selected = idx;
                invalidate();
                if (pick != null) pick.onColor(SWATCHES[idx]);
            } else {
                if (pick != null) pick.onCustom();
            }
            return true;
        }
    }

    // ================================================================ 笔宽滑块
    static class WidthSliderView extends View {
        interface OnChange {
            void onChange(float w);
        }

        static final float MIN = 1f, MAX = 24f;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private float value = 3f;
        private int color = 0xFF111111;
        private OnChange change;
        private boolean dragging;

        WidthSliderView(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            p.setAntiAlias(true);
        }

        void setOnChange(OnChange c) { change = c; }

        void setValue(float w) {
            value = Math.max(MIN, Math.min(MAX, w));
            invalidate();
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        private float trackLeft() { return 46 * d; }

        private float trackRight() { return getWidth() - 14 * d; }

        @Override protected void onDraw(Canvas canvas) {
            float cy = getHeight() / 2f;

            // 左侧粗细示例
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            float sampleR = Math.min(11 * d, value * d / 2f);
            canvas.drawCircle(18 * d, cy, Math.max(1.2f * d, sampleR), p);

            // 轨道
            p.setColor(0x33000000);
            p.setStyle(Paint.Style.FILL);
            RectF track = new RectF(trackLeft(), cy - 1.5f * d, trackRight(), cy + 1.5f * d);
            canvas.drawRoundRect(track, 2 * d, 2 * d, p);

            // 已选段
            float t = (value - MIN) / (MAX - MIN);
            float x = trackLeft() + (trackRight() - trackLeft()) * t;
            p.setColor(0xFF0A84FF);
            RectF done = new RectF(trackLeft(), cy - 1.5f * d, x, cy + 1.5f * d);
            canvas.drawRoundRect(done, 2 * d, 2 * d, p);

            // 滑钮
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, cy, (dragging ? 10 : 9) * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * d);
            p.setColor(0x33000000);
            canvas.drawCircle(x, cy, (dragging ? 10 : 9) * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0A84FF);
            canvas.drawCircle(x, cy, 2.2f * d, p);
        }

        private void updateFromX(float x) {
            float l = trackLeft(), r = trackRight();
            float t = (x - l) / Math.max(1f, r - l);
            t = Math.max(0f, Math.min(1f, t));
            float v = MIN + (MAX - MIN) * t;
            v = Math.round(v * 2f) / 2f;
            if (v != value) {
                value = v;
                invalidate();
                if (change != null) change.onChange(value);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    updateFromX(e.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }
}
