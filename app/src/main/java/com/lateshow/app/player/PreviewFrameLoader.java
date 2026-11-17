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
    /**
     * 出图并显示到 ImageView 后的回调（主线程）；用于松手后把收起定时提前到 1.6s，可 null。
     * 松手时宿主把预览框手动钉回显示等这帧（见 PlayerActivity），帧一到就该收起了。
     */
    public Runnable onFrameShown;
    /**
     * 是否处于用户拖动中。库的 PreviewTimeBar.setPosition 每次进度更新都会走
     * delegate.updateProgress——只要预览框还显示着，就会以「主播放器实时位置」
     * 调 loadPreview 并把预览框 setX 移过去。抓帧目标绝不能被主播放器位置
     * 污染（预览会跟着播放跑、与 thumb 脱钩），非拖动触发一律忽略。
     */
    private boolean scrubbing = false;

    /** 由 PlayerActivity 在 onScrubStart/Stop 维护 */
    public void setScrubbing(boolean scrubbing) {
        this.scrubbing = scrubbing;
    }

    public PreviewFrameLoader(ThumbnailProbe probe, ImageView imageView, long bucketMs) {
        this.probe = probe;
        this.imageView = imageView;
        this.bucketMs = bucketMs;
    }

    @Override
    public void loadPreview(long currentPosition, long max) {
        if (!scrubbing) return;   // 主播放器进度更新触发（delegate.updateProgress）：忽略
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
                if (onFrameShown != null) onFrameShown.run();
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
        scrubbing = true;   // 松手补抓属于拖动语义，放行本次
        loadPreview(currentPositionMs, 0L);
        scrubbing = false;
    }

    /** 取消在途抓帧（页面不可见时） */
    public void cancel() {
        probe.cancel();
    }
}
