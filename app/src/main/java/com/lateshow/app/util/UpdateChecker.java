package com.lateshow.app.util;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 应用内检查更新：读取 GitHub Release 最新版本 → 比对当前 versionName → 下载 APK 并唤起系统安装。
 *
 * 审计 A-04：完整性来源只能是 GitHub Release 提供的官方 {@code digest}（sha256:...），
 * 第三方镜像只作为下载加速通道，不再作为信任来源。安装前还会校验：
 * <ol>
 *   <li>下载文件的 SHA-256 与 asset digest 一致；</li>
 *   <li>待安装 APK 的 applicationId 与当前应用一致；</li>
 *   <li>待安装 APK 的签名证书与已安装应用一致（防止同包名不同签名覆盖安装）。</li>
 * </ol>
 * 缺少 digest 时仍执行第 2、3 条与 zip 头/大小校验，不允许「只校验 PK 两字节」直接安装。
 */
public final class UpdateChecker {

    public static final String REPO = "consoleaiden/late-show-app";
    public static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .dns(com.lateshow.app.api.SmartDns.INSTANCE)
            .followSslRedirects(false)          // 审计 A-35：禁止 HTTPS→HTTP 协议降级
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /** 下载用：超时更长，允许同协议重定向（GitHub 资产会 302 到 release-assets 域名） */
    private static final OkHttpClient DOWNLOAD = new OkHttpClient.Builder()
            .dns(com.lateshow.app.api.SmartDns.INSTANCE)
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build();

    /** 备用加速线路；仅加速，完整性由 digest + 签名证书保证 */
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
        /** GitHub asset 的 digest，形如 "sha256:<hex>"；完整性校验的唯一可信来源 */
        public String digest;
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
        /** GitHub 自 2023 起为 asset 提供，例如 "sha256:ab12..." */
        String digest;
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
                                info.digest = a.digest;
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
        if (act.isFinishing() || act.isDestroyed()) return;
        final ProgressDialog d = new ProgressDialog(act,
                "正在下载更新 v" + info.version, "准备中…");
        d.show();

        new Thread(() -> {
            File dir = new File(act.getCacheDir(), "updates");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            final File out = new File(dir, "late-show-update.apk");
            String[] urls = buildUrlCandidates(info.apkUrl);
            String lastErr = "未知错误";
            for (int i = 0; i < urls.length; i++) {
                final int idx = i;
                MAIN.post(() -> {
                    if (!act.isFinishing() && !act.isDestroyed()) {
                        d.setMessage(idx == 0 ? "直连 GitHub…"
                                : "切换线路 " + idx + "/" + (urls.length - 1) + "…");
                    }
                });
                try {
                    fetch(urls[i], out, d, info);
                    // 下载完成后再校验包名与签名证书（摘要校验已在 fetch 内完成）
                    if (!verifyApkIdentity(act, out)) {
                        lastErr = "安装包签名或包名与当前应用不一致，已拒绝安装";
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        continue;
                    }
                    MAIN.post(() -> {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        d.dismiss();
                        install(act, out);
                    });
                    return;
                } catch (Exception e) {
                    String m = e.getMessage();
                    lastErr = (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
                }
            }
            final String err = lastErr;
            MAIN.post(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                d.dismiss();
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

    /**
     * 下载单个线路；成功返回（否则抛异常，由调用方切换下一条线路）。
     * 校验：zip 头、Release 记录大小、SHA-256 digest。
     */
    private static void fetch(String url, File out, ProgressDialog d, Info info) throws IOException {
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
            if (total <= 0) total = info.size;
            long got = 0;
            byte[] head = new byte[2];
            int headLen = 0;
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("无法初始化完整性校验");
            }
            try (InputStream in = r.body().byteStream();
                 FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (headLen < 2) {
                        for (int k = 0; k < n && headLen < 2; k++) head[headLen++] = buf[k];
                    }
                    fo.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        final int p = (int) Math.min(100, got * 100 / total);
                        MAIN.post(() -> {
                            d.setProgress(p);
                            d.setMessage("已下载 " + p + "%");
                        });
                    }
                }
            }
            if (headLen < 2 || head[0] != 'P' || head[1] != 'K') {
                throw new IOException("下载内容不是 APK 文件");
            }
            if (info.size > 0 && got != info.size) {
                throw new IOException("文件大小与 Release 记录不一致");
            }
            String want = expectedSha256(info.digest);
            if (want != null) {
                String gotHex = Credentials.sha256Hex(md.digest());
                if (!want.equalsIgnoreCase(gotHex)) {
                    throw new IOException("SHA-256 校验失败，安装包可能被篡改");
                }
            }
            if (got <= 0) throw new IOException("下载内容为空");
        }
    }

    /** GitHub digest 形如 "sha256:ab12…"；也兼容纯 64 位十六进制 */
    private static String expectedSha256(String digest) {
        if (digest == null) return null;
        String d = digest.trim().toLowerCase(Locale.US);
        int c = d.indexOf(':');
        if (c >= 0) {
            if (!"sha256".equals(d.substring(0, c))) return null;
            d = d.substring(c + 1);
        }
        return d.matches("[0-9a-f]{64}") ? d : null;
    }

    /**
     * 校验待安装 APK 的 applicationId 与签名证书（审计 A-04 第 2、3 条）。
     * 这样即使 Release 账号被攻破、digest 也被一并替换，攻击者仍无法用另一份私钥签出可覆盖安装的包。
     */
    @SuppressWarnings("deprecation")
    private static boolean verifyApkIdentity(Activity act, File apk) {
        try {
            PackageManager pm = act.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo target = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            PackageInfo installed = pm.getPackageInfo(act.getPackageName(), flags);
            if (target == null || installed == null) return false;
            if (!act.getPackageName().equals(target.packageName)) return false;
            Set<String> a = certs(installed), b = certs(target);
            if (a.isEmpty() || b.isEmpty()) return false;
            for (String s : a) if (b.contains(s)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certs(PackageInfo pi) {
        Set<String> out = new HashSet<>();
        Signature[] arr;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            arr = pi.signingInfo.hasMultipleSigners()
                    ? pi.signingInfo.getApkContentsSigners()
                    : pi.signingInfo.getSigningCertificateHistory();
        } else {
            arr = pi.signatures;
        }
        if (arr == null) return out;
        for (Signature s : arr) {
            String h = Credentials.sha256Hex(s.toByteArray());
            if (!h.isEmpty()) out.add(h);
        }
        return out;
    }

    private static void showFailDialog(final Activity act, final Info info, String err) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        new MaterialAlertDialogBuilder(act)
                .setTitle("下载失败")
                .setMessage(err + "\n\n可以改用浏览器下载安装包。")
                .setPositiveButton("浏览器下载", (d, w) -> openReleasePage(act, info))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 外部 ACTION_VIEW 白名单（审计 A-34）：只放行 github.com 的 https 页面 */
    private static void openReleasePage(Activity act, Info info) {
        String url = info != null && !TextUtils.isEmpty(info.pageUrl) ? info.pageUrl : RELEASES_PAGE;
        try {
            Uri u = Uri.parse(url);
            if (!"https".equalsIgnoreCase(u.getScheme())) return;
            String host = u.getHost();
            if (host == null || !(host.equalsIgnoreCase("github.com")
                    || host.toLowerCase(Locale.US).endsWith(".github.com"))) return;
            act.startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) { }
    }

    private static void install(final Activity act, File apk) {
        if (act.isFinishing() || act.isDestroyed()) return;
        // Android 8.0+ 需要「安装未知应用」授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !act.getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(act)
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

    /** 下载进度弹窗：替代已废弃的 framework ProgressDialog，走 App 统一弹层主题 */
    private static final class ProgressDialog {
        final AlertDialog d;
        final android.widget.TextView msg;
        final android.widget.ProgressBar bar;

        ProgressDialog(Activity act, String title, String message) {
            float den = act.getResources().getDisplayMetrics().density;
            android.widget.LinearLayout box = new android.widget.LinearLayout(act);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = Math.round(24 * den);
            box.setPadding(pad, Math.round(12 * den), pad, pad);
            msg = new android.widget.TextView(act);
            msg.setTextSize(13);
            msg.setTextColor(androidx.core.content.ContextCompat.getColor(act, com.lateshow.app.R.color.t2));
            box.addView(msg);
            bar = new android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(12 * den);
            box.addView(bar, lp);
            d = new MaterialAlertDialogBuilder(act)
                    .setTitle(title)
                    .setCancelable(false)
                    .setView(box)
                    .create();
            msg.setText(message);
        }

        void show() { d.show(); }
        void setMax(int m) { bar.setMax(m); }
        void setMessage(String m) { msg.setText(m); }
        void setProgress(int p) { bar.setProgress(p); }
        void dismiss() { try { d.dismiss(); } catch (Exception ignored) { } }
    }
}
