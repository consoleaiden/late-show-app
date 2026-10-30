package com.lateshow.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 设置页：播放源 + 同步控制台 + 主题 */
public class SettingsFragment extends Fragment {

    private EditText inputBaseUrl;
    private TextView srcStatus, btnSave, pauseWarn, autoCount, selCount;
    private GridLayout statsGrid;
    private LinearLayout pauseRows;
    private CheckBox chkAll;
    private RecyclerView srcList, logList;
    private SyncSourceAdapter srcAdapter;
    private LogAdapter logAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling = false;
    private Models.PauseState pauseState = new Models.PauseState();

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
        inputBaseUrl = v.findViewById(R.id.inputBaseUrl);
        srcStatus = v.findViewById(R.id.srcStatus);
        btnSave = v.findViewById(R.id.btnSave);
        statsGrid = v.findViewById(R.id.statsGrid);
        pauseWarn = v.findViewById(R.id.pauseWarn);
        pauseRows = v.findViewById(R.id.pauseRows);
        autoCount = v.findViewById(R.id.autoCount);
        selCount = v.findViewById(R.id.selCount);
        chkAll = v.findViewById(R.id.chkAll);
        srcList = v.findViewById(R.id.srcList);
        logList = v.findViewById(R.id.logList);

        inputBaseUrl.setText(PrefStore.get().baseUrl());

        // 播放源保存
        btnSave.setOnClickListener(vv -> {
            String url = inputBaseUrl.getText().toString().trim();
            if (url.isEmpty()) {
                toast("请输入后端地址");
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                toast("地址需以 http:// 或 https:// 开头");
                return;
            }
            PrefStore.get().setBaseUrl(url);
            btnSave.setText("检测中…");
            VodSyncApi.get().healthz(new VodSyncApi.ApiCb<Long>() {
                @Override public void onOk(Long ms) {
                    if (!isAdded()) return;
                    PrefStore.get().setBaseUrl(url);
                    srcStatus.setText("● 当前生效 · 延迟 " + ms + "ms");
                    srcStatus.setTextColor(getColor(R.color.ok));
                    btnSave.setText("已保存");
                    btnSave.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_ok));
                    toast("播放源已保存");
                    postResetSave();
                    refreshConsole(true);
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    srcStatus.setText("● 连接失败 · " + m);
                    srcStatus.setTextColor(getColor(R.color.bad));
                    btnSave.setText("保存");
                    toast("地址不可用：" + m);
                }
            });
        });

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
        addStat(TimeUtil.thousands(s.sourcesTotal), "源站总数 " + s.sourcesTotal + " · 已启用 " + s.sourcesEnabled);
        addStat(TimeUtil.thousands(s.videosTotal), "已入库视频");
        addStat(String.valueOf(s.sourcesSyncing), "正在同步");
        addStat(TimeUtil.thousands(s.todayInserted), "今日新增 " + s.todayInserted);
    }

    private void addStat(String value, String label) {
        View card = getLayoutInflater().inflate(R.layout.item_stat, statsGrid, false);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED));
        lp.width = 0;
        lp.setMargins((int) (4 * getResources().getDisplayMetrics().density), 0, 0,
                (int) (8 * getResources().getDisplayMetrics().density));
        card.setLayoutParams(lp);
        ((TextView) card.findViewById(R.id.statValue)).setText(value);
        ((TextView) card.findViewById(R.id.statLabel)).setText(label);
        statsGrid.addView(card);
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
