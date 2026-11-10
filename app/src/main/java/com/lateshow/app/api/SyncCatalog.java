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
 * <p>M2 / M5（2026-11-10 与后端对齐）：
 * <ul>
 *   <li>后端 {@code wd}/{@code tag} 都是 {@code max_length=64}，超长直接 422，
 *       所以所有检索词在这里统一截断，UI 层再给一次可视提示；</li>
 *   <li>标签胶囊点击走 {@code tag=}（索引命中的词必命中），搜索框输入始终走 {@code wd=}；
 *       只有后端明确回 {@code tag_found=false} 时才用同词全文检索兜一次。</li>
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

        // 标签模式是「粘性状态」而不是一次性意图：翻页时仍要带同一个 tag，
        // 否则第 2 页会退回 wd=，出现两页结果口径不一致（M5 对齐后端约定）
        final String tag = clamp(SearchIntent.currentTag());
        final boolean tagMode = !tag.isEmpty() && ServerCaps.supports(ServerCaps.CAP_TAG_SEARCH);

        final String path;
        if (tagMode) {
            params.put("tag", tag);
            // tag 是本地标签索引上的过滤条件，走列表接口即可：
            // 语义与普通浏览一致（同一张表、同一套分页），且完整支持 source / type_name 过滤；
            // 走 /search 只会额外背上一堆聚合字段，对 tag 检索没有意义。
            path = ApiPaths.VIDEOS;
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
     * tag 模式的自愈层（M5，按后端约定实现）：只在后端明确回 {@code tag_found=false}
     * （索引里没有这个词）时，用同一个词再走一次 {@code ?wd=} 全文检索，并给结果打上
     * {@link Models.VideoListResp#fullTextFallback}，让 UI 说明「这次是全文检索的结果」。
     *
     * <p><b>为什么不是「0 条就回退」</b>：{@code tag_found=true} 但结果为 0，是「标签存在、
     * 只是当前筛选（源站/分类）下没有片」——这是真实结论，回退到全文检索会跨源站
     * 塞进一堆无关结果，比空列表更误导。后端给的判据是 {@code tag_found}，就只认它。
     *
     * <p>老后端不返回该字段时，退化为「0 条即回退」，保证前后端版本错配也不会白屏。
     */
    private static VodSyncApi.ApiCb<Models.VideoListResp> tagFallbackCb(
            final String tag, final Map<String, String> base,
            final VodSyncApi.ApiCb<Models.VideoListResp> cb) {
        return new VodSyncApi.ApiCb<Models.VideoListResp>() {
            @Override public void onOk(Models.VideoListResp r) {
                if (!tagMissed(r)) { cb.onOk(r); return; }

                Map<String, String> p = new HashMap<>(base);
                p.remove("tag");
                p.put("wd", tag);
                VodSyncApi.get().get(ApiPaths.SEARCH, p, Models.VideoListResp.class, TAG_VIDEOS,
                        new VodSyncApi.ApiCb<Models.VideoListResp>() {
                    @Override public void onOk(Models.VideoListResp r2) {
                        if (r2 != null && r2.total > 0) {
                            r2.fullTextFallback = true;
                            cb.onOk(r2);
                        } else if (r != null) {
                            r.fullTextFallback = true;   // 全文也没结果：交给空态文案说明
                            cb.onOk(r);
                        } else {
                            cb.onOk(r2);
                        }
                    }
                    @Override public void onErr(String m) {
                        if (r != null) { r.fullTextFallback = true; cb.onOk(r); }
                        else cb.onErr(m);
                    }
                });
            }
            @Override public void onErr(String m) { cb.onErr(m); }
        };
    }

    /**
     * 后端是否明确表示「标签索引里没有这个词」。
     * 有 {@code tag_found} 就只认它；没有（老版本后端）才退回「0 条」判断。
     */
    private static boolean tagMissed(Models.VideoListResp r) {
        if (r == null) return true;
        if (r.tagFound != null) return !r.tagFound;
        return r.total <= 0 && (r.list == null || r.list.isEmpty());
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
