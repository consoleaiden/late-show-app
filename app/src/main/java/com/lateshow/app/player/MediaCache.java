package com.lateshow.app.player;

import android.content.Context;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/**
 * 主/副播放器共享的磁盘缓存（单例）。
 *
 * <p>背景：源站 seek 后出帧实测要 7~28 秒（2026-11-17），因为每次 seek 都要
 * 向源站重新拉分片。主播放器播放时分片本来就全部经过本地，用 {@link SimpleCache}
 * 把它们落盘后，副播放器抓「已看区间」的预览帧直接命中本地缓存，出帧从十几秒
 * 降到 1 秒内，且<b>零额外流量</b>（分片是主播放器本来就拉过的）。副播放器抓
 * 未看区间时拉到的分片同样写入缓存——第二次拖到同一区间就是秒出。
 *
 * <p>必须主/副播放器共用<b>同一个</b> {@link SimpleCache} 实例：同一目录重复
 * 创建实例会被目录锁拒绝。
 */
public final class MediaCache {

    /** 缓存上限 256MB，LRU 淘汰；放应用内部缓存目录，系统空间不足时可被整体回收 */
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private static volatile SimpleCache instance;

    private MediaCache() { }

    public static SimpleCache get(Context ctx) {
        if (instance == null) {
            synchronized (MediaCache.class) {
                if (instance == null) {
                    File dir = new File(ctx.getApplicationContext().getCacheDir(), "media_cache");
                    instance = new SimpleCache(dir,
                            new LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                            new StandaloneDatabaseProvider(ctx.getApplicationContext()));
                }
            }
        }
        return instance;
    }

    /**
     * 把既有 DataSource 工厂（含广告过滤）包上磁盘缓存。
     * 命中缓存时零网络请求；出错时回源（FLAG_IGNORE_CACHE_ON_ERROR）。
     */
    public static CacheDataSource.Factory cacheFactory(Context ctx, DataSource.Factory upstream) {
        return new CacheDataSource.Factory()
                .setCache(get(ctx))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
}
