package com.example.dq.model

/**
 * 扫描任务 AI 后续(自动打标/生成表描述)进度:与扫描并行推进,按类别分别计数。
 * 计数在内存、重启清零(续扫会为 DONE 表补齐 AI 后续并重新计数);
 * 对应开关关闭或入队前已熔断的类别不会有任务入队,总数恒 0(前端据此显示「未启用」)。
 */
data class ScanAiProgress(
    val tagTotal: Int,  // AI 打标已入队数
    val tagDone: Int,   // AI 打标已完成数(成功/跳过/失败都算完成)
    val docTotal: Int,  // 表描述已入队数
    val docDone: Int,   // 表描述已完成数
)
