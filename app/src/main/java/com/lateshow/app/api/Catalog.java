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

    /**
     * 作废仍在途的列表请求（审计 A-09）：首页换关键词 / 换源站 / 换分类时调用，
     * 避免旧响应把新筛选结果覆盖掉。实现可真正 cancel Call，也可用代次丢弃回调。
     * 默认空实现，保证旧实现无需改动。
     */
    default void cancelVideos() { }

    /** 影片详情（含线路与选集） */
    void detail(String slug, long vodId, VodSyncApi.ApiCb<Models.DetailResp> cb);

    /** 可用性探测：成功返回耗时 ms */
    void ping(VodSyncApi.ApiCb<Long> cb);

    /**
     * 探测「当前选中源站」：直连模式应探测该源站的 json 接口（首页圆环状态依据）。
     * slug 为空表示「全部源站」，实现可自选兜底目标；NAS 模式无逐源概念，默认退化为 {@link #ping}。
     */
    default void pingCurrent(String slug, VodSyncApi.ApiCb<Long> cb) {
        ping(cb);
    }

    /**
     * 相关推荐。
     *
     * <p>NAS 模式走 VodSync v1.9.0 的预计算接口（带真实相关度分数）；直连模式没有服务端计算，
     * 实现方可用「同分类热门」兜底（score=0，客户端不展示百分比）。默认不支持。
     *
     * @param base  当前详情（提供 slug / vodId / 分类）
     * @param limit 期望条数
     */
    default void related(Models.VideoDetail base, int limit,
                         VodSyncApi.ApiCb<Models.RelatedResp> cb) {
        cb.onErr("当前数据源不支持相关推荐");
    }

    /**
     * 相同演员作品（VodSync v1.9.1 GET /api/v1/star/related）：与当前影片共享明星的其他影片。
     * 响应 stars 为明星集（Tab 数据源），list 卡片带 match_stars（客户端本地分组切换 Tab）。
     * 直连模式不支持，默认 onErr。
     */
    default void starRelated(Models.VideoDetail base, int limit,
                             VodSyncApi.ApiCb<Models.StarRelatedResp> cb) {
        cb.onErr("当前数据源不支持明星作品推荐");
    }

    /**
     * 按明星查作品（VodSync v1.9.2 GET /api/v1/star/works）：以明星为中心的完整作品列表，
     * 不受 star/related 的全局截断影响。total 为该明星现存作品总数（不含 exclude_id）。
     * 服务端 v1.9.1 未部署该接口时返回 404 → 调用方应降级为本地过滤。
     */
    default void starWorks(Models.VideoDetail base, String term, int limit,
                           VodSyncApi.ApiCb<Models.StarWorksResp> cb) {
        cb.onErr("当前数据源不支持按明星查询");
    }

    boolean isStatic();
}
