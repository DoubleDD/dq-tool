package com.example.dq.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 数据比对·佐证字段(实体解析辅助证据)的识别、取值比较与 LLM 兜底识别,全部为纯函数便于单测。
 *
 * 设计口径(详见 数据比对 wiki「佐证字段」):
 * - 佐证类型白名单 = 行政区划/位置/所在河流(文本级、跨系统口径稳);**经纬度明确排除**
 *   (精度/坐标系差异会误杀,只在同名消歧 prompt 里供模型参考);
 * - 佐证字段来源 = 基准表比对字段中按名称/注释规则命中,规则全没命中才由大模型兜底挑;
 * - 比较三态:一致(MATCH)/冲突(CONFLICT)/中性(NEUTRAL)——缺失、一码一名无法互译
 *   一律中性,「缺失 ≠ 冲突」,任何单一字段都可能是错的;
 * - 负证据分级:区划冲突 + 位置/河流也冲突 = 多属性独立冲突(确定性拆);仅区划冲突但
 *   位置/河流一致 = 单属性冲突(交模型仲裁)。匹配只判同一性,不判哪个值对。
 */

/** 佐证字段类型 */
enum class EvidenceKind(val label: String) {
    REGION("行政区划"),
    PLACE("位置"),
    RIVER("所在河流"),
}

/** 证据比较三态:一致 / 冲突 / 中性(缺失或无法比较;缺失≠冲突) */
enum class EvidenceVerdict { MATCH, CONFLICT, NEUTRAL }

object CompareEvidence {

    private val mapper = jacksonObjectMapper()

    /** 待识别字段的最小信息(名称 + 注释) */
    data class FieldHint(val name: String, val comment: String?)

    /** 名称/注释命中规则:类型 → (字段名小写包含任一, 注释包含任一) */
    private val RULES: Map<EvidenceKind, Pair<List<String>, List<String>>> = mapOf(
        EvidenceKind.REGION to (listOf("ad_code", "adcode", "ad_cd", "xzqh", "xzqdm", "xzqhm",
            "region", "district", "division") to listOf("行政区划", "区划", "行政区")),
        EvidenceKind.PLACE to (listOf("res_loc", "location", "loc", "address", "addr",
            "place", "position", "site") to listOf("位置", "地址", "所在地", "坐落")),
        EvidenceKind.RIVER to (listOf("river", "res_rv", "rv_name", "water_system", "shuixi",
            "res_bas") to listOf("河流", "水系", "所在河")),
    )

    /**
     * 规则识别佐证字段:字段名(小写包含)或注释(包含)命中即归类;一类最多取一个
     * (先命中先得,按传入字段顺序——调用方应传基准表字段顺序);名称/注释都不命中返回空 map
     */
    fun detectByRule(fields: List<FieldHint>): Map<EvidenceKind, String> {
        val result = LinkedHashMap<EvidenceKind, String>()
        for (f in fields) {
            val name = f.name.lowercase()
            val comment = f.comment.orEmpty()
            for ((kind, rule) in RULES) {
                if (kind in result) continue
                if (rule.first.any { it in name } || rule.second.any { it in comment }) {
                    result[kind] = f.name
                }
            }
        }
        return result
    }

    // ---------- 取值比较 ----------

    /** 行政区划比较:双侧纯数字码按 6 位县级前缀;双侧名称按归一化相等/包含;一码一名中性 */
    fun regionVerdict(a: String?, b: String?): EvidenceVerdict {
        val va = a?.trim()?.takeIf { it.isNotEmpty() } ?: return EvidenceVerdict.NEUTRAL
        val vb = b?.trim()?.takeIf { it.isNotEmpty() } ?: return EvidenceVerdict.NEUTRAL
        val aDigits = va.all { it.isDigit() }
        val bDigits = vb.all { it.isDigit() }
        if (aDigits && bDigits) {
            // 都够 6 位按县级前缀比;任一侧不足 6 位只能精确比(保守:不等即冲突,位数不同的长码截断比)
            return if (regionCodeKey(va) == regionCodeKey(vb)) EvidenceVerdict.MATCH else EvidenceVerdict.CONFLICT
        }
        if (aDigits != bDigits) return EvidenceVerdict.NEUTRAL // 一码一名无法互译(v2 区划表),中性
        val na = normalizeNameForMatch(va)
        val nb = normalizeNameForMatch(vb)
        if (na.isEmpty() || nb.isEmpty()) return EvidenceVerdict.NEUTRAL
        return if (na == nb || na.contains(nb) || nb.contains(na)) EvidenceVerdict.MATCH else EvidenceVerdict.CONFLICT
    }

    /** 区划代码比较键:≥6 位取前 6(县级),不足 6 位原样 */
    private fun regionCodeKey(code: String): String =
        if (code.length >= 6) code.substring(0, 6) else code

    /**
     * 位置/河流比较(自由文本):归一化后相等/包含 → 一致;双侧都有值且完全无包含关系 → 冲突。
     * 冲突判定只用于「多属性独立冲突」组合,不单独作为否决依据(写法差异大,误杀率高)
     */
    fun textVerdict(a: String?, b: String?): EvidenceVerdict {
        val na = a?.let { normalizeNameForMatch(it) }?.takeIf { it.isNotEmpty() } ?: return EvidenceVerdict.NEUTRAL
        val nb = b?.let { normalizeNameForMatch(it) }?.takeIf { it.isNotEmpty() } ?: return EvidenceVerdict.NEUTRAL
        return if (na == nb || na.contains(nb) || nb.contains(na)) EvidenceVerdict.MATCH else EvidenceVerdict.CONFLICT
    }

    /** 三类佐证的比较结果集(按类型给出,缺类型的为 NEUTRAL) */
    data class Verdicts(val region: EvidenceVerdict, val place: EvidenceVerdict, val river: EvidenceVerdict) {
        /** 正证据加分:区划一致 +1.0,位置/河流一致各 +0.5 */
        fun bonus(): Double =
            (if (region == EvidenceVerdict.MATCH) 1.0 else 0.0) +
                (if (place == EvidenceVerdict.MATCH) 0.5 else 0.0) +
                (if (river == EvidenceVerdict.MATCH) 0.5 else 0.0)

        /** 多属性独立冲突:区划冲突 + 位置/河流也冲突 → 确定性拆(两个独立维度都指向不同) */
        fun strongConflict(): Boolean =
            region == EvidenceVerdict.CONFLICT &&
                (place == EvidenceVerdict.CONFLICT || river == EvidenceVerdict.CONFLICT)

        /** 单属性冲突:区划冲突但位置/河流无冲突且至少一项一致 → 交模型仲裁 */
        fun arbitrableConflict(): Boolean =
            region == EvidenceVerdict.CONFLICT && !strongConflict()

        /** 有无冲突(自动采纳的禁区:有冲突的候选无论分数多高都必须交模型) */
        fun anyConflict(): Boolean =
            region == EvidenceVerdict.CONFLICT || place == EvidenceVerdict.CONFLICT || river == EvidenceVerdict.CONFLICT
    }

    /** 逐类比较两侧行的佐证取值(evidence = 类型 → 字段名;行 map 键为基准字段名) */
    fun verdictsOf(baseRow: Map<String, String?>, targetRow: Map<String, String?>,
                   evidence: Map<EvidenceKind, String>): Verdicts {
        fun value(row: Map<String, String?>, kind: EvidenceKind): String? =
            evidence[kind]?.let { idValue(row, it) }
        return Verdicts(
            regionVerdict(value(baseRow, EvidenceKind.REGION), value(targetRow, EvidenceKind.REGION)),
            textVerdict(value(baseRow, EvidenceKind.PLACE), value(targetRow, EvidenceKind.PLACE)),
            textVerdict(value(baseRow, EvidenceKind.RIVER), value(targetRow, EvidenceKind.RIVER)))
    }

    // ---------- LLM 兜底识别(规则全没命中时;新场景 COMPARE_EVIDENCE) ----------

    const val SYSTEM_PROMPT =
        "你是数据治理专家。用户给出一张表的字段清单(名称、类型、注释)," +
            "请从中挑出「能帮助区分两个同名对象是否为不同实体」的字段,最多三类:" +
            "行政区划(region)、位置/地址(place)、所在河流/水系(river)。" +
            "没有合适的字段就不输出该类;经纬度不要选。只输出 JSON,不要解释。"

    /** 拼兜底识别 prompt;纯函数 */
    fun buildDetectPrompt(table: String, fields: List<FieldHint>): String {
        val sb = StringBuilder()
        sb.append("表 ").append(table).append(" 的字段清单:\n")
        for (f in fields) {
            sb.append("- ").append(f.name.take(80))
            f.comment?.takeIf { it.isNotBlank() }?.let { sb.append("(").append(it.take(80)).append(")") }
            sb.append('\n')
        }
        sb.append("\n请输出 JSON 对象,键为 region/place/river,值为字段名,如 {\"region\":\"AD_CODE\",\"place\":\"RES_LOC\"};" +
            "没有合适字段输出 {}。")
        return sb.toString()
    }

    /**
     * 解析兜底识别结果:抽取首个 JSON 对象,只保留 region/place/river 三类、
     * 且值确实在字段清单内的条目(忽略大小写,归一为清单内实际字段名);坏 JSON/全无效返回空 map
     */
    fun parseDetectAnswer(answer: String, fields: List<FieldHint>): Map<EvidenceKind, String> {
        val validNames = fields.associateBy { it.name.lowercase() }
        val kindByKey = mapOf("region" to EvidenceKind.REGION, "place" to EvidenceKind.PLACE,
            "river" to EvidenceKind.RIVER)
        val start = answer.indexOf('{')
        if (start < 0) return emptyMap()
        val parsed: Any? = try {
            mapper.readValue(answer.substring(start), Any::class.java)
        } catch (e: Exception) {
            return emptyMap()
        }
        val obj = parsed as? Map<*, *> ?: return emptyMap()
        val result = LinkedHashMap<EvidenceKind, String>()
        for ((k, v) in obj) {
            val kind = kindByKey[(k as? String)?.trim()?.lowercase()] ?: continue
            val field = (v as? String)?.trim()?.lowercase()?.let { validNames[it]?.name } ?: continue
            result.putIfAbsent(kind, field)
        }
        return result
    }
}
