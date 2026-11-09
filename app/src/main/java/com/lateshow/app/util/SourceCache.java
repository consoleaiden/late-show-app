package com.lateshow.app.util;

import com.lateshow.app.model.Models;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * slug → 源站展示名 内存缓存（由首页 /api/v1/sources 响应填充）。
 *
 * 审计 A-26/A-08：改为有界 LRU，并提供 {@link #clear()} —— 后端地址 / Token / 数据源模式
 * 变化时必须失效，否则卡片会显示上一台服务器的源站中文名。
 */
public final class SourceCache {

    private static final int MAX = 256;

    private static final Map<String, String> MAP =
            new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > MAX;
                }
            };

    private SourceCache() {}

    public static void putAll(java.util.List<Models.Source> sources) {
        if (sources == null) return;
        synchronized (MAP) {
            for (Models.Source s : sources) {
                if (s != null && s.slug != null && s.name != null) MAP.put(s.slug, s.name);
            }
        }
    }

    /** 由 /api/v1/sources 的 SyncSource 列表填充 */
    public static void putSync(java.util.List<Models.SyncSource> sources) {
        if (sources == null) return;
        synchronized (MAP) {
            for (Models.SyncSource s : sources) {
                if (s != null && s.slug != null && s.name != null) MAP.put(s.slug, s.name);
            }
        }
    }

    public static void clear() {
        synchronized (MAP) {
            MAP.clear();
        }
    }

    /** slug 找不到展示名时回退为 slug 本身 */
    public static String nameOf(String slug) {
        if (slug == null) return "";
        synchronized (MAP) {
            String n = MAP.get(slug);
            return n != null ? n : slug;
        }
    }
}
