package com.lateshow.app.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.lateshow.app.R;
import com.lateshow.app.api.StaticCatalog;
import com.lateshow.app.store.PrefStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 封面图代理加速加载器。
 *
 * <p>封面源站多在公网资源站，直连时部分 host 出图很慢（列表整屏灰块）。策略：
 * <ul>
 *   <li>先直连加载并计时，<b>500ms 内没出图</b>就把该 host 记入慢站点表，并立即改走
 *       「资源站直连」的代理访问 API（{@link PrefStore#proxies()} 列表 +
 *       {@link StaticCatalog#proxyUrlOf} 把封面地址编码进代理 url 参数）——
 *       与 API 请求共用同一套代理配置，设置页可维护、即时生效；</li>
 *   <li>慢站点 TTL 内的后续封面直接走代理，不再等 500ms；</li>
 *   <li>代理按列表顺序逐个尝试，全部失败回退直连；</li>
 *   <li>直连已出图（成功或失败都算）则定时器作废，不做任何切换；</li>
 *   <li>RecyclerView 复用时，旧绑定挂在该 ImageView 上的定时任务会被撤掉，
 *       代理失败链也会按「视图当前 URL」校验，不会把旧图灌进复用后的卡片。</li>
 * </ul>
 *
 * <p>500ms 的代价：慢 host 上第一张封面会先经历一次被取消的直连请求，随后代理出图；
 * 快 host 无任何额外开销（500ms 定时器空转一次）。
 */
public final class CoverProxy {

    /** 直连出图超时：超过即转代理加速 */
    private static final long TIMEOUT_MS = 500L;
    /** 慢站点记忆时长：TTL 内该 host 的封面直接走代理 */
    private static final long SLOW_TTL_MS = 10 * 60 * 1000L;
    /** 慢站点表上限：超过即清理过期项（同 StaticCatalog.cleanupSlow 的思路） */
    private static final int MAX_SLOW = 128;

    private static final String TAG = "CoverProxy";
    private static final Map<String, Long> slowUntil = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CoverProxy() { }

    /**
     * 加载封面到 ImageView。placeholder / error 用调用方原配色资源，其余 Glide 选项
     * （centerCrop / RGB_565 / 无动画）与原各处加载保持一致。
     */
    public static void load(ImageView iv, String url, int placeholderRes, int errorRes) {
        // 视图复用：撤掉上一次绑定挂在该视图上的定时任务
        Runnable old = (Runnable) iv.getTag(R.id.tag_cover_task);
        if (old != null) { MAIN.removeCallbacks(old); iv.setTag(R.id.tag_cover_task, null); }
        iv.setTag(R.id.tag_cover_url, url);

        Context c = iv.getContext();
        if (url == null || url.length() == 0) {
            iv.setImageResource(placeholderRes);
            return;
        }
        List<String> proxies = PrefStore.get().proxies();
        if (proxies.isEmpty()) {
            AppLog.d(TAG, "代理列表为空，仅直连 " + hostOf(url));
            direct(iv, url, placeholderRes, errorRes, null);
            return;
        }
        final String host = hostOf(url);
        if (isSlow(host)) {
            viaProxies(iv, url, proxies, 0, placeholderRes, errorRes);
            return;
        }
        // 直连计时：500ms 内没出图（成功/失败都算出图）就转代理
        final boolean[] settled = {false};
        RequestListener<Drawable> watcher = new RequestListener<Drawable>() {
            @Override public boolean onLoadFailed(@Nullable GlideException e, Object model,
                    Target<Drawable> target, boolean isFirstResource) {
                settled[0] = true;
                return false;   // 不消费：error 占位图照常显示
            }
            @Override public boolean onResourceReady(Drawable resource, Object model,
                    Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                settled[0] = true;
                return false;
            }
        };
        direct(iv, url, placeholderRes, errorRes, watcher);
        Runnable switchTask = () -> {
            iv.setTag(R.id.tag_cover_task, null);
            if (settled[0]) return;
            if (!url.equals(iv.getTag(R.id.tag_cover_url))) return;   // 已被复用绑定别的图
            AppLog.d(TAG, "封面直连超 " + TIMEOUT_MS + "ms，转代理 " + host);
            markSlow(host);
            viaProxies(iv, url, proxies, 0, placeholderRes, errorRes);
        };
        iv.setTag(R.id.tag_cover_task, switchTask);
        MAIN.postDelayed(switchTask, TIMEOUT_MS);
    }

    /** 代理失败链：按列表顺序逐个尝试，全部失败回退直连 */
    private static void viaProxies(ImageView iv, String url, List<String> proxies, int idx,
                                   int placeholderRes, int errorRes) {
        if (!url.equals(iv.getTag(R.id.tag_cover_url))) return;   // 视图已被复用
        if (idx >= proxies.size()) {
            AppLog.d(TAG, "代理全部失败，回退直连 " + hostOf(url));
            direct(iv, url, placeholderRes, errorRes, null);
            return;
        }
        String purl = StaticCatalog.proxyUrlOf(proxies.get(idx), url);
        Glide.with(iv).load(purl)
                .apply(opts(placeholderRes, errorRes))
                .listener(new RequestListener<Drawable>() {
                    @Override public boolean onLoadFailed(@Nullable GlideException e, Object model,
                            Target<Drawable> target, boolean isFirstResource) {
                        AppLog.d(TAG, "代理 " + idx + " 失败，尝试下一个：" + e);
                        viaProxies(iv, url, proxies, idx + 1, placeholderRes, errorRes);
                        return true;   // 消费错误：不闪 error 占位图，链式尝试下一个代理
                    }
                    @Override public boolean onResourceReady(Drawable resource, Object model,
                            Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(iv);
    }

    private static void direct(ImageView iv, String url, int placeholderRes, int errorRes,
                               RequestListener<Drawable> listener) {
        com.bumptech.glide.RequestBuilder<Drawable> b = Glide.with(iv).load(url)
                .apply(opts(placeholderRes, errorRes));
        if (listener != null) b = b.listener(listener);
        b.into(iv);
    }

    private static RequestOptions opts(int placeholderRes, int errorRes) {
        return new RequestOptions()
                .placeholder(placeholderRes)
                .error(errorRes)
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)
                .dontAnimate();
    }

    // ---------------- 慢站点记忆（进程级） ----------------

    private static String hostOf(String url) {
        try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return url; }
    }

    private static boolean isSlow(String host) {
        cleanup();
        Long until = slowUntil.get(host);
        return until != null && until > System.currentTimeMillis();
    }

    private static void markSlow(String host) {
        slowUntil.put(host, System.currentTimeMillis() + SLOW_TTL_MS);
        cleanup();
    }

    private static void cleanup() {
        if (slowUntil.size() < MAX_SLOW) return;
        long now = System.currentTimeMillis();
        slowUntil.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
        while (slowUntil.size() > MAX_SLOW) {
            String oldest = null; long min = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : slowUntil.entrySet()) {
                if (e.getValue() != null && e.getValue() < min) { min = e.getValue(); oldest = e.getKey(); }
            }
            if (oldest == null) break;
            slowUntil.remove(oldest);
        }
    }
}
