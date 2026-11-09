package com.lateshow.app.api;

/**
 * 首页标签检索意图（审计 A-30 / B-01，配合 ServerCaps 门控）。
 *
 * <p>「点详情页标签 → 用标签值精确检索同标签影片」需要后端提供 {@code tag} 参数。
 * 在后端能力就绪前，客户端仍按原有 {@code wd} 全文检索；能力就绪后，
 * {@link SyncCatalog#videos} 会消费这里的一次性意图，改用 {@code tag} 参数。
 * 用一次性意图而不是给 Catalog 接口加形参，是为了不改动 8 方法契约、不影响直连模式。
 */
public final class SearchIntent {

    private static volatile String sTag = null;

    private SearchIntent() { }

    public static void setTag(String tag) {
        sTag = (tag == null || tag.trim().isEmpty()) ? null : tag.trim();
    }

    /** 读取并清除；返回 null 表示这次不是标签检索 */
    public static String consumeTag() {
        String t = sTag;
        sTag = null;
        return t;
    }

    public static void clear() {
        sTag = null;
    }
}
