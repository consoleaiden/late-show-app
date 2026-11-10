package com.lateshow.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.security.MessageDigest;

/**
 * 摘要工具回归。
 *
 * <p>重点锁住 v1.5.114/115 的线上故障：更新校验把 {@code MessageDigest.digest()} 的
 * <b>结果</b>又喂回「算摘要」的函数，等于 {@code sha256(sha256(file))}，于是四条线路
 * 下的都是正确文件却全部报「SHA-256 校验失败，安装包可能被篡改」。
 */
public class DigestUtilTest {

    /** 标准测试向量：sha256("abc") */
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void sha256HexMatchesKnownVector() {
        assertEquals(ABC_SHA256, DigestUtil.sha256Hex("abc".getBytes()));
    }

    @Test
    public void hexFormatsWithoutRehashing() throws Exception {
        // 摘要结果 → 十六进制，必须与 MessageDigest 计算出的值逐位相同
        byte[] raw = MessageDigest.getInstance("SHA-256").digest("abc".getBytes());
        assertEquals(ABC_SHA256, DigestUtil.hex(raw));
        // 32 字节摘要 → 64 个十六进制字符
        assertEquals(64, DigestUtil.hex(raw).length());
    }

    /** 双重摘要一定不等于单次摘要：这正是旧实现与期望值对不上的原因 */
    @Test
    public void doubleHashingWouldNeverMatch() throws Exception {
        byte[] raw = MessageDigest.getInstance("SHA-256").digest("abc".getBytes());
        String single = DigestUtil.hex(raw);
        String doubled = DigestUtil.sha256Hex(raw);
        assertEquals(ABC_SHA256, single);
        assertNotEquals("把摘要再算一次摘要，结果必然不同——这就是校验永远失败的原因",
                single, doubled);
    }

    @Test
    public void emptyInputIsEmptyString() {
        assertEquals("", DigestUtil.hex(null));
        assertEquals("", DigestUtil.hex(new byte[0]));
    }

    @Test
    public void lowerCaseHex() {
        assertEquals("0f", DigestUtil.hex(new byte[]{0x0f}));
        assertEquals("ff", DigestUtil.hex(new byte[]{(byte) 0xff}));
    }
}
