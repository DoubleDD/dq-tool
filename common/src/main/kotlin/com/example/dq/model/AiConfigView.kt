package com.example.dq.model

/**
 * AI 配置回显;apiKey 明文不回传,只给 hasKey 标记是否已配置。
 * available 表示合并默认配置后的有效配置是否完整(布尔,不泄露默认配置细节),
 * 前端据此决定「AI 自动打标」复选框的默认勾选。
 * 价格字段为合并默认值后的有效计费价格(非敏感,直接回显;未配置时即 DeepSeek 默认价),两档计费:
 * 工作时间(高峰)与 非工作时间(谷价);workPeriods 为工作时间段列表(元素 "HH:mm-HH:mm")。
 */
data class AiConfigView(
    val baseUrl: String?,
    val model: String?,
    val hasKey: Boolean,
    val available: Boolean,
    /** 是否启用峰谷计价(false 时只用单一输入/输出价,不显示工作/非工作时间两档) */
    val peakValleyEnabled: Boolean = true,
    /** 工作时间(高峰)输入价 / 单一输入价(元/百万 token) */
    val peakInputPrice: Double = 9.0,
    /** 工作时间(高峰)输出价 / 单一输出价(元/百万 token) */
    val peakOutputPrice: Double = 27.0,
    /** 非工作时间(谷价)输入价(元/百万 token) */
    val valleyInputPrice: Double = 4.5,
    /** 非工作时间(谷价)输出价(元/百万 token) */
    val valleyOutputPrice: Double = 13.5,
    /** 工作时间段(高峰),元素形如 "09:00-12:00" */
    val workPeriods: List<String> = listOf("09:00-12:00", "14:00-18:00"),
    /** 周末全天按谷价计费 */
    val weekendValley: Boolean = true,
)
