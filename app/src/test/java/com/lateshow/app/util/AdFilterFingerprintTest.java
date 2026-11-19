package com.lateshow.app.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 广告指纹库（后端 v1.17.0）客户端侧的单测：纯 Java，不依赖设备。
 *
 * <p>核心约束：
 * <ul>
 *   <li>桶序号必须与服务端口径一致（Python round = half-even，不是 Math.round）；</li>
 *   <li>桶宽随包下发，禁止硬编码；</li>
 *   <li>老规则包没有 fingerprints 时行为与 v1.16.x 逐字一致；</li>
 *   <li>指纹命中只是「判广告的快捷路径」，fail-open 安全钳绝不能被绕过。</li>
 * </ul>
 */
public class AdFilterFingerprintTest {

    private static final String BASE = "https://cdn.example.com/v/abc/index.m3u8";

    /** 两段正片 + 两段广告（两个源组，各 20s）；不命中任何内置 URL 模式。 */
    private static final String PLAYLIST =
            "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXTINF:10.0,\n"
            + "https://cdn.example.com/a/1.ts\n"
            + "#EXTINF:10.0,\n"
            + "https://cdn.example.com/a/2.ts\n"
            + "#EXT-X-DISCONTINUITY\n"
            + "#EXTINF:10.0,\n"
            + "https://other.example.com/b/1.ts\n"
            + "#EXTINF:10.0,\n"
            + "https://other.example.com/b/2.ts\n"
            + "#EXT-X-ENDLIST\n";

    private static String fp10() {
        return "{\"fingerprints\":[{"
                + "\"fp_hash\":\"adfp\",\"host\":\"cdn.example.com\","
                + "\"dur_bucket\":10,\"size_bucket\":0,"
                + "\"dur_bucket_ms\":1000,\"size_bucket_b\":65536,\"confidence\":0.95"
                + "}]}";
    }

    // ---------- 1. 桶序号归一化 ----------

    @Test
    public void bucketUsesBankersRoundingLikePython() {
        // Python round(x.5) 取偶数；Math.round 是 half-up，会在 2.5 处错判成 3
        assertEquals(2, HlsAdFilter.Fingerprint.bucket(1500, 1000));
        assertEquals(2, HlsAdFilter.Fingerprint.bucket(2500, 1000));
        assertEquals(4, HlsAdFilter.Fingerprint.bucket(3500, 1000));
        assertEquals(12, HlsAdFilter.Fingerprint.bucket(12500, 1000));
    }

    // ---------- 2. 规则解析兼容 / 校验 ----------

    @Test
    public void oldPackWithoutFingerprintsKeepsLegacyBehavior() {
        HlsAdFilter.Rules r = AdFilterRules.load("{\"thresholds\":{\"relative_max_ratio\":0.7}}");
        assertTrue(r.fingerprints.isEmpty());
        assertEquals(0.7, r.relativeMaxRatio, 0.0001);
        assertNull(r.matchFingerprint("cdn.example.com", 10000, 0));
        assertNull(HlsAdFilter.filter(BASE, PLAYLIST, r));   // 20s vs 20s 不满足 50% 阈值
    }

    @Test
    public void parseFingerprintsIndexByLowercaseHostAndSkipBadEntries() {
        String json = "{\"fingerprints\":["
                + "{\"fp_hash\":\"aaa\",\"host\":\"CDN.Example.com\",\"dur_bucket\":10,"
                + "\"size_bucket\":0,\"dur_bucket_ms\":1000,\"size_bucket_b\":65536,\"confidence\":0.95},"
                + "{\"fp_hash\":\"bad-width\",\"host\":\"x.com\",\"dur_bucket\":5,"
                + "\"size_bucket\":0,\"dur_bucket_ms\":0,\"size_bucket_b\":65536},"
                + "{\"fp_hash\":\"bad-size\",\"host\":\"x.com\",\"dur_bucket\":5,"
                + "\"size_bucket\":3,\"dur_bucket_ms\":1000,\"size_bucket_b\":0},"
                + "{\"fp_hash\":\"\",\"host\":\"x.com\",\"dur_bucket\":5,"
                + "\"size_bucket\":0,\"dur_bucket_ms\":1000,\"size_bucket_b\":0}"
                + "]}";
        HlsAdFilter.Rules r = AdFilterRules.load(json);
        assertEquals(1, r.fingerprints.size());
        assertTrue(r.fingerprints.containsKey("cdn.example.com"));
        assertEquals("aaa", r.matchFingerprint("cdn.example.com", 10000, 0));
        assertNull(r.matchFingerprint("x.com", 5000, 0));
        assertNull(r.matchFingerprint("other.com", 10000, 0));
    }

    @Test
    public void bucketWidthComesFromPackNotHardcoded() {
        String json = "{\"fingerprints\":[{"
                + "\"fp_hash\":\"wide\",\"host\":\"cdn.example.com\",\"dur_bucket\":5,"
                + "\"size_bucket\":0,\"dur_bucket_ms\":2000,\"size_bucket_b\":0,\"confidence\":0.9"
                + "}]}";
        HlsAdFilter.Rules r = AdFilterRules.load(json);
        assertEquals("wide", r.matchFingerprint("cdn.example.com", 10000, 0));  // 10000/2000=5
        assertNull(r.matchFingerprint("cdn.example.com", 9000, 0));             // 9000/2000=4.5→4
    }

    @Test
    public void brokenPackFallsBackToBuiltins() {
        HlsAdFilter.Rules r = AdFilterRules.load("{ this is not json");
        assertTrue(r.fingerprints.isEmpty());
        assertEquals(0.5, r.relativeMaxRatio, 0.0001);
    }

    // ---------- 3. 指纹命中判广告 + 上报明细 ----------

    @Test
    public void fingerprintHitRemovesAdGroupAndCollectsSegments() {
        HlsAdFilter.Rules rules = AdFilterRules.load(fp10());
        HlsAdFilter.Result res = HlsAdFilter.filter(BASE, PLAYLIST, rules);

        assertNotNull("指纹命中应直接判广告（无需走 50% 时长阈值）", res);
        assertEquals(1, res.removedRuns);
        assertTrue(res.matchedFingerprints.contains("adfp"));
        assertEquals(2, res.removedSegments.size());
        assertEquals(2, res.removedSegments.get(0).index);   // 全局序号，跨 run 累计
        assertEquals(10000L, res.removedSegments.get(0).durMs);
        assertEquals(3, res.removedSegments.get(1).index);
        assertEquals(10000L, res.removedSegments.get(1).durMs);
        assertTrue(res.playlist.contains("cdn.example.com/a/1.ts"));
        assertFalse("广告组应从重建后的列表里消失", res.playlist.contains("other.example.com"));
    }

    @Test
    public void safetyClampStillAppliesToFingerprintHits() {
        // 正片 40s + 两组指纹广告 35s/25s：删掉六成内容，即使指纹命中也必须整体放弃
        String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:40\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXTINF:10.0,\nhttps://cdn.example.com/a/1.ts\n"
                + "#EXTINF:10.0,\nhttps://cdn.example.com/a/2.ts\n"
                + "#EXTINF:10.0,\nhttps://cdn.example.com/a/3.ts\n"
                + "#EXTINF:10.0,\nhttps://cdn.example.com/a/4.ts\n"
                + "#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:35.0,\nhttps://other.example.com/b/1.ts\n"
                + "#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:25.0,\nhttps://other.example.com/c/1.ts\n"
                + "#EXT-X-ENDLIST\n";
        String pack = "{\"fingerprints\":["
                + "{\"fp_hash\":\"big1\",\"host\":\"cdn.example.com\",\"dur_bucket\":35,"
                + "\"size_bucket\":0,\"dur_bucket_ms\":1000,\"size_bucket_b\":65536,\"confidence\":0.99},"
                + "{\"fp_hash\":\"big2\",\"host\":\"cdn.example.com\",\"dur_bucket\":25,"
                + "\"size_bucket\":0,\"dur_bucket_ms\":1000,\"size_bucket_b\":65536,\"confidence\":0.99}"
                + "]}";
        HlsAdFilter.Result res = HlsAdFilter.filter(BASE, playlist, AdFilterRules.load(pack));
        assertNull("安全钳不能被指纹绕过", res);
        assertTrue(HlsAdFilter.lastRejectReason().startsWith("safety-clamp"));
    }

    @Test
    public void unknownFingerprintDoesNotAffectLegacyDetection() {
        String pack = "{\"fingerprints\":[{"
                + "\"fp_hash\":\"other\",\"host\":\"cdn.example.com\",\"dur_bucket\":3,"
                + "\"size_bucket\":0,\"dur_bucket_ms\":1000,\"size_bucket_b\":65536,"
                + "\"confidence\":0.9}]}";
        HlsAdFilter.Result res = HlsAdFilter.filter(BASE, PLAYLIST, AdFilterRules.load(pack));
        assertNull("桶不匹配时行为与老包一致", res);
    }

    /** v1.19.0 契约 §4.6：reason 字面量必须与服务端口径逐字一致，改错即服务端 400。 */
    @Test
    public void feedbackReasonLiteralsMatchServerContract() {
        assertEquals("still_has_ads", AdFilter.FP_REASON_STILL_ADS);
        assertEquals("over_deleted", AdFilter.FP_REASON_OVER_DELETED);
    }
}
