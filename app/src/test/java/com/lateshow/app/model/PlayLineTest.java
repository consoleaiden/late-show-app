package com.lateshow.app.model;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * {@link Models.PlayLine#stableKey(int)} 的纯 JVM 单元测试。
 * 不引用任何 Android API，直接在本地 JVM 运行（需 testImplementation 'junit:junit:4.13.2'）。
 */
public class PlayLineTest {

    @Test
    public void stableKeyReturnsPositionWhenOriginalIndexIsNull() {
        Models.PlayLine line = new Models.PlayLine();
        line.originalIndex = null;
        assertEquals(5, line.stableKey(5));
    }

    @Test
    public void stableKeyReturnsZeroWhenOriginalIndexIsZero() {
        Models.PlayLine line = new Models.PlayLine();
        line.originalIndex = 0;
        // 0 是合法下标，不能被当成「缺失」而回退到 position
        assertEquals(0, line.stableKey(9));
    }

    @Test
    public void stableKeyPrefersOriginalIndexOverPosition() {
        Models.PlayLine line = new Models.PlayLine();
        line.originalIndex = 7;
        assertEquals(7, line.stableKey(1));
    }

    @Test
    public void stableKeyFallsBackToPositionWhenOriginalIndexIsNegative() {
        Models.PlayLine line = new Models.PlayLine();
        line.originalIndex = -1;
        assertEquals(2, line.stableKey(2));
    }
}
