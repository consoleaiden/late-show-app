package com.lateshow.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * GitHub Release JSON 解析（检查更新的入口数据）。
 *
 * <p>这组用例锁的是 v1.5.107~111 的线上故障：release 包开了 R8，旧的 POJO 反射映射
 * 把没注解的 {@code assets} 字段改名，导致「检测到新版本 → 点立即更新 → 提示没有
 * 可下载的 APK」。现在解析走 {@link ReleaseReader} 的显式取值，只要这几条断言绿，
 * 就说明「APK 直链一定能被找到」。
 */
public class ReleaseReaderTest {

    /** 线上真实结构：两个资产，apk 在前、.apk.sha256 在后 */
    private static final String REAL = "{\n"
            + "  \"tag_name\": \"v1.5.111\",\n"
            + "  \"html_url\": \"https://github.com/consoleaiden/late-show-app/releases/tag/v1.5.111\",\n"
            + "  \"body\": \"- 修复标签检索\\n- 镜像加速下载\",\n"
            + "  \"assets\": [\n"
            + "    {\"name\": \"late-show-v1.5.111.apk\", \"size\": 3729755,\n"
            + "     \"browser_download_url\": \"https://github.com/consoleaiden/late-show-app/releases/download/v1.5.111/late-show-v1.5.111.apk\",\n"
            + "     \"digest\": \"sha256:59055dca984c3f2909e0c26ae750ac4a83e9dc24715b1897685101f47c936548\"},\n"
            + "    {\"name\": \"late-show-v1.5.111.apk.sha256\", \"size\": 89,\n"
            + "     \"browser_download_url\": \"https://github.com/consoleaiden/late-show-app/releases/download/v1.5.111/late-show-v1.5.111.apk.sha256\",\n"
            + "     \"digest\": \"sha256:325b10a34838cae88505ee43c6b4b6d4fa59e61b212dfa2417a4632110be2a6c\"}\n"
            + "  ]\n"
            + "}";

    @Test
    public void picksApkAssetAndStripsVersionPrefix() {
        ReleaseReader.Release r = ReleaseReader.read(REAL);
        assertNotNull(r);
        assertEquals("1.5.111", r.version);
        assertEquals("https://github.com/consoleaiden/late-show-app/releases/tag/v1.5.111", r.pageUrl);
        assertEquals(3729755L, r.size);
        assertEquals("sha256:59055dca984c3f2909e0c26ae750ac4a83e9dc24715b1897685101f47c936548", r.digest);
        assertNotNull("assets 必须能被解析出来，否则点更新就报「没有可下载的 APK」", r.apkUrl);
        assertEquals("https://github.com/consoleaiden/late-show-app/releases/download/v1.5.111/late-show-v1.5.111.apk",
                r.apkUrl);
    }

    /** 资产顺序颠倒时也不能选到 .apk.sha256 */
    @Test
    public void ignoresChecksumAssetEvenWhenListedFirst() {
        String json = "{\"tag_name\":\"v1.5.112\",\"assets\":["
                + "{\"name\":\"a.apk.sha256\",\"browser_download_url\":\"https://x/a.apk.sha256\",\"size\":89},"
                + "{\"name\":\"a.apk\",\"browser_download_url\":\"https://x/a.apk\",\"size\":100}"
                + "]}";
        ReleaseReader.Release r = ReleaseReader.read(json);
        assertNotNull(r);
        assertEquals("https://x/a.apk", r.apkUrl);
        assertEquals(100L, r.size);
    }

    /** 只有校验文件、没上传 APK：version 仍要能读到，apkUrl 为空交给 UI 打开发布页 */
    @Test
    public void missingApkLeavesUrlNullButKeepsVersion() {
        String json = "{\"tag_name\":\"v9.9.9\",\"assets\":["
                + "{\"name\":\"a.apk.sha256\",\"browser_download_url\":\"https://x/a.sha256\"}]}";
        ReleaseReader.Release r = ReleaseReader.read(json);
        assertNotNull(r);
        assertEquals("9.9.9", r.version);
        assertNull(r.apkUrl);
        assertNull(r.digest);
    }

    /** 老 Release 没有 digest 字段：不能因此报错，交给签名证书校验兜底 */
    @Test
    public void missingDigestIsNullNotCrash() {
        String json = "{\"tag_name\":\"1.5.10\",\"assets\":["
                + "{\"name\":\"a.apk\",\"browser_download_url\":\"https://x/a.apk\",\"size\":7}]}";
        ReleaseReader.Release r = ReleaseReader.read(json);
        assertNotNull(r);
        assertEquals("1.5.10", r.version);
        assertNull(r.digest);
        assertEquals(7L, r.size);
    }

    /** 大写后缀也要认出来 */
    @Test
    public void acceptsUpperCaseApkSuffix() {
        String json = "{\"tag_name\":\"v1.0.1\",\"assets\":["
                + "{\"name\":\"A.APK\",\"browser_download_url\":\"https://x/A.APK\"}]}";
        ReleaseReader.Release r = ReleaseReader.read(json);
        assertNotNull(r);
        assertEquals("https://x/A.APK", r.apkUrl);
    }

    @Test
    public void invalidJsonReturnsNull() {
        assertNull(ReleaseReader.read(null));
        assertNull(ReleaseReader.read(""));
        assertNull(ReleaseReader.read("not json"));
        assertNull(ReleaseReader.read("[]"));
    }

    /** 空响应体/空 assets：不抛异常 */
    @Test
    public void emptyAssetsObjectIsSafe() {
        ReleaseReader.Release r = ReleaseReader.read("{\"tag_name\":\"v2.0.0\",\"assets\":[]}");
        assertNotNull(r);
        assertEquals("2.0.0", r.version);
        assertNull(r.apkUrl);
        assertEquals("", r.notes);
    }
}
