package com.lateshow.app.api;

import com.lateshow.app.model.Models;
import com.lateshow.app.util.ApiPaths;

import java.util.HashMap;
import java.util.Map;

/**
 * NAS 同步库数据源：VodSync REST 接口。
 *
 * 审计 A-30 / B-01：标签检索按后端能力门控。后端 capabilities 含 {@code tag_search}
 * 时，标签点击走精确的 {@code tag} 参数；否则退化为原有的 {@code wd} 全文检索。
 *
 * <p>M2 / M5（2026-11-10 接口核对）：
 * <ul>
 *   <li>后端 {@code wd}/{@code tag} 都是 {@code max_length=64}，超长直接 422，
 *       所以所有检索词在这里统一截断，UI 层再给一次可视提示；</li>
 *   <li>tag 索引覆盖不足时后端会返回 {@code tag_found=false} 且 0 条，
 *       客户端自动用同词的全文检索兜一次，避免「明明有片却搜不到」。</li>
 * </ul>
 */
public final class SyncCatalog implements Catalog {

    /** 列表请求组：换筛选条件时整体取消，旧响应不再回调 UI（审计 A-09 / A-11） */
    private static final Object TAG_VIDEOS = new Object();

    /** 后端 aggregate_search 的 wd / tag 均为 max_length=64（M2） */
    public static final int MAX_QUERY_LEN = 64;

    @Override
    public void cancelVideos() {
        VodSyncApi.cancelTag(TAG_VIDEOS);
    }

    @Override
    public void sources(VodSyncApi.ApiCb<Models.SyncSourcesResp> cb) {
        VodSyncApi.get().get(ApiPaths.SOURCES, null, Models.SyncSourcesResp.class, cb);
    }

    @Override
    public void categories(String slug, VodSyncApi.ApiCb<Models.CategoryResp> cb) {
        Map<String, String> p = new HashMap<>();
        if (slug != null && !slug.isEmpty()) p.put("source", slug);
        VodSyncApi.get().get(ApiPaths.CATEGORIES, p, Models.CategoryResp.class, cb);
    }

    @Override
    public void videos(int page, int pageSize, String slug, String typeName, String keyword,
                       VodSyncApi.ApiCb<Models.VideoListResp> cb) {
        final String kw = clamp(keyword);
        final boolean searching = !kw.isEmpty();
        final Map<String, String> params = new HashMap<>();
        params.put("page", String.valueOf(page));
        params.put("page_size", String.valueOf(pageSize));
        if (slug != null && !slug.isEmpty()) params.put("source", slug);
        if (typeName != null && !typeName.isEmpty()) params.put("type_name", typeName);

        // 一次性标签意图：能力就绪时改用 tag 参数，不再把标签当全文关键词（B-01）
        final String tag = clamp(SearchIntent.consumeTag());
        final boolean tagMode = !tag.isEmpty() && ServerCaps.supports(ServerCaps.CAP_TAG_SEARCH);

        final String path;
        if (tagMode) {
            params.put("tag", tag);
            path = ApiPaths.SEARCH;
        } else if (searching) {
            params.put("wd", kw);
            path = ApiPaths.SEARCH;
        } else {
            path = ApiPaths.VIDEOS;
        }
        VodSyncApi.get().get(path, params, Models.VideoListResp.class, TAG_VIDEOS,
                tagMode ? tagFallbackCb(tag, params, cb) : cb);
    }

    /**
     * 检索词截断（M2）：后端 {@code max_length=64}，超长会 422 并在 UI 上糊一段英文。
     * 按 UTF-16 单元截断，但遇到代理对（emoji）往回退一位，绝不切出半个字符——
     * 宁可少一个字，也不能把非法字符发给后端。
     */
    public static String clamp(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() <= MAX_QUERY_LEN) return s;
        int end = MAX_QUERY_LEN;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;   // 别把代理对切一半
        return s.substring(0, end).trim();
    }

    /**
     * tag 模式的自愈层（M5）：标签索引没覆盖该词时（{@code tag_found=false} 或 0 条），
     * 用同一个词再走一次 {@code ?wd=} 全文检索，并给结果打上
     * {@link Models.VideoListResp#fullTextFallback} 让 UI 说明「这次是全文检索的结果」。
     *
     * <p>为什么放在客户端而不是等后端补数据：后端补齐 658 万条标签要时间，期间用户
     * 点标签就是「搜不到」。多一次请求换「一定能搜到」，这笔账划算；回退也只发生在
     * 标签结果为空的场景，正常命中时零额外开销。
     */
    private static VodSyncApi.ApiCb<Models.VideoListResp> tagFallbackCb(
            final String tag, final Map<String, String> base,
            final VodSyncApi.ApiCb<Models.VideoListResp> cb) {
        return new VodSyncApi.ApiCb<Models.VideoListResp>() {
            @Override public void onOk(Models.VideoListResp r) {
                boolean empty = r == null || r.total <= 0;
                if (!empty) { cb.onOk(r); return; }

                Map<String, String> p = new HashMap<>(base);
                p.remove("tag");
                p.put("wd", tag);
                VodSyncApi.get().get(ApiPaths.SEARCH, p, Models.VideoListResp.class, TAG_VIDEOS,
                        new VodSyncApi.ApiCb<Models.VideoListResp>() {
                    @Override public void onOk(Models.VideoListResp r2) {
                        if (r2 != null && r2.total > 0) {
                            r2.fullTextFallback = true;
                            cb.onOk(r2);
                        } else {
                            cb.onOk(r);       // 兜底也没结果：交回原始（空）结果
                        }
                    }
                    @Override public void onErr(String m) { cb.onOk(r); }
                });
            }
            @Override public void onErr(String m) { cb.onErr(m); }
        };
    }

    @Override
    public void detail(String slug, long vodId, VodSyncApi.ApiCb<Models.DetailResp> cb) {
        VodSyncApi.get().get(ApiPaths.video(slug, vodId), null, Models.DetailResp.class, cb);
    }

    /** 相关推荐：VodSync v1.9.0 预计算表直读（P95 < 50ms），带真实相关度分数 */
    @Override
    public void related(Models.VideoDetail base, int limit, VodSyncApi.ApiCb<Models.RelatedResp> cb) {
        if (base == null) { cb.onErr("缺少详情"); return; }
        Map<String, String> p = new HashMap<>();
        p.put("slug", base.source != null ? base.source : "");
        p.put("id", String.valueOf(base.vodId));
        p.put("limit", String.valueOf(limit));
        VodSyncApi.get().get(ApiPaths.RELATED, p, Models.RelatedResp.class, cb);
    }

    /** 明星作品推荐：VodSync v1.9.1 star_map 倒排索引直查 */
    @Override
    public void starRelated(Models.VideoDetail base, int limit, VodSyncApi.ApiCb<Models.StarRelatedResp> cb) {
        if (base == null) { cb.onErr("缺少详情"); return; }
        Map<String, String> p = new HashMap<>();
        p.put("slug", base.source != null ? base.source : "");
        p.put("vod_id", String.valueOf(base.vodId));
        p.put("limit", String.valueOf(limit));
        VodSyncApi.get().get(ApiPaths.STAR_RELATED, p, Models.StarRelatedResp.class, cb);
    }

    /** 按明星查作品（v1.9.2 GET /api/v1/star/works） */
    @Override
    public void starWorks(Models.VideoDetail base, String term, int limit,
                          VodSyncApi.ApiCb<Models.StarWorksResp> cb) {
        if (base == null) { cb.onErr("缺少详情"); return; }
        String t = clamp(term);   // 后端 term 也是 max_length=64，超长直接 400
        if (t.isEmpty()) { cb.onOk(null); return; }
        Map<String, String> p = new HashMap<>();
        p.put("slug", base.source != null ? base.source : "");
        p.put("term", t);
        p.put("limit", String.valueOf(limit));
        p.put("exclude_id", String.valueOf(base.vodId));
        VodSyncApi.get().get(ApiPaths.STAR_WORKS, p, Models.StarWorksResp.class, cb);
    }

    @Override
    public void ping(VodSyncApi.ApiCb<Long> cb) {
        VodSyncApi.get().healthz(cb);
    }

    @Override
    public boolean isStatic() {
        return false;
    }
}
