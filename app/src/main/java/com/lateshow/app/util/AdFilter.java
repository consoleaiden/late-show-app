package com.lateshow.app.util;

import android.net.Uri;

import com.google.gson.JsonObject;
import com.lateshow.app.api.ServerCaps;
import com.lateshow.app.api.VodSyncApi;
import com.lateshow.app.store.PrefStore;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能去广告门面：开关 + 过滤入口 + 规则包拉取（TTL 30min）+ 命中上报。
 *
 * <p>分级降级设计（Phase 1 = App 端内置规则本地过滤）：
 * <ul>
 *   <li>老后端（healthz 无 adfilter 能力）→ 规则拉取与上报整体跳过，零额外请求；</li>
 *   <li>新后端但接口失败 → 静默跳过，内置规则照常工作，缓存键到下一 TTL 才重试；</li>
 *   <li>用户关开关 → {@link #filter(Uri, byte[])} 直接放行，DataSource 无行为差异。</li>
 * </ul>
 * 所有回调失败均静默：广告过滤永远不能影响播放可用性。
 */
public final class AdFilter {

    /** 后端能力标识（/healthz capabilities；详见 docs/ADFILTER_BACKEND_SPEC.md） */
    public static final String CAP = "adfilter";
    private static final String TAG = "AdFilter";
    private static final long PULL_TTL_MS = 30 * 60 * 1000L;
    private static final int REPORT_BATCH = 5;
    private static final int REPORT_MAX = 50;
    /** 上报失败后的静默期：避免每次起播都去撞一个正在 429/断网的接口 */
    private static final long REPORT_BACKOFF_MS = 10 * 60 * 1000L;
    /** 删除区间缓存条数（每条一个播放列表，几十条足够覆盖最近看的片子） */
    private static final int SPAN_CACHE_MAX = 32;

    private static volatile boolean pulling;
    private static volatile HlsAdFilter.Rules sRules;
    private static final Object RULES_LOCK = new Object();

    /** 上报退避截止时刻（ms）；0 = 无退避 */
    private static volatile long sReportBackoffUntil;

    private AdFilter() { }

    // ==================== 过滤入口（AdFilterDataSource 调用） ====================

    /** 最近一次未执行过滤的原因（诊断用）；null = 本次实际执行了过滤 */
    private static volatile String skipReason;

    public static String lastSkipReason() { return skipReason; }

    /**
     * 最近一次命中摘要，例如 {@code 2runs/51s of 3061s}；null = 还没命中过。
     *
     * <p>日志里只打 {@code swapped=true} 说明不了「删对了量」——服务端上报失败时
     * 这条摘要就是唯一还能看到的证据，排查「明明开关开着却没去广告」时必须靠它。
     */
    private static volatile String lastSummary;

    public static String lastSummary() { return lastSummary; }

    /**
     * @return 过滤后的播放列表字节；null = 原样放行（开关关/站点 off/未命中/解析失败/安全钳）
     */
    public static byte[] filter(Uri uri, byte[] body) {
        if (uri == null || body == null || body.length == 0) {
            skipReason = "empty-input";
            return null;
        }
        if (!PrefStore.get().adFilter()) {
            skipReason = "switch-off";
            return null;
        }
        HlsAdFilter.Rules rules = rules();
        if (rules.offFor(uri.getHost())) {
            skipReason = "site-off";
            forgetSpans(uri.toString());   // 该站配置为不过滤：历史区间作废，别拿它算时间轴
            return null;
        }
        HlsAdFilter.Result res = HlsAdFilter.filter(uri.toString(),
                new String(body, StandardCharsets.UTF_8), rules);
        if (res == null) {
            skipReason = "filter:" + HlsAdFilter.lastRejectReason();
            forgetSpans(uri.toString());   // 本次没有删除：旧区间已不代表当前时间轴
            return null;
        }
        skipReason = null;
        lastSummary = res.removedRuns + "runs/" + (res.removedMs / 1000) + "s of "
                + (res.totalMs / 1000) + "s";
        rememberSpans(uri.toString(), res.removedSpans);
        record(uri.toString(), res);
        return res.playlist.getBytes(StandardCharsets.UTF_8);
    }

    private static HlsAdFilter.Rules rules() {
        HlsAdFilter.Rules r = sRules;
        if (r == null) {
            synchronized (RULES_LOCK) {
                if (sRules == null) sRules = AdFilterRules.load(PrefStore.get().adFilterPack());
                r = sRules;
            }
        }
        return r;
    }

    // ==================== 删除区间缓存：原始时间轴 ↔ 过滤后时间轴 ====================
    //
    // 过滤把广告整段删掉，播放器时间轴因此变短：过滤后 0s 可能对应原始 25.8s。
    // 历史记录里存的是「原始时间轴」的位置（跨设备、跨是否开过滤都一致），
    // 起播时要正向映射，回写进度时要反向映射，否则续播会整体前移一个广告时长。
    // 缓存必须能跨进程：用户通常退出 App 之后再回来续播，纯内存缓存那时已经没了。

    /** URL(去 query) -> 删除区间 [s0,e0,s1,e1,...]，原时间轴 ms；访问序 LRU */
    private static final Map<String, long[]> sSpans =
            new LinkedHashMap<String, long[]>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, long[]> e) {
                    return size() > SPAN_CACHE_MAX;
                }
            };
    private static boolean sSpansLoaded;

    private static void rememberSpans(String url, long[] spans) {
        if (spans == null || spans.length < 2) return;
        String key = spanKey(url);
        if (key == null) return;
        ensureSpansLoaded();
        synchronized (sSpans) { sSpans.put(key, spans); }
        persistSpans();
    }

    /**
     * 丢弃某条播放地址的删除区间。
     *
     * <p>两种情况必须丢弃：站点被配置成不过滤（{@code mode:off}），或本次没删成功
     * （fail-open）。否则缓存里那份旧区间会继续参与时间轴换算，把位置算到不存在的地方。
     */
    private static void forgetSpans(String url) {
        String key = spanKey(url);
        if (key == null) return;
        ensureSpansLoaded();
        boolean removed;
        synchronized (sSpans) { removed = sSpans.remove(key) != null; }
        if (removed) persistSpans();
    }

    private static void ensureSpansLoaded() {
        if (sSpansLoaded) return;
        synchronized (sSpans) {
            if (sSpansLoaded) return;
            sSpansLoaded = true;
            try {
                String raw = PrefStore.get().adFilterSpans();
                if (raw != null && !raw.isEmpty()) {
                    com.google.gson.JsonObject o =
                            com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
                    for (String k : o.keySet()) {
                        com.google.gson.JsonArray a = o.getAsJsonArray(k);
                        if (a == null || a.size() < 2) continue;
                        long[] arr = new long[a.size()];
                        for (int i = 0; i < arr.length; i++) arr[i] = a.get(i).getAsLong();
                        sSpans.put(k, arr);
                    }
                }
            } catch (Exception e) {
                AppLog.w(TAG, "span cache load fail: " + e);   // 坏数据只影响映射，清空即可
            }
        }
    }

    private static void persistSpans() {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            synchronized (sSpans) {
                for (Map.Entry<String, long[]> e : sSpans.entrySet()) {
                    com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                    for (long v : e.getValue()) a.add(v);
                    o.add(e.getKey(), a);
                }
            }
            PrefStore.get().setAdFilterSpans(o.toString());
        } catch (Exception e) {
            AppLog.w(TAG, "span cache save fail: " + e);
        }
    }

    /** 归一化成 scheme://host/path（丢掉 query）：同一个列表换签名参数不该换缓存键 */
    private static String spanKey(String url) {
        if (url == null) return null;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null || host == null) return null;
            return scheme + "://" + host + (u.getPath() == null ? "" : u.getPath());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取某条播放地址对应的删除区间。
     *
     * <p>起播地址通常是 master（{@code .../QTxjYTKu/index.m3u8}），而过滤命中的是它下面
     * 的变体列表（{@code .../QTxjYTKu/3375kb/hls/index.m3u8}），两者不相等。变体路径必然以
     * master 所在目录为前缀，所以精确未命中时按目录前缀回退，取最近使用的那条。
     */
    private static long[] spansFor(String url) {
        String key = spanKey(url);
        if (key == null) return null;
        ensureSpansLoaded();
        synchronized (sSpans) {
            long[] exact = sSpans.get(key);
            if (exact != null) return exact;
            int cut = key.lastIndexOf('/');
            if (cut < 0) return null;
            String dir = key.substring(0, cut + 1);
            String hit = null;
            for (String k : sSpans.keySet()) {      // 迭代中不能 get（accessOrder 会 CME）
                if (k.startsWith(dir)) hit = k;     // 迭代序=访问序，最后一个即最近使用的
            }
            return hit == null ? null : sSpans.get(hit);
        }
    }

    /**
     * 原始时间轴 → 过滤后时间轴。落在被删区间内的位置前移到该区间起点。
     * 无缓存 / 无删除时原值返回，恒不为负。
     */
    public static long mapStartMs(String url, long rawMs) {
        if (rawMs <= 0) return Math.max(0, rawMs);
        // 开关关着时时间轴就是原始轴，缓存里残留的旧区间一律不参与换算
        if (!PrefStore.get().adFilter()) return rawMs;
        long[] spans = spansFor(url);
        if (spans == null || spans.length < 2) return rawMs;
        long shift = 0;
        for (int i = 0; i + 1 < spans.length; i += 2) {
            long s = spans[i], e = spans[i + 1];
            if (rawMs >= e) {
                shift += (e - s);
            } else if (rawMs > s) {
                return Math.max(0, s - shift);   // 原位置正好在广告里：跳到广告结束处
            } else {
                break;
            }
        }
        return Math.max(0, rawMs - shift);
    }

    /** 过滤后时间轴 → 原始时间轴（进度回写用，保证历史里存的永远是原始位置） */
    public static long unmapPosition(String url, long posMs) {
        if (posMs <= 0) return posMs;
        if (!PrefStore.get().adFilter()) return posMs;   // 关闭时播放器时间轴即原始轴
        long[] spans = spansFor(url);
        if (spans == null || spans.length < 2) return posMs;
        long shift = 0;
        for (int i = 0; i + 1 < spans.length; i += 2) {
            long newStart = spans[i] - shift;    // 该删除区间在过滤后轴上的起点
            if (posMs >= newStart) {
                shift += (spans[i + 1] - spans[i]);
            } else {
                break;
            }
        }
        return posMs + shift;
    }

    /** 设置页展示用：规则来源 + 版本 + 待上报条数 */
    public static String statusLine() {
        int ver = PrefStore.get().adFilterPackVersion();
        String src;
        if (ver > 0) {
            src = "服务端规则 v" + ver + (PrefStore.get().adFilterBeta() ? "（内测通道）" : "");
        } else {
            src = ServerCaps.supports(CAP) ? "内置规则（尚未拉到服务端包）" : "内置规则（后端不支持）";
        }
        int pending = PrefStore.get().adFilterReports().size();
        return "规则：" + src + (pending > 0 ? " · 待上报 " + pending + " 条" : "");
    }

    /** 设置页「立即刷新规则」：清掉 TTL 防抖后重新拉取（失败仍静默，不影响播放） */
    public static void refreshNow() {
        PrefStore.get().setAdFilterPullAt(0L);
        ensureFresh();
    }

    // ==================== 播放入口调用：规则保鲜 + 冲刷上报 ====================

    /** 在 PlayerActivity / DetailFragment 起播时调用；TTL 内重复调用只冲刷上报队列 */
    public static void ensureFresh() {
        try {
            long now = System.currentTimeMillis();
            if (now - PrefStore.get().adFilterPullAt() < PULL_TTL_MS) {
                flushReports();
                return;
            }
            if (pulling) return;
            if (!ServerCaps.supports(CAP)) return;   // 老后端：零请求，内置规则照常
            pulling = true;
            PrefStore.get().setAdFilterPullAt(now);  // 防抖：失败也等下一 TTL，避免每次播放都打接口
            Map<String, String> q = new HashMap<>();
            q.put("version", String.valueOf(PrefStore.get().adFilterPackVersion()));
            // 内测通道：后端按 channel 分版本线，未知值会按 stable 处理，不会有 4xx 风险
            q.put("channel", PrefStore.get().adFilterBeta() ? "beta" : "stable");
            VodSyncApi.get().get(ApiPaths.ADFILTER_RULES, q, JsonObject.class,
                    new VodSyncApi.ApiCb<JsonObject>() {
                        @Override public void onOk(JsonObject resp) {
                            pulling = false;
                            applyPull(resp);
                            flushReports();
                        }

                        @Override public void onErr(String msg) {
                            pulling = false;   // 静默：内置规则继续工作
                        }
                    });
        } catch (Exception e) {
            pulling = false;
            AppLog.w(TAG, "ensureFresh fail: " + e);
        }
    }

    private static void applyPull(JsonObject resp) {
        if (resp == null || !resp.has("changed") || !resp.get("changed").isJsonPrimitive()
                || !resp.get("changed").getAsBoolean()) return;
        if (!resp.has("rules") || !resp.get("rules").isJsonObject()) return;
        int ver = PrefStore.get().adFilterPackVersion() + 1;
        if (resp.has("version") && resp.get("version").isJsonPrimitive()) {
            try {
                ver = resp.get("version").getAsInt();
            } catch (Exception ignored) { }
        }
        PrefStore.get().setAdFilterPack(resp.get("rules").toString(), ver);
        sRules = null;   // 下次过滤时按新包重建
        AppLog.d(TAG, "rule pack updated -> v" + ver);
    }

    // ==================== 命中上报（批量、可丢、不影响播放） ====================

    private static void record(String url, HlsAdFilter.Result r) {
        try {
            if (!ServerCaps.supports(CAP)) return;   // 老后端不攒队列
            JsonObject it = new JsonObject();
            it.addProperty("url", url);
            it.addProperty("rule_version", PrefStore.get().adFilterPackVersion());
            it.addProperty("removed_runs", r.removedRuns);
            it.addProperty("removed_ms", r.removedMs);
            it.addProperty("total_ms", r.totalMs);
            it.addProperty("at", System.currentTimeMillis() / 1000L);
            List<String> list = PrefStore.get().adFilterReports();
            list.add(it.toString());
            while (list.size() > REPORT_MAX) list.remove(0);
            PrefStore.get().setAdFilterReports(list);
            if (list.size() >= REPORT_BATCH) flushReports();
        } catch (Exception e) {
            AppLog.w(TAG, "record fail: " + e);
        }
    }

    /** 上报队列快照；成功即清空，失败保留到下次（服务端幂等：按 url+at 去重） */
    private static void flushReports() {
        try {
            long now = System.currentTimeMillis();
            if (now < sReportBackoffUntil) return;   // 退避中：刚失败过，别每次起播都去撞
            List<String> list = PrefStore.get().adFilterReports();
            if (list.isEmpty() || pulling) return;
            if (!ServerCaps.supports(CAP)) return;
            StringBuilder body = new StringBuilder("{\"items\":[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) body.append(',');
                body.append(list.get(i));
            }
            body.append("]}");
            final int sent = list.size();
            VodSyncApi.get().postJson(ApiPaths.ADFILTER_REPORT, body.toString(), JsonObject.class,
                    new VodSyncApi.ApiCb<JsonObject>() {
                        @Override public void onOk(JsonObject resp) {
                            sReportBackoffUntil = 0L;
                            PrefStore.get().setAdFilterReports(null);
                            // 服务端把 dropped/duplicates 分开报，正好区分「数据脏」与「重复重试」
                            String stat = "";
                            try {
                                if (resp != null) {
                                    stat = " accepted=" + resp.get("accepted")
                                            + " dropped=" + resp.get("dropped")
                                            + " dup=" + resp.get("duplicates");
                                }
                            } catch (Exception ignored) { }
                            AppLog.d(TAG, "report ok " + sent + " item(s)" + stat);
                        }

                        @Override public void onErr(String msg) {
                            // 保留队列，等退避结束后的下一次 TTL / 起播触发
                            sReportBackoffUntil = System.currentTimeMillis() + REPORT_BACKOFF_MS;
                            AppLog.w(TAG, "report fail(" + sent + "): " + msg);
                        }
                    });
        } catch (Exception e) {
            AppLog.w(TAG, "flushReports fail: " + e);
        }
    }
}
