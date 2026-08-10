package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.ReportExportView
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.ReportExportRepository
import com.example.dq.util.SystemOpen
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.exists

/**
 * Word 报告异步导出任务:单线程队列执行(导出含多次 LLM 调用与逐库聚合,不并发),
 * 任务落 H2(report_export),前端任务列表轮询进度;产物文件存 数据目录/reports/。
 * 「打开」用系统软件(MS Office → WPS → 默认关联),「打开文件目录」调系统文件管理器。
 */
class WordReportExportService(
    private val reportService: WordReportService,
    private val repo: ReportExportRepository,
    private val dataSourceRepo: DataSourceRepository,
    private val config: AppConfig,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "word-report-export").apply { isDaemon = true }
    }

    private val reportDir: Path
        get() = config.dataDir.resolve("reports")

    /** 提交导出任务,返回任务 id;文件实际生成在后台线程 */
    fun submit(datasourceId: Long, database: String?, schemaNames: List<String>?): Long {
        dataSourceRepo.findById(datasourceId)
            ?: throw IllegalArgumentException("数据源不存在: $datasourceId")
        if (schemaNames != null && schemaNames.isEmpty()) {
            throw IllegalArgumentException("未选择要导出的库")
        }
        val id = repo.insert(datasourceId, database ?: "", schemaNames?.joinToString(","))
        executor.execute { run(id, datasourceId, database, schemaNames) }
        log.info("Word 报告导出任务已提交: id={}, datasourceId={}, db={}, 库={}", id, datasourceId, database, schemaNames)
        return id
    }

    private fun run(id: Long, datasourceId: Long, database: String?, schemaNames: List<String>?) {
        repo.markRunning(id)
        // 文件名用中文(数据源名-数据调研报告-任务id),任务 id 保证同名数据源多次导出不互相覆盖
        val dsName = dataSourceRepo.findById(datasourceId)?.name ?: "数据源$datasourceId"
        val safeName = dsName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val file = reportDir.resolve("$safeName-数据调研报告-$id.docx")
        try {
            Files.createDirectories(reportDir)
            Files.newOutputStream(file).use { out ->
                reportService.export(datasourceId, database, schemaNames, out) { done, total, stage ->
                    repo.updateProgress(id, done, total, stage)
                }
            }
            repo.finish(id, file.toString(), Files.size(file))
            log.info("Word 报告导出完成: id={}, 文件={}({} bytes)", id, file, Files.size(file))
        } catch (e: Exception) {
            log.error("Word 报告导出失败: id={}", id, e)
            Files.deleteIfExists(file)
            repo.fail(id, (e.message ?: "导出失败").take(2000))
        }
    }

    /** 任务列表(新的在前),datasourceId 为空查全部 */
    fun list(datasourceId: Long?): List<ReportExportView> {
        val dsNames = dataSourceRepo.findAll().associate { it.id to it.name }
        return repo.list(datasourceId).map { r ->
            ReportExportView(r.id, r.datasourceId, dsNames[r.datasourceId], r.dbName.ifEmpty { null },
                r.schemaNames?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                r.status, r.stage, r.progressDone, r.progressTotal,
                r.filePath?.let { Path.of(it).fileName?.toString() }, r.fileSize, r.error,
                r.createdAt, r.startedAt, r.finishedAt)
        }
    }

    /** 另存为下载:任务必须 DONE 且文件还在 */
    fun downloadFile(id: Long): Path {
        val row = repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        if (row.status != "DONE" || row.filePath == null) {
            throw IllegalStateException("任务未完成,不能下载")
        }
        val path = Path.of(row.filePath)
        if (!path.exists()) {
            throw IllegalStateException("报告文件已被移动或删除,请重新导出")
        }
        return path
    }

    fun downloadName(id: Long): String {
        val row = repo.findById(id) ?: throw IllegalArgumentException("导出任务不存在: $id")
        val dsName = dataSourceRepo.findById(row.datasourceId)?.name ?: "数据调研报告"
        return "$dsName-数据调研报告.docx"
    }

    /** 调系统软件打开文档(MS Office → WPS → 默认关联) */
    fun openDocument(id: Long) = SystemOpen.openDocument(downloadFile(id))

    /** 打开文件所在目录并选中文件 */
    fun reveal(id: Long) = SystemOpen.reveal(downloadFile(id))

    /** 服务重启恢复:PENDING/RUNNING 任务置 FAILED(ServiceEnv 装配时调用一次) */
    fun recoverUnfinished() {
        val n = repo.failUnfinished()
        if (n > 0) {
            log.warn("服务重启,{} 个未完成的报告导出任务已置为失败", n)
        }
    }
}
