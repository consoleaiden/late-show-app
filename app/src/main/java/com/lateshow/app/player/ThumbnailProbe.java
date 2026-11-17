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
    /**
     * 拖动中两次真正 seek 的最小间隔。旧实现每次 get() 都立即 seek：120ms 节流下
     * 拖动 2 秒能打出十几次 seek，解码器被反复打断永远渲染不出一帧（真机实测
     * 连续拖动 6 秒 0 次出图）。转向最新目标前先等这么久。
     */
    private static final long MIN_SEEK_INTERVAL_MS = 800L;

    private final ExoPlayer player;
    private final TextureView textureView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> cache;
    private final long bucketMs;
    private final int maxWidth;

    private String url;
    private boolean released = false;
    /**
     * 是否已渲染出帧。这是**唯一**的抓帧信号：
     * - 比 STATE_READY 更早、更可靠：真机出现过「已 renderedFirstFrame 但 state 仍是 BUFFERING」，
     *   只等 READY 会白白超时（2026-11-17 实测差 43ms 就放弃）。
     * - 也比 READY 更准确：READY 态下 seek 时 state 仍是 READY，把它当信号会抓到
     *   seek 之前的旧帧（表现为预览画面静止不动 + 缓存污染，2026-11-17 真机确认）。
     * onRenderedFirstFrame 在每次 seek 后新位置首帧渲染时重新触发。
     */
    private volatile boolean frameRendered = false;

    // ===== 抓帧状态机：目标可中途更新，新请求不再粗暴取消在途抓帧 =====
    // 旧实现每次 get() 都 seq++ 取消上一个 poll：源站 seek 出帧要 7~28 秒，远超
    // 拖动时长，于是拖动中没有任何一次抓帧能活到出帧——预览画面永远静止在
    // 上一次成功的帧（真机确认）。改为「目标更新」模式：拖动中只更新目标，
    // 状态机周期性把 seek 转向最新目标（限流），出帧后回调最新目标。
    /** 最新抓帧目标（ms） */
    private long pendingTarget = -1L;
    /** 最新目标所在时间桶 */
    private long pendingBucket = -1L;
    /** 最新目标的回调 */
    private Callback pendingCb = null;
    /** 状态机是否在跑 */
    private boolean grabActive = false;
    /** 当前 seek 指向的位置（用于判断目标是否已漂移） */
    private long seekingTarget = -1L;
    /** 本次 seek 的发起时刻 */
    private long seekAt = 0L;

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
                PlayHeaders.diag("probe state=" + state + " pos=" + player.getCurrentPosition()
                        + " vfmt=" + player.getVideoFormat()
                        + " vsize=" + player.getVideoSize());
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
        // 复位抓帧状态机（换源后旧目标与在途任务全部失效）
        main.removeCallbacksAndMessages(null);
        grabActive = false;
        pendingCb = null;
        seekingTarget = -1L;
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        // 预热：先 seek 到开头，让解码器与分片缓存进入就绪态，后续拖动的首次抓帧才够快
        player.seekTo(0L);
        PlayHeaders.diag("probe setMedia " + url);
    }

    public void get(long positionMs, Callback cb) {
        if (released) return;
        long bucket = Math.max(0, positionMs) / bucketMs;
        Bitmap hit = cache.get(bucket);
        if (hit != null) {
            cb.onBitmap(hit);
            // 关键：命中缓存说明用户目标已经到此处，必须作废在途抓帧。
            // 否则之前发起、尚未出帧的慢速抓帧完成后，其迟到回调会把预览
            // 画面拉回旧位置——反复拖动时画面在已抓过的桶之间来回跳
            // （真机确认：@1136s→@1135s→@1141s→@1137s→@1134s 倒跳）。
            cancelPending();
            return;
        }
        // 只更新目标：状态机在跑就不打断（等待完成后自动转向新目标）
        pendingTarget = Math.max(0, positionMs);
        pendingBucket = bucket;
        pendingCb = cb;
        if (!grabActive) startGrab();
    }

    /** 作废在途抓帧（目标已被缓存命中 / 页面退出） */
    private void cancelPending() {
        main.removeCallbacks(tickTask);
        grabActive = false;
        pendingCb = null;
        seekingTarget = -1L;
        pauseSoon();
    }

    private final Runnable tickTask = new Runnable() {
        @Override public void run() { tick(); }
    };

    private void startGrab() {
        main.removeCallbacks(tickTask);
        grabActive = true;
        seekingTarget = -1L;
        seekAt = 0L;
        cancelPauseSoon();
        // 静音播放：暂停态下 seek 后 ExoPlayer 卡在 BUFFERING，渲染不出帧
        player.setPlayWhenReady(true);
        tick();
    }

    private void tick() {
        if (released || !grabActive) return;
        main.removeCallbacks(tickTask);
        long now = SystemClock.uptimeMillis();
        // 目标漂移且距上次 seek 足够久 → 转向最新目标（限流防解码器风暴）
        if (pendingTarget != seekingTarget
                && now - seekAt >= MIN_SEEK_INTERVAL_MS) {
            frameRendered = false;
            seekingTarget = pendingTarget;
            seekAt = now;
            player.seekTo(seekingTarget);
        }
        // 出帧判定：只认 onRenderedFirstFrame。不能把 STATE_READY 也算进去：
        // 副播放器因 followTo 预热常驻 READY，READY 态下 seek 时 state 不会变回
        // BUFFERING，若把 READY 当信号会抓到 seek 之前的旧帧——预览画面永远
        // 静止且旧帧污染 LRU 缓存。onRenderedFirstFrame 在每次 seek 后新位置
        // 首帧渲染时重新触发。
        if (frameRendered
                && textureView.isAvailable()
                && textureView.getWidth() > 0
                && textureView.getHeight() > 0) {
            // 目标已漂移：刚渲染的是 seekingTarget 位置的画面，而 pendingTarget
            // 已被下一次拖动更新。绝不能拿旧帧冒充新目标（会以新 bucket 写进
            // LRU 缓存、回调给新目标的请求——预览错位且缓存被污染）。
            // 丢弃本帧；此时解码器刚空闲，立即转向最新目标（不受 MIN_SEEK_INTERVAL 限制）。
            if (pendingTarget != seekingTarget) {
                seekingTarget = pendingTarget;
                seekAt = SystemClock.uptimeMillis();
                frameRendered = false;
                player.seekTo(seekingTarget);
                main.postDelayed(tickTask, POLL_MS);
                return;
            }
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && raw.getWidth() > 0) {
                    // 诊断：getBitmap 可能返回「非 null 但全黑/全透明」的位图，
                    // 与「返回 null」是两种完全不同的故障，必须分开看。
                    int cx = raw.getWidth() / 2;
                    int cy = raw.getHeight() / 2;
                    PlayHeaders.diag("probe bitmap bucket=" + pendingBucket
                            + " target=" + seekingTarget
                            + " seekAge=" + (now - seekAt) + "ms"
                            + " wh=" + raw.getWidth() + "x" + raw.getHeight()
                            + " center=" + Integer.toHexString(raw.getPixel(cx, cy))
                            + " corner=" + Integer.toHexString(raw.getPixel(2, 2)));
                    Bitmap out = raw;
                    if (raw.getWidth() > maxWidth) {
                        out = Bitmap.createScaledBitmap(raw, maxWidth,
                                raw.getHeight() * maxWidth / raw.getWidth(), true);
                    }
                    cache.put(pendingBucket, out);
                    if (pendingCb != null) pendingCb.onBitmap(out);
                    finishGrab();
                    return;
                }
                PlayHeaders.diag("probe bitmap NULL bucket=" + pendingBucket
                        + " state=" + player.getPlaybackState()
                        + " avail=" + textureView.isAvailable()
                        + " wh=" + textureView.getWidth() + "x" + textureView.getHeight());
            } catch (Throwable t) {
                // 个别 ROM 上硬解输出可能取不到帧：放弃本次，不影响播放
                PlayHeaders.diag("probe getBitmap fail: " + t);
            }
        }
        // 超时：本次 seek 后一直没出帧。目标已漂移就转向新目标再试一轮，
        // 否则收工（保持上一帧，预览框静默等待下一次拖动）。
        if (now - seekAt > TIMEOUT_MS) {
            PlayHeaders.diag("probe timeout target=" + seekingTarget
                    + " latest=" + pendingTarget
                    + " state=" + player.getPlaybackState()
                    + " avail=" + textureView.isAvailable());
            if (pendingTarget != seekingTarget) {
                tick();
            } else {
                finishGrab();
            }
            return;
        }
        main.postDelayed(tickTask, POLL_MS);
    }

    private void finishGrab() {
        grabActive = false;
        seekingTarget = -1L;
        pendingCb = null;
        // 不立刻停：保持解码器就绪，接下来的拖动能秒出帧
        pauseSoon();
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

    /**
     * 空闲时让副播放器跟随主播放器位置，保持解码器与缓冲「热」。
     * 用户拖到主播放器已缓冲附近时，seek 命中缓冲区能立刻出帧；
     * 若每次都从冷启动 seek，实测要等 8~15 秒才出画（2026-11-17）。
     */
    public void followTo(long positionMs) {
        if (released || grabActive) return;   // 抓帧进行中不打扰
        long cur = player.getCurrentPosition();
        if (Math.abs(positionMs - cur) < 3000L) return;   // 已足够接近，不打扰
        cancelPauseSoon();
        player.setPlayWhenReady(true);
        player.seekTo(Math.max(0, positionMs));
        pauseSoon();
    }

    /** 取消在途抓帧（onPause / 换源时调用），避免后台继续拉流解码 */
    public void cancel() {
        main.removeCallbacksAndMessages(null);   // 清 tick + pause 任务
        grabActive = false;
        pendingCb = null;
        seekingTarget = -1L;
        player.setPlayWhenReady(false);
    }

    public void release() {
        released = true;
        cancel();
        player.release();
    }
}
