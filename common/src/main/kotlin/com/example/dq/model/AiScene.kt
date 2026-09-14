package com.example.dq.model

/** AI 调用场景(用量统计按场景归类展示) */
enum class AiScene(val label: String) {
    /** 表说明(手动生成 + 扫描后自动生成) */
    TABLE_DOC("表说明"),
    /** 扫描后 AI 自动打标 */
    AUTO_TAG("自动打标"),
    /** Word 报告分析文字生成 */
    WORD_REPORT("报告分析"),
    /** ER 关系推导·语义匹配(表级粗筛 + 字段级精判) */
    RELATION_INFER("关系推导"),
    /** 数据比对·匹配逻辑 3:编码与名称都没配上的残余对象交大模型归一化配对 */
    COMPARE_MATCH("比对匹配"),
    /** 「AI 配置」连通性测试 */
    TEST("连通测试"),
    ;

    companion object {
        fun labelOf(name: String?): String = name?.let { runCatching { valueOf(it).label }.getOrNull() } ?: name ?: "-"
    }
}
