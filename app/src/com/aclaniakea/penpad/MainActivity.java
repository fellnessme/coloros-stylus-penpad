package com.aclaniakea.penpad;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView status;
    private Button toggle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 申请 Shizuku 客户端权限（仅首次需要用户点允许）
        try {
            String p = "xyz.shizuku.extra.permission.API_V23";
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{p}, 100);
            }
        } catch (Throwable ignored) { }
        final boolean inkOk = InkEngine.init();
        android.util.Log.i("PenPad", "InkEngine ready=" + inkOk + " err=" + InkEngine.error());
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AccessibilityHelper.ensureAll(this);
        refresh();
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        addTitle(root, "笔迹批注 2.0");
        addBody(root, "笔画对象模型重写版：可选中、可撤销、可回溯、可吸附直尺。");

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status);

        Button perm = new Button(this);
        perm.setText("① 授予悬浮窗权限");
        perm.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) {
                Toast.makeText(this, "请在系统设置里手动开启悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(perm, full());

        Button acc = new Button(this);
        acc.setText("② 开启无障碍服务（笔键功能）");
        acc.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "找到「笔键助手」并开启", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        });
        root.addView(acc, full());

        Button keep = new Button(this);
        keep.setText("③ 让无障碍常驻（清后台 / 重启后自动恢复）");
        keep.setOnClickListener(v -> {
            if (!AccessibilityHelper.canWrite(this)) {
                Toast.makeText(this, "缺少 WRITE_SECURE_SETTINGS，请先执行一次：\n"
                        + "adb shell pm grant " + getPackageName()
                        + " android.permission.WRITE_SECURE_SETTINGS", Toast.LENGTH_LONG).show();
                return;
            }
            boolean ok = AccessibilityHelper.ensureEnabled(this);
            KeepAliveReceiver.schedule(this);
            Toast.makeText(this, ok ? "已写回系统设置，15 分钟自检一次，重启自动恢复"
                    : "写入失败，请检查权限", Toast.LENGTH_LONG).show();
            refresh();
        });
        root.addView(keep, full());

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (!canOverlay(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Services.isRunning) {
                FloatService.send(this, FloatService.ACTION_STOP);
                Services.isRunning = false;
            } else {
                FloatService.start(this);
                Services.isRunning = true;
            }
            toggle.postDelayed(this::refresh, 400);
        });
        root.addView(toggle, full());

        addSep(root);
        addBody(root, "批注中快捷操作");

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(quickBtn("清空", () -> FloatService.send(this, FloatService.ACTION_CLEAR)), weight());
        quick.addView(quickBtn("存图", () -> FloatService.send(this, FloatService.ACTION_SAVE)), weight());
        quick.addView(quickBtn("穿透", () -> FloatService.send(this, FloatService.ACTION_PASS)), weight());
        quick.addView(quickBtn("回溯", () -> FloatService.send(this, FloatService.ACTION_TIME)), weight());
        quick.addView(quickBtn("调色", () -> FloatService.send(this, FloatService.ACTION_PICK)), weight());
        root.addView(quick);

        addSep(root);
        addBody(root, "防误触");

        CheckBox penOnly = new CheckBox(this);
        penOnly.setText("只认笔，忽略手指");
        penOnly.setChecked(Prefs.penOnly(this));
        penOnly.setOnClickListener(v -> {
            Prefs.setPenOnly(this, penOnly.isChecked());
            Toast.makeText(this, "重新开启批注后生效", Toast.LENGTH_SHORT).show();
        });
        root.addView(penOnly);

        CheckBox palm = new CheckBox(this);
        palm.setText("手掌抑制（接触面积过大时忽略）");
        palm.setChecked(Prefs.palmReject(this));
        palm.setOnClickListener(v -> {
            Prefs.setPalmReject(this, palm.isChecked());
            Toast.makeText(this, "重新开启批注后生效", Toast.LENGTH_SHORT).show();
        });
        root.addView(palm);

        addSep(root);
        final int tool = Stroke.PEN;
        addBody(root, "默认笔宽（dp，可在工具条里随时调）");
        SeekBar sb = new SeekBar(this);
        sb.setMax(47);
        sb.setProgress(Math.round(Prefs.width(this, tool) * 2) - 2);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                if (!fromUser) return;
                Prefs.setWidth(MainActivity.this, tool, (prog + 2) / 2f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb, full());

        addBody(root, "默认颜色（笔）");
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        for (int c : ToolBar.SWATCHES) {
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            v.setLayoutParams(lp);
            GradientDrawable g = new GradientDrawable();
            g.setColor(c);
            g.setCornerRadius(dp(18));
            g.setStroke(dp(1), 0x33000000);
            v.setBackground(g);
            v.setOnClickListener(x -> {
                Prefs.setColor(this, tool, c);
                Toast.makeText(this, "笔色已更新，重新开启批注生效", Toast.LENGTH_SHORT).show();
            });
            colors.addView(v);
        }
        root.addView(colors);

        addSep(root);
        addBody(root, "工具条把手：拖动可移动位置，长按切换「手指穿透」。\n"
                + "选区出现后可在笔迹下方操作：删除 / 复制 / 改色 / 完成。\n\n"
                + "笔键（需开启无障碍 + 系统 keylayout 映射）：单击开关批注，双击下一项，三击上一项。");

        return sv;
    }

    private Button quickBtn(String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            if (!Services.isRunning) {
                Toast.makeText(this, "批注还没开启", Toast.LENGTH_SHORT).show();
                return;
            }
            r.run();
        });
        return b;
    }

    private void refresh() {
        boolean ov = canOverlay(this);
        boolean acc = isAccessibilityEnabled(this);
        status.setText("悬浮窗权限：" + (ov ? "已授予" : "未授予")
                + "　无障碍：" + (acc ? "已开启" : "未开启")
                + "\n系统手写笔开关：" + (AccessibilityHelper.penState(this) == 1 ? "已开启" : "未开启/被系统重置")
                + "\n批注状态：" + (Services.isRunning ? "运行中" : "未启动"));
        toggle.setText(Services.isRunning ? "停止批注" : "开始批注（全屏手写）");
    }

    public static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(c);
        return true;
    }

    public static boolean isAccessibilityEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName());
        } catch (Exception e) { return false; }
    }

    private void addTitle(LinearLayout r, String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(24);
        tv.setGravity(Gravity.START);
        tv.setPadding(0, 0, 0, dp(8));
        r.addView(tv);
    }

    private void addBody(LinearLayout r, String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setTextColor(Color.DKGRAY);
        tv.setPadding(0, dp(6), 0, dp(6));
        r.addView(tv);
    }

    private void addSep(LinearLayout r) {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(12), 0, dp(12));
        v.setLayoutParams(lp);
        v.setBackgroundColor(0x22000000);
        r.addView(v);
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
