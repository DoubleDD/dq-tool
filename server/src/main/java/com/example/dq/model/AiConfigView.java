package com.example.dq.model;

/** AI 配置回显;apiKey 明文不回传,只给 hasKey 标记是否已配置 */
public record AiConfigView(String baseUrl, String model, boolean hasKey) {
}
