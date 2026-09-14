package com.example.dq.service

import com.example.dq.dialect.DbDialect
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.AiScene
import com.example.dq.model.ColumnMeta
import com.example.dq.model.CompareDiffPage
import com.example.dq.model.CompareDiffRow
import com.example.dq.model.CompareExportOverviewRow
import com.example.dq.model.CompareJobDetailView
import com.example.dq.model.CompareJobView
import com.example.dq.model.CompareReportView
import com.example.dq.model.CompareTargetSpec
import com.example.dq.model.CompareTargetView
import com.example.dq.model.CreateCompareJobRequest
import com.example.dq.model.FieldDiff
import com.example.dq.model.FieldIssueRank
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.util.ExcelCells
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.math.BigDecimal
import java.sql.Types
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据比对:以一张基准表为准,与多个目标系统(数据源)中的对应表按对象身份对齐后逐字段比对,
 * 产出差异明细(compare_diff)与质量报告(compare_target 四项比率)。
 * 长时任务模型同 SampleExportService:固定 2 线程池后台执行、任务/目标/明细落 H2、
 * updateStage/updateProgress 增量进度、recoverUnfinished 启动恢复;
 * 单个目标失败只把该 target 置 FAILED 记 error 继续下一个,基准读取失败才整个任务 FAILED。
 *
 * 对象对齐(匹配)逻辑一任务一套(compare_job.match_mode,新建向导第二步选择,[MatchMode]):
 * - EXACT:对象编码与对象名称都相等才算同一对象;
 * - CODE_THEN_NAME:先用编码配,编码没配上的再用对象名称配;
 * - CODE_NAME_LLM:在 1、2 的残余之上再交大模型归一化配对(见 [CompareMatchPrompts]);
 * - 老任务(match_mode 为空)= 只按编码对齐,与历史口径完全一致。
 * 比对核心 [diffObjects](逐字段)与对象对齐 [matchObjects] 均为纯函数(companion),便于单测。
 */
class CompareService(
    private val repo: CompareRepository,
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val metadataService: MetadataService,
    private val systemSettingsService: SystemSettingsService,
    /** 表所属系统(导出总览「所属系统」列;只读本地 H2,不连业务库) */
    private val tableSystemRepo: TableSystemRepository,
    /** AI 配置(匹配逻辑 3 用);未配置时降级为「残余不配」,不炸任务 */
    private val aiConfigService: AiConfigService? = null,
    /** 匹配逻辑 3 的 LLM 调用点(默认走 [AiService.chat],测试可注入 fake) */
    private val aiChat: AiChat = { config, system, user ->
        AiService().chat(config, system, user, AiScene.COMPARE_MATCH)
    },
) {

    /** 匹配逻辑 3 的 LLM 调用点:给定可用 AI 配置与会话内容,返回模型回答 */
    fun interface AiChat {
        fun call(config: AiConfigService.Config, systemPrompt: String, userPrompt: String): String
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    /** 任务执行线程池(线程名 compare-N,守护) */
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "compare-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
    }

    /** 查询选择列:field=基准字段名(行 map 的键), column=该侧实际列名 */
    private data class SelectedCol(val field: String, val column: String)

    // ---------- 提交 / 重跑 ----------

    /**
     * 提交比对任务:同步校验(基准数据源存在、目标非空、fields 含 keyField、各表字段映射,
     * 忽略大小写;目标缺主键列直接报错,缺其他比对列记「列缺失」按不一致计)→ 落任务与目标
     * (RUNNING,total=1+目标数)→ 后台执行。返回任务 id
     */
    fun submit(req: CreateCompareJobRequest): Long {
        val name = req.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("任务名称不能为空")
        val baseDsId = req.baseDatasourceId ?: throw IllegalArgumentException("请选择基准数据源")
        val baseTable = req.baseTable?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("基准表不能为空")
        val keyField = req.keyField?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("请选择比对主键")
        val rawFields = req.fields?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
            ?: throw IllegalArgumentException("请选择比对字段")
        if (rawFields.isEmpty()) throw IllegalArgumentException("请选择比对字段")
        if (rawFields.none { it.equals(keyField, ignoreCase = true) }) {
            throw IllegalArgumentException("比对字段必须包含主键字段: $keyField")
        }
        val specs = req.targets?.filter { it.datasourceId != null && !it.table.isNullOrBlank() }
        if (specs.isNullOrEmpty()) throw IllegalArgumentException("请至少添加一个比对目标")

        // 基准侧:数据源存在 + 表字段映射(请求字段名归一为基准表实际列名,忽略大小写)
        dataSourceService.get(baseDsId)
        val baseDb = req.baseDb ?: ""
        val baseColumns = metadataService.listTableColumns(
            baseDsId, baseDb, effectiveSchema(req.baseSchema, baseDb), baseTable)
        if (baseColumns.isEmpty()) throw IllegalArgumentException("基准表不存在或没有字段: $baseTable")
        val baseByName = baseColumns.associateBy { it.name.lowercase() }
        if (baseByName[keyField.lowercase()] == null) {
            throw IllegalArgumentException("基准表不存在主键字段: $keyField")
        }
        val fields = rawFields.map { f ->
            baseByName[f.lowercase()]?.name ?: throw IllegalArgumentException("基准表不存在字段: $f")
        }
        val actualKey = baseByName.getValue(keyField.lowercase()).name
        // 对象名称(显示名)字段:用户指定优先(必须属于比对字段),未指定回退第一个文本型非主键字段;此处即解析成实际列名
        val displayField = resolveDisplayField(req.displayField, fields, baseByName, actualKey)
        // 对象对齐匹配逻辑:一任务一套;匹配逻辑 2/3 的第二路按「对象名称」字段配对,故必须显式指定该字段
        val matchMode = normalizeMatchMode(req.matchMode)
        if (matchMode.requiresName && displayField == null) {
            throw IllegalArgumentException(
                "${matchMode.label}需要按对象名称配对,请在「选择基准字段」里指定对象名称字段")
        }

        // 目标侧:数据源存在 + 表存在 + 主键列必须存在(缺其他比对列允许,比对时记「列缺失」);
        // 字段映射(第四步人工连线)可选:给出时键值都归一为双侧实际列名,且必须映射到比对主键
        val resolved = specs.map { spec ->
            val dsId = spec.datasourceId!!
            val ds = dataSourceService.get(dsId)
            val db = spec.db ?: ""
            val table = spec.table!!.trim()
            val cols = metadataService.listTableColumns(dsId, db, effectiveSchema(spec.schema, db), table)
            if (cols.isEmpty()) throw IllegalArgumentException("目标表不存在或没有字段: ${ds.name}.$table")
            val colsByName = cols.associateBy { it.name.lowercase() }
            val mapping = normalizeMapping(spec.mapping, fields, baseByName, colsByName, actualKey)
            val targetKey = mapping?.get(actualKey) ?: actualKey
            if (colsByName[targetKey.lowercase()] == null) {
                throw IllegalArgumentException("目标表缺少比对主键列: ${ds.name}.$table 无 $targetKey")
            }
            ResolvedTarget(dsId, ds.name, db, spec.schema, table,
                mapping?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) })
        }

        val jobId = repo.insertJob(name, baseDsId, baseDb, req.baseSchema, baseTable,
            actualKey, objectMapper.writeValueAsString(fields), 1 + resolved.size, displayField, matchMode.value)
        for (t in resolved) {
            repo.insertTarget(jobId, t.datasourceId, t.dsName, t.db, t.schema, t.table, t.mappingJson)
        }
        executor.execute { run(jobId) }
        log.info("比对任务已提交: id={}, 名称={}, 基准={}.{}, 目标数={}, 匹配逻辑={}",
            jobId, name, baseDb, baseTable, resolved.size, matchMode.value)
        return jobId
    }

    private data class ResolvedTarget(val datasourceId: Long, val dsName: String?,
                                      val db: String, val schema: String?, val table: String,
                                      val mappingJson: String? = null)

    /**
     * 字段映射归一:null/空 = 不指定(执行时按字段名忽略大小写自动匹配,旧行为);
     * 给出时逐条校验(基准字段必须存在且在比对字段内、目标列必须存在),返回
     * `基准表实际列名 → 目标表实际列名`;缺比对主键直接报错(否则无法按主键对齐行)。
     */
    private fun normalizeMapping(raw: Map<String, String>?, fields: List<String>,
                                 baseByName: Map<String, ColumnMeta>,
                                 targetByName: Map<String, ColumnMeta>,
                                 actualKey: String): Map<String, String>? {
        if (raw.isNullOrEmpty()) return null
        val out = LinkedHashMap<String, String>()
        for ((baseField, targetCol) in raw) {
            val bf = baseField.trim()
            val tc = targetCol?.trim().orEmpty()
            if (bf.isEmpty() || tc.isEmpty()) continue
            val baseCol = baseByName[bf.lowercase()]
                ?: throw IllegalArgumentException("字段映射的基准字段不存在: $bf")
            if (fields.none { it.equals(baseCol.name, ignoreCase = true) }) {
                throw IllegalArgumentException("字段映射的基准字段不在比对字段内: $bf")
            }
            val targetColumn = targetByName[tc.lowercase()]
                ?: throw IllegalArgumentException("字段映射的目标列不存在: $tc")
            out[baseCol.name] = targetColumn.name
        }
        if (out.isEmpty()) return null
        if (out.keys.none { it.equals(actualKey, ignoreCase = true) }) {
            throw IllegalArgumentException("字段映射必须包含比对主键: $actualKey")
        }
        return out
    }

    /** 解析落库的字段映射 JSON;解析失败按「未指定映射」处理(不炸任务,执行时回落自动匹配) */
    private fun parseMapping(json: String?): Map<String, String> =
        json?.takeIf { it.isNotBlank() }?.let {
            try {
                objectMapper.readValue<Map<String, String>>(it)
            } catch (e: Exception) {
                log.warn("字段映射 JSON 解析失败,按未指定处理: {}", e.message)
                emptyMap()
            }
        } ?: emptyMap()

    /** 重跑:仅 DONE/FAILED/CANCELED 可重跑;清空既有目标与差异明细,按原目标清单重建后重新执行 */
    fun rerun(jobId: Long) {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        if (job.status == "RUNNING") throw IllegalStateException("任务运行中,不能重跑")
        val oldTargets = repo.listTargets(jobId)
        if (oldTargets.isEmpty()) throw IllegalStateException("任务没有比对目标,不能重跑")
        repo.clearResults(jobId)
        repo.markRerun(jobId, 1 + oldTargets.size)
        for (t in oldTargets) {
            // 数据源名快照按最新库刷新(可能已改名);数据源被删则沿用旧快照,执行时该目标会失败
            val dsName = try {
                dataSourceService.get(t.datasourceId).name
            } catch (e: Exception) {
                t.dsName
            }
            repo.insertTarget(jobId, t.datasourceId, dsName, t.dbName, t.schemaName, t.tableName, t.fieldMappingJson)
        }
        executor.execute { run(jobId) }
        log.info("比对任务重跑: id={}, 目标数={}", jobId, oldTargets.size)
    }

    // ---------- 后台执行 ----------

    /** 任务执行体:读基准全量 → 逐目标比对落明细与指标 → 完成;基准失败整个任务 FAILED,单目标失败不炸任务 */
    private fun run(jobId: Long) {
        val job = repo.getJob(jobId) ?: return
        try {
            repo.updateStage(jobId, "连接数据源,读取基准表…")
            val baseDs = dataSourceService.get(job.baseDatasourceId)
            val baseDialect = dialectFactory.get(baseDs.dbType!!)
            val baseSchema = effectiveSchema(job.baseSchema, job.baseDb)
            val baseColumns = metadataService.listTableColumns(
                job.baseDatasourceId, job.baseDb, baseSchema, job.baseTable)
            val baseByName = baseColumns.associateBy { it.name.lowercase() }
            val fields = parseFields(job.fieldsJson)
            val keyColumn = baseByName[job.keyField.lowercase()]
                ?: throw IllegalStateException("基准表不存在主键字段: ${job.keyField}")
            val numericFields = fields.mapNotNull { baseByName[it.lowercase()] }
                .filter { it.isNumeric() }.map { it.name }.toSet()
            // 对象名称(显示名)字段:提交时已解析入库;老任务(列为空)按同一规则回退,保证重跑口径一致
            val displayField = job.displayField?.takeIf { it.isNotBlank() }
                ?: resolveDisplayField(null, fields, baseByName, keyColumn.name)
            // 对象对齐匹配逻辑:老任务(match_mode 空)按「只按编码」解读,与历史结果口径一致
            val mode = MatchMode.fromValue(job.matchMode)
            if (mode.requiresName && displayField == null) {
                log.warn("比对任务匹配逻辑 {} 缺少对象名称字段,退化为「只按编码对齐」: jobId={}", mode.value, jobId)
            }
            val baseMap = loadRows(job.baseDatasourceId, job.baseDb, baseSchema, job.baseTable, baseDialect,
                fields.map { SelectedCol(it, baseByName.getValue(it.lowercase()).name) }, keyColumn.name)
            repo.updateProgress(jobId, 1, "基准表读取完成(共 ${baseMap.size} 行)")

            val targets = repo.listTargets(jobId)
            var done = 1
            for (t in targets) {
                val label = t.dsName ?: "数据源${t.datasourceId}"
                repo.updateStage(jobId, "比对 $label…")
                repo.markTargetRunning(t.id)
                try {
                    compareOneTarget(job, t, fields, numericFields, displayField, mode, baseMap)
                } catch (e: Exception) {
                    log.warn("比对目标失败: jobId={}, 目标={}.{}: {}", jobId, t.dbName, t.tableName, e.message)
                    repo.failTarget(t.id, (e.message ?: "比对失败").take(2000))
                }
                done++
                repo.updateProgress(jobId, done, "比对 $label 完成")
            }
            repo.finishJob(jobId)
            log.info("比对任务完成: id={}, 目标数={}", jobId, targets.size)
        } catch (e: Exception) {
            log.error("比对任务失败: id={}", jobId, e)
            repo.failJob(jobId, (e.message ?: "比对任务失败").take(2000))
        }
    }

    /**
     * 单个目标:目标侧字段解析 → 拉目标全量 → 按匹配逻辑对齐对象 → diffObjects → 批量落明细(500/批)
     * → 算指标落 compare_target。
     * 字段解析两种口径:任务带字段映射(第四步人工连线)时只认映射(`基准列名 → 目标列名`),
     * 未映射到的基准字段不进 select、由 diffObjects 记「列缺失」;无映射时按字段名忽略大小写自动匹配(旧行为)。
     * 对齐口径按 [mode]:编码/名称两路纯对齐由 [matchObjects] 完成,匹配逻辑 3 的残余再由大模型补配;
     * 两侧实际列名都归一成基准字段名,故配对后的逐字段比较与旧口径完全一致。
     */
    private fun compareOneTarget(job: CompareRepository.JobRow, t: CompareRepository.TargetRow,
                                 fields: List<String>, numericFields: Set<String>, displayField: String?,
                                 mode: MatchMode,
                                 baseMap: LinkedHashMap<String, Map<String, String?>>) {
        val ds = dataSourceService.get(t.datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)
        val schema = effectiveSchema(t.schemaName, t.dbName)
        val cols = metadataService.listTableColumns(t.datasourceId, t.dbName, schema, t.tableName)
        if (cols.isEmpty()) throw IllegalStateException("目标表不存在或没有字段: ${t.tableName}")
        val byName = cols.associateBy { it.name.lowercase() }
        val mapping = parseMapping(t.fieldMappingJson).mapKeys { it.key.lowercase() }
        val keyColumn = if (mapping.isEmpty()) {
            byName[job.keyField.lowercase()]
                ?: throw IllegalStateException("目标表缺少比对主键列: ${job.keyField}")
        } else {
            val mapped = mapping[job.keyField.lowercase()]
                ?: throw IllegalStateException("字段映射缺少比对主键: ${job.keyField}")
            byName[mapped.lowercase()]
                ?: throw IllegalStateException("目标表缺少映射的比对主键列: $mapped")
        }
        // 只选目标侧存在的列;缺失列不进 map,diffObjects 按「列缺失」全部计不一致
        val select = if (mapping.isEmpty()) {
            fields.mapNotNull { f -> byName[f.lowercase()]?.let { SelectedCol(f, it.name) } }
        } else {
            fields.mapNotNull { f ->
                mapping[f.lowercase()]?.let { col -> byName[col.lowercase()]?.let { SelectedCol(f, it.name) } }
            }
        }
        val targetMap = loadRows(t.datasourceId, t.dbName, schema, t.tableName, dialect, select, keyColumn.name)

        // 对象对齐:编码/名称两路(纯函数),匹配逻辑 3 再对残余调用大模型归一化补配
        val nameField = displayField?.takeIf { mode.requiresName }
        var match = matchObjects(baseMap, targetMap, job.keyField, nameField, mode)
        if (mode == MatchMode.CODE_NAME_LLM) {
            val ai = aiMatchResidues(match, baseMap, targetMap, job.keyField, nameField)
            if (ai.pairs.isNotEmpty() || ai.note != null) {
                match = match.copy(pairs = match.pairs + ai.pairs, aiMatched = ai.pairs.size,
                    llmNote = ai.note, llmFailed = ai.failed)
            }
        }
        val result = diffObjects(baseMap, targetMap, fields, job.keyField, numericFields, displayField,
            match.pairs)

        val all = result.same + result.diff + result.missing + result.extra
        all.chunked(DIFF_BATCH_SIZE).forEach { batch ->
            repo.insertDiffs(job.id, t.id, batch.map { d ->
                CompareRepository.DiffInput(d.objectKey.take(500), d.objectName.take(500), d.diffType,
                    d.diffs?.let { objectMapper.writeValueAsString(truncateDiffs(it)) }, d.matchBy)
            })
        }

        val baseCount = baseMap.size
        val coverage = if (baseCount == 0) 0.0 else result.matchedCount.toDouble() / baseCount
        val consistencyDenominator = result.matchedCount.toLong() * fields.size
        val fieldConsistency = if (consistencyDenominator == 0L) 1.0
        else 1.0 - result.fieldMismatchCount.toDouble() / consistencyDenominator
        val completeness = if (result.comparedCells == 0) 1.0
        else result.nonNullCells.toDouble() / result.comparedCells
        val score = coverage * 0.4 + fieldConsistency * 0.4 + completeness * 0.2
        repo.updateTargetStats(t.id, baseCount, result.targetCount, result.matchedCount, result.missing.size,
            result.extra.size, result.fieldMismatchCount, coverage, fieldConsistency, completeness, score,
            match.codeMatched, match.nameMatched, match.aiMatched)
        if (match.llmNote != null) {
            log.info("比对目标大模型补配: jobId={}, 目标={}.{}, {}", job.id, t.dbName, t.tableName, match.llmNote)
        }
        if (match.llmFailed) {
            // 大模型补配失败不炸任务(该目标仍按编码/名称口径完成),把原因挂到目标上供人工判断
            repo.appendTargetNote(t.id, match.llmNote ?: "大模型归一化匹配失败,残余对象按未匹配处理")
        }
    }

    /**
     * 分页拉全量进内存(pageRowsSql 按主键列 orderBy,pageSize 5000),key=主键值 trim 后的字符串,
     * 行值 rs.getObject()?.toString();主键为空的行跳过;单侧超过 [MAX_SIDE_ROWS] 抛 IllegalStateException
     */
    private fun loadRows(datasourceId: Long, database: String, schema: String, table: String,
                         dialect: DbDialect, select: List<SelectedCol>, keyColumn: String)
            : LinkedHashMap<String, Map<String, String?>> {
        val map = LinkedHashMap<String, Map<String, String?>>()
        dataSourceService.getConnection(datasourceId, database.ifBlank { null }).use { conn ->
            conn.createStatement().use { stmt ->
                // 与分段扫描同口径的单条 SQL 超时(系统设置可改)
                stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
                val keyIndex = select.indexOfFirst { it.column == keyColumn } + 1
                var offset = 0L
                while (true) {
                    var pageRows = 0
                    stmt.executeQuery(dialect.pageRowsSql(conn, schema, table, select.map { it.column },
                        null, dialect.quote(keyColumn), offset, PAGE_SIZE)).use { rs ->
                        while (rs.next()) {
                            pageRows++
                            if (map.size >= MAX_SIDE_ROWS) {
                                throw IllegalStateException("单侧行数超过上限 ${MAX_SIDE_ROWS / 10000} 万,请缩小比对范围")
                            }
                            val key = rs.getObject(keyIndex)?.toString()?.trim()
                            if (key.isNullOrEmpty()) continue // 主键为空无法对齐,跳过
                            val row = LinkedHashMap<String, String?>(select.size)
                            select.forEachIndexed { i, c -> row[c.field] = rs.getObject(i + 1)?.toString() }
                            map[key] = row
                        }
                    }
                    if (pageRows < PAGE_SIZE) break
                    offset += PAGE_SIZE
                }
            }
        }
        return map
    }

    // ---------- 查询 / 归档 / 删除 ----------

    /** 任务列表(新的在前);includeArchived=true 时含已归档 */
    fun list(includeArchived: Boolean): List<CompareJobView> =
        repo.listJobs(includeArchived).map { toJobView(it) }

    /** 任务详情:任务字段 + 目标指标列表 */
    fun detail(id: Long): CompareJobDetailView {
        val job = repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        return CompareJobDetailView(toJobView(job), repo.listTargets(id).map { toTargetView(it) })
    }

    /** 差异明细分页:targetId/diffType/kw 组合过滤;diffType 非法值 400 */
    fun diffs(jobId: Long, targetId: Long?, diffType: String?, kw: String?, page: Int?, size: Int?): CompareDiffPage {
        repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val type = diffType?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (type != null && type !in DIFF_TYPES) throw IllegalArgumentException("非法差异类型: $diffType")
        val p = (page ?: 1).coerceAtLeast(1)
        val s = (size ?: 20).coerceIn(1, 500)
        val (rows, total) = repo.diffsPage(jobId, targetId, type, kw, p, s)
        return CompareDiffPage(rows.map { r ->
            CompareDiffRow(r.id, r.targetId, r.objectKey, r.objectName, r.diffType,
                parseDiffs(r.diffJson), r.matchBy)
        }, total, p, s)
    }

    fun archive(id: Long, archived: Boolean) {
        repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        repo.setArchived(id, archived)
    }

    /** 删除任务(tx 级联删三表);RUNNING 中禁止删 */
    fun delete(id: Long) {
        val job = repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        if (job.status == "RUNNING") throw IllegalStateException("任务运行中,不能删除")
        repo.deleteJob(id)
        log.info("比对任务已删除: id={}, 名称={}", id, job.name)
    }

    // ---------- 差异导出 / 质量报告 ----------

    /**
     * 差异导出(按客户既有核对表格式):
     * - sheet 1「总览」:一行一个系统(首行基准表),列口径见 [EXPORT_OVERVIEW_HEADERS]
     * - 总览里有几条差异数据(即几个比对目标)就跟着几个明细 sheet:sheet 名「序号_系统名」,
     *   序号与总览行顺序一一对应;每个 sheet 装该系统**全部**差异,粒度为字段级
     *   (DIFF 行把创建任务时勾选的每个不一致字段各列一行,MISSING/EXTRA 整行一条)
     *
     * 明细行数受 Excel 单表上限约束(1,048,576 行),超过 [MAX_ROWS_PER_SHEET] 时截断并在 sheet 内标注;
     * 系统数超过 Excel 的 sheet 上限(65530)时直接报错,避免导出损坏的文件。
     */
    fun exportDiff(jobId: Long, out: OutputStream) {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val targets = repo.listTargets(jobId)
        if (targets.size > MAX_SHEETS - 1) {
            throw IllegalStateException("比对系统数超过 Excel 上限(${MAX_SHEETS - 1}),无法导出为比对报告")
        }
        val counts = repo.countForExport(jobId)
        val context = exportContext(job, targets)
        val overview = buildOverviewRows(job, targets, counts, context)

        SXSSFWorkbook(SXSSF_ROW_WINDOW).use { wb ->
            writeOverviewSheet(wb, overview)
            // 总览里的每个目标行一个明细 sheet,序号与总览行顺序一致;每目标明细只从库读一次
            val used = mutableSetOf(OVERVIEW_SHEET_NAME)
            var idx = 0
            for (t in targets) {
                val diffs = repo.listDiffsForExport(jobId, t.id)
                if (diffs.isEmpty()) continue
                val sheet = wb.createSheet(
                    ExcelCells.sheetName("${++idx}_${t.dsName ?: "数据源" + t.datasourceId}", used))
                writeDiffDetailSheet(sheet, job, t, counts[t.id].orEmpty(), overviewSummary(overview, t.id),
                    diffs, context)
                sheet.flushRows() // 行写盘,避免多个 sheet 同时驻留内存(临时文件由 wb.close() 统一清理)
            }
            wb.write(out)
        } // use 块关闭工作簿并清理临时文件(dispose 已废弃,close 已覆盖)
    }

    /** 总览行构建(纯函数,便于单测):第一行为基准表本身(无目标 id、与基准差留空),之后一行一个比对目标 */
    internal fun buildOverviewRows(job: CompareRepository.JobRow, targets: List<CompareRepository.TargetRow>,
                                   counts: Map<Long, Map<String, Int>>,
                                   ctx: ExportContext): List<CompareExportOverviewRow> {
        val baseCount = targets.mapNotNull { it.baseCount }.maxOrNull()
        val rows = ArrayList<CompareExportOverviewRow>(targets.size + 1)
        rows.add(CompareExportOverviewRow(
            tableName = job.baseTable,
            tableComment = ctx.comment(job.baseDatasourceId, job.baseDb, job.baseTable),
            systemName = ctx.systemName(job.baseDatasourceId, job.baseDb, job.baseTable),
            rowCount = baseCount,
            dataUpdatedAt = DATA_UPDATED_AT_PLACEHOLDER,
            diffFromBase = null,
            matchedCount = null,
            diffCount = null,
            diffReason = BASELINE_REASON,
            targetId = null,
        ))
        // 基准行数缺失时退化为各目标自报的 base_count,保证「与基准差」仍可计算
        val perTargetBase = targets.mapNotNull { it.baseCount }.firstOrNull() ?: baseCount
        for (t in targets) {
            val targetCount = t.targetCountOrFallback()
            rows.add(CompareExportOverviewRow(
                tableName = t.tableName,
                tableComment = ctx.comment(t.datasourceId, t.dbName, t.tableName),
                systemName = ctx.systemName(t.datasourceId, t.dbName, t.tableName),
                rowCount = targetCount,
                dataUpdatedAt = DATA_UPDATED_AT_PLACEHOLDER,
                diffFromBase = if (targetCount != null && perTargetBase != null) targetCount - perTargetBase else null,
                matchedCount = t.matchedCount,
                // 差异条数 = 非 SAME 差异行数(缺失 + 多余 + 不一致对象,不含 SAME)
                diffCount = counts[t.id].orEmpty().values.sum(),
                diffReason = t.diffReason(baseCount = perTargetBase, targetCount = targetCount),
                targetId = t.id,
            ))
        }
        return rows
    }

    /**
     * 导出上下文(总览附属信息 + 明细表头的中文字段名):
     * - 表中文名/所属系统按「数据源 + 库 + schema」预取一次
     * - 字段注释按需惰性查一次并缓存(明细 sheet 表头用字段注释做中文列名,无注释回落字段名)
     * 元数据不可达、table_system 无登记都静默降级,导出必须能出文件。
     */
    internal data class ExportContext(
        private val comments: Map<String, String> = emptyMap(),
        private val systems: Map<String, String> = emptyMap(),
        private val fallbackSystem: Map<Long, String?> = emptyMap(),
        /** 明细表头用的字段注释:表键 → (字段小写 → 注释),由 exportContext 预取 */
        private val columnComments: Map<String, Map<String, String>> = emptyMap(),
    ) {
        /** 表中文名:表注释,取不到返回 null */
        fun comment(datasourceId: Long, db: String, table: String): String? =
            comments[key(datasourceId, db, table)]

        /** 所属系统:table_system 登记值,未登记回落数据源名 */
        fun systemName(datasourceId: Long, db: String, table: String): String? =
            systems[key(datasourceId, db, table)] ?: fallbackSystem[datasourceId]

        /** 明细表头:字段注释优先(表中文名口径),无注释回落字段名 */
        fun fieldHeader(datasourceId: Long, db: String, table: String, field: String): String =
            columnComments[key(datasourceId, db, table)]?.get(field.lowercase()) ?: field

        companion object {
            /** 上下文键:数据源 + 库 + 表名(库名/表名忽略大小写) */
            fun key(datasourceId: Long, db: String, table: String): String =
                "$datasourceId\u0000${db.lowercase()}\u0000${table.lowercase()}"
        }
    }

    /** 预取导出上下文:表注释 + 所属系统 + 明细表头字段注释(元数据失败静默为空;所属系统读本地 H2) */
    private fun exportContext(job: CompareRepository.JobRow,
                              targets: List<CompareRepository.TargetRow>): ExportContext {
        val comments = HashMap<String, String>()
        val systems = HashMap<String, String>()
        val fallbackSystem = HashMap<Long, String?>()
        val columnComments = HashMap<String, Map<String, String>>()
        // 取样范围 = 数据源 + 库 + schema:各目标 schema 各自快照在 compare_target 上,不能只用库名推
        val scopes = LinkedHashSet<Triple<Long, String, String>>()
        scopes.add(Triple(job.baseDatasourceId, job.baseDb,
            effectiveSchema(job.baseSchema, job.baseDb)))
        targets.forEach {
            scopes.add(Triple(it.datasourceId, it.dbName, effectiveSchema(it.schemaName, it.dbName)))
        }
        for ((dsId, db, schema) in scopes) {
            fallbackSystem[dsId] = try {
                dataSourceService.get(dsId).name
            } catch (e: Exception) {
                null // 数据源已删除:所属系统留空(基准回落空串),不影响导出
            }
            try {
                for (t in metadataService.listTables(dsId, db.ifBlank { null }, schema)) {
                    val name = t.name ?: continue
                    t.comment?.takeIf { it.isNotBlank() }?.let { comments[ExportContext.key(dsId, db, name)] = it }
                }
            } catch (e: Exception) {
                log.debug("导出取表注释失败(忽略): 数据源{} 库{} schema{}: {}", dsId, db, schema, e.message)
            }
            try {
                for ((table, system) in tableSystemRepo.findBySchema(dsId, db, schema)) {
                    if (system.isNotBlank()) systems[ExportContext.key(dsId, db, table)] = system
                }
            } catch (e: Exception) {
                log.debug("导出取所属系统失败(忽略): 数据源{} 库{} schema{}: {}", dsId, db, schema, e.message)
            }
        }
        // 明细表头的字段注释:无注释的字段回落字段名,取不到只记 debug
        for (t in targets) {
            val tableKey = ExportContext.key(t.datasourceId, t.dbName, t.tableName)
            if (columnComments.containsKey(tableKey)) continue
            try {
                columnComments[tableKey] = metadataService.listTableColumns(
                    t.datasourceId, t.dbName.ifBlank { null },
                    effectiveSchema(t.schemaName, t.dbName), t.tableName)
                    .filter { !it.comment.isNullOrBlank() }
                    .associate { it.name.lowercase() to it.comment!! }
            } catch (e: Exception) {
                log.debug("导出取字段注释失败(忽略): 数据源{} 库{} 表{}: {}",
                    t.datasourceId, t.dbName, t.tableName, e.message)
            }
        }
        return ExportContext(comments, systems, fallbackSystem, columnComments)
    }

    /** 总览 sheet:表头 + 一行一系统(首行基准表,与后续明细 sheet 序号一一对应) */
    private fun writeOverviewSheet(wb: SXSSFWorkbook, overview: List<CompareExportOverviewRow>) {
        val sheet = wb.createSheet(OVERVIEW_SHEET_NAME)
        val head = sheet.createRow(0)
        EXPORT_OVERVIEW_HEADERS.forEachIndexed { i, h -> head.createCell(i).setCellValue(h) }
        var r = 1
        for (row in overview) {
            val excelRow = sheet.createRow(r++)
            excelRow.createCell(0).setCellValue(row.tableComment ?: "")
            excelRow.createCell(1).setCellValue(row.tableName)
            excelRow.createCell(2).setCellValue(row.systemName ?: "")
            ExcelCells.cell(excelRow.createCell(3), row.rowCount)
            excelRow.createCell(4).setCellValue(row.dataUpdatedAt ?: "")
            ExcelCells.cell(excelRow.createCell(5), row.diffFromBase)
            ExcelCells.cell(excelRow.createCell(6), row.matchedCount)
            ExcelCells.cell(excelRow.createCell(7), row.diffCount)
            excelRow.createCell(8).setCellValue(row.diffReason ?: UNFINISHED_REASON)
        }
        sheet.flushRows()
    }

    /**
     * 明细 sheet(精简版,对齐客户既有核对表):
     * - 第 1 行:单行上下文 `系统 · 目标表 ← 基准表 · 主键 · 目标 N 行/基准 M 行 · 差异构成`,第 2 行留空
     * - 第 3 行:表头 = 各行 diff_json 出现过的字段(创建任务时勾选的基准表字段,用字段注释做中文名,
     *   无注释回落字段名)+ 末列「说明」
     * - 其后一行一个差异对象,一格一个对象:字段取该字段的目标取值(缺失行没有目标取值则显示基准值),
     *   用「说明」解释差异:MISSING→「基准有目标无」、EXTRA→「目标有基准无」、
     *   DIFF→「中文列名: 基准「基准值」→ 目标「目标值」」(见 [describeDiff])
     *
     * 行数超 [MAX_ROWS_PER_SHEET] 时截断并追加说明行(Excel 单表上限兜底)。
     */
    private fun writeDiffDetailSheet(sheet: SXSSFSheet, job: CompareRepository.JobRow,
                                     t: CompareRepository.TargetRow, counts: Map<String, Int>,
                                     summary: CompareExportOverviewRow?,
                                     rows: List<CompareRepository.DiffRow>, ctx: ExportContext) {
        val system = summary?.systemName ?: t.dsName ?: "数据源${t.datasourceId}"
        val targetTable = listOfNotNull(t.dbName.takeIf { it.isNotBlank() }, t.tableName).joinToString(".")
        val baseTable = listOfNotNull(job.baseDb.takeIf { it.isNotBlank() }, job.baseTable).joinToString(".")
        val context = buildString {
            append(system).append(" · ").append(targetTable).append(" ← ").append(baseTable)
            append(" · 主键 ").append(job.keyField)
            append(" · ").append(countDesc(summary))
            append(" · ").append(countComposition(counts))
        }
        sheet.createRow(0).createCell(0).setCellValue(context) // 第 2 行留空

        // 列 = 各行 diff_json 出现过的字段,按创建任务时的字段顺序排(字段级粒度即这几个基准表字段);
        // 老任务/异常数据没有 diff_json 时兜底用任务勾选的全部字段列
        val fields = parseFields(job.fieldsJson)
        val seen = LinkedHashSet<String>()
        rows.forEach { seen.addAll(parseValueMap(it.diffJson).keys) }
        val columns = (
            if (seen.isEmpty()) fields
            else fields.filter { it in seen } + seen.filter { it !in fields }
            ).toMutableList()

        var r = 2
        val head = sheet.createRow(r++)
        columns.forEachIndexed { i, f ->
            head.createCell(i).setCellValue(ctx.fieldHeader(t.datasourceId, t.dbName, t.tableName, f))
        }
        head.createCell(columns.size).setCellValue(LAST_COLUMN_HEADER)

        var written = 0
        var truncated = false
        for (row in rows) {
            if (written >= MAX_ROWS_PER_SHEET) {
                truncated = true
                break
            }
            val excelRow = sheet.createRow(r++)
            val rowMap = parseValueMap(row.diffJson)
            columns.forEachIndexed { i, f ->
                val cell = rowMap[f]
                // 字段取值:优先目标侧(一行一格一个对象);缺失行没有目标值则显示基准值
                excelRow.createCell(i).setCellValue(
                    when {
                        cell == null -> ""
                        row.diffType == "EXTRA" -> cell.value.orEmpty()
                        cell.value == null -> cell.base.orEmpty()
                        else -> cell.value
                    })
            }
            excelRow.createCell(columns.size).setCellValue(
                describeDiff(row.diffType, rowMap) { f -> ctx.fieldHeader(t.datasourceId, t.dbName, t.tableName, f) })
            written++
        }
        if (truncated) {
            sheet.createRow(r).createCell(0)
                .setCellValue("(差异行数超过 $MAX_ROWS_PER_SHEET 行,已截断;完整明细请用差异明细页面筛选查看)")
        }
    }

    /** 解析 diff_json 为「字段 → (基准值, 目标值)」;MISSING/EXTRA 行同样是整行快照 */
    private fun parseValueMap(json: String?): Map<String, FieldDiff> =
        parseDiffs(json).orEmpty().associateBy { it.field }

    /**
     * 该字段是否不一致:新数据看显式 [FieldDiff.matched];旧数据/中间版本(matched=null)按旧契约
     * 「value 非空即不一致」解读(旧 DIFF 行只存不一致字段;中间版本一致字段 value 为 null)。
     */
    private fun FieldDiff.isMismatch(): Boolean = matched?.let { !it } ?: (value != null)

    /**
     * 一行差异的「说明」文案。
     * - MISSING/EXTRA 整行粒度,沿用简短口径;
     * - DIFF 逐字段展开:字段名用中文列名(与表头同口径,无注释回落字段名),
     *   显式标注「基准 → 目标」方向;空值显示「(空)」,目标缺列单独说明
     */
    private fun describeDiff(diffType: String, rowMap: Map<String, FieldDiff>,
                             labelOf: (String) -> String): String =
        when (diffType) {
            "MISSING" -> "基准有目标无"
            "EXTRA" -> "目标有基准无"
            else -> rowMap.values.filter { it.isMismatch() }
                .joinToString(";") { d ->
                    val label = labelOf(d.field)
                    when {
                        d.value == MISSING_COLUMN_MARK -> "$label: 目标无此列(基准为「${d.base.orEmpty().ifEmpty { "(空)" }}」)"
                        else -> "$label: 基准「${d.base?.takeIf { it.isNotEmpty() } ?: "(空)"}」→ 目标「${d.value?.takeIf { it.isNotEmpty() } ?: "(空)"}」"
                    }
                }
                .ifEmpty { "字段不一致" }
        }

    /** 目标「条数」描述:目标 N 行 / 基准 M 行;缺数时给出可读占位 */
    private fun countDesc(summary: CompareExportOverviewRow?): String {
        val target = summary?.rowCount?.toString() ?: "—"
        val base = summary?.let { s ->
            // 基准行数 = 目标行数 − 与基准差;差为空说明两侧有一侧缺数,退回 — 展示
            if (s.rowCount != null && s.diffFromBase != null) (s.rowCount - s.diffFromBase).toString() else "—"
        } ?: "—"
        return "目标 $target 行 / 基准 $base 行"
    }

    /** 差异构成描述:缺失 x 条、多余 y 条、不一致 z 条 */
    private fun countComposition(counts: Map<String, Int>): String {
        if (counts.isEmpty()) return "无差异"
        return DIFF_TYPE_ORDER.mapNotNull { type ->
            val n = counts[type] ?: 0
            if (n <= 0) null else "${DIFF_TYPE_LABELS[type] ?: type} $n 条"
        }.joinToString("、").ifEmpty { "无差异" }
    }

    /** 取单目标总览行(明细 sheet 的条数/差异原因与总览保持同一口径) */
    private fun overviewSummary(overview: List<CompareExportOverviewRow>, targetId: Long): CompareExportOverviewRow? =
        overview.firstOrNull { it.targetId == targetId }

    /** 老任务没有 target_count 时兜底:匹配数 + 多余数(等于目标侧实际行数) */
    private fun fallbackTargetCount(t: CompareRepository.TargetRow): Int? =
        if (t.matchedCount != null && t.extraCount != null) t.matchedCount + t.extraCount else null

    /** 目标行未落 target_count 时的展示用条数 */
    private fun CompareRepository.TargetRow.targetCountOrFallback(): Int? = targetCount ?: fallbackTargetCount(this)

    /** 差异原因:自动按差异构成拼写(可导出后人工补充);目标未完成时给出明确说明 */
    private fun CompareRepository.TargetRow.diffReason(baseCount: Int?, targetCount: Int?): String? {
        if (status != "DONE") return "比对未完成($status)" + (error?.let { ":$it" } ?: "")
        val extra = extraCount ?: 0
        val missing = missingCount ?: 0
        val mismatch = fieldMismatchCount ?: 0
        if (extra == 0 && missing == 0 && mismatch == 0) return "与基准完全一致"
        val parts = ArrayList<String>(4)
        if (missing > 0) parts.add("基准有目标无的对象 $missing 条")
        if (extra > 0) parts.add("目标有基准无的对象 $extra 条")
        if (mismatch > 0) parts.add("字段不一致单元格 $mismatch 处")
        if (baseCount != null && targetCount != null && baseCount != targetCount) {
            parts.add("行数相差 ${targetCount - baseCount}(目标 $targetCount / 基准 $baseCount)")
        }
        return parts.joinToString(";")
    }

    /** 质量报告:目标指标 + 问题字段排行(解析 DIFF 行 diff_json 按字段计数取前 10)+ 汇总 */
    fun report(jobId: Long): CompareReportView {
        repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val targets = repo.listTargets(jobId).map { toTargetView(it) }
        val counts = repo.countByDiffType(jobId)
        val fieldCounts = HashMap<String, Long>()
        for (json in repo.listDiffJsons(jobId)) {
            // diff_json 为整行快照:以显式 matched 判定,new 契约下一致字段也有 value
            for (d in parseDiffs(json).orEmpty()) {
                if (d.isMismatch()) fieldCounts.merge(d.field, 1L, Long::plus)
            }
        }
        val fieldIssues = fieldCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(FIELD_ISSUE_TOP_N)
            .map { FieldIssueRank(it.key, it.value) }
        val consistencies = targets.mapNotNull { it.fieldConsistency }
        return CompareReportView(
            targets = targets,
            fieldIssues = fieldIssues,
            baseCount = targets.mapNotNull { it.baseCount }.maxOrNull() ?: 0,
            sameCount = counts["SAME"] ?: 0,
            diffObjectCount = counts["DIFF"] ?: 0,
            missingTotal = counts["MISSING"] ?: 0,
            extraTotal = counts["EXTRA"] ?: 0,
            avgFieldConsistency = if (consistencies.isEmpty()) 1.0 else consistencies.average(),
        )
    }

    /** 服务重启恢复:残留 RUNNING 任务置 FAILED(ServiceEnv.initDatabase 装配时调用一次) */
    fun recoverUnfinished() {
        val n = repo.failRunningOnStartup("应用重启,任务中断")
        repo.failUnfinishedTargets("应用重启,任务中断")
        if (n > 0) {
            log.warn("服务重启,{} 个未完成的比对任务已置为失败", n)
        }
    }

    // ---------- 内部辅助 ----------

    private fun toJobView(r: CompareRepository.JobRow): CompareJobView {
        val dsName = try {
            dataSourceService.get(r.baseDatasourceId).name
        } catch (e: Exception) {
            null // 数据源已删除:名称留空,不影响任务展示
        }
        return CompareJobView(
            r.id, r.name, r.baseDatasourceId, dsName, r.baseDb, r.baseSchema, r.baseTable, r.keyField,
            r.displayField, r.matchMode, parseFields(r.fieldsJson), r.status, r.stage, r.totalUnits, r.doneUnits,
            if (r.totalUnits > 0) r.doneUnits * 100 / r.totalUnits else 0,
            r.error, r.archived, r.createdAt, r.startedAt, r.finishedAt,
            r.startedAt?.let { Duration.between(it, r.finishedAt ?: LocalDateTime.now()).toMillis() })
    }

    private fun toTargetView(t: CompareRepository.TargetRow) = CompareTargetView(
        t.id, t.datasourceId, t.dsName, t.dbName, t.schemaName, t.tableName, t.status,
        t.baseCount, t.targetCount, t.matchedCount,
        // 老任务没有匹配来源计数:编码命中视为全部命中,名称/大模型补配记 0
        t.codeMatchedCount ?: t.matchedCount, t.nameMatchedCount ?: 0, t.aiMatchedCount ?: 0,
        t.missingCount, t.extraCount, t.fieldMismatchCount,
        t.coverage, t.fieldConsistency, t.completeness, t.score, t.error,
        parseMapping(t.fieldMappingJson).takeIf { it.isNotEmpty() })

    private fun parseFields(json: String?): List<String> =
        json?.let {
            try {
                objectMapper.readValue<List<String>>(it)
            } catch (e: Exception) {
                log.warn("比对字段 JSON 解析失败: {}", e.message)
                emptyList()
            }
        } ?: emptyList()

    private fun parseDiffs(json: String?): List<FieldDiff>? =
        json?.let {
            try {
                objectMapper.readValue<List<FieldDiff>>(it)
            } catch (e: Exception) {
                log.warn("差异明细 JSON 解析失败: {}", e.message)
                null
            }
        }

    /** diff_json 值截断:防 CLOB/大字段撑爆明细表 */
    private fun truncateDiffs(diffs: List<FieldDiff>): List<FieldDiff> =
        diffs.map { d ->
            FieldDiff(d.field, d.base?.take(MAX_DIFF_VALUE_CHARS), d.value?.take(MAX_DIFF_VALUE_CHARS), d.matched)
        }

    // ---------- 匹配逻辑 3:大模型归一化补配(实例侧,复用注入的 LLM 调用点) ----------

    /** 匹配逻辑 3 的补配结果 */
    private data class AiMatchOutcome(val pairs: List<MatchedPair>, val note: String?, val failed: Boolean)

    /** [AiMatchOutcome] 的对外视图(单测断言用,不暴露内部实现) */
    internal data class AiMatchView(val pairs: List<MatchedPair>, val note: String?, val failed: Boolean)

    /** 单测入口:直接对给定残余跑一轮大模型补配(生产路径由 compareOneTarget 调用) */
    internal fun aiMatchResiduesForTest(match: MatchResult,
                                        baseMap: LinkedHashMap<String, Map<String, String?>>,
                                        targetMap: Map<String, Map<String, String?>>,
                                        keyField: String, nameField: String?): AiMatchView {
        val outcome = aiMatchResidues(match, baseMap, targetMap, keyField, nameField)
        return AiMatchView(outcome.pairs, outcome.note, outcome.failed)
    }

    /**
     * 匹配逻辑 3:把编码/名称两路都没配上的双侧残余对象交大模型归一化配对。
     * - 没有残余 / 未配置大模型 / 残余超过 [LLM_RESIDUE_LIMIT] → 不调用模型,残余保持缺失/多余,并给出说明;
     * - 否则按「基准侧分批 × 目标侧全量残余」组 prompt(单批配对数受
     *   [CompareMatchPrompts.MAX_PAIRS_PER_REQUEST] 约束),逐批解析配对;
     * - 单个批次失败只跳过该批(记说明并置 failed)继续下一批,**绝不因为模型返工而炸任务**。
     */
    private fun aiMatchResidues(match: MatchResult, baseMap: LinkedHashMap<String, Map<String, String?>>,
                                targetMap: Map<String, Map<String, String?>>, keyField: String,
                                nameField: String?): AiMatchOutcome {
        val (baseResidueKeys, targetResidueKeys) = match.residues(baseMap, targetMap)
        if (baseResidueKeys.isEmpty() || targetResidueKeys.isEmpty()) return AiMatchOutcome(emptyList(), null, false)
        val config = aiConfigService?.findConfig()
        if (config == null) {
            return AiMatchOutcome(emptyList(),
                "未配置大模型,${baseResidueKeys.size} 条基准侧残余与 ${targetResidueKeys.size} 条目标侧残余按未匹配处理",
                false)
        }
        if (baseResidueKeys.size > LLM_RESIDUE_LIMIT || targetResidueKeys.size > LLM_RESIDUE_LIMIT) {
            return AiMatchOutcome(emptyList(),
                "残余对象过多(基准 ${baseResidueKeys.size} 条 / 目标 ${targetResidueKeys.size} 条,上限 " +
                    "$LLM_RESIDUE_LIMIT),未做归一化补配", false)
        }

        val baseSeqKeys = baseResidueKeys.withIndex().associate { (i, k) -> (i + 1) to k }
        val targetSeqKeys = targetResidueKeys.withIndex().associate { (i, k) -> (i + 1) to k }
        val baseItems = baseResidueKeys.mapIndexed { i, k ->
            CompareMatchPrompts.MatchItem(i + 1, idValue(baseMap.getValue(k), keyField),
                nameField?.let { idValue(baseMap.getValue(k), it) })
        }
        val targetItems = targetResidueKeys.mapIndexed { i, k ->
            CompareMatchPrompts.MatchItem(i + 1, idValue(targetMap.getValue(k), keyField),
                nameField?.let { idValue(targetMap.getValue(k), it) })
        }
        // 单批基准侧条数:保证「批内基准 × 目标全量」不超过单请求配对数上限,且不少于下限(避免批次过碎漏配)
        val batchSize = (CompareMatchPrompts.MAX_PAIRS_PER_REQUEST / targetItems.size)
            .coerceAtLeast(LLM_MIN_BATCH_SIZE)
        val pairs = ArrayList<MatchedPair>()
        val errors = ArrayList<String>()
        val usedBaseKeys = HashSet<String>()
        val usedTargetKeys = HashSet<String>()
        for (batch in baseItems.chunked(batchSize)) {
            val prompt = CompareMatchPrompts.buildMatchPrompt(keyField, nameField ?: "名称",
                keyField, nameField ?: "名称", batch, targetItems)
            val answer = try {
                aiChat.call(config, CompareMatchPrompts.SYSTEM_PROMPT, prompt)
            } catch (e: Exception) {
                log.warn("大模型归一化补配调用失败(跳过该批): {}", e.message)
                if (errors.size < LLM_ERROR_KEEP) errors.add(abbreviate(e.message))
                continue
            }
            val parsed = CompareMatchPrompts.parsePairs(answer, batch.map { it.seq },
                targetItems.map { it.seq })
            for (p in parsed) {
                val baseKey = baseSeqKeys[p.baseSeq] ?: continue
                val targetKey = targetSeqKeys[p.targetSeq] ?: continue
                if (!usedBaseKeys.add(baseKey) || !usedTargetKeys.add(targetKey)) continue
                pairs.add(MatchedPair(baseKey, targetKey, "LLM"))
            }
        }
        if (errors.isEmpty()) return AiMatchOutcome(pairs, null, false)
        return AiMatchOutcome(pairs,
            "大模型归一化补配部分批次失败(${errors.joinToString(";")}),失败批次的对象按未匹配处理", true)
    }

    /** 错误信息缩写:进目标备注/日志前限长 */
    private fun abbreviate(message: String?): String =
        (message ?: "未知错误").let { if (it.length <= 200) it else it.take(200) + "…" }

    companion object {
        /** 任务执行线程池的线程序号(线程命名 compare-N) */
        private val THREAD_IDX = AtomicInteger()

        /** 分页拉全量的每页行数 */
        const val PAGE_SIZE = 5000

        /** 单侧行数上限:超出抛 IllegalStateException,任务判 FAILED */
        const val MAX_SIDE_ROWS = 500_000

        /** 差异明细批量落库的每批行数 */
        private const val DIFF_BATCH_SIZE = 500

        /**
         * diff_json 单值截断长度:匹配逻辑 2/3 下同一对象可能两侧编码/名称都不同,
         * 说明文案(基准「…」→ 目标「…」)会明显变长,故放宽到 12000 字符防截断误导。
         */
        private const val MAX_DIFF_VALUE_CHARS = 12000

        /** 问题字段排行取前 N */
        private const val FIELD_ISSUE_TOP_N = 10

        /** 目标缺列时 diff_json 里 value 的特殊标记 */
        const val MISSING_COLUMN_MARK = "«列缺失»"

        /** 大模型归一化补配的参与总量上限(双侧残余各不超过该值才送模型) */
        const val LLM_RESIDUE_LIMIT = 2000

        /** 单次请求最少送多少条基准侧对象(与目标侧残余的乘积受 CompareMatchPrompts.MAX_PAIRS_PER_REQUEST 约束) */
        const val LLM_MIN_BATCH_SIZE = 5

        /** 单次请求失败的累计记录上限(避免 note 过长) */
        private const val LLM_ERROR_KEEP = 3

        /** 匹配逻辑归一:空 = EXACT(新建默认);未知值报 400,避免静默按别的口径跑 */
        internal fun normalizeMatchMode(raw: String?): MatchMode = MatchMode.normalize(raw)

        private val DIFF_TYPES = setOf("SAME", "DIFF", "MISSING", "EXTRA")

        private val DIFF_TYPE_LABELS = mapOf(
            "SAME" to "一致", "DIFF" to "不一致", "MISSING" to "缺失", "EXTRA" to "多余")

        /** 导出总览 sheet 名(固定第一个 sheet) */
        const val OVERVIEW_SHEET_NAME = "总览"

        /** 总览 sheet 表头(与客户既有核对表列口径一致) */
        private val EXPORT_OVERVIEW_HEADERS = listOf(
            "表中文名", "表英文名称", "所属系统", "条数", "数据最新更新时间", "与基准差", "匹配编码数",
            "差异条数", "差异原因")

        /** 明细 sheet 末列表头:解释该行差异(缺失/多余/字段级不一致) */
        private const val LAST_COLUMN_HEADER = "说明"

        /** 差异构成展示顺序 */
        private val DIFF_TYPE_ORDER = listOf("MISSING", "EXTRA", "DIFF")

        /** 「数据最新更新时间」当前未采集,导出统一占位 */
        private const val DATA_UPDATED_AT_PLACEHOLDER = "—"

        /** 基准行差异原因占位 */
        private const val BASELINE_REASON = "基准表(不参与差异统计)"

        /** 目标未完成时的差异原因兜底 */
        private const val UNFINISHED_REASON = "比对未完成,无差异明细"

        /** 明细 sheet 的最大数据行数(Excel 单表上限 1,048,576 行,留出上下文与表头余量) */
        const val MAX_ROWS_PER_SHEET = 1_000_000

        /** Excel 单工作簿 sheet 上限(65530),总览占 1 个 */
        const val MAX_SHEETS = 65_530

        /** SXSSF 滑动窗口行数 */
        private const val SXSSF_ROW_WINDOW = 200

        /** schema 归一:未填时用库名兜底(MySQL 场景 schema=库名,与抽样导出口径一致) */
        internal fun effectiveSchema(schema: String?, db: String): String =
            schema?.takeIf { it.isNotBlank() } ?: db.takeIf { it.isNotBlank() } ?: ""

        /** 是否文本型字段(显示名选取口径:字符型或 CLOB) */
        internal fun isTextType(c: ColumnMeta): Boolean =
            c.isCharacter() || c.jdbcType == Types.CLOB || c.jdbcType == Types.NCLOB

        /**
         * 对象名称(显示名)字段解析:
         * - 用户指定优先:`specified` 必须属于 `fields`(忽略大小写),归一为基准表实际列名;
         * - 未指定则回退「比对字段中第一个文本型非主键字段」;
         * - 都没有返回 null(object_name 落空串)。
         * `fields` 需为基准表实际列名,返回值可直接作为行 map 的键(与 displayName 取值口径一致)。
         */
        internal fun resolveDisplayField(specified: String?, fields: List<String>,
                                         baseByName: Map<String, ColumnMeta>, keyName: String): String? {
            val picked = specified?.trim()?.takeIf { it.isNotEmpty() }
            if (picked != null) {
                if (fields.none { it.equals(picked, ignoreCase = true) }) {
                    throw IllegalArgumentException("对象名称字段必须在比对字段内: $picked")
                }
                return baseByName[picked.lowercase()]?.name
                    ?: throw IllegalArgumentException("基准表不存在字段: $picked")
            }
            return fields.mapNotNull { baseByName[it.lowercase()] }
                .firstOrNull { !it.name.equals(keyName, ignoreCase = true) && isTextType(it) }?.name
        }

        /** 单条对象差异:diffs 仅 DIFF 行有值 */
        /** 单条对象差异:diffs 仅 DIFF 行有值;matchBy 为该行对象的对齐来源(CODE/NAME/LLM) */
        data class ObjectDiff(val objectKey: String, val objectName: String,
                              val diffType: String, val diffs: List<FieldDiff>?,
                              val matchBy: String? = null)

        /** 比对结果:四类明细 + 算指标所需计数(comparedCells=matched×比对字段数,即目标侧已比对单元格数) */
        data class CompareDiffResult(
            val same: List<ObjectDiff>,
            val diff: List<ObjectDiff>,
            val missing: List<ObjectDiff>,
            val extra: List<ObjectDiff>,
            /** 双侧都存在的对象数(SAME+DIFF) */
            val matchedCount: Int,
            /** 目标侧实际行数(含多余行),总览表「条数」与「与基准差」用 */
            val targetCount: Int,
            /** 不一致字段单元格总数(含「列缺失」) */
            val fieldMismatchCount: Int,
            /** 目标侧已比对单元格数(matched × fields.size) */
            val comparedCells: Int,
            /** 其中非空单元格数(列缺失按空计) */
            val nonNullCells: Int,
            /** 按编码对齐上的对象数 */
            val codeMatched: Int,
            /** 按对象名称补配上的对象数 */
            val nameMatched: Int,
            /** 大模型归一化补配上的对象数 */
            val aiMatched: Int,
        )

        /**
         * 比对核心纯函数:把已经对齐好的对象逐字段比较,产出四类明细与指标计数。
         * 行 map 的键为基准字段名(目标侧列名在取数时已归一成基准字段名);目标行只含目标侧存在的列,
         * 缺列(key 不在行 map)记「列缺失」按不一致计。
         * 比较规则:字符串 trim;NULL 与空串视为一致;numericFields 中的字段去千分位逗号后转
         * BigDecimal 用 compareTo==0 判定(解析失败回落字符串比较);主键字段对命中行恒一致。
         *
         * 对象对齐(appliedPairs,见 [matchObjects] 与实例侧大模型补配):为空时退化为「两侧行键相等」的
         * 旧口径(老测试与直接调用方);给出时按配对逐对比较,两侧键不等的对象(靠名称/大模型配上的)也算命中,
         * 明细的 object_key 统一取**基准侧键**(配对行以基准对象为视角),两侧各自的键值差异照常在字段级体现。
         * 配对里任一侧不在行 map(防御脏数据)时该配对忽略,对应的行退回缺失/多余。
         *
         * DIFF/MISSING/EXTRA 三类明细都会为**全部比对字段**各生成一条 [FieldDiff],这样界面与导出
         * 单看 diff_json 就能「每格显示真实值」:
         * - DIFF 行:base=基准值、value=目标真实值(一致字段也有值),matched 标明该字段是否一致;
         * - MISSING 行:base=基准值、value 为 null(目标整行不存在),matched 恒 false;
         * - EXTRA 行:base 为 null(基准整行不存在)、value=目标真实值,matched 恒 false。
         */
        fun diffObjects(baseMap: LinkedHashMap<String, Map<String, String?>>,
                        targetMap: Map<String, Map<String, String?>>,
                        fields: List<String>, keyField: String,
                        numericFields: Set<String> = emptySet(),
                        displayField: String? = null,
                        appliedPairs: List<MatchedPair> = emptyList()): CompareDiffResult {
            val same = ArrayList<ObjectDiff>()
            val diff = ArrayList<ObjectDiff>()
            val missing = ArrayList<ObjectDiff>()
            val extra = ArrayList<ObjectDiff>()
            var fieldMismatchCount = 0
            var comparedCells = 0
            var nonNullCells = 0
            var codeMatched = 0
            var nameMatched = 0
            var aiMatched = 0
            // 已配上的目标侧键:未出现在配对里的目标行才是「多余」
            val pairedTargetKeys = HashSet<String>()

            // 配对建立:显式配对优先(名称/大模型配对两侧键不等),其余按「主键值相等」自动配对(旧口径)
            val keyedTarget = LinkedHashMap<String, String>(targetMap.size)
            for ((targetKey, row) in targetMap) {
                idValue(row, keyField)?.let { keyedTarget.putIfAbsent(it, targetKey) }
            }
            val pairs = ArrayList<MatchedPair>(minOf(baseMap.size, targetMap.size) + appliedPairs.size)
            val pairedBaseKeys = HashSet<String>()
            for (p in appliedPairs) {
                if (p.baseKey !in baseMap || p.targetKey !in targetMap) continue
                if (!pairedBaseKeys.add(p.baseKey) || !pairedTargetKeys.add(p.targetKey)) continue
                pairs.add(p)
            }
            for ((baseKey, baseRow) in baseMap) {
                if (baseKey in pairedBaseKeys) continue
                val targetKey = idValue(baseRow, keyField)?.let { keyedTarget[it] } ?: continue
                if (targetKey in pairedTargetKeys) continue
                pairs.add(MatchedPair(baseKey, targetKey, "CODE"))
                pairedBaseKeys.add(baseKey)
                pairedTargetKeys.add(targetKey)
            }

            for (pair in pairs) {
                val baseRow = baseMap[pair.baseKey] ?: continue
                val targetRow = targetMap[pair.targetKey] ?: continue
                when (pair.by) {
                    "CODE" -> codeMatched++
                    "NAME" -> nameMatched++
                    else -> aiMatched++
                }
                // 配对的双侧对象以「基准侧键」为该对象的标识(缺失/多余行才用自己一侧的键)
                val objectKey = pair.baseKey
                val name = displayName(baseRow, displayField)
                val rowDiffs = ArrayList<FieldDiff>(fields.size)
                var allEqual = true
                for (f in fields) {
                    comparedCells++
                    val baseValue = baseRow[f]?.trim()
                    val rowDiff = if (!targetRow.containsKey(f)) {
                        // 目标缺该列:全部按不一致计,value 用特殊标记说明
                        fieldMismatchCount++
                        allEqual = false
                        FieldDiff(f, baseValue, MISSING_COLUMN_MARK, matched = false)
                    } else {
                        val targetValue = targetRow[f]
                        if (!targetValue.isNullOrBlank()) nonNullCells++
                        val equal = valuesEqual(f, baseRow[f], targetValue, numericFields)
                        if (!equal) {
                            fieldMismatchCount++
                            allEqual = false
                        }
                        // 一致字段同样保存目标真实值(matched=true),界面/导出才能每格显示真实值
                        FieldDiff(f, baseValue, targetValue?.trim(), matched = equal)
                    }
                    rowDiffs.add(rowDiff)
                }
                if (allEqual) {
                    same.add(ObjectDiff(objectKey, name, "SAME", null, pair.by))
                } else {
                    diff.add(ObjectDiff(objectKey, name, "DIFF", rowDiffs, pair.by))
                }
            }
            for ((baseKey, baseRow) in baseMap) {
                if (baseKey in pairedBaseKeys) continue
                // 整行快照:基准值齐备、目标缺行(matched=false 表示该字段「目标侧缺失」)
                missing.add(ObjectDiff(baseKey, displayName(baseRow, displayField), "MISSING",
                    fields.map { FieldDiff(it, baseRow[it]?.trim(), null, matched = false) }))
            }
            for ((targetKey, targetRow) in targetMap) {
                if (targetKey in pairedTargetKeys) continue
                // 整行快照:目标值齐备、基准缺行(base 全 null,matched=false 表示「基准侧缺失」)
                extra.add(ObjectDiff(targetKey, displayName(targetRow, displayField), "EXTRA",
                    fields.map { FieldDiff(it, null, targetRow[it]?.trim(), matched = false) }))
            }
            val matchedCount = same.size + diff.size
            return CompareDiffResult(same, diff, missing, extra, matchedCount, targetMap.size,
                fieldMismatchCount, comparedCells, nonNullCells, codeMatched, nameMatched, aiMatched)
        }

        /** 显示名:显示字段值的 trim,无显示字段或值为空则空串 */
        private fun displayName(row: Map<String, String?>, displayField: String?): String =
            displayField?.let { row[it]?.trim() } ?: ""

        /** 字段值比较:先按「空」归一(NULL/空白=空),数值字段走 BigDecimal 归一,其余 trim 后字符串相等 */
        internal fun valuesEqual(field: String, baseValue: String?, targetValue: String?,
                                 numericFields: Set<String>): Boolean {
            val b = baseValue?.trim()
            val t = targetValue?.trim()
            val bEmpty = b.isNullOrEmpty()
            val tEmpty = t.isNullOrEmpty()
            if (bEmpty || tEmpty) return bEmpty && tEmpty // NULL 与空串视为一致
            if (field in numericFields) {
                val bdB = parseNumber(b!!)
                val bdT = parseNumber(t!!)
                if (bdB != null && bdT != null) return bdB.compareTo(bdT) == 0
            }
            return b == t
        }

        /** 数值解析:去千分位逗号后转 BigDecimal,失败返回 null */
        private fun parseNumber(s: String): BigDecimal? =
            try {
                BigDecimal(s.replace(",", ""))
            } catch (e: NumberFormatException) {
                null
            }
    }
}

// ---------- 对象对齐(匹配逻辑):与实例无关的纯模型与算法,放文件级便于单测与复用 ----------

/**
 * 对象对齐(匹配)逻辑(compare_job.match_mode),一任务一套,新建向导第二步由用户选择。
 * 规则来源:对比表与基准表的身份字段(code/name)常对不上,故提供三种判定「是否同一个对象」的口径。
 * [LEGACY] 不是用户可选项:老任务 match_mode 为空时按「只按对象编码对齐」解读,保证历史结果口径不回归。
 */
enum class MatchMode(val value: String, val label: String) {
    /** 规则 1:对象编码与对象名称都完全相等才算同一对象 */
    EXACT("EXACT", "编码+名称都相等"),
    /** 规则 2:有编码先用编码配,编码没配上的再用对象名称配 */
    CODE_THEN_NAME("CODE_THEN_NAME", "先编码后名称"),
    /** 规则 3:编码/名称都没配上的残余再交大模型归一化配对 */
    CODE_NAME_LLM("CODE_NAME_LLM", "编码/名称+大模型归一化"),
    /** 老任务(数据库 match_mode 为空):只按对象编码对齐 */
    LEGACY("", "仅编码(老任务)"),
    ;

    /** 是否需要用「对象名称」字段配对(决定提交时是否强制指定该字段) */
    val requiresName: Boolean get() = this != LEGACY

    companion object {
        /** 数据库值 → 枚举;未知值/空值一律按 [LEGACY] 解读(不炸老任务) */
        fun fromValue(value: String?): MatchMode =
            entries.firstOrNull { it != LEGACY && it.value.equals(value?.trim(), ignoreCase = true) } ?: LEGACY

        /** 请求值归一:空 = [EXACT](新建默认);未知值报 [IllegalArgumentException],避免静默按别的口径跑 */
        fun normalize(raw: String?): MatchMode {
            val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return EXACT
            return entries.firstOrNull { it != LEGACY && it.value.equals(v, ignoreCase = true) }
                ?: throw IllegalArgumentException("非法匹配逻辑: $raw")
        }
    }
}

/** 一对已对齐的对象:baseKey/targetKey 为两侧行 map 的键,by 为对齐来源(CODE/NAME/LLM) */
data class MatchedPair(val baseKey: String, val targetKey: String, val by: String)

/** 对象对齐结果:pairs 之外的两侧行即缺失/多余 */
data class MatchResult(
    val pairs: List<MatchedPair>,
    val codeMatched: Int,
    val nameMatched: Int,
    val aiMatched: Int = 0,
    /** 大模型补配的说明(超限/未配置/分批失败等),仅在需要人工关注时给出 */
    val llmNote: String? = null,
    /** 大模型补配是否有失败批次(调用方据此把说明挂到目标上) */
    val llmFailed: Boolean = false,
)

/**
 * 对象对齐纯函数:把基准/目标全量行按身份标识配对,产出「哪两条是同一个对象」。
 * - [MatchMode.LEGACY](老任务,数据库无值):只按对象编码([keyField] 的值)配对;
 * - [MatchMode.EXACT]:编码与对象名称都相等才配对;
 * - [MatchMode.CODE_THEN_NAME] / [MatchMode.CODE_NAME_LLM]:先按编码配,残余再按对象名称配
 *   (规则 3 的大模型补配在实例侧 [CompareService] 里继续做);
 * - 编码按 trim 后**区分大小写**比较(历史口径不变),名称按 trim 后忽略大小写比较;空值不参与配对;
 *   重复键保留先出现的行;
 * - 名称字段缺失(未提供)时等效只按编码配对。
 * 配对结果按「编码命中在前、名称命中在后」的顺序返回,便于调用方按来源计数。
 */
internal fun matchObjects(baseMap: LinkedHashMap<String, Map<String, String?>>,
                          targetMap: Map<String, Map<String, String?>>,
                          keyField: String, nameField: String?, mode: MatchMode): MatchResult {
    val pairs = ArrayList<MatchedPair>(minOf(baseMap.size, targetMap.size))
    val matchedBase = HashSet<String>()
    val matchedTarget = HashSet<String>()
    // 第一路:按编码配对(老任务与规则 1 都靠它)
    appendCodePairs(baseMap, targetMap, keyField, pairs, matchedBase, matchedTarget)
    val codeMatched = pairs.size
    // 第二路:编码没配上的残余再按对象名称配对(规则 1 要求名称也相等,不额外补配)
    var nameMatched = 0
    if (mode != MatchMode.LEGACY && mode != MatchMode.EXACT && nameField != null) {
        appendNamePairs(baseMap, targetMap, nameField, pairs, matchedBase, matchedTarget)
        nameMatched = pairs.size - codeMatched
    }
    return MatchResult(pairs, codeMatched, nameMatched)
}

/**
 * 按编码配对:两侧该字段都非空才参与,命中一对记一对。
 * 编码比较**区分大小写**(与历史口径一致:老实现直接拿 trim 后的编码值当行 map 的键做相等判定),
 * 保证老任务与新任务在规则 1/2 第一路上的结果完全相同;大小写/格式不统一的脏数据交给匹配逻辑 3 处理。
 */
private fun appendCodePairs(baseMap: Map<String, Map<String, String?>>,
                            targetMap: Map<String, Map<String, String?>>,
                            keyField: String, pairs: MutableList<MatchedPair>,
                            matchedBase: MutableSet<String>, matchedTarget: MutableSet<String>) {
    for ((baseKey, baseRow) in baseMap) {
        if (baseKey in matchedBase) continue
        val code = idValue(baseRow, keyField) ?: continue
        val targetKey = targetMap.entries
            .firstOrNull { it.key !in matchedTarget && code == idValue(it.value, keyField) }
            ?.key ?: continue
        pairs.add(MatchedPair(baseKey, targetKey, "CODE"))
        matchedBase.add(baseKey)
        matchedTarget.add(targetKey)
    }
}

/** 按对象名称配对(忽略大小写):只处理编码路没配上的残余,名称为空的行不参与 */
private fun appendNamePairs(baseMap: Map<String, Map<String, String?>>,
                            targetMap: Map<String, Map<String, String?>>,
                            nameField: String, pairs: MutableList<MatchedPair>,
                            matchedBase: MutableSet<String>, matchedTarget: MutableSet<String>) {
    // 目标侧残余按名称建索引(重名只留先出现的一条),基准侧残余逐个查
    val targetByName = HashMap<String, String>()
    for ((targetKey, row) in targetMap) {
        if (targetKey in matchedTarget) continue
        val name = idValue(row, nameField) ?: continue
        targetByName.putIfAbsent(name.lowercase(), targetKey)
    }
    for ((baseKey, baseRow) in baseMap) {
        if (baseKey in matchedBase) continue
        val name = idValue(baseRow, nameField) ?: continue
        val targetKey = targetByName[name.lowercase()] ?: continue
        if (targetKey in matchedTarget) continue
        pairs.add(MatchedPair(baseKey, targetKey, "NAME"))
        matchedBase.add(baseKey)
        matchedTarget.add(targetKey)
    }
}

/** 身份标识取值:trim 后为空按「没有该字段值」处理(空值不参与身份匹配) */
internal fun idValue(row: Map<String, String?>, field: String): String? =
    row[field]?.trim()?.takeIf { it.isNotEmpty() }

/** 对象对齐结果里没配上的双侧残余(baseKey 列表, targetKey 列表) */
internal fun MatchResult.residues(baseMap: LinkedHashMap<String, Map<String, String?>>,
                                  targetMap: Map<String, Map<String, String?>>): Pair<List<String>, List<String>> {
    val matchedBase = pairs.mapTo(HashSet()) { it.baseKey }
    val matchedTarget = pairs.mapTo(HashSet()) { it.targetKey }
    return baseMap.keys.filter { it !in matchedBase } to targetMap.keys.filter { it !in matchedTarget }
}
