package com.lateshow.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private TextView emptyView;
    private EditText inputSearch;
    private RecyclerView chips, grid;
    // 顶栏搜索展开/收起（搜索输入默认隐藏，点搜索按钮从右向左滑出）
    private View searchBox, btnSearch, btnSearchClose;
    private boolean searchExpanded = false;
    private ChipAdapter chipAdapter;
    private VideoAdapter videoAdapter;

    private final List<Models.SyncSource> sources = new ArrayList<>();
    /** 下一次 refreshSources 必须穿透缓存（未成年模式切换等口径变化后使用，只用一次） */
    private boolean forceSources = false;
    private String curSlug = "";      // 空 = 全部源站
    private String curName = "全部源站";
    private String curCategory = null; // null = 全部
    private String keyword = "";

    /** 首页视图还没建好时收到「详情页标签快捷搜索」：先存下，onViewCreated 里补上 */
    private String pendingTagKeyword, pendingTagSlug, pendingTagName;

    /**
     * 正在回填标签词：afterTextChanged 默认把「文本变化」当作用户输入、
     * 回到 wd 全文模式；标签跳转要先立 tag 模式再改文本，用这个标记跳过清除。
     */
    private boolean tagJumpInProgress;

    /** 列表每页条数：翻页终止条件要拿它和本页实际返回条数比 */
    private static final int PAGE_SIZE = 18;

    private int page = 1;
    /** 到底标记：VodSync v1.10.0 起全站列表 total/pagecount 是估值，不再用它判断翻页结束 */
    private boolean noMore = false;
    private boolean loading = false;
    private boolean catFailed = false; // 分类接口失败时用列表数据兜底
    private List<Models.VideoSummary> lastRawList = new ArrayList<>();

    /** 搜索防抖：连续输入只在停顿 300ms 后发一次请求（审计 A-09） */
    private static final long SEARCH_DEBOUNCE_MS = 300L;
    private final android.os.Handler searchHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSearch;
    private int searchSeq = 0;
    /** 列表代次：只有最新一次筛选的响应才能改 UI 与 loading（审计 A-09） */
    private int listGeneration = 0;
    /** 源站 / 全部分类 共用的玻璃弹层（页面内浮层，真实采样背后海报） */
    private View menuOverlay, menuScrim;
    private com.lateshow.app.ui.widget.LiquidGlassPanel menuPanel;
    private androidx.activity.OnBackPressedCallback menuBack;
    /** 弹层开关代次：防止关闭动画的收尾把刚重开的面板又藏掉 */
    private int menuSeq = 0;
    /** 代码触发 setText 时抑制防抖重载，避免「切数据源」被重载两次（A-27） */
    private boolean suppressSearchReload = false;

    /** 搜索历史每页条数（默认展示前 10 条，翻页查看更多） */
    private static final int HIST_PAGE_SIZE = 10;
    private LinearLayout histPanel, histBody, histPager;
    private TextView histCount, histEmpty, histPage, histPrev, histNext, btnClearHist;
    private int histPageNo = 0;

    /** 滚动隐显状态：true = 顶栏上滑隐藏中（底导航由 MainActivity 同步处理） */
    private boolean chromeHidden;
    /** 分类横滑侧按钮状态：true = 源站/搜索按钮已滑出、chips 边距已扩展 */
    private boolean chipsBtnHidden;
    /** 顶部功能玻璃胶囊（setupTopBar 装配，滚动隐显用） */
    private com.lateshow.app.ui.widget.LiquidGlassBar topbar;

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
        btnSource = v.findViewById(R.id.btnSource);
        btnSourceIcon = v.findViewById(R.id.btnSourceIcon);
        inputSearch = v.findViewById(R.id.inputSearch);
        // M2：后端 wd / tag 都是 max_length=64，超长会 422 并在界面上糊一段英文错误。
        // 输入层直接挡住，用户不会先打满再被打回。
        if (inputSearch != null) {
            inputSearch.setFilters(new InputFilter[]{
                    new InputFilter.LengthFilter(com.lateshow.app.api.SyncCatalog.MAX_QUERY_LEN)});
        }
        setupSearchHistory(v);
        setupSkeleton(v);
        chips = v.findViewById(R.id.chips);
        grid = v.findViewById(R.id.grid);
        emptyView = v.findViewById(R.id.emptyView);

        curSlug = PrefStore.get().currentSourceSlug();
        curName = PrefStore.get().currentSourceName();
        beginSourceProbe(); // 初始呼吸态，探测出结果后定格橙/灰

        chipAdapter = new ChipAdapter(c -> {
            // 分类芯片属于弹层外部区域：点选分类即视为「操作了弹层以外」，
            // 先收起当前弹层（源站/分类都适用），再切分类
            if (menuOverlay != null && menuOverlay.getVisibility() == View.VISIBLE) {
                dismissMenuOverlay();
            }
            curCategory = c == null ? null : c.typeName;
            com.lateshow.app.util.UiAnim.fadeUp(grid, 8);   // 换分类＝整批内容换掉，给一次淡入
            reload();
        });
        chipAdapter.setOnLongPress(this::showAllCategoriesDialog);
        chips.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
        chips.setAdapter(chipAdapter);
        chips.setHasFixedSize(true);
        // 横滑分类时：源站按钮向左滑出、搜索按钮向右滑出（chips 边距同步扩展让出空间），
        // 滚动结束一起平滑回位 —— 让分类文字有更多展示空间
        chips.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dx != 0) setChipSideButtonsHidden(true);
            }

            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) setChipSideButtonsHidden(false);
            }
        });
        setupMenuOverlay(v);
        setupTopBar(v);

        videoAdapter = new VideoAdapter(new VideoAdapter.OnClick() {
            @Override public void onCard(CardItem item, View source) { openDetail(item, source); }
            @Override public void onDelete(CardItem item) { /* 首页卡片无删除 */ }
        });
        GridLayoutManager glm = new GridLayoutManager(getContext(), 3);
        grid.setLayoutManager(glm);
        grid.setAdapter(videoAdapter);
        grid.setHasFixedSize(true); // 条目尺寸固定，插入新页不触发整表重测量
        grid.setItemAnimator(null); // 关闭条目动画：默认动画器的浮点平移会导致减速阶段文字亚像素抖动

        // 无限加载 + 回到顶部按钮显隐（FAB 图标由布局 android:src 指定）
        com.google.android.material.floatingactionbutton.FloatingActionButton fabTop =
                v.findViewById(R.id.fabTop);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // 滚动即隐藏顶部功能胶囊与底部导航胶囊，滚动结束回位（见 onScrollStateChanged）
                if (dy != 0) setChromeHidden(true);
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm != null) {
                    boolean show = lm.findFirstVisibleItemPosition() > 8;
                    fabTop.setVisibility(show ? View.VISIBLE : View.GONE);
                }
                // 终止条件按「本页不足一页」而不是 page >= pagecount：
                // 服务端无筛选时 total 是 SUM(sources.total_videos) 估值，pagecount 可能多算 1~2 页
                if (loading || noMore) return;
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                int total = lm.getItemCount();
                if (last >= total - 6) loadPage(page + 1);
            }

            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // 图标已全部静态化，滚动时不再需要暂停/恢复动画
                if (newState != RecyclerView.SCROLL_STATE_IDLE) hideHistory();
                else setChromeHidden(false);   // 滚动结束：顶栏下滑回来 / 导航胶囊上滑回来
            }
        });
        fabTop.setOnClickListener(vv -> {
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
                // 普通输入 = 搜索框检索，一律回到 wd= 全文模式（后端约定）；
                // 标签跳转会在回填文本前把 tagJumpInProgress 置位，跳过这里的清除
                if (!tagJumpInProgress) com.lateshow.app.api.SearchIntent.clear();
                if (suppressSearchReload) return;   // 代码回填不再触发一次重载（A-27）
                scheduleReload();
            }
        });

        // 详情页标签跳转可能发生在首页视图创建之前：这里补上关键词与源站，
        // 下面 refreshSources()/reload() 就会直接按该条件加载，不会多发一次请求
        boolean tagPending = pendingTagKeyword != null;
        if (tagPending) {
            curSlug = pendingTagSlug != null ? pendingTagSlug : "";
            curName = pendingTagName != null ? pendingTagName : "全部源站";
            curCategory = null;
            keyword = com.lateshow.app.api.SyncCatalog.clamp(pendingTagKeyword);
            // 先立标签模式再回填文本：afterTextChanged 见到 tagJumpInProgress 就不会清掉它
            tagJumpInProgress = true;
            com.lateshow.app.api.SearchIntent.setTag(keyword);
            inputSearch.setText(keyword);   // 触发 afterTextChanged → 防抖 reload
            tagJumpInProgress = false;
            caretToEnd();
            inputSearch.clearFocus();
            pendingTagKeyword = null;
        }

        refreshSources();
        if (!tagPending) reload();
    }

    /**
     * 详情页点标签 → 快速搜索：切到该影片所在源站，把标签值当关键词检索。
     * 复用首页的搜索框 / 分类芯片 / 海报墙，不新建页面；关键词同时记入搜索历史。
     */
    public void searchFromTag(String word, String slug, String sourceName) {
        final String k = com.lateshow.app.api.SyncCatalog.clamp(word);
        if (k.isEmpty()) return;
        final String s = slug == null ? "" : slug;
        final String n = sourceName == null || sourceName.isEmpty()
                ? (s.isEmpty() ? "全部源站" : s) : sourceName;
        curSlug = s;
        curName = n;
        curCategory = null;
        PrefStore.get().setCurrentSource(s, n);
        PrefStore.get().addSearchHistory(k);
        if (inputSearch == null) {
            pendingTagKeyword = k;
            pendingTagSlug = s;
            pendingTagName = n;
            return;
        }
        hideHistory();
        hideKeyboard();
        keyword = k;
        // 标签胶囊点击 = 精确标签检索（后端约定：走 tag=，索引里有就必命中）。
        // 先立模式再回填文本，避免 afterTextChanged 把它当普通输入清掉
        tagJumpInProgress = true;
        com.lateshow.app.api.SearchIntent.setTag(k);
        if (!k.equals(inputSearch.getText().toString().trim())) {
            inputSearch.setText(k);          // afterTextChanged → 防抖 reload
        } else {
            scheduleReload();                // 同关键词也要重新按新源站加载
        }
        tagJumpInProgress = false;
        caretToEnd();
        toastShort("在「" + n + "」中搜索：" + k);
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

    // ---------- 顶栏：液态玻璃 + 搜索展开/收起 + 分类长按弹层 ----------

    /** 顶栏玻璃参数与底部导航一致；采样源 = 网格（内容从顶栏后面穿过） */
    private void setupTopBar(View v) {
        topbar = v.findViewById(R.id.topBar);
        if (topbar != null) {
            topbar.setBackdropSource(grid);
            float d = getResources().getDisplayMetrics().density;
            topbar.setGlassCornerRadius(24f * d);
            topbar.setRefraction(18f * d, 42f * d);   // 折射带 / 折射量
            topbar.setBlurSigma(36f * d);             // 磨砂 4 倍强度（tier1 据此把降采样加深到 1/40）
            topbar.setFrost(1.04f);                   // 白雾（原 0.26 × 4，渲染端钳到 1）
            topbar.setHighlight(0.30f);
            topbar.setDispersion(0.8f);
        }
        applyTopInsets(v, topbar);
        // 统一交互规则：顶栏虽在弹层之上（保持芯片可点），但点它的空白/边距仍算「弹层外部」，
        // 命中即收起。子控件（芯片、源站按钮、搜索按钮）会先消费触摸，各自由自己的回调收尾
        if (topbar != null) {
            topbar.setOnTouchListener((vv, e) -> {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                        && menuOverlay != null && menuOverlay.getVisibility() == View.VISIBLE) {
                    dismissMenuOverlay();
                }
                return false;   // 不消费：让子控件继续正常响应
            });
        }
        searchBox = v.findViewById(R.id.searchBox);
        btnSearch = v.findViewById(R.id.btnSearch);
        btnSearchClose = v.findViewById(R.id.btnSearchClose);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(vv -> {
                if (searchExpanded) submitSearch(inputSearch.getText().toString());
                else expandSearch();
            });
        }
        if (btnSearchClose != null) {
            btnSearchClose.setOnClickListener(vv -> collapseSearch(true));
        }
        if (chips != null) {
            // 长按分类区 = 弹出全部分类列表快速选择
            chips.setOnLongClickListener(vv -> {
                showAllCategoriesDialog();
                return true;
            });
        }
    }

    /**
     * 状态栏让位：MainActivity 不再给内容区补顶部 padding（海报要顶到屏幕最上沿），
     * 首页自己把顶栏/网格/骨架屏/历史面板整体下移状态栏高度。
     * 布局里的 4/64/58dp 是相对状态栏下沿的原设计值，这里叠加 bars.top。
     */
    private void applyTopInsets(View root, com.lateshow.app.ui.widget.LiquidGlassBar topbar) {
        View gridV = root.findViewById(R.id.grid);
        View skelV = root.findViewById(R.id.skeletonBox);
        View histV = root.findViewById(R.id.histPanel);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            float d = getResources().getDisplayMetrics().density;
            if (topbar != null) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) topbar.getLayoutParams();
                lp.topMargin = (int) (bars.top + 4f * d);
                topbar.setLayoutParams(lp);
            }
            if (gridV != null) gridV.setPadding(0, (int) (bars.top + 64f * d), 0, gridV.getPaddingBottom());
            if (skelV != null) {
                ((android.widget.FrameLayout.LayoutParams) skelV.getLayoutParams()).topMargin =
                        (int) (bars.top + 64f * d);
            }
            if (histV != null) {
                ((android.widget.FrameLayout.LayoutParams) histV.getLayoutParams()).topMargin =
                        (int) (bars.top + 58f * d);
            }
            // 玻璃弹层：与源站按钮左缘对齐（栏边距 10 + 按钮边距 5 - 4dp 微调），
            // 顶边在顶栏下方 6dp 处（顶栏底 = 状态栏 + 4 + 48）
            if (menuPanel != null) {
                android.widget.FrameLayout.LayoutParams mlp =
                        (android.widget.FrameLayout.LayoutParams) menuPanel.getLayoutParams();
                mlp.topMargin = (int) (bars.top + 58f * d);
                mlp.leftMargin = (int) (11f * d);
                menuPanel.setLayoutParams(mlp);
            }
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }

    /** 玻璃弹层：绑定视图 / 尺寸 / 玻璃参数 / 返回键 */
    private void setupMenuOverlay(View root) {
        menuOverlay = root.findViewById(R.id.menuOverlay);
        menuScrim = root.findViewById(R.id.menuScrim);
        menuPanel = root.findViewById(R.id.menuPanel);
        if (menuOverlay == null || menuPanel == null) return;
        if (menuScrim != null) menuScrim.setOnClickListener(vv -> dismissMenuOverlay());

        // 宽度 = 原源站列表 250dp 的 3/4（源站、分类两个列表完全一致）
        float d = getResources().getDisplayMetrics().density;
        int w = Math.round(MENU_BASE_W_DP * MENU_W_RATIO * d);
        ViewGroup.LayoutParams lp = menuPanel.getLayoutParams();
        lp.width = w;
        menuPanel.setLayoutParams(lp);

        menuPanel.setGlassCornerRadius(14f * d);
        menuPanel.setRefraction(18f * d, 30f * d);
        menuPanel.setBlurSigma(32f * d);   // 磨砂 4 倍强度
        menuPanel.setFrost(1.36f);         // 白雾（原 0.34 × 4，渲染端钳到 1）
        menuPanel.setHighlight(0.28f);
        menuPanel.setDispersion(0.6f);
        menuPanel.setBackdropSource(grid);

        // 返回键：弹层打开时先关弹层（与 PopupWindow 的返回行为一致）
        menuBack = new androidx.activity.OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                dismissMenuOverlay();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), menuBack);
    }

    /** 内容挂进玻璃弹层并显示（顶部/左侧位置见 applyTopInsets） */
    private void showMenuOverlay(View content, boolean animate) {
        if (menuOverlay == null || menuPanel == null) return;
        menuSeq++;
        menuPanel.animate().cancel();
        menuPanel.removeAllViews();
        // 保留内容自带的精确 LayoutParams（wrapMenuScroll 已按行数给出高度）。
        // 不能用 WRAP_CONTENT 覆盖挂载：那样 clampMenuHeight 会把「未定高」当成
        // 「拉满可用高度」处理，弹层变成固定全高，内容少时下方一大片空白。
        menuPanel.addView(content);
        menuOverlay.setVisibility(View.VISIBLE);
        menuOverlay.setAlpha(1f);
        if (menuBack != null) menuBack.setEnabled(true);
        if (animate) {
            menuPanel.setAlpha(0f);
            menuPanel.setTranslationY(-dp(8));
            menuPanel.animate().alpha(1f).translationY(0f).setDuration(150L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        } else {
            menuPanel.setAlpha(1f);
            menuPanel.setTranslationY(0f);
        }
        // 浮层刚可见时位置/背景才有意义：先按导航栏真实位置收敛高度，再强制重采背后海报
        menuPanel.post(() -> {
            clampMenuHeight(content);
            menuPanel.refreshGlass();
        });
    }

    /** 收起源站 / 分类弹层 */
    private void dismissMenuOverlay() {
        if (menuOverlay == null || menuOverlay.getVisibility() != View.VISIBLE) return;
        final int seq = ++menuSeq;
        if (menuBack != null) menuBack.setEnabled(false);
        menuPanel.animate().cancel();
        menuPanel.animate().alpha(0f).translationY(-dp(6)).setDuration(100L)
                .withEndAction(() -> {
                    if (seq != menuSeq || menuOverlay == null) return;  // 期间又被打开/关闭过
                    menuOverlay.setVisibility(View.GONE);
                    menuOverlay.setAlpha(1f);
                    menuPanel.setAlpha(1f);
                    menuPanel.setTranslationY(0f);
                    menuPanel.removeAllViews();
                }).start();
    }

    /** 搜索输入从右向左滑出，盖住分类区；弹出键盘并展示搜索历史 */
    private void expandSearch() {
        if (searchExpanded || searchBox == null) return;
        if (menuOverlay != null && menuOverlay.getVisibility() == View.VISIBLE) dismissSourceMenu();
        searchExpanded = true;
        chips.setVisibility(View.GONE);
        searchBox.setVisibility(View.VISIBLE);
        float d = getResources().getDisplayMetrics().density;
        searchBox.setTranslationX(260f * d);
        searchBox.animate().translationX(0f).setDuration(260L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        if (inputSearch != null) {
            inputSearch.requestFocus();
            inputSearch.post(() -> {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) requireContext()
                                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(inputSearch, 0);
            });
        }
        showHistory();
    }

    /**
     * 收起搜索输入，恢复分类区。
     * @param clearKeyword true = 清空关键词恢复全部列表（✕）；false = 保留当前关键词（提交/焦点移出）
     */
    private void collapseSearch(boolean clearKeyword) {
        if (!searchExpanded || searchBox == null) return;
        searchExpanded = false;
        hideHistory();
        hideKeyboard();
        if (inputSearch != null) {
            inputSearch.clearFocus();
            if (clearKeyword) inputSearch.setText("");   // afterTextChanged → 防抖 reload 全部
        }
        float d = getResources().getDisplayMetrics().density;
        searchBox.animate().translationX(260f * d).setDuration(220L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    searchBox.setVisibility(View.GONE);
                    chips.setVisibility(View.VISIBLE);
                    setChipSideButtonsHidden(false);   // 恢复分类区：两侧按钮确保回位
                }).start();
    }

    /** 长按分类区：全部分类列表（与源站列表同一套玻璃弹层 / 位置 / 宽度 / 交互） */
    private void showAllCategoriesDialog() {
        if (!isAdded() || chipAdapter == null) return;
        java.util.List<Models.Category> items = chipAdapter.getItems();
        if (items.isEmpty()) return;
        // 源站弹层已打开时，长按分类 = 把弹层内容切换成分类列表（保持打开），
        // 而不是先关掉源站弹层再 return —— 那样长按看起来只是「关闭」了什么也没弹出
        boolean switching = menuOverlay != null && menuOverlay.getVisibility() == View.VISIBLE;
        int sel = chipAdapter.getSelected();
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));
        for (int i = 0; i < items.size(); i++) {
            Models.Category c = items.get(i);
            String name = (c == null || c.typeName == null || c.typeName.isEmpty())
                    ? "全部" : c.typeName;
            addCategoryRow(box, name, i == sel, i);
        }
        // 切换内容不做入场动画（弹层本身已可见），避免「闪一下重新弹」
        showMenuOverlay(wrapMenuScroll(box, items.size()), !switching);
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
            else { hideHistory(); collapseSearch(false); }   // 焦点移出 = 收起（关键词保留）
        });
        // 键盘「搜索」键：记录关键词并检索，随后收起输入框
        inputSearch.setOnEditorActionListener((vv, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                submitSearch(inputSearch.getText().toString());
                collapseSearch(false);
                return true;
            }
            return false;
        });
        // 搜索图标点击由 btnSearch 统一处理（未展开=滑出输入框，已展开=提交），这里不再单独绑定

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
        String k = clampQuery(raw);
        if (k.isEmpty()) { // 空关键词不记录，仅展示历史
            showHistory();
            return;
        }
        PrefStore.get().addSearchHistory(k);
        hideKeyboard();
        hideHistory();
        // 搜索框输入一律走 wd= 全文检索（后端约定）：先退出标签模式，
        // 否则关键词与上次点的标签相同时会继续按 tag= 查
        com.lateshow.app.api.SearchIntent.clear();
        if (!k.equals(inputSearch.getText().toString().trim())) {
            inputSearch.setText(k); // 触发 afterTextChanged → reload
            caretToEnd();
        } else {
            keyword = k;
            reload();
        }
    }

    /** 点击历史 tag：回填关键词并检索（同时把该记录置顶） */
    private void applyHistory(String k0) {
        String k = clampQuery(k0);
        PrefStore.get().addSearchHistory(k);
        histPageNo = 0;
        hideKeyboard();
        hideHistory();
        com.lateshow.app.api.SearchIntent.clear();   // 历史词 = 全文检索，同上
        if (k.equals(inputSearch.getText().toString().trim())) {
            keyword = k;
            reload();
        } else {
            inputSearch.setText(k); // 触发 afterTextChanged → reload
            caretToEnd();
        }
    }

    /**
     * 检索词归一（M2）：截断到后端 {@code max_length=64} 并给出可视提示。
     * 输入框已有 {@link InputFilter.LengthFilter} 挡着，这里覆盖「历史记录回填」
     * 「详情页标签跳转」这类绕过键盘的路径。
     */
    private String clampQuery(String raw) {
        String t = raw == null ? "" : raw.trim();
        String c = com.lateshow.app.api.SyncCatalog.clamp(t);
        if (c.length() < t.length()) toastShort("关键词最长 64 字，已截断后检索");
        return c;
    }

    /** 光标移到末尾：按实际文本长度算，避免文本被过滤器截断后越界 */
    private void caretToEnd() {
        if (inputSearch == null) return;
        int n = inputSearch.getText() == null ? 0 : inputSearch.getText().length();
        inputSearch.setSelection(n);
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

    /**
     * 滚动隐显：列表滚动时顶部功能胶囊向上滑出屏幕、底部导航胶囊向下滑出屏幕；
     * 滚动结束（SCROLL_STATE_IDLE）后各自滑回原位。重复调用同方向幂等。
     * 顶部隐藏位移 = 顶栏顶边 + 高度（移出父容器上边界后被裁剪，状态栏区域也不残留）。
     */
    private void setChromeHidden(boolean hide) {
        if (chromeHidden == hide) return;
        chromeHidden = hide;
        if (topbar != null && topbar.getWidth() > 0) {
            float target = hide ? -(topbar.getTop() + topbar.getHeight()) : 0f;
            topbar.animate().translationY(target)
                    .setDuration(hide ? 190 : 240)
                    .setInterpolator(hide
                            ? new android.view.animation.AccelerateInterpolator(1.6f)
                            : new android.view.animation.DecelerateInterpolator(1.8f))
                    .start();
        }
        if (getActivity() instanceof com.lateshow.app.MainActivity) {
            ((com.lateshow.app.MainActivity) getActivity()).animateTabbar(hide);
        }
    }

    /**
     * 分类横滑隐显两侧按钮：滑动时源站按钮向左滑出、搜索按钮向右滑出，
     * 目标位置额外加 28dp 缓冲 —— 加上胶囊自身 10dp 外边距后按钮已完全离开屏幕，
     * 不依赖胶囊裁剪也绝无残留；分类区边距同步收窄（正边距方案，见
     * {@link #animateChipsMargins}）。滚动结束一起平滑回位。
     * 搜索框展开（chips 被盖住）时不介入。
     */
    private void setChipSideButtonsHidden(boolean hide) {
        if (chips == null || btnSource == null || btnSearch == null || topbar == null) return;
        if (searchBox != null && searchBox.getVisibility() == View.VISIBLE) return;
        if (chipsBtnHidden == hide) return;
        chipsBtnHidden = hide;
        if (chips.getWidth() <= 0 || topbar.getWidth() <= 0) return;

        android.view.animation.Interpolator itp = hide
                ? new android.view.animation.AccelerateInterpolator(1.6f)
                : new android.view.animation.DecelerateInterpolator(1.8f);
        final int overshoot = dp(28);   // 滑出缓冲：多移一段距离，确保完全离开可视区
        if (btnSource.getWidth() > 0) {
            // 源站按钮整体移出父栏左边界再多退一段（LiquidGlassBar 按圆角轮廓裁剪）
            btnSource.animate()
                    .translationX(hide ? -(btnSource.getLeft() + btnSource.getWidth() + overshoot) : 0f)
                    .setDuration(hide ? 190 : 240).setInterpolator(itp).start();
        }
        if (btnSearch.getWidth() > 0) {
            // 搜索按钮整体移出父栏右边界再多退一段
            btnSearch.animate()
                    .translationX(hide ? topbar.getWidth() - btnSearch.getLeft() + overshoot : 0f)
                    .setDuration(hide ? 190 : 240).setInterpolator(itp).start();
        }
        animateChipsMargins(hide, itp);
    }

    /**
     * 分类区边距随按钮滑出同步扩展：静止时 51dp（按钮 + 间隙），横滑时收窄到 4dp，
     * 与按钮滑出同节奏；全程正边距，RecyclerView 本体始终在胶囊内部，
     * 不会像旧的负 margin 方案那样把内容推出胶囊圆角。
     * 动画走 margin + requestLayout（顶栏只有一行，重排代价很小；滚动位置不跳）。
     */
    private void animateChipsMargins(boolean extend, android.view.animation.Interpolator itp) {
        if (chips == null) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) chips.getLayoutParams();
        final int fromL = lp.leftMargin, fromR = lp.rightMargin;
        final int to = extend ? dp(4) : dp(51);
        if (fromL == to && fromR == to) return;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(extend ? 190 : 240);
        va.setInterpolator(itp);
        va.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) chips.getLayoutParams();
            p.leftMargin = Math.round(fromL + (to - fromL) * t);
            p.rightMargin = Math.round(fromR + (to - fromR) * t);
            chips.setLayoutParams(p);
        });
        va.start();
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
        dismissSourceMenu();   // 与原 PopupWindow 一致：失焦即收
        com.lateshow.app.api.NetMonitor.removeListener(onNetChanged);
        stopRingAnim();
        super.onPause();
    }

    /**
     * 切 Tab（底部导航在 Fragment 容器之外，属于「弹层以外的区域」）：
     * 页面被隐藏时立即收起弹层，避免切回来还挂着上一次的弹层
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            dismissSourceMenu();
        } else {
            resetChrome();   // 切回首页：无论离开时滚动到什么状态，顶栏/底导航都回位
            // Tab 切换走 show()/hide()，不会触发 onResume：设置页里改过数据源 / 未成年模式时，
            // 脏标记必须在这里消费掉，否则首页会一直显示切换前口径的源站与列表
            if (com.lateshow.app.api.Catalogs.consumeDirty()) onDataSourceChanged();
        }
    }

    /** 顶栏/底导航强制回位（切回首页或详情页收起露出首页时由宿主触发） */
    public void resetChrome() {
        chromeHidden = false;
        if (topbar != null) topbar.setTranslationY(0f);
        if (chipsBtnHidden) setChipSideButtonsHidden(false);   // 滑出中的侧按钮一并回位
    }

    @Override
    public void onDestroyView() {
        stopRingAnim();
        dismissSourceMenu();
        searchHandler.removeCallbacksAndMessages(null);
        skelHandler.removeCallbacksAndMessages(null);
        com.lateshow.app.api.Catalogs.get().cancelVideos();
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
                com.lateshow.app.util.Icons.tint(btnSourceIcon, c);
            });
            ringAnim.start();
        } else {
            boolean ok = state == RING_OK;
            btnSource.setBackgroundResource(ok ? R.drawable.bg_ring_on : R.drawable.bg_ring_off);
            com.lateshow.app.util.Icons.tint(btnSourceIcon,
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
        resetChrome();   // 从全屏播放等页面回来：顶栏/底导航确保回位显示
        com.lateshow.app.api.NetMonitor.addListener(onNetChanged);
        pingHealth(); // 回前台必重测：列表可能全部命中缓存，没有网络请求可依赖
        // 设置页切换过数据源：重新拉源站与列表
        if (com.lateshow.app.api.Catalogs.consumeDirty()) onDataSourceChanged();
    }

    /** 数据源模式切换后调用：重置筛选条件并重载（由设置页 / MainActivity 触发） */
    public void onDataSourceChanged() {
        // 审计 A-27：这里已直接重载，顺手消费脏标记，避免下一次 onResume 再重载一遍
        com.lateshow.app.api.Catalogs.consumeDirty();
        ++listGeneration;
        com.lateshow.app.api.Catalogs.get().cancelVideos();
        loading = false;
        curSlug = PrefStore.get().currentSourceSlug();
        curName = PrefStore.get().currentSourceName();
        curCategory = null;
        keyword = "";
        com.lateshow.app.api.SearchIntent.clear();
        suppressSearchReload = true;
        if (inputSearch != null) inputSearch.setText("");
        suppressSearchReload = false;
        if (getView() == null) return;
        final int cyc = beginSourceProbe();
        refreshSources();
        reload();
        pingHealth(cyc); // 三者同一轮次：源站/首屏/探测谁先成功都点亮圆环
    }

    /**
     * 未成年模式切换（设置页触发）：开↔关走同一条路径，回首页必然重拉源站与列表。
     *
     * <p>旧实现只清了接口缓存，但 Tab 切换走 show()/hide() 不触发 onResume，首页的
     * {@code sources} 内存列表与海报墙谁都不重载——于是「关→开」看起来毫无变化，
     * 而「开→关」偶尔因为缓存到期碰巧发了一次请求，两条路径表现不一致。
     * 现在统一由这里把状态改动作废到底：清源站名映射 → 必要时重置选中源站 → 强制走网络。
     */
    public void onMinorModeChanged() {
        com.lateshow.app.util.SourceCache.clear();
        if (PrefStore.get().minorMode()
                && PrefStore.get().isAdultSource(PrefStore.get().currentSourceSlug())) {
            // 当前选中的源站刚被判定为 18+：退回「全部源站」，
            // 否则后续列表请求会带着一个已被服务端剔除的 source，稳定拿到空结果
            PrefStore.get().setCurrentSource("", "全部源站");
        }
        forceSources = true;      // 下一轮 refreshSources 必须穿透缓存
        onDataSourceChanged();    // 内部已消费脏标记，并重置筛选 + 重拉源站与首屏
    }

    /** 源站列表缓存 key：带模式前缀，两种模式不互相命中 */
    private String ckSources() {
        return CK_SOURCES + "|" + com.lateshow.app.api.Catalogs.cacheTag();
    }

    /** 源站列表：菜单数据 + 元信息——优先用缓存，过期再拉（数据源由 Catalog 决定） */
    private void refreshSources() {
        final String ck = ckSources();
        final int cyc = ringCycle; // 结果归属发起时的轮次，过期即丢弃
        // 口径刚变过（未成年模式开关）：缓存一律不可信，直接走网络
        final boolean bypass = forceSources;
        forceSources = false;
        if (!bypass) {
            Models.SyncSourcesResp cached = ApiCache.get(ck, Models.SyncSourcesResp.class, TTL_LIST);
            if (cached != null && cached.list != null && !cached.list.isEmpty()) {
                applySources(cached, true);
                return; // 缓存新鲜，不再发请求
            }
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
                        toastShort("需要 API Token · 请在设置页填写");
                        return;
                    }
                    // 网络失败：退回本地缓存的源站列表，菜单仍可用
                    Models.SyncSourcesResp stale = ApiCache.getStale(ck, Models.SyncSourcesResp.class);
                    if (stale != null && stale.list != null && !stale.list.isEmpty()) {
                        applySources(stale, true);
                        return;
                    }
                    toastShort(nasDownHint("源站加载失败：" + m));
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

    /**
     * 后端是否具备 18+ 标记能力（VodSync v1.15.0+）。
     * 版本未知（healthz 还没回来）时按「有」处理：标记表在拿到真实响应后自然会被纠正；
     * 只有明确是老版本才跳过，避免一次旧响应把已有标记清空。
     */
    private static boolean backendLabelsAdult() {
        String v = com.lateshow.app.api.ServerCaps.version();
        return v.isEmpty() || com.lateshow.app.api.ServerCaps.compare(v, "1.15.0") >= 0;
    }

    /** 渲染源站列表与统计信息；fromCache=true 表示数据来自本地缓存 */
    private void applySources(Models.SyncSourcesResp r, boolean fromCache) {
        // 同步刷新「18+ 源站标记表」：本地播放历史 / 收藏的过滤依据（见 PrefStore#updateAdultSources）。
        // 两道保护：① 直连模式的源站清单没有 is_adult 概念，不参与；
        // ② 1.15.0 之前的老后端根本不发 is_adult，不能用它把已有标记抹成空。
        if (!com.lateshow.app.api.Catalogs.isStatic() && backendLabelsAdult()) {
            PrefStore.get().updateAdultSources(r != null ? r.list : null,
                    !PrefStore.get().minorMode());
        }
        sources.clear();
        if (r.list != null) {
            for (Models.SyncSource s : r.list) {
                if (s.enabled) sources.add(s);
            }
        }
        com.lateshow.app.util.SourceCache.putSync(sources);
        // 兜底：选中源站在当前口径下已不可用（刚开启未成年模式，或后端已下线该源站）→ 回到「全部源站」
        if (!curSlug.isEmpty() && PrefStore.get().isAdultSource(curSlug)
                && PrefStore.get().minorMode()) {
            curSlug = "";
            curName = "全部源站";
            PrefStore.get().setCurrentSource("", "全部源站");
            final View v = getView();
            if (v != null) v.post(this::reload);
        }
        // 圆环状态由 pingHealth / 网络请求的 reportSourceProbe 决定，这里不再直接改
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

    /** 弹层基准宽度：原源站列表宽 250dp；现按 3/4 收窄（源站/分类共用同一数值） */
    private static final float MENU_BASE_W_DP = 250f;
    private static final float MENU_W_RATIO = 0.75f;

    private void showSourceMenu() {
        if (!isAdded() || getView() == null || menuPanel == null) return;
        if (menuOverlay.getVisibility() == View.VISIBLE) {
            dismissMenuOverlay();   // 再点一次源站按钮 = 关闭
            return;
        }

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        box.setPadding(0, pad, 0, pad);

        int rows = 1;
        if (sources.isEmpty()) {
            // 源站列表为空：显示「全部源站」+「重新加载」兜底行，保证点击始终有反馈
            addSourceRow(box, "全部源站", "", curSlug.isEmpty(), "");
            addSourceRow(box, "重新加载源站列表", "↻", false, null);
            rows++;
        } else {
            // 「全部源站」固定项
            addSourceRow(box, "全部源站", "", curSlug.isEmpty(), "");

            // 按影片数降序（数据多的源站排前面）
            List<Models.SyncSource> sorted = new ArrayList<>(sources);
            java.util.Collections.sort(sorted, (a, b) -> Integer.compare(b.totalVideos, a.totalVideos));
            for (Models.SyncSource s : sorted) {
                addSourceRow(box, s.name, TimeUtil.compact(s.totalVideos),
                        s.slug != null && s.slug.equals(curSlug), s.slug);
                rows++;
            }
        }
        showMenuOverlay(wrapMenuScroll(box, rows), true);
    }

    /** 立即收起弹层（不做动画）：onPause / onDestroyView 用，避免视图销毁后动画还在跑 */
    private void dismissSourceMenu() {
        menuSeq++;
        if (menuBack != null) menuBack.setEnabled(false);
        if (menuPanel != null) {
            menuPanel.animate().cancel();
            menuPanel.removeAllViews();
            menuPanel.setAlpha(1f);
            menuPanel.setTranslationY(0f);
        }
        if (menuOverlay != null) {
            menuOverlay.setVisibility(View.GONE);
            menuOverlay.setAlpha(1f);
        }
    }

    private boolean pendingMenu = false;

    /** 弹层内容包一层限高的滚动容器（源站/分类共用） */
    private View wrapMenuScroll(LinearLayout box, int rows) {
        androidx.core.widget.NestedScrollView sv =
                new androidx.core.widget.NestedScrollView(getContext());
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int want = dp(12) + dp(38) * rows;
        sv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(want, menuAvailHeight())));
        return sv;
    }

    /**
     * 弹层可用高度。
     * <p>注意：<b>不能用 DisplayMetrics.heightPixels</b>——那是整屏物理高度，包含系统导航条，
     * 而 fragment 根布局的高度已经扣掉了底部系统栏（MainActivity 给 container 补了
     * bars.bottom padding），再加上悬浮导航胶囊 48dp + 底距 8dp + 10dp 间隙，
     * 用屏幕高度算出来的弹层一定会伸到导航栏底下（v1.5.143 仍然遮挡的根因）。
     */
    private int menuAvailHeight() {
        int topUsed = menuTopUsed();
        View root = getView();
        int rootH = root != null ? root.getHeight() : 0;
        if (rootH <= 0) {
            // 兜底：整屏高度减系统导航条（估算 48dp），再减顶部占用与底部导航区
            rootH = getResources().getDisplayMetrics().heightPixels - dp(48);
        }
        int bottomReserve = dp(48 + 8 + 10);   // 导航胶囊 48 + 底距 8 + 间隙 10
        return Math.max(dp(120), rootH - topUsed - bottomReserve);
    }

    /**
     * 弹层高度收敛：自适应内容高度，但永远不超过导航胶囊上方 10dp 的可用上限（上限不增大）。
     *
     * <p>实现为「实测内容高度 与 上限 取小」：内容容器（NestedScrollView）的子 View 在
     * UNSPECIFIED 高度下测量，拿到的是行内容真实高度，不受容器当前高度影响——
     * 行数少时弹层收缩到内容高度，行数多时被上限截断进入滚动。
     * 之前把 WRAP_CONTENT 直接改成上限值，是「固定全高 + 下方空白」的根因。
     */
    private void clampMenuHeight(View content) {
        if (content == null || menuPanel == null) return;
        View tabbar = requireActivity().findViewById(R.id.tabbar);
        if (tabbar == null || !tabbar.isShown()) return;
        int[] tb = new int[2];
        int[] pn = new int[2];
        tabbar.getLocationOnScreen(tb);
        menuPanel.getLocationOnScreen(pn);
        int avail = tb[1] - pn[1] - dp(10);
        if (avail <= dp(120)) return;

        // 实测内容高度 = 内容容器子 View 的真实高度 + 容器自身上下 padding
        int contentH;
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            ViewGroup g = (ViewGroup) content;
            contentH = g.getChildAt(0).getHeight() + g.getPaddingTop() + g.getPaddingBottom();
        } else {
            contentH = content.getHeight();
        }
        if (contentH <= 0) {
            // 首帧布局尚未完成：下一帧再收敛一次（layout 后子 View 高度即可用）
            menuPanel.postDelayed(() -> clampMenuHeight(content), 16);
            return;
        }
        int target = Math.min(contentH, avail);
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        if (lp != null && lp.height != target) {
            lp.height = target;
            content.setLayoutParams(lp);
        }
    }

    /** 弹层顶边已占用的像素（状态栏 + 58dp；弹层还没被 insets 调整过时用布局默认值兜底） */
    private int menuTopUsed() {
        if (menuPanel != null) {
            return ((android.widget.FrameLayout.LayoutParams) menuPanel.getLayoutParams()).topMargin;
        }
        return dp(58);
    }

    /** 全部分类列表的一行：点选 = 选中该分类并收起弹层 */
    private void addCategoryRow(LinearLayout box, String name, boolean sel, int index) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_source_menu, box, false);
        ((TextView) row.findViewById(R.id.name)).setText(name);
        ((TextView) row.findViewById(R.id.count)).setText("");
        row.findViewById(R.id.dot).setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> {
            dismissMenuOverlay();
            if (chipAdapter != null) chipAdapter.selectAt(index);
        });
        box.addView(row);
    }

    private void addSourceRow(LinearLayout box, String name, String count, boolean sel, String slug) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_source_menu, box, false);
        ((TextView) row.findViewById(R.id.name)).setText(name);
        TextView cnt = row.findViewById(R.id.count);
        cnt.setText(count);
        row.findViewById(R.id.dot).setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> {
            dismissMenuOverlay();
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
        final int gen = listGeneration;   // 旧分类响应不能覆盖新源站的芯片
        Models.CategoryResp cached = ApiCache.get(ck, Models.CategoryResp.class, TTL_CAT);
        if (cached != null && cached.list != null && !cached.list.isEmpty()) {
            applyCategories(cached.list);
            return; // 6 小时内分类直接复用缓存，不发请求
        }
        com.lateshow.app.api.Catalogs.get().categories(curSlug,
            new VodSyncApi.ApiCb<Models.CategoryResp>() {
                @Override public void onOk(Models.CategoryResp r) {
                    if (!isAdded() || gen != listGeneration) return;
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
                    if (!isAdded() || gen != listGeneration) return;
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

    /** 搜索防抖：连续输入只保留最后一次，停顿 300ms 才真正发请求（审计 A-09） */
    private void scheduleReload() {
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
        final int seq = ++searchSeq;
        pendingSearch = () -> {
            if (!isAdded() || getView() == null || seq != searchSeq) return;
            reload();
        };
        searchHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void reload() {
        // 新筛选先作废旧请求：NAS 模式真正 cancel Call，直连模式用代次丢弃回调
        ++listGeneration;
        com.lateshow.app.api.Catalogs.get().cancelVideos();
        loading = false;   // 旧请求被作废，其 onOk/onErr 不能再改这个标记
        page = 1;
        noMore = false;   // 换源站 / 分类 / 关键词后重新允许翻页
        videoAdapter.setItems(new ArrayList<>());
        emptyView.setVisibility(View.GONE);
        loadPage(1);
        refreshCategories();
    }

    private void loadPage(int p) {
        loading = true;
        final int reqPage = p;
        final int gen = listGeneration;   // 只认发起时的代次（审计 A-09）
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

        com.lateshow.app.api.Catalogs.get().videos(p, PAGE_SIZE, curSlug, type, kw,
            new VodSyncApi.ApiCb<Models.VideoListResp>() {
                @Override public void onOk(Models.VideoListResp r) {
                    if (!isAdded() || gen != listGeneration) return;   // 过期响应直接丢弃
                    loading = false;
                    // 首屏列表拿到 = 当前源站确实能出数据，作为「畅通」证据点亮圆环
                    if (reqPage == 1) reportSourceProbe(cyc, RING_OK);
                    if (reqPage == 1) ApiCache.put(ck, r); // 只缓存第一页，翻页数据不落盘
                    applyList(r, reqPage, false);
                }
                @Override public void onErr(String m) {
                    if (!isAdded() || gen != listGeneration) return;   // 过期错误不污染新筛选
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

    /** 列表缓存 key：模式 + 源站 + 分类 + 检索模式 + 关键词 + 页码（第 1 页才落盘） */
    private String listCacheKey(int p) {
        // 同一个词可能是「标签检索」也可能是「全文检索」，结果集完全不同，
        // 必须在 key 里区分，否则点标签会直接命中上次全文搜索的缓存（M5）
        String tag = com.lateshow.app.api.SearchIntent.currentTag();
        String mode = tag.isEmpty() ? "wd" : "tag:" + tag;
        return "videos|" + com.lateshow.app.api.Catalogs.cacheTag() + "|" + curSlug
                + "|" + (curCategory == null ? "" : curCategory)
                + "|" + mode
                + "|" + keyword + "|" + p;
    }

    /** 渲染列表响应；fromCache=true 时给出离线提示 */
    private void applyList(Models.VideoListResp r, int reqPage, boolean fromCache) {
        hideSkeleton();
        page = reqPage;
        // 本页不足一页 = 已经到底。r.pagecount 只作参考，不再参与终止判断
        // （全站列表的 total 是估值，pagecount 可能比真实页数多 1~2 页）
        noMore = r.list == null || r.list.size() < PAGE_SIZE;
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
        boolean empty = videoAdapter.getItemCount() == 0;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyView.setText(emptyHint(r));
        if (reqPage == 1) showSearchQuality(r);
        if (fromCache && getContext() != null) {
            android.widget.Toast.makeText(getContext(), "网络不可用，显示本地缓存内容",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 空态文案：分清三种「没有」，别让用户把「这个标签在当前源站没片」当成后端挂了。
     * <ul>
     *   <li>标签检索 · 索引里没这个词（且全文也没兜到）→ 建议换词；</li>
     *   <li>标签检索 · 索引命中了但当前筛选下为空 → 建议切源站/换分类；</li>
     *   <li>普通浏览 / 全文检索 → 原有文案。</li>
     * </ul>
     */
    private String emptyHint(Models.VideoListResp r) {
        String tag = com.lateshow.app.api.SearchIntent.currentTag();
        if (!tag.isEmpty() && r != null && Boolean.FALSE.equals(r.tagFound)) {
            return "标签「" + tag + "」暂未收录\n已经用全文检索找过，换个关键词试试";
        }
        if (!tag.isEmpty()) {
            return "「" + tag + "」在" + curName + "下暂无内容\n试试切回「全部源站」或换个分类";
        }
        if (keyword != null && !keyword.isEmpty()) {
            return "没搜到「" + keyword + "」\n换个关键词，或切回「全部源站」再试";
        }
        return "暂无内容\n换个分类或切换源站重试";
    }

    /**
     * 检索质量提示（M5 + F4）。原先是 metaSync 行内提示，改为一行 Toast：
     * <ul>
     *   <li>标签索引没有该词、已自动改用全文检索 → 说明这次是全文结果；</li>
     *   <li>有源站超时 / 被单源上限截断 → 说明结果不完整，别让用户以为「就这么多」。</li>
     * </ul>
     */
    private void showSearchQuality(Models.VideoListResp r) {
        if (r == null || getContext() == null) return;
        String tag = com.lateshow.app.api.SearchIntent.currentTag();
        boolean searching = keyword != null && !keyword.isEmpty();
        if (r.fullTextFallback && r.total > 0) {
            toastShort("标签「" + (tag.isEmpty() ? keyword : tag) + "」未收录 · 已改用全文检索");
            return;
        }
        if (searching && r.partial()) {
            int failed = r.failedSourceCount();
            toastShort(failed > 0
                    ? "结果可能不完整 · " + failed + " 个源站未响应"
                    : "结果可能不完整 · 部分源站被截断");
        }
    }

    private CardItem toCard(Models.VideoSummary v) {        CardItem c = new CardItem();
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
