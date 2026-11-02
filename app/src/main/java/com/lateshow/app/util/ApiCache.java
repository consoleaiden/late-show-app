package com.lateshow.app.util;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 接口响应缓存（两层：内存 L1 + 文件 L2），带 TTL。
 *
 * 数据范围：只缓存「首页第一页列表」「分类」「源站列表」这类读多写少、体积可控的响应；
 * 分页第 2 页及以后不缓存，搜索只缓存当次已加载的第一页。
 *
 * 存储位置：{filesDir}/api-cache/<key>.json，内容 {"t":毫秒时间戳,"d":原始响应 JSON}。
 * 写盘异步（单线程串行），读优先内存，未命中再读文件，全程不阻塞 UI 的明显耗时。
 */
public final class ApiCache {

    private static final String TAG = "ApiCache";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Gson GSON = new Gson();
    private static final Map<String, Entry> L1 = new HashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int MAX_FILES = 60;

    private static File dir;

    private ApiCache() {}

    /** 缓存条目（时间戳 + 原始 JSON + 已解析对象，避免重复解析） */
    private static final class Entry {
        long savedAt;
        String json;
        Object obj;
    }

    public static void init(Context ctx) {
        if (dir != null) return;
        synchronized (ApiCache.class) {
            if (dir != null) return;
            dir = new File(ctx.getApplicationContext().getFilesDir(), "api-cache");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
        }
    }

    /** 命中且未过期返回对象；过期/不存在返回 null（过期条目会保留供 getStale 使用） */
    public static <T> T get(String key, Class<T> clazz, long ttlMs) {
        Entry e = read(key);
        if (e == null) return null;
        if (ttlMs > 0 && System.currentTimeMillis() - e.savedAt > ttlMs) return null;
        return parse(e, clazz);
    }

    /** 忽略 TTL 读取（离线兜底：网络失败时用旧数据） */
    public static <T> T getStale(String key, Class<T> clazz) {
        Entry e = read(key);
        return e == null ? null : parse(e, clazz);
    }

    /** 缓存年龄（毫秒），无缓存返回 -1 */
    public static long ageMs(String key) {
        Entry e = read(key);
        return e == null ? -1 : System.currentTimeMillis() - e.savedAt;
    }

    public static void put(String key, Object value) {
        if (value == null) return;
        final String json;
        try {
            json = GSON.toJson(value);
        } catch (Exception ex) {
            Log.w(TAG, "put 序列化失败 " + key, ex);
            return;
        }
        final Entry e = new Entry();
        e.savedAt = System.currentTimeMillis();
        e.json = json;
        e.obj = value;
        synchronized (L1) {
            L1.put(key, e);
        }
        IO.execute(() -> writeFile(key, e.savedAt, json));
    }

    /** 清空全部接口缓存（设置页「清除缓存」用） */
    public static void clear() {
        synchronized (L1) {
            L1.clear();
        }
        IO.execute(() -> {
            if (dir == null) return;
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
        });
    }

    public static long sizeBytes() {
        if (dir == null) return 0;
        long sum = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) sum += f.length();
        return sum;
    }

    // ---------- 内部 ----------

    private static Entry read(String key) {
        synchronized (L1) {
            Entry m = L1.get(key);
            if (m != null) return m;
        }
        if (dir == null) return null;
        File f = fileOf(key);
        if (!f.exists()) return null;
        String raw = readText(f);
        if (raw == null || raw.isEmpty()) return null;
        try {
            Wrapper w = GSON.fromJson(raw, Wrapper.class);
            if (w == null || w.d == null) return null;
            Entry e = new Entry();
            e.savedAt = w.t;
            e.json = w.d;
            synchronized (L1) {
                L1.put(key, e);
            }
            return e;
        } catch (Exception ex) {
            Log.w(TAG, "读缓存失败 " + key, ex);
            return null;
        }
    }

    private static <T> T parse(Entry e, Class<T> clazz) {
        if (clazz == null) return null;
        if (e.obj != null && clazz.isInstance(e.obj)) return clazz.cast(e.obj);
        try {
            T t = GSON.fromJson(e.json, clazz);
            if (t != null) e.obj = t;
            return t;
        } catch (Exception ex) {
            Log.w(TAG, "解析缓存失败 " + clazz.getSimpleName(), ex);
            return null;
        }
    }

    private static void writeFile(String key, long ts, String json) {
        if (dir == null) return;
        try {
            Wrapper w = new Wrapper();
            w.t = ts;
            w.d = json;
            File f = fileOf(key);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(GSON.toJson(w).getBytes(UTF8));
            }
            pruneFiles();
        } catch (Exception ex) {
            Log.w(TAG, "写缓存失败 " + key, ex);
        }
    }

    /** 文件数超上限时按最后修改时间删最旧的 */
    private static void pruneFiles() {
        File[] fs = dir.listFiles();
        if (fs == null || fs.length <= MAX_FILES) return;
        List<File> list = new ArrayList<>();
        for (File f : fs) list.add(f);
        list.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < list.size() - MAX_FILES; i++) //noinspection ResultOfMethodCallIgnored
            list.get(i).delete();
    }

    private static File fileOf(String key) {
        return new File(dir, safeName(key) + ".json");
    }

    /** key → 文件名（只保留字母数字，超长截断后拼 hash 防碰撞） */
    private static String safeName(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length() && sb.length() < 40; i++) {
            char c = key.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return sb + "_" + Integer.toHexString(key.hashCode());
    }

    private static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), UTF8);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 文件落盘结构 */
    private static final class Wrapper {
        long t;
        String d;
    }
}
