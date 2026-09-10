package com.example.dq.repository

import com.example.dq.model.AnchorField
import com.example.dq.model.RelationInferJob
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * ER 关系推导任务(relation_infer_job):创建/进度/终态;
 * 服务重启时残留 RUNNING 统一置 FAILED(error=服务重启中断;推导可重跑,不做断点续推)。
 * anchor_columns 存锚点字段 JSON 串([{"name":"id","aliases":["work_order_id"]}]);
 * 读侧对 M1 初期逗号分隔旧格式兜底解析,不写回
 */
class RelationInferJobRepository(private val jdbc: Jdbc) {

    private val json = jacksonObjectMapper()

    private val mapper: (ResultSet) -> RelationInferJob = { rs ->
        RelationInferJob(
            rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("db_name"), rs.getString("schema_name"),
            rs.getString("anchor_table"), parseAnchorFields(rs.getString("anchor_columns")),
            rs.getBoolean("use_semantic"), rs.getString("status"), rs.getString("stage"),
            rs.getInt("total_steps"), rs.getInt("done_steps"), rs.getInt("found_count"), rs.getString("error"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()

    /** 解析 anchor_columns:JSON 数组优先;旧逗号分隔格式(无映射名)兜底;坏内容返回空列表不炸详情 */
    private fun parseAnchorFields(raw: String): List<AnchorField> =
        if (raw.trimStart().startsWith("[")) {
            runCatching {
                json.readValue(raw, object : TypeReference<List<AnchorField>>() {})
            }.getOrDefault(emptyList())
        } else {
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { AnchorField(it) }
        }

    /** 创建任务(立即 RUNNING,started_at 记提交时刻;anchorFields 序列化为 JSON 落库) */
    fun insert(datasourceId: Long, dbName: String, schema: String,
               anchorTable: String, anchorFields: List<AnchorField>, useSemantic: Boolean): Long =
        jdbc.insert("INSERT INTO relation_infer_job(datasource_id, db_name, schema_name, anchor_table, anchor_columns, " +
                "use_semantic, started_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            datasourceId, dbName, schema, anchorTable, json.writeValueAsString(anchorFields), useSemantic)

    fun findById(id: Long): RelationInferJob? =
        jdbc.queryOne("SELECT * FROM relation_infer_job WHERE id=?", id, mapper = mapper)

    /** 按库查询任务列表(前端 1s 轮询),新的在前 */
    fun listBySchema(datasourceId: Long, dbName: String, schema: String): List<RelationInferJob> =
        jdbc.query("SELECT * FROM relation_infer_job WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                "ORDER BY id DESC LIMIT 100", datasourceId, dbName, schema, mapper = mapper)

    fun updateStage(id: Long, stage: String) {
        jdbc.update("UPDATE relation_infer_job SET stage=? WHERE id=?", stage, id)
    }

    fun updateProgress(id: Long, totalSteps: Int, doneSteps: Int, foundCount: Int) {
        jdbc.update("UPDATE relation_infer_job SET total_steps=?, done_steps=?, found_count=? WHERE id=?",
            totalSteps, doneSteps, foundCount, id)
    }

    /** 任务完成(done_steps 兜底写满,消除进度计数与终态不一致;同 sample_export 口径) */
    fun finish(id: Long, foundCount: Int) {
        jdbc.update("UPDATE relation_infer_job SET status='DONE', found_count=?, done_steps=total_steps, " +
                "finished_at=CURRENT_TIMESTAMP WHERE id=?", foundCount, id)
    }

    fun fail(id: Long, error: String) {
        jdbc.update("UPDATE relation_infer_job SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            error, id)
    }

    /**
     * 追加非致命附注(语义通道单批/单表 LLM 失败,任务继续;复用 error 列,终态不清空)。
     * 单条截断 300 字符,历史附注超长时截头,整体不超列长 2048
     */
    fun appendNote(id: Long, note: String) {
        val n = if (note.length <= 300) note else note.substring(0, 300)
        jdbc.update("UPDATE relation_infer_job SET error = CASE WHEN error IS NULL OR error='' THEN ? " +
                "ELSE SUBSTRING(error, 1, 1700) || '; ' || ? END WHERE id=?", n, n, id)
    }

    /** 服务重启:残留 RUNNING(执行线程已随重启消亡)统一置 FAILED */
    fun failRunningOnStartup(): Int =
        jdbc.update("UPDATE relation_infer_job SET status='FAILED', error='服务重启,推导任务中断', " +
                "finished_at=CURRENT_TIMESTAMP WHERE status='RUNNING'")
}
