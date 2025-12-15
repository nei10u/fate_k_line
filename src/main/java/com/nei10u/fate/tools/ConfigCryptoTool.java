package com.nei10u.fate.tools;

import com.nei10u.fate.config.crypto.ConfigCrypto;

/**
 * 本地生成/验证配置密文的工具（避免你手写 ENC(...)）。
 *
 * 用法（推荐用环境变量注入密钥）：
 * export FATE_CONFIG_SECRET='your-long-random-secret'
 * mvn -q -DskipTests package
 * java -cp target/classes com.nei10u.fate.tools.ConfigCryptoTool encrypt
 * 'sk-...'
 * java -cp target/classes com.nei10u.fate.tools.ConfigCryptoTool decrypt
 * 'ENC(v1:...)'
 */
public class ConfigCryptoTool {

    public static void main(String[] args) {
//        if (args == null || args.length < 2) {
//            System.err.println("用法: ConfigCryptoTool <encrypt|decrypt> <value>");
//            System.exit(2);
//        }
//
//        String mode = args[0];
//        String value = args[1];
        String secret = "test123456";
        if (secret == null || secret.isBlank()) {
            System.err.println("缺少环境变量 FATE_CONFIG_SECRET");
            System.exit(3);
        }

//        if ("encrypt".equalsIgnoreCase(mode)) {
            System.out.println(ConfigCrypto.encryptToEnc(secret, "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"));
            return;
//        }
//        if ("decrypt".equalsIgnoreCase(mode)) {
//            System.out.println(ConfigCrypto.decryptEnc(secret, value));
//            return;
//        }
//
//        System.err.println("未知模式: " + mode + "，仅支持 encrypt / decrypt");
//        System.exit(4);
    }
}
