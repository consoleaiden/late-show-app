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

import com.lateshow.app.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
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

    /**
     * 选线路用：只取前 2 个字节，超时压到 6~8 秒。
     * 没有这一层时，一条挂掉的镜像要等满 connect/read 超时（12+45 秒）才轮到下一条，
     * 用户看到的就是「进度条卡住不动」。
     */
    private static final OkHttpClient PROBE = new OkHttpClient.Builder()
            .dns(com.lateshow.app.api.SmartDns.INSTANCE)
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build();

    /**
     * 下载加速线路（用户实测可用，顺序＝优先尝试顺序）。
     *
     * <p>用法是「前缀 + 完整 GitHub 直链」，例如：
     * {@code https://ghproxy.net/https://github.com/owner/repo/releases/download/v1.5.110/xx.apk}。
     *
     * <p>安全性：镜像只负责搬运字节，完整性由 Release 的 SHA-256 digest + 安装包签名证书
     * 双重校验（见 {@link #fetch} 与 {@link #verifyApkIdentity}）。即使镜像返回了被篡改的
     * 内容，也会在安装前被拒绝并自动切下一条线路，不会装进用户手机。
     */
    private static final String[] MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/"
    };

    private static final String UA = "Mozilla/5.0 (Linux; Android) LateShow-App";

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

    /**
     * 解析 GitHub Release 的活交给 {@link ReleaseReader}：它用 JsonObject 显式取值，
     * 不依赖 Gson 反射，R8 改名不会再把 {@code assets} 弄丢
     * （v1.5.107~111「检测到更新但没有可下载的 APK」的根因，详见该类注释）。
     */

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
                    ReleaseReader.Release rel = ReleaseReader.read(r.body().string());
                    if (rel == null || TextUtils.isEmpty(rel.version)) {
                        fail(cb, "尚未发布任何版本");
                        return;
                    }
                    String latest = rel.version;

                    Info info = new Info();
                    info.version = latest;
                    info.notes = rel.notes;
                    info.pageUrl = rel.pageUrl;
                    info.apkUrl = rel.apkUrl;
                    info.size = rel.size;
                    info.digest = rel.digest;
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

    /** 下载 APK 并唤起系统安装器（带进度条；加速线路优先，逐条失败自动切换） */
    public static void downloadAndInstall(final Activity act, final Info info) {
        if (info == null || TextUtils.isEmpty(info.apkUrl)) {
            // 拿不到直链（例如 Release 只上传了校验文件）：别让用户卡死，直接打开发布页
            Toast.makeText(act, "未获取到安装包直链，正在打开发布页…", Toast.LENGTH_SHORT).show();
            openReleasePage(act, info);
            return;
        }
        if (act.isFinishing() || act.isDestroyed()) return;
        final ProgressDialog d = new ProgressDialog(act,
                "正在下载更新 v" + info.version + (info.size > 0 ? " · " + mb(info.size) : ""),
                "正在选择线路…");
        d.show();

        new Thread(() -> {
            File dir = new File(act.getCacheDir(), "updates");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            final File out = new File(dir, "late-show-update.apk");
            //noinspection ResultOfMethodCallIgnored
            out.delete();                       // 上一次没下完的残包不能用
            // 先花 2~8 秒探出「此刻真能连上的那条线路」，再整包下载：
            // 比「死线路下到一半超时再换」快得多，用户也不会看到长时间 0%
            String[] urls = buildUrlCandidates(info.apkUrl);
            String picked = urls.length > 1 ? pickReachable(urls) : null;
            final String[] order = reorder(urls, picked);
            String lastErr = "未知错误";
            // 已落盘字节：换线路时带 Range 续传，不再从 0 重下（用户看到进度倒退会以为出故障）
            final long[] written = {0L};
            for (int i = 0; i < order.length; i++) {
                final int idx = i;
                final long resumeAt = written[0];
                final String why = idx == 0 ? "" : lastErr;
                MAIN.post(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (idx == 0) {
                        d.setMessage("正在连接 " + label(order[0]) + "…");
                    } else {
                        d.setMessage("已换线路 " + label(order[idx]) + "（" + (idx + 1) + "/"
                                + order.length + "）" + (resumeAt > 0 ? " · 从 " + mb(resumeAt) + " 续传" : "")
                                + (why.isEmpty() ? "" : "\n上次中断：" + why));
                    }
                });
                try {
                    fetch(order[i], out, d, info, written);
                    // 下载完成后再校验包名与签名证书（摘要校验已在 fetch 内完成）
                    if (!verifyApkIdentity(act, out)) {
                        lastErr = "安装包签名或包名与当前应用不一致，已拒绝安装";
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        written[0] = 0L;
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
                    // 内容不对（不是 APK / 摘要不符）就别再续传这堆字节了，下一条线路从头来
                    if (lastErr.contains("不是 APK") || lastErr.contains("SHA-256")) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        written[0] = 0L;
                    }
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

    /**
     * 候选顺序：加速镜像在前，官方直链兜底。
     * 国内直连 {@code release-assets.githubusercontent.com} 经常被限速甚至连不上，
     * 而镜像只是把同一个 URL 的字节转发回来（完整性由 digest + 签名校验保证），
     * 所以默认先走镜像；镜像全挂了才回官方。
     */
    private static String[] buildUrlCandidates(String apkUrl) {
        String[] out = new String[MIRRORS.length + 1];
        for (int i = 0; i < MIRRORS.length; i++) out[i] = MIRRORS[i] + apkUrl;
        out[MIRRORS.length] = apkUrl;
        return out;
    }

    /** 用 Range: bytes=0-1 探一条线路是否真的能出数据；不读正文，探完立刻断开 */
    private static String pickReachable(String[] urls) {
        for (String u : urls) {
            Request req = new Request.Builder()
                    .url(u)
                    .header("User-Agent", UA)
                    .header("Range", "bytes=0-1")
                    .build();
            try (Response r = PROBE.newCall(req).execute()) {
                if (r.isSuccessful() || r.code() == 206) return u;
            } catch (Exception ignored) {
                // 这条不通，试下一条
            }
        }
        return null;
    }

    /** 把探测命中的线路排到第一位，其余保持原顺序 */
    private static String[] reorder(String[] urls, String first) {
        if (first == null) return urls;
        String[] out = new String[urls.length];
        out[0] = first;
        int j = 1;
        for (String u : urls) if (!u.equals(first)) out[j++] = u;
        return out;
    }

    /** 进度文案用的线路名：镜像显示域名，官方直链显示「GitHub 直连」 */
    private static String label(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return "线路";
            String h = host.toLowerCase(Locale.US);
            if (h.equals("github.com") || h.endsWith(".github.com")) return "GitHub 直连";
            return host;
        } catch (Exception e) {
            return "线路";
        }
    }

    /**
     * 更新弹窗正文首行：当前版本 → 新版本 · 安装包大小。
     * 体积来自 Release asset（由 {@link ReleaseReader} 解析），拿不到就不显示，
     * 避免出现「安装包 0 MB」这种废话。
     */
    public static String brief(Info info) {
        String cur = "当前 v" + currentVersion();
        if (info == null) return cur;
        StringBuilder sb = new StringBuilder(cur);
        if (info.version != null) sb.append(" → v").append(info.version);
        if (info.size > 0) sb.append(" · 安装包 ").append(mb(info.size));
        return sb.toString();
    }

    /** 字节数 → "3.7 MB"（下载进度用） */
    private static String mb(long bytes) {
        if (bytes <= 0) return "0 MB";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /**
     * 下载单条线路，支持跨线路续传。
     *
     * <p>{@code written[0]} 是「已落盘字节」：进入时据此发 {@code Range: bytes=N-}，
     * 服务端回 206 就接着写（先把已有前缀喂给摘要，保证整包 SHA-256 仍然正确）；
     * 回 200 说明它忽略 Range，那就从 0 重来。失败时把实际落盘字节写回 {@code written[0]}，
     * 下一条线路接着传——用户不会看到进度条倒退回 0（v1.5.116 起）。
     *
     * <p>校验：zip 头、Release 记录大小、SHA-256 digest。
     */
    private static void fetch(String url, File out, ProgressDialog d, Info info, long[] written)
            throws IOException {
        final String line = label(url);
        long start = written == null ? 0 : written[0];
        if (start > 0 && (!out.isFile() || out.length() < start)) start = 0;   // 残包对不上就重下

        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/octet-stream,application/vnd.android.package-archive,*/*");
        if (start > 0) rb.header("Range", "bytes=" + start + "-");

        try (Response r = DOWNLOAD.newCall(rb.build()).execute()) {
            if (r.body() == null) throw new IOException("响应为空");
            boolean partial = r.code() == 206;
            if (!r.isSuccessful() && !partial) throw new IOException("HTTP " + r.code());
            if (start > 0 && !partial) start = 0;      // 不支持续传：整包重来

            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("无法初始化完整性校验");
            }
            boolean headOk = true;
            if (start > 0) headOk = feedPrefix(out, start, md);

            long total = r.body().contentLength();
            total = total > 0 ? start + total : (info.size > 0 ? info.size : 0);
            long got = start;
            int lastPct = -2;
            byte[] head = new byte[2];
            int headLen = 0;
            try (InputStream in = r.body().byteStream();
                 FileOutputStream fo = new FileOutputStream(out, start > 0)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (start == 0 && headLen < 2) {
                        for (int k = 0; k < n && headLen < 2; k++) head[headLen++] = buf[k];
                    }
                    fo.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    if (written != null) written[0] = got;      // 中断也能从这里接着传
                    // 只在百分比变化时刷新一次，避免每 32KB 就往主线程投一条消息
                    final int pct = total > 0 ? (int) Math.min(100, got * 100 / total) : -1;
                    if (pct != lastPct) {
                        lastPct = pct;
                        final long g = got, t = total;
                        MAIN.post(() -> {
                            if (pct >= 0) d.setProgress(pct);
                            d.setMessage(line + " · " + (pct >= 0 ? pct + "% · " : "")
                                    + mb(g) + (t > 0 ? " / " + mb(t) : ""));
                        });
                    }
                }
            }
            if (start == 0 && (headLen < 2 || head[0] != 'P' || head[1] != 'K')) {
                throw new IOException("下载内容不是 APK 文件");
            }
            if (start > 0 && !headOk) {
                throw new IOException("下载内容不是 APK 文件");
            }
            if (info.size > 0 && got != info.size) {
                throw new IOException("文件大小与 Release 记录不一致（" + got + "/" + info.size + "）");
            }
            String want = expectedSha256(info.digest);
            if (want != null) {
                // 注意：md.digest() 已经是摘要结果，只需要转十六进制；
                // 早前误用 Credentials.sha256Hex(md.digest()) 等于又算了一遍摘要，
                // 导致正确文件也永远校验失败（v1.5.114/115）。
                String gotHex = DigestUtil.hex(md.digest());
                if (!want.equalsIgnoreCase(gotHex)) {
                    throw new IOException("SHA-256 校验失败，安装包可能被篡改");
                }
            }
            if (got <= 0) throw new IOException("下载内容为空");
        }
    }

    /**
     * 续传前把已落盘的前缀重新喂给摘要（MessageDigest 不能跨请求续算），
     * 顺便确认开头确实是 ZIP（"PK"），防止把上次的坏残包接着写。
     */
    private static boolean feedPrefix(File f, long len, MessageDigest md) throws IOException {
        byte[] buf = new byte[32 * 1024];
        long left = len;
        boolean headOk = true;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int n;
            long pos = 0;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                if (pos == 0) {
                    headOk = n >= 2 && buf[0] == 'P' && buf[1] == 'K';
                }
                md.update(buf, 0, n);
                pos += n;
                left -= n;
            }
            if (pos != len) return false;   // 文件比记录的短：当残包作废
        }
        return headOk;
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
                .setMessage(err + "\n\n已尝试全部下载线路。可以重试（会从已下载的部分续传），"
                        + "或改用浏览器直接下载安装包。")
                .setPositiveButton("重试", (d, w) -> downloadAndInstall(act, info))
                .setNeutralButton("浏览器下载", (d, w) -> openReleasePage(act, info))
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
                    .setMessage("请允许「方寸」安装应用，返回后再次点击更新即可。")
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
