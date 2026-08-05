package com.example.dq.service;

import com.example.dq.model.AiConfigRequest;
import com.example.dq.model.AiConfigView;
import com.example.dq.repository.AiConfigRepository;
import com.example.dq.util.CryptoUtil;
import org.springframework.stereotype.Service;

/** AI 大模型接口配置:页面可视化配置,apiKey AES-GCM 加密存 H2 */
@Service
public class AiConfigService {

    /** 解密后的可用配置 */
    public record Config(String baseUrl, String apiKey, String model) {}

    private final AiConfigRepository repository;
    private final CryptoUtil crypto;

    public AiConfigService(AiConfigRepository repository, CryptoUtil crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

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

    /** 取解密后的配置;未配置或信息不全时抛异常(409 返回前端) */
    public Config requireConfig() {
        AiConfigRepository.AiConfigRow row = repository.get()
                .filter(r -> r.baseUrl() != null && !r.baseUrl().isBlank()
                        && r.model() != null && !r.model().isBlank()
                        && r.apiKeyEnc() != null && !r.apiKeyEnc().isEmpty())
                .orElseThrow(() -> new IllegalStateException("请先在「AI 配置」中填写完整的大模型接口信息(接口地址 / API Key / 模型)"));
        return new Config(row.baseUrl().replaceAll("/+$", ""), crypto.decrypt(row.apiKeyEnc()), row.model());
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
