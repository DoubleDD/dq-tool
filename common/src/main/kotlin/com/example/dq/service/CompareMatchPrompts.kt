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

    /** 待配对对象(身份标识):编号 1 起,code/name 可为空(带上字段名便于模型理解);
     *  evidence = 佐证字段取值(佐证类型 label → 值,如 行政区划/位置/所在河流),供模型综合判同 */
    data class MatchItem(val seq: Int, val code: String?, val name: String?,
                         val evidence: Map<String, String?> = emptyMap())

    /** 同名二轮消歧的待判定对象:编码/名称 + 其余比对字段取值(供模型区分同名不同对象) */
    data class SameNameItem(val seq: Int, val code: String?, val name: String?,
                            val details: List<kotlin.Pair<String, String?>>)

    /** 同名二轮消歧的系统 prompt:与首轮归一化(跨名称语义配对)目标不同,只判「同名是否同一对象」 */
    const val SAME_NAME_SYSTEM_PROMPT =
        "你是数据治理专家。用户给出若干组来自不同系统的同名对象清单(每组内对象同名但未必是同一个对象)," +
            "并附带各对象的其余字段取值。请根据字段取值判断哪些是同一个业务对象(同一地点/同一实体)," +
            "哪些只是重名的不同对象。只输出 JSON,不要解释。"

    /** 一个同名歧义组的待判定内容(装批用):组序号 1 起(跨批全局编号)+ 组名 + 双侧条目(组内序号 1 起) */
    data class SameNameGroupItems(val groupSeq: Int, val name: String,
                                  val bases: List<SameNameItem>, val targets: List<SameNameItem>)

    /** 组配批量消歧的配对结果:组序号 + 组内 基准侧序号 → 目标侧序号 */
    data class GroupPair(val groupSeq: Int, val baseSeq: Int, val targetSeq: Int)

    /**
     * 组配批量消歧 prompt:多个同名组装进一次请求(每组一段,组内序号各自 1 起),
     * 输出带组号的配对数组 [{"g":组号,"b":基准侧序号,"t":目标侧序号}];纯函数。
     * 每组字段清单由调用方先做区分度裁剪(剔除身份/名称/全空/全同值/UUID 形态字段)
     */
    @JvmStatic
    fun buildSameNameBatchPrompt(groups: List<SameNameGroupItems>,
                                 fieldsByGroup: Map<Int, List<String>>): String {
        val sb = StringBuilder()
        sb.append("以下按组给出基准/目标两侧的同名对象(每组内名称相同,但同名不一定同一个对象)。")
            .append("请逐组根据其余字段的取值判断哪些是同一个对象,哪些只是重名。\n")
        for (g in groups) {
            sb.append("\n== 同名组 ").append(g.groupSeq).append("(名称均为「")
                .append(g.name.take(MAX_IDENTIFIER_CHARS)).append("」)==\n")
            sb.append("比对字段: ").append(fieldsByGroup[g.groupSeq].orEmpty().joinToString(" / ")).append("\n")
            sb.append("基准侧(共 ").append(g.bases.size).append(" 条):\n")
            appendSameNameItems(sb, g.bases)
            sb.append("目标侧(共 ").append(g.targets.size).append(" 条):\n")
            appendSameNameItems(sb, g.targets)
        }
        sb.append("\n请输出同一个对象的配对数组,元素形如 {\"g\": <组号>, \"b\": <基准侧序号>, \"t\": <目标侧序号>},")
            .append("序号为组内列出的 1 起编号,如 [{\"g\":1,\"b\":1,\"t\":2}]。")
            .append("一条只能配一条,同组内同一序号不要重复出现;重名的不同对象不要强行配对,")
            .append("判断不了或没有对应的不要输出。只输出 JSON 数组本身,没有配对时输出 []。")
        return sb.toString()
    }

    /**
     * 解析组配批量消歧回答:抽取首个 JSON 数组,只保留 g 在 [groups] 内、b/t 在该组合法序号内、
     * 且组内序号不重复的配对(重复保留先出现的);g 缺失/非数值、b/t 解析不出一律丢弃;
     * 带多余文字/坏 JSON 返回空,不抛异常。[groups] = 组号 → (合法基准序号集, 合法目标序号集)
     */
    @JvmStatic
    fun parseGroupPairs(answer: String, groups: Map<Int, kotlin.Pair<Set<Int>, Set<Int>>>): List<GroupPair> {
        val parsed = parseArray(answer)
        val result = ArrayList<GroupPair>()
        val used = HashSet<String>()
        for (item in parsed) {
            val g = seqOf(item["g"]) ?: continue
            val b = seqOf(item["b"]) ?: continue
            val t = seqOf(item["t"]) ?: continue
            val valid = groups[g] ?: continue
            if (b !in valid.first || t !in valid.second) continue
            // 组内一对一:同组同侧序号重复只留先出现的(基准/目标序号各自独立判重)
            if (!used.add("b$g|$b") || !used.add("t$g|$t")) continue
            result.add(GroupPair(g, b, t))
        }
        return result
    }

    /** 配对结果:基准侧序号 → 目标侧序号 */
    data class Pair(val baseSeq: Int, val targetSeq: Int)

    /** code/name 单值截断长度,防超长名称撑爆 prompt */
    const val MAX_IDENTIFIER_CHARS = 80

    const val SYSTEM_PROMPT =
        "你是数据治理专家。用户给出若干基准对象,每条基准下方列出了它的候选目标对象(每项含对象编号、" +
            "对象名称及佐证属性取值),请判断哪些候选与基准是同一个业务对象。判断依据:名称相同或语义相同" +
            "(全称与简称、别名、俗称、括号补充说明、错别字、多音字、缺失名称等)即视为同一对象," +
            "不要因为编号格式不同就判为不同对象。两侧记录来自不同系统,任一字段取值都可能与对方不一致——" +
            "取值不一致不代表不是同一主体,也不代表哪一侧是错的;请依据全部属性综合判断," +
            "字段取值差异会在后续差异明细中如实呈现,不影响本次配对判断。" +
            "每条基准只可能匹配它列出的候选之一,没有对应候选时该基准不输出。只输出 JSON,不要解释。"

    /**
     * 拼候选裁决 prompt:逐条「基准 #b: code=… | name=… | 佐证… / 候选: t. …; t. …」,
     * 目标只列该基准召回到的候选(按目标全局序号),输出格式与旧全量清单版一致
     * (「基准序号 - 目标全局序号」配对数组);
     * [regionConflicts] = 基准序号 → 区划单属性冲突的目标序号,候选条目后中性标注
     * 「(行政区划与基准不一致)」——只是事实提示,不带对错暗示(判同不判对错);纯函数。
     */
    @JvmStatic
    fun buildCandidateMatchPrompt(baseCodeLabel: String, baseNameLabel: String,
                                  bases: List<MatchItem>, targetsBySeq: Map<Int, MatchItem>,
                                  candidates: Map<Int, List<Int>>,
                                  regionConflicts: Map<Int, Set<Int>> = emptyMap()): String {
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
                    if (regionConflicts[b.seq]?.contains(tSeq) == true) {
                        sb.append("(行政区划与基准不一致)")
                    }
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

    /** 单条身份渲染「code=… | name=… | 佐证=…」,空值忽略,超长截断(候选裁决与清单共用) */
    private fun appendIdentity(sb: StringBuilder, item: MatchItem, codeLabel: String, nameLabel: String) {
        val code = item.code?.takeIf { c -> c.isNotBlank() }?.take(MAX_IDENTIFIER_CHARS)
        val name = item.name?.takeIf { n -> n.isNotBlank() }?.take(MAX_IDENTIFIER_CHARS)
        val parts = ArrayList<String>(2 + item.evidence.size)
        if (code != null) parts.add("$codeLabel=$code")
        if (name != null) parts.add("$nameLabel=$name")
        for ((label, value) in item.evidence) {
            value?.takeIf { it.isNotBlank() }?.take(MAX_IDENTIFIER_CHARS)?.let { parts.add("$label=$it") }
        }
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
