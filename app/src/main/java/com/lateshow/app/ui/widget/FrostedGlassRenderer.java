package com.lateshow.app.ui.widget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.lateshow.app.R;

/**
 * 纯毛玻璃渲染引擎（被 {@link FrostedGlassBar} / {@link FrostedGlassPanel} 共用）。
 *
 * <p>效果就三件事：<b>采样背后内容 → 降采样模糊 → 叠一层白雾</b>。
 * 不含任何「液态玻璃」元素（无 AGSL / 无 SDF 边缘折射 / 无 7 通道色散 / 无边缘高光），
 * 因此不会出现彩虹边、边缘画面被「吸」进玻璃的扭曲感。
 *
 * <p>实现说明：
 *  <ul>
 *    <li><b>全 API 可用</b>：模糊走「画进 1/8~1/28 小图再双线性放大」的纯 Canvas 方案，
 *        不依赖 RenderEffect / RenderNode。后者在华为等部分 GPU 驱动上会被静默丢弃，
 *        导致玻璃整块变透明（Mate30 实测过），降采样则任何设备都能出磨砂；</li>
 *    <li><b>降采样倍数</b>由模糊强度推导：强度越大 → 小图越小 → 放大后越糊；</li>
 *    <li><b>采样节流</b>：滚动时最多 ~11fps 重采，静止时零开销（不主动 invalidate 自我续帧）；</li>
 *    <li><b>永不全透明</b>：采样失败时白雾层仍在，最坏情况只是「一层磨砂底色」。</li>
 *  </ul>
 *
 * <p>主题可调：模糊强度 / 白雾浓度 / 底色浓度 / 总开关全部由外部传入，
 * 宿主（Activity / Fragment）在主题变更时调用对应的 setter 即可实时生效。
 */
public class FrostedGlassRenderer {

    private static final String TAG = "FrostedGlass";

    /** 降采样倍数上下限：1/8（轻磨砂）~ 1/28（接近纯色雾面） */
    private static final int MIN_DOWNSCALE = 8;
    private static final int MAX_DOWNSCALE = 28;
    /** 模糊强度映射到的最大 sigma（dp），对应 MAX_DOWNSCALE */
    private static final float MAX_SIGMA_DP = 40f;

    /** 采样节流间隔：滚动时最多 ~11fps 刷新背景（磨砂无细节，低帧率看不出卡，但省 CPU） */
    private static final long CAPTURE_INTERVAL_MS = 90L;

    private final View host;

    private View backdrop;
    private Bitmap backdropBmp;
    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect drawRect = new Rect();

    private float cornerRadiusPx = -1f;
    private float blurSigma = 24f;          // px，<=0 表示用默认
    private float frost = 0.40f;            // 白雾浓度 0~1
    private float baseAlpha = 0.88f;        // 底色（玻璃关闭时 / 采样失败时）浓度 0~1
    private int tintColor = 0xFF101318;     // 白雾与底色用的颜色
    private boolean glassOn = true;

    private boolean capturePending = true;
    private long lastCapture;
    private int lastW, lastH;
    private final int[] srcPos = new int[2];
    private final int[] selfPos = new int[2];

    private final ViewTreeObserver.OnPreDrawListener preDraw = new ViewTreeObserver.OnPreDrawListener() {
        @Override public boolean onPreDraw() {
            step();
            return true;
        }
    };

    public FrostedGlassRenderer(@NonNull View host) {
        this.host = host;
        this.tintColor = ContextCompat.getColor(host.getContext(), R.color.barBg);
        Log.i(TAG, "init host=" + host.getClass().getSimpleName()
                + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " device=" + android.os.Build.MANUFACTURER + ' ' + android.os.Build.MODEL);
    }

    // -------------------- 参数 --------------------

    /** 采样源：玻璃模糊的就是它的内容（列表 / 网格）。 */
    public void setBackdrop(@NonNull View v) {
        backdrop = v;
        forceCapture();
    }

    public void setCornerRadiusPx(float px) {
        cornerRadiusPx = px;
        forceCapture();
    }

    /** 模糊强度（px sigma）。宿主由主题值换算后传入。 */
    public void setBlurSigma(float sigma) {
        blurSigma = sigma;
        forceCapture();     // 降采样倍数由 sigma 推导，小图尺寸会变，必须重采
    }

    /** 白雾浓度 0~1：越大越「奶」，越小越透。 */
    public void setFrost(float f) {
        frost = Math.max(0f, Math.min(1f, f));
        forceCapture();
    }

    /** 底色浓度 0~1：玻璃关闭、或采样失败时保证玻璃可见。 */
    public void setBaseAlpha(float a) {
        baseAlpha = Math.max(0f, Math.min(1f, a));
        refreshBackground();
    }

    /** 白雾 / 底色使用的颜色（主题方案的 surface，或内置 barBg）。 */
    public void setTintColor(int color) {
        tintColor = color;
        refreshBackground();
        forceCapture();
    }

    /** 毛玻璃总开关：关闭后只保留一层底色，不再采样背后内容（省电、且没有模糊感）。 */
    public void setGlassOn(boolean on) {
        if (glassOn == on) return;
        glassOn = on;
        forceCapture();
        host.invalidate();
    }

    /** 强制重采背景（显示/隐藏、尺寸变化、滚动停止时调用） */
    public void forceCapture() {
        capturePending = true;
        if (host.getWidth() > 0) host.invalidate();
    }

    // -------------------- 生命周期 --------------------

    public void attach() {
        host.getViewTreeObserver().addOnPreDrawListener(preDraw);
        refreshBackground();
    }

    public void detach() {
        try {
            host.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        } catch (Exception ignored) { }
        if (backdropBmp != null && !backdropBmp.isRecycled()) {
            backdropBmp.recycle();
            backdropBmp = null;
        }
    }

    /** 尺寸变化后圆角与背景要重算 */
    public void onSizeChanged() {
        refreshBackground();
        forceCapture();
    }

    /**
     * 兜底底色：圆角矩形。宿主把它设为 background（View 背景先于 dispatchDraw 绘制），
     * 这样即使采样失败、或用户关掉毛玻璃，玻璃栏也不会「透明消失」。
     */
    public Drawable backgroundDrawable() {
        float d = host.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(applyAlpha(tintColor, glassOn ? Math.min(baseAlpha, 0.6f) : baseAlpha));
        float r = cornerRadiusPx > 0 ? cornerRadiusPx
                : (host.getHeight() > 0 ? host.getHeight() / 2f : 24f * d);
        bg.setCornerRadius(r);
        return bg;
    }

    public void refreshBackground() {
        host.setBackground(backgroundDrawable());
    }

    // -------------------- 内部实现 --------------------

    /** 每帧前置：节流采样背后内容（不主动 invalidate，避免静止空转） */
    private void step() {
        int w = host.getWidth(), h = host.getHeight();
        if (!glassOn || backdrop == null || w <= 0 || h <= 0) return;
        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            capturePending = true;
        }
        long now = SystemClock.uptimeMillis();
        if (capturePending || now - lastCapture >= CAPTURE_INTERVAL_MS) {
            captureDownsampled(w, h);
            lastCapture = now;
            capturePending = false;
        }
    }

    /** 实际降采样倍数：模糊强度越大 → 小图越小 → 放大后越糊 */
    private int downscale() {
        float d = Math.max(1f, host.getResources().getDisplayMetrics().density);
        float dp = Math.max(0f, blurSigma > 0f ? blurSigma : 9f * d) / d;
        int span = MAX_DOWNSCALE - MIN_DOWNSCALE;
        int extra = Math.round(span * Math.min(1f, dp / MAX_SIGMA_DP));
        return MIN_DOWNSCALE + Math.max(0, Math.min(span, extra));
    }

    /** 把背后内容按 1/downscale 画进小图，绘制时双线性放大 → 磨砂 */
    private void captureDownsampled(int w, int h) {
        final int ds = downscale();
        int sw = Math.max(1, w / ds);
        int sh = Math.max(1, h / ds);
        if (backdropBmp == null || backdropBmp.isRecycled()
                || backdropBmp.getWidth() != sw || backdropBmp.getHeight() != sh) {
            if (backdropBmp != null && !backdropBmp.isRecycled()) backdropBmp.recycle();
            try {
                backdropBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            } catch (Throwable e) {
                backdropBmp = null;
                Log.w(TAG, "downsample bitmap alloc failed: " + e);
                return;
            }
        }
        Canvas c = new Canvas(backdropBmp);
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        relPos();
        float s = (float) sw / w;
        c.scale(s, s);
        c.translate(srcPos[0] - selfPos[0], srcPos[1] - selfPos[1]);
        backdrop.draw(c);
    }

    private void relPos() {
        backdrop.getLocationInWindow(srcPos);
        host.getLocationInWindow(selfPos);
    }

    /** 在宿主 dispatchDraw 里、子 View 之前调用：画「模糊背景 + 白雾」 */
    public void draw(@NonNull Canvas canvas) {
        int w = host.getWidth(), h = host.getHeight();
        if (w <= 0 || h <= 0) return;
        if (!glassOn) return;   // 关闭时只靠 background 底色

        if (backdropBmp != null && !backdropBmp.isRecycled()) {
            drawRect.set(0, 0, w, h);
            canvas.drawBitmap(backdropBmp, null, drawRect, drawPaint);
        }
        // 白雾：磨砂的关键 —— 把内容「压」成半透明白，玻璃才有实体感
        if (frost > 0.001f) {
            float radius = cornerRadiusPx > 0 ? cornerRadiusPx : h / 2f;
            frostPaint.setColor(applyAlpha(tintColor, Math.min(0.92f, frost)));
            canvas.drawRoundRect(0f, 0f, (float) w, (float) h, radius, radius, frostPaint);
        }
    }

    private static int applyAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (255 * alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
