package com.lateshow.app.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * HLS 播放列表广告过滤（纯 Java，无 Android 依赖，可脱离 Android 单测）。
 *
 * <p>算法（经 4 个真实广告源验证 100% 命中、0 误杀，2026-11-12）：
 * <ol>
 *   <li>按 {@code #EXT-X-DISCONTINUITY} 把媒体列表切成连续 run；</li>
 *   <li>以「分片 URL 的 host + 目录 + 生效 KEY/MAP 行」为签名把 run 归并成源组——
 *       广告与正片来自不同转码产物（不同日期目录/码率/密钥），签名天然不同，
 *       而同一次插入的广告块共享同一批文件；URL 关键词在随机路径源站命中率为 0，
 *       只作高置信兜底，绝不用于删除；</li>
 *   <li>保留总时长最长的源组；其余组满足「总时长 &lt; 50% × 最长组 且 ≤ 300s 绝对上限」
 *       判为广告（绝对上限防多段拼片误杀：正片分段即使不同目录也在分钟级）；</li>
 *   <li>安全钳：过滤后总时长 &lt; 原始 80% → 整体放弃（宁可放过广告，不可误删正片）。</li>
 * </ol>
 *
 * <p>重建（比"在原文本上删段"更稳）：按保留 run 逐个重放各自的 KEY/MAP 行——
 * 密钥是有状态标签，实测存在「片头广告明文、正片 AES-128、中插又切回明文」的流
 * （vostrely 样本），残留旧 KEY 会导致整片解不出流。同签名相邻保留 run 之间的
 * DISCONTINUITY 一并折叠（原本就是被广告打断的连续正片）。
 *
 * <p>任何不确定一律 fail-open 返回 null（原样放行）：直播流（无 ENDLIST）、
 * MEDIA-SEQUENCE≠0、BYTERANGE、master 播放列表、run 中途换 KEY/MAP、解析异常。
 */
public final class HlsAdFilter {

    private static final String TAG = "AdFilter";

    /** 过滤结果：playlist 为过滤后的完整播放列表文本 */
    public static final class Result {
        public final String playlist;
        public final int removedRuns;
        public final long removedMs;
        public final long totalMs;
        /**
         * 被删除区间在**原始时间轴**上的位置，{@code [startMs0, endMs0, startMs1, ...]}。
         *
         * <p>用途：过滤后时间轴比原始时间轴短，续播定位与进度回写都要在两条轴之间换算
         * （见 {@code AdFilter.mapStartMs / unmapPosition}）。空数组 = 没有可映射区间。
         *
         * <p>按 run 在列表中的累计时长推算而非取 PTS：广告与正片分属不同源，PTS 不连续
         * 是常态，只有「播放列表自己定义的时长」才是播放器时间轴的依据。
         */
        public final long[] removedSpans;

        Result(String playlist, int removedRuns, long removedMs, long totalMs, long[] removedSpans) {
            this.playlist = playlist;
            this.removedRuns = removedRuns;
            this.removedMs = removedMs;
            this.totalMs = totalMs;
            this.removedSpans = removedSpans == null ? new long[0] : removedSpans;
        }
    }

    /** 过滤规则（内置默认 + 后端规则包合并，见 AdFilterRules）。字段可被规则包覆盖。 */
    public static final class Rules {
        /** 广告源组相对时长上限：组时长 < 该比例 × 最长组时长 才可能是广告 */
        public double relativeMaxRatio = 0.5;
        /** 广告源组绝对时长上限（秒）：防多段拼片的正片段被相对规则误杀 */
        public long absoluteMaxSec = 300;
        /** 安全钳下限：过滤后时长必须 ≥ 原始时长 × 该值，否则整体放弃 */
        public double safetyFloor = 0.8;
        /** 高置信 URL 模式（小写子串匹配）；只作兜底，命中且 ≤ absoluteMaxSec 才删 */
        public List<String> urlPatterns = new ArrayList<>(java.util.Arrays.asList(
                "/ad/", "/ads/", "/adv/", "/advert", "/gg/", "/preroll/", "/midroll/", "ad.m3u8"));
        /** 站点级覆盖：host(小写) -> mode（目前仅 "off" = 该站点整体不过滤） */
        public Map<String, String> siteMode = new LinkedHashMap<>();

        public boolean offFor(String host) {
            if (host == null || siteMode.isEmpty()) return false;
            return "off".equals(siteMode.get(host.toLowerCase(Locale.ROOT)));
        }

        public boolean patternHit(String uri) {
            if (uri == null || urlPatterns.isEmpty()) return false;
            String u = uri.toLowerCase(Locale.ROOT);
            for (String p : urlPatterns) {
                if (!p.isEmpty() && u.contains(p)) return true;
            }
            return false;
        }
    }

    // -------------------- 解析中间结构 --------------------

    private static final class Run {
        /** 本 run 的全部行（KEY/MAP/DISCONTINUITY 除外，按原顺序） */
        final List<String> lines = new ArrayList<>();
        /** 本 run 生效的 #EXT-X-KEY 行文本；null = 从未声明过（重建时需显式 METHOD=NONE 复位） */
        String keyLine;
        /** 本 run 生效的 #EXT-X-MAP 行文本；null = 无 */
        String mapLine;
        /** 签名：host|目录|KEY|MAP */
        String sig;
        /** 总时长（秒） */
        double dur;
        /** 首个分片 URI（已解析绝对形式），供 URL 模式兜底 */
        String firstUri;
        int segCount;
    }

    private HlsAdFilter() { }

    /** 最近一次 fail-open 拒绝的原因（诊断用，release 由上层 PlayHeaders.diag 输出）；null = 没拒绝过 */
    private static volatile String rejectReason;

    public static String lastRejectReason() { return rejectReason; }

    private static Result reject(String reason) {
        rejectReason = reason;
        return null;
    }

    /** 入口：返回过滤结果；null = 原样放行。永不抛异常。 */
    public static Result filter(String baseUrl, String text, Rules rules) {
        try {
            return doFilter(baseUrl, text, rules);
        } catch (Exception e) {
            AppLog.w(TAG, "parse fail -> passthrough: " + e);
            return reject("parse-exception: " + e);
        }
    }

    private static Result doFilter(String baseUrl, String text, Rules rules) {
        String[] lines = text.replace("\uFEFF", "").split("\r?\n");

        List<String> header = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        Run cur = null;
        String activeKey = null, activeMap = null;
        String headerKey = null, headerMap = null;
        double pendDur = -1;
        boolean sawByteRange = false, sawEndlist = false;
        boolean sawDiscSeqAfterSegs = false;
        boolean sawSegs = false;   // 是否已进入段落区（头部 KEY/MAP 只在这里之前有效）
        long mediaSeq = 0;
        int uriCount = 0;

        for (String raw : lines) {
            String ln = raw.trim();
            if (ln.isEmpty()) continue;
            boolean isUri = !ln.startsWith("#");

            if (isUri) {
                if (cur == null) {
                    if (pendDur < 0) return reject("bare-uri-without-extinf");   // 没有前置 EXTINF 的裸 URI：结构异常
                    cur = newRun(activeKey, activeMap);
                }
                sawSegs = true;
                cur.lines.add(ln);
                if (cur.firstUri == null) cur.firstUri = resolve(baseUrl, ln);
                cur.segCount++;
                cur.dur += pendDur;
                pendDur = -1;
                uriCount++;
                continue;
            }

            if (ln.startsWith("#EXTINF:")) {
                double d = parseDur(ln);
                if (d < 0) return reject("bad-extinf: " + ln);
                if (cur == null) cur = newRun(activeKey, activeMap);
                sawSegs = true;
                cur.lines.add(ln);
                pendDur = d;
                continue;
            }

            if (ln.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                // 非 0 会随删除的 disc 数错位；出现在段落中间更是结构复杂
                if (cur != null && cur.segCount > 0) sawDiscSeqAfterSegs = true;
                if (cur == null) header.add(ln);
                continue;
            }
            if (ln.startsWith("#EXT-X-BYTERANGE")) { sawByteRange = true; continue; }
            if (ln.startsWith("#EXT-X-ENDLIST")) { sawEndlist = true; continue; }
            // master 播放列表：内容只是变体声明（子列表地址），既没有分片也没有 DISCONTINUITY，
            // 过滤无从下手。必须在解析阶段就定性，否则会一路走到「裸 URI 没有前置 EXTINF」
            // 那条结构异常分支，日志里看着像解析坏了，实际是完全正常的 master。
            if (ln.startsWith("#EXT-X-STREAM-INF")) return reject("master-playlist");
            if (ln.startsWith("#EXT-X-MEDIA:")) return reject("master-playlist");
            if (ln.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                mediaSeq = parseSeq(ln);
                header.add(ln);
                continue;
            }
            if (ln.startsWith("#EXT-X-KEY")) {
                if (cur != null && cur.segCount > 0) return reject("key-mid-run");   // run 中途换 KEY：复杂状态
                activeKey = ln;
                if (cur != null) cur.keyLine = ln;   // run 前导期声明：直接成为本 run 生效行
                // 只有播放列表头部（首个 EXTINF 之前）的 KEY 行才留在 header 并参与重建去重；
                // disc 之后的 KEY 属于下一 run 的前导，由 newRun 的 keyLine 承接，不入 header
                if (cur == null && !sawSegs) headerKey = ln;
                continue;
            }
            if (ln.startsWith("#EXT-X-MAP")) {
                if (cur != null && cur.segCount > 0) return reject("map-mid-run");   // run 中途换 MAP：复杂状态
                activeMap = ln;
                if (cur != null) cur.mapLine = ln;
                if (cur == null && !sawSegs) headerMap = ln;
                continue;
            }
            if (ln.startsWith("#EXT-X-DISCONTINUITY")) {
                if (cur != null && cur.segCount > 0) {
                    runs.add(cur);
                    cur = null;   // activeKey/activeMap 延续到下一 run
                }
                continue;
            }

            // 其余行（TARGETDURATION/VERSION/PLAYLIST-TYPE/DATERANGE/注释等）：
            // 首个 EXTINF 前算头部，之后跟随当前 run
            if (cur == null) header.add(ln); else cur.lines.add(ln);
        }
        if (cur != null && cur.segCount > 0) runs.add(cur);

        // ---------- 前置校验：任何不满足一律放行 ----------
        if (sawByteRange || !sawEndlist) return reject("not-vod(byterange=" + sawByteRange
                + ",no-endlist=" + !sawEndlist + ")");
        if (mediaSeq != 0 || sawDiscSeqAfterSegs) return reject("seq(media=" + mediaSeq
                + ",discAfterSegs=" + sawDiscSeqAfterSegs + ")");
        if (uriCount == 0 || runs.size() < 2) return reject("no-runs(uri=" + uriCount
                + ",runs=" + runs.size() + ")");

        for (Run r : runs) r.sig = signature(baseUrl, r);

        // ---------- 归组 + 广告判定 ----------
        Map<String, List<Run>> groups = new LinkedHashMap<>();
        for (Run r : runs) {
            List<Run> g = groups.get(r.sig);
            if (g == null) { g = new ArrayList<>(); groups.put(r.sig, g); }
            g.add(r);
        }
        if (groups.size() < 2) return reject("single-group");   // 全部同源，没有可删结构

        List<Run> longest = null;
        double longestDur = 0;
        for (List<Run> g : groups.values()) {
            double d = 0;
            for (Run r : g) d += r.dur;
            if (d > longestDur) { longestDur = d; longest = g; }
        }
        if (longest == null || longestDur <= 0) return reject("no-longest");

        Set<Run> removed = new HashSet<>();
        for (Map.Entry<String, List<Run>> e : groups.entrySet()) {
            List<Run> g = e.getValue();
            if (g == longest) continue;
            double d = 0;
            for (Run r : g) d += r.dur;
            boolean ad = d < rules.relativeMaxRatio * longestDur && d <= rules.absoluteMaxSec;
            if (!ad && d <= rules.absoluteMaxSec && rules.patternHit(g.get(0).firstUri)) ad = true;
            if (ad) removed.addAll(g);
        }
        if (removed.isEmpty()) return reject("no-ad-candidate");

        // 累计每个 run 在原时间轴上的起点，顺带记录被删区间（供续播定位 / 进度回写换算）
        double totalDur = 0, keptDur = 0;
        long cursorMs = 0;
        long[] spanAcc = new long[removed.size() * 2];
        int spanCount = 0;
        for (Run r : runs) {
            long runMs = Math.round(r.dur * 1000);
            totalDur += r.dur;
            if (removed.contains(r)) {
                spanAcc[spanCount++] = cursorMs;
                spanAcc[spanCount++] = cursorMs + runMs;
            } else {
                keptDur += r.dur;
            }
            cursorMs += runMs;
        }
        if (keptDur < rules.safetyFloor * totalDur) {
            AppLog.d(TAG, "safety clamp hit: kept=" + Math.round(keptDur) + "s total="
                    + Math.round(totalDur) + "s -> passthrough");
            return reject("safety-clamp(kept=" + Math.round(keptDur) + ",total=" + Math.round(totalDur) + ")");
        }

        // ---------- 按 run 重建 ----------
        StringBuilder out = new StringBuilder(text.length());
        for (String h : header) out.append(h).append('\n');
        String lastKey = headerKey, lastMap = headerMap, prevSig = null;
        int removedRuns = 0;
        for (Run r : runs) {
            if (removed.contains(r)) { removedRuns++; continue; }
            if (prevSig != null && !r.sig.equals(prevSig)) out.append("#EXT-X-DISCONTINUITY\n");
            if (!Objects.equals(r.keyLine, lastKey)) {
                out.append(r.keyLine != null ? r.keyLine : "#EXT-X-KEY:METHOD=NONE").append('\n');
                lastKey = r.keyLine;
            }
            if (r.mapLine != null) {
                if (!Objects.equals(r.mapLine, lastMap)) {
                    out.append(r.mapLine).append('\n');
                    lastMap = r.mapLine;
                }
            } else if (lastMap != null) {
                return reject("map-reset");   // MAP 无法安全复位
            }
            for (String l : r.lines) out.append(l).append('\n');
            prevSig = r.sig;
        }
        out.append("#EXT-X-ENDLIST\n");

        AppLog.d(TAG, "filtered " + removedRuns + " ad run(s): "
                + Math.round(totalDur - keptDur) + "s / " + Math.round(totalDur) + "s"
                + " spans=" + describeSpans(spanAcc, spanCount));
        return new Result(out.toString(), removedRuns,
                Math.round((totalDur - keptDur) * 1000), Math.round(totalDur * 1000),
                spanCount == 0 ? new long[0] : java.util.Arrays.copyOf(spanAcc, spanCount));
    }

    /** 区间数组转 "a-b,c-d"（秒，保留一位小数），仅用于日志。 */
    private static String describeSpans(long[] spans, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < count; i += 2) {
            if (sb.length() > 1) sb.append(',');
            sb.append(spans[i] / 1000).append('-').append(spans[i + 1] / 1000);
        }
        return sb.append(']').toString();
    }

    private static Run newRun(String key, String map) {
        Run r = new Run();
        r.keyLine = key;
        r.mapLine = map;
        return r;
    }

    /** 签名 = host | 分片目录 | KEY 行 | MAP 行。host 必须参与：实测正片段可能落在另一 host 上 */
    private static String signature(String baseUrl, Run r) {
        String u = r.firstUri != null ? r.firstUri : "";
        String host = "", dir = u;
        try {
            URI abs = new URI(baseUrl).resolve(u);
            host = abs.getHost() == null ? "" : abs.getHost();
            String p = abs.getPath() == null ? "" : abs.getPath();
            int cut = p.lastIndexOf('/');
            dir = cut >= 0 ? p.substring(0, cut) : p;
        } catch (Exception e) {
            int cut = u.lastIndexOf('/');
            dir = cut >= 0 ? u.substring(0, cut) : u;
        }
        return host + "|" + dir + "|" + (r.keyLine == null ? "-" : r.keyLine)
                + "|" + (r.mapLine == null ? "-" : r.mapLine);
    }

    private static String resolve(String baseUrl, String uri) {
        try {
            return new URI(baseUrl).resolve(uri).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    private static double parseDur(String line) {
        try {
            String v = line.substring(8).split(",")[0].trim();
            double d = Double.parseDouble(v);
            return d >= 0 ? d : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long parseSeq(String line) {
        try {
            return Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
