package com.lateshow.app.util;

import android.view.MotionEvent;
import android.view.View;

/** 横向滑动手势调节播放进度：拖动实时预览，松手生效；轻点回调 onTap */
public final class GestureSeek {

    public interface Callback {
        long durationMs();
        long positionMs();
        void onSeekStart();
        void onSeekPreview(long targetMs, String previewText);
        void onSeekEnd(long targetMs);
        void onTap();
    }

    private GestureSeek() {}

    /**
     * @param fullSwipeMs 手指划满整个控件宽度对应的进度毫秒数
     */
    public static void attach(View v, final long fullSwipeMs, final Callback cb) {
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
                        long delta = deltaMs(view, dx, fullSwipeMs);
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
                        long delta = deltaMs(view, ev.getX() - down[0], fullSwipeMs);
                        cb.onSeekEnd(clamp(base[0] + delta, cb.durationMs()));
                    } else {
                        cb.onTap();
                    }
                    return true;
            }
            return false;
        });
    }

    private static long deltaMs(View v, float dx, float fullSwipeMs) {
        return (long) (dx / Math.max(1, v.getWidth()) * fullSwipeMs);
    }

    private static long clamp(long t, long dur) {
        if (t < 0) return 0;
        if (dur > 0 && t > dur) return dur;
        return t;
    }
}
