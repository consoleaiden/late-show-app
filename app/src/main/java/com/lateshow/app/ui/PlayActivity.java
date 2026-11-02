package com.lateshow.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.lateshow.app.MainActivity;
import com.lateshow.app.R;
import com.lateshow.app.adapter.VideoAdapter;
import com.lateshow.app.api.VodSyncApi;
import com.lateshow.app.model.CardItem;
import com.lateshow.app.model.Models;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.util.ApiCache;
import com.lateshow.app.util.GestureSeek;
import com.lateshow.app.util.OfflineMedia;

import java.util.ArrayList;
import java.util.List;

/** 播放详情页：竖版海报 + 简介 + 收藏 + 线路/选集网格 + 内嵌播放器（点全屏进横屏） */
public class PlayActivity extends AppCompatActivity {

    private String slug, sourceName, title, pic, remark;
    private long vodId;
    private Models.VideoDetail detail;
    private int curLine = 0, curEp = 0;
    /** true = 详情来自本地缓存（服务器不可达），此时界面标注「离线」 */
    private boolean offlineMode = false;

    private TextView tvTitle, tvMeta, tvDesc, btnToggleDesc, btnPlay, epCount, badge, breadcrumb;
    private com.airbnb.lottie.LottieAnimationView btnFav;
    private RecyclerView lines, epGrid;

    // 内嵌播放器
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView seekOverlay;
    private View playerContainer, posterBox;
    private String curUrl;
    private final Handler saveHandler = new Handler(Looper.getMainLooper());

    /** 全屏播放返回后带回进度，无缝续播内嵌播放 */
    private final ActivityResultLauncher<Intent> fsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                long pos = result.getData() != null ? result.getData().getLongExtra("pos", 0) : 0;
                if (player != null) {
                    if (pos > 0) player.seekTo(pos);
                    player.play();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrefStore.init(this);
        ApiCache.init(this);
        OfflineMedia.init(this);
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play);
        com.lateshow.app.util.LottieIcon.bindTree(findViewById(android.R.id.content));
        // 封面播放键在深色圆钮上，固定白色
        android.view.View playCover = findViewById(R.id.imgPlayCover);
        if (playCover != null) com.lateshow.app.util.LottieIcon.tint(playCover, 0xF2FFFFFF);

        Intent it = getIntent();
        slug = it.getStringExtra("slug");
        vodId = it.getLongExtra("vodId", -1);
        title = it.getStringExtra("title");
        pic = it.getStringExtra("pic");
        remark = it.getStringExtra("remark");
        sourceName = it.getStringExtra("sourceName");

        tvTitle = findViewById(R.id.tvTitle);
        tvMeta = findViewById(R.id.tvMeta);
        tvDesc = findViewById(R.id.tvDesc);
        btnToggleDesc = findViewById(R.id.btnToggleDesc);
        btnPlay = findViewById(R.id.btnPlay);
        btnFav = findViewById(R.id.btnFav);
        epCount = findViewById(R.id.epCount);
        badge = findViewById(R.id.badge);
        breadcrumb = findViewById(R.id.breadcrumb);
        lines = findViewById(R.id.lines);
        epGrid = findViewById(R.id.epGrid);
        playerView = findViewById(R.id.playerView);
        seekOverlay = findViewById(R.id.seekOverlay);
        playerContainer = findViewById(R.id.playerContainer);
        posterBox = findViewById(R.id.posterBox);

        tvTitle.setText(title);
        breadcrumb.setText("DETAIL / " + (sourceName != null ? sourceName : slug));
        badge.setText(remark != null && !remark.isEmpty() ? remark : "详情");
        if (pic != null) {
            Glide.with(this).load(pic).placeholder(R.color.ink700).centerCrop().into((android.widget.ImageView) findViewById(R.id.cover));
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlayCover).setOnClickListener(v -> playCurrent(0));
        btnPlay.setOnClickListener(v -> {
            // 已在内嵌播放中：按钮变为 播放/暂停 切换
            if (player != null) {
                if (player.isPlaying()) player.pause();
                else player.play();
            } else {
                playCurrent(0);
            }
        });
        btnToggleDesc.setOnClickListener(v -> {
            boolean expanded = tvDesc.getMaxLines() == Integer.MAX_VALUE;
            tvDesc.setMaxLines(expanded ? 3 : Integer.MAX_VALUE);
            btnToggleDesc.setText(expanded ? "展开简介" : "收起简介");
        });

        btnFav.setOnClickListener(v -> {
            PrefStore.FavItem f = new PrefStore.FavItem();
            f.slug = slug;
            f.sourceName = sourceName;
            f.vodId = vodId;
            f.title = title;
            f.pic = pic;
            f.remarks = remark;
            f.typeName = detail != null ? detail.typeName : null;
            f.content = detail != null ? detail.vodContent : null;
            f.epCount = epCountOf(detail);
            PrefStore.get().toggleFav(f);
            boolean fav = PrefStore.get().isFav(slug, vodId);
            // 收藏即把完整播放数据（线路 + 每集地址）落盘，离线也能播
            if (fav && detail != null) {
                OfflineMedia.saveDetail(detail);
                PrefStore.get().fillOfflineMeta(slug, vodId,
                        detail.typeName, detail.vodContent, epCountOf(detail), true);
            } else if (!fav && PrefStore.get().getHist(slug, vodId) == null) {
                // 取消收藏且没有播放历史：连带清掉离线播放数据
                OfflineMedia.delete(slug, vodId);
                PrefStore.get().setOfflineCached(slug, vodId, false);
            }
            // 详情尚未加载完就点了收藏：补一次加载，成功后会把播放数据缓存下来
            if (fav && detail == null) loadDetail();
            syncFavState(true);
            Toast.makeText(this, fav ? "已加入收藏 · 已缓存可离线播放" : "已取消收藏", Toast.LENGTH_SHORT).show();
        });
        syncFavState(false);

        loadDetail();
    }

    private void applyTheme() {
        com.lateshow.app.util.ThemeManager.apply(this);
    }

    /** 收藏态：心形描边(未收藏) / 实心(已收藏)，animate=true 时播放描边→实心过渡 */
    private void syncFavState(boolean animate) {
        boolean fav = PrefStore.get().isFav(slug, vodId);
        com.lateshow.app.util.LottieIcon.tint(btnFav, getColor(fav ? R.color.acc : R.color.t2));
        if (animate) {
            if (fav) {
                btnFav.setSpeed(1f);
                btnFav.setProgress(0f);
                btnFav.playAnimation();
            } else {
                btnFav.setSpeed(-1f);
                btnFav.setProgress(1f);
                btnFav.playAnimation();
            }
        } else {
            btnFav.pauseAnimation();
            btnFav.setProgress(fav ? 1f : 0f);
        }
    }

    private void loadDetail() {
        com.lateshow.app.api.Catalogs.get().detail(slug, vodId,
            new VodSyncApi.ApiCb<Models.DetailResp>() {
                @Override public void onOk(Models.DetailResp r) {
                    if (isDestroyed()) return;
                    if (r.detail == null) {
                        toast("资源不可用，可返回首页重新选择");
                        return;
                    }
                    detail = r.detail;
                    offlineMode = false;
                    renderDetail();
                    // 已收藏 / 有播放历史 → 刷新离线播放数据（覆盖旧线路与地址）
                    if (PrefStore.get().isFav(slug, vodId) || PrefStore.get().getHist(slug, vodId) != null) {
                        OfflineMedia.saveDetail(detail);
                        PrefStore.get().fillOfflineMeta(slug, vodId,
                                detail.typeName, detail.vodContent, epCountOf(detail), true);
                    }
                }
                @Override public void onErr(String m) {
                    if (isDestroyed()) return;
                    // 网络失败：优先用本地缓存的完整播放数据（收藏 / 历史里保存过的）
                    Models.VideoDetail cached = OfflineMedia.loadDetail(slug, vodId);
                    if (cached != null && cached.vodPlayList != null && !cached.vodPlayList.isEmpty()) {
                        detail = cached;
                        offlineMode = true;
                        renderDetail();
                        toast("离线模式：使用本地缓存的播放数据");
                        return;
                    }
                    if (com.lateshow.app.util.AuthGate.handleApiError(PlayActivity.this, m, PlayActivity.this::loadDetail)) {
                        toast("需要 API Token，请在设置页填写后重试");
                        return;
                    }
                    toast("详情加载失败：" + m + (PrefStore.get().isFav(slug, vodId) ? "（该片未缓存播放数据）" : ""));
                }
            });
    }

    /** 分集数：取各线路中最多的一路（不同线路集数可能不同） */
    private static int epCountOf(Models.VideoDetail d) {
        if (d == null || d.vodPlayList == null) return 0;
        int max = 0;
        for (Models.PlayLine l : d.vodPlayList) {
            if (l != null && l.episodes != null) max = Math.max(max, l.episodes.size());
        }
        return max;
    }

    private void renderDetail() {
        tvTitle.setText(detail.vodName);
        if (detail.vodPic != null && !detail.vodPic.isEmpty()) {
            Glide.with(this).load(detail.vodPic).placeholder(R.color.ink700).centerCrop().into((android.widget.ImageView) findViewById(R.id.cover));
        }

        // 元信息：类型 · 来源 · 更新时间
        StringBuilder meta = new StringBuilder();
        if (detail.typeName != null && !detail.typeName.isEmpty()) meta.append(detail.typeName);
        if (sourceName != null && !sourceName.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(sourceName);
        }
        if (detail.vodTime != null && !detail.vodTime.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(com.lateshow.app.util.TimeUtil.stamp(detail.vodTime));
        }
        if (offlineMode) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("离线缓存");
        }
        tvMeta.setText(meta);

        String desc = detail.vodContent;
        if (desc != null) desc = desc.replaceAll("<[^>]+>", "").trim();
        if (desc == null || desc.isEmpty()) desc = "暂无简介";
        tvDesc.setText(desc);

        // 断点续播：读取历史
        PrefStore.HistItem h = PrefStore.get().getHist(slug, vodId);
        if (h != null && h.epIndex > 0) curEp = h.epIndex;

        // 线路
        final List<Models.PlayLine> ls = detail.vodPlayList != null ? detail.vodPlayList : new ArrayList<>();
        if (ls.size() > 1) {
            lines.setVisibility(View.VISIBLE);
            lines.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
            RecyclerView.Adapter<RecyclerView.ViewHolder> la = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                    return new RecyclerView.ViewHolder(getLayoutInflater().inflate(R.layout.item_line, p, false)) {};
                }
                @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
                    TextView tv = (TextView) vh.itemView;
                    Models.PlayLine l = ls.get(pos);
                    tv.setText(l.name != null ? l.name : "线路" + (pos + 1));
                    boolean sel = pos == curLine;
                    tv.setBackgroundResource(sel ? R.drawable.bg_episode_current : R.drawable.bg_smbtn);
                    tv.setTextColor(getColor(sel ? R.color.acc : R.color.t2));
                    tv.setOnClickListener(v -> {
                        curLine = pos;
                        curEp = 0;
                        notifyDataSetChanged();
                        if (epGrid.getAdapter() != null) epGrid.getAdapter().notifyDataSetChanged();
                        updatePlayLabel();
                    });
                }
                @Override public int getItemCount() { return ls.size(); }
            };
            lines.setAdapter(la);
        } else {
            lines.setVisibility(View.GONE);
        }

        // 选集网格
        List<Models.Episode> eps = currentEpisodes();
        epCount.setText(eps.size() + " 集");
        epGrid.setLayoutManager(new GridLayoutManager(this, 5));
        RecyclerView.Adapter<RecyclerView.ViewHolder> ea = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                return new RecyclerView.ViewHolder(getLayoutInflater().inflate(R.layout.item_episode, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
                TextView tv = (TextView) vh.itemView;
                Models.Episode e = eps.get(pos);
                tv.setText(e.name != null ? e.name : "第" + (pos + 1) + "集");
                boolean cur = pos == curEp;
                boolean watched = h != null && pos < h.epIndex;
                tv.setBackgroundResource(cur ? R.drawable.bg_episode_current : R.drawable.bg_episode);
                tv.setTextColor(getColor(cur ? R.color.acc : (watched ? R.color.t3 : R.color.t2)));
                tv.setOnClickListener(v -> {
                    curEp = pos;
                    notifyDataSetChanged();
                    if (lines.getAdapter() != null) lines.getAdapter().notifyDataSetChanged();
                    playCurrent(0);
                });
            }
            @Override public int getItemCount() { return eps.size(); }
        };
        epGrid.setAdapter(ea);
        updatePlayLabel();

        if (h != null && h.playedSec > 0) {
            btnPlay.setText("继续播放 · " + (eps.isEmpty() ? "" : epName(curEp)));
        }
    }

    private List<Models.Episode> currentEpisodes() {
        if (detail == null || detail.vodPlayList == null || detail.vodPlayList.isEmpty()) return new ArrayList<>();
        if (curLine >= detail.vodPlayList.size()) curLine = 0;
        List<Models.Episode> eps = detail.vodPlayList.get(curLine).episodes;
        return eps != null ? eps : new ArrayList<>();
    }

    private String epName(int idx) {
        List<Models.Episode> eps = currentEpisodes();
        if (eps.isEmpty()) return "";
        Models.Episode e = eps.get(Math.min(idx, eps.size() - 1));
        return e.name != null ? e.name : "第" + (idx + 1) + "集";
    }

    private void updatePlayLabel() {
        PrefStore.HistItem h = PrefStore.get().getHist(slug, vodId);
        String prefix;
        if (player != null) {
            prefix = player.isPlaying() ? "暂停 · " : "继续播放 · ";
        } else {
            prefix = (h != null && h.playedSec > 0 && h.epIndex == curEp) ? "继续播放 · " : "播放 · ";
        }
        btnPlay.setText(prefix + epName(curEp));
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面（含进全屏）时保存进度并暂停内嵌播放
        if (player != null) {
            long d = player.getDuration() > 0 ? player.getDuration() / 1000 : 0;
            saveProgress(player.getCurrentPosition() / 1000, d);
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void playCurrent(long seekMs) {
        List<Models.Episode> eps = currentEpisodes();
        if (eps.isEmpty()) {
            toast("暂无可播放的选集");
            return;
        }
        if (curEp >= eps.size()) curEp = 0;
        Models.Episode e = eps.get(curEp);

        PrefStore.HistItem h = PrefStore.get().getHist(slug, vodId);
        long startMs = seekMs;
        if (startMs == 0 && h != null && h.epIndex == curEp) startMs = h.playedSec * 1000;
        // 无历史进度（或换了选集）：应用「跳过片头」设置，从设定秒数开始播放
        if (startMs == 0) {
            int skip = PrefStore.get().skipIntroSec();
            if (skip > 0) startMs = skip * 1000L;
        }

        // 播放即产生历史 → 同时把完整播放数据落盘，保证收藏/历史页离线可播
        if (detail != null) {
            OfflineMedia.saveDetail(detail);
            PrefStore.get().fillOfflineMeta(slug, vodId,
                    detail.typeName, detail.vodContent, epCountOf(detail), true);
        }

        // 先写一条历史占位（播放器内会持续更新进度）
        saveProgress(startMs / 1000, h != null ? h.totalSec : 0);
        startInline(e.url, startMs);
    }

    // ==================== 内嵌播放 ====================

    /** 在详情页内嵌窗口播放（横版 16:9），不进入全屏 */
    private void startInline(String url, long startMs) {
        curUrl = url;
        posterBox.setVisibility(View.GONE);
        playerContainer.setVisibility(View.VISIBLE);
        // 海报隐藏后，徽章/标题/元信息列与下方简介左对齐（去掉原有 14dp 间距）
        View infoCol = findViewById(R.id.infoCol);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) infoCol.getLayoutParams();
        if (lp.leftMargin != 0) {
            lp.leftMargin = 0;
            infoCol.requestLayout();
        }
        if (player == null) {
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(30000);
            player = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                    .build();
            playerView.setPlayer(player);
            playerView.setControllerShowTimeoutMs(2500);
            // 点全屏按钮才进入横屏全屏
            playerView.setFullscreenButtonClickListener(v -> goFullscreen());
            // 倍速 + 播放设置（悬浮工具，见布局右上角）
            com.lateshow.app.util.PlayerPanel.attach(
                    findViewById(R.id.btnSpeed), findViewById(R.id.btnGear), player);
            com.lateshow.app.util.PlayerPanel.applySavedSpeed(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        long d = player.getDuration() / 1000;
                        if (d > 0) saveProgress(player.getCurrentPosition() / 1000, d);
                    } else if (state == Player.STATE_ENDED) {
                        long d = player.getDuration() / 1000;
                        saveProgress(Math.max(0, d), d);
                    }
                }
                @Override public void onIsPlayingChanged(boolean p) {
                    updatePlayLabel();
                }
            });
            attachSeekGesture();
            saveHandler.postDelayed(saveTick, 5000);
        }
        // 若播放地址由本服务代理转发，补上 token 参数（第三方 CDN 直链原样播放）
        String playUrl = VodSyncApi.get().decorate(url);
        player.setMediaItem(MediaItem.fromUri(playUrl), Math.max(0, startMs));
        player.prepare();
        player.play();
    }

    /** 横向滑动手势快进/快退（内嵌窗口）：划满全宽 = 总时长 1/2，半屏 = 1/4 */
    private void attachSeekGesture() {
        GestureSeek.attach(playerView, 0.5f, new GestureSeek.Callback() {
            @Override public long durationMs() { return player != null ? player.getDuration() : 0; }
            @Override public long positionMs() { return player != null ? player.getCurrentPosition() : 0; }
            @Override public void onSeekStart() {
                if (playerView.isControllerFullyVisible()) playerView.hideController();
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
                if (playerView.isControllerFullyVisible()) playerView.hideController();
                else playerView.showController();
            }
        });
    }

    /** 点全屏按钮：暂停内嵌播放并携带当前进度进入横屏全屏 */
    private void goFullscreen() {
        if (player == null || curUrl == null) return;
        long pos = player.getCurrentPosition();
        player.pause();
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("url", curUrl);
        it.putExtra("title", title + " · " + epName(curEp));
        it.putExtra("slug", slug);
        it.putExtra("vodId", vodId);
        it.putExtra("epIndex", curEp);
        it.putExtra("lineIndex", curLine);
        it.putExtra("epName", epName(curEp));
        it.putExtra("pic", pic != null ? pic : (detail != null ? detail.vodPic : null));
        it.putExtra("remarks", remark);
        it.putExtra("sourceName", sourceName);
        it.putExtra("startMs", pos);
        fsLauncher.launch(it);
    }

    /** 每 5 秒落一次播放进度（收藏/历史页可实时展示），顺带做「跳过片尾」检查 */
    private final Runnable saveTick = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) {
                long d = player.getDuration() > 0 ? player.getDuration() / 1000 : 0;
                saveProgress(player.getCurrentPosition() / 1000, d);
                skipEndingIfNeeded();
            }
            saveHandler.postDelayed(this, 5000);
        }
    };

    /** 播放位置进入「片尾区间」时直接跳到结尾结束播放 */
    private void skipEndingIfNeeded() {
        int skip = PrefStore.get().skipEndingSec();
        if (skip <= 0 || player == null) return;
        long dur = player.getDuration();
        if (dur <= 0) return;
        if (player.getCurrentPosition() >= dur - skip * 1000L) {
            player.seekTo(dur); // 触发 STATE_ENDED，进度按播完保存
        }
    }

    private void saveProgress(long playedSec, long totalSec) {
        List<Models.Episode> eps = currentEpisodes();
        Models.Episode e = eps.isEmpty() ? null : eps.get(Math.min(curEp, eps.size() - 1));
        PrefStore.HistItem item = new PrefStore.HistItem();
        item.slug = slug;
        item.sourceName = sourceName;
        item.vodId = vodId;
        item.title = title;
        item.pic = pic != null ? pic : (detail != null ? detail.vodPic : null);
        item.remarks = remark;
        item.typeName = detail != null ? detail.typeName : null;
        item.content = detail != null ? detail.vodContent : null;
        item.epCount = epCountOf(detail);
        item.cached = detail != null; // 播放数据在 playCurrent 时已落盘
        item.lineIndex = curLine;
        item.epIndex = curEp;
        item.epName = e != null ? e.name : null;
        item.playedSec = playedSec;
        item.totalSec = totalSec;
        item.lastPlayedAt = System.currentTimeMillis();
        PrefStore.get().saveHist(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放器返回后刷新继续播放标签与选集状态
        if (detail != null && epGrid.getAdapter() != null) {
            epGrid.getAdapter().notifyDataSetChanged();
            updatePlayLabel();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    public static void start(Context ctx, CardItem item) {
        Intent it = new Intent(ctx, PlayActivity.class);
        it.putExtra("slug", item.slug);
        it.putExtra("vodId", item.vodId);
        it.putExtra("title", item.title);
        it.putExtra("pic", item.pic);
        it.putExtra("remark", item.remark);
        it.putExtra("sourceName", item.sourceName);
        ctx.startActivity(it);
    }
}
