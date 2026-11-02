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
        /** 收藏/历史卡片封面上的删除按钮 */
        void onDelete(CardItem item);
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
        h.maskBottom.setVisibility(hist || fav || (it.kind == CardItem.KIND_HOME && it.timeStr != null) ? View.VISIBLE : View.GONE);
        // 删除角标仅收藏/历史卡片显示
        h.btnDel.setVisibility(hist || fav ? View.VISIBLE : View.GONE);
        // 书签角标仅收藏 Tab 显示（历史/首页书签会与遮罩内容重叠）
        h.badgeFav.setVisibility(fav ? View.VISIBLE : View.GONE);

        if (hist) {
            h.tvSrc.setText(com.lateshow.app.util.SourceCache.nameOf(it.slug));
            // 底部遮罩：左 = 播放时间，右 = 进度时长（已播朱橙 / 总时长白）
            h.tvMaskLeft.setText(it.topTimeStr != null ? it.topTimeStr : "");
            String played = TimeUtil.dur(it.playedSec);
            String total = TimeUtil.dur(it.totalSec);
            SpannableStringBuilder sb = new SpannableStringBuilder(played);
            sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(h.itemView.getContext(), R.color.acc)),
                    0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" / ").append(total);
            h.tvMaskRight.setText(sb);
        } else if (fav) {
            // 底部遮罩：左 = 收藏时间，右 = 源站名
            h.tvMaskLeft.setText(it.timeStr != null ? it.timeStr : "");
            h.tvMaskRight.setText(com.lateshow.app.util.SourceCache.nameOf(it.slug));
        } else {
            // 底部遮罩：左 = 添加时间
            h.tvMaskLeft.setText(it.timeStr != null ? it.timeStr : "");
            h.tvMaskRight.setText("");
        }

        // 备注角标仅首页卡片显示（收藏/历史右上角是删除按钮，避免重叠）
        if (it.kind == CardItem.KIND_HOME && it.remark != null && !it.remark.isEmpty()) {
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
        h.btnDel.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(it);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        AppCompatImageView cover;
        TextView title, tvSrc, tvMaskLeft, tvMaskRight, tvRemark;
        LinearLayout topMask, maskBottom;
        FrameLayout badgeFav, btnDel;

        VH(@NonNull View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.tvTitle);
            tvSrc = v.findViewById(R.id.tvSrc);
            tvMaskLeft = v.findViewById(R.id.tvMaskLeft);
            tvMaskRight = v.findViewById(R.id.tvMaskRight);
            tvRemark = v.findViewById(R.id.tvRemark);
            topMask = v.findViewById(R.id.topMask);
            maskBottom = v.findViewById(R.id.maskBottom);
            badgeFav = v.findViewById(R.id.badgeFav);
            btnDel = v.findViewById(R.id.btnDel);
        }
    }
}
