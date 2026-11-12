package com.lateshow.app.ui;

import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.lateshow.app.R;
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
            tickHandler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {        PrefStore.init(this);
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

        // 播放器构建：DataSource 经 AdFilterDataSource 包装，m3u8 响应体在交还播放器前
        // 原位重写（剔除广告 run，内置规则本地执行）；分片请求不受影响，仍直连 CDN
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        new com.lateshow.app.util.AdFilterDataSource.Factory(httpFactory)))
                .build();
        pv.setPlayer(player);
        // 控制栏/半透明遮罩 2 秒后自动隐藏（media3 默认 5 秒）
        pv.setControllerShowTimeoutMs(2000);
        // 倍速 + 播放设置（悬浮工具，右上角），并把上次倍速立即应用
        final View playerTools = findViewById(R.id.playerTools);
        com.lateshow.app.util.PlayerPanel.attach(
                findViewById(R.id.btnSpeed), findViewById(R.id.btnGear), player);
        com.lateshow.app.util.PlayerPanel.applySavedSpeed(player);
        // 胶囊跟随控制栏显隐：轻点屏幕出现、约 2 秒后随控制栏自动隐藏（与内嵌一致）
        pv.setControllerVisibilityListener(new androidx.media3.ui.PlayerView.ControllerVisibilityListener() {
            @Override public void onVisibilityChanged(int visibility) {
                playerTools.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE);
            }
        });
        // 手势：横向滑动快进/快退（划满全屏 = 总时长 1/2，半屏 = 1/4）；
        // 纵向滑动：右半屏调亮度、左半屏调音量；轻点显隐控制栏
        final TextView seekOverlay = findViewById(R.id.seekOverlay);
        GestureSeek.attach(pv, 0.5f, new GestureSeek.Callback() {
            @Override public long durationMs() { return player != null ? player.getDuration() : 0; }
            @Override public long positionMs() { return player != null ? player.getCurrentPosition() : 0; }
            @Override public void onSeekStart() {
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
        com.lateshow.app.util.PlayHeaders.diag("fullscreen play " + streamUrl + " startMs=" + startMs);
        MediaItem mi = MediaItem.fromUri(streamUrl);
        player.setMediaItem(mi, Math.max(0, startMs));
        player.prepare();
        player.play();
        tickHandler.postDelayed(endingTick, TICK_MS);

        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) {
                // 跟随视频本身方向：横屏视频→横屏，竖屏视频→竖屏（未知尺寸保持不变）
                if (vs.width == 0 || vs.height == 0) return;
                int want = vs.width >= vs.height
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
                        + " stream=" + streamUrl);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                syncKeepScreenOn();
            }
        });
    }

    /** 播放/缓冲期间保持屏幕常亮；暂停、播完、idle 时恢复系统息屏策略 */
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
    }

    private void saveProgress(long playedSec, long totalSec) {
        lastSavedSec = playedSec;
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
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
