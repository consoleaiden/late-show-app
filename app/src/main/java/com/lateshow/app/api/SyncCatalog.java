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

    @Override
    public void ping(VodSyncApi.ApiCb<Long> cb) {
        VodSyncApi.get().healthz(cb);
    }

    @Override
    public boolean isStatic() {
        return false;
    }
}
