package com.lateshow.app.util;

import android.view.MotionEvent;
import android.view.View;

/**
 * 横向滑动手势调节播放进度：拖动实时预览，松手生效；轻点回调 onTap。
 * 快进/快退幅度按视频总时长动态计算：
 *   划过整个控件宽度 = 总时长 × durationRatio（如 0.5 → 半屏 = 总时长 1/4）
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

    /** 总时长未知（元数据未就绪）时，划满全宽的兜底毫秒数 */
    private static final long FALLBACK_FULL_MS = 90_000L;

    private GestureSeek() {}

    /**
     * @param durationRatio 划满控件宽度对应的进度占总时长的比例（0.5 = 全宽 1/2）
     */
    public static void attach(View v, final float durationRatio, final Callback cb) {
        final float[] down = new float[2];
        final boolean[] seeking = {false};
        final long[] base = {0};

        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getX();
                    down[1] = ev.getY();
                    seeking[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getX() - down[0];
                    float dy = ev.getY() - down[1];
                    float th = 32f * view.getResources().getDisplayMetrics().density;
                    if (!seeking[0] && Math.abs(dx) > th && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        seeking[0] = true;
                        base[0] = cb.positionMs();
                        cb.onSeekStart();
                    }
                    if (seeking[0]) {
                        long delta = deltaMs(view, dx, durationRatio, cb);
                        long target = clamp(base[0] + delta, cb.durationMs());
                        String arrow = delta >= 0 ? "▶▶ 快进 " : "◀◀ 快退 ";
                        String txt = arrow + TimeUtil.dur(Math.abs(delta) / 1000)
                                + "   " + TimeUtil.dur(target / 1000)
                                + (cb.durationMs() > 0 ? " / " + TimeUtil.dur(cb.durationMs() / 1000) : "");
                        cb.onSeekPreview(target, txt);
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    if (seeking[0]) {
                        seeking[0] = false;
                        cb.onSeekEnd(base[0]);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (seeking[0]) {
                        seeking[0] = false;
                        long delta = deltaMs(view, ev.getX() - down[0], durationRatio, cb);
                        cb.onSeekEnd(clamp(base[0] + delta, cb.durationMs()));
                    } else {
                        cb.onTap();
                    }
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
}
