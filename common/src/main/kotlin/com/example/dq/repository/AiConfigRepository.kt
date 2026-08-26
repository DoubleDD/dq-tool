package com.example.dq.repository

/** AI 大模型接口配置(单行,id 固定 1);价格列为 NULL 表示使用配置文件/内置 DeepSeek 默认价 */
class AiConfigRepository(private val jdbc: Jdbc) {

    /** api_key_enc 已加密存储,读取后由调用方解密;workPeriods 为 "HH:mm-HH:mm,..." 字符串 */
    data class AiConfigRow(
        val baseUrl: String?,
        val apiKeyEnc: String?,
        val model: String?,
        val peakValleyEnabled: Boolean?,
        val peakInputPrice: Double?,
        val peakOutputPrice: Double?,
        val valleyInputPrice: Double?,
        val valleyOutputPrice: Double?,
        val workPeriods: String?,
        val weekendValley: Boolean?,
    )

    fun get(): AiConfigRow? =
        jdbc.queryOne("SELECT * FROM ai_config WHERE id=1") { rs ->
            AiConfigRow(
                rs.getString("base_url"),
                rs.getString("api_key_enc"),
                rs.getString("model"),
                rs.getBoolean("peak_valley_enabled").takeIf { !rs.wasNull() },
                rs.getDouble("peak_input_price").takeIf { !rs.wasNull() },
                rs.getDouble("peak_output_price").takeIf { !rs.wasNull() },
                rs.getDouble("valley_input_price").takeIf { !rs.wasNull() },
                rs.getDouble("valley_output_price").takeIf { !rs.wasNull() },
                rs.getString("work_periods"),
                rs.getBoolean("weekend_valley").takeIf { !rs.wasNull() },
            )
        }

    /** 价格字段传 null 表示保持已存值(未存过则回落默认价) */
    fun upsert(
        baseUrl: String,
        apiKeyEnc: String,
        model: String,
        peakValleyEnabled: Boolean?,
        peakInputPrice: Double?,
        peakOutputPrice: Double?,
        valleyInputPrice: Double?,
        valleyOutputPrice: Double?,
        workPeriods: String?,
        weekendValley: Boolean?,
    ) {
        // 单行表:先读旧值,价格字段 null 时沿用旧值(与 apiKey 的「留空不修改」语义一致)
        val old = get()
        val enabled = peakValleyEnabled ?: old?.peakValleyEnabled
        val pInput = peakInputPrice ?: old?.peakInputPrice
        val pOutput = peakOutputPrice ?: old?.peakOutputPrice
        val vInput = valleyInputPrice ?: old?.valleyInputPrice
        val vOutput = valleyOutputPrice ?: old?.valleyOutputPrice
        val wPeriods = workPeriods ?: old?.workPeriods
        val wv = weekendValley ?: old?.weekendValley
        val n = jdbc.update(
            """UPDATE ai_config SET base_url=?, api_key_enc=?, model=?,
               peak_valley_enabled=?, peak_input_price=?, peak_output_price=?,
               valley_input_price=?, valley_output_price=?, work_periods=?, weekend_valley=?,
               updated_at=CURRENT_TIMESTAMP WHERE id=1""",
            baseUrl, apiKeyEnc, model, enabled, pInput, pOutput, vInput, vOutput, wPeriods, wv
        )
        if (n == 0) {
            jdbc.update(
                """INSERT INTO ai_config(id, base_url, api_key_enc, model,
                   peak_valley_enabled, peak_input_price, peak_output_price,
                   valley_input_price, valley_output_price, work_periods, weekend_valley)
                   VALUES (1,?,?,?,?,?,?,?,?,?,?)""",
                baseUrl, apiKeyEnc, model, enabled, pInput, pOutput, vInput, vOutput, wPeriods, wv
            )
        }
    }
}
