package com.lateshow.app.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 方寸品牌字标的一次性「手绘」Drawable（开屏编排用，fangcun-logo 几何，160 设计坐标）。
 *
 * <p>progress 0→1：宣白方框描线（0→45%）→ 朱砂三角绘出（40→95%，与方框尾段轻微重叠，
 * 节奏更连贯）→ 完整字标。颜色由构造传入（开屏固定暗场：宣白 + 朱砂）。
 */
public final class FangcunMarkDrawable extends Drawable {

    private final Paint framePaint;
    private final Paint triPaint;
    private final Path framePath = new Path();
    private final Path triPath = new Path();
    private final Path segment = new Path();

    /** 0→1 的绘制进度（SplashActivity 用 ValueAnimator 驱动） */
    private float progress;

    public FangcunMarkDrawable(int frameColor, int triColor) {
        framePaint = strokePaint(frameColor, 2f);
        triPaint = strokePaint(triColor, 2.8f);

        // 方框：x16 y16 w128 h128 rx9
        framePath.addRoundRect(16f, 16f, 144f, 144f, 9f, 9f, Path.Direction.CW);
        triPath.moveTo(103.45f, 84.93f);
        triPath.lineTo(70.67f, 103.56f);
        triPath.quadTo(62.22f, 108.33f, 62.22f, 98.56f);
        triPath.lineTo(62.22f, 61.33f);
        triPath.quadTo(62.22f, 51.56f, 70.67f, 56.36f);
        triPath.lineTo(103.45f, 75.2f);
        triPath.quadTo(112f, 80f, 103.45f, 84.93f);
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

    public void setProgress(float t) {
        progress = Math.max(0f, Math.min(1f, t));
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float scale = Math.min(b.width(), b.height()) / 160f * 0.92f;
        canvas.save();
        canvas.translate(b.centerX() - 80f * scale, b.centerY() - 80f * scale);
        canvas.scale(scale, scale);
        // 方框：0→45% 描线
        float fFrame = Math.min(1f, progress / 0.45f);
        if (fFrame > 0) {
            PathMeasure m = new PathMeasure(framePath, false);
            segment.rewind();
            m.getSegment(0f, m.getLength() * fFrame, segment, true);
            canvas.drawPath(segment, framePaint);
        }
        // 三角：40→95% 绘出
        float fTri = (progress - 0.40f) / 0.55f;
        if (fTri > 0) {
            PathMeasure m = new PathMeasure(triPath, false);
            segment.rewind();
            m.getSegment(0f, m.getLength() * Math.min(1f, fTri), segment, true);
            canvas.drawPath(segment, triPaint);
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
