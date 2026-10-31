package com.lateshow.app.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.lateshow.app.BuildConfig;
import com.lateshow.app.api.SmartDns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 应用内检查更新：读取 GitHub Release 最新版本 → 比对当前 versionName → 下载 APK 并唤起系统安装。
 * 发布由 GitHub Actions 在每次 push 后自动创建 Release（见 .github/workflows/build.yml）。
 */
public final class UpdateChecker {

    public static final String REPO = "consoleaiden/late-show-app";
    public static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .dns(SmartDns.INSTANCE)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /** 下载用：超时更长，跟随重定向（GitHub 资产会 302 到 release-assets 域名） */
    private static final OkHttpClient DOWNLOAD = new OkHttpClient.Builder()
            .dns(SmartDns.INSTANCE)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build();

    /** 备用加速线路（按实测可用率排序；直连 GitHub 资产域名在部分移动网络被拦截时使用） */
    private static final String[] MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghps.cc/",
            "https://ghproxy.net/",
            "https://ghfast.top/"
    };

    private UpdateChecker() { }

    /** 当前版本号（构建时注入） */
    public static String currentVersion() {
        return BuildConfig.VERSION_NAME;
    }

    public static class Info {
        public String version;   // 1.5.12
        public String notes;     // Release 正文
        public String apkUrl;    // 直链
        public String pageUrl;   // Release 页面
        public long size;
    }

    public interface Cb {
        /** info 为 null 表示已是最新；error 非 null 表示检查失败 */
        void onResult(Info info, String error);
    }

    private static class Release {
        @SerializedName("tag_name") String tagName;
        @SerializedName("html_url") String htmlUrl;
        String body;
        List<Asset> assets;
    }

    private static class Asset {
        String name;
        @SerializedName("browser_download_url") String downloadUrl;
        long size;
    }

    /** 检查更新（子线程请求，回调在主线程） */
    public static void check(final Cb cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(API_LATEST)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "LateShow-App")
                        .build();
                try (Response r = CLIENT.newCall(req).execute()) {
                    if (!r.isSuccessful() || r.body() == null) {
                        fail(cb, "GitHub 返回 HTTP " + r.code());
                        return;
                    }
                    Release rel = GSON.fromJson(r.body().string(), Release.class);
                    if (rel == null || TextUtils.isEmpty(rel.tagName)) {
                        fail(cb, "尚未发布任何版本");
                        return;
                    }
                    String latest = rel.tagName;
                    if (latest.startsWith("v") || latest.startsWith("V")) latest = latest.substring(1);

                    Info info = new Info();
                    info.version = latest;
                    info.notes = rel.body != null ? rel.body : "";
                    info.pageUrl = rel.htmlUrl;
                    if (rel.assets != null) {
                        for (Asset a : rel.assets) {
                            if (a.name != null && a.name.endsWith(".apk")) {
                                info.apkUrl = a.downloadUrl;
                                info.size = a.size;
                                break;
                            }
                        }
                    }
                    boolean newer = isNewer(latest, BuildConfig.VERSION_NAME);
                    final Info out = newer ? info : null;
                    MAIN.post(() -> cb.onResult(out, null));
                }
            } catch (Exception e) {
                fail(cb, "检查失败：" + e.getMessage());
            }
        }).start();
    }

    private static void fail(Cb cb, String msg) {
        MAIN.post(() -> cb.onResult(null, msg));
    }

    /** latest 是否比 current 新（按数字段比较，忽略后缀） */
    public static boolean isNewer(String latest, String current) {
        int[] a = parse(latest), b = parse(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        int cut = s.indexOf('-');
        if (cut > 0) s = s.substring(0, cut);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", "")); }
            catch (Exception e) { out[i] = 0; }
        }
        return out;
    }

    /** 下载 APK 并唤起系统安装器（带进度条；直连失败自动切换加速线路） */
    public static void downloadAndInstall(final Activity act, final Info info) {
        if (info == null || TextUtils.isEmpty(info.apkUrl)) {
            Toast.makeText(act, "该版本没有可下载的 APK", Toast.LENGTH_SHORT).show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("正在下载更新 v" + info.version);
        pd.setMessage("准备中…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            final File out = new File(act.getCacheDir(), "late-show-update.apk");
            String[] urls = buildUrlCandidates(info.apkUrl);
            String lastErr = "未知错误";
            for (int i = 0; i < urls.length; i++) {
                final int idx = i;
                MAIN.post(() -> pd.setMessage(idx == 0 ? "直连 GitHub…"
                        : "切换线路 " + idx + "/" + (urls.length - 1) + "…"));
                try {
                    if (fetch(urls[i], out, pd, info.size)) {
                        MAIN.post(() -> {
                            pd.dismiss();
                            install(act, out);
                        });
                        return;
                    }
                    lastErr = "文件校验失败（可能下载到错误页面）";
                } catch (Exception e) {
                    String m = e.getMessage();
                    lastErr = (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
                }
            }
            final String err = lastErr;
            MAIN.post(() -> {
                pd.dismiss();
                showFailDialog(act, info, err);
            });
        }).start();
    }

    private static String[] buildUrlCandidates(String apkUrl) {
        String[] out = new String[1 + MIRRORS.length];
        out[0] = apkUrl;
        for (int i = 0; i < MIRRORS.length; i++) out[i + 1] = MIRRORS[i] + apkUrl;
        return out;
    }

    /** 下载单个线路；成功返回 true（大小与 APK 头都对得上） */
    private static boolean fetch(String url, File out, ProgressDialog pd, long expectSize) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) LateShow-App")
                .header("Accept", "application/octet-stream,application/vnd.android.package-archive,*/*")
                .build();
        try (Response r = DOWNLOAD.newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) {
                throw new IOException("HTTP " + r.code());
            }
            long total = r.body().contentLength();
            if (total <= 0) total = expectSize;
            long got = 0;
            byte[] head = new byte[2];
            int headLen = 0;
            try (InputStream in = r.body().byteStream();
                 FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (headLen < 2) {
                        for (int k = 0; k < n && headLen < 2; k++) head[headLen++] = buf[k];
                    }
                    fo.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        final int p = (int) Math.min(100, got * 100 / total);
                        MAIN.post(() -> {
                            pd.setProgress(p);
                            pd.setMessage("已下载 " + p + "%");
                        });
                    }
                }
            }
            // 校验：APK 是 zip，头两字节必须是 PK；大小需与 Release 记录一致
            if (headLen < 2 || head[0] != 'P' || head[1] != 'K') return false;
            if (expectSize > 0 && got != expectSize) return false;
            return got > 0;
        }
    }

    private static void showFailDialog(final Activity act, final Info info, String err) {
        if (act == null || act.isFinishing()) return;
        new AlertDialog.Builder(act)
                .setTitle("下载失败")
                .setMessage(err + "\n\n可以改用浏览器下载安装包。")
                .setPositiveButton("浏览器下载", (d, w) -> {
                    try {
                        act.startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(TextUtils.isEmpty(info.pageUrl) ? RELEASES_PAGE : info.pageUrl)));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void install(final Activity act, File apk) {
        // Android 8.0+ 需要「安装未知应用」授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act)
                    .setTitle("需要安装权限")
                    .setMessage("请允许「夜场 LATE SHOW」安装应用，返回后再次点击更新即可。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception ignored) { }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "无法唤起安装器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
