package com.lateshow.app.player;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import com.github.rubensousa.previewseekbar.PreviewLoader;
import com.lateshow.app.util.PlayHeaders;

/**
 * 把 {@link ThumbnailProbe} 接进 PreviewSeekBar 的 {@link PreviewLoader}。
 *
 * <p>库在 {@code onScrubMove} 里**每次移动都回调、且没有节流**，直接拿去 seek 会把解码器
 * 打满。这里做两层去重：时间桶（同一桶只抓一次）+ 最小间隔。
 */
public final class PreviewFrameLoader implements PreviewLoader {

    /** 两次抓帧的最小间隔 */
    private static final long MIN_INTERVAL_MS = 120L;
    /** 同一时间桶在多长时间内不重复抓（此时进度变化很小，画面几乎一样） */
    private static final long SAME_BUCKET_GUARD_MS = 800L;

    private final ThumbnailProbe probe;
    private final ImageView imageView;
    private final long bucketMs;

    private long lastAt = 0L;
    private long lastBucket = Long.MIN_VALUE;
    private long lastBucketAt = 0L;

    public PreviewFrameLoader(ThumbnailProbe probe, ImageView imageView, long bucketMs) {
        this.probe = probe;
        this.imageView = imageView;
        this.bucketMs = bucketMs;
    }

    @Override
    public void loadPreview(long currentPosition, long max) {
        long now = SystemClock.uptimeMillis();
        long bucket = Math.max(0, currentPosition) / bucketMs;
        if (bucket == lastBucket && now - lastBucketAt < SAME_BUCKET_GUARD_MS) return;
        if (now - lastAt < MIN_INTERVAL_MS) return;
        lastAt = now;
        lastBucket = bucket;
        lastBucketAt = now;
        final long target = Math.max(0, currentPosition);
        probe.get(target, new ThumbnailProbe.Callback() {
            @Override public void onBitmap(Bitmap bmp) {
                if (bmp == null) {
                    // 明确区分「抓到空帧」和「没回调」：两者都说明预览画面没更新
                    PlayHeaders.diag("probe frame NULL @" + (target / 1000) + "s bucket=" + bucket);
                    return;
                }
                PlayHeaders.diag("probe frame @" + (target / 1000) + "s bucket=" + bucket
                        + " wh=" + bmp.getWidth() + "x" + bmp.getHeight());
                imageView.setImageBitmap(bmp);
                imageView.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * 松手时强制补一帧：拖动中被最小间隔/同桶保护跳过的「最终位置」在这里补抓，
     * 否则停留时预览框里可能还是上一个桶的旧画面。
     */
    public void flush(long currentPositionMs) {
        lastBucket = Long.MIN_VALUE;
        lastBucketAt = 0L;
        loadPreview(currentPositionMs, 0L);
    }

    /** 取消在途抓帧（页面不可见时） */
    public void cancel() {
        probe.cancel();
    }
}
