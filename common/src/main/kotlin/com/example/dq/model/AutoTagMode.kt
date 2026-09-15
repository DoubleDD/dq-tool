package com.example.dq.model

/**
 * AI 自动打标对「已有标记的表」的处理模式(`scan_job.auto_tag_mode` 持久化,断点续扫仍生效)。
 * - SKIP:维持老行为,已有任一 USER 标记的表跳过不打;
 * - APPEND:不跳过,模型选出的标记幂等追加,旧标记全部保留;
 * - OVERWRITE:不跳过,先清掉该表全部 AI 来源旧标记(不碰 MANUAL/SYSTEM),再按模型回答落新标记,回答 NONE 即覆盖为空。
 */
enum class AutoTagMode {
    SKIP, APPEND, OVERWRITE;

    companion object {
        /** 解析请求/落库值:空白与非法值一律按 SKIP(向后兼容老任务与旧导出文件) */
        @JvmStatic
        fun parse(value: String?): AutoTagMode =
            value?.trim()?.uppercase()?.let { runCatching { valueOf(it) }.getOrNull() } ?: SKIP
    }
}
