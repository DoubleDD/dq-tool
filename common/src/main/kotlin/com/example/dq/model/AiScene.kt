package com.example.dq.model

/** AI 调用场景(用量统计按场景归类展示) */
enum class AiScene(val label: String) {
    /** 表说明(手动生成 + 扫描后自动生成) */
    TABLE_DOC("表说明"),
    /** 扫描后 AI 自动打标 */
    AUTO_TAG("自动打标"),
    /** Word 报告分析文字生成 */
    WORD_REPORT("报告分析"),
    /** 「AI 配置」连通性测试 */
    TEST("连通测试"),
    ;

    companion object {
        fun labelOf(name: String?): String = name?.let { runCatching { valueOf(it).label }.getOrNull() } ?: name ?: "-"
    }
}
