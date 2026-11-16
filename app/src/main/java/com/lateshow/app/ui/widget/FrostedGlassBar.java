package com.lateshow.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 毛玻璃栏（底部导航 / 首页顶栏共用）。
 *
 * 绘制逻辑全部在 {@link FrostedGlassRenderer}：采样背后内容 → 降采样模糊 → 白雾。
 * 本类只负责「圆角裁切 + 阴影 + 把玻璃层画在子 View 之下」。
 */
public class FrostedGlassBar extends LinearLayout {

    private final FrostedGlassRenderer glass;
    private final android.graphics.Path clipPath = new android.graphics.Path();
    private float cornerPx = -1f;

    public FrostedGlassBar(Context context) {
        super(context);
        glass = new FrostedGlassRenderer(this);
        init();
    }

    public FrostedGlassBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        glass = new FrostedGlassRenderer(this);
        init();
    }

    public FrostedGlassBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        glass = new FrostedGlassRenderer(this);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, @NonNull Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx());
            }
        });
        setClipToOutline(true);
        setElevation(6f * getResources().getDisplayMetrics().density);
        glass.refreshBackground();
    }

    private float cornerPx() {
        float d = getResources().getDisplayMetrics().density;
        if (cornerPx > 0) return cornerPx;
        return getHeight() > 0 ? Math.min(getHeight() / 2f, 24f * d) : 24f * d;
    }

    /** 设置采样源：玻璃模糊的就是它的内容（主界面的内容容器）。 */
    public void setBackdropSource(View source) {
        glass.setBackdrop(source);
    }

    public void setGlassCornerRadius(float px) {
        cornerPx = px;
        glass.setCornerRadiusPx(px);
        glass.refreshBackground();
        invalidateOutline();
    }

    public void setBlurSigma(float sigma) {
        glass.setBlurSigma(sigma);
    }

    public void setFrost(float f) {
        glass.setFrost(f);
    }

    public void setBaseAlpha(float a) {
        glass.setBaseAlpha(a);
    }

    public void setTintColor(int color) {
        glass.setTintColor(color);
    }

    /** 毛玻璃总开关：关闭后只保留底色，不再模糊背后内容。 */
    public void setGlassOn(boolean on) {
        glass.setGlassOn(on);
    }

    /** 显示时强制重采背景（位置/内容刚有意义时） */
    public void refreshGlass() {
        glass.forceCapture();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        glass.attach();
    }

    @Override
    protected void onDetachedFromWindow() {
        glass.detach();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            glass.onSizeChanged();
            invalidateOutline();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        // 画布级圆角兜底裁剪：部分 ROM（HarmonyOS 等）的 outline 裁剪对子 View 不生效，
        // 内容会画出胶囊圆角（顶栏分类滑到边缘时尤为明显）。把画布裁到「圆角矩形外扩 1px」：
        // outline 裁剪生效的设备上它是超集（不改变任何像素、不破坏抗锯齿边），
        // 不生效的设备上由它兜底，保证胶囊内内容绝不越界。
        int w = getWidth(), h = getHeight();
        boolean clipped = false;
        if (w > 0 && h > 0) {
            float r = cornerPx() + 1f;
            clipPath.reset();
            clipPath.addRoundRect(-1f, -1f, w + 1f, h + 1f, r, r, android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            clipped = true;
        }
        glass.draw(canvas);
        super.dispatchDraw(canvas);
        if (clipped) canvas.restore();
    }
}
