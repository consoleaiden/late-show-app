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
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
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
        // DiffUtil 增量刷新：只重绑有变化的条目，避免全量 notifyDataSetChanged 的整表重绘与封面闪烁
        final List<CardItem> oldItems = new ArrayList<>(items);
        final List<CardItem> newItems = new ArrayList<>(list);
        DiffUtil.DiffResult r = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldItems.size(); }
            @Override public int getNewListSize() { return newItems.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                CardItem a = oldItems.get(o), b = newItems.get(n);
                return a.kind == b.kind && a.vodId == b.vodId
                        && (a.slug == null ? b.slug == null : a.slug.equals(b.slug));
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                CardItem a = oldItems.get(o), b = newItems.get(n);
                return same(a.title, b.title) && same(a.pic, b.pic) && same(a.remark, b.remark)
                        && same(a.timeStr, b.timeStr) && same(a.topTimeStr, b.topTimeStr)
                        && a.playedSec == b.playedSec && a.totalSec == b.totalSec && a.cached == b.cached;
            }
        }, false);
        items.clear();
        items.addAll(newItems);
        r.dispatchUpdatesTo(this);
    }

    private static boolean same(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
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
        VH h = new VH(v);
        // 圆角统一由 coverBox 的背景轮廓裁剪（bg_cover_round 8dp）：图片/遮罩/角标全部裁进圆角内
        h.coverBox.setClipToOutline(true);
        // Lottie 图标一次装载随 ViewHolder 复用：删除角标白色静止、收藏角标心形实心（橙色）
        com.lateshow.app.util.LottieIcon.bind(h.imgDel, "ic_delete");
        com.lateshow.app.util.LottieIcon.tint(h.imgDel, 0xCCFFFFFF);
        com.lateshow.app.util.LottieIcon.bind(h.imgBadgeFav, "ic_heart");
        com.lateshow.app.util.LottieIcon.tint(h.imgBadgeFav, h.itemView.getResources().getColor(R.color.acc));
        return h;
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
            // 底部遮罩：左 = 收藏日期（只到天，窄卡片放不下完整时间），右 = 源站名
            h.tvMaskLeft.setText(TimeUtil.md(it.timeStr));
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

        // 封面加载：RGB_565 解码省一半内存、无淡入动画。
        // 圆角不再烘焙进位图（transform 会覆盖 centerCrop / 565 无 alpha 通道烘不出透明角），
        // 改为 coverBox 按 bg_cover_round 的圆角轮廓做 outline 裁剪：任何加载状态四角都是 8dp
        Glide.with(h.cover).load(it.pic)
                .apply(new RequestOptions()
                        .placeholder(R.color.ink700)
                        .error(R.color.ink700)
                        .centerCrop()
                        .format(DecodeFormat.PREFER_RGB_565)
                        .dontAnimate())
                .into(h.cover);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCard(it);
        });
        h.btnDel.setOnClickListener(v -> {
            // 点删除时播放一次垃圾桶盖摇摆
            h.imgDel.setProgress(0f);
            h.imgDel.playAnimation();
            if (listener != null) listener.onDelete(it);
        });
    }

    /** 条目附着时归位残留的浮点位移（被中断的动画可能留下非零 translation，导致文字亚像素抖动） */
    @Override
    public void onViewAttachedToWindow(@NonNull VH h) {
        if (h.itemView.getTranslationY() != 0f) h.itemView.setTranslationY(0f);
        if (h.itemView.getTranslationX() != 0f) h.itemView.setTranslationX(0f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        FrameLayout coverBox;
        AppCompatImageView cover;
        TextView title, tvSrc, tvMaskLeft, tvMaskRight, tvRemark;
        LinearLayout topMask, maskBottom;
        FrameLayout badgeFav, btnDel;
        com.airbnb.lottie.LottieAnimationView imgDel, imgBadgeFav;

        VH(@NonNull View v) {
            super(v);
            coverBox = v.findViewById(R.id.coverBox);
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
            imgDel = v.findViewById(R.id.imgDel);
            imgBadgeFav = v.findViewById(R.id.imgBadgeFav);
        }
    }
}
