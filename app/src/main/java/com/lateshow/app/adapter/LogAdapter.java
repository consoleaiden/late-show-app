package com.lateshow.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.lateshow.app.R;
import com.lateshow.app.model.Models;
import com.lateshow.app.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/** 最近同步日志列表 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

    private final List<Models.SyncLog> items = new ArrayList<>();
    /** 上一轮行签名：内容未变的日志行不再重绑（审计 A-25） */
    private final List<String> signatures = new ArrayList<>();

    public void setItems(List<Models.SyncLog> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    /** 控制台内是 ScrollView 套 wrap_content 容器，用 LinearLayout 手工铺行保证全部日志可见 */
    public void renderInto(LinearLayout host) {
        if (host == null) return;
        boolean structural = host.getChildCount() != items.size()
                || signatures.size() != items.size();
        if (structural) {
            host.removeAllViews();
            LayoutInflater inf = LayoutInflater.from(host.getContext());
            for (int i = 0; i < items.size(); i++) {
                host.addView(inf.inflate(R.layout.item_log, host, false));
            }
            signatures.clear();
            for (int i = 0; i < items.size(); i++) signatures.add("");
        }
        for (int i = 0; i < items.size(); i++) {
            String sig = signatureOf(items.get(i));
            if (!structural && sig.equals(signatures.get(i))) continue;
            signatures.set(i, sig);
            onBindViewHolder(new VH(host.getChildAt(i)), i);
        }
    }

    private static String signatureOf(Models.SyncLog l) {
        return String.valueOf(l.status) + "|" + l.mode + "|" + l.slug + "|" + l.fetched
                + "|" + l.inserted + "|" + l.updated + "|" + l.error + "|" + l.startedAt;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Models.SyncLog l = items.get(pos);
        Context ctx = h.itemView.getContext();

        int dot, color;
        String label;
        switch (l.status == null ? "" : l.status) {
            case "success": dot = R.drawable.dot_ok; color = R.color.ok; label = "完成"; break;
            case "failed": dot = R.drawable.dot_bad; color = R.color.bad; label = "失败"; break;
            case "paused": dot = R.drawable.dot_warn; color = R.color.warn; label = "已暂停"; break;
            // v1.6.0：部分完成（批内有失败页，断点保留可续传）
            case "incomplete": dot = R.drawable.dot_warn; color = R.color.warn; label = "部分完成"; break;
            // v1.6.0：服务重启时中断的任务
            case "interrupted": dot = R.drawable.dot_bad; color = R.color.bad; label = "中断"; break;
            default: dot = R.drawable.dot_acc; color = R.color.acc; label = "同步中";
        }
        h.dot.setBackgroundResource(dot);
        h.title.setText(modeName(l.mode) + "同步" + label + " · " + slugOf(l.slug));
        h.sub.setText("抓取 " + l.fetched + " · 新增 " + l.inserted + " · 更新 " + l.updated
                + (l.error != null && !l.error.isEmpty() ? " · " + l.error : ""));
        h.time.setText(TimeUtil.stampOrEmpty(l.startedAt));
        h.title.setTextColor(ContextCompat.getColor(ctx, color));
    }

    private static String modeName(String m) {
        return "full".equals(m) ? "全量" : "增量";
    }

    private static String slugOf(String slug) {
        return slug == null ? "" : slug;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        View dot;
        TextView title, sub, time;
        VH(@NonNull View v) {
            super(v);
            dot = v.findViewById(R.id.dot);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
            time = v.findViewById(R.id.time);
        }
    }
}
