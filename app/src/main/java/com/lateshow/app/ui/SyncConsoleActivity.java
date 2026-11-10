package com.lateshow.app.ui;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lateshow.app.R;
import com.lateshow.app.adapter.LogAdapter;
import com.lateshow.app.adapter.SyncSourceAdapter;
import com.lateshow.app.api.VodSyncApi;
import com.lateshow.app.model.Models;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.util.Skeleton;
import com.lateshow.app.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同步控制台（独立页面）。
 *
 * 功能分区逐项对齐 VodSync Web 管理面板：运行概览 / 同步操作 / 同步暂停控制 /
 * 聚合搜索 / 数据表浏览 / 源站同步状态 / 最近同步日志 / 访问控制与 Token /
 * 明星词库 / 诊断与维护 / 播放线路策略（v1.11.0）。
 * 视觉与交互沿用设置页：SettingsCard 卡片 + 同一时间只展开一个的手风琴 + 回到顶部 FAB。
 */
public class SyncConsoleActivity extends AppCompatActivity {

    private static final int[] HEADERS = {
            R.id.hdrOverview, R.id.hdrActions, R.id.hdrPause, R.id.hdrSearch, R.id.hdrTables,
            R.id.hdrSources, R.id.hdrLogs, R.id.hdrAuth, R.id.hdrStars, R.id.hdrMaint, R.id.hdrLine,
            R.id.hdrTagIdx};
    private static final int[] BODIES = {
            R.id.panelOverview, R.id.panelActions, R.id.panelPause, R.id.panelSearch, R.id.panelTables,
            R.id.panelSources, R.id.panelLogs, R.id.panelAuth, R.id.panelStars, R.id.panelMaint, R.id.panelLine,
            R.id.panelTagIdx};
    private static final int[] ARROWS = {
            R.id.arOverview, R.id.arActions, R.id.arPause, R.id.arSearch, R.id.arTables,
            R.id.arSources, R.id.arLogs, R.id.arAuth, R.id.arStars, R.id.arMaint, R.id.arLine,
            R.id.arTagIdx};
    private static final String[] POLICY_VALUES = {"inherit", "filter", "off", "whitelist"};

    private TextView tvConsoleStatus, tvConsoleTime, autoCount, selCount, tvRuntime, pauseWarn, searchInfo,
            tvTablePage, tvAuthState, tvTokenValue, tvStarStatus, tvMaintOutput, tvLineHint,
            tvTokenListHint, tvTagIdxState, tvTagCapability;
    private LinearLayout statsGrid, pauseRows, statsSkeleton, srcSkeleton, logSkeleton,
            searchResults, tableResults, starList, srcList, logList, tokenList, tagIdxList;
    private CheckBox chkAll;
    private Switch swAuthEnable;
    private Spinner spTable, spLineSource, spLinePolicy;
    private EditText etSearch, etTableQuery, etStarTerms, etPwOld, etPwNew, etPwNew2;

    private SyncSourceAdapter srcAdapter;
    private LogAdapter logAdapter;
    private final List<String> tableNames = new ArrayList<>();
    private final List<String> lineSourceSlugs = new ArrayList<>();
    private final List<String> lineSourceLabels = new ArrayList<>();
    private int tablePage = 1, tablePages = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling = false;
    /** 轮询代次：只认最后一次 refreshAll 的结果，写操作后的旧响应一律丢弃（审计 A-10） */
    private int pollGeneration = 0;
    /** 在途轮询请求分组：新一轮刷新 / 页面销毁时整体取消（A-10 / A-11） */
    private static final Object POLL_TAG = new Object();
    /** 进程内明文 Token：仅供「临时显示」，绝不落盘、不进 logcat、不进剪贴板（A-06） */
    private String transientToken = null;
    private final Runnable hideTokenTask = () -> {
        if (tvTokenValue == null) return;
        tvTokenValue.setText(tokenMaskText());
        tvTokenValue.setTextColor(color(R.color.t2));
    };
    private Models.PauseState pauseState = new Models.PauseState();
    /** 全库视频数：标签索引卡用它算覆盖率（来自 /stats，0 表示尚未取到） */
    private int videosTotal = 0;

    private final ObjectAnimator[] animStats = new ObjectAnimator[1];
    private final ObjectAnimator[] animSrc = new ObjectAnimator[1];
    private final ObjectAnimator[] animLog = new ObjectAnimator[1];
    private final Handler skelHandler = new Handler(Looper.getMainLooper());
    private boolean skelShown = false;
    private final Runnable skelShowTask = () -> {
        if (isFinishing() || statsSkeleton == null || skelShown) return;
        skelShown = true;
        Skeleton.show(statsSkeleton, animStats);
        Skeleton.show(srcSkeleton, animSrc);
        Skeleton.show(logSkeleton, animLog);
    };

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!polling || !uiAlive()) return;
            refreshAll(false);
            handler.postDelayed(this, 5000);
        }
    };

    /** Activity 仍可安全弹窗 / 更新 UI（避免窗口泄漏与异步回调崩溃） */
    private boolean uiAlive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrefStore.init(this);
        com.lateshow.app.util.ApiCache.init(this);
        com.lateshow.app.util.ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync_console);
        com.lateshow.app.util.LottieIcon.bindTree(findViewById(android.R.id.content));

        setupAccordion();
        setupFab();
        bindViews();
        setupSkeleton();
        setupLists();
        bindActions();
        loadStaticSections();
        refreshAll(true);
    }

    // ==================== 手风琴 / 回到顶部 ====================

    private void setupAccordion() {
        for (int i = 0; i < HEADERS.length; i++) {
            final int idx = i;
            findViewById(HEADERS[i]).setOnClickListener(v -> {
                togglePanel(idx);
                // 展开板块时按需加载该板块的静态数据
                if (findViewById(BODIES[idx]).getVisibility() == View.VISIBLE) onSectionOpened(idx);
            });
        }
        applyPanels(0);   // 默认展开「运行概览」
    }

    private void onSectionOpened(int idx) {
        if (idx == 4) loadTables();                       // 数据表浏览
        else if (idx == 7) { loadAuthSection(); loadAuthTokens(); }  // 访问控制与 Token
        else if (idx == 8) loadStarTerms();               // 明星词库
        else if (idx == 10) refreshLineSourceSpinner();   // 播放线路策略
        else if (idx == 11) loadTagIndex();               // 标签索引
    }

    private void togglePanel(int idx) {
        boolean wantOpen = findViewById(BODIES[idx]).getVisibility() != View.VISIBLE;
        applyPanels(wantOpen ? idx : -1);
    }

    private void applyPanels(int target) {
        for (int i = 0; i < BODIES.length; i++) {
            View body = findViewById(BODIES[i]);
            View arrow = findViewById(ARROWS[i]);
            boolean open = i == target;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(open ? 180f : 0f).setDuration(180).start();
            if (arrow instanceof com.airbnb.lottie.LottieAnimationView) {
                com.airbnb.lottie.LottieAnimationView lav = (com.airbnb.lottie.LottieAnimationView) arrow;
                lav.setSpeed(1f);
                lav.setProgress(0f);
                lav.playAnimation();
            }
        }
    }

    private void setupFab() {
        android.widget.ScrollView scroll = findViewById(R.id.scrollConsole);
        com.google.android.material.floatingactionbutton.FloatingActionButton fab = findViewById(R.id.fabTop);
        final com.airbnb.lottie.LottieDrawable icon =
                com.lateshow.app.util.LottieIcon.into(fab, "ic_top", false);
        scroll.setOnScrollChangeListener((View.OnScrollChangeListener) (v, sx, sy, ox, oy) ->
                fab.setVisibility(sy > scroll.getHeight() / 2 ? View.VISIBLE : View.GONE));
        fab.setOnClickListener(v -> {
            if (icon != null) { icon.setProgress(0f); icon.playAnimation(); }
            scroll.smoothScrollTo(0, 0);
            fab.setVisibility(View.GONE);
        });
    }

    // ==================== 绑定 / 初始化 ====================

    private void bindViews() {
        tvConsoleStatus = findViewById(R.id.tvConsoleStatus);
        tvConsoleTime = findViewById(R.id.tvConsoleTime);
        autoCount = findViewById(R.id.autoCount);
        selCount = findViewById(R.id.selCount);
        tvRuntime = findViewById(R.id.tvRuntime);
        pauseWarn = findViewById(R.id.pauseWarn);
        searchInfo = findViewById(R.id.searchInfo);
        tvTablePage = findViewById(R.id.tvTablePage);
        tvAuthState = findViewById(R.id.tvAuthState);
        tvTokenValue = findViewById(R.id.tvTokenValue);
        tvStarStatus = findViewById(R.id.tvStarStatus);
        tvMaintOutput = findViewById(R.id.tvMaintOutput);
        tvLineHint = findViewById(R.id.tvLineHint);
        tvTokenListHint = findViewById(R.id.tvTokenListHint);
        tvTagIdxState = findViewById(R.id.tvTagIdxState);
        tvTagCapability = findViewById(R.id.tvTagCapability);
        tagIdxList = findViewById(R.id.tagIdxList);
        tokenList = findViewById(R.id.tokenList);
        statsGrid = findViewById(R.id.statsGrid);
        pauseRows = findViewById(R.id.pauseRows);
        statsSkeleton = findViewById(R.id.statsSkeleton);
        srcSkeleton = findViewById(R.id.srcSkeleton);
        logSkeleton = findViewById(R.id.logSkeleton);
        searchResults = findViewById(R.id.searchResults);
        tableResults = findViewById(R.id.tableResults);
        starList = findViewById(R.id.starList);
        srcList = findViewById(R.id.srcList);
        logList = findViewById(R.id.logList);
        chkAll = findViewById(R.id.chkAll);
        swAuthEnable = findViewById(R.id.swAuthEnable);
        spTable = findViewById(R.id.spTable);
        spLineSource = findViewById(R.id.spLineSource);
        spLinePolicy = findViewById(R.id.spLinePolicy);
        etSearch = findViewById(R.id.etSearch);
        etTableQuery = findViewById(R.id.etTableQuery);
        etStarTerms = findViewById(R.id.etStarTerms);
        etPwOld = findViewById(R.id.etPwOld);
        etPwNew = findViewById(R.id.etPwNew);
        etPwNew2 = findViewById(R.id.etPwNew2);

        findViewById(R.id.btnConsoleBack).setOnClickListener(v -> finish());
        // 手动刷新入口已按预览移除；数据仍由 onCreate 首次加载 + 5 秒轮询刷新

        spLinePolicy.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.play_line_policy)));
        ((ArrayAdapter<String>) spLinePolicy.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    private void setupSkeleton() {
        statsSkeleton.addView(Skeleton.statCards(this, 2));
        srcSkeleton.addView(Skeleton.settingRows(this, 4));
        logSkeleton.addView(Skeleton.settingRows(this, 3));
    }

    private void setupLists() {
        srcAdapter = new SyncSourceAdapter(new SyncSourceAdapter.Callbacks() {
            @Override public void onPickChanged() { syncSelectionUi(); }
            @Override public void onAction(String msg) { toast(msg); refreshAll(false); }
            @Override public Map<String, String> ctx() { return null; }
            @Override public void onEditCustom(Models.SyncSource s) { showCustomSourceDialog(s); }
            @Override public void onDeleteCustom(Models.SyncSource s) { confirmDeleteCustom(s); }
        });
        // 不再接 RecyclerView：控制台外层是 ScrollView(fillViewport=true)，
        // 内嵌 wrap_content RecyclerView 只会测量出 1 行（源站 57 条被裁成 1 条）。
        // 数据在 refreshAll 里用 renderInto() 直接铺进 LinearLayout。
        logAdapter = new LogAdapter();

        chkAll.setOnCheckedChangeListener((bv, on) -> {
            srcAdapter.setAllPicked(on);
            srcAdapter.renderInto(srcList);
            syncSelectionUi();
        });
    }

    private void bindActions() {
        // ② 同步操作
        findViewById(R.id.btnSyncAll).setOnClickListener(v -> post(com.lateshow.app.util.ApiPaths.SYNC_ALL_AUTO, "全站增量同步已受理"));
        findViewById(R.id.btnRefresh).setOnClickListener(v -> post(com.lateshow.app.util.ApiPaths.SOURCES_REFRESH, "源站列表刷新已受理"));
        findViewById(R.id.btnFullSync).setOnClickListener(v -> post(com.lateshow.app.util.ApiPaths.SYNC_ALL_FULL, "全站全量同步已受理（耗时长）"));
        findViewById(R.id.btnRecount).setOnClickListener(v -> post(com.lateshow.app.util.ApiPaths.MAINTENANCE_RECOUNT, "条数校准已受理"));
        findViewById(R.id.btnAddCustom).setOnClickListener(v -> showCustomSourceDialog(null));
        findViewById(R.id.btnProxyStatus).setOnClickListener(v -> showProxyStatusDialog());

        // ⑥ 批量自动同步
        findViewById(R.id.btnAllOn).setOnClickListener(v -> autoSyncBatch("all", true));
        findViewById(R.id.btnAllOff).setOnClickListener(v -> autoSyncBatch("all", false));
        findViewById(R.id.btnSelOn).setOnClickListener(v -> autoSyncBatch(pickedSlugs(), true));
        findViewById(R.id.btnSelOff).setOnClickListener(v -> autoSyncBatch(pickedSlugs(), false));

        // ④ 聚合搜索
        findViewById(R.id.btnSearch).setOnClickListener(v -> doSearch());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            doSearch();
            return true;
        });

        // ⑤ 数据表浏览
        findViewById(R.id.btnTablesReload).setOnClickListener(v -> loadTables());
        findViewById(R.id.btnTableSearch).setOnClickListener(v -> { tablePage = 1; loadTableRows(); });
        findViewById(R.id.btnTablePrev).setOnClickListener(v -> {
            if (tablePage > 1) { tablePage--; loadTableRows(); }
        });
        findViewById(R.id.btnTableNext).setOnClickListener(v -> {
            if (tablePage < tablePages) { tablePage++; loadTableRows(); }
        });

        // ⑧ 访问控制与 Token
        swAuthEnable.setOnCheckedChangeListener((bv, on) -> setAuthEnabled(on));
        findViewById(R.id.btnTokenCopy).setOnClickListener(v -> copyToken());
        findViewById(R.id.btnTokenRotate).setOnClickListener(v -> rotateToken());
        findViewById(R.id.btnChangePw).setOnClickListener(v -> changePassword());

        // ⑨ 明星词库
        findViewById(R.id.btnStarAdd).setOnClickListener(v -> addStarTerms());
        findViewById(R.id.btnStarRecompute).setOnClickListener(v ->
                postJson(com.lateshow.app.util.ApiPaths.STAR_TERMS_RECOMPUTE, "{}", "相关推荐重算已受理", this::loadStarTerms));

        // ⑩ 诊断与维护
        findViewById(R.id.btnRelatedStatus).setOnClickListener(v -> showJson(com.lateshow.app.util.ApiPaths.RELATED_STATUS, null));
        findViewById(R.id.btnStarTagsStatus).setOnClickListener(v -> showJson(com.lateshow.app.util.ApiPaths.STAR_TAGS_STATUS, null));
        findViewById(R.id.btnUntrackedStars).setOnClickListener(v -> showJson(com.lateshow.app.util.ApiPaths.STAR_TAGS_UNTRACKED, null));
        findViewById(R.id.btnRelatedRecompute).setOnClickListener(v ->
                post(com.lateshow.app.util.ApiPaths.MAINTENANCE_RELATED, "相关推荐重算已受理"));
        findViewById(R.id.btnStarTagsBackfill).setOnClickListener(v ->
                post(com.lateshow.app.util.ApiPaths.MAINTENANCE_STAR_TAGS, "明星标签回填已受理"));

        // ⑪ 播放线路策略
        findViewById(R.id.btnLineApply).setOnClickListener(v -> applyLinePolicy());
        findViewById(R.id.btnSourcePause).setOnClickListener(v -> toggleSourcePause());

        // ⑧ 具名 Token（v1.14.0）：签发 / 刷新 / 逐条吊销
        findViewById(R.id.btnTokenNew).setOnClickListener(v -> showCreateTokenDialog());
        findViewById(R.id.btnTokenListRefresh).setOnClickListener(v -> loadAuthTokens());

        // ⑫ 标签索引
        findViewById(R.id.btnTagIdxStatus).setOnClickListener(v -> loadTagIndex());
        findViewById(R.id.btnTagIdxRebuild).setOnClickListener(v -> confirmTagRebuild());
    }

    /** 首次进入预取一些不常变的数据，避免展开板块时空白 */
    private void loadStaticSections() {
        loadTables();
        loadAuthSection();
    }

    // ==================== ① 运行概览 / ③ 暂停 / ⑥ 源站 / ⑦ 日志 ====================

    private void refreshAll(boolean showSkeleton) {
        if (!uiAlive()) return;
        final int gen = ++pollGeneration;
        // 上一轮未完成的轮询请求整体取消：写操作后立刻刷新时不会有两批响应交叉落 UI
        VodSyncApi.cancelTag(POLL_TAG);
        // 状态行右侧的「上次刷新」：每轮请求发起时更新，替代已移除的手动刷新按钮
        if (tvConsoleTime != null) {
            tvConsoleTime.setText("上次刷新 " + android.text.format.DateFormat.format(
                    "HH:mm:ss", new java.util.Date()));
        }
        if (showSkeleton && !skelShown) {
            skelHandler.removeCallbacks(skelShowTask);
            skelHandler.postDelayed(skelShowTask, 220);
        }
        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.PAUSE, null, Models.PauseResp.class,
                POLL_TAG, new VodSyncApi.ApiCb<Models.PauseResp>() {
            @Override public void onOk(Models.PauseResp r) {
                if (!uiAlive() || gen != pollGeneration) return;
                Models.PauseState p = r.state != null ? r.state : new Models.PauseState();
                p.global = r.global; p.full = r.full; p.incremental = r.incremental;
                pauseState = p;
                buildPauseRows();
                updatePauseWarn();
            }
            @Override public void onErr(String m) { }
        });

        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.SOURCES, null, Models.SyncSourcesResp.class,
                POLL_TAG, new VodSyncApi.ApiCb<Models.SyncSourcesResp>() {
            @Override public void onOk(Models.SyncSourcesResp r) {
                if (!uiAlive() || gen != pollGeneration) return;
                Skeleton.hide(srcSkeleton, animSrc);
                srcAdapter.setItems(r.list != null ? r.list : new ArrayList<>());
                srcAdapter.renderInto(srcList);
                syncSelectionUi();
                refreshLineSourceSpinner();
                tvConsoleStatus.setText("● 已连接 · 源站 " + srcAdapter.getItemCount() + " 个 · 5 秒自动刷新");
                tvConsoleStatus.setTextColor(color(R.color.ok));
            }
            @Override public void onErr(String m) {
                if (!uiAlive() || gen != pollGeneration) return;
                Skeleton.hide(srcSkeleton, animSrc);
                tvConsoleStatus.setText("● 连接失败 · " + m);
                tvConsoleStatus.setTextColor(color(R.color.bad));
            }
        });

        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.LOGS,
                HomeFragment.map("limit", "20"), Models.LogsResp.class,
                POLL_TAG, new VodSyncApi.ApiCb<Models.LogsResp>() {
            @Override public void onOk(Models.LogsResp r) {
                if (!uiAlive() || gen != pollGeneration) return;
                Skeleton.hide(logSkeleton, animLog);
                logAdapter.setItems(r.list != null ? r.list : new ArrayList<>());
                logAdapter.renderInto(logList);
            }
            @Override public void onErr(String m) {
                if (!uiAlive() || gen != pollGeneration) return;
                Skeleton.hide(logSkeleton, animLog);
            }
        });

        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.STATS, null, Models.StatsResp.class,
                POLL_TAG, new VodSyncApi.ApiCb<Models.StatsResp>() {
            @Override public void onOk(Models.StatsResp r) {
                if (!uiAlive() || gen != pollGeneration) return;
                Skeleton.hide(statsSkeleton, animStats);
                renderStats(r);
            }
            @Override public void onErr(String m) {
                if (!uiAlive() || gen != pollGeneration) return;
                Skeleton.hide(statsSkeleton, animStats);
            }
        });
    }

    private void renderStats(Models.StatsResp s) {
        videosTotal = s.videosTotal;
        autoCount.setText("自动同步 " + s.sourcesEnabled + "/" + s.sourcesTotal);
        String[][] row1 = {
                {TimeUtil.thousands(s.sourcesTotal), "源站总数 · 已启用 " + s.sourcesEnabled},
                {TimeUtil.thousands(s.videosTotal), "已入库视频"}};
        String[][] row2 = {
                {String.valueOf(s.sourcesSyncing), "正在同步"},
                {TimeUtil.thousands(s.todayInserted), "今日新增 / 更新 " + TimeUtil.thousands(s.todayUpdated)}};
        boolean hasLoad = s.sync_load != null;
        String[][] row3 = hasLoad ? new String[][]{
                {s.sync_load.running + "/" + s.sync_load.limit,
                        "同步负载" + (s.sync_load.queued > 0 ? " · 排队 " + s.sync_load.queued : "")},
                {String.valueOf(s.sourcesUnfinished), "有断点待续传"}} : null;
        // 审计 A-25：5 秒轮询只复用并改文案，不再 removeAllViews + 重新 inflate
        ensureStatRows(hasLoad ? 3 : 2);
        bindStatRow(0, row1);
        bindStatRow(1, row2);
        if (hasLoad) bindStatRow(2, row3);
        StringBuilder sb = new StringBuilder();
        if (s.proxy != null && s.proxy.enabled) {
            sb.append("代理通道 · 已启用\n直连 ").append(s.proxy.directSuccess)
              .append(" · 代理 ").append(s.proxy.proxySuccess)
              .append(" · 失败 ").append(s.proxy.routeFail);
            if (s.proxy.preferred_hosts != null && !s.proxy.preferred_hosts.isEmpty()) {
                sb.append(" · 走代理 ").append(s.proxy.preferred_hosts.size()).append(" 站");
            }
        } else {
            sb.append("代理通道 · 未启用");
        }
        if (s.db_pool != null && s.db_pool.size > 0) {
            sb.append("\n连接池 · ").append(s.db_pool.checkedOut).append("/").append(s.db_pool.size)
              .append(" · 溢出 ").append(s.db_pool.overflow).append("/").append(s.db_pool.maxOverflow);
        }
        tvRuntime.setText(sb.toString());
        tvRuntime.setVisibility(View.VISIBLE);
    }

    /** 只在行数变化时增删，其余轮次复用已有卡片（A-25） */
    private void ensureStatRows(int want) {
        int dp8 = (int) (8 * getResources().getDisplayMetrics().density);
        while (statsGrid.getChildCount() > want) {
            statsGrid.removeViewAt(statsGrid.getChildCount() - 1);
        }
        while (statsGrid.getChildCount() < want) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp8;
            row.setLayoutParams(rowLp);
            for (int i = 0; i < 2; i++) {
                View card = getLayoutInflater().inflate(R.layout.item_stat, row, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.leftMargin = dp8;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            statsGrid.addView(row);
        }
    }

    private void bindStatRow(int idx, String[][] cells) {
        if (idx < 0 || idx >= statsGrid.getChildCount()) return;
        LinearLayout row = (LinearLayout) statsGrid.getChildAt(idx);
        for (int i = 0; i < cells.length && i < row.getChildCount(); i++) {
            ((TextView) row.getChildAt(i).findViewById(R.id.statValue)).setText(cells[i][0]);
            ((TextView) row.getChildAt(i).findViewById(R.id.statLabel)).setText(cells[i][1]);
        }
    }

    private void buildPauseRows() {
        pauseRows.removeAllViews();
        String[][] defs = {
                {"global", "全局暂停", "全量、增量与定时任务全部停止"},
                {"full", "暂停全量同步", "检查点保存，继续后自动续传"},
                {"incremental", "暂停增量同步", "水位线不动，继续后自动重跑"},
        };
        for (String[] d : defs) {
            View row = getLayoutInflater().inflate(R.layout.item_pause_row, pauseRows, false);
            ((TextView) row.findViewById(R.id.pTitle)).setText(d[1]);
            ((TextView) row.findViewById(R.id.pDesc)).setText(d[2]);
            Switch sw = row.findViewById(R.id.pSwitch);
            String scope = d[0];
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(scopeOn(scope));
            sw.setOnCheckedChangeListener((bv, on) -> {
                String path = on ? com.lateshow.app.util.ApiPaths.PAUSE : com.lateshow.app.util.ApiPaths.RESUME;
                VodSyncApi.get().post(path, HomeFragment.map("scope", scope), Models.MsgResp.class,
                        new VodSyncApi.ApiCb<Models.MsgResp>() {
                    @Override public void onOk(Models.MsgResp r) {
                        toast(r.msg != null ? r.msg : "已更新");
                        refreshAll(false);
                    }
                    @Override public void onErr(String m) { toast(m); sw.setChecked(!on); }
                });
            });
            pauseRows.addView(row);
        }
    }

    private boolean scopeOn(String scope) {
        if ("global".equals(scope)) return pauseState.global;
        if ("full".equals(scope)) return pauseState.full;
        return pauseState.incremental;
    }

    private void updatePauseWarn() {
        boolean g = pauseState.global, f = pauseState.full, i = pauseState.incremental;
        if (!g && !f && !i) { pauseWarn.setVisibility(View.GONE); return; }
        String text;
        if (g) text = "全局暂停中：全量、增量与定时任务均已停止";
        else if (f && i) text = "全量与增量均已暂停：继续后将从断点自动续传";
        else if (f) text = "全量同步已暂停：检查点已保存，继续后自动续传";
        else text = "增量同步已暂停：水位线不动，继续后自动重跑";
        pauseWarn.setVisibility(View.VISIBLE);
        pauseWarn.setText("● " + text);
    }

    private String pickedSlugs() {
        StringBuilder sb = new StringBuilder();
        for (Models.SyncSource s : srcAdapter.getItems()) {
            if (s.pick) {
                if (sb.length() > 0) sb.append(",");
                sb.append(s.slug);
            }
        }
        return sb.toString();
    }

    private void autoSyncBatch(String slugs, boolean enabled) {
        if (slugs == null || slugs.isEmpty()) { toast("未选择源站"); return; }
        VodSyncApi.get().post(com.lateshow.app.util.ApiPaths.SOURCES_AUTO_SYNC,
                HomeFragment.map("enabled", String.valueOf(enabled), "slugs", slugs),
                Models.MsgResp.class, new VodSyncApi.ApiCb<Models.MsgResp>() {
            @Override public void onOk(Models.MsgResp r) { toast(r.msg != null ? r.msg : "已更新"); refreshAll(false); }
            @Override public void onErr(String m) { toast(m); }
        });
    }

    private void syncSelectionUi() {
        int picked = 0, auto = 0;
        for (Models.SyncSource s : srcAdapter.getItems()) {
            if (s.pick) picked++;
            if (s.autoSync) auto++;
        }
        int total = srcAdapter.getItemCount();
        autoCount.setText("自动同步 " + auto + "/" + total);
        selCount.setText(picked == 0 ? "未选择源站" : "已选 " + picked + " 个源站");
        selCount.setTextColor(getColor(picked == 0 ? R.color.t3 : R.color.accText));
        if (total > 0) {
            chkAll.setOnCheckedChangeListener(null);
            chkAll.setChecked(picked == total);
            chkAll.setOnCheckedChangeListener((bv, c) -> {
                srcAdapter.setAllPicked(c);
                srcAdapter.renderInto(srcList);
                syncSelectionUi();
            });
        }
    }

    // ==================== 通用请求 ====================

    /** POST（query 传参），成功后刷新控制台 */
    private void post(String pathAndQuery, String okMsg) {
        String path = pathAndQuery;
        Map<String, String> params = null;
        int q = pathAndQuery.indexOf('?');
        if (q >= 0) {
            path = pathAndQuery.substring(0, q);
            params = new java.util.HashMap<>();
            for (String pair : pathAndQuery.substring(q + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) params.put(kv[0], kv[1]);
            }
        }
        VodSyncApi.get().post(path, params, Models.MsgResp.class, new VodSyncApi.ApiCb<Models.MsgResp>() {
            @Override public void onOk(Models.MsgResp r) { toast(r.msg != null ? r.msg : okMsg); refreshAll(false); }
            @Override public void onErr(String m) { toast(m); }
        });
    }

    private void postJson(String path, String body, String okMsg, Runnable after) {
        VodSyncApi.get().postJson(path, body, JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) {
                toast(r != null && r.has("msg") ? r.get("msg").getAsString() : okMsg);
                if (after != null) after.run();
            }
            @Override public void onErr(String m) { toast(m); }
        });
    }

    // ==================== ② 自定义源站 / 代理状态 ====================

    private void showCustomSourceDialog(final Models.SyncSource edit) {
        if (!uiAlive()) return;   // 审计 A-10：异步回调里弹窗必须先判 Activity 状态
        final boolean isEdit = edit != null;
        View dlg = getLayoutInflater().inflate(R.layout.dialog_custom_source, null);
        final EditText inName = dlg.findViewById(R.id.dcsName);
        final EditText inUrl = dlg.findViewById(R.id.dcsUrl);
        final EditText inSlug = dlg.findViewById(R.id.dcsSlug);
        final EditText inDesc = dlg.findViewById(R.id.dcsDesc);
        final TextView probeView = dlg.findViewById(R.id.dcsProbe);
        final TextView hintView = dlg.findViewById(R.id.dcsHint);
        dlg.findViewById(R.id.dcsToken).setVisibility(View.GONE);
        if (isEdit) {
            dlg.findViewById(R.id.dcsSlug).setVisibility(View.GONE);
            inName.setText(edit.name);
            inUrl.setText(edit.apiUrl);
            inDesc.setText(edit.description);
            hintView.setVisibility(View.VISIBLE);
            hintView.setText("⚠ 修改地址会清空该站已入库的数据并重置同步水位线（后端行为）。");
        } else {
            hintView.setVisibility(View.VISIBLE);
            hintView.setTextColor(color(R.color.t3));
            hintView.setText("标识留空时由名称自动推导；保存前会先抓一页验证地址可用性。");
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isEdit ? "编辑自定义源站 · " + edit.slug : "添加自定义源站")
                .setView(dlg)
                .setPositiveButton(isEdit ? "保存" : "添加", null)
                .setNegativeButton("取消", null)
                .setNeutralButton(isEdit ? null : "测试连接", null)
                .create();

        dialog.setOnShowListener(d -> {
            if (!isEdit) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    String url = inUrl.getText().toString().trim();
                    if (url.isEmpty()) { toast("请先填写接口地址"); return; }
                    probeView.setText("● 探测中…");
                    probeView.setTextColor(color(R.color.t2));
                    try {
                        VodSyncApi.get().postJson(com.lateshow.app.util.ApiPaths.SOURCES_PROBE,
                                new org.json.JSONObject().put("api_url", url).toString(),
                                Models.ProbeResp.class, new VodSyncApi.ApiCb<Models.ProbeResp>() {
                            @Override public void onOk(Models.ProbeResp r) {
                                if (r != null && r.ok) {
                                    probeView.setTextColor(color(R.color.ok));
                                    probeView.setText("● 可用 · " + (r.total > 0 ? "共 " + r.total + " 条 · " : "") + r.elapsedMs + "ms");
                                } else {
                                    probeView.setTextColor(color(R.color.bad));
                                    probeView.setText("● 不可用 · " + (r != null && r.error != null ? r.error : "未知原因"));
                                }
                            }
                            @Override public void onErr(String m) {
                                probeView.setTextColor(color(R.color.bad));
                                probeView.setText("● 探测失败 · " + m);
                            }
                        });
                    } catch (Exception e) { probeView.setText("● 探测失败 · " + e.getMessage()); }
                });
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = inName.getText().toString().trim();
                String url = inUrl.getText().toString().trim();
                String desc = inDesc.getText().toString().trim();
                if (name.isEmpty()) { toast("请填写源站名称"); return; }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast("接口地址需以 http:// 或 https:// 开头"); return;
                }
                if (isEdit) patchCustomSource(edit, name, url, desc, dialog);
                else addCustomSource(name, url, inSlug.getText().toString().trim(), desc, dialog, false);
            });
        });
        dialog.show();
    }

    private void addCustomSource(String name, String url, String slug, String desc,
                                 final AlertDialog dialog, final boolean force) {
        try {
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("name", name).put("api_url", url).put("description", desc)
                    .put("probe", true).put("allow_unreachable", force);
            if (!slug.isEmpty()) body.put("slug", slug);
            VodSyncApi.get().postJson(com.lateshow.app.util.ApiPaths.SOURCES_CUSTOM, body.toString(),
                    Models.CustomSourceResp.class, new VodSyncApi.ApiCb<Models.CustomSourceResp>() {
                @Override public void onOk(Models.CustomSourceResp r) {
                    dialog.dismiss();
                    toast(r.msg != null ? r.msg : "已添加");
                    refreshAll(false);
                }
                @Override public void onErr(String m) {
                    if (!uiAlive()) return;
                    if (!force && m != null && m.contains("源站不可用")) {
                        new MaterialAlertDialogBuilder(SyncConsoleActivity.this)
                                .setTitle("探测失败")
                                .setMessage(m + "\n\n确认地址无误仍要强制添加吗？")
                                .setPositiveButton("强制添加", (d2, w2) ->
                                        addCustomSource(name, url, slug, desc, dialog, true))
                                .setNegativeButton("取消", null).show();
                    } else toast(m);
                }
            });
        } catch (Exception e) { toast("请求构造失败：" + e.getMessage()); }
    }

    private void patchCustomSource(Models.SyncSource s, String name, String url, String desc, final AlertDialog dialog) {
        try {
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("name", name).put("api_url", url).put("description", desc);
            VodSyncApi.get().patchJson(com.lateshow.app.util.ApiPaths.sourceCustom(s.slug), body.toString(),
                    Models.MsgResp.class, new VodSyncApi.ApiCb<Models.MsgResp>() {
                @Override public void onOk(Models.MsgResp r) {
                    dialog.dismiss();
                    toast(r.msg != null ? r.msg : "已修改");
                    refreshAll(false);
                }
                @Override public void onErr(String m) { toast(m); }
            });
        } catch (Exception e) { toast("请求构造失败：" + e.getMessage()); }
    }

    private void confirmDeleteCustom(final Models.SyncSource s) {
        if (!uiAlive()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除自定义源站")
                .setMessage("确认删除「" + s.name + "」（" + s.slug + "）？\n"
                        + "该站已入库的 " + TimeUtil.thousands(s.totalVideos) + " 条数据将一并删除，且不可恢复。")
                .setPositiveButton("删除", (d, w) -> VodSyncApi.get().delete(
                        com.lateshow.app.util.ApiPaths.sourceCustom(s.slug),
                        HomeFragment.map("confirm", s.slug, "drop_table", "true"),
                        Models.MsgResp.class, new VodSyncApi.ApiCb<Models.MsgResp>() {
                    @Override public void onOk(Models.MsgResp r) { toast(r.msg != null ? r.msg : "已删除"); refreshAll(false); }
                    @Override public void onErr(String m) { toast(m); }
                }))
                .setNegativeButton("取消", null).show();
    }

    private void showProxyStatusDialog() {
        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.STATS, null, Models.StatsResp.class,
                new VodSyncApi.ApiCb<Models.StatsResp>() {
            @Override public void onOk(Models.StatsResp s) {
                if (!uiAlive()) return;   // 异步回调里构造并弹出对话框
                StringBuilder sb = new StringBuilder();
                if (s.proxy == null) {
                    sb.append("该版本后端未上报代理状态（需 v1.4.0+）。");
                } else {
                    sb.append(s.proxy.enabled ? "代理已启用" : "代理未启用").append('\n');
                    if (s.proxy.urls != null && !s.proxy.urls.isEmpty()) {
                        sb.append("代理地址：").append(join(s.proxy.urls)).append('\n');
                    }
                    sb.append("直连成功 ").append(s.proxy.directSuccess)
                      .append(" · 代理成功 ").append(s.proxy.proxySuccess)
                      .append(" · 路由失败 ").append(s.proxy.routeFail).append('\n');
                    sb.append(s.proxy.preferred_hosts == null || s.proxy.preferred_hosts.isEmpty()
                            ? "当前无源站走代理（全部直连）"
                            : "当前走代理的源站：" + join(new ArrayList<>(s.proxy.preferred_hosts.keySet())));
                }
                new MaterialAlertDialogBuilder(SyncConsoleActivity.this)
                        .setTitle("中转代理状态").setMessage(sb.toString())
                        .setPositiveButton("好", null).show();
            }
            @Override public void onErr(String m) { toast(m); }
        });
    }

    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ==================== ④ 聚合搜索 ====================

    private void doSearch() {
        // 后端 wd 是 max_length=64（M2），控制台同样截断，避免直接吃 422
        final String kw = com.lateshow.app.api.SyncCatalog.clamp(etSearch.getText().toString());
        if (kw.isEmpty()) { toast("请输入关键词"); return; }
        hideKeyboard();
        searchInfo.setText("搜索中…");
        searchResults.removeAllViews();
        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.SEARCH,
                HomeFragment.map("wd", kw, "page", "1", "page_size", "20"),
                JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) {
                JsonArray arr = arrayOf(r, "list");
                int total = intOf(r, "total");
                int ms = intOf(r, "elapsed_ms");
                boolean partial = r != null && r.has("total_is_partial")
                        && r.get("total_is_partial").getAsBoolean();
                int failed = arrayOf(r, "sources_failed").size();
                searchInfo.setText("找到 " + total + " 条 · 耗时 " + ms + "ms · 已搜 "
                        + intOf(r, "sources_searched") + " 个源站"
                        + (partial ? " · 结果不完整" : "")
                        + (failed > 0 ? "（" + failed + " 源失败）" : ""));
                if (arr.size() == 0) {
                    addResult(searchResults, "没有匹配的影片", "换个关键词试试，或先在「源站同步状态」确认已同步数据");
                    return;
                }
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    addResult(searchResults, str(o, "vod_name"),
                            str(o, "source_name") + " · " + str(o, "type_name")
                                    + " · " + str(o, "vod_remarks") + " · " + str(o, "vod_time"));
                }
            }
            @Override public void onErr(String m) { searchInfo.setText("搜索失败：" + m); }
        });
    }

    // ==================== ⑤ 数据表浏览 ====================

    private void loadTables() {
        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.ADMIN_TABLES, null, JsonObject.class,
                new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) {
                tableNames.clear();
                List<String> labels = new ArrayList<>();
                JsonArray arr = arrayOf(r, "tables");
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String name = str(o, "name");
                    if (name.isEmpty()) continue;
                    // v1.12.1：vod_* 表带 label（源站中文名），显示更友好；请求仍用 name
                    String label = str(o, "label");
                    tableNames.add(name);
                    labels.add((label.isEmpty() ? name : label + " · " + name)
                            + "（" + intOf(o, "rows") + " 行）");
                }
                ArrayAdapter<String> ad = new ArrayAdapter<>(SyncConsoleActivity.this,
                        android.R.layout.simple_spinner_item, labels);
                ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spTable.setAdapter(ad);
                if (!tableNames.isEmpty()) loadTableRows();
            }
            @Override public void onErr(String m) {
                tvTablePage.setText("表清单读取失败：" + m);
            }
        });
    }

    private void loadTableRows() {
        if (tableNames.isEmpty()) return;
        int pos = spTable.getSelectedItemPosition();
        if (pos < 0 || pos >= tableNames.size()) return;
        String table = tableNames.get(pos);
        String kw = etTableQuery.getText().toString().trim();
        String path = com.lateshow.app.util.ApiPaths.tableRows(table);
        tableResults.removeAllViews();
        tvTablePage.setText("查询中…");
        VodSyncApi.get().get(path,
                HomeFragment.map("page", String.valueOf(tablePage), "page_size", "20", "q", kw),
                JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) {
                tablePage = intOf(r, "page") > 0 ? intOf(r, "page") : tablePage;
                tablePages = Math.max(1, intOf(r, "pages"));
                tvTablePage.setText("第 " + tablePage + " / " + tablePages + " 页 · 共 "
                        + intOf(r, "total") + " 行");
                JsonArray rows = arrayOf(r, "rows");
                if (rows.size() == 0) {
                    addResult(tableResults, "没有数据", "可清空关键词后浏览全表");
                    return;
                }
                for (JsonElement e : rows) {
                    if (!e.isJsonObject()) continue;
                    StringBuilder line = new StringBuilder();
                    for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                        JsonElement v = en.getValue();
                        String text = (v == null || v.isJsonNull()) ? "" : v.getAsString();
                        if (text.length() > 60) text = text.substring(0, 60) + "…";
                        if (line.length() > 0) line.append(" · ");
                        line.append(en.getKey()).append("=").append(text);
                        if (line.length() > 220) break;
                    }
                    addResult(tableResults, "#" + (tableResults.getChildCount() + 1), line.toString());
                }
            }
            @Override public void onErr(String m) { tvTablePage.setText("查询失败：" + m); }
        });
    }

    // ==================== ⑧ 访问控制与 Token ====================

    /**
     * 审计 A-06：不再请求 {@code /auth/token} 把明文拉到界面上常驻，也不写剪贴板。
     * 只展示掩码；新生成的 Token 直接写入 Keystore（本 App 自动完成凭据轮换）。
     */
    private String tokenMaskText() {
        String t = transientToken;
        if (t == null || t.isEmpty()) t = PrefStore.get().apiToken();
        if (t == null || t.isEmpty()) return "（本 App 尚未配置 API Token · 请到设置页填写）";
        return "已配置 " + com.lateshow.app.util.Credentials.mask(t) + " · 明文已隐藏";
    }

    /** 后端返回新 Token 时调用：只落 Keystore，不在界面常驻明文 */
    private void adoptNewToken(JsonObject r) {
        if (r == null || !r.has("token") || r.get("token").isJsonNull()) return;
        String t = r.get("token").getAsString();
        if (t == null || t.trim().isEmpty()) return;
        transientToken = t.trim();
        PrefStore.get().setApiToken(transientToken);   // 加密落盘 + 清跨主机缓存
        if (uiAlive() && tvTokenValue != null) {
            handler.removeCallbacks(hideTokenTask);
            tvTokenValue.setText(tokenMaskText());
            tvTokenValue.setTextColor(color(R.color.t2));
        }
    }

    private void loadAuthSection() {
        if (!uiAlive()) return;
        tvTokenValue.setText(tokenMaskText());
        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.AUTH_STATE, null, Models.AuthState.class,
                new VodSyncApi.ApiCb<Models.AuthState>() {
            @Override public void onOk(Models.AuthState s) {
                if (!uiAlive()) return;
                tvAuthState.setText("● " + (s.enabled ? "已启用授权访问" : "未启用授权访问")
                        + (s.loggedIn ? " · 已登录" : "")
                        + (s.hasToken ? " · 已生成 Token" : ""));
                swAuthEnable.setOnCheckedChangeListener(null);
                swAuthEnable.setChecked(s.enabled);
                swAuthEnable.setOnCheckedChangeListener((bv, on) -> setAuthEnabled(on));
            }
            @Override public void onErr(String m) {
                if (uiAlive()) tvAuthState.setText("● 状态读取失败：" + m);
            }
        });
    }

    private void setAuthEnabled(boolean on) {
        if (!uiAlive()) return;
        try {
            VodSyncApi.get().postJson(com.lateshow.app.util.ApiPaths.AUTH_SETTINGS,
                    new org.json.JSONObject().put("enabled", on).toString(),
                    JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
                @Override public void onOk(JsonObject r) {
                    if (!uiAlive()) return;
                    adoptNewToken(r);
                    toast(on ? "已启用授权访问 · 新 Token 已自动同步到本 App" : "已关闭授权访问");
                    loadAuthSection();
                }
                @Override public void onErr(String m) { if (uiAlive()) { toast(m); loadAuthSection(); } }
            });
        } catch (Exception e) { toast("请求构造失败：" + e.getMessage()); }
    }

    /**
     * 审计 A-06：不再把 Token 写入系统剪贴板（其它 App 与输入法均可读取）。
     * 改为「临时显示 10 秒」，到点自动恢复掩码，明文只存在于本页内存。
     */
    private void copyToken() {
        if (!uiAlive()) return;
        if (transientToken == null || transientToken.isEmpty()) {
            String local = PrefStore.get().apiToken();
            if (local == null || local.isEmpty()) {
                toast("本 App 尚未配置 Token，请到设置页填写");
                return;
            }
            transientToken = local;
        }
        tvTokenValue.setText(transientToken);
        tvTokenValue.setTextColor(color(R.color.warn));
        handler.removeCallbacks(hideTokenTask);
        handler.postDelayed(hideTokenTask, 10000);
        toast("明文临时显示 10 秒 · 不会写入剪贴板");
    }

    private void rotateToken() {
        if (!uiAlive()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("重新生成 API Token")
                .setMessage("旧 Token 立即失效，本 App 会自动改用新 Token；其它客户端需手动更新。继续？")
                .setPositiveButton("重新生成", (d, w) ->
                        VodSyncApi.get().postJson(com.lateshow.app.util.ApiPaths.AUTH_TOKEN,
                                "{}", JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
                            @Override public void onOk(JsonObject r) {
                                if (!uiAlive()) return;
                                adoptNewToken(r);
                                toast("新 Token 已生成并自动写入本 App 凭据");
                            }
                            @Override public void onErr(String m) { if (uiAlive()) toast(m); }
                        }))
                .setNegativeButton("取消", null).show();
    }

    private void changePassword() {
        String old = etPwOld.getText().toString();
        String nw = etPwNew.getText().toString();
        String nw2 = etPwNew2.getText().toString();
        if (!nw.equals(nw2)) { toast("两次输入的新密码不一致"); return; }
        if (nw.length() < 6) { toast("新密码至少 6 位"); return; }
        try {
            VodSyncApi.get().postJson(com.lateshow.app.util.ApiPaths.AUTH_PASSWORD,
                    new org.json.JSONObject().put("old", old).put("new", nw).toString(),
                    JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
                @Override public void onOk(JsonObject r) {
                    etPwOld.setText(""); etPwNew.setText(""); etPwNew2.setText("");
                    toast("管理面板密码已修改");
                }
                @Override public void onErr(String m) { toast(m); }
            });
        } catch (Exception e) { toast("请求构造失败：" + e.getMessage()); }
    }

    // ==================== ⑧-b 具名 Token（v1.14.0） ====================

    /**
     * GET /api/v1/auth/tokens —— 已签发 Token 清单（**后端不回显明文**）。
     *
     * <p>与「重新生成」的区别：重置会立刻作废当前 Token，签发具名 Token 不动现有的。
     * 典型用法是给电视 / 车机单独发一枚只读 Token，admin 留给本控制台（B-08）。
     */
    private void loadAuthTokens() {
        if (!uiAlive() || tvTokenListHint == null) return;
        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.AUTH_TOKENS, null,
                Models.AuthTokenList.class, new VodSyncApi.ApiCb<Models.AuthTokenList>() {
            @Override public void onOk(Models.AuthTokenList r) {
                if (!uiAlive()) return;
                List<Models.AuthToken> list = r != null && r.list != null ? r.list : new ArrayList<>();
                int admin = 0, read = 0;
                for (Models.AuthToken t : list) {
                    if ("admin".equals(t.scope)) admin++; else read++;
                }
                tvTokenListHint.setText("● 共 " + list.size() + " 枚 · 管理 " + admin + " / 只读 " + read);
                tokenList.removeAllViews();
                if (list.isEmpty()) {
                    addResult(tokenList, "没有可用 Token", "点「签发只读 Token」新建一枚");
                    return;
                }
                for (Models.AuthToken t : list) addTokenRow(t);
            }
            @Override public void onErr(String m) {
                if (uiAlive()) tvTokenListHint.setText("● 清单读取失败：" + m);
            }
        });
    }

    /** 清单单行：作用域 / 备注 / 签发与最近使用时间 + 吊销（存量单 Token 只能重置不能单独吊销） */
    private void addTokenRow(final Models.AuthToken t) {
        String sub = timeShort(t.createdAt) + " 签发"
                + (t.lastUsedAt == null || t.lastUsedAt.isEmpty() ? " · 从未使用" : " · 最近 " + timeShort(t.lastUsedAt))
                + (t.note == null || t.note.isEmpty() ? "" : " · " + t.note);
        LinearLayout box = addResult(tokenList,
                (t.legacy() ? "存量 Token" : "具名 Token #" + t.id)
                        + " · " + ("admin".equals(t.scope) ? "管理" : "只读"),
                sub);
        if (t.legacy()) return;   // id=0 只能通过「重新生成」轮换
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallBtn("吊销", v -> confirmRevokeToken(t)));
        box.addView(row);
    }

    private void confirmRevokeToken(final Models.AuthToken t) {
        if (!uiAlive()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("吊销 Token #" + t.id)
                .setMessage("该 Token 立即失效；正在用它的设备会 401，需要重新配置。继续？")
                .setPositiveButton("吊销", (d, w) -> revokeToken(t))
                .setNegativeButton("取消", null).show();
    }

    private void revokeToken(final Models.AuthToken t) {
        VodSyncApi.get().delete(com.lateshow.app.util.ApiPaths.authToken(t.id), null,
                JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) {
                if (!uiAlive()) return;
                toast("Token #" + t.id + " 已吊销");
                loadAuthTokens();
            }
            @Override public void onErr(String m) { if (uiAlive()) toast(m); }
        });
    }

    /** 签发对话框：作用域（只读 / 管理）+ 可选备注 */
    private void showCreateTokenDialog() {
        if (!uiAlive()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"只读 read（推荐：TV / 车机）", "管理 admin（可改同步与配置）"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        box.addView(sp);

        final EditText note = new EditText(this);
        note.setHint("备注（可选，例如：客厅电视）");
        note.setTextSize(12f);
        note.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(note, lp);

        new MaterialAlertDialogBuilder(this)
                .setTitle("签发具名 Token")
                .setView(box)
                .setPositiveButton("签发", (d, w) -> createToken(
                        sp.getSelectedItemPosition() == 1 ? "admin" : "read",
                        note.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void createToken(String scope, String note) {
        try {
            org.json.JSONObject body = new org.json.JSONObject().put("scope", scope);
            if (!note.isEmpty()) body.put("note", note);
            VodSyncApi.get().postJson(com.lateshow.app.util.ApiPaths.AUTH_TOKENS, body.toString(),
                    Models.TokenCreateResp.class, new VodSyncApi.ApiCb<Models.TokenCreateResp>() {
                @Override public void onOk(Models.TokenCreateResp r) {
                    if (!uiAlive()) return;
                    loadAuthTokens();
                    if (r == null || r.token == null || r.token.isEmpty()) {
                        toast("已签发，但响应里没有明文（请到服务端查看）");
                        return;
                    }
                    showIssuedToken(r);
                }
                @Override public void onErr(String m) { if (uiAlive()) toast(m); }
            });
        } catch (Exception e) { toast("请求构造失败：" + e.getMessage()); }
    }

    /** 明文只在这里出现一次（后端不存明文），所以必须让用户当场抄走 */
    private void showIssuedToken(final Models.TokenCreateResp r) {
        TextView tv = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        tv.setTextSize(12f);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setText(r.token);
        new MaterialAlertDialogBuilder(this)
                .setTitle("新 Token（只显示这一次）")
                .setMessage("作用域：" + r.scope + (r.note == null || r.note.isEmpty() ? "" : " · " + r.note)
                        + "\n关闭后无法再查看，请立刻填到目标设备。")
                .setView(tv)
                .setPositiveButton("复制", (d, w) -> {
                    Object svc = getSystemService(Context.CLIPBOARD_SERVICE);
                    if (svc instanceof android.content.ClipboardManager) {
                        ((android.content.ClipboardManager) svc)
                                .setPrimaryClip(android.content.ClipData.newPlainText("VodSync Token", r.token));
                        toast("已复制 · 用完记得从剪贴板清掉");
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** "2026-11-10T08:12:09.384515" → "11-10 08:12"；解析失败原样返回 */
    private static String timeShort(String iso) {
        if (iso == null || iso.isEmpty()) return "-";
        try {
            String d = iso.length() >= 16 ? iso.substring(0, 16) : iso;   // yyyy-MM-ddTHH:mm
            return d.substring(5).replace('T', ' ');
        } catch (Exception e) { return iso; }
    }

    // ==================== ⑫ 标签索引（搜索可用性） ====================

    /**
     * GET /api/v1/tag/status —— 标签倒排索引就绪度。
     *
     * <p>这张卡存在的唯一理由：{@code tag_search} 能力声明与索引覆盖率可能不一致。
     * 后端声明了能力、客户端就会走 {@code ?tag=}，一旦索引只覆盖 3%，用户点标签
     * 搜不到片还不知道为什么。这里把 rows / 覆盖率 / 在途站点摊开，能一眼看出
     * 「是没数据还是没建索引」。
     */
    private void loadTagIndex() {
        if (!uiAlive() || tvTagIdxState == null) return;
        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.TAG_STATUS, null,
                Models.TagStatusResp.class, new VodSyncApi.ApiCb<Models.TagStatusResp>() {
            @Override public void onOk(Models.TagStatusResp r) {
                if (!uiAlive() || r == null) return;
                StringBuilder sb = new StringBuilder();
                sb.append(r.ready ? "● 索引已就绪" : "● 索引未就绪")
                  .append(r.enabled ? " · 功能已启用" : " · 功能已关闭")
                  .append(r.onSync ? " · 同步时增量维护" : " · 同步不维护");
                sb.append("\n已建索引 ").append(TimeUtil.thousands((int) Math.min(r.rows, Integer.MAX_VALUE)))
                  .append(" 行");
                if (videosTotal > 0) {
                    double pct = r.rows * 100.0 / videosTotal;
                    sb.append(" · 覆盖约 ").append(String.format(java.util.Locale.US, "%.1f", pct)).append("%")
                      .append(pct < 30 ? "（偏低：标签检索会漏片）" : "");
                }
                int building = r.buildingCount();
                if (building > 0) sb.append("\n正在回填 ").append(building).append(" 个源站（后台串行，可断点续跑）");
                tvTagIdxState.setText(sb.toString());

                // 能力声明与数据是否一致：这是 M5 的现场证据
                boolean declared = com.lateshow.app.api.ServerCaps.supports(
                        com.lateshow.app.api.ServerCaps.CAP_TAG_SEARCH);
                boolean consistent = declared == r.ready;
                tvTagCapability.setTextColor(color(consistent ? R.color.t3 : R.color.warn));
                tvTagCapability.setText("能力声明 tag_search = " + (declared ? "是" : "否")
                        + " · 索引 ready = " + (r.ready ? "是" : "否")
                        + (consistent ? " · 一致" : " · 不一致：客户端会按声明走，可能搜不到片"));

                renderTagSources(r);
            }
            @Override public void onErr(String m) {
                if (uiAlive()) tvTagIdxState.setText("● 状态读取失败：" + m);
            }
        });
    }

    /** 逐站进度：只列「在跑 / 失败 / 有索引行」的，避免 57 行刷屏 */
    private void renderTagSources(Models.TagStatusResp r) {
        if (tagIdxList == null) return;
        tagIdxList.removeAllViews();
        List<Models.TagSourceProgress> src = r.sources != null ? r.sources : new ArrayList<>();
        int shown = 0;
        for (Models.TagSourceProgress s : src) {
            boolean busy = "running".equals(s.status) || "pending".equals(s.status);
            boolean bad = "error".equals(s.status);
            if (!busy && !bad && s.indexed <= 0) continue;
            if (shown >= 12) break;
            String sub = (busy ? "回填中 " : bad ? "失败 " : "已建 ")
                    + String.format(java.util.Locale.US, "%.0f", s.progress) + "%"
                    + " · 索引 " + s.indexed + " 行 / 源站 " + s.total + " 条"
                    + (s.error == null || s.error.isEmpty() ? "" : " · " + s.error);
            addResult(tagIdxList, s.slug, sub);
            shown++;
        }
        if (shown == 0) addResult(tagIdxList, "尚未建立逐站索引",
                "点「重建索引」让后端按源站逐站回填（可断点续跑）");
        else if (src.size() > shown) addResult(tagIdxList, "… 还有 " + (src.size() - shown) + " 个源站", "仅显示前 12 条");
    }

    private void confirmTagRebuild() {
        if (!uiAlive()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("重建标签索引")
                .setMessage("默认按增量补齐（快，不动已有索引）。\n"
                        + "「强制重建」会先清空标签行再从头跑，耗时很长，只在怀疑索引与数据不一致时用。")
                .setPositiveButton("增量补齐", (d, w) -> rebuildTagIndex(false))
                .setNeutralButton("强制重建", (d, w) -> rebuildTagIndex(true))
                .setNegativeButton("取消", null).show();
    }

    private void rebuildTagIndex(boolean force) {
        Map<String, String> p = HomeFragment.map("slug", "", "force", String.valueOf(force));
        VodSyncApi.get().post(com.lateshow.app.util.ApiPaths.MAINTENANCE_TAG_INDEX, p,
                Models.TagIndexRebuildResp.class, new VodSyncApi.ApiCb<Models.TagIndexRebuildResp>() {
            @Override public void onOk(Models.TagIndexRebuildResp r) {
                if (!uiAlive()) return;
                toast(r != null && r.msg != null && !r.msg.isEmpty()
                        ? r.msg
                        : (r != null && r.accepted ? "回填任务已受理" : "已有同类任务在执行，本次未重复提交"));
                loadTagIndex();
            }
            @Override public void onErr(String m) { if (uiAlive()) toast(m); }
        });
    }

    // ==================== ⑨ 明星词库 ====================

    private void loadStarTerms() {
        VodSyncApi.get().get(com.lateshow.app.util.ApiPaths.STAR_TERMS, null, JsonObject.class,
                new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) {
                JsonArray arr = arrayOf(r, "list");
                tvStarStatus.setText("启用 " + intOf(r, "active") + " 条 · 共 " + arr.size()
                        + " 条 · 推断模式 " + str(r, "infer_mode"));
                starList.removeAllViews();
                int shown = 0;
                for (JsonElement e : arr) {
                    if (!e.isJsonObject() || shown >= 30) continue;
                    addStarRow(e.getAsJsonObject());
                    shown++;
                }
                if (arr.size() > shown) addResult(starList, "… 还有 " + (arr.size() - shown) + " 条", "仅显示前 30 条");
                if (arr.size() == 0) addResult(starList, "词库为空", "在上方批量粘贴主演名后点「批量添加」");
            }
            @Override public void onErr(String m) { tvStarStatus.setText("读取失败：" + m); }
        });
    }

    /** 明星词库单行：名称 / 状态 + 面板同款的单条操作（停用、改别名、删除） */
    private void addStarRow(JsonObject o) {
        final int id = intOf(o, "id");
        final boolean enabled = o.has("enabled") && o.get("enabled").getAsBoolean();
        LinearLayout box = addResult(starList, str(o, "term"),
                (enabled ? "启用" : "停用")
                        + (str(o, "aliases").isEmpty() ? "" : " · 别名 " + str(o, "aliases"))
                        + (str(o, "note").isEmpty() ? "" : " · " + str(o, "note")));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallBtn(enabled ? "停用" : "启用", v ->
                patchTerm(id, "{\"enabled\":" + (!enabled) + "}")));
        row.addView(smallBtn("改别名", v -> editTermAlias(id, str(o, "aliases"))));
        row.addView(smallBtn("删除", v -> {
            if (!uiAlive()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("删除词条")
                    .setMessage("确认删除「" + str(o, "term") + "」？")
                    .setPositiveButton("删除", (d, w) -> VodSyncApi.get().delete(
                            com.lateshow.app.util.ApiPaths.starTerm(id), null, JsonObject.class,
                            new VodSyncApi.ApiCb<JsonObject>() {
                        @Override public void onOk(JsonObject r) { toast("已删除"); loadStarTerms(); }
                        @Override public void onErr(String m) { toast(m); }
                    }))
                    .setNegativeButton("取消", null).show();
        }));
        box.addView(row);
    }

    private void patchTerm(int id, String body) {
        VodSyncApi.get().patchJson(com.lateshow.app.util.ApiPaths.starTerm(id), body, JsonObject.class,
                new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) { toast("已更新"); loadStarTerms(); }
            @Override public void onErr(String m) { toast(m); }
        });
    }

    private void editTermAlias(int id, String current) {
        if (!uiAlive()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(current);
        input.setHint("逗号 / 顿号 / 竖线分隔");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new MaterialAlertDialogBuilder(this)
                .setTitle("修改别名")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        patchTerm(id, new org.json.JSONObject()
                                .put("aliases", input.getText().toString()).toString());
                    } catch (Exception e) { toast("请求构造失败"); }
                })
                .setNegativeButton("取消", null).show();
    }

    private TextView smallBtn(String text, View.OnClickListener listener) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10.5f);
        t.setTextColor(color(R.color.t2));
        t.setBackgroundResource(R.drawable.bg_smbtn);
        int dp = (int) (8 * getResources().getDisplayMetrics().density);
        t.setPadding(dp, dp / 2, dp, dp / 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp / 2, dp, 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(listener);
        return t;
    }

    private void addStarTerms() {
        String terms = etStarTerms.getText().toString().trim();
        if (terms.isEmpty()) { toast("请先粘贴要添加的主演名"); return; }
        try {
            VodSyncApi.get().postJson(com.lateshow.app.util.ApiPaths.STAR_TERMS,
                    new org.json.JSONObject().put("terms", terms).toString(),
                    JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
                @Override public void onOk(JsonObject r) {
                    etStarTerms.setText("");
                    toast(r != null && r.has("msg") ? r.get("msg").getAsString() : "已添加");
                    loadStarTerms();
                }
                @Override public void onErr(String m) { toast(m); }
            });
        } catch (Exception e) { toast("请求构造失败：" + e.getMessage()); }
    }

    // ==================== ⑩ 诊断与维护 ====================

    private void showJson(String path, Map<String, String> params) {
        tvMaintOutput.setText("读取中…");
        VodSyncApi.get().get(path, params, JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) { tvMaintOutput.setText(pretty(r)); }
            @Override public void onErr(String m) { tvMaintOutput.setText("读取失败：" + m); }
        });
    }

    /** 把任意 JSON 对象转成可读的多行 key: value（嵌套只显示规模，避免刷屏） */
    private static String pretty(JsonObject o) {
        if (o == null) return "（空响应）";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            JsonElement v = en.getValue();
            String text;
            if (v == null || v.isJsonNull()) text = "-";
            else if (v.isJsonArray()) {
                JsonArray a = v.getAsJsonArray();
                text = a.size() + " 项";
                if (a.size() > 0 && a.get(0).isJsonObject()) {
                    JsonObject first = a.get(0).getAsJsonObject();
                    StringBuilder keys = new StringBuilder();
                    for (String k : first.keySet()) {
                        if (keys.length() > 0) keys.append(", ");
                        keys.append(k);
                    }
                    text += "（字段：" + keys + "）";
                }
            } else if (v.isJsonObject()) text = v.getAsJsonObject().size() + " 个字段";
            else text = v.getAsString();
            if (text.length() > 180) text = text.substring(0, 180) + "…";
            sb.append(en.getKey()).append(": ").append(text).append('\n');
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "（无字段）" : (out.length() > 2000 ? out.substring(0, 2000) + "…" : out);
    }

    // ==================== ⑪ 播放线路策略 ====================

    private void refreshLineSourceSpinner() {
        List<String> labels = new ArrayList<>();
        List<String> slugs = new ArrayList<>();
        for (Models.SyncSource s : srcAdapter.getItems()) {
            slugs.add(s.slug);
            labels.add(s.name + "（" + s.slug + "）");
        }
        // 5 秒轮询也会走到这里：数据没变就别重建 Adapter，否则会把用户正在选的源站重置掉
        if (labels.equals(lineSourceLabels)) return;
        lineSourceLabels.clear(); lineSourceLabels.addAll(labels);
        lineSourceSlugs.clear(); lineSourceSlugs.addAll(slugs);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLineSource.setAdapter(ad);
    }

    private Models.SyncSource selectedLineSource() {
        int pos = spLineSource.getSelectedItemPosition();
        if (pos < 0 || pos >= srcAdapter.getItems().size()) return null;
        return srcAdapter.getItems().get(pos);
    }

    private void applyLinePolicy() {
        Models.SyncSource s = selectedLineSource();
        if (s == null) { toast("请先选择源站"); return; }
        int pos = spLinePolicy.getSelectedItemPosition();
        String policy = POLICY_VALUES[pos >= 0 && pos < POLICY_VALUES.length ? pos : 0];
        VodSyncApi.get().post(com.lateshow.app.util.ApiPaths.sourcePlayLinePolicy(s.slug),
                HomeFragment.map("policy", policy), Models.MsgResp.class,
                new VodSyncApi.ApiCb<Models.MsgResp>() {
            @Override public void onOk(Models.MsgResp r) {
                tvLineHint.setText("● " + s.name + " 已设为 " + policy);
                toast(r.msg != null ? r.msg : "线路策略已更新");
                refreshAll(false);
            }
            @Override public void onErr(String m) { tvLineHint.setText("● 设置失败：" + m); }
        });
    }

    private void toggleSourcePause() {
        final Models.SyncSource s = selectedLineSource();
        if (s == null) { toast("请先选择源站"); return; }
        boolean wantPause = !s.paused;
        String path = wantPause
                ? com.lateshow.app.util.ApiPaths.sourcePause(s.slug)
                : com.lateshow.app.util.ApiPaths.sourceResume(s.slug);
        VodSyncApi.get().post(path, null, Models.MsgResp.class,
                new VodSyncApi.ApiCb<Models.MsgResp>() {
            @Override public void onOk(Models.MsgResp r) {
                toast(r.msg != null ? r.msg : (wantPause ? "已暂停该源站" : "已继续该源站"));
                tvLineHint.setText("● " + s.name + (wantPause ? " 已暂停" : " 已继续"));
                refreshAll(false);
            }
            @Override public void onErr(String m) { toast(m); }
        });
    }

    // ==================== 工具 ====================

    /** 往结果容器追加一条「主标题 + 副标题」，样式沿用设置页小字/卡片 */
    private LinearLayout addResult(LinearLayout parent, String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundResource(R.drawable.bg_field);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (5 * getResources().getDisplayMetrics().density);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12f);
        t.setTextColor(color(R.color.t1));
        box.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(sub);
            d.setTextSize(10f);
            d.setTextColor(color(R.color.t3));
            d.setLineSpacing(1f, 1.4f);
            box.addView(d);
        }
        parent.addView(box);
        return box;
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    private static JsonArray arrayOf(JsonObject o, String key) {
        if (o == null) return new JsonArray();
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static int intOf(JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return 0;
        try { return e.getAsInt(); } catch (Exception ignored) { return 0; }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int color(int id) {
        return ContextCompat.getColor(this, id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 审计 A-33：先撤销可能残留的旧轮询，避免多次 onResume 叠出多个 5 秒循环
        handler.removeCallbacks(pollTask);
        polling = true;
        handler.postDelayed(pollTask, 5000);
        refreshAll(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        handler.removeCallbacks(pollTask);
        handler.removeCallbacks(hideTokenTask);
        if (tvTokenValue != null) tvTokenValue.setText(tokenMaskText());
    }

    @Override
    protected void onDestroy() {
        polling = false;
        ++pollGeneration;                 // 所有在途回调立即失效
        handler.removeCallbacksAndMessages(null);
        skelHandler.removeCallbacksAndMessages(null);
        VodSyncApi.cancelTag(POLL_TAG);   // 取消本页所有在途轮询请求
        transientToken = null;            // 进程内明文副本随页面销毁
        skelHandler.removeCallbacks(skelShowTask);
        Skeleton.hide(statsSkeleton, animStats);
        Skeleton.hide(srcSkeleton, animSrc);
        Skeleton.hide(logSkeleton, animLog);
        super.onDestroy();
    }
}
