package com.lateshow.app.adapter;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.lateshow.app.R;
import com.lateshow.app.model.Models;
import com.lateshow.app.util.TimeUtil;
import com.lateshow.app.ui.HomeFragment;

import java.util.ArrayList;
import java.util.List;

/** 设置页源站同步状态列表（勾选 + 自动同步开关 + 进度 + 同步/校准） */
public class SyncSourceAdapter extends RecyclerView.Adapter<SyncSourceAdapter.VH> {

    public interface Callbacks {
        void onPickChanged();
        void onAction(String msg);
        java.util.Map<String, String> ctx();
    }

    private final List<Models.SyncSource> items = new ArrayList<>();
    private final Callbacks cb;

    public SyncSourceAdapter(Callbacks cb) {
        this.cb = cb;
    }

    public void setItems(List<Models.SyncSource> list) {
        for (int i = 0; i < items.size() && i < list.size(); i++) {
            list.get(i).pick = items.get(i).pick; // 保留勾选态
        }
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public List<Models.SyncSource> getItems() {
        return items;
    }

    public void setAllPicked(boolean picked) {
        for (Models.SyncSource s : items) s.pick = picked;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sync_source, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Models.SyncSource s = items.get(pos);
        h.name.setText(s.name);
        h.chk.setOnCheckedChangeListener(null);
        h.chk.setChecked(s.pick);
        h.chk.setOnCheckedChangeListener((bv, c) -> {
            s.pick = c;
            if (cb != null) cb.onPickChanged();
        });

        h.swAuto.setOnCheckedChangeListener(null);
        h.swAuto.setChecked(s.autoSync);
        h.autoLabel.setTextColor(ContextCompat.getColor(h.itemView.getContext(), s.autoSync ? R.color.t3 : R.color.warn));
        h.swAuto.setOnCheckedChangeListener((bv, on) -> {
            java.util.Map<String, String> p = HomeFragment.map("enabled", String.valueOf(on));
            com.lateshow.app.api.VodSyncApi.get().post("/api/v1/sources/" + s.slug + "/auto-sync", p,
                    Models.MsgResp.class, new com.lateshow.app.api.VodSyncApi.ApiCb<Models.MsgResp>() {
                @Override public void onOk(Models.MsgResp r) {
                    s.autoSync = on;
                    if (cb != null) cb.onAction(r.msg != null ? r.msg : "已更新");
                }
                @Override public void onErr(String m) {
                    h.swAuto.setOnCheckedChangeListener(null);
                    h.swAuto.setChecked(!on);
                    if (cb != null) cb.onAction(m);
                }
            });
        });

        // 状态判定（API 文档 §5.1）：running > paused > syncing(断点) > 已完成
        Context ctx = h.itemView.getContext();
        String pill;
        int dotRes, colorRes;
        if (s.running) {
            pill = s.paused ? "已暂停" : ("正在同步 · " + ("full".equals(s.runningMode) ? "全量" : "增量"));
            dotRes = s.paused ? R.drawable.dot_warn : R.drawable.dot_acc;
            colorRes = s.paused ? R.color.warn : R.color.acc;
        } else if (s.paused) {
            pill = "本站暂停";
            dotRes = R.drawable.dot_warn;
            colorRes = R.color.warn;
        } else if (s.totalPages > 0 && s.lastPage < s.totalPages) {
            pill = "断点 " + s.lastPage + "/" + s.totalPages + " 页";
            dotRes = R.drawable.dot_warn;
            colorRes = R.color.warn;
        } else {
            pill = "已完成";
            dotRes = R.drawable.dot_ok;
            colorRes = R.color.ok;
        }
        h.pill.setText(pill);
        h.pill.setTextColor(ContextCompat.getColor(ctx, colorRes));
        h.pill.setCompoundDrawablesWithIntrinsicBounds(ContextCompat.getDrawable(ctx, dotRes), null, null, null);
        h.pill.setCompoundDrawablePadding((int) (4 * ctx.getResources().getDisplayMetrics().density));

        h.meta.setText(s.slug + " · " + TimeUtil.thousands(s.totalVideos) + " 条 · 最后同步 " + TimeUtil.stampOrEmpty(s.lastSyncAt));

        int pct = (int) Math.max(0, Math.min(100, s.progress));
        h.pbar.setProgress(pct);
        String info = pct + "%" + (s.running && s.totalPages > 0 ? " · " + s.lastPage + "/" + s.totalPages : "");
        h.pct.setText(info);

        h.btnSync.setOnClickListener(v -> {
            if (s.running && !s.paused) {
                if (cb != null) cb.onAction("正在同步中");
                return;
            }
            java.util.Map<String, String> p = HomeFragment.map("mode", "auto");
            com.lateshow.app.api.VodSyncApi.get().post("/api/v1/sync/" + s.slug, p,
                    Models.MsgResp.class, new com.lateshow.app.api.VodSyncApi.ApiCb<Models.MsgResp>() {
                @Override public void onOk(Models.MsgResp r) {
                    if (cb != null) cb.onAction(r.msg != null ? r.msg : "同步任务已受理");
                }
                @Override public void onErr(String m) {
                    if (cb != null) cb.onAction(m);
                }
            });
        });

        h.btnCali.setOnClickListener(v -> {
            java.util.Map<String, String> p = HomeFragment.map("mode", "full");
            com.lateshow.app.api.VodSyncApi.get().post("/api/v1/sync/" + s.slug, p,
                    Models.MsgResp.class, new com.lateshow.app.api.VodSyncApi.ApiCb<Models.MsgResp>() {
                @Override public void onOk(Models.MsgResp r) {
                    if (cb != null) cb.onAction("校准（全量）已受理 · " + s.name);
                }
                @Override public void onErr(String m) {
                    if (cb != null) cb.onAction(m);
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        CheckBox chk;
        TextView name, pill, autoLabel, meta, pct, btnSync, btnCali;
        Switch swAuto;
        ProgressBar pbar;

        VH(@NonNull View v) {
            super(v);
            chk = v.findViewById(R.id.chk);
            name = v.findViewById(R.id.name);
            pill = v.findViewById(R.id.pill);
            autoLabel = v.findViewById(R.id.autoLabel);
            meta = v.findViewById(R.id.meta);
            pct = v.findViewById(R.id.pct);
            btnSync = v.findViewById(R.id.btnSync);
            btnCali = v.findViewById(R.id.btnCali);
            swAuto = v.findViewById(R.id.swAuto);
            pbar = v.findViewById(R.id.pbar);
        }
    }
}
