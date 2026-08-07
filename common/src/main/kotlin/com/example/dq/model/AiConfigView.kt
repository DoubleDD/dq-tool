package com.example.dq.model

/** AI 配置回显;apiKey 明文不回传,只给 hasKey 标记是否已配置 */
data class AiConfigView(val baseUrl: String?, val model: String?, val hasKey: Boolean)
