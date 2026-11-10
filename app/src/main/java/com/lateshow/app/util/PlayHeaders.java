package com.lateshow.app.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 播放器请求头策略（真机排障 2026-11-10）。
 *
 * <p>真机实测：同一 m3u8 地址，手机浏览器可正常访问，而 ExoPlayer 默认 UA
 * （{@code ExoPlayerLib/x.x.x}）被源站 CDN 以 403 拒绝 —— 典型的 UA 防盗链。
 * 因此播放请求统一携带浏览器 UA；Referer 只在首次失败后的重试里追加
 * （多数站点不需要，少数防盗链只认带 Referer 的请求，默认带上也无副作用）。
 */
public final class PlayHeaders {

    /** 播放请求统一 UA：与详情页兜底解析（UA_MOBILE）保持同族，桌面 Chrome 更稳 */
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private PlayHeaders() { }

    /** m3u8 所在站点的 origin（含结尾斜杠），作 Referer 兜底；解析不出返回 null */
    public static String refererFor(String url) {
        if (url == null) return null;
        int s = url.indexOf("://");
        if (s <= 0) return null;
        int p = url.indexOf('/', s + 3);
        return p > 0 ? url.substring(0, p + 1) : url + "/";
    }

    /**
     * 播放请求默认头。UA 由 {@code DefaultHttpDataSource.Factory#setUserAgent} 单独设置，
     * 这里只放通用头；{@code withReferer} 为 true 时追加同源 Referer（防盗链重试）。
     */
    public static Map<String, String> props(String url, boolean withReferer) {
        Map<String, String> m = new HashMap<>();
        m.put("Accept", "*/*");
        if (withReferer && url != null) {
            String r = refererFor(url);
            if (r != null) m.put("Referer", r);
        }
        return m;
    }

    /**
     * 播放诊断日志：release 包也保留（tag=LateShowPlay）。
     * 经过 {@link AppLog#safe} 脱敏：URL 只留 scheme://host/path，query 一律丢弃。
     */
    public static void diag(String msg) {
        android.util.Log.i("LateShowPlay", AppLog.safe(msg));
    }
}
