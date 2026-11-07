package com.lateshow.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lateshow.app.R;
import com.lateshow.app.adapter.ChipAdapter;
import com.lateshow.app.adapter.VideoAdapter;
import com.lateshow.app.api.VodSyncApi;
import com.lateshow.app.model.CardItem;
import com.lateshow.app.model.Models;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.util.ApiCache;
import com.lateshow.app.util.TimeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 首页：源站切换 + 搜索 + 分类筛选 + 3 列海报墙（无限加载） */
public class HomeFragment extends Fragment {

    /** 列表缓存 TTL：10 分钟内切回首页不再重复请求 */
    private static final long TTL_LIST = 10 * 60 * 1000L;
    /** 分类缓存 TTL：分类变动很慢，6 小时内直接用缓存 */
    private static final long TTL_CAT = 6 * 60 * 60 * 1000L;
    private static final String CK_SOURCES = "sources";

    private View btnSource;
    private ImageView btnSourceIcon;
    private TextView metaCount, metaSync, emptyView;
    private EditText inputSearch;
    private RecyclerView chips, grid;
    private ChipAdapter chipAdapter;
    private VideoAdapter videoAdapter;

    private final List<Models.SyncSource> sources = new ArrayList<>();
    private String curSlug = "";      // 空 = 全部源站
    private String curName = "全部源站";
    private String curCategory = null; // null = 全部
    private String keyword = "";

    private int page = 1, pagecount = 1;
    private boolean loading = false;
    private boolean catFailed = false; // 分类接口失败时用列表数据兜底
    private List<Models.VideoSummary> lastRawList = new ArrayList<>();

    /** 搜索历史每页条数（默认展示前 10 条，翻页查看更多） */
    private static final int HIST_PAGE_SIZE = 10;
    private LinearLayout histPanel, histBody, histPager;
    private TextView histCount, histEmpty, histPage, histPrev, histNext, btnClearHist;
    private int histPageNo = 0;

    /** 首屏骨架屏：9 张占位卡 + 呼吸动画，避免首次加载白屏 */
    private LinearLayout skeletonBox;
    private android.animation.ObjectAnimator skelAnim;
    private final android.os.Handler skelHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable skelShowTask = new Runnable() {
        @Override public void run() {
            if (skeletonBox == null || videoAdapter == null || videoAdapter.getItemCount() > 0) return;
            skeletonBox.setVisibility(View.VISIBLE);
            if (skelAnim != null) skelAnim.start();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_home, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        com.lateshow.app.util.LottieIcon.bindTree(v);
        btnSource = v.findViewById(R.id.btnSource);
        btnSourceIcon = v.findViewById(R.id.btnSourceIcon);
        // 顶栏两个图标常驻循环播放（源站菜单中线滑动 / 放大镜摇摆）
        com.lateshow.app.util.LottieIcon.loop(btnSourceIcon, "ic_menu_list");
        com.lateshow.app.util.LottieIcon.loop(v.findViewById(R.id.imgSearch), "ic_search");
        inputSearch = v.findViewById(R.id.inputSearch);
        setupSearchHistory(v);
        setupSkeleton(v);
        chips = v.findViewById(R.id.chips);
        grid = v.findViewById(R.id.grid);
        metaCount = v.findViewById(R.id.metaCount);
        metaSync = v.findViewById(R.id.metaSync);
        emptyView = v.findViewById(R.id.emptyView);

        curSlug = PrefStore.get().currentSourceSlug();
        curName = PrefStore.get().currentSourceName();
        beginSourceProbe(); // 初始呼吸态，探测出结果后定格橙/灰

        chipAdapter = new ChipAdapter(c -> {
            curCategory = c == null ? null : c.typeName;
            com.lateshow.app.util.UiAnim.fadeUp(grid, 8);   // 换分类＝整批内容换掉，给一次淡入
            reload();
        });
        chips.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
        chips.setAdapter(chipAdapter);
        chips.setHasFixedSize(true);

        videoAdapter = new VideoAdapter(new VideoAdapter.OnClick() {
            @Override public void onCard(CardItem item, View source) { openDetail(item, source); }
            @Override public void onDelete(CardItem item) { /* 首页卡片无删除 */ }
        });
        GridLayoutManager glm = new GridLayoutManager(getContext(), 3);
        grid.setLayoutManager(glm);
        grid.setAdapter(videoAdapter);
        grid.setHasFixedSize(true); // 条目尺寸固定，插入新页不触发整表重测量
        grid.setItemAnimator(null); // 关闭条目动画：默认动画器的浮点平移会导致减速阶段文字亚像素抖动

        // 无限加载 + 回到顶部按钮显隐（FAB 用 LottieDrawable 注入图标）
        com.google.android.material.floatingactionbutton.FloatingActionButton fabTop =
                v.findViewById(R.id.fabTop);
        final com.airbnb.lottie.LottieDrawable fabIcon =
                com.lateshow.app.util.LottieIcon.into(fabTop, "ic_top", false);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm != null) {
                    boolean show = lm.findFirstVisibleItemPosition() > 8;
                    fabTop.setVisibility(show ? View.VISIBLE : View.GONE);
                }
                if (loading || page >= pagecount) return;
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                int total = lm.getItemCount();
                if (last >= total - 6) loadPage(page + 1);
            }

            /** 滚动期间暂停顶栏两个常驻循环动画，静止后恢复——消除每帧额外重绘 */
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                boolean scrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
                if (scrolling) hideHistory(); // 开始滚动列表即收起历史面板
                if (btnSourceIcon instanceof com.airbnb.lottie.LottieAnimationView) {
                    com.airbnb.lottie.LottieAnimationView lv = (com.airbnb.lottie.LottieAnimationView) btnSourceIcon;
                    if (scrolling) lv.pauseAnimation();
                    else if (lv.getComposition() != null) lv.playAnimation();
                }
                View search = getView() != null ? getView().findViewById(R.id.imgSearch) : null;
                if (search instanceof com.airbnb.lottie.LottieAnimationView) {
                    com.airbnb.lottie.LottieAnimationView lv = (com.airbnb.lottie.LottieAnimationView) search;
                    if (scrolling) lv.pauseAnimation();
                    else if (lv.getComposition() != null) lv.playAnimation();
                }
            }
        });
        fabTop.setOnClickListener(vv -> {
            if (fabIcon != null) {
                fabIcon.setProgress(0f);
                fabIcon.playAnimation();
            }
            grid.smoothScrollToPosition(0);
            fabTop.setVisibility(View.GONE);
        });

        btnSource.setOnClickListener(vv -> showSourceMenu());
        inputSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void afterTextChanged(Editable s) {
                keyword = s.toString().trim();
                if (!keyword.isEmpty()) hideHistory(); // 输入中收起历史，把空间让给结果列表
                reload();
            }
        });

        refreshSources();
        reload();
    }

    private void openDetail(CardItem item, View source) {
        // 关键词检索后点开影片也算一次有效搜索：记录历史（覆盖「打字后直接点卡片」的场景）
        if (keyword != null && !keyword.isEmpty()) PrefStore.get().addSearchHistory(keyword);
        // 详情页改为同窗口的 Fragment 浮层：卡片坐标交给它做跟手一镜到底
        if (getActivity() instanceof com.lateshow.app.MainActivity) {
            ((com.lateshow.app.MainActivity) getActivity()).openDetail(item, source);
        }
    }

    // ---------- 首屏骨架屏 ----------

    /** 构建 3×3 占位卡：封面块沿用 8dp 圆角底色，标题两行灰条，整体做透明度呼吸 */
    private void setupSkeleton(View root) {
        skeletonBox = root.findViewById(R.id.skeletonBox);
        if (skeletonBox == null || getContext() == null) return;
        final int cols = 3, rows = 3;
        int itemW = (getResources().getDisplayMetrics().widthPixels / cols) - dp(10);
        int coverH = Math.round(itemW * 16f / 9f);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                LinearLayout card = new LinearLayout(getContext());
                card.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                cp.leftMargin = dp(5);
                cp.rightMargin = dp(5);
                cp.bottomMargin = dp(14);

                View cover = new View(getContext());
                cover.setBackgroundResource(R.drawable.bg_cover_round);
                card.addView(cover, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, coverH));

                View line1 = new View(getContext());
                line1.setBackgroundResource(R.drawable.bg_skel);
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(9));
                lp1.topMargin = dp(8);
                card.addView(line1, lp1);

                View line2 = new View(getContext());
                line2.setBackgroundResource(R.drawable.bg_skel);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                        Math.round(itemW * 0.6f), dp(9));
                lp2.topMargin = dp(6);
                card.addView(line2, lp2);

                row.addView(card, cp);
            }
            skeletonBox.addView(row);
        }
        skelAnim = android.animation.ObjectAnimator.ofFloat(skeletonBox, "alpha", 1f, 0.45f);
        skelAnim.setDuration(900);
        skelAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        skelAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        skelAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
    }

    /** 延迟 220ms 再显示：本地缓存命中或极快响应时不会闪一下骨架 */
    private void showSkeletonDelayed() {
        if (skeletonBox == null) return;
        skelHandler.removeCallbacks(skelShowTask);
        skelHandler.postDelayed(skelShowTask, 220);
    }

    private void hideSkeleton() {
        skelHandler.removeCallbacks(skelShowTask);
        if (skeletonBox == null) return;
        if (skeletonBox.getVisibility() != View.GONE) skeletonBox.setVisibility(View.GONE);
        if (skelAnim != null) skelAnim.cancel();
    }

    // ---------- 搜索历史 ----------

    /** 绑定搜索历史：点击/聚焦搜索框展开；回车或点搜索图标记录关键词；支持单条删除、翻页、一键清空 */
    private void setupSearchHistory(View root) {
        histPanel = root.findViewById(R.id.histPanel);
        histBody = root.findViewById(R.id.histBody);
        histPager = root.findViewById(R.id.histPager);
        histCount = root.findViewById(R.id.histCount);
        histEmpty = root.findViewById(R.id.histEmpty);
        histPage = root.findViewById(R.id.histPage);
        histPrev = root.findViewById(R.id.histPrev);
        histNext = root.findViewById(R.id.histNext);
        btnClearHist = root.findViewById(R.id.btnClearHist);
        if (histPanel == null || inputSearch == null) return;

        inputSearch.setOnClickListener(vv -> showHistory());
        inputSearch.setOnFocusChangeListener((vv, has) -> {
            if (has) showHistory();
            else hideHistory();
        });
        // 键盘「搜索」键：记录关键词并检索
        inputSearch.setOnEditorActionListener((vv, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                submitSearch(inputSearch.getText().toString());
                return true;
            }
            return false;
        });
        // 搜索图标：与回车等价
        View icon = root.findViewById(R.id.imgSearch);
        if (icon != null) icon.setOnClickListener(vv -> submitSearch(inputSearch.getText().toString()));

        histPrev.setOnClickListener(vv -> {
            if (histPageNo > 0) {
                histPageNo--;
                renderHistory();
            }
        });
        histNext.setOnClickListener(vv -> {
            if (histPageNo < histPageCount() - 1) {
                histPageNo++;
                renderHistory();
            }
        });
        btnClearHist.setOnClickListener(vv -> {
            PrefStore.get().clearSearchHistory();
            histPageNo = 0;
            renderHistory();
            toastShort("搜索历史已清空");
        });
    }

    /** 提交搜索：记录历史（去重置顶）→ 收起面板与键盘 → 按关键词重新加载 */
    private void submitSearch(String raw) {
        String k = raw == null ? "" : raw.trim();
        if (k.isEmpty()) { // 空关键词不记录，仅展示历史
            showHistory();
            return;
        }
        PrefStore.get().addSearchHistory(k);
        hideKeyboard();
        hideHistory();
        if (!k.equals(inputSearch.getText().toString().trim())) {
            inputSearch.setText(k); // 触发 afterTextChanged → reload
            inputSearch.setSelection(k.length());
        } else {
            keyword = k;
            reload();
        }
    }

    /** 点击历史 tag：回填关键词并检索（同时把该记录置顶） */
    private void applyHistory(String k) {
        PrefStore.get().addSearchHistory(k);
        histPageNo = 0;
        hideKeyboard();
        hideHistory();
        if (k.equals(inputSearch.getText().toString().trim())) {
            keyword = k;
            reload();
        } else {
            inputSearch.setText(k); // 触发 afterTextChanged → reload
            inputSearch.setSelection(k.length());
        }
    }

    /** 展开历史面板（首次展开回到第一页，已展开时保持当前页） */
    private void showHistory() {
        if (histPanel == null || !isAdded()) return;
        if (histPanel.getVisibility() != View.VISIBLE) histPageNo = 0;
        renderHistory();
        histPanel.setVisibility(View.VISIBLE);
        com.lateshow.app.util.UiAnim.slideUp(histPanel, 12, 220);
    }

    private void hideHistory() {
        if (histPanel != null && histPanel.getVisibility() == View.VISIBLE) {
            com.lateshow.app.util.UiAnim.fadeOut(histPanel, 120, () -> histPanel.setVisibility(View.GONE));
        }
    }

    /** 总页数：按每页 10 条向上取整，至少 1 页 */
    private int histPageCount() {
        int total = PrefStore.get().searchHistory().size();
        return Math.max(1, (total + HIST_PAGE_SIZE - 1) / HIST_PAGE_SIZE);
    }

    /** 渲染当前页历史：2 列 tag 网格 + 翻页状态 + 清空按钮显隐（无记录时显示空态） */
    private void renderHistory() {
        if (histPanel == null || !isAdded()) return;
        List<String> all = PrefStore.get().searchHistory();
        int total = all.size();
        boolean empty = total == 0;

        histCount.setText(total + " 条");
        histEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        histBody.setVisibility(empty ? View.GONE : View.VISIBLE);
        btnClearHist.setVisibility(empty ? View.GONE : View.VISIBLE);
        histBody.removeAllViews();
        if (empty) {
            histPager.setVisibility(View.GONE);
            histPage.setText("");
            return;
        }

        int pages = histPageCount();
        if (histPageNo >= pages) histPageNo = pages - 1;
        if (histPageNo < 0) histPageNo = 0;
        int from = histPageNo * HIST_PAGE_SIZE;
        int to = Math.min(from + HIST_PAGE_SIZE, total);

        int rows = 0;
        for (int i = from; i < to; i += 2) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (rows > 0) rp.topMargin = dp(6);
            row.addView(tagView(all.get(i), false));
            if (i + 1 < to) {
                row.addView(tagView(all.get(i + 1), true));
            } else {
                // 本页奇数条：右侧放等宽占位，避免左侧 tag 被拉满
                row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            histBody.addView(row, rp);
            rows++;
        }

        // 单页时不显示「1/1 页」，减掉一处无信息量的噪声
        histPage.setText(pages > 1 ? (histPageNo + 1) + "/" + pages + " 页" : "");
        histPager.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
        histPrev.setAlpha(histPageNo > 0 ? 1f : 0.35f);
        histNext.setAlpha(histPageNo < pages - 1 ? 1f : 0.35f);
    }

    /** 单个历史 tag：点 tag 回填检索，点右侧叉号删除该条 */
    private View tagView(final String word, boolean withLeftMargin) {
        View tag = LayoutInflater.from(getContext()).inflate(R.layout.item_hist_tag, histBody, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1f);
        if (withLeftMargin) lp.leftMargin = dp(6);
        tag.setLayoutParams(lp);
        ((TextView) tag.findViewById(R.id.tagText)).setText(word);
        tag.setOnClickListener(vv -> applyHistory(word));
        tag.findViewById(R.id.tagDel).setOnClickListener(vv -> {
            PrefStore.get().removeSearchHistory(word);
            renderHistory();
        });
        return tag;
    }

    private void hideKeyboard() {
        if (inputSearch == null || getContext() == null) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getContext()
                        .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(inputSearch.getWindowToken(), 0);
        inputSearch.clearFocus();
    }

    private void toastShort(String msg) {
        if (getContext() != null) {
            android.widget.Toast.makeText(getContext(), msg, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPause() {
        hideHistory();
        hideSkeleton();
        com.lateshow.app.api.NetMonitor.removeListener(onNetChanged);
        stopRingAnim();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        stopRingAnim();
        super.onDestroyView();
    }

    /** 源站按钮圆环状态机：灰=失败 / 橙=畅通 / 呼吸=测试中 */
    private static final int RING_FAIL = 0;
    private static final int RING_OK = 1;
    private static final int RING_TESTING = 2;

    private android.animation.ValueAnimator ringAnim;
    private com.lateshow.app.util.RingBreathDrawable ringBg;

    /** 呼吸最亮相位（=橙端）：渐变贴环处峰值不透明度，约 0.47 */
    private static final int GLOW_PEAK = 120;
    /** 呼吸最暗相位（=灰端）：留一层极淡的雾，保证明暗过渡有层次而不是彻底消失 */
    private static final int GLOW_MIN = 24;
    private static final android.animation.ArgbEvaluator RING_EVAL = new android.animation.ArgbEvaluator();

    /** 第几轮检测：每次开始新检测自增，回调带着发起时的轮次回来，过期结果直接丢弃 */
    private int ringCycle;
    /** 本轮是否已判定畅通：畅通一旦成立，同轮迟到的失败结果不再覆盖（失败多为过期请求的超时） */
    private boolean ringCycleOk;
    /** 本轮开始时刻（elapsedRealtime）：用于保证呼吸态至少可见一段时间 */
    private long ringCycleStartAt;
    private long lastNetProbeAt;

    /** 开新一轮检测：进入呼吸态，同时作废上一轮所有在途结果 */
    private int beginSourceProbe() {
        ringCycle++;
        ringCycleOk = false;
        ringCycleStartAt = android.os.SystemClock.elapsedRealtime();
        renderSourceState(RING_TESTING);
        return ringCycle;
    }

    /**
     * 上报某一轮的结果。三条规则：
     * 1) 轮次已过期 → 丢弃（切网前发出、切网后才超时的请求不会把已点亮的状态打回灰色）
     * 2) 本轮已判定畅通 → 失败结果丢弃（畅通优先）
     * 3) 呼吸态至少展示 600ms，避免 100ms 级快响应让圆环闪一下就跳色
     */
    private void reportSourceProbe(int cycle, int state) {
        if (cycle != ringCycle) return;
        if (ringCycleOk) return;
        if (state == RING_OK) ringCycleOk = true;
        long dt = android.os.SystemClock.elapsedRealtime() - ringCycleStartAt;
        long wait = state == RING_TESTING ? 0 : Math.max(0, 600 - dt);
        if (wait == 0 || btnSource == null) {
            renderSourceState(state);
            return;
        }
        final int st = state;
        btnSource.postDelayed(() -> {
            if (cycle == ringCycle) renderSourceState(st);
        }, wait);
    }

    /** 源站按钮圆环：橙色=畅通，灰色=不通，橙↔灰呼吸=连接测试中（纯渲染，状态迁移走 begin/report） */
    private void renderSourceState(int state) {
        if (btnSource == null || btnSourceIcon == null) return;
        stopRingAnim();
        if (state == RING_TESTING) {
            // 呼吸态用程序化 Drawable：描边色 + 环内径向渐变可被动画平滑驱动（xml ripple 底做不到颜色动画）
            final int orange = getResources().getColor(R.color.acc, null);
            final int gray = getResources().getColor(R.color.t3, null);
            ringBg = new com.lateshow.app.util.RingBreathDrawable(
                    dp(1.5f), getResources().getColor(R.color.ink800, null));
            ringBg.setRingColor(orange);
            ringBg.setGlowAlpha(GLOW_PEAK);
            btnSource.setBackground(ringBg);
            // 用 float 进度驱动颜色：颜色与渐变透明度取同一个 fraction，保证严格同相位呼吸
            ringAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            ringAnim.setDuration(900);
            ringAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            ringAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            ringAnim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue(); // 0 = 橙(亮) … 1 = 灰(暗)
                int c = (int) RING_EVAL.evaluate(f, orange, gray);
                ringBg.setRingColor(c);
                ringBg.setGlowAlpha(Math.round(GLOW_PEAK + (GLOW_MIN - GLOW_PEAK) * f));
                com.lateshow.app.util.LottieIcon.tint(btnSourceIcon, c);
            });
            ringAnim.start();
        } else {
            boolean ok = state == RING_OK;
            btnSource.setBackgroundResource(ok ? R.drawable.bg_ring_on : R.drawable.bg_ring_off);
            com.lateshow.app.util.LottieIcon.tint(btnSourceIcon,
                    getResources().getColor(ok ? R.color.acc : R.color.t3, null));
        }
    }

    private void stopRingAnim() {
        if (ringAnim != null) {
            ringAnim.cancel();
            ringAnim = null;
        }
    }

    /** 轻量健康探测：NAS 探 /healthz；直连探「当前选中源站」的 json 接口。测试中呼吸，出结果定格橙/灰 */
    private void pingHealth() {
        pingHealth(beginSourceProbe());
    }

    /** 复用调用方已开启的轮次（源站列表 / 首屏列表与探测共享同一轮次，谁先成功都算数） */
    private void pingHealth(final int cyc) {
        com.lateshow.app.api.Catalogs.get().pingCurrent(curSlug, new VodSyncApi.ApiCb<Long>() {
            @Override public void onOk(Long ms) {
                if (isAdded()) reportSourceProbe(cyc, RING_OK);
            }
            @Override public void onErr(String m) {
                if (isAdded()) reportSourceProbe(cyc, RING_FAIL);
            }
        });
    }

    /** 网络切换（WiFi ↔ 蜂窝）：新网络就绪后重测一次，否则圆环会停在上个网络的结果上 */
    private final Runnable onNetChanged = () -> {
        View v = getView();
        if (v == null) return;
        v.postDelayed(() -> {
            if (!isAdded() || getView() == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastNetProbeAt < 3000) return; // 事件风暴兜底
            lastNetProbeAt = now;
            pingHealth();
        }, 800);
    };

    @Override
    public void onResume() {
        super.onResume();
        com.lateshow.app.api.NetMonitor.addListener(onNetChanged);
        pingHealth(); // 回前台必重测：列表可能全部命中缓存，没有网络请求可依赖
        // 设置页切换过数据源：重新拉源站与列表
        if (com.lateshow.app.api.Catalogs.consumeDirty()) onDataSourceChanged();
    }

    /** 数据源模式切换后调用：重置筛选条件并重载（由设置页 / MainActivity 触发） */
    public void onDataSourceChanged() {
        curSlug = PrefStore.get().currentSourceSlug();
        curName = PrefStore.get().currentSourceName();
        curCategory = null;
        keyword = "";
        if (inputSearch != null) inputSearch.setText("");
        if (getView() == null) return;
        final int cyc = beginSourceProbe();
        refreshSources();
        reload();
        pingHealth(cyc); // 三者同一轮次：源站/首屏/探测谁先成功都点亮圆环
    }

    /** 源站列表缓存 key：带模式前缀，两种模式不互相命中 */
    private String ckSources() {
        return CK_SOURCES + "|" + com.lateshow.app.api.Catalogs.cacheTag();
    }

    /** 源站列表：菜单数据 + 元信息——优先用缓存，过期再拉（数据源由 Catalog 决定） */
    private void refreshSources() {
        final String ck = ckSources();
        final int cyc = ringCycle; // 结果归属发起时的轮次，过期即丢弃
        Models.SyncSourcesResp cached = ApiCache.get(ck, Models.SyncSourcesResp.class, TTL_LIST);
        if (cached != null && cached.list != null && !cached.list.isEmpty()) {
            applySources(cached, true);
            return; // 缓存新鲜，不再发请求
        }
        com.lateshow.app.api.Catalogs.get().sources(
            new VodSyncApi.ApiCb<Models.SyncSourcesResp>() {
                @Override public void onOk(Models.SyncSourcesResp r) {
                    if (!isAdded()) return;
                    ApiCache.put(ck, r);
                    reportSourceProbe(cyc, RING_OK); // 源站列表拿到 = 后端可达
                    applySources(r, false);
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    reportSourceProbe(cyc, RING_FAIL);
                    // 401：服务端开启了访问控制，引导填写 API Token
                    if (com.lateshow.app.util.AuthGate.handleApiError(getActivity(), m, HomeFragment.this::reload)) {
                        metaCount.setText("需要 API Token · 请在设置页填写");
                        return;
                    }
                    // 网络失败：退回本地缓存的源站列表，菜单仍可用
                    Models.SyncSourcesResp stale = ApiCache.getStale(ck, Models.SyncSourcesResp.class);
                    if (stale != null && stale.list != null && !stale.list.isEmpty()) {
                        applySources(stale, true);
                        return;
                    }
                    metaCount.setText(nasDownHint("源站加载失败：" + m));
                    if (pendingMenu) {
                        pendingMenu = false;
                        android.widget.Toast.makeText(getContext(), "源站列表加载失败：" + m, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
    }

    /** NAS 模式失败时提示可以切到直连模式；直连模式失败则原样返回 */
    private static String nasDownHint(String msg) {
        if (com.lateshow.app.api.Catalogs.isStatic()) return msg;
        return msg + "\n可在设置页切换到「资源站直连」";
    }

    /** 渲染源站列表与统计信息；fromCache=true 表示数据来自本地缓存 */
    private void applySources(Models.SyncSourcesResp r, boolean fromCache) {
        sources.clear();
        if (r.list != null) {
            for (Models.SyncSource s : r.list) {
                if (s.enabled) sources.add(s);
            }
        }
        com.lateshow.app.util.SourceCache.putSync(sources);
        // 圆环状态由 pingHealth / 网络请求的 reportSourceProbe 决定，这里不再直接改
        long total = 0;
        for (Models.SyncSource s : sources) total += s.totalVideos;
        metaCount.setText("已收录 " + TimeUtil.compact((int) total) + " 部 · " + sources.size() + " 源");
        if (fromCache) {
            long age = ApiCache.ageMs(ckSources());
            metaSync.setText("● 缓存数据" + (age >= 0 ? " · " + ageText(age) : ""));
        } else {
            metaSync.setText("● " + TimeUtil.nowHm() + " 同步");
        }
        if (pendingMenu) {
            pendingMenu = false;
            showSourceMenu();
        }
    }

    /** 缓存年龄 → 「刚刚 / N 分钟前 / N 小时前」 */
    private static String ageText(long ms) {
        long min = ms / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        return (min / 60) + " 小时前";
    }

    private void showSourceMenu() {
        if (!isAdded() || getView() == null) return;

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        box.setPadding(0, pad, 0, pad);

        int rows = 1;
        if (sources.isEmpty()) {
            // 源站列表为空：显示「全部源站」+「重新加载」兜底行，保证点击始终有反馈
            addMenuRow(box, "全部源站", "", curSlug.isEmpty(), "");
            addMenuRow(box, "重新加载源站列表", "↻", false, null);
        } else {
            // 「全部源站」固定项
            addMenuRow(box, "全部源站", "", curSlug.isEmpty(), "");

            // 按影片数降序（数据多的源站排前面）
            List<Models.SyncSource> sorted = new ArrayList<>(sources);
            java.util.Collections.sort(sorted, (a, b) -> Integer.compare(b.totalVideos, a.totalVideos));
            for (Models.SyncSource s : sorted) {
                addMenuRow(box, s.name, TimeUtil.compact(s.totalVideos), s.slug != null && s.slug.equals(curSlug), s.slug);
                rows++;
            }
        }

        androidx.core.widget.NestedScrollView sv = new androidx.core.widget.NestedScrollView(getContext());
        sv.setVerticalScrollBarEnabled(true);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int want = dp(12) + dp(38) * rows;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(want, maxH)));

        PopupWindow pw = new PopupWindow(sv, dp(250),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // 浮层统一 12dp 圆角（原 bg_card_lg 18dp，弹出层比页面卡片更收一档）、1dp 描边
        pw.setBackgroundDrawable(getResources().getDrawable(R.drawable.bg_menu));
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(8));
        // 左移 4dp：浮层左缘盖住海报列左缘，消除背后海报切出的 1px 细线
        pw.showAsDropDown(btnSource, -dp(4), dp(6));
        box.setTag(pw);
    }

    private boolean pendingMenu = false;

    private void addMenuRow(LinearLayout box, String name, String count, boolean sel, String slug) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_source_menu, box, false);
        ((TextView) row.findViewById(R.id.name)).setText(name);
        TextView cnt = row.findViewById(R.id.count);
        cnt.setText(count);
        row.findViewById(R.id.dot).setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> {
            PopupWindow pw = (PopupWindow) box.getTag();
            if (pw != null) pw.dismiss();
            if (slug == null) {
                // 「重新加载源站列表」兜底行
                android.widget.Toast.makeText(getContext(), "正在重新加载源站…", android.widget.Toast.LENGTH_SHORT).show();
                refreshSources();
                return;
            }
            curSlug = slug;
            curName = name;
            // 切换源站后原分类大概率不存在，重置为「全部」，避免加载失败
            curCategory = null;
            PrefStore.get().setCurrentSource(curSlug, curName);
            android.widget.Toast.makeText(getContext(), "源站：" + curName,
                    android.widget.Toast.LENGTH_SHORT).show();
            // 立即重测新选中源站的连通性，圆环状态不滞后到下次回到前台
            pingHealth();
            refreshCategories();
            reload();
        });
        box.addView(row);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshCategories() {
        catFailed = false;
        final String ck = "cats|" + com.lateshow.app.api.Catalogs.cacheTag() + "|" + curSlug;
        Models.CategoryResp cached = ApiCache.get(ck, Models.CategoryResp.class, TTL_CAT);
        if (cached != null && cached.list != null && !cached.list.isEmpty()) {
            applyCategories(cached.list);
            return; // 6 小时内分类直接复用缓存，不发请求
        }
        com.lateshow.app.api.Catalogs.get().categories(curSlug,
            new VodSyncApi.ApiCb<Models.CategoryResp>() {
                @Override public void onOk(Models.CategoryResp r) {
                    if (!isAdded()) return;
                    if (r.list != null && !r.list.isEmpty()) {
                        ApiCache.put(ck, r);
                        applyCategories(r.list);
                    } else {
                        // 接口无数据：从已加载影片兜底
                        catFailed = true;
                        buildCatsFromItems();
                    }
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    // 网络失败：先用本地缓存的分类，仍没有再按影片类型兜底
                    Models.CategoryResp stale = ApiCache.getStale(ck, Models.CategoryResp.class);
                    if (stale != null && stale.list != null && !stale.list.isEmpty()) {
                        applyCategories(stale.list);
                        return;
                    }
                    catFailed = true;
                    buildCatsFromItems();
                }
            });
    }

    /** 渲染分类芯片：当前分类在新列表中不存在时自动回到「全部」 */
    private void applyCategories(List<Models.Category> list) {
        if (curCategory != null) {
            boolean found = false;
            for (Models.Category c : list) {
                if (c != null && curCategory.equals(c.typeName)) { found = true; break; }
            }
            if (!found) curCategory = null;
        }
        chipAdapter.setCategories(list);
        chipAdapter.selectByName(curCategory);
    }

    /** 用已加载影片的分类名去重生成分类芯片（兜底，保证分类始终可见） */
    private void buildCatsFromItems() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (Models.VideoSummary v : lastRawList) {
            if (v.typeName != null && !v.typeName.isEmpty()) set.add(v.typeName);
        }
        if (set.isEmpty()) return;
        List<Models.Category> cats = new ArrayList<>();
        for (String n : set) {
            Models.Category c = new Models.Category();
            c.typeName = n;
            c.count = 0;
            cats.add(c);
        }
        chipAdapter.setCategories(cats);
        chipAdapter.selectByName(curCategory);
    }

    private void reload() {
        page = 1;
        videoAdapter.setItems(new ArrayList<>());
        emptyView.setVisibility(View.GONE);
        loadPage(1);
        refreshCategories();
    }

    private void loadPage(int p) {
        loading = true;
        final int reqPage = p;
        final String ck = listCacheKey(p);
        final String kw = keyword;
        final String type = curCategory;
        final int cyc = ringCycle; // 首屏列表拿到即证明连通

        // 第一页优先用缓存（TTL 内直接渲染，不发请求）
        if (reqPage == 1) {
            Models.VideoListResp cached = ApiCache.get(ck, Models.VideoListResp.class, TTL_LIST);
            if (cached != null && cached.list != null && !cached.list.isEmpty()) {
                loading = false;
                applyList(cached, 1, false);
                return;
            }
        }

        // 走网络且当前无数据：首屏显示骨架屏占位（延迟显示，避免快响应闪屏）
        if (reqPage == 1 && videoAdapter.getItemCount() == 0) showSkeletonDelayed();

        com.lateshow.app.api.Catalogs.get().videos(p, 18, curSlug, type, kw,
            new VodSyncApi.ApiCb<Models.VideoListResp>() {
                @Override public void onOk(Models.VideoListResp r) {
                    if (!isAdded()) return;
                    loading = false;
                    // 首屏列表拿到 = 当前源站确实能出数据，作为「畅通」证据点亮圆环
                    if (reqPage == 1) reportSourceProbe(cyc, RING_OK);
                    if (reqPage == 1) ApiCache.put(ck, r); // 只缓存第一页，翻页数据不落盘
                    applyList(r, reqPage, false);
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    loading = false;
                    hideSkeleton();
                    if (reqPage == 1 && videoAdapter.getItemCount() == 0) {
                        // 网络失败：退回本地缓存的第一页，离线也能浏览
                        Models.VideoListResp stale = ApiCache.getStale(ck, Models.VideoListResp.class);
                        if (stale != null && stale.list != null && !stale.list.isEmpty()) {
                            applyList(stale, 1, true);
                            return;
                        }
                        if (com.lateshow.app.util.AuthGate.handleApiError(getActivity(), m, HomeFragment.this::reload)) {
                            emptyView.setVisibility(View.VISIBLE);
                            emptyView.setText("需要 API Token\n请到「设置」填写后点击重试");
                            return;
                        }
                        emptyView.setVisibility(View.VISIBLE);
                        emptyView.setText(nasDownHint("加载失败：" + m)
                                + "\n直连模式不用 NAS，换源站可重试");
                    }
                }
            });
    }

    /** 列表缓存 key：模式 + 源站 + 分类 + 关键词 + 页码（第 1 页才落盘） */
    private String listCacheKey(int p) {
        return "videos|" + com.lateshow.app.api.Catalogs.cacheTag() + "|" + curSlug
                + "|" + (curCategory == null ? "" : curCategory)
                + "|" + keyword + "|" + p;
    }

    /** 渲染列表响应；fromCache=true 时给出离线提示 */
    private void applyList(Models.VideoListResp r, int reqPage, boolean fromCache) {
        hideSkeleton();
        page = reqPage;
        pagecount = Math.max(1, r.pagecount);
        if (r.list != null) lastRawList = r.list;
        List<CardItem> cards = new ArrayList<>();
        if (r.list != null) {
            for (Models.VideoSummary v : r.list) cards.add(toCard(v));
        }
        if (reqPage == 1) {
            videoAdapter.setItems(cards);
        } else {
            videoAdapter.addItems(cards);
        }
        // 分类接口失败时，用首页数据兜底生成分类
        if (catFailed && reqPage == 1) buildCatsFromItems();
        emptyView.setVisibility(videoAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        if (fromCache && getContext() != null) {
            metaSync.setText("● 离线缓存 · " + TimeUtil.nowHm());
            android.widget.Toast.makeText(getContext(), "网络不可用，显示本地缓存内容",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private CardItem toCard(Models.VideoSummary v) {
        CardItem c = new CardItem();
        c.kind = CardItem.KIND_HOME;
        c.slug = v.source;
        String name = v.sourceName != null && !v.sourceName.isEmpty() ? v.sourceName : null;
        c.sourceName = name != null ? name : com.lateshow.app.util.SourceCache.nameOf(v.source);
        c.vodId = v.vodId;
        c.title = v.vodName;
        c.pic = v.vodPic;
        c.remark = v.vodRemarks;
        c.timeStr = TimeUtil.stamp(v.vodTime);
        c.fav = PrefStore.get().isFav(c.slug, c.vodId);
        return c;
    }

    public static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
