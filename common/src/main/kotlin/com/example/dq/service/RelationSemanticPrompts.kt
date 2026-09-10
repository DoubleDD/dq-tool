package com.example.dq.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * ER 关系推导·语义匹配(M2)的 prompt 组装与响应解析,全部为纯函数便于单测。
 * 两阶段打法(讨论稿决策 2,原料逐级回退):
 * 阶段一·表级粗筛——锚点表 vs 全库表清单分批给 LLM;每张表有 AI 描述(table_doc)用描述,
 *   无描述回退表注释(meta_table.comment),两者皆无该表不进 prompt(名字匹配仍可能命中它);
 * 阶段二·字段级精判——粗筛出的每张表,字段清单(名+类型+注释;注释为空的字段不进 prompt)
 *   与锚点字段发给 LLM,返回字段对应关系。
 * 解析容错:LLM 回答允许带多余文字,抽取首个 JSON 数组;坏 JSON/无有效条目返回空,不抛异常。
 */
object RelationSemanticPrompts {

    private val mapper = jacksonObjectMapper()

    /** 阶段一·表级原料:有描述用描述,无描述回退表注释,皆无则不进 prompt */
    data class TableMaterial(val tableName: String, val comment: String?, val doc: String?)

    /** 阶段二·字段原料:字段名+类型+注释;aliases 为锚点字段的用户手填映射名(仅锚点侧有,prompt 中作常见名提示) */
    data class ColumnMaterial(val columnName: String, val colType: String?, val comment: String?,
                              val aliases: List<String> = emptyList())

    const val TABLE_SCREEN_SYSTEM_PROMPT =
        "你是数据库专家。根据锚点表的业务含义,从候选表清单中挑出与锚点表可能存在业务关联(如外键引用、主从、字典对照)的表。" +
                "只输出 JSON,不要解释。"

    const val COLUMN_MATCH_SYSTEM_PROMPT =
        "你是数据库专家。根据字段名与字段注释的语义,判断两张表的字段之间哪些存在关联(一方引用另一方的业务编码/标识)。" +
                "只输出 JSON,不要解释。"

    // ---------- 阶段一:表级粗筛 ----------

    /** 拼表级粗筛 prompt:锚点表信息 + 候选表清单(按回退规则取描述/注释,皆无的表不出现);纯函数 */
    @JvmStatic
    fun buildTableScreenPrompt(anchorTable: String, anchorComment: String?, anchorDoc: String?,
                               tables: List<TableMaterial>): String {
        val sb = StringBuilder()
        sb.append("锚点表:").append(anchorTable).append('\n')
        if (!anchorComment.isNullOrBlank()) {
            sb.append("表注释:").append(anchorComment.trim()).append('\n')
        }
        if (!anchorDoc.isNullOrBlank()) {
            sb.append("表描述:").append(anchorDoc.trim()).append('\n')
        }
        sb.append("\n候选表(表名 — 描述或注释):\n")
        for (t in tables) {
            val text = t.doc?.takeIf { it.isNotBlank() }?.trim()
                ?: t.comment?.takeIf { it.isNotBlank() }?.trim()
                ?: continue // 描述/注释皆无:该表在语义通道出局,不进 prompt
            sb.append("- ").append(t.tableName).append(" — ").append(text).append('\n')
        }
        sb.append("\n请输出与锚点表可能相关的表名 JSON 数组,如 [\"flood_ctrl\",\"basin\"];" +
                "没有相关的输出 []。只输出 JSON 数组本身,不要输出其他内容。")
        return sb.toString()
    }

    /** 阶段一是否值得调用 LLM:回退规则下至少有一张表能进 prompt */
    @JvmStatic
    fun hasTableScreenMaterial(tables: List<TableMaterial>): Boolean =
        tables.any { !it.doc.isNullOrBlank() || !it.comment.isNullOrBlank() }

    /**
     * 解析表级粗筛回答:抽取 JSON 字符串数组,过滤回候选范围内的实际表名(大小写不敏感归一);
     * 带多余文字/坏 JSON 容错返回空列表,不抛异常
     */
    @JvmStatic
    fun parseRelatedTables(answer: String, validTables: Collection<String>): List<String> {
        val names = parseStringArray(answer) ?: return emptyList()
        val byLower = validTables.associateBy { it.lowercase() }
        val result = LinkedHashSet<String>()
        for (n in names) {
            val actual = byLower[n.trim().lowercase()]
            if (actual != null) result.add(actual)
        }
        return result.toList()
    }

    // ---------- 阶段二:字段级精判 ----------

    /** 拼字段级精判 prompt:锚点字段全列(带映射名的附常见名提示)+ 目标表仅列有注释的字段(无注释字段语义匹配出局);纯函数 */
    @JvmStatic
    fun buildColumnMatchPrompt(anchorTable: String, anchorColumns: List<ColumnMaterial>,
                               targetTable: String, targetColumns: List<ColumnMaterial>): String {
        val sb = StringBuilder()
        sb.append("锚点表:").append(anchorTable).append('\n')
        sb.append("锚点字段:\n")
        for (c in anchorColumns) {
            appendColumn(sb, c)
        }
        sb.append("\n待匹配表:").append(targetTable).append('\n')
        sb.append("字段(仅列出有注释的字段):\n")
        for (c in targetColumns) {
            if (c.comment.isNullOrBlank()) continue // 无注释字段不进 prompt
            appendColumn(sb, c)
        }
        sb.append("\n请输出字段对应关系 JSON 数组,每项为 {\"anchor\":\"锚点字段名\",\"column\":\"待匹配表字段名\"}," +
                "如 [{\"anchor\":\"res_code\",\"column\":\"water_code\"}];没有对应的输出 []。" +
                "只输出 JSON 数组本身,不要输出其他内容。")
        return sb.toString()
    }

    /** 目标表是否有可参与精判的字段(至少一个字段有注释) */
    @JvmStatic
    fun hasColumnMatchMaterial(targetColumns: List<ColumnMaterial>): Boolean =
        targetColumns.any { !it.comment.isNullOrBlank() }

    /**
     * 解析字段级精判回答:抽取 JSON 数组,每项取 anchor/column 两个字段名,
     * 过滤回锚点/目标字段范围内的实际名(大小写不敏感归一)、去重;坏 JSON/无有效项返回空列表
     */
    @JvmStatic
    fun parseColumnPairs(answer: String, anchorColumns: Collection<String>,
                         targetColumns: Collection<String>): List<Pair<String, String>> {
        val array = extractJsonArray(answer) ?: return emptyList()
        val anchorByLower = anchorColumns.associateBy { it.lowercase() }
        val targetByLower = targetColumns.associateBy { it.lowercase() }
        val result = LinkedHashSet<Pair<String, String>>()
        for (node in array) {
            if (!node.isObject) continue
            val a = node.get("anchor")?.takeIf { it.isTextual }?.asText() ?: continue
            val c = node.get("column")?.takeIf { it.isTextual }?.asText() ?: continue
            val anchor = anchorByLower[a.trim().lowercase()] ?: continue
            val target = targetByLower[c.trim().lowercase()] ?: continue
            result.add(anchor to target)
        }
        return result.toList()
    }

    // ---------- 内部 ----------

    private fun appendColumn(sb: StringBuilder, c: ColumnMaterial) {
        sb.append("- ").append(c.columnName)
        if (!c.colType.isNullOrBlank()) {
            sb.append(' ').append(c.colType.trim())
        }
        if (!c.comment.isNullOrBlank()) {
            sb.append(" — ").append(c.comment.trim())
        }
        // 映射名提示(仅锚点字段携带):帮 LLM 关联「其他表中引用该字段的常见字段名」
        if (c.aliases.isNotEmpty()) {
            sb.append(" (在其他表中常见名: ").append(c.aliases.joinToString(", ")).append(')')
        }
        sb.append('\n')
    }

    /** 抽取首个 JSON 数组:回答允许带前后多余文字;找不到/解析失败返回 null */
    private fun extractJsonArray(answer: String) =
        answer.indexOf('[').takeIf { it >= 0 }?.let { start ->
            val end = answer.lastIndexOf(']')
            if (end > start) {
                runCatching { mapper.readTree(answer.substring(start, end + 1)) }
                    .getOrNull()?.takeIf { it.isArray }
            } else null
        }

    /** 抽取 JSON 字符串数组(阶段一回答):非字符串项忽略 */
    private fun parseStringArray(answer: String): List<String>? =
        extractJsonArray(answer)?.mapNotNull { if (it.isTextual) it.asText() else null }
}
