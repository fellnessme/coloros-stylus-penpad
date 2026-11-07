package com.aclaniakea.penpad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 悬浮批注层：画布 + 工具条 + 选区浮条 + 时间回溯条。 */
public class FloatService extends Service implements ToolBar.Host, DrawView.Listener {

    public static final String ACTION_START = "com.aclaniakea.penpad.START";
    public static final String ACTION_STOP = "com.aclaniakea.penpad.STOP";
    public static final String ACTION_CLEAR = "com.aclaniakea.penpad.CLEAR";
    public static final String ACTION_SAVE = "com.aclaniakea.penpad.SAVE";
    public static final String ACTION_PASS = "com.aclaniakea.penpad.PASS";
    public static final String ACTION_TIME = "com.aclaniakea.penpad.TIME";
    public static final String ACTION_PICK = "com.aclaniakea.penpad.PICK";
    public static final String EXTRA_TOOL = "tool";

    private static final String CHANNEL = "penpad";
    private static final int NOTIF_ID = 4242;
    private static final int EXT_COLORS[] = {
            0xFFFF9500, 0xFFAF52DE, 0xFF5AC8FA, 0xFF00C7BE, 0xFFFF2D55, 0xFF8E8E93
    };

    private WindowManager wm;
    private DrawView drawView;
    private ToolBar toolBar;
    private WindowManager.LayoutParams canvasLp, barLp;

    private LinearLayout selHost;
    private WindowManager.LayoutParams selLp;

    private FrameLayout timeHost;
    private WindowManager.LayoutParams timeLp;
    private SeekBar timeSeek;
    private TextView timeLabel;
    private boolean timePanelShown;

    private ColorPicker pickerPanel;
    private WindowManager.LayoutParams pickerLp;

    private boolean fingerPass;
    private boolean night;
    private float d;
    private int extColorIdx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent i) { return null; }

    /** 常驻看门狗：系统笔开关被重置就立刻写回，避免「用着用着笔就写不了」 */
    private final Runnable penWatch = new Runnable() {
        @Override public void run() {
            if (drawView != null) {
                int st = AccessibilityHelper.penState(FloatService.this);
                if (st != 1 && AccessibilityHelper.ensurePen(FloatService.this)) {
                    Log.i("PenPad", "pen switch restored by watchdog");
                    Toast.makeText(FloatService.this, "已自动恢复系统手写笔开关", Toast.LENGTH_SHORT).show();
                }
            }
            ui.postDelayed(this, 10000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        d = getResources().getDisplayMetrics().density;
        night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        startForegroundSafe();
        addCanvas();
        try {
            addToolBar();
        } catch (Throwable t) {
            // 工具条失败不能连带把画布一起弄崩（历史闪退点：ToolBar.setTool）
            android.util.Log.e("PenPad", "addToolBar failed", t);
            toolBar = null;
            Toast.makeText(this, "工具条加载失败，笔迹仍可书写", Toast.LENGTH_LONG).show();
        }
        // 启动即自愈：系统笔开关 + 无障碍，然后每 10s 巡检一次
        try {
            AccessibilityHelper.ensureAll(this);
        } catch (Throwable ignored) { }
        ui.postDelayed(penWatch, 1200);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (a != null) {
            switch (a) {
                case ACTION_STOP: stopSelf(); break;
                case ACTION_CLEAR: if (drawView != null) drawView.clearAll(); break;
                case ACTION_SAVE: savePng(); break;
                case ACTION_PASS: togglePass(); break;
                case ACTION_TIME: toggleTimePanel(); break;
                case ACTION_PICK: showColorPicker(); break;
                default:
                    if (intent.hasExtra(EXTRA_TOOL)) selectTool(intent.getIntExtra(EXTRA_TOOL, Stroke.PEN));
                    break;
            }
        }
        return START_STICKY;
    }

    // ------------------------------------------------------------- 画布
    private void addCanvas() {
        drawView = new DrawView(this);
        drawView.setListener(this);
        drawView.setPenOnly(Prefs.penOnly(this));
        drawView.setPalmReject(Prefs.palmReject(this));
        int tool = Prefs.lastTool(this);
        drawView.setTool(tool);
        drawView.setColor(Prefs.color(this, tool));
        drawView.setWidth(Prefs.width(this, tool));

        canvasLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        canvasLp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(drawView, canvasLp);
    }

    // ------------------------------------------------------------- 工具条
    private void addToolBar() {
        toolBar = new ToolBar(this, this);
        int tool = Prefs.lastTool(this);
        toolBar.setTool(tool);
        toolBar.setColorState(Prefs.color(this, tool));
        toolBar.setWidthState(Prefs.width(this, tool));

        barLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        barLp.gravity = Gravity.TOP | Gravity.START;
        barLp.x = Prefs.barX(this) >= 0 ? Prefs.barX(this) : dp(24);
        barLp.y = Prefs.barY(this) >= 0 ? Prefs.barY(this) : dp(8);
        wm.addView(toolBar, barLp);
        clampToolBar();

        bindGripDrag();
    }

    /** 工具条超出屏幕时自动左移，避免右侧按钮（存图/退出）被裁掉 */
    private void clampToolBar() {
        toolBar.post(new Runnable() {
            @Override public void run() {
                if (toolBar == null || barLp == null) return;
                int w = toolBar.getWidth(), h = toolBar.getHeight();
                if (w <= 0) { toolBar.postDelayed(this, 60); return; }
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int screenH = getResources().getDisplayMetrics().heightPixels;
                int maxX = Math.max(dp(8), screenW - w - dp(8));
                int maxY = Math.max(dp(8), screenH - h - dp(8));
                boolean changed = false;
                if (barLp.x > maxX) { barLp.x = maxX; changed = true; }
                if (barLp.x < dp(4)) { barLp.x = dp(4); changed = true; }
                if (barLp.y > maxY) { barLp.y = maxY; changed = true; }
                if (barLp.y < 0) { barLp.y = 0; changed = true; }
                if (changed) {
                    try { wm.updateViewLayout(toolBar, barLp); } catch (Exception ignored) {}
                    Prefs.setBarPos(FloatService.this, barLp.x, barLp.y);
                }
            }
        });
    }

    /** 拖动把手：轻点无操作，长按切换手指穿透，拖动改位置 */
    private void bindGripDrag() {
        final View grip = toolBar.gripHandle();
        if (grip == null) return;
        grip.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            boolean dragging, longFired;
            final Runnable longPress = new Runnable() {
                @Override public void run() {
                    longFired = true;
                    togglePass();
                }
            };

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = barLp.x;
                        startY = barLp.y;
                        dragging = false;
                        longFired = false;
                        ui.postDelayed(longPress, 650);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > 6 * d) {
                            dragging = true;
                            ui.removeCallbacks(longPress);
                        }
                        if (dragging) {
                            barLp.x = startX + (int) dx;
                            barLp.y = startY + (int) dy;
                            try { wm.updateViewLayout(toolBar, barLp); } catch (Exception ignored) {}
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(longPress);
                        if (dragging) Prefs.setBarPos(FloatService.this, barLp.x, barLp.y);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    // ------------------------------------------------------------- ToolBar.Host
    @Override public void onToolSelected(int tool) { selectTool(tool); }

    private void selectTool(int tool) {
        drawView.setTool(tool);
        if (toolBar != null) {
            toolBar.setTool(tool);
            toolBar.setColorState(Prefs.color(this, tool));
            toolBar.setWidthState(Prefs.width(this, tool));
        }
        Prefs.setLastTool(this, tool);
        if (tool == Stroke.TAPE || tool == Stroke.ERASER) {
            if (drawView.isRulerVisible()) { drawView.toggleRuler(); if (toolBar != null) toolBar.setRulerOn(false); }
        }
    }

    @Override public void onRulerToggle() {
        drawView.toggleRuler();
        boolean on = drawView.isRulerVisible();
        if (on) {
            drawView.setRulerAlpha(Prefs.rulerAlpha(this));
            drawView.setRulerThicknessDp(Prefs.rulerThicknessDp(this));
            drawView.setRulerLengthRatio(Prefs.rulerLengthRatio(this));
        }
        if (toolBar != null) toolBar.setRulerOn(on);
        Toast.makeText(this, on ? "直尺：开（长按尺子图标可调长度/宽度/透明度）" : "直尺：关",
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- 尺子设置面板
    private FrameLayout rulerHost;
    private WindowManager.LayoutParams rulerLp;
    private boolean rulerPanelShown;

    @Override public void onRulerSettings() {
        if (rulerPanelShown) { hideRulerPanel(); return; }
        if (drawView == null) return;
        if (!drawView.isRulerVisible()) {
            onRulerToggle();
        }
        buildRulerPanel();
        rulerPanelShown = true;
    }

    private void hideRulerPanel() {
        if (rulerHost != null) {
            try { wm.removeView(rulerHost); } catch (Exception ignored) {}
        }
        rulerHost = null;
        rulerPanelShown = false;
    }

    private void buildRulerPanel() {
        rulerHost = new FrameLayout(this);
        rulerHost.setPadding(dp(14), dp(10), dp(14), dp(10));
        rulerHost.setBackground(ToolBar.roundBg(night ? 0xF2202024 : 0xF2FFFFFF, 22 * d,
                night ? 0x33FFFFFF : 0x14000000));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        final TextView lenVal = tinyLabel("尺子长度 " + Math.round(Prefs.rulerLengthRatio(this) * 100) + "%");
        col.addView(lenVal);
        SeekBar len = new SeekBar(this);
        len.setMax(100);
        len.setProgress(Math.round((Prefs.rulerLengthRatio(this) - 0.30f) / 1.00f * 100f));
        len.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float v = 0.30f + p * 0.01f;
                lenVal.setText("尺子长度 " + Math.round(v * 100) + "%");
                if (!fromUser) return;
                Prefs.setRulerLengthRatio(FloatService.this, v);
                drawView.setRulerLengthRatio(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        col.addView(len, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        final TextView sizeVal = tinyLabel("尺身宽度 34dp");
        col.addView(sizeVal);
        SeekBar size = new SeekBar(this);
        size.setMax(100);
        size.setProgress(Math.round((Prefs.rulerThicknessDp(this) - 22f) / 98f * 100f));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float v = 22f + p * 0.98f;
                sizeVal.setText("尺身宽度 " + Math.round(v) + "dp");
                if (!fromUser) return;
                Prefs.setRulerThicknessDp(FloatService.this, v);
                drawView.setRulerThicknessDp(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        col.addView(size, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        final TextView alphaVal = tinyLabel("透明度 " + Math.round(Prefs.rulerAlpha(this) * 100) + "%");
        col.addView(alphaVal);
        SeekBar alpha = new SeekBar(this);
        alpha.setMax(80);
        alpha.setProgress(Math.round((Prefs.rulerAlpha(this) - 0.15f) / 0.80f * 80f));
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float a = 0.15f + p * 0.01f;
                alphaVal.setText("透明度 " + Math.round(a * 100) + "%");
                if (!fromUser) return;
                Prefs.setRulerAlpha(FloatService.this, a);
                drawView.setRulerAlpha(a);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        col.addView(alpha, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.addView(selBtn("转 90°", new Runnable() {
            @Override public void run() {
                drawView.ruler().rotateBy((float) (Math.PI / 2));
                drawView.invalidate();
            }
        }), weight());
        btns.addView(selBtn("横放归中", new Runnable() {
            @Override public void run() {
                drawView.resetRulerAngle();
                drawView.ruler().resetPosition();
                drawView.invalidate();
            }
        }), weight());
        btns.addView(selBtn("关闭尺子", new Runnable() {
            @Override public void run() {
                if (drawView.isRulerVisible()) drawView.toggleRuler();
                if (toolBar != null) toolBar.setRulerOn(false);
                hideRulerPanel();
            }
        }), weight());
        btns.addView(selBtn("收起", new Runnable() {
            @Override public void run() { hideRulerPanel(); }
        }), weight());
        col.addView(btns);

        rulerHost.addView(col);
        rulerLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        rulerLp.gravity = Gravity.BOTTOM;
        rulerLp.y = dp(56);
        rulerLp.x = dp(12);
        try {
            wm.addView(rulerHost, rulerLp);
        } catch (Exception e) {
            rulerHost = null;
            rulerPanelShown = false;
            Toast.makeText(this, "尺子面板打不开：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private TextView tinyLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(night ? 0xFFE8E8E8 : 0xFF1A1A1A);
        tv.setPadding(dp(2), dp(2), 0, 0);
        return tv;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    @Override public void onColorPicked(int color) {
        int tool = drawView.getTool();
        drawView.setColor(color);
        Prefs.setColor(this, tool, color);
        if (toolBar != null) toolBar.setColorState(color);
    }

    @Override public void onCustomColorRequest() {
        showColorPicker();
    }

    /** 自选颜色面板（HSV 任意取色 + 色相条 + 透明度条 + 十六进制） */
    private void showColorPicker() {
        if (drawView == null) return;
        hideColorPicker();
        final int tool = drawView.getTool();
        final int start = Prefs.color(this, tool);
        pickerPanel = new ColorPicker(this, start, new ColorPicker.Listener() {
            @Override public void onColorChanged(int color) {
                drawView.setColor(color);
                if (toolBar != null) toolBar.setColorState(color);
            }

            @Override public void onColorConfirmed(int color) {
                drawView.setColor(color);
                Prefs.setColor(FloatService.this, tool, color);
                if (toolBar != null) toolBar.setColorState(color);
                hideColorPicker();
            }

            @Override public void onColorCancelled() {
                drawView.setColor(start);
                if (toolBar != null) toolBar.setColorState(start);
                hideColorPicker();
            }
        });

        pickerLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        pickerLp.gravity = Gravity.CENTER;
        pickerLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try {
            wm.addView(pickerPanel, pickerLp);
        } catch (Exception e) {
            android.util.Log.e("PenPad", "picker add failed", e);
            pickerPanel = null;
            Toast.makeText(this, "取色面板打不开：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void hideColorPicker() {
        if (pickerPanel == null) return;
        try { wm.removeView(pickerPanel); } catch (Exception ignored) { }
        pickerPanel = null;
        pickerLp = null;
    }

    @Override public void onWidthChanged(float w) {
        drawView.setWidth(w);
        Prefs.setWidth(this, drawView.getTool(), w);
    }

    @Override public void onUndo() { drawView.undo(); }

    @Override public void onRedo() { drawView.redo(); }

    @Override public void onClear() {
        drawView.clearAll();
        hideSelBar();
        if (timePanelShown) refreshTimePanel();
    }

    @Override public void onSave() { savePng(); }

    @Override public void onExit() {
        Toast.makeText(this, "批注已关闭", Toast.LENGTH_SHORT).show();
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------- DrawView.Listener
    @Override public void onSelectionChanged(boolean has, int count) {
        if (has) showSelBar(); else hideSelBar();
    }

    @Override public void onHistoryChanged(boolean canUndo, boolean canRedo) {
        if (toolBar != null) toolBar.setHistoryState(canUndo, canRedo);
    }

    @Override public void onStrokeCountChanged(int count) {
        if (timePanelShown) refreshTimePanel();
    }

    // ------------------------------------------------------------- 选区浮条
    private void showSelBar() {
        if (selHost == null) buildSelBar();
        try { wm.removeView(selHost); } catch (Exception ignored) {}
        RectF b = drawView.selectionBounds();
        int w = dp(4 * 74 + 12);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int x = (int) b.left;
        if (x + w > screenW - dp(12)) x = screenW - w - dp(12);
        if (x < dp(12)) x = dp(12);
        selLp.x = x;
        selLp.y = (int) b.bottom + dp(14);
        try { wm.addView(selHost, selLp); } catch (Exception ignored) {}
    }

    private void hideSelBar() {
        if (selHost == null) return;
        try { wm.removeView(selHost); } catch (Exception ignored) {}
    }

    private void buildSelBar() {
        selHost = new LinearLayout(this);
        selHost.setOrientation(LinearLayout.HORIZONTAL);
        selHost.setGravity(Gravity.CENTER_VERTICAL);
        selHost.setPadding(dp(6), dp(4), dp(6), dp(4));
        selHost.setBackground(ToolBar.roundBg(night ? 0xF2202024 : 0xF2FFFFFF, 22 * d,
                night ? 0x33FFFFFF : 0x14000000));

        selHost.addView(selBtn("删除", new Runnable() {
            @Override public void run() {
                drawView.deleteSelection();
                hideSelBar();
            }
        }));
        selHost.addView(selBtn("复制", new Runnable() {
            @Override public void run() {
                drawView.duplicateSelection();
                ui.postDelayed(new Runnable() { @Override public void run() { showSelBar(); } }, 40);
            }
        }));
        selHost.addView(selBtn("改色", new Runnable() {
            @Override public void run() {
                int c = drawView.getColor();
                drawView.recolorSelection(c);
            }
        }));
        selHost.addView(selBtn("完成", new Runnable() {
            @Override public void run() {
                drawView.clearSelection();
                hideSelBar();
            }
        }));

        selLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        selLp.gravity = Gravity.TOP | Gravity.START;
    }

    private TextView selBtn(String text, final Runnable r) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(night ? 0xFFE8E8E8 : 0xFF1A1A1A);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        return tv;
    }

    // ------------------------------------------------------------- 时间回溯
    private void toggleTimePanel() {
        if (timePanelShown) {
            if (timeHost != null) {
                try { wm.removeView(timeHost); } catch (Exception ignored) {}
            }
            timePanelShown = false;
            drawView.setTimeFilter(-1);
            return;
        }
        if (drawView.strokeCount() == 0) {
            Toast.makeText(this, "还没有笔迹可回溯", Toast.LENGTH_SHORT).show();
            return;
        }
        buildTimePanel();
        refreshTimePanel();
        timePanelShown = true;
        timeSeek.setProgress(1000);
        drawView.setTimeFilter(-1);
    }

    private void buildTimePanel() {
        timeHost = new FrameLayout(this);
        timeHost.setPadding(dp(18), dp(4), dp(18), dp(4));
        timeHost.setBackground(ToolBar.roundBg(night ? 0xF2202024 : 0xF2FFFFFF, 24 * d,
                night ? 0x33FFFFFF : 0x14000000));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        timeLabel = new TextView(this);
        timeLabel.setTextColor(night ? 0xFFE8E8E8 : 0xFF1A1A1A);
        timeLabel.setTextSize(12);
        timeLabel.setPadding(0, 0, dp(10), 0);
        timeLabel.setText("全部");

        timeSeek = new SeekBar(this);
        timeSeek.setMax(1000);
        timeSeek.setProgress(1000);
        timeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                long min = drawView.timeMin(), max = drawView.timeMax();
                if (max <= min) return;
                if (p >= 1000) {
                    timeLabel.setText("全部");
                    drawView.setTimeFilter(-1);
                } else {
                    long t = min + (long) ((max - min) * (p / 1000.0));
                    timeLabel.setText("回溯 " + (1000 - p) / 10 + "%");
                    drawView.setTimeFilter(t);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }

            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        row.addView(timeLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(timeSeek, new LinearLayout.LayoutParams(0, dp(36), 1f));

        TextView close = selBtn("×", new Runnable() {
            @Override public void run() { toggleTimePanel(); }
        });
        row.addView(close);

        timeHost.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        timeLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        timeLp.gravity = Gravity.BOTTOM;
        timeLp.y = dp(64);
        try { wm.addView(timeHost, timeLp); } catch (Exception ignored) {}
    }

    private void refreshTimePanel() {
        if (!timePanelShown || timeSeek == null) return;
    }

    // ------------------------------------------------------------- 穿透
    private void togglePass() {
        fingerPass = !fingerPass;
        if (fingerPass) {
            canvasLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            canvasLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try { wm.updateViewLayout(drawView, canvasLp); } catch (Exception ignored) {}
        Toast.makeText(this, fingerPass ? "手指穿透：开（笔迹保留，触摸交给下层 App）" : "手指穿透：关",
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- 保存
    private void savePng() {
        Bitmap bmp = drawView.renderToBitmap();
        if (bmp == null) {
            Toast.makeText(this, "画布还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "PenPad_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PenPad");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new RuntimeException("MediaStore 拒绝写入");
            OutputStream os = getContentResolver().openOutputStream(uri);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            if (os != null) os.close();
            Toast.makeText(this, "已保存到相册 Pictures/PenPad\n" + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            bmp.recycle();
        }
    }

    // ------------------------------------------------------------- 通知
    private void startForegroundSafe() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "笔迹批注",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("笔迹批注已开启")
                .setContentText("顶部工具条可拖动；最右 X 退出批注")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pi)
                .setOngoing(true);
        startForeground(NOTIF_ID, b.build());
    }

    // ------------------------------------------------------------- 生命周期
    @Override public void onDestroy() {
        super.onDestroy();
        Services.isRunning = false;
        hideColorPicker();
        hideRulerPanel();
        try { if (drawView != null) wm.removeView(drawView); } catch (Exception ignored) {}
        try { if (toolBar != null) wm.removeView(toolBar); } catch (Exception ignored) {}
        try { if (selHost != null) wm.removeView(selHost); } catch (Exception ignored) {}
        try { if (timeHost != null) wm.removeView(timeHost); } catch (Exception ignored) {}
        ui.removeCallbacksAndMessages(null);
        drawView = null;
        toolBar = null;
        selHost = null;
        timeHost = null;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, FloatService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, FloatService.class));
    }

    public static void send(Context c, String action) {
        Intent i = new Intent(c, FloatService.class);
        i.setAction(action);
        try { c.startService(i); } catch (Exception ignored) {}
    }

    private int dp(float v) { return (int) (v * d + 0.5f); }
}
