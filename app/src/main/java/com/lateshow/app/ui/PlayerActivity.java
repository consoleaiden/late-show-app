package com.lateshow.app.ui;

import android.content.Intent;
import android.os.Bundle;
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

/** ExoPlayer 全屏播放（HLS 直链 + 断点续播） */
public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private String slug, epName, title, pic, remarks, sourceName;
    private long vodId;
    private int epIndex, lineIndex;
    private long lastSavedSec = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrefStore.init(this);
        int t = PrefStore.get().theme();
        AppCompatDelegate.setDefaultNightMode(
                t == PrefStore.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : t == PrefStore.THEME_AUTO ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

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

        MediaItem mi = MediaItem.fromUri(url);
        player.setMediaItem(mi, Math.max(0, startMs));
        player.prepare();
        player.play();

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
            long dur = player.getDuration() > 0 ? player.getDuration() / 1000 : 0;
            saveProgress(player.getCurrentPosition() / 1000, dur);
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
