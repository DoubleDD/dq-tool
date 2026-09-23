package com.example.dq.repository

import com.example.dq.model.AiTagBatchTask
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * 批量 AI 打标后台任务(ai_tag_batch_task,V75 + V76 table_names):创建/进度/终态汇总;
 * 服务重启时残留 PENDING/RUNNING 统一置 FAILED(执行线程已随重启消亡,不做断点续跑)。
 * table_names 存打标表名 JSON 数组,active 视图带出让表列表页对打标中的表显示 loading。
 */
class AiTagBatchTaskRepository(private val jdbc: Jdbc) {

    private val json = jacksonObjectMapper()

    private val mapper: (ResultSet) -> AiTagBatchTask = { rs ->
        val tagged = rs.getInt("tagged_count")
        val unmatched = rs.getInt("unmatched_count")
        val skipped = rs.getInt("skipped_count")
        AiTagBatchTask(
            rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("db_name"), rs.getString("schema_name"),
            rs.getInt("table_count"), parseTableNames(rs.getString("table_names")),
            rs.getString("status"), rs.getString("stage"),
            rs.getInt("progress_done"), rs.getInt("progress_total"),
            if (rs.wasNull()) null else tagged,
            if (rs.wasNull()) null else unmatched,
            if (rs.wasNull()) null else skipped,
            rs.getString("error"), ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    /** 解析 table_names:JSON 数组;空/坏内容返回空列表不炸详情(老行 V76 前为 NULL) */
    private fun parseTableNames(raw: String?): List<String> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.readValue(raw, object : TypeReference<List<String>>() {}) }
                .getOrDefault(emptyList())
        }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()

    /** 创建任务(显式 PENDING:线程池排队期间诚实显示「排队中」;started_at 由 markRunning 在开始执行时记) */
    fun insert(datasourceId: Long, dbName: String, schema: String, tableCount: Int, tableNames: List<String>): Long =
        jdbc.insert("INSERT INTO ai_tag_batch_task(datasource_id, db_name, schema_name, table_count, table_names) " +
                "VALUES (?,?,?,?,?)",
            datasourceId, dbName, schema, tableCount, json.writeValueAsString(tableNames))

    /** 后台线程取出任务开始执行:置 RUNNING 并记开始时刻(排队等待不计入耗时) */
    fun markRunning(id: Long) {
        jdbc.update("UPDATE ai_tag_batch_task SET status='RUNNING', started_at=CURRENT_TIMESTAMP WHERE id=?", id)
    }

    /** 逐表推进:done=已处理表数,total=表总数,stage=当前正在打标的表名 */
    fun updateProgress(id: Long, done: Int, total: Int, stage: String) {
        jdbc.update("UPDATE ai_tag_batch_task SET progress_done=?, progress_total=?, stage=? WHERE id=?",
            done, total, stage, id)
    }

    /** 任务完成:进度兜底写满(消除计数与终态不一致,同 relation_infer_job 口径)+ 终态汇总计数 */
    fun finish(id: Long, tagged: Int, unmatched: Int, skipped: Int) {
        jdbc.update("UPDATE ai_tag_batch_task SET status='DONE', progress_done=progress_total, " +
                "tagged_count=?, unmatched_count=?, skipped_count=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            tagged, unmatched, skipped, id)
    }

    fun fail(id: Long, error: String) {
        jdbc.update("UPDATE ai_tag_batch_task SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            error, id)
    }

    /** 后台任务中心轮询:全部未完成任务(跨库,PENDING 排队在前),id 升序 */
    fun listActive(): List<AiTagBatchTask> =
        jdbc.query("SELECT * FROM ai_tag_batch_task WHERE status IN ('PENDING','RUNNING') ORDER BY id",
            mapper = mapper)

    fun findById(id: Long): AiTagBatchTask? =
        jdbc.queryOne("SELECT * FROM ai_tag_batch_task WHERE id=?", id, mapper = mapper)

    /** 服务重启:残留 PENDING/RUNNING 统一置 FAILED */
    fun failUnfinished(): Int =
        jdbc.update("UPDATE ai_tag_batch_task SET status='FAILED', error='服务重启,打标任务中断', " +
                "finished_at=CURRENT_TIMESTAMP WHERE status IN ('PENDING','RUNNING')")
}
