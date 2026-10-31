package com.lateshow.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lateshow.app.R;
import com.lateshow.app.adapter.VideoAdapter;
import com.lateshow.app.model.CardItem;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/** 收藏页：胶囊 Tab「收藏 / 播放历史」 */
public class FavFragment extends Fragment {

    private TextView subTitle, metaInfo, btnClear, emptyView, segFav, segHist;
    private RecyclerView grid;
    private VideoAdapter adapter;
    private boolean showHist = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_fav, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        subTitle = v.findViewById(R.id.subTitle);
        metaInfo = v.findViewById(R.id.metaInfo);
        btnClear = v.findViewById(R.id.btnClear);
        emptyView = v.findViewById(R.id.emptyView);
        segFav = v.findViewById(R.id.segFav);
        segHist = v.findViewById(R.id.segHist);
        grid = v.findViewById(R.id.grid);

        adapter = new VideoAdapter(this::openDetail);
        grid.setLayoutManager(new GridLayoutManager(getContext(), 3));
        grid.setAdapter(adapter);

        segFav.setOnClickListener(vv -> switchSeg(false));
        segHist.setOnClickListener(vv -> switchSeg(true));
        btnClear.setOnClickListener(vv -> {
            PrefStore.get().clearHist();
            render();
            toast("播放历史已清空");
        });

        render();
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
    }

    private int getColor(int id) {
        return androidx.core.content.ContextCompat.getColor(requireContext(), id);
    }

    private void openDetail(CardItem item) {
        if (getContext() != null) PlayActivity.start(getContext(), item);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getView() != null) render();
    }

    private void render() {
        List<CardItem> cards = new ArrayList<>();
        if (showHist) {
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
                c.topTimeStr = TimeUtil.stampOrEmpty(String.valueOf(h.lastPlayedAt)).isEmpty()
                        ? "" : topStamp(h.lastPlayedAt);
                c.fav = PrefStore.get().isFav(h.slug, h.vodId);
                cards.add(c);
            }
            subTitle.setText(cards.size() + " 条记录 · 支持断点续播");
            metaInfo.setText("按最近播放排序");
            btnClear.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
            emptyView.setText("还没有播放记录\n去首页挑一部，进度会自动记在这里");
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
                c.fav = true;
                cards.add(c);
            }
            subTitle.setText(cards.size() + " 部待看 · 按收藏时间排序");
            metaInfo.setText("本地收藏");
            btnClear.setVisibility(View.GONE);
            emptyView.setText("还没有收藏\n播放页右上角书签即可收藏");
        }
        adapter.setItems(cards);
        emptyView.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        metaInfo.setText(metaInfo.getText() + " · 共 " + cards.size() + " 部");
    }

    private String topStamp(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format("%02d.%02d %02d:%02d",
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }
}
