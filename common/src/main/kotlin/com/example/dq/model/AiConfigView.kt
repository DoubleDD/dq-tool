package com.example.dq.model

/**
 * AI 配置回显;apiKey 明文不回传,只给 hasKey 标记是否已配置。
 * available 表示合并默认配置后的有效配置是否完整(布尔,不泄露默认配置细节),
 * 前端据此决定「AI 自动打标」复选框的默认勾选。
 */
data class AiConfigView(val baseUrl: String?, val model: String?, val hasKey: Boolean, val available: Boolean)
