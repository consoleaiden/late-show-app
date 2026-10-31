package com.lateshow.app.util;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 敏感配置（API Token）安全存储：用 Android Keystore 里的 AES-GCM 密钥加密后再落到 SharedPreferences，
 * 避免明文 token 被 root/备份/反编译直接读走。Keystore 不可用时降级为明文（保证功能不中断）。
 */
public final class SecureStore {

    private static final String ALIAS = "lateshow_secure_v1";
    private static final String PREFIX = "enc1:";
    private static final int IV_LEN = 12;

    private SecureStore() { }

    public static String get(SharedPreferences sp, String name) {
        String raw = sp.getString(name, "");
        if (raw == null || raw.isEmpty()) return "";
        if (!raw.startsWith(PREFIX)) return raw; // 兼容未加密写入的旧值
        try {
            byte[] data = Base64.decode(raw.substring(PREFIX.length()), Base64.NO_WRAP);
            if (data.length <= IV_LEN) return "";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, IV_LEN));
            return new String(c.doFinal(data, IV_LEN, data.length - IV_LEN), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static void put(SharedPreferences sp, String name, String value) {
        if (value == null || value.isEmpty()) {
            sp.edit().remove(name).apply();
            return;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            sp.edit().putString(name, PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            sp.edit().putString(name, value).apply();
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
