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

    private final List<Models.Source> sources = new ArrayList<>();
    private String curSlug = "";      // 空 = 全部源站
    private String curName = "全部源站";
    private String curCategory = null; // null = 全部
    private String keyword = "";

    private int page = 1, pagecount = 1;
    private boolean loading = false;

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

    /** 源站列表：菜单数据 + 元信息 */
    private void refreshSources() {
        VodSyncApi.get().get("/source", map("only_ready", "true"), Models.SourceListResp.class,
            new VodSyncApi.ApiCb<Models.SourceListResp>() {
                @Override public void onOk(Models.SourceListResp r) {
                    if (!isAdded()) return;
                    sources.clear();
                    if (r.list != null) sources.addAll(r.list);
                    long total = 0;
                    for (Models.Source s : sources) total += s.videoCount;
                    metaCount.setText("已收录 " + TimeUtil.compact((int) total) + " 部");
                    metaSync.setText("● " + TimeUtil.nowHm() + " 同步");
                }
                @Override public void onErr(String m) {
                    if (!isAdded()) return;
                    metaCount.setText("源站加载失败");
                }
            });
    }

    private void showSourceMenu() {
        if (!isAdded() || getView() == null) return;
        View content = LayoutInflater.from(getContext()).inflate(R.layout.item_source_menu, null, false);
        // 用 LinearLayout 容器装多行
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        box.setPadding(0, pad, 0, pad);

        // 「全部源站」固定项
        addMenuRow(box, "全部源站", "", curSlug.isEmpty());
        for (Models.Source s : sources) {
            addMenuRow(box, s.name, TimeUtil.compact(s.videoCount), s.slug.equals(curSlug));
        }

        PopupWindow pw = new PopupWindow(box,
                (int) (280 * getResources().getDisplayMetrics().density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(getResources().getDrawable(R.drawable.bg_card_lg));
        pw.setOutsideTouchable(true);
        pw.showAsDropDown(btnSource, 0, (int) (6 * getResources().getDisplayMetrics().density));
        box.setTag(pw);
    }

    private void addMenuRow(LinearLayout box, String name, String count, boolean sel) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_source_menu, box, false);
        ((TextView) row.findViewById(R.id.name)).setText(name);
        TextView cnt = row.findViewById(R.id.count);
        cnt.setText(count);
        row.findViewById(R.id.dot).setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> {
            PopupWindow pw = (PopupWindow) box.getTag();
            if (pw != null) pw.dismiss();
            if ("全部源站".equals(name)) {
                curSlug = "";
                curName = "全部源站";
            } else {
                for (Models.Source s : sources) {
                    if (s.name.equals(name)) {
                        curSlug = s.slug;
                        curName = s.name;
                    }
                }
            }
            PrefStore.get().setCurrentSource(curSlug, curName);
            btnSource.setText(curName);
            refreshCategories();
            reload();
        });
        box.addView(row);
    }

    private void refreshCategories() {
        Map<String, String> p = new HashMap<>();
        if (!curSlug.isEmpty()) p.put("source", curSlug);
        VodSyncApi.get().get("/api/v1/categories", p, Models.CategoryResp.class,
            new VodSyncApi.ApiCb<Models.CategoryResp>() {
                @Override public void onOk(Models.CategoryResp r) {
                    if (!isAdded()) return;
                    chipAdapter.setCategories(r.list);
                }
                @Override public void onErr(String m) { /* 分类加载失败不阻塞列表 */ }
            });
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
                    List<CardItem> cards = new ArrayList<>();
                    if (r.list != null) {
                        for (Models.VideoSummary v : r.list) cards.add(toCard(v));
                    }
                    if (reqPage == 1) {
                        videoAdapter.setItems(cards);
                    } else {
                        videoAdapter.addItems(cards);
                    }
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
        c.sourceName = v.sourceName != null && !v.sourceName.isEmpty() ? v.sourceName : v.source;
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
