package com.example.dq.service

import com.example.dq.model.ColumnMeta
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Types

/**
 * 数据比对导出「数据最新更新时间」的时间字段探测:名称/注释规则 + 大模型语义匹配,全部为纯函数便于单测。
 *
 * 口径(2026-09 确认):
 * 1. **优先 update 类字段**(update_time/updatetime/修改时间…),其次 create 类(create_time/创建时间…);
 *    同类里日期时间类型的字段优先于其它类型(避免 `update_remark` 之类同名干扰);
 * 2. 名称/注释都没命中时,把该表字段清单(名称/类型/注释)交大模型挑「记录最新修改时间」的字段;
 * 3. 仍未命中返回 null,调用方不取数、导出该格留空。
 *
 * 大模型回答解析容错:允许带解释文字/JSON 包裹/反引号,统一抽取字段名后与字段清单忽略大小写与分隔符匹配;
 * 任何解析失败返回 null,不抛异常(未配置大模型或调用失败由调用方降级为留空)。
 */
object CompareTimeFieldPrompts {

    private val mapper = jacksonObjectMapper()

    /** 单次请求单表字段清单上限,防超宽表撑爆 prompt;超出截断并在 prompt 里标注 */
    const val MAX_COLUMNS_PER_PROMPT = 200

    /** 字段名/类型/注释单值截断长度 */
    const val MAX_IDENTIFIER_CHARS = 80

    const val SYSTEM_PROMPT =
        "你是数据治理专家。用户给出业务表的字段清单(名称、类型、注释),请判断哪个字段记录的是" +
                "「数据最新修改/更新时间」(如更新时间、修改时间、最后更新时间)。只输出该字段的名称;" +
                "没有合适的字段输出 NONE。只输出结果本身,不要解释。"

    /** update 类字段名特征(字段名去掉分隔符/转小写后包含其一即命中) */
    private val UPDATE_NAME_TOKENS = listOf(
        "updatetime", "updtime", "updatedat", "updateat", "updatedate", "updateddate",
        "modifytime", "modifydate", "modifydt", "modifiedtime", "modifieddate", "modifiedat",
        "lastmodified", "lastmodify", "lastupdate", "lastupdatetime", "lastmodifiedtime",
        "gmtmodified", "gmtmodify", "gmtupdate", "edittime", "editdate", "upddatetime",
    )

    /** create 类字段名特征(仅在没有 update 类字段时使用) */
    private val CREATE_NAME_TOKENS = listOf(
        "createtime", "createdat", "createat", "createdate", "createddate", "createdtime",
        "gmtcreate", "gmtcreated", "inserttime", "insertdate", "insertdatetime",
        "addtime", "adddate", "recordtime", "recorddate", "inputtime",
    )

    /** update 类注释特征 */
    private val UPDATE_COMMENT_TOKENS = listOf(
        "更新时间", "更新日期", "更新时刻", "修改时间", "修改日期", "最后修改", "最近修改", "最后更新", "最近更新",
    )

    /** create 类注释特征 */
    private val CREATE_COMMENT_TOKENS = listOf(
        "创建时间", "创建日期", "创建时刻", "入库时间", "录入时间", "新增时间", "创建于",
    )

    /**
     * 按名称/注释规则挑「数据最新更新时间」字段:先 update 类(日期时间类型的优先),再 create 类;都没有返回 null
     */
    @JvmStatic
    fun detectByRule(columns: List<ColumnMeta>): ColumnMeta? {
        fun pick(match: (ColumnMeta) -> Boolean): ColumnMeta? =
            columns.firstOrNull { match(it) && isTemporal(it) } ?: columns.firstOrNull(match)
        return pick { isUpdateField(it) } ?: pick { isCreateField(it) }
    }

    /** 是否 update 类时间字段(名称或注释命中) */
    @JvmStatic
    fun isUpdateField(c: ColumnMeta): Boolean =
        matchesName(c.name, UPDATE_NAME_TOKENS) || matchesComment(c.comment, UPDATE_COMMENT_TOKENS)

    /** 是否 create 类时间字段(名称或注释命中) */
    @JvmStatic
    fun isCreateField(c: ColumnMeta): Boolean =
        matchesName(c.name, CREATE_NAME_TOKENS) || matchesComment(c.comment, CREATE_COMMENT_TOKENS)

    /** 日期时间类型(名称/展示类型含 date/time,或 JDBC 类型为时间族) */
    @JvmStatic
    fun isTemporal(c: ColumnMeta): Boolean {
        if (c.jdbcType == Types.DATE || c.jdbcType == Types.TIME || c.jdbcType == Types.TIMESTAMP ||
            c.jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || c.jdbcType == Types.TIME_WITH_TIMEZONE
        ) {
            return true
        }
        val t = (c.typeName + " " + c.displayType).lowercase()
        return t.contains("date") || t.contains("time") || t.contains("timestamp")
    }

    /** 字段名归一(转小写 + 去掉下划线/空格/连字符等分隔符):`gmt_modified` → `gmtmodified` */
    @JvmStatic
    fun normalizeName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    private fun matchesName(name: String, tokens: List<String>): Boolean {
        val n = normalizeName(name)
        return tokens.any { n.contains(it) }
    }

    private fun matchesComment(comment: String?, tokens: List<String>): Boolean {
        val c = comment?.trim() ?: return false
        return tokens.any { c.contains(it) }
    }

    /**
     * 拼语义匹配 prompt:表名 + 字段清单(名称/类型/注释)+ 输出约定;纯函数
     */
    @JvmStatic
    fun buildPrompt(table: String, columns: List<ColumnMeta>): String {
        val sb = StringBuilder()
        sb.append("业务表 ").append(table).append(" 的字段清单(共 ").append(columns.size).append(" 个):\n")
        val truncated = columns.size > MAX_COLUMNS_PER_PROMPT
        for (c in columns.take(MAX_COLUMNS_PER_PROMPT)) {
            sb.append("- ").append(c.name.take(MAX_IDENTIFIER_CHARS))
            if (c.displayType.isNotBlank()) {
                sb.append(" (").append(c.displayType.take(MAX_IDENTIFIER_CHARS)).append(')')
            }
            if (!c.comment.isNullOrBlank()) {
                sb.append("  # ").append(c.comment.take(MAX_IDENTIFIER_CHARS))
            }
            sb.append('\n')
        }
        if (truncated) {
            sb.append("…(共 ").append(columns.size).append(" 个,以上仅列出前 ")
                .append(MAX_COLUMNS_PER_PROMPT).append(" 个)\n")
        }
        sb.append("\n请输出记录「数据最新修改/更新时间」的字段名称;没有合适的字段输出 NONE。只输出字段名或 NONE。")
        return sb.toString()
    }

    /**
     * 解析语义匹配回答:抽取字段名(容忍 JSON/引号/反引号/解释文字),与字段清单忽略大小写与分隔符匹配;
     * 输出 NONE/空/匹配不上返回 null
     */
    @JvmStatic
    fun parseAnswer(answer: String, columns: List<ColumnMeta>): ColumnMeta? {
        val text = answer.trim()
        if (text.isEmpty()) return null
        // 明确表示「没有」:模型可能答 NONE,也可能答「没有合适的字段(输出 NONE)」
        if (text.uppercase().contains("NONE")) return null
        val raw = extractName(text)
        val target = raw?.let { normalizeName(it) }
        if (!target.isNullOrEmpty() && target != "null" && target != "无") {
            columns.firstOrNull { normalizeName(it.name) == target }?.let { return it }
            columns.firstOrNull { normalizeName(it.name).contains(target) }?.let { return it }
        }
        // 兜底:回答是整句解释时,直接在回答里找出现过的字段名(取最长的那个,避免短名抢先误命中)
        val normalizedText = normalizeName(text)
        return columns.map { it to normalizeName(it.name) }
            .filter { it.second.length >= 4 && normalizedText.contains(it.second) }
            .maxByOrNull { it.second.length }?.first
    }

    /** 从回答里抽字段名:优先 JSON 对象/数组里的字符串,其次去掉引号与包裹文字后的首个标识符 */
    private fun extractName(answer: String): String? {
        val text = answer.trim()
        if (text.isEmpty()) return null
        // JSON 包裹:{"field":"update_time"} / ["update_time"]
        val jsonStart = text.indexOfFirst { it == '{' || it == '[' }
        if (jsonStart >= 0) {
            runCatching { mapper.readValue(text.substring(jsonStart), Any::class.java) }
                .getOrNull()
                ?.let { parsed -> firstString(parsed)?.let { return it.trim() } }
        }
        // 普通文本:去掉引号/反引号/换行,取首个词
        return text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('"', '\'', '`', '“', '”', '。', '.', ':', '：', ',', '，')
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
    }

    private fun firstString(value: Any?): String? = when (value) {
        is String -> value
        is List<*> -> value.firstNotNullOfOrNull { firstString(it) }
        is Map<*, *> -> value.values.firstNotNullOfOrNull { firstString(it) }
        else -> null
    }
}
