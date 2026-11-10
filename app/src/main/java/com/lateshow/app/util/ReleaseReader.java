package com.lateshow.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * GitHub「latest release」JSON → 下载安装包所需的字段。
 *
 * <p><b>为什么不用 POJO + Gson 反射</b>：release 包开了 R8，字段名会被改成 a/b/c。
 * 带 {@code @SerializedName} 的字段还能靠注解找回 JSON 名字，<b>没注解的字段就彻底失配</b>
 * （v1.5.107~v1.5.111 的线上故障：{@code assets} 没有注解被改名，Gson 找不到 →
 * 版本能检测到、点「立即更新」却提示「没有可下载的 APK」）。
 * 这里改用 {@link JsonObject} 逐级显式取值，Gson 只做语法解析、不碰反射，
 * 混淆与否都不影响结果，属于结构上免疫。
 *
 * <p>本类不引用任何 Android API，可直接在 JVM 单测里跑。
 */
public final class ReleaseReader {

    private ReleaseReader() { }

    /** 解析结果；字段缺失时为 null，调用方按「拿不到直链」处理（引导去打开发布页） */
    public static final class Release {
        /** 版本号，已去掉 {@code v} 前缀；无法解析时为 null */
        public String version;
        /** Release 正文（更新说明） */
        public String notes = "";
        /** Release 页面地址 */
        public String pageUrl;
        /** APK 直链；该版本没上传 apk 资源时为 null */
        public String apkUrl;
        /** APK 字节数；未知为 0 */
        public long size;
        /** GitHub 提供的 {@code sha256:<hex>}；未知为 null */
        public String digest;
    }

    /**
     * @param json GitHub {@code /releases/latest} 的响应体
     * @return 解析结果；整体不是合法对象时返回 null
     */
    public static Release read(String json) {
        if (json == null) return null;
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el == null || !el.isJsonObject()) return null;
            root = el.getAsJsonObject();
        } catch (Exception e) {
            return null;
        }

        Release out = new Release();
        out.version = stripV(str(root, "tag_name"));
        out.pageUrl = str(root, "html_url");
        out.notes = nz(str(root, "body"));
        try {
            fillAsset(out, root.getAsJsonArray("assets"));
        } catch (Exception ignored) {
            // assets 不是数组（异常响应/中间件改写）：当作没有资产，交由 UI 引导去发布页
        }
        return out;
    }

    /** 选第一个 {@code .apk} 资源（Release 里同时挂着 {@code .apk.sha256} 校验文件，不能选错） */
    private static void fillAsset(Release out, JsonArray assets) {
        if (assets == null) return;
        for (JsonElement e : assets) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String name = str(a, "name");
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".apk")) continue;
            out.apkUrl = str(a, "browser_download_url");
            out.size = lng(a, "size");
            out.digest = str(a, "digest");
            return;
        }
    }

    private static String stripV(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        if (t.isEmpty()) return null;
        if (t.charAt(0) == 'v' || t.charAt(0) == 'V') t = t.substring(1);
        return t.trim().isEmpty() ? null : t;
    }

    /** 取字符串字段；缺失 / 非字符串 / JSON null 一律返回 null */
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long lng(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0L;
        try {
            return e.getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
