package com.lateshow.app.util;

import com.lateshow.app.store.PrefStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 凭据相关的单一出口（审计 A-06 / A-08）。
 *
 * <p>只对外提供「指纹」与「掩码」，不提供明文 getter 以外的新出口；调用方需要真实 Token 时
 * 仍走 {@link PrefStore#apiToken()}（Keystore 解密），避免再产生第二份明文副本。
 *
 * <p>fingerprint 用于接口缓存命名空间：后端地址或 Token 变化后指纹立即变化，旧缓存自动不命中，
 * 从根上消除「换后端后命中上一主机数据」。
 */
public final class Credentials {

    private Credentials() { }

    public static String mask(String token) {
        return SecureStore.mask(token);
    }

    /** 缓存命名空间指纹：sha256(baseUrl + '|' + sha256(token)) 的前 16 位十六进制，不含任何明文 */
    public static String fingerprint() {
        try {
            String base = PrefStore.get().baseUrl();
            String token = PrefStore.get().apiToken();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] t = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder inner = new StringBuilder();
            for (byte b : t) inner.append(String.format("%02x", b & 0xff));
            // 未成年模式参与命名空间：开关切换后所有接口缓存整体失效，
            // 下一次请求必然拿到与开关一致口径的新数据（未成年=仅非 18+ 源站）
            String scope = base + "|" + inner + "|m" + (PrefStore.get().minorMode() ? 1 : 0);
            byte[] f = md.digest(scope.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format("%02x", f[i] & 0xff));
            return out.toString();
        } catch (Exception e) {
            return "nofp";
        }
    }

    /** 通用 SHA-256（十六进制），供更新器校验 digest 复用 */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
