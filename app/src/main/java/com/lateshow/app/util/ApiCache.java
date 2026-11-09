package com.lateshow.app.util;

import android.content.Context;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 接口响应缓存（两层：有界内存 LRU + 文件），带 TTL。
 *
 * 审计 A-08/A-22/A-26：
 * <ul>
 *   <li>所有 key 自动加「后端地址 + Token 指纹」命名空间，换后端/换 Token 后旧缓存立即不命中；</li>
 *   <li>L1 由无界 HashMap 改为有界 LRU(64)；</li>
 *   <li>磁盘读取全部放到 IO 线程：进程启动时后台预热，主线程 {@code get/getStale}
 *       只查内存、绝不做磁盘 IO（原实现在首页四处主线程同步读盘 + 双次 Gson）。</li>
 * </ul>
 * 代价：预热完成前的极早期请求可能 miss 而走网络 —— 这是可接受且不阻塞 UI 的取舍，
 * 网络失败时仍可由后续预热结果提供 stale 兜底。
 */
public final class ApiCache {

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Gson GSON = new Gson();
    private static final int MAX_L1 = 64;
    private static final int MAX_FILES = 60;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** 有界 LRU：accessOrder=true，超上限淘汰最久未用 */
    private static final Map<String, Entry> L1 =
            new LinkedHashMap<String, Entry>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> e) {
                    return size() > MAX_L1;
                }
            };

    private static File dir;
    private static volatile boolean warmed = false;

    private ApiCache() {}

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
        warmAsync();
    }

    /** 后台把磁盘缓存读进 L1；只解析外层 Wrapper，业务对象的解析按需进行 */
    private static void warmAsync() {
        if (warmed) return;
        warmed = true;
        IO.execute(() -> {
            if (dir == null) return;
            File[] fs = dir.listFiles();
            if (fs == null) return;
            // 按修改时间倒序，只预热最新的 MAX_L1 个
            List<File> list = new ArrayList<>();
            for (File f : fs) if (f.getName().endsWith(".json")) list.add(f);
            list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            int n = Math.min(list.size(), MAX_L1);
            for (int i = 0; i < n; i++) {
                File f = list.get(i);
                String raw = readText(f);
                if (raw == null || raw.isEmpty()) continue;
                try {
                    Wrapper w = GSON.fromJson(raw, Wrapper.class);
                    if (w == null || w.d == null || w.k == null || w.k.isEmpty()) continue;
                    Entry e = new Entry();
                    e.savedAt = w.t;
                    e.json = w.d;
                    synchronized (L1) {
                        L1.put(w.k, e);
                    }
                } catch (Exception ignored) { }
            }
            pruneFiles();
        });
    }

    /** 缓存命名空间：后端地址或 Token 变化后自动切换，旧主机数据不再命中（A-08） */
    private static String scoped(String key) {
        return Credentials.fingerprint() + "|" + key;
    }

    /** 命中且未过期返回对象；过期/不存在返回 null（过期条目会保留供 getStale 使用） */
    public static <T> T get(String key, Class<T> clazz, long ttlMs) {
        Entry e = read(scoped(key));
        if (e == null) return null;
        if (ttlMs > 0 && System.currentTimeMillis() - e.savedAt > ttlMs) return null;
        return parse(e, clazz);
    }

    /** 忽略 TTL 读取（离线兜底：网络失败时用旧数据） */
    public static <T> T getStale(String key, Class<T> clazz) {
        Entry e = read(scoped(key));
        return e == null ? null : parse(e, clazz);
    }

    public static long ageMs(String key) {
        Entry e = read(scoped(key));
        return e == null ? -1 : System.currentTimeMillis() - e.savedAt;
    }

    public static void put(String key, Object value) {
        if (value == null) return;
        final String json;
        try {
            json = GSON.toJson(value);
        } catch (Exception ex) {
            AppLog.w("ApiCache", "put 序列化失败 " + key);
            return;
        }
        final String sk = scoped(key);
        final Entry e = new Entry();
        e.savedAt = System.currentTimeMillis();
        e.json = json;
        e.obj = value;
        synchronized (L1) {
            L1.put(sk, e);
        }
        IO.execute(() -> writeFile(sk, e.savedAt, json));
    }

    /** 清空全部接口缓存（设置页「清除缓存」、后端地址/Token 变更时用） */
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

    /** 只读内存：主线程不再做磁盘 IO */
    private static Entry read(String scopedKey) {
        synchronized (L1) {
            return L1.get(scopedKey);
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
            AppLog.w("ApiCache", "解析缓存失败 " + clazz.getSimpleName());
            return null;
        }
    }

    private static void writeFile(String scopedKey, long ts, String json) {
        if (dir == null) return;
        try {
            Wrapper w = new Wrapper();
            w.t = ts;
            w.d = json;
            w.k = scopedKey;
            File f = fileOf(scopedKey);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(GSON.toJson(w).getBytes(UTF8));
            }
            pruneFiles();
        } catch (Exception ex) {
            AppLog.w("ApiCache", "写缓存失败 " + scopedKey);
        }
    }

    private static void pruneFiles() {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null || fs.length <= MAX_FILES) return;
        List<File> list = new ArrayList<>();
        for (File f : fs) list.add(f);
        list.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < list.size() - MAX_FILES; i++) //noinspection ResultOfMethodCallIgnored
            list.get(i).delete();
    }

    private static File fileOf(String scopedKey) {
        return new File(dir, safeName(scopedKey) + ".json");
    }

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

    /** 文件落盘结构；k = 带指纹的完整 key，供进程启动时预热恢复 */
    private static final class Wrapper {
        long t;
        String d;
        String k;
    }
}
