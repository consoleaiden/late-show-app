package com.lateshow.app.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 检索模式语义（M5，2026-11-10 与后端对齐）。
 *
 * <p>锁住两条不能再改回去的约定：
 * <ol>
 *   <li>标签模式是<b>粘性</b>的——翻页时 {@code currentTag()} 仍要返回同一个词，
 *       否则会出现「第 1 页标签结果、第 2 页全文结果」；</li>
 *   <li>搜索框输入（{@link #clear()}）能可靠退出标签模式。</li>
 * </ol>
 */
public class SearchIntentTest {

    @Before
    public void setUp() {
        SearchIntent.clear();
    }

    @After
    public void tearDown() {
        SearchIntent.clear();
    }

    @Test
    public void tagModeStaysForPaging() {
        SearchIntent.setTag("巨乳");
        assertTrue(SearchIntent.isTagMode());
        assertEquals("巨乳", SearchIntent.currentTag());
        // 翻页会再次读取：必须还在，不能被「消费」掉
        assertEquals("巨乳", SearchIntent.currentTag());
        assertEquals("巨乳", SearchIntent.currentTag());
    }

    @Test
    public void clearLeavesTagMode() {
        SearchIntent.setTag("夜店");
        SearchIntent.clear();
        assertFalse(SearchIntent.isTagMode());
        assertNull(SearchIntent.currentTag());
    }

    @Test
    public void blankTagIsNotATagMode() {
        SearchIntent.setTag("   ");
        assertFalse(SearchIntent.isTagMode());
        SearchIntent.setTag(null);
        assertFalse(SearchIntent.isTagMode());
    }

    @Test
    public void tagIsTrimmed() {
        SearchIntent.setTag("  巨乳  ");
        assertEquals("巨乳", SearchIntent.currentTag());
    }

    /** 旧的一次性语义仍在，但读完即退出——新代码不该用它 */
    @Test
    public void legacyConsumeExitsTagMode() {
        SearchIntent.setTag("巨乳");
        assertEquals("巨乳", SearchIntent.consumeTag());
        assertFalse(SearchIntent.isTagMode());
    }
}
