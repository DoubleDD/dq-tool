package com.example.dq.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 数据比对·匹配逻辑 3(大模型归一化匹配)的 prompt 组装与响应解析,全部为纯函数便于单测。
 *
 * 输入是「编码/名称两路都没配对上的双侧残余对象」,流程三段式(见 CompareService.aiMatchResidues):
 * 1. 本地归一化精确补配(零成本,不经过本文件);
 * 2. 相似度召回(零成本,不经过本文件):每条基准从目标残余里召回 ≤ top-K 候选;
 * 3. 模型裁决(本文件):按条目预算把「基准 + 各自候选列表」装批组 prompt,让模型按业务语义判断
 *    哪些候选与基准是同一个对象(编码规则不同、名称简写/别名/错别字、缺名称等脏数据场景),
 *    只可能配它列出的候选,防全量两两组合的平方级 token 消耗。
 * 输出统一为「基准序号 - 目标全局序号」配对数组,序号指 prompt 里列出的 1 起序号。
 * 解析容错:回答允许带多余文字,抽取首个 JSON 数组;坏 JSON/无有效配对返回空,不抛异常
 * (残余对象下一律记缺失/多余,绝不因为模型返回不乖就炸任务)。
 */
object CompareMatchPrompts {

    private val mapper = jacksonObjectMapper()

    /** 待配对对象(身份标识):编号 1 起,code/name 可为空(带上字段名便于模型理解) */
    data class MatchItem(val seq: Int, val code: String?, val name: String?)

    /** 同名二轮消歧的待判定对象:编码/名称 + 其余比对字段取值(供模型区分同名不同对象) */
    data class SameNameItem(val seq: Int, val code: String?, val name: String?,
                            val details: List<kotlin.Pair<String, String?>>)

    /** 同名二轮消歧的系统 prompt:与首轮归一化(跨名称语义配对)目标不同,只判「同名是否同一对象」 */
    const val SAME_NAME_SYSTEM_PROMPT =
        "你是数据治理专家。用户给出两份来自不同系统的同名对象清单(同名但未必是同一个对象)," +
            "并附带各对象的其余字段取值。请根据字段取值判断哪些是同一个业务对象(同一地点/同一实体)," +
            "哪些只是重名的不同对象。只输出 JSON,不要解释。"

    /** 配对结果:基准侧序号 → 目标侧序号 */
    data class Pair(val baseSeq: Int, val targetSeq: Int)

    /** code/name 单值截断长度,防超长名称撑爆 prompt */
    const val MAX_IDENTIFIER_CHARS = 80

    const val SYSTEM_PROMPT =
        "你是数据治理专家。用户给出若干基准对象,每条基准下方列出了它的候选目标对象(每项含对象编号与对象名称)," +
            "请判断哪些候选与基准是同一个业务对象。判断依据:名称相同或语义相同(全称与简称、别名、俗称、" +
            "括号补充说明、错别字、多音字、缺失名称等)即视为同一对象,不要因为编号格式不同就判为不同对象。" +
            "每条基准只可能匹配它列出的候选之一,没有对应候选时该基准不输出。只输出 JSON,不要解释。"

    /**
     * 拼候选裁决 prompt:逐条「基准 #b: code=… | name=… / 候选: t. …; t. …」,
     * 目标只列该基准召回到的候选(按目标全局序号),输出格式与旧全量清单版一致
     * (「基准序号 - 目标全局序号」配对数组);纯函数。
     */
    @JvmStatic
    fun buildCandidateMatchPrompt(baseCodeLabel: String, baseNameLabel: String,
                                  bases: List<MatchItem>, targetsBySeq: Map<Int, MatchItem>,
                                  candidates: Map<Int, List<Int>>): String {
        val sb = StringBuilder()
        sb.append("编号与名称可能不一致,请按业务含义判断同一个对象。每条基准只可能匹配它下方列出的候选目标," +
            "候选按目标全局序号编号;判断不了或没有对应候选时不要输出该基准。\n\n")
        for (b in bases) {
            sb.append("基准 ").append(b.seq).append(": ")
            appendIdentity(sb, b, baseCodeLabel, baseNameLabel)
            sb.append('\n')
            val cands = candidates[b.seq].orEmpty()
            if (cands.isEmpty()) {
                sb.append("  候选: (无)\n")
            } else {
                sb.append("  候选: ")
                cands.forEachIndexed { i, tSeq ->
                    if (i > 0) sb.append("; ")
                    val t = targetsBySeq[tSeq] ?: return@forEachIndexed
                    sb.append(t.seq).append(". ")
                    appendIdentity(sb, t, baseCodeLabel, baseNameLabel)
                }
                sb.append('\n')
            }
        }
        sb.append("\n请输出同一个对象的配对数组,元素形如 {\"b\": <基准序号>, \"t\": <目标全局序号>},")
            .append("序号为上面列出的编号,如 [{\"b\":1,\"t\":3}]。")
            .append("一条只能配一条,同一序号不要重复出现;目标只能配它所属基准下方列出的候选。")
            .append("只输出 JSON 数组本身,没有配对时输出 []。")
        return sb.toString()
    }

    /** 候选集校验:只保留 t 确实出现在该 b 候选列表里的配对(防模型跨候选乱配);纯函数 */
    @JvmStatic
    fun filterPairsByCandidates(pairs: List<Pair>, candidates: Map<Int, List<Int>>): List<Pair> =
        pairs.filter { p -> candidates[p.baseSeq]?.contains(p.targetSeq) == true }

    /** 单条身份渲染「code=… | name=…」,空值忽略,超长截断(候选裁决与清单共用) */
    private fun appendIdentity(sb: StringBuilder, item: MatchItem, codeLabel: String, nameLabel: String) {
        val code = item.code?.takeIf { c -> c.isNotBlank() }?.take(MAX_IDENTIFIER_CHARS)
        val name = item.name?.takeIf { n -> n.isNotBlank() }?.take(MAX_IDENTIFIER_CHARS)
        val parts = ArrayList<String>(2)
        if (code != null) parts.add("$codeLabel=$code")
        if (name != null) parts.add("$nameLabel=$name")
        sb.append(if (parts.isEmpty()) "(编号与名称均为空)" else parts.joinToString(" | "))
    }

    /**
     * 同名二轮消歧 prompt:同名的双侧清单带**全部比对字段取值**(字段名 = 基准字段名,两侧行 map
     * 已归一成基准字段名),让模型区分「同名同一对象」与「同名不同对象」;输出格式与首轮归一化
     * 一致(序号配对数组),复用 [parsePairs] 解析;纯函数。
     */
    @JvmStatic
    fun buildSameNameRefinePrompt(name: String, fields: List<String>,
                                  bases: List<SameNameItem>, targets: List<SameNameItem>): String {
        val sb = StringBuilder()
        sb.append("以下基准/目标两侧的对象名称相同(均为「").append(name.take(MAX_IDENTIFIER_CHARS)).append("」),")
            .append("但同名不一定同一个对象。请根据其余字段的取值判断哪些是同一个对象,哪些只是重名。\n\n")
        sb.append("比对字段: ").append(fields.joinToString(" / ")).append("\n")
        sb.append("基准侧(共 ").append(bases.size).append(" 条):\n")
        appendSameNameItems(sb, bases)
        sb.append("\n目标侧(共 ").append(targets.size).append(" 条):\n")
        appendSameNameItems(sb, targets)
        sb.append("\n请输出同一个对象的配对数组,元素形如 {\"b\": <基准侧序号>, \"t\": <目标侧序号>},")
            .append("序号为上面列出的 1 起编号,如 [{\"b\":1,\"t\":2}]。")
            .append("一条只能配一条,同一序号不要重复出现;重名的不同对象不要强行配对,")
            .append("判断不了或没有对应的不要输出。只输出 JSON 数组本身,没有配对时输出 []。")
        return sb.toString()
    }

    /** 一行一条「序号. code=… | name=… | 字段=「值」…」:编码/名称为空省略,字段空值标 (空),超长截断 */
    private fun appendSameNameItems(sb: StringBuilder, items: List<SameNameItem>) {
        for (it in items) {
            sb.append(it.seq).append(". ")
            val parts = ArrayList<String>(it.details.size + 2)
            it.code?.takeIf { c -> c.isNotBlank() }?.take(MAX_IDENTIFIER_CHARS)
                ?.let { c -> parts.add("code=$c") }
            it.name?.takeIf { n -> n.isNotBlank() }?.take(MAX_IDENTIFIER_CHARS)
                ?.let { n -> parts.add("name=$n") }
            for ((f, v) in it.details) {
                val value = v?.trim()?.take(MAX_IDENTIFIER_CHARS)
                parts.add("$f=「${value ?: "(空)"}」")
            }
            sb.append(if (parts.isEmpty()) "(无字段取值)" else parts.joinToString(" | "))
            sb.append('\n')
        }
    }

    /**
     * 解析配对回答:抽取 JSON 数组,只保留两侧序号都在合法范围内、且序号不重复的配对
     * (同一序号出现在多条时保留先出现的,后续忽略);带多余文字/坏 JSON 返回空列表,不抛异常。
     */
    @JvmStatic
    fun parsePairs(answer: String, baseSeqs: Collection<Int>, targetSeqs: Collection<Int>): List<Pair> {
        val baseValid = baseSeqs.toHashSet()
        val targetValid = targetSeqs.toHashSet()
        val result = ArrayList<Pair>()
        val usedBase = HashSet<Int>()
        val usedTarget = HashSet<Int>()
        val parsed = parseArray(answer)
        for (item in parsed) {
            val b = seqOf(item["b"])
            val t = seqOf(item["t"])
            if (b == null || t == null) continue
            if (b !in baseValid || t !in targetValid) continue
            if (!usedBase.add(b) || !usedTarget.add(t)) continue
            result.add(Pair(b, t))
        }
        return result
    }

    /** 抽取首个 JSON 数组解析成对象列表;缺失/坏 JSON 返回空列表(不抛异常) */
    private fun parseArray(answer: String): List<Map<String, Any?>> {
        val start = answer.indexOf('[')
        if (start < 0) return emptyList()
        val json = answer.substring(start)
        val parsed: Any? = try {
            mapper.readValue(json, Any::class.java)
        } catch (e: Exception) {
            // 数组中带尾部多余文字时,退化为逐段匹配最长的可解析前缀
            readLongestArrayPrefix(json)
        }
        return parseArrayValue(parsed)
    }

    /**
     * 截取可解析的最长数组前缀:模型常在数组后追加说明文字(如 `] 说明…`),
     * 从右往左找第一个 `]` 尝试解析,失败再往前退,全失败返回 null
     */
    private fun readLongestArrayPrefix(json: String): Any? {
        var end = json.lastIndexOf(']')
        while (end > 0) {
            try {
                return mapper.readValue(json.substring(0, end + 1), Any::class.java)
            } catch (e: Exception) {
                end = json.lastIndexOf(']', end - 1)
            }
        }
        return null
    }

    /** 数组值归一成「对象列表」:非数组/元素非对象一律丢弃 */
    @Suppress("UNCHECKED_CAST")
    private fun parseArrayValue(parsed: Any?): List<Map<String, Any?>> {
        val list = parsed as? List<*> ?: return emptyList()
        return list.mapNotNull { it as? Map<String, Any?> }
    }

    /** 序号解析:兼容数字与字符串,非法返回 null */
    private fun seqOf(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
