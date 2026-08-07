package com.example.dq.repository

/** AI 大模型接口配置(单行,id 固定 1) */
class AiConfigRepository(private val jdbc: Jdbc) {

    /** api_key_enc 已加密存储,读取后由调用方解密 */
    data class AiConfigRow(val baseUrl: String?, val apiKeyEnc: String?, val model: String?)

    fun get(): AiConfigRow? =
        jdbc.queryOne("SELECT * FROM ai_config WHERE id=1") { rs ->
            AiConfigRow(rs.getString("base_url"), rs.getString("api_key_enc"), rs.getString("model"))
        }

    fun upsert(baseUrl: String, apiKeyEnc: String, model: String) {
        val n = jdbc.update("UPDATE ai_config SET base_url=?, api_key_enc=?, model=?, updated_at=CURRENT_TIMESTAMP WHERE id=1",
            baseUrl, apiKeyEnc, model)
        if (n == 0) {
            jdbc.update("INSERT INTO ai_config(id, base_url, api_key_enc, model) VALUES (1,?,?,?)",
                baseUrl, apiKeyEnc, model)
        }
    }
}
