package com.example.dq.model

/** AI 配置保存入参;apiKey 为空表示不修改已存的 key;价格字段为 null 表示保持已存值(未存过则回落默认价) */
data class AiConfigRequest(
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
    val peakValleyEnabled: Boolean? = null,
    val peakInputPrice: Double? = null,
    val peakOutputPrice: Double? = null,
    val valleyInputPrice: Double? = null,
    val valleyOutputPrice: Double? = null,
    /** 工作时间段(高峰),元素形如 "09:00-12:00";null 表示保持已存值 */
    val workPeriods: List<String>? = null,
    val weekendValley: Boolean? = null,
)
