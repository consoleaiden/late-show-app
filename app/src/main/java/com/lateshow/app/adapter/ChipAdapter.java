package com.lateshow.app.adapter;

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

    private final List<Models.Category> items = new ArrayList<>();
    private int selected = 0; // 0 = 「全部」
    private final OnPick listener;

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

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chip, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Models.Category c = items.get(pos);
        h.chip.setText(c == null ? "全部" : c.typeName);
        boolean sel = pos == selected;
        int acc = ContextCompat.getColor(h.itemView.getContext(), R.color.acc);
        int accInk = ContextCompat.getColor(h.itemView.getContext(), R.color.accInk);
        int t2 = ContextCompat.getColor(h.itemView.getContext(), R.color.t2);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(h.itemView, 999));
        bg.setColor(sel ? acc : Color.TRANSPARENT);
        if (!sel) {
            bg.setStroke(1, ContextCompat.getColor(h.itemView.getContext(), R.color.line));
        }
        h.chip.setBackground(bg);
        h.chip.setTextColor(sel ? accInk : t2);

        h.chip.setOnClickListener(v -> {
            int old = selected;
            selected = h.getAdapterPosition();
            if (selected == RecyclerView.NO_POSITION) selected = old;
            notifyItemChanged(old);
            notifyItemChanged(selected);
            if (listener != null) listener.onPick(items.get(selected));
        });
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
        VH(@NonNull View v) {
            super(v);
            chip = v.findViewById(R.id.chipText);
        }
    }
}
