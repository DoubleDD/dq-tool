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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * 表格批量导入数据源 + 表数据抽样导出:上传 Excel(每行一张表)→ 按数据源身份 key 去重建档/复用/修复
 * → 按「数据源 × 水利对象类别」分组,每组产出一个 xlsx(首 sheet 为「预览目录」,其后每表一个 sheet,
 * 抽样 50 行)→ 任务目录打包 zip。单线程队列执行,任务落 H2(sample_export / sample_export_item),
 * 前端任务列表轮询进度;产物存 数据目录/sample-exports/。
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

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sample-export").apply { isDaemon = true }
    }

    /** 暂停/恢复/取消删除共用的锁:工作线程在任务级检查点挂起,恢复或取消时唤醒 */
    private val pauseLock = Object()

    /** 暂停中的任务被取消删除时抛出;run() 捕获后静默结束(记录与产物已删,不再写失败状态) */
    private class TaskCancelledException : RuntimeException()

    private val exportDir: Path
        get() = config.dataDir.resolve("sample-exports")

    /** 提交导出任务:同步解析(快)→ 落任务与明细 → 后台执行;有效行为 0 直接抛错(400) */
    fun submit(fileName: String, input: InputStream): Long {
        val result = SampleTableExcelParser.parse(input)
        if (result.rows.isEmpty()) {
            throw IllegalArgumentException(
                "Excel 中没有有效数据行(共 ${result.skipped.size} 行缺少必需列或类型无法识别)")
        }
        val taskId = repo.insert(fileName, result.rows.size)
        repo.insertItems(taskId, result.rows)
        // 解析行(含口令)直接内存传给后台线程,口令不落库
        executor.execute { run(taskId, result.rows, result.skipped) }
        log.info("抽样导出任务已提交: id={}, 文件={}, 有效行={}, 跳过行={}", taskId, fileName, result.rows.size, result.skipped.size)
        return taskId
    }

    private fun run(taskId: Long, rows: List<SampleTableRow>, skipped: List<SkippedRow>) {
        repo.markRunning(taskId)
        try {
            val outcomes = importDatasources(taskId, rows, skipped)
            exportTables(taskId, rows, outcomes)
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
     * 测连并发(4 线程 + 单条超时,写法照 DiagnosticsService.checkDatasources);测连无落库副作用,
     * 建档/修复的落库动作在主线程串行执行,避免超时后后台线程与主线程重复建档
     */
    private fun importDatasources(
        taskId: Long, rows: List<SampleTableRow>, skipped: List<SkippedRow>,
    ): Map<String, DsOutcome> {
        repo.updateStage(taskId, "导入数据源")
        val unique = rows.distinctBy { it.dsKey }
        val existingByKey = dataSourceRepo.findAll().mapNotNull { ds ->
            SampleTableExcelParser.keyOfExisting(ds)?.let { it to ds }
        }.toMap()
        val usedNames = dataSourceRepo.findAll().mapNotNull { it.name }.toMutableSet()

        // 并发实测:新建与「已存但连接状态 ERROR」的需要测;已存且状态正常直接复用不测
        val needTest = unique.filter { row ->
            val existing = existingByKey[row.dsKey]
            existing == null || existing.connStatus == "ERROR"
        }
        val testErrors = HashMap<String, String?>() // dsKey → 错误消息(无此项表示未测)
        if (needTest.isNotEmpty()) {
            val idx = AtomicInteger()
            val pool = Executors.newFixedThreadPool(minOf(4, needTest.size)) { r ->
                Thread(r, "sample-export-dstest-" + idx.incrementAndGet()).apply { isDaemon = true }
            }
            try {
                val futures = needTest.associate { it.dsKey to pool.submit(Callable { testConnectionError(it) }) }
                for (row in needTest) {
                    testErrors[row.dsKey] = try {
                        futures.getValue(row.dsKey).get(DS_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        "连接超时(${DS_TEST_TIMEOUT_SECONDS} 秒)"
                    } catch (e: Exception) {
                        (e.message ?: "连接失败").take(2000)
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }

        val outcomes = LinkedHashMap<String, DsOutcome>()
        val report = ArrayList<Map<String, Any?>>()
        // 解析阶段的无效行也进 ds_report,说明缺哪列
        for (s in skipped) {
            report.add(mapOf(
                "name" to (s.sysDesc ?: s.tableName ?: "第${s.seq}行"),
                "host" to s.host, "port" to s.port, "databaseName" to s.databaseName,
                "datasourceId" to null,
                "action" to "ROW_SKIPPED", "message" to "第${s.seq}行: ${s.reason}"))
        }
        var processed = 0
        for (row in unique) {
            checkPaused(taskId)
            val outcome = try {
                processOneDatasource(row, existingByKey[row.dsKey],
                    if (testErrors.containsKey(row.dsKey)) testErrors[row.dsKey] else null, usedNames)
            } catch (e: Exception) {
                // 单数据源处理异常(如建档落库失败)不中断整批
                log.warn("数据源建档失败: {}: {}", row.displayName, e.message)
                DsOutcome(null, row.displayName, "ADDED_ERROR", (e.message ?: "建档失败").take(2000), false)
            }
            outcomes[row.dsKey] = outcome
            processed++
            report.add(mapOf(
                "name" to outcome.name, "host" to row.host, "port" to row.effectivePort,
                "databaseName" to row.databaseName, "datasourceId" to outcome.dsId,
                "action" to outcome.action, "message" to outcome.message))
            repo.updateStage(taskId, "导入数据源 $processed/${unique.size}")
            repo.updateDsCounts(taskId, unique.size,
                outcomes.values.count { it.action == "ADDED" },
                outcomes.values.count { it.action == "SKIPPED" },
                outcomes.values.count { it.action == "FIXED" },
                outcomes.values.count { it.action == "ADDED_ERROR" || it.action == "STILL_ERROR" },
                objectMapper.writeValueAsString(report))
        }
        return outcomes
    }

    /** 实测单个唯一数据源;返回 null 表示连通,否则为错误消息 */
    private fun testConnectionError(row: SampleTableRow): String? = try {
        dataSourceService.testConnection(TestConnectionRequest(
            jdbcUrl = withMssqlDefaults(row.jdbcUrl), username = row.username, password = row.password))
        null
    } catch (e: Exception) {
        (e.message ?: "连接失败").take(2000)
    }

    /** 单个唯一数据源的建档/复用/修复(主线程串行);testError 为 null 表示已实测连通或未测 */
    private fun processOneDatasource(
        row: SampleTableRow, existing: com.example.dq.model.DataSourceConfig?,
        testError: String?, usedNames: MutableSet<String>,
    ): DsOutcome {
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
        if (existing.connStatus != "ERROR") {
            return DsOutcome(existing.id, existing.name ?: "", "SKIPPED", null, true)
        }
        // 已存且连接状态 ERROR:用 Excel 连接信息修复——通则更新连接信息(旧错误保留作历史),不通则刷新错误
        // 注意:除连接信息(jdbcUrl/用户名/密码)外,既有配置一律保留——分组/阈值/库过滤白名单/SSH 隧道
        // 全部回填旧值,批量导入不允许改动历史数据源的这些设置(只负责新增)
        return if (testError == null) {
            dataSourceService.update(existing.id!!, DataSourceRequest(
                name = existing.name, jdbcUrl = withMssqlDefaults(row.jdbcUrl), username = row.username,
                password = row.password, // 空表示沿用旧密码(与编辑对话框「留空不改」同口径)
                rowThreshold = existing.rowThreshold, sizeThresholdBytes = existing.sizeThresholdBytes,
                schemaFilter = existing.schemaFilter, groupName = existing.groupName,
                sshEnabled = existing.sshEnabled, sshHost = existing.sshHost, sshPort = existing.sshPort,
                sshUsername = existing.sshUsername, sshAuthMethod = existing.sshAuthMethod))
            dataSourceRepo.updateConnStatus(existing.id!!, "OK", existing.connError)
            DsOutcome(existing.id, existing.name ?: "", "FIXED", null, true)
        } else {
            dataSourceRepo.updateConnStatus(existing.id!!, "ERROR", testError)
            DsOutcome(existing.id, existing.name ?: "", "STILL_ERROR", testError, false)
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

    // ---------- 阶段二:按组导出表数据 ----------

    private data class WorkItem(val row: SampleTableRow, val itemId: Long?, val outcome: DsOutcome?)

    private fun exportTables(taskId: Long, rows: List<SampleTableRow>, outcomes: Map<String, DsOutcome>) {
        repo.updateStage(taskId, "导出表数据")
        val itemsBySeq = repo.listItems(taskId).associateBy { it.seq }
        // 数据源信息在阶段一之后重新取(含刚建档的),供方言与文件名使用
        val dsById = dataSourceRepo.findAll().associateBy { it.id }
        val taskDir = exportDir.resolve("task-$taskId")
        var done = 0
        val total = rows.size

        // 绑定数据源;无映射的明细直接失败(正常流程不会发生:无效行在解析期已剔除)
        val pending = ArrayList<WorkItem>()
        for (row in rows) {
            val item = itemsBySeq[row.seq]
            val outcome = outcomes[row.dsKey]
            if (item != null && outcome?.dsId != null) {
                repo.bindItemDatasource(item.id, outcome.dsId)
                pending.add(WorkItem(row, item.id, outcome))
            } else {
                if (item != null) {
                    repo.failItem(item.id,
                        (outcome?.message?.let { "数据源建档失败: $it" } ?: "数据行缺少必需列").take(2000))
                }
                repo.updateProgress(taskId, ++done, total, "导出 ${row.tableName}")
            }
        }

        // 按「数据源 × 类别」分组(类别空归一为「未分类」),每组一个 xlsx;分组保持 Excel 行序
        val groups = pending.groupBy { (it.outcome!!.dsId!!) to (it.row.category?.takeIf { c -> c.isNotBlank() } ?: "未分类") }
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
                val row = w.row
                val outcome = w.outcome!!
                // 处理前先亮阶段(当前在导哪张表),完成后再推进度,保证最终 done 能到 total
                repo.updateStage(taskId, "导出 ${row.tableName}")
                if (!outcome.connectable || dialect == null) {
                    // 数据源建档即失败/修复仍失败:不进查询,直接失败
                    val msg = if (dialect == null) "数据源不存在: $dsId"
                    else "数据源无法连接: ${outcome.message ?: ""}".trim()
                    repo.failItem(w.itemId!!, msg.take(2000))
                    catalogRows.add(catalogRow(index, w, null, null, msg))
                } else {
                    repo.updateItemStatus(w.itemId!!, "RUNNING")
                    try {
                        val (header, data) = sampleRows(dsId, row, dialect)
                        // sheet 名规范:「模式名称.表英文名称」(模式为空用库名兜底;同名只出现一次),再经清洗截断去重
                        val sheetBase = listOfNotNull(row.schemaName ?: row.databaseName, row.tableName)
                            .distinct().joinToString(".")
                        val sheetName = ExcelCells.sheetName(sheetBase, usedSheetNames)
                        val sheet = wb.createSheet(sheetName)
                        val headRow = sheet.createRow(0)
                        header.forEachIndexed { i, h -> headRow.createCell(i).setCellValue(h) }
                        data.forEachIndexed { r, values ->
                            val excelRow = sheet.createRow(r + 1)
                            values.forEachIndexed { c, v -> writeCell(excelRow.createCell(c), v) }
                        }
                        repo.finishItem(w.itemId, sheetName, relPath, data.size)
                        catalogRows.add(catalogRow(index, w, sheetName, data.size, null))
                    } catch (e: Exception) {
                        val msg = (e.message ?: "导出失败").take(2000)
                        log.warn("抽样导出表失败: taskId={}, 表={}: {}", taskId, row.tableName, msg)
                        repo.failItem(w.itemId, msg)
                        catalogRows.add(catalogRow(index, w, null, null, msg))
                    }
                }
                done++
                repo.updateProgress(taskId, done, total, "导出 ${row.tableName}")
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
    private fun sampleRows(dsId: Long, row: SampleTableRow, dialect: com.example.dq.dialect.DbDialect)
            : Pair<List<String>, List<Array<Any?>>> {
        dataSourceService.getConnection(dsId, row.databaseName).use { conn ->
            conn.createStatement().use { stmt ->
                // 与数据预览同口径的单条 SQL 超时(系统设置可改)
                stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
                stmt.executeQuery(
                    dialect.sampleRowsSql(row.schemaName ?: row.databaseName, row.tableName,
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
        listOf(index + 1, "", w.row.sysDesc ?: "", w.row.databaseName, w.row.schemaName ?: "",
            w.row.tableCnName ?: "", w.row.tableName, sheetName ?: "", rowCount ?: "", remark ?: "")

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
