package com.lateshow.app.api;

import com.lateshow.app.model.Models;
import com.lateshow.app.util.ApiPaths;

import java.util.HashMap;
import java.util.Map;

/**
 * NAS 同步库数据源：VodSync REST 接口。
 *
 * 审计 A-30 / B-01：标签检索按后端能力门控。后端 capabilities 含 {@code tag_search}
 * （或版本 ≥ 1.14.0）时，标签点击走精确的 {@code tag} 参数；否则退化为原有的
 * {@code wd} 全文检索。这样后端上线前后同一份客户端都能正常工作。
 */
public final class SyncCatalog implements Catalog {

    /** 列表请求组：换筛选条件时整体取消，旧响应不再回调 UI（审计 A-09 / A-11） */
    private static final Object TAG_VIDEOS = new Object();

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
        boolean searching = keyword != null && !keyword.isEmpty();
        Map<String, String> params = new HashMap<>();
        params.put("page", String.valueOf(page));
        params.put("page_size", String.valueOf(pageSize));
        if (slug != null && !slug.isEmpty()) params.put("source", slug);
        if (typeName != null && !typeName.isEmpty()) params.put("type_name", typeName);

        String path;
        // 一次性标签意图：能力就绪时改用 tag 参数，不再把标签当全文关键词（B-01）
        String tag = SearchIntent.consumeTag();
        boolean tagMode = tag != null && ServerCaps.supports(ServerCaps.CAP_TAG_SEARCH);
        if (tagMode) {
            params.put("tag", tag);
            path = ApiPaths.SEARCH;
        } else if (searching) {
            params.put("wd", keyword);
            path = ApiPaths.SEARCH;
        } else {
            path = ApiPaths.VIDEOS;
        }
        VodSyncApi.get().get(path, params, Models.VideoListResp.class, TAG_VIDEOS, cb);
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
        if (term == null || term.trim().isEmpty()) { cb.onOk(null); return; }
        Map<String, String> p = new HashMap<>();
        p.put("slug", base.source != null ? base.source : "");
        p.put("term", term.trim());
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
