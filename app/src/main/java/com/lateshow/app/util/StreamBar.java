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
import com.lateshow.app.util.AppLog;

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
        if (bar == null) {
            AppLog.w("StreamBar", "bind fail: view not found in root " + root);
            return;
        }
        final boolean show = PrefStore.get().showStreamTools();
        if (!show || TextUtils.isEmpty(url)) {
            bar.setVisibility(View.GONE);
            com.lateshow.app.util.PlayHeaders.diag("streamBar hide show=" + show
                    + " urlLen=" + (url == null ? -1 : url.length()));
            return;
        }
        bar.setVisibility(View.VISIBLE);
        // 地址条与视频画面（TextureView）同层：显式压到最上层并给一点阴影，
        // 否则在某些机型的合成顺序里会被画面盖住（真机 2026-11-13：详情页小窗看不到地址条）
        bar.bringToFront();
        bar.setElevation(4f * bar.getResources().getDisplayMetrics().density);
        // 用常开的 diag 日志（AppLog 在 release 包完全关闭，排障时看不到）
        com.lateshow.app.util.PlayHeaders.diag("streamBar show urlLen=" + url.length()
                + " w=" + bar.getWidth() + " vis=" + bar.getVisibility());
        // 审计 A-34 / A-06：展示、复制与外发前统一剥掉可能残留的凭据 query 参数
        final String safe = sanitize(url);
        TextView tv = root.findViewById(R.id.tvStreamUrl);
        if (tv != null) {
            tv.setText(safe);
            tv.setOnClickListener(v -> copy(v.getContext(), safe));
        }
        View copy = root.findViewById(R.id.btnCopyStream);
        if (copy != null) copy.setOnClickListener(v -> copy(v.getContext(), safe));
        View download = root.findViewById(R.id.btnDownloadStream);
        if (download != null) download.setOnClickListener(v -> open(v.getContext(), safe));
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

    /** 外部 ACTION_VIEW 只允许 http(s)：拒绝 file://、content://、javascript: 等 scheme（A-34） */
    private static boolean safeExternal(String url) {
        try {
            String scheme = Uri.parse(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    /** 去掉 URL 里的 token / api_key 类参数，避免凭据随复制、浏览器历史与下载器日志外泄（A-06） */
    private static String sanitize(String url) {
        if (TextUtils.isEmpty(url)) return url;
        try {
            Uri u = Uri.parse(url);
            java.util.Set<String> names = u.getQueryParameterNames();
            boolean dirty = false;
            for (String k : names) {
                if (k == null) continue;
                String lower = k.toLowerCase(java.util.Locale.US);
                if (lower.contains("token") || lower.contains("api_key") || lower.equals("apikey")) {
                    dirty = true;
                    break;
                }
            }
            if (!dirty) return url;
            Uri.Builder b = u.buildUpon().clearQuery();
            for (String k : names) {
                if (k == null) continue;
                String lower = k.toLowerCase(java.util.Locale.US);
                if (lower.contains("token") || lower.contains("api_key") || lower.equals("apikey")) continue;
                for (String v : u.getQueryParameters(k)) b.appendQueryParameter(k, v);
            }
            return b.build().toString();
        } catch (Exception e) {
            return url;
        }
    }

    private static void open(Context ctx, String url) {
        if (TextUtils.isEmpty(url)) return;
        if (!safeExternal(url)) {
            Toast.makeText(ctx, "仅支持 http(s) 播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            Toast.makeText(ctx, "已交给系统打开，可用浏览器 / 下载器保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "没有可处理该链接的应用", Toast.LENGTH_SHORT).show();
        }
    }
}
