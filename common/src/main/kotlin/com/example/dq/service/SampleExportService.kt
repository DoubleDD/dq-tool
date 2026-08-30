package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.SampleExportDetailView
import com.example.dq.model.SampleExportItemView
import com.example.dq.model.SampleExportTaskView
import com.example.dq.model.TestConnectionRequest
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.SampleExportRepository
import com.example.dq.service.SampleTableExcelParser.SampleTableRow
import com.example.dq.service.SampleTableExcelParser.SkippedRow
import com.example.dq.util.ExcelCells
import com.example.dq.util.SystemOpen
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.Comparator
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * 表格批量导入数据源 + 表数据抽样导出,显式两步流程:上传 Excel(每行一张表)→ 第一步数据源检测
 * (按数据源身份 key 去重建档/复用/修复,连不上的标记错误等人工处理),检测完成任务停在 DETECTED
 * 等用户决策;用户点「继续导出」才走第二步——按 ds_key 重新解析数据源(用户可能已就地编辑或
 * 重新导入修过连接信息),按「数据源 × 水利对象类别」分组,每组产出一个 xlsx(首 sheet 为
 * 「预览目录」,其后每表一个 sheet,抽样 50 行)→ 任务目录打包 zip。
 * 固定 4 线程池执行(暂停中的任务只占住自己的线程,后提交的任务不必排队等它),
 * 任务落 H2(sample_export / sample_export_item),前端任务列表轮询进度;
 * 产物存 数据目录/sample-exports/。解析行(含口令)只活在检测线程执行期内,不落库不常驻内存
 */
class SampleExportService(
    private val repo: SampleExportRepository,
    private val dataSourceRepo: DataSourceRepository,
    private val dataSourceService: DataSourceService,
    private val systemSettingsService: SystemSettingsService,
    private val dialectFactory: DialectFactory,
    private val config: AppConfig,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    /**
     * 任务执行线程池。刻意不用单线程队列:任务可在检查点暂停(长时挂起),
     * 单线程会让暂停中的任务堵住后面所有任务;并发任务间「查重→建档/修复」经 [dsWriteLock] 互斥
     */
    private val executor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "sample-export-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
    }

    /** 并发任务间数据源「查重→建档/修复」的互斥锁:锁内按最新库重判,避免两个任务为同一 dsKey 重复建档 */
    private val dsWriteLock = Any()

    /** 暂停/恢复/取消删除共用的锁:工作线程在任务级检查点挂起,恢复或取消时唤醒 */
    private val pauseLock = Object()

    /** 暂停中的任务被取消删除时抛出;run() 捕获后静默结束(记录与产物已删,不再写失败状态) */
    private class TaskCancelledException : RuntimeException()

    private val exportDir: Path
        get() = config.dataDir.resolve("sample-exports")

    /** 提交任务:同步解析(快)→ 落任务与明细 → 后台跑第一步数据源检测;有效行为 0 直接抛错(400) */
    fun submit(fileName: String, input: InputStream): Long {
        val result = SampleTableExcelParser.parse(input)
        if (result.rows.isEmpty()) {
            throw IllegalArgumentException(
                "Excel 中没有有效数据行(共 ${result.skipped.size} 行缺少必需列或类型无法识别)")
        }
        val taskId = repo.insert(fileName, result.rows.size)
        repo.insertItems(taskId, result.rows)
        // 解析行(含口令)直接内存传给后台线程,口令不落库;检测线程结束即丢弃
        executor.execute { run(taskId, result.rows, result.skipped) }
        log.info("抽样导出任务已提交: id={}, 文件={}, 有效行={}, 跳过行={}", taskId, fileName, result.rows.size, result.skipped.size)
        return taskId
    }

    /** 第一步:数据源检测(去重建档/复用/修复),完成后任务停在 DETECTED 等用户决策是否导出 */
    private fun run(taskId: Long, rows: List<SampleTableRow>, skipped: List<SkippedRow>) {
        repo.markRunning(taskId)
        try {
            importDatasources(taskId, rows, skipped)
            checkPaused(taskId)
            repo.markDetected(taskId)
            log.info("抽样导出数据源检测完成,待用户决策: id={}", taskId)
        } catch (e: TaskCancelledException) {
            // 暂停中的任务被「取消并删除」:记录与产物已删,不再写状态
            log.info("抽样导出任务已取消删除: id={}", taskId)
        } catch (e: Exception) {
            log.error("抽样导出数据源检测失败: id={}", taskId, e)
            repo.fail(taskId, (e.message ?: "数据源检测失败").take(2000))
        }
    }

    /** 用户决策继续导出(仅 DETECTED 状态可发起,竞争失败说明状态已被别人改变):转后台跑第二步 */
    fun export(id: Long) {
        repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        if (repo.markExporting(id) == 0) {
            throw IllegalStateException("任务不在「检测完成待导出」状态,不能继续导出")
        }
        executor.execute { runExport(id) }
    }

    /**
     * 第二步:按已落库明细重新解析数据源(见 [resolveOutcomesForExport])→ 分组抽样导出 → 打包 zip。
     * 用户在检测后可能已就地编辑数据源或重新导入修过连接信息,因此不沿用检测阶段的内存结果
     */
    private fun runExport(taskId: Long) {
        try {
            val items = repo.listItems(taskId)
            val outcomes = resolveOutcomesForExport(items)
            exportTables(taskId, items, outcomes)
            checkPaused(taskId)
            val (zipPath, zipSize) = zipTaskDir(taskId)
            repo.finish(taskId, zipPath, zipSize)
            log.info("抽样导出完成: id={}, zip={}({} bytes)", taskId, zipPath, zipSize)
        } catch (e: TaskCancelledException) {
            // 暂停中的任务被「取消并删除」:记录与产物已删,不再写状态
            log.info("抽样导出任务已取消删除: id={}", taskId)
        } catch (e: Exception) {
            // 任务级失败不清已落盘文件,留着便于排查
            log.error("抽样导出失败: id={}", taskId, e)
            repo.fail(taskId, (e.message ?: "导出失败").take(2000))
        }
    }

    /**
     * 重新导入表格(修复数据源的另一条路径,与就地编辑二选一):仅「检测完成待导出」(DETECTED)
     * 或失败(FAILED)的任务可重新导入。新 Excel 全量替换任务明细并重跑第一步检测
     * (建档/复用/修复逻辑同首次导入,FIXED 路径即「用 Excel 新连接信息更新数据源」),
     * 检测完成后任务再次回到 DETECTED 等用户决策
     */
    fun reimport(id: Long, fileName: String, input: InputStream) {
        val row = repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        if (row.status != "DETECTED" && row.status != "FAILED") {
            throw IllegalStateException("仅「检测完成待导出」或失败的任务可以重新导入")
        }
        val result = SampleTableExcelParser.parse(input)
        if (result.rows.isEmpty()) {
            throw IllegalArgumentException(
                "Excel 中没有有效数据行(共 ${result.skipped.size} 行缺少必需列或类型无法识别)")
        }
        repo.replaceItems(id, fileName, result.rows.size)
        repo.insertItems(id, result.rows)
        // 同步先落「排队中」明细:replaceItems 已把 ds_report 清空,不等线程池调度,明细抽屉立刻可见新数据源列表;
        // 进线程执行时由 onStart 回调翻「校验中」
        writeTestingReport(id, result.rows.distinctBy { it.dsKey }, result.skipped)
        deleteArtifacts(id)
        executor.execute { run(id, result.rows, result.skipped) }
        log.info("抽样导出任务重新导入: id={}, 文件={}, 有效行={}, 跳过行={}",
            id, fileName, result.rows.size, result.skipped.size)
    }

    /**
     * 任务级暂停检查点(数据源导入逐源、导出逐表、打包前各调一次):
     * 状态为 PAUSED 时挂起等待恢复;记录被删除(取消删除)或状态被置 FAILED 时抛 [TaskCancelledException]。
     * 注意粒度是「下一个检查点」:单个数据源实测(最长 20s)与单表查询中途不会被打断
     */
    private fun checkPaused(taskId: Long) {
        synchronized(pauseLock) {
            while (true) {
                when (repo.findById(taskId)?.status) {
                    null, "FAILED" -> throw TaskCancelledException()
                    "PAUSED" -> pauseLock.wait(1000)
                    else -> return
                }
            }
        }
    }

    // ---------- 阶段一:数据源去重导入 ----------

    /** 单个唯一数据源的处理结果;connectable=false 的其下表不进查询直接失败 */
    private data class DsOutcome(
        val dsId: Long?, val name: String, val action: String, val message: String?, val connectable: Boolean)

    /**
     * 按 dsKey 去重后逐数据源建档/复用/修复,返回 dsKey → 处理结果。
     * 测连 4 线程并发、**按完成先后**逐源建档并翻报告行(快源不必等慢源);测连无落库副作用,
     * 建档/修复的落库动作在 [dsWriteLock] 内按最新库重判串行执行(并发任务间不重复建档)
     */
    private fun importDatasources(
        taskId: Long, rows: List<SampleTableRow>, skipped: List<SkippedRow>,
    ): Map<String, DsOutcome> {
        repo.updateStage(taskId, "导入数据源")
        val unique = rows.distinctBy { it.dsKey }
        val existingByKey = dataSourceRepo.findAll().mapNotNull { ds ->
            SampleTableExcelParser.keyOfExisting(ds)?.let { it to ds }
        }.toMap()

        // 先落全部「校验中」再开测(测连期间明细不留空白);报告行按下标定位,处理完原地翻最终结果
        val (report, testingBase) = writeTestingReport(taskId, unique, skipped)
        val indexByKey = unique.mapIndexed { i, row -> row.dsKey to i }.toMap()

        val outcomes = LinkedHashMap<String, DsOutcome>()
        var processed = 0

        /** 序列化当前报告与计数落库(onStart/processAndReport 都会触发,多线程并发写,JSON 快照取写入当时最新) */
        fun persistReport() {
            repo.updateDsCounts(taskId, unique.size,
                outcomes.values.count { it.action == "ADDED" },
                // 已跳过含「按表格改名」(RENAMED):都是复用既有数据源,未重复建档
                outcomes.values.count { it.action == "SKIPPED" || it.action == "RENAMED" },
                outcomes.values.count { it.action == "FIXED" },
                outcomes.values.count { it.action == "ADDED_ERROR" || it.action == "STILL_ERROR" },
                objectMapper.writeValueAsString(report))
        }

        /** 单数据源建档/复用/修复并翻转报告行(测连回调与免测路径共用);单源失败不中断整批 */
        fun processAndReport(row: SampleTableRow, testError: String?) {
            checkPaused(taskId)
            val outcome = try {
                processOneDatasource(row, existingByKey[row.dsKey], testError)
            } catch (e: Exception) {
                log.warn("数据源建档失败: {}: {}", row.displayName, e.message)
                DsOutcome(null, row.displayName, "ADDED_ERROR", (e.message ?: "建档失败").take(2000), false)
            }
            outcomes[row.dsKey] = outcome
            processed++
            val entry = report[testingBase + indexByKey.getValue(row.dsKey)]
            entry["name"] = outcome.name
            entry["datasourceId"] = outcome.dsId
            entry["action"] = outcome.action
            entry["message"] = outcome.message
            repo.updateStage(taskId, "导入数据源 $processed/${unique.size}")
            persistReport()
        }

        // 并发实测:新建与「已存但连接状态 ERROR」的需要测;已存且状态正常直接复用不测
        val needTest = unique.filter { row ->
            val existing = existingByKey[row.dsKey]
            existing == null || existing.connStatus == "ERROR"
        }
        val needTestKeys = needTest.map { it.dsKey }.toSet()
        // 按生命周期回调:onStart=真正进线程执行时翻「校验中」(在池队列里等的不算),onEach=完成翻最终结果;
        // 谁先测完谁先翻,排在前面的慢源/超时源不堵后面的快源
        testConnectionsConcurrently(
            needTest.associate { row ->
                row.dsKey to TestConnectionRequest(
                    jdbcUrl = withMssqlDefaults(row.jdbcUrl), username = row.username, password = row.password)
            },
            onStart = { key ->
                report[testingBase + indexByKey.getValue(key)]["action"] = "TESTING"
                persistReport()
            },
        ) { key, err -> processAndReport(unique[indexByKey.getValue(key)], err) }
        // 免测源(已存在且非 ERROR)不经历测连,这里统一翻行为「已跳过」
        for (row in unique) {
            if (row.dsKey !in needTestKeys) processAndReport(row, null)
        }
        return outcomes
    }

    /**
     * 把全部唯一数据源以「排队中(QUEUING)」连同基础信息(名称/地址/端口/库名)落 ds_report,
     * 前端明细立刻可见全部待测数据源;解析阶段的无效行记 ROW_SKIPPED,说明缺哪列。
     * 返回可变的报告行列表与「排队中」区起始下标;随后由 [testConnectionsConcurrently] 的
     * onStart 回调把真正进线程执行的翻成「校验中(TESTING)」,完成回调翻成最终结果(行序不变)。
     * 首次检测与重新导入共用;必须在并发测连之前调用(测连期间明细不留空白)
     */
    private fun writeTestingReport(
        taskId: Long, unique: List<SampleTableRow>, skipped: List<SkippedRow>,
    ): Pair<ArrayList<MutableMap<String, Any?>>, Int> {
        val report = ArrayList<MutableMap<String, Any?>>()
        for (s in skipped) {
            report.add(mutableMapOf(
                "name" to (s.sysDesc ?: s.tableName ?: "第${s.seq}行"),
                "dbType" to null, // 无效行未解析出类型
                "host" to s.host, "port" to s.port, "databaseName" to s.databaseName,
                "datasourceId" to null,
                "action" to "ROW_SKIPPED", "message" to "第${s.seq}行: ${s.reason}"))
        }
        val testingBase = report.size
        for (row in unique) {
            report.add(mutableMapOf(
                "name" to row.displayName, "dbType" to row.dbType.name,
                "host" to row.host, "port" to row.effectivePort,
                "databaseName" to row.databaseName, "datasourceId" to null,
                "action" to "QUEUING", "message" to null))
        }
        repo.updateDsCounts(taskId, unique.size, 0, 0, 0, 0, objectMapper.writeValueAsString(report))
        return report to testingBase
    }

    /**
     * 并发实测一组连接请求(dsKey → 错误消息,null 值表示连通;未测的 key 无此项)。
     * 检测与导出前复测同一写法:4 线程池 + 单条 20s 超时(超时的单条记「连接超时」算失败,不拖住整批)。
     * 生命周期回调:[onStart] 在任务**真正进线程开始执行**时触发(还在池队列里等的不触发),
     * [onEach] 在单个完成时按完成先后触发——调用方据此把报告行从「排队中」翻「校验中」再翻最终结果。
     * 回调在多线程上触发(各自的数据源行互不相干),全部完成后返回汇总 map;
     * 回调里抛出的异常(如暂停中的任务被取消)会上抛给调用方
     */
    private fun testConnectionsConcurrently(
        toTest: Map<String, TestConnectionRequest>,
        onStart: ((String) -> Unit)? = null,
        onEach: ((String, String?) -> Unit)? = null,
    ): Map<String, String?> {
        val errors = LinkedHashMap<String, String?>()
        if (toTest.isEmpty()) return errors
        val idx = AtomicInteger()
        val pool = Executors.newFixedThreadPool(minOf(4, toTest.size)) { r ->
            Thread(r, "sample-export-dstest-" + idx.incrementAndGet()).apply { isDaemon = true }
        }
        try {
            val completion = ExecutorCompletionService<Pair<String, String?>>(pool)
            val futures = LinkedHashMap<String, Future<Pair<String, String?>>>()
            val pending = LinkedHashMap<String, Long>() // dsKey → 提交时间(nanoTime),超时尚未完成的记超时
            for ((key, req) in toTest) {
                futures[key] = completion.submit(Callable {
                    onStart?.invoke(key)
                    key to testRequestError(req)
                })
                pending[key] = System.nanoTime()
            }
            val timeoutNanos = TimeUnit.SECONDS.toNanos(DS_TEST_TIMEOUT_SECONDS)
            while (pending.isNotEmpty()) {
                val done = completion.poll(200, TimeUnit.MILLISECONDS)
                if (done != null) {
                    val (key, err) = done.get()
                    // 已按超时登记过的任务(被取消)可能随后出现在完成队列,跳过不重复处理
                    if (pending.remove(key) != null) {
                        errors[key] = err
                        onEach?.invoke(key, err)
                    }
                    continue
                }
                // 暂无新完成:把超过单条超时的任务记「连接超时」并取消(其线程由 shutdownNow 回收)
                val now = System.nanoTime()
                for (key in pending.filterValues { now - it > timeoutNanos }.keys.toList()) {
                    pending.remove(key)
                    futures.getValue(key).cancel(true)
                    val msg = "连接超时(${DS_TEST_TIMEOUT_SECONDS} 秒)"
                    errors[key] = msg
                    onEach?.invoke(key, msg)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return errors
    }

    /** 实测单个连接请求;返回 null 表示连通,否则为错误消息 */
    private fun testRequestError(req: TestConnectionRequest): String? = try {
        dataSourceService.testConnection(req)
        null
    } catch (e: Exception) {
        (e.message ?: "连接失败").take(2000)
    }

    /**
     * 单个唯一数据源的建档/复用/修复;testError 为 null 表示已实测连通或未测。
     * 全部进 [dsWriteLock] 按最新库重判(并发任务可能刚建过/改过同一 dsKey);
     * **名称一律以本次导入的表格为准**:已存在的同名(dsKey)数据源与表格名不同则改名(只动 name 列,
     * 连接状态等配置不动;重名自动加后缀),相同才原样复用
     */
    private fun processOneDatasource(
        row: SampleTableRow, snapshotExisting: com.example.dq.model.DataSourceConfig?,
        testError: String?,
    ): DsOutcome {
        synchronized(dsWriteLock) {
            val fresh = dataSourceRepo.findAll()
            val existing = fresh.mapNotNull { ds ->
                SampleTableExcelParser.keyOfExisting(ds)?.let { it to ds }
            }.toMap()[row.dsKey]
            val usedNames = fresh.mapNotNull { it.name }.toMutableSet()
            if (existing == null) {
                // 新数据源:测连成败都建档(连不上也建,密码为空也建,便于用户后续在数据源页修复)
                val name = uniqueName(row.displayName, usedNames)
                val newId = dataSourceService.create(DataSourceRequest(
                    name = name, jdbcUrl = withMssqlDefaults(row.jdbcUrl), username = row.username, password = row.password,
                    rowThreshold = null, sizeThresholdBytes = null, groupName = "表格批量导入"))
                return if (testError == null) {
                    dataSourceRepo.updateConnStatus(newId, "OK", null)
                    DsOutcome(newId, name, "ADDED", null, true)
                } else {
                    dataSourceRepo.updateConnStatus(newId, "ERROR", testError)
                    DsOutcome(newId, name, "ADDED_ERROR", testError, false)
                }
            }
            // 名称以导入表格为准:与已存相同免写库;不同则占用表格名(被别的数据源占用时自动加后缀)
            val newName = when {
                row.displayName == existing.name -> existing.name ?: row.displayName
                !usedNames.contains(row.displayName) -> row.displayName
                else -> uniqueName(row.displayName, usedNames)
            }
            val renamed = newName != existing.name
            if (existing.connStatus != "ERROR") {
                // 已存在且连接正常:复用;名称不同则按表格改名(只动 name 列,连接状态标记不清)
                if (renamed) {
                    dataSourceRepo.updateName(existing.id!!, newName)
                    return DsOutcome(existing.id, newName, "RENAMED", null, true)
                }
                return DsOutcome(existing.id, existing.name ?: "", "SKIPPED", null, true)
            }
            // 已存且连接状态 ERROR:用 Excel 连接信息修复——通则更新连接信息(旧错误保留作历史),不通则刷新错误
            // 注意:除连接信息(jdbcUrl/用户名/密码)与名称外,既有配置一律保留——分组/阈值/库过滤白名单/SSH 隧道
            // 全部回填旧值,批量导入不允许改动历史数据源的这些设置(只负责新增与改名)
            return if (testError == null) {
                dataSourceService.update(existing.id!!, DataSourceRequest(
                    name = newName, jdbcUrl = withMssqlDefaults(row.jdbcUrl), username = row.username,
                    password = row.password, // 空表示沿用旧密码(与编辑对话框「留空不改」同口径)
                    rowThreshold = existing.rowThreshold, sizeThresholdBytes = existing.sizeThresholdBytes,
                    schemaFilter = existing.schemaFilter, groupName = existing.groupName,
                    sshEnabled = existing.sshEnabled, sshHost = existing.sshHost, sshPort = existing.sshPort,
                    sshUsername = existing.sshUsername, sshAuthMethod = existing.sshAuthMethod))
                dataSourceRepo.updateConnStatus(existing.id!!, "OK", existing.connError)
                DsOutcome(existing.id, newName, "FIXED", null, true)
            } else {
                dataSourceRepo.updateConnStatus(existing.id!!, "ERROR", testError)
                DsOutcome(existing.id, newName, "STILL_ERROR", testError, false)
            }
        }
    }

    /** 重名自动加「 (2)」「 (3)」后缀(与 DataSourceTransferService.importOne 同口径) */
    private fun uniqueName(base: String, usedNames: MutableSet<String>): String {
        var name = base
        var seq = 2
        while (!usedNames.add(name)) {
            name = "$base (${seq++})"
        }
        return name
    }

    // ---------- 阶段二:导出前重新解析数据源 + 按组导出表数据 ----------

    /**
     * 导出前按 ds_key 重新解析数据源(不沿用检测阶段的内存结果——用户在检测后可能已就地编辑数据源、
     * 或重新导入 Excel 修过连接信息):匹配不到已存数据源的判失败;conn_status=ERROR 的用
     * 「已存连接信息」并发实测(口令已加密存数据源,不再依赖 Excel 内存行),实测连通的回写 OK
     * (旧错误保留作历史)放行,仍不通的维持 ERROR,导出时其下表记错误跳过
     */
    private fun resolveOutcomesForExport(items: List<SampleExportRepository.ItemRow>): Map<String, DsOutcome> {
        val existingByKey = dataSourceRepo.findAll().mapNotNull { ds ->
            SampleTableExcelParser.keyOfExisting(ds)?.let { it to ds }
        }.toMap()
        val uniqueKeys = items.mapNotNull { it.dsKey }.distinct()
        // ERROR 的先并发实测(用已存连接信息,可能用户已就地修过)
        val testErrors = testConnectionsConcurrently(uniqueKeys.mapNotNull { key ->
            existingByKey[key]?.takeIf { it.connStatus == "ERROR" }?.let { ds ->
                val c = dataSourceService.get(ds.id!!)
                key to TestConnectionRequest(
                    jdbcUrl = c.jdbcUrl, username = c.username, password = c.password,
                    sshEnabled = c.sshEnabled, sshHost = c.sshHost, sshPort = c.sshPort,
                    sshUsername = c.sshUsername, sshAuthMethod = c.sshAuthMethod,
                    sshPassword = c.sshPassword, sshPrivateKey = c.sshPrivateKey, sshPassphrase = c.sshPassphrase)
            }
        }.toMap())
        val outcomes = LinkedHashMap<String, DsOutcome>()
        for (key in uniqueKeys) {
            val ds = existingByKey[key]
            outcomes[key] = when {
                ds == null -> DsOutcome(null, "", "MISSING", "数据源已被删除或连接信息无法识别", false)
                ds.connStatus == "ERROR" -> {
                    val err = testErrors[key]
                    if (err == null) {
                        dataSourceRepo.updateConnStatus(ds.id!!, "OK", ds.connError)
                        DsOutcome(ds.id, ds.name ?: "", "FIXED", null, true)
                    } else {
                        dataSourceRepo.updateConnStatus(ds.id!!, "ERROR", err)
                        DsOutcome(ds.id, ds.name ?: "", "STILL_ERROR", err, false)
                    }
                }
                else -> DsOutcome(ds.id, ds.name ?: "", "READY", null, true)
            }
        }
        return outcomes
    }

    private data class WorkItem(val item: SampleExportRepository.ItemRow, val outcome: DsOutcome)

    /**
     * 按「数据源 × 类别」分组导出。明细来自已落库的 sample_export_item(口令不落表,
     * 连接一律走已存数据源);按 ds_key 重新绑定数据源,匹配不到的明细直接失败(如数据源已被删除)
     */
    private fun exportTables(
        taskId: Long, items: List<SampleExportRepository.ItemRow>, outcomes: Map<String, DsOutcome>,
    ) {
        repo.updateStage(taskId, "导出表数据")
        // 数据源信息在导出前重新取(含检测阶段刚建档/修复的),供方言与文件名使用
        val dsById = dataSourceRepo.findAll().associateBy { it.id }
        val taskDir = exportDir.resolve("task-$taskId")
        var done = 0
        val total = items.size

        // 按 ds_key 重新绑定数据源;匹配不到的明细直接失败
        val pending = ArrayList<WorkItem>()
        for (item in items) {
            val outcome = item.dsKey?.let { outcomes[it] }
            if (outcome?.dsId != null) {
                repo.bindItemDatasource(item.id, outcome.dsId)
                pending.add(WorkItem(item, outcome))
            } else {
                repo.failItem(item.id,
                    (outcome?.message?.let { "数据源无法使用: $it" } ?: "数据行缺少数据源标识").take(2000))
                repo.updateProgress(taskId, ++done, total, "导出 ${item.tableName ?: ""}")
            }
        }

        // 按「数据源 × 类别」分组(类别空归一为「未分类」),每组一个 xlsx;分组保持明细行序
        val groups = pending.groupBy { (it.outcome.dsId!!) to (it.item.category?.takeIf { c -> c.isNotBlank() } ?: "未分类") }
        val usedPaths = mutableSetOf<String>()
        for ((key, groupItems) in groups) {
            val (dsId, category) = key
            done = exportGroup(taskId, taskDir, dsId, category, groupItems, dsById, usedPaths, total, done)
        }
    }

    /** 导出一个分组(一个数据源 × 一个类别)为一个 xlsx;返回处理后的 done 计数 */
    private fun exportGroup(
        taskId: Long, taskDir: Path, dsId: Long, category: String,
        groupItems: List<WorkItem>, dsById: Map<Long?, com.example.dq.model.DataSourceConfig>,
        usedPaths: MutableSet<String>, total: Int, doneInit: Int,
    ): Int {
        var done = doneInit
        val ds = dsById[dsId]
        val dsName = ds?.name ?: "数据源$dsId"
        val dialect = ds?.dbType?.let { dialectFactory.get(it) }
        // 文件落盘 类别目录/数据源名.xlsx;同名数据源同类别冲突追加 -dsId
        val safeCategory = safeFileName(category)
        var baseName = safeFileName(dsName)
        var relPath = "$safeCategory/$baseName.xlsx"
        if (!usedPaths.add(relPath)) {
            baseName = "$baseName-$dsId"
            relPath = "$safeCategory/$baseName.xlsx"
            usedPaths.add(relPath)
        }

        XSSFWorkbook().use { wb ->
            // 第一个 sheet「预览目录」:先只写表头,数据行在组内表全部处理完后回填(XSSF 随机访问)
            // 注意 createRow(0) 对同一行只能调一次(POI 对已存在行会清空重建),表头先建行再逐格写
            val catalog = wb.createSheet("预览目录")
            val catalogHead = catalog.createRow(0)
            CATALOG_HEADERS.forEachIndexed { i, h -> catalogHead.createCell(i).setCellValue(h) }
            val usedSheetNames = mutableSetOf("预览目录")
            val catalogRows = ArrayList<List<Any?>>()

            groupItems.forEachIndexed { index, w ->
                checkPaused(taskId)
                val item = w.item
                val outcome = w.outcome
                // 处理前先亮阶段(当前在导哪张表),完成后再推进度,保证最终 done 能到 total
                repo.updateStage(taskId, "导出 ${item.tableName ?: ""}")
                if (!outcome.connectable || dialect == null) {
                    // 数据源连不上/已被删除:不进查询,直接失败
                    val msg = if (dialect == null) "数据源不存在: $dsId"
                    else "数据源无法连接: ${outcome.message ?: ""}".trim()
                    repo.failItem(w.item.id, msg.take(2000))
                    catalogRows.add(catalogRow(index, w, null, null, msg))
                } else {
                    repo.updateItemStatus(w.item.id, "RUNNING")
                    try {
                        val (header, data) = sampleRows(dsId, item, dialect)
                        // sheet 名规范:「模式名称.表英文名称」(模式为空用库名兜底;同名只出现一次),再经清洗截断去重
                        val sheetBase = listOfNotNull(item.schemaName ?: item.databaseName, item.tableName)
                            .distinct().joinToString(".")
                        val sheetName = ExcelCells.sheetName(sheetBase, usedSheetNames)
                        val sheet = wb.createSheet(sheetName)
                        val headRow = sheet.createRow(0)
                        header.forEachIndexed { i, h -> headRow.createCell(i).setCellValue(h) }
                        data.forEachIndexed { r, values ->
                            val excelRow = sheet.createRow(r + 1)
                            values.forEachIndexed { c, v -> writeCell(excelRow.createCell(c), v) }
                        }
                        repo.finishItem(w.item.id, sheetName, relPath, data.size)
                        catalogRows.add(catalogRow(index, w, sheetName, data.size, null))
                    } catch (e: Exception) {
                        val msg = (e.message ?: "导出失败").take(2000)
                        log.warn("抽样导出表失败: taskId={}, 表={}: {}", taskId, item.tableName, msg)
                        repo.failItem(w.item.id, msg)
                        catalogRows.add(catalogRow(index, w, null, null, msg))
                    }
                }
                done++
                repo.updateProgress(taskId, done, total, "导出 ${item.tableName ?: ""}")
            }

            // 回填「预览目录」数据行(组内全部表处理完才知道成败与行数)
            catalogRows.forEachIndexed { r, values ->
                val excelRow = catalog.createRow(r + 1)
                values.forEachIndexed { c, v -> writeCell(excelRow.createCell(c), v) }
            }
            val file = taskDir.resolve(relPath)
            Files.createDirectories(file.parent)
            Files.newOutputStream(file).use { wb.write(it) }
        }
        log.info("抽样导出分组完成: taskId={}, 类别={}, 数据源={}, 表数={}", taskId, category, dsName, groupItems.size)
        return done
    }

    /** 借连接抽样最多 [SAMPLE_ROWS] 行:表头取 ResultSetMetaData 列名;schema 为空(MySQL 场景 schema=库名)用 databaseName 兜底 */
    private fun sampleRows(dsId: Long, item: SampleExportRepository.ItemRow, dialect: com.example.dq.dialect.DbDialect)
            : Pair<List<String>, List<Array<Any?>>> {
        dataSourceService.getConnection(dsId, item.databaseName).use { conn ->
            conn.createStatement().use { stmt ->
                // 与数据预览同口径的单条 SQL 超时(系统设置可改)
                stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
                stmt.executeQuery(
                    dialect.sampleRowsSql(item.schemaName ?: item.databaseName ?: "", item.tableName ?: "",
                        emptyList(), SAMPLE_ROWS)).use { rs ->
                    val meta = rs.metaData
                    val header = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                    val data = ArrayList<Array<Any?>>()
                    while (rs.next() && data.size < SAMPLE_ROWS) {
                        data.add(Array(meta.columnCount) { rs.getObject(it + 1) })
                    }
                    return header to data
                }
            }
        }
    }

    /** 「预览目录」一行:序号 系统来源名称(留空) 描述 库 模式 表中文名 表英文名 预览sheet名 样本行数 备注 */
    private fun catalogRow(index: Int, w: WorkItem, sheetName: String?, rowCount: Int?, remark: String?): List<Any?> =
        listOf(index + 1, "", w.item.sysDesc ?: "", w.item.databaseName, w.item.schemaName ?: "",
            w.item.tableCnName ?: "", w.item.tableName, sheetName ?: "", rowCount ?: "", remark ?: "")

    /** 单元格:null 写空串,Number 写数值,其余 toString 截断 1000 字符(与数据预览同口径) */
    private fun writeCell(cell: Cell, value: Any?) {
        when (value) {
            null -> cell.setCellValue("")
            is Number -> cell.setCellValue(value.toDouble())
            else -> {
                val s = value.toString()
                cell.setCellValue(if (s.length <= MAX_CELL_CHARS) s else s.substring(0, MAX_CELL_CHARS))
            }
        }
    }

    /** 文件/目录名安全化:Windows 非法字符替换为 _ */
    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "_" }

    // ---------- 阶段三:打包 zip ----------

    /** 把 task-<id> 目录打成 task-<id>.zip(zip 内顶层即类别目录);没有任何产物文件则不打 */
    private fun zipTaskDir(taskId: Long): Pair<String?, Long?> {
        repo.updateStage(taskId, "打包 zip")
        val taskDir = exportDir.resolve("task-$taskId")
        if (!taskDir.exists()) {
            return null to null
        }
        val files = Files.walk(taskDir).use { s ->
            s.asSequence().filter { it.isRegularFile() }.sortedBy { it.toString() }.toList()
        }
        if (files.isEmpty()) {
            return null to null
        }
        val zipFile = exportDir.resolve("task-$taskId.zip")
        ZipOutputStream(Files.newOutputStream(zipFile), Charsets.UTF_8).use { zos ->
            for (f in files) {
                zos.putNextEntry(ZipEntry(taskDir.relativize(f).joinToString("/") { it.toString() }))
                Files.copy(f, zos)
                zos.closeEntry()
            }
        }
        return zipFile.toString() to Files.size(zipFile)
    }

    // ---------- 查询 / 下载 / 打开 / 暂停 / 删除 ----------

    /** 暂停运行中的任务:工作线程在下一个检查点挂起(排队未开始的 PENDING 不可暂停) */
    fun pause(id: Long) {
        repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        if (repo.markPaused(id) == 0) throw IllegalStateException("任务已结束或不在运行中,无法暂停")
    }

    /** 恢复暂停的任务并唤醒工作线程 */
    fun resume(id: Long) {
        repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        if (repo.markResumed(id) == 0) throw IllegalStateException("任务未处于暂停状态")
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    /**
     * 批量删除:完成/失败任务直接删记录与产物;暂停中的任务先置 FAILED(唤醒等待线程退出,即「取消并删除」);
     * 排队中/运行中的任务跳过(不进 deleted,由前端提示)。返回 {deleted, skipped}
     */
    fun delete(ids: List<Long>): Map<String, Any> {
        val deleted = ArrayList<Long>()
        val skipped = ArrayList<Long>()
        for (id in ids.distinct()) {
            val row = repo.findById(id)
            if (row == null || row.status == "PENDING" || row.status == "RUNNING") {
                skipped.add(id)
                continue
            }
            if (row.status == "PAUSED") {
                repo.fail(id, "任务已取消并删除")
                synchronized(pauseLock) { pauseLock.notifyAll() }
            }
            repo.deleteTask(id)
            deleteArtifacts(id)
            deleted.add(id)
            log.info("抽样导出任务已删除: id={}, 文件={}", id, row.fileName)
        }
        return mapOf("deleted" to deleted, "skipped" to skipped)
    }

    /** 删除任务产物:task-<id> 目录与 task-<id>.zip;失败只告警不阻塞记录删除 */
    private fun deleteArtifacts(id: Long) {
        try {
            val dir = exportDir.resolve("task-$id")
            if (dir.exists()) {
                Files.walk(dir).use { s ->
                    s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            Files.deleteIfExists(exportDir.resolve("task-$id.zip"))
        } catch (e: Exception) {
            log.warn("抽样导出任务产物删除失败: task-{}: {}", id, e.message)
        }
    }

    /** 导入模版 xlsx(表头 + 示例行),供前端「下载模版」按钮 */
    fun writeTemplate(out: java.io.OutputStream) = SampleTableExcelParser.writeTemplate(out)

    /** 任务列表(新的在前) */
    fun list(): List<SampleExportTaskView> = repo.list().map { toTaskView(it) }

    /** 任务详情:任务字段 + 数据源导入明细 + 逐表明细(datasourceName 附带) */
    fun detail(id: Long): SampleExportDetailView {
        val row = repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        val dsNames = dataSourceRepo.findAll().associate { it.id to it.name }
        val dsReport: List<Map<String, Any?>> = row.dsReport?.let { json ->
            try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                log.warn("ds_report JSON 解析失败: taskId={}: {}", id, e.message)
                emptyList()
            }
        } ?: emptyList()
        val items = repo.listItems(id).map { item ->
            SampleExportItemView(item.id, item.seq, item.category, item.sysNo, item.sysDesc, item.dbType,
                item.host, item.port, item.username, item.databaseName, item.schemaName, item.tableName,
                item.tableCnName, item.dsKey, item.datasourceId, dsNames[item.datasourceId], item.sheetName,
                item.status, item.rowCount, item.error, item.excelFile)
        }
        return SampleExportDetailView(toTaskView(row), dsReport, items)
    }

    private fun toTaskView(r: SampleExportRepository.TaskRow) = SampleExportTaskView(
        r.id, r.fileName, r.status, r.stage, r.totalItems, r.doneItems,
        r.dsTotal, r.dsAdded, r.dsSkipped, r.dsFixed, r.dsError,
        r.zipPath?.let { Path.of(it).fileName?.toString() }, r.zipSize, r.error,
        r.createdAt, r.startedAt, r.finishedAt)

    /** 下载 zip:任务必须 DONE 且文件还在 */
    fun downloadZip(id: Long): Path {
        val row = repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        if (row.status != "DONE" || row.zipPath == null) {
            throw IllegalStateException("任务未完成或没有产物,不能下载")
        }
        val path = Path.of(row.zipPath)
        if (!path.exists()) {
            throw IllegalStateException("zip 文件已被移动或删除,请重新导出")
        }
        return path
    }

    /** 下载文件名:<原Excel名去扩展名>-抽样导出-<id>.zip */
    fun downloadName(id: Long): String {
        val row = repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        val base = row.fileName.substringBeforeLast('.', row.fileName)
        return "$base-抽样导出-$id.zip"
    }

    /** 调系统文件管理器打开任务产物目录(目录不存在先建) */
    fun openDir(id: Long) {
        repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        val dir = exportDir.resolve("task-$id")
        Files.createDirectories(dir)
        SystemOpen.openDir(dir)
    }

    /** 服务重启恢复:PENDING/RUNNING 任务与明细置 FAILED(ServiceEnv 装配时调用一次) */
    fun recoverUnfinished() {
        val n = repo.failUnfinished()
        repo.failUnfinishedItems()
        if (n > 0) {
            log.warn("服务重启,{} 个未完成的抽样导出任务已置为失败", n)
        }
    }

    companion object {
        /** 任务执行线程池的线程序号(线程命名 sample-export-N) */
        private val THREAD_IDX = AtomicInteger()

        /** 每表抽样行数 */
        private const val SAMPLE_ROWS = 50

        /** 单元格字符串截断长度(与数据预览/AI 抽样同口径) */
        private const val MAX_CELL_CHARS = 1000

        /** 单数据源实测超时(比诊断实测的 40s 短:批量导入场景单个卡住不应拖住整批) */
        private const val DS_TEST_TIMEOUT_SECONDS = 20L

        private val CATALOG_HEADERS = listOf(
            "序号", "系统来源名称", "实际系统或模式描述", "数据库名称", "模式名称",
            "表中文名称", "表英文名称", "预览sheet名", "样本行数", "备注")

        /**
         * SQL Server 批量导入的 Excel 里不带高级连接参数,而 mssql-jdbc 高版本默认 encrypt=true 且严格校验证书,
         * 老版本 SQL Server 直接连不上。这里补上与数据源页「高级」页签一致的默认组合
         * (encrypt=true;trustServerCertificate=true;sslProtocol=TLSv1.1),URL 里已有(任意大小写)的参数不覆盖。
         */
        internal fun withMssqlDefaults(jdbcUrl: String): String {
            if (!jdbcUrl.startsWith("jdbc:sqlserver:", ignoreCase = true)) return jdbcUrl
            var url = jdbcUrl
            for (param in listOf("encrypt=true", "trustServerCertificate=true", "sslProtocol=TLSv1.1")) {
                val key = param.substringBefore('=')
                val exists = url.split(';').drop(1)
                    .any { it.substringBefore('=').trim().equals(key, ignoreCase = true) }
                if (!exists) url = "$url;$param"
            }
            return url
        }
    }
}
