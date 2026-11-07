package com.aclaniakea.penpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 笔键接收器。
 *
 * ColorOS 在 interceptKeyBeforeQueueing 阶段就把笔键消费掉了，普通 App
 * （包括无障碍服务的按键过滤）都收不到，所以由 adb 起的 shell 守护脚本
 * 读 /dev/input 后广播过来。
 *
 * 广播格式：am broadcast -a com.aclaniakea.penpad.PENKEY --ei code N
 *   N = 0 单击 / 1 双击 / 2 三击 / 4 按住
 */
public class PenKeyReceiver extends BroadcastReceiver {

    private static final String TAG = "PenPadKey";

    public static final String ACTION = "com.aclaniakea.penpad.PENKEY";

    /** 连击判定窗口：这段时间内没有新按键就结算 */
    private static final long MULTI_CLICK_GAP_MS = 250L;

    private static int pendingCount = 0;
    private static android.os.Handler handler;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int code = intent.getIntExtra("code", -1);
        Log.i(TAG, "penkey broadcast code=" + code);

        switch (code) {
            case 1:   // 笔直接报的双击
                dispatchNext(true);
                return;
            case 2:   // 笔直接报的三击
                dispatchNext(false);
                return;
            case 4:   // 按住
                toggleOverlay(context);
                return;
            case 0:   // 单击
            default:
                // 批注是关着的：立刻打开，不用等连击判定（打开是最常用的操作，要快）
                if (!Services.isRunning) {
                    toggleOverlay(context);
                    return;
                }
                // 已经是开着的：可能是要关，也可能是在翻页，攒一会儿再判
                accumulate(context);
                return;
        }
    }

    /** 笔的固件对快速连按也只上报 0x600，所以在这里数次数。 */
    private static synchronized void accumulate(final Context ctx) {
        if (handler == null) handler = new android.os.Handler(android.os.Looper.getMainLooper());
        pendingCount++;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                int n;
                synchronized (PenKeyReceiver.class) {
                    n = pendingCount;
                    pendingCount = 0;
                }
                Log.i(TAG, "settle clicks=" + n);
                if (n >= 3) dispatchNext(false);      // 三击 → 上一项
                else if (n == 2) dispatchNext(true);  // 双击 → 下一项
                else {
                    Context app = ctx.getApplicationContext();
                    toggleOverlay(app);
                }
            }
        }, MULTI_CLICK_GAP_MS);
    }

    private static void toggleOverlay(Context c) {
        if (!MainActivity.canOverlay(c)) {
            Log.w(TAG, "no overlay permission");
            return;
        }
        if (Services.isRunning) {
            FloatService.stop(c);
            Services.isRunning = false;
        } else {
            FloatService.start(c);
            Services.isRunning = true;
        }
    }

    /** 手势必须由无障碍服务发，这里转发给正在运行的服务实例。 */
    private static void dispatchNext(boolean next) {
        PenKeyService svc = PenKeyService.INSTANCE;
        if (svc == null) {
            Log.w(TAG, "accessibility service not running, drop gesture");
            return;
        }
        svc.remoteNextPrev(next);
    }
}
