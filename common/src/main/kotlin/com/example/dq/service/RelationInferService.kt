package com.example.dq.service

import com.example.dq.dialect.DbDialect
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.AiScene
import com.example.dq.model.AnchorField
import com.example.dq.model.AnchorFieldRequest
import com.example.dq.model.RelationCardinality
import com.example.dq.model.RelationInferJob
import com.example.dq.model.RelationInferStage
import com.example.dq.model.RelationSource
import com.example.dq.model.RelationStatus
import com.example.dq.model.SchemaColumn
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.RelationInferJobRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TableRelationRepository
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.concurrent.Executors

/**
 * ER 关系推导:
 * 名字匹配通道——锚点表+锚点字段 → 整库字段缓存(meta_schema_column,未就绪则实时拉取并回填)忽略大小写命中;
 *   每个锚点字段的搜索名集合 = 本名 + 用户手填映射名(aliases,trim/去空/忽略大小写去重),任一命中即候选;
 *   aliasOnly=true 时搜索名集合只含映射名(本名不参与,避免 id 等通用名全库命中),无映射名的锚点字段不参与名字匹配;
 *   经映射名命中的关系 remark 注明「经映射字段名 xx 命中」(本名命中不加);
 * 语义匹配通道(useSemantic=true,M2)——阶段一表级粗筛(stage=SEMANTIC_TABLE:锚点表 vs 全库表清单分批发 LLM,
 *   每张表原料回退:有 table_doc 描述用描述、无描述用表注释、皆无不进 prompt)筛出相关表,
 *   阶段二字段级精判(stage=SEMANTIC_COLUMN:逐张相关表把有注释字段与锚点字段发 LLM 求字段对;
 *   锚点字段行附映射名提示);
 * 两条通道候选合并去重(同一字段对 source 优先 NAME_MATCH;已确认/候选对已存在不重复验证/插入,
 *   已确认关系不被降级;已否决对不跳过——重新验证并回炉为 CANDIDATE,同 id 整行刷新含方向翻转)
 * → 逐对串行验证(stage=VERIFY):值交集(两端各 distinctSampleSql ≤1000,值 toString().trim() 求交集率)
 *   + 基数(唯一索引/主键单列缓存短路,否则 hasDuplicateSql;两端唯一=ONE_TO_ONE,
 *   一端唯一=ONE_TO_MANY(one 侧存唯一方),两端重复=SUSPECT_MANY_TO_MANY 标 remark;
 *   查询异常=剔除不入候选;任一侧样本为空(空表/字段全 NULL)不剔除——空库也要能产出候选:
 *   交集率/置信度留空,基数只信索引缓存,均不可证唯一时暂按锚点侧为「一」存 ONE_TO_MANY,remark 注明待复核)
 * → CANDIDATE 落 table_relation;单轮候选对上限 [candidateLimit],超出截断并记 warn 日志。
 *
 * 语义通道容错:单批/单表 LLM 调用失败记 error 日志并附注到 job.error,继续其余批次,不炸 job
 * (名字匹配结果必须保住);仅提交时无 AI 配置(submitInfer requireConfig)直接失败。
 * 纯阻塞 API;任务异步执行(单线程守护线程池),进度落 relation_infer_job 供前端轮询。
 */
class RelationInferService(
    private val relationRepo: TableRelationRepository,
    private val jobRepo: RelationInferJobRepository,
    private val metaCacheRepo: MetaCacheRepository,
    private val dataSourceService: DataSourceService,
    private val systemSettingsService: SystemSettingsService,
    private val dialectFactory: DialectFactory,
    private val aiConfigService: AiConfigService,
    private val tableDocRepo: TableDocRepository,
    aiService: AiService,
    /** LLM 调用点(AiService 是 final class,测试经此注入 fake);场景固定为关系推导 */
    private val chat: (AiConfigService.Config, String, String) -> String =
        { c, s, u -> aiService.chat(c, s, u, AiScene.RELATION_INFER, null) },
    /** 单轮候选对上限(防护;测试可注入小值) */
    private val candidateLimit: Int = MAX_CANDIDATES,
    /** 语义表级粗筛每批表数(每批一次 LLM 调用;测试可注入小值) */
    private val tableBatchSize: Int = TABLE_BATCH_SIZE,
) {

    private val log = LoggerFactory.getLogger(RelationInferService::class.java)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "relation-infer").apply { isDaemon = true }
    }

    /** 提交一轮推导,返回任务 id;useSemantic=true 时需已配置大模型(无配置抛 IllegalStateException,壳层映射 409);
     *  aliasOnly=true 时名字匹配只用映射名(见类注释) */
    fun submitInfer(datasourceId: Long, dbName: String?, schemaName: String?, table: String?,
                    fields: List<AnchorFieldRequest>?, useSemantic: Boolean, aliasOnly: Boolean = false): Long {
        // 语义通道前置校验放在参数校验之前:配置缺失直接拒,不产生任务
        val aiConfig = if (useSemantic) aiConfigService.requireConfig() else null
        val anchorTable = table?.trim().orEmpty()
        if (anchorTable.isEmpty()) {
            throw IllegalArgumentException("锚点表不能为空")
        }
        val anchorFields = normalizeAnchorFields(fields)
        if (anchorFields.isEmpty()) {
            throw IllegalArgumentException("锚点字段不能为空")
        }
        val schema = schemaName?.trim().orEmpty()
        if (schema.isEmpty()) {
            throw IllegalArgumentException("schema 不能为空")
        }
        val db = dbName?.trim().orEmpty()
        dataSourceService.get(datasourceId) // 数据源不存在抛 IllegalArgumentException
        val jobId = jobRepo.insert(datasourceId, db, schema, anchorTable,
            anchorFields.map { AnchorField(it.name, it.aliases) }, useSemantic)
        executor.execute { runInfer(jobId, datasourceId, db, schema, anchorTable, anchorFields, aiConfig, aliasOnly) }
        return jobId
    }

    /** 锚点字段归一:name/aliases trim 去空;映射名与本名忽略大小写去重(保留首次出现写法);同名锚点字段忽略大小写去重 */
    private fun normalizeAnchorFields(fields: List<AnchorFieldRequest>?): List<AnchorSpec> {
        val byName = LinkedHashMap<String, AnchorSpec>() // 小写本名 → 归一后锚点字段(去重)
        for (f in fields.orEmpty()) {
            val name = f.name?.trim().orEmpty()
            if (name.isEmpty()) continue
            val seen = HashSet<String>()
            seen.add(name.lowercase())
            val aliases = f.aliases.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
            byName.putIfAbsent(name.lowercase(), AnchorSpec(name, aliases))
        }
        return byName.values.toList()
    }

    fun listJobs(datasourceId: Long, dbName: String?, schemaName: String): List<RelationInferJob> =
        jobRepo.listBySchema(datasourceId, dbName ?: "", schemaName)

    fun getJob(id: Long): RelationInferJob =
        jobRepo.findById(id) ?: throw IllegalArgumentException("推导任务不存在: $id")

    /** 服务重启:残留 RUNNING 任务标 FAILED(推导可重跑,不做断点续推) */
    fun failRunningOnStartup(): Int = jobRepo.failRunningOnStartup()

    // ---------- 异步执行 ----------

    private fun runInfer(jobId: Long, datasourceId: Long, dbName: String, schema: String,
                         anchorTable: String, anchorFields: List<AnchorSpec>,
                         aiConfig: AiConfigService.Config?, aliasOnly: Boolean) {
        var found = 0
        try {
            val ds = dataSourceService.get(datasourceId)
            val dialect = dialectFactory.get(ds.dbType!!)
            // 多库方言(SQL Server/Kingbase)按库分池切 catalog;其余方言 database 参数被忽略
            val dbParam = if (dialect.supportsMultiDatabase()) dbName else null

            // 1. 名字匹配:整库字段清单(缓存优先,未就绪实时拉取并回填)
            jobRepo.updateStage(jobId, RelationInferStage.NAME_MATCH.name)
            val schemaColumns = schemaColumns(datasourceId, dbParam, dbName, schema, dialect)

            // 锚点字段本名按缓存中的实际大小写归一(唯一键/验证 SQL 用实际名);映射名保持用户写法
            val anchorSpecs = anchorFields.map { spec ->
                val actual = schemaColumns.firstOrNull { it.table == anchorTable && it.name.equals(spec.name, true) }?.name
                if (actual != null) spec.copy(name = actual) else spec
            }
            // 搜索名集合 = 本名+映射名(aliasOnly 时仅映射名):小写搜索名 → (锚点字段, 命中用映射名写法;本名命中为 null)
            // 搜索名冲突(如甲字段的映射名撞上乙字段本名)先到先得,确定性
            val searchToAnchor = LinkedHashMap<String, Pair<AnchorSpec, String?>>()
            for (spec in anchorSpecs) {
                if (!aliasOnly) {
                    searchToAnchor.putIfAbsent(spec.name.lowercase(), spec to null)
                }
                for (alias in spec.aliases) {
                    searchToAnchor.putIfAbsent(alias.lowercase(), spec to alias)
                }
            }
            if (aliasOnly) {
                val noAlias = anchorSpecs.filter { it.aliases.isEmpty() }.map { it.name }
                if (noAlias.isNotEmpty()) {
                    log.info("关系推导: 任务 {} 仅映射名匹配,锚点字段 {} 无映射名,不参与名字匹配", jobId, noAlias)
                }
            }

            // 候选对:方向无关 key 去重;排除锚点表自身;跳过已存在对(已确认/候选不重复验证,已确认关系不被降级);
            // REJECTED 不入 existing:否决对重新推导——重新验证并回炉为 CANDIDATE(见 TableRelationRepository.upsertDerived)
            val existing = HashSet<String>()
            for (r in relationRepo.listBySchema(datasourceId, dbName, schema)) {
                if (r.status != RelationStatus.REJECTED.name) {
                    existing.add(pairKey(ColRef(r.oneTable, r.oneColumn), ColRef(r.manyTable, r.manyColumn)))
                }
            }
            val candidates = LinkedHashMap<String, Candidate>()
            var totalHits = 0 // 命中名字匹配且未被去重的新候选总数(截断前)
            var dupSkips = 0 // 命中但对已存在(历史关系/本轮重复)跳过数
            var truncated = 0 // 命中但超出候选上限被丢弃数
            val hitsByName = LinkedHashMap<String, MutableList<String>>() // 小写搜索名 → 命中的 表.字段(诊断日志用)
            for (sc in schemaColumns) {
                if (sc.table == anchorTable) continue
                val (spec, hitAlias) = searchToAnchor[sc.name.lowercase()] ?: continue
                hitsByName.getOrPut(sc.name.lowercase()) { ArrayList() }.add(sc.table + "." + sc.name)
                val anchor = ColRef(anchorTable, spec.name)
                val other = ColRef(sc.table, sc.name)
                val key = pairKey(anchor, other)
                if (key in existing || candidates.containsKey(key)) {
                    dupSkips++
                    continue
                }
                totalHits++
                if (candidates.size < candidateLimit) {
                    candidates[key] = Candidate(anchor, other, RelationSource.NAME_MATCH, hitAlias)
                } else {
                    truncated++
                }
            }
            log.info("关系推导: 任务 {} 名字匹配搜索名 {}{},命中明细:{};已存在/重复对跳过 {} 次,新候选 {} 对(上限 {},丢弃 {} 对)",
                jobId, searchToAnchor.keys, if (aliasOnly) "(仅映射名)" else "",
                hitsByName.entries.joinToString("; ") { (n, hits) ->
                    "$n → ${hits.size} 个字段 " + hits.take(30).joinToString(",", "[", "]") +
                        if (hits.size > 30) "...(略)" else ""
                }.ifEmpty { "无" },
                dupSkips, candidates.size, candidateLimit, truncated)

            // 2. 语义匹配(useSemantic 时):表级粗筛 + 字段级精判;单批/单表失败附注后继续,不炸 job
            // 语义通道已走的步数(批次数 + 精判表数),计入 total_steps 与 done_steps 基数
            var semanticSteps = 0
            var semanticDone = 0
            var semanticHits = 0 // 语义通道新候选总数(截断前)
            if (aiConfig != null) {
                // 表注释(meta_table 缓存)与 AI 表描述(table_doc):阶段一原料
                val tableComments = metaCacheRepo.listTables(datasourceId, dbName, schema)
                    .associate { it.tableName to it.comment }
                val tableDocs = tableDocRepo.findBySchema(datasourceId, dbName, schema)
                val allTables = schemaColumns.map { it.table }.distinct()

                // 阶段一·表级粗筛:回退规则下有原料的表分批(锚点表自身不进候选清单)
                jobRepo.updateStage(jobId, RelationInferStage.SEMANTIC_TABLE.name)
                val material = allTables.filter { it != anchorTable }.map { t ->
                    RelationSemanticPrompts.TableMaterial(t, tableComments[t], tableDocs[t])
                }.filter { RelationSemanticPrompts.hasTableScreenMaterial(listOf(it)) }
                val batches = material.chunked(tableBatchSize)
                semanticSteps += batches.size
                jobRepo.updateProgress(jobId, semanticSteps, semanticDone, 0)
                val relatedTables = LinkedHashSet<String>()
                for ((idx, batch) in batches.withIndex()) {
                    try {
                        val answer = chat(aiConfig, RelationSemanticPrompts.TABLE_SCREEN_SYSTEM_PROMPT,
                            RelationSemanticPrompts.buildTableScreenPrompt(
                                anchorTable, tableComments[anchorTable], tableDocs[anchorTable], batch))
                        relatedTables.addAll(RelationSemanticPrompts.parseRelatedTables(answer, allTables))
                    } catch (e: Exception) {
                        // 单批失败:附注后继续其余批次,名字匹配结果不受影响
                        log.warn("语义表级粗筛第 {} 批调用失败,跳过该批: {}", idx + 1, e.message)
                        jobRepo.appendNote(jobId, "语义表级粗筛第 ${idx + 1} 批失败:" + e.message)
                    }
                    semanticDone++
                    jobRepo.updateProgress(jobId, semanticSteps, semanticDone, 0)
                }
                relatedTables.remove(anchorTable) // 排除锚点表自身

                // 阶段二·字段级精判:逐张相关表求锚点字段↔该表字段对应关系(每表一次调用)
                jobRepo.updateStage(jobId, RelationInferStage.SEMANTIC_COLUMN.name)
                val anchorMaterial = anchorSpecs.map { spec ->
                    val sc = schemaColumns.firstOrNull { it.table == anchorTable && it.name == spec.name }
                    RelationSemanticPrompts.ColumnMaterial(spec.name, sc?.type, sc?.comment, spec.aliases)
                }
                semanticSteps += relatedTables.size
                jobRepo.updateProgress(jobId, semanticSteps, semanticDone, 0)
                for (t in relatedTables) {
                    val targetCols = schemaColumns.filter { it.table == t }
                        .map { RelationSemanticPrompts.ColumnMaterial(it.name, it.type, it.comment) }
                    if (RelationSemanticPrompts.hasColumnMatchMaterial(targetCols)) {
                        try {
                            val answer = chat(aiConfig, RelationSemanticPrompts.COLUMN_MATCH_SYSTEM_PROMPT,
                                RelationSemanticPrompts.buildColumnMatchPrompt(
                                    anchorTable, anchorMaterial, t, targetCols))
                            for ((a, c) in RelationSemanticPrompts.parseColumnPairs(
                                answer, anchorSpecs.map { it.name }, targetCols.map { it.columnName })) {
                                val anchor = ColRef(anchorTable, a)
                                val other = ColRef(t, c)
                                val key = pairKey(anchor, other)
                                // 合并规则:已存在对/名字匹配已命中的对不覆盖(source 优先 NAME_MATCH;REJECTED 不跳过,重新验证回炉)
                                if (key in existing || candidates.containsKey(key)) continue
                                semanticHits++
                                if (candidates.size < candidateLimit) {
                                    candidates[key] = Candidate(anchor, other, RelationSource.SEMANTIC)
                                }
                            }
                        } catch (e: Exception) {
                            log.warn("语义字段级精判表 {} 调用失败,跳过该表: {}", t, e.message)
                            jobRepo.appendNote(jobId, "语义字段级精判表 $t 失败:" + e.message)
                        }
                    }
                    semanticDone++
                    jobRepo.updateProgress(jobId, semanticSteps, semanticDone, 0)
                }
            }
            if (totalHits + semanticHits > candidateLimit) {
                log.warn("关系推导候选 {} 对超出单轮上限 {},超出部分已截断(数据源 {} 库 {}/{})",
                    totalHits + semanticHits, candidateLimit, datasourceId, dbName, schema)
            }

            // 3. 逐对验证:值交集 + 基数(串行,单条超时与分段扫描同口径)
            jobRepo.updateStage(jobId, RelationInferStage.VERIFY.name)
            jobRepo.updateProgress(jobId, semanticSteps + candidates.size, semanticDone, 0)
            var done = 0
            var dropped = 0 // 验证阶段剔除数(查询异常;并发下撞已存在关系未插入也计入)
            dataSourceService.getConnection(datasourceId, dbParam).use { conn ->
                for ((_, cand) in candidates) {
                    try {
                        if (verifyAndInsert(conn, dialect, datasourceId, dbName, schema,
                                cand.a, cand.b, cand.source, cand.hitAlias)) {
                            found++
                        } else {
                            // 唯一键幂等:并发/重跑下该对已被插入,不重复计数
                            dropped++
                            log.info("关系推导: 任务 {} 候选对已存在,未重复插入: {}.{} <-> {}.{}",
                                jobId, cand.a.table, cand.a.column, cand.b.table, cand.b.column)
                        }
                    } catch (e: Exception) {
                        // 查询异常=剔除该对,不中断整轮推导
                        dropped++
                        log.warn("候选对验证失败,已剔除: {}.{} <-> {}.{} - {}", cand.a.table, cand.a.column,
                            cand.b.table, cand.b.column, e.message)
                    }
                    done++
                    jobRepo.updateProgress(jobId, semanticSteps + candidates.size, semanticDone + done, found)
                }
            }
            jobRepo.finish(jobId, found)
            log.info("关系推导完成: 任务 {} 锚点 {}.{},候选 {} 对(名字命中 {} + 语义命中 {}),验证剔除 {} 对,新发现 {} 对(含否决回炉)",
                jobId, anchorTable, anchorSpecs.map { it.name }, candidates.size, totalHits, semanticHits, dropped, found)
        } catch (e: Exception) {
            log.warn("关系推导任务失败: {}", e.message, e)
            jobRepo.fail(jobId, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 整库字段清单:缓存优先;未就绪则连业务库实时拉取并整粒度回填缓存(与 MetadataService 同口径) */
    private fun schemaColumns(datasourceId: Long, dbParam: String?, dbName: String, schema: String,
                              dialect: DbDialect): List<SchemaColumn> {
        if (metaCacheRepo.isSchemaColumnsReady(datasourceId, dbName, schema)) {
            val cached = metaCacheRepo.listSchemaColumns(datasourceId, dbName, schema)
                .map { SchemaColumn(it.tableName, it.columnName, it.colType ?: "", it.comment ?: "") }
            log.info("关系推导: 数据源 {} 库 {}/{} 字段清单命中缓存,共 {} 张表 {} 个字段",
                datasourceId, dbName, schema, cached.map { it.table }.distinct().size, cached.size)
            return cached
        }
        val fresh = dataSourceService.getConnection(datasourceId, dbParam).use { conn ->
            dialect.listSchemaColumns(conn, schema)
        }
        log.info("关系推导: 数据源 {} 库 {}/{} 字段清单缓存未就绪,实时拉取 {} 张表 {} 个字段并回填缓存",
            datasourceId, dbName, schema, fresh.map { it.table }.distinct().size, fresh.size)
        val ordinals = HashMap<String, Int>()
        metaCacheRepo.replaceSchemaColumns(datasourceId, dbName, schema, fresh.map { c ->
            val ord = ordinals[c.table] ?: 0
            ordinals[c.table] = ord + 1
            MetaCacheRepository.CachedSchemaColumn(c.table, ord, c.name, c.type, c.comment)
        })
        return fresh
    }

    /**
     * 验证单对候选并入库,返回是否新插入/回炉:
     * 值交集率 = 交集数/较小样本数;任一侧样本为空(空表/字段全 NULL)不剔除——空库也要能产出候选,
     *   交集率/置信度留空,基数只看索引缓存(空表判重 SQL 无意义,会误判两端唯一),remark 注明未验证待人工裁决;
     * 两端唯一=ONE_TO_ONE(字典序小者入 one 侧),一端唯一=ONE_TO_MANY(one 侧存唯一方),
     * 两端重复=SUSPECT_MANY_TO_MANY(方向同 ONE_TO_ONE 归一化,remark 注明待人工裁决);
     * 样本为空且两端都无法经索引证明唯一时,暂按锚点侧(a)为「一」存 ONE_TO_MANY;
     * hitAlias 非空表示经映射名命中,remark 注明「经映射字段名 xx 命中」(与其他备注可并存,分号连接);
     * 入库经 upsertDerived:命中已否决行回炉为 CANDIDATE(按最新方向/验证数据整行刷新),命中非否决行不动
     */
    private fun verifyAndInsert(conn: Connection, dialect: DbDialect, datasourceId: Long, dbName: String,
                                schema: String, a: ColRef, b: ColRef, source: RelationSource,
                                hitAlias: String?): Boolean {
        val valuesA = distinctValues(conn, dialect, schema, a)
        val valuesB = distinctValues(conn, dialect, schema, b)
        val emptySample = valuesA.isEmpty() || valuesB.isEmpty()
        val ratio: Double? = if (emptySample) null else overlapRatio(valuesA, valuesB)
        // 样本为空时不跑判重 SQL(空表无重复是假象),只信索引缓存的结构信息
        val uniqueA = isUnique(conn, dialect, datasourceId, dbName, schema, a, sqlCheck = !emptySample)
        val uniqueB = isUnique(conn, dialect, datasourceId, dbName, schema, b, sqlCheck = !emptySample)

        val cardinality: RelationCardinality
        val one: ColRef
        val many: ColRef
        val remarks = ArrayList<String>(3)
        if (hitAlias != null) {
            remarks.add("经映射字段名 $hitAlias 命中")
        }
        if (emptySample) {
            remarks.add("样本为空(空表/字段全 NULL),未做值交集验证,待有数据后复核")
        }
        when {
            uniqueA && uniqueB -> {
                cardinality = RelationCardinality.ONE_TO_ONE
                val (o, m) = normalizeDirection(a, b)
                one = o; many = m
            }
            uniqueA -> {
                cardinality = RelationCardinality.ONE_TO_MANY
                one = a; many = b // one 侧存唯一方
            }
            uniqueB -> {
                cardinality = RelationCardinality.ONE_TO_MANY
                one = b; many = a
            }
            emptySample -> {
                // 空样本 + 无索引佐证:无从判重,暂按锚点侧为「一」(锚点通常是主键),待人工裁决
                cardinality = RelationCardinality.ONE_TO_MANY
                one = a; many = b
                remarks.add("无法判重,暂按锚点侧为「一」")
            }
            else -> {
                cardinality = RelationCardinality.SUSPECT_MANY_TO_MANY
                val (o, m) = normalizeDirection(a, b)
                one = o; many = m
                remarks.add("两端字段均存在重复值,疑似多对多,待人工裁决")
            }
        }
        return relationRepo.upsertDerived(datasourceId, dbName, schema,
            one.table, one.column, many.table, many.column,
            cardinality.name, source.name,
            if (emptySample) null else confidenceOf(ratio!!), ratio,
            remarks.joinToString(";").ifEmpty { null }) != null
    }

    /** 去重采样:最多 [DISTINCT_SAMPLE_LIMIT] 个 DISTINCT 非 NULL 值;统一 toString().trim(),空白串不计入 */
    private fun distinctValues(conn: Connection, dialect: DbDialect, schema: String, ref: ColRef): Set<String> {
        conn.createStatement().use { st ->
            st.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
            st.executeQuery(dialect.distinctSampleSql(schema, ref.table, ref.column, DISTINCT_SAMPLE_LIMIT)).use { rs ->
                val values = HashSet<String>()
                while (rs.next()) {
                    val v = rs.getObject(1)?.toString()?.trim()
                    if (!v.isNullOrEmpty()) values.add(v)
                }
                return values
            }
        }
    }

    /** 该侧字段是否唯一:缓存索引中唯一索引/主键单列恰好覆盖该字段 → 唯一(免查询);
     *  sqlCheck=true 时再跑判重 SQL 兜底(样本为空时调用方传 false——空表无重复是假象,判重 SQL 无意义) */
    private fun isUnique(conn: Connection, dialect: DbDialect, datasourceId: Long, dbName: String,
                         schema: String, ref: ColRef, sqlCheck: Boolean = true): Boolean {
        val byIndex = metaCacheRepo.listIndexes(datasourceId, dbName, schema, ref.table)
            .groupBy { it.indexName }
            .any { (_, rows) -> rows.first().unique && rows.size == 1 && rows[0].columnName.equals(ref.column, true) }
        if (byIndex) {
            return true
        }
        if (!sqlCheck) {
            return false // 空表判重无意义:无索引佐证按不唯一处理
        }
        conn.createStatement().use { st ->
            st.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
            st.executeQuery(dialect.hasDuplicateSql(schema, ref.table, ref.column)).use { rs ->
                return !rs.next() // 有首行=存在重复值
            }
        }
    }

    /** 字段引用(表名+字段名) */
    data class ColRef(val table: String, val column: String)

    /** 归一后的锚点字段:name 本名(经缓存实际大小写归一);aliases 映射名(trim/去空/与本名忽略大小写去重) */
    data class AnchorSpec(val name: String, val aliases: List<String> = emptyList())

    /** 候选字段对:两端引用 + 命中来源 + 名字匹配命中用的映射名(本名命中/语义命中为 null);
     *  同对被双通道命中时优先 NAME_MATCH */
    data class Candidate(val a: ColRef, val b: ColRef, val source: RelationSource, val hitAlias: String? = null)

    companion object {
        /** 单轮候选对上限:超出截断(防整库同名字段爆量) */
        const val MAX_CANDIDATES = 200

        /** 值交集采样上限(两端各取最多 N 个 DISTINCT 值) */
        const val DISTINCT_SAMPLE_LIMIT = 1000

        /** 语义表级粗筛每批表数(每批一次 LLM 调用) */
        const val TABLE_BATCH_SIZE = 100

        /** 方向无关的字段对 key(去重/已存在对判定用) */
        fun pairKey(a: ColRef, b: ColRef): String {
            val x = a.table + "." + a.column
            val y = b.table + "." + b.column
            return if (x <= y) "$x|$y" else "$y|$x"
        }

        /** 方向归一化:(表名,字段名) 字典序小者入 one 侧(ONE_TO_ONE/SUSPECT_MANY_TO_MANY 用) */
        fun normalizeDirection(a: ColRef, b: ColRef): Pair<ColRef, ColRef> =
            if (a.table < b.table || (a.table == b.table && a.column <= b.column)) a to b else b to a

        /** 值交集率:交集数/较小样本数(调用方保证两侧非空) */
        fun overlapRatio(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val smaller = if (a.size <= b.size) a else b
            val larger = if (a.size <= b.size) b else a
            return smaller.count { it in larger }.toDouble() / smaller.size
        }

        /** 置信度分级(名字/语义两通道统一):交集率 ≥0.8=HIGH;>0=MEDIUM;=0=LOW(只降置信不否决) */
        fun confidenceOf(ratio: Double): String = when {
            ratio >= 0.8 -> "HIGH"
            ratio > 0.0 -> "MEDIUM"
            else -> "LOW"
        }
    }
}
