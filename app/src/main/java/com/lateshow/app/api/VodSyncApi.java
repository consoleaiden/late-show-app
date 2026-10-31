package com.lateshow.app.api;

import androidx.annotation.Nullable;
import com.google.gson.Gson;
import com.lateshow.app.store.PrefStore;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
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
 */
public final class VodSyncApi {

    public interface ApiCb<T> {
        void onOk(T result);
        void onErr(String message);
    }

    private static final Gson GSON = new Gson();
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");
    /** 所有回调统一切回主线程（UI 只能在主线程更新） */
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile VodSyncApi sInstance;
    private final OkHttpClient client;

    private VodSyncApi() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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

    public String baseUrl() {
        return PrefStore.get().baseUrl();
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
            @Override public void onFailure(Call call, IOException e) {
                String msg = "网络异常：" + e.getMessage();
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
                String msg = "网络异常：" + e.getMessage();
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
                String err = extractError(r.code(), body);
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
