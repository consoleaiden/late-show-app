package com.lateshow.app.api;

import com.lateshow.app.model.Models;

/**
 * 数据源统一契约。
 *
 * 两种实现：
 *  - {@link SyncCatalog}：自家 NAS 上的 VodSync 聚合库（原行为，需在线且可通过 Token 鉴权）
 *  - {@link StaticCatalog}：静态目录 + 第三方苹果CMS 接口直连（NAS 关机时的兜底）
 *
 * 上层（首页 / 详情页）只依赖本接口，切换模式不需要改任何 UI 逻辑。
 */
public interface Catalog {

    /** NAS 同步库 */
    int MODE_SYNC = 0;
    /** 资源站直连（静态目录） */
    int MODE_STATIC = 1;

    /** 源站列表（含名称、条数、可用状态），slug 为源站唯一标识 */
    void sources(VodSyncApi.ApiCb<Models.SyncSourcesResp> cb);

    /** 某源站的分类列表 */
    void categories(String slug, VodSyncApi.ApiCb<Models.CategoryResp> cb);

    /** 分页影片列表；typeName 为空表示全部，keyword 为空表示不搜索 */
    void videos(int page, int pageSize, String slug, String typeName, String keyword,
                VodSyncApi.ApiCb<Models.VideoListResp> cb);

    /** 影片详情（含线路与选集） */
    void detail(String slug, long vodId, VodSyncApi.ApiCb<Models.DetailResp> cb);

    /** 可用性探测：成功返回耗时 ms */
    void ping(VodSyncApi.ApiCb<Long> cb);

    boolean isStatic();
}
