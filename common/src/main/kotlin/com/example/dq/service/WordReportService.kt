package com.example.dq.service

import com.deepoove.poi.XWPFTemplate
import com.deepoove.poi.config.Configure
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.ScanStatus
import com.example.dq.model.ScanTableView
import com.example.dq.model.TagKind
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Word 数据调研报告导出:以 common 资源 templates/data-survey-report.docx 为模板,poi-tl 渲染。
 * 渲染范围:封面(报告名/输出时间/数据库类型)+ 一~四章正文。
 *
 * 数据口径(与 Excel 导出 writeSummary 一致):
 * - 取每库最近一次 DONE 扫描快照(支持历史快照:重扫进行中/最近任务失败时回落到更早的 DONE 任务,
 *   不要求为导出重新扫描——大库扫一遍很慢)
 * - 空表 = 0 行;空字段 = 有值数为 0;有值率分桶按 scan_column.fillRate
 * - 选中的库必须已完成全表扫描(快照覆盖当前全部表且无失败表),否则 409 拦截列出库名提示先扫描
 * - 快照里缺失的数据实时补算:表体积(size_bytes 为 null,如 Oracle 受限账号看不到段视图)回业务库元数据实时查询
 * - 实例描述取库级描述(schema_doc,库列表页可编辑)
 * - 第三章按 USER 表标记分节,打标表逐表取最近 DONE 快照(未扫描的表跳过不计);2.2 分组表已删(与 2.1 重复)
 * - 分析文字(各节引导段/质量预警/3.x(三)/第四章)由大模型生成,统计数字由代码算好放进 prompt;
 *   未配置或调用失败时渲染「(待人工编写)」占位,不阻塞导出
 */
class WordReportService(
    private val dataSourceService: DataSourceService,
    private val metadataService: MetadataService,
    private val scanRepository: ScanRepository,
    private val schemaDocRepo: SchemaDocRepository,
    private val dialectFactory: DialectFactory,
    private val tagRepo: TagRepository,
    private val tableDocRepo: TableDocRepository,
    private val aiConfigService: AiConfigService,
    aiService: AiService,
    /** LLM 调用点(AiService 是 final class,测试经此注入 fake) */
    private val chat: (AiConfigService.Config, String, String) -> String =
        { c, s, u -> aiService.chat(c, s, u) },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 每库完整聚合(一/二章共用同一批快照一次遍历;三章按打标表子集聚合) */
    private data class SchemaStats(
        val name: String, val description: String,
        val tableCount: Int, val emptyTableCount: Int, val emptyTableNames: List<String>,
        val columnCount: Int, val emptyColumnCount: Int,
        val totalRows: Long, val sizeBytes: Long, val valueSum: Long,
        val avgFillRate: Double,             // 各字段有值率算术平均(与 Excel 表列表同口径)
        val fillBuckets: IntArray,           // 有值率分桶:<10 / 10-50 / 50-80 / >=80
        val tableCommentCount: Int, val columnCommentCount: Int,
        val typeCounts: IntArray,            // 字符串/整数/小数/时间/其他
        val backupTables: List<String>, val shardedTables: List<String>,
        val largeTables: List<LargeTable>,
    )

    private data class LargeTable(val name: String, val rows: Long, val sizeBytes: Long?,
                                  val purpose: String, val columns: Int)

    /**
     * @param schemaNames 限定导出的库(导出对话框勾选 / 表列表页单库导出);null = 全部库
     * @param onProgress  进度回调(已完成步数, 总步数, 阶段描述),异步导出任务落库供前端轮询
     */
    fun export(
        datasourceId: Long, database: String?, schemaNames: List<String>?, out: OutputStream,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ) {
        val ds = dataSourceService.get(datasourceId)
        // 库清单口径与库列表页一致(schema_stat 缓存 + 白名单;首次访问会连业务库拉取)
        var schemas = metadataService.listSchemaStats(datasourceId, database)
        if (schemaNames != null) {
            val wanted = HashSet(schemaNames)
            schemas = schemas.filter { it.name in wanted }
        }
        if (schemas.isEmpty()) {
            throw IllegalArgumentException("该数据源下没有可导出的库")
        }
        // 历史 DONE 快照:每库最近一次完成的任务(重扫进行中/最近失败不影响导出)
        val latestDone = scanRepository.latestDoneJobsBySchema(datasourceId, database)

        // 全表扫描校验:快照存在、无失败表、且覆盖当前全部表(表数以 schema_stat 缓存为准,未知时跳过该项)
        val docs = schemaDocRepo.findByDatasource(datasourceId, database ?: "")
        val jobTables = ArrayList<Pair<String, List<ScanTableView>>>(schemas.size)
        val incomplete = ArrayList<String>()
        for (s in schemas) {
            val name = s.name!!
            val job = latestDone[name]
            val tables = job?.let { scanRepository.listScanTables(it.id) }.orEmpty()
            if (job == null || tables.any { it.status != ScanStatus.DONE } ||
                (s.tableCount != null && tables.size < s.tableCount!!)) {
                incomplete.add(name)
            } else {
                jobTables.add(name to tables)
            }
        }
        if (incomplete.isNotEmpty()) {
            throw IllegalStateException("以下库未完成全表扫描,请先扫描再导出报告: " + incomplete.joinToString("、"))
        }

        // 进度总步数:逐库聚合 N + 标记节 LLM S + 固定分析段 8 + 渲染 1
        val tagCount = countTagSections(datasourceId, database, jobTables.map { it.first }.toSet())
        val progress = Progress(jobTables.size + tagCount + 8 + 1, onProgress)

        val aggs = ArrayList<SchemaStats>(jobTables.size)
        for ((name, tables) in jobTables) {
            aggs.add(aggregate(datasourceId, database, ds, name, docs[name] ?: "", tables))
            progress.step("聚合库「$name」统计数据")
        }

        val data = buildRenderData(ds, aggs)
        // 第三章:按 USER 标记分节(只统计本次导出范围内的库)
        val sections = buildTagSections(datasourceId, database, ds, aggs.map { it.name }.toSet(), docs, progress)
        data["tagSectionsHint"] = if (sections.isEmpty()) "当前数据源没有可用的用户标记数据,可在表列表打标后重新导出。" else ""
        data["tagSections"] = sections
        // 分析文字最后生成(LLM 调用耗时,且依赖全部统计结果)
        data.putAll(buildAnalyses(ds, aggs, sections, progress))

        val templateStream = javaClass.getResourceAsStream(TEMPLATE_PATH)
            ?: throw IllegalStateException("Word 报告模板资源缺失: $TEMPLATE_PATH")
        try {
            templateStream.use { input ->
                val groupTable = WordReportTables.GroupTablePolicy()
                val config = Configure.builder()
                    .bind("schemas", LoopRowTableRenderPolicy())
                    .bind("qualityRows", LoopRowTableRenderPolicy())
                    .bind("fillRateTable", groupTable)
                    .bind("metadataTable", groupTable)
                    .bind("typeTable", groupTable)
                    .bind("redundancyTable", groupTable)
                    .bind("largeTable", groupTable)
                    .bind("tagSections", WordReportTables.TagSectionsPolicy())
                    .build()
                XWPFTemplate.compile(input, config).render(data).writeAndClose(out)
            }
        } catch (e: Exception) {
            log.error("Word 报告渲染失败: datasourceId={}, db={}", datasourceId, database, e)
            throw e
        }
        progress.step("渲染 Word 文档")
        log.info("Word 报告已导出: datasourceId={}, db={}, 库数={}, 标记节数={}",
            datasourceId, database, aggs.size, sections.size)
    }

    /** 进度步进器:总步数在任务拆解后一次性确定 */
    private class Progress(private val total: Int, private val cb: (Int, Int, String) -> Unit) {
        private var done = 0
        fun step(stage: String) {
            done++
            cb(done, total, stage)
        }
    }

    // ---------- 聚合 ----------

    /** 单库聚合:遍历表与字段快照一次算全;体积快照缺失时经实时补算(allowLiveSize=false 时降级为部分合计,
     * 第三章打标表子集不能用整库实时体积,会纳入未打标表) */
    private fun aggregate(
        datasourceId: Long, database: String?, ds: DataSourceConfig,
        schema: String, description: String, tables: List<ScanTableView>,
        allowLiveSize: Boolean = true,
    ): SchemaStats {
        val docs = tableDocRepo.findBySchema(datasourceId, database ?: "", schema)
        var emptyTables = 0
        val emptyNames = ArrayList<String>()
        var columnCount = 0
        var emptyColumns = 0
        var totalRows = 0L
        var sizeBytes = 0L
        var sizeMissing = false
        var valueSum = 0L
        var tableComments = 0
        var columnComments = 0
        val fillRates = ArrayList<Double>()
        val fillBuckets = IntArray(4)
        val typeCounts = IntArray(5)
        val backups = ArrayList<String>()
        val shards = ArrayList<String>()
        val largeTables = ArrayList<LargeTable>()
        for (t in tables) {
            val tableName = t.tableName!!
            val rows = t.totalRows ?: t.scannedRows
            totalRows += rows
            if (t.totalRows != null && t.totalRows == 0L) {
                emptyTables++
                emptyNames.add(tableName)
            }
            if (t.sizeBytes != null) {
                sizeBytes += t.sizeBytes
            } else {
                sizeMissing = true
            }
            if (!t.comment.isNullOrBlank()) {
                tableComments++
            }
            if (BACKUP_RE.containsMatchIn(tableName)) {
                backups.add(tableName)
            } else if (SHARD_RE.containsMatchIn(tableName)) {
                shards.add(tableName)
            }
            val cols = scanRepository.listScanColumns(t.id)
            for (c in cols) {
                columnCount++
                if (c.valueCount == 0L) {
                    emptyColumns++
                }
                valueSum += c.valueCount
                fillRates.add(c.fillRate)
                fillBuckets[fillBucket(c.fillRate)]++
                typeCounts[typeCategory(c.columnType)]++
                if (!c.columnComment.isNullOrBlank()) {
                    columnComments++
                }
            }
            if (rows > LARGE_TABLE_ROWS || (t.sizeBytes ?: 0L) > LARGE_TABLE_BYTES) {
                largeTables.add(LargeTable(tableName, rows, t.sizeBytes,
                    docs[tableName]?.takeIf { it.isNotBlank() } ?: t.comment?.takeIf { it.isNotBlank() } ?: "-",
                    cols.size))
            }
        }
        // 报告需要而快照缺失的数据(表体积)实时计算;实时也拿不到(或子集统计不能用整库口径)时用快照部分值
        if (sizeMissing) {
            val live = if (allowLiveSize) liveSchemaSize(datasourceId, database, ds, schema) else null
            if (live != null) {
                sizeBytes = live
            } else {
                log.warn("库 {} 部分表缺少体积快照且实时查询不可用,报告占用空间为部分表合计({} bytes)", schema, sizeBytes)
            }
        }
        largeTables.sortByDescending { it.rows }
        return SchemaStats(schema, description, tables.size, emptyTables, emptyNames, columnCount, emptyColumns,
            totalRows, sizeBytes, valueSum, if (fillRates.isEmpty()) 0.0 else fillRates.average(),
            fillBuckets, tableComments, columnComments, typeCounts, backups, shards, largeTables)
    }

    /** 实时计算库体积:业务库元数据按 schema 汇总(差异收敛在 dialect 层) */
    private fun liveSchemaSize(datasourceId: Long, database: String?, ds: DataSourceConfig, schema: String): Long? {
        return try {
            val dialect = dialectFactory.get(ds.dbType!!)
            dataSourceService.getConnection(datasourceId).use { conn ->
                dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database))
                dialect.sumSizeBySchema(conn)[schema]
            }
        } catch (e: Exception) {
            log.warn("实时查询库 {} 体积失败: {}", schema, e.message)
            null
        }
    }

    // ---------- 第三章:按 USER 标记分节 ----------

    /** 会生成章节的 USER 标记数(有本数据源/范围内打标表的标记),供进度总步数计算 */
    private fun countTagSections(datasourceId: Long, database: String?, allowedSchemas: Set<String>): Int =
        tagRepo.listAll().filter { it.kind == TagKind.USER }.count { tag ->
            tagRepo.findTaggedTables(tag.id, datasourceId, database ?: "").any { it.schemaName in allowedSchemas }
        }

    private fun buildTagSections(
        datasourceId: Long, database: String?, ds: DataSourceConfig,
        allowedSchemas: Set<String>, schemaDocs: Map<String, String>, progress: Progress,
    ): List<WordReportTables.TagSection> {
        val userTags = tagRepo.listAll().filter { it.kind == TagKind.USER }
        val sections = ArrayList<WordReportTables.TagSection>()
        var idx = 0
        for (tag in userTags) {
            val tagged = tagRepo.findTaggedTables(tag.id, datasourceId, database ?: "")
                .filter { it.schemaName in allowedSchemas }
            if (tagged.isEmpty()) {
                continue
            }
            // 每张打标表逐表取最近 DONE 快照(可来自不同任务);未扫描的打标表跳过不计
            var unscanned = 0
            val schemaAggs = ArrayList<SchemaStats>()
            for ((schema, items) in tagged.groupBy { it.schemaName }) {
                val snapshots = scanRepository.latestDoneScanTables(datasourceId, database, schema)
                val tables = items.mapNotNull { snapshots[it.tableName] }
                unscanned += items.size - tables.size
                if (tables.isNotEmpty()) {
                    // 打标表子集的体积不做整库实时补算(会纳入未打标表),快照缺失即部分合计
                    schemaAggs.add(aggregate(datasourceId, database, ds, schema,
                        schemaDocs[schema] ?: "", tables, allowLiveSize = false))
                }
            }
            idx++
            sections.add(buildTagSection(idx, tag.name, schemaAggs, unscanned))
            progress.step("生成「${tag.name}」标记节分析")
        }
        return sections
    }

    private fun buildTagSection(idx: Int, tagName: String, aggs: List<SchemaStats>, unscanned: Int): WordReportTables.TagSection {
        // (一) 总体情况:规则统计句(纯数字,不占 LLM 额度)
        val summary = StringBuilder()
            .append("「").append(tagName).append("」数据主要分布在").append(aggs.size).append("个数据库实例,")
            .append("已扫描打标数据表总数").append(thousands(aggs.sumOf { it.tableCount.toLong() })).append("张,")
            .append("其中空表总数").append(thousands(aggs.sumOf { it.emptyTableCount.toLong() })).append("张,")
            .append("空表总率").append(percent(aggs.sumOf { it.emptyTableCount.toLong() }, aggs.sumOf { it.tableCount.toLong() })).append("%,")
            .append("累计字段").append(thousands(aggs.sumOf { it.columnCount.toLong() })).append("个,")
            .append("其中空字段").append(thousands(aggs.sumOf { it.emptyColumnCount.toLong() })).append("个,")
            .append("字段有值总率").append(percent(aggs.sumOf { it.valueSum }, aggs.sumOf { it.totalRows })).append("%,")
            .append("累计数据总行数").append(thousands(aggs.sumOf { it.totalRows })).append("行,")
            .append("累计数据总存储量约").append(formatBytes(aggs.sumOf { it.sizeBytes })).append("。")
        if (unscanned > 0) {
            summary.append("另有 ").append(unscanned).append(" 张打标表未扫描,未纳入本次统计。")
        }
        return WordReportTables.TagSection(
            "3.$idx", tagName, summary.toString(),
            OVERVIEW_HEADERS, overviewRows(aggs),
            QUALITY_HEADERS, qualityRows(aggs),
            analysis("「$tagName」标签数据可用性分析", tagFacts(tagName, aggs, unscanned)),
        )
    }

    // ---------- 渲染数据 ----------

    private fun buildRenderData(ds: DataSourceConfig, aggs: List<SchemaStats>): MutableMap<String, Any> {
        val data = HashMap<String, Any>()
        // 封面
        data["reportName"] = (ds.name ?: "") + "数据调研报告"
        data["outputDate"] = LocalDate.now().format(DATE_FMT)
        data["dbType"] = ds.dbType?.label ?: "-"
        // 1.1 总体情况
        data["dsName"] = ds.name ?: ""
        data["schemaCount"] = aggs.size.toString()
        data["tableCount"] = thousands(aggs.sumOf { it.tableCount.toLong() })
        val emptyTables = aggs.sumOf { it.emptyTableCount.toLong() }
        data["emptyTableCount"] = thousands(emptyTables)
        data["emptyTableRate"] = percent(emptyTables, aggs.sumOf { it.tableCount.toLong() })
        val columns = aggs.sumOf { it.columnCount.toLong() }
        data["columnCount"] = thousands(columns)
        val emptyColumns = aggs.sumOf { it.emptyColumnCount.toLong() }
        data["emptyColumnCount"] = thousands(emptyColumns)
        // 字段有值总率:全字段有值数 / 全字段行数(按行加权;分母为 0 显示 -)
        data["fillRate"] = percent(aggs.sumOf { it.valueSum }, aggs.sumOf { it.totalRows })
        data["totalRows"] = thousands(aggs.sumOf { it.totalRows })
        data["totalSize"] = formatBytes(aggs.sumOf { it.sizeBytes })

        // 1.2 数据库实例一览(LoopRow)
        data["schemas"] = aggs.mapIndexed { i, a ->
            mapOf(
                "index" to (i + 1).toString(),
                "name" to a.name,
                "description" to a.description,
                "tableCount" to thousands(a.tableCount.toLong()),
                "columnCount" to thousands(a.columnCount.toLong()),
                "totalRows" to thousands(a.totalRows),
                "sizeText" to formatBytes(a.sizeBytes),
            )
        }
        data["sumTables"] = thousands(aggs.sumOf { it.tableCount.toLong() })
        data["sumColumns"] = thousands(columns)
        data["sumRows"] = thousands(aggs.sumOf { it.totalRows })
        data["sumSize"] = formatBytes(aggs.sumOf { it.sizeBytes })

        // 2.1 整体数据质量概况(LoopRow + 合计/平均行)
        data["qualityRows"] = aggs.map { a ->
            mapOf(
                "name" to a.name,
                "tableCount" to thousands(a.tableCount.toLong()),
                "emptyCount" to thousands(a.emptyTableCount.toLong()),
                "emptyRate" to pctCell(a.emptyTableCount.toLong(), a.tableCount.toLong()),
                "columnCount" to thousands(a.columnCount.toLong()),
                "emptyColumnCount" to thousands(a.emptyColumnCount.toLong()),
                "emptyColumnRate" to pctCell(a.emptyColumnCount.toLong(), a.columnCount.toLong()),
                "avgFillRate" to String.format("%.2f%%", a.avgFillRate),
            )
        }
        data["qSumTables"] = thousands(aggs.sumOf { it.tableCount.toLong() })
        data["qSumEmpty"] = thousands(emptyTables)
        data["qEmptyRate"] = pctCell(emptyTables, aggs.sumOf { it.tableCount.toLong() })
        data["qSumColumns"] = thousands(columns)
        data["qSumEmptyColumns"] = thousands(emptyColumns)
        data["qEmptyColumnRate"] = pctCell(emptyColumns, columns)
        data["qAvgFillRate"] =
            String.format("%.2f%%", if (aggs.isEmpty()) 0.0 else aggs.map { it.avgFillRate }.average())

        // 2.3 字段填充率分布(分组表,每库 4 行)
        data["fillRateTable"] = WordReportTables.GroupTable(
            listOf("数据库实例", "有值率区间", "字段数", "占比"),
            aggs.map { a ->
                WordReportTables.Group(a.name, FILL_BUCKET_LABELS.mapIndexed { i, label ->
                    listOf(label, thousands(a.fillBuckets[i].toLong()),
                        pctCell(a.fillBuckets[i].toLong(), a.columnCount.toLong()))
                })
            })
        // 2.4 元数据完整性(分组表,每库 2 行)
        data["metadataTable"] = WordReportTables.GroupTable(
            listOf("数据库实例", "分析维度", "有注释", "无注释", "注释率"),
            aggs.map { a ->
                WordReportTables.Group(a.name, listOf(
                    listOf("表级注释", thousands(a.tableCommentCount.toLong()),
                        thousands((a.tableCount - a.tableCommentCount).toLong()),
                        pctCell(a.tableCommentCount.toLong(), a.tableCount.toLong())),
                    listOf("字段级注释", thousands(a.columnCommentCount.toLong()),
                        thousands((a.columnCount - a.columnCommentCount).toLong()),
                        pctCell(a.columnCommentCount.toLong(), a.columnCount.toLong())),
                ))
            })
        // 2.5 数据类型分布(分组表,每库 5 行)
        data["typeTable"] = WordReportTables.GroupTable(
            listOf("数据库实例", "数据类型", "字段数", "占比", "类型示例"),
            aggs.map { a ->
                WordReportTables.Group(a.name, TYPE_LABELS.mapIndexed { i, label ->
                    listOf(label, thousands(a.typeCounts[i].toLong()),
                        pctCell(a.typeCounts[i].toLong(), a.columnCount.toLong()), TYPE_EXAMPLES[i])
                })
            })
        // 2.6 数据冗余分析(分组表,每库 3 行)
        data["redundancyTable"] = WordReportTables.GroupTable(
            listOf("数据库实例", "冗余类别", "表数量", "占比", "表名示例"),
            aggs.map { a ->
                WordReportTables.Group(a.name, listOf(
                    redundancyRow("空表", a.emptyTableNames, a.tableCount),
                    redundancyRow("备份表", a.backupTables, a.tableCount),
                    redundancyRow("分表", a.shardedTables, a.tableCount),
                ))
            })
        // 2.7 大体量表(分组表,每库 N 行;无大表的库不占组)
        data["largeTable"] = WordReportTables.GroupTable(
            listOf("数据库实例", "表名", "行数", "占用存储空间", "表用途", "日增量", "月增量", "年增量", "字段总数"),
            aggs.filter { it.largeTables.isNotEmpty() }.map { a ->
                WordReportTables.Group(a.name, a.largeTables.map { t ->
                    listOf(t.name, thousands(t.rows), t.sizeBytes?.let { formatBytes(it) } ?: "-",
                        t.purpose, "-", "-", "-", thousands(t.columns.toLong()))
                })
            })
        return data
    }

    private fun redundancyRow(label: String, names: List<String>, tableCount: Int): List<String> =
        listOf(label, thousands(names.size.toLong()), pctCell(names.size.toLong(), tableCount.toLong()),
            names.take(3).joinToString("、").ifEmpty { "-" })

    // ---------- LLM 分析文字 ----------

    private fun buildAnalyses(
        ds: DataSourceConfig, aggs: List<SchemaStats>, sections: List<WordReportTables.TagSection>,
        progress: Progress,
    ): Map<String, String> {
        fun stepAnalysis(section: String, facts: String): String =
            analysis(section, facts).also { progress.step("生成${section}文字") }

        val result = HashMap<String, String>()
        result["emptyAnalysis"] = stepAnalysis("空表分析", emptyFacts(aggs))
        result["fillRateAnalysis"] = stepAnalysis("字段填充率分析", fillRateFacts(aggs))
        // 质量预警:字段问题最突出的库(空字段率最高)
        val worst = aggs.maxByOrNull { if (it.columnCount > 0) it.emptyColumnCount.toDouble() / it.columnCount else 0.0 }
        result["fillRateWarning"] = if (worst == null) PLACEHOLDER else stepAnalysis("质量预警",
            "库 ${worst.name} 字段总数 ${worst.columnCount},空字段 ${worst.emptyColumnCount}," +
                "空字段率 ${pctCell(worst.emptyColumnCount.toLong(), worst.columnCount.toLong())}," +
                "平均有值率 ${String.format("%.2f%%", worst.avgFillRate)};" +
                "全部库平均有值率 ${String.format("%.2f%%", aggs.map { it.avgFillRate }.average())}。")
        result["metadataAnalysis"] = stepAnalysis("元数据完整性分析", metadataFacts(aggs))
        result["typeAnalysis"] = stepAnalysis("数据类型分布分析", typeFacts(aggs))
        result["redundancyAnalysis"] = stepAnalysis("数据冗余分析", redundancyFacts(aggs))
        val largeCount = aggs.sumOf { it.largeTables.size }
        result["largeTableAnalysis"] = stepAnalysis("大体量表分析",
            "全部库共 ${aggs.sumOf { it.tableCount }} 张表,其中行数超过 100 万或占用空间超过 1GB 的大体量表共 $largeCount 张," +
                "占比 ${pctCell(largeCount.toLong(), aggs.sumOf { it.tableCount.toLong() })}。" +
                "各库大体量表数:" + aggs.joinToString("; ") { "${it.name} ${it.largeTables.size} 张" } + "。")
        result["overallAnalysis"] = stepAnalysis("数据源整体分析说明", overallFacts(ds, aggs, sections))
        return result
    }

    /** 统一 LLM 入口:未配置或调用失败返回占位文字,不阻塞导出 */
    private fun analysis(section: String, facts: String): String {
        val config = aiConfigService.findConfig() ?: return PLACEHOLDER
        return try {
            chat(config, ANALYSIS_SYSTEM_PROMPT, "【$section】请根据以下统计数据撰写分析文字:\n$facts")
        } catch (e: Exception) {
            log.warn("LLM 生成「{}」分析失败,留占位: {}", section, e.message)
            PLACEHOLDER
        }
    }

    private fun emptyFacts(aggs: List<SchemaStats>): String {
        val tables = aggs.sumOf { it.tableCount }
        val empty = aggs.sumOf { it.emptyTableCount }
        return "共 ${aggs.size} 个库,$tables 张表,空表(0 行)$empty 张,空表占比 ${pctCell(empty.toLong(), tables.toLong())}。" +
            "各库: " + aggs.joinToString("; ") {
                "${it.name} 表 ${it.tableCount} 空表 ${it.emptyTableCount}" +
                    "(${pctCell(it.emptyTableCount.toLong(), it.tableCount.toLong())})" +
                    (if (it.emptyTableNames.isEmpty()) "" else ",空表示例 " + it.emptyTableNames.take(5).joinToString("、"))
            } + "。"
    }

    private fun fillRateFacts(aggs: List<SchemaStats>): String {
        val buckets = IntArray(4)
        aggs.forEach { a -> a.fillBuckets.forEachIndexed { i, v -> buckets[i] += v } }
        val total = aggs.sumOf { it.columnCount }
        return "共 ${aggs.size} 个库,字段总数 $total。各有值率区间字段数: " +
            FILL_BUCKET_LABELS.mapIndexed { i, l -> "$l ${buckets[i]} 个(占比 ${pctCell(buckets[i].toLong(), total.toLong())})" }
                .joinToString("; ") + "。"
    }

    private fun metadataFacts(aggs: List<SchemaStats>): String {
        val tables = aggs.sumOf { it.tableCount }
        val tableComments = aggs.sumOf { it.tableCommentCount }
        val columns = aggs.sumOf { it.columnCount }
        val columnComments = aggs.sumOf { it.columnCommentCount }
        return "共 ${aggs.size} 个库。表级注释:有注释 $tableComments / 共 $tables(注释率 ${pctCell(tableComments.toLong(), tables.toLong())});" +
            "字段级注释:有注释 $columnComments / 共 $columns(注释率 ${pctCell(columnComments.toLong(), columns.toLong())})。"
    }

    private fun typeFacts(aggs: List<SchemaStats>): String {
        val counts = IntArray(5)
        aggs.forEach { a -> a.typeCounts.forEachIndexed { i, v -> counts[i] += v } }
        val total = aggs.sumOf { it.columnCount }
        return "共 ${aggs.size} 个库,字段总数 $total。各类型字段数: " +
            TYPE_LABELS.mapIndexed { i, l -> "$l ${counts[i]} 个(占比 ${pctCell(counts[i].toLong(), total.toLong())})" }
                .joinToString("; ") + "。"
    }

    private fun redundancyFacts(aggs: List<SchemaStats>): String {
        val tables = aggs.sumOf { it.tableCount }
        val empty = aggs.sumOf { it.emptyTableCount }
        val backup = aggs.sumOf { it.backupTables.size }
        val shard = aggs.sumOf { it.shardedTables.size }
        return "共 ${aggs.size} 个库,$tables 张表。空表 $empty 张(占比 ${pctCell(empty.toLong(), tables.toLong())})," +
            "备份表(_copy/_bak/_tmp 等)$backup 张(占比 ${pctCell(backup.toLong(), tables.toLong())})," +
            "分表(日期/序号后缀)$shard 张(占比 ${pctCell(shard.toLong(), tables.toLong())})。" +
            "备份表示例:" + aggs.flatMap { it.backupTables }.take(5).joinToString("、").ifEmpty { "无" } + ";" +
            "分表示例:" + aggs.flatMap { it.shardedTables }.take(5).joinToString("、").ifEmpty { "无" } + "。"
    }

    private fun overallFacts(
        ds: DataSourceConfig, aggs: List<SchemaStats>, sections: List<WordReportTables.TagSection>,
    ): String =
        "数据源 ${ds.name}(类型 ${ds.dbType?.label ?: "-"}),共 ${aggs.size} 个库," +
            "表 ${aggs.sumOf { it.tableCount }} 张(空表 ${aggs.sumOf { it.emptyTableCount }} 张)," +
            "字段 ${aggs.sumOf { it.columnCount }} 个(空字段 ${aggs.sumOf { it.emptyColumnCount }} 个)," +
            "数据总行数 ${aggs.sumOf { it.totalRows }},总存储量 ${formatBytes(aggs.sumOf { it.sizeBytes })}," +
            "字段有值总率 ${percent(aggs.sumOf { it.valueSum }, aggs.sumOf { it.totalRows })}%。" +
            "用户标记 ${sections.size} 个:" + sections.joinToString("、") { it.tagName }.ifEmpty { "无" } + "。"

    private fun tagFacts(tagName: String, aggs: List<SchemaStats>, unscanned: Int): String =
        "标记「$tagName」:分布在 ${aggs.size} 个库,已扫描打标表 ${aggs.sumOf { it.tableCount }} 张" +
            "(空表 ${aggs.sumOf { it.emptyTableCount }} 张),字段 ${aggs.sumOf { it.columnCount }} 个," +
            "数据总行数 ${aggs.sumOf { it.totalRows }},存储量 ${formatBytes(aggs.sumOf { it.sizeBytes })}," +
            "字段有值总率 ${percent(aggs.sumOf { it.valueSum }, aggs.sumOf { it.totalRows })}%,未扫描打标表 $unscanned 张。" +
            "各库: " + aggs.joinToString("; ") { "${it.name} ${it.tableCount} 张表 ${it.totalRows} 行" } + "。"

    // ---------- 打标表两张明细表 ----------

    private fun overviewRows(aggs: List<SchemaStats>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        aggs.forEachIndexed { i, a ->
            rows.add(listOf((i + 1).toString(), a.name, a.description,
                thousands(a.tableCount.toLong()), thousands(a.columnCount.toLong()),
                thousands(a.totalRows), formatBytes(a.sizeBytes)))
        }
        rows.add(listOf("", "合计", "",
            thousands(aggs.sumOf { it.tableCount.toLong() }), thousands(aggs.sumOf { it.columnCount.toLong() }),
            thousands(aggs.sumOf { it.totalRows }), formatBytes(aggs.sumOf { it.sizeBytes })))
        return rows
    }

    private fun qualityRows(aggs: List<SchemaStats>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        for (a in aggs) {
            rows.add(listOf(a.name, thousands(a.tableCount.toLong()), thousands(a.emptyTableCount.toLong()),
                pctCell(a.emptyTableCount.toLong(), a.tableCount.toLong()),
                thousands(a.columnCount.toLong()), thousands(a.emptyColumnCount.toLong()),
                pctCell(a.emptyColumnCount.toLong(), a.columnCount.toLong()),
                String.format("%.2f%%", a.avgFillRate)))
        }
        rows.add(listOf("合计/平均",
            thousands(aggs.sumOf { it.tableCount.toLong() }), thousands(aggs.sumOf { it.emptyTableCount.toLong() }),
            pctCell(aggs.sumOf { it.emptyTableCount.toLong() }, aggs.sumOf { it.tableCount.toLong() }),
            thousands(aggs.sumOf { it.columnCount.toLong() }), thousands(aggs.sumOf { it.emptyColumnCount.toLong() }),
            pctCell(aggs.sumOf { it.emptyColumnCount.toLong() }, aggs.sumOf { it.columnCount.toLong() }),
            String.format("%.2f%%", if (aggs.isEmpty()) 0.0 else aggs.map { it.avgFillRate }.average())))
        return rows
    }

    private companion object {
        const val TEMPLATE_PATH = "/templates/data-survey-report.docx"
        const val PLACEHOLDER = "(待人工编写)"
        const val LARGE_TABLE_ROWS = 1_000_000L
        const val LARGE_TABLE_BYTES = 1024L * 1024 * 1024 // 1GB

        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
        val OVERVIEW_HEADERS = listOf("序号", "数据库实例", "实例描述", "总表", "字段数", "数据行数", "占用空间")
        val QUALITY_HEADERS = listOf("数据库实例", "表数", "空表数", "空表率", "字段数", "空字段数", "空字段率", "平均有值率")
        val FILL_BUCKET_LABELS = listOf("＜10%(低填充)", "10%-50%(中低填充)", "50%-80%(中等填充)", "≥80%(高填充)")
        val TYPE_LABELS = listOf("字符串型", "整数型", "小数型", "时间型", "其他型")
        val TYPE_EXAMPLES = listOf("Varchar、char、text", "Int、bigint、tinyint", "Decimal、float、double",
            "Datetime、timestamp", "-")

        /** 备份表:_copy/_bak/_backup/_tmp 结尾(可带序号) */
        val BACKUP_RE = Regex("_(copy|bak|backup|tmp)\\d*$", RegexOption.IGNORE_CASE)

        /** 分表:_MMdd/_yyyyMM/_yyyyMMdd 等 4/6/8 位数字日期后缀 */
        val SHARD_RE = Regex("_(\\d{4}|\\d{6}|\\d{8})$")

        const val ANALYSIS_SYSTEM_PROMPT =
            "你是数据调研报告撰写助手。根据给定统计数据撰写一段 120-200 字的中文分析文字," +
                "严格使用给定数字、不得编造或四舍五入改写数字,不使用标题/列表/换行,直接输出正文段落。"

        /** 有值率分桶:<10 / 10-50 / 50-80 / >=80 */
        fun fillBucket(rate: Double): Int = when {
            rate < 10.0 -> 0
            rate < 50.0 -> 1
            rate < 80.0 -> 2
            else -> 3
        }

        /** 字段类型归类:0 字符串 / 1 整数 / 2 小数 / 3 时间 / 4 其他(按类型名小写匹配,各库类型名通用) */
        fun typeCategory(columnType: String?): Int {
            val t = columnType?.lowercase()?.ifBlank { null } ?: return 4
            return when {
                t.contains("char") || t.contains("text") || t.contains("clob") || t.contains("json") -> 0
                t.contains("decimal") || t.contains("numeric") || t.contains("float") ||
                    t.contains("double") || t.contains("real") || t.contains("money") || t.contains("number") -> 2
                t.contains("date") || t.contains("time") || t.contains("year") -> 3
                t.contains("int") || t.contains("serial") || t.contains("bit") -> 1
                else -> 4
            }
        }

        /** 千分位整数,与报告示例样式一致(如 100,649,673) */
        fun thousands(v: Long): String = String.format("%,d", v)

        /** 百分比数值(两位小数,模板里 % 是句子的固定文字);分母为 0 时返回 "-" */
        fun percent(part: Long, total: Long): String =
            if (total > 0) String.format("%.2f", part * 100.0 / total) else "-"

        /** 百分比单元格(自带 %,表格单元格无固定文字);分母为 0 时返回 "-" */
        fun pctCell(part: Long, total: Long): String =
            if (total > 0) String.format("%.2f%%", part * 100.0 / total) else "-"

        /** 字节数转可读单位,如 18.9 GB(与 Excel 导出 formatBytes 同款) */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) {
                return "$bytes B"
            }
            val units = arrayOf("KB", "MB", "GB", "TB", "PB")
            var v = bytes.toDouble()
            var u = -1
            do {
                v /= 1024
                u++
            } while (v >= 1024 && u < units.size - 1)
            return String.format("%.1f %s", v, units[u])
        }
    }
}
