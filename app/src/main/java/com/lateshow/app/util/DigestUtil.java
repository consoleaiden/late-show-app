package com.lateshow.app.util;

import java.security.MessageDigest;

/**
 * 摘要工具（纯 Java，无 Android 依赖，可直接单测）。
 *
 * <p>存在的理由：{@link Credentials#sha256Hex(byte[])} 是「对入参算 SHA-256 再转十六进制」，
 * 而更新校验拿到的是 {@code MessageDigest.digest()} 的<b>结果</b>，再喂进去就成了
 * {@code sha256(sha256(file))} —— v1.5.114/115 的「SHA-256 校验失败，安装包可能被篡改」
 * 就是这一行写错导致的（每个线路下的都是正确文件，却全部校验不过）。
 */
public final class DigestUtil {

    private DigestUtil() { }

    /** 字节数组 → 小写十六进制字符串（不重新计算摘要） */
    public static String hex(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** 对入参本身算 SHA-256 并转十六进制（用于证书指纹这类原始字节） */
    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "";
        }
    }
}
