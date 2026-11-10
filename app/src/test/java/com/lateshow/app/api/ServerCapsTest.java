package com.lateshow.app.api;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

/**
 * 能力门控语义的单测（B-10）。
 *
 * <p>核心约束：服务端下发了 capabilities 就**只能**以它为准。否则后端把
 * {@code tag_search} 摘掉（索引覆盖不足时的兜底手段）会被版本号兜底逻辑架空，
 * 客户端照样发 {@code ?tag=}，用户继续搜不到东西。
 */
public class ServerCapsTest {

    @After
    public void tearDown() {
        ServerCaps.update("", null);   // 静态状态，逐个用例后复位
    }

    @Test
    public void explicitCapabilityEnables() {
        ServerCaps.update("1.9.0", new HashSet<>(Arrays.asList("tag_search")));
        assertTrue(ServerCaps.supports(ServerCaps.CAP_TAG_SEARCH));
        assertTrue(ServerCaps.hasCapabilityList());
    }

    @Test
    public void absentCapabilityDisablesEvenWhenVersionIsHigh() {
        // 版本够、但清单里明确没有 → 必须判为不支持（后端摘掉能力的场景）
        ServerCaps.update("1.14.1", new HashSet<>(Arrays.asList("paging_v2", "error_codes")));
        assertFalse(ServerCaps.supports(ServerCaps.CAP_TAG_SEARCH));
    }

    @Test
    public void missingCapabilityListFallsBackToVersion() {
        // 老服务端不返回 capabilities：按最低版本推断，保持向后兼容
        ServerCaps.update("1.14.0", null);
        assertFalse(ServerCaps.hasCapabilityList());
        assertTrue(ServerCaps.supports(ServerCaps.CAP_TAG_SEARCH));
    }

    @Test
    public void missingCapabilityListAndOldVersionDisables() {
        ServerCaps.update("1.13.9", null);
        assertFalse(ServerCaps.supports(ServerCaps.CAP_TAG_SEARCH));
    }

    @Test
    public void unknownCapabilityIsNeverSupported() {
        ServerCaps.update("9.9.9", null);
        assertFalse(ServerCaps.supports("something_new"));
        assertFalse(ServerCaps.supports(null));
    }

    @Test
    public void versionCompareIgnoresSuffix() {
        assertEquals(0, ServerCaps.compare("1.14.0-rc1", "1.14.0"));
        assertTrue(ServerCaps.compare("1.14.10", "1.14.9") > 0);
        assertTrue(ServerCaps.compare("1.9.0", "1.14.0") < 0);
    }
}
