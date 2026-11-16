package com.lateshow.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 毛玻璃弹层卡片（源站列表 / 全部分类列表共用）。
 *
 * 与 {@link FrostedGlassBar} 同一套 {@link FrostedGlassRenderer}：
 * 采样背后内容 → 降采样模糊 → 白雾。全 API 可用，不依赖 RenderEffect。
 *
 * 弹层是页面内浮层（不是独立 Window），才能真实采样到背后列表内容，
 * 也因此省掉了 PopupWindow / Dialog 在这类设备上的模糊不支持问题。
 */
public class FrostedGlassPanel extends FrameLayout {

    private final FrostedGlassRenderer glass;
    private float cornerPx = -1f;

    public FrostedGlassPanel(@NonNull Context context) {
        super(context);
        glass = new FrostedGlassRenderer(this);
        init();
    }

    public FrostedGlassPanel(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        glass = new FrostedGlassRenderer(this);
        init();
    }

    public FrostedGlassPanel(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        glass = new FrostedGlassRenderer(this);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, @NonNull Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius());
            }
        });
        setClipToOutline(true);
        setElevation(10f * getResources().getDisplayMetrics().density);
        // 弹层需要更实一点，保证小字可读
        glass.setBaseAlpha(0.92f);
        glass.setFrost(0.55f);
        glass.setBlurSigma(28f * getResources().getDisplayMetrics().density);
        glass.refreshBackground();
    }

    private float radius() {
        if (cornerPx > 0) return cornerPx;
        float d = getResources().getDisplayMetrics().density;
        return 14f * d;
    }

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

    /** 毛玻璃总开关 */
    public void setGlassOn(boolean on) {
        glass.setGlassOn(on);
    }

    /** 显示时强制重采背景（浮层刚变为可见时位置/内容才有意义） */
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
        glass.draw(canvas);
        super.dispatchDraw(canvas);
    }
}
