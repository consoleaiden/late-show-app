package com.lateshow.app.api;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.lateshow.app.model.Models;
import com.lateshow.app.util.AppLog;
import com.lateshow.app.util.PlayListParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *   3. 分页列表 = 该站 ac=videolist&pg=N，可按 t=&lt;type_id&gt; 过滤、wd=&lt;关键词&gt; 搜索
 *   4. 详情     = 该站 ac=detail&ids=&lt;vod_id&gt;，把 vod_play_from / vod_play_url 拆成线路 + 选集
 *
 * 审计整改：
 * <ul>
 *   <li>A-24：第三方 JSON 解析 / 700+ 集播放串拆分放到后台单线程池，不再占主线程；</li>
 *   <li>A-28：分类 id 按源站隔离；「全部源站」不再把 {@code source=""} 写回卡片；
 *       苹果 CMS 只认 {@code pg} 时按 pageSize 推算虚拟总页数，避免上层提前判定到底；</li>
 *   <li>A-26：slowUntil 有上限并主动清理过期项；</li>
 *   <li>A-32/D-01：播放串解析统一委托 {@link PlayListParser}；</li>
 *   <li>A-35：禁止 HTTPS→HTTP 协议降级，日志走统一开关；响应体增加大小上限。</li>
 * </ul>
 */
public final class StaticCatalog implements Catalog {

    private static final String TAG = "StaticCatalog";

    /** 资源站目录（源站列表来源） */
    public static final String DIR_URL = "https://mylazily.github.io/ziyuanzhan/data/latest.json";

    private static final long SLOW_DIRECT_MS = 3500;
    private static final long SLOW_TTL_MS = 10 * 60 * 1000L;
    private static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_SLOW_ENTRIES = 64;

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset GBK = Charset.forName("GBK");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 后台解析线程池：第三方 JSON 体积大、含 700+ 集播放串，不能在主线程解析（A-24） */
    private static final ExecutorService PARSE = Executors.newSingleThreadExecutor();

    /** slug(=站点域名) → 接口地址 / 名称 / 条数 / 是否可用 */
    private final Map<String, String> apiBySlug = new ConcurrentHashMap<>();
    private final Map<String, String> nameBySlug = new ConcurrentHashMap<>();
    private final Map<String, Integer> totalBySlug = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enabledBySlug = new ConcurrentHashMap<>();
    /** 分类名 → type_id，必须按源站隔离：两个站同名分类的 id 不同（A-28） */
    private final Map<String, Map<String, Integer>> typeIdBySlug = new ConcurrentHashMap<>();

    private final OkHttpClient http;
    private final OkHttpClient proxyHttp;
    /** host → 代理优先到期时间戳（该 host 直连失败/过慢过） */
    private final Map<String, Long> slowUntil = new ConcurrentHashMap<>();
    /** 列表请求代次：直连的是第三方站点，无法按 tag 取消，用代次丢弃过期回调（A-09） */
    private volatile int videoGen = 0;

    private volatile boolean dirLoaded = false;
    private volatile boolean dirLoading = false;
    private final List<OnDir> dirWaiters = new ArrayList<>();

    public StaticCatalog() {
        http = new OkHttpClient.Builder()
                .dns(SmartDns.INSTANCE)
                .followSslRedirects(false)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(9, TimeUnit.SECONDS)
                .build();
        proxyHttp = new OkHttpClient.Builder()
                .dns(SmartDns.INSTANCE)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ---------------- 回调切换工具 ----------------

    private static <T> void okMain(VodSyncApi.ApiCb<T> cb, T r) {
        MAIN.post(() -> cb.onOk(r));
    }

    private static <T> void errMain(VodSyncApi.ApiCb<T> cb, String m) {
        MAIN.post(() -> cb.onErr(m));
    }

    private static void errMain(OnErr err, String m) {
        MAIN.post(() -> err.onErr(m));
    }

    private void putTypeId(String slug, String name, int id) {
        if (slug == null || name == null || id <= 0) return;
        typeIdBySlug.computeIfAbsent(slug, k -> new ConcurrentHashMap<>()).put(name, id);
    }

    private Integer typeIdFor(String slug, String name) {
        if (slug == null || name == null) return null;
        Map<String, Integer> m = typeIdBySlug.get(slug);
        return m == null ? null : m.get(name);
    }

    /** 由接口地址反查实际使用的 slug（「全部源站」会退化为目录里资源最多的站） */
    private String slugOfApi(String apiBase) {
        if (apiBase == null) return "";
        for (Map.Entry<String, String> e : apiBySlug.entrySet()) {
            if (apiBase.equals(e.getValue())) return e.getKey();
        }
        return "";
    }

    // ---------------- 目录加载 ----------------

    private interface OnDir { void done(String err); }

    private interface OnApi { void onApi(String apiBase); }

    private interface OnErr { void onErr(String msg); }

    private interface OnBody { void onBody(String body) throws Exception; }

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
                    AppLog.d(TAG, "目录加载完成，源站 " + n + " 个");
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

    /** 拿到某源站的接口地址；目录未加载则先加载。slug 为空时自动选目录里资源最多的可用站 */
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
                errMain(cb, "资源站目录加载失败：" + e);
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
            okMain(cb, r);
        });
    }

    @Override
    public void categories(final String slug, final VodSyncApi.ApiCb<Models.CategoryResp> cb) {
        withApi(slug, apiBase -> {
            final String resolvedSlug = slugOfApi(apiBase);
            HttpUrl u = HttpUrl.parse(apiBase);
            if (u == null) { errMain(cb, "源站地址无效"); return; }
            HttpUrl url = u.newBuilder().addQueryParameter("ac", "list").build();
            get(url, body -> {
                Models.CategoryResp r = new Models.CategoryResp();
                r.code = 1;
                r.list = new ArrayList<>();
                JSONObject o = new JSONObject(body);
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
                        putTypeId(resolvedSlug, name, id);
                    }
                }
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
                            putTypeId(resolvedSlug, name, id);
                        }
                    }
                }
                r.total = r.list.size();
                okMain(cb, r);
            }, m -> errMain(cb, m));
        }, m -> errMain(cb, m));
    }

    /** 直连第三方站点无法按 tag 取消 Call，改用代次丢弃过期回调（A-09） */
    @Override
    public void cancelVideos() {
        videoGen++;
    }

    @Override
    public void videos(int page, int pageSize, String slug, String typeName, String keyword,
                       final VodSyncApi.ApiCb<Models.VideoListResp> rawCb) {
        final int gen = videoGen;   // 同一代次内允许多页并发；换筛选条件才整体作废
        final VodSyncApi.ApiCb<Models.VideoListResp> cb =
                new VodSyncApi.ApiCb<Models.VideoListResp>() {
                    @Override public void onOk(Models.VideoListResp r) {
                        if (gen == videoGen) rawCb.onOk(r);
                    }
                    @Override public void onErr(String m) {
                        if (gen == videoGen) rawCb.onErr(m);
                    }
                };
        final int pg = Math.max(1, page);
        final int expected = pageSize > 0 ? pageSize : 18;
        final String kw = keyword == null ? "" : keyword.trim();
        withApi(slug, apiBase -> {
            // A-28：「全部源站」时 withApi 会退化到某个真实站点，卡片必须写真实 slug，
            // 否则不同站同 vodId 会互相覆盖离线缓存键。
            final String resolvedSlug = slugOfApi(apiBase);
            HttpUrl u = HttpUrl.parse(apiBase);
            if (u == null) { errMain(cb, "源站地址无效"); return; }
            HttpUrl.Builder b = u.newBuilder()
                    .addQueryParameter("ac", "videolist")
                    .addQueryParameter("pg", String.valueOf(pg));
            Integer tid = typeIdFor(slug == null || slug.isEmpty() ? resolvedSlug : slug, typeName);
            if (typeName != null && !typeName.isEmpty() && tid != null) {
                b.addQueryParameter("t", String.valueOf(tid));
            }
            if (!kw.isEmpty()) b.addQueryParameter("wd", kw);
            get(b.build(), body -> {
                Models.VideoListResp r = new Models.VideoListResp();
                r.code = 1;
                JSONObject o = new JSONObject(body);
                r.page = o.optInt("page", pg);
                int reported = o.optInt("pagecount", 0);
                r.total = o.optInt("total", 0);
                // 苹果 CMS 不认 page_size：当站点没给 pagecount 时，按客户端期望页大小
                // 与 total 推算虚拟页数，避免上层「本页不足一页」提前终止翻页（A-28）。
                if (reported <= 0 && r.total > 0) {
                    reported = Math.max(1, (r.total + expected - 1) / expected);
                }
                r.pagecount = Math.max(1, reported);
                r.list = new ArrayList<>();
                JSONArray arr = o.optJSONArray("list");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        Models.VideoSummary v = new Models.VideoSummary();
                        v.source = resolvedSlug;
                        v.sourceName = nameBySlug.get(resolvedSlug);
                        v.vodId = it.optLong("vod_id", 0L);
                        v.typeId = it.optInt("type_id", 0);
                        v.typeName = it.optString("type_name", "");
                        v.vodName = it.optString("vod_name", "");
                        v.vodPic = it.optString("vod_pic", "");
                        v.vodRemarks = it.optString("vod_remarks", "");
                        v.vodTime = it.optString("vod_time", "");
                        if (v.vodId <= 0) continue;
                        if (!TextUtils.isEmpty(v.typeName) && v.typeId > 0) {
                            putTypeId(resolvedSlug, v.typeName, v.typeId);
                        }
                        r.list.add(v);
                    }
                }
                okMain(cb, r);
            }, m -> errMain(cb, m));
        }, m -> errMain(cb, m));
    }

    @Override
    public void detail(String slug, final long vodId, final VodSyncApi.ApiCb<Models.DetailResp> cb) {
        withApi(slug, apiBase -> {
            final String resolvedSlug = slugOfApi(apiBase);
            HttpUrl u = HttpUrl.parse(apiBase);
            if (u == null) { errMain(cb, "源站地址无效"); return; }
            HttpUrl url = u.newBuilder()
                    .addQueryParameter("ac", "detail")
                    .addQueryParameter("ids", String.valueOf(vodId))
                    .build();
            get(url, body -> {
                JSONObject o = new JSONObject(body);
                JSONArray arr = o.optJSONArray("list");
                if (arr == null || arr.length() == 0) {
                    errMain(cb, "该源站没有这部影片的播放数据");
                    return;
                }
                JSONObject it = arr.optJSONObject(0);
                if (it == null) { errMain(cb, "详情格式异常"); return; }
                Models.VideoDetail d = new Models.VideoDetail();
                d.source = resolvedSlug;
                d.sourceName = nameBySlug.get(resolvedSlug);
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
                d.vodActor = it.optString("vod_actor", "");
                d.vodTag = it.optString("vod_tag", "");
                d.vodArea = it.optString("vod_area", "");
                d.vodYear = it.optString("vod_year", "");
                // D-01：与详情页兜底解析共用同一实现
                d.vodPlayList = PlayListParser.parse(d.vodPlayFrom, d.vodPlayUrl);
                if (d.vodPlayList.isEmpty()) {
                    errMain(cb, "该源站未提供可播放地址");
                    return;
                }
                Models.DetailResp r = new Models.DetailResp();
                r.code = 1;
                r.detail = d;
                okMain(cb, r);
            }, m -> errMain(cb, m));
        }, m -> errMain(cb, m));
    }

    /** 相关推荐（直连兜底）：用「同分类第一页 + 排除自身」凑一栏同类热门 */
    @Override
    public void related(final Models.VideoDetail base, final int limit,
                        final VodSyncApi.ApiCb<Models.RelatedResp> cb) {
        if (base == null || base.typeName == null || base.typeName.isEmpty()) {
            errMain(cb, "该源站未提供分类信息");
            return;
        }
        final String src = base.source;
        final long selfId = base.vodId;
        int pageSize = Math.max(limit + 2, 12);
        videos(1, pageSize, src, base.typeName, "",
            new VodSyncApi.ApiCb<Models.VideoListResp>() {
                @Override public void onOk(Models.VideoListResp r) {
                    Models.RelatedResp out = new Models.RelatedResp();
                    out.code = 1;
                    out.fallback = true;
                    out.list = new ArrayList<>();
                    if (r != null && r.list != null) {
                        for (Models.VideoSummary v : r.list) {
                            if (v.vodId == selfId) continue;
                            Models.RelatedCard c = new Models.RelatedCard();
                            c.vodId = v.vodId;
                            c.vodName = v.vodName;
                            c.vodPic = v.vodPic;
                            c.vodRemarks = v.vodRemarks;
                            c.typeName = v.typeName;
                            c.source = src;
                            c.sourceName = v.sourceName;
                            c.score = 0d;
                            c.mode = "fallback";
                            out.list.add(c);
                            if (out.list.size() >= limit) break;
                        }
                    }
                    if (out.list.isEmpty()) errMain(cb, "暂无同类推荐");
                    else okMain(cb, out);
                }
                @Override public void onErr(String m) { errMain(cb, m); }
            });
    }

    @Override
    public void ping(final VodSyncApi.ApiCb<Long> cb) {
        final long t0 = System.currentTimeMillis();
        Request req = new Request.Builder().url(DIR_URL).head().header("User-Agent", UA).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fetchViaProxiesHead(t0, cb);
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    if (r.isSuccessful()) okMain(cb, System.currentTimeMillis() - t0);
                    else errMain(cb, "HTTP " + r.code());
                } catch (Exception e) {
                    errMain(cb, "网络异常：" + e.getMessage());
                }
            }
        });
    }

    /** 直连模式健康探测：探测「当前选中源站」的 json 接口，HTTP 200 即视为畅通 */
    @Override
    public void pingCurrent(String slug, final VodSyncApi.ApiCb<Long> cb) {
        final long t0 = System.currentTimeMillis();
        withApi(slug, apiBase -> {
            HttpUrl u = HttpUrl.parse(apiBase);
            if (u == null) { errMain(cb, "源站地址无效"); return; }
            HttpUrl url = u.newBuilder()
                    .addQueryParameter("ac", "videolist")
                    .addQueryParameter("pg", "1")
                    .build();
            Request req = new Request.Builder().url(url).header("User-Agent", UA).build();
            http.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    errMain(cb, "网络异常：" + e.getMessage());
                }
                @Override public void onResponse(Call call, Response resp) {
                    try (Response r = resp) {
                        if (r.isSuccessful()) okMain(cb, System.currentTimeMillis() - t0);
                        else errMain(cb, "HTTP " + r.code());
                    } catch (Exception e) {
                        errMain(cb, "网络异常：" + e.getMessage());
                    }
                }
            });
        }, e -> errMain(cb, e));
    }

    /** 目录 HEAD 探测的代理兜底：按代理列表顺序逐个尝试 */
    private void fetchViaProxiesHead(final long t0, final VodSyncApi.ApiCb<Long> cb) {
        final List<String> proxies = proxies();
        if (proxies.isEmpty()) { errMain(cb, "直连失败且代理列表为空"); return; }
        tryHeadProxy(0, proxies, t0, cb);
    }

    private void tryHeadProxy(final int idx, final List<String> proxies, final long t0,
                               final VodSyncApi.ApiCb<Long> cb) {
        if (idx >= proxies.size()) { errMain(cb, "直连失败且所有代理均不可用"); return; }
        Request pr;
        try {
            pr = new Request.Builder().url(proxyUrlOf(proxies.get(idx), DIR_URL))
                    .head().header("User-Agent", UA).build();
        } catch (Exception e) {
            tryHeadProxy(idx + 1, proxies, t0, cb);
            return;
        }
        proxyHttp.newCall(pr).enqueue(new Callback() {
            @Override public void onFailure(Call c2, IOException e2) {
                tryHeadProxy(idx + 1, proxies, t0, cb);
            }
            @Override public void onResponse(Call c2, Response resp2) {
                try (Response r2 = resp2) {
                    if (r2.isSuccessful()) okMain(cb, System.currentTimeMillis() - t0);
                    else tryHeadProxy(idx + 1, proxies, t0, cb);
                } catch (Exception ex) {
                    tryHeadProxy(idx + 1, proxies, t0, cb);
                }
            }
        });
    }

    @Override
    public boolean isStatic() {
        return true;
    }

    // ---------------- 内部工具 ----------------

    /**
     * 统一 GET：带浏览器 UA，宽松解码（部分老站返回 GBK）。
     * 解析在 PARSE 后台线程执行，结果回调统一切回主线程（A-24）。
     */
    private void get(HttpUrl url, final OnBody ok, final OnErr err) {
        final OnErr mainErr = m -> errMain(err, m);
        final String host = url.host();
        if (isSlow(host)) {
            AppLog.d(TAG, "慢站点走代理：" + host);
            fetchViaProxies(url, ok, e1 -> {
                AppLog.d(TAG, "代理全部失败，回退直连 " + host + "：" + e1);
                fetch(url, null, ok, e2 -> mainErr.onErr(e2 + "（直连与代理均失败）"));
            });
        } else {
            fetch(url, null, ok, e2 -> {
                AppLog.d(TAG, "直连失败，改走代理 " + host + "：" + e2);
                fetchViaProxies(url, ok, e3 -> mainErr.onErr(e3 + "（已尝试代理）"));
            });
        }
    }

    private void fetchViaProxies(final HttpUrl url, final OnBody ok, final OnErr err) {
        final List<String> proxies = proxies();
        if (proxies.isEmpty()) { err.onErr("代理列表为空"); return; }
        tryProxy(0, proxies, url, ok, err);
    }

    private void tryProxy(final int idx, final List<String> proxies, final HttpUrl url,
                           final OnBody ok, final OnErr err) {
        if (idx >= proxies.size()) { err.onErr("所有代理均不可用"); return; }
        fetch(url, proxies.get(idx), ok, e -> tryProxy(idx + 1, proxies, url, ok, err));
    }

    /** 单次请求：proxyBase 非空时把目标地址编码进该代理的 url 参数 */
    private void fetch(final HttpUrl url, final String proxyBase, final OnBody ok, final OnErr err) {
        final String host = url.host();
        final boolean viaProxy = proxyBase != null;
        String target = viaProxy ? proxyUrlOf(proxyBase, url.toString()) : url.toString();
        Request req = new Request.Builder()
                .url(target)
                .header("User-Agent", UA)
                .header("Accept", "application/json,text/plain,*/*")
                .build();
        final long t0 = System.currentTimeMillis();
        (viaProxy ? proxyHttp : http).newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                err.onErr("网络异常：" + e.getMessage());
            }
            @Override public void onResponse(Call call, Response resp) {
                final String body;
                try (Response r = resp) {
                    if (!r.isSuccessful()) {
                        err.onErr("HTTP " + r.code());
                        return;
                    }
                    body = decode(r);
                } catch (Exception e) {
                    err.onErr("读取失败：" + e.getMessage());
                    return;
                }
                long cost = System.currentTimeMillis() - t0;
                if (viaProxy || cost > SLOW_DIRECT_MS) markSlow(host);
                // A-24：解析与播放串拆分放到后台线程，主线程只负责最终回调
                PARSE.execute(() -> {
                    try {
                        ok.onBody(body);
                    } catch (Exception e) {
                        AppLog.w(TAG, "解析失败 " + url);
                        errMain(err, "数据解析失败：" + e.getMessage());
                    }
                });
            }
        });
    }

    /** 当前代理列表（设置页可维护，即时生效） */
    private static List<String> proxies() {
        try {
            return com.lateshow.app.store.PrefStore.get().proxies();
        } catch (Exception e) {
            List<String> def = new ArrayList<>();
            for (String p : com.lateshow.app.store.PrefStore.DEFAULT_PROXIES) def.add(p);
            return def;
        }
    }

    static String proxyUrlOf(String proxyBase, String raw) {
        try {
            return proxyBase + java.net.URLEncoder.encode(raw, "UTF-8");
        } catch (Exception e) {
            return proxyBase + raw;
        }
    }

    private boolean isSlow(String host) {
        cleanupSlow();
        Long until = slowUntil.get(host);
        return until != null && until > System.currentTimeMillis();
    }

    /** A-26：主动清理过期项并限制表大小，避免长期运行无限增长 */
    private void cleanupSlow() {
        if (slowUntil.size() < MAX_SLOW_ENTRIES) return;
        long now = System.currentTimeMillis();
        slowUntil.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
        while (slowUntil.size() > MAX_SLOW_ENTRIES) {
            String oldest = null;
            long min = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : slowUntil.entrySet()) {
                if (e.getValue() != null && e.getValue() < min) { min = e.getValue(); oldest = e.getKey(); }
            }
            if (oldest == null) break;
            slowUntil.remove(oldest);
        }
    }

    private void markSlow(String host) {
        slowUntil.put(host, System.currentTimeMillis() + SLOW_TTL_MS);
        cleanupSlow();
    }

    /** 响应体解码：优先用头部 charset，无声明时按 UTF-8，出现替换符再试 GBK；限制体积 */
    private static String decode(Response r) throws IOException {
        if (r.body() == null) return "";
        long len = r.body().contentLength();
        if (len > MAX_RESPONSE_BYTES) throw new IOException("响应过大，已拒绝解析");
        okhttp3.MediaType mt = r.body().contentType();
        Charset cs = mt == null ? null : mt.charset(null);
        byte[] bytes = r.body().bytes();
        String s = new String(bytes, cs != null ? cs : UTF8);
        if (cs == null && s.indexOf('\uFFFD') >= 0) {
            String g = new String(bytes, GBK);
            if (g.indexOf('\uFFFD') < 0) return g;
        }
        return s;
    }

    /** 苹果 CMS 播放串 → 线路/选集：统一委托 PlayListParser（D-01） */
    static List<Models.PlayLine> parsePlayList(String from, String urls) {
        return PlayListParser.parse(from, urls);
    }

    private static String stripHtml(String html) {
        if (TextUtils.isEmpty(html)) return "";
        String s = HTML_TAG.matcher(html).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        return s.replaceAll("\\s{2,}", " ").trim();
    }
}
