package com.nei10u.fate.config.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 配置加解密工具（面向“配置文件/环境变量里的敏感字段”场景）。
 *
 * 密文格式：
 *   ENC(v1:<base64url>)
 *
 * 其中 base64url 解码后的字节布局：
 *   [1 byte version=1][16 bytes salt][12 bytes iv][ciphertext+tag...]
 */
public final class ConfigCrypto {

    public static final String ENC_PREFIX = "ENC(";
    public static final String ENC_SUFFIX = ")";

    private static final byte VERSION_V1 = 1;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERS = 120_000;
    private static final int GCM_TAG_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ConfigCrypto() {
    }

    public static boolean looksEncrypted(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.startsWith(ENC_PREFIX) && v.endsWith(ENC_SUFFIX);
    }

    public static String unwrapEncrypted(String encValue) {
        String v = Objects.requireNonNull(encValue, "encValue").trim();
        if (!looksEncrypted(v)) {
            throw new IllegalArgumentException("不是 ENC(...) 格式密文");
        }
        return v.substring(ENC_PREFIX.length(), v.length() - ENC_SUFFIX.length()).trim();
    }

    public static String encryptToEnc(String secret, String plaintext) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("缺少配置解密密钥（secret 不能为空）");
        }
        if (plaintext == null) {
            plaintext = "";
        }
        try {
            byte[] salt = new byte[SALT_LEN];
            SECURE_RANDOM.nextBytes(salt);
            byte[] iv = new byte[IV_LEN];
            SECURE_RANDOM.nextBytes(iv);

            SecretKey key = deriveKey(secret, salt);
            byte[] cipher = aesGcmEncrypt(key, iv, plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(1 + SALT_LEN + IV_LEN + cipher.length);
            buf.put(VERSION_V1);
            buf.put(salt);
            buf.put(iv);
            buf.put(cipher);

            String payload = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
            return ENC_PREFIX + payload + ENC_SUFFIX;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("配置加密失败: " + e.getMessage(), e);
        }
    }

    public static String decryptEnc(String secret, String encValue) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("缺少配置解密密钥（secret 不能为空）");
        }
        String payload = unwrapEncrypted(encValue);
        if (!payload.startsWith("v1:")) {
            throw new IllegalArgumentException("不支持的密文版本: " + payload);
        }
        String b64 = payload.substring("v1:".length()).trim();
        try {
            byte[] all = Base64.getUrlDecoder().decode(b64);
            ByteBuffer buf = ByteBuffer.wrap(all);

            byte version = buf.get();
            if (version != VERSION_V1) {
                throw new IllegalArgumentException("不支持的密文版本号: " + version);
            }

            byte[] salt = new byte[SALT_LEN];
            buf.get(salt);
            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            byte[] cipher = new byte[buf.remaining()];
            buf.get(cipher);

            SecretKey key = deriveKey(secret, salt);
            byte[] plain = aesGcmDecrypt(key, iv, cipher);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("配置解密失败（密钥不匹配或密文被篡改）: " + e.getMessage(), e);
        }
    }

    private static SecretKey deriveKey(String secret, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERS, KEY_BITS);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = skf.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] aesGcmEncrypt(SecretKey key, byte[] iv, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plaintext);
    }

    private static byte[] aesGcmDecrypt(SecretKey key, byte[] iv, byte[] ciphertext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }
}


