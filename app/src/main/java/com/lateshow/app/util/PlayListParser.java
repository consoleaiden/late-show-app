package com.lateshow.app.util;

import com.lateshow.app.model.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * 苹果 CMS 播放串解析的唯一实现（审计 D-01 / A-32）。
 *
 * <p>此前 {@code DetailFragment.parsePlayUrl()} 与 {@code StaticCatalog.parsePlayList()}
 * 是两份近乎逐行同构的实现，任何一处修正都会漂移。这里统一：
 * 线路用 {@code $$$} 分隔，集用 {@code #} 分隔，集名与地址用 {@code $} 分隔。
 *
 * <p>纯 Java 实现，不依赖任何 Android API，便于 JVM 单元测试。
 *
 * <p>关于 v1.11.0 的 {@code playable}：原始播放串里没有该字段，因此解析结果为 null，
 * 调用方按「字段缺失 = 可播」处理；服务端结构化下发的 {@code vod_play_list} 会带该字段。
 */
public final class PlayListParser {

    private PlayListParser() { }

    /** 有独立线路名串（vod_play_from）时的解析 */
    public static List<Models.PlayLine> parse(String from, String urls) {
        List<Models.PlayLine> out = new ArrayList<>();
        if (empty(urls)) return out;
        String[] groups = urls.split("\\$\\$\\$");
        String[] names = empty(from) ? new String[0] : from.split("\\$\\$\\$");
        int upstream = 0;
        for (int g = 0; g < groups.length; g++) {
            final int srcIdx = upstream++;
            String group = groups[g];
            if (group == null) continue;
            group = group.trim();
            if (group.isEmpty()) continue;
            Models.PlayLine line = new Models.PlayLine();
            line.name = (g < names.length && !empty(names[g].trim()))
                    ? names[g].trim() : ("线路" + (g + 1));
            line.originalIndex = srcIdx;
            line.episodes = new ArrayList<>();
            for (String seg : group.split("#")) {
                if (seg == null) continue;
                String s = seg.trim();
                if (s.isEmpty()) continue;
                Models.Episode ep = new Models.Episode();
                int sep = s.indexOf('$');
                if (sep > 0) {
                    ep.name = s.substring(0, sep).trim();
                    ep.url = s.substring(sep + 1).trim();
                } else {
                    ep.url = s;
                }
                if (empty(ep.name)) ep.name = "第" + (line.episodes.size() + 1) + "集";
                if (empty(ep.url)) continue;
                line.episodes.add(ep);
            }
            if (!line.episodes.isEmpty()) out.add(line);
        }
        return out;
    }

    /** 只有合并播放串（vod_play_url）时的解析，线路名自动生成 */
    public static List<Models.PlayLine> parseRaw(String raw) {
        return parse(null, raw);
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
