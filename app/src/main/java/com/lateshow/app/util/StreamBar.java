package com.lateshow.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.lateshow.app.R;
import com.lateshow.app.store.PrefStore;

/**
 * 播放地址工具条（view_stream_bar）的统一绑定。
 *
 * 内嵌播放器（DetailFragment）与全屏播放页（PlayerActivity）共用：
 * 读 PrefStore.showStreamTools() 决定显隐，显示当前真正在播的直链，
 * 提供「复制」与「下载」（交给系统浏览器 / 下载器处理）。
 */
public final class StreamBar {

    private StreamBar() { }

    /**
     * @param root 能找到 streamBar / tvStreamUrl / btnCopyStream / btnDownloadStream 的任意祖先 View
     * @param url  归一化后真正在播的地址（已 decorate 或未 decorate 均可，展示原样）
     */
    public static void bind(View root, String url) {
        if (root == null) return;
        final View bar = root.findViewById(R.id.streamBar);
        if (bar == null) return;
        final boolean show = PrefStore.get().showStreamTools();
        if (!show || TextUtils.isEmpty(url)) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.setVisibility(View.VISIBLE);
        TextView tv = root.findViewById(R.id.tvStreamUrl);
        if (tv != null) {
            tv.setText(url);
            tv.setOnClickListener(v -> copy(v.getContext(), url));
        }
        View copy = root.findViewById(R.id.btnCopyStream);
        if (copy != null) copy.setOnClickListener(v -> copy(v.getContext(), url));
        View download = root.findViewById(R.id.btnDownloadStream);
        if (download != null) download.setOnClickListener(v -> open(v.getContext(), url));
    }

    /** 开关关闭或播放器收起时立即隐藏，避免残留在画面上 */
    public static void hide(View root) {
        if (root == null) return;
        View bar = root.findViewById(R.id.streamBar);
        if (bar != null) bar.setVisibility(View.GONE);
    }

    private static void copy(Context ctx, String url) {
        if (TextUtils.isEmpty(url)) return;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("play-url", url));
        Toast.makeText(ctx, "播放地址已复制", Toast.LENGTH_SHORT).show();
    }

    private static void open(Context ctx, String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            Toast.makeText(ctx, "已交给系统打开，可用浏览器 / 下载器保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            copy(ctx, url);
            Toast.makeText(ctx, "没有可处理该链接的应用，地址已复制", Toast.LENGTH_SHORT).show();
        }
    }
}
