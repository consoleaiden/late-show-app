package com.lateshow.app.ui;

import androidx.appcompat.app.AlertDialog;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private TextView segModeSync, segModeStatic, tvModeState, tvRuntime;
    private TextView tvSavedCount, tvSavedEmpty;
    private LinearLayout savedList, proxySection, proxyList;
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
            R.id.hdrMode, R.id.hdrSource, R.id.hdrPlay, R.id.hdrCache, R.id.hdrSync, R.id.hdrApp};
    private static final int[] PANEL_BODIES = {
            R.id.panelMode, R.id.panelSource, R.id.panelPlay, R.id.panelCache, R.id.panelSync, R.id.panelApp};
    private static final int[] PANEL_ARROWS = {
            R.id.arMode, R.id.arSource, R.id.arPlay, R.id.arCache, R.id.arSync, R.id.arApp};

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
            // Lottie 箭头：展开/收起时从头播放一次上下轻点
            if (arrow instanceof com.airbnb.lottie.LottieAnimationView) {
                com.airbnb.lottie.LottieAnimationView lav = (com.airbnb.lottie.LottieAnimationView) arrow;
                lav.setSpeed(1f);
                lav.setProgress(0f);
                lav.playAnimation();
            }
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
        com.lateshow.app.util.LottieIcon.bindTree(v);
        setupAccordion(v);

        // 回到顶部浮动按钮：滚动超过半屏出现，图标/交互与首页、收藏页同款
        android.widget.ScrollView scroll = v.findViewById(R.id.scrollSettings);
        com.google.android.material.floatingactionbutton.FloatingActionButton fabTop =
                v.findViewById(R.id.fabTop);
        final com.airbnb.lottie.LottieDrawable fabIcon =
                com.lateshow.app.util.LottieIcon.into(fabTop, "ic_top", false);
        scroll.setOnScrollChangeListener((View.OnScrollChangeListener) (vv, sx, sy, osx, osy) ->
                fabTop.setVisibility(sy > scroll.getHeight() / 2 ? View.VISIBLE : View.GONE));
        fabTop.setOnClickListener(vv -> {
            if (fabIcon != null) {
                fabIcon.setProgress(0f);
                fabIcon.playAnimation();
            }
            scroll.smoothScrollTo(0, 0);
            fabTop.setVisibility(View.GONE);
        });

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
        bindClearButton(inputBaseUrl, v.findViewById(R.id.btnClearBaseUrl));
        bindClearButton(inputToken, v.findViewById(R.id.btnClearToken));

        // 数据源模式：NAS 同步库 / 资源站直连（NAS 关机时兜底）
        segModeSync = v.findViewById(R.id.segModeSync);
        segModeStatic = v.findViewById(R.id.segModeStatic);
        tvModeState = v.findViewById(R.id.tvModeState);
        proxySection = v.findViewById(R.id.proxySection);
        proxyList = v.findViewById(R.id.proxyList);
        segModeSync.setOnClickListener(x -> switchDataSource(com.lateshow.app.api.Catalog.MODE_SYNC));
        segModeStatic.setOnClickListener(x -> switchDataSource(com.lateshow.app.api.Catalog.MODE_STATIC));
        renderModeSwitch();
        renderProxies();
        v.findViewById(R.id.btnAddProxy).setOnClickListener(x -> showProxyDialog(-1));

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

        // 已保存播放源列表
        savedList = v.findViewById(R.id.savedList);
        tvSavedCount = v.findViewById(R.id.tvSavedCount);
        tvSavedEmpty = v.findViewById(R.id.tvSavedEmpty);
        renderSavedSources();

        // 自定义源站 + 代理状态
        tvRuntime = v.findViewById(R.id.tvRuntime);
        v.findViewById(R.id.btnAddCustom).setOnClickListener(vv -> showCustomSourceDialog(null));
        v.findViewById(R.id.btnProxyStatus).setOnClickListener(vv -> showProxyStatusDialog());

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
            @Override public void onEditCustom(Models.SyncSource s) { showCustomSourceDialog(s); }
            @Override public void onDeleteCustom(Models.SyncSource s) { confirmDeleteCustom(s); }
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

        // 主题切换：三态形变图标（明/暗/随系统，与 Web 端 demo 同一套时序与缓动），播完再应用主题重建
        final com.lateshow.app.util.ThemeToggleIcon themeIcon = v.findViewById(R.id.btnTheme);
        themeIcon.setMode(PrefStore.get().theme(), false);
        v.findViewById(R.id.btnTheme).setOnClickListener(vv -> {
            int t = (PrefStore.get().theme() + 1) % 3;
            themeIcon.setMode(t, true);
            vv.postDelayed(() -> {
                if (!isAdded()) return;
                PrefStore.get().setTheme(t);
                String name = t == PrefStore.THEME_DARK ? "主题：深色" : t == PrefStore.THEME_LIGHT ? "主题：浅色" : "主题：跟随系统";
                toast(name);
                if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).applyTheme();
            }, 700);
        });

        refreshConsole(true);
    }

    /** 顶部状态条：显示服务端是否开启鉴权、Token 是否已配置；连接结果同时驱动模块标题图标的绿/灰 */
    private void refreshAuthBadge() {
        VodSyncApi.get().authState(new VodSyncApi.ApiCb<Models.AuthState>() {
            @Override public void onOk(Models.AuthState s) {
                if (!isAdded() || s == null) return;
                setHeaderIconsConnected(true);
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
                setHeaderIconsConnected(false);
                srcStatus.setTextColor(getColor(R.color.bad));
                srcStatus.setText("● 未连接 · " + m);
            }
        });
    }

    /** 模块标题图标连通状态：能连上播放源 → 绿色（与「已授权 · Token 有效」一致），否则灰色 */
    private void setHeaderIconsConnected(boolean ok) {
        View root = getView();
        if (root == null || !isAdded()) return;
        int color = getColor(ok ? R.color.ok : R.color.t3);
        android.widget.ImageView icS = root.findViewById(R.id.icSourceHeader);
        android.widget.ImageView icY = root.findViewById(R.id.icSyncHeader);
        if (icS != null) icS.setColorFilter(color);
        if (icY != null) icY.setColorFilter(color);
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
        selCount.setTextColor(getColor(picked == 0 ? R.color.t3 : R.color.accText));
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

    /** 输入框右侧清空按钮：有内容才显示，点击一键清空并保持输入焦点 */
    private void bindClearButton(final EditText input, final View btn) {
        if (input == null || btn == null) return;
        btn.setVisibility(input.getText().length() > 0 ? View.VISIBLE : View.GONE);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                btn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });
        btn.setOnClickListener(x -> {
            input.setText("");
            input.requestFocus();
        });
    }

    private void okStatus(String text) {
        if (!isAdded()) return;
        setHeaderIconsConnected(true);
        srcStatus.setText(text);
        srcStatus.setTextColor(getColor(R.color.ok));
        btnSave.setText("已保存");
        btnSave.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_ok));
        // 保存成功：地址 + Token 一并登记进播放源列表（按地址去重）
        PrefStore.get().upsertSavedSource(
                inputBaseUrl.getText().toString().trim(),
                inputToken.getText().toString().trim(), null);
        renderSavedSources();
        toast("配置已保存");
        postResetSave();
    }

    private void failStatus(String text) {
        if (!isAdded()) return;
        setHeaderIconsConnected(false);
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
                new MaterialAlertDialogBuilder(getContext())
                        .setTitle("已是最新版本")
                        .setMessage("当前版本 v" + UpdateChecker.currentVersion())
                        .setPositiveButton("好", null)
                        .show();
                return;
            }
            String notes = info.notes == null ? "" : info.notes.trim();
            if (notes.length() > 500) notes = notes.substring(0, 500) + "…";
            new MaterialAlertDialogBuilder(getContext())
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

    /** 拉取 stats / sources / pause / logs（NAS 专属，直连模式跳过） */
    private void refreshConsole(boolean alsoStats) {
        if (!isAdded()) return;
        if (com.lateshow.app.api.Catalogs.isStatic()) return;
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
                {TimeUtil.thousands(s.todayInserted), "今日新增"}});
        // v1.4.1：同步负载 + 待续传（有断点的站数）
        if (s.sync_load != null) {
            addStatRow(new String[][]{
                    {s.sync_load.running + "/" + s.sync_load.limit,
                            "同步负载（并发上限）" + (s.sync_load.queued > 0 ? " · 排队 " + s.sync_load.queued : "")},
                    {String.valueOf(s.sourcesUnfinished), "有断点待续传"}});
        }
        renderRuntime(s);
    }

    /** 运行态明细：代理降级通道（v1.4.0）+ 数据库连接池（v1.4.1） */
    private void renderRuntime(Models.StatsResp s) {
        if (tvRuntime == null || !isAdded()) return;
        StringBuilder sb = new StringBuilder();
        // 一行一个维度：比「代理：已启用 · 直连成功 0 · 代理成功 1694 · …」长句更好扫读
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
        String text = sb.toString();
        if (text.isEmpty()) { tvRuntime.setVisibility(View.GONE); return; }
        tvRuntime.setText(text);
        tvRuntime.setVisibility(View.VISIBLE);
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

    /** 列表拼接（minSdk 24 不用 String.join，其需 API 26） */
    private static String joinList(List<String> list, String sep) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ---------- 已保存播放源（多地址管理） ----------

    /** 渲染已保存播放源列表；当前生效地址标「使用中」 */
    private void renderSavedSources() {
        if (savedList == null || !isAdded()) return;
        List<PrefStore.SavedSource> list = PrefStore.get().savedSources();
        savedList.removeAllViews();
        String active = PrefStore.get().baseUrl();
        tvSavedCount.setText(list.isEmpty() ? "" : list.size() + " 个");
        tvSavedEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        for (final PrefStore.SavedSource it : list) {
            View row = getLayoutInflater().inflate(R.layout.item_saved_source, savedList, false);
            ((TextView) row.findViewById(R.id.sName)).setText(it.name);
            ((TextView) row.findViewById(R.id.sUrl)).setText(it.url);
            boolean isActive = it.url != null && it.url.equals(active);
            row.findViewById(R.id.sActive).setVisibility(isActive ? View.VISIBLE : View.GONE);
            row.findViewById(R.id.btnUse).setOnClickListener(v -> applySavedSource(it));
            row.findViewById(R.id.btnEditSource).setOnClickListener(v -> showSavedSourceEditDialog(it));
            row.findViewById(R.id.btnDelSource).setOnClickListener(v -> confirmDeleteSavedSource(it));
            savedList.addView(row);
        }
    }

    /** 一键切换：写回地址与 Token，地址变化时清接口缓存并通知首页重载，再走保存校验 */
    private void applySavedSource(PrefStore.SavedSource it) {
        String old = PrefStore.get().baseUrl();
        PrefStore.get().setBaseUrl(it.url);
        PrefStore.get().setApiToken(it.token);
        inputBaseUrl.setText(it.url);
        inputToken.setText(it.token);
        if (!old.equals(PrefStore.get().baseUrl())) {
            // 不同后端数据不同：清缓存避免串数据
            com.lateshow.app.util.ApiCache.clear();
            if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).notifyDataSourceChanged();
        }
        verifySource(true);
        renderSavedSources();
    }

    /** 编辑播放源条目（名称 / 地址 / Token），校验地址格式；若是当前生效地址同步更新输入框 */
    private void showSavedSourceEditDialog(final PrefStore.SavedSource it) {
        if (!isAdded()) return;
        View dlg = getLayoutInflater().inflate(R.layout.dialog_custom_source, null);
        EditText inName = dlg.findViewById(R.id.dcsName);
        EditText inUrl = dlg.findViewById(R.id.dcsUrl);
        EditText inToken = dlg.findViewById(R.id.dcsToken);
        EditText inSlug = dlg.findViewById(R.id.dcsSlug);
        EditText inDesc = dlg.findViewById(R.id.dcsDesc);
        dlg.findViewById(R.id.dcsProbe).setVisibility(View.GONE);
        dlg.findViewById(R.id.dcsSlug).setVisibility(View.GONE);
        dlg.findViewById(R.id.dcsDesc).setVisibility(View.GONE);
        inName.setText(it.name);
        inUrl.setText(it.url);
        inToken.setText(it.token);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("编辑播放源")
                .setView(dlg)
                .setPositiveButton("保存", (d, w) -> {
                    String url = inUrl.getText().toString().trim();
                    if (PrefStore.validateProxy(url) != null) { toast("地址需以 http:// 或 https:// 开头"); return; }
                    String oldUrl = it.url;
                    it.name = inName.getText().toString().trim();
                    it.url = url;
                    it.token = inToken.getText().toString().trim();
                    List<PrefStore.SavedSource> list = PrefStore.get().savedSources();
                    list.removeIf(x -> x != it && x.url != null && x.url.equals(it.url)); // 地址改成已有项：覆盖旧项
                    PrefStore.get().saveSavedSourcesList(list);
                    if (oldUrl.equals(PrefStore.get().baseUrl())) {
                        // 编辑的是当前生效地址：输入框与生效值同步
                        PrefStore.get().setBaseUrl(it.url);
                        PrefStore.get().setApiToken(it.token);
                        inputBaseUrl.setText(it.url);
                        inputToken.setText(it.token);
                    }
                    renderSavedSources();
                    toast("已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteSavedSource(final PrefStore.SavedSource it) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除播放源")
                .setMessage("确认删除「" + it.name + "」？\n" + it.url
                        + "\n\n仅从列表移除，不影响当前已生效的配置。")
                .setPositiveButton("删除", (d, w) -> {
                    PrefStore.get().removeSavedSource(it.url);
                    renderSavedSources();
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 直连模式代理访问 API（新增 / 修改 / 删除） ----------

    /** 渲染代理列表；直连模式显示，NAS 模式隐藏 */
    private void renderProxies() {
        if (proxyList == null || !isAdded()) return;
        List<String> list = PrefStore.get().proxies();
        proxyList.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            View row = getLayoutInflater().inflate(R.layout.item_proxy, proxyList, false);
            ((TextView) row.findViewById(R.id.pUrl)).setText(list.get(i));
            row.findViewById(R.id.btnEditProxy).setOnClickListener(v -> showProxyDialog(idx));
            row.findViewById(R.id.btnDelProxy).setOnClickListener(v -> confirmDeleteProxy(idx));
            proxyList.addView(row);
        }
    }

    /** 新增（index=-1）/ 修改代理，校验 http(s) 前缀 */
    private void showProxyDialog(final int index) {
        if (!isAdded()) return;
        List<String> list = PrefStore.get().proxies();
        final boolean editing = index >= 0 && index < list.size();
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextSize(12);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        if (editing) input.setText(list.get(index));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(editing ? "修改代理" : "新增代理")
                .setMessage("请求方式：代理地址 + ?url=目标地址（整段 URL 编码）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String url = input.getText().toString().trim();
                    String err = PrefStore.validateProxy(url);
                    if (err != null) { toast(err); return; }
                    if (editing) list.set(index, url); else list.add(url);
                    PrefStore.get().setProxies(list);
                    renderProxies();
                    toast(editing ? "代理已更新，立即生效" : "代理已添加，立即生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteProxy(final int index) {
        if (!isAdded()) return;
        List<String> list = PrefStore.get().proxies();
        if (index < 0 || index >= list.size()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除代理")
                .setMessage("确认删除代理？\n" + list.get(index)
                        + "\n\n删除后该代理不再参与兜底；列表清空后直连失败将无代理可用。")
                .setPositiveButton("删除", (d, w) -> {
                    list.remove(index);
                    PrefStore.get().setProxies(list);
                    renderProxies();
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 自定义源站（VodSync v1.8.0） ----------

    /**
     * 添加 / 编辑自定义源站对话框。
     * 编辑时 slug 不可改（它决定表名与 API 路径）；改地址会清空该站已入库数据（后端行为）。
     */
    private void showCustomSourceDialog(final Models.SyncSource edit) {
        if (!isAdded()) return;
        final boolean isEdit = edit != null;
        View dlg = getLayoutInflater().inflate(R.layout.dialog_custom_source, null);
        final EditText inName = dlg.findViewById(R.id.dcsName);
        final EditText inUrl = dlg.findViewById(R.id.dcsUrl);
        final EditText inSlug = dlg.findViewById(R.id.dcsSlug);
        final EditText inToken = dlg.findViewById(R.id.dcsToken);
        final EditText inDesc = dlg.findViewById(R.id.dcsDesc);
        final TextView probeView = dlg.findViewById(R.id.dcsProbe);
        final TextView hintView = dlg.findViewById(R.id.dcsHint);
        inToken.setVisibility(View.GONE);
        if (isEdit) {
            inSlug.setVisibility(View.GONE);
            inName.setText(edit.name);
            inUrl.setText(edit.apiUrl);
            inDesc.setText(edit.description);
            hintView.setVisibility(View.VISIBLE);
            hintView.setText("⚠ 修改地址会清空该站已入库的数据并重置同步水位线（后端行为）。");
        } else {
            hintView.setVisibility(View.VISIBLE);
            hintView.setTextColor(getColor(R.color.t3));
            hintView.setText("标识留空时由名称自动推导；保存前会先抓一页验证地址可用性。");
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isEdit ? "编辑自定义源站 · " + edit.slug : "添加自定义源站")
                .setView(dlg)
                .setPositiveButton(isEdit ? "保存" : "添加", null)
                .setNegativeButton("取消", null)
                .setNeutralButton(isEdit ? null : "测试连接", null)
                .create();

        dialog.setOnShowListener(d -> {
            // 测试连接（仅添加时）：POST /api/v1/sources/probe，永远 200，用 ok 字段判结果
            if (!isEdit) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    String url = inUrl.getText().toString().trim();
                    if (url.isEmpty()) { toast("请先填写接口地址"); return; }
                    probeView.setText("● 探测中…");
                    probeView.setTextColor(getColor(R.color.t2));
                    try {
                        org.json.JSONObject body = new org.json.JSONObject().put("api_url", url);
                        VodSyncApi.get().postJson("/api/v1/sources/probe", body.toString(),
                                Models.ProbeResp.class, new VodSyncApi.ApiCb<Models.ProbeResp>() {
                            @Override public void onOk(Models.ProbeResp r) {
                                if (!isAdded()) return;
                                if (r != null && r.ok) {
                                    probeView.setTextColor(getColor(R.color.ok));
                                    probeView.setText("● 可用 · " + (r.total > 0 ? "共 " + r.total + " 条 · " : "") + r.elapsedMs + "ms");
                                } else {
                                    probeView.setTextColor(getColor(R.color.bad));
                                    probeView.setText("● 不可用 · " + (r != null && r.error != null ? r.error : "未知原因"));
                                }
                            }
                            @Override public void onErr(String m) {
                                if (!isAdded()) return;
                                probeView.setTextColor(getColor(R.color.bad));
                                probeView.setText("● 探测失败 · " + m);
                            }
                        });
                    } catch (Exception e) {
                        probeView.setTextColor(getColor(R.color.bad));
                        probeView.setText("● 探测失败 · " + e.getMessage());
                    }
                });
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = inName.getText().toString().trim();
                String url = inUrl.getText().toString().trim();
                String desc = inDesc.getText().toString().trim();
                if (name.isEmpty()) { toast("请填写源站名称"); return; }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast("接口地址需以 http:// 或 https:// 开头");
                    return;
                }
                if (isEdit) {
                    patchCustomSource(edit, name, url, desc, dialog);
                } else {
                    addCustomSource(name, url, inSlug.getText().toString().trim(), desc, dialog, false);
                }
            });
        });
        dialog.show();
    }

    /** 添加自定义源站（默认带探测；探测失败询问是否强制添加） */
    private void addCustomSource(String name, String url, String slug, String desc,
                                 final AlertDialog dialog, final boolean force) {
        try {
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("name", name)
                    .put("api_url", url)
                    .put("description", desc)
                    .put("probe", true)
                    .put("allow_unreachable", force);
            if (!slug.isEmpty()) body.put("slug", slug);
            VodSyncApi.get().postJson("/api/v1/sources/custom", body.toString(),
                    Models.CustomSourceResp.class, new VodSyncApi.ApiCb<Models.CustomSourceResp>() {
                @Override public void onOk(Models.CustomSourceResp r) {
                    if (!isAdded()) return;
                    dialog.dismiss();
                    toast(r.msg != null ? r.msg : "已添加");
                    refreshConsole(false);
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    if (!force && m != null && m.contains("源站不可用")) {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle("探测失败")
                                .setMessage(m + "\n\n确认地址无误仍要强制添加吗？")
                                .setPositiveButton("强制添加", (d2, w2) ->
                                        addCustomSource(name, url, slug, desc, dialog, true))
                                .setNegativeButton("取消", null)
                                .show();
                    } else {
                        toast(m);
                    }
                }
            });
        } catch (Exception e) {
            toast("请求构造失败：" + e.getMessage());
        }
    }

    /** 修改自定义源站（PATCH；后端只更新传出的字段） */
    private void patchCustomSource(Models.SyncSource s, String name, String url, String desc, final AlertDialog dialog) {
        try {
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("name", name)
                    .put("api_url", url)
                    .put("description", desc);
            VodSyncApi.get().patchJson("/api/v1/sources/" + s.slug + "/custom", body.toString(),
                    Models.MsgResp.class, new VodSyncApi.ApiCb<Models.MsgResp>() {
                @Override public void onOk(Models.MsgResp r) {
                    if (!isAdded()) return;
                    dialog.dismiss();
                    toast(r.msg != null ? r.msg : "已修改");
                    refreshConsole(false);
                }
                @Override public void onErr(String m) { if (isAdded()) toast(m); }
            });
        } catch (Exception e) {
            toast("请求构造失败：" + e.getMessage());
        }
    }

    /** 删除自定义源站：后端要求 confirm=<slug>，二次确认防误删 */
    private void confirmDeleteCustom(final Models.SyncSource s) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除自定义源站")
                .setMessage("确认删除「" + s.name + "」（" + s.slug + "）？\n"
                        + "该站已入库的 " + TimeUtil.thousands(s.totalVideos) + " 条数据将一并删除，且不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    Map<String, String> p = HomeFragment.map("confirm", s.slug, "drop_table", "true");
                    VodSyncApi.get().delete("/api/v1/sources/" + s.slug + "/custom", p,
                            Models.MsgResp.class, new VodSyncApi.ApiCb<Models.MsgResp>() {
                        @Override public void onOk(Models.MsgResp r) {
                            toast(r.msg != null ? r.msg : "已删除");
                            refreshConsole(false);
                        }
                        @Override public void onErr(String m) { toast(m); }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 代理运行态弹窗（v1.4.0：是否启用、代理列表、走代理的源站、成功/失败计数） */
    private void showProxyStatusDialog() {
        if (!isAdded()) return;
        VodSyncApi.get().get("/api/v1/stats", null, Models.StatsResp.class,
            new VodSyncApi.ApiCb<Models.StatsResp>() {
                @Override public void onOk(Models.StatsResp s) {
                    if (!isAdded()) return;
                    StringBuilder sb = new StringBuilder();
                    if (s.proxy == null) {
                        sb.append("该版本后端未上报代理状态（需 v1.4.0+）。");
                    } else {
                        sb.append(s.proxy.enabled ? "代理已启用" : "代理未启用").append('\n');
                        if (s.proxy.urls != null && !s.proxy.urls.isEmpty()) {
                            sb.append("代理地址：").append(joinList(s.proxy.urls, "、")).append('\n');
                        }
                        sb.append("直连成功 ").append(s.proxy.directSuccess)
                          .append(" · 代理成功 ").append(s.proxy.proxySuccess)
                          .append(" · 路由失败 ").append(s.proxy.routeFail).append('\n');
                        if (s.proxy.preferred_hosts != null && !s.proxy.preferred_hosts.isEmpty()) {
                            sb.append("当前走代理的源站：").append(joinList(new ArrayList<>(s.proxy.preferred_hosts.keySet()), "、"));
                        } else {
                            sb.append("当前无源站走代理（全部直连）");
                        }
                    }
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("中转代理状态")
                            .setMessage(sb.toString())
                            .setPositiveButton("好", null)
                            .show();
                }
                @Override public void onErr(String m) { toast(m); }
            });
    }

    // ---------- 数据源模式 ----------

    /** 渲染模式选中态；直连模式显示代理管理、隐藏 NAS 专属模块（播放源与鉴权 / 同步控制台） */
    private void renderModeSwitch() {
        View root = getView();
        if (root == null || segModeSync == null || !isAdded()) return;
        boolean st = com.lateshow.app.api.Catalogs.isStatic();
        segModeSync.setBackgroundResource(st ? R.drawable.bg_seg_off : R.drawable.bg_seg_on);
        segModeStatic.setBackgroundResource(st ? R.drawable.bg_seg_on : R.drawable.bg_seg_off);
        segModeSync.setTextColor(getColor(st ? R.color.t2 : R.color.accInk));
        segModeStatic.setTextColor(getColor(st ? R.color.accInk : R.color.t2));
        if (tvModeState != null) tvModeState.setText(st ? "直连 · 免 NAS" : "NAS · 需在线");
        // 模块标题图标随模式切换，不展开也能一眼识别当前模式
        android.widget.ImageView icMode = root.findViewById(R.id.icModeHeader);
        if (icMode != null) icMode.setImageResource(st ? R.drawable.ic_set_static : R.drawable.ic_set_mode);
        View syncCard = root.findViewById(R.id.cardSync);
        if (syncCard != null) syncCard.setVisibility(st ? View.GONE : View.VISIBLE);
        View srcCard = root.findViewById(R.id.cardSource);
        if (srcCard != null) srcCard.setVisibility(st ? View.GONE : View.VISIBLE);
        if (st) {
            // 直连模式：两个 NAS 专属模块已隐藏，图标复位为灰色
            setHeaderIconsConnected(false);
        }
        if (proxySection != null) {
            proxySection.setVisibility(st ? View.VISIBLE : View.GONE);
            if (st) renderProxies(); // 回到直连模式时刷新代理列表
        }
    }

    /** 切换数据源：清空接口缓存（两种模式数据不混用）→ 通知首页重载 */
    private void switchDataSource(int mode) {
        if (PrefStore.get().dataMode() == mode) return;
        PrefStore.get().setDataMode(mode);
        // 两种模式的 slug 体系不同（NAS slug vs 站点域名），切模式后回到「全部源站」
        PrefStore.get().setCurrentSource("", "全部源站");
        com.lateshow.app.api.Catalogs.markDirty();
        com.lateshow.app.util.ApiCache.clear();
        renderModeSwitch();
        toast(mode == com.lateshow.app.api.Catalog.MODE_STATIC
                ? "已切到资源站直连 · NAS 关机也能用"
                : "已切回 NAS 同步库");
        // 切回 NAS 模式：模块重新可见，立即自动测一次播放源连通性以点亮标题图标
        if (mode == com.lateshow.app.api.Catalog.MODE_SYNC) refreshAuthBadge();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).notifyDataSourceChanged();
        }
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
        renderModeSwitch();
        // 每次进入设置页（首次 + 切回 tab）：NAS 模式自动测一次播放源连通性，驱动标题图标绿/灰
        if (!com.lateshow.app.api.Catalogs.isStatic()) refreshAuthBadge();
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
