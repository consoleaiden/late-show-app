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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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
            R.id.hdrSources, R.id.hdrLogs, R.id.hdrAuth, R.id.hdrStars, R.id.hdrMaint, R.id.hdrLine};
    private static final int[] BODIES = {
            R.id.panelOverview, R.id.panelActions, R.id.panelPause, R.id.panelSearch, R.id.panelTables,
            R.id.panelSources, R.id.panelLogs, R.id.panelAuth, R.id.panelStars, R.id.panelMaint, R.id.panelLine};
    private static final int[] ARROWS = {
            R.id.arOverview, R.id.arActions, R.id.arPause, R.id.arSearch, R.id.arTables,
            R.id.arSources, R.id.arLogs, R.id.arAuth, R.id.arStars, R.id.arMaint, R.id.arLine};
    private static final String[] POLICY_VALUES = {"inherit", "filter", "off", "whitelist"};

    private TextView tvConsoleStatus, autoCount, selCount, tvRuntime, pauseWarn, searchInfo,
            tvTablePage, tvAuthState, tvTokenValue, tvStarStatus, tvMaintOutput, tvLineHint;
    private LinearLayout statsGrid, pauseRows, statsSkeleton, srcSkeleton, logSkeleton,
            searchResults, tableResults, starList;
    private RecyclerView srcList, logList;
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
    private Models.PauseState pauseState = new Models.PauseState();

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
            if (!polling) return;
            refreshAll(false);
            handler.postDelayed(this, 5000);
        }
    };

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
        else if (idx == 7) loadAuthSection();             // 访问控制与 Token
        else if (idx == 8) loadStarTerms();               // 明星词库
        else if (idx == 10) refreshLineSourceSpinner();   // 播放线路策略
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
        findViewById(R.id.btnConsoleRefresh).setOnClickListener(v -> {
            refreshAll(true);
            toast("已刷新");
        });

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
        srcList.setLayoutManager(new LinearLayoutManager(this));
        srcList.setAdapter(srcAdapter);

        logAdapter = new LogAdapter();
        logList.setLayoutManager(new LinearLayoutManager(this));
        logList.setAdapter(logAdapter);

        chkAll.setOnCheckedChangeListener((bv, on) -> {
            srcAdapter.setAllPicked(on);
            syncSelectionUi();
        });
    }

    private void bindActions() {
        // ② 同步操作
        findViewById(R.id.btnSyncAll).setOnClickListener(v -> post("/api/v1/sync-all?mode=auto", "全站增量同步已受理"));
        findViewById(R.id.btnRefresh).setOnClickListener(v -> post("/api/v1/sources/refresh", "源站列表刷新已受理"));
        findViewById(R.id.btnFullSync).setOnClickListener(v -> post("/api/v1/sync-all?mode=full", "全站全量同步已受理（耗时长）"));
        findViewById(R.id.btnRecount).setOnClickListener(v -> post("/api/v1/maintenance/recount", "条数校准已受理"));
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
                postJson("/api/v1/star-terms/recompute", "{}", "相关推荐重算已受理", this::loadStarTerms));

        // ⑩ 诊断与维护
        findViewById(R.id.btnRelatedStatus).setOnClickListener(v -> showJson("/api/v1/related/status", null));
        findViewById(R.id.btnStarTagsStatus).setOnClickListener(v -> showJson("/api/v1/star/tags/status", null));
        findViewById(R.id.btnUntrackedStars).setOnClickListener(v -> showJson("/api/v1/star/tags/untracked", null));
        findViewById(R.id.btnRelatedRecompute).setOnClickListener(v ->
                post("/api/v1/maintenance/related-recompute", "相关推荐重算已受理"));
        findViewById(R.id.btnStarTagsBackfill).setOnClickListener(v ->
                post("/api/v1/maintenance/star-tags", "明星标签回填已受理"));

        // ⑪ 播放线路策略
        findViewById(R.id.btnLineApply).setOnClickListener(v -> applyLinePolicy());
        findViewById(R.id.btnSourcePause).setOnClickListener(v -> toggleSourcePause());
    }

    /** 首次进入预取一些不常变的数据，避免展开板块时空白 */
    private void loadStaticSections() {
        loadTables();
        loadAuthSection();
    }

    // ==================== ① 运行概览 / ③ 暂停 / ⑥ 源站 / ⑦ 日志 ====================

    private void refreshAll(boolean showSkeleton) {
        if (showSkeleton && !skelShown) {
            skelHandler.removeCallbacks(skelShowTask);
            skelHandler.postDelayed(skelShowTask, 220);
        }
        VodSyncApi.get().get("/api/v1/pause", null, Models.PauseResp.class,
                new VodSyncApi.ApiCb<Models.PauseResp>() {
            @Override public void onOk(Models.PauseResp r) {
                Models.PauseState p = r.state != null ? r.state : new Models.PauseState();
                p.global = r.global; p.full = r.full; p.incremental = r.incremental;
                pauseState = p;
                buildPauseRows();
                updatePauseWarn();
            }
            @Override public void onErr(String m) { }
        });

        VodSyncApi.get().get("/api/v1/sources", null, Models.SyncSourcesResp.class,
                new VodSyncApi.ApiCb<Models.SyncSourcesResp>() {
            @Override public void onOk(Models.SyncSourcesResp r) {
                Skeleton.hide(srcSkeleton, animSrc);
                srcAdapter.setItems(r.list != null ? r.list : new ArrayList<>());
                syncSelectionUi();
                refreshLineSourceSpinner();
                tvConsoleStatus.setText("● 已连接 · 源站 " + srcAdapter.getItemCount() + " 个 · 5 秒自动刷新");
                tvConsoleStatus.setTextColor(color(R.color.ok));
            }
            @Override public void onErr(String m) {
                Skeleton.hide(srcSkeleton, animSrc);
                tvConsoleStatus.setText("● 连接失败 · " + m);
                tvConsoleStatus.setTextColor(color(R.color.bad));
            }
        });

        VodSyncApi.get().get("/api/v1/logs", HomeFragment.map("limit", "20"), Models.LogsResp.class,
                new VodSyncApi.ApiCb<Models.LogsResp>() {
            @Override public void onOk(Models.LogsResp r) {
                Skeleton.hide(logSkeleton, animLog);
                logAdapter.setItems(r.list != null ? r.list : new ArrayList<>());
            }
            @Override public void onErr(String m) { Skeleton.hide(logSkeleton, animLog); }
        });

        VodSyncApi.get().get("/api/v1/stats", null, Models.StatsResp.class,
                new VodSyncApi.ApiCb<Models.StatsResp>() {
            @Override public void onOk(Models.StatsResp r) {
                Skeleton.hide(statsSkeleton, animStats);
                renderStats(r);
            }
            @Override public void onErr(String m) { Skeleton.hide(statsSkeleton, animStats); }
        });
    }

    private void renderStats(Models.StatsResp s) {
        autoCount.setText("自动同步 " + s.sourcesEnabled + "/" + s.sourcesTotal);
        statsGrid.removeAllViews();
        addStatRow(new String[][]{
                {TimeUtil.thousands(s.sourcesTotal), "源站总数 · 已启用 " + s.sourcesEnabled},
                {TimeUtil.thousands(s.videosTotal), "已入库视频"}});
        addStatRow(new String[][]{
                {String.valueOf(s.sourcesSyncing), "正在同步"},
                {TimeUtil.thousands(s.todayInserted), "今日新增 / 更新 " + TimeUtil.thousands(s.todayUpdated)}});
        if (s.sync_load != null) {
            addStatRow(new String[][]{
                    {s.sync_load.running + "/" + s.sync_load.limit,
                            "同步负载" + (s.sync_load.queued > 0 ? " · 排队 " + s.sync_load.queued : "")},
                    {String.valueOf(s.sourcesUnfinished), "有断点待续传"}});
        }
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

    private void addStatRow(String[][] cells) {
        int dp8 = (int) (8 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp8;
        row.setLayoutParams(rowLp);
        for (int i = 0; i < cells.length; i++) {
            View card = getLayoutInflater().inflate(R.layout.item_stat, row, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp8;
            card.setLayoutParams(lp);
            ((TextView) card.findViewById(R.id.statValue)).setText(cells[i][0]);
            ((TextView) card.findViewById(R.id.statLabel)).setText(cells[i][1]);
            row.addView(card);
        }
        statsGrid.addView(row);
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
                String path = on ? "/api/v1/pause" : "/api/v1/resume";
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
        VodSyncApi.get().post("/api/v1/sources/auto-sync",
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
            chkAll.setOnCheckedChangeListener((bv, c) -> { srcAdapter.setAllPicked(c); syncSelectionUi(); });
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
                        VodSyncApi.get().postJson("/api/v1/sources/probe",
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
            VodSyncApi.get().postJson("/api/v1/sources/custom", body.toString(),
                    Models.CustomSourceResp.class, new VodSyncApi.ApiCb<Models.CustomSourceResp>() {
                @Override public void onOk(Models.CustomSourceResp r) {
                    dialog.dismiss();
                    toast(r.msg != null ? r.msg : "已添加");
                    refreshAll(false);
                }
                @Override public void onErr(String m) {
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
            VodSyncApi.get().patchJson("/api/v1/sources/" + s.slug + "/custom", body.toString(),
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
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除自定义源站")
                .setMessage("确认删除「" + s.name + "」（" + s.slug + "）？\n"
                        + "该站已入库的 " + TimeUtil.thousands(s.totalVideos) + " 条数据将一并删除，且不可恢复。")
                .setPositiveButton("删除", (d, w) -> VodSyncApi.get().delete(
                        "/api/v1/sources/" + s.slug + "/custom",
                        HomeFragment.map("confirm", s.slug, "drop_table", "true"),
                        Models.MsgResp.class, new VodSyncApi.ApiCb<Models.MsgResp>() {
                    @Override public void onOk(Models.MsgResp r) { toast(r.msg != null ? r.msg : "已删除"); refreshAll(false); }
                    @Override public void onErr(String m) { toast(m); }
                }))
                .setNegativeButton("取消", null).show();
    }

    private void showProxyStatusDialog() {
        VodSyncApi.get().get("/api/v1/stats", null, Models.StatsResp.class,
                new VodSyncApi.ApiCb<Models.StatsResp>() {
            @Override public void onOk(Models.StatsResp s) {
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
        final String kw = etSearch.getText().toString().trim();
        if (kw.isEmpty()) { toast("请输入关键词"); return; }
        hideKeyboard();
        searchInfo.setText("搜索中…");
        searchResults.removeAllViews();
        VodSyncApi.get().get("/api/v1/search",
                HomeFragment.map("wd", kw, "page", "1", "page_size", "20"),
                JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) {
                JsonArray arr = arrayOf(r, "list");
                int total = intOf(r, "total");
                int ms = intOf(r, "elapsed_ms");
                searchInfo.setText("找到 " + total + " 条 · 耗时 " + ms + "ms · 已搜 "
                        + intOf(r, "sources_searched") + " 个源站");
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
        VodSyncApi.get().get("/api/v1/admin/tables", null, JsonObject.class,
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
        String path = "/api/v1/admin/tables/" + table + "/rows";
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

    private void loadAuthSection() {
        VodSyncApi.get().get("/api/v1/auth/state", null, Models.AuthState.class,
                new VodSyncApi.ApiCb<Models.AuthState>() {
            @Override public void onOk(Models.AuthState s) {
                tvAuthState.setText("● " + (s.enabled ? "已启用授权访问" : "未启用授权访问")
                        + (s.loggedIn ? " · 已登录" : "")
                        + (s.hasToken ? " · 已生成 Token" : ""));
                swAuthEnable.setOnCheckedChangeListener(null);
                swAuthEnable.setChecked(s.enabled);
                swAuthEnable.setOnCheckedChangeListener((bv, on) -> setAuthEnabled(on));
            }
            @Override public void onErr(String m) { tvAuthState.setText("● 状态读取失败：" + m); }
        });
        VodSyncApi.get().get("/api/v1/auth/token", null, JsonObject.class,
                new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) {
                tvTokenValue.setText(str(r, "token").isEmpty() ? "（未生成）" : str(r, "token"));
            }
            @Override public void onErr(String m) { tvTokenValue.setText("（读取失败：" + m + "）"); }
        });
    }

    private void setAuthEnabled(boolean on) {
        try {
            VodSyncApi.get().postJson("/api/v1/auth/settings",
                    new org.json.JSONObject().put("enabled", on).toString(),
                    JsonObject.class, new VodSyncApi.ApiCb<JsonObject>() {
                @Override public void onOk(JsonObject r) {
                    toast(on ? "已启用授权访问（Token 已自动更换）" : "已关闭授权访问");
                    if (r != null && r.has("token") && !r.get("token").isJsonNull()) {
                        tvTokenValue.setText(r.get("token").getAsString());
                    }
                    loadAuthSection();
                }
                @Override public void onErr(String m) { toast(m); loadAuthSection(); }
            });
        } catch (Exception e) { toast("请求构造失败：" + e.getMessage()); }
    }

    private void copyToken() {
        String t = tvTokenValue.getText().toString().trim();
        if (t.isEmpty() || t.startsWith("（")) { toast("还没有 Token"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("vodsync-token", t));
        toast("Token 已复制");
    }

    private void rotateToken() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("重新生成 API Token")
                .setMessage("旧 Token 立即失效，已配置的客户端（含本 App）需要同步更新。继续？")
                .setPositiveButton("重新生成", (d, w) ->
                        VodSyncApi.get().postJson("/api/v1/auth/token", "{}", JsonObject.class,
                                new VodSyncApi.ApiCb<JsonObject>() {
                            @Override public void onOk(JsonObject r) {
                                tvTokenValue.setText(str(r, "token"));
                                toast("新 Token 已生成，请同步更新 App 里的 API Token");
                            }
                            @Override public void onErr(String m) { toast(m); }
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
            VodSyncApi.get().postJson("/api/v1/auth/password",
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

    // ==================== ⑨ 明星词库 ====================

    private void loadStarTerms() {
        VodSyncApi.get().get("/api/v1/star-terms", null, JsonObject.class,
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
        row.addView(smallBtn("删除", v -> new MaterialAlertDialogBuilder(this)
                .setTitle("删除词条")
                .setMessage("确认删除「" + str(o, "term") + "」？")
                .setPositiveButton("删除", (d, w) -> VodSyncApi.get().delete(
                        "/api/v1/star-terms/" + id, null, JsonObject.class,
                        new VodSyncApi.ApiCb<JsonObject>() {
                    @Override public void onOk(JsonObject r) { toast("已删除"); loadStarTerms(); }
                    @Override public void onErr(String m) { toast(m); }
                }))
                .setNegativeButton("取消", null).show()));
        box.addView(row);
    }

    private void patchTerm(int id, String body) {
        VodSyncApi.get().patchJson("/api/v1/star-terms/" + id, body, JsonObject.class,
                new VodSyncApi.ApiCb<JsonObject>() {
            @Override public void onOk(JsonObject r) { toast("已更新"); loadStarTerms(); }
            @Override public void onErr(String m) { toast(m); }
        });
    }

    private void editTermAlias(int id, String current) {
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
            VodSyncApi.get().postJson("/api/v1/star-terms",
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
        VodSyncApi.get().post("/api/v1/sources/" + s.slug + "/play-line-policy",
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
                ? "/api/v1/sources/" + s.slug + "/pause"
                : "/api/v1/sources/" + s.slug + "/resume";
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
        polling = true;
        handler.postDelayed(pollTask, 5000);
        refreshAll(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        handler.removeCallbacks(pollTask);
    }

    @Override
    protected void onDestroy() {
        skelHandler.removeCallbacks(skelShowTask);
        Skeleton.hide(statsSkeleton, animStats);
        Skeleton.hide(srcSkeleton, animSrc);
        Skeleton.hide(logSkeleton, animLog);
        super.onDestroy();
    }
}
