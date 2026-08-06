package com.example.dq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 大模型接口默认配置(application.yml 的 ai.*);用户未在页面配置时作为兜底 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 接口地址(OpenAI 兼容,如 http://host:port/v1) */
    private String baseUrl;
    /** API Key(明文,仅本地部署使用) */
    private String apiKey;
    /** 模型名 */
    private String model;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
