package com.lateshow.app.util;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.lateshow.app.store.PrefStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 广告过滤 DataSource：包装既有 HttpDataSource，对 m3u8 响应体做原位重写。
 *
 * <p>关键设计——<b>不是本地代理</b>：复用播放器本来就发起的那次播放列表请求，
 * 只在响应交还播放器前把广告 run 剔除（{@link HlsAdFilter}）。分片 ts 请求原样
 * 透传、仍直连 CDN——零额外网络跳数、零带宽成本、播放链路不新增任何依赖。
 *
 * <p>过滤后的文本仍以原 URL 为基准交还（不触碰分片 URI），相对路径解析不受影响；
 * 解析失败 / 未启用 / 非播放列表一律透传（fail-open）。
 *
 * <p><b>DataSource 契约（v1.5.160 修复的严重缺陷）</b>：media3 的
 * {@code StatsDataSource.open()} 在本类 {@code open()} 返回后会立刻调用
 * {@code Assertions.checkNotNull(getUri())}，而 {@code DefaultHttpDataSource.getUri()}
 * 的实现是「connection == null ? null : ...」。因此在 {@code open()} 内提前
 * {@code up.close()} 会让 connection 置空、getUri() 返回 null，触发
 * {@code NullPointerException}（R8 会把 checkNotNull 优化成 reference.getClass()，
 * 所以堆栈/文案都指向 Object.getClass()），表现为「播放失败：解码失败
 * (ERROR_CODE_IO_UNSPECIFIED)」，且与广告过滤开关无关（包装器始终在链路上）。
 * 修复方式：open() 期间绝不关闭上游；URI 在 open() 内缓存；上游由 {@link #close()}
 * 或读尽后关闭。
 */
public final class AdFilterDataSource implements DataSource {

    /**
     * 播放列表缓冲上限。HLS 播放列表通常几十 KB；超限说明不是播放列表（或异常巨大），
     * 此时放弃过滤、把已读前缀交还后继续直读上游——绝不能截断，截断会得到坏列表。
     */
    private static final int MAX_PLAYLIST_BYTES = 16 * 1024 * 1024;

    private final DataSource up;

    /** 播放列表已缓冲的响应体（过滤后或原样）。 */
    private byte[] body;
    /** 超限降级模式下已读出的前缀。 */
    private byte[] prefix;
    private long pos;
    /** true = 走缓冲体（body 或 prefix），false = 纯透传上游。 */
    private boolean buffered;
    /** true = 超限，prefix 读完后继续直读上游。 */
    private boolean overflow;
    private boolean upstreamClosed;
    /** open() 内缓存的最终 URI（跟随重定向后）；close() 前始终可返回。 */
    @Nullable private Uri cachedUri;

    /** 包装既有 Factory；接入点：new DefaultMediaSourceFactory(new Factory(httpFactory)) */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory base;

        public Factory(DataSource.Factory base) {
            this.base = base;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            // 开关关闭时零开销直通：不包装、不改任何行为，把风险面降到 0。
            // 每次请求都会调用本方法，因此运行中开/关开关即时生效。
            if (!PrefStore.get().adFilter()) {
                return base.createDataSource();
            }
            return new AdFilterDataSource(base.createDataSource());
        }
    }

    private AdFilterDataSource(DataSource up) {
        this.up = up;
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        up.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        body = null;
        prefix = null;
        pos = 0;
        buffered = false;
        overflow = false;
        upstreamClosed = false;
        cachedUri = null;

        long len = up.open(dataSpec);
        // 必须在任何 close 之前取：DefaultHttpDataSource.getUri() 依赖活着的 connection
        Uri openedUri = up.getUri();
        cachedUri = openedUri != null ? openedUri : dataSpec.uri;

        String path = dataSpec.uri.getPath();
        boolean isPlaylist = path != null
                && path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
        if (!isPlaylist) return len;

        // 播放列表本来就是一次性小请求（百 KB 级），整体缓冲后重写。
        // 注意：这里读完上游但【不关闭】——关闭权交给 close() / 读尽时，
        // 否则 StatsDataSource 紧随其后的 getUri() 会拿到 null。
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        try {
            while ((n = up.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_PLAYLIST_BYTES) {
                    overflow = true;
                    break;
                }
            }
        } catch (IOException e) {
            PlayHeaders.diag("adfilter buffer-read fail url=" + dataSpec.uri
                    + " got=" + bos.size() + " err=" + e);
            closeUpstream();
            throw e;
        }

        byte[] in = bos.toByteArray();
        if (overflow) {
            // 异常超大响应：交付已读前缀，其余继续直读上游，不做任何改写
            PlayHeaders.diag("adfilter url=" + dataSpec.uri + " overflow bytes=" + in.length
                    + " -> passthrough(prefix+stream)");
            prefix = in;
            buffered = true;
            return C.LENGTH_UNSET;   // 总长度未知
        }

        byte[] out = AdFilter.filter(dataSpec.uri, in);
        PlayHeaders.diag("adfilter url=" + dataSpec.uri
                + " upstreamLen=" + len + " bytes=" + in.length
                + " swapped=" + (out != null)
                + (out != null ? "" : " skip=" + AdFilter.lastSkipReason()));
        body = out != null ? out : in;   // out==null（未命中/失败）→ 原样放行
        pos = 0;
        buffered = true;
        return body.length;
    }

    @Override
    public int read(@NonNull byte[] target, int offset, int length) throws IOException {
        if (!buffered) {
            int n = up.read(target, offset, length);
            if (n == C.RESULT_END_OF_INPUT) closeUpstream();
            return n;
        }
        if (length == 0) return 0;

        byte[] cur = overflow ? prefix : body;
        if (cur != null && pos < cur.length) {
            int n = (int) Math.min(length, cur.length - pos);
            System.arraycopy(cur, (int) pos, target, offset, n);
            pos += n;
            return n;
        }
        if (overflow) {
            // 前缀已交付完，剩余部分直读上游
            int n = up.read(target, offset, length);
            if (n == C.RESULT_END_OF_INPUT) closeUpstream();
            return n;
        }
        closeUpstream();
        return C.RESULT_END_OF_INPUT;
    }

    @Nullable
    @Override
    public Uri getUri() {
        // 契约：close() 之前必须返回有效 URI（media3 用它对相对分片路径做基准解析）。
        // 上游 HTTP 连接在播完列表后被关闭，因此此处必须用 open() 时缓存的 URI。
        if (cachedUri != null) return cachedUri;
        return up.getUri();
    }

    @Override
    public void close() throws IOException {
        body = null;
        prefix = null;
        pos = 0;
        buffered = false;
        overflow = false;
        cachedUri = null;
        if (upstreamClosed) return;
        upstreamClosed = true;
        up.close();
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return up.getResponseHeaders();
    }

    /** 关闭上游，幂等；关闭异常不影响播放（与 ExoPlayer 的 closeQuietly 语义一致）。 */
    private void closeUpstream() {
        if (upstreamClosed) return;
        upstreamClosed = true;
        try {
            up.close();
        } catch (Exception e) {
            PlayHeaders.diag("adfilter upstream close fail: " + e);
        }
    }
}
