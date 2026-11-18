package com.lateshow.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 智能去广告规则装载：内置默认 + 后端规则包（AdFilter.ensureFresh 拉取，PrefStore 持久化）合并。
 *
 * <p>设计原则：
 * <ul>
 *   <li>规则只是「算法参数 + 高置信模式 + 站点例外」，不是通用 DSL——引擎不做解释器；</li>
 *   <li>坏包（JSON 非法 / 字段越界）整体丢弃回退内置，绝不部分采纳；</li>
 *   <li>所有数值在装载时钳制到安全区间，规则包不能把算法调成必然误删（如 ratio=0.99）；</li>
 *   <li>未知字段一律忽略：老 App 收到新字段不报错，向前兼容。</li>
 * </ul>
 */
public final class AdFilterRules {

    private static final String TAG = "AdFilter";

    private AdFilterRules() { }

    /** @param packJson 后端规则包原文（JsonObject.toString），空/null = 仅内置规则 */
    public static HlsAdFilter.Rules load(String packJson) {
        HlsAdFilter.Rules r = new HlsAdFilter.Rules();
        if (packJson == null || packJson.trim().isEmpty()) return r;
        try {
            JsonObject p = JsonParser.parseString(packJson).getAsJsonObject();

            if (p.has("thresholds") && p.get("thresholds").isJsonObject()) {
                JsonObject t = p.getAsJsonObject("thresholds");
                r.relativeMaxRatio = clamp(dbl(t, "relative_max_ratio", r.relativeMaxRatio), 0.05, 0.95);
                r.absoluteMaxSec = clampL(long_(t, "absolute_max_sec", r.absoluteMaxSec), 10, 1800);
                r.safetyFloor = clamp(dbl(t, "safety_floor", r.safetyFloor), 0.5, 1.0);
                // 高置信场景（命中「同一批分片被贴两遍」）的下限。不允许低于 0.4：
                // 再低就意味着允许删掉一大半内容，任何误判都会毁掉播放。
                r.safetyFloorHighConfidence = clamp(
                        dbl(t, "safety_floor_high_confidence", r.safetyFloorHighConfidence), 0.4, 1.0);
                // 「重复插入」判据的相对上限，只允许往保守方向调（0.3~0.9）
                r.duplicateMaxRatio = clamp(
                        dbl(t, "duplicate_max_ratio", r.duplicateMaxRatio), 0.3, 0.9);
            }

            if (p.has("url_patterns") && p.get("url_patterns").isJsonArray()) {
                List<String> list = new ArrayList<>();
                JsonArray arr = p.getAsJsonArray("url_patterns");
                for (JsonElement e : arr) {
                    if (e.isJsonPrimitive()) {
                        String s = e.getAsString().toLowerCase(Locale.ROOT).trim();
                        if (!s.isEmpty() && list.size() < 64) list.add(s);
                    }
                }
                if (!list.isEmpty()) r.urlPatterns = list;   // 空数组视为无效，保留内置
            }

            if (p.has("site_overrides") && p.get("site_overrides").isJsonArray()) {
                JsonArray arr = p.getAsJsonArray("site_overrides");
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String host = str(o, "host");
                    String mode = str(o, "mode");
                    if (!host.isEmpty() && !mode.isEmpty() && r.siteMode.size() < 256) {
                        r.siteMode.put(host.toLowerCase(Locale.ROOT), mode.toLowerCase(Locale.ROOT));
                    }
                }
            }

            // v1.17.0 广告指纹库（可选字段）：老包 / 空库没有 fingerprints 时完全跳过，
            // 行为与 v1.16.x 逐字一致。单条坏指纹只丢该条，不让整包降级——指纹是增强
            // 判据，阈值 / 站点覆盖这些核心规则不能因为一条脏指纹一起失效。
            // 桶宽一律读下发字段，禁止硬编码 1000 / 65536（服务端可配）。
            if (p.has("fingerprints") && p.get("fingerprints").isJsonArray()) {
                JsonArray arr = p.getAsJsonArray("fingerprints");
                int loaded = 0;
                for (JsonElement e : arr) {
                    if (!e.isJsonObject() || loaded >= 256) continue;
                    JsonObject o = e.getAsJsonObject();
                    String hash = str(o, "fp_hash");
                    String host = str(o, "host").toLowerCase(Locale.ROOT);
                    long durWidth = long_(o, "dur_bucket_ms", 0);
                    long sizeWidth = long_(o, "size_bucket_b", 0);
                    long durBucket = long_(o, "dur_bucket", -1);
                    long sizeBucket = long_(o, "size_bucket", -1);
                    double confidence = dbl(o, "confidence", 0.5);
                    if (hash.isEmpty() || host.isEmpty() || durWidth <= 0 || sizeWidth < 0
                            || durBucket < 0 || sizeBucket < 0) continue;
                    // size_bucket_b=0（服务端关闭大小分桶）时 size_bucket 必为 0；
                    // 反之拿到了非零桶号却没有桶宽，宁可丢掉这条也不做错匹配。
                    if (sizeWidth == 0 && sizeBucket != 0) continue;
                    HlsAdFilter.Fingerprint fp = new HlsAdFilter.Fingerprint(
                            hash, host,
                            (int) Math.min(durBucket, Integer.MAX_VALUE),
                            (int) Math.min(sizeBucket, Integer.MAX_VALUE),
                            (int) Math.min(durWidth, Integer.MAX_VALUE),
                            (int) Math.min(sizeWidth, Integer.MAX_VALUE),
                            clamp(confidence, 0.0, 1.0));
                    List<HlsAdFilter.Fingerprint> list = r.fingerprints.get(host);
                    if (list == null) {
                        list = new ArrayList<>();
                        r.fingerprints.put(host, list);
                    }
                    list.add(fp);
                    loaded++;
                }
                if (loaded > 0) AppLog.d(TAG, "rule pack fingerprints=" + loaded);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "bad rule pack -> builtin: " + e);
            return new HlsAdFilter.Rules();
        }
        return r;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }

    private static double dbl(JsonObject o, String k, double def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static long long_(JsonObject o, String k, long def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    private static long clampL(long v, long lo, long hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
