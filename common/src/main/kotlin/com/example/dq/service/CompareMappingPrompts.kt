package com.example.dq.service

import com.example.dq.model.ColumnMeta
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 数据比对·列级对比的字段映射预生成 prompt 组装与响应解析,全部为纯函数便于单测。
 *
 * 输入是「基准表需映射的字段(= 比对字段,含名称/类型/注释)」与「目标表的全部列(同口径)」,
 * 让模型按字段名与业务语义为每个基准字段挑出最可能是同一含义的目标列(目标侧字段常改名/缩写/翻译)。
 * 输出统一为「基准字段名 → 目标列名」对象,人工在向导第四步画布审核后再提交。
 * 解析容错:回答允许带多余文字,抽取首个 JSON 对象;坏 JSON 退化为「从右往左找最长可解析对象前缀」;
 * 基准字段不在比对字段内/目标列不存在/同目标列被重复映射的条目一律丢弃;
 * 主键漏映时按目标表同名列(忽略大小写)兜底补齐(提交硬校验要求映射含主键)。
 * 任何解析失败返回空 map,不抛异常(未产出的目标由调用方记 note,绝不因为模型返回不乖就炸向导)。
 */
object CompareMappingPrompts {

    private val mapper = jacksonObjectMapper()

    /** 单次请求单表列清单上限,防超宽表撑爆 prompt;超出截断并在 prompt 里标注 */
    const val MAX_COLUMNS_PER_PROMPT = 200

    /** 字段名/类型/注释单值截断长度 */
    const val MAX_IDENTIFIER_CHARS = 80

    const val SYSTEM_PROMPT =
        "你是数据集成专家。用户给出基准表的一批字段与目标表的全部列(均含名称、类型、注释)," +
                "请为基准表每个字段挑出目标表中最可能是同一含义的列,输出字段映射 JSON。" +
                "匹配依据:字段名相同或语义相同(全称/缩写、别名、翻译、拼音)且类型相容;" +
                "判断不了或目标侧确实没有对应列的字段不要输出。只输出 JSON,不要解释。"

    /** prompt 行项:列名/展示类型/注释(注释为空省略) */
    data class ColumnItem(val name: String, val displayType: String?, val comment: String?)

    fun columnItemOf(c: ColumnMeta): ColumnItem =
        ColumnItem(c.name, c.displayType, c.comment?.takeIf { it.isNotBlank() })

    /** 拼映射 prompt:基准需映射字段清单 + 目标全列清单 + 输出格式约束;纯函数 */
    @JvmStatic
    fun buildMappingPrompt(baseTable: String, baseFields: List<ColumnItem>,
                           targetTable: String, targetColumns: List<ColumnItem>): String {
        val sb = StringBuilder()
        sb.append("基准表 ").append(baseTable).append(" 需要映射的字段(共 ").append(baseFields.size).append(" 个):\n")
        appendColumns(sb, baseFields)
        sb.append("\n目标表 ").append(targetTable).append(" 的全部列(共 ").append(targetColumns.size).append(" 个):\n")
        appendColumns(sb, targetColumns)
        sb.append("\n请输出基准字段到目标列的映射对象,键为基准字段名、值为目标列名,形如 ")
            .append("{\"基准字段1\":\"目标列1\",\"基准字段2\":\"目标列2\"}。")
            .append("只输出 JSON 对象本身,没有映射时输出 {}。")
        return sb.toString()
    }

    /** 追加列清单:一行一列「- 名 (类型)  # 注释」,空注释省略,超长截断,超量标注 */
    private fun appendColumns(sb: StringBuilder, cols: List<ColumnItem>) {
        val truncated = cols.size > MAX_COLUMNS_PER_PROMPT
        for (c in cols.take(MAX_COLUMNS_PER_PROMPT)) {
            sb.append("- ").append(c.name.take(MAX_IDENTIFIER_CHARS))
            if (!c.displayType.isNullOrBlank()) {
                sb.append(" (").append(c.displayType.take(MAX_IDENTIFIER_CHARS)).append(')')
            }
            if (!c.comment.isNullOrBlank()) {
                sb.append("  # ").append(c.comment.take(MAX_IDENTIFIER_CHARS))
            }
            sb.append('\n')
        }
        if (truncated) {
            sb.append("…(共 ").append(cols.size).append(" 个,以上仅列出前 ")
                .append(MAX_COLUMNS_PER_PROMPT).append(" 个)\n")
        }
    }

    /**
     * 解析映射建议:抽取首个 JSON 对象,只保留「基准字段在 fields 内 / 目标列存在(均忽略大小写,
     * 归一为实际列名) / 同一目标列只映射一次(保留先出现的条目)」;主键漏映且目标表有同名列时兜底补主键。
     * 带多余文字/坏 JSON 返回空 map,不抛异常。
     */
    @JvmStatic
    fun parseMappingSuggest(answer: String, fields: List<String>,
                            targetColumns: List<ColumnMeta>, keyField: String): Map<String, String> {
        val fieldByLower = fields.associateBy { it.lowercase() }
        val targetByLower = targetColumns.associateBy { it.name.lowercase() }
        val out = LinkedHashMap<String, String>()
        val usedTarget = HashSet<String>()
        for ((bf, tc) in parseObject(answer)) {
            if (bf.isBlank() || tc.isBlank()) continue
            val baseName = fieldByLower[bf.trim().lowercase()] ?: continue
            val targetName = targetByLower[tc.trim().lowercase()]?.name ?: continue
            if (!usedTarget.add(targetName.lowercase())) continue
            out[baseName] = targetName
        }
        // 主键兜底:大模型漏映主键时按目标表同名列(忽略大小写)补齐,否则提交会被「映射必须包含比对主键」拦下
        val keyBase = fieldByLower[keyField.trim().lowercase()]
        if (keyBase != null && out.keys.none { it.equals(keyBase, ignoreCase = true) }) {
            val keyTarget = targetByLower[keyField.lowercase()]?.name
            if (keyTarget != null && usedTarget.add(keyTarget.lowercase())) {
                out[keyBase] = keyTarget
            }
        }
        return out
    }

    /** 抽取首个 JSON 对象解析成「字符串 → 字符串」映射;缺失/坏 JSON/非字符串值返回空 map(不抛异常) */
    private fun parseObject(answer: String): Map<String, String> {
        val start = answer.indexOf('{')
        if (start < 0) return emptyMap()
        val json = answer.substring(start)
        val parsed: Any? = try {
            mapper.readValue(json, Any::class.java)
        } catch (e: Exception) {
            // 对象后带尾部多余文字时,退化为从右往左找最长的可解析前缀
            readLongestObjectPrefix(json)
        }
        val obj = parsed as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj) {
            if (k is String && v is String) out[k] = v
        }
        return out
    }

    /** 截取可解析的最长对象前缀:模型常在对象后追加说明文字(如 `} 说明…`),从右往左找 `}` 尝试解析,失败再往前退 */
    private fun readLongestObjectPrefix(json: String): Any? {
        var end = json.lastIndexOf('}')
        while (end > 0) {
            try {
                return mapper.readValue(json.substring(0, end + 1), Any::class.java)
            } catch (e: Exception) {
                end = json.lastIndexOf('}', end - 1)
            }
        }
        return null
    }
}
