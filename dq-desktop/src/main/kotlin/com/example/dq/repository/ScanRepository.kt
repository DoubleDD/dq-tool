package com.example.dq.repository

import com.example.dq.model.ChunkRecord
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanJobEvent
import com.example.dq.model.ScanStatus
import com.example.dq.model.ScanTableView
import java.sql.ResultSet
import java.time.LocalDateTime

class ScanRepository(private val jdbc: Jdbc) {

    // ---------- scan_job ----------

    data class JobRow(val id: Long, val datasourceId: Long, val dbName: String?, val schemaName: String,
                      val status: ScanStatus, val forceFull: Boolean, val nullRulesJson: String?,
                      val totalTables: Int, val doneTables: Int, val error: String?,
                      val createdAt: LocalDateTime?, val startedAt: LocalDateTime?, val finishedAt: LocalDateTime?)

    private val jobMapper: (ResultSet) -> JobRow = { rs ->
        JobRow(
            rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("db_name"), rs.getString("schema_name"),
            ScanStatus.valueOf(rs.getString("status")), rs.getBoolean("force_full"),
            rs.getString("null_rules"), rs.getInt("total_tables"), rs.getInt("done_tables"),
            rs.getString("error"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? {
        val t = rs.getTimestamp(col)
        return t?.toLocalDateTime()
    }

    fun insertJob(datasourceId: Long, dbName: String?, schema: String, forceFull: Boolean, nullRulesJson: String?, totalTables: Int): Long {
        val jobId = jdbc.insert(
            "INSERT INTO scan_job(datasource_id, db_name, schema_name, status, force_full, null_rules, total_tables) " +
                    "VALUES (?,?,?,'PENDING',?,?,?)",
            datasourceId, dbName, schema, forceFull, nullRulesJson, totalTables)
        insertJobEvent(jobId, ScanStatus.PENDING)
        return jobId
    }

    fun findJob(jobId: Long): JobRow? =
        jdbc.queryOne("SELECT * FROM scan_job WHERE id=?", jobId, mapper = jobMapper)

    fun listJobs(datasourceId: Long?, dbName: String?, schema: String?): List<JobRow> {
        val sql = StringBuilder("SELECT * FROM scan_job WHERE 1=1")
        val args = ArrayList<Any?>()
        if (datasourceId != null) {
            sql.append(" AND datasource_id=?")
            args.add(datasourceId)
        }
        if (!dbName.isNullOrBlank()) {
            sql.append(" AND db_name=?")
            args.add(dbName)
        }
        if (!schema.isNullOrBlank()) {
            sql.append(" AND schema_name=?")
            args.add(schema)
        }
        sql.append(" ORDER BY id DESC")
        return jdbc.query(sql.toString(), *args.toTypedArray(), mapper = jobMapper)
    }

    /**
     * 每个 schema 最近一次扫描任务(按 id 最大),供库列表页展示"最近扫描"。
     * dbName 为空时只匹配 db_name 为 NULL 的任务(与 ScanRequest.database 的落库口径一致)。
     */
    fun latestJobsBySchema(datasourceId: Long, dbName: String?): Map<String, JobRow> {
        val dbCond = if (!dbName.isNullOrBlank()) "db_name=?" else "db_name IS NULL"
        val args = ArrayList<Any?>()
        args.add(datasourceId)
        if (!dbName.isNullOrBlank()) args.add(dbName)
        val sql = "SELECT * FROM scan_job WHERE datasource_id=? AND " + dbCond +
                " AND id IN (SELECT MAX(id) FROM scan_job WHERE datasource_id=? AND " + dbCond +
                " GROUP BY schema_name)"
        args.add(datasourceId)
        if (!dbName.isNullOrBlank()) args.add(dbName)
        val latest = HashMap<String, JobRow>()
        for (row in jdbc.query(sql, *args.toTypedArray(), mapper = jobMapper)) {
            latest[row.schemaName] = row
        }
        return latest
    }

    /**
     * 每张表最近一次 DONE 扫描的信息:任务 id、该表的完成时间、统计行数、扫描时记录的大小快照、是否采样。
     * totalRows 仅对非采样表是 COUNT(*) 精确值;采样表只是采样行数,展示层不应据此替换估算值。
     */
    data class LatestScan(val jobId: Long, val finishedAt: LocalDateTime?, val totalRows: Long?,
                          val sizeBytes: Long?, val sampled: Boolean)

    /** 运行中任务里每张未完成表的进度:任务 id、表级状态(PENDING/RUNNING)、分段进度 */
    data class RunningScan(val jobId: Long, val status: String, val doneChunks: Int, val totalChunks: Int)

    /**
     * 运行中任务里每张未完成表的分段进度,供表列表页"扫描中显示进度"。
     * 只取 j.status='RUNNING' 的任务内 t.status 为 PENDING/RUNNING 的表;dbName 口径与 latestJobsBySchema 一致。
     */
    fun runningScansByTable(datasourceId: Long, dbName: String?, schemaName: String): Map<String, RunningScan> {
        val dbCond = if (!dbName.isNullOrBlank()) "j.db_name=?" else "j.db_name IS NULL"
        val args = ArrayList<Any?>()
        args.add(datasourceId)
        if (!dbName.isNullOrBlank()) args.add(dbName)
        args.add(schemaName)
        val sql = "SELECT t.table_name, t.job_id, t.status, t.done_chunks, t.total_chunks FROM scan_table t " +
                "JOIN scan_job j ON t.job_id=j.id " +
                "WHERE j.datasource_id=? AND " + dbCond + " AND j.schema_name=? AND j.status='RUNNING' " +
                "AND t.status IN ('PENDING','RUNNING') ORDER BY t.job_id"
        val result = HashMap<String, RunningScan>()
        jdbc.query(sql, *args.toTypedArray()) { rs ->
            result[rs.getString(1)] =
                RunningScan(rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5))
        }
        return result
    }

    /**
     * 每张表最近一次扫描完成(表级 DONE)的任务 id、完成时间及该表统计行数/大小快照,
     * 供表列表页"点击表名直达最新结果"、"最近扫描时间"列,以及用扫描准确值覆盖元数据估算的行数/大小。
     * 同一表可能出现在多个任务里,按 job_id 升序遍历、后者覆盖前者,最终留下 job_id 最大者;
     * dbName 为空的口径与 latestJobsBySchema 一致。
     */
    fun latestDoneJobsByTable(datasourceId: Long, dbName: String?, schemaName: String): Map<String, LatestScan> {
        val dbCond = if (!dbName.isNullOrBlank()) "j.db_name=?" else "j.db_name IS NULL"
        val args = ArrayList<Any?>()
        args.add(datasourceId)
        if (!dbName.isNullOrBlank()) args.add(dbName)
        args.add(schemaName)
        val sql = "SELECT t.table_name, t.job_id, t.finished_at, t.total_rows, t.size_bytes, t.sampled FROM scan_table t " +
                "JOIN scan_job j ON t.job_id=j.id " +
                "WHERE j.datasource_id=? AND " + dbCond + " AND j.schema_name=? AND t.status='DONE' " +
                "ORDER BY t.job_id"
        val result = HashMap<String, LatestScan>()
        jdbc.query(sql, *args.toTypedArray()) { rs ->
            val totalRows = rs.getLong("total_rows")
            val totalNull = rs.wasNull() // wasNull 只反映最近一次读取,需即时判断
            val sizeBytes = rs.getLong("size_bytes")
            val sizeNull = rs.wasNull()
            result[rs.getString(1)] = LatestScan(rs.getLong(2), ts(rs, "finished_at"),
                if (totalNull) null else totalRows, if (sizeNull) null else sizeBytes, rs.getBoolean("sampled"))
        }
        return result
    }

    fun updateJobStatus(jobId: Long, status: ScanStatus) {
        jdbc.update("UPDATE scan_job SET status=? WHERE id=?", status.name, jobId)
    }

    fun markJobRunning(jobId: Long) {
        jdbc.update("UPDATE scan_job SET status='RUNNING', started_at=CURRENT_TIMESTAMP, finished_at=NULL, error=NULL WHERE id=?", jobId)
        insertJobEvent(jobId, ScanStatus.RUNNING)
    }

    fun finishJob(jobId: Long, status: ScanStatus, error: String?) {
        jdbc.update("UPDATE scan_job SET status=?, finished_at=CURRENT_TIMESTAMP, error=? WHERE id=?",
            status.name, error, jobId)
        insertJobEvent(jobId, status)
    }

    /** done_tables + 1,返回自增后的值 */
    fun incrementJobDoneTables(jobId: Long): Int {
        jdbc.update("UPDATE scan_job SET done_tables = done_tables + 1 WHERE id=?", jobId)
        return jdbc.queryOne("SELECT done_tables FROM scan_job WHERE id=?", jobId) { rs -> rs.getInt(1) }!!
    }

    fun markRunningJobsInterrupted(): Int {
        // 先取出受影响任务,逐条补 INTERRUPTED 事件(重启恢复场景,量不大)
        val ids = jdbc.query("SELECT id FROM scan_job WHERE status IN ('PENDING','RUNNING')") { rs -> rs.getLong(1) }
        if (ids.isEmpty()) {
            return 0
        }
        val n = jdbc.update("UPDATE scan_job SET status='INTERRUPTED' WHERE status IN ('PENDING','RUNNING')")
        ids.forEach { insertJobEvent(it, ScanStatus.INTERRUPTED) }
        return n
    }

    // ---------- scan_job_event ----------

    private val eventMapper: (ResultSet) -> ScanJobEvent = { rs ->
        ScanJobEvent(ScanStatus.valueOf(rs.getString("status")), ts(rs, "created_at"))
    }

    fun insertJobEvent(jobId: Long, status: ScanStatus) {
        jdbc.update("INSERT INTO scan_job_event(job_id, status) VALUES (?,?)", jobId, status.name)
    }

    fun listJobEvents(jobId: Long): List<ScanJobEvent> =
        jdbc.query("SELECT status, created_at FROM scan_job_event WHERE job_id=? ORDER BY id",
            jobId, mapper = eventMapper)

    /** 批量取多个任务的事件并按任务分组,供列表接口避免逐任务查询 */
    fun listJobEventsByJob(jobIds: List<Long>): Map<Long, List<ScanJobEvent>> {
        if (jobIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = jobIds.joinToString(", ") { "?" }
        val result = HashMap<Long, MutableList<ScanJobEvent>>()
        jdbc.query("SELECT job_id, status, created_at FROM scan_job_event WHERE job_id IN (" + placeholders +
                ") ORDER BY id", *jobIds.toTypedArray()) { rs ->
            result.getOrPut(rs.getLong("job_id")) { ArrayList() }
                .add(ScanJobEvent(ScanStatus.valueOf(rs.getString("status")), ts(rs, "created_at")))
        }
        return result
    }

    /** 删除任务及其全部明细(表/分段/字段结果),由 service 层在事务中调用 */
    fun deleteJob(jobId: Long) {
        jdbc.update("DELETE FROM scan_chunk WHERE scan_table_id IN (SELECT id FROM scan_table WHERE job_id=?)", jobId)
        jdbc.update("DELETE FROM scan_column WHERE scan_table_id IN (SELECT id FROM scan_table WHERE job_id=?)", jobId)
        jdbc.update("DELETE FROM scan_table WHERE job_id=?", jobId)
        jdbc.update("DELETE FROM scan_job_event WHERE job_id=?", jobId)
        jdbc.update("DELETE FROM scan_job WHERE id=?", jobId)
    }

    /**
     * 级联删除(替代 Java 版 ScanService.delete 上的 Spring @Transactional):
     * 在同一事务/同一连接上顺序执行 deleteJob 的全部删除语句,任一失败整体回滚。
     */
    fun deleteCascade(jobId: Long) {
        jdbc.tx { conn ->
            listOf(
                "DELETE FROM scan_chunk WHERE scan_table_id IN (SELECT id FROM scan_table WHERE job_id=?)",
                "DELETE FROM scan_column WHERE scan_table_id IN (SELECT id FROM scan_table WHERE job_id=?)",
                "DELETE FROM scan_table WHERE job_id=?",
                "DELETE FROM scan_job_event WHERE job_id=?",
                "DELETE FROM scan_job WHERE id=?"
            ).forEach { sql ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, jobId)
                    ps.executeUpdate()
                }
            }
        }
    }

    // ---------- scan_table ----------

    private val tableMapper: (ResultSet) -> ScanTableView = { rs ->
        val est = rs.getLong("est_rows")
        val estRows = if (rs.wasNull()) null else est
        val size = rs.getLong("size_bytes")
        val sizeBytes = if (rs.wasNull()) null else size
        val sr = rs.getLong("sample_rows")
        val sampleRows = if (rs.wasNull()) null else sr
        val tr = rs.getLong("total_rows")
        val totalRows = if (rs.wasNull()) null else tr
        ScanTableView(
            rs.getLong("id"), rs.getLong("job_id"), rs.getString("table_name"),
            ScanStatus.valueOf(rs.getString("status")), rs.getBoolean("sampled"),
            sampleRows, estRows, sizeBytes, rs.getString("comment"), rs.getString("storage_info"),
            rs.getString("chunk_key"),
            rs.getInt("total_chunks"), rs.getInt("done_chunks"), rs.getLong("scanned_rows"),
            totalRows, rs.getString("error"), ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    fun insertScanTable(jobId: Long, tableName: String, estRows: Long?, sizeBytes: Long?,
                        comment: String?, storageInfo: String?): Long =
        jdbc.insert(
            "INSERT INTO scan_table(job_id, table_name, status, est_rows, size_bytes, comment, storage_info) " +
                    "VALUES (?,?,'PENDING',?,?,?,?)",
            jobId, tableName, estRows, sizeBytes, comment, storageInfo)

    fun findScanTable(scanTableId: Long): ScanTableView? =
        jdbc.queryOne("SELECT * FROM scan_table WHERE id=?", scanTableId, mapper = tableMapper)

    fun findScanTableByName(jobId: Long, tableName: String): ScanTableView? =
        jdbc.queryOne("SELECT * FROM scan_table WHERE job_id=? AND table_name=?", jobId, tableName,
            mapper = tableMapper)

    fun listScanTables(jobId: Long): List<ScanTableView> =
        jdbc.query("SELECT * FROM scan_table WHERE job_id=? ORDER BY id", jobId, mapper = tableMapper)

    /** 规划完成:写入分段键/采样标记/段数,置为 RUNNING */
    fun markTablePlanned(scanTableId: Long, chunkKey: String?, sampled: Boolean, sampleRows: Long?, totalChunks: Int) {
        jdbc.update("UPDATE scan_table SET status='RUNNING', chunk_key=?, sampled=?, sample_rows=?, total_chunks=?, " +
                "started_at=CURRENT_TIMESTAMP WHERE id=?",
            chunkKey, sampled, sampleRows, totalChunks, scanTableId)
    }

    fun markTableRunning(scanTableId: Long) {
        jdbc.update("UPDATE scan_table SET status='RUNNING', started_at=CURRENT_TIMESTAMP, finished_at=NULL, error=NULL WHERE id=?",
            scanTableId)
    }

    fun finishTable(scanTableId: Long, status: ScanStatus, totalRows: Long?, error: String?) {
        if (status == ScanStatus.DONE) {
            jdbc.update("UPDATE scan_table SET status='DONE', total_rows=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
                totalRows, scanTableId)
        } else {
            jdbc.update("UPDATE scan_table SET status=?, error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
                status.name, error, scanTableId)
        }
    }

    /** done_chunks+1 且 scanned_rows 累加,返回最新 done_chunks */
    fun recordChunkDone(scanTableId: Long, rowCount: Long): Int {
        jdbc.update("UPDATE scan_table SET done_chunks = done_chunks + 1, scanned_rows = scanned_rows + ? WHERE id=?",
            rowCount, scanTableId)
        return jdbc.queryOne("SELECT done_chunks FROM scan_table WHERE id=?", scanTableId) { rs -> rs.getInt(1) } ?: 0
    }

    // ---------- scan_chunk ----------

    private val chunkMapper: (ResultSet) -> ChunkRecord = { rs ->
        ChunkRecord(
            rs.getLong("id"), rs.getLong("scan_table_id"), rs.getInt("seq"),
            rs.getString("range_start"), rs.getString("range_end"), rs.getBoolean("null_chunk"),
            ScanStatus.valueOf(rs.getString("status")), rs.getLong("row_count"),
            rs.getString("col_stats"), rs.getInt("attempts"))
    }

    fun insertChunk(scanTableId: Long, seq: Int, rangeStart: String?, rangeEnd: String?, nullChunk: Boolean): Long =
        jdbc.insert(
            "INSERT INTO scan_chunk(scan_table_id, seq, range_start, range_end, null_chunk, status) " +
                    "VALUES (?,?,?,?,?,'PENDING')",
            scanTableId, seq, rangeStart, rangeEnd, nullChunk)

    fun findChunk(chunkId: Long): ChunkRecord? =
        jdbc.queryOne("SELECT * FROM scan_chunk WHERE id=?", chunkId, mapper = chunkMapper)

    fun listChunks(scanTableId: Long): List<ChunkRecord> =
        jdbc.query("SELECT * FROM scan_chunk WHERE scan_table_id=? ORDER BY seq", scanTableId, mapper = chunkMapper)

    fun listDoneChunks(scanTableId: Long): List<ChunkRecord> =
        jdbc.query("SELECT * FROM scan_chunk WHERE scan_table_id=? AND status='DONE' ORDER BY seq",
            scanTableId, mapper = chunkMapper)

    fun markChunkRunning(chunkId: Long) {
        jdbc.update("UPDATE scan_chunk SET status='RUNNING', attempts = attempts + 1, error=NULL WHERE id=?", chunkId)
    }

    fun markChunkDone(chunkId: Long, rowCount: Long, colStatsJson: String?) {
        jdbc.update("UPDATE scan_chunk SET status='DONE', row_count=?, col_stats=? WHERE id=?",
            rowCount, colStatsJson, chunkId)
    }

    fun markChunkStatus(chunkId: Long, status: ScanStatus, error: String?) {
        jdbc.update("UPDATE scan_chunk SET status=?, error=? WHERE id=?", status.name, error, chunkId)
    }

    fun deleteChunks(scanTableId: Long) {
        jdbc.update("DELETE FROM scan_chunk WHERE scan_table_id=?", scanTableId)
    }

    /** 续扫:把未完成的段重置为 PENDING,返回重置数量 */
    fun resetUnfinishedChunks(scanTableId: Long): Int =
        jdbc.update("UPDATE scan_chunk SET status='PENDING', error=NULL " +
                "WHERE scan_table_id=? AND status IN ('PENDING','RUNNING','FAILED','CANCELED')", scanTableId)

    fun cancelPendingChunksByJob(jobId: Long): Int =
        jdbc.update("UPDATE scan_chunk SET status='CANCELED' WHERE status='PENDING' AND scan_table_id IN " +
                "(SELECT id FROM scan_table WHERE job_id=?)", jobId)

    fun markRunningChunksCanceled(): Int =
        jdbc.update("UPDATE scan_chunk SET status='CANCELED' WHERE status='RUNNING'")

    fun listRunningChunksByJob(jobId: Long): List<ChunkRecord> =
        jdbc.query("SELECT c.* FROM scan_chunk c JOIN scan_table t ON c.scan_table_id = t.id " +
                "WHERE t.job_id=? AND c.status='RUNNING'", jobId, mapper = chunkMapper)

    // ---------- scan_column ----------

    fun insertScanColumn(scanTableId: Long, col: ScanColumnView) {
        jdbc.update("INSERT INTO scan_column(scan_table_id, column_name, column_type, column_comment, nullable, " +
                "default_value, key_label, total_rows, null_count, empty_count, rule_hit_count) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            scanTableId, col.columnName, col.columnType, col.columnComment, col.nullable,
            col.defaultValue, col.keyLabel, col.totalRows,
            col.nullCount, col.emptyCount, col.ruleHitCount)
    }

    fun listScanColumns(scanTableId: Long): List<ScanColumnView> =
        jdbc.query("SELECT * FROM scan_column WHERE scan_table_id=? ORDER BY id", scanTableId) { rs ->
            ScanColumnView.of(
                rs.getString("column_name"), rs.getString("column_type"),
                rs.getString("column_comment"),
                if (rs.getObject("nullable") == null) null else rs.getBoolean("nullable"),
                rs.getString("default_value"), rs.getString("key_label"),
                rs.getLong("total_rows"), rs.getLong("null_count"),
                rs.getLong("empty_count"), rs.getLong("rule_hit_count"))
        }

    fun deleteScanColumns(scanTableId: Long) {
        jdbc.update("DELETE FROM scan_column WHERE scan_table_id=?", scanTableId)
    }
}
