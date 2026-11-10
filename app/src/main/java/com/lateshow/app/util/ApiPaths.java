package com.lateshow.app.util;

/**
 * 后端 API 路径常量（审计 A-32 / D-08）。
 *
 * <p>此前 35 处 {@code "/api/v1/..."} 字面量散落在 SyncCatalog 与控制台里，
 * 后端调整一个路径要全文 grep。集中到此处后，契约变更只需改一处。
 * 动态段用方法拼装，避免字符串拼接里漏掉斜杠。
 */
public final class ApiPaths {

    private static final String V1 = "/api/v1";

    private ApiPaths() { }

    public static final String HEALTHZ = "/healthz";
    public static final String AUTH_STATE = V1 + "/auth/state";
    public static final String AUTH_TOKEN = V1 + "/auth/token";
    /** v1.14.0 起：具名 Token 清单 / 新增（不动现有 Token，可为 TV / 车机单独发只读 Token） */
    public static final String AUTH_TOKENS = V1 + "/auth/tokens";
    public static final String AUTH_SETTINGS = V1 + "/auth/settings";
    public static final String AUTH_PASSWORD = V1 + "/auth/password";

    public static final String SOURCES = V1 + "/sources";
    public static final String SOURCES_REFRESH = V1 + "/sources/refresh";
    public static final String SOURCES_PROBE = V1 + "/sources/probe";
    public static final String SOURCES_CUSTOM = V1 + "/sources/custom";
    public static final String SOURCES_AUTO_SYNC = V1 + "/sources/auto-sync";
    public static final String CATEGORIES = V1 + "/categories";
    public static final String VIDEOS = V1 + "/videos";
    public static final String SEARCH = V1 + "/search";
    public static final String RELATED = V1 + "/related";
    public static final String STAR_RELATED = V1 + "/star/related";
    public static final String STAR_WORKS = V1 + "/star/works";
    public static final String STAR_TERMS = V1 + "/star-terms";

    public static final String PAUSE = V1 + "/pause";
    public static final String RESUME = V1 + "/resume";
    public static final String STATS = V1 + "/stats";
    public static final String LOGS = V1 + "/logs";
    public static final String SYNC_ALL = V1 + "/sync-all";
    /** 控制台按钮走 post(pathAndQuery)，query 会在 post() 里被拆成参数 */
    public static final String SYNC_ALL_AUTO = SYNC_ALL + "?mode=auto";
    public static final String SYNC_ALL_FULL = SYNC_ALL + "?mode=full";
    public static final String ADMIN_TABLES = V1 + "/admin/tables";
    public static final String RELATED_STATUS = V1 + "/related/status";
    public static final String STAR_TAGS_STATUS = V1 + "/star/tags/status";
    public static final String STAR_TAGS_UNTRACKED = V1 + "/star/tags/untracked";
    public static final String STAR_TERMS_RECOMPUTE = STAR_TERMS + "/recompute";
    public static final String MAINTENANCE_RECOUNT = V1 + "/maintenance/recount";
    public static final String MAINTENANCE_RELATED = V1 + "/maintenance/related-recompute";
    public static final String MAINTENANCE_STAR_TAGS = V1 + "/maintenance/star-tags";
    /** v1.14.0：标签倒排索引运维（status 读就绪度，tag-index 触发回填） */
    public static final String TAG_STATUS = V1 + "/tag/status";
    public static final String MAINTENANCE_TAG_INDEX = V1 + "/maintenance/tag-index";

    public static String video(String slug, long vodId) {
        return VIDEOS + "/" + slug + "/" + vodId;
    }

    public static String sync(String slug) {
        return V1 + "/sync/" + slug;
    }

    public static String source(String slug) {
        return SOURCES + "/" + slug;
    }

    public static String sourceAutoSync(String slug) {
        return SOURCES + "/" + slug + "/auto-sync";
    }

    public static String sourcePlayLinePolicy(String slug) {
        return SOURCES + "/" + slug + "/play-line-policy";
    }

    public static String sourceCustom(String slug) {
        return SOURCES + "/" + slug + "/custom";
    }

    public static String starTerm(long id) {
        return STAR_TERMS + "/" + id;
    }

    /** 吊销具名 Token（id=0 是存量单 Token，后端不允许单独吊销） */
    public static String authToken(long id) {
        return AUTH_TOKENS + "/" + id;
    }

    public static String tableRows(String table) {
        return ADMIN_TABLES + "/" + table + "/rows";
    }

    public static String sourcePause(String slug) {
        return source(slug) + "/pause";
    }

    public static String sourceResume(String slug) {
        return source(slug) + "/resume";
    }

    /**
     * 免鉴权路径：这些请求不注入 Token，避免后端收紧 auth 接口校验后形成引导死锁（C-07）。
     *
     * <p>只放行真正的「引导前」端点，与后端 {@code auth.PUBLIC_PATHS} 对齐：
     * {@code /healthz} 与 {@code /api/v1/auth/state}。
     *
     * <p>此前这里用 {@code startsWith("/api/v1/auth/")} 把整个 auth 分组都当公开路径，
     * 后果是 {@code /auth/settings}、{@code /auth/token}、{@code /auth/password}、
     * {@code /auth/tokens} 都不带 Token —— 一旦后端开启授权访问，控制台这些操作会
     * 直接 401（而后端 {@code requires_admin} 对 {@code /api/v1/auth/*} 明确要求 admin）。
     */
    public static boolean isPublic(String path) {
        if (path == null) return false;
        return path.equals(HEALTHZ) || path.equals(AUTH_STATE);
    }
}
