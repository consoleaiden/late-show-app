package com.lateshow.app.util;

import android.graphics.PorterDuff;
import android.view.View;
import android.widget.ImageView;

/**
 * 静态图标工具：项目所有图标统一走 vector drawable + 运行时单色着色。
 *
 * 历史：之前图标是 Lottie（assets/lottie/*.json）与 PotLab 动态矢量动画，
 * 动态图标会持续触发窗口重绘（顶栏呼吸动画曾是首页静止卡顿的来源），
 * 已全部替换为静态矢量：静态图不需要逐帧重绘，也不需要 Lottie 运行时。
 */
public final class Icons {

    private Icons() {}

    /** 单色着色：填充与描边统一按给定颜色渲染（SRC_IN 覆盖所有非透明像素） */
    public static void tint(View v, int color) {
        if (v instanceof ImageView) {
            ((ImageView) v).setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    /** 清掉着色，回到矢量自身配色 */
    public static void clearTint(View v) {
        if (v instanceof ImageView) ((ImageView) v).clearColorFilter();
    }
}
