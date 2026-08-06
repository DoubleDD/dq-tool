package com.example.dq.scan

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.ColChunkStat
import com.example.dq.model.ColumnMeta
import com.example.dq.model.NullRule
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanStatus
import com.example.dq.model.ScanTableView
import com.example.dq.repository.ScanRepository
import com.example.dq.service.DataSourceService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

/** 单个分段的统计执行 + 结果落库 + 表/任务完成推进 */
class ChunkRunner(
    private val repo: ScanRepository,
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val config: AppConfig,
    private val executor: ScanExecutor,
) {

    private val objectMapper = jacksonObjectMapper()

    /** scanTableId / jobId 的完成判定需要串行化 */
    private val locks = ConcurrentHashMap<Long, Any>()

    fun run(chunkId: Long) {
        run(chunkId, false)
    }

    private fun run(chunkId: Long, retried: Boolean) {
        val chunk = repo.findChunk(chunkId)
        if (chunk == null || chunk.status != ScanStatus.PENDING) {
            return
        }
        val table = repo.findScanTable(chunk.scanTableId) ?: return
        val job = repo.findJob(table.jobId)
        if (job == null || job.status != ScanStatus.RUNNING) {
            return
        }

        val ds = dataSourceService.get(job.datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)
        repo.markChunkRunning(chunkId)
        try {
            dataSourceService.getConnection(job.datasourceId).use { conn ->
                dialect.useDatabase(conn, dataSourceService.resolveDatabase(job.datasourceId, job.dbName))
                val cols = dialect.listColumns(conn, job.schemaName, table.tableName)
                val chunkKey = if (table.chunkKey == null) null
                else cols.firstOrNull { it.name == table.chunkKey }
                val rules = parseRules(job.nullRulesJson)
                val range = chunk.toRange()

                var total = 0L
                val acc = LinkedHashMap<String, LongArray>() // column -> [null, empty, ruleHit]
                for (batch in partition(cols, COL_BATCH)) {
                    val sql = dialect.buildColumnStatsSql(job.schemaName, table.tableName, batch,
                        range, chunkKey, rules, table.sampled,
                        table.sampleRows ?: config.scan.sampleRows,
                        table.estRows)
                    conn.createStatement().use { stmt ->
                        stmt.queryTimeout = config.scan.statementTimeoutSeconds
                        executor.registerStatement(chunkId, stmt)
                        try {
                            stmt.executeQuery(sql).use { rs ->
                                if (rs.next()) {
                                    total = rs.getLong(1)
                                    parseBatch(rs, batch, dialect.selectorLayout(batch, rules), acc)
                                }
                            }
                        } finally {
                            executor.unregisterStatement(chunkId)
                        }
                    }
                }

                // 执行期间任务被取消:结果作废
                val current = repo.findJob(job.id)
                if (current == null || current.status != ScanStatus.RUNNING) {
                    repo.markChunkStatus(chunkId, ScanStatus.CANCELED, null)
                    return
                }

                val stats = ArrayList<ColChunkStat>()
                acc.forEach { (col, v) -> stats.add(ColChunkStat(col, v[0], v[1], v[2])) }
                repo.markChunkDone(chunkId, total, objectMapper.writeValueAsString(stats))
                afterChunkDone(table.id, total, conn)
            }
        } catch (e: Exception) {
            executor.unregisterStatement(chunkId)
            val jobStatus = repo.findJob(job.id)?.status
            if (jobStatus != ScanStatus.RUNNING) {
                repo.markChunkStatus(chunkId, ScanStatus.CANCELED, null)
                return
            }
            log.warn("分段执行失败 chunkId={} table={}: {}", chunkId, table.tableName, e.message)
            if (!retried && chunk.attempts < MAX_ATTEMPTS) {
                repo.markChunkStatus(chunkId, ScanStatus.PENDING, null)
                run(chunkId, true)
            } else {
                val msg = e.message ?: e.javaClass.simpleName
                repo.markChunkStatus(chunkId, ScanStatus.FAILED, msg)
                failTable(table.id, msg)
            }
        }
    }

    private fun parseBatch(rs: ResultSet, batch: List<ColumnMeta>, layout: List<BooleanArray>,
                           acc: MutableMap<String, LongArray>) {
        var idx = 2 // 第 1 列是 total
        for (i in batch.indices) {
            val col = batch[i]
            val nullCount = rs.getLong(idx++)
            val emptyCount = if (layout[i][0]) rs.getLong(idx++) else 0
            val ruleHit = if (layout[i][1]) rs.getLong(idx++) else 0
            val v = acc.getOrPut(col.name) { LongArray(3) }
            v[0] += nullCount
            v[1] += emptyCount
            v[2] += ruleHit
        }
    }

    /** 分段完成后推进表级进度;全部完成则聚合产出字段级结果(复用当前连接,避免嵌套借连接打满池) */
    private fun afterChunkDone(scanTableId: Long, rowCount: Long, conn: Connection) {
        synchronized(lock(scanTableId)) {
            val done = repo.recordChunkDone(scanTableId, rowCount)
            val table = repo.findScanTable(scanTableId)
            if (table == null || done < table.totalChunks) {
                return
            }
            finalizeTable(table, conn)
        }
    }

    private fun finalizeTable(table: ScanTableView, conn: Connection) {
        try {
            val job = repo.findJob(table.jobId) ?: throw NoSuchElementException()
            val ds = dataSourceService.get(job.datasourceId)
            val dialect = dialectFactory.get(ds.dbType!!)

            // 聚合所有分段的字段计数
            val acc = LinkedHashMap<String, LongArray>()
            var totalRows = 0L
            for (c in repo.listDoneChunks(table.id)) {
                totalRows += c.rowCount
                val stats: List<ColChunkStat> = objectMapper.readValue(
                    c.colStatsJson, object : TypeReference<List<ColChunkStat>>() {})
                for (s in stats) {
                    val v = acc.getOrPut(s.column) { LongArray(3) }
                    v[0] += s.nullCount
                    v[1] += s.emptyCount
                    v[2] += s.ruleHitCount
                }
            }

            // 补列的展示元数据(复用分段执行的连接)
            val metas = LinkedHashMap<String, ColumnMeta>()
            for (c in dialect.listColumns(conn, job.schemaName, table.tableName)) {
                metas[c.name] = c
            }

            repo.deleteScanColumns(table.id)
            val finalTotal = totalRows
            acc.forEach { (col, v) ->
                val m = metas[col]
                repo.insertScanColumn(table.id, ScanColumnView.of(
                    col,
                    m?.displayType ?: "",
                    m?.comment ?: "",
                    m?.nullable,
                    m?.defaultValue,
                    m?.keyLabel() ?: "",
                    finalTotal, v[0], v[1], v[2]))
            }
            repo.finishTable(table.id, ScanStatus.DONE, totalRows, null)
            checkJobCompletion(table.jobId)
        } catch (e: Exception) {
            log.error("表结果聚合失败 scanTableId={}", table.id, e)
            repo.finishTable(table.id, ScanStatus.FAILED, null, "结果聚合失败: " + e.message)
            checkJobCompletion(table.jobId)
        }
    }

    /** 表失败(分段重试耗尽或规划失败时调用) */
    fun failTable(scanTableId: Long, message: String) {
        synchronized(lock(scanTableId)) {
            val table = repo.findScanTable(scanTableId)
            if (table == null || table.status == ScanStatus.DONE || table.status == ScanStatus.FAILED) {
                return
            }
            repo.finishTable(scanTableId, ScanStatus.FAILED, null, message)
            checkJobCompletion(table.jobId)
        }
    }

    private fun checkJobCompletion(jobId: Long) {
        synchronized(lock(-jobId - 1)) {
            val doneTables = repo.incrementJobDoneTables(jobId)
            val job = repo.findJob(jobId)
            if (job == null || doneTables < job.totalTables) {
                return
            }
            val tables = repo.listScanTables(jobId)
            val failed = tables.count { it.status == ScanStatus.FAILED }
            if (failed > 0) {
                repo.finishJob(jobId, ScanStatus.FAILED, "$failed 张表统计失败")
            } else {
                repo.finishJob(jobId, ScanStatus.DONE, null)
            }
        }
    }

    private fun lock(id: Long): Any = locks.computeIfAbsent(id) { Any() }

    private fun parseRules(json: String?): List<NullRule> {
        if (json.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            objectMapper.readValue(json, object : TypeReference<List<NullRule>>() {})
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun <T> partition(list: List<T>, size: Int): List<List<T>> {
        val out = ArrayList<List<T>>()
        var i = 0
        while (i < list.size) {
            out.add(list.subList(i, minOf(i + size, list.size)))
            i += size
        }
        return out
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChunkRunner::class.java)

        /** 每条统计 SQL 包含的最大列数,与 AbstractDialect.MAX_COLS_PER_SQL 对应 */
        private const val COL_BATCH = 80
        private const val MAX_ATTEMPTS = 2
    }
}
