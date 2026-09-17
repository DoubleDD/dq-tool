package com.example.dq.repository

import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * 比对任务批量导入批次表(V59)compare_import:原件落盘留档 + 数据源映射报告 + 建出的任务清单。
 * 生命周期短(解析 → 确认 → 建任务),只存到「任务建完」为止,之后归任务列表管;
 * 批次记录与原件一起长期保留供溯源(不做批次删除与文件清理)。
 * 状态机:BUILDING(占位/建任务中)→ DS_REVIEW(待确认数据源)→ DONE;失败 FAILED。
 * 重启残留 BUILDING 置 FAILED([failBuildingOnStartup],与 compare_job 残留 RUNNING 同思路)
 */
class CompareImportRepository(private val jdbc: Jdbc) {

    data class BatchRow(val id: Long, val fileName: String, val filePath: String, val fileSize: Long?,
                        val status: String, val dsReport: String?, val taskCount: Int,
                        val jobIds: String?, val error: String?,
                        val createdAt: LocalDateTime?, val finishedAt: LocalDateTime?)

    private val mapper: (ResultSet) -> BatchRow = { rs ->
        BatchRow(rs.getLong("id"), rs.getString("file_name"), rs.getString("file_path"),
            rs.getLong("file_size").let { if (rs.wasNull()) null else it },
            rs.getString("status"), rs.getString("ds_report"), rs.getInt("task_count"),
            rs.getString("job_ids"), rs.getString("error"),
            rs.getTimestamp("created_at")?.toLocalDateTime(), rs.getTimestamp("finished_at")?.toLocalDateTime())
    }

    /** 插行占位拿批次 id(原件随后写 compare-imports/batch-<id>/ 再回填 file_path/file_size) */
    fun insert(fileName: String): Long =
        jdbc.insert("INSERT INTO compare_import(file_name, file_path, status) VALUES (?,'','BUILDING')", fileName)

    /** 原件落盘后回填路径与大小 */
    fun updateFileInfo(id: Long, filePath: String, fileSize: Long) {
        jdbc.update("UPDATE compare_import SET file_path=?, file_size=? WHERE id=?", filePath, fileSize, id)
    }

    /** 解析 + 数据源匹配完成:置「待确认数据源」并落报告与 sheet 数 */
    fun markDsReview(id: Long, dsReportJson: String, taskCount: Int) {
        jdbc.update("UPDATE compare_import SET status='DS_REVIEW', ds_report=?, task_count=? WHERE id=?",
            dsReportJson, taskCount, id)
    }

    /** 用户确认数据源映射:DS_REVIEW → BUILDING(并发守卫,返回影响行数) */
    fun markBuilding(id: Long): Int =
        jdbc.update("UPDATE compare_import SET status='BUILDING' WHERE id=? AND status='DS_REVIEW'", id)

    /** 建任务完成:落最终数据源报告与任务 id 清单 */
    fun finish(id: Long, dsReportJson: String, jobIdsJson: String) {
        jdbc.update("UPDATE compare_import SET status='DONE', ds_report=?, job_ids=?, " +
            "finished_at=CURRENT_TIMESTAMP WHERE id=?", dsReportJson, jobIdsJson, id)
    }

    fun fail(id: Long, error: String) {
        jdbc.update("UPDATE compare_import SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            error.take(1000), id)
    }

    /** 服务重启:残留 BUILDING 批次置 FAILED(建任务线程已随重启消亡;原件与已建任务保留) */
    fun failBuildingOnStartup(error: String): Int =
        jdbc.update("UPDATE compare_import SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP " +
            "WHERE status='BUILDING'", error)

    fun findById(id: Long): BatchRow? =
        jdbc.queryOne("SELECT * FROM compare_import WHERE id=?", id, mapper = mapper)
}
