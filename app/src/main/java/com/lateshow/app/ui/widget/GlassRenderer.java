package com.lateshow.app.ui.widget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.lateshow.app.R;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 液态玻璃渲染引擎（被 LiquidGlassBar / LiquidGlassPanel 共用，三级平滑降级）。
 *
 * 效果移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）：
 * 把「背后内容」在玻璃区域内的部分采样出来，经过
 * 「模糊 → 圆角矩形 SDF 边缘折射 + 7 通道色散 + 磨砂白雾 + 边缘高光」输出成玻璃。
 *
 * 三级降级（按设备能力选择，诊断日志 tag=LateShowGlass）：
 *  <li>tier 2 API 33+：RenderNode + RenderEffect(AGSL RuntimeShader + 高斯模糊)，折射/色散完整；</li>
 *  <li>tier 1 API 31+：<b>CPU 降采样模糊</b>（把背后内容画进 1/10 小图再放大 = 磨砂）
 *      + 白雾 + 渐变高光。<b>刻意不用 RenderEffect</b>：华为等部分 GPU 驱动会静默丢弃
 *      RenderEffect/RenderNode 的绘制，导致玻璃整块变透明（Mate30 就是这个问题）。
 *      降采样走纯 Canvas 绘制，任何设备都能出磨砂；</li>
 *  <li>tier 0 API&lt;31：静态半透明圆角底（无实时内容），颜色跟随 barBg。</li>
 *
 * 另有一层兜底：{@link #backgroundDrawable()} 返回的半透明圆角底色由宿主设为 background，
 * 即使内容采样完全失败，玻璃栏也不会变成「全透明看不见」。
 */
public class GlassRenderer {

    private static final String TAG = "GlassRenderer";
    private static final String DIAG_TAG = "LateShowGlass";

    public static final int TIER_FULL = 2;    // API 33+
    public static final int TIER_BLUR = 1;    // API 31+（Mate30 / HarmonyOS 4.2 在此档）
    public static final int TIER_STATIC = 0;  // API < 31

    /**
     * tier 1 降采样比例：1/10 缩略再放大。比 1/8 更糊（磨砂感更强），
     * 采样位图也更小（CPU 采样开销再降 ~20%），一石二鸟。
     */
    private static final int DOWNSCALE = 10;
    /**
     * tier 1 采样节流：滚动时最多 ~11fps 刷新背景。磨砂本身没有细节，
     * 低帧率更新看不出「卡」，但 CPU 全量采样的开销实打实地砍掉一截。
     */
    private static final long CAPTURE_INTERVAL_MS = 90L;

    private final View host;
    private final int tier;

    private View backdrop;
    private RenderNode node;          // tier 2
    private RuntimeShader shader;     // tier 2
    private RenderEffect effect;      // tier 2

    private Bitmap backdropBmp;       // tier 1
    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Rect drawRect = new android.graphics.Rect();
    private long lastCapture;
    private boolean capturePending = true;
    /**
     * 背景内容是否有变化（滚动过）。tier1 的 CPU 采样全量画一遍背景 View，
     * 开销不小；顶栏 Lottie 常驻循环会让窗口不停重绘，若每次 preDraw 都采样，
     * 页面静止时也在 15fps 空转 → 卡顿感来源。改成「只有背景滚动过才采样」。
     */
    private boolean contentDirty = true;
    /** 上次看到的背景 RecyclerView 可见子项数：翻页/换分类不滚动也会换内容，靠它补一刀 */
    private int lastChildCount = -1;

    private float cornerRadiusPx = -1f;
    private float refractionHeightPx = -1f;
    private float refractionAmountPx = -1f;
    private float blurSigma = -1f;
    private float frost = 0.16f;
    private float highlight = 0.20f;
    private float dispersion = 0.6f;
    private float falloff = 6.0f;
    /** 兜底底色不透明度（tier 2 很淡，tier 1 中等，tier 0 接近不透明） */
    private float baseAlpha = 0.28f;
    private int barBgColor = 0xFFFFFFFF;

    private boolean dirty = true;
    private final int[] srcPos = new int[2];
    private final int[] selfPos = new int[2];

    private final ViewTreeObserver.OnPreDrawListener preDraw = new ViewTreeObserver.OnPreDrawListener() {
        @Override public boolean onPreDraw() {
            step();
            return true;
        }
    };

    public GlassRenderer(@NonNull View host) {
        this.host = host;
        this.tier = computeTier();
        // 兜底底色浓度：完整玻璃靠 shader，底色调淡；降级档需要更实的底保证可读
        if (tier == TIER_FULL) baseAlpha = 0.10f;
        else if (tier == TIER_BLUR) baseAlpha = 0.38f;
        initPaints();
        Log.i(DIAG_TAG, "GlassRenderer init: tier=" + tier + " sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + ' ' + Build.MODEL
                + " host=" + host.getClass().getSimpleName());
    }

    public static int computeTier() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return TIER_FULL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return TIER_BLUR;
        return TIER_STATIC;
    }

    public int tier() {
        return tier;
    }

    private void initPaints() {
        barBgColor = ContextCompat.getColor(host.getContext(), R.color.barBg);
        if (tier == TIER_FULL) loadShader();
    }

    public void setBackdrop(@NonNull View v) {
        if (backdrop instanceof RecyclerView && scrollHook != null) {
            ((RecyclerView) backdrop).removeOnScrollListener(scrollHook);
        }
        backdrop = v;
        if (v instanceof RecyclerView) {
            if (scrollHook == null) {
                scrollHook = new RecyclerView.OnScrollListener() {
                    @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                        contentDirty = true;
                    }
                };
            }
            ((RecyclerView) v).addOnScrollListener(scrollHook);
            contentDirty = true;
        }
        forceCapture();
    }

    private RecyclerView.OnScrollListener scrollHook;

    public void setCornerRadiusPx(float px) {
        cornerRadiusPx = px;
        dirty = true;
        forceCapture();
    }

    public void setRefraction(float heightPx, float amountPx) {
        refractionHeightPx = heightPx;
        refractionAmountPx = amountPx;
        dirty = true;
    }

    public void setBlurSigma(float sigma) {
        blurSigma = sigma;
        dirty = true;
    }

    public void setFrost(float f) {
        frost = f;
        forceCapture();
    }

    public void setHighlight(float h) {
        highlight = h;
        forceCapture();
    }

    public void setDispersion(float d) {
        dispersion = d;
        dirty = true;
    }

    /** 兜底底色浓度（0~1）：玻璃采样失败时至少还有这层，不会变全透明 */
    public void setBaseAlpha(float a) {
        baseAlpha = a;
        refreshBackground();
    }

    /** 强制重采背景（显示/隐藏、尺寸变化、滚动停止时调用） */
    public void forceCapture() {
        capturePending = true;
        if (host.getWidth() > 0) host.invalidate();
    }

    public void attach() {
        host.getViewTreeObserver().addOnPreDrawListener(preDraw);
        refreshBackground();
        if (tier == TIER_FULL) {
            Log.i(DIAG_TAG, "shader loaded: " + (shader != null ? "OK" : "FAILED -> tier1 downsample"));
        }
    }

    public void detach() {
        try {
            host.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        } catch (Exception ignored) { }
        if (backdrop instanceof RecyclerView && scrollHook != null) {
            ((RecyclerView) backdrop).removeOnScrollListener(scrollHook);
        }
        if (backdropBmp != null && !backdropBmp.isRecycled()) {
            backdropBmp.recycle();
            backdropBmp = null;
        }
    }

    /**
     * 兜底底色：半透明圆角矩形。宿主必须把它设为 background（View 背景先于 dispatchDraw 绘制），
     * 这样即使 RenderNode / 采样全部失败，玻璃栏也只是一层「磨砂白」，而不是透明消失。
     */
    public Drawable backgroundDrawable() {
        float d = host.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        int c = ContextCompat.getColor(host.getContext(), R.color.barBg);
        float a = tier == TIER_STATIC ? 0.92f : baseAlpha;
        bg.setColor(applyAlpha(c, a));
        float r = cornerRadiusPx > 0 ? cornerRadiusPx
                : (host.getHeight() > 0 ? host.getHeight() / 2f : 24f * d);
        bg.setCornerRadius(r);
        return bg;
    }

    public void refreshBackground() {
        host.setBackground(backgroundDrawable());
    }

    /** 尺寸变化后圆角要用新高度重算 */
    public void onSizeChanged() {
        dirty = true;
        refreshBackground();
        forceCapture();
    }

    private void loadShader() {
        try (InputStream in = host.getResources().openRawResource(R.raw.liquid_glass_bar);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            shader = new RuntimeShader(sb.toString());
        } catch (Throwable e) {
            shader = null;
            Log.w(DIAG_TAG, "RuntimeShader compile failed: " + e);
        }
    }

    /** 每帧前置：tier2 录 RenderNode；tier1 节流采一次降采样缩略图 */
    private void step() {
        int w = host.getWidth(), h = host.getHeight();
        if (backdrop == null || w <= 0 || h <= 0) return;
        boolean needRedraw = false;
        if (tier == TIER_FULL && shader != null) {
            recordNode(w, h);       // 内容每帧都在动，必须每帧重录
            needRedraw = true;
        } else if (tier >= TIER_BLUR) {
            // 只有背景滚动过 / 被显式要求重采时才做 CPU 采样：
            // 顶栏 Lottie 循环触发的重绘不再带动每帧全量采样（静止零开销）
            if (backdrop instanceof RecyclerView) {
                int cc = ((RecyclerView) backdrop).getChildCount();
                if (cc != lastChildCount) {
                    lastChildCount = cc;
                    contentDirty = true;
                }
            }
            long now = SystemClock.uptimeMillis();
            if ((capturePending || contentDirty) && now - lastCapture >= CAPTURE_INTERVAL_MS) {
                captureDownsampled(w, h);
                lastCapture = now;
                capturePending = false;
                contentDirty = false;
                needRedraw = true;
            }
        }
        if (needRedraw) host.invalidate();
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void recordNode(int w, int h) {
        if (node == null) node = new RenderNode(TAG);
        relPos();
        int dx = selfPos[0] - srcPos[0];
        int dy = selfPos[1] - srcPos[1];

        node.setPosition(0, 0, w, h);
        Canvas rec = node.beginRecording(w, h);
        rec.translate(dx, dy);
        rec.clipRect(dx, dy, dx + w, dy + h);
        backdrop.draw(rec);
        node.endRecording();

        if (dirty) {
            applyShaderEffect(w, h);
            dirty = false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void applyShaderEffect(int w, int h) {
        float d = host.getResources().getDisplayMetrics().density;
        float sigma = blurSigma > 0 ? blurSigma : 9f * d;
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
        node.setRenderEffect(effect);
    }

    private void relPos() {
        backdrop.getLocationInWindow(srcPos);
        host.getLocationInWindow(selfPos);
    }

    /**
     * tier 1：把背后内容按 1/DOWNSCALE 画进小图，绘制时双线性放大 → 磨砂。
     * 纯 Canvas 绘制，不依赖 RenderEffect，华为/低端 GPU 也能出效果。
     */
    private void captureDownsampled(int w, int h) {
        int sw = Math.max(1, w / DOWNSCALE);
        int sh = Math.max(1, h / DOWNSCALE);
        if (backdropBmp == null || backdropBmp.isRecycled()
                || backdropBmp.getWidth() != sw || backdropBmp.getHeight() != sh) {
            if (backdropBmp != null && !backdropBmp.isRecycled()) backdropBmp.recycle();
            try {
                backdropBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            } catch (Throwable e) {
                backdropBmp = null;
                Log.w(DIAG_TAG, "downsample bitmap alloc failed: " + e);
                return;
            }
        }
        Canvas c = new Canvas(backdropBmp);
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        relPos();
        float s = (float) sw / w;
        c.scale(s, s);
        c.translate(srcPos[0] - selfPos[0], srcPos[1] - selfPos[1]);
        backdrop.draw(c);
    }

    /** 在宿主 dispatchDraw 里、子 View 之前调用：画玻璃层（背景内容 + 白雾 + 高光） */
    public void draw(@NonNull Canvas canvas) {
        int w = host.getWidth(), h = host.getHeight();
        if (w <= 0 || h <= 0) return;
        float d = host.getResources().getDisplayMetrics().density;
        float radius = cornerRadiusPx > 0 ? cornerRadiusPx : h / 2f;

        if (tier == TIER_FULL && node != null && shader != null) {
            canvas.drawRenderNode(node);
            return;   // shader 自带白雾与高光
        }

        if (tier >= TIER_BLUR) {
            if (backdropBmp != null && !backdropBmp.isRecycled()) {
                float sigma = blurSigma > 0 ? blurSigma : 9f * d;
                // 降采样本身已经糊了，再按 sigma 调一点不透明度，模拟更强模糊
                drawPaint.setAlpha((int) Math.min(255f, 210f + sigma * 3f));
                drawRect.set(0, 0, w, h);
                canvas.drawBitmap(backdropBmp, null, drawRect, drawPaint);
            }
            // 白雾：磨砂的关键（内容 → 半透明白 → 柔和高光）。
            // tier1（Mate30 等 CPU 磨砂档）白雾基准从 0.30 提到 0.42：
            // 降采样模糊在浅色海报上不够「雾」，加浓白雾才有明确的磨砂观感
            float frostA = (tier == TIER_BLUR ? 0.42f : 0.30f) + frost;
            frostPaint.setColor(applyAlpha(barBgColor, Math.min(0.85f, frostA)));
            canvas.drawRoundRect(0f, 0f, (float) w, (float) h, radius, radius, frostPaint);
            if (highlight > 0f) {
                int c = barBgColor;
                hiPaint.setShader(new LinearGradient(
                        0f, 0f, 0f, (float) h,
                        new int[]{applyAlpha(0xFFFFFFFF, highlight),
                                applyAlpha(c, 0f)},
                        null, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(0f, 0f, (float) w, (float) h, radius, radius, hiPaint);
            }
        }
        // tier 0：只靠 background 的静态半透明底，这里不画
    }

    private static int applyAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (255 * alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
