package com.lateshow.app.util;

import android.view.MotionEvent;
import android.view.View;

/**
 * 播放器手势：
 *  - 横向滑动：调节播放进度（拖动实时预览，松手生效）；快进/快退幅度按视频总时长动态计算，
 *    划过整个控件宽度 = 总时长 × durationRatio（如 0.5 → 半屏 = 总时长 1/4）。
 *  - 纵向滑动（可选，传入 {@link VerticalCallback} 时生效）：右半屏调屏幕亮度、左半屏调音量。
 *  - 轻点：回调 onTap（用于显隐控制栏）。
 *
 * <p>横向与纵向由首个超过阈值的位移方向锁定，锁定后另一方向不再响应，互不干扰。
 */
public final class GestureSeek {

    public interface Callback {
        long durationMs();
        long positionMs();
        void onSeekStart();
        void onSeekPreview(long targetMs, String previewText);
        void onSeekEnd(long targetMs);
        void onTap();
    }

    /** 纵向手势回调：right=true 表示右半屏（亮度），false 表示左半屏（音量）；value ∈ [0,1] */
    public interface VerticalCallback {
        /** 手势开始时的当前值（0..1），作为相对调节的基准 */
        float currentValue(boolean right);
        void onVerticalStart(boolean right, float value);
        void onVerticalProgress(boolean right, float value);
        void onVerticalEnd(boolean right, float value);
    }

    /** 总时长未知（元数据未就绪）时，划满全宽的兜底毫秒数 */
    private static final long FALLBACK_FULL_MS = 90_000L;
    /** 纵向手势起步阈值（dp）：低于此位移视为轻点 */
    private static final float VERTICAL_THRESHOLD_DP = 24f;
    /** 纵向灵敏度：划过整屏高度 = 100% 变化量 */
    private static final float VERTICAL_SENSITIVITY = 1.0f;

    private static final int MODE_NONE = 0;
    private static final int MODE_SEEK = 1;
    private static final int MODE_VERTICAL = 2;

    private GestureSeek() {}

    /**
     * @param durationRatio 划满控件宽度对应的进度占总时长的比例（0.5 = 全宽 1/2）
     */
    public static void attach(View v, final float durationRatio, final Callback cb) {
        attach(v, durationRatio, cb, null);
    }

    /**
     * @param vcb 纵向手势回调；null 表示不启用纵向手势（仅横向进度 + 轻点）
     */
    public static void attach(View v, final float durationRatio, final Callback cb,
                              final VerticalCallback vcb) {
        attach(v, durationRatio, cb, vcb, null);
    }

    /**
     * @param enabled 手势开关（每根手指按下时判定）。传 null 表示始终开启；
     *                关闭时 onTouch 返回 false，触摸事件穿透给下层视图（如海报的播放按钮）。
     */
    public static void attach(View v, final float durationRatio, final Callback cb,
                              final VerticalCallback vcb,
                              final java.util.function.BooleanSupplier enabled) {
        final float[] down = new float[2];
        final int[] mode = {MODE_NONE};
        final long[] base = {0};
        final boolean[] vRight = {false};
        final float[] vBase = {0f};
        final float[] vLast = {0f};

        v.setOnTouchListener((view, ev) -> {
            if (enabled != null && !enabled.getAsBoolean()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getX();
                    down[1] = ev.getY();
                    mode[0] = MODE_NONE;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getX() - down[0];
                    float dy = ev.getY() - down[1];
                    float th = 32f * view.getResources().getDisplayMetrics().density;
                    float vth = VERTICAL_THRESHOLD_DP * view.getResources().getDisplayMetrics().density;
                    // 首次超过阈值时锁定方向：横向→进度，纵向→亮度/音量
                    if (mode[0] == MODE_NONE) {
                        if (Math.abs(dx) > th && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            mode[0] = MODE_SEEK;
                            base[0] = cb.positionMs();
                            cb.onSeekStart();
                        } else if (vcb != null && Math.abs(dy) > vth
                                && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                            mode[0] = MODE_VERTICAL;
                            vRight[0] = down[0] >= view.getWidth() * 0.5f;
                            vBase[0] = clamp01(vcb.currentValue(vRight[0]));
                            vLast[0] = vBase[0];
                            vcb.onVerticalStart(vRight[0], vBase[0]);
                        }
                    }
                    if (mode[0] == MODE_SEEK) {
                        long delta = deltaMs(view, dx, durationRatio, cb);
                        long target = clamp(base[0] + delta, cb.durationMs());
                        String arrow = delta >= 0 ? "▶▶ 快进 " : "◀◀ 快退 ";
                        String txt = arrow + TimeUtil.dur(Math.abs(delta) / 1000)
                                + "   " + TimeUtil.dur(target / 1000)
                                + (cb.durationMs() > 0 ? " / " + TimeUtil.dur(cb.durationMs() / 1000) : "");
                        cb.onSeekPreview(target, txt);
                    } else if (mode[0] == MODE_VERTICAL) {
                        // 上滑增大、下滑减小：dy<0 表示上滑，故取负号
                        vLast[0] = clamp01(vBase[0]
                                - dy / Math.max(1, view.getHeight()) * VERTICAL_SENSITIVITY);
                        vcb.onVerticalProgress(vRight[0], vLast[0]);
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    if (mode[0] == MODE_SEEK) cb.onSeekEnd(base[0]);
                    else if (mode[0] == MODE_VERTICAL) vcb.onVerticalEnd(vRight[0], vLast[0]);
                    mode[0] = MODE_NONE;
                    return true;

                case MotionEvent.ACTION_UP:
                    if (mode[0] == MODE_SEEK) {
                        long delta = deltaMs(view, ev.getX() - down[0], durationRatio, cb);
                        cb.onSeekEnd(clamp(base[0] + delta, cb.durationMs()));
                    } else if (mode[0] == MODE_VERTICAL) {
                        vcb.onVerticalEnd(vRight[0], vLast[0]);
                    } else {
                        cb.onTap();
                    }
                    mode[0] = MODE_NONE;
                    return true;
            }
            return false;
        });
    }

    /** 滑动距离 → 毫秒：比例 × 视频总时长，随手指划过的宽度线性变化 */
    private static long deltaMs(View v, float dx, float ratio, Callback cb) {
        float frac = dx / Math.max(1, v.getWidth());
        long dur = cb.durationMs();
        if (dur <= 0) return (long) (frac * FALLBACK_FULL_MS); // 时长未知兜底
        return (long) (frac * dur * ratio);
    }

    private static long clamp(long t, long dur) {
        if (t < 0) return 0;
        if (dur > 0 && t > dur) return dur;
        return t;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
