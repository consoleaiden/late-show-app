package com.lateshow.app.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 源站按钮「测试中呼吸」态背景。
 *
 * 三层从下到上：
 * 1. 不透明圆面（ink800），保证图标下方是实体色，与原静态圆环一致；
 * 2. 内侧径向渐变：贴圆环处最深，向圆心递减、正中心完全透明（由边缘向内「扩散」的观感）；
 * 3. 1.5dp 描边圆环。
 *
 * 颜色 / 渐变峰值透明度都由外部 ValueAnimator 逐帧 set，二者共享同一个 fraction，
 * 因此填充与圆环严格同相位。
 *
 * 渐变用「白色 alpha 掩膜 + PorterDuffColorFilter(SRC_IN)」实现：
 * 掩膜几何只在尺寸变化时构建一次，改颜色时只重建一个轻量 ColorFilter，
 * 避免每帧新建 RadialGradient（native 分配）。
 */
public class RingBreathDrawable extends Drawable {

    /** 停靠点位置：0 = 圆心，1 = 圆环内侧 */
    private static final float[] STOPS = {0f, 0.35f, 0.64f, 0.88f, 0.985f};
    /** 对应停靠点的相对不透明度（再乘以运行时峰值 alpha），典型的凸形衰减：越贴环越浓 */
    private static final float[] PROFILE = {0f, 0.10f, 0.30f, 0.68f, 1f};

    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float strokeWidth;
    private final int faceColor;

    private int ringColor = 0xFFFF5A36;
    /** 渐变峰值不透明度 0..255（贴环处），由动画驱动呼吸 */
    private int glowAlpha = 0;
    /** Drawable.setAlpha 的整体透明度 */
    private int drawableAlpha = 255;

    private Shader mask;
    private float maskRadius;
    private PorterDuffColorFilter ringFilter;
    private int filterColor;

    public RingBreathDrawable(float strokeWidth, int faceColor) {
        this.strokeWidth = strokeWidth;
        this.faceColor = faceColor;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(strokeWidth);
    }

    /** 圆环与渐变填充的颜色（同一色，保证「环内是环色的延伸」） */
    public void setRingColor(int color) {
        if (ringColor == color) return;
        ringColor = color;
        invalidateSelf();
    }

    /** 渐变峰值不透明度：0 = 完全无填充，255 = 贴环处满色 */
    public void setGlowAlpha(int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        if (glowAlpha == a) return;
        glowAlpha = a;
        invalidateSelf();
    }

    public int getGlowAlpha() {
        return glowAlpha;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        float outer = Math.min(bounds.width(), bounds.height()) / 2f;
        float inner = Math.max(0f, outer - strokeWidth); // 圆环内侧半径
        if (inner <= 0f) {
            mask = null;
            maskRadius = 0f;
            return;
        }
        int[] colors = new int[PROFILE.length];
        for (int i = 0; i < PROFILE.length; i++) {
            colors[i] = (Math.round(PROFILE[i] * 255f) << 24) | 0x00FFFFFF;
        }
        mask = new RadialGradient(bounds.exactCenterX(), bounds.exactCenterY(), inner,
                colors, STOPS, Shader.TileMode.CLAMP);
        maskRadius = inner;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float outer = Math.min(b.width(), b.height()) / 2f;
        float centerRadius = outer - strokeWidth / 2f; // 描边中线

        // 1) 实体圆面
        facePaint.setColor(faceColor);
        facePaint.setAlpha(alpha(255));
        canvas.drawCircle(cx, cy, centerRadius, facePaint);

        // 2) 内侧径向渐变：由环向内变淡，圆心透明
        int ga = alpha(glowAlpha);
        if (ga > 0 && mask != null && maskRadius > 0f) {
            if (ringFilter == null || filterColor != ringColor) {
                ringFilter = new PorterDuffColorFilter(ringColor, PorterDuff.Mode.SRC_IN);
                filterColor = ringColor;
            }
            glowPaint.setShader(mask);
            glowPaint.setColorFilter(ringFilter);
            glowPaint.setAlpha(ga);
            canvas.drawCircle(cx, cy, maskRadius, glowPaint);
            glowPaint.setShader(null);
            glowPaint.setColorFilter(null);
        }

        // 3) 描边圆环（压在渐变之上，边缘干净）
        ringPaint.setColor(ringColor);
        ringPaint.setAlpha(alpha(255));
        canvas.drawCircle(cx, cy, centerRadius, ringPaint);
    }

    /** 叠加 Drawable 整体 alpha 后的实际不透明度 */
    private int alpha(int base) {
        return base * drawableAlpha / 255;
    }

    @Override
    public void setAlpha(int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        if (drawableAlpha == a) return;
        drawableAlpha = a;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // 颜色由 setRingColor 驱动，这里不做二次滤色
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
