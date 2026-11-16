package com.lateshow.app.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 色盘取色器：饱和/明度方区 + 色相条 + 透明度条 + 当前色预览。
 *
 * 与旧版「预设色板」的区别：可任选任意颜色与透明度，拖动实时回调 onColorChanged，
 * 供编辑器边调边看效果（配色预览区同步刷新）。
 */
public class ColorPickerView extends View {

    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF previewRect = new RectF();
    private final RectF svRect = new RectF();
    private final RectF hueRect = new RectF();
    private final RectF alphaRect = new RectF();

    private int pad, previewH, svSize, barH, gap;

    private float hue = 120f;
    private float sat = 1f;
    private float val = 1f;
    private float alpha = 1f;
    private int currentColor = Color.WHITE;

    private OnColorChangedListener listener;

    public ColorPickerView(Context context) {
        super(context);
    }

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnColorChangedListener(OnColorChangedListener l) {
        this.listener = l;
    }

    public int getColor() {
        return currentColor;
    }

    /** 用 ARGB 颜色初始化选色位置（透明度单独记录） */
    public void setColor(int color) {
        float[] hsv = new float[3];
        Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color), hsv);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        alpha = Color.alpha(color) / 255f;
        currentColor = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        pad = dp(2);
        previewH = dp(42);
        barH = dp(22);
        gap = dp(10);
        svSize = Math.max(dp(120), width - pad * 2);
        int height = pad * 2 + previewH + gap + svSize + gap + barH + gap + barH;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float x0 = pad, x1 = w - pad;
        float y = pad;
        previewRect.set(x0, y, x1, y + previewH);
        y += previewH + gap;
        svRect.set(x0, y, x1, y + svSize);
        y += svSize + gap;
        hueRect.set(x0, y, x1, y + barH);
        y += barH + gap;
        alphaRect.set(x0, y, x1, y + barH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 当前色预览
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(currentColor);
        canvas.drawRoundRect(previewRect, dp(8), dp(8), paint);

        // 饱和/明度方区：横向 白→纯色相，纵向 透明→黑
        int hueColor = Color.HSVToColor(new float[]{hue, 1f, 1f});
        paint.setShader(new LinearGradient(svRect.left, 0, svRect.right, 0,
                Color.WHITE, hueColor, Shader.TileMode.CLAMP));
        canvas.drawRect(svRect, paint);
        paint.setShader(new LinearGradient(0, svRect.top, 0, svRect.bottom,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        canvas.drawRect(svRect, paint);
        paint.setShader(null);

        // 色相条
        int[] hueColors = new int[7];
        for (int i = 0; i < 7; i++) {
            hueColors[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
        }
        paint.setShader(new LinearGradient(hueRect.left, 0, hueRect.right, 0,
                hueColors, null, Shader.TileMode.CLAMP));
        canvas.drawRect(hueRect, paint);
        paint.setShader(null);

        // 透明度条：棋盘格底 + 当前色 0→255 渐变
        drawChecker(canvas, alphaRect);
        int r = Color.red(currentColor), g = Color.green(currentColor), b = Color.blue(currentColor);
        paint.setShader(new LinearGradient(alphaRect.left, 0, alphaRect.right, 0,
                Color.argb(0, r, g, b), Color.argb(255, r, g, b),
                Shader.TileMode.CLAMP));
        canvas.drawRect(alphaRect, paint);
        paint.setShader(null);

        // SV 指示器
        float cx = svRect.left + sat * svRect.width();
        float cy = svRect.top + (1f - val) * svRect.height();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.BLACK);
        canvas.drawCircle(cx, cy, dp(7), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, dp(6), paint);

        // 色相/透明度指示线
        drawBarCursor(canvas, hueRect, hue / 360f);
        drawBarCursor(canvas, alphaRect, alpha);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawBarCursor(Canvas canvas, RectF rect, float fraction) {
        float x = rect.left + fraction * rect.width();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(Color.WHITE);
        canvas.drawLine(x, rect.top, x, rect.bottom, paint);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(Color.BLACK);
        canvas.drawLine(x, rect.top, x, rect.bottom, paint);
    }

    private void drawChecker(Canvas canvas, RectF rect) {
        float cell = dp(6);
        paint.setShader(null);
        for (int row = 0; row * cell < rect.height() + cell; row++) {
            for (int col = 0; col * cell < rect.width() + cell; col++) {
                paint.setColor(((row + col) & 1) == 0 ? 0xFFDDDDDD : 0xFFFFFFFF);
                float left = rect.left + col * cell;
                float top = rect.top + row * cell;
                canvas.drawRect(left, top,
                        Math.min(left + cell, rect.right),
                        Math.min(top + cell, rect.bottom), paint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP) {
            return super.onTouchEvent(e);
        }
        float x = e.getX(), y = e.getY();
        boolean handled = true;
        if (svRect.contains(x, y)) {
            sat = clamp((x - svRect.left) / svRect.width());
            val = clamp(1f - (y - svRect.top) / svRect.height());
        } else if (hueRect.contains(x, y)) {
            hue = clamp((x - hueRect.left) / hueRect.width()) * 360f;
        } else if (alphaRect.contains(x, y)) {
            alpha = clamp((x - alphaRect.left) / alphaRect.width());
        } else {
            handled = false;
        }
        if (handled) {
            updateColor();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void updateColor() {
        currentColor = Color.HSVToColor(Math.round(alpha * 255),
                new float[]{hue, sat, val});
        invalidate();
        if (listener != null) listener.onColorChanged(currentColor);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
