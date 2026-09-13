package com.example.dq.service

import com.example.dq.dialect.DbDialect
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.ColumnMeta
import com.example.dq.model.CompareDiffPage
import com.example.dq.model.CompareDiffRow
import com.example.dq.model.CompareJobDetailView
import com.example.dq.model.CompareJobView
import com.example.dq.model.CompareReportView
import com.example.dq.model.CompareTargetSpec
import com.example.dq.model.CompareTargetView
import com.example.dq.model.CreateCompareJobRequest
import com.example.dq.model.FieldDiff
import com.example.dq.model.FieldIssueRank
import com.example.dq.repository.CompareRepository
import com.example.dq.util.ExcelCells
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
 * 数据比对:以一张基准表为准,与多个目标系统(数据源)中的对应表按主键逐字段比对,
 * 产出差异明细(compare_diff)与质量报告(compare_target 四项比率)。
 * 长时任务模型同 SampleExportService:固定 2 线程池后台执行、任务/目标/明细落 H2、
 * updateStage/updateProgress 增量进度、recoverUnfinished 启动恢复;
 * 单个目标失败只把该 target 置 FAILED 记 error 继续下一个,基准读取失败才整个任务 FAILED。
 * 比对核心 [diffObjects] 为纯函数(companion),便于单测。
 */
class CompareService(
    private val repo: CompareRepository,
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val metadataService: MetadataService,
    private val systemSettingsService: SystemSettingsService,
) {

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

        // 目标侧:数据源存在 + 表存在 + 主键列必须存在(缺其他比对列允许,比对时记「列缺失」)
        val resolved = specs.map { spec ->
            val dsId = spec.datasourceId!!
            val ds = dataSourceService.get(dsId)
            val db = spec.db ?: ""
            val table = spec.table!!.trim()
            val cols = metadataService.listTableColumns(dsId, db, effectiveSchema(spec.schema, db), table)
            if (cols.isEmpty()) throw IllegalArgumentException("目标表不存在或没有字段: ${ds.name}.$table")
            if (cols.none { it.name.equals(actualKey, ignoreCase = true) }) {
                throw IllegalArgumentException("目标表缺少比对主键列: ${ds.name}.$table 无 $actualKey")
            }
            ResolvedTarget(dsId, ds.name, db, spec.schema, table)
        }

        val jobId = repo.insertJob(name, baseDsId, baseDb, req.baseSchema, baseTable,
            actualKey, objectMapper.writeValueAsString(fields), 1 + resolved.size)
        for (t in resolved) {
            repo.insertTarget(jobId, t.datasourceId, t.dsName, t.db, t.schema, t.table)
        }
        executor.execute { run(jobId) }
        log.info("比对任务已提交: id={}, 名称={}, 基准={}.{}, 目标数={}", jobId, name, baseDb, baseTable, resolved.size)
        return jobId
    }

    private data class ResolvedTarget(val datasourceId: Long, val dsName: String?,
                                      val db: String, val schema: String?, val table: String)

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
            repo.insertTarget(jobId, t.datasourceId, dsName, t.dbName, t.schemaName, t.tableName)
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
            // 显示名:比对字段中第一个文本型非主键字段
            val displayField = fields.mapNotNull { baseByName[it.lowercase()] }
                .firstOrNull { !it.name.equals(keyColumn.name, ignoreCase = true) && isTextType(it) }?.name
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
                    compareOneTarget(job, t, fields, numericFields, displayField, baseMap)
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

    /** 单个目标:字段映射(忽略大小写)→ 拉目标全量 → diffObjects → 批量落明细(500/批)→ 算指标落 compare_target */
    private fun compareOneTarget(job: CompareRepository.JobRow, t: CompareRepository.TargetRow,
                                 fields: List<String>, numericFields: Set<String>, displayField: String?,
                                 baseMap: LinkedHashMap<String, Map<String, String?>>) {
        val ds = dataSourceService.get(t.datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)
        val schema = effectiveSchema(t.schemaName, t.dbName)
        val cols = metadataService.listTableColumns(t.datasourceId, t.dbName, schema, t.tableName)
        if (cols.isEmpty()) throw IllegalStateException("目标表不存在或没有字段: ${t.tableName}")
        val byName = cols.associateBy { it.name.lowercase() }
        val keyColumn = byName[job.keyField.lowercase()]
            ?: throw IllegalStateException("目标表缺少比对主键列: ${job.keyField}")
        // 只选目标侧存在的列;缺失列不进 map,diffObjects 按「列缺失」全部计不一致
        val select = fields.mapNotNull { f -> byName[f.lowercase()]?.let { SelectedCol(f, it.name) } }
        val targetMap = loadRows(t.datasourceId, t.dbName, schema, t.tableName, dialect, select, keyColumn.name)

        val result = diffObjects(baseMap, targetMap, fields, job.keyField, numericFields, displayField)
        val all = result.same + result.diff + result.missing + result.extra
        all.chunked(DIFF_BATCH_SIZE).forEach { batch ->
            repo.insertDiffs(job.id, t.id, batch.map { d ->
                CompareRepository.DiffInput(d.objectKey.take(500), d.objectName.take(500), d.diffType,
                    d.diffs?.let { objectMapper.writeValueAsString(truncateDiffs(it)) })
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
        repo.updateTargetStats(t.id, baseCount, result.matchedCount, result.missing.size, result.extra.size,
            result.fieldMismatchCount, coverage, fieldConsistency, completeness, score)
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
            CompareDiffRow(r.id, r.targetId, r.objectKey, r.objectName, r.diffType, parseDiffs(r.diffJson))
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
     * 差异明细导出:SXSSFWorkbook 每目标一 sheet(sheet 名取数据源名快照,经 ExcelCells 清洗去重),
     * 只导非 SAME 行;DIFF 行逐字段展开,MISSING/EXTRA 整行一条(字段/基准值/目标值留空)
     */
    fun exportDiff(jobId: Long, out: OutputStream) {
        repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val targets = repo.listTargets(jobId)
        SXSSFWorkbook(200).use { wb ->
            val usedSheetNames = mutableSetOf<String>()
            for (t in targets) {
                val sheet = wb.createSheet(ExcelCells.sheetName(t.dsName ?: "数据源${t.datasourceId}", usedSheetNames))
                val head = sheet.createRow(0)
                EXPORT_HEADERS.forEachIndexed { i, h -> head.createCell(i).setCellValue(h) }
                var r = 1
                for (row in repo.listDiffsForExport(jobId, t.id)) {
                    val typeLabel = DIFF_TYPE_LABELS[row.diffType] ?: row.diffType
                    val diffs = parseDiffs(row.diffJson)
                    if (row.diffType == "DIFF" && !diffs.isNullOrEmpty()) {
                        for (d in diffs) {
                            val excelRow = sheet.createRow(r++)
                            excelRow.createCell(0).setCellValue(row.objectKey ?: "")
                            excelRow.createCell(1).setCellValue(row.objectName ?: "")
                            excelRow.createCell(2).setCellValue(typeLabel)
                            excelRow.createCell(3).setCellValue(d.field)
                            excelRow.createCell(4).setCellValue(d.base ?: "")
                            excelRow.createCell(5).setCellValue(d.value ?: "")
                        }
                    } else {
                        val excelRow = sheet.createRow(r++)
                        excelRow.createCell(0).setCellValue(row.objectKey ?: "")
                        excelRow.createCell(1).setCellValue(row.objectName ?: "")
                        excelRow.createCell(2).setCellValue(typeLabel)
                    }
                }
            }
            wb.write(out)
            wb.dispose()
        }
    }

    /** 质量报告:目标指标 + 问题字段排行(解析 DIFF 行 diff_json 按字段计数取前 10)+ 汇总 */
    fun report(jobId: Long): CompareReportView {
        repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val targets = repo.listTargets(jobId).map { toTargetView(it) }
        val counts = repo.countByDiffType(jobId)
        val fieldCounts = HashMap<String, Long>()
        for (json in repo.listDiffJsons(jobId)) {
            for (d in parseDiffs(json).orEmpty()) {
                fieldCounts.merge(d.field, 1L, Long::plus)
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
            parseFields(r.fieldsJson), r.status, r.stage, r.totalUnits, r.doneUnits,
            if (r.totalUnits > 0) r.doneUnits * 100 / r.totalUnits else 0,
            r.error, r.archived, r.createdAt, r.startedAt, r.finishedAt,
            r.startedAt?.let { Duration.between(it, r.finishedAt ?: LocalDateTime.now()).toMillis() })
    }

    private fun toTargetView(t: CompareRepository.TargetRow) = CompareTargetView(
        t.id, t.datasourceId, t.dsName, t.dbName, t.schemaName, t.tableName, t.status,
        t.baseCount, t.matchedCount, t.missingCount, t.extraCount, t.fieldMismatchCount,
        t.coverage, t.fieldConsistency, t.completeness, t.score, t.error)

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
            FieldDiff(d.field, d.base?.take(MAX_DIFF_VALUE_CHARS), d.value?.take(MAX_DIFF_VALUE_CHARS))
        }

    companion object {
        /** 任务执行线程池的线程序号(线程命名 compare-N) */
        private val THREAD_IDX = AtomicInteger()

        /** 分页拉全量的每页行数 */
        const val PAGE_SIZE = 5000

        /** 单侧行数上限:超出抛 IllegalStateException,任务判 FAILED */
        const val MAX_SIDE_ROWS = 500_000

        /** 差异明细批量落库的每批行数 */
        private const val DIFF_BATCH_SIZE = 500

        /** diff_json 单值截断长度 */
        private const val MAX_DIFF_VALUE_CHARS = 1000

        /** 问题字段排行取前 N */
        private const val FIELD_ISSUE_TOP_N = 10

        /** 目标缺列时 diff_json 里 value 的特殊标记 */
        const val MISSING_COLUMN_MARK = "«列缺失»"

        private val DIFF_TYPES = setOf("SAME", "DIFF", "MISSING", "EXTRA")

        private val DIFF_TYPE_LABELS = mapOf(
            "SAME" to "一致", "DIFF" to "不一致", "MISSING" to "缺失", "EXTRA" to "多余")

        private val EXPORT_HEADERS = listOf("对象编码", "对象名称", "差异类型", "字段", "基准值", "目标值")

        /** schema 归一:未填时用库名兜底(MySQL 场景 schema=库名,与抽样导出口径一致) */
        internal fun effectiveSchema(schema: String?, db: String): String =
            schema?.takeIf { it.isNotBlank() } ?: db.takeIf { it.isNotBlank() } ?: ""

        /** 是否文本型字段(显示名选取口径:字符型或 CLOB) */
        internal fun isTextType(c: ColumnMeta): Boolean =
            c.isCharacter() || c.jdbcType == Types.CLOB || c.jdbcType == Types.NCLOB

        /** 单条对象差异:diffs 仅 DIFF 行有值 */
        data class ObjectDiff(val objectKey: String, val objectName: String,
                              val diffType: String, val diffs: List<FieldDiff>?)

        /** 比对结果:四类明细 + 算指标所需计数(comparedCells=matched×比对字段数,即目标侧已比对单元格数) */
        data class CompareDiffResult(
            val same: List<ObjectDiff>,
            val diff: List<ObjectDiff>,
            val missing: List<ObjectDiff>,
            val extra: List<ObjectDiff>,
            /** 双侧都存在的对象数(SAME+DIFF) */
            val matchedCount: Int,
            /** 不一致字段单元格总数(含「列缺失」) */
            val fieldMismatchCount: Int,
            /** 目标侧已比对单元格数(matched × fields.size) */
            val comparedCells: Int,
            /** 其中非空单元格数(列缺失按空计) */
            val nonNullCells: Int,
        )

        /**
         * 比对核心纯函数:基准/目标全量按主键 map 对齐,逐字段比较,产出四类明细与指标计数。
         * 行 map 的键为基准字段名;目标行只含目标侧存在的列,缺列(key 不在行 map)记「列缺失」按不一致计。
         * 比较规则:字符串 trim;NULL 与空串视为一致;numericFields 中的字段去千分位逗号后转
         * BigDecimal 用 compareTo==0 判定(解析失败回落字符串比较);主键字段对命中行恒一致。
         */
        fun diffObjects(baseMap: LinkedHashMap<String, Map<String, String?>>,
                        targetMap: Map<String, Map<String, String?>>,
                        fields: List<String>, keyField: String,
                        numericFields: Set<String> = emptySet(),
                        displayField: String? = null): CompareDiffResult {
            val same = ArrayList<ObjectDiff>()
            val diff = ArrayList<ObjectDiff>()
            val missing = ArrayList<ObjectDiff>()
            val extra = ArrayList<ObjectDiff>()
            var fieldMismatchCount = 0
            var comparedCells = 0
            var nonNullCells = 0

            for ((key, baseRow) in baseMap) {
                val targetRow = targetMap[key]
                if (targetRow == null) {
                    missing.add(ObjectDiff(key, displayName(baseRow, displayField), "MISSING", null))
                    continue
                }
                val diffs = ArrayList<FieldDiff>()
                for (f in fields) {
                    comparedCells++
                    val baseValue = baseRow[f]
                    if (!targetRow.containsKey(f)) {
                        // 目标缺该列:全部按不一致计,value 用特殊标记说明
                        fieldMismatchCount++
                        diffs.add(FieldDiff(f, baseValue?.trim(), MISSING_COLUMN_MARK))
                        continue
                    }
                    val targetValue = targetRow[f]
                    if (!targetValue.isNullOrBlank()) nonNullCells++
                    if (!valuesEqual(f, baseValue, targetValue, numericFields)) {
                        fieldMismatchCount++
                        diffs.add(FieldDiff(f, baseValue?.trim(), targetValue?.trim()))
                    }
                }
                if (diffs.isEmpty()) {
                    same.add(ObjectDiff(key, displayName(baseRow, displayField), "SAME", null))
                } else {
                    diff.add(ObjectDiff(key, displayName(baseRow, displayField), "DIFF", diffs))
                }
            }
            for ((key, targetRow) in targetMap) {
                if (!baseMap.containsKey(key)) {
                    extra.add(ObjectDiff(key, displayName(targetRow, displayField), "EXTRA", null))
                }
            }
            val matchedCount = same.size + diff.size
            return CompareDiffResult(same, diff, missing, extra, matchedCount,
                fieldMismatchCount, comparedCells, nonNullCells)
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
