package com.lateshow.app.ui.widget;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.lateshow.app.R;
import com.lateshow.app.theme.ThemeEngine;

/**
 * 把主题方案（或默认值）映射到毛玻璃控件的参数。
 *
 * <p>底栏 / 顶栏 / 弹层共用同一套映射，避免各页面各写一份导致参数漂移。
 * 传入的 {@code r} 为 {@code null} 表示主题引擎关闭 → 用内置默认值
 * （毛玻璃开、模糊 32dp、白雾 0.40、底色 0.88、颜色 barBg）。
 */
public final class GlassTheme {

    /** 默认模糊 sigma（dp） */
    public static final float DEFAULT_SIGMA_DP = 32f;
    /** 默认白雾浓度 */
    public static final float DEFAULT_FROST = 0.40f;
    /** 默认底色浓度 */
    public static final float DEFAULT_BASE_ALPHA = 0.88f;
    /** 模糊滑杆 100% 对应的 sigma（dp） */
    private static final float MAX_SIGMA_DP = 40f;

    private GlassTheme() {}

    /** 模糊强度 0~100 → sigma px */
    public static float sigma(int barBlur0to100, float density) {
        int b = Math.max(0, Math.min(100, barBlur0to100));
        return Math.max(2f * density, b / 100f * MAX_SIGMA_DP * density);
    }

    public static void applyBar(@NonNull FrostedGlassBar bar, @NonNull Context ctx,
                                @Nullable ThemeEngine.Resolved r, float density) {
        if (r == null) {
            bar.setGlassOn(true);
            bar.setBlurSigma(DEFAULT_SIGMA_DP * density);
            bar.setFrost(DEFAULT_FROST);
            bar.setBaseAlpha(DEFAULT_BASE_ALPHA);
            bar.setTintColor(ContextCompat.getColor(ctx, R.color.barBg));
            return;
        }
        bar.setGlassOn(r.glassOn);
        bar.setBlurSigma(sigma(r.barBlur, density));
        bar.setFrost(r.barFrost / 100f);
        bar.setBaseAlpha(r.barAlpha / 100f);
        bar.setTintColor(r.surface);
    }

    public static void applyPanel(@NonNull FrostedGlassPanel panel, @NonNull Context ctx,
                                  @Nullable ThemeEngine.Resolved r, float density) {
        if (r == null) {
            panel.setGlassOn(true);
            panel.setBlurSigma(28f * density);
            panel.setFrost(0.55f);
            panel.setBaseAlpha(0.92f);
            panel.setTintColor(ContextCompat.getColor(ctx, R.color.barBg));
            return;
        }
        panel.setGlassOn(r.glassOn);
        panel.setBlurSigma(sigma(r.barBlur, density));
        panel.setFrost(Math.min(1f, r.barFrost / 100f * 1.35f));
        panel.setBaseAlpha(Math.min(1f, r.barAlpha / 100f * 1.05f));
        panel.setTintColor(r.surface);
    }
}
