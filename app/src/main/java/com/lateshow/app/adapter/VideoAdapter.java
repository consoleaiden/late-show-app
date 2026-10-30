package com.lateshow.app.adapter;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.lateshow.app.R;
import com.lateshow.app.model.CardItem;
import com.lateshow.app.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/** 首页/收藏/历史三态卡片（kind 决定遮罩布局与角标，规范 §9.2） */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {

    public interface OnClick {
        void onCard(CardItem item);
    }

    private final List<CardItem> items = new ArrayList<>();
    private final OnClick listener;

    public VideoAdapter(OnClick listener) {
        this.listener = listener;
    }

    public void setItems(List<CardItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public void addItems(List<CardItem> list) {
        int start = items.size();
        items.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    public List<CardItem> getItems() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        final CardItem it = items.get(pos);
        h.title.setText(it.title);

        // 遮罩显隐
        boolean hist = it.kind == CardItem.KIND_HIST;
        boolean fav = it.kind == CardItem.KIND_FAV;
        h.topMask.setVisibility(hist ? View.VISIBLE : View.GONE);
        h.bottomMask.setVisibility(hist || fav || (it.kind == CardItem.KIND_HOME && it.timeStr != null) ? View.VISIBLE : View.GONE);
        h.badgeFav.setVisibility(it.fav || fav ? View.VISIBLE : View.GONE);

        if (hist) {
            h.tvSrc.setText(it.sourceName != null ? it.sourceName : it.slug);
            h.tvTopTime.setText(it.topTimeStr != null ? it.topTimeStr : "");
            // 进度：已播时长(朱橙) / 总时长(白)
            String played = TimeUtil.dur(it.playedSec);
            String total = TimeUtil.dur(it.totalSec);
            SpannableStringBuilder sb = new SpannableStringBuilder(played);
            sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(h.itemView.getContext(), R.color.acc)),
                    0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" / ").append(total);
            h.bottomMask.setText(sb);
        } else if (fav) {
            String src = it.sourceName != null ? it.sourceName : it.slug;
            h.bottomMask.setText(src + " · " + (it.timeStr != null ? it.timeStr : ""));
        } else {
            h.bottomMask.setText(it.timeStr != null ? it.timeStr : "");
        }

        // 备注角标
        if (it.remark != null && !it.remark.isEmpty()) {
            h.tvRemark.setVisibility(View.VISIBLE);
            h.tvRemark.setText(it.remark);
        } else {
            h.tvRemark.setVisibility(View.GONE);
        }

        Glide.with(h.cover).load(it.pic)
                .placeholder(R.color.ink700)
                .error(R.color.ink700)
                .centerCrop()
                .into(h.cover);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCard(it);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        AppCompatImageView cover;
        TextView title, tvSrc, tvTopTime, bottomMask, tvRemark;
        LinearLayout topMask;
        FrameLayout badgeFav;

        VH(@NonNull View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.tvTitle);
            tvSrc = v.findViewById(R.id.tvSrc);
            tvTopTime = v.findViewById(R.id.tvTopTime);
            bottomMask = v.findViewById(R.id.tvBottomMask);
            tvRemark = v.findViewById(R.id.tvRemark);
            topMask = v.findViewById(R.id.topMask);
            badgeFav = v.findViewById(R.id.badgeFav);
        }
    }
}
