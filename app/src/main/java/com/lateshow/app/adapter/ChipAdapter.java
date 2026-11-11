package com.lateshow.app.adapter;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.lateshow.app.R;
import com.lateshow.app.model.Models;

import java.util.ArrayList;
import java.util.List;

/** 分类横滑芯片 */
public class ChipAdapter extends RecyclerView.Adapter<ChipAdapter.VH> {

    public interface OnPick {
        void onPick(Models.Category c);
    }

    /** 长按任意芯片 = 弹出全部分类列表（挂载在芯片上，RecyclerView 层收不到长按） */
    public interface OnLongPress {
        void onLongPress();
    }

    private final List<Models.Category> items = new ArrayList<>();
    private int selected = 0; // 0 = 「全部」
    private final OnPick listener;
    private OnLongPress longPress;

    public ChipAdapter(OnPick listener) {
        this.listener = listener;
        items.add(null); // 「全部」占位
    }

    public void setCategories(List<Models.Category> cats) {
        items.clear();
        items.add(null);
        if (cats != null) items.addAll(cats);
        selected = 0;
        notifyDataSetChanged();
    }

    /** 全部分类列表（含首位的 null=「全部」占位），供长按弹层使用 */
    public List<Models.Category> getItems() {
        return items;
    }

    /** 按序号选中（长按弹层点选），等价于点击对应芯片 */
    public void selectAt(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        int old = selected;
        selected = pos;
        notifyItemChanged(old);
        notifyItemChanged(selected);
        if (listener != null) listener.onPick(items.get(pos));
    }

    /** 注册长按回调（在 HomeFragment 里指向「全部分类」弹层） */
    public void setOnLongPress(OnLongPress lp) {
        this.longPress = lp;
    }

    /** 按分类名恢复选中态（找不到则保持「全部」） */
    public void selectByName(String name) {
        if (name == null) return;
        for (int i = 1; i < items.size(); i++) {
            Models.Category c = items.get(i);
            if (c != null && name.equals(c.typeName)) {
                int old = selected;
                selected = i;
                notifyItemChanged(old);
                notifyItemChanged(selected);
                return;
            }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        VH h = new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chip, parent, false));
        // 选中/未选中两套背景随 ViewHolder 缓存复用，避免每次 bind 新建 GradientDrawable
        float radius = dp(h.itemView, 999);
        h.bgSel = new GradientDrawable();
        h.bgSel.setCornerRadius(radius);
        h.bgSel.setColor(ContextCompat.getColor(h.itemView.getContext(), R.color.acc));
        h.bgOff = new GradientDrawable();
        h.bgOff.setCornerRadius(radius);
        h.bgOff.setColor(Color.TRANSPARENT);
        h.bgOff.setStroke(1, ContextCompat.getColor(h.itemView.getContext(), R.color.line));
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Models.Category c = items.get(pos);
        h.chip.setText(c == null ? "全部" : c.typeName);
        boolean sel = pos == selected;
        int accInk = ContextCompat.getColor(h.itemView.getContext(), R.color.accInk);
        int t2 = ContextCompat.getColor(h.itemView.getContext(), R.color.t2);

        h.chip.setBackground(sel ? h.bgSel : h.bgOff);
        h.chip.setTextColor(sel ? accInk : t2);

        h.chip.setOnClickListener(v -> {
            int old = selected;
            selected = h.getAdapterPosition();
            if (selected == RecyclerView.NO_POSITION) selected = old;
            notifyItemChanged(old);
            notifyItemChanged(selected);
            if (listener != null) listener.onPick(items.get(selected));
        });
        // 芯片是可点击 View，会消费触摸序列——长按弹层必须挂在它身上才收得到
        if (longPress != null) {
            h.chip.setOnLongClickListener(v -> {
                longPress.onLongPress();
                return true;
            });
        } else {
            h.chip.setOnLongClickListener(null);
            h.chip.setLongClickable(false);
        }
    }

    private static float dp(View v, int n) {
        return n * v.getResources().getDisplayMetrics().density;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView chip;
        GradientDrawable bgSel, bgOff;
        VH(@NonNull View v) {
            super(v);
            chip = v.findViewById(R.id.chipText);
        }
    }
}
