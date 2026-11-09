package com.lateshow.app.util;

import com.lateshow.app.BuildConfig;

/**
 * 统一日志出口（审计 A-35 / A-06）。
 *
 * <p>release 包彻底关闭 Log 输出：工程里有约 20 处 {@code Log.*} 会带出完整第三方 URL、
 * masked token 与请求路径，生产包没有必要输出。debug 包保留排障能力。
 *
 * <p>所有经过这里的内容还会做一次脱敏：去掉 URL query（token / 签名参数都在 query 里），
 * 并把形如 {@code vs_xxx} 的整段 token 替换为掩码。这样即使未来误把完整 URL 传进来也不会漏。
 */
public final class AppLog {

    private static final boolean ENABLED = BuildConfig.DEBUG;
    private static final String TAG_PREFIX = "LateShow.";

    private AppLog() { }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void d(String tag, String msg) {
        if (ENABLED) android.util.Log.d(TAG_PREFIX + tag, safe(msg));
    }

    public static void w(String tag, String msg) {
        if (ENABLED) android.util.Log.w(TAG_PREFIX + tag, safe(msg));
    }

    public static void w(String tag, String msg, Throwable t) {
        if (ENABLED) android.util.Log.w(TAG_PREFIX + tag, safe(msg), t);
    }

    public static void e(String tag, String msg) {
        if (ENABLED) android.util.Log.e(TAG_PREFIX + tag, safe(msg));
    }

    /** URL 只保留 scheme://host/path，query 与 fragment 一律丢弃；顺带掩码 token 形态字符串 */
    public static String safe(String s) {
        if (s == null) return "";
        String out = s;
        int q = out.indexOf('?');
        // 只裁掉看起来像 URL 的片段里的 query：按空格切开逐段处理，避免误伤普通文案
        String[] parts = out.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            String p = parts[i];
            int cut = p.indexOf('?');
            if (cut > 0 && (p.startsWith("http://") || p.startsWith("https://"))) p = p.substring(0, cut);
            sb.append(p);
        }
        out = sb.toString();
        // token 形态：vs_ 开头的长串（服务端生成规则），整段替换为掩码
        out = out.replaceAll("vs_[A-Za-z0-9_\\-]{8,}", "<token>");
        return out;
    }
}
