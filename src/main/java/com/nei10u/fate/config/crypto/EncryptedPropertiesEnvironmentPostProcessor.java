package com.nei10u.fate.config.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 在应用启动的“环境准备阶段”解密敏感配置，并覆盖到最高优先级 PropertySource。
 *
 * 当前仅处理：spring.ai.openai.api-key
 *
 * 使用方式：
 * 1) 设置密钥：环境变量 FATE_CONFIG_SECRET（建议至少 32 字符随机串）
 * 2) 将 spring.ai.openai.api-key 设置为 ENC(v1:...) 形式（可写在 env 或
 * application.properties）
 */
public class EncryptedPropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EncryptedPropertiesEnvironmentPostProcessor.class);

    public static final String TARGET_KEY = "spring.ai.openai.api-key";
    public static final String PROPERTY_SOURCE_NAME = "fateDecryptedSecrets";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String current = environment.getProperty(TARGET_KEY);
        if (current == null || current.isBlank()) {
            return;
        }
        if (!ConfigCrypto.looksEncrypted(current)) {
            return;
        }

        String secret = "test123456";
        // if (secret == null || secret.isBlank()) {
        // log.error("检测到 {} 为密文，但未设置 {}，无法解密。", TARGET_KEY, SECRET_ENV);
        // return;
        // }

        try {
            String decrypted = ConfigCrypto.decryptEnc(secret, current);
            Map<String, Object> map = new HashMap<>();
            map.put(TARGET_KEY, decrypted);
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, map));
            log.info("已解密并注入配置：{}", TARGET_KEY);
        } catch (Exception e) {
            log.error("解密 {} 失败: {}", TARGET_KEY, e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        // 必须在 ConfigDataEnvironmentPostProcessor（负责加载
        // application.properties）之后执行，否则读不到配置项。
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
