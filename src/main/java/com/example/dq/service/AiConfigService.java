package com.example.dq.service;

import com.example.dq.config.AiProperties;
import com.example.dq.model.AiConfigRequest;
import com.example.dq.model.AiConfigView;
import com.example.dq.repository.AiConfigRepository;
import com.example.dq.util.CryptoUtil;
import org.springframework.stereotype.Service;

/**
 * AI 大模型接口配置:页面可视化配置,apiKey AES-GCM 加密存 H2。
 * 页面未配置的字段回落到 application.yml 的 ai.* 默认配置(逐字段生效)。
 */
@Service
public class AiConfigService {

    /**
     * 解密后的可用配置。
     * usingDefault=true 表示用户未配置(任一字段回落到了 application.yml 的默认值),
     * 此时对外不暴露任何默认配置细节,调用失败只提示用户自行配置。
     */
    public record Config(String baseUrl, String apiKey, String model, boolean usingDefault) {}

    private final AiConfigRepository repository;
    private final CryptoUtil crypto;
    private final AiProperties defaults;

    public AiConfigService(AiConfigRepository repository, CryptoUtil crypto, AiProperties defaults) {
        this.repository = repository;
        this.crypto = crypto;
        this.defaults = defaults;
    }

    /** 只回显用户自己的配置(H2),默认配置不回显、不暴露 */
    public AiConfigView get() {
        return repository.get()
                .map(r -> new AiConfigView(r.baseUrl(), r.model(), r.apiKeyEnc() != null && !r.apiKeyEnc().isEmpty()))
                .orElse(new AiConfigView(null, null, false));
    }

    /** apiKey 为空串/null 时保留已存 key */
    public void save(AiConfigRequest req) {
        String apiKeyEnc = repository.get()
                .map(AiConfigRepository.AiConfigRow::apiKeyEnc)
                .orElse(null);
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            apiKeyEnc = crypto.encrypt(req.apiKey().trim());
        }
        repository.upsert(trim(req.baseUrl()), apiKeyEnc, trim(req.model()));
    }

    /** 取合并默认配置后的可用配置;合并后仍不完整时抛异常(409 返回前端),不泄露默认值 */
    public Config requireConfig() {
        Config effective = effectiveConfig();
        if (effective.baseUrl() == null || effective.baseUrl().isBlank()
                || effective.model() == null || effective.model().isBlank()
                || effective.apiKey() == null || effective.apiKey().isBlank()) {
            throw new IllegalStateException("请先在「AI 配置」中填写完整的大模型接口信息(接口地址 / API Key / 模型)");
        }
        return new Config(effective.baseUrl().replaceAll("/+$", ""), effective.apiKey(), effective.model(),
                effective.usingDefault());
    }

    /** 逐字段合并:页面配置(H2)优先,空字段回落到 application.yml 的 ai.* 默认值 */
    private Config effectiveConfig() {
        AiConfigRepository.AiConfigRow row = repository.get().orElse(null);
        String baseUrl = row != null ? trim(row.baseUrl()) : null;
        String model = row != null ? trim(row.model()) : null;
        String apiKey = row != null && row.apiKeyEnc() != null && !row.apiKeyEnc().isEmpty()
                ? crypto.decrypt(row.apiKeyEnc()) : null;
        boolean usingDefault = false;
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = trim(defaults.getBaseUrl());
            usingDefault = true;
        }
        if (model == null || model.isBlank()) {
            model = trim(defaults.getModel());
            usingDefault = true;
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = trim(defaults.getApiKey());
            usingDefault = true;
        }
        return new Config(baseUrl, apiKey, model, usingDefault);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
