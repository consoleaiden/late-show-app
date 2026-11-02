package com.lateshow.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
    private String slug, epName, title, pic, remarks, sourceName;
    private long vodId;
    private int epIndex, lineIndex;
    private long lastSavedSec = 0;

    /** 周期检查「跳过片尾」 */
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable endingTick = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) {
                int skip = PrefStore.get().skipEndingSec();
                if (skip > 0) {
                    long dur = player.getDuration();
                    if (dur > 0 && player.getCurrentPosition() >= dur - skip * 1000L) {
                        player.seekTo(dur); // 触发 STATE_ENDED，进度按播完保存
                    }
                }
            }
            tickHandler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrefStore.init(this);
        com.lateshow.app.util.ApiCache.init(this);
        com.lateshow.app.util.OfflineMedia.init(this);
        int t = PrefStore.get().theme();
        AppCompatDelegate.setDefaultNightMode(
                t == PrefStore.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : t == PrefStore.THEME_AUTO ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        com.lateshow.app.util.LottieIcon.bindTree(findViewById(android.R.id.content));

        Intent it = getIntent();
        String url = it.getStringExtra("url");
        title = it.getStringExtra("title");
        slug = it.getStringExtra("slug");
        vodId = it.getLongExtra("vodId", -1);
        epIndex = it.getIntExtra("epIndex", 0);
        lineIndex = it.getIntExtra("lineIndex", 0);
        epName = it.getStringExtra("epName");
        pic = it.getStringExtra("pic");
        remarks = it.getStringExtra("remarks");
        sourceName = it.getStringExtra("sourceName");
        long startMs = it.getLongExtra("startMs", 0);
        // 直进全屏且无进度：应用「跳过片头」设置
        if (startMs == 0) {
            int skip = PrefStore.get().skipIntroSec();
            if (skip > 0) startMs = skip * 1000L;
        }

        PlayerView pv = findViewById(R.id.playerView);

        // 播放源站多为 m3u8 直链，不带 Referer（见 API 文档 §9.7/9.8）
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(30000);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                .build();
        pv.setPlayer(player);
        // 倍速 + 播放设置（悬浮工具，右上角），并把上次倍速立即应用
        com.lateshow.app.util.PlayerPanel.attach(
                findViewById(R.id.btnSpeed), findViewById(R.id.btnGear), player);
        com.lateshow.app.util.PlayerPanel.applySavedSpeed(player);

        // 横向滑动手势快进/快退：按总时长比例计算，划满全屏 = 总时长 1/2，半屏 = 1/4
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
        });

        MediaItem mi = MediaItem.fromUri(com.lateshow.app.api.VodSyncApi.get().decorate(url));
        player.setMediaItem(mi, Math.max(0, startMs));
        player.prepare();
        player.play();
        tickHandler.postDelayed(endingTick, 3000);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    long dur = player.getDuration() / 1000;
                    if (dur > 0 && lastSavedSec == 0) {
                        // 已知总时长，立即补一条总时长
                        saveProgress(player.getCurrentPosition() / 1000, dur);
                    }
                } else if (state == Player.STATE_ENDED) {
                    saveProgress(player.getDuration() / 1000, player.getDuration() / 1000);
                }
            }
        });
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
        h.lineIndex = lineIndex;
        h.epIndex = epIndex;
        h.epName = epName;
        h.playedSec = playedSec;
        h.totalSec = totalSec;
        h.lastPlayedAt = System.currentTimeMillis();
        PrefStore.get().saveHist(h);
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
