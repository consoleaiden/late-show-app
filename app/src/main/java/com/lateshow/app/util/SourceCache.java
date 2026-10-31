package com.lateshow.app.util;

import com.lateshow.app.model.Models;

import java.util.HashMap;
import java.util.Map;

/** slug → 源站展示名 内存缓存（由首页 /source 响应填充） */
public final class SourceCache {
    private static final Map<String, String> MAP = new HashMap<>();

    private SourceCache() {}

    public static void putAll(java.util.List<Models.Source> sources) {
        if (sources == null) return;
        synchronized (MAP) {
            for (Models.Source s : sources) MAP.put(s.slug, s.name);
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
