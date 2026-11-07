package com.aclaniakea.penpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 笔键助手：监听手写笔按键（需要系统 keylayout 把
 * MSC_SCAN 0x0c0600/0601/0602/0604 映射成 STYLUS_BUTTON_*），
 * 并执行开关批注 / 上下一项 的动作。
 */
public class PenKeyService extends AccessibilityService {

    /** 供广播接收器调用（笔键由 adb 守护脚本转发过来） */
    public static volatile PenKeyService INSTANCE = null;

    /** 双击/三击触发的上一项/下一项 */
    public void remoteNextPrev(boolean next) { nextOrPrev(next); }

    private static final String TAG = "PenPadKey";

    private static final int KEY_STYLUS_PRIMARY = 308;   // 单击
    private static final int KEY_STYLUS_SECONDARY = 309; // 双击
    private static final int KEY_STYLUS_TERTIARY = 310;  // 三击
    private static final int KEY_STYLUS_TAIL = 311;      // 按住

    private final Handler h = new Handler(Looper.getMainLooper());
    private String fgPackage = "";
    private long keyDownAt = 0L;
    private long lastToggleAt = 0L;

    /** 书写中/刚写完 800ms 内，笔键视为噪声：手掌握持时极易压到笔键导致误关批注层。 */
    private static final long DRAW_GUARD_MS = 800L;

    private static boolean drawingRecently() {
        long t = DrawView.lastTouchAt;
        return t > 0 && android.os.SystemClock.uptimeMillis() - t < DRAW_GUARD_MS;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Log.i(TAG, "connected");
        // 自愈：确保系统设置里本服务仍处于启用状态，并挂上看门狗
        AccessibilityHelper.ensureEnabled(this);
        // 借 Shizuku 的 shell 身份启动并维持笔键守护
        DaemonKeeper.start(this);
        KeepAliveReceiver.schedule(this);
        // 笔开关守护：ColorOS 会不定时把 settings_enable_oppo_pencil 重置成 0，
        // 表现就是"笔突然写不出字"。这里每 3 秒检查一次，掉到 0 就立刻写回 1。
        h.removeCallbacks(penKeeper);
        h.post(penKeeper);
    }

    private final Runnable penKeeper = new Runnable() {
        @Override public void run() {
            try {
                if (AccessibilityHelper.penState(PenKeyService.this) != 1) {
                    AccessibilityHelper.ensurePen(PenKeyService.this);
                }
                AccessibilityHelper.ensureEnabled(PenKeyService.this);
            } catch (Throwable ignored) { }
            h.postDelayed(this, 3000);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence p = e.getPackageName();
            if (p != null) fgPackage = p.toString();
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        Log.i(TAG, "onKeyEvent code=" + code + " action=" + event.getAction()
                + " scan=" + event.getScanCode() + " repeat=" + event.getRepeatCount());
        if (code < KEY_STYLUS_PRIMARY || code > KEY_STYLUS_TAIL) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            keyDownAt = android.os.SystemClock.uptimeMillis();
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        if (event.getRepeatCount() > 0) return false;

        // 1) 正在书写 / 刚画完：一律不处理，交给系统（避免手掌压键把批注层关掉）
        if (drawingRecently()) {
            Log.i(TAG, "stylus key " + code + " ignored (drawing)");
            return false;
        }
        // 2) 极短的瞬时抖动（<25ms）忽略
        if (keyDownAt > 0 && android.os.SystemClock.uptimeMillis() - keyDownAt < 25) {
            Log.i(TAG, "stylus key " + code + " ignored (too short)");
            return false;
        }

        Log.i(TAG, "stylus key " + code + " fg=" + fgPackage);

        switch (code) {
            case KEY_STYLUS_PRIMARY:
            case KEY_STYLUS_TAIL:
                toggleOverlay();
                return true;
            case KEY_STYLUS_SECONDARY:
                nextOrPrev(true);
                return true;
            case KEY_STYLUS_TERTIARY:
                nextOrPrev(false);
                return true;
            default:
                return false;
        }
    }

    private void toggleOverlay() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastToggleAt < 700) return;      // 连击去抖
        if (drawingRecently()) return;             // 书写中不切换
        lastToggleAt = now;
        if (!MainActivity.canOverlay(this)) return;
        if (Services.isRunning) {
            FloatService.stop(this);
            Services.isRunning = false;
        } else {
            FloatService.start(this);
            Services.isRunning = true;
        }
    }

    /** 视频类上下滑；文档类左右滑。 */
    private boolean horizontal() {
        String p = fgPackage.toLowerCase();
        return p.contains("office") || p.contains("wps") || p.contains("pdf")
                || p.contains("powerpoint") || p.contains("microsoft.office")
                || p.contains("adobe") || p.contains("notes");
    }

    private void nextOrPrev(boolean next) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int hpx = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        if (horizontal()) {
            float y = hpx * 0.5f;
            if (next) { path.moveTo(w * 0.85f, y); path.lineTo(w * 0.15f, y); }
            else      { path.moveTo(w * 0.15f, y); path.lineTo(w * 0.85f, y); }
        } else {
            float x = w * 0.5f;
            if (next) { path.moveTo(x, hpx * 0.80f); path.lineTo(x, hpx * 0.20f); }
            else      { path.moveTo(x, hpx * 0.20f); path.lineTo(x, hpx * 0.80f); }
        }
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 220);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gd, null, null);
    }
}
