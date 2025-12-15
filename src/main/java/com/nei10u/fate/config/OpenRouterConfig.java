package com.nei10u.fate.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class OpenRouterConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Value("${spring.ai.openai.chat.options.headers.HTTP-Referer}")
    private String httpReferer;

    @Value("${spring.ai.openai.chat.options.headers.X-Title}")
    private String xTitle;

    @Bean
    @Primary
    public OpenAiApi openAiApi() {
        // 明确指定路径以避免HTTP 405错误
        return new OpenAiApi.Builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions") // 明确指定 completions 路径
                .embeddingsPath("/embeddings") // 明确指定 embeddings 路径
                .build();
    }

    @Bean
    @Primary
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi) {
        // 创建OpenAiChatOptions实例，包含模型和自定义头部
        Map<String, String> httpHeaders = new HashMap<>();
        if (StringUtils.hasText(httpReferer)) {
            httpHeaders.put("HTTP-Referer", httpReferer);
        }
        if (StringUtils.hasText(xTitle)) {
            httpHeaders.put("X-Title", xTitle);
        }

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .httpHeaders(httpHeaders)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}