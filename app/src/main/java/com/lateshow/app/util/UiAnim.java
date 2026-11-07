package com.lateshow.app.util;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 页面内切换用的极轻量动效（不是共享元素，只是让"内容整体换了一批"这件事有个交代）。
 *
 * <p>共享元素（一镜到底）走的是 {@link HeroMotionLayout} 那套：只有「列表卡片 ↔ 详情页」
 * 这种有明确起止视图的场景才用。同一个页面里换筛选项、换分段、弹搜索面板，没有起止视图，
 * 用「淡入 + 轻微位移」就够，也最不容易出错。
 */
public final class UiAnim {

    /** 淡入 + 从下往上 8dp 归位（内容整体换掉时用） */
    public static void fadeUp(View v) {
        fadeUp(v, 8);
    }

    public static void fadeUp(View v, int fromDp) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(v, fromDp));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /** 淡入 + 从下往上展开（弹出层用，位移大一点） */
    public static void slideUp(View v, int fromDp, int durationMs) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(v, fromDp));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(durationMs)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();
    }

    /** 淡出（隐藏前的一次收尾，调用方自己负责 setVisibility） */
    public static void fadeOut(View v, int durationMs, Runnable end) {
        if (v == null) return;
        v.animate().cancel();
        v.animate()
                .alpha(0f)
                .setDuration(durationMs)
                .withEndAction(() -> {
                    v.setAlpha(1f);      // 复位，下次显示不用再手动设
                    v.setTranslationY(0f);
                    if (end != null) end.run();
                })
                .start();
    }

    private static float dp(View v, int value) {
        return value * v.getResources().getDisplayMetrics().density;
    }

    private UiAnim() {}
}
