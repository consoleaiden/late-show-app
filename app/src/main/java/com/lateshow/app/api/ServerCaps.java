package com.lateshow.app.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 后端能力门控（审计 A-30 / B-10）。
 *
 * <p>{@code /healthz} 返回 {@code version} 与可选的 {@code capabilities} 数组；
 * 客户端用它决定能不能使用新版参数（例如 {@code tag_search}），避免「后端还没上、前端已经发新参数」
 * 或「后端已经上、旧客户端按老契约解析」的错配。
 *
 * <p>判定策略（双保险，后端只要满足其一即可）：
 * 1) capabilities 显式包含该能力；
 * 2) 版本号达到该能力约定的最低版本（见 {@link #MIN_VERSION_FOR_CAP}）。
 */
public final class ServerCaps {

    public static final String CAP_TAG_SEARCH = "tag_search";
    public static final String CAP_BACKFILL_PLAY_FROM = "backfill_play_from";

    private static final java.util.Map<String, String> MIN_VERSION_FOR_CAP = new java.util.HashMap<>();
    static {
        MIN_VERSION_FOR_CAP.put(CAP_TAG_SEARCH, "1.14.0");
        MIN_VERSION_FOR_CAP.put(CAP_BACKFILL_PLAY_FROM, "1.11.0");
    }

    private static volatile String sVersion = "";
    private static volatile Set<String> sCaps = Collections.emptySet();

    private ServerCaps() { }

    public static void update(String version, Set<String> caps) {
        sVersion = version == null ? "" : version.trim();
        sCaps = caps == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<>(caps));
    }

    public static String version() {
        return sVersion;
    }

    public static boolean supports(String cap) {
        if (cap == null) return false;
        if (sCaps.contains(cap)) return true;
        String min = MIN_VERSION_FOR_CAP.get(cap);
        return min != null && !sVersion.isEmpty() && compare(sVersion, min) >= 0;
    }

    /** 语义化版本比较，忽略后缀（1.14.0-rc1 → 1.14.0）；非数字段按 0 处理。纯函数，可单测。 */
    public static int compare(String a, String b) {
        int[] x = parts(a), y = parts(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int l = i < x.length ? x[i] : 0;
            int r = i < y.length ? y[i] : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    private static int[] parts(String v) {
        if (v == null || v.trim().isEmpty()) return new int[0];
        String s = v.trim();
        int cut = s.indexOf('-');
        if (cut > 0) s = s.substring(0, cut);
        String[] p = s.split("\\.");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try { out[i] = Integer.parseInt(p[i].replaceAll("[^0-9]", "")); }
            catch (Exception e) { out[i] = 0; }
        }
        return out;
    }
}
