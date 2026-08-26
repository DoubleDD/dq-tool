package com.example.dq.service

import com.example.dq.model.ScanColumnExport
import com.example.dq.model.ScanEventExport
import com.example.dq.model.ScanExportFile
import com.example.dq.model.ScanImportResult
import com.example.dq.model.ScanJobExport
import com.example.dq.model.ScanPreviewDs
import com.example.dq.model.ScanPreviewLocalDs
import com.example.dq.model.ScanTableExport
import com.example.dq.model.ScanTransferPreview
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.ScanRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 扫描记录导出/导入:把扫描任务连同事件/表/分段/字段明细打包成 JSON,在另一台机器的部署导入,
 * 扫描记录列表即可看到导入的历史记录(详情/字段统计照常可查)。
 * 跨实例对齐:数据源按名预检,导入时显式映射(文件数据源名 → 本机数据源 id,0 或缺失=跳过该数据源的全部任务);
 * 不导出任何内部 id,导入时全部重新生成。任务级幂等去重:同数据源 + db(可空等值)+ schema + 创建时间
 * 已存在则跳过,重复导入同一文件不产生重复记录。单任务导入失败计入 failed 并记 warning,不中断整批;
 * 跳过(未映射/映射目标不存在/判重)与失败均逐条记 warnings,导入结果弹窗可见明细原因。
 */
class ScanTransferService(
    private val scanRepo: ScanRepository,
    private val dataSourceRepo: DataSourceRepository,
) {

    private val objectMapper = jacksonObjectMapper()

    /** 导出指定任务(jobIds 为空 = 全部任务)为 JSON 文件;不存在的 id 直接忽略 */
    fun export(jobIds: List<Long>, out: OutputStream) {
        val dsNames = dataSourceRepo.findAll().associate { it.id!! to (it.name ?: "") }
        val jobs = if (jobIds.isEmpty()) {
            scanRepo.listJobs(null, null, null)
        } else {
            jobIds.mapNotNull { scanRepo.findJob(it) }
        }
        val file = ScanExportFile(
            app = APP_MARKER,
            version = VERSION,
            exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            jobs = jobs.map { toExport(it, dsNames) },
        )
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, file)
    }

    /** 导入预检:解析文件并按数据源名聚合 job 数,附本机数据源清单与同名匹配,供前端做数据源映射 */
    fun preview(input: InputStream): ScanTransferPreview {
        val file = parseFile(input)
        val local = dataSourceRepo.findAll()
        val localIdByName = local.mapNotNull { c -> c.name?.takeIf { it.isNotBlank() }?.let { it to c.id!! } }.toMap()
        val dsRows = LinkedHashMap<String, Int>()
        for (job in file.jobs) {
            dsRows[job.datasourceName] = (dsRows[job.datasourceName] ?: 0) + 1
        }
        return ScanTransferPreview(
            totalJobs = file.jobs.size,
            datasources = dsRows.map { (name, count) ->
                ScanPreviewDs(name, count, localIdByName[name])
            },
            localDatasources = local.map { ScanPreviewLocalDs(it.id!!, it.name ?: "") },
        )
    }

    /**
     * 导入导出文件;格式标识不对抛 IllegalArgumentException(Web 层映射 400)。
     * mapping:文件数据源名 → 本机数据源 id;值 0 或缺失表示跳过该数据源的全部任务(计入 skipped);
     * 映射到本机不存在的数据源同样跳过;所有跳过/失败均逐条记 warning,供前端展示明细原因。
     */
    @JvmOverloads
    fun importJson(input: InputStream, mapping: Map<String, Long> = emptyMap()): ScanImportResult {
        val file = parseFile(input)
        val result = ScanImportResult()
        val localIds = dataSourceRepo.findAll().mapNotNull { it.id }.toSet()

        for (job in file.jobs) {
            result.total++
            val dsId = mapping[job.datasourceName]?.takeIf { it > 0 }
            val label = job.datasourceName + "/" +
                    (job.dbName?.takeIf { it.isNotBlank() }?.let { "$it." } ?: "") + job.schemaName
            if (dsId == null) {
                result.skipped++
                result.warnings.add("任务 $label:数据源「${job.datasourceName}」未映射本机数据源,已跳过")
                continue
            }
            if (dsId !in localIds) {
                result.skipped++
                result.warnings.add("任务 $label:映射的本机数据源(id=$dsId)不存在,已跳过")
                continue
            }
            try {
                // 幂等去重:同数据源 + db + schema + 创建时间的任务已存在则跳过
                if (scanRepo.existsJob(dsId, job.dbName, job.schemaName, parseTs(job.createdAt))) {
                    result.skipped++
                    result.warnings.add("任务 $label:本机已存在相同任务(同数据源+库+schema+创建时间),判重跳过")
                    continue
                }
                scanRepo.insertImportedCascade(dsId, job)
                result.imported++
            } catch (e: Exception) {
                result.failed++
                result.warnings.add("任务 $label 导入失败: ${e.message}")
            }
        }
        return result
    }

    // ---------- 导出组装 ----------

    private fun toExport(job: ScanRepository.JobRow, dsNames: Map<Long, String>): ScanJobExport {
        val events = scanRepo.listJobEvents(job.id).map { ScanEventExport(it.status!!, ts(it.at)) }
        val tables = scanRepo.listScanTables(job.id).map { t ->
            val columns = scanRepo.listScanColumns(t.id).map { col ->
                ScanColumnExport(col.columnName ?: "", col.columnType, col.columnComment, col.nullable,
                    col.defaultValue, col.keyLabel, col.totalRows, col.nullCount, col.emptyCount, col.ruleHitCount)
            }
            ScanTableExport(t.tableName, t.status!!, t.sampled, t.sampleRows, t.estRows, t.sizeBytes,
                t.chunkKey, t.comment, t.storageInfo, t.totalChunks, t.doneChunks, t.scannedRows, t.totalRows,
                t.error, ts(t.startedAt), ts(t.finishedAt), scanRepo.listChunksForExport(t.id), columns)
        }
        return ScanJobExport(dsNames[job.datasourceId] ?: "", job.dbName, job.schemaName, job.status,
            job.forceFull, job.autoTag, job.workers, job.genDoc, job.nullRulesJson, job.totalTables,
            job.doneTables, job.error, ts(job.createdAt), ts(job.startedAt), ts(job.finishedAt), events, tables)
    }

    /** 解析并校验导出文件;格式不对抛 IllegalArgumentException(Web 层映射 400) */
    private fun parseFile(input: InputStream): ScanExportFile {
        val file: ScanExportFile = try {
            objectMapper.readValue(input)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的扫描记录导出文件", e)
        }
        if (file.app != APP_MARKER || file.version != VERSION) {
            throw IllegalArgumentException("不是有效的扫描记录导出文件")
        }
        return file
    }

    private fun ts(value: LocalDateTime?): String? = value?.format(TS_FORMAT)

    private fun parseTs(value: String?): LocalDateTime? =
        value?.takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it, TS_FORMAT) }

    private companion object {
        const val APP_MARKER = "dq-tool-scans"
        const val VERSION = 1
        val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}
