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
 * 液态玻璃栏（底部导航 / 首页顶栏共用）。
 *
 * 绘制逻辑全部在 {@link GlassRenderer}：tier2 = AGSL 折射+色散，tier1 = CPU 降采样磨砂，
 * tier0 = 静态半透明。本类只负责「圆角裁切 + 阴影 + 把玻璃层画在子 View 之下」。
 */
public class LiquidGlassBar extends LinearLayout {

    private final GlassRenderer glass;
    private float cornerPx = -1f;

    public LiquidGlassBar(Context context) {
        super(context);
        glass = new GlassRenderer(this);
        init();
    }

    public LiquidGlassBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        glass = new GlassRenderer(this);
        init();
    }

    public LiquidGlassBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        glass = new GlassRenderer(this);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        if (glass.tier() >= GlassRenderer.TIER_BLUR) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, @NonNull Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx());
                }
            });
            setClipToOutline(true);
            setElevation(6f * getResources().getDisplayMetrics().density);
        }
        glass.refreshBackground();
    }

    private float cornerPx() {
        float d = getResources().getDisplayMetrics().density;
        if (cornerPx > 0) return cornerPx;
        return getHeight() > 0 ? Math.min(getHeight() / 2f, 24f * d) : 24f * d;
    }

    public int getGlassTier() {
        return glass.tier();
    }

    /** 设置采样源：玻璃模糊/折射的就是它的内容（主界面的内容容器）。 */
    public void setBackdropSource(View source) {
        glass.setBackdrop(source);
    }

    public void setGlassCornerRadius(float px) {
        cornerPx = px;
        glass.setCornerRadiusPx(px);
        glass.refreshBackground();
        invalidateOutline();
    }

    public void setRefraction(float heightPx, float amountPx) {
        glass.setRefraction(heightPx, amountPx);
    }

    public void setBlurSigma(float sigma) {
        glass.setBlurSigma(sigma);
    }

    public void setFrost(float f) {
        glass.setFrost(f);
    }

    public void setHighlight(float h) {
        glass.setHighlight(h);
    }

    public void setDispersion(float d) {
        glass.setDispersion(d);
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
