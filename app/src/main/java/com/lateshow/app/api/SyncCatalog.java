package com.lateshow.app.api;

import com.lateshow.app.model.Models;

import java.util.HashMap;
import java.util.Map;

/** NAS 同步库数据源：原 VodSync REST 接口，行为与改造前完全一致 */
public final class SyncCatalog implements Catalog {

    @Override
    public void sources(VodSyncApi.ApiCb<Models.SyncSourcesResp> cb) {
        VodSyncApi.get().get("/api/v1/sources", null, Models.SyncSourcesResp.class, cb);
    }

    @Override
    public void categories(String slug, VodSyncApi.ApiCb<Models.CategoryResp> cb) {
        Map<String, String> p = new HashMap<>();
        if (slug != null && !slug.isEmpty()) p.put("source", slug);
        VodSyncApi.get().get("/api/v1/categories", p, Models.CategoryResp.class, cb);
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
        if (searching) {
            params.put("wd", keyword);
            path = "/api/v1/search";
        } else {
            path = "/api/v1/videos";
        }
        VodSyncApi.get().get(path, params, Models.VideoListResp.class, cb);
    }

    @Override
    public void detail(String slug, long vodId, VodSyncApi.ApiCb<Models.DetailResp> cb) {
        VodSyncApi.get().get("/api/v1/videos/" + slug + "/" + vodId, null, Models.DetailResp.class, cb);
    }

    /** 相关推荐：VodSync v1.9.0 预计算表直读（P95 < 50ms），带真实相关度分数 */
    @Override
    public void related(Models.VideoDetail base, int limit, VodSyncApi.ApiCb<Models.RelatedResp> cb) {
        if (base == null) { cb.onErr("缺少详情"); return; }
        Map<String, String> p = new HashMap<>();
        p.put("slug", base.source != null ? base.source : "");
        p.put("id", String.valueOf(base.vodId));
        p.put("limit", String.valueOf(limit));
        VodSyncApi.get().get("/api/v1/related", p, Models.RelatedResp.class, cb);
    }

    /** 明星作品推荐：VodSync v1.9.1 star_map 倒排索引直查（毫秒级），与当前影片共享明星的其他影片 */
    @Override
    public void starRelated(Models.VideoDetail base, int limit, VodSyncApi.ApiCb<Models.StarRelatedResp> cb) {
        if (base == null) { cb.onErr("缺少详情"); return; }
        Map<String, String> p = new HashMap<>();
        p.put("slug", base.source != null ? base.source : "");
        p.put("vod_id", String.valueOf(base.vodId));
        p.put("limit", String.valueOf(limit));
        VodSyncApi.get().get("/api/v1/star/related", p, Models.StarRelatedResp.class, cb);
    }

    /** 按明星查作品：VodSync v1.9.2 GET /api/v1/star/works（走 (slug,term) 索引，P95 < 50ms） */
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
        VodSyncApi.get().get("/api/v1/star/works", p, Models.StarWorksResp.class, cb);
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
