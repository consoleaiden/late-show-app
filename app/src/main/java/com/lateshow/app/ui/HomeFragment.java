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
        inputSearch = v.findViewById(R.id.inputSearch);
        chips = v.findViewById(R.id.chips);
        grid = v.findViewById(R.id.grid);
        metaCount = v.findViewById(R.id.metaCount);
        metaSync = v.findViewById(R.id.metaSync);
        emptyView = v.findViewById(R.id.emptyView);

        curSlug = PrefStore.get().currentSourceSlug();
        curName = PrefStore.get().currentSourceName();
        setSourceHealth(false); // 默认灰色圆环，探测成功后转为橙色

        chipAdapter = new ChipAdapter(c -> {
            curCategory = c == null ? null : c.typeName;
            reload();
        });
        chips.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
        chips.setAdapter(chipAdapter);

        videoAdapter = new VideoAdapter(new VideoAdapter.OnClick() {
            @Override public void onCard(CardItem item) { openDetail(item); }
            @Override public void onDelete(CardItem item) { /* 首页卡片无删除 */ }
        });
        GridLayoutManager glm = new GridLayoutManager(getContext(), 3);
        grid.setLayoutManager(glm);
        grid.setAdapter(videoAdapter);

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
                reload();
            }
        });

        refreshSources();
        reload();
    }

    private void openDetail(CardItem item) {
        if (getContext() != null) PlayActivity.start(getContext(), item);
    }

    /** 源站按钮圆环状态：true=后端通畅（橙色圆环），false=不通（灰色圆环） */
    private void setSourceHealth(boolean ok) {
        if (btnSource == null || btnSourceIcon == null) return;
        btnSource.setBackgroundResource(ok ? R.drawable.bg_ring_on : R.drawable.bg_ring_off);
        int tint = getResources().getColor(ok ? R.color.acc : R.color.t3, null);
        btnSourceIcon.setColorFilter(tint);
    }

    /** 轻量健康探测（GET /healthz），决定顶部圆环颜色 */
    private void pingHealth() {
        VodSyncApi.get().healthz(new VodSyncApi.ApiCb<Long>() {
            @Override public void onOk(Long ms) {
                if (!isAdded()) return;
                setSourceHealth(true);
            }
            @Override public void onErr(String m) {
                if (!isAdded()) return;
                setSourceHealth(false);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        pingHealth();
    }

    /** 源站列表：菜单数据 + 元信息（接口：GET /api/v1/sources）——优先用缓存，过期再拉 */
    private void refreshSources() {
        Models.SyncSourcesResp cached = ApiCache.get(CK_SOURCES, Models.SyncSourcesResp.class, TTL_LIST);
        if (cached != null && cached.list != null && !cached.list.isEmpty()) {
            applySources(cached, true);
            return; // 缓存新鲜，不再发请求
        }
        VodSyncApi.get().get("/api/v1/sources", null, Models.SyncSourcesResp.class,
            new VodSyncApi.ApiCb<Models.SyncSourcesResp>() {
                @Override public void onOk(Models.SyncSourcesResp r) {
                    if (!isAdded()) return;
                    ApiCache.put(CK_SOURCES, r);
                    applySources(r, false);
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    setSourceHealth(false);
                    // 401：服务端开启了访问控制，引导填写 API Token
                    if (com.lateshow.app.util.AuthGate.handleApiError(getActivity(), m, HomeFragment.this::reload)) {
                        metaCount.setText("需要 API Token · 请在设置页填写");
                        return;
                    }
                    // 网络失败：退回本地缓存的源站列表，菜单仍可用
                    Models.SyncSourcesResp stale = ApiCache.getStale(CK_SOURCES, Models.SyncSourcesResp.class);
                    if (stale != null && stale.list != null && !stale.list.isEmpty()) {
                        applySources(stale, true);
                        return;
                    }
                    metaCount.setText("源站加载失败：" + m);
                    if (pendingMenu) {
                        pendingMenu = false;
                        android.widget.Toast.makeText(getContext(), "源站列表加载失败：" + m, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
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
        if (!fromCache) setSourceHealth(true); // 缓存数据不改圆环状态，交给 healthz 探测
        long total = 0;
        for (Models.SyncSource s : sources) total += s.totalVideos;
        metaCount.setText("已收录 " + TimeUtil.compact((int) total) + " 部 · " + sources.size() + " 源");
        if (fromCache) {
            long age = ApiCache.ageMs(CK_SOURCES);
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
        pw.setBackgroundDrawable(getResources().getDrawable(R.drawable.bg_card_lg));
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(8));
        pw.showAsDropDown(btnSource, 0, dp(6));
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
        final String ck = "cats|" + curSlug;
        Models.CategoryResp cached = ApiCache.get(ck, Models.CategoryResp.class, TTL_CAT);
        if (cached != null && cached.list != null && !cached.list.isEmpty()) {
            applyCategories(cached.list);
            return; // 6 小时内分类直接复用缓存，不发请求
        }
        Map<String, String> p = new HashMap<>();
        if (!curSlug.isEmpty()) p.put("source", curSlug);
        VodSyncApi.get().get("/api/v1/categories", p, Models.CategoryResp.class,
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
        final boolean searching = !keyword.isEmpty();
        Map<String, String> params = new HashMap<>();
        params.put("page", String.valueOf(p));
        params.put("page_size", "18");
        if (!curSlug.isEmpty()) params.put("source", curSlug);
        if (curCategory != null) params.put("type_name", curCategory);

        String path;
        if (searching) {
            params.put("wd", keyword);
            path = "/api/v1/search";
        } else {
            path = "/api/v1/videos";
        }
        final int reqPage = p;
        final String ck = listCacheKey(p);

        // 第一页优先用缓存（TTL 内直接渲染，不发请求）
        if (reqPage == 1) {
            Models.VideoListResp cached = ApiCache.get(ck, Models.VideoListResp.class, TTL_LIST);
            if (cached != null && cached.list != null && !cached.list.isEmpty()) {
                loading = false;
                applyList(cached, 1, false);
                return;
            }
        }

        VodSyncApi.get().get(path, params, Models.VideoListResp.class,
            new VodSyncApi.ApiCb<Models.VideoListResp>() {
                @Override public void onOk(Models.VideoListResp r) {
                    if (!isAdded()) return;
                    loading = false;
                    if (reqPage == 1) ApiCache.put(ck, r); // 只缓存第一页，翻页数据不落盘
                    applyList(r, reqPage, false);
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    loading = false;
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
                        emptyView.setText("加载失败：" + m + "\n请在设置页检查播放源地址");
                    }
                }
            });
    }

    /** 列表缓存 key：源站 + 分类 + 关键词 + 页码（第 1 页才落盘） */
    private String listCacheKey(int p) {
        return "videos|" + curSlug + "|" + (curCategory == null ? "" : curCategory)
                + "|" + keyword + "|" + p;
    }

    /** 渲染列表响应；fromCache=true 时给出离线提示 */
    private void applyList(Models.VideoListResp r, int reqPage, boolean fromCache) {
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
