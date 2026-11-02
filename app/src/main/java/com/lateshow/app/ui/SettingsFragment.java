package com.lateshow.app.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lateshow.app.MainActivity;
import com.lateshow.app.R;
import com.lateshow.app.adapter.LogAdapter;
import com.lateshow.app.adapter.SyncSourceAdapter;
import com.lateshow.app.api.VodSyncApi;
import com.lateshow.app.model.Models;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.ui.HomeFragment;
import com.lateshow.app.util.TimeUtil;
import com.lateshow.app.util.UpdateChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 设置页：播放源 + 同步控制台 + 主题 */
public class SettingsFragment extends Fragment {

    private EditText inputBaseUrl, inputToken;
    private TextView srcStatus, btnSave, btnTest, pauseWarn, autoCount, selCount, tvVersion, btnCheckUpdate, tvCacheInfo;
    private LinearLayout statsGrid;
    private LinearLayout pauseRows;
    private CheckBox chkAll;
    private RecyclerView srcList, logList;
    private SyncSourceAdapter srcAdapter;
    private LogAdapter logAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling = false;
    private Models.PauseState pauseState = new Models.PauseState();

    /** 手风琴面板：同一时间只展开一个 */
    private static final int[] PANEL_HEADERS = {
            R.id.hdrSource, R.id.hdrPlay, R.id.hdrCache, R.id.hdrSync, R.id.hdrApp};
    private static final int[] PANEL_BODIES = {
            R.id.panelSource, R.id.panelPlay, R.id.panelCache, R.id.panelSync, R.id.panelApp};
    private static final int[] PANEL_ARROWS = {
            R.id.arSource, R.id.arPlay, R.id.arCache, R.id.arSync, R.id.arApp};

    /** 初始化手风琴：点头部切换，展开一个自动收起其他（默认展开第一个） */
    private void setupAccordion(View root) {
        for (int i = 0; i < PANEL_HEADERS.length; i++) {
            final int idx = i;
            root.findViewById(PANEL_HEADERS[i]).setOnClickListener(v -> togglePanel(root, idx, true));
        }
        // 默认展开第一个面板
        applyPanels(root, 0);
    }

    private void togglePanel(View root, int idx, boolean user) {
        View body = root.findViewById(PANEL_BODIES[idx]);
        boolean wantOpen = body.getVisibility() != View.VISIBLE;
        applyPanels(root, wantOpen ? idx : -1);
    }

    /** 只展开 target（-1 = 全部收起），其余收起，箭头旋转同步 */
    private void applyPanels(View root, int target) {
        for (int i = 0; i < PANEL_BODIES.length; i++) {
            View body = root.findViewById(PANEL_BODIES[i]);
            View arrow = root.findViewById(PANEL_ARROWS[i]);
            boolean open = i == target;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(open ? 180f : 0f).setDuration(180).start();
        }
    }

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            refreshConsole(false);
            handler.postDelayed(this, 5000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_settings, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        setupAccordion(v);

        inputBaseUrl = v.findViewById(R.id.inputBaseUrl);
        inputToken = v.findViewById(R.id.inputToken);
        srcStatus = v.findViewById(R.id.srcStatus);
        btnSave = v.findViewById(R.id.btnSave);
        btnTest = v.findViewById(R.id.btnTest);
        statsGrid = v.findViewById(R.id.statsGrid);
        pauseWarn = v.findViewById(R.id.pauseWarn);
        pauseRows = v.findViewById(R.id.pauseRows);
        autoCount = v.findViewById(R.id.autoCount);
        selCount = v.findViewById(R.id.selCount);
        chkAll = v.findViewById(R.id.chkAll);
        srcList = v.findViewById(R.id.srcList);
        logList = v.findViewById(R.id.logList);

        inputBaseUrl.setText(PrefStore.get().baseUrl());
        inputToken.setText(PrefStore.get().apiToken());

        // 播放偏好：跳过片头/片尾（输入即保存，0 = 不跳过）
        EditText inputSkipIntro = v.findViewById(R.id.inputSkipIntro);
        EditText inputSkipEnding = v.findViewById(R.id.inputSkipEnding);
        inputSkipIntro.setText(String.valueOf(PrefStore.get().skipIntroSec()));
        inputSkipEnding.setText(String.valueOf(PrefStore.get().skipEndingSec()));
        inputSkipIntro.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                PrefStore.get().setSkipIntroSec(parseIntSafe(s.toString()));
            }
        });
        inputSkipEnding.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                PrefStore.get().setSkipEndingSec(parseIntSafe(s.toString()));
            }
        });

        // 离线与缓存：展示占用并提供清理入口（离线播放数据不随清缓存删除）
        tvCacheInfo = v.findViewById(R.id.tvCacheInfo);
        v.findViewById(R.id.btnClearCache).setOnClickListener(x -> {
            com.lateshow.app.util.ApiCache.clear();
            refreshCacheInfo();
            toast("首页/分类/源站缓存已清除");
        });
        refreshCacheInfo();

        // 版本与更新
        tvVersion = v.findViewById(R.id.tvVersion);
        btnCheckUpdate = v.findViewById(R.id.btnCheckUpdate);
        tvVersion.setText("v" + UpdateChecker.currentVersion());
        btnCheckUpdate.setOnClickListener(x -> doCheckUpdate());
        v.findViewById(R.id.btnReleasePage).setOnClickListener(x -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE)));
            } catch (Exception ignored) { }
        });

        // 播放源 + API Token：保存 / 测试连接（先探 auth/state，再用受保护接口验证 Token）
        btnSave.setOnClickListener(vv -> verifySource(true));
        btnTest.setOnClickListener(vv -> verifySource(false));

        // 同步操作
        v.findViewById(R.id.btnSyncAll).setOnClickListener(vv -> post("/api/v1/sync-all?mode=auto", "全站同步已受理"));
        v.findViewById(R.id.btnRefresh).setOnClickListener(vv -> post("/api/v1/sources/refresh", "源站列表刷新已受理"));
        v.findViewById(R.id.btnFullSync).setOnClickListener(vv -> post("/api/v1/sync-all?mode=full", "全站全量同步已受理（耗时长）"));
        v.findViewById(R.id.btnRecount).setOnClickListener(vv -> post("/api/v1/maintenance/recount", "条数校准已受理"));

        // 暂停三开关
        buildPauseRows();

        // 源站列表
        srcAdapter = new SyncSourceAdapter(new SyncSourceAdapter.Callbacks() {
            @Override public void onPickChanged() { syncSelectionUi(); }
            @Override public void onAction(String msg) { toast(msg); refreshConsole(false); }
            @Override public Map<String, String> ctx() { return null; }
        });
        srcList.setLayoutManager(new LinearLayoutManager(getContext()));
        srcList.setAdapter(srcAdapter);

        // 批量操作
        chkAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            srcAdapter.setAllPicked(isChecked);
            syncSelectionUi();
        });
        v.findViewById(R.id.btnAllOn).setOnClickListener(vv -> autoSyncBatch("all", true));
        v.findViewById(R.id.btnAllOff).setOnClickListener(vv -> autoSyncBatch("all", false));
        v.findViewById(R.id.btnSelOn).setOnClickListener(vv -> autoSyncBatch(pickedSlugs(), true));
        v.findViewById(R.id.btnSelOff).setOnClickListener(vv -> autoSyncBatch(pickedSlugs(), false));

        // 日志
        logAdapter = new LogAdapter();
        logList.setLayoutManager(new LinearLayoutManager(getContext()));
        logList.setAdapter(logAdapter);

        // 主题切换
        v.findViewById(R.id.btnTheme).setOnClickListener(vv -> {
            int t = (PrefStore.get().theme() + 1) % 3;
            PrefStore.get().setTheme(t);
            String name = t == PrefStore.THEME_DARK ? "主题：深色" : t == PrefStore.THEME_LIGHT ? "主题：浅色" : "主题：跟随系统";
            toast(name);
            if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).applyTheme();
        });

        refreshConsole(true);
        refreshAuthBadge();
    }

    /** 顶部状态条：显示服务端是否开启鉴权、Token 是否已配置 */
    private void refreshAuthBadge() {
        VodSyncApi.get().authState(new VodSyncApi.ApiCb<Models.AuthState>() {
            @Override public void onOk(Models.AuthState s) {
                if (!isAdded() || s == null) return;
                if (!s.enabled) {
                    srcStatus.setTextColor(getColor(R.color.ok));
                    srcStatus.setText("● 服务端未开启鉴权 · 可直连");
                } else if (PrefStore.get().apiToken().isEmpty()) {
                    srcStatus.setTextColor(getColor(R.color.warn));
                    srcStatus.setText("● 已开启鉴权 · 尚未填写 API Token");
                } else {
                    srcStatus.setTextColor(getColor(R.color.t2));
                    srcStatus.setText("● 已开启鉴权 · Token 已配置"
                            + (s.tokenHint != null && !s.tokenHint.isEmpty() ? "（" + s.tokenHint + "）" : ""));
                }
            }
            @Override public void onErr(String m) {
                if (!isAdded()) return;
                srcStatus.setTextColor(getColor(R.color.bad));
                srcStatus.setText("● 未连接 · " + m);
            }
        });
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
        if (slugs == null || slugs.isEmpty()) {
            toast("未选择源站");
            return;
        }
        Map<String, String> p = HomeFragment.map("enabled", String.valueOf(enabled), "slugs", slugs);
        VodSyncApi.get().post("/api/v1/sources/auto-sync", p, Models.MsgResp.class,
            new VodSyncApi.ApiCb<Models.MsgResp>() {
                @Override public void onOk(Models.MsgResp r) {
                    toast(r.msg != null ? r.msg : "已更新");
                    refreshConsole(false);
                }
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
        selCount.setTextColor(getColor(picked == 0 ? R.color.t3 : R.color.acc));
        if (total > 0) {
            chkAll.setOnCheckedChangeListener(null);
            chkAll.setChecked(picked == total);
            chkAll.setOnCheckedChangeListener((bv, c) -> {
                srcAdapter.setAllPicked(c);
                syncSelectionUi();
            });
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
                String path = on ? "/api/v1/pause" : "/api/v1/resume";
                VodSyncApi.get().post(path, HomeFragment.map("scope", scope), Models.MsgResp.class,
                    new VodSyncApi.ApiCb<Models.MsgResp>() {
                        @Override public void onOk(Models.MsgResp r) {
                            toast(r.msg != null ? r.msg : "已更新");
                            refreshConsole(false);
                        }
                        @Override public void onErr(String m) {
                            toast(m);
                            sw.setChecked(!on);
                        }
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

    /** 保存/测试：先探 /api/v1/auth/state 判断是否开启鉴权，再用 /api/v1/stats 验证 Token */
    private void verifySource(final boolean save) {
        if (!isAdded()) return;
        final String url = inputBaseUrl.getText().toString().trim();
        final String token = inputToken.getText().toString().trim();
        if (url.isEmpty()) {
            toast("请输入后端地址");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("地址需以 http:// 或 https:// 开头");
            return;
        }

        PrefStore.get().setBaseUrl(url);
        // 拦截器读取的是已保存的 Token：验证前必须先让输入值生效，验证失败再回滚
        final String oldToken = PrefStore.get().apiToken();
        if (!token.equals(oldToken)) PrefStore.get().setApiToken(token);

        btnSave.setText("检测中…");
        srcStatus.setTextColor(getColor(R.color.t2));
        srcStatus.setText("● 正在探测服务器…");
        final long t0 = System.currentTimeMillis();

        VodSyncApi.get().authState(new VodSyncApi.ApiCb<Models.AuthState>() {
            @Override public void onOk(final Models.AuthState s) {
                if (!isAdded()) return;
                if (s == null) {
                    failStatus("● 响应异常，无法判断鉴权状态");
                    return;
                }
                if (!s.enabled) {
                    // 服务端未开启访问控制：带不带 Token 都能用
                    okStatus("● 该服务器未开启鉴权 · 可不填 Token · " + (System.currentTimeMillis() - t0) + "ms");
                    return;
                }
                if (token.isEmpty()) {
                    srcStatus.setTextColor(getColor(R.color.warn));
                    srcStatus.setText("● 该服务器已开启鉴权 · 请填写 API Token");
                    resetSave("保存");
                    toast("服务端已开启访问控制，需要 API Token");
                    return;
                }
                // 用受保护接口验证 Token
                VodSyncApi.get().get("/api/v1/stats", null, Models.StatsResp.class,
                        new VodSyncApi.ApiCb<Models.StatsResp>() {
                            @Override public void onOk(Models.StatsResp r) {
                                if (!isAdded()) return;
                                okStatus("● 已授权 · Token 有效 · 延迟 " + (System.currentTimeMillis() - t0) + "ms");
                                refreshConsole(true);
                            }
                            @Override public void onErr(String m) {
                                if (!isAdded()) return;
                                if (VodSyncApi.isTokenError(m)) {
                                    // 验证不通过的 Token 不保留，回滚为之前的值
                                    if (!token.equals(oldToken)) PrefStore.get().setApiToken(oldToken);
                                    String detail = VodSyncApi.tokenErrorDetail(m);
                                    if (detail.length() > 70) detail = detail.substring(0, 70) + "…";
                                    failStatus("● Token 无效 · " + detail);
                                    toast("API Token 无效");
                                } else {
                                    failStatus("● 连接失败 · " + m);
                                }
                            }
                        });
            }
            @Override public void onErr(String m) {
                if (!isAdded()) return;
                failStatus("● 无法连接 · " + m);
                toast("地址不可用，请检查地址与端口");
            }
        });
    }

    private void okStatus(String text) {
        if (!isAdded()) return;
        srcStatus.setText(text);
        srcStatus.setTextColor(getColor(R.color.ok));
        btnSave.setText("已保存");
        btnSave.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_ok));
        toast("配置已保存");
        postResetSave();
    }

    private void failStatus(String text) {
        if (!isAdded()) return;
        srcStatus.setText(text);
        srcStatus.setTextColor(getColor(R.color.bad));
        resetSave("保存");
    }

    private void resetSave(String label) {
        btnSave.setText(label);
        if (isAdded()) {
            btnSave.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_main));
        }
    }

    /** 检查 GitHub Release 是否有新版本，有则提示更新 */
    private void doCheckUpdate() {
        if (!isAdded() || getContext() == null) return;
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("检查中…");
        UpdateChecker.check((info, error) -> {
            if (!isAdded() || getContext() == null) return;
            btnCheckUpdate.setEnabled(true);
            btnCheckUpdate.setText("检查更新");
            if (error != null) {
                Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
                return;
            }
            if (info == null) {
                new AlertDialog.Builder(getContext())
                        .setTitle("已是最新版本")
                        .setMessage("当前版本 v" + UpdateChecker.currentVersion())
                        .setPositiveButton("好", null)
                        .show();
                return;
            }
            String notes = info.notes == null ? "" : info.notes.trim();
            if (notes.length() > 500) notes = notes.substring(0, 500) + "…";
            new AlertDialog.Builder(getContext())
                    .setTitle("发现新版本 v" + info.version)
                    .setMessage("当前 v" + UpdateChecker.currentVersion() + "\n\n" + notes)
                    .setPositiveButton("立即更新", (d, w) -> {
                        if (getActivity() != null) UpdateChecker.downloadAndInstall(getActivity(), info);
                    })
                    .setNegativeButton("稍后", null)
                    .show();
        });
    }

    private void updatePauseWarn() {
        boolean g = pauseState.global, f = pauseState.full, i = pauseState.incremental;
        if (!g && !f && !i) {
            pauseWarn.setVisibility(View.GONE);
            return;
        }
        String text;
        if (g) text = "全局暂停中：全量、增量与定时任务均已停止";
        else if (f && i) text = "全量与增量均已暂停：继续后将从断点自动续传";
        else if (f) text = "全量同步已暂停：检查点已保存，继续后自动续传";
        else text = "增量同步已暂停：水位线不动，继续后自动重跑";
        pauseWarn.setVisibility(View.VISIBLE);
        pauseWarn.setText("● " + text);
    }

    private void postResetSave() {
        handler.postDelayed(() -> {
            if (btnSave != null) {
                btnSave.setText("保存");
                btnSave.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_main));
            }
        }, 1800);
    }

    /** 拉取 stats / sources / pause / logs */
    private void refreshConsole(boolean alsoStats) {
        if (!isAdded()) return;
        VodSyncApi.get().get("/api/v1/pause", null, Models.PauseResp.class,
            new VodSyncApi.ApiCb<Models.PauseResp>() {
                @Override public void onOk(Models.PauseResp r) {
                    if (!isAdded()) return;
                    Models.PauseState p = r.state != null ? r.state : new Models.PauseState();
                    p.global = r.global;
                    p.full = r.full;
                    p.incremental = r.incremental;
                    pauseState = p;
                    buildPauseRows();
                    updatePauseWarn();
                }
                @Override public void onErr(String m) { }
            });

        VodSyncApi.get().get("/api/v1/sources", null, Models.SyncSourcesResp.class,
            new VodSyncApi.ApiCb<Models.SyncSourcesResp>() {
                @Override public void onOk(Models.SyncSourcesResp r) {
                    if (!isAdded()) return;
                    srcAdapter.setItems(r.list != null ? r.list : new ArrayList<>());
                    syncSelectionUi();
                }
                @Override public void onErr(String m) { }
            });

        VodSyncApi.get().get("/api/v1/logs", HomeFragment.map("limit", "10"), Models.LogsResp.class,
            new VodSyncApi.ApiCb<Models.LogsResp>() {
                @Override public void onOk(Models.LogsResp r) {
                    if (!isAdded()) return;
                    logAdapter.setItems(r.list != null ? r.list : new ArrayList<>());
                }
                @Override public void onErr(String m) { }
            });

        VodSyncApi.get().get("/api/v1/stats", null, Models.StatsResp.class,
            new VodSyncApi.ApiCb<Models.StatsResp>() {
                @Override public void onOk(Models.StatsResp r) {
                    if (!isAdded()) return;
                    renderStats(r);
                }
                @Override public void onErr(String m) { }
            });
    }

    private void renderStats(Models.StatsResp s) {
        statsGrid.removeAllViews();
        addStatRow(new String[][]{
                {TimeUtil.thousands(s.sourcesTotal), "源站总数 · 已启用 " + s.sourcesEnabled},
                {TimeUtil.thousands(s.videosTotal), "已入库视频"}});
        addStatRow(new String[][]{
                {String.valueOf(s.sourcesSyncing), "正在同步"},
                {TimeUtil.thousands(s.todayInserted), "今日新增 " + s.todayInserted}});
    }

    private void addStatRow(String[][] cells) {
        android.content.Context ctx = requireContext();
        int dp8 = (int) (8 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(ctx);
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
        final String msg = okMsg;
        VodSyncApi.get().post(path, params, Models.MsgResp.class,
            new VodSyncApi.ApiCb<Models.MsgResp>() {
                @Override public void onOk(Models.MsgResp r) {
                    toast(r.msg != null ? r.msg : msg);
                    refreshConsole(false);
                }
                @Override public void onErr(String m) { toast(m); }
            });
    }

    private void toast(String s) {
        if (isAdded()) Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }

    /** 跳过秒数输入框解析：空/非法按 0（不跳过），上限 600 由 PrefStore 兜底 */
    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 刷新缓存占用展示（接口缓存 + 离线播放数据） */
    private void refreshCacheInfo() {
        if (tvCacheInfo == null) return;
        long apiBytes = com.lateshow.app.util.ApiCache.sizeBytes();
        long mediaBytes = com.lateshow.app.util.OfflineMedia.sizeBytes();
        int mediaCount = com.lateshow.app.util.OfflineMedia.count();
        tvCacheInfo.setText("接口缓存（首页/分类/源站，10 分钟~6 小时自动过期）：" + fmtSize(apiBytes)
                + "\n离线播放数据（收藏/历史，最多 80 部 / 40MB）：" + mediaCount + " 部 · " + fmtSize(mediaBytes));
    }

    private static String fmtSize(long b) {
        if (b <= 0) return "0 KB";
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1024f / 1024f);
    }

    private int getColor(int id) {
        return ContextCompat.getColor(requireContext(), id);
    }

    @Override
    public void onResume() {
        super.onResume();
        polling = true;
        handler.postDelayed(pollTask, 5000);
    }

    @Override
    public void onPause() {
        super.onPause();
        polling = false;
        handler.removeCallbacks(pollTask);
    }
}
