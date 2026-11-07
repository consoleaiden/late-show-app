package com.lateshow.app.util;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;

import com.lateshow.app.R;

/**
 * 骨架屏占位构件：与首页首屏骨架同一套视觉规范
 * （8dp 圆角封面块 / 6dp 圆角灰条 / 900ms 透明度呼吸）。
 *
 * 只负责「造占位 View」，显示时机交给调用方：本地缓存或极快响应时不该闪一下骨架，
 * 统一用「延迟 220ms 仍未出数据才显示」的策略。
 */
public final class Skeleton {

    private Skeleton() {}

    /** 灰条（宽度/高度由调用方给 LayoutParams 决定） */
    public static View bar(Context c) {
        View v = new View(c);
        v.setBackgroundResource(R.drawable.bg_skel);
        return v;
    }

    public static LinearLayout box(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        return b;
    }

    /** 海报墙骨架：cols 列 × rows 行，封面 9:16 + 标题两行灰条（收藏 / 历史页复用） */
    public static LinearLayout posterGrid(Context c, int cols, int rows) {
        LinearLayout root = box(c);
        int itemW = (c.getResources().getDisplayMetrics().widthPixels / cols) - dp(c, 10);
        int coverH = Math.round(itemW * 16f / 9f);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < cols; i++) {
                LinearLayout card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                cp.leftMargin = dp(c, 5);
                cp.rightMargin = dp(c, 5);
                cp.bottomMargin = dp(c, 14);

                View cover = new View(c);
                cover.setBackgroundResource(R.drawable.bg_cover_round);
                card.addView(cover, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, coverH));

                View line1 = bar(c);
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 9));
                lp1.topMargin = dp(c, 8);
                card.addView(line1, lp1);

                View line2 = bar(c);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                        Math.round(itemW * 0.6f), dp(c, 9));
                lp2.topMargin = dp(c, 6);
                card.addView(line2, lp2);

                row.addView(card, cp);
            }
            root.addView(row);
        }
        return root;
    }

    /** 设置页列表行骨架：左标题条（弹性）+ 右状态条，行高对齐 item_sync_source */
    public static LinearLayout settingRows(Context c, int count) {
        LinearLayout root = box(c);
        int rowH = dp(c, 44);
        for (int i = 0; i < count; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            View name = bar(c);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, dp(c, 10), 1f);
            np.rightMargin = dp(c, 48);
            row.addView(name, np);

            View state = bar(c);
            row.addView(state, new LinearLayout.LayoutParams(dp(c, 36), dp(c, 16)));

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH);
            root.addView(row, rp);

            if (i < count - 1) {
                View divider = new View(c);
                divider.setBackgroundResource(R.color.line);
                root.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f))));
            }
        }
        return root;
    }

    /** 统计小卡骨架：rows 行 × 2 列，形状对齐 item_stat（bg_card + 数值条 + 标签条） */
    public static LinearLayout statCards(Context c, int rows) {
        LinearLayout root = box(c);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(c, 8);
            for (int i = 0; i < 2; i++) {
                LinearLayout card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundResource(R.drawable.bg_card);
                card.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));

                View v1 = bar(c);
                card.addView(v1, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 14)));

                View v2 = bar(c);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(c, 74), dp(c, 8));
                lp2.topMargin = dp(c, 8);
                card.addView(v2, lp2);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.leftMargin = dp(c, 8);
                row.addView(card, lp);
            }
            root.addView(row, rowLp);
        }
        return root;
    }

    /** 透明度呼吸：1.0 ↔ 0.45，900ms 来回，与首页首屏骨架节奏一致 */
    public static ObjectAnimator breathe(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.45f);
        a.setDuration(900);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        return a;
    }

    /** 显示骨架并启动呼吸（重复调用不会重启动画） */
    public static void show(View box, ObjectAnimator[] holder) {
        if (box == null) return;
        if (box.getVisibility() != View.VISIBLE) box.setVisibility(View.VISIBLE);
        if (holder[0] == null) holder[0] = breathe(box);
        if (!holder[0].isStarted()) holder[0].start();
    }

    /** 隐藏骨架并停掉呼吸动画（避免 View 不可见时仍在跑动画） */
    public static void hide(View box, ObjectAnimator[] holder) {
        if (box != null && box.getVisibility() != View.GONE) box.setVisibility(View.GONE);
        if (holder[0] != null) {
            holder[0].cancel();
            holder[0] = null;
            if (box != null) box.setAlpha(1f);
        }
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
