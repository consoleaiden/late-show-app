package com.lateshow.app.util;

import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
import com.lateshow.app.R;
import com.lateshow.app.api.StaticCatalog;
import com.lateshow.app.store.PrefStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 封面图访问方式选择器（本地测速）。
 *
 * <p>每个资源站的图片访问速度不一且不稳定，故本地按 host 建立「测速结论」：
 * <ul>
 *   <li>首次访问某源站时，用第一张图片对「直连」与「设置中当前使用的代理」
 *       并发测速（各 1 秒超时），<b>先成功者胜出</b>，后续该源站的图片都沿用
 *       这个方式；</li>
 *   <li>两者都失败 / 超时时默认直连（与旧行为一致，不阻塞出图）；</li>
 *   <li>测速结论只存在于进程内存：<b>重启 App / 切换网络环境 / 切换当前代理</b>
 *       时清空（见 {@link #clearSpeedMap()}），满足「重新测速」的要求；</li>
 *   <li>没有配置可用代理时直接直连，不测速；</li>
 *   <li>RecyclerView 复用时按视图当前 URL 校验，旧绑定不会把结论或图灌进新卡片。</li>
 * </ul>
 *
 * <p>测速请求只判「能否在 1 秒内拿到响应」，不读取响应体，避免额外流量。
 */
public final class CoverProxy {

    /** 单张图片测速超时（用户要求 1 秒） */
    private static final long SPEED_TIMEOUT_MS = 1000L;
    private static final String TAG = "CoverProxy";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120 Mobile Safari/537.36";

    /**
     * 各资源站的图片访问方式（进程内，随重启 / 网络变化 / 切换代理清空）：
     * host -&gt; {@code true} = 走当前代理，{@code false} = 直连。
     */
    private static final Map<String, Boolean> speedMode = new ConcurrentHashMap<>();
    /** 正在测速的 host：同一源站的多张图并发到达时只测一次，其余等结论 */
    private static final java.util.Set<String> racing = ConcurrentHashMap.newKeySet();
    /** 测速在途时到达的加载请求：结论出来后统一按结论加载 */
    private static final Map<String, List<Runnable>> pending = new ConcurrentHashMap<>();

    /** 主线程：Glide 的 into() 只能在主线程调用，测速回调在 OkHttp 线程需切回 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 测速专用客户端：整体 1 秒超时，失败即判负 */
    private static final OkHttpClient SPEED_HTTP = new OkHttpClient.Builder()
            .connectTimeout(SPEED_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SPEED_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(SPEED_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(SPEED_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    private CoverProxy() { }

    /** 清空测速结论：网络变化 / 切换当前代理时调用，之后首次访问各源站重新测速 */
    public static void clearSpeedMap() {
        speedMode.clear();
    }

    /**
     * 加载封面到 ImageView。placeholder / error 用调用方原配色资源，其余 Glide 选项
     * （centerCrop / RGB_565 / 无动画）与原各处加载保持一致。
     */
    public static void load(ImageView iv, String url, int placeholderRes, int errorRes) {
        iv.setTag(R.id.tag_cover_url, url);
        if (url == null || url.isEmpty()) {
            iv.setImageResource(placeholderRes);
            return;
        }
        final String host = hostOf(url);
        Boolean mode = speedMode.get(host);
        if (mode != null) {
            loadMode(iv, url, mode, placeholderRes, errorRes);
            return;
        }
        // 首次访问该源站：直连 vs 当前代理并发测速；同 host 并发到达只测一次
        if (!racing.add(host)) {
            pending.computeIfAbsent(host, k -> new ArrayList<Runnable>()).add(
                    () -> {
                        Boolean m = speedMode.get(host);
                        loadMode(iv, url, m != null && m, placeholderRes, errorRes);
                    });
            return;
        }
        final String active = PrefStore.get().activeProxy();
        if (active == null || active.isEmpty()) {
            // 无可用代理：直连，并记为直连（代理列表变更后会重新测速）
            speedMode.put(host, Boolean.FALSE);
            finishRace(host);
            loadMode(iv, url, false, placeholderRes, errorRes);
            return;
        }
        race(iv, url, host, active, placeholderRes, errorRes);
    }

    /** 测速结束（无论结论）：解除在途标记，把等待中的加载请求按结论放行 */
    private static void finishRace(String host) {
        racing.remove(host);
        List<Runnable> q = pending.remove(host);
        if (q == null) return;
        for (Runnable r : q) MAIN.post(r);
    }

    /**
     * 并发测速：同一张图分别「直连」与「经当前代理」请求（各 1 秒超时），
     * 先成功者胜出并记忆；都失败 / 超时则默认直连。
     */
    private static void race(final ImageView iv, final String url, final String host,
                             final String active, final int placeholderRes, final int errorRes) {
        final AtomicBoolean settled = new AtomicBoolean(false);
        final AtomicInteger remain = new AtomicInteger(2);
        final String proxyUrl = StaticCatalog.proxyUrlOf(active, url);
        final Call[] issued = new Call[2];
        final Settler[] box = new Settler[1];

        Callback directCb = new Callback() {
            @Override public void onFailure(Call call, IOException e) { box[0].settle(false, false); }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) { box[0].settle(false, r.isSuccessful()); }
                catch (Exception e) { box[0].settle(false, false); }
            }
        };
        Callback proxyCb = new Callback() {
            @Override public void onFailure(Call call, IOException e) { box[0].settle(true, false); }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) { box[0].settle(true, r.isSuccessful()); }
                catch (Exception e) { box[0].settle(true, false); }
            }
        };

        box[0] = (useProxy, ok) -> {
            if (ok) {
                if (!settled.compareAndSet(false, true)) return;
                speedMode.put(host, useProxy);
                AppLog.d(TAG, "测速 " + host + "：" + (useProxy ? "代理" : "直连") + " 更快");
                UiNotice.show("封面测速 " + host + "：" + (useProxy ? "代理更快" : "直连更快"));
                Call other = issued[useProxy ? 1 : 0];
                if (other != null) other.cancel();
                finishRace(host);
                MAIN.post(() -> loadMode(iv, url, useProxy, placeholderRes, errorRes));
            } else if (remain.decrementAndGet() == 0) {
                if (!settled.compareAndSet(false, true)) return;
                speedMode.put(host, Boolean.FALSE);
                AppLog.d(TAG, "测速 " + host + "：直连与代理均失败，默认直连");
                UiNotice.show("封面测速 " + host + "：均失败，暂用直连");
                finishRace(host);
                MAIN.post(() -> loadMode(iv, url, false, placeholderRes, errorRes));
            }
        };

        issued[0] = newSpeedCall(url);
        issued[1] = newSpeedCall(proxyUrl);
        issued[0].enqueue(directCb);
        issued[1].enqueue(proxyCb);
    }

    /** 测速仲裁：useProxy 表示胜出来源（false 直连 / true 代理），ok 表示是否成功 */
    private interface Settler {
        void settle(boolean useProxy, boolean ok);
    }

    private static Call newSpeedCall(String url) {
        return SPEED_HTTP.newCall(new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "image/*,*/*")
                .build());
    }

    /** 按测速结论加载：代理方式用当前代理编码地址；视图已被复用则放弃 */
    private static void loadMode(ImageView iv, String url, boolean useProxy,
                                 int placeholderRes, int errorRes) {
        if (!url.equals(iv.getTag(R.id.tag_cover_url))) return;
        String target = url;
        if (useProxy) {
            String active = PrefStore.get().activeProxy();
            if (active == null || active.isEmpty()) {
                useProxy = false;
            } else {
                target = StaticCatalog.proxyUrlOf(active, url);
            }
        }
        Glide.with(iv).load(target)
                .apply(opts(placeholderRes, errorRes))
                .into(iv);
    }

    private static RequestOptions opts(int placeholderRes, int errorRes) {
        return new RequestOptions()
                .placeholder(placeholderRes)
                .error(errorRes)
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)
                .dontAnimate();
    }

    private static String hostOf(String url) {
        try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return url; }
    }
}
