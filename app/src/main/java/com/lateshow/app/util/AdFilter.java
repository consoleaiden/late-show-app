package com.lateshow.app.util;

import android.net.Uri;

import com.google.gson.JsonObject;
import com.lateshow.app.api.ServerCaps;
import com.lateshow.app.api.VodSyncApi;
import com.lateshow.app.store.PrefStore;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

    private static volatile boolean pulling;
    private static volatile HlsAdFilter.Rules sRules;
    private static final Object RULES_LOCK = new Object();

    private AdFilter() { }

    // ==================== 过滤入口（AdFilterDataSource 调用） ====================

    /**
     * @return 过滤后的播放列表字节；null = 原样放行（开关关/站点 off/未命中/解析失败/安全钳）
     */
    public static byte[] filter(Uri uri, byte[] body) {
        if (uri == null || body == null || body.length == 0 || !PrefStore.get().adFilter()) return null;
        HlsAdFilter.Rules rules = rules();
        if (rules.offFor(uri.getHost())) return null;
        HlsAdFilter.Result res = HlsAdFilter.filter(uri.toString(),
                new String(body, StandardCharsets.UTF_8), rules);
        if (res == null) return null;
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
            List<String> list = PrefStore.get().adFilterReports();
            if (list.isEmpty() || pulling) return;
            if (!ServerCaps.supports(CAP)) return;
            StringBuilder body = new StringBuilder("{\"items\":[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) body.append(',');
                body.append(list.get(i));
            }
            body.append("]}");
            VodSyncApi.get().postJson(ApiPaths.ADFILTER_REPORT, body.toString(), JsonObject.class,
                    new VodSyncApi.ApiCb<JsonObject>() {
                        @Override public void onOk(JsonObject resp) {
                            PrefStore.get().setAdFilterReports(null);
                        }

                        @Override public void onErr(String msg) {
                            // 保留队列，等下一次 TTL / 播放触发；条数上限自动滚动丢弃最旧
                        }
                    });
        } catch (Exception e) {
            AppLog.w(TAG, "flushReports fail: " + e);
        }
    }
}
