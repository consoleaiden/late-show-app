package com.lateshow.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import com.lateshow.app.util.TimeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 首页：源站切换 + 搜索 + 分类筛选 + 3 列海报墙（无限加载） */
public class HomeFragment extends Fragment {

    private TextView btnSource, metaCount, metaSync, emptyView;
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
        btnSource = v.findViewById(R.id.btnSource);
        inputSearch = v.findViewById(R.id.inputSearch);
        chips = v.findViewById(R.id.chips);
        grid = v.findViewById(R.id.grid);
        metaCount = v.findViewById(R.id.metaCount);
        metaSync = v.findViewById(R.id.metaSync);
        emptyView = v.findViewById(R.id.emptyView);

        curSlug = PrefStore.get().currentSourceSlug();
        curName = PrefStore.get().currentSourceName();
        btnSource.setText(curName);

        chipAdapter = new ChipAdapter(c -> {
            curCategory = c == null ? null : c.typeName;
            reload();
        });
        chips.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
        chips.setAdapter(chipAdapter);

        videoAdapter = new VideoAdapter(this::openDetail);
        GridLayoutManager glm = new GridLayoutManager(getContext(), 3);
        grid.setLayoutManager(glm);
        grid.setAdapter(videoAdapter);

        // 无限加载
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (loading || page >= pagecount) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                int total = lm.getItemCount();
                if (last >= total - 6) loadPage(page + 1);
            }
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

    /** 源站列表：菜单数据 + 元信息（接口：GET /api/v1/sources） */
    private void refreshSources() {
        VodSyncApi.get().get("/api/v1/sources", null, Models.SyncSourcesResp.class,
            new VodSyncApi.ApiCb<Models.SyncSourcesResp>() {
                @Override public void onOk(Models.SyncSourcesResp r) {
                    if (!isAdded()) return;
                    sources.clear();
                    if (r.list != null) {
                        for (Models.SyncSource s : r.list) {
                            if (s.enabled) sources.add(s);
                        }
                    }
                    com.lateshow.app.util.SourceCache.putSync(sources);
                    long total = 0;
                    for (Models.SyncSource s : sources) total += s.totalVideos;
                    metaCount.setText("已收录 " + TimeUtil.compact((int) total) + " 部 · " + sources.size() + " 源");
                    metaSync.setText("● " + TimeUtil.nowHm() + " 同步");
                    if (pendingMenu) {
                        pendingMenu = false;
                        showSourceMenu();
                    }
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    metaCount.setText("源站加载失败");
                    if (pendingMenu) {
                        pendingMenu = false;
                        showSourceMenu();
                    }
                }
            });
    }

    private void showSourceMenu() {
        if (!isAdded() || getView() == null) return;
        if (sources.isEmpty()) {
            // 源站列表尚未就绪：先拉一次，回来后再弹菜单
            pendingMenu = true;
            refreshSources();
            return;
        }

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        box.setPadding(0, pad, 0, pad);

        // 「全部源站」固定项
        addMenuRow(box, "全部源站", "", curSlug.isEmpty(), "");
        int rows = 1;

        // 按影片数降序（数据多的源站排前面）
        List<Models.SyncSource> sorted = new ArrayList<>(sources);
        java.util.Collections.sort(sorted, (a, b) -> Integer.compare(b.totalVideos, a.totalVideos));
        for (Models.SyncSource s : sorted) {
            addMenuRow(box, s.name, TimeUtil.compact(s.totalVideos), s.slug != null && s.slug.equals(curSlug), s.slug);
            rows++;
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
            curSlug = slug != null ? slug : "";
            curName = name;
            PrefStore.get().setCurrentSource(curSlug, curName);
            btnSource.setText(curName);
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
        Map<String, String> p = new HashMap<>();
        if (!curSlug.isEmpty()) p.put("source", curSlug);
        VodSyncApi.get().get("/api/v1/categories", p, Models.CategoryResp.class,
            new VodSyncApi.ApiCb<Models.CategoryResp>() {
                @Override public void onOk(Models.CategoryResp r) {
                    if (!isAdded()) return;
                    if (r.list != null && !r.list.isEmpty()) {
                        chipAdapter.setCategories(r.list);
                        chipAdapter.selectByName(curCategory);
                    } else {
                        // 接口无数据：从已加载影片兜底
                        catFailed = true;
                        buildCatsFromItems();
                    }
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    // 分类接口失败（超时/400）：从已加载影片的 type_name 生成分类
                    catFailed = true;
                    buildCatsFromItems();
                }
            });
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
        VodSyncApi.get().get(path, params, Models.VideoListResp.class,
            new VodSyncApi.ApiCb<Models.VideoListResp>() {
                @Override public void onOk(Models.VideoListResp r) {
                    if (!isAdded()) return;
                    loading = false;
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
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    loading = false;
                    if (reqPage == 1 && videoAdapter.getItemCount() == 0) {
                        emptyView.setVisibility(View.VISIBLE);
                        emptyView.setText("加载失败：" + m + "\n请在设置页检查播放源地址");
                    }
                }
            });
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
