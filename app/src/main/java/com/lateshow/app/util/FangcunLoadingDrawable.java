package com.lateshow.app.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.AnimationUtils;

import androidx.core.content.ContextCompat;

import com.lateshow.app.R;

/**
 * 方寸品牌加载动画（fangcun-loading.svg 的 Canvas 复刻，3.2s 无缝循环）。
 *
 * <p>一轮编排（fraction 0→1）：朱砂三角从起点绘出（0→38%）→ 完整停顿（38→52%）
 * → 收缩抹除成一枚残线点（52→86%，scale 1→0.04 + 尾段擦除）→ 点停留（86→92%）
 * → 隐形复位（92→100%）；方框每轮在 20→32% 间转 90°，四轮累计 360° 回正。
 *
 * <p>无背景色（用户要求）：透明底，方框颜色随主题翻转（brandMarkStroke 浅色=暖墨 /
 * 深色=宣白），三角恒为品牌朱砂 —— 明暗主题下都不违和。线宽不随收缩缩放
 * （路径缩放 + 固定笔画宽，等价 SVG 的 non-scaling-stroke）。
 *
 * <p>用法：{@link #start()} 在宿主可见时调用（首次绘制也会自动起跑兜底），
 * {@link #pause()} 在宿主隐藏时调用（离屏不空转，画面停在静止完整态）。
 */
public final class FangcunLoadingDrawable extends Drawable {

    /** 一轮时长（与品牌 SVG 的 3.2s 循环一致） */
    private static final long PERIOD_MS = 3200L;

    private final Paint framePaint;
    private final Paint triPaint;
    private final Path framePath = new Path();
    private final Path triPath = new Path();
    private final Path segment = new Path();
    private final Matrix tmp = new Matrix();

    /** 循环驱动器：线性 0→1，阶段缓动在 draw 内自己算 */
    private final android.animation.ValueAnimator anim = newAnim();

    private float fraction;   // 当前循环内 0→1
    private int cycles;       // 已完成循环数（方框每轮 +90°）
    private boolean paused;   // 宿主显式暂停后，draw() 不再自动起跑

    public FangcunLoadingDrawable(Context ctx) {
        framePaint = strokePaint(ContextCompat.getColor(ctx, R.color.brandMarkStroke), 1.5f);
        triPaint = strokePaint(ContextCompat.getColor(ctx, R.color.brandTri), 2.2f);

        // 方框：x64 y64 w72 h72 rx5（设计坐标 200x200，同 SVG viewBox）
        framePath.addRoundRect(64f, 64f, 136f, 136f, 5f, 5f, Path.Direction.CW);
        // 三角（正向前向坐标；绘制时整体 rotate(90) 成播放键朝向）
        triPath.moveTo(102.7f, 86.8f);
        triPath.lineTo(113.3f, 105.2f);
        triPath.quadTo(116f, 110f, 110.5f, 110f);
        triPath.lineTo(89.5f, 110f);
        triPath.quadTo(84f, 110f, 86.7f, 105.2f);
        triPath.lineTo(97.3f, 86.8f);
        triPath.quadTo(100f, 82f, 102.7f, 86.8f);
        triPath.close();
    }

    private static Paint strokePaint(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private android.animation.ValueAnimator newAnim() {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(PERIOD_MS);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.setInterpolator(null);
        a.addUpdateListener(an -> {
            fraction = (float) an.getAnimatedValue();
            invalidateSelf();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationRepeat(android.animation.Animator animation) {
                cycles++;
            }
        });
        return a;
    }

    /** 启动循环动画（幂等） */
    public void start() {
        paused = false;
        if (!anim.isRunning()) anim.start();
    }

    /** 暂停并回到静止完整态（宿主隐藏时调用，避免离屏空转） */
    public void pause() {
        paused = true;
        anim.cancel();
        fraction = 0f;
        invalidateSelf();
    }

    /** 平滑插值（近似 SVG cubic-bezier(.45,0,.25,1) 的缓入缓出） */
    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /** 区间 [a,b) 内的归一化进度，不在区间返回 -1 */
    private static float in(float f, float a, float b) {
        return (f >= a && f < b) ? (f - a) / (b - a) : -1f;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        // 宿主忘了 start() 时首次绘制自动起跑（任何接入点都有动画）
        if (!anim.isRunning() && !paused) {
            // 用真实墙钟对齐 phase，避免两次创建的实例同帧同相位
            fraction = ((AnimationUtils.currentAnimationTimeMillis() - (long) (cycles * PERIOD_MS))
                    % PERIOD_MS) / (float) PERIOD_MS;
            anim.start();
        }

        float scale = Math.min(b.width(), b.height()) / 200f * 0.92f;
        float cx = b.centerX(), cy = b.centerY();

        // ---- 方框：每轮 20→32% 顺时针转 90°，四轮 360° 回正 ----
        float rot = cycles * 90f;
        float rSpin = in(fraction, 0.20f, 0.32f);
        if (rSpin >= 0) rot += 90f * ease(rSpin);
        else if (fraction >= 0.32f) rot += 90f;

        // ---- 三角：绘出 → 停顿 → 收缩抹除 → 点停留 → 隐形复位 ----
        float drawF = 0f, scaleF = 1f, visible = 1f;
        float p = in(fraction, 0f, 0.38f);
        if (p >= 0) {                       // 绘出
            drawF = ease(p);
        } else if (fraction < 0.52f) {      // 完整停顿
            drawF = 1f;
        } else if (fraction < 0.86f) {      // 收缩抹除：scale 1→0.04，可见段 1→0.01
            float q = ease(in(fraction, 0.52f, 0.86f));
            scaleF = 1f - 0.96f * q;
            drawF = 1f - 0.99f * q;
        } else if (fraction < 0.92f) {      // 点停留
            scaleF = 0.04f;
            drawF = 0.01f;
        } else {                            // 隐形复位（不画，scale 回 1）
            visible = 0f;
            scaleF = 0.04f + 0.96f * ease(in(fraction, 0.92f, 1f));
        }

        canvas.save();
        canvas.translate(cx - 100f * scale, cy - 100f * scale);
        canvas.scale(scale, scale);
        // 方框（旋转中心 100,100）
        canvas.save();
        canvas.rotate(rot, 100f, 100f);
        canvas.drawPath(framePath, framePaint);
        canvas.restore();
        // 三角：整体 rotate(90)（尖朝右）→ scale 收缩（线宽恒定）→ 取前 drawF 段
        if (visible > 0) {
            tmp.reset();
            tmp.postScale(scaleF, scaleF, 100f, 100f);
            Path scaled = new Path();
            triPath.transform(tmp, scaled);
            PathMeasure m = new PathMeasure(scaled, false);
            segment.rewind();
            float stop = Math.max(0.5f, m.getLength() * Math.max(0.01f, drawF));
            m.getSegment(0f, stop, segment, true);
            canvas.save();
            canvas.rotate(90f, 100f, 100f);
            canvas.drawPath(segment, triPaint);
            canvas.restore();
        }
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) {
        framePaint.setAlpha(alpha);
        triPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public int getAlpha() { return triPaint.getAlpha(); }

    @Override public void setColorFilter(ColorFilter cf) {
        framePaint.setColorFilter(cf);
        triPaint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
