package com.aclaniakea.penpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 自选颜色面板（参考 macOS 取色器布局）：
 *   上：饱和度/明度二维取色区（任意颜色）
 *   中：色相条
 *   下：透明度条（棋盘格）
 *   底：常用色块 + 十六进制输入 + 取消/完成
 */
public class ColorPicker extends LinearLayout {

    private static final String TAG = "PenPad";

    public interface Listener {
        void onColorChanged(int color);
        void onColorConfirmed(int color);
        void onColorCancelled();
    }

    private static final int[] PRESETS = {
            0xFF000000, 0xFF808080, 0xFFFFFFFF, 0xFFFF3B30,
            0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE,
            0xFF0A84FF, 0xFF5E5CE6, 0xFFAF52DE, 0xFFFF2D55,
            0xFF8E8E93, 0xFF5AC8FA, 0xFFA2845E, 0xFFFFD60A
    };

    private final float d;
    private final boolean night;
    private final PickerView picker;
    private final EditText hex;
    private Listener listener;
    private boolean syncingHex;

    public ColorPicker(Context c, int initialColor, Listener l) {
        super(c);
        this.listener = l;
        d = getResources().getDisplayMetrics().density;
        night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        setOrientation(VERTICAL);
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setBackground(ToolBar.roundBg(night ? 0xF2202024 : 0xF2FFFFFF, 18 * d,
                night ? 0x33FFFFFF : 0x14000000));

        TextView title = new TextView(c);
        title.setText("自选颜色");
        title.setTextSize(13);
        title.setTextColor(night ? 0xFFE8E8E8 : 0xFF1A1A1A);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(2), 0, 0, dp(6));
        addView(title, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        picker = new PickerView(c, initialColor);
        picker.setOnChange(new PickerView.OnChange() {
            @Override public void onChange(int color) {
                syncHex(color);
                if (listener != null) listener.onColorChanged(color);
            }
        });
        addView(picker, new LayoutParams(dp(304), dp(352)));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        hex = new EditText(c);
        hex.setSingleLine(true);
        hex.setTextSize(13);
        hex.setInputType(InputType.TYPE_CLASS_TEXT);
        hex.setHint("#RRGGBB");
        hex.setTextColor(night ? 0xFFE8E8E8 : 0xFF1A1A1A);
        hex.setBackgroundColor(0x00000000);
        hex.setPadding(dp(6), dp(4), dp(6), dp(4));
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (syncingHex) return;
                Integer c = parseHex(s.toString());
                if (c == null) return;
                picker.setColor(c, false);
            }
        });
        row.addView(hex, new LayoutParams(0, dp(38), 1f));

        row.addView(button("取消", new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onColorCancelled();
            }
        }));
        row.addView(button("完成", new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onColorConfirmed(picker.color());
            }
        }));
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        syncHex(picker.color());
    }

    private TextView button(String text, final Runnable r) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(night ? 0xFFE8E8E8 : 0xFF1A1A1A);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(9), dp(14), dp(9));
        tv.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        return tv;
    }

    private void syncHex(int color) {
        syncingHex = true;
        if (hex != null) {
            String s = String.format(Locale.US, "#%08X", color);
            hex.setText(s);
            hex.setSelection(s.length());
        }
        syncingHex = false;
    }

    private static Integer parseHex(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        try {
            if (t.length() == 6) return (int) (0xFF000000L | Long.parseLong(t, 16));
            if (t.length() == 8) return (int) Long.parseLong(t, 16);
        } catch (Exception ignored) { }
        return null;
    }

    private int dp(float v) { return (int) (v * d + 0.5f); }

    // ================================================================ 取色区
    static class PickerView extends View {

        interface OnChange { void onChange(int color); }

        private final float d;
        private final boolean night;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clear = new Paint();
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF svRect = new RectF();
        private final RectF hueRect = new RectF();
        private final RectF alphaRect = new RectF();
        private final RectF tmp = new RectF();

        private float hue = 0f, sat = 0f, val = 0f;
        private int alpha = 255;
        private int region = 0;       // 1=SV 2=hue 3=alpha
        private OnChange change;

        PickerView(Context c, int initial) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            night = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            shade.setStyle(Paint.Style.FILL);
            setColor(initial, true);
        }

        void setOnChange(OnChange c) { change = c; }

        int color() { return Color.HSVToColor(alpha, new float[]{hue, sat, val}); }

        void setColor(int argb, boolean silent) {
            int a = Color.alpha(argb);
            float[] hsv = new float[3];
            Color.colorToHSV(argb, hsv);
            if (hsv[1] > 0.0001f || hsv[2] > 0.0001f) {
                hue = hsv[0];
                sat = hsv[1];
                val = hsv[2];
            } else {
                val = hsv[2];
                sat = 0f;
            }
            alpha = a;
            invalidate();
            if (!silent && change != null) change.onChange(color());
        }

        private float pad() { return 10 * d; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float p0 = pad();
            float svH = 138 * d;
            svRect.set(p0, 4 * d, w - p0, 4 * d + svH);
            float hueTop = svRect.bottom + 14 * d;
            hueRect.set(p0, hueTop, w - p0, hueTop + 22 * d);
            float alphaTop = hueRect.bottom + 12 * d;
            alphaRect.set(p0, alphaTop, w - p0, alphaTop + 22 * d);
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = 8 * d;

            // ---- 饱和度/明度
            int hueColor = Color.HSVToColor(new float[]{hue, 1f, 1f});
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(svRect.left, svRect.top, svRect.right, svRect.top,
                    Color.WHITE, hueColor, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(svRect, radius, radius, p);
            p.setShader(new LinearGradient(svRect.left, svRect.top, svRect.left, svRect.bottom,
                    Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(svRect, radius, radius, p);
            p.setShader(null);

            // ---- 色相条
            int[] rainbow = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                    0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
            p.setShader(new LinearGradient(hueRect.left, hueRect.top, hueRect.right, hueRect.top,
                    rainbow, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(hueRect, 10 * d, 10 * d, p);
            p.setShader(null);

            // ---- 透明度条（先画棋盘格）
            canvas.save();
            float ar = 10 * d;
            tmp.set(alphaRect);
            canvas.clipRect(tmp);
            drawCheckerboard(canvas, tmp);
            canvas.restore();
            int opaque = color() | 0xFF000000;
            p.setShader(new LinearGradient(alphaRect.left, alphaRect.top,
                    alphaRect.right, alphaRect.top, opaque & 0x00FFFFFF, opaque, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(alphaRect, ar, ar, p);
            p.setShader(null);

            // ---- 指示器
            float sx = svRect.left + sat * svRect.width();
            float sy = svRect.top + (1f - val) * svRect.height();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xFFFFFFFF);
            canvas.drawCircle(sx, sy, 8 * d, p);
            p.setColor(0x66000000);
            canvas.drawCircle(sx, sy, 9.6f * d, p);

            float hx = hueRect.left + (hue / 360f) * hueRect.width();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            canvas.drawCircle(hx, hueRect.centerY(), 9 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(0x55000000);
            canvas.drawCircle(hx, hueRect.centerY(), 9 * d, p);

            float ax = alphaRect.left + (alpha / 255f) * alphaRect.width();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            canvas.drawCircle(ax, alphaRect.centerY(), 9 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(0x55000000);
            canvas.drawCircle(ax, alphaRect.centerY(), 9 * d, p);

            // ---- 常用色块
            float cell = 36 * d;
            float sw = Math.min(cell * 0.72f, (getWidth() - 2 * pad()) / 8f * 0.72f);
            float stepX = (getWidth() - 2 * pad()) / 8f;
            float top = alphaRect.bottom + 16 * d;
            for (int i = 0; i < PRESETS.length; i++) {
                int col = i % 8, rowIdx = i / 8;
                float cx = pad() + stepX * col + stepX / 2f;
                float cy = top + rowIdx * (sw + 12 * d) + sw / 2f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(PRESETS[i]);
                canvas.drawCircle(cx, cy, sw / 2f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1 * d);
                p.setColor(0x33000000);
                canvas.drawCircle(cx, cy, sw / 2f, p);
            }

            // ---- 预览
            float py = top + 2 * (sw + 12 * d) + 4 * d;
            tmp.set(pad(), py, getWidth() - pad(), Math.min(getHeight() - 2 * d, py + 26 * d));
            if (tmp.bottom > tmp.top) {
                canvas.save();
                canvas.clipRect(tmp);
                drawCheckerboard(canvas, tmp);
                canvas.restore();
                p.setStyle(Paint.Style.FILL);
                p.setColor(color());
                canvas.drawRoundRect(tmp, 6 * d, 6 * d, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1 * d);
                p.setColor(night ? 0x33FFFFFF : 0x1F000000);
                canvas.drawRoundRect(tmp, 6 * d, 6 * d, p);
            }
        }

        private void drawCheckerboard(Canvas canvas, RectF r) {
            float cell = 8 * d;
            p.setStyle(Paint.Style.FILL);
            int col = 0;
            for (float y = r.top; y < r.bottom; y += cell) {
                for (float x = r.left; x < r.right; x += cell) {
                    p.setColor((col++ % 2 == 0) ? 0xFFFFFFFF : 0xFFD8D8D8);
                    canvas.drawRect(x, y, Math.min(x + cell, r.right), Math.min(y + cell, r.bottom), p);
                }
                col++;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (svRect.contains(x, y)) region = 1;
                    else if (hueRect.contains(x, y)) region = 2;
                    else if (alphaRect.contains(x, y)) region = 3;
                    else { region = hitPreset(x, y); }
                    apply(x, y);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (region > 0 && region <= 3) apply(x, y);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    region = 0;
                    return true;
                default:
                    return true;
            }
        }

        private void apply(float x, float y) {
            try {
                if (region == 1) {
                    float s = (x - svRect.left) / Math.max(1f, svRect.width());
                    float v = 1f - (y - svRect.top) / Math.max(1f, svRect.height());
                    sat = Math.max(0f, Math.min(1f, s));
                    val = Math.max(0f, Math.min(1f, v));
                } else if (region == 2) {
                    float t = (x - hueRect.left) / Math.max(1f, hueRect.width());
                    hue = Math.max(0f, Math.min(359.9f, t * 360f));
                } else if (region == 3) {
                    float t = (x - alphaRect.left) / Math.max(1f, alphaRect.width());
                    alpha = Math.round(Math.max(0f, Math.min(1f, t)) * 255f);
                }
                invalidate();
                if (change != null) change.onChange(color());
            } catch (Exception ex) {
                Log.w(TAG, "picker apply fail", ex);
            }
        }

        private int hitPreset(float x, float y) {
            float cellW = (getWidth() - 2 * pad()) / 8f;
            float sw = Math.min(36 * d * 0.72f, cellW * 0.72f);
            float top = alphaRect.bottom + 16 * d;
            for (int i = 0; i < PRESETS.length; i++) {
                int col = i % 8, rowIdx = i / 8;
                float cx = pad() + cellW * col + cellW / 2f;
                float cy = top + rowIdx * (sw + 12 * d) + sw / 2f;
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy < (sw / 2f + 4 * d) * (sw / 2f + 4 * d)) {
                    setColor(PRESETS[i], false);
                    return 0;
                }
            }
            return 0;
        }
    }
}
