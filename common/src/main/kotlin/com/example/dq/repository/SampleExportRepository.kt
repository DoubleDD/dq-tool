package com.example.dq.repository

import com.example.dq.service.SampleTableExcelParser
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDateTime

/**
 * 表格批量导入数据源 + 抽样导出任务(V24);两步流程:上传后先跑数据源检测
 * (PENDING → RUNNING → DETECTED,停在 DETECTED 等用户决策是否导出),
 * 用户点「继续导出」再跑导出(DETECTED → RUNNING → DONE/FAILED);
 * 运行中可暂停为 PAUSED(恢复回到 RUNNING);服务重启时未完成(PENDING/RUNNING/PAUSED)
 * 任务与明细统一置 FAILED(与 report_export 同思路)
 */
class SampleExportRepository(private val jdbc: Jdbc) {

    // ---------- 任务行 ----------

    data class TaskRow(val id: Long, val fileName: String, val status: String, val stage: String?,
                       val totalItems: Int, val doneItems: Int,
                       val dsTotal: Int, val dsAdded: Int, val dsSkipped: Int, val dsFixed: Int, val dsError: Int,
                       val dsReport: String?, val zipPath: String?, val zipSize: Long?, val error: String?,
                       val createdAt: LocalDateTime?, val startedAt: LocalDateTime?, val finishedAt: LocalDateTime?)

    private val taskMapper: (ResultSet) -> TaskRow = { rs ->
        val zipSize = rs.getLong("zip_size")
        TaskRow(rs.getLong("id"), rs.getString("file_name"), rs.getString("status"), rs.getString("stage"),
            rs.getInt("total_items"), rs.getInt("done_items"),
            rs.getInt("ds_total"), rs.getInt("ds_added"), rs.getInt("ds_skipped"),
            rs.getInt("ds_fixed"), rs.getInt("ds_error"),
            rs.getString("ds_report"), rs.getString("zip_path"),
            if (rs.wasNull()) null else zipSize, rs.getString("error"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    // ---------- 明细行 ----------

    data class ItemRow(val id: Long, val taskId: Long, val seq: Int, val category: String?,
                       val sysNo: String?, val sysDesc: String?, val dbType: String?, val host: String?,
                       val port: Int?, val username: String?, val databaseName: String?, val schemaName: String?,
                       val tableName: String?, val tableCnName: String?, val dsKey: String?,
                       val datasourceId: Long?, val sheetName: String?, val status: String,
                       val rowCount: Int?, val error: String?, val excelFile: String?,
                       /** Excel「数据量」列的抽样行数;null=默认抽样行数 */
                       val sampleLimit: Int?)

    private val itemMapper: (ResultSet) -> ItemRow = { rs ->
        val port = rs.getInt("port")
        val dsId = rs.getLong("datasource_id")
        val rowCount = rs.getInt("row_count")
        val sampleLimit = rs.getInt("sample_limit")
        val hasSampleLimit = !rs.wasNull()
        ItemRow(rs.getLong("id"), rs.getLong("task_id"), rs.getInt("seq"), rs.getString("category"),
            rs.getString("sys_no"), rs.getString("sys_desc"), rs.getString("db_type"), rs.getString("host"),
            if (rs.wasNull()) null else port, rs.getString("username"),
            rs.getString("database_name"), rs.getString("schema_name"),
            rs.getString("table_name"), rs.getString("table_cn_name"), rs.getString("ds_key"),
            if (rs.wasNull()) null else dsId, rs.getString("sheet_name"), rs.getString("status"),
            if (rs.wasNull()) null else rowCount, rs.getString("error"), rs.getString("excel_file"),
            if (hasSampleLimit) sampleLimit else null)
    }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()

    // ---------- 任务操作 ----------

    fun insert(fileName: String, totalItems: Int): Long =
        jdbc.insert("INSERT INTO sample_export(file_name, total_items) VALUES (?,?)", fileName, totalItems)

    fun findById(id: Long): TaskRow? =
        jdbc.queryOne("SELECT * FROM sample_export WHERE id=?", id, mapper = taskMapper)

    /** 任务列表:新的在前 */
    fun list(): List<TaskRow> =
        jdbc.query("SELECT * FROM sample_export ORDER BY id DESC LIMIT 200", mapper = taskMapper)

    fun markRunning(id: Long) {
        jdbc.update("UPDATE sample_export SET status='RUNNING', started_at=CURRENT_TIMESTAMP WHERE id=?", id)
    }

    fun updateStage(id: Long, stage: String) {
        jdbc.update("UPDATE sample_export SET stage=? WHERE id=?", stage, id)
    }

    fun updateProgress(id: Long, done: Int, total: Int, stage: String) {
        jdbc.update("UPDATE sample_export SET done_items=?, total_items=?, stage=? WHERE id=?",
            done, total, stage, id)
    }

    /** 数据源导入阶段的计数与明细报告(JSON 数组原文) */
    fun updateDsCounts(id: Long, total: Int, added: Int, skipped: Int, fixed: Int, error: Int, dsReportJson: String) {
        jdbc.update("UPDATE sample_export SET ds_total=?, ds_added=?, ds_skipped=?, ds_fixed=?, ds_error=?, ds_report=? WHERE id=?",
            total, added, skipped, fixed, error, dsReportJson, id)
    }

    /** 任务完成(done_items 兜底写满,消除进度计数与终态不一致);整个任务没有任何 xlsx 产出时 zipPath/zipSize 为 null */
    fun finish(id: Long, zipPath: String?, zipSize: Long?) {
        jdbc.withStatement({ conn ->
            conn.prepareStatement("UPDATE sample_export SET status='DONE', zip_path=?, zip_size=?, done_items=total_items, finished_at=CURRENT_TIMESTAMP WHERE id=?")
        }) { ps ->
            ps.setString(1, zipPath)
            if (zipSize != null) ps.setLong(2, zipSize) else ps.setNull(2, Types.BIGINT)
            ps.setLong(3, id)
            ps.executeUpdate()
        }
    }

    fun fail(id: Long, error: String) {
        jdbc.update("UPDATE sample_export SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            error, id)
    }

    /** 暂停:仅 RUNNING → PAUSED 生效,返回更新行数(0=任务已结束或已暂停,调用方据此报错) */
    fun markPaused(id: Long): Int =
        jdbc.update("UPDATE sample_export SET status='PAUSED' WHERE id=? AND status='RUNNING'", id)

    /** 恢复:仅 PAUSED → RUNNING 生效,返回更新行数 */
    fun markResumed(id: Long): Int =
        jdbc.update("UPDATE sample_export SET status='RUNNING' WHERE id=? AND status='PAUSED'", id)

    /** 数据源检测完成:RUNNING → DETECTED(待用户决策是否继续导出),返回更新行数 */
    fun markDetected(id: Long): Int =
        jdbc.update("UPDATE sample_export SET status='DETECTED', stage='检测完成,待导出' WHERE id=? AND status='RUNNING'", id)

    /** 用户决策继续导出:DETECTED → RUNNING(进入导出阶段),返回更新行数 */
    fun markExporting(id: Long): Int =
        jdbc.update("UPDATE sample_export SET status='RUNNING', stage='导出表数据' WHERE id=? AND status='DETECTED'", id)

    /** 删除任务(sample_export_item 经 task_id 外键级联删除) */
    fun deleteTask(id: Long) {
        jdbc.update("DELETE FROM sample_export WHERE id=?", id)
    }

    /** 服务重启:PENDING(内存队列已丢)/RUNNING/PAUSED(等待线程已随重启消亡)任务统一置 FAILED */
    fun failUnfinished(): Int =
        jdbc.update("UPDATE sample_export SET status='FAILED', error='服务重启,导出任务中断', " +
                "finished_at=CURRENT_TIMESTAMP WHERE status IN ('PENDING','RUNNING','PAUSED')")

    /** 服务重启:未完成任务的 PENDING/RUNNING 明细一并置 FAILED */
    fun failUnfinishedItems(): Int =
        jdbc.update("UPDATE sample_export_item SET status='FAILED', error='服务重启,导出任务中断' " +
                "WHERE status IN ('PENDING','RUNNING')")

    // ---------- 明细操作 ----------

    /** 批量插入明细(单事务);口令不落表,ds_key 取自解析行 */
    fun insertItems(taskId: Long, rows: List<SampleTableExcelParser.SampleTableRow>) {
        jdbc.tx { conn ->
            conn.prepareStatement(
                "INSERT INTO sample_export_item(task_id, seq, category, sys_no, sys_desc, db_type, host, port, username, " +
                        "database_name, schema_name, table_name, table_cn_name, ds_key, sample_limit) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .use { ps ->
                    for (r in rows) {
                        ps.setLong(1, taskId)
                        ps.setInt(2, r.seq)
                        ps.setString(3, r.category)
                        ps.setString(4, r.sysNo)
                        ps.setString(5, r.sysDesc)
                        ps.setString(6, r.dbType.name)
                        ps.setString(7, r.host)
                        val port = r.port
                        if (port != null) ps.setInt(8, port) else ps.setNull(8, Types.INTEGER)
                        ps.setString(9, r.username)
                        ps.setString(10, r.databaseName)
                        ps.setString(11, r.schemaName)
                        ps.setString(12, r.tableName)
                        ps.setString(13, r.tableCnName)
                        ps.setString(14, r.dsKey)
                        val limit = r.sampleLimit
                        if (limit != null) ps.setInt(15, limit) else ps.setNull(15, Types.INTEGER)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }
    }

    fun listItems(taskId: Long): List<ItemRow> =
        jdbc.query("SELECT * FROM sample_export_item WHERE task_id=? ORDER BY seq", taskId, mapper = itemMapper)

    /**
     * 重新导入 Excel:全量替换明细(先删旧行),文件名/总数同步更新,统计/报告/产物路径/错误一并清零。
     * 状态与 started_at 由调用方随后重置(markRunning)。单事务
     */
    fun replaceItems(id: Long, fileName: String, totalItems: Int) {
        jdbc.tx { conn ->
            conn.prepareStatement("DELETE FROM sample_export_item WHERE task_id=?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "UPDATE sample_export SET file_name=?, total_items=?, done_items=0, ds_total=0, ds_added=0, " +
                    "ds_skipped=0, ds_fixed=0, ds_error=0, ds_report=NULL, zip_path=NULL, zip_size=NULL, " +
                    "error=NULL, stage=NULL, started_at=NULL, finished_at=NULL WHERE id=?")
                .use { ps ->
                    ps.setString(1, fileName)
                    ps.setInt(2, totalItems)
                    ps.setLong(3, id)
                    ps.executeUpdate()
                }
        }
    }

    /** 数据源导入完成后回绑匹配到的数据源 id */
    fun bindItemDatasource(id: Long, datasourceId: Long) {
        jdbc.update("UPDATE sample_export_item SET datasource_id=? WHERE id=?", datasourceId, id)
    }

    fun updateItemStatus(id: Long, status: String) {
        jdbc.update("UPDATE sample_export_item SET status=? WHERE id=?", status, id)
    }

    fun finishItem(id: Long, sheetName: String?, excelFile: String?, rowCount: Int) {
        jdbc.update("UPDATE sample_export_item SET status='DONE', sheet_name=?, excel_file=?, row_count=? WHERE id=?",
            sheetName, excelFile, rowCount, id)
    }

    fun failItem(id: Long, error: String) {
        jdbc.update("UPDATE sample_export_item SET status='FAILED', error=? WHERE id=?", error, id)
    }
}
