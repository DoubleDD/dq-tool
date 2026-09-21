package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.AiScene
import com.example.dq.model.ColumnMeta
import com.example.dq.model.CompareAiTrace
import com.example.dq.model.CompareImportView
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.PendingReason
import com.example.dq.model.TestConnectionRequest
import com.example.dq.repository.CompareAiTraceRepository
import com.example.dq.repository.CompareImportRepository
import com.example.dq.repository.DataSourceRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists

/**
 * 比对任务批量导入:上传一张 xlsx(一 sheet 一任务,格式见 [CompareImportExcelParser])批量建比对任务。
 * 三步流程:①submit(原件先落盘留档 → 解析 → 按「类型+地址+端口+库」匹配已建档数据源 → 批次置
 * DS_REVIEW 等用户确认)→ ②confirm(支持用户在映射清单上逐行改绑:绑到任意现有数据源或「待新建」;
 * 数据源同步落库 + 逐 sheet 快速建 PENDING 任务,连接实测与字段映射推导转后台)→
 * ③人工审核映射后开始比对(归 CompareService.confirmMapping/start)。
 *
 * 关键口径:
 * - **原件留档**:上传字节先写 数据目录/compare-imports/batch-<id>/<原文件名>(文件名仅去路径分隔符,
 *   一批次一目录不冲突),解析失败/用户放弃也不删;口令只活在内存(confirm 时重新从原件读入);
 * - **大模型是前置条件**:未配置时 submit 直接抛 [IllegalStateException](上层转 409),此时文件不落盘;
 * - **confirm 不阻塞大模型/实测**:批次只做「数据源插行 + 建任务」等秒级动作即置 DONE,前端弹窗立即可关。
 *   待新建数据源先插行(有口令的 conn_status 空=未实测,实测挪到后台;无口令的照旧直接标 ERROR),
 *   任务落 PENDING + MAPPING_RUNNING(映射推导中);涉及异常数据源(无法建档/无口令/绑到 ERROR)
 *   的任务直接 DS_ERROR,不进推导队列;
 * - **后台逐任务推导**(映射线程池):先实测任务涉及的待新建数据源(批次内同 dsId 只测一次,
 *   单条 20s 与抽样导出同口径),实测失败 → 数据源转 ERROR、任务转 DS_ERROR 不推导;连通后做
 *   基准表校验(失败转 IMPORT_ERROR)并逐目标串行调大模型推导映射(表格锁定身份字段合并优先、
 *   必含主键校验照旧),完成转 MAPPING_REVIEW 等人工审核;单目标推导失败只留锁定项并记目标说明,
 *   整任务意外异常转 IMPORT_ERROR 记 error;
 * - 异常数据源(无口令未实测/实测连不上)也建档(conn_status=ERROR,便于在数据源页修复后复活任务);
 * - 重启恢复:残留 BUILDING 批次置 FAILED;残留 MAPPING_RUNNING 任务由 CompareService.recoverUnfinished
 *   转 MAPPING_REVIEW(各清各的,不重复清理)
 */
class CompareImportService(
    private val repo: CompareImportRepository,
    private val dataSourceRepo: DataSourceRepository,
    private val dataSourceService: DataSourceService,
    private val metadataService: MetadataService,
    private val compareService: CompareService,
    private val aiConfigService: AiConfigService?,
    private val config: AppConfig,
    /** AI 配置读取点(默认 [AiConfigService.findConfig];测试可注入 fake) */
    private val aiConfigProvider: () -> AiConfigService.Config? = { aiConfigService?.findConfig() },
    /** 表字段清单读取点(默认走 [MetadataService] 缓存优先路径;测试可注入 fake 避免连业务库) */
    private val columnsLister: (datasourceId: Long, db: String, schema: String, table: String) -> List<ColumnMeta> =
        { dsId, db, schema, table -> metadataService.listTableColumns(dsId, db, schema, table) },
    /** 字段映射推导的 LLM 调用点(默认 COMPARE_MAPPING 场景,测试可注入 fake) */
    private val aiMappingChat: CompareService.AiChat = { c, s, u ->
        AiService().chat(c, s, u, AiScene.COMPARE_MAPPING)
    },
    /** 单数据源实测(默认 [DataSourceService.testConnection];返回 null=连通,测试可注入 fake) */
    private val dsTester: (TestConnectionRequest) -> String? = { req ->
        try {
            dataSourceService.testConnection(req)
            null
        } catch (e: Exception) {
            (e.message ?: "连接失败").take(2000)
        }
    },
    /** AI 判定留痕仓储(AI 用量库 compare_ai_trace);空 = 不留痕(单测默认) */
    private val aiTraceRepo: CompareAiTraceRepository? = null,
    /** AI 判定留痕记录点(默认写 [aiTraceRepo];测试注入捕获器) */
    private val aiTraceRecorder: (CompareAiTrace) -> Unit = { t -> aiTraceRepo?.insert(t) },
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    /** 建任务线程池(数据源同步落库 + 快速落 PENDING 任务,秒级,不做暂停/恢复;并发批次间数据源「查重→建档」经 [dsWriteLock] 互斥) */
    private val executor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "compare-import-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
    }

    /** 字段映射线程池:任务落库后逐任务后台推进(待新建数据源实测 + 大模型推导);与建任务线程池分离,避免长耗时阻塞后续批次确认 */
    private val mappingExecutor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "compare-import-map-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
    }

    /** 并发批次间数据源「查重→建档」互斥锁:锁内按最新库重判,避免两个批次为同一地址重复建档 */
    private val dsWriteLock = Any()

    private val importDir: Path get() = config.dataDir.resolve("compare-imports")

    /** 原件下载载体:fileName 原样(供 Content-Disposition),path 为落盘位置 */
    data class CompareImportFile(val fileName: String, val path: Path)

    // ---------- ① 提交:落盘 + 解析 + 数据源匹配 ----------

    /**
     * 上传导入:未配置大模型直接抛 [IllegalStateException](409,文件不落盘,入口即拦);
     * 原件先落盘(批次插行拿 id → 写文件 → 回填 file_path/file_size)→ 解析全部 sheet →
     * 汇总去重数据源逐条匹配已建档 → 批次置 DS_REVIEW。解析失败批次置 FAILED(原件保留)并把异常抛给上层
     */
    fun submit(fileName: String, input: InputStream): CompareImportView {
        aiConfigProvider()
            ?: throw IllegalStateException("请先在「AI 配置」中完成大模型配置,再使用比对任务批量导入")
        val bytes = input.readBytes()
        val safeName = sanitizeFileName(fileName)
        val id = repo.insert(safeName)
        try {
            val dir = importDir.resolve("batch-$id")
            Files.createDirectories(dir)
            val file = dir.resolve(safeName)
            Files.write(file, bytes)
            repo.updateFileInfo(id, file.toString(), bytes.size.toLong())

            val parsed = CompareImportExcelParser.parse(bytes.inputStream())
            if (parsed.sheets.isEmpty()) {
                throw IllegalArgumentException("Excel 中没有有效的工作表(${describeParseSkips(parsed).take(500)})")
            }
            repo.markDsReview(id, objectMapper.writeValueAsString(buildDsReport(parsed)), parsed.sheets.size)
            log.info("比对导入批次已提交: id={}, 文件={}, 有效 sheet={}, 跳过 sheet={}",
                id, safeName, parsed.sheets.size, parsed.skippedSheets.size)
            return get(id)
        } catch (e: Exception) {
            repo.fail(id, (e.message ?: "导入解析失败").take(1000))
            throw e
        }
    }

    /** 批次详情(前端数据源映射清单弹窗) */
    fun get(id: Long): CompareImportView {
        val b = repo.findById(id) ?: throw IllegalArgumentException("导入批次不存在: $id")
        return toView(b)
    }

    /** 下载原件:文件名原样,供 controller 流式输出 */
    fun downloadFile(id: Long): CompareImportFile {
        val b = repo.findById(id) ?: throw IllegalArgumentException("导入批次不存在: $id")
        val path = Path.of(b.filePath)
        if (!path.exists()) throw IllegalStateException("原件文件已被移动或删除")
        return CompareImportFile(b.fileName, path)
    }

    /** 导入模版 xlsx(表头 + 基准/对比两行示例) */
    fun writeTemplate(out: OutputStream) = CompareImportExcelParser.writeTemplate(out)

    // ---------- ② 确认:建档数据源 + 逐 sheet 建任务 ----------

    /**
     * 用户确认数据源映射(仅 DS_REVIEW 批次):转后台「数据源同步落库 + 逐 sheet 快速建任务」,
     * 批次 BUILDING → DONE/FAILED(秒级;连接实测与字段映射推导在批次 DONE 后由映射线程池接手)。
     * [overrides] 为用户在映射清单上的逐行选择:键 = ds_report 行 key,值 = 改绑的数据源 id
     * (null = 该行「待新建」,按表格连接信息先插行建档);空映射 = 全部按匹配结果照旧(不带 body 的老前端)。
     * 键不在报告里的映射项忽略;改绑的数据源 id 不存在抛 [IllegalArgumentException](上层转 400),
     * 校验在转后台前完成,失败不消费 DS_REVIEW 状态(可改后重新确认)
     */
    fun confirm(id: Long, overrides: Map<String, Long?> = emptyMap()) {
        val batch = repo.findById(id) ?: throw IllegalArgumentException("导入批次不存在: $id")
        val reportKeys = parseReportKeys(batch.dsReport)
        val effective = overrides.filterKeys { it in reportKeys }
        val missing = effective.values.filterNotNull().toSet()
            .filter { dataSourceRepo.findById(it) == null }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("改绑的数据源不存在: ${missing.joinToString(", ")}")
        }
        if (repo.markBuilding(id) == 0) throw IllegalStateException("批次不在「待确认数据源」状态,不能确认")
        executor.execute { runConfirm(id, effective) }
    }

    /** ds_report JSON 里的数据源行 key 集合(NOTE 行 key 为 null 不参与;解析失败按空集,映射项全部忽略) */
    private fun parseReportKeys(dsReportJson: String?): Set<String> {
        if (dsReportJson == null) return emptySet()
        return try {
            objectMapper.readValue<List<Map<String, Any?>>>(dsReportJson)
                .mapNotNullTo(HashSet()) { it["key"] as? String }
        } catch (e: Exception) {
            log.warn("比对导入 ds_report JSON 解析失败(confirm 校验映射): {}", e.message)
            emptySet()
        }
    }

    private fun runConfirm(id: Long, overrides: Map<String, Long?>) {
        try {
            val batch = repo.findById(id) ?: return
            // 重新从原件解析:口令只活在内存,不落库不跨阶段保留
            val parsed = Files.newInputStream(Path.of(batch.filePath)).use {
                CompareImportExcelParser.parse(it)
            }
            val report = ArrayList<MutableMap<String, Any?>>()
            appendParseNotes(parsed, report)
            // 数据源同步落库:待新建直接插行(有口令的 conn_status 空=未实测,实测挪后台;无口令照旧标 ERROR)
            val outcomes = resolveDatasources(parsed, report, overrides)
            // 逐 sheet 快速落 PENDING 任务(目标行只写表格锁定的身份映射),基准校验与字段映射转后台线程池
            val dsTests = BatchDsTester()
            val jobIds = ArrayList<Long>()
            val mappings = ArrayList<MappingTaskContext>()
            for (sheet in parsed.sheets) {
                val created = createPendingTask(id, batch.fileName, sheet, outcomes, report, dsTests) ?: continue
                jobIds.add(created.jobId)
                created.mapping?.let { mappings.add(it) }
            }
            repo.finish(id, objectMapper.writeValueAsString(report), objectMapper.writeValueAsString(jobIds))
            log.info("比对导入批次任务已落库: id={}, 任务数={}, 待后台推导={}", id, jobIds.size, mappings.size)
            for (ctx in mappings) mappingExecutor.execute { runMapping(ctx) }
        } catch (e: Exception) {
            log.error("比对导入批次建任务失败: id={}", id, e)
            repo.fail(id, (e.message ?: "建任务失败").take(1000))
        }
    }

    /** phase 1 产物:任务 id + 后台映射上下文(null = 无需推导:DS_ERROR 任务不进队列) */
    private data class CreatedTask(val jobId: Long, val mapping: MappingTaskContext?)

    /**
     * 后台字段映射上下文:phase 1 快速建档时按 sheet 暂存,phase 2 线程逐任务回写。
     * [rows]/[targetOutcomes]/[targetIds]/[lockedList] 同序,只含成功绑定到数据源的目标行
     * (未建档目标已在报告记 NOTE);[keyField]/[displayField] 为表格原值(基准表归一在 phase 2 做)
     */
    private data class MappingTaskContext(
        val jobId: Long,
        val sheet: CompareImportExcelParser.SheetTask,
        val baseOutcome: DsOutcome,
        val keyField: String,
        val displayField: String,
        val rows: List<CompareImportExcelParser.ImportRow>,
        val targetOutcomes: List<DsOutcome>,
        val targetIds: List<Long>,
        val lockedList: List<Map<String, String>>,
        val dsTests: BatchDsTester,
    ) {
        /** 表格原值兜底比对字段(基准表读不出时的落库值) */
        val fallbackFields: List<String> get() = listOf(keyField, displayField).distinct()
    }

    // ---------- 数据源:匹配 / 建档 ----------

    /**
     * 单个唯一数据源的确认结果;connectable=false 的其下任务记 DS_ERROR;
     * pendingTest=true(待新建有口令已插行未实测)的由后台映射阶段先实测再推导
     */
    private data class DsOutcome(
        val dsId: Long?, val name: String, val action: String, val error: String?,
        val connectable: Boolean, val pendingTest: Boolean = false)

    /**
     * submit 阶段的数据源映射报告:跨 sheet 汇总去重(类型+地址+端口+库),逐条匹配已建档——
     * MATCHED 已匹配 / CREATE 待新建(有用户名口令) / ERROR 异常(两头都没有);
     * 按表格推算的数据源名(「实际系统或模式描述(数据库名称)」)与已建档名不一致时以地址匹配为准并在 error 里提示;
     * 跳过的 sheet/行记 NOTE
     */
    private fun buildDsReport(parsed: CompareImportExcelParser.ParseResult): ArrayList<MutableMap<String, Any?>> {
        val report = ArrayList<MutableMap<String, Any?>>()
        appendParseNotes(parsed, report)
        val existingByKey = existingByLocationKey()
        for (row in uniqueRows(parsed)) {
            val entry = reportEntry(row)
            val existing = existingByKey[row.dsKey]
            when {
                existing != null -> {
                    entry["action"] = "MATCHED"
                    entry["datasourceId"] = existing.id
                    entry["name"] = existing.name
                    if (!row.dsDisplay.isNullOrBlank() && row.dsDisplayName != existing.name) {
                        entry["error"] = "与表格「实际系统或模式描述」推算名(${row.dsDisplayName})不一致,以地址匹配为准"
                    }
                }
                row.username != null && row.password != null -> entry["action"] = "CREATE"
                else -> {
                    entry["action"] = "ERROR"
                    entry["error"] = "数据源不存在且表格未提供用户名/口令"
                }
            }
            report.add(entry)
        }
        return report
    }

    /**
     * confirm 阶段:逐「待新建」同步插行建档(不再同步实测:有口令的 conn_status 留空=未实测,
     * 连接实测挪到后台映射阶段按批次去重;无口令的照旧直接标 ERROR),异常数据源也建档
     * (ERROR 状态,便于在数据源页修复后复活任务)。
     * [overrides] 为用户改绑(键已按报告行 key 过滤、id 已校验存在):值非 null 直接绑定该数据源,
     * 跳过建档,action 记 REBOUND(改绑);值 null = 用户明确「待新建」,即使地址能匹配已建档也强制走建档
     */
    private fun resolveDatasources(
        parsed: CompareImportExcelParser.ParseResult, report: ArrayList<MutableMap<String, Any?>>,
        overrides: Map<String, Long?> = emptyMap(),
    ): Map<String, DsOutcome> {
        val unique = uniqueRows(parsed)
        val allExisting = dataSourceRepo.findAll()
        val existingByKey = allExisting.mapNotNull { ds ->
            DatasourceKeyMatcher.locationKeyOf(ds)?.let { it to ds }
        }.toMap()
        val existingById = allExisting.associateBy { it.id }
        val outcomes = LinkedHashMap<String, DsOutcome>()

        for (row in unique) {
            val entry = reportEntry(row)
            val existing = existingByKey[row.dsKey]
            val outcome = when {
                overrides.containsKey(row.dsKey) -> {
                    val bindId = overrides[row.dsKey]
                    val bindDs = bindId?.let { existingById[it] }
                    when {
                        bindDs != null -> bindExisting(bindDs, entry, "REBOUND", null)
                        bindId != null -> {
                            // confirm 已校验存在;兜底异步执行期间被删:记异常,涉及任务走「无法建档」
                            entry["action"] = "ERROR"
                            entry["error"] = "改绑的数据源 $bindId 已不存在"
                            DsOutcome(null, entry["name"] as String, "ERROR", entry["error"] as String, false)
                        }
                        // 用户明确「待新建」:跳过地址重判强制建档(地址本可匹配时允许建出重复数据源)
                        else -> createDatasource(row, entry, skipRecheck = existing != null)
                    }
                }
                existing != null -> bindExisting(existing, entry, "MATCHED",
                    if (!row.dsDisplay.isNullOrBlank() && row.dsDisplayName != existing.name) {
                        "与表格「实际系统或模式描述」推算名(${row.dsDisplayName})不一致,以地址匹配为准"
                    } else null)
                else -> createDatasource(row, entry)
            }
            outcomes[row.dsKey] = outcome
            report.add(entry)
        }
        return outcomes
    }

    /** 绑定已建档数据源(地址匹配 MATCHED / 用户改绑 REBOUND);连接状态 ERROR 的不自动复测,任务记 DS_ERROR */
    private fun bindExisting(
        ds: DataSourceConfig, entry: MutableMap<String, Any?>,
        action: String, nameNote: String?,
    ): DsOutcome {
        entry["action"] = action
        entry["datasourceId"] = ds.id
        entry["name"] = ds.name
        if (nameNote != null) entry["error"] = nameNote
        val connectable = ds.connStatus != "ERROR"
        if (!connectable) entry["error"] = listOfNotNull(entry["error"] as String?,
            "数据源连接状态异常: ${ds.connError ?: ""}".trim()).joinToString(";")
        return DsOutcome(ds.id, ds.name ?: entry["name"] as String, action,
            entry["error"] as String?, connectable)
    }

    /**
     * 建档单个数据源(同步插行不实测);锁内按最新库重判,并发批次不重复建档([skipRecheck]=用户明确「待新建」时跳过地址重判)。
     * 类型直接取表格「数据库类型」列;命名取「实际系统或模式描述(数据库名称)」(描述空则只用库名),重名加「 (2)」后缀。
     * 有口令:conn_status 留空(未实测),返回 pendingTest=true 由后台映射阶段实测;无口令:照旧直接标 ERROR 免测
     */
    private fun createDatasource(
        row: CompareImportExcelParser.ImportRow, entry: MutableMap<String, Any?>,
        skipRecheck: Boolean = false,
    ): DsOutcome {
        val display = row.dsDisplayName
        synchronized(dsWriteLock) {
            if (!skipRecheck) {
                val recheck = existingByLocationKey()[row.dsKey]
                if (recheck != null) {
                    entry["action"] = "MATCHED"
                    entry["datasourceId"] = recheck.id
                    entry["name"] = recheck.name
                    val connectable = recheck.connStatus != "ERROR"
                    return DsOutcome(recheck.id, recheck.name ?: display, "MATCHED", null, connectable)
                }
            }
            val usedNames = dataSourceRepo.findAll().mapNotNull { it.name }.toMutableSet()
            val name = uniqueName(display, usedNames)
            val newId = dataSourceService.create(DataSourceRequest(
                name = name, jdbcUrl = SampleExportService.withMssqlDefaults(jdbcUrlOf(row)),
                username = row.username, password = row.password,
                rowThreshold = null, sizeThresholdBytes = null, groupName = "比对批量导入"))
            entry["action"] = "CREATED"
            entry["datasourceId"] = newId
            entry["name"] = name
            return if (row.username == null || row.password == null) {
                // 无口令:照旧直接标 ERROR 免测(后台也不再测),涉及任务记 DS_ERROR,修复数据源后复活
                val error = "缺少用户名/口令,未实测"
                dataSourceRepo.updateConnStatus(newId, "ERROR", error)
                entry["error"] = error
                DsOutcome(newId, name, "CREATED", error, connectable = false)
            } else {
                // 有口令:先插行(conn_status 空=未实测),连接实测挪到后台映射阶段(批次内同 dsId 只测一次)
                DsOutcome(newId, name, "CREATED", null, connectable = true, pendingTest = true)
            }
        }
    }

    // ---------- 逐 sheet 建任务(phase 1:同步快速落库) ----------

    /**
     * 一个 sheet 快速落一个 PENDING 任务(不调大模型、不连业务库,秒级):身份字段按表格原值兜底
     * (基准表校验与字段归一在后台推导阶段做);表格给了的身份字段锁定进目标 mapping(从推导范围剔除);
     * 涉及异常数据源(无法建档/无口令未实测/绑到 conn_status=ERROR)置 DS_ERROR 不进推导队列,
     * 否则 MAPPING_RUNNING 等后台推导。任务名 = 文件名(去扩展名)-sheet 名,超长截断 200。
     * 基准数据源无法建档时不建任务、记报告(返回 null)
     */
    private fun createPendingTask(
        batchId: Long, fileName: String, sheet: CompareImportExcelParser.SheetTask,
        outcomes: Map<String, DsOutcome>, report: ArrayList<MutableMap<String, Any?>>,
        dsTests: BatchDsTester,
    ): CreatedTask? {
        val taskName = taskName(fileName, sheet.sheetName, sheet.sheetIndex)
        val baseOutcome = outcomes[sheet.base.dsKey]
        val baseDsId = baseOutcome?.dsId
        if (baseDsId == null) {
            report.add(mutableMapOf("key" to sheet.base.dsKey, "name" to "工作表「${sheet.sheetName}」",
                "host" to sheet.base.host, "port" to sheet.base.port, "databaseName" to sheet.base.databaseName,
                "action" to "NOTE", "datasourceId" to null,
                "error" to "基准数据源无法建档,未建任务: ${baseOutcome?.error ?: "数据源异常"}"))
            return null
        }
        var dsError = !baseOutcome.connectable

        // 库/schema 按数据源方言归一后再落库:单库方言(MySQL 等)schema=库名、db 空,与手工建任务同口径;
        // 直接存表格原值(库名进 db、schema 空)会让前端编辑向导/字段审核按 schemas/{schema}/ 拼出 // 路径 404
        val (baseDb, baseSchema) = compareService.normalizeLocation(baseDsId, sheet.base.databaseName, sheet.base.schemaName)

        // 身份字段按表格原值兜底;基准表实际列名归一(含表存在/身份字段校验)挪到后台推导阶段
        val keyField = sheet.base.codeField!!
        val displayField = sheet.base.nameField!!
        val fields = listOf(keyField, displayField).distinct()

        val targets = ArrayList<CompareService.PendingTargetSpec>()
        val rows = ArrayList<CompareImportExcelParser.ImportRow>()
        val targetOutcomes = ArrayList<DsOutcome>()
        val lockedList = ArrayList<Map<String, String>>()
        for (row in sheet.targets) {
            val outcome = outcomes[row.dsKey]
            val targetDsId = outcome?.dsId
            if (targetDsId == null) {
                dsError = true
                report.add(mutableMapOf("key" to row.dsKey, "name" to "工作表「${sheet.sheetName}」目标 ${row.tableName}",
                    "host" to row.host, "port" to row.port, "databaseName" to row.databaseName,
                    "action" to "NOTE", "datasourceId" to null,
                    "error" to "目标数据源无法建档,该目标未加入任务: ${outcome?.error ?: "数据源异常"}"))
                continue
            }
            if (!outcome.connectable) dsError = true
            val locked = LinkedHashMap<String, String>()
            if (row.codeField != null) locked[keyField] = row.codeField
            if (row.nameField != null) locked[displayField] = row.nameField
            val (tDb, tSchema) = compareService.normalizeLocation(targetDsId, row.databaseName, row.schemaName)
            targets.add(CompareService.PendingTargetSpec(
                targetDsId, outcome.name, tDb, tSchema, row.tableName, locked))
            rows.add(row)
            targetOutcomes.add(outcome)
            lockedList.add(locked)
        }

        if (dsError) {
            // 涉及异常数据源:直接 DS_ERROR 落库,不进推导队列(修复数据源后经 confirmMapping 复活)
            val jobId = compareService.createPending(taskName, baseDsId, baseDb,
                baseSchema, sheet.base.tableName, keyField, fields, displayField,
                targets, PendingReason.DS_ERROR, sheet.objectCategory, batchId)
            return CreatedTask(jobId, null)
        }
        val created = compareService.createPendingWithTargetIds(taskName, baseDsId, baseDb,
            baseSchema, sheet.base.tableName, keyField, fields, displayField,
            targets, PendingReason.MAPPING_RUNNING, sheet.objectCategory, batchId)
        return CreatedTask(created.jobId, MappingTaskContext(created.jobId, sheet, baseOutcome,
            keyField, displayField, rows, targetOutcomes, created.targetIds, lockedList, dsTests))
    }

    // ---------- 后台映射推导(phase 2:实测 + 基准校验 + 逐目标大模型推导) ----------

    /**
     * 后台逐任务推进:① 实测任务涉及的待新建数据源(批次内同 dsId 只测一次;失败 → 数据源转 ERROR、
     * 任务转 DS_ERROR 不推导)→ ② 基准表校验(表存在、code/name 字段存在;失败转 IMPORT_ERROR,
     * 身份字段按表格原值兜底)与字段归一 → ③ 逐目标串行调大模型推导(锁定项合并优先、必含主键校验),
     * 全完转 MAPPING_REVIEW 等人工审核;单目标推导失败只留锁定项并记目标说明(现行口径),
     * 整任务意外异常转 IMPORT_ERROR 记 error
     */
    private fun runMapping(ctx: MappingTaskContext) {
        try {
            // ① 待新建数据源实测(基准 + 各目标;去重与并发等待由 BatchDsTester 保证)
            val failedDs = LinkedHashMap<String, String>()
            for (o in listOf(ctx.baseOutcome) + ctx.targetOutcomes) {
                val dsId = o.dsId
                if (!o.pendingTest || dsId == null) continue
                val err = ctx.dsTests.test(dsId, ::testCreatedDatasource)
                if (err != null) failedDs.putIfAbsent(o.name, err)
            }
            if (failedDs.isNotEmpty()) {
                compareService.completePendingMapping(ctx.jobId, ctx.keyField, ctx.fallbackFields, ctx.displayField,
                    emptyMap(), PendingReason.DS_ERROR,
                    "数据源连接实测失败: " + failedDs.entries.joinToString(";") { "${it.key}(${it.value})" })
                return
            }

            // ② 基准表校验 + 字段归一(此刻数据源已实测;读不出/校验失败整任务转 IMPORT_ERROR)
            var keyField = ctx.keyField
            var displayField = ctx.displayField
            var fields = ctx.fallbackFields
            var baseFieldItems = fields.map { CompareMappingPrompts.ColumnItem(it, null, null) }
            val baseColumns = try {
                columnsLister(ctx.baseOutcome.dsId!!, ctx.sheet.base.databaseName,
                    CompareService.effectiveSchema(ctx.sheet.base.schemaName, ctx.sheet.base.databaseName),
                    ctx.sheet.base.tableName)
            } catch (e: Exception) {
                log.warn("比对导入基准表读取失败: sheet={}, 表={}: {}", ctx.sheet.sheetName,
                    ctx.sheet.base.tableName, e.message)
                null
            }
            val importError = when {
                baseColumns == null -> "基准表读取失败: ${ctx.sheet.base.tableName}"
                baseColumns.isEmpty() -> "基准表不存在或没有字段: ${ctx.sheet.base.tableName}"
                else -> {
                    val byName = baseColumns.associateBy { it.name.lowercase() }
                    val kc = byName[keyField.lowercase()]
                    val nc = byName[displayField.lowercase()]
                    if (kc == null || nc == null) {
                        "基准表缺少身份字段: " + listOfNotNull(
                            if (kc == null) keyField else null, if (nc == null) displayField else null)
                            .joinToString("、")
                    } else {
                        keyField = kc.name
                        displayField = nc.name
                        fields = baseColumns.map { it.name }
                        baseFieldItems = baseColumns.map { CompareMappingPrompts.columnItemOf(it) }
                        null
                    }
                }
            }
            if (importError != null) {
                compareService.completePendingMapping(ctx.jobId, keyField, fields, displayField,
                    emptyMap(), PendingReason.IMPORT_ERROR, importError)
                return
            }

            // ③ 逐目标串行推导:锁定(表格给了 code/name)→ 大模型推导其余 → 合并(锁定优先)
            val aiConfig = aiConfigProvider()
            val targetMappings = LinkedHashMap<Long, Map<String, String>?>()
            for (i in ctx.rows.indices) {
                val row = ctx.rows[i]
                val outcome = ctx.targetOutcomes[i]
                val locked = ctx.lockedList[i]
                val targetId = ctx.targetIds[i]
                var mapping: Map<String, String> = locked
                // 留痕上下文:走到大模型调用才记(列读取/未配置等前置失败不是 AI 判定)
                var tracePrompt: String? = null
                var traceAnswer: String? = null
                var traceMerged: Map<String, String>? = null
                var traceDurationMs: Long? = null
                try {
                    val cols = columnsLister(outcome.dsId!!, row.databaseName,
                        CompareService.effectiveSchema(row.schemaName, row.databaseName), row.tableName)
                    if (cols.isEmpty()) throw IllegalArgumentException("目标表不存在或没有字段")
                    for ((_, tc) in locked) {
                        if (cols.none { it.name.equals(tc, ignoreCase = true) }) {
                            throw IllegalArgumentException("表格指定的身份列在目标表不存在: $tc")
                        }
                    }
                    if (aiConfig == null) throw IllegalStateException("未配置大模型")
                    val prompt = CompareMappingPrompts.buildMappingPrompt(
                        loc(ctx.sheet.base.databaseName, ctx.sheet.base.schemaName, ctx.sheet.base.tableName),
                        baseFieldItems, loc(row.databaseName, row.schemaName, row.tableName),
                        cols.map { CompareMappingPrompts.columnItemOf(it) }, locked)
                    tracePrompt = prompt
                    val callStart = System.nanoTime()
                    val answer = try {
                        aiMappingChat.call(aiConfig, CompareMappingPrompts.SYSTEM_PROMPT, prompt)
                    } finally {
                        // 调用成功/抛异常都记耗时(未走到调用则保持 null,不落留痕)
                        traceDurationMs = (System.nanoTime() - callStart) / 1_000_000
                    }
                    traceAnswer = answer
                    val suggested = CompareMappingPrompts.parseMappingSuggest(answer, fields, cols, keyField)
                    val merged = LinkedHashMap<String, String>(suggested)
                    locked.forEach { (k, v) -> merged[k] = v } // 锁定优先(表格身份字段不进推导范围)
                    traceMerged = merged
                    if (merged.keys.none { it.equals(keyField, ignoreCase = true) }) {
                        throw IllegalStateException("映射不含比对主键,需人工审核补线")
                    }
                    mapping = merged
                    // 留痕:推导出的完整映射 + 表格锁定项(锁定行前端标注)
                    recordTrace {
                        CompareAiTrace(jobId = ctx.jobId, targetId = targetId,
                            targetLabel = loc(row.databaseName, row.schemaName, row.tableName),
                            scene = AiScene.COMPARE_MAPPING.name, stage = CompareAiTrace.STAGE_MAPPING,
                            model = aiConfig.model,
                            requestContent = traceRequestContent(CompareMappingPrompts.SYSTEM_PROMPT, prompt),
                            responseContent = answer,
                            resultJson = objectMapper.writeValueAsString(
                                mapOf("mapping" to merged, "locked" to locked)),
                            durationMs = traceDurationMs)
                    }
                } catch (e: Exception) {
                    log.warn("比对导入映射推导失败: sheet={}, 目标={}.{}: {}",
                        ctx.sheet.sheetName, row.databaseName, row.tableName, e.message)
                    // 推导失败只留锁定项并记目标说明,由人工在审核画布上补线(不做无大模型降级)
                    compareService.notePendingTarget(targetId,
                        "映射推导失败(${(e.message ?: e.javaClass.simpleName).take(200)}),仅保留表格锁定项,请人工审核补线")
                    // 留痕:调过大模型的失败也记一条(仅锁定项 + 错误摘要进 response)
                    if (tracePrompt != null) {
                        recordTrace {
                            CompareAiTrace(jobId = ctx.jobId, targetId = targetId,
                                targetLabel = loc(row.databaseName, row.schemaName, row.tableName),
                                scene = AiScene.COMPARE_MAPPING.name, stage = CompareAiTrace.STAGE_MAPPING,
                                model = aiConfig?.model,
                                requestContent = traceRequestContent(CompareMappingPrompts.SYSTEM_PROMPT, tracePrompt!!),
                                responseContent = traceAnswer
                                    ?: "调用失败: ${(e.message ?: e.javaClass.simpleName).take(200)}",
                                // failed 标记:映射推导失败的 result_json 非空(保留锁定项供前端展示),
                                // 靠它区分「成功但映射少」与「推导失败只剩锁定项」
                                resultJson = traceMerged?.let {
                                    objectMapper.writeValueAsString(
                                        mapOf("mapping" to it, "locked" to locked, "failed" to true))
                                } ?: objectMapper.writeValueAsString(
                                    mapOf("mapping" to emptyMap<String, String>(), "locked" to locked,
                                        "failed" to true)),
                                durationMs = traceDurationMs)
                        }
                    }
                }
                targetMappings[targetId] = mapping
            }
            compareService.completePendingMapping(ctx.jobId, keyField, fields, displayField,
                targetMappings, PendingReason.MAPPING_REVIEW, null)
        } catch (e: Exception) {
            log.error("比对导入后台映射推导异常: jobId={}", ctx.jobId, e)
            runCatching {
                compareService.completePendingMapping(ctx.jobId, ctx.keyField, ctx.fallbackFields,
                    ctx.displayField, emptyMap(), PendingReason.IMPORT_ERROR,
                    "映射推导异常: ${(e.message ?: e.javaClass.simpleName).take(500)}")
            }
        }
    }

    /**
     * 实测单个已建档数据源(从库取解密后的连接信息;单条 20s 超时,与抽样导出同口径走 [ConnectionTester]),
     * 结果回写 conn_status(OK/ERROR);返回 null = 连通
     */
    private fun testCreatedDatasource(dsId: Long): String? {
        val req = try {
            val c = dataSourceService.get(dsId)
            TestConnectionRequest(jdbcUrl = c.jdbcUrl, username = c.username, password = c.password)
        } catch (e: Exception) {
            return "数据源读取失败: ${e.message}".take(2000)
        }
        val err = ConnectionTester.testAll(mapOf("ds" to req), dsTester)["ds"]
        if (err == null) dataSourceRepo.updateConnStatus(dsId, "OK", null)
        else dataSourceRepo.updateConnStatus(dsId, "ERROR", err)
        return err
    }

    /**
     * 批次级数据源实测去重:同一 dsId 只测一次(任务间并行,同 ds 的后来者等先来者的结果);
     * 锁粒度到 dsId,不同数据源的实测互不阻塞
     */
    private class BatchDsTester {
        private val locks = ConcurrentHashMap<Long, Any>()
        private val results = ConcurrentHashMap<Long, String>() // dsId → 实测错误(空串 = 连通)

        /** 取 dsId 的实测结果(null = 连通);未测过则在 dsId 锁内实测并缓存 */
        fun test(dsId: Long, doTest: (Long) -> String?): String? {
            results[dsId]?.let { return it.takeIf { e -> e.isNotEmpty() } }
            val lock = locks.computeIfAbsent(dsId) { Any() }
            return synchronized(lock) {
                val cached = results[dsId]
                if (cached != null) {
                    cached.takeIf { it.isNotEmpty() }
                } else {
                    val err = doTest(dsId)
                    results[dsId] = err ?: ""
                    err
                }
            }
        }
    }

    // ---------- 恢复 / 视图 ----------

    /**
     * 服务重启恢复:残留 BUILDING 批次置 FAILED(ServiceEnv.initDatabase 装配时调用一次);
     * 任务侧残留 MAPPING_RUNNING 由 [CompareService.recoverUnfinished] 转 MAPPING_REVIEW(各清各的,不重复清理)
     */
    fun recoverUnfinished() {
        val n = repo.failBuildingOnStartup("应用重启,导入建任务中断")
        if (n > 0) log.warn("服务重启,{} 个建任务中的比对导入批次已置为失败", n)
    }

    private fun toView(b: CompareImportRepository.BatchRow): CompareImportView {
        val dsReport: List<Map<String, Any?>> = b.dsReport?.let { json ->
            try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                log.warn("比对导入 ds_report JSON 解析失败: id={}: {}", b.id, e.message)
                emptyList()
            }
        } ?: emptyList()
        val jobIds: List<Long> = b.jobIds?.let { json ->
            try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                log.warn("比对导入 job_ids JSON 解析失败: id={}: {}", b.id, e.message)
                emptyList()
            }
        } ?: emptyList()
        return CompareImportView(b.id, b.fileName, b.fileSize, b.status, dsReport, b.taskCount,
            jobIds, b.error, b.createdAt, b.finishedAt)
    }

    // ---------- 内部辅助 ----------

    /** 跨 sheet 汇总去重数据源(类型+地址+端口+库) */
    private fun uniqueRows(parsed: CompareImportExcelParser.ParseResult) =
        parsed.sheets.flatMap { listOf(it.base) + it.targets }.distinctBy { it.dsKey }

    /** 已建档数据源按定位 key 索引(类型/地址解析不出的不参与匹配) */
    private fun existingByLocationKey() = dataSourceRepo.findAll().mapNotNull { ds ->
        DatasourceKeyMatcher.locationKeyOf(ds)?.let { it to ds }
    }.toMap()

    /** 报告行骨架:key/名称(表格「实际系统或模式描述」列)/地址/端口/库名;action/datasourceId/error 由处理阶段填 */
    private fun reportEntry(row: CompareImportExcelParser.ImportRow) = mutableMapOf<String, Any?>(
        "key" to row.dsKey,
        "name" to (row.dsDisplay ?: "${row.host}:${row.port}/${row.databaseName}"),
        "host" to row.host, "port" to row.port, "databaseName" to row.databaseName,
        "action" to null, "datasourceId" to null, "error" to null)

    /**
     * 全部 sheet 被跳过时的错误明细:整 sheet 原因 + 逐行原因。
     * 行级原因必须一并抛出——否则「是」行因缺必需列被丢时,用户只看到「缺少基准行」而找不到真实问题
     */
    private fun describeParseSkips(parsed: CompareImportExcelParser.ParseResult): String {
        val sheetPart = "共跳过 ${parsed.skippedSheets.size} 个 sheet:" +
            parsed.skippedSheets.joinToString("；") { "${it.sheetName} ${it.reason}" }
        if (parsed.skippedRows.isEmpty()) return sheetPart
        return sheetPart + "；另跳过 ${parsed.skippedRows.size} 行:" +
            parsed.skippedRows.joinToString("；") { "${it.sheetName} 第${it.seq}行 ${it.reason}" }
    }

    /** 跳过的 sheet / 行进报告(NOTE 行),供前端展示哪些内容没进任务 */
    private fun appendParseNotes(
        parsed: CompareImportExcelParser.ParseResult, report: ArrayList<MutableMap<String, Any?>>,
    ) {
        for (s in parsed.skippedSheets) {
            report.add(mutableMapOf("key" to null, "name" to "工作表「${s.sheetName}」", "host" to null,
                "port" to null, "databaseName" to null, "action" to "NOTE", "datasourceId" to null,
                "error" to "工作表跳过: ${s.reason}"))
        }
        for (s in parsed.skippedRows) {
            report.add(mutableMapOf("key" to null, "name" to "工作表「${s.sheetName}」第${s.seq}行", "host" to null,
                "port" to null, "databaseName" to null, "action" to "NOTE", "datasourceId" to null,
                "error" to "行跳过: ${s.reason}"))
        }
    }

    /** 定位串(库.模式.表,空段省略),prompt 里标注两侧表用 */
    private fun loc(db: String?, schema: String?, table: String) =
        listOfNotNull(db?.takeIf { it.isNotBlank() }, schema?.takeIf { it.isNotBlank() }, table)
            .joinToString(".")

    /** AI 判定留痕记录:组装/写入任何失败只记 warn,绝不影响映射推导主流程 */
    private fun recordTrace(build: () -> CompareAiTrace) {
        try {
            aiTraceRecorder(build())
        } catch (e: Exception) {
            log.warn("比对导入 AI 判定留痕记录失败(忽略): {}", e.message)
        }
    }

    /** 留痕的请求内容口径:与 AiService 落 ai_usage_log 的 [system]+[user] 拼装一致(落库前由仓储截断) */
    private fun traceRequestContent(systemPrompt: String, userPrompt: String): String =
        "[system]\n$systemPrompt\n\n[user]\n$userPrompt"

    /** 按解析行拼 JDBC URL(类型取表格「数据库类型」列) */
    private fun jdbcUrlOf(row: CompareImportExcelParser.ImportRow): String =
        SampleTableExcelParser.buildJdbcUrl(row.dbType, row.host, row.port, row.databaseName)

    /** 重名自动加「 (2)」「 (3)」后缀(与抽样导出/数据源导入同口径) */
    private fun uniqueName(base: String, usedNames: MutableSet<String>): String {
        var name = base
        var seq = 2
        while (!usedNames.add(name)) {
            name = "$base (${seq++})"
        }
        return name
    }

    companion object {
        /** 建任务线程池的线程序号(线程命名 compare-import-N) */
        private val THREAD_IDX = AtomicInteger()

        /**
         * 任务名 = 上传文件名(去扩展名) + `-` + sheet 名,超长截断 200;
         * sheet 名为空/含非法字符时回退 `<文件名>-sheet<序号>`(序号 1 起)
         */
        internal fun taskName(fileName: String, sheetName: String, sheetIndex: Int): String {
            val base = fileName.substringBeforeLast('.', fileName).trim().ifEmpty { "导入任务" }
            val sheet = sheetName.trim()
            val sheetPart = sheet.takeIf { it.isNotEmpty() && !it.contains(Regex("[\\\\/:*?\"<>|]")) }
                ?: "sheet${sheetIndex + 1}"
            return "$base-$sheetPart".take(200)
        }

        /** 原件文件名清洗:仅去路径分隔符(防 `../` 注入),其余原样保留;空/纯点回退默认名 */
        internal fun sanitizeFileName(fileName: String): String {
            val cleaned = fileName.replace('\\', '_').replace('/', '_').trim()
            return if (cleaned.isEmpty() || cleaned.all { it == '.' }) "compare-import.xlsx" else cleaned
        }
    }
}
