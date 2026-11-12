package com.lateshow.app.util;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 */
public final class AdFilterDataSource implements DataSource {

    private final DataSource up;
    private byte[] body;
    private long pos;
    private boolean swapped;

    /** 包装既有 Factory；接入点：new DefaultMediaSourceFactory(new Factory(httpFactory)) */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory base;

        public Factory(DataSource.Factory base) {
            this.base = base;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
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
        pos = 0;
        swapped = false;
        long len = up.open(dataSpec);
        String path = dataSpec.uri.getPath();
        boolean isPlaylist = path != null
                && path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
        if (!isPlaylist) return len;

        // 播放列表本来就是一次性小请求（百 KB 级），整体缓冲后重写
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        try {
            while ((n = up.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) {
                bos.write(buf, 0, n);
                if (bos.size() > 8 * 1024 * 1024) break;   // 防御：超大响应放弃过滤
            }
        } catch (IOException e) {
            com.lateshow.app.util.PlayHeaders.diag("adfilter buffer-read fail url="
                    + dataSpec.uri + " got=" + bos.size() + " err=" + e);
            throw e;
        } finally {
            up.close();   // 上游已读完，提前关闭；close() 里不再重复委托
        }
        byte[] in = bos.toByteArray();
        byte[] out = AdFilter.filter(dataSpec.uri, in);
        com.lateshow.app.util.PlayHeaders.diag("adfilter url=" + dataSpec.uri
                + " upstreamLen=" + len + " bytes=" + in.length
                + " swapped=" + (out != null)
                + (out != null ? "" : " skip=" + AdFilter.lastSkipReason()));
        body = out != null ? out : in;   // out==null（未命中/失败）→ 原样放行
        pos = 0;
        swapped = true;
        return body.length;
    }

    @Override
    public int read(@NonNull byte[] target, int offset, int length) throws IOException {
        if (!swapped) return up.read(target, offset, length);
        if (body == null || pos >= body.length) return C.RESULT_END_OF_INPUT;
        if (length == 0) return 0;
        int n = (int) Math.min(length, body.length - pos);
        System.arraycopy(body, (int) pos, target, offset, n);
        pos += n;
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        // 必须返回上游最终 URI（跟随重定向后）：HLS 相对分片路径以它为基准解析，
        // 与未过滤时的行为完全一致
        Uri u = up.getUri();
        return u != null ? u : null;
    }

    @Override
    public void close() throws IOException {
        if (!swapped) up.close();
        body = null;
        pos = 0;
        swapped = false;
    }

    @NonNull
    @Override
    public Map<String, java.util.List<String>> getResponseHeaders() {
        return up.getResponseHeaders();
    }
}
