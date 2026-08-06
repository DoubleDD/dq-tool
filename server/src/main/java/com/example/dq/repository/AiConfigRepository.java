package com.example.dq.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** AI 大模型接口配置(单行,id 固定 1) */
@Repository
public class AiConfigRepository {

    /** api_key_enc 已加密存储,读取后由调用方解密 */
    public record AiConfigRow(String baseUrl, String apiKeyEnc, String model) {}

    private final JdbcTemplate jdbc;

    public AiConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AiConfigRow> get() {
        List<AiConfigRow> list = jdbc.query("SELECT * FROM ai_config WHERE id=1",
                (rs, i) -> new AiConfigRow(rs.getString("base_url"), rs.getString("api_key_enc"), rs.getString("model")));
        return list.stream().findFirst();
    }

    public void upsert(String baseUrl, String apiKeyEnc, String model) {
        int n = jdbc.update("UPDATE ai_config SET base_url=?, api_key_enc=?, model=?, updated_at=CURRENT_TIMESTAMP WHERE id=1",
                baseUrl, apiKeyEnc, model);
        if (n == 0) {
            jdbc.update("INSERT INTO ai_config(id, base_url, api_key_enc, model) VALUES (1,?,?,?)",
                    baseUrl, apiKeyEnc, model);
        }
    }
}
