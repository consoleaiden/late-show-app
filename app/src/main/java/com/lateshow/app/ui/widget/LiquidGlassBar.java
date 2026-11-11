package com.lateshow.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
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
 * 液态玻璃底部导航栏（三级平滑降级）。
 *
 * 效果移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）：
 * 每帧把背后内容（setBackdropSource 传入的内容容器）绘入 RenderNode，
 * 经「高斯模糊 → 圆角矩形 SDF 边缘折射 + 7 通道色散 + 磨砂白雾 + 边缘高光」
 * 的 AGSL RuntimeShader（raw/liquid_glass_bar.agsl）输出为玻璃。
 *
 * 前提：内容必须延伸到导航栏后面（布局上 container 与本控件重叠），
 * 否则玻璃后面只有纯色背景，折射无从谈起。
 *
 * 降级阶梯（按设备能力自动选择，诊断日志 tag=LateShowGlass）：
 *  tier 2  API 33+  完整液态玻璃：AGSL 折射 + 色散 + 磨砂 + 高光
 *  tier 1  API 31+  实时磨砂玻璃：RenderEffect 高斯模糊底层内容 + 白雾/高光手绘
 *                   （RuntimeShader 是 API 33 才有；华为 Mate30/HarmonyOS 4.2 = API 31 落在这档）
 *  tier 0  API<31   静态半透明圆角胶囊
 */
public class LiquidGlassBar extends LinearLayout {

    private static final String TAG = "LiquidGlassBar";
    private static final String DIAG_TAG = "LateShowGlass";

    /** 2=AGSL 完整玻璃 1=RenderEffect 磨砂 0=静态半透明 */
    private final int tier;
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

    // tier 1 手绘磨砂层用的画笔
    private Paint frostPaint;
    private Paint hiPaint;

    public LiquidGlassBar(Context context) {
        super(context);
        tier = computeTier();
        init();
    }

    public LiquidGlassBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        tier = computeTier();
        init();
    }

    public LiquidGlassBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs);
        tier = computeTier();
        init();
    }

    private static int computeTier() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return 2;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return 1; // API 31
        return 0;
    }

    private void init() {
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        if (tier >= 1) {
            // 圆角轮廓：裁掉玻璃画面的方角 + 悬浮投影
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, @NonNull Outline outline) {
                    float r = cornerRadiusPx > 0 ? cornerRadiusPx : view.getHeight() / 2f;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            setClipToOutline(true);
            setElevation(6f * d);
        }
        if (tier == 0) {
            applyStaticFallback();
        }
        Log.i(DIAG_TAG, "LiquidGlassBar init: tier=" + tier
                + " sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + ' ' + Build.MODEL);
    }

    /** tier 0 兜底：半透明圆角胶囊（颜色跟随明暗主题的 barBg），观感接近磨砂玻璃。 */
    private void applyStaticFallback() {
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        int c = ContextCompat.getColor(getContext(), R.color.barBg);
        bg.setColor(Color.argb((int) (255 * 0.92f), Color.red(c), Color.green(c), Color.blue(c)));
        bg.setCornerRadius(cornerRadiusPx > 0 ? cornerRadiusPx : 30f * d);
        setBackground(bg);
    }

    /** 设置采样源：玻璃模糊/折射的就是它的内容（主界面的内容容器）。 */
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
        if (tier < 1) return;
        if (tier == 2) loadShader();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
        if (tier == 2) {
            Log.i(DIAG_TAG, "shader loaded: " + (shader != null ? "OK" : "FAILED -> fallback"));
            if (shader == null) {
                // AGSL 编译失败：降为 tier 1 的手绘磨砂路径
                enterTier1Paints();
            }
        } else {
            enterTier1Paints();
        }
    }

    /** tier 1 手绘磨砂层的画笔（也用于 tier 2 shader 编译失败时兜底）。 */
    private void enterTier1Paints() {
        float d = getResources().getDisplayMetrics().density;
        frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int c = ContextCompat.getColor(getContext(), R.color.barBg);
        frostPaint.setColor(Color.argb((int) (255 * Math.min(0.75f, 0.35f + frost)),
                Color.red(c), Color.green(c), Color.blue(c)));
        hiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Log.i(DIAG_TAG, "tier1 paints ready (frost alpha="
                + (frostPaint.getAlpha() / 255f) + ")");
    }

    @Override
    protected void onDetachedFromWindow() {
        if (tier >= 1) {
            getViewTreeObserver().removeOnPreDrawListener(preDraw);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            dirty = true;
            if (tier == 0 && getBackground() instanceof GradientDrawable) {
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
            // shader 编译失败（极少数设备的 AGSL 支持差异）→ 退到 tier 1 磨砂
            shader = null;
            Log.w(DIAG_TAG, "RuntimeShader compile failed: " + e.getMessage());
        }
    }

    /** 每帧把采样源在玻璃区域内的部分绘入 RenderNode，参数有变时重建 RenderEffect。 */
    private void recordBackdrop() {
        if (backdrop == null || getWidth() <= 0 || getHeight() <= 0) return;
        // tier 2 且 shader 编译失败走手绘磨砂 → 不再需要 node
        if (tier == 2 && shader == null) return;
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
        invalidate();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void applyEffect(int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        float sigma = blurSigma > 0 ? blurSigma : 9f * d;
        if (tier == 2 && shader != null) {
            float radius = cornerRadiusPx > 0 ? cornerRadiusPx : h / 2f;
            float refH = refractionHeightPx > 0 ? refractionHeightPx : 24f * d;
            // 负位移 = 向内采样 = 边缘透镜放大（Kyant0 lens 的方向约定）
            float refA = -(refractionAmountPx > 0 ? refractionAmountPx : 30f * d);

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
        } else {
            // tier 1：只有高斯模糊（RenderEffect 是 API 31 起，Mate30 可用）
            effect = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP);
        }
        node.setRenderEffect(effect);
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float radius = cornerRadiusPx > 0 ? cornerRadiusPx
                : (getHeight() > 0 ? getHeight() / 2f : 24f * d);
        boolean drawNode = node != null && (tier == 2 ? shader != null : true);
        if (drawNode) {
            // 玻璃层（背后内容的折射/模糊画面），画在子 View（图标）之下
            canvas.drawRenderNode(node);
        }
        if (frostPaint != null) {
            // tier 1 磨砂白雾：盖在模糊画面上（shader 里没有这层，因为 AGSL 版自带）
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, frostPaint);
            // 顶部亮边（模拟玻璃上沿反光）
            if (hiPaint != null && getWidth() > 0) {
                if (hiPaint.getShader() == null) {
                    int c = ContextCompat.getColor(getContext(), R.color.barBg);
                    hiPaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                            Color.argb((int) (255 * highlight), 255, 255, 255),
                            Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))));
                }
                canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, hiPaint);
            }
        }
        super.dispatchDraw(canvas);
    }
}
