package com.example.dq.repository

import java.sql.ResultSet
import java.time.LocalDateTime

/** Word 报告异步导出任务(V9);状态机 PENDING → RUNNING → DONE/FAILED,服务重启时未完成任务统一置 FAILED */
class ReportExportRepository(private val jdbc: Jdbc) {

    data class Row(val id: Long, val datasourceId: Long, val dbName: String, val schemaNames: String?,
                   val status: String, val stage: String?, val progressDone: Int, val progressTotal: Int,
                   val filePath: String?, val fileSize: Long?, val error: String?,
                   val createdAt: LocalDateTime?, val startedAt: LocalDateTime?, val finishedAt: LocalDateTime?)

    private val mapper: (ResultSet) -> Row = { rs ->
        val size = rs.getLong("file_size")
        Row(rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("db_name"), rs.getString("schema_names"),
            rs.getString("status"), rs.getString("stage"), rs.getInt("progress_done"), rs.getInt("progress_total"),
            rs.getString("file_path"), if (rs.wasNull()) null else size, rs.getString("error"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()

    fun insert(datasourceId: Long, dbName: String, schemaNames: String?): Long =
        jdbc.insert("INSERT INTO report_export(datasource_id, db_name, schema_names) VALUES (?,?,?)",
            datasourceId, dbName, schemaNames)

    fun findById(id: Long): Row? =
        jdbc.queryOne("SELECT * FROM report_export WHERE id=?", id, mapper = mapper)

    /** 任务列表:新的在前;datasourceId 为空查全部 */
    fun list(datasourceId: Long?): List<Row> =
        if (datasourceId == null) {
            jdbc.query("SELECT * FROM report_export ORDER BY id DESC LIMIT 200", mapper = mapper)
        } else {
            jdbc.query("SELECT * FROM report_export WHERE datasource_id=? ORDER BY id DESC LIMIT 200",
                datasourceId, mapper = mapper)
        }

    fun markRunning(id: Long) {
        jdbc.update("UPDATE report_export SET status='RUNNING', started_at=CURRENT_TIMESTAMP WHERE id=?", id)
    }

    fun updateProgress(id: Long, done: Int, total: Int, stage: String) {
        jdbc.update("UPDATE report_export SET progress_done=?, progress_total=?, stage=? WHERE id=?",
            done, total, stage, id)
    }

    fun finish(id: Long, filePath: String, fileSize: Long) {
        jdbc.update("UPDATE report_export SET status='DONE', file_path=?, file_size=?, " +
                "finished_at=CURRENT_TIMESTAMP WHERE id=?", filePath, fileSize, id)
    }

    fun fail(id: Long, error: String) {
        jdbc.update("UPDATE report_export SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            error, id)
    }

    /** 服务重启:PENDING(内存队列已丢)/RUNNING 统一置 FAILED,与扫描任务的中断恢复同思路 */
    fun failUnfinished(): Int =
        jdbc.update("UPDATE report_export SET status='FAILED', error='服务重启,导出任务中断', " +
                "finished_at=CURRENT_TIMESTAMP WHERE status IN ('PENDING','RUNNING')")
}
