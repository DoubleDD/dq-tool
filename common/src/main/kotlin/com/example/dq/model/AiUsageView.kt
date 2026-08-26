package com.example.dq.model

/** AI 用量统计:汇总指标 */
data class AiUsageSummary(
    val calls: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val cost: Double,
)

/** AI 用量统计:单日聚合(日期 yyyy-MM-dd,无调用的天补零) */
data class AiUsageDay(
    val date: String,
    val calls: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val cost: Double,
    /** 输入/输出分项费用;老流水无分项数据时为 null(前端不展示分项金额) */
    val promptCost: Double? = null,
    val completionCost: Double? = null,
)

/** AI 用量统计:按场景聚合 */
data class AiUsageScene(
    val scene: String,
    val label: String,
    val calls: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val cost: Double,
)

/** AI 用量统计页数据:汇总 + 每日序列 + 场景分布 */
data class AiUsageStatsView(
    val summary: AiUsageSummary,
    val series: List<AiUsageDay>,
    val scenes: List<AiUsageScene>,
)

/** AI 用量明细(最近调用列表) */
data class AiUsageLogView(
    val id: Long,
    val scene: String,
    val sceneLabel: String,
    val model: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val cost: Double,
    /** PEAK(峰)/ VALLEY(谷) */
    val period: String,
    val createdAt: String,
)
