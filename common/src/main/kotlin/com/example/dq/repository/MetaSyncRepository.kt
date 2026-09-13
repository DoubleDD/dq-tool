package com.example.dq.repository

import com.example.dq.model.MetaSyncItem
import com.example.dq.model.MetaSyncJob
import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * 元数据批量同步任务(V41):任务(meta_sync_job)+ 逐数据源明细(meta_sync_item)。
 * 状态机:PENDING → RUNNING → DONE/FAILED/CANCELED;取消经检查点异步生效(cancel 只落库标志,
 * 工作线程在逐表/逐数据源检查点响应并把未完成明细置 CANCELED);
 * 服务重启时残留 PENDING/RUNNING 统一置 FAILED(与 sample_export 同思路)
 */
class MetaSyncRepository(private val jdbc: Jdbc) {

    private val jobMapper: (ResultSet) -> MetaSyncJob = { rs ->
        MetaSyncJob(rs.getLong("id"), rs.getString("status"),
            rs.getInt("total_ds"), rs.getInt("done_ds"), rs.getInt("failed_ds"), rs.getString("error"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    private val itemMapper: (ResultSet) -> MetaSyncItem = { rs ->
        MetaSyncItem(rs.getLong("id"), rs.getLong("job_id"), rs.getLong("datasource_id"),
            rs.getString("datasource_name"), rs.getString("status"),
            rs.getInt("db_count"), rs.getInt("schema_count"), rs.getInt("table_count"),
            rs.getString("progress"), rs.getString("error"),
            ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()

    // ---------- 任务操作 ----------

    fun insertJob(totalDs: Int): Long =
        jdbc.insert("INSERT INTO meta_sync_job(total_ds) VALUES (?)", totalDs)

    fun findJob(id: Long): MetaSyncJob? =
        jdbc.queryOne("SELECT * FROM meta_sync_job WHERE id=?", id, mapper = jobMapper)

    /** 最近一次任务(页面打开时恢复轮询用) */
    fun findLatest(): MetaSyncJob? =
        jdbc.queryOne("SELECT * FROM meta_sync_job ORDER BY id DESC LIMIT 1", mapper = jobMapper)

    /** 是否有未结束任务(同时只允许一个同步任务运行) */
    fun hasRunning(): Boolean =
        (jdbc.queryOne("SELECT COUNT(*) FROM meta_sync_job WHERE status IN ('PENDING','RUNNING')") { it.getLong(1) } ?: 0L) > 0

    /** 指定数据源是否有未结束的同步明细(元数据导入前校验,避免与同步的整粒度覆盖互相踩踏) */
    fun hasRunningForDatasource(datasourceId: Long): Boolean =
        (jdbc.queryOne("SELECT COUNT(*) FROM meta_sync_item i JOIN meta_sync_job j ON j.id = i.job_id " +
                "WHERE i.datasource_id=? AND j.status IN ('PENDING','RUNNING') " +
                "AND i.status IN ('PENDING','RUNNING')", datasourceId) { it.getLong(1) } ?: 0L) > 0

    fun isCanceled(id: Long): Boolean =
        findJob(id)?.status == "CANCELED"

    fun markRunning(id: Long) {
        jdbc.update("UPDATE meta_sync_job SET status='RUNNING', started_at=CURRENT_TIMESTAMP WHERE id=?", id)
    }

    /** 任务终态(done_ds 兜底写满仅在全成功时;有失败明细的任务如实保留计数) */
    fun finishJob(id: Long, status: String, error: String?) {
        jdbc.update("UPDATE meta_sync_job SET status=?, error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            status, error, id)
    }

    /** 取消:仅 PENDING/RUNNING → CANCELED 生效,返回更新行数(0=任务已结束,调用方据此报错) */
    fun markCanceled(id: Long): Int =
        jdbc.update("UPDATE meta_sync_job SET status='CANCELED', finished_at=CURRENT_TIMESTAMP " +
                "WHERE id=? AND status IN ('PENDING','RUNNING')", id)

    fun incrDone(id: Long) {
        jdbc.update("UPDATE meta_sync_job SET done_ds=done_ds+1 WHERE id=?", id)
    }

    fun incrFailed(id: Long) {
        jdbc.update("UPDATE meta_sync_job SET failed_ds=failed_ds+1 WHERE id=?", id)
    }

    /** 服务重启:PENDING(内存队列已丢)/RUNNING 任务统一置 FAILED */
    fun failUnfinished(): Int =
        jdbc.update("UPDATE meta_sync_job SET status='FAILED', error='服务重启,同步任务中断', " +
                "finished_at=CURRENT_TIMESTAMP WHERE status IN ('PENDING','RUNNING')")

    /** 服务重启:未完成任务的 PENDING/RUNNING 明细一并置 FAILED */
    fun failUnfinishedItems(): Int =
        jdbc.update("UPDATE meta_sync_item SET status='FAILED', error='服务重启,同步任务中断' " +
                "WHERE status IN ('PENDING','RUNNING')")

    // ---------- 明细操作 ----------

    /** 批量插入明细(datasourceId → 名称快照;单事务) */
    fun insertItems(jobId: Long, items: List<Pair<Long, String>>) {
        jdbc.tx { conn ->
            conn.prepareStatement(
                "INSERT INTO meta_sync_item(job_id, datasource_id, datasource_name) VALUES (?,?,?)")
                .use { ps ->
                    for ((dsId, name) in items) {
                        ps.setLong(1, jobId)
                        ps.setLong(2, dsId)
                        ps.setString(3, name)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }
    }

    fun listItems(jobId: Long): List<MetaSyncItem> =
        jdbc.query("SELECT * FROM meta_sync_item WHERE job_id=? ORDER BY id", jobId, mapper = itemMapper)

    fun markItemRunning(id: Long) {
        jdbc.update("UPDATE meta_sync_item SET status='RUNNING', started_at=CURRENT_TIMESTAMP, " +
                "error=NULL, progress=NULL WHERE id=?", id)
    }

    /** 明细进度文本(逐 schema/逐表更新,供前端轮询展示) */
    fun updateItemProgress(id: Long, progress: String) {
        jdbc.update("UPDATE meta_sync_item SET progress=? WHERE id=?", progress.take(512), id)
    }

    fun finishItem(id: Long, dbCount: Int, schemaCount: Int, tableCount: Int) {
        jdbc.update("UPDATE meta_sync_item SET status='DONE', db_count=?, schema_count=?, table_count=?, " +
                "finished_at=CURRENT_TIMESTAMP WHERE id=?", dbCount, schemaCount, tableCount, id)
    }

    fun failItem(id: Long, error: String) {
        jdbc.update("UPDATE meta_sync_item SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            error.take(2000), id)
    }

    /** 取消生效:未完成明细置 CANCELED */
    fun cancelItem(id: Long) {
        jdbc.update("UPDATE meta_sync_item SET status='CANCELED', finished_at=CURRENT_TIMESTAMP " +
                "WHERE id=? AND status IN ('PENDING','RUNNING')", id)
    }
}
