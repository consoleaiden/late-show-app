package com.lateshow.app.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/** VodSync 数据模型（只声明会解析的字段，未知字段由 Gson 忽略） */
public final class Models {
    private Models() {}

    public static class VideoSummary {
        public String source;
        @SerializedName("vod_id") public long vodId;
        @SerializedName("type_id") public int typeId;
        @SerializedName("type_name") public String typeName;
        @SerializedName("vod_name") public String vodName;
        @SerializedName("vod_pic") public String vodPic;
        @SerializedName("vod_remarks") public String vodRemarks;
        @SerializedName("vod_time") public String vodTime;
        @SerializedName("source_name") public String sourceName;
    }

    public static class Episode {
        public String name;
        public String url;
        /** VodSync v1.11.0：线路/集的可播判定。null = 字段缺失（旧服务端/离线缓存），按可播处理 */
        public Boolean playable;
        /** 上游播放来源标识（如 ffzy / bfzy），仅用于展示与排障 */
        public String from;
        /** 线路类型（hls / direct / web_page 等） */
        public String type;

        public boolean isPlayable() {
            return playable == null || playable;
        }
    }

    public static class PlayLine {
        public String name;
        public List<Episode> episodes;
        /**
         * VodSync v1.11.0：该线路在上游原始串里的下标（0 基）。
         * 服务端会剔除分享页线路并按直链优先重排，数组下标会变，只有这个值跨策略/版本稳定；
         * 用它做「用户上次选的是哪条线路」的持久化键。旧服务端 / 直连模式为 null。
         */
        @SerializedName("original_index") public Integer originalIndex;
        /** VodSync v1.11.0：线路是否可播。null = 字段缺失（按可播处理，保证向后兼容） */
        public Boolean playable;
        public String from;
        public String type;

        /** 线路的稳定标识：优先服务端 original_index，缺失时退回当前列表位置（兼容旧数据） */
        public int stableKey(int position) {
            return originalIndex != null && originalIndex >= 0 ? originalIndex : position;
        }

        /**
         * 线路可播（审计 A-29）：只有服务端明确下发 {@code playable=false} 才判不可用；
         * 字段缺失、旧服务端、离线缓存一律按可播处理，避免把正常线路误置灰。
         * 「线路没有任何选集」由渲染层按「暂无选集」处理，不在这里判死刑。
         */
        public boolean isPlayable() {
            return playable == null || playable;
        }
    }

    public static class VideoDetail extends VideoSummary {
        @SerializedName("vod_content") public String vodContent;
        @SerializedName("vod_play_url") public String vodPlayUrl;
        @SerializedName("vod_play_from") public String vodPlayFrom;
        @SerializedName("vod_play_list") public List<PlayLine> vodPlayList;
        /** 演员（逗号分隔；直连模式取自源站原始字段，NAS 模式待服务端详情接口透出，缺失时用 star_map 明星集兜底） */
        @SerializedName("vod_actor") public String vodActor;
        /** 标签（逗号分隔，题材/地区等） */
        @SerializedName("vod_tag") public String vodTag;
        @SerializedName("vod_area") public String vodArea;
        @SerializedName("vod_year") public String vodYear;
        @SerializedName("vod_director") public String vodDirector;
        /** 相关推荐（VodSync v1.9.0 预计算结果，详情接口内嵌；直连模式为空，需自行拉取） */
        @SerializedName("related") public List<RelatedCard> related;
        /** true = 该批推荐来自服务端兜底链（同分类/同地区年代/最新收录），无真实相关度 */
        @SerializedName("related_fallback") public boolean relatedFallback;
    }

    /**
     * 相关推荐卡片。结构与视频卡片对齐，额外带 source/source_name（支持跨站跳转）与 score。
     * score ∈ [0,1]；mode = match | fallback；score<=0 表示无相关度。
     * VodSync v1.10.0 起算法口径变更（整体下调、不再是相似度百分比），
     * 客户端只用它理解排序语义，不展示具体数值或百分比。
     */
    public static class RelatedCard {
        @SerializedName("vod_id") public long vodId;
        @SerializedName("vod_name") public String vodName;
        @SerializedName("vod_pic") public String vodPic;
        @SerializedName("vod_remarks") public String vodRemarks;
        @SerializedName("type_name") public String typeName;
        public String source;
        @SerializedName("source_name") public String sourceName;
        public double score;
        public String mode;
    }

    /** GET /api/v1/related 的响应 */
    public static class RelatedResp {
        public int code;
        @SerializedName("vod_id") public long vodId;
        public boolean fallback;
        public List<RelatedCard> list;
    }

    /** GET /api/v1/star/related 响应（VodSync v1.9.1 明星作品推荐，走 star_map 索引毫秒级） */
    public static class StarRelatedResp {
        public int code;
        public StarTarget target;
        /** 目标视频的明星集（真实演员∩词库 + 标题推断），Tab 数据源 */
        public List<String> stars;
        /** v1.9.2 新增：{明星名: 作品总数}，Tab 排序/角标用；v1.9.1 旧服务端缺失（null），客户端自动降级 */
        @SerializedName("star_counts") public Map<String, Integer> starCounts;
        /** v1.9.2 新增：候选总数超过 limit 被截断；旧服务端缺失时恒 false */
        public boolean truncated;
        /** 与目标共享明星的其他影片，overlap 降序 */
        public List<StarCard> list;
    }

    public static class StarTarget {
        @SerializedName("vod_id") public long vodId;
        @SerializedName("vod_name") public String vodName;
    }

    /** 明星作品卡片：结构与视频卡片对齐 + match_stars（命中的明星，客户端据此做 Tab 分组） */
    public static class StarCard {
        @SerializedName("vod_id") public long vodId;
        @SerializedName("vod_name") public String vodName;
        @SerializedName("vod_pic") public String vodPic;
        @SerializedName("vod_remarks") public String vodRemarks;
        @SerializedName("type_name") public String typeName;
        public String source;
        public List<String> match_stars;
        /** 与目标重叠的明星数（>=1） */
        public int overlap;
    }

    /** GET /api/v1/star/works 响应（VodSync v1.9.2 按明星查作品，以明星为中心不受全局截断影响） */
    public static class StarWorksResp {
        public int code;
        public String slug;
        /** 服务端归一化后的明星主名（别名请求会归一） */
        public String term;
        /** 该明星现存作品总数，不受 limit 影响（也不含 exclude_id 排除项） */
        public int total;
        /** 按 vod_time desc 排序的作品卡片，字段与 StarCard 一致 */
        public List<StarCard> list;
    }

    public static class VideoListResp {
        public int code;
        public int page;
        public int pagecount;
        /** v1.10.0 起全站列表的 total 是估值；true 时 UI 文案须降级为「约 N 条」 */
        @SerializedName("total_approximate") public boolean totalApproximate;
        /** 后端建议展示的查询耗时（毫秒），仅用于诊断文案 */
        @SerializedName("query_ms") public Double queryMs;
        public int total;
        public List<VideoSummary> list;

        // ---- 以下为 v1.14 起 /api/v1/search 的检索质量字段（F4）----
        /** 实际使用的每页条数（后端回填生效值） */
        @SerializedName("page_size") public Integer pageSize;
        /** 检索关键词（后端归一化后回显） */
        public String wd;
        /** true = 结果不完整：有源站超时/截断，total 只是已搜到的部分 */
        @SerializedName("total_is_partial") public boolean totalIsPartial;
        /** 被截断（达到单源上限）的源站 slug */
        @SerializedName("truncated_sources") public List<String> truncatedSources;
        /** 检索失败/超时被跳过的源站 slug */
        @SerializedName("sources_failed") public List<String> sourcesFailed;
        /** 本次真正参与检索的源站数 */
        @SerializedName("sources_searched") public Integer sourcesSearched;
        @SerializedName("elapsed_ms") public Double elapsedMs;
        /**
         * true = 本次请求原本走 {@code ?tag=} 精确检索，但标签索引未覆盖该词
         * （{@code tag_found=false} 且结果为 0），客户端已自动改用 {@code ?wd=} 全文检索。
         *
         * <p>后端不返回此字段，是客户端 {@code SyncCatalog} 回退后打的标记，
         * 声明为 {@code transient} 保证不会被 Gson 反序列化覆盖。
         */
        public transient boolean fullTextFallback;
        /** 标签检索是否命中索引（后端仅 {@code ?tag=} 请求返回；null = 未用 tag 模式） */
        @SerializedName("tag_found") public Boolean tagFound;

        /** 检索结果是否「不完整」（有源站失败或被截断），UI 据此提示 */
        public boolean partial() {
            if (totalIsPartial) return true;
            return sourcesFailed != null && !sourcesFailed.isEmpty();
        }

        /** 失败源站数（无数据时 0） */
        public int failedSourceCount() {
            return sourcesFailed == null ? 0 : sourcesFailed.size();
        }
    }

    public static class DetailResp {
        public int code;
        public VideoDetail detail;
    }

    public static class Category {
        @SerializedName("type_id") public int typeId;
        @SerializedName("type_name") public String typeName;
        public int count;
    }

    public static class CategoryResp {
        public int code;
        public int total;
        public List<Category> list;
    }

    /** GET /source 的源站条目 */
    public static class Source {
        public String name;
        public String slug;
        public String status;
        @SerializedName("video_count") public int videoCount;
        @SerializedName("last_sync_at") public String lastSyncAt;
        public boolean synced;
    }

    public static class SourceListResp {
        public int code;
        public int total;
        public int alive;
        public int ready;
        public List<Source> list;
    }

    /** GET /api/v1/sources 的源站条目（含同步状态） */
    public static class SyncSource {
        public String slug;
        public String name;
        public String status;
        @SerializedName("api_url") public String apiUrl;
        public boolean enabled;
        @SerializedName("total_videos") public int totalVideos;
        public boolean running;
        @SerializedName("running_mode") public String runningMode;
        @SerializedName("running_seconds") public Double runningSeconds; // 后端可能返回小数（如 1938.9），用 Double 容错
        public double progress;
        @SerializedName("last_page") public Double lastPage;
        @SerializedName("total_pages") public Double totalPages;
        @SerializedName("last_sync_at") public String lastSyncAt;
        public boolean paused;
        @SerializedName("auto_sync") public boolean autoSync;
        /** v1.4.1：已提交但被并发闸门排队（尚未真正开跑） */
        public boolean queued;
        /** v1.4.0：是否同步过（水位线/入库/断点任一成立）；false 显示「从未同步」 */
        public boolean synced;
        /** v1.8.0：来源（latest=官方目录 / custom=面板手动登记的自定义源站） */
        public String origin;
        public String homepage;
        public String description;
        /** v1.11+ 源站线路策略与失败计数（审计 C-08）：控制台可据此展示策略效果 */
        @SerializedName("fail_streak") public Integer failStreak;
        @SerializedName("skip_until") public String skipUntil;
        /**
         * v1.14+：线路统计。后端为对象 {"lines":..,"dropped":..,"fallback":..}；
         * 用 JsonElement 容错（兼容历史版本返回字符串或 null 的形态），解析失败不再拖垮整个列表。
         */
        @SerializedName("play_line") public com.google.gson.JsonElement playLine;
        @SerializedName("play_line_effective") public String playLineEffective;
        @SerializedName("play_line_policy") public String playLinePolicy;
        public boolean pick; // UI 勾选态

        /** 线路总数（play_line.lines，取不到返回 0） */
        public int playLineLines() {
            return playLineInt("lines");
        }

        /** 被丢弃线路数（play_line.dropped） */
        public int playLineDropped() {
            return playLineInt("dropped");
        }

        /** 回退线路数（play_line.fallback） */
        public int playLineFallback() {
            return playLineInt("fallback");
        }

        private int playLineInt(String key) {
            try {
                if (playLine != null && playLine.isJsonObject()
                        && playLine.getAsJsonObject().has(key)) {
                    return playLine.getAsJsonObject().get(key).getAsInt();
                }
            } catch (Exception ignored) { }
            return 0;
        }
    }

    public static class SyncSourcesResp {
        public int code;
        public List<SyncSource> list;
    }

    public static class PauseState {
        public boolean global;
        public boolean full;
        public boolean incremental;
    }

    /** GET /api/v1/pause 响应（flat 结构） */
    public static class PauseResp {
        public int code;
        public boolean global;
        public boolean full;
        public boolean incremental;
        public PauseState state;
    }

    public static class StatsResp {
        public int code;
        @SerializedName("sources_total") public int sourcesTotal;
        @SerializedName("sources_enabled") public int sourcesEnabled;
        @SerializedName("sources_syncing") public int sourcesSyncing;
        @SerializedName("sources_unfinished") public int sourcesUnfinished; // v1.4.0：有断点的站数
        @SerializedName("videos_total") public int videosTotal;
        @SerializedName("today_inserted") public int todayInserted;
        @SerializedName("today_updated") public int todayUpdated;
        public PauseState pause;
        /** v1.4.0：中转代理运行态 */
        public ProxyInfo proxy;
        /** v1.4.1：数据库连接池 */
        public DbPool db_pool;
        /** v1.4.1：同步负载（正在跑 / 排队 / 并发上限） */
        public SyncLoad sync_load;
    }

    /** GET /api/v1/stats 的 proxy 段（v1.4.0 中转代理降级通道） */
    public static class ProxyInfo {
        public boolean enabled;
        public java.util.List<String> urls;
        @SerializedName("direct_success") public int directSuccess;
        @SerializedName("proxy_success") public int proxySuccess;
        @SerializedName("route_fail") public int routeFail;
        /** key=源站域名，value=当前偏好的代理地址 + 记忆时长 */
        public java.util.Map<String, PreferredProxy> preferred_hosts;
    }

    public static class PreferredProxy {
        public String proxy;
        @SerializedName("age_seconds") public double ageSeconds;
    }

    /** GET /api/v1/stats 的 db_pool 段（v1.4.1 连接池排障） */
    public static class DbPool {
        public int size;
        @SerializedName("checked_out") public int checkedOut;
        public int overflow;
        @SerializedName("max_overflow") public int maxOverflow;
        @SerializedName("pool_timeout") public int poolTimeout;
    }

    /** GET /api/v1/stats 的 sync_load 段（v1.4.1 并发闸门） */
    public static class SyncLoad {
        public int running;
        public int queued;
        public int limit;
    }

    public static class SyncLog {
        public int id;
        public String slug;
        public String mode;
        public String status;
        public int fetched;
        public int inserted;
        public int updated;
        public int pages;
        public String error;
        @SerializedName("started_at") public String startedAt;
        @SerializedName("finished_at") public String finishedAt;
    }

    public static class LogsResp {
        public int code;
        public List<SyncLog> list;
    }

    public static class MsgResp {
        public int code;
        public String msg;
    }

    /** POST /api/v1/sources/probe 响应（v1.8.0 连通性测试，永远 200，ok 字段表示结果） */
    public static class ProbeResp {
        public int code;
        public boolean ok;
        public String error;
        public int total;
        @SerializedName("elapsed_ms") public int elapsedMs;
    }

    /** POST /api/v1/sources/custom 响应（v1.8.0 添加自定义源站） */
    public static class CustomSourceResp {
        public int code;
        public String slug;
        public String msg;
        @SerializedName("sync_started") public boolean syncStarted;
        public ProbeResp probe;
    }

    /** GET /api/v1/auth/state（免鉴权）：服务端访问控制状态 */
    public static class AuthState {
        public boolean enabled;
        @SerializedName("logged_in") public boolean loggedIn;
        @SerializedName("has_token") public boolean hasToken;
        @SerializedName("token_hint") public String tokenHint;
        @SerializedName("password_changed_at") public String passwordChangedAt;
        @SerializedName("token_created_at") public String tokenCreatedAt;
    }

    // ==================== v1.14 新增：具名 Token 与标签索引 ====================

    /** GET /api/v1/auth/tokens 的单个 Token（**永不返回明文**） */
    public static class AuthToken {
        /** 0 = 存量单 Token（只能通过「重置」轮换，不能单独吊销） */
        public int id;
        /** legacy / scoped */
        public String kind;
        /** read / admin */
        public String scope;
        public String note;
        @SerializedName("created_at") public String createdAt;
        @SerializedName("last_used_at") public String lastUsedAt;
        public boolean present;

        public boolean legacy() { return "legacy".equals(kind) || id == 0; }
    }

    public static class AuthTokenList {
        public int code;
        public List<AuthToken> list;
    }

    /** POST /api/v1/auth/tokens 响应：{@code token} 是**一次性明文**，只在本次响应里出现 */
    public static class TokenCreateResp {
        public int code;
        public int id;
        public String token;
        public String scope;
        public String note;
        @SerializedName("created_at") public String createdAt;
    }

    /**
     * GET /api/v1/tag/status：标签倒排索引就绪度。
     * 后端用它决定 {@code /healthz} 是否声明 {@code tag_search}。
     */
    public static class TagStatusResp {
        public int code;
        /** 索引可用（可以对外声明 tag_search） */
        public boolean ready;
        /** 后端是否启用标签索引功能 */
        public boolean enabled;
        /** 同步入库时是否增量维护索引 */
        @SerializedName("on_sync") public boolean onSync;
        /** 正在回填的源站（元素形态随版本变化，用 JsonElement 兜底） */
        public List<com.google.gson.JsonElement> building;
        /** 已建索引行数 */
        public long rows;
        /** 逐站进度 */
        public List<TagSourceProgress> sources;

        public int buildingCount() {
            return building == null ? 0 : building.size();
        }
    }

    /** 单个源站的标签索引进度 */
    public static class TagSourceProgress {
        public String slug;
        /** pending / running / done / error */
        public String status;
        public long cursor;
        public long processed;
        public long total;
        public long indexed;
        public double progress;
        public String error;
        @SerializedName("finished_at") public String finishedAt;
    }

    /** POST /api/v1/maintenance/tag-index 响应 */
    public static class TagIndexRebuildResp {
        public int code;
        /** true = 本次受理；false = 已有同类任务在跑，未重复提交 */
        public boolean accepted;
        public boolean force;
        /** "all" 或具体 slug */
        public String targets;
        public String msg;
    }
}
