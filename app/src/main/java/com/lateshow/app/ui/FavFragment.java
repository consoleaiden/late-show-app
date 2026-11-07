package com.lateshow.app.ui;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lateshow.app.R;
import com.lateshow.app.adapter.VideoAdapter;
import com.lateshow.app.model.CardItem;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.util.OfflineMedia;
import com.lateshow.app.util.Skeleton;
import com.lateshow.app.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/** 收藏页：胶囊 Tab「收藏 / 播放历史」 */
public class FavFragment extends Fragment {

    private TextView subTitle, metaInfo, btnClear, emptyView, segFav, segHist;
    private RecyclerView grid;
    private VideoAdapter adapter;
    private boolean showHist = false;

    // 首屏骨架屏（构建在 XML 的 skeletonBox 里，延迟 220ms 仍未出数据才显示）
    private LinearLayout skeletonBox;
    private final ObjectAnimator[] skelAnim = new ObjectAnimator[1];
    private final Handler skelHandler = new Handler(Looper.getMainLooper());
    private final Runnable skelShowTask = new Runnable() {
        @Override public void run() {
            if (!isAdded() || skeletonBox == null || adapter == null) return;
            if (adapter.getItemCount() > 0) return;   // 已出数据：不显示
            Skeleton.show(skeletonBox, skelAnim);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_fav, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        com.lateshow.app.util.LottieIcon.bindTree(v);
        subTitle = v.findViewById(R.id.subTitle);
        metaInfo = v.findViewById(R.id.metaInfo);
        btnClear = v.findViewById(R.id.btnClear);
        emptyView = v.findViewById(R.id.emptyView);
        segFav = v.findViewById(R.id.segFav);
        segHist = v.findViewById(R.id.segHist);
        grid = v.findViewById(R.id.grid);

        adapter = new VideoAdapter(new VideoAdapter.OnClick() {
            @Override public void onCard(CardItem item, View source) { openDetail(item, source); }
            @Override public void onDelete(CardItem item) { confirmDelete(item); }
        });
        grid.setLayoutManager(new GridLayoutManager(getContext(), 3));
        grid.setAdapter(adapter);
        grid.setHasFixedSize(true); // 条目尺寸固定，避免插入/删除时整表重测量
        grid.setItemAnimator(null); // 关闭条目动画：默认动画器的浮点平移会导致减速阶段文字亚像素抖动

        // 回到顶部浮动按钮（FAB 用 LottieDrawable 注入图标）
        com.google.android.material.floatingactionbutton.FloatingActionButton fabTop =
                v.findViewById(R.id.fabTop);
        final com.airbnb.lottie.LottieDrawable fabIcon =
                com.lateshow.app.util.LottieIcon.into(fabTop, "ic_top", false);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                boolean show = lm != null && lm.findFirstVisibleItemPosition() > 8;
                fabTop.setVisibility(show ? View.VISIBLE : View.GONE);
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

        segFav.setOnClickListener(vv -> switchSeg(false));
        segHist.setOnClickListener(vv -> switchSeg(true));
        btnClear.setOnClickListener(vv -> {
            PrefStore.get().clearHist();
            render();
            toast("播放历史已清空");
        });

        setupSkeleton(v);
        render();
    }

    // ---------- 首屏骨架屏 ----------

    /** 骨架屏：3×3 海报卡占位（封面 8dp 圆角块 + 标题两行灰条，与首页首屏骨架同款） */
    private void setupSkeleton(View v) {
        skeletonBox = v.findViewById(R.id.skeletonBox);
        if (skeletonBox == null || getContext() == null) return;
        skeletonBox.addView(Skeleton.posterGrid(requireContext(), 3, 3));
    }

    /** 延迟 220ms 再显示：本地记录少时渲染在 220ms 内完成，不会闪一下骨架 */
    private void showSkeletonDelayed() {
        skelHandler.removeCallbacks(skelShowTask);
        skelHandler.postDelayed(skelShowTask, 220);
    }

    private void hideSkeleton() {
        skelHandler.removeCallbacks(skelShowTask);
        Skeleton.hide(skeletonBox, skelAnim);
    }

    @Override
    public void onDestroyView() {
        skelHandler.removeCallbacks(skelShowTask);
        Skeleton.hide(skeletonBox, skelAnim);
        skeletonBox = null;
        super.onDestroyView();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(getContext(), s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void switchSeg(boolean hist) {
        if (showHist == hist) return;
        showHist = hist;
        segFav.setBackgroundResource(showHist ? R.drawable.bg_seg_off : R.drawable.bg_seg_on);
        segHist.setBackgroundResource(showHist ? R.drawable.bg_seg_on : R.drawable.bg_seg_off);
        segFav.setTextColor(getColor(showHist ? R.color.t2 : R.color.accInk));
        segHist.setTextColor(getColor(showHist ? R.color.accInk : R.color.t2));
        render();
        com.lateshow.app.util.UiAnim.fadeUp(grid, 8);   // 收藏 ↔ 历史：整块内容换掉，淡入一次
    }

    private int getColor(int id) {
        return androidx.core.content.ContextCompat.getColor(requireContext(), id);
    }

    private void openDetail(CardItem item, View source) {
        if (getActivity() instanceof com.lateshow.app.MainActivity) {
            ((com.lateshow.app.MainActivity) getActivity()).openDetail(item, source);
        }
    }

    /** 封面右上角删除：确认后移除收藏/播放记录（收藏连带清理离线播放数据） */
    private void confirmDelete(CardItem item) {
        if (!isAdded() || getContext() == null) return;
        boolean isFav = item.kind == CardItem.KIND_FAV;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isFav ? "删除收藏" : "删除播放记录")
                .setMessage(item.title)
                .setPositiveButton("删除", (d, w) -> {
                    if (isFav) {
                        PrefStore.get().removeFav(item.slug, item.vodId);
                        // 取消收藏且没有播放历史：连带清掉离线播放数据
                        if (PrefStore.get().getHist(item.slug, item.vodId) == null) {
                            OfflineMedia.delete(item.slug, item.vodId);
                            PrefStore.get().setOfflineCached(item.slug, item.vodId, false);
                        }
                        toast("已删除收藏");
                    } else {
                        PrefStore.get().removeHist(item.slug, item.vodId);
                        toast("已删除播放记录");
                    }
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getView() != null) render();
    }

    /** 卡片构建用后台线程：OfflineMedia.has 是磁盘 stat，历史记录上百条时不能放主线程 */
    private static final java.util.concurrent.ExecutorService CARD_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void render() {
        final boolean hist = showHist;
        if (!isAdded()) return;
        // 列表为空才可能看到空白：届时先占位（有数据时切换 Tab 直接换内容，不闪骨架）
        if (adapter.getItemCount() == 0) showSkeletonDelayed();
        CARD_EXEC.execute(() -> {
            final List<CardItem> cards = new ArrayList<>();
            final int[] cachedCount = {0};
            if (hist) {
                for (PrefStore.HistItem h : PrefStore.get().hists()) {
                    CardItem c = new CardItem();
                    c.kind = CardItem.KIND_HIST;
                    c.slug = h.slug;
                    c.sourceName = h.sourceName;
                    c.vodId = h.vodId;
                    c.title = h.title;
                    c.pic = h.pic;
                    c.remark = h.remarks;
                    c.playedSec = h.playedSec;
                    c.totalSec = h.totalSec;
                    // 播放数据是否已落盘（老版本记录没有该标记时按文件存在性判断）
                    c.cached = h.cached || OfflineMedia.has(h.slug, h.vodId);
                    if (c.cached) cachedCount[0]++;
                    c.topTimeStr = TimeUtil.stampOrEmpty(String.valueOf(h.lastPlayedAt)).isEmpty()
                            ? "" : topStamp(h.lastPlayedAt);
                    c.fav = PrefStore.get().isFav(h.slug, h.vodId);
                    cards.add(c);
                }
            } else {
                for (PrefStore.FavItem f : PrefStore.get().favs()) {
                    CardItem c = new CardItem();
                    c.kind = CardItem.KIND_FAV;
                    c.slug = f.slug;
                    c.sourceName = f.sourceName;
                    c.vodId = f.vodId;
                    c.title = f.title;
                    c.pic = f.pic;
                    c.remark = f.remarks;
                    c.timeStr = topStamp(f.savedAt);
                    c.cached = f.cached || OfflineMedia.has(f.slug, f.vodId);
                    if (c.cached) cachedCount[0]++;
                    c.fav = true;
                    cards.add(c);
                }
            }
            if (!isAdded()) return;
            final int cc = cachedCount[0];
            requireActivity().runOnUiThread(() -> {
                // 期间用户已切换 Tab 或页面已销毁：丢弃这次结果
                if (!isAdded() || hist != showHist || getView() == null) return;
                hideSkeleton();
                adapter.setItems(cards);
                if (hist) {
                    subTitle.setText(cards.size() + " 条记录 · 支持断点续播");
                    metaInfo.setText("按最近播放排序");
                    btnClear.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
                    emptyView.setText("还没有播放记录\n去首页挑一部，进度会自动记在这里");
                } else {
                    subTitle.setText(cards.size() + " 部待看 · 按收藏时间排序");
                    metaInfo.setText("本地收藏");
                    btnClear.setVisibility(View.GONE);
                    emptyView.setText("还没有收藏\n播放页右上角书签即可收藏");
                }
                emptyView.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
                metaInfo.setText(metaInfo.getText() + " · 共 " + cards.size() + " 部"
                        + (cc > 0 ? " · " + cc + " 部可离线播放" : ""));
            });
        });
    }

    private String topStamp(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format("%02d.%02d %02d:%02d",
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }
}
