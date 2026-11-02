package com.lateshow.app.util;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.lateshow.app.model.Models;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 收藏 / 播放历史的「完整播放数据」离线存储。
 *
 * 存什么：整份 VideoDetail —— 含 vod_name / vod_pic / vod_content / type_name、
 * vod_play_list（线路名 + 每集 name/url，即分集数与全部播放地址），
 * 也就是离线播放所需的全部字段。
 *
 * 存哪里：{filesDir}/offline-media/<slug>_<vodId>.json（每部剧一个文件，可单独删除）。
 * 更新策略：进度变化只更新 PrefStore 里的历史条目；播放数据在「详情加载成功且已收藏/已有历史」、
 * 点播放、点收藏时写入，用覆盖写保证是最新线路与地址。
 * 容量控制：最多 80 部 / 40MB，超出按文件最后修改时间（LRU）淘汰最旧。
 */
public final class OfflineMedia {

    private static final String TAG = "OfflineMedia";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Gson GSON = new Gson();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int MAX_FILES = 80;
    private static final long MAX_BYTES = 40L * 1024 * 1024;
    private static final int L1_MAX = 12;

    /** 内存 LRU：详情页来回切换不必重复读文件 */
    private static final Map<String, Models.VideoDetail> L1 =
            new LinkedHashMap<String, Models.VideoDetail>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Models.VideoDetail> e) {
                    return size() > L1_MAX;
                }
            };

    private static File dir;

    private OfflineMedia() {}

    public static void init(Context ctx) {
        if (dir != null) return;
        synchronized (OfflineMedia.class) {
            if (dir != null) return;
            dir = new File(ctx.getApplicationContext().getFilesDir(), "offline-media");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
        }
    }

    public static String key(String slug, long vodId) {
        return (slug == null ? "_" : slug) + "_" + vodId;
    }

    /** 保存完整播放数据（异步写盘，立即进内存缓存） */
    public static void saveDetail(Models.VideoDetail d) {
        if (d == null || d.source == null || d.vodId <= 0) return;
        final String k = key(d.source, d.vodId);
        synchronized (L1) {
            L1.put(k, d);
        }
        final String json;
        try {
            json = GSON.toJson(d);
        } catch (Exception e) {
            Log.w(TAG, "序列化详情失败", e);
            return;
        }
        IO.execute(() -> {
            if (dir == null) return;
            try (FileOutputStream fo = new FileOutputStream(new File(dir, k + ".json"))) {
                fo.write(json.getBytes(UTF8));
            } catch (Exception e) {
                Log.w(TAG, "写离线详情失败 " + k, e);
            }
            prune();
        });
    }

    /** 读取缓存的播放数据（内存 → 文件），没有返回 null */
    public static Models.VideoDetail loadDetail(String slug, long vodId) {
        final String k = key(slug, vodId);
        synchronized (L1) {
            Models.VideoDetail m = L1.get(k);
            if (m != null) return m;
        }
        File f = fileOf(slug, vodId);
        if (f == null || !f.exists()) return null;
        String raw = readText(f);
        if (raw == null || raw.isEmpty()) return null;
        try {
            Models.VideoDetail d = GSON.fromJson(raw, Models.VideoDetail.class);
            if (d != null) {
                synchronized (L1) {
                    L1.put(k, d);
                }
            }
            return d;
        } catch (Exception e) {
            Log.w(TAG, "解析离线详情失败 " + k, e);
            return null;
        }
    }

    public static boolean has(String slug, long vodId) {
        final String k = key(slug, vodId);
        synchronized (L1) {
            if (L1.containsKey(k)) return true;
        }
        File f = fileOf(slug, vodId);
        return f != null && f.exists();
    }

    public static void delete(String slug, long vodId) {
        final String k = key(slug, vodId);
        synchronized (L1) {
            L1.remove(k);
        }
        IO.execute(() -> {
            File f = fileOf(slug, vodId);
            if (f != null && f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        });
    }

    public static int count() {
        if (dir == null) return 0;
        File[] fs = dir.listFiles();
        return fs == null ? 0 : fs.length;
    }

    public static long sizeBytes() {
        if (dir == null) return 0;
        long sum = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) sum += f.length();
        return sum;
    }

    /** 超容量按 LRU 清理 */
    private static void prune() {
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) return;
        long total = 0;
        List<File> list = new ArrayList<>();
        for (File f : fs) {
            list.add(f);
            total += f.length();
        }
        if (list.size() <= MAX_FILES && total <= MAX_BYTES) return;
        list.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int files = list.size();
        for (File f : list) {
            if (files <= MAX_FILES && total <= MAX_BYTES) break;
            long len = f.length();
            if (f.delete()) {
                files--;
                total -= len;
                synchronized (L1) {
                    L1.remove(f.getName().replace(".json", ""));
                }
            }
        }
    }

    private static File fileOf(String slug, long vodId) {
        if (dir == null) return null;
        return new File(dir, key(slug, vodId) + ".json");
    }

    private static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), UTF8);
        } catch (Exception e) {
            return null;
        }
    }
}
