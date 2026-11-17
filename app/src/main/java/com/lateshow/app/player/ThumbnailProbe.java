package com.lateshow.app.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.lateshow.app.util.PlayHeaders;

import android.util.LruCache;

/**
 * 视频帧抓取：一个**独立**的 ExoPlayer 实例，把画面渲染到 {@link TextureView} 上，
 * 再 {@code getBitmap()} 取出该帧。
 *
 * <p>为什么不复用主播放器：复用会打断正在播出的画面/声音，还要把 {@code surface_type}
 * 从 surface_view 换成 texture_view，风险大；独立实例与主播放完全隔离，互不干扰。
 *
 * <p>时间轴一致性：抓帧实例必须走与主播放器**同一条** DataSource 链路（含广告过滤），
 * 否则主播放器的进度是「过滤后时间轴」，抓帧却按原始时间轴 seek，预览画面会整体偏移。
 *
 * <p>节流与缓存：{@link #get(long, Callback)} 按「时间桶」去重并做 LRU 缓存，
 * 同一桶只 seek 一次；未命中才 seek + 轮询首帧，超时不出图就静默放弃（保持上一帧）。
 */
public final class ThumbnailProbe {

    public interface Callback {
        void onBitmap(@Nullable Bitmap bmp);
    }

    /**
     * 单帧最长等待时间；超时放弃，避免一直占用解码器。
     * 源站 seek 后出帧实测 8~15 秒（2026-11-17），太短会「有框无画」。
     */
    private static final long TIMEOUT_MS = 15000L;
    /** 抓帧/超时后多久暂停副播放器（省流量）；期间再次拖动会取消计时 */
    private static final long IDLE_PAUSE_MS = 12000L;
    /** 轮询间隔 */
    private static final long POLL_MS = 40L;

    private final ExoPlayer player;
    private final TextureView textureView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> cache;
    private final long bucketMs;
    private final int maxWidth;

    private String url;
    /** 递增序号：换源 / 取消时让在途轮询立即失效 */
    private int seq = 0;
    private boolean released = false;
    /**
     * 是否已渲染出帧。这是比 STATE_READY 更早、更可靠的抓帧信号：
     * 真机上出现「已 renderedFirstFrame 但 state 仍是 BUFFERING」的情况，
     * 只等 READY 会白白超时（2026-11-17 实测差 43ms 就放弃）。
     */
    private volatile boolean frameRendered = false;

    public ThumbnailProbe(Context ctx,
                          androidx.media3.datasource.DataSource.Factory dataSourceFactory,
                          TextureView textureView,
                          long bucketMs,
                          int cacheCount,
                          int maxWidth) {
        this.textureView = textureView;
        this.bucketMs = Math.max(500L, bucketMs);
        this.maxWidth = Math.max(1, maxWidth);
        this.cache = new LruCache<Long, Bitmap>(Math.max(1, cacheCount)) {
            @Override protected int sizeOf(Long key, Bitmap value) { return 1; }   // 按张数限
        };
        player = new ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        // 缩略图只要最低清晰度，省流量；静音；不自动播
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSizeSd()
                .setMaxVideoBitrate(400_000)
                .build());
        player.setVolume(0f);
        // 注意：不能设 playWhenReady=false —— 暂停态下 seek 后 ExoPlayer 会一直停在
        // STATE_BUFFERING，拿不到帧。改为静音播放，抓完帧再暂停。
        player.setPlayWhenReady(true);
        player.setVideoTextureView(textureView);
        // SurfaceTexture 可能因 View 短暂不可见被系统回收；一旦重建就重新绑回副播放器，
        // 否则会出现「state=READY 但 avail=false」而永远抓不到帧。
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                PlayHeaders.diag("probe surface available " + w + "x" + h);
                player.setVideoTextureView(textureView);
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                PlayHeaders.diag("probe surface destroyed");
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                PlayHeaders.diag("probe error " + error.getErrorCodeName()
                        + " msg=" + error.getMessage());
            }

            @Override public void onPlaybackStateChanged(int state) {
                // 状态变化低频，留给排查（正式包也不刷屏）
                PlayHeaders.diag("probe state=" + state + " pos=" + player.getCurrentPosition());
            }

            @Override public void onRenderedFirstFrame() {
                frameRendered = true;
            }
        });
    }

    /** 换集 / 换线路时调用：换源并清空缓存 */
    public void setMedia(String url) {
        if (url == null || url.equals(this.url)) return;
        this.url = url;
        cache.evictAll();
        seq++;
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        // 预热：先 seek 到开头，让解码器与分片缓存进入就绪态，后续拖动的首次抓帧才够快
        player.seekTo(0L);
        PlayHeaders.diag("probe setMedia " + url);
    }

    public void get(long positionMs, Callback cb) {
        long bucket = Math.max(0, positionMs) / bucketMs;
        Bitmap hit = cache.get(bucket);
        if (hit != null) {
            cb.onBitmap(hit);
            return;
        }
        final int mySeq = ++seq;
        final long startAt = SystemClock.uptimeMillis();
        frameRendered = false;
        // 静音播放：暂停态下 seek 后 ExoPlayer 卡在 BUFFERING，渲染不出帧
        cancelPauseSoon();
        player.setPlayWhenReady(true);
        player.seekTo(Math.max(0, positionMs));
        poll(mySeq, startAt, bucket, cb);
    }

    /**
     * 延迟暂停副播放器：抓帧后不立刻停，让它保持 READY 与缓冲，
     * 这样紧接着的拖动能立刻出帧；超时（IDLE_PAUSE_MS）才真正省流量。
     */
    public void pauseSoon() {
        main.removeCallbacks(pauseTask);
        main.postDelayed(pauseTask, IDLE_PAUSE_MS);
    }

    private void cancelPauseSoon() {
        main.removeCallbacks(pauseTask);
    }

    private final Runnable pauseTask = new Runnable() {
        @Override public void run() {
            if (!released) player.setPlayWhenReady(false);
        }
    };

    private void poll(final int mySeq, final long startAt, final long bucket, final Callback cb) {
        if (released || mySeq != seq) return;
        main.postDelayed(() -> {
            if (released || mySeq != seq) return;
            long now = SystemClock.uptimeMillis();
            if (now - startAt > TIMEOUT_MS) {
                PlayHeaders.diag("probe timeout bucket=" + bucket
                        + " state=" + player.getPlaybackState()
                        + " avail=" + textureView.isAvailable()
                        + " wh=" + textureView.getWidth() + "x" + textureView.getHeight()
                        + " pos=" + player.getCurrentPosition());
                pauseSoon();
                return;
            }
            if ((frameRendered || player.getPlaybackState() == Player.STATE_READY)
                    && textureView.isAvailable()
                    && textureView.getWidth() > 0
                    && textureView.getHeight() > 0) {
                try {
                    Bitmap raw = textureView.getBitmap();
                    if (raw != null && raw.getWidth() > 0) {
                        Bitmap out = raw;
                        if (raw.getWidth() > maxWidth) {
                            out = Bitmap.createScaledBitmap(raw, maxWidth,
                                    raw.getHeight() * maxWidth / raw.getWidth(), true);
                        }
                        cache.put(bucket, out);
                        cb.onBitmap(out);
                        // 不立刻停：保持解码器就绪，接下来的拖动能秒出帧
                        pauseSoon();
                        return;
                    }
                    PlayHeaders.diag("probe bitmap empty bucket=" + bucket);
                } catch (Throwable t) {
                    // 个别 ROM 上硬解输出可能取不到帧：放弃本次，不影响播放
                    PlayHeaders.diag("probe getBitmap fail: " + t);
                    return;
                }
            }
            poll(mySeq, startAt, bucket, cb);
        }, POLL_MS);
    }

    /**
     * 空闲时让副播放器跟随主播放器位置，保持解码器与缓冲「热」。
     * 用户拖到主播放器已缓冲附近时，seek 命中缓冲区能立刻出帧；
     * 若每次都从冷启动 seek，实测要等 8~15 秒才出画（2026-11-17）。
     */
    public void followTo(long positionMs) {
        if (released) return;
        long cur = player.getCurrentPosition();
        if (Math.abs(positionMs - cur) < 3000L) return;   // 已足够接近，不打扰
        // 让在途轮询失效：接下来要以「跟随」为目的，而不是抓某一帧
        seq++;
        main.removeCallbacksAndMessages(null);
        cancelPauseSoon();
        player.setPlayWhenReady(true);
        player.seekTo(Math.max(0, positionMs));
        pauseSoon();
    }

    /** 取消在途抓帧（onPause / 换源时调用），避免后台继续拉流解码 */
    public void cancel() {
        seq++;
        cancelPauseSoon();
        main.removeCallbacksAndMessages(null);
        player.setPlayWhenReady(false);
    }

    public void release() {
        released = true;
        cancel();
        player.release();
    }
}
