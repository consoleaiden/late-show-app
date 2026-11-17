package com.lateshow.app.ui;

import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.github.rubensousa.previewseekbar.PreviewBar;
import com.github.rubensousa.previewseekbar.media3.PreviewTimeBar;
import com.lateshow.app.R;
import com.lateshow.app.player.PreviewFrameLoader;
import com.lateshow.app.player.ThumbnailProbe;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.util.GestureSeek;

/** ExoPlayer 横屏全屏播放（HLS 直链 + 断点续播 + 滑动快进快退），退出时把进度带回内嵌播放器 */
public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView pv;
    private String slug, epName, title, pic, remarks, sourceName;
    private long vodId;
    /** 真正在播的地址（decorate 后），播放地址工具条展示的就是它 */
    private String streamUrl;
    private int epIndex, lineIndex;
    /** VodSync v1.11.0：线路稳定标识（original_index），null = 老版本详情页未传 */
    private Integer lineKey;
    private long lastSavedSec = 0;

    /** 帧预览：抓帧器与预览装载器（仅「进度条帧预览」开关开启时创建） */
    private ThumbnailProbe probe;
    private PreviewFrameLoader previewLoader;
    /** 拖拽进度条前的控制栏自动隐藏时长，拖拽期间临时禁用自动隐藏 */
    private int savedShowTimeoutMs = -1;
    /** 预览框容器（previewFrameLayout，与 PreviewTimeBar 同父）；松手后手动钉住等帧用 */
    private View previewBox;
    /** 收起预览框的任务：帧到达后 1.6s / 兜底 5s；手动 setVisibility，不走 delegate */
    private Runnable hidePreviewTask;
    /** 松手后钉住预览框的任务：等 delegate 的淡出动画结束后再手动 VISIBLE */
    private Runnable showPreviewBoxTask;
    /** 是否正在拖动进度条：拖动期间不做「跟随预热」，避免打断在途抓帧 */
    private boolean userScrubbing = false;
    /** 与主播放器同一条链路（含广告过滤），抓帧实例复用，保证时间轴一致 */
    private DefaultHttpDataSource.Factory httpFactory;

    /** 5 秒周期：检查「跳过片尾」+ 落盘播放进度（审计 A-14，异常退出也不丢进度） */
    private static final long TICK_MS = 5000L;
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable endingTick = new Runnable() {
        @Override public void run() {
            if (player != null) {
                long durMs = player.getDuration();
                long pos = player.getCurrentPosition();
                if (player.isPlaying()) {
                    int skip = PrefStore.get().skipEndingSec();
                    if (skip > 0 && durMs > 0 && pos >= durMs - skip * 1000L) {
                        player.seekTo(durMs); // 触发 STATE_ENDED，进度按播完保存
                    }
                    // 进度变化超过 3 秒才写一次整表，避免拖动/暂停时高频写盘（A-23）
                    if (durMs > 0 && pos > 0 && Math.abs(pos / 1000 - lastSavedSec) >= 3) {
                        saveProgress(pos / 1000, durMs / 1000);
                    }
                }
            }
            // 帧预览预热：仅在控制栏可见（用户可能拖动）时，让副播放器跟随主播放器位置，
            // 保持解码器/缓冲「热」——用户拖到已缓冲附近时 seek 命中缓冲能立刻出帧。
            // 平时不跟随，避免副播放器一直拉流白耗流量。
            if (probe != null && player != null && !userScrubbing
                    && pv.isControllerFullyVisible()
                    && player.getPlaybackState() == Player.STATE_READY) {
                probe.followTo(player.getCurrentPosition());
            }
            tickHandler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrefStore.init(this);
        com.lateshow.app.util.ApiCache.init(this);
        com.lateshow.app.util.OfflineMedia.init(this);
        com.lateshow.app.util.ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // 沉浸式全屏：隐藏状态栏/导航栏，下滑临时呼出，刘海屏允许延伸进缺口
        hideSystemBars();

        // 左上角退出全屏返回按钮（进度已在 onPause 保存并回传详情页）；箭头白色，黑色画面下可见
        View btnBackFull = findViewById(R.id.btnBackFull);
        com.lateshow.app.util.Icons.tint(btnBackFull, 0xFFFFFFFF); // 与倍速文字同色
        btnBackFull.setOnClickListener(v -> finish());

        Intent it = getIntent();
        String url = it.getStringExtra("url");
        title = it.getStringExtra("title");
        slug = it.getStringExtra("slug");
        vodId = it.getLongExtra("vodId", -1);
        epIndex = it.getIntExtra("epIndex", 0);
        lineIndex = it.getIntExtra("lineIndex", 0);
        int lk = it.getIntExtra("lineKey", -1);
        lineKey = lk >= 0 ? Integer.valueOf(lk) : null;
        epName = it.getStringExtra("epName");
        pic = it.getStringExtra("pic");
        remarks = it.getStringExtra("remarks");
        sourceName = it.getStringExtra("sourceName");
        // 审计 A-13：切主题 / 配置变更重建后优先用已保存的实时播放位置，
        // 不能再用 Intent 里的原始 startMs 把用户已看的进度冲掉。
        long startMs = savedInstanceState != null
                ? savedInstanceState.getLong("resume_pos_ms", it.getLongExtra("startMs", 0))
                : it.getLongExtra("startMs", 0);
        // 直进全屏且无进度：应用「跳过片头」设置
        if (startMs == 0) {
            int skip = PrefStore.get().skipIntroSec();
            if (skip > 0) startMs = skip * 1000L;
        }

        pv = findViewById(R.id.playerView);

        // 播放源站多为 m3u8 直链，不带 Referer（见 API 文档 §9.7/9.8）；
        // 真机实测（2026-11-10）：源站 CDN 对 ExoPlayer 默认 UA 返回 403，统一带浏览器 UA
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(30000)
                .setUserAgent(com.lateshow.app.util.PlayHeaders.UA);
        httpFactory.setDefaultRequestProperties(
                com.lateshow.app.util.PlayHeaders.props(url, false));
        this.httpFactory = httpFactory;

        // 播放器构建：DataSource 经 AdFilterDataSource 包装（m3u8 响应体原位重写剔除
        // 广告），再套主/副播放器共享的磁盘缓存——主播放器播过的分片落盘，副播放器
        // 抓「已看区间」预览帧命中本地，出帧从源站的 7~28 秒降到 1 秒内且零额外流量
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        com.lateshow.app.player.MediaCache.cacheFactory(this,
                                new com.lateshow.app.util.AdFilterDataSource.Factory(httpFactory))))
                .build();
        pv.setPlayer(player);
        // 控制栏/半透明遮罩超时自动隐藏（media3 默认 5 秒）。原来 2 秒太短，
        // 手指抬起就已经消失，容易被当成「没有控制条」（真机 2026-11-13）
        pv.setControllerShowTimeoutMs(5000);
        // 播放设置聚合面板（倍速/广告提报/跳过片头片尾/复制地址/下载），内嵌与全屏共用；
        // 地址按需现取（streamUrl 字段，decorate 后的真正在播直链），并把上次倍速立即应用
        final View playerTools = findViewById(R.id.playerTools);
        com.lateshow.app.util.PlayerPanel.attach(
                findViewById(R.id.btnGear), player, () -> streamUrl);
        com.lateshow.app.util.PlayerPanel.applySavedSpeed(player);
        // 胶囊跟随控制栏显隐：轻点屏幕出现、约 2 秒后随控制栏自动隐藏（与内嵌一致）
        pv.setControllerVisibilityListener(new androidx.media3.ui.PlayerView.ControllerVisibilityListener() {
            @Override public void onVisibilityChanged(int visibility) {
                playerTools.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE);
                com.lateshow.app.util.PlayHeaders.diag("fs ctrl vis=" + visibility
                        + " fullyVisible=" + pv.isControllerFullyVisible());
            }
        });
        // 手势：横向滑动快进/快退（划满全屏 = 总时长 1/2，半屏 = 1/4）；
        // 纵向滑动：右半屏调亮度、左半屏调音量；轻点显隐控制栏。
        // 挂到内容帧而不是 PlayerView 根：控制栏区域要留给进度条/按钮，
        // 否则手势层的 ACTION_DOWN 会把进度条的拖动抢走。
        final TextView seekOverlay = findViewById(R.id.seekOverlay);
        final View gestureTarget = findContentFrame() != null ? findContentFrame() : pv;
        GestureSeek.attach(gestureTarget, 0.5f, new GestureSeek.Callback() {
            @Override public long durationMs() { return player != null ? player.getDuration() : 0; }
            @Override public long positionMs() { return player != null ? player.getCurrentPosition() : 0; }
            @Override public void onSeekStart() {
                com.lateshow.app.util.PlayHeaders.diag("fs gesture seekStart dur="
                        + (player != null ? player.getDuration() : -1));
                if (pv.isControllerFullyVisible()) pv.hideController();
                seekOverlay.setVisibility(View.VISIBLE);
            }
            @Override public void onSeekPreview(long targetMs, String previewText) {
                seekOverlay.setText(previewText);
            }
            @Override public void onSeekEnd(long targetMs) {
                seekOverlay.setVisibility(View.GONE);
                if (player != null) player.seekTo(targetMs);
            }
            @Override public void onTap() {
                com.lateshow.app.util.PlayHeaders.diag("fs gesture tap fullyVisible="
                        + pv.isControllerFullyVisible());
                if (pv.isControllerFullyVisible()) pv.hideController();
                else pv.showController();
            }
        }, new GestureSeek.VerticalCallback() {
            @Override public float currentValue(boolean right) {
                return right ? currentBrightness() : currentVolumeFraction();
            }
            @Override public void onVerticalStart(boolean right, float value) {
                if (pv.isControllerFullyVisible()) pv.hideController();
                seekOverlay.setVisibility(View.VISIBLE);
                seekOverlay.setText(label(right, value));
            }
            @Override public void onVerticalProgress(boolean right, float value) {
                if (right) applyBrightness(value);
                else applyVolumeFraction(value);
                seekOverlay.setText(label(right, value));
            }
            @Override public void onVerticalEnd(boolean right, float value) {
                seekOverlay.setVisibility(View.GONE);
            }
        });

        // 智能去广告：按 TTL 拉取后端规则包 + 冲刷命中上报（老后端无 adfilter 能力时静默跳过）
        com.lateshow.app.util.AdFilter.ensureFresh();
        streamUrl = com.lateshow.app.api.VodSyncApi.get().decorate(url);
        // 进度条帧预览（默认关闭，见 PrefStore.framePreview）：副 ExoPlayer 抓帧 + 预览框跟随。
        // 放在 decorate 之后：抓帧实例用同一个（已 decorate 的）地址，与主播放器完全一致；
        // 且只在这里取一次地址，避免 decorate 每次生成新 token 导致抓帧实例反复换源重载。
        setupFramePreview();
        // startMs 来自详情页内嵌播放器的当前位置，已经在「过滤后时间轴」上：
        // 时间轴映射只在详情页入口做一次，这里再做会重复前移（见 AdFilter.mapStartMs）。
        com.lateshow.app.util.PlayHeaders.diag("fullscreen play " + streamUrl + " startMs=" + startMs);
        MediaItem mi = MediaItem.fromUri(streamUrl);
        player.setMediaItem(mi, Math.max(0, startMs));
        player.prepare();
        player.play();
        tickHandler.postDelayed(endingTick, TICK_MS);

        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) {
                // 比例：显式下发，避免 ARFL 停在 0（= 填满容器）= 竖屏视频被拉成横屏
                applyVideoSize(vs);
                // 跟随视频本身方向：横屏视频→横屏，竖屏视频→竖屏（未知尺寸保持不变）
                if (vidW == 0 || vidH == 0) return;
                int want = vidW >= vidH
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                if (want != getRequestedOrientation()) setRequestedOrientation(want);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                syncKeepScreenOn();
                if (state == Player.STATE_READY) {
                    long dur = player.getDuration() / 1000;
                    if (dur > 0 && lastSavedSec == 0) {
                        // 已知总时长，立即补一条总时长
                        saveProgress(player.getCurrentPosition() / 1000, dur);
                    }
                    // 插桩（部分视频无控制条/无法快进排查）：时间轴属性决定控制条行为
                    try {
                        androidx.media3.common.Timeline.Window w = player.getCurrentTimeline()
                                .getWindow(player.getCurrentMediaItemIndex(),
                                        new androidx.media3.common.Timeline.Window());
                        com.lateshow.app.util.PlayHeaders.diag("fs ready dur=" + player.getDuration()
                                + " winDur=" + (w.durationUs / 1000) + " winSeekable=" + w.isSeekable
                                + " winLive=" + w.isLive() + " periods=" + player.getCurrentTimeline().getWindowCount());
                    } catch (Exception e) {
                        com.lateshow.app.util.PlayHeaders.diag("fs ready timeline info fail: " + e);
                    }
                    // 黑屏自愈：画面容器偶发 0x0（有声音没画面）时强制重排一次
                    if (pv.getWidth() == 0 || pv.getHeight() == 0) {
                        com.lateshow.app.util.PlayHeaders.diag("ready but pv 0x0 -> relayout");
                        pv.requestLayout();
                    }
                } else if (state == Player.STATE_ENDED) {
                    saveProgress(player.getDuration() / 1000, player.getDuration() / 1000);
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                com.lateshow.app.util.PlayHeaders.diag("error " + error.getErrorCodeName()
                        + " msg=" + error.getMessage()
                        + " stream=" + streamUrl);
                for (Throwable c = error.getCause(); c != null; c = c.getCause()) {
                    com.lateshow.app.util.PlayHeaders.diag("  cause " + c.getClass().getSimpleName()
                            + ": " + c.getMessage());
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                syncKeepScreenOn();
            }
        });
    }

    /** 播放/缓冲期间保持屏幕常亮；暂停、播完、idle 时恢复系统息屏策略 */
    /** 最近一次视频尺寸（已折算旋转角） */
    private int vidW = 0, vidH = 0;

    /**
     * 记下视频真实尺寸并立刻下发比例。
     *
     * <p>media3 只在 onVideoSizeChanged 事件那一刻把比例写给内部 AspectRatioFrameLayout；
     * 该值停在 0 时语义是「填满容器」，720×1280 竖屏会被拉成整屏 16:9（画面畸形）。
     * 这里不依赖事件时序，自己算并显式下发，幂等。
     */
    private void applyVideoSize(androidx.media3.common.VideoSize vs) {
        if (vs == null) return;
        int w = vs.width, h = vs.height;
        if (w <= 0 || h <= 0) return;
        if (vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270) {
            int t = w;
            w = h;
            h = t;
        }
        vidW = w;
        vidH = h;
        applyVideoAspect();
    }

    /**
     * 帧预览接线。控制器视图是 PlayerView 懒加载的（setPlayer 时才 inflate），
     * onCreate 里立刻 findViewById 会拿到 null，因此延迟重试。
     */
    private void setupFramePreview() {
        if (probe != null || !PrefStore.get().framePreview()) return;
        pv.post(() -> tryAttachPreview(0));
    }

    private void tryAttachPreview(int attempt) {
        if (probe != null || !PrefStore.get().framePreview()) return;
        PreviewTimeBar tb = pv.findViewById(androidx.media3.ui.R.id.exo_progress);
        ImageView ivPreview = pv.findViewById(R.id.ivPreview);
        View box = pv.findViewById(R.id.previewFrameLayout);
        // tvProbe 在 activity 根布局（与 PlayerView 同级），不在 PlayerView 子树里
        TextureView tvProbe = findViewById(R.id.tvProbe);
        if (tb == null || ivPreview == null || tvProbe == null || box == null) {
            if (attempt < 10) pv.postDelayed(() -> tryAttachPreview(attempt + 1), 150);
            else com.lateshow.app.util.PlayHeaders.diag("frame preview attach FAILED: controls not inflated in 1.5s");
            return;
        }
        previewBox = box;
        // 抓帧实例走与主播放器同一条 DataSource 链路（含广告过滤），否则
        // 主进度是「过滤后时间轴」而抓帧按原始时间轴 seek，预览画面会整体偏移
        probe = new ThumbnailProbe(this,
                com.lateshow.app.player.MediaCache.cacheFactory(this,
                        new com.lateshow.app.util.AdFilterDataSource.Factory(httpFactory)),
                tvProbe, 2000L, 12, 320);
        previewLoader = new PreviewFrameLoader(probe, ivPreview, 2000L);
        tb.setPreviewLoader(previewLoader);
        // 松手后的「钉住等帧」：previewAutoHide=true 让库在松手瞬间收起预览框
        // （delegate 状态=隐藏），而抓帧有固有延迟（命中磁盘缓存也要约 1 秒，内存位图
        // 缓存启动时是空的）——帧到达时框已经没了，用户永远看到黑框。
        // 解法：松手后**手动**把框 setVisibility(VISIBLE) 钉回松手位置。关键在于
        // 不经过 delegate.show()：showingPreview 保持 false，库的 updateProgress 就
        // 既不会挪框（setX 飘移）也不会用主播放器位置喂 loadPreview（画面乱跳）
        // ——这正是之前「反复拖动后预览框飘移」的根因链路。delegate 无感知，
        // 下次拖动时它的 show() 正常接管。收起也手动 setVisibility(INVISIBLE)，
        // 不能在框手动可见时调 tb.hidePreview() 之外再乱动 delegate 状态。
        hidePreviewTask = () -> {
            com.lateshow.app.util.PlayHeaders.diag("preview box hide");
            if (previewBox != null) previewBox.setVisibility(View.INVISIBLE);
        };
        showPreviewBoxTask = () -> {
            // delegate 淡出动画已结束；再次开始拖动时不抢 delegate 的显示逻辑
            if (previewBox != null && !userScrubbing
                    && previewBox.getVisibility() != View.VISIBLE) {
                com.lateshow.app.util.PlayHeaders.diag("preview box pinned");
                previewBox.setVisibility(View.VISIBLE);
            }
        };
        previewLoader.onFrameShown = () -> {
            if (userScrubbing) return;   // 拖动中绝不收起（框由 delegate 管理）
            pv.removeCallbacks(hidePreviewTask);
            // 1.9s = 显示 1.6s + 300ms 钉框延迟补偿（帧同步到达时，框还要
            // 等 delegate 淡出结束才显示，不补偿的话实际只显示 1.3s）
            pv.postDelayed(hidePreviewTask, 1900L);
        };
        // 拖拽期间别让控制栏自动隐藏，否则预览框会跟着消失
        tb.addOnScrubListener(new PreviewBar.OnScrubListener() {
            @Override public void onScrubStart(PreviewBar b) {
                userScrubbing = true;
                // 拖动开始：取消还没触发的收起/钉住任务，预览框跟随 delegate 显示
                if (hidePreviewTask != null) pv.removeCallbacks(hidePreviewTask);
                if (showPreviewBoxTask != null) pv.removeCallbacks(showPreviewBoxTask);
                if (previewLoader != null) previewLoader.setScrubbing(true);
                savedShowTimeoutMs = pv.getControllerShowTimeoutMs();
                pv.setControllerShowTimeoutMs(Integer.MAX_VALUE);
                pv.showController();
            }

            @Override public void onScrubMove(PreviewBar b, int progress, boolean fromUser) { }

            @Override public void onScrubStop(PreviewBar b) {
                userScrubbing = false;
                if (savedShowTimeoutMs >= 0) pv.setControllerShowTimeoutMs(savedShowTimeoutMs);
                savedShowTimeoutMs = -1;
                // 不立刻停副播放器：保持 READY/缓冲，紧接着的拖动能秒出帧；
                // 由 ThumbnailProbe.pauseSoon() 在空闲 12s 后真正暂停。
                if (probe != null) probe.pauseSoon();
                // 库 autoHide 刚把预览框收起：手动钉回松手位置等 flush 补抓帧
                // （5s 兜底收起；帧到达时 onFrameShown 把收起提前到 1.6s）。
                // 注意 delegate.hide() 是**淡出动画**，动画结束时会把框设回
                // INVISIBLE——立即 setVisibility(VISIBLE) 会被它覆盖（真机实测框
                // 闪一下就没了），所以延迟到淡出结束后（300ms）再钉住显示。
                // 顺序必须先钉框再 flush：缓存命中时 onFrameShown 同步触发，
                // 会把收起定时覆盖成 1.6s。
                if (previewBox != null) {
                    pv.removeCallbacks(showPreviewBoxTask);
                    pv.postDelayed(showPreviewBoxTask, 300L);
                    pv.removeCallbacks(hidePreviewTask);
                    pv.postDelayed(hidePreviewTask, 5000L);
                }
                // 补抓松手时的最终位置（拖动中可能被最小间隔/同桶保护跳过）：
                // 帧到达后显示 1.6s 并入缓存——下次拖到同位置立即显示
                if (previewLoader != null) previewLoader.flush(b.getProgress());
                if (previewLoader != null) previewLoader.setScrubbing(false);
            }
        });
        if (streamUrl != null) probe.setMedia(streamUrl);
        com.lateshow.app.util.PlayHeaders.diag("frame preview attached");
    }

    /** 回到前台时按当前偏好对齐：开关可能在设置页被改动 */
    private void syncFramePreview() {
        if (PrefStore.get().framePreview()) {
            setupFramePreview();
        } else if (probe != null) {
            probe.release();
            probe = null;
            previewLoader = null;
            // 可能在松手等帧期间被关掉：把钉住的预览框和收起/钉住任务一并清掉
            if (hidePreviewTask != null) pv.removeCallbacks(hidePreviewTask);
            if (showPreviewBoxTask != null) pv.removeCallbacks(showPreviewBoxTask);
            if (previewBox != null) previewBox.setVisibility(View.INVISIBLE);
        }
    }

    /** 按类型找到内部内容帧（AspectRatioFrameLayout） */
    private androidx.media3.ui.AspectRatioFrameLayout findContentFrame() {
        if (pv == null) return null;
        for (int i = 0; i < pv.getChildCount(); i++) {
            View c = pv.getChildAt(i);
            if (c instanceof androidx.media3.ui.AspectRatioFrameLayout) {
                return (androidx.media3.ui.AspectRatioFrameLayout) c;
            }
        }
        return null;
    }

    /**
     * 按视频比例 fit 内容帧：先 setAspectRatio，再把 LayoutParams 钉成精确尺寸并居中，
     * 未生效则延迟复查。只靠 setAspectRatio 的 requestLayout 在部分页面状态下不可靠。
     */
    private void applyVideoAspect() {
        if (pv == null || vidW <= 0 || vidH <= 0) return;
        androidx.media3.ui.AspectRatioFrameLayout arfl = findContentFrame();
        if (arfl == null) return;
        final float aspect = (float) vidW / (float) vidH;
        arfl.setAspectRatio(aspect);
        pinContentFrame(arfl, aspect, 0);
    }

    private void pinContentFrame(androidx.media3.ui.AspectRatioFrameLayout arfl, float aspect, int tries) {
        if (pv == null || tries > 20) return;
        int cw = pv.getWidth(), ch = pv.getHeight();
        if (cw <= 0 || ch <= 0) {
            pv.postDelayed(() -> pinContentFrame(arfl, aspect, tries + 1), 120);
            return;
        }
        int w, h;
        if ((float) cw / ch > aspect) {
            h = ch;
            w = Math.round(ch * aspect);
        } else {
            w = cw;
            h = Math.round(cw / aspect);
        }
        if (arfl.getWidth() == w && arfl.getHeight() == h) return;   // 已生效，停止复查
        arfl.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                w, h, android.view.Gravity.CENTER));
        pv.requestLayout();
        com.lateshow.app.util.PlayHeaders.diag("fs aspect pin " + vidW + "x" + vidH
                + " -> " + w + "x" + h + " (try " + tries + ")");
        pv.postDelayed(() -> pinContentFrame(arfl, aspect, tries + 1), 120);
    }

    private void syncKeepScreenOn() {
        boolean on = player != null && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_IDLE
                && player.getPlaybackState() != Player.STATE_ENDED;
        if (pv != null) pv.setKeepScreenOn(on);
    }

    // ==================== 全屏纵向手势：右半屏亮度 / 左半屏音量 ====================

    private String label(boolean right, float value) {
        int pct = Math.round(value * 100f);
        return right ? "亮度 " + pct + "%" : "音量 " + pct + "%";
    }

    /** 当前窗口亮度：优先取本窗口已设值，否则读系统亮度作起步基准 */
    private float currentBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.screenBrightness >= 0f) return Math.min(1f, Math.max(0f, lp.screenBrightness));
        try {
            int sys = android.provider.Settings.System.getInt(
                    getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS);
            return Math.min(1f, Math.max(0f, sys / 255f));
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void applyBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.min(1f, Math.max(0.02f, value));  // 保底 2%，避免全黑
        getWindow().setAttributes(lp);
    }

    private AudioManager audio() {
        return (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    private float currentVolumeFraction() {
        AudioManager am = audio();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return 0f;
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private void applyVolumeFraction(float value) {
        AudioManager am = audio();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return;
        int idx = Math.round(Math.min(1f, Math.max(0f, value)) * max);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);  // flag=0：不弹系统音量面板
    }

    /** 隐藏系统状态栏与导航栏（真全屏）；回到前台/焦点恢复时重新隐藏 */
    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat c =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars(); // 对话框/通知等抢焦点后恢复时补隐藏
    }

    /** 配置变更（含切主题）重建时保存实时播放位置，供 onCreate 恢复（A-13 / A-12） */
    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (player != null) out.putLong("resume_pos_ms", player.getCurrentPosition());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 开关可能在设置页改过：每次回到前台按当前偏好重新决定地址条显隐
        com.lateshow.app.util.StreamBar.bind(findViewById(android.R.id.content), streamUrl);
        syncFramePreview();
    }

    private void saveProgress(long playedSec, long totalSec) {
        lastSavedSec = playedSec;
        // 进度回写必须换回原始时间轴：历史是「这部剧看到哪」的语义，与本次是否开了
        // 去广告无关。不换算的话，开过滤看一次就会把记录往后推一个广告时长。
        playedSec = com.lateshow.app.util.AdFilter.unmapPosition(streamUrl, playedSec * 1000) / 1000;
        if (totalSec > 0) {
            totalSec = com.lateshow.app.util.AdFilter.unmapPosition(streamUrl, totalSec * 1000) / 1000;
        }
        PrefStore.HistItem h = new PrefStore.HistItem();
        h.slug = slug;
        h.sourceName = sourceName;
        h.vodId = vodId;
        h.title = title != null && title.contains(" · ") ? title.substring(0, title.lastIndexOf(" · ")) : title;
        h.pic = pic;
        h.remarks = remarks;
        h.lineIndex = lineIndex;   // 兼容字段
        h.lineKey = lineKey;       // 稳定键：回到详情页/下次进入时按它恢复线路
        h.epIndex = epIndex;
        // saveHist 是整条替换：把详情页/历史里已有的离线展示字段带过来，避免退出全屏后被清空
        PrefStore.HistItem old = PrefStore.get().getHist(slug, vodId);
        if (old != null) {
            h.typeName = old.typeName;
            h.content = old.content;
            h.epCount = old.epCount;
            h.cached = old.cached;
        }
        h.epName = epName;
        h.playedSec = playedSec;
        h.totalSec = totalSec;
        h.lastPlayedAt = System.currentTimeMillis();
        PrefStore.get().saveHist(h);
    }

    /** 退出全屏：与进场对称的缩放淡出，回到详情页内嵌播放器时不会硬切 */
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fs_back_in, R.anim.fs_out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面不可见：立刻停掉在途抓帧，避免后台继续拉流解码
        if (previewLoader != null) previewLoader.cancel();
        if (player != null) {
            long pos = player.getCurrentPosition();
            long dur = player.getDuration() > 0 ? player.getDuration() / 1000 : 0;
            saveProgress(pos / 1000, dur);
            // 把进度带回详情页内嵌播放器
            Intent d = new Intent();
            d.putExtra("pos", pos);
            setResult(RESULT_OK, d);
            player.pause();
            syncKeepScreenOn();   // 暂停后立即放开常亮，允许系统息屏
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tickHandler.removeCallbacksAndMessages(null);
        if (hidePreviewTask != null && pv != null) pv.removeCallbacks(hidePreviewTask);
        if (showPreviewBoxTask != null && pv != null) pv.removeCallbacks(showPreviewBoxTask);
        if (probe != null) {
            probe.release();
            probe = null;
            previewLoader = null;
            previewBox = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
