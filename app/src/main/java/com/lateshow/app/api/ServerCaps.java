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
 * <p>判定策略（B-10 修订）：
 * 1) 服务端下发了非空 {@code capabilities} → **以它为准**，不含该能力就是不支持；
 * 2) 服务端没下发 {@code capabilities}（老版本只给 version）→ 回退按版本号判断
 *    （见 {@link #MIN_VERSION_FOR_CAP}）。
 *
 * <p>为什么不再「两者取或」：后端修复问题时可以把能力从清单里摘掉（例如标签索引
 * 覆盖不足时摘 {@code tag_search}），但如果客户端还拿版本号兜底，摘了等于没摘——
 * 客户端照样发新参数。能力清单是服务端的显式承诺，必须优先于版本推断。
 */
public final class ServerCaps {

    public static final String CAP_TAG_SEARCH = "tag_search";
    public static final String CAP_BACKFILL_PLAY_FROM = "backfill_play_from";
    public static final String CAP_ADFILTER = "adfilter";

    private static final java.util.Map<String, String> MIN_VERSION_FOR_CAP = new java.util.HashMap<>();
    static {
        MIN_VERSION_FOR_CAP.put(CAP_TAG_SEARCH, "1.14.0");
        MIN_VERSION_FOR_CAP.put(CAP_BACKFILL_PLAY_FROM, "1.11.0");
        MIN_VERSION_FOR_CAP.put(CAP_ADFILTER, "1.16.0");
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
        // 服务端明确给了能力清单：清单即契约，没有就是没有（B-10）
        if (!sCaps.isEmpty()) return sCaps.contains(cap);
        // 老服务端只给 version：按能力约定的最低版本推断
        String min = MIN_VERSION_FOR_CAP.get(cap);
        return min != null && !sVersion.isEmpty() && compare(sVersion, min) >= 0;
    }

    /** 服务端是否显式下发了能力清单（区分「能力为空」与「没有这个字段」） */
    public static boolean hasCapabilityList() {
        return !sCaps.isEmpty();
    }

    /** 供控制台展示：当前生效的能力清单（只读副本） */
    public static java.util.Set<String> current() {
        return sCaps;
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
