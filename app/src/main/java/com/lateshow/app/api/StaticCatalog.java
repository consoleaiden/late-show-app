package com.lateshow.app.api;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.lateshow.app.model.Models;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 「资源站直连」数据源：NAS 关机时的兜底模式。
 *
 * 数据链路（与 NAS 模式对外表现一致，只是数据来自第三方）：
 *   1. 源站列表 = mylazily 维护的资源站目录 latest.json（每个条目带一个苹果CMS vod 接口）
 *   2. 分类     = 该站 ac=list 返回的 class 数组；无 class 时从列表条目的 type_name/type_id 反推
 *   3. 分页列表 = 该站 ac=videolist&pg=N，可按 t=<type_id> 过滤、wd=<关键词> 搜索
 *   4. 详情     = 该站 ac=detail&ids=<vod_id>，把 vod_play_from / vod_play_url 拆成线路 + 选集
 *
 * 注意：直连模式不经过自家服务端，因此没有鉴权、没有服务端聚合与去重；源站质量由目录维护方决定。
 */
public final class StaticCatalog implements Catalog {

    private static final String TAG = "StaticCatalog";

    /** 资源站目录（源站列表来源） */
    public static final String DIR_URL = "https://mylazily.github.io/ziyuanzhan/data/latest.json";

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset GBK = Charset.forName("GBK");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** slug(=站点域名) → 接口地址 / 名称 / 条数 / 是否可用 */
    private final Map<String, String> apiBySlug = new ConcurrentHashMap<>();
    private final Map<String, String> nameBySlug = new ConcurrentHashMap<>();
    private final Map<String, Integer> totalBySlug = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enabledBySlug = new ConcurrentHashMap<>();
    /** 分类名 → type_id（苹果CMS 按 t 过滤需要 id，而上层只有分类名） */
    private final Map<String, Integer> typeIdByName = new ConcurrentHashMap<>();

    private final OkHttpClient http;

    private volatile boolean dirLoaded = false;
    private volatile boolean dirLoading = false;
    private final List<OnDir> dirWaiters = new ArrayList<>();

    public StaticCatalog() {
        http = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build();
    }

    // ---------------- 目录加载 ----------------

    private interface OnDir { void done(String err); }

    private interface OnApi { void onApi(String apiBase); }

    private interface OnBody { void onBody(String body) throws Exception; }

    private interface OnErr { void onErr(String msg); }

    /** 目录只拉一次（进程内复用）；并发调用排队等同一份结果 */
    private void loadDir(final OnDir cb) {
        synchronized (this) {
            if (dirLoaded) { cb.done(null); return; }
            dirWaiters.add(cb);
            if (dirLoading) return;
            dirLoading = true;
        }
        Request req = new Request.Builder().url(DIR_URL).header("User-Agent", UA).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                finishDir("网络异常：" + e.getMessage());
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    if (!r.isSuccessful()) { finishDir("HTTP " + r.code()); return; }
                    String body = decode(r);
                    int n = parseDir(body);
                    Log.d(TAG, "目录加载完成，源站 " + n + " 个");
                    finishDir(n > 0 ? null : "目录为空或格式不符");
                } catch (Exception e) {
                    finishDir("目录解析失败：" + e.getMessage());
                }
            }
        });
    }

    private void finishDir(final String err) {
        final List<OnDir> waiters;
        synchronized (this) {
            dirLoading = false;
            if (err == null) dirLoaded = true;
            waiters = new ArrayList<>(dirWaiters);
            dirWaiters.clear();
        }
        MAIN.post(() -> {
            for (OnDir w : waiters) w.done(err);
        });
    }

    /** 解析目录 JSON，填充 slug→接口/名称/条数/状态 */
    private int parseDir(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("resources");
        if (arr == null) return 0;
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String api = o.optString("api", "").trim();
            if (api.isEmpty()) continue;
            HttpUrl u = HttpUrl.parse(api);
            if (u == null || u.host() == null) continue;
            String slug = u.host();
            String name = o.optString("name", slug).trim();
            apiBySlug.put(slug, api);
            nameBySlug.put(slug, name.isEmpty() ? slug : name);
            totalBySlug.put(slug, o.optInt("resource_count", 0));
            enabledBySlug.put(slug, !"down".equalsIgnoreCase(o.optString("status", "ok")));
            n++;
        }
        return n;
    }

    /** 拿到某源站的接口地址；目录未加载则先加载。slug 为空时自动选目录里资源最多的可用站（近似「全部源站」） */
    private void withApi(final String slug, final OnApi ok, final OnErr err) {
        final String key = (slug == null || slug.isEmpty()) ? defaultSlug() : slug;
        if (key != null && !key.isEmpty()) {
            String api = apiBySlug.get(key);
            if (api != null) { ok.onApi(api); return; }
        }
        loadDir(e -> {
            if (e != null) { err.onErr(e); return; }
            String k = (slug == null || slug.isEmpty()) ? defaultSlug() : slug;
            String a = k == null ? null : apiBySlug.get(k);
            if (a == null) err.onErr("未找到源站：" + slug);
            else ok.onApi(a);
        });
    }

    /** 目录内资源最多且可用的源站（直连模式没有服务端聚合，用单站兜底「全部源站」） */
    private String defaultSlug() {
        String best = null;
        int bestTotal = -1;
        for (Map.Entry<String, String> en : apiBySlug.entrySet()) {
            if (!Boolean.TRUE.equals(enabledBySlug.get(en.getKey()))) continue;
            Integer t = totalBySlug.get(en.getKey());
            int total = t == null ? 0 : t;
            if (total > bestTotal) { bestTotal = total; best = en.getKey(); }
        }
        return best;
    }

    // ---------------- Catalog 实现 ----------------

    @Override
    public void sources(final VodSyncApi.ApiCb<Models.SyncSourcesResp> cb) {
        loadDir(e -> {
            if (e != null) {
                cb.onErr("资源站目录加载失败：" + e);
                return;
            }
            Models.SyncSourcesResp r = new Models.SyncSourcesResp();
            r.code = 1;
            r.list = new ArrayList<>();
            for (Map.Entry<String, String> en : apiBySlug.entrySet()) {
                Models.SyncSource s = new Models.SyncSource();
                s.slug = en.getKey();
                s.apiUrl = en.getValue();
                s.name = nameBySlug.containsKey(s.slug) ? nameBySlug.get(s.slug) : s.slug;
                Integer total = totalBySlug.get(s.slug);
                s.totalVideos = total == null ? 0 : total;
                s.enabled = Boolean.TRUE.equals(enabledBySlug.get(s.slug));
                s.status = s.enabled ? "ok" : "down";
                r.list.add(s);
            }
            cb.onOk(r);
        });
    }

    @Override
    public void categories(final String slug, final VodSyncApi.ApiCb<Models.CategoryResp> cb) {
        withApi(slug, apiBase -> {
            HttpUrl u = HttpUrl.parse(apiBase);
            if (u == null) { cb.onErr("源站地址无效"); return; }
            HttpUrl url = u.newBuilder().addQueryParameter("ac", "list").build();
            get(url, body -> {
                Models.CategoryResp r = new Models.CategoryResp();
                r.code = 1;
                r.list = new ArrayList<>();
                JSONObject o = new JSONObject(body);
                // 标准苹果CMS：class 数组
                JSONArray cls = o.optJSONArray("class");
                if (cls != null) {
                    for (int i = 0; i < cls.length(); i++) {
                        JSONObject it = cls.optJSONObject(i);
                        if (it == null) continue;
                        int id = it.optInt("type_id", 0);
                        String name = it.optString("type_name", "").trim();
                        if (id <= 0 || name.isEmpty()) continue;
                        Models.Category c = new Models.Category();
                        c.typeId = id;
                        c.typeName = name;
                        r.list.add(c);
                        typeIdByName.put(name, id);
                    }
                }
                // 非标准站点（ac=list 直接回视频列表）：用条目的 type_name/type_id 反推分类
                if (r.list.isEmpty()) {
                    JSONArray list = o.optJSONArray("list");
                    if (list != null) {
                        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject it = list.optJSONObject(i);
                            if (it == null) continue;
                            int id = it.optInt("type_id", 0);
                            String name = it.optString("type_name", "").trim();
                            if (id <= 0 || name.isEmpty() || !seen.add(name)) continue;
                            Models.Category c = new Models.Category();
                            c.typeId = id;
                            c.typeName = name;
                            r.list.add(c);
                            typeIdByName.put(name, id);
                        }
                    }
                }
                r.total = r.list.size();
                cb.onOk(r);
            }, cb::onErr);
        }, cb::onErr);
    }

    @Override
    public void videos(int page, int pageSize, String slug, String typeName, String keyword,
                       final VodSyncApi.ApiCb<Models.VideoListResp> cb) {
        final int pg = Math.max(1, page);
        final String kw = keyword == null ? "" : keyword.trim();
        withApi(slug, apiBase -> {
            HttpUrl u = HttpUrl.parse(apiBase);
            if (u == null) { cb.onErr("源站地址无效"); return; }
            HttpUrl.Builder b = u.newBuilder()
                    .addQueryParameter("ac", "videolist")
                    .addQueryParameter("pg", String.valueOf(pg));
            if (typeName != null && !typeName.isEmpty()) {
                Integer tid = typeIdByName.get(typeName);
                if (tid != null) b.addQueryParameter("t", String.valueOf(tid));
            }
            if (!kw.isEmpty()) b.addQueryParameter("wd", kw);
            get(b.build(), body -> {
                Models.VideoListResp r = new Models.VideoListResp();
                r.code = 1;
                JSONObject o = new JSONObject(body);
                r.page = o.optInt("page", pg);
                r.pagecount = Math.max(1, o.optInt("pagecount", 1));
                r.total = o.optInt("total", 0);
                r.list = new ArrayList<>();
                JSONArray arr = o.optJSONArray("list");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        Models.VideoSummary v = new Models.VideoSummary();
                        v.source = slug;
                        v.sourceName = nameBySlug.get(slug);
                        v.vodId = it.optLong("vod_id", 0L);
                        v.typeId = it.optInt("type_id", 0);
                        v.typeName = it.optString("type_name", "");
                        v.vodName = it.optString("vod_name", "");
                        v.vodPic = it.optString("vod_pic", "");
                        v.vodRemarks = it.optString("vod_remarks", "");
                        v.vodTime = it.optString("vod_time", "");
                        if (v.vodId <= 0) continue;
                        if (!TextUtils.isEmpty(v.typeName) && v.typeId > 0) typeIdByName.put(v.typeName, v.typeId);
                        r.list.add(v);
                    }
                }
                cb.onOk(r);
            }, cb::onErr);
        }, cb::onErr);
    }

    @Override
    public void detail(String slug, final long vodId, final VodSyncApi.ApiCb<Models.DetailResp> cb) {
        withApi(slug, apiBase -> {
            HttpUrl u = HttpUrl.parse(apiBase);
            if (u == null) { cb.onErr("源站地址无效"); return; }
            HttpUrl url = u.newBuilder()
                    .addQueryParameter("ac", "detail")
                    .addQueryParameter("ids", String.valueOf(vodId))
                    .build();
            get(url, body -> {
                JSONObject o = new JSONObject(body);
                JSONArray arr = o.optJSONArray("list");
                if (arr == null || arr.length() == 0) {
                    cb.onErr("该源站没有这部影片的播放数据");
                    return;
                }
                JSONObject it = arr.optJSONObject(0);
                if (it == null) { cb.onErr("详情格式异常"); return; }
                Models.VideoDetail d = new Models.VideoDetail();
                d.source = slug;
                d.sourceName = nameBySlug.get(slug);
                d.vodId = it.optLong("vod_id", vodId);
                d.typeId = it.optInt("type_id", 0);
                d.typeName = it.optString("type_name", "");
                d.vodName = it.optString("vod_name", "");
                d.vodPic = it.optString("vod_pic", "");
                d.vodRemarks = it.optString("vod_remarks", "");
                d.vodTime = it.optString("vod_time", "");
                d.vodContent = stripHtml(it.optString("vod_content", ""));
                d.vodPlayFrom = it.optString("vod_play_from", "");
                d.vodPlayUrl = it.optString("vod_play_url", "");
                d.vodPlayList = parsePlayList(d.vodPlayFrom, d.vodPlayUrl);
                if (d.vodPlayList.isEmpty()) {
                    cb.onErr("该源站未提供可播放地址");
                    return;
                }
                Models.DetailResp r = new Models.DetailResp();
                r.code = 1;
                r.detail = d;
                cb.onOk(r);
            }, cb::onErr);
        }, cb::onErr);
    }

    @Override
    public void ping(final VodSyncApi.ApiCb<Long> cb) {
        final long t0 = System.currentTimeMillis();
        Request req = new Request.Builder().url(DIR_URL).head().header("User-Agent", UA).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                MAIN.post(() -> cb.onErr("网络异常：" + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    if (r.isSuccessful()) MAIN.post(() -> cb.onOk(System.currentTimeMillis() - t0));
                    else MAIN.post(() -> cb.onErr("HTTP " + r.code()));
                } catch (Exception e) {
                    MAIN.post(() -> cb.onErr("网络异常：" + e.getMessage()));
                }
            }
        });
    }

    @Override
    public boolean isStatic() {
        return true;
    }

    // ---------------- 内部工具 ----------------

    /** 统一 GET：带浏览器 UA，宽松解码（部分老站返回 GBK），回调切回主线程 */
    private void get(HttpUrl url, final OnBody ok, final OnErr err) {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json,text/plain,*/*")
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                MAIN.post(() -> err.onErr("网络异常：" + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) {
                final String body;
                try (Response r = resp) {
                    if (!r.isSuccessful()) {
                        MAIN.post(() -> err.onErr("HTTP " + r.code()));
                        return;
                    }
                    body = decode(r);
                } catch (Exception e) {
                    MAIN.post(() -> err.onErr("读取失败：" + e.getMessage()));
                    return;
                }
                MAIN.post(() -> {
                    try {
                        ok.onBody(body);
                    } catch (Exception e) {
                        Log.w(TAG, "解析失败 " + url, e);
                        err.onErr("数据解析失败：" + e.getMessage());
                    }
                });
            }
        });
    }

    /** 响应体解码：优先用头部 charset，无声明时按 UTF-8，出现替换符再试 GBK */
    private static String decode(Response r) throws IOException {
        if (r.body() == null) return "";
        Charset cs = r.body().charset();
        byte[] bytes = r.body().bytes();
        String s = new String(bytes, cs != null ? cs : UTF8);
        if (cs == null && s.indexOf('\uFFFD') >= 0) {
            String g = new String(bytes, GBK);
            if (g.indexOf('\uFFFD') < 0) return g;
        }
        return s;
    }

    /** 苹果CMS 播放串 → 线路/选集：线路用 $$$ 分隔，集用 # 分隔，集名与地址用 $ 分隔 */
    static List<Models.PlayLine> parsePlayList(String from, String urls) {
        List<Models.PlayLine> out = new ArrayList<>();
        if (TextUtils.isEmpty(urls)) return out;
        String[] groups = urls.split("\\$\\$\\$");
        String[] names = TextUtils.isEmpty(from) ? new String[0] : from.split("\\$\\$\\$");
        for (int g = 0; g < groups.length; g++) {
            Models.PlayLine line = new Models.PlayLine();
            line.name = g < names.length && !TextUtils.isEmpty(names[g].trim())
                    ? names[g].trim() : ("线路" + (g + 1));
            line.episodes = new ArrayList<>();
            for (String seg : groups[g].split("#")) {
                if (TextUtils.isEmpty(seg)) continue;
                String s = seg.trim();
                if (s.isEmpty()) continue;
                int sep = s.indexOf('$');
                Models.Episode ep = new Models.Episode();
                if (sep > 0) {
                    ep.name = s.substring(0, sep).trim();
                    ep.url = s.substring(sep + 1).trim();
                } else {
                    ep.url = s;
                }
                if (TextUtils.isEmpty(ep.name)) ep.name = "第" + (line.episodes.size() + 1) + "集";
                if (TextUtils.isEmpty(ep.url)) continue;
                line.episodes.add(ep);
            }
            if (!line.episodes.isEmpty()) out.add(line);
        }
        return out;
    }

    private static String stripHtml(String html) {
        if (TextUtils.isEmpty(html)) return "";
        String s = HTML_TAG.matcher(html).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        return s.replaceAll("\\s{2,}", " ").trim();
    }
}
