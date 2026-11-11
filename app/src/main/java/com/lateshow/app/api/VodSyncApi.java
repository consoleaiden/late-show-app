package com.lateshow.app.api;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.lateshow.app.model.Models;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.util.ApiPaths;
import com.lateshow.app.util.AppLog;
import com.lateshow.app.util.SecureStore;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * VodSync API 封装。
 *
 * 约定（B-11）：成功 = HTTP 2xx 且业务 {@code code:0}（或纯数据响应不含 code / ok）；
 * 失败 = HTTP 4xx/5xx + detail，或 2xx 但 {@code code != 0} / {@code ok:false}。
 * v1.14 起后端统一为 {@code code:0} 表示成功，{@code code:1} 的旧口径已废弃。
 *
 * 审计 A-11/A-20/A-30/C-06/C-07/A-06：
 * <ul>
 *   <li>每个请求注册到可取消的 tag 集合，{@link #cancelTag(Object)} / {@link #cancelAll()}
 *       可在页面销毁、筛选变更时取消在途请求；600ms 重试重建 Request 而非复用旧对象；</li>
 *   <li>{@code deliver()} 校验业务失败码；healthz 与 ping 统一口径并读取 {@code version/capabilities}
 *       写入 {@link ServerCaps}；</li>
 *   <li>免鉴权路径不注入 Token，避免后端收紧 auth 接口后形成引导死锁；</li>
 *   <li>所有方法跳过空串参数，DELETE 不带空 FORM body；</li>
 *   <li>日志统一走 {@link AppLog}（release 关闭 + 脱敏），不再把 token / 完整 URL 写进 logcat。</li>
 * </ul>
 */
public final class VodSyncApi {

    public interface ApiCb<T> {
        void onOk(T result);
        void onErr(String message);
    }

    /** onErr 里 token 失效的统一前缀（UI 据此引导用户去设置页） */
    public static final String TOKEN_ERR = "[[token-invalid]]";
    private static final String DBG = "Api";
    private static final long HEALTH_TIMEOUT_MS = 3000L;

    public static boolean isTokenError(String msg) {
        return msg != null && msg.startsWith(TOKEN_ERR);
    }

    public static String tokenErrorDetail(String msg) {
        if (msg == null) return "";
        if (msg.startsWith(TOKEN_ERR)) return msg.substring(TOKEN_ERR.length()).trim();
        return msg.trim();
    }

    public static String tokenErrMsg(String detail) {
        return TOKEN_ERR + (detail == null || detail.isEmpty() ? "API Token 无效或未填写" : detail);
    }

    private static final Gson GSON = new Gson();
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    /** 所有回调统一切回主线程（UI 只能在主线程更新） */
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 在途请求索引（审计 A-11）：tag → Call 集合，可按页面/筛选维度整体取消 */
    private static final Map<Object, Set<Call>> ACTIVE = new ConcurrentHashMap<>();
    private static final Object DEFAULT_TAG = new Object();

    private static volatile VodSyncApi sInstance;
    private final OkHttpClient client;

    private VodSyncApi() {
        Interceptor auth = chain -> {
            Request req = chain.request();
            String token = PrefStore.get().apiToken();
            String path = req.url().encodedPath();
            boolean own = sameHost(req.url().host(), hostOf(baseUrl()));
            // 只给「自己服务器」加头，且跳过免鉴权路径（C-07）。
            boolean authed = false;
            if (own && !token.isEmpty()
                    && !ApiPaths.isPublic(path)) {
                req = req.newBuilder()
                        .header("Authorization", "Bearer " + token)
                        .header("X-API-Key", token)
                        .build();
                authed = true;
            }
            // 未成年模式（VodSync v1.15.0 minor_mode）：开启后本服务的每个 API 请求
            // 都带 X-Minor-Mode: 1，服务端在全部内容读接口统一剔除 18+ 源站；
            // 老后端会忽略未知请求头，无需能力门控。关闭 = 不带头，服务端零开销。
            if (own && PrefStore.get().minorMode()) {
                req = req.newBuilder().header("X-Minor-Mode", "1").build();
            }
            AppLog.d(DBG, "→ " + req.method() + " " + path
                    + " host=" + req.url().host()
                    + " auth=" + (authed ? SecureStore.mask(token) : "NO"));
            return chain.proceed(req);
        };
        client = new OkHttpClient.Builder()
                .dns(SmartDns.INSTANCE)
                .addInterceptor(auth)
                .followSslRedirects(false)   // 审计 A-35：禁止协议降级
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public static VodSyncApi get() {
        if (sInstance == null) {
            synchronized (VodSyncApi.class) {
                if (sInstance == null) sInstance = new VodSyncApi();
            }
        }
        return sInstance;
    }

    /** 切网回调（NetMonitor 调用）：旧连接绑定的是已失效的网卡地址，必须全部丢弃 */
    public static void onNetworkChanged() {
        VodSyncApi i = sInstance;
        if (i == null) return;
        try {
            i.client.connectionPool().evictAll();
        } catch (Exception ignored) { }
    }

    // ---------- 在途请求取消（A-11） ----------

    private static void register(Object tag, Call call) {
        ACTIVE.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet()).add(call);
    }

    private static void unregister(Object tag, Call call) {
        Set<Call> s = ACTIVE.get(tag);
        if (s == null) return;
        s.remove(call);
        if (s.isEmpty()) ACTIVE.remove(tag, s);
    }

    /** 取消某一组请求（例如首页筛选、控制台轮询）；被取消的请求不再回调 UI */
    public static void cancelTag(Object tag) {
        if (tag == null) return;
        Set<Call> s = ACTIVE.remove(tag);
        if (s == null) return;
        for (Call c : s) {
            try { c.cancel(); } catch (Exception ignored) { }
        }
    }

    /** 取消全部在途请求（切换后端 / 退出应用时用） */
    public static void cancelAll() {
        for (Object t : new ArrayList<>(ACTIVE.keySet())) cancelTag(t);
    }

    private static boolean recoverable(IOException e) {
        return e instanceof java.net.UnknownHostException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.ConnectException;
    }

    /** 网络异常 → 可读文案：区分「域名解析失败 / 地址族不可达 / 超时」 */
    private static String describe(IOException e) {
        if (e == null) return "未知错误";
        String raw = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.UnknownHostException) {
            return raw.isEmpty() ? "无法解析后端域名（请检查网络）" : raw;
        }
        if (e instanceof java.net.SocketTimeoutException) return "连接超时（后端不可达或网络不通）";
        if (e instanceof java.net.NoRouteToHostException) return "网络不可达（当前网络不支持该地址）";
        if (e instanceof java.net.ConnectException) return "无法连接后端：" + raw;
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unreachable") || lower.contains("enetunreach")) {
            return "网络不可达（地址族与当前网络不匹配）";
        }
        if (lower.contains("failed to connect")) return "无法连接后端：" + raw;
        return raw.isEmpty() ? e.getClass().getSimpleName() : raw;
    }

    public String baseUrl() {
        return PrefStore.get().baseUrl();
    }

    private static String hostOf(String url) {
        try {
            HttpUrl u = HttpUrl.parse(url);
            return u == null ? null : u.host();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sameHost(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private HttpUrl urlOf(String path) {
        String full = path != null && (path.startsWith("http://") || path.startsWith("https://"))
                ? path : baseUrl() + path;
        return HttpUrl.parse(full);
    }

    /**
     * 兜底用：当播放/图片地址确实由本服务代理转发时，理论上需要带凭据。
     * 审计 A-06 后不再把 Token 追加到 URL query（URL 会进日志、剪贴板、播放器缓存与 CDN 日志）。
     * 若后端代理播放需要鉴权，应改用短期签名 URL（见后端协同报告 B-09）。
     *
     * <p>未成年模式（v1.15.0）：指向本服务的播放/图片地址无法自定义请求头（播放器内核、
     * <img> 等），按后端契约改加 {@code ?minor_mode=1} 查询参数（非敏感，仅模式开关）。
     */
    public String decorate(String url) {
        try {
            if (!PrefStore.get().minorMode()) return url;
            HttpUrl u = HttpUrl.parse(url);
            if (u == null || !sameHost(u.host(), hostOf(baseUrl()))) return url;
            if (u.queryParameter("minor_mode") != null) return url;
            return u.newBuilder().addQueryParameter("minor_mode", "1").build().toString();
        } catch (Exception e) {
            return url;
        }
    }

    /** 鉴权状态探测（免鉴权接口）：判断服务端是否开启访问控制 */
    public void authState(final ApiCb<Models.AuthState> cb) {
        get(ApiPaths.AUTH_STATE, null, Models.AuthState.class, cb);
    }

    // ---------- 统一发送器 ----------

    private <T> void enqueue(Request req, Class<T> clazz, ApiCb<T> cb, Object tag, boolean allowRetry) {
        final Object t = tag == null ? DEFAULT_TAG : tag;
        final Call call = client.newCall(req);
        register(t, call);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call c, IOException e) {
                if (allowRetry && recoverable(e) && !c.isCanceled()) {
                    unregister(t, call);
                    SmartDns.invalidate();
                    client.connectionPool().evictAll();
                    AppLog.d(DBG, "↻ 连接失败，600ms 后清缓存重试一次");
                    MAIN.postDelayed(() -> {
                        if (c.isCanceled()) return;
                        // 审计 A-11：重建 Request，不复用旧 Request 对象
                        enqueue(req.newBuilder().build(), clazz, cb, t, false);
                    }, 600);
                    return;
                }
                unregister(t, call);
                if (c.isCanceled()) return;   // 主动取消不回调 UI
                String msg = "网络异常：" + describe(e);
                MAIN.post(() -> cb.onErr(msg));
            }

            @Override public void onResponse(Call c, Response resp) throws IOException {
                unregister(t, call);
                if (c.isCanceled()) return;
                deliver(resp, clazz, cb);
            }
        });
    }

    /** GET 请求并解析为指定类型 */
    public <T> void get(String path, @Nullable Map<String, String> params,
                        final Class<T> clazz, final ApiCb<T> cb) {
        get(path, params, clazz, null, cb);
    }

    public <T> void get(String path, @Nullable Map<String, String> params,
                        final Class<T> clazz, @Nullable Object tag, final ApiCb<T> cb) {
        HttpUrl base = urlOf(path);
        if (base == null) {
            cb.onErr("地址无效：" + baseUrl() + path);
            return;
        }
        HttpUrl.Builder b = base.newBuilder();
        addParams(b, params);
        enqueue(new Request.Builder().url(b.build()).get().build(), clazz, cb, tag, true);
    }

    /** POST 请求（query 传参）并解析为指定类型 */
    public <T> void post(String path, @Nullable Map<String, String> params,
                         final Class<T> clazz, final ApiCb<T> cb) {
        HttpUrl base = urlOf(path);
        if (base == null) {
            cb.onErr("地址无效：" + baseUrl() + path);
            return;
        }
        HttpUrl.Builder b = base.newBuilder();
        addParams(b, params);
        enqueue(new Request.Builder().url(b.build())
                .post(RequestBody.create("", FORM)).build(), clazz, cb, null, false);
    }

    /** POST 请求（JSON body，FastAPI BaseModel 接口用） */
    public <T> void postJson(String path, @Nullable String jsonBody,
                            final Class<T> clazz, final ApiCb<T> cb) {
        sendJson("POST", path, null, jsonBody, clazz, cb);
    }

    /** PATCH 请求（JSON body） */
    public <T> void patchJson(String path, @Nullable String jsonBody,
                              final Class<T> clazz, final ApiCb<T> cb) {
        sendJson("PATCH", path, null, jsonBody, clazz, cb);
    }

    /** DELETE 请求（query 传参，不带空 FORM body —— 审计 C-06） */
    public <T> void delete(String path, @Nullable Map<String, String> params,
                           final Class<T> clazz, final ApiCb<T> cb) {
        sendJson("DELETE", path, params, null, clazz, cb);
    }

    private <T> void sendJson(String method, String path, @Nullable Map<String, String> params,
                               @Nullable String jsonBody, final Class<T> clazz, final ApiCb<T> cb) {
        HttpUrl base = urlOf(path);
        if (base == null) {
            cb.onErr("地址无效：" + baseUrl() + path);
            return;
        }
        HttpUrl.Builder b = base.newBuilder();
        addParams(b, params);
        RequestBody body = jsonBody != null ? RequestBody.create(jsonBody, JSON)
                : ("DELETE".equals(method) ? null : RequestBody.create("", FORM));
        enqueue(new Request.Builder().url(b.build()).method(method, body).build(),
                clazz, cb, null, false);
    }

    /** 所有方法统一跳过空串参数（审计 C-06），避免中间层把 key= 当成显式空值而返回 422 */
    private static void addParams(HttpUrl.Builder b, @Nullable Map<String, String> params) {
        if (params == null) return;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() == null) continue;
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                b.addQueryParameter(e.getKey(), e.getValue());
            }
        }
    }

    private <T> void deliver(Response resp, Class<T> clazz, ApiCb<T> cb) {
        try (Response r = resp) {
            String body = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                AppLog.d(DBG, "← " + r.code() + " " + r.request().url().encodedPath());
                // 401：token 缺失/错误/被重置 —— 不重试，交给 UI 引导填写
                String err = r.code() == 401
                        ? tokenErrMsg(extractError(r.code(), body))
                        : extractError(r.code(), body);
                MAIN.post(() -> cb.onErr(err));
                return;
            }
            // 审计 A-20：HTTP 200 也可能是业务失败（code:0 / ok:false），不能当成功渲染
            String biz = businessError(body);
            if (biz != null) {
                MAIN.post(() -> cb.onErr(biz));
                return;
            }
            T out = GSON.fromJson(body, clazz);
            if (out == null) {
                MAIN.post(() -> cb.onErr("响应为空"));
                return;
            }
            MAIN.post(() -> cb.onOk(out));
        } catch (Exception e) {
            MAIN.post(() -> cb.onErr("解析失败：" + friendlyParseError(e)));
        }
    }

    /**
     * 把 Gson/JSON 的底层异常翻译成用户能看懂的一句话，并截断长度，
     * 避免首页/控制台弹出几十行的原始堆栈（后端字段类型变更时常见）。
     */
    private static String friendlyParseError(Exception e) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String up = raw.toUpperCase();
        if (raw.contains("JsonSyntaxException") || raw.contains("MalformedJsonException")
                || up.contains("EXPECTED") || up.contains("BEGIN_OBJECT")
                || up.contains("BEGIN_ARRAY") || raw.contains("IllegalStateException")) {
            return "服务器数据格式与 App 版本不匹配，请检查 App 或后端是否已更新";
        }
        if (raw.length() > 100) raw = raw.substring(0, 100) + "…";
        return raw;
    }

    /**
     * 只把「明确的业务失败」判为错误；纯数据响应（无 code / 无 ok）一律放行。
     *
     * <p>业务码语义（后端协同报告 B-11，2026-11-09 起生效）：
     * 成功 = HTTP 2xx 且 {@code code == 0}；业务失败 = {@code code != 0}。
     * 旧版「code:1 为成功」的约定已废弃，后端 v1.14.x 全部接口已切到 code:0（实测 /stats、
     * /videos、/sources、/search、detail 均为 {"code":0,...}）。
     */
    private static String businessError(String body) {
        if (body == null || body.isEmpty() || body.charAt(0) != '{') return null;
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("code") && o.optInt("code", 0) != 0) {
                String m = o.optString("error", "");
                if (m.isEmpty()) m = o.optString("msg", "");
                if (m.isEmpty()) m = o.optString("detail", "操作失败");
                // 2xx 里不该出现 40101，但万一出现仍按 token 失效引导，避免死循环提示
                if (o.optInt("code", 0) == 40101) return tokenErrMsg(m);
                return "业务失败：" + m;
            }
            if (o.has("ok") && !o.optBoolean("ok", true)) {
                String m = o.optString("error", "");
                if (m.isEmpty()) m = o.optString("detail", "操作未完成");
                return "操作失败：" + m;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String extractError(int code, String body) {
        try {
            JSONObject o = new JSONObject(body);
            Object detail = o.opt("detail");
            if (detail instanceof String) return (String) detail;
            if (detail instanceof JSONArray) {
                JSONArray arr = (JSONArray) detail;
                if (arr.length() > 0) {
                    JSONObject first = arr.getJSONObject(0);
                    String loc = first.optJSONArray("loc") != null ? first.optJSONArray("loc").optString(1) : "";
                    String msg = first.optString("msg", "");
                    return (loc.isEmpty() ? "" : loc + " ") + msg;
                }
            }
            return body.isEmpty() ? "HTTP " + code : body;
        } catch (Exception e) {
            return "HTTP " + code;
        }
    }

    // ---------- 健康检查 / 能力门控（A-20 / A-30） ----------

    /** 健康检查：与 ping 完全同一口径（2xx 即有效），顺带读取版本与能力清单 */
    public void healthz(final ApiCb<Long> cb) {
        ping(HEALTH_TIMEOUT_MS, cb);
    }

    /**
     * 播放源连接测试：/healthz 往返延迟，独立短超时。
     * 派生客户端保留 DNS 配置，但用固定 token 的拦截器由主客户端继承；
     * 只判 HTTP 2xx，不要求 JSON 可解析（后端若改成纯文本 ok 也不会误判故障）。
     * 响应里若有 version / capabilities，写入 ServerCaps 供能力门控使用。
     */
    public void ping(long timeoutMs, final ApiCb<Long> cb) {
        HttpUrl url = urlOf(ApiPaths.HEALTHZ);
        if (url == null) {
            cb.onErr("地址无效");
            return;
        }
        OkHttpClient probeClient = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        Request req = new Request.Builder().url(url).get().build();
        final long t0 = System.currentTimeMillis();
        final Call call = probeClient.newCall(req);
        register(DEFAULT_TAG, call);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call c, IOException e) {
                unregister(DEFAULT_TAG, call);
                if (c.isCanceled()) return;
                MAIN.post(() -> cb.onErr("连接超时或不可达"));
            }

            @Override public void onResponse(Call c, Response resp) {
                unregister(DEFAULT_TAG, call);
                try (Response r = resp) {
                    final boolean ok = r.isSuccessful();
                    final int code = r.code();
                    final long cost = System.currentTimeMillis() - t0;
                    if (ok && r.body() != null) {
                        String body = r.body().string();
                        parseCaps(body);
                    }
                    MAIN.post(() -> {
                        if (ok) cb.onOk(cost);
                        else cb.onErr("HTTP " + code);
                    });
                } catch (Exception e) {
                    MAIN.post(() -> cb.onErr("连接测试失败"));
                }
            }
        });
    }

    /** /healthz 返回 {"version":"1.13.0","capabilities":["..."]} 时更新能力门控 */
    private static void parseCaps(String body) {
        if (body == null || body.isEmpty() || body.charAt(0) != '{') return;
        try {
            JSONObject o = new JSONObject(body);
            String v = o.optString("version", "");
            Set<String> caps = new HashSet<>();
            JSONArray arr = o.optJSONArray("capabilities");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "");
                    if (!s.isEmpty()) caps.add(s);
                }
            }
            JSONObject capObj = o.optJSONObject("capabilities");
            if (capObj != null) {
                java.util.Iterator<String> it = capObj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (capObj.optBoolean(k, false)) caps.add(k);
                }
            }
            ServerCaps.update(v, caps);
        } catch (Exception ignored) { }
    }

    // ---------- 临时凭据验证（A-07） ----------

    public static class VerifyResult {
        public boolean authEnabled;
        public long elapsedMs;
    }

    /**
     * 用「临时 baseUrl + 临时 token」做一次性验证，全程不读写全局 PrefStore，
     * 成功后由调用方原子提交。这样「测试连接」永远不会产生副作用（审计 A-07）。
     *
     * 流程：GET /api/v1/auth/state（免鉴权）→ 若开启鉴权且 token 非空 →
     * GET /api/v1/stats（带临时 token）→ 全部通过才 onOk。
     */
    public static void verify(final String base, final String token,
                              final ApiCb<VerifyResult> cb) {
        new Thread(() -> {
            String tmp = base == null ? "" : base.trim();
            while (tmp.endsWith("/")) tmp = tmp.substring(0, tmp.length() - 1);
            final String root = tmp;   // lambda 捕获：去尾斜杠后固化为 final
            String t = token == null ? "" : token.trim();
            long t0 = System.currentTimeMillis();
            OkHttpClient probe = new OkHttpClient.Builder()
                    .dns(SmartDns.INSTANCE)
                    .followSslRedirects(false)
                    .addInterceptor(chain -> {
                        Request req = chain.request();
                        if (!t.isEmpty() && !ApiPaths.isPublic(req.url().encodedPath())) {
                            req = req.newBuilder()
                                    .header("Authorization", "Bearer " + t)
                                    .header("X-API-Key", t)
                                    .build();
                        }
                        return chain.proceed(req);
                    })
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .callTimeout(8, TimeUnit.SECONDS)
                    .build();
            try {
                HttpUrl authUrl = HttpUrl.parse(root + ApiPaths.AUTH_STATE);
                if (authUrl == null) {
                    MAIN.post(() -> cb.onErr("地址无效：" + root));
                    return;
                }
                String authBody = execute(probe, new Request.Builder().url(authUrl).get().build());
                JSONObject auth = new JSONObject(authBody);
                boolean enabled = auth.optBoolean("enabled", false);

                if (enabled && t.isEmpty()) {
                    MAIN.post(() -> cb.onErr("[[need-token]]该服务器已开启访问控制，需要填写 API Token"));
                    return;
                }
                if (enabled) {
                    HttpUrl statsUrl = HttpUrl.parse(root + ApiPaths.STATS);
                    if (statsUrl == null) {
                        MAIN.post(() -> cb.onErr("地址无效：" + root));
                        return;
                    }
                    String statsBody = execute(probe, new Request.Builder().url(statsUrl).get().build());
                    JSONObject st = new JSONObject(statsBody);
                    // B-11 语义：2xx 且 code==0 才是成功；探测已带 token，401 在 execute() 里抛出
                    if (st.has("code") && st.optInt("code", 0) != 0) {
                        MAIN.post(() -> cb.onErr(tokenErrMsg(st.optString("error", ""))));
                        return;
                    }
                }
                VerifyResult r = new VerifyResult();
                r.authEnabled = enabled;
                r.elapsedMs = System.currentTimeMillis() - t0;
                MAIN.post(() -> cb.onOk(r));
            } catch (IOException e) {
                final String msg;
                if (e.getMessage() != null && e.getMessage().contains("401")) {
                    msg = tokenErrMsg("API Token 无效，请重新复制");
                } else {
                    msg = "无法连接：" + describe(e);
                }
                MAIN.post(() -> cb.onErr(msg));
            } catch (Exception e) {
                MAIN.post(() -> cb.onErr("验证失败：" + e.getMessage()));
            }
        }, "verify-source").start();
    }

    private static String execute(OkHttpClient c, Request req) throws IOException {
        try (Response r = c.newCall(req).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            if (r.code() == 401) throw new IOException("401 " + body);
            if (!r.isSuccessful()) {
                throw new IOException("HTTP " + r.code() + " " + extractError(r.code(), body));
            }
            return body;
        }
    }
}
