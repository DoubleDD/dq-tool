package com.example.dq.model

/**
 * 批量 AI 打标(表列表「批量打标」弹窗 AI 页签)单表结果:
 * - applied=true:本次新打上标记,tagName 为模型选中的标记名
 * - skipped=true:跳过(备份表 / 表已有同标记),reason 说明原因,tagName 可能为已命中的标记名
 * - 其余:模型未匹配到合适标记(NONE 或幻觉标记),reason 说明,不打标
 */
data class BatchAiTagResult(
    val applied: Boolean = false,
    val tagName: String? = null,
    val skipped: Boolean = false,
    val reason: String? = null,
)
