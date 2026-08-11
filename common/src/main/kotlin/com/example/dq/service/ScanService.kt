package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.NullRule
import com.example.dq.model.Range
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanJobEvent
import com.example.dq.model.ScanJobView
import com.example.dq.model.ScanRequest
import com.example.dq.model.ScanStatus
import com.example.dq.model.ScanTableView
import com.example.dq.model.TableStat
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.scan.ChunkRunner
import com.example.dq.scan.ScanExecutor
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory

/** 扫描任务生命周期:创建/规划/进度/取消/断点续扫 */
class ScanService(
    private val repo: ScanRepository,
    private val dsRepo: DataSourceRepository,
    private val schemaStatRepo: SchemaStatRepository,
    private val metaCacheRepo: MetaCacheRepository,
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val config: AppConfig,
    private val executor: ScanExecutor,
    private val chunkRunner: ChunkRunner,
) {

    private val objectMapper = jacksonObjectMapper()

    /** 发起扫描,立即返回 jobId */
    fun createScan(req: ScanRequest): Long {
        val datasourceId = requireNotNull(req.datasourceId) { "数据源 id 不能为空" }
        val schema = requireNotNull(req.schema) { "schema 不能为空" }
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)

        var all: List<TableStat> = emptyList()
        dataSourceService.getConnection(datasourceId).use { conn ->
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, req.database))
            all = dialect.listTables(conn, schema)
        }
        // 顺带刷新库列表缓存:all 是该 schema 的全量表清单,聚合计数与体积即可,零额外查询
        schemaStatRepo.upsert(
            datasourceId, req.database,
            SchemaStatRepository.CachedStat(schema, all.size, sumSize(all))
        )
        // 同步刷新表级结构缓存:all 是该 schema 最新全量表清单,覆盖本地(表清单/注释/估算行数/体积)
        metaCacheRepo.replaceTables(datasourceId, normalizeDb(req.database), schema,
            all.map { MetaCacheRepository.CachedTable(it.name ?: "", it.comment, it.storageInfo, it.estRows, it.sizeBytes) })
        var targets = all
        if (!req.tables.isNullOrEmpty()) {
            val wanted = req.tables.toSet()
            targets = all.filter { it.name != null && it.name in wanted }
            if (targets.isEmpty()) {
                throw IllegalArgumentException("选中的表在库中不存在")
            }
        }
        // 表大小上限:超过上限的表直接不纳入任务;大小未知的表(null)不参与过滤
        val maxTableSizeBytes = req.maxTableSizeBytes
        if (maxTableSizeBytes != null) {
            targets = targets.filter { t -> t.sizeBytes == null || t.sizeBytes <= maxTableSizeBytes }
            if (targets.isEmpty()) {
                throw IllegalArgumentException("没有不超过大小上限的表可扫描")
            }
        }
        if (targets.isEmpty()) {
            throw IllegalArgumentException("该库下没有表")
        }

        val rulesJson = objectMapper.writeValueAsString(req.nullRules ?: emptyList<NullRule>())
        val jobId = repo.insertJob(datasourceId, req.database, schema, req.forceFull, rulesJson, targets.size, req.autoTag)
        val scanTableIds = ArrayList<Long>()
        for (t in targets) {
            scanTableIds.add(
                repo.insertScanTable(
                    jobId, t.name!!, t.estRows, t.sizeBytes,
                    t.comment, t.storageInfo
                )
            )
        }
        repo.markJobRunning(jobId)
        scanTableIds.forEach { id -> executor.submit { planTable(id) } }
        return jobId
    }

    /** 表级规划:取字段、选分段键、判定采样、计算分段、入队 */
    internal fun planTable(scanTableId: Long) {
        val table = repo.findScanTable(scanTableId) ?: return
        val job = repo.findJob(table.jobId)
        if (job == null || job.status != ScanStatus.RUNNING) {
            return
        }
        try {
            val ds = dataSourceService.get(job.datasourceId)
            val dialect = dialectFactory.get(ds.dbType!!)
            val tableName = table.tableName!!
            var ranges: List<Range> = emptyList()
            dataSourceService.getConnection(job.datasourceId).use { conn ->
                dialect.useDatabase(conn, dataSourceService.resolveDatabase(job.datasourceId, job.dbName))
                val cols = dialect.listColumns(conn, job.schemaName, tableName)
                if (cols.isEmpty()) {
                    chunkRunner.failTable(scanTableId, "表不存在或没有字段")
                    return
                }
                // 同步刷新字段/索引结构缓存(扫描已拿到最新结构;失败不影响扫描)
                try {
                    metaCacheRepo.replaceColumns(job.datasourceId, normalizeDb(job.dbName), job.schemaName, tableName,
                        cols.mapIndexed { i, c -> MetaCacheRepository.CachedColumn(
                            i, c.name, c.typeName, c.displayType, c.jdbcType, c.nullable,
                            c.defaultValue, c.comment, c.primaryKey, c.pkSeq, c.uniqueIndexFirst) })
                    val idx = dialect.listIndexes(conn, job.schemaName, tableName)
                    metaCacheRepo.replaceIndexes(job.datasourceId, normalizeDb(job.dbName), job.schemaName, tableName,
                        idx.flatMap { ix -> ix.columns.mapIndexed { i, col ->
                            MetaCacheRepository.CachedIndex(ix.name, ix.unique, i, col) } })
                } catch (e: Exception) {
                    log.debug("扫描时同步结构缓存失败(不影响扫描): {}", e.message)
                }
                val chunkKey = dialect.pickChunkKey(cols)
                val sampled = !job.forceFull && overThreshold(ds, table)
                ranges = if (sampled) {
                    listOf(Range.whole())
                } else {
                    dialect.planChunks(
                        conn, job.schemaName, tableName,
                        chunkKey, table.estRows ?: 0,
                        config.scan.chunksPerTable
                    )
                }
                repo.markTablePlanned(
                    scanTableId, chunkKey?.name,
                    sampled, if (sampled) config.scan.sampleRows else null, ranges.size
                )
            }
            val chunkIds = ArrayList<Long>()
            for (i in ranges.indices) {
                val r = ranges[i]
                chunkIds.add(repo.insertChunk(scanTableId, i, r.start, r.end, r.nullChunk))
            }
            chunkIds.forEach { id -> executor.submit { chunkRunner.run(id) } }
        } catch (e: Exception) {
            log.warn("表规划失败 scanTableId={}: {}", scanTableId, e.message)
            chunkRunner.failTable(scanTableId, "规划失败: " + e.message)
        }
    }

    private fun overThreshold(ds: DataSourceConfig, table: ScanTableView): Boolean {
        val rowThreshold = ds.rowThreshold ?: config.scan.rowThreshold
        val sizeThreshold = ds.sizeThresholdBytes ?: config.scan.sizeThresholdBytes
        if (table.estRows != null && table.estRows > rowThreshold) {
            return true
        }
        return table.sizeBytes != null && table.sizeBytes > sizeThreshold
    }

    /** 表清单的数据+索引总字节;全部为 null(方言不支持)时返回 null */
    private fun sumSize(tables: List<TableStat>): Long? {
        if (tables.none { it.sizeBytes != null }) {
            return null
        }
        return tables.sumOf { it.sizeBytes ?: 0L }
    }

    /** 无库概念方言的 database 归一为空串,与 meta_* 缓存口径一致 */
    private fun normalizeDb(database: String?): String = database ?: ""

    // ---------- 查询 ----------

    fun listJobs(datasourceId: Long?, dbName: String?, schemaName: String?): List<ScanJobView> {
        val dsNames = dsRepo.findAll().associate { it.id to it.name }
        val jobs = repo.listJobs(datasourceId, dbName, schemaName)
        val eventsByJob = repo.listJobEventsByJob(jobs.map { it.id })
        return jobs.map { j ->
            toView(j, dsNames[j.datasourceId], null, eventsByJob.getOrDefault(j.id, emptyList()))
        }
    }

    fun getJob(jobId: Long): ScanJobView {
        val job = repo.findJob(jobId)
            ?: throw IllegalArgumentException("任务不存在: $jobId")
        val dsName = dsRepo.findById(job.datasourceId)?.name
        return toView(job, dsName, repo.listScanTables(jobId), repo.listJobEvents(jobId))
    }

    fun getColumns(jobId: Long, tableName: String): List<ScanColumnView> {
        val table = repo.findScanTableByName(jobId, tableName)
            ?: throw IllegalArgumentException("任务中不存在该表: $tableName")
        return repo.listScanColumns(table.id)
    }

    fun getTable(jobId: Long, tableName: String): ScanTableView =
        repo.findScanTableByName(jobId, tableName)
            ?: throw IllegalArgumentException("任务中不存在该表: $tableName")

    private fun toView(
        j: ScanRepository.JobRow, dsName: String?, tables: List<ScanTableView>?,
        events: List<ScanJobEvent>,
    ): ScanJobView {
        var rules: List<NullRule> = emptyList()
        if (!j.nullRulesJson.isNullOrBlank()) {
            try {
                rules = objectMapper.readValue(j.nullRulesJson, object : TypeReference<List<NullRule>>() {})
            } catch (ignored: Exception) {
            }
        }
        val dbType = dsRepo.findById(j.datasourceId)?.dbType
        return ScanJobView(
            j.id, j.datasourceId, dsName, dbType, j.dbName, j.schemaName, j.status,
            j.forceFull, rules, j.totalTables, j.doneTables, progress(j, tables), j.error,
            j.createdAt, j.startedAt, j.finishedAt, events, tables
        )
    }

    /** 任务总进度:按各表估算行数加权 */
    private fun progress(j: ScanRepository.JobRow, tables: List<ScanTableView>?): Double {
        if (j.status == ScanStatus.DONE) {
            return 100.0
        }
        val ts = tables ?: repo.listScanTables(j.id)
        if (ts.isEmpty()) {
            return 0.0
        }
        var sumWeight = 0.0
        var sumDone = 0.0
        for (t in ts) {
            val estRows = t.estRows
            val weight = if (estRows != null && estRows > 0) estRows.toDouble() else 1.0
            val fraction = when (t.status) {
                ScanStatus.DONE -> 1.0
                // 终态表不再前进,按完成计入避免进度条卡住
                ScanStatus.FAILED, ScanStatus.CANCELED -> 1.0
                else -> if (t.totalChunks > 0) t.doneChunks.toDouble() / t.totalChunks else 0.0
            }
            sumWeight += weight
            sumDone += weight * fraction
        }
        return if (sumWeight > 0) sumDone * 100.0 / sumWeight else 0.0
    }

    // ---------- 取消 / 断点续扫 ----------

    /** 删除任务:进行中(PENDING/RUNNING)的任务需先取消,删除会清掉全部分段与字段统计 */
    fun delete(jobId: Long) {
        val job = repo.findJob(jobId)
            ?: throw IllegalArgumentException("任务不存在: $jobId")
        if (job.status == ScanStatus.PENDING || job.status == ScanStatus.RUNNING) {
            throw IllegalStateException("任务正在进行中,请先取消再删除")
        }
        repo.deleteCascade(jobId)
    }

    fun cancel(jobId: Long) {
        val job = repo.findJob(jobId)
            ?: throw IllegalArgumentException("任务不存在: $jobId")
        if (job.status != ScanStatus.RUNNING && job.status != ScanStatus.PENDING) {
            return
        }
        repo.updateJobStatus(jobId, ScanStatus.CANCELED)
        repo.cancelPendingChunksByJob(jobId)
        for (c in repo.listRunningChunksByJob(jobId)) {
            executor.cancelStatement(c.id)
        }
        for (t in repo.listScanTables(jobId)) {
            if (t.status == ScanStatus.PENDING || t.status == ScanStatus.RUNNING) {
                repo.finishTable(t.id, ScanStatus.CANCELED, null, null)
            }
        }
        repo.finishJob(jobId, ScanStatus.CANCELED, null)
    }

    /** 断点续扫:校验结构未变,未完成的段重新入队 */
    fun resume(jobId: Long) {
        val job = repo.findJob(jobId)
            ?: throw IllegalArgumentException("任务不存在: $jobId")
        if (job.status != ScanStatus.CANCELED && job.status != ScanStatus.INTERRUPTED
            && job.status != ScanStatus.FAILED
        ) {
            throw IllegalStateException("只有已取消/已中断/失败的任务才能续扫,当前状态: " + job.status)
        }
        val ds = dataSourceService.get(job.datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)

        repo.markJobRunning(jobId)
        for (t in repo.listScanTables(jobId)) {
            if (t.status == ScanStatus.DONE) {
                continue
            }
            try {
                dataSourceService.getConnection(job.datasourceId).use { conn ->
                    dialect.useDatabase(conn, dataSourceService.resolveDatabase(job.datasourceId, job.dbName))
                    val cols = dialect.listColumns(conn, job.schemaName, t.tableName!!)
                    if (cols.isEmpty()) {
                        throw IllegalStateException("表 " + t.tableName + " 已不存在,无法续扫,请重新发起扫描")
                    }
                    val chunkKey = dialect.pickChunkKey(cols)
                    val newKey = chunkKey?.name
                    if (t.totalChunks > 0 && newKey != t.chunkKey) {
                        throw IllegalStateException(
                            "表 " + t.tableName + " 的分段键已变化(" + t.chunkKey + " -> " + newKey + "),请重新发起扫描"
                        )
                    }
                }
            } catch (e: IllegalStateException) {
                repo.finishJob(jobId, ScanStatus.FAILED, e.message)
                throw e
            } catch (e: Exception) {
                repo.finishJob(jobId, ScanStatus.FAILED, e.message)
                throw IllegalStateException("续扫校验失败: " + e.message, e)
            }

            if (t.totalChunks == 0) {
                // 规划未完成,重新规划
                repo.deleteChunks(t.id)
                executor.submit { planTable(t.id) }
            } else {
                repo.resetUnfinishedChunks(t.id)
                repo.markTableRunning(t.id)
                for (c in repo.listChunks(t.id)) {
                    if (c.status == ScanStatus.PENDING) {
                        executor.submit { chunkRunner.run(c.id) }
                    }
                }
            }
        }

        // 极端情况:上次中断发生在最后一张表完成之后、任务收尾之前
        val after = repo.listScanTables(jobId)
        if (after.all { it.status == ScanStatus.DONE }) {
            repo.finishJob(jobId, ScanStatus.DONE, null)
        }
    }

    /** 应用重启恢复:运行中的任务标记为中断,可手动续扫 */
    fun recoverAfterRestart() {
        val jobs = repo.markRunningJobsInterrupted()
        val chunks = repo.markRunningChunksCanceled()
        if (jobs > 0) {
            log.info("检测到 {} 个未完成任务已标记为 INTERRUPTED({} 个运行中分段已重置),可通过续扫继续", jobs, chunks)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ScanService::class.java)
    }
}
