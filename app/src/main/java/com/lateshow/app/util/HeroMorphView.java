package com.lateshow.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 详情页一镜到底的「容器形变」层。
 *
 * <p>做法：把一个位图按「插值后的圆角矩形」裁切，并在矩形内保持 centerCrop。
 * 矩形从 A 连续变到 B 的同时，可见的画面窗口也跟着连续变化 —— 观感是画面在放大/缩小并
 * 轻微平移，而不是两张图交叉淡化。这样做的好处：
 * <ul>
 *   <li>任何中间帧画面都严丝合缝铺满矩形，不会出现透明缝或拉伸变形；</li>
 *   <li>终点帧的裁切结果与 ImageView#centerCrop 完全一致，撤掉这一层时没有跳变；</li>
 *   <li>只需一张位图、一次绘制，开销与普通 ImageView 相当。</li>
 * </ul>
 *
 * <p>坐标系为添加它的父容器坐标（{@link #setFrames} 传入的矩形也按父容器坐标）。
 */
public class HeroMorphView extends View {

    private Bitmap bitmap;
    private final RectF from = new RectF();
    private final RectF to = new RectF();
    private final RectF cur = new RectF();
    private float fromRadius, toRadius;
    private float progress;

    private final Path clipPath = new Path();
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int placeholderColor = 0xFF171B21;   // 位图未就绪时的占位色（与海报占位块一致）

    public HeroMorphView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /** 设置形变的两端（矩形 + 圆角半径），随后用 {@link #setProgress} 驱动 */
    public void setFrames(RectF from, float fromRadius, RectF to, float toRadius) {
        this.from.set(from);
        this.to.set(to);
        this.fromRadius = fromRadius;
        this.toRadius = toRadius;
        apply();
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        invalidate();
    }

    public boolean hasBitmap() {
        return bitmap != null && !bitmap.isRecycled();
    }

    /** 位图未解码完成时的占位色：先让「卡片形状」动起来，图片到位后无缝替换 */
    public void setPlaceholderColor(int color) {
        this.placeholderColor = color;
    }

    /** 0 = 起点矩形，1 = 终点矩形 */
    public void setProgress(float p) {
        this.progress = p;
        apply();
    }

    private void apply() {
        float p = progress;
        cur.set(
                from.left + (to.left - from.left) * p,
                from.top + (to.top - from.top) * p,
                from.right + (to.right - from.right) * p,
                from.bottom + (to.bottom - from.bottom) * p);
        float r = fromRadius + (toRadius - fromRadius) * p;
        clipPath.reset();
        if (r > 0.01f) clipPath.addRoundRect(cur, r, r, Path.Direction.CW);
        else clipPath.addRect(cur, Path.Direction.CW);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cur.isEmpty()) return;
        if (!hasBitmap()) {
            fillPaint.setColor(placeholderColor);
            canvas.drawPath(clipPath, fillPaint);
            return;
        }
        int save = canvas.save();
        canvas.clipPath(clipPath);
        // centerCrop：等比放大到刚好铺满当前矩形，再居中
        float s = Math.max(cur.width() / bitmap.getWidth(), cur.height() / bitmap.getHeight());
        float dx = cur.centerX() - bitmap.getWidth() * s / 2f;
        float dy = cur.centerY() - bitmap.getHeight() * s / 2f;
        matrix.setScale(s, s);
        matrix.postTranslate(dx, dy);
        canvas.drawBitmap(bitmap, matrix, paint);
        canvas.restoreToCount(save);
    }
}
