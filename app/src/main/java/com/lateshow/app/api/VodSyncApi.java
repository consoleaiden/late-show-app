package com.lateshow.app.api;

import androidx.annotation.Nullable;
import com.google.gson.Gson;
import com.lateshow.app.model.Models;
import com.lateshow.app.store.PrefStore;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * VodSync API 封装。
 * 约定：成功 HTTP 200 + code:1；失败为 HTTP 4xx/5xx + detail。
 *
 * 鉴权（服务端 v1.5.0+）：除 /healthz 与 /api/v1/auth/* 外，所有接口都需要
 * {@code Authorization: Bearer <token>}。Token 由拦截器统一注入，且只注入给「自己服务器」的请求，
 * 绝不外泄给第三方封面图 / m3u8 CDN。
 */
public final class VodSyncApi {

    public interface ApiCb<T> {
        void onOk(T result);
        void onErr(String message);
    }

    /** onErr 里 token 失效的统一前缀（UI 据此引导用户去设置页） */
    public static final String TOKEN_ERR = "[[token-invalid]]";
    private static final String DBG = "LateShowApi";

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
    private static volatile VodSyncApi sInstance;
    private final OkHttpClient client;

    private VodSyncApi() {
        Interceptor auth = chain -> {
            Request req = chain.request();
            String token = PrefStore.get().apiToken();
            // 只给「自己服务器」加头：封面/播放直链通常在第三方 CDN，带上就是泄漏
            boolean authed = false;
            if (!token.isEmpty() && sameHost(req.url().host(), hostOf(baseUrl()))) {
                // 主用 Authorization，同时带兼容头 X-API-Key（个别中间层会剥离 Authorization）
                req = req.newBuilder()
                        .header("Authorization", "Bearer " + token)
                        .header("X-API-Key", token)
                        .build();
                authed = true;
            }
            android.util.Log.d(DBG, "→ " + req.method() + " " + req.url().encodedPath()
                    + " host=" + req.url().host()
                    + " auth=" + (authed ? com.lateshow.app.util.SecureStore.mask(token) : "NO"));
            return chain.proceed(req);
        };
        client = new OkHttpClient.Builder()
                .dns(SmartDns.INSTANCE)
                .addInterceptor(auth)
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

    /** 连接层失败 → 值得清缓存重试一次（请求尚未到达服务器，GET 重试无副作用） */
    private static boolean recoverable(IOException e) {
        return e instanceof java.net.UnknownHostException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.ConnectException;
    }

    /** 网络异常 → 可读文案：区分「域名解析失败 / 地址族不可达 / 超时」，便于用户判断是否为 IPv6 问题 */
    private static String describe(IOException e) {
        if (e == null) return "未知错误";
        String raw = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.UnknownHostException) {
            // SmartDns 会给出「当前 WiFi 无 IPv6，而该后端仅有 IPv6 地址」这类具体原因
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

    /**
     * 兜底用：当播放/图片地址确实由本服务代理转发时，用 query 参数带上 token
     * （WebView / video 标签 / 系统播放器加不了请求头的情况）。第三方 CDN 直链原样返回。
     */
    public String decorate(String url) {
        if (url == null || url.isEmpty()) return url;
        String token = PrefStore.get().apiToken();
        if (token.isEmpty()) return url;
        HttpUrl u = HttpUrl.parse(url);
        if (u == null || !sameHost(u.host(), hostOf(baseUrl()))) return url;
        if (u.queryParameter("token") != null) return url;
        return u.newBuilder().addQueryParameter("token", token).build().toString();
    }

    /** 鉴权状态探测（免鉴权接口）：判断服务端是否开启访问控制 */
    public void authState(final ApiCb<Models.AuthState> cb) {
        get("/api/v1/auth/state", null, Models.AuthState.class, cb);
    }

    /** GET 请求并解析为指定类型 */
    public <T> void get(String path, @Nullable Map<String, String> params, final Class<T> clazz, final ApiCb<T> cb) {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(baseUrl() + path);
        if (base == null) {
            cb.onErr("地址无效：" + baseUrl() + path);
            return;
        }
        HttpUrl.Builder b = base.newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) b.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        Request req = new Request.Builder().url(b.build()).get().build();
        client.newCall(req).enqueue(new Callback() {
            private boolean retried = false;

            @Override public void onFailure(Call call, IOException e) {
                // 切网后的第一次请求常见：解析结果还是上个网络的 / 连接池里是旧网卡的连接
                // → 清缓存 + 清连接池后重试一次（GET 幂等，重试安全）
                if (!retried && recoverable(e)) {
                    retried = true;
                    SmartDns.invalidate();
                    client.connectionPool().evictAll();
                    android.util.Log.d(DBG, "↻ 连接失败（" + e + "），已清 DNS/连接池并重试一次");
                    client.newCall(call.request()).enqueue(this);
                    return;
                }
                String msg = "网络异常：" + describe(e);
                MAIN.post(() -> cb.onErr(msg));
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                deliver(resp, clazz, cb);
            }
        });
    }

    /** POST 请求（query 传参）并解析为指定类型 */
    public <T> void post(String path, @Nullable Map<String, String> params, final Class<T> clazz, final ApiCb<T> cb) {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(baseUrl() + path);
        if (base == null) {
            cb.onErr("地址无效：" + baseUrl() + path);
            return;
        }
        HttpUrl.Builder b = base.newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() != null) b.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        Request req = new Request.Builder().url(b.build())
                .post(RequestBody.create("", FORM)).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                String msg = "网络异常：" + describe(e);
                MAIN.post(() -> cb.onErr(msg));
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                deliver(resp, clazz, cb);
            }
        });
    }

    /** POST 请求（JSON body，FastAPI BaseModel 接口用） */
    public <T> void postJson(String path, @Nullable String jsonBody, final Class<T> clazz, final ApiCb<T> cb) {
        sendJson("POST", path, null, jsonBody, clazz, cb);
    }

    /** PATCH 请求（JSON body） */
    public <T> void patchJson(String path, @Nullable String jsonBody, final Class<T> clazz, final ApiCb<T> cb) {
        sendJson("PATCH", path, null, jsonBody, clazz, cb);
    }

    /** DELETE 请求（query 传参） */
    public <T> void delete(String path, @Nullable Map<String, String> params, final Class<T> clazz, final ApiCb<T> cb) {
        sendJson("DELETE", path, params, null, clazz, cb);
    }

    /** 统一的 JSON/DELETE 发送器 */
    private <T> void sendJson(String method, String path, @Nullable Map<String, String> params,
                              @Nullable String jsonBody, final Class<T> clazz, final ApiCb<T> cb) {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(baseUrl() + path);
        if (base == null) {
            cb.onErr("地址无效：" + baseUrl() + path);
            return;
        }
        HttpUrl.Builder b = base.newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() != null) b.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        RequestBody body = jsonBody != null
                ? RequestBody.create(jsonBody, JSON)
                : RequestBody.create("", FORM);
        Request req = new Request.Builder().url(b.build()).method(method, body).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                String msg = "网络异常：" + describe(e);
                MAIN.post(() -> cb.onErr(msg));
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                deliver(resp, clazz, cb);
            }
        });
    }

    private <T> void deliver(Response resp, Class<T> clazz, ApiCb<T> cb) {
        try (Response r = resp) {
            String body = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                android.util.Log.d(DBG, "← " + r.code() + " " + r.request().url().encodedPath()
                        + " body=" + (body.length() > 300 ? body.substring(0, 300) : body));
                // 401：token 缺失/错误/被重置 —— 不重试，交给 UI 引导填写
                String err = r.code() == 401
                        ? tokenErrMsg(extractError(r.code(), body))
                        : extractError(r.code(), body);
                MAIN.post(() -> cb.onErr(err));
                return;
            }
            T out = GSON.fromJson(body, clazz);
            if (out == null) {
                MAIN.post(() -> cb.onErr("响应为空"));
                return;
            }
            MAIN.post(() -> cb.onOk(out));
        } catch (Exception e) {
            String msg = "解析失败：" + e.getMessage();
            MAIN.post(() -> cb.onErr(msg));
        }
    }

    /** 从错误响应里提取 detail（字符串或 FastAPI 校验数组） */
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

    /** 健康检查：返回延迟 ms，失败回调 onErr */
    public void healthz(final ApiCb<Long> cb) {
        long t0 = System.currentTimeMillis();
        get("/healthz", null, java.util.Map.class, new ApiCb<java.util.Map>() {
            @Override public void onOk(java.util.Map result) {
                cb.onOk(System.currentTimeMillis() - t0);
            }
            @Override public void onErr(String message) {
                cb.onErr(message);
            }
        });
    }
}
