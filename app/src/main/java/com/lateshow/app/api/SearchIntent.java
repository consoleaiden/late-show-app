package com.lateshow.app.api;

/**
 * 首页检索模式（审计 A-30 / B-01 / M5，2026-11-10 与后端对齐）。
 *
 * <p>后端明确的两条路径：
 * <ul>
 *   <li><b>标签胶囊点击</b> → 走 {@code tag=}，索引里有的词必命中（{@code tag_found=true}）；</li>
 *   <li><b>搜索框输入</b> → 始终走 {@code wd=} 全文检索，只在收到 {@code tag_found=false}
 *       时由客户端回退。</li>
 * </ul>
 *
 * <p>为什么是「粘性」状态而不是一次性意图：翻页会再次调用
 * {@link SyncCatalog#videos}，如果标签意图只消费一次，第 2 页就会退回 {@code wd=}，
 * 出现「第 1 页是标签结果、第 2 页是全文结果」的错位。所以这里保存的是「当前处于标签
 * 检索模式」这一状态，直到用户改输入框、点历史词或切数据源才清除。
 *
 * <p>用状态而不是给 Catalog 接口加形参，是为了不改动 8 方法契约、不影响直连模式。
 */
public final class SearchIntent {

    /** 当前标签检索词；null = 普通浏览 / 全文检索模式 */
    private static volatile String sTag = null;

    private SearchIntent() { }

    /** 进入标签检索模式（标签胶囊点击时调用） */
    public static void setTag(String tag) {
        sTag = (tag == null || tag.trim().isEmpty()) ? null : tag.trim();
    }

    /**
     * 读取当前标签（不清除）：翻页与切换筛选靠它保持同一检索模式。
     *
     * <p><b>永不返回 null</b>：调用方普遍写成 {@code tag.isEmpty()}（缓存 key、空态文案），
     * 早期版本这里返回 null 导致首页一启动就 NPE 崩溃（v1.5.111/112）。
     * 「没有标签」用空串表达，语义仍由 {@link #isTagMode()} 判定。
     */
    public static String currentTag() {
        String t = sTag;
        return t == null ? "" : t;
    }

    public static boolean isTagMode() {
        return sTag != null;
    }

    /** 退出标签检索模式（改输入框、点历史词、切数据源、清空筛选时调用） */
    public static void clear() {
        sTag = null;
    }

    /**
     * 旧的一次性语义，仅为兼容保留：读完即退出标签模式。
     * 新代码请用 {@link #currentTag()}——一次性语义会在翻页时丢模式。
     *
     * @deprecated 使用 {@link #currentTag()} 配合显式 {@link #clear()}
     */
    @Deprecated
    public static String consumeTag() {
        String t = sTag;
        sTag = null;
        return t;
    }
}
