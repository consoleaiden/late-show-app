package com.lateshow.app.util;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 敏感配置（API Token）安全存储：Android Keystore 里的 AES-GCM 密钥加密后落到 SharedPreferences。
 *
 * 审计 A-05/A-06：不再静默降级。
 * <ul>
 *   <li>加密失败：删除该键并抛 {@link SecureStoreException}，绝不写入明文；</li>
 *   <li>解密失败：抛异常并把原因记入 {@link #lastError()}，绝不静默返回空串
 *       （旧实现会让全 App 突然 401 且无任何线索）；</li>
 *   <li>只在「读取到没有 enc1: 前缀的旧值」时兼容升级前的明文，不产生新的明文副本。</li>
 * </ul>
 * 另提供 {@link #encryptForStorage(String)} / {@link #decryptFromStorage(String)}，
 * 供 {@code saved_sources} 里的逐条 Token 加密复用同一密钥与格式。
 */
public final class SecureStore {

    private static final String ALIAS = "lateshow_secure_v1";
    private static final String PREFIX = "enc1:";
    private static final int IV_LEN = 12;

    private static volatile String sLastError = null;

    private SecureStore() { }

    public static final class SecureStoreException extends RuntimeException {
        public SecureStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static String lastError() {
        return sLastError;
    }

    public static void clearError() {
        sLastError = null;
    }

    public static String get(SharedPreferences sp, String name) {
        String raw = sp.getString(name, "");
        if (raw == null || raw.isEmpty()) return "";
        if (!raw.startsWith(PREFIX)) return raw; // 兼容升级前写入的明文旧值
        try {
            return decryptFromStorage(raw);
        } catch (SecureStoreException e) {
            sLastError = e.getMessage();
            AppLog.e("SecureStore", "解密失败：" + sLastError);
            throw e;
        }
    }

    public static void put(SharedPreferences sp, String name, String value) {
        if (value == null || value.isEmpty()) {
            sp.edit().remove(name).apply();
            clearError();
            return;
        }
        try {
            String enc = encryptForStorage(value);
            sp.edit().putString(name, enc).apply();
            clearError();
        } catch (SecureStoreException e) {
            // 绝不降级为明文：宁可让用户重新填写，也不把凭据写进可读的 SP
            sp.edit().remove(name).apply();
            sLastError = e.getMessage();
            AppLog.e("SecureStore", "加密失败，已拒绝写入明文：" + sLastError);
            throw e;
        }
    }

    /** 静态加密：返回 "enc1:" + base64(iv || ciphertext)，与 api_token 主副本同格式 */
    public static String encryptForStorage(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new SecureStoreException("Keystore 加密不可用：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 静态解密；密文损坏 / 密钥丢失时抛异常，不返回猜测值 */
    public static String decryptFromStorage(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored; // 旧明文
        try {
            byte[] data = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (data.length <= IV_LEN) throw new IllegalArgumentException("密文过短");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, IV_LEN));
            return new String(c.doFinal(data, IV_LEN, data.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecureStoreException("凭据无法解密（密钥可能已随系统重置），请重新填写 API Token", e);
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        java.security.Key k = ks.getKey(ALIAS, null);
        if (k instanceof SecretKey) return (SecretKey) k;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    /** 日志脱敏：vs_HYgS…u51c */
    public static String mask(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.length() <= 12) return t.isEmpty() ? "" : t.charAt(0) + "***";
        return t.substring(0, 7) + "…" + t.substring(t.length() - 4);
    }
}
