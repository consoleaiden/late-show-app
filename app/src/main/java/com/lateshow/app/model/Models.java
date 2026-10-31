package com.lateshow.app.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

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
    }

    public static class VideoDetail extends VideoSummary {
        @SerializedName("vod_content") public String vodContent;
        @SerializedName("vod_play_url") public String vodPlayUrl;
        @SerializedName("vod_play_from") public String vodPlayFrom;
        @SerializedName("vod_play_list") public List<PlayLine> vodPlayList;
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
        @SerializedName("videos_total") public int videosTotal;
        @SerializedName("today_inserted") public int todayInserted;
        @SerializedName("today_updated") public int todayUpdated;
        public PauseState pause;
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
}
