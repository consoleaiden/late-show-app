package com.lateshow.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.lateshow.app.R;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 液态玻璃底部导航栏。
 *
 * 效果移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）：
 * 每帧把背后内容（setBackdropSource 传入的内容容器）绘入 RenderNode，
 * 经「高斯模糊 → 圆角矩形 SDF 边缘折射 + 7 通道色散 + 磨砂白雾 + 边缘高光」
 * 的 AGSL RuntimeShader（raw/liquid_glass_bar.agsl）输出为玻璃。
 *
 * 前提：内容必须延伸到导航栏后面（布局上 container 与本控件重叠），
 * 否则玻璃后面只有纯色背景，折射无从谈起。
 *
 * Android 13（API 33）以下不支持 RuntimeShader，自动退化为半透明圆角背景。
 */
public class LiquidGlassBar extends LinearLayout {

    private static final String TAG = "LiquidGlassBar";

    private final boolean supported;
    private View backdrop;
    private RenderNode node;
    private RuntimeShader shader;
    private RenderEffect effect;

    // 玻璃参数（-1 = 用代码里给的默认值）
    private float cornerRadiusPx = -1f;
    private float refractionHeightPx = -1f;
    private float refractionAmountPx = -1f;
    private float blurSigma = -1f;
    private float frost = 0.40f;
    private float highlight = 0.20f;
    private float dispersion = 0.6f;
    private float falloff = 6.0f;

    private boolean dirty = true;
    private final int[] srcPos = new int[2];
    private final int[] selfPos = new int[2];
    private final ViewTreeObserver.OnPreDrawListener preDraw = new ViewTreeObserver.OnPreDrawListener() {
        @Override public boolean onPreDraw() {
            recordBackdrop();
            return true;
        }
    };

    public LiquidGlassBar(Context context) {
        super(context);
        supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        init();
    }

    public LiquidGlassBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        init();
    }

    public LiquidGlassBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        init();
    }

    private void init() {
        setWillNotDraw(false);
        if (supported) {
            // 圆角轮廓：裁掉折射画面的方角 + 悬浮投影
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, @NonNull Outline outline) {
                    float r = cornerRadiusPx > 0 ? cornerRadiusPx : view.getHeight() / 2f;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            setClipToOutline(true);
            setElevation(6f * getResources().getDisplayMetrics().density);
        } else {
            // 低版本回退：半透明圆角条（颜色跟随明暗主题的 barBg）
            applyFallbackBackground();
        }
    }

    /** 半透明圆角背景：低版本或 shader 编译失败时兜底，观感仍接近磨砂玻璃。 */
    private void applyFallbackBackground() {
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        int c = ContextCompat.getColor(getContext(), R.color.barBg);
        bg.setColor(Color.argb((int) (255 * 0.92f), Color.red(c), Color.green(c), Color.blue(c)));
        bg.setCornerRadius(cornerRadiusPx > 0 ? cornerRadiusPx : 30f * d);
        setBackground(bg);
    }

    /** 设置采样源：玻璃折射的就是它的内容（主界面的内容容器）。 */
    public void setBackdropSource(View source) {
        backdrop = source;
        dirty = true;
    }

    public void setGlassCornerRadius(float px) { cornerRadiusPx = px; dirty = true; }

    public void setRefraction(float heightPx, float amountPx) {
        refractionHeightPx = heightPx;
        refractionAmountPx = amountPx;
        dirty = true;
    }

    public void setBlurSigma(float sigma) { blurSigma = sigma; dirty = true; }

    public void setFrost(float f) { frost = f; dirty = true; }

    public void setHighlight(float h) { highlight = h; dirty = true; }

    public void setDispersion(float d) { dispersion = d; dirty = true; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!supported) return;
        loadShader();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (supported) {
            getViewTreeObserver().removeOnPreDrawListener(preDraw);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            dirty = true;
            if (!supported && getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) getBackground()).setCornerRadius(h / 2f);
            }
            invalidate();
        }
    }

    private void loadShader() {
        try (InputStream in = getResources().openRawResource(R.raw.liquid_glass_bar);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            shader = new RuntimeShader(sb.toString());
        } catch (Exception e) {
            // shader 编译失败（极少数低端设备的 AGSL 支持差异）→ 退回半透明背景
            shader = null;
            applyFallbackBackground();
        }
    }

    /** 每帧把采样源在玻璃区域内的部分绘入 RenderNode，参数有变时重建 RenderEffect。 */
    private void recordBackdrop() {
        if (backdrop == null || shader == null || getWidth() <= 0 || getHeight() <= 0) return;
        if (node == null) node = new RenderNode(TAG);
        int w = getWidth(), h = getHeight();
        backdrop.getLocationInWindow(srcPos);
        getLocationInWindow(selfPos);
        int dx = selfPos[0] - srcPos[0];
        int dy = selfPos[1] - srcPos[1];

        node.setPosition(0, 0, w, h);
        Canvas rec = node.beginRecording(w, h);
        rec.translate(dx, dy);
        // 只记录玻璃覆盖的区域，避免整页内容重复绘制
        rec.clipRect(dx, dy, dx + w, dy + h);
        backdrop.draw(rec);
        node.endRecording();

        if (dirty) {
            applyEffect(w, h);
            dirty = false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void applyEffect(int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        float radius = cornerRadiusPx > 0 ? cornerRadiusPx : h / 2f;
        float refH = refractionHeightPx > 0 ? refractionHeightPx : 24f * d;
        // 负位移 = 向内采样 = 边缘透镜放大（Kyant0 lens 的方向约定）
        float refA = -(refractionAmountPx > 0 ? refractionAmountPx : 30f * d);
        float sigma = blurSigma > 0 ? blurSigma : 9f * d;

        shader.setFloatUniform("size", w, h);
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        shader.setFloatUniform("refractionHeight", refH);
        shader.setFloatUniform("refractionAmount", refA);
        shader.setFloatUniform("dispersion", dispersion);
        shader.setFloatUniform("frost", frost);
        shader.setFloatUniform("highlight", highlight);
        shader.setFloatUniform("falloff", falloff);

        RenderEffect shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content");
        RenderEffect blur = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP);
        effect = RenderEffect.createChainEffect(shaderEffect, blur);
        node.setRenderEffect(effect);
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (supported && node != null && shader != null) {
            // 玻璃层（背后内容的折射画面），画在子 View（图标）之下
            canvas.drawRenderNode(node);
        }
        super.dispatchDraw(canvas);
    }
}
