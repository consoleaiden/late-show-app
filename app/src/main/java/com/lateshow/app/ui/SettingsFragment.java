package com.lateshow.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.lateshow.app.MainActivity;
import com.lateshow.app.R;
import com.lateshow.app.api.VodSyncApi;
import com.lateshow.app.model.Models;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.util.UpdateChecker;

import java.util.List;

/**
 * 设置页：数据源模式 / 播放源与鉴权 / 播放偏好 / 离线与缓存 / 应用更新。
 *
 * 「同步控制台」已迁为独立页面 {@link SyncConsoleActivity}；本页只保留入口，
 * 入口显隐与标题延迟由「播放源连接测试」结果驱动（3 秒超时，见 {@link #probeSource()}）。
 */
public class SettingsFragment extends Fragment {

    private EditText inputBaseUrl, inputToken;
    private TextView srcStatus, btnSave, btnTest, tvVersion, btnCheckUpdate, tvCacheInfo;
    private TextView segModeSync, segModeStatic, tvModeState;
    private TextView tvSavedCount, tvSavedEmpty, tvSourceLatency;
    private View consoleEntry;
    private LinearLayout savedList, proxySection, proxyList;
    private Switch swStreamTools;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 播放源连接测试是否成功：唯一决定「模块有效状态」与同步控制台入口显隐 */
    private boolean sourceAvailable = false;
    /** 连接测试序号：手动保存失败时让在途的探测结果失效，避免旧结果把状态改回「有效」 */
    private int probeSeq = 0;
    /** 验证代次：连续点击保存/测试时只认最后一次结果（审计 A-07 / A-10） */
    private int verifySeq = 0;
    /** 「清空 Token」是独立意图：输入框留空默认表示「沿用已保存的 Token」 */
    private boolean tokenClearIntent = false;

    /** 手风琴面板：同一时间只展开一个（同步控制台独立成页后不再占用一个面板） */
    private static final int[] PANEL_HEADERS = {
            R.id.hdrMode, R.id.hdrSource, R.id.hdrPlay, R.id.hdrCache, R.id.hdrApp};
    private static final int[] PANEL_BODIES = {
            R.id.panelMode, R.id.panelSource, R.id.panelPlay, R.id.panelCache, R.id.panelApp};
    private static final int[] PANEL_ARROWS = {
            R.id.arMode, R.id.arSource, R.id.arPlay, R.id.arCache, R.id.arApp};

    /** 初始化手风琴：点头部切换，展开一个自动收起其他（默认展开第一个） */
    private void setupAccordion(View root) {
        for (int i = 0; i < PANEL_HEADERS.length; i++) {
            final int idx = i;
            root.findViewById(PANEL_HEADERS[i]).setOnClickListener(v -> togglePanel(root, idx, true));
        }
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_settings, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        // MainActivity 不再统一补顶部 padding（首页海报要顶到状态栏），
        // 本页页头仍从状态栏下沿开始，自己补 bars.top。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v, (vv, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            vv.setPadding(vv.getPaddingLeft(), bars.top,
                    vv.getPaddingRight(), vv.getPaddingBottom());
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(v);
        setupAccordion(v);

        // 回到顶部浮动按钮：滚动超过半屏出现，图标/交互与首页、收藏页同款
        android.widget.ScrollView scroll = v.findViewById(R.id.scrollSettings);
        com.google.android.material.floatingactionbutton.FloatingActionButton fabTop =
                v.findViewById(R.id.fabTop);
        scroll.setOnScrollChangeListener((View.OnScrollChangeListener) (vv, sx, sy, osx, osy) ->
                fabTop.setVisibility(sy > scroll.getHeight() / 2 ? View.VISIBLE : View.GONE));
        fabTop.setOnClickListener(vv -> {
            scroll.smoothScrollTo(0, 0);
            fabTop.setVisibility(View.GONE);
        });

        inputBaseUrl = v.findViewById(R.id.inputBaseUrl);
        inputToken = v.findViewById(R.id.inputToken);
        srcStatus = v.findViewById(R.id.srcStatus);
        btnSave = v.findViewById(R.id.btnSave);
        btnTest = v.findViewById(R.id.btnTest);
        tvSourceLatency = v.findViewById(R.id.tvSourceLatency);
        consoleEntry = v.findViewById(R.id.consoleEntry);

        inputBaseUrl.setText(PrefStore.get().baseUrl());
        // 审计 A-06：Token 绝不回显明文，避免截图 / 输入法候选栏 / 无障碍服务泄露。
        // 输入框留空 = 沿用已保存的 Token；点右侧按钮 = 明确要清空已保存的 Token。
        inputToken.setText("");
        renderTokenHint();
        inputToken.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (s != null && s.length() > 0) tokenClearIntent = false;
            }
            @Override public void afterTextChanged(android.text.Editable s) { renderTokenHint(); }
        });
        bindClearButton(inputBaseUrl, v.findViewById(R.id.btnClearBaseUrl));
        final View clearToken = v.findViewById(R.id.btnClearToken);
        if (clearToken != null) {
            clearToken.setVisibility(View.VISIBLE);
            clearToken.setOnClickListener(x -> {
                inputToken.setText("");
                tokenClearIntent = true;
                renderTokenHint();
                inputToken.requestFocus();
                toast("将清除已保存的 Token，点「保存」生效");
            });
        }

        // 同步控制台独立页面入口：仅模块有效（连接测试成功）时可见
        v.findViewById(R.id.btnOpenConsole).setOnClickListener(x -> {
            if (getContext() == null) return;
            startActivity(new Intent(getContext(), SyncConsoleActivity.class));
        });

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

        // 播放偏好：显示播放地址与下载按钮（内嵌播放器 + 全屏播放页共用）
        swStreamTools = v.findViewById(R.id.swStreamTools);
        swStreamTools.setChecked(PrefStore.get().showStreamTools());
        swStreamTools.setOnCheckedChangeListener((bv, on) -> PrefStore.get().setShowStreamTools(on));

        // 播放偏好：未成年模式（VodSync v1.15.0 minor_mode）。开启后所有 API 请求带
        // X-Minor-Mode: 1，服务端在内容读接口统一剔除 18+ 源站；缓存命名空间含本开关。
        // 开↔关必须走同一条失效链路：清接口缓存 + 清源站名映射 + 置脏 + 立刻通知首页重载，
        // 只靠「回首页时缓存刚好过期」会出现「关→开 不刷新」的方向性差异。
        android.widget.Switch swMinorMode = v.findViewById(R.id.swMinorMode);
        if (swMinorMode != null) {
            swMinorMode.setChecked(PrefStore.get().minorMode());
            swMinorMode.setOnCheckedChangeListener((bv, on) -> {
                if (PrefStore.get().minorMode() == on) return;   // 程序化回填，不重复处理
                PrefStore.get().setMinorMode(on);
                com.lateshow.app.util.ApiCache.clear();
                com.lateshow.app.util.SourceCache.clear();
                com.lateshow.app.api.Catalogs.markDirty();
                if (getActivity() instanceof com.lateshow.app.MainActivity) {
                    ((com.lateshow.app.MainActivity) getActivity()).notifyMinorModeChanged();
                }
                toast(on ? "未成年模式已开启：仅展示非 18+ 源站内容"
                         : "未成年模式已关闭：源站与内容已恢复完整");
            });
        }

        // 播放偏好：智能去广告（App 端 HLS 播放列表过滤，默认开启；后端规则包可选增强）
        android.widget.Switch swAdFilter = v.findViewById(R.id.swAdFilter);
        if (swAdFilter != null) {
            swAdFilter.setChecked(PrefStore.get().adFilter());
            swAdFilter.setOnCheckedChangeListener((bv, on) -> {
                if (PrefStore.get().adFilter() == on) return;   // 程序化回填，不重复处理
                PrefStore.get().setAdFilter(on);
                toast(on ? "智能去广告已开启"
                         : "智能去广告已关闭：将播放原始片源");
            });
        }

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

        // 主题切换：三态形变图标（明/暗/随系统），播完再应用主题重建
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
    }

    // ==================== 播放源连接测试：标题延迟 + 模块有效状态 ====================

    /** 进入设置页自动执行：3 秒超时的 /healthz 往返探测 */
    private void probeSource() {
        if (!isAdded() || getView() == null) return;
        if (com.lateshow.app.api.Catalogs.isStatic()) return;   // 直连模式没有 VodSync 后端
        tvSourceLatency.setText("检测中");
        tvSourceLatency.setTextColor(getColor(R.color.t3));
        final int seq = ++probeSeq;
        VodSyncApi.get().ping(3000L, new VodSyncApi.ApiCb<Long>() {
            @Override public void onOk(Long ms) {
                if (!isAdded() || seq != probeSeq) return;
                setSourceState(true, ms);
                refreshAuthBadge();
            }
            @Override public void onErr(String m) {
                if (!isAdded() || seq != probeSeq) return;
                setSourceState(false, null);
            }
        });
    }

    /**
     * 测试结果统一落地：标题延迟文案、标题图标颜色、模块有效状态、同步控制台入口显隐。
     * 成功 = 绿色延迟 ms；超时/失败 = 灰色「超时」且入口隐藏。
     */
    private void setSourceState(boolean ok, Long ms) {
        sourceAvailable = ok;
        if (!isAdded() || getView() == null) return;
        tvSourceLatency.setText(ok ? (ms == null ? "已连接" : ms + "ms") : "超时");
        tvSourceLatency.setTextColor(getColor(ok ? R.color.ok : R.color.t3));
        android.widget.ImageView ic = getView().findViewById(R.id.icSourceHeader);
        if (ic != null) ic.setColorFilter(getColor(ok ? R.color.ok : R.color.t3));
        consoleEntry.setVisibility(ok ? View.VISIBLE : View.GONE);
    }

    /** 顶部状态条：显示服务端鉴权状态与 Token 配置情况（不再驱动图标颜色，避免与连接测试冲突） */
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

    // ==================== 播放源保存 / 测试 ====================

    /**
     * 保存 / 测试连接（审计 A-07）。
     *
     * <p>用「临时 baseUrl + 临时 Token」走 {@link VodSyncApi#verify}，全程不读写全局配置；
     * 只有 save=true 且验证通过，才在 {@link #commitSource} 里一次性提交地址、Token 与播放源列表。
     * 因此「测试连接」不写盘、不写列表、不清缓存、不通知首页重载、不触发状态探测，
     * 失败也不需要任何回滚（从未写过）。
     */
    private void verifySource(final boolean save) {
        if (!isAdded()) return;
        final String url = inputBaseUrl.getText().toString().trim();
        final String typed = inputToken.getText().toString().trim();
        String err = PrefStore.validateBaseUrl(url);
        if (err != null) { toast(err); return; }

        // 留空 = 沿用当前已保存的 Token；只有明确的「清空 Token」按钮才提交空值
        final String token = tokenClearIntent ? ""
                : (typed.isEmpty() ? PrefStore.get().apiToken() : typed);
        final int seq = ++verifySeq;
        if (save) btnSave.setText("检测中…"); else btnTest.setText("检测中…");
        srcStatus.setTextColor(getColor(R.color.t2));
        srcStatus.setText("● 正在验证（此阶段不会修改当前配置）…");

        VodSyncApi.verify(url, token, new VodSyncApi.ApiCb<VodSyncApi.VerifyResult>() {
            @Override public void onOk(VodSyncApi.VerifyResult r) {
                if (!isAdded() || seq != verifySeq) return;
                restoreVerifyButtons();
                String state = r.authEnabled ? "已授权 · Token 有效" : "服务端未开启鉴权 · 可不填 Token";
                if (!save) {
                    srcStatus.setTextColor(getColor(R.color.ok));
                    srcStatus.setText("● 测试通过 · " + state + " · " + r.elapsedMs + "ms（未保存）");
                    toast("连接正常，当前配置未被修改");
                    return;
                }
                // 唯一的持久化入口：验证成功后原子提交
                commitSource(url, token);
                srcStatus.setTextColor(getColor(R.color.ok));
                srcStatus.setText("● 已保存 · " + state + " · " + r.elapsedMs + "ms");
                btnSave.setText("已保存");
                btnSave.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_ok));
                toast("配置已保存");
                postResetSave();
                probeSource();
            }

            @Override public void onErr(String m) {
                if (!isAdded() || seq != verifySeq) return;
                probeSeq++;   // 作废在途探测，避免稍后返回的成功把失败态改回「有效」
                setSourceState(false, null);
                String detail = VodSyncApi.tokenErrorDetail(m).replace("[[need-token]]", "");
                if (detail.length() > 70) detail = detail.substring(0, 70) + "…";
                srcStatus.setTextColor(getColor(R.color.bad));
                srcStatus.setText("● " + (save ? "保存失败" : "测试失败") + " · " + detail);
                restoreVerifyButtons();
                toast("验证未通过，当前配置保持原样");
            }
        });
    }

    /** 原子提交：地址 / Token / 已保存列表一并落地；凭据变化时由 PrefStore 统一清缓存 */
    private void commitSource(String url, String token) {
        String oldUrl = PrefStore.get().baseUrl();
        String oldToken = PrefStore.get().apiToken();
        tokenClearIntent = false;
        inputToken.setText("");
        PrefStore.get().setBaseUrl(url);
        PrefStore.get().setApiToken(token);
        PrefStore.get().upsertSavedSource(url, token, null);
        renderSavedSources();
        renderTokenHint();
        if (!url.equals(oldUrl) || !token.equals(oldToken)) {
            com.lateshow.app.api.Catalogs.markDirty();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).notifyDataSourceChanged();
            }
        }
    }

    /** Token 输入框提示：只显示掩码，绝不回填明文（审计 A-06） */
    private void renderTokenHint() {
        if (inputToken == null) return;
        if (tokenClearIntent) {
            inputToken.setHint("将清除已保存的 Token");
            return;
        }
        String saved = PrefStore.get().apiToken();
        if (saved.isEmpty()) {
            inputToken.setHint("vs_xxxxxxxxxxxx（服务端开启鉴权时填写）");
        } else {
            inputToken.setHint("已保存 " + com.lateshow.app.util.Credentials.mask(saved) + " · 留空表示不修改");
        }
    }

    private void restoreVerifyButtons() {
        if (!isAdded()) return;
        btnSave.setText("保存");
        btnTest.setText("测试连接");
        btnSave.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_main));
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

    private void postResetSave() {
        handler.postDelayed(() -> {
            if (btnSave != null && isAdded()) {
                btnSave.setText("保存");
                btnSave.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_main));
            }
        }, 1800);
    }

    // ==================== 已保存播放源（多地址管理） ====================

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

    /** 一键切换：用户显式选择已保存的源，直接原子生效（不再二次验证、不再重复写列表） */
    private void applySavedSource(PrefStore.SavedSource it) {
        PrefStore.get().setBaseUrl(it.url);
        PrefStore.get().setApiToken(it.token == null ? "" : it.token);
        inputBaseUrl.setText(it.url);
        inputToken.setText("");          // 审计 A-06：明文不回显到输入框
        tokenClearIntent = false;
        renderTokenHint();
        // 凭据变化清缓存 / 能力门控由 PrefStore.onCredentialsChanged 统一负责
        com.lateshow.app.api.Catalogs.markDirty();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).notifyDataSourceChanged();
        }
        renderSavedSources();
        probeSource();
        toast("已切换播放源：" + (it.name == null ? it.url : it.name));
    }

    /**
     * 编辑播放源条目：字段顺序 = 播放源名称 / 播放源 URL 地址 / API Token。
     *
     * 修复说明：旧实现把列表项对象（列表 A）直接改字段，再重新反序列化一份列表 B 写回，
     * A/B 引用不同导致修改全部丢失；且 {@code list.removeIf(x -> x != it && x.url.equals(...))}
     * 中 {@code x != it} 恒为 true，URL 不变时会被误删 —— 表现就是「保存后条目消失」。
     * 现在改为按编辑前的 URL 做原子更新，就地改字段并保持位置。
     */
    private void showSavedSourceEditDialog(final PrefStore.SavedSource it) {
        if (!isAdded()) return;
        View dlg = getLayoutInflater().inflate(R.layout.dialog_saved_source, null);
        EditText inName = dlg.findViewById(R.id.dssName);
        EditText inUrl = dlg.findViewById(R.id.dssUrl);
        EditText inToken = dlg.findViewById(R.id.dssToken);
        inName.setText(it.name);
        inUrl.setText(it.url);
        // 审计 A-06：编辑弹窗同样不回填明文 Token，留空表示「保持原值」
        inToken.setText("");
        inToken.setHint(it.token == null || it.token.isEmpty()
                ? "未配置 Token · 留空表示不修改"
                : "已保存 " + com.lateshow.app.util.Credentials.mask(it.token) + " · 留空表示不修改");
        final String oldUrl = it.url;
        final boolean wasActive = oldUrl != null && oldUrl.equals(PrefStore.get().baseUrl());
        final String oldToken = it.token == null ? "" : it.token;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("编辑播放源")
                .setView(dlg)
                .setPositiveButton("保存", (d, w) -> {
                    String name = inName.getText().toString().trim();
                    String url = inUrl.getText().toString().trim();
                    String typedToken = inToken.getText().toString().trim();
                    String urlErr = PrefStore.validateBaseUrl(url);
                    if (urlErr != null) { toast(urlErr); return; }
                    // 留空 = 不修改该条目的 Token（updateSavedSource 里 null 表示保持）
                    String effectiveToken = typedToken.isEmpty() ? oldToken : typedToken;
                    PrefStore.get().updateSavedSource(oldUrl, name, url,
                            typedToken.isEmpty() ? null : typedToken);
                    if (wasActive) {
                        // 编辑的是当前生效地址：生效值同步，但输入框仍不回显明文
                        boolean changed = !url.equals(PrefStore.get().baseUrl())
                                || !effectiveToken.equals(PrefStore.get().apiToken());
                        PrefStore.get().setBaseUrl(url);
                        PrefStore.get().setApiToken(effectiveToken);
                        inputBaseUrl.setText(url);
                        inputToken.setText("");
                        tokenClearIntent = false;
                        renderTokenHint();
                        if (changed) {
                            com.lateshow.app.api.Catalogs.markDirty();
                            if (getActivity() instanceof MainActivity) {
                                ((MainActivity) getActivity()).notifyDataSourceChanged();
                            }
                        }
                    }
                    renderSavedSources();   // 条目保持在列表中，不消失
                    toast("已保存");
                    // 生效地址或 Token 变了：重测连接，让标题延迟与入口状态同步更新
                    if (wasActive && (!url.equals(oldUrl) || !effectiveToken.equals(oldToken))) probeSource();
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

    // ==================== 直连模式代理访问 API ====================

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

    // ==================== 数据源模式 / 其它 ====================

    /** 渲染模式选中态；直连模式显示代理管理、隐藏 NAS 专属的「播放源与鉴权」模块 */
    private void renderModeSwitch() {
        View root = getView();
        if (root == null || segModeSync == null || !isAdded()) return;
        boolean st = com.lateshow.app.api.Catalogs.isStatic();
        segModeSync.setBackgroundResource(st ? R.drawable.bg_seg_off : R.drawable.bg_seg_on);
        segModeStatic.setBackgroundResource(st ? R.drawable.bg_seg_on : R.drawable.bg_seg_off);
        segModeSync.setTextColor(getColor(st ? R.color.t2 : R.color.accInk));
        segModeStatic.setTextColor(getColor(st ? R.color.accInk : R.color.t2));
        if (tvModeState != null) tvModeState.setText(st ? "直连 · 免 NAS" : "NAS · 需在线");
        android.widget.ImageView icMode = root.findViewById(R.id.icModeHeader);
        if (icMode != null) icMode.setImageResource(st ? R.drawable.ic_set_static : R.drawable.ic_set_mode);
        View srcCard = root.findViewById(R.id.cardSource);
        if (srcCard != null) srcCard.setVisibility(st ? View.GONE : View.VISIBLE);
        if (st) {
            // 直连模式没有 VodSync 后端：不测延迟，入口隐藏，图标复位为灰色
            sourceAvailable = false;
            tvSourceLatency.setText("直连");
            tvSourceLatency.setTextColor(getColor(R.color.t3));
            if (consoleEntry != null) consoleEntry.setVisibility(View.GONE);
            android.widget.ImageView ic = root.findViewById(R.id.icSourceHeader);
            if (ic != null) ic.setColorFilter(getColor(R.color.t3));
        }
        if (proxySection != null) {
            proxySection.setVisibility(st ? View.VISIBLE : View.GONE);
            if (st) renderProxies();
        }
    }

    /** 切换数据源：清空接口缓存（两种模式数据不混用）→ 通知首页重载 */
    private void switchDataSource(int mode) {
        if (PrefStore.get().dataMode() == mode) return;
        PrefStore.get().setDataMode(mode);
        PrefStore.get().setCurrentSource("", "全部源站");
        com.lateshow.app.api.Catalogs.markDirty();
        com.lateshow.app.util.ApiCache.clear();
        renderModeSwitch();
        toast(mode == com.lateshow.app.api.Catalog.MODE_STATIC
                ? "已切到资源站直连 · NAS 关机也能用"
                : "已切回 NAS 同步库");
        // 切回 NAS 模式：模块重新可见，立即自动测一次播放源连通性
        if (mode == com.lateshow.app.api.Catalog.MODE_SYNC) probeSource();
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
                    .setMessage(UpdateChecker.brief(info) + "\n\n" + notes)
                    .setPositiveButton("立即更新", (d, w) -> {
                        if (getActivity() != null) UpdateChecker.downloadAndInstall(getActivity(), info);
                    })
                    .setNegativeButton("稍后", null)
                    .show();
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
        renderModeSwitch();
        // 每次进入设置页（首次 + 切回 tab）：自动执行一次 3 秒超时的播放源连接测试
        if (!com.lateshow.app.api.Catalogs.isStatic()) probeSource();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
