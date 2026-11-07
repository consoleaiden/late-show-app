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

        /** 线路的稳定标识：优先服务端 original_index，缺失时退回当前列表位置（兼容旧数据） */
        public int stableKey(int position) {
            return originalIndex != null && originalIndex >= 0 ? originalIndex : position;
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
        public int total;
        public List<VideoSummary> list;
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
        public boolean pick; // UI 勾选态
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
}
