package com.lateshow.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.motion.widget.MotionLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
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
import com.lateshow.app.util.GestureSeek;
import com.lateshow.app.util.HeroMotionLayout;
import com.lateshow.app.util.LottieIcon;
import com.lateshow.app.util.OfflineMedia;
import com.lateshow.app.util.TimeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 详情页（单 Activity + Fragment 架构）。
 *
 * <p>为什么是 Fragment：小米相册那种「手指拖着页面缩小、松手回弹/关闭」的跟手感，
 * 必须让详情页和视频列表在同一个窗口里 —— 列表要被真实地"露出来"，而不是靠截图假装。
 * 所以详情页从独立 Activity 改成挂在 {@link MainActivity#openDetail} 上的全屏 Fragment。
 *
 * <p>一镜到底由 {@link HeroMotionLayout}（MotionLayout）承载：
 * progress 0 = 整页展开，1 = 缩到列表卡片位置。进入动画、跟手拖动、松手回弹、
 * 关闭回落全部只是「谁在推这个 progress」，视觉表现完全一致，不会出现两套动画打架。
 * 卡片的屏幕坐标随列表滚动变化，因此 ds_closed 状态在运行时用 ConstraintSet 覆盖
 * （见 {@link #buildClosedState()}）。
 *
 * <p>配色分两段（见 colors.xml 的 heroBand 注释）：信息带（海报 + 片名 / 分类 / 演员 / 标签）
 * 固定用偏深底色 {@code heroBand}，海报底部的渐变才有落点；信息带以下（简介 / 选集 / 推荐）
 * 用主题页底 {@code ink900}（与设置页同色）和主题文字色，随明暗主题切换。
 */
public class DetailFragment extends Fragment {

    public static final String TAG = "detail";

    private static final String ARG_SLUG = "slug";
    private static final String ARG_VOD_ID = "vodId";
    private static final String ARG_TITLE = "title";
    private static final String ARG_PIC = "pic";
    private static final String ARG_REMARK = "remark";
    private static final String ARG_SOURCE_NAME = "sourceName";
    private static final String ARG_CARD = "cardBounds";   // [x, y, w, h] 屏幕坐标

    /** 进入：卡片 → 整页 */
    private static final int ENTER_MS = 430;
    /** 松手后自动关闭 */
    private static final int CLOSE_MS = 260;
    /** 松手回弹归位 */
    private static final int SPRING_BACK_MS = 320;
    /** 拖过这个比例（或向下甩得够快）就关闭 */
    private static final float CLOSE_THRESHOLD = 0.3f;
    private static final float FLING_VELOCITY = 900f;
    /** 收起到底时页面的圆角，对齐列表卡片封面 */
    private static final int CARD_RADIUS_DP = 8;

    /** 兜底解析网页播放页用（只在 ExoPlayer 报错时触发一次，不进主流程） */
    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final java.util.regex.Pattern M3U8_PATTERN =
            java.util.regex.Pattern.compile("https?://[^\"'\\s<>\\\\]+\\.m3u8[^\"'\\s<>\\\\]*");
    private static final okhttp3.OkHttpClient pageHttp = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private String slug, sourceName, title, pic, remark;
    private long vodId;
    private Models.VideoDetail detail;
    private int curLine = 0, curEp = 0;
    /** true = 详情来自本地缓存（服务器不可达），此时界面标注「离线」 */
    private boolean offlineMode = false;

    private HeroMotionLayout motion;
    private View scrim, surface, playRoot, topScrim, statusScrim, heroFade;
    /** 整页底色（信息带以下，与主题页底同色）：收起时淡到全透明，只留海报落到卡片上 */
    private ColorDrawable pageBg;
    /** 信息带底色（海报 + 信息模块）：同样在收起时淡出，否则会留一块比卡片大的灰板 */
    private ColorDrawable bandBg;
    private View heroBand;
    private FrameLayout heroWrap;
    private ImageView cover;
    private View btnPlayCover;
    private LinearLayout infoModule, contentCol, tagRow, epPagerRow;
    private android.widget.HorizontalScrollView epPagerScroll;
    private android.widget.ScrollView scrollPlay;

    /** 选集分页：长剧一次铺 700+ 格会让整页测量卡死，按页切换（见 epPagerRow） */
    private int epPage = 0;
    private int epPageSize = 50;
    private int epSpan = 5;
    /** 内嵌播放中：收起不再走淡出，而是播放器淡出、露出海报后照常形变 */
    private boolean inlineActive = false;
    /** 上次看到的集数（只用来把历史里看过的集数画成弱色，避免每次 bind 都读一遍 SharedPreferences） */
    private int historyEp = -1;
    private String curRawUrl;      // 原始地址（可能是网页播放页）
    private String curPlayUrl;     // 归一化后真正在播的直链
    private long curStartMs;
    private boolean triedHtmlResolve;
    /** 进场动画结束后回调（详情页内跳详情时，宿主靠它摘掉下面的旧页） */
    private Runnable enterEndAction;
    private int[] cardBounds;
    private boolean closing = false;      // 收起过程（决定末端是否整页淡出）
    private boolean dismissed = false;
    private ValueAnimator motionAnim;
    private float radiusPx = 0f;
    /** 顶部遮罩的两段基础 alpha（由滚动位置决定，0~1）；收起时再乘整体透明度 */
    private float scrimTopBase = 1f, scrimStatusBase = 0f, lastBgAlpha = 1f;

    private TextView tvTitle, tvMeta, tvDesc, btnToggleDesc, btnPlay, epCount,
            chipSource, chipUpdate, tvActor;
    private View rowActor, rowTags;
    private com.airbnb.lottie.LottieAnimationView btnFav;
    private RecyclerView lines, epGrid;

    // 相关推荐（VodSync v1.9.0 预计算，直连模式为同分类兜底）
    private View relatedSection;
    private RecyclerView relatedList;
    private TextView tvRelatedHint;
    private static final int RELATED_LIMIT = 12;

    // 相同演员作品（v1.9.1 /api/v1/star/related + v1.9.2 /api/v1/star/works）：
    // 「全部」Tab 用 star/related 的 top50 本地展示；明星 Tab 按需拉完整作品并缓存，
    // 旧服务端（无 works 接口）自动降级为本地 match_stars 过滤
    private View castSection;
    private RecyclerView castTabs, castList;
    private TextView tvCastHint;
    private static final int STAR_LIMIT = 50;    // star/related 一次拉足，「全部」Tab 不再发请求
    private static final int CAST_MAX_TABS = 6;  // 「全部」之外最多 6 个明星 Tab
    private Models.StarRelatedResp starResp;
    private String curStarTab;                   // null = 「全部」Tab
    private final Map<String, List<Models.StarCard>> starWorksCache = new HashMap<>();
    private final Map<String, Integer> starTotalCache = new HashMap<>();  // term -> 完整作品总数
    private final Set<String> starWorksLoading = new HashSet<>();
    private int castGen = 0;                     // 防串台：详情切换后丢弃旧请求回调
    private String castHintAll;                  // 「全部」Tab 的提示语（切换 Tab 时恢复用）

    // 内嵌播放器
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView seekOverlay;
    private View playerContainer;
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

    // ==================== 构造 / 生命周期 ====================

    public static DetailFragment newInstance(CardItem item, int[] cardBounds) {
        DetailFragment f = new DetailFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SLUG, item.slug);
        b.putLong(ARG_VOD_ID, item.vodId);
        b.putString(ARG_TITLE, item.title);
        b.putString(ARG_PIC, item.pic);
        b.putString(ARG_REMARK, item.remark);
        b.putString(ARG_SOURCE_NAME, item.sourceName);
        if (cardBounds != null && cardBounds.length == 4) b.putIntArray(ARG_CARD, cardBounds);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle st) {
        Bundle a = getArguments();
        if (a != null) {
            slug = a.getString(ARG_SLUG);
            vodId = a.getLong(ARG_VOD_ID, -1);
            title = a.getString(ARG_TITLE);
            pic = a.getString(ARG_PIC);
            remark = a.getString(ARG_REMARK);
            sourceName = a.getString(ARG_SOURCE_NAME);
            cardBounds = a.getIntArray(ARG_CARD);
        }

        motion = v.findViewById(R.id.motionRoot);
        scrim = v.findViewById(R.id.scrim);
        surface = v.findViewById(R.id.detailSurface);
        playRoot = v.findViewById(R.id.playRoot);
        topScrim = v.findViewById(R.id.topScrim);
        statusScrim = v.findViewById(R.id.statusScrim);
        heroFade = v.findViewById(R.id.heroFade);
        scrollPlay = v.findViewById(R.id.scrollPlay);
        heroBand = v.findViewById(R.id.heroBand);
        heroWrap = v.findViewById(R.id.heroWrap);
        cover = v.findViewById(R.id.cover);
        btnPlayCover = v.findViewById(R.id.btnPlayCover);
        infoModule = v.findViewById(R.id.infoModule);
        contentCol = v.findViewById(R.id.contentCol);
        epPagerScroll = v.findViewById(R.id.epPagerScroll);
        epPagerRow = v.findViewById(R.id.epPagerRow);

        tvTitle = v.findViewById(R.id.tvTitle);
        tvMeta = v.findViewById(R.id.tvMeta);
        tvDesc = v.findViewById(R.id.tvDesc);
        btnToggleDesc = v.findViewById(R.id.btnToggleDesc);
        btnPlay = v.findViewById(R.id.btnPlay);
        btnFav = v.findViewById(R.id.btnFav);
        epCount = v.findViewById(R.id.epCount);
        chipSource = v.findViewById(R.id.chipSource);
        chipUpdate = v.findViewById(R.id.chipUpdate);
        tvActor = v.findViewById(R.id.tvActor);
        rowActor = v.findViewById(R.id.rowActor);
        rowTags = v.findViewById(R.id.rowTags);
        tagRow = v.findViewById(R.id.tagRow);
        lines = v.findViewById(R.id.lines);
        epGrid = v.findViewById(R.id.epGrid);
        relatedSection = v.findViewById(R.id.relatedSection);
        relatedList = v.findViewById(R.id.relatedList);
        tvRelatedHint = v.findViewById(R.id.tvRelatedHint);
        castSection = v.findViewById(R.id.castSection);
        castTabs = v.findViewById(R.id.castTabs);
        castList = v.findViewById(R.id.castList);
        tvCastHint = v.findViewById(R.id.tvCastHint);
        playerView = v.findViewById(R.id.playerView);
        seekOverlay = v.findViewById(R.id.seekOverlay);
        playerContainer = v.findViewById(R.id.playerContainer);

        LottieIcon.bindTree(v);
        // 封面播放键在深色圆钮上，固定白色
        LottieIcon.tint(v.findViewById(R.id.imgPlayCover), 0xF2FFFFFF);

        // 页面分两段：信息带（固定偏深底）+ 信息带以下（跟随主题的页底）
        bandBg = new ColorDrawable(getColor(R.color.heroBand));
        heroBand.setBackground(bandBg);
        pageBg = new ColorDrawable(getColor(R.color.ink900));
        playRoot.setBackground(pageBg);

        // 海报区：横向满屏、纵向半屏（屏幕高度的一半）
        ViewGroup.LayoutParams hlp = heroWrap.getLayoutParams();
        hlp.height = getResources().getDisplayMetrics().heightPixels / 2;
        heroWrap.setLayoutParams(hlp);

        tvTitle.setText(title);
        String srcLabel = sourceName != null && !sourceName.isEmpty() ? sourceName : slug;
        if (srcLabel != null && !srcLabel.isEmpty()) chipSource.setText(srcLabel);
        else chipSource.setVisibility(View.GONE);
        if (remark != null && !remark.trim().isEmpty()) chipUpdate.setText(remark.trim());
        else chipUpdate.setVisibility(View.GONE);
        if (pic != null) {
            Glide.with(this).load(pic).placeholder(R.color.heroSurface).centerCrop().into(cover);
        }

        applyInsets(v);
        setupMotion(st);
        setupScrollScrim();

        v.findViewById(R.id.btnPlayCover).setOnClickListener(x -> playCurrent(0));
        btnPlay.setOnClickListener(x -> {
            if (player != null) {
                if (player.isPlaying()) player.pause();
                else player.play();
            } else {
                playCurrent(0);
            }
        });
        btnToggleDesc.setOnClickListener(x -> {
            boolean expanded = tvDesc.getMaxLines() == Integer.MAX_VALUE;
            tvDesc.setMaxLines(expanded ? 3 : Integer.MAX_VALUE);
            btnToggleDesc.setText(expanded ? "展开简介" : "收起简介");
        });
        btnFav.setOnClickListener(x -> toggleFav());
        syncFavState(false);

        loadDetail();
    }

    /** 状态栏 / 导航栏：内容延伸到系统栏下面，海报才能真正盖住顶部那一栏 */
    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            // 底部留给导航条/手势条，避免最后一屏内容被遮住
            scrollPlay.setPadding(0, 0, 0, bars.bottom);
            int scrimH = bars.top + dp(84);
            setHeight(topScrim, scrimH);
            setHeight(statusScrim, scrimH);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static void setHeight(View v, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.height != h) {
            lp.height = h;
            v.setLayoutParams(lp);
        }
    }

    /** 海报滚出状态栏后，把深色遮罩换成底色遮罩，文字不会压着一层灰 */
    private void setupScrollScrim() {
        scrollPlay.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            float t = Math.min(1f, sy / (float) Math.max(1, heroWrap.getHeight() * 0.55f));
            scrimTopBase = 1f - t;
            scrimStatusBase = t;
            applyScrimAlpha(lastBgAlpha);
        });
    }

    /**
     * 顶部两层遮罩的 alpha = 滚动决定的基础值 × 收起进度决定的整体透明度。
     *
     * <p>不乘这个整体透明度的话，收起到底时页面只有卡片那么大，128dp 的顶部遮罩
     * 会把整张海报压黑 —— 而列表卡片是原色的，摘下浮层那一下就会"亮一下"。
     */
    private void applyScrimAlpha(float overall) {
        if (topScrim != null) topScrim.setAlpha(scrimTopBase * overall);
        if (statusScrim != null) statusScrim.setAlpha(scrimStatusBase * overall);
    }

    // ==================== 一镜到底（MotionLayout 跟手） ====================

    /**
     * 初始化收起态并播进入动画。
     * 首帧先把整页藏起来（INVISIBLE），等布局完成、算好卡片目标位置后再显示，
     * 否则会先闪一帧「铺满的完整页面」。
     */
    private void setupMotion(Bundle st) {
        surface.setVisibility(View.INVISIBLE);
        surface.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                if (radiusPx <= 0.5f) o.setRect(0, 0, v.getWidth(), v.getHeight());
                else o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        surface.setClipToOutline(true);
        // 海报区同样按收起进度收圆角：末端四个角与卡片完全一致，撤掉浮层时不会露出方角
        heroWrap.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                if (radiusPx <= 0.5f) o.setRect(0, 0, v.getWidth(), v.getHeight());
                else o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        heroWrap.setClipToOutline(true);

        motion.setOnDragCallback(new HeroMotionLayout.OnDragCallback() {
            @Override public boolean canDragDown() {
                return scrollPlay.getScrollY() <= 0;
            }
            @Override public void onDragStart() {
                cancelMotionAnim();
                fireEnterEnd();   // 进场被手势打断：旧页的清理不能丢，否则会一直压在下面
                closing = true;
            }
            @Override public void onDragProgress(float progress) {
                setP(progress);
            }
            @Override public void onRelease(float progress, float vy) {
                if (progress > CLOSE_THRESHOLD || vy > FLING_VELOCITY) {
                    animateTo(progress, 1f, CLOSE_MS, new AccelerateInterpolator(1.3f),
                            DetailFragment.this::dismiss);
                } else {
                    closing = false;
                    animateTo(progress, 0f, SPRING_BACK_MS, new DecelerateInterpolator(1.8f), null);
                }
            }
        });

        final boolean restore = st != null;
        motion.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                motion.getViewTreeObserver().removeOnPreDrawListener(this);
                buildClosedState();
                boolean morph = cardBounds != null && cardBounds.length == 4;
                if (restore || !morph) {
                    // 重建，或没有卡片起点（详情页内跳详情且来源卡片已不存在）：直接以展开态出现
                    setP(0f);
                    fireEnterEnd();
                } else {
                    closing = false;
                    setP(1f);
                    animateTo(1f, 0f, ENTER_MS, new DecelerateInterpolator(1.5f), DetailFragment.this::fireEnterEnd);
                }
                surface.setVisibility(View.VISIBLE);
                return true;
            }
        });
    }

    /** 进场动画结束（含「无转场直接展开」的情况）：宿主用它来摘掉下面那张旧的详情页 */
    public void setOnEnterEnd(Runnable r) {
        this.enterEndAction = r;
    }

    private void fireEnterEnd() {
        Runnable r = enterEndAction;
        enterEndAction = null;
        if (r != null) r.run();
    }

    /**
     * 把 MotionScene 的 ds_closed 状态改写成「列表卡片此刻的位置」。
     *
     * <p>卡片位置只有运行时才知道（列表可能滚过、屏幕尺寸也可能变），所以这里按当前
     * 海报在窗口里的位置反算：整页以自身中心为 pivot 等比缩放到卡片宽度，再平移让
     * 海报中心与卡片中心重合 —— 收起到底时海报正好压在卡片上，撤掉 Fragment 时不会跳。
     */
    private void buildClosedState() {
        int rootW = motion.getWidth(), rootH = motion.getHeight();
        if (rootW <= 0 || rootH <= 0 || cardBounds == null || cardBounds.length != 4) return;

        int[] origin = new int[2];
        motion.getLocationOnScreen(origin);
        float cardW = Math.max(1, cardBounds[2]);
        float cardH = Math.max(1, cardBounds[3]);
        float cardCX = cardBounds[0] - origin[0] + cardW / 2f;
        float cardCY = cardBounds[1] - origin[1] + cardH / 2f;

        int[] cl = new int[2];
        cover.getLocationOnScreen(cl);
        float posterCX = cl[0] - origin[0] + cover.getWidth() / 2f;
        float posterCY = cl[1] - origin[1] + cover.getHeight() / 2f;

        // 缩放按「海报 → 卡片」对齐（宽、高分别算）：
        // 收起到底时海报正好铺满卡片，页面其余部分已经淡到全透明，所以撤掉浮层是像素级衔接，
        // 不会出现「比卡片大一圈的深色板」或者收完再跳一下。
        float posterW = Math.max(1, cover.getWidth());
        float posterH = Math.max(1, cover.getHeight());
        float sx = cardW / posterW;
        float sy = cardH / posterH;
        float cx = rootW / 2f, cy = rootH / 2f;
        float tx = cardCX - cx - (posterCX - cx) * sx;
        float ty = cardCY - cy - (posterCY - cy) * sy;

        ConstraintSet closed = new ConstraintSet();
        closed.clone(motion);
        closed.setAlpha(R.id.scrim, 0f);
        closed.setScaleX(R.id.detailSurface, sx);
        closed.setScaleY(R.id.detailSurface, sy);
        closed.setTranslationX(R.id.detailSurface, tx);
        closed.setTranslationY(R.id.detailSurface, ty);
        motion.updateState(R.id.ds_closed, closed);
    }

    /** 统一入口：progress 一变，MotionLayout 的约束插值 + 页面自身的叠加效果一起更新 */
    private void setP(float p) {
        p = Math.max(0f, Math.min(1f, p));
        motion.setProgress(p);
        // 海报下方的内容随收起淡出：只剩海报落到卡片上，文字不会缩成一团糊影
        float contentAlpha = 1f - smooth(0.15f, 0.55f, p);
        infoModule.setAlpha(contentAlpha);
        contentCol.setAlpha(contentAlpha);
        // 整页底色（信息带 + 下方内容区）和海报底部渐变一起淡出（只在下拉收起时）：
        // 露出的就是后面的视频列表，最后只剩海报压在卡片上
        float bgAlpha = 1f - smooth(0.45f, 0.88f, p);
        pageBg.setAlpha(Math.round(255 * bgAlpha));
        bandBg.setAlpha(Math.round(255 * bgAlpha));
        heroFade.setAlpha(bgAlpha);
        lastBgAlpha = bgAlpha;
        applyScrimAlpha(bgAlpha);
        // 内嵌播放中：收起时让播放器先淡出，露出下面一直在位的海报，
        // 这样落点仍然是海报↔卡片，不会出现「视频缩成一张卡片」的跳变
        if (inlineActive && playerContainer != null) {
            playerContainer.setAlpha(1f - smooth(0.52f, 0.90f, p));
        }
        radiusPx = dp(CARD_RADIUS_DP) * p;
        surface.invalidateOutline();
        heroWrap.invalidateOutline();
    }

    private void animateTo(float from, float to, int dur, android.view.animation.Interpolator ip,
                           @Nullable Runnable end) {
        cancelMotionAnim();
        final ValueAnimator va = ValueAnimator.ofFloat(from, to);
        va.setDuration(dur);
        va.setInterpolator(ip);
        va.addUpdateListener(a -> setP((float) a.getAnimatedValue()));
        if (end != null) {
            va.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled = false;
                @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(Animator a) {
                    if (!cancelled) end.run();
                }
            });
        }
        motionAnim = va;
        va.start();
    }

    private void cancelMotionAnim() {
        if (motionAnim != null) {
            motionAnim.cancel();
            motionAnim = null;
        }
    }

    /** 系统返回键 / 侧滑返回：走同一套收起动画 */
    public void requestClose() {
        if (dismissed || motion == null || getView() == null) return;
        closing = true;
        cancelMotionAnim();
        fireEnterEnd();
        // 海报已经被滚出屏幕（或没有卡片起点＝详情页内跳详情）：形变终点看不到，改「缩小 + 淡出」收场
        if (cardBounds == null || cardBounds.length != 4
                || scrollPlay.getScrollY() > heroWrap.getHeight()) {
            final View root = getView();
            root.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(200).withEndAction(this::dismiss).start();
            return;
        }
        animateTo(motion.getProgress(), 1f, CLOSE_MS, new AccelerateInterpolator(1.3f),
                this::dismiss);
    }

    private void dismiss() {
        if (dismissed) return;
        dismissed = true;
        inlineActive = false;
        cancelMotionAnim();
        releasePlayer();
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).onDetailClosed(this);
        // 视图动画已结束，直接摘掉 Fragment（列表在原位，卡片本来就露着）
        getParentFragmentManager().beginTransaction()
                .remove(this)
                .commitAllowingStateLoss();
    }

    private static float smooth(float start, float end, float x) {
        float t = (x - start) / (end - start);
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    // ==================== 收藏 / 详情 ====================

    private void toggleFav() {
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
        toast(fav ? "已加入收藏 · 已缓存可离线播放" : "已取消收藏");
    }

    /** 收藏态：心形描边(未收藏) / 实心(已收藏)，animate=true 时播放描边→实心过渡 */
    private void syncFavState(boolean animate) {
        boolean fav = PrefStore.get().isFav(slug, vodId);
        LottieIcon.tint(btnFav, getColor(fav ? R.color.acc : R.color.t2));
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
                    if (!isAdded()) return;
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
                    if (!isAdded()) return;
                    // 网络失败：优先用本地缓存的完整播放数据（收藏 / 历史里保存过的）
                    Models.VideoDetail cached = OfflineMedia.loadDetail(slug, vodId);
                    if (cached != null && cached.vodPlayList != null && !cached.vodPlayList.isEmpty()) {
                        detail = cached;
                        offlineMode = true;
                        renderDetail();
                        toast("离线模式：使用本地缓存的播放数据");
                        return;
                    }
                    if (com.lateshow.app.util.AuthGate.handleApiError(requireActivity(), m,
                            DetailFragment.this::loadDetail)) {
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

    // ==================== 信息模块 ====================

    /** 信息模块（海报渐变落点下方的一个整体）：源站 / 更新信息 / 片名 / 分类·日期 / 演员 / 标签 */
    private void renderInfoModule() {
        if (detail == null) return;
        tvTitle.setText(detail.vodName != null ? detail.vodName : title);
        if (detail.vodPic != null && !detail.vodPic.isEmpty()) {
            Glide.with(this).load(detail.vodPic).placeholder(R.color.heroSurface).centerCrop().into(cover);
        }

        // 源站：显示名优先，回落 slug
        String srcLabel = sourceName != null && !sourceName.isEmpty() ? sourceName
                : (detail.sourceName != null && !detail.sourceName.isEmpty() ? detail.sourceName : slug);
        if (srcLabel != null && !srcLabel.isEmpty()) {
            chipSource.setText(srcLabel);
            chipSource.setVisibility(View.VISIBLE);
        } else {
            chipSource.setVisibility(View.GONE);
        }

        // 更新信息：vod_remarks（如「更新至 20 集」/「高清」/「已完结」）
        String upd = detail.vodRemarks != null && !detail.vodRemarks.trim().isEmpty()
                ? detail.vodRemarks.trim() : (remark != null ? remark.trim() : null);
        if (upd != null && !upd.isEmpty()) {
            chipUpdate.setText(upd);
            chipUpdate.setVisibility(View.VISIBLE);
        } else {
            chipUpdate.setVisibility(View.GONE);
        }

        // 分类 · 日期（离线时补标记）
        StringBuilder meta = new StringBuilder();
        if (!TextUtils.isEmpty(detail.typeName)) meta.append(detail.typeName);
        if (!TextUtils.isEmpty(detail.vodTime)) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(TimeUtil.stamp(detail.vodTime));
        }
        if (offlineMode) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("离线缓存");
        }
        tvMeta.setText(meta);
        tvMeta.setVisibility(meta.length() > 0 ? View.VISIBLE : View.GONE);

        renderActorTags();
    }

    /** 演员 / 标签：数据源没给就整行隐藏（不占位、不出现空标签） */
    private void renderActorTags() {
        if (detail == null) return;
        setActor(splitNames(detail.vodActor));
        setTags(splitNames(detail.vodTag));
    }

    private void setActor(List<String> actors) {
        if (rowActor == null || tvActor == null) return;
        if (actors == null || actors.isEmpty()) {
            rowActor.setVisibility(View.GONE);
            return;
        }
        tvActor.setText(TextUtils.join(" / ", actors));
        rowActor.setVisibility(View.VISIBLE);
    }

    private void setTags(List<String> tags) {
        if (tagRow == null || rowTags == null) return;
        tagRow.removeAllViews();
        if (tags == null || tags.isEmpty()) {
            rowTags.setVisibility(View.GONE);
            return;
        }
        int padH = dp(9), padV = dp(4);
        for (String t : tags) {
            TextView chip = new TextView(requireContext());
            chip.setText(t);
            chip.setTextSize(11f);
            chip.setTextColor(getColor(R.color.heroT2));
            chip.setBackgroundResource(R.drawable.bg_hero_tag);
            chip.setPadding(padH, padV, padH, padV);
            chip.setMaxLines(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            tagRow.addView(chip, lp);
        }
        rowTags.setVisibility(View.VISIBLE);
    }

    /**
     * 演员 / 标签分词：去空、去重、最多 12 个（防超长撑爆模块）。
     *
     * <p>真实数据里三种写法都有：逗号分隔（"金智媛,李姃垠,孙贤周"）、空格分隔
     * （"井柏然 孙艺洲 张颂文"）、标签用间隔号分组（"中国·2026·悬疑"）。
     * 空格只在「该段全是中文名」时才拆 —— 西文名（"Tom Hanks"）本身带空格，不能拆。
     */
    private static List<String> splitNames(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,，、/|;；·\n]")) {
            String v = part.trim();
            if (v.isEmpty()) continue;
            if (v.indexOf(' ') > 0 && hasCjk(v)) {
                for (String sub : v.split("\\s+")) addName(out, sub.trim());
            } else {
                addName(out, v);
            }
            if (out.size() >= 12) break;
        }
        return out;
    }

    private static void addName(List<String> out, String v) {
        if (v.isEmpty() || out.size() >= 12 || out.contains(v)) return;
        out.add(v);
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    // ==================== 详情渲染 ====================

    private void renderDetail() {
        renderInfoModule();

        String desc = detail.vodContent;
        if (desc != null) {
            // 先删标签再解码 HTML 实体：数据源简介里常见 &nbsp; &amp; &#39; 等，直接用会显示成字面量
            desc = desc.replaceAll("<[^>]+>", "");
            desc = android.text.Html.fromHtml(desc, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
            // 实体解码后会产生连续空白/不换行空格，压成单个空格
            desc = desc.replaceAll("[\\s\\u00A0]{2,}", " ").trim();
        }
        if (desc == null || desc.isEmpty()) desc = "暂无简介";
        tvDesc.setText(desc);

        // 断点续播：读取历史（线路 + 集数都要恢复；只恢复集数会在多线路时对不上集）
        PrefStore.HistItem h = PrefStore.get().getHist(slug, vodId);
        if (h != null) {
            historyEp = h.epIndex;
            if (h.lineIndex > 0) curLine = h.lineIndex;
            if (h.epIndex > 0) curEp = h.epIndex;
        }
        if (curLine >= playList().size()) curLine = 0;
        epPage = Math.max(0, curEp / Math.max(1, epPageSize));

        renderLines();
        renderEpisodes();

        if (h != null && h.playedSec > 0) {
            btnPlay.setText("继续播放 · " + epName(curEp));
        }

        loadRelated();
        loadCast();
    }

    /** 线路选择：多线路才显示；切换线路会把集数归零并重排选集 */
    private void renderLines() {
        final List<Models.PlayLine> ls = playList();
        if (ls.size() <= 1) {
            lines.setVisibility(View.GONE);
            return;
        }
        lines.setVisibility(View.VISIBLE);
        lines.setLayoutManager(new LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false));
        lines.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                return new RecyclerView.ViewHolder(getLayoutInflater().inflate(R.layout.item_line, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
                TextView tv = (TextView) vh.itemView;
                Models.PlayLine l = ls.get(pos);
                tv.setText(l != null && l.name != null ? l.name : "线路" + (pos + 1));
                boolean sel = pos == curLine;
                tv.setBackgroundResource(sel ? R.drawable.bg_episode_current : R.drawable.bg_episode);
                tv.setTextColor(getColor(sel ? R.color.accText : R.color.t2));
                tv.setOnClickListener(v -> {
                    if (curLine == pos) return;
                    curLine = pos;
                    curEp = 0;
                    epPage = 0;
                    notifyDataSetChanged();
                    renderEpisodes();
                });
            }
            @Override public int getItemCount() { return ls.size(); }
        });
    }

    /**
     * 选集网格（分页）。
     *
     * <p>真实数据里有 700+ 集的长剧：一次性铺开会把整页测量量顶到上千个 View，
     * 滑动和收起都会卡，所以按 {@link #epPageSize} 分页，页内再按最长集名自适应列数
     * （综艺类的「第20261030期」在 5 列里会被截成马赛克，自动降到 3 列）。
     */
    private void renderEpisodes() {
        final List<Models.Episode> eps = currentEpisodes();
        if (!eps.isEmpty()) {
            if (curEp >= eps.size()) curEp = eps.size() - 1;
            if (curEp < 0) curEp = 0;
        }

        // 列数 / 每页条数：先量最长集名，再定列数，页大小 = 列数 × 8 行
        epSpan = chooseSpan(eps);
        int pageSize = Math.max(1, epSpan * 8);
        if (pageSize != epPageSize) {
            epPageSize = pageSize;
            epPage = curEp / epPageSize;
        }
        int totalPages = Math.max(1, (eps.size() + epPageSize - 1) / epPageSize);
        if (epPage >= totalPages) epPage = totalPages - 1;
        if (epPage < 0) epPage = 0;

        final int from = epPage * epPageSize;
        final int to = Math.min(eps.size(), from + epPageSize);
        final int base = from;   // lambda 里要用，必须 effectively final

        if (eps.isEmpty()) {
            epCount.setText("暂无选集");
        } else if (eps.size() > epPageSize) {
            epCount.setText("共 " + eps.size() + " 集 · " + (from + 1) + "-" + to);
        } else {
            epCount.setText(eps.size() + " 集");
        }

        buildEpPager(totalPages, eps.size());

        epGrid.setLayoutManager(new GridLayoutManager(requireContext(), epSpan));
        epGrid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                return new RecyclerView.ViewHolder(getLayoutInflater().inflate(R.layout.item_episode, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
                TextView tv = (TextView) vh.itemView;
                final int idx = base + pos;
                Models.Episode e = idx < eps.size() ? eps.get(idx) : null;
                tv.setText(e != null && e.name != null && !e.name.isEmpty()
                        ? e.name : "第" + (idx + 1) + "集");
                boolean cur = idx == curEp;
                boolean watched = historyEp > 0 && idx < historyEp;
                tv.setBackgroundResource(cur ? R.drawable.bg_episode_current : R.drawable.bg_episode);
                tv.setTextColor(getColor(cur ? R.color.accText
                        : (watched ? R.color.t3 : R.color.t2)));
                tv.setOnClickListener(v -> {
                    curEp = idx;
                    notifyDataSetChanged();
                    if (lines.getAdapter() != null) lines.getAdapter().notifyDataSetChanged();
                    playCurrent(0);
                });
            }
            @Override public int getItemCount() { return Math.max(0, to - from); }
        });
        updatePlayLabel();
    }

    /** 长剧分页条：每页一个胶囊「1-40 / 41-80 …」，只有一页时整条隐藏 */
    private void buildEpPager(int totalPages, int total) {
        if (epPagerScroll == null || epPagerRow == null) return;
        epPagerRow.removeAllViews();
        if (totalPages <= 1) {
            epPagerScroll.setVisibility(View.GONE);
            return;
        }
        epPagerScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < totalPages; i++) {
            final int page = i;
            int start = i * epPageSize + 1;
            int end = Math.min(total, (i + 1) * epPageSize);
            TextView tv = new TextView(requireContext());
            tv.setText(start + "-" + end);
            tv.setTextSize(11f);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(dp(10), dp(5), dp(10), dp(5));
            boolean sel = i == epPage;
            tv.setBackgroundResource(sel ? R.drawable.bg_episode_current : R.drawable.bg_episode);
            tv.setTextColor(getColor(sel ? R.color.accText : R.color.t2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                if (epPage == page) return;
                epPage = page;
                curEp = page * epPageSize;      // 翻页后把「当前集」带到该页第一集
                renderEpisodes();
                epGrid.scrollToPosition(0);
            });
            epPagerRow.addView(tv);
        }
    }

    /** 按最长集名挑列数：保证每个格子放得下一个完整集名（不含边距），3~5 列之间钳制 */
    private int chooseSpan(List<Models.Episode> eps) {
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 12f, getResources().getDisplayMetrics()));
        float max = 0;
        for (Models.Episode e : eps) {
            if (e == null || e.name == null) continue;
            max = Math.max(max, paint.measureText(e.name));
        }
        if (max <= 0) max = paint.measureText("第88集");
        int avail = getResources().getDisplayMetrics().widthPixels - dp(32);  // 内容区左右各 16dp
        float need = max + dp(16);                                           // 格内留白 + 边距
        int span = (int) Math.floor(avail / Math.max(need, (float) dp(52)));
        return Math.max(3, Math.min(5, span));
    }

    /** 相关推荐：详情响应里内嵌了就直接用（NAS 模式零额外请求），否则按数据源能力补拉一次 */
    private void loadRelated() {
        if (relatedSection == null || detail == null) return;
        List<Models.RelatedCard> emb = detail.related;
        if (emb != null && !emb.isEmpty()) {
            renderRelated(emb, detail.relatedFallback);
            return;
        }
        if (offlineMode) { // 离线缓存里没有推荐数据，不占位
            relatedSection.setVisibility(View.GONE);
            return;
        }
        final Models.VideoDetail base = detail;
        com.lateshow.app.api.Catalogs.get().related(base, RELATED_LIMIT,
            new VodSyncApi.ApiCb<Models.RelatedResp>() {
                @Override public void onOk(Models.RelatedResp r) {
                    if (!isAdded() || detail != base) return; // 期间已切到别的片
                    if (r == null || r.list == null || r.list.isEmpty()) return;
                    renderRelated(r.list, r.fallback);
                }
                @Override public void onErr(String m) {
                    // 推荐属增强项：失败静默收起，不影响详情主流程
                    if (relatedSection != null) relatedSection.setVisibility(View.GONE);
                }
            });
    }

    /** 渲染横向推荐卡：排除自身；score>0 且非兜底条目才显示相关度百分比 */
    private void renderRelated(final List<Models.RelatedCard> raw, boolean fallback) {
        final List<Models.RelatedCard> list = new ArrayList<>();
        for (Models.RelatedCard c : raw) {
            if (c == null || c.vodId <= 0) continue;
            boolean self = c.vodId == vodId && (c.source == null || c.source.equals(slug));
            if (self) continue;
            list.add(c);
        }
        if (list.isEmpty()) {
            relatedSection.setVisibility(View.GONE);
            return;
        }
        tvRelatedHint.setText(fallback ? "同类热门" : "按类型 · 主演匹配");
        relatedSection.setVisibility(View.VISIBLE);
        relatedList.setLayoutManager(new LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false));
        relatedList.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                return new RecyclerView.ViewHolder(
                        getLayoutInflater().inflate(R.layout.item_related, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
                Models.RelatedCard c = list.get(pos);
                View v = vh.itemView;
                ImageView cover = v.findViewById(R.id.relCover);
                TextView name = v.findViewById(R.id.relName);
                TextView score = v.findViewById(R.id.relScore);
                Glide.with(DetailFragment.this).load(c.vodPic)
                        .placeholder(R.color.heroSurface)
                        .centerCrop()
                        .dontAnimate()
                        .into(cover);
                name.setText(c.vodName != null ? c.vodName : "");
                if (c.score > 0.005 && !"fallback".equals(c.mode)) {
                    score.setText(Math.round(c.score * 100) + "%");
                    score.setVisibility(View.VISIBLE);
                } else {
                    score.setVisibility(View.GONE);
                }
                v.setOnClickListener(x -> openOther(c.source != null && !c.source.isEmpty() ? c.source : slug,
                        c.vodId, c.vodName, c.vodPic, c.vodRemarks, c.sourceName, cover));
            }
            @Override public int getItemCount() { return list.size(); }
        });
    }

    /** 相同演员作品：NAS 模式调 /api/v1/star/related（走 star_map 索引，毫秒级）。
     *  空数据 / 离线 / 直连模式一律静默隐藏，不占位不影响主流程 */
    private void loadCast() {
        if (castSection == null || detail == null) return;
        if (offlineMode || com.lateshow.app.api.Catalogs.isStatic()) {
            castSection.setVisibility(View.GONE);
            return;
        }
        final Models.VideoDetail base = detail;
        castGen++;                                    // 详情已切换：之前的明星 Tab 请求回调全部作废
        starWorksCache.clear();
        starTotalCache.clear();
        starWorksLoading.clear();
        curStarTab = null;
        final int gen = castGen;
        com.lateshow.app.api.Catalogs.get().starRelated(base, STAR_LIMIT,
            new VodSyncApi.ApiCb<Models.StarRelatedResp>() {
                @Override public void onOk(Models.StarRelatedResp r) {
                    if (!isAdded() || detail != base || gen != castGen) return; // 期间已切到别的片
                    boolean usable = r != null && r.stars != null && !r.stars.isEmpty()
                            && r.list != null && !r.list.isEmpty();
                    starResp = usable ? r : null;
                    curStarTab = null;
                    // 详情没有演员字段时，用 star_map 命中的明星集补进信息模块
                    if (starResp != null && detail != null && TextUtils.isEmpty(detail.vodActor)) {
                        setActor(starResp.stars);
                    }
                    if (starResp == null) {
                        castSection.setVisibility(View.GONE);
                        return;
                    }
                    renderCast();
                }
                @Override public void onErr(String m) {
                    if (castSection != null) castSection.setVisibility(View.GONE);
                }
            });
    }

    /** 渲染明星 Tab（全部 + 命中明星）与横向影片卡。
     *  Tab 排序优先用服务端 star_counts（v1.9.2 完整作品数，可包含被 top50 挤出的明星），
     *  旧服务端自动降级为本地命中数；明星 Tab 切换按需调 star/works 拉完整作品 */
    private void renderCast() {
        // 本地命中数：「全部」Tab 的 top50 列表里各明星出现的次数
        final Map<String, Integer> hitCount = new HashMap<>();
        for (Models.StarCard c : starResp.list) {
            if (c.match_stars == null) continue;
            for (String s : c.match_stars) {
                Integer n = hitCount.get(s);
                hitCount.put(s, n == null ? 1 : n + 1);
            }
        }
        final Map<String, Integer> counts = starResp.starCounts != null
                ? starResp.starCounts : hitCount;     // 新服务端用完整作品数，旧服务端退回本地命中
        final List<String> ranked = new ArrayList<>(starResp.stars);
        java.util.Collections.sort(ranked, (a, b) -> Integer.compare(
                counts.getOrDefault(b, 0), counts.getOrDefault(a, 0)));
        final List<String> tabs = new ArrayList<>();
        tabs.add(null);
        for (String s : ranked) {
            if (tabs.size() > CAST_MAX_TABS) break;
            if (s != null && !s.trim().isEmpty() && !tabs.contains(s)
                    && counts.getOrDefault(s, 0) > 0) tabs.add(s);
        }

        castHintAll = starResp.stars.get(0);
        if (starResp.stars.size() > 1) castHintAll += " 等 " + starResp.stars.size() + " 位";
        if (starResp.truncated) castHintAll += " · 已截取前 " + STAR_LIMIT + " 部";
        tvCastHint.setText(curStarTab == null ? castHintAll : tabHint(curStarTab));

        castTabs.setLayoutManager(new LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false));
        castTabs.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                return new RecyclerView.ViewHolder(getLayoutInflater()
                        .inflate(R.layout.item_cast_tab, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
                String t = tabs.get(pos);
                TextView tv = vh.itemView.findViewById(R.id.tabText);
                boolean sel = t == null ? curStarTab == null : t.equals(curStarTab);
                tv.setText(t == null ? "全部" : tabLabel(t));
                tv.setBackgroundResource(sel ? R.drawable.bg_seg_on : R.drawable.bg_seg_off);
                tv.setTextColor(getColor(sel ? R.color.maskText : R.color.t2));
                vh.itemView.setOnClickListener(x -> {
                    boolean same = t == null ? curStarTab == null : t.equals(curStarTab);
                    if (same) return;
                    curStarTab = t;
                    castTabs.getAdapter().notifyDataSetChanged();
                    castList.getAdapter().notifyDataSetChanged();
                    castList.scrollToPosition(0);
                    updateCastHint();
                    if (t != null && !starWorksCache.containsKey(t)) loadStarWorks(t);
                });
            }
            @Override public int getItemCount() { return tabs.size(); }
        });

        castList.setLayoutManager(new LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false));
        castList.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                return new RecyclerView.ViewHolder(getLayoutInflater()
                        .inflate(R.layout.item_related, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
                Models.StarCard c = castFiltered().get(pos);
                View v = vh.itemView;
                ImageView cover = v.findViewById(R.id.relCover);
                TextView name = v.findViewById(R.id.relName);
                TextView tag = v.findViewById(R.id.relScore);
                Glide.with(DetailFragment.this).load(c.vodPic)
                        .placeholder(R.color.heroSurface)
                        .centerCrop()
                        .dontAnimate()
                        .into(cover);
                name.setText(c.vodName != null ? c.vodName : "");
                // 「全部」Tab 显示共享明星（如「吴京」）；明星 Tab 内已不言自明，隐藏
                if (curStarTab == null && c.match_stars != null && !c.match_stars.isEmpty()) {
                    tag.setText(c.match_stars.get(0));
                    tag.setVisibility(View.VISIBLE);
                } else {
                    tag.setVisibility(View.GONE);
                }
                v.setOnClickListener(x -> openOther(c.source != null && !c.source.isEmpty() ? c.source : slug,
                        c.vodId, c.vodName, c.vodPic, c.vodRemarks, null, cover));
            }
            @Override public int getItemCount() { return castFiltered().size(); }
        });
        castSection.setVisibility(View.VISIBLE);
    }

    /** 当前 Tab 的影片：null=全部（star/related top50 全量展示）；
     *  明星 Tab 优先用 star/works 缓存，未命中（加载中或旧服务端无接口）回退本地 match_stars 过滤 */
    private List<Models.StarCard> castFiltered() {
        List<Models.StarCard> out = new ArrayList<>();
        if (starResp == null || starResp.list == null) return out;
        if (curStarTab == null) {
            for (Models.StarCard c : starResp.list) {
                if (c != null && c.vodId > 0) out.add(c);
            }
            return out;
        }
        List<Models.StarCard> cached = starWorksCache.get(curStarTab);
        if (cached != null) {
            out.addAll(cached);
            return out;
        }
        for (Models.StarCard c : starResp.list) {   // 降级：本地过滤 top50 内命中该明星的影片
            if (c == null || c.vodId <= 0) continue;
            if (c.match_stars == null || !c.match_stars.contains(curStarTab)) continue;
            out.add(c);
        }
        return out;
    }

    /** 明星 Tab 按需拉取该明星完整作品（v1.9.2 /api/v1/star/works），结果缓存；失败静默降级为本地过滤 */
    private void loadStarWorks(String term) {
        final Models.VideoDetail base = detail;
        final int gen = castGen;
        starWorksLoading.add(term);
        com.lateshow.app.api.Catalogs.get().starWorks(base, term, STAR_LIMIT,
            new VodSyncApi.ApiCb<Models.StarWorksResp>() {
                @Override public void onOk(Models.StarWorksResp r) {
                    starWorksLoading.remove(term);
                    if (!isAdded() || detail != base || gen != castGen) return;
                    starWorksCache.put(term, r != null && r.list != null
                            ? r.list : new ArrayList<>());
                    if (r != null && r.total > 0) starTotalCache.put(term, r.total);
                    if (term.equals(curStarTab)) {
                        updateCastHint();
                        if (castList.getAdapter() != null) castList.getAdapter().notifyDataSetChanged();
                        castList.scrollToPosition(0);
                    }
                }
                @Override public void onErr(String m) {
                    starWorksLoading.remove(term);
                    // 旧服务端无 works 接口（404）或网络失败：不缓存，castFiltered 自动回退本地过滤
                    if (!isAdded() || detail != base || gen != castGen) return;
                    if (term.equals(curStarTab)) updateCastHint();
                }
            });
        updateCastHint();   // 立即切换为「加载中」提示
    }

    /** 明星区提示语：「全部」Tab 用汇总语；明星 Tab 显示「共 N 部」/「加载中」 */
    private void updateCastHint() {
        if (tvCastHint == null || starResp == null) return;
        tvCastHint.setText(curStarTab == null ? castHintAll : tabHint(curStarTab));
    }

    private String tabHint(String term) {
        Integer total = starTotalCache.get(term);
        if (total == null && starResp.starCounts != null) total = starResp.starCounts.get(term);
        if (total != null && total > 0) return term + " · 共 " + total + " 部";
        if (starWorksLoading.contains(term)) return term + " · 作品加载中…";
        return term;
    }

    /** Tab 文案：作品数已知时附带角标（如「奈奈·42」），未知只显示名字 */
    private String tabLabel(String term) {
        Integer total = starTotalCache.get(term);
        if (total == null && starResp.starCounts != null) total = starResp.starCounts.get(term);
        return total != null && total > 0 ? term + "·" + total : term;
    }

    /**
     * 统一取播放线路。
     *
     * <p>NAS 详情接口会直接给结构化的 vod_play_list；直连模式和一些旧缓存只有
     * vod_play_url 原始串（"线路$$$线路"，线路内 "集名$地址#集名$地址"）。
     * 这里统一补齐，避免出现「接口有播放地址但选集是空的、点播放没反应」。
     */
    private List<Models.PlayLine> playList() {
        if (detail == null) return new ArrayList<>();
        if (detail.vodPlayList != null && !detail.vodPlayList.isEmpty()) return detail.vodPlayList;
        if (!TextUtils.isEmpty(detail.vodPlayUrl)) {
            detail.vodPlayList = parsePlayUrl(detail.vodPlayUrl);
        }
        return detail.vodPlayList != null ? detail.vodPlayList : new ArrayList<>();
    }

    /** 客户端兜底解析 vod_play_url（与 VodSync 的 parse_play_url 规则一致） */
    private static List<Models.PlayLine> parsePlayUrl(String raw) {
        List<Models.PlayLine> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        int li = 0;
        for (String line : raw.split("\\$\\$\\$")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            List<Models.Episode> eps = new ArrayList<>();
            for (String seg : line.split("#")) {
                seg = seg.trim();
                if (seg.isEmpty()) continue;
                Models.Episode e = new Models.Episode();
                int cut = seg.indexOf('$');
                if (cut > 0) {
                    e.name = seg.substring(0, cut).trim();
                    e.url = seg.substring(cut + 1).trim();
                } else {
                    e.name = "EP" + li;
                    e.url = seg;
                }
                if (!TextUtils.isEmpty(e.url)) eps.add(e);
            }
            if (!eps.isEmpty()) {
                Models.PlayLine pl = new Models.PlayLine();
                pl.name = "线路" + (li + 1);
                pl.episodes = eps;
                out.add(pl);
            }
            li++;
        }
        return out;
    }

    private List<Models.Episode> currentEpisodes() {
        List<Models.PlayLine> ls = playList();
        if (ls.isEmpty()) return new ArrayList<>();
        if (curLine < 0 || curLine >= ls.size()) curLine = 0;
        Models.PlayLine l = ls.get(curLine);
        if (l == null || l.episodes == null) return new ArrayList<>();
        return l.episodes;
    }

    private String epName(int idx) {
        List<Models.Episode> eps = currentEpisodes();
        if (eps.isEmpty()) return "";
        Models.Episode e = eps.get(Math.min(idx, eps.size() - 1));
        return e.name != null ? e.name : "第" + (idx + 1) + "集";
    }

    private void updatePlayLabel() {
        if (btnPlay == null) return;
        List<Models.Episode> eps = currentEpisodes();
        if (eps.isEmpty()) {
            // 没有可播地址时按钮文案要说清楚，别留一个「播放 · 」让人以为点了没反应
            btnPlay.setText("暂无可播放的选集");
            return;
        }
        PrefStore.HistItem h = PrefStore.get().getHist(slug, vodId);
        String prefix;
        if (player != null) {
            prefix = player.isPlaying() ? "暂停 · " : "继续播放 · ";
        } else {
            prefix = (h != null && h.playedSec > 0 && h.epIndex == curEp) ? "继续播放 · " : "播放 · ";
        }
        btnPlay.setText(prefix + epName(curEp));
    }

    // ==================== 播放 ====================

    @Override
    public void onResume() {
        super.onResume();
        // 详情页整页深色 → 状态栏图标固定白色（离开时由 MainActivity 还原）
        setLightStatusBarIcons(false);
        // 从播放器返回后刷新继续播放标签与选集状态
        if (detail != null && epGrid.getAdapter() != null) {
            epGrid.getAdapter().notifyDataSetChanged();
            updatePlayLabel();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // 离开页面（含进全屏）时保存进度并暂停内嵌播放
        if (player != null) {
            long d = player.getDuration() > 0 ? player.getDuration() / 1000 : 0;
            saveProgress(player.getCurrentPosition() / 1000, d);
            player.pause();
        }
    }

    @Override
    public void onDestroyView() {
        cancelMotionAnim();
        releasePlayer();
        super.onDestroyView();
    }

    private void releasePlayer() {
        saveHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void setLightStatusBarIcons(boolean light) {
        if (getActivity() == null) return;
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(
                getActivity().getWindow(), getActivity().getWindow().getDecorView());
        c.setAppearanceLightStatusBars(light);
    }

    private void playCurrent(long seekMs) {
        List<Models.Episode> eps = currentEpisodes();
        if (eps.isEmpty()) {
            toast("暂无可播放的选集");
            return;
        }
        if (curEp >= eps.size()) curEp = 0;
        Models.Episode e = eps.get(curEp);
        if (e == null || TextUtils.isEmpty(e.url)) {
            toast("该集没有播放地址，试试切换线路");
            return;
        }

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

    /** 在详情页内嵌窗口播放（横版 16:9），不进入全屏 */
    private void startInline(String url, long startMs) {
        curUrl = url;
        inlineActive = true;
        triedHtmlResolve = false;
        // 播放器盖在海报之上（海报不隐藏）：页面高度不变，收起形变的对应关系仍然成立
        playerContainer.setVisibility(View.VISIBLE);
        playerContainer.setAlpha(1f);
        btnPlayCover.setVisibility(View.GONE);
        // 点「播放」时页面通常停在简介/选集处，滚回顶部让画面可见，否则会以为没播
        scrollPlay.smoothScrollTo(0, 0);
        if (player == null) {
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(30000);
            player = new ExoPlayer.Builder(requireContext())
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                    .build();
            playerView.setPlayer(player);
            // 控制栏/半透明遮罩 2 秒后自动隐藏
            playerView.setControllerShowTimeoutMs(2000);
            // 点全屏按钮才进入横屏全屏
            playerView.setFullscreenButtonClickListener(v -> goFullscreen());
            // 倍速 + 播放设置（悬浮工具，见布局右上角）
            com.lateshow.app.util.PlayerPanel.attach(
                    getView().findViewById(R.id.btnSpeed), getView().findViewById(R.id.btnGear), player);
            com.lateshow.app.util.PlayerPanel.applySavedSpeed(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    syncKeepScreenOn();
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
                    syncKeepScreenOn();
                }
                @Override public void onPlayerError(PlaybackException error) {
                    // 有些源站的 "播放地址" 其实是一个网页播放页，ExoPlayer 拿到 HTML 必然失败：
                    // 先尝试从页面里把真正的 m3u8 抠出来重试一次，还不行才提示（并建议换线路）
                    if (!triedHtmlResolve && isPlayPage(curRawUrl)) {
                        triedHtmlResolve = true;
                        resolvePlayPage(curRawUrl, curStartMs);
                        return;
                    }
                    toast("播放失败：" + describe(error) + "，可试试切换线路");
                }
            });
            attachSeekGesture();
            saveHandler.postDelayed(saveTick, 5000);
        }
        playUrl(url, startMs);
    }

    /** 真正下发给播放器的地址：网页播放页先归一化成直链（见 {@link #guessStreamUrl}） */
    private void playUrl(String rawUrl, long startMs) {
        if (player == null) return;
        curRawUrl = rawUrl;
        curStartMs = startMs;
        curPlayUrl = guessStreamUrl(rawUrl);
        String playUrl = VodSyncApi.get().decorate(curPlayUrl);
        player.setMediaItem(MediaItem.fromUri(playUrl), Math.max(0, startMs));
        player.prepare();
        player.play();
    }

    private static final String[] MEDIA_EXT =
            {".m3u8", ".mp4", ".flv", ".mkv", ".ts", ".mov", ".webm"};

    /** 是否已经是可直接播放的媒体地址（按扩展名判断，查询串先去掉） */
    private static boolean looksLikeMedia(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        for (String ext : MEDIA_EXT) {
            if (u.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 是否「网页播放页」形态（{@code https://play.xxx.com/play/AbCd1234}）。
     *
     * <p>只对这种形态做归一化：真实数据里 556 条无扩展名地址全部长这样，而且正好都是
     * 700+ 集的长剧；而其它无扩展名的地址（带参数的转发接口等）本来就是能播的，
     * 不能去动它。
     */
    private static boolean isPlayPage(String url) {
        if (TextUtils.isEmpty(url) || looksLikeMedia(url)) return false;
        String u = url;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        return u.matches(".*/play/[^/]+/?");
    }

    /**
     * 网页播放页 → 直链。
     *
     * <p>这类站点页面里的播放源就是同路径下的 {@code index.m3u8}，先按这个规则拼一次，
     * 播放器报错再退回去解析 HTML（见 {@link #resolvePlayPage}）。
     */
    private static String guessStreamUrl(String url) {
        if (!isPlayPage(url)) return url;
        String s = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return s + "/index.m3u8";
    }

    /** 兜底：抓播放页 HTML，把里面第一个 m3u8 地址抠出来再播一次 */
    private void resolvePlayPage(final String rawUrl, final long startMs) {
        new Thread(() -> {
            String found = null;
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(rawUrl)
                        .header("User-Agent", UA_MOBILE)
                        .build();
                try (okhttp3.Response resp = pageHttp.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    java.util.regex.Matcher m = M3U8_PATTERN.matcher(body);
                    if (m.find()) found = m.group();
                }
            } catch (Exception ignore) {
                // 抓不到就按失败处理，下面统一提示
            }
            final String url = found;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded() || player == null) return;
                if (url != null) {
                    triedHtmlResolve = false;   // 换了地址重新给一次机会
                    playUrl(url, startMs);
                } else {
                    toast("该线路只提供网页播放页，无法直接播放，请切换线路");
                }
            });
        }).start();
    }

    private static String describe(PlaybackException e) {
        if (e == null) return "未知错误";
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "地址返回异常";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                return "网络连接失败";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "网络超时";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
                return "该地址不是视频流";
            default:
                return "解码失败(" + e.getErrorCodeName() + ")";
        }
    }

    /** 内嵌播放/缓冲期间保持屏幕常亮；暂停、播完、idle 时恢复系统息屏策略 */
    private void syncKeepScreenOn() {
        if (playerView == null) return;
        boolean on = player != null && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_IDLE
                && player.getPlaybackState() != Player.STATE_ENDED;
        playerView.setKeepScreenOn(on);
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
        Intent it = new Intent(requireContext(), PlayerActivity.class);
        it.putExtra("url", curPlayUrl != null ? curPlayUrl : guessStreamUrl(curUrl));
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
        // 进出全屏都用「放大/缩小 + 淡入淡出」接住，不再是系统默认的左右滑
        if (getActivity() != null) {
            getActivity().overridePendingTransition(R.anim.fs_in, R.anim.fs_out);
        }
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

    // ==================== 工具 ====================

    /**
     * 详情页内跳到另一部片（相关推荐 / 相同演员）。
     *
     * <p>转场：把被点的那张卡片的封面当作「卡片坐标」传给新详情页，新页从这张封面形变展开；
     * 旧详情页先留着当背景，新页展开完成后由 {@link MainActivity} 摘掉（见 setOnEnterEnd）。
     *
     * @param source 被点卡片的封面 ImageView；为 null 时（例如数据里没有封面视图）退化为直接展开
     */
    private void openOther(String s, long id, String t, String p, String r, String srcName, View source) {
        if (!(getActivity() instanceof MainActivity)) return;
        final MainActivity act = (MainActivity) getActivity();
        final CardItem item = new CardItem();
        item.slug = s;
        item.vodId = id;
        item.title = t;
        item.pic = p;
        item.remark = r;
        item.sourceName = srcName;
        // 延后一帧再换页：当前这次点击还在这张卡片的 onClick 里，等事件分发结束再拆视图
        new Handler(Looper.getMainLooper()).post(() -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            act.openDetail(item, source);
        });
    }

    private int getColor(int id) {
        return requireContext().getColor(id);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        if (getContext() != null) Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }
}
