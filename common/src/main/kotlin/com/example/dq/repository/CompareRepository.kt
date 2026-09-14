package com.example.dq.repository

import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * 数据比对(V43):compare_job 任务表 / compare_target 目标指标表 / compare_diff 差异明细表。
 * 无外键,删除任务由 [deleteJob] tx 级联删三表;重跑由 [clearResults] tx 清空目标与差异明细;
 * 服务重启时残留 RUNNING 任务统一置 FAILED([failRunningOnStartup],与 report_export 同思路)
 */
class CompareRepository(private val jdbc: Jdbc) {

    // ---------- 任务行 ----------

    data class JobRow(val id: Long, val name: String, val baseDatasourceId: Long, val baseDb: String,
                      val baseSchema: String?, val baseTable: String, val keyField: String,
                      val displayField: String?, val matchMode: String?, val compareMode: String?,
                      val fieldsJson: String?,
                      val status: String, val stage: String?,
                      val totalUnits: Int, val doneUnits: Int, val error: String?, val archived: Boolean,
                      val createdAt: LocalDateTime?, val startedAt: LocalDateTime?, val finishedAt: LocalDateTime?)

    private val jobMapper: (ResultSet) -> JobRow = { rs ->
        JobRow(rs.getLong("id"), rs.getString("name"), rs.getLong("base_datasource_id"),
            rs.getString("base_db") ?: "", rs.getString("base_schema"), rs.getString("base_table"),
            rs.getString("key_field"), rs.getString("display_field"), rs.getString("match_mode"),
            rs.getString("compare_mode"), rs.getString("fields_json"),
            rs.getString("status"), rs.getString("stage"),
            rs.getInt("total_units"), rs.getInt("done_units"), rs.getString("error"), rs.getBoolean("archived"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"))
    }

    // ---------- 目标行 ----------

    data class TargetRow(val id: Long, val jobId: Long, val datasourceId: Long, val dsName: String?,
                         val dbName: String, val schemaName: String?, val tableName: String, val status: String,
                         val baseCount: Int?, val targetCount: Int?, val matchedCount: Int?,
                         val codeMatchedCount: Int?, val nameMatchedCount: Int?, val aiMatchedCount: Int?,
                         val missingCount: Int?,
                         val extraCount: Int?, val fieldMismatchCount: Int?, val coverage: Double?,
                         val fieldConsistency: Double?, val completeness: Double?, val score: Double?,
                         val error: String?, val fieldMappingJson: String? = null)

    private val targetMapper: (ResultSet) -> TargetRow = { rs ->
        TargetRow(rs.getLong("id"), rs.getLong("job_id"), rs.getLong("datasource_id"), rs.getString("ds_name"),
            rs.getString("db_name") ?: "", rs.getString("schema_name"), rs.getString("table_name"),
            rs.getString("status"), intOrNull(rs, "base_count"), intOrNull(rs, "target_count"),
            intOrNull(rs, "matched_count"),
            intOrNull(rs, "code_matched_count"), intOrNull(rs, "name_matched_count"),
            intOrNull(rs, "ai_matched_count"),
            intOrNull(rs, "missing_count"), intOrNull(rs, "extra_count"), intOrNull(rs, "field_mismatch_count"),
            doubleOrNull(rs, "coverage"), doubleOrNull(rs, "field_consistency"),
            doubleOrNull(rs, "completeness"), doubleOrNull(rs, "score"), rs.getString("error"),
            rs.getString("field_mapping_json"))
    }

    // ---------- 差异明细行 ----------

    data class DiffRow(val id: Long, val jobId: Long, val targetId: Long, val objectKey: String?,
                       val objectName: String?, val diffType: String, val diffJson: String?,
                       /** 该行对象的对齐来源:CODE / NAME / LLM;NULL = 老数据(按编码对齐解读) */
                       val matchBy: String? = null)

    private val diffMapper: (ResultSet) -> DiffRow = { rs ->
        DiffRow(rs.getLong("id"), rs.getLong("job_id"), rs.getLong("target_id"), rs.getString("object_key"),
            rs.getString("object_name"), rs.getString("diff_type"), rs.getString("diff_json"),
            rs.getString("match_by"))
    }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()

    private fun intOrNull(rs: ResultSet, col: String): Int? {
        val v = rs.getInt(col)
        return if (rs.wasNull()) null else v
    }

    private fun doubleOrNull(rs: ResultSet, col: String): Double? {
        val v = rs.getDouble(col)
        return if (rs.wasNull()) null else v
    }

    // ---------- 任务操作 ----------

    /**
     * 落任务:displayField 为提交时解析好的对象名称(显示名)字段,可空(空 = 无显示字段,object_name 落空串);
     * matchMode 为对象对齐匹配逻辑(EXACT/CODE_THEN_NAME/CODE_NAME_LLM),空 = 老任务按「只按编码」解读;
     * compareMode 为对比模式(ROW/COLUMN),空 = 行级(老任务兼容)
     */
    fun insertJob(name: String, baseDatasourceId: Long, baseDb: String, baseSchema: String?, baseTable: String,
                  keyField: String, fieldsJson: String, totalUnits: Int, displayField: String? = null,
                  matchMode: String? = null, compareMode: String? = null): Long =
        jdbc.insert("INSERT INTO compare_job(name, base_datasource_id, base_db, base_schema, base_table, " +
            "key_field, fields_json, display_field, match_mode, compare_mode, status, total_units, started_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,'RUNNING',?,CURRENT_TIMESTAMP)",
            name, baseDatasourceId, baseDb, baseSchema, baseTable, keyField, fieldsJson, displayField,
            matchMode, compareMode, totalUnits)

    fun getJob(id: Long): JobRow? =
        jdbc.queryOne("SELECT * FROM compare_job WHERE id=?", id, mapper = jobMapper)

    /** 任务列表:新的在前;includeArchived=false 时不返回已归档任务 */
    fun listJobs(includeArchived: Boolean): List<JobRow> =
        if (includeArchived) {
            jdbc.query("SELECT * FROM compare_job ORDER BY created_at DESC, id DESC", mapper = jobMapper)
        } else {
            jdbc.query("SELECT * FROM compare_job WHERE archived=FALSE ORDER BY created_at DESC, id DESC",
                mapper = jobMapper)
        }

    /** 后台任务中心轮询:RUNNING 任务(跨全部库,id 升序) */
    fun listActiveJobs(): List<JobRow> =
        jdbc.query("SELECT * FROM compare_job WHERE status='RUNNING' ORDER BY id", mapper = jobMapper)

    /** 活动任务的目标进度:jobId → (目标总数, 已出终态数);一次 GROUP BY 覆盖全部活动任务 */
    fun countActiveTargets(jobIds: List<Long>): Map<Long, Pair<Int, Int>> {
        if (jobIds.isEmpty()) return emptyMap()
        val placeholders = jobIds.joinToString(",") { "?" }
        return jdbc.query("SELECT job_id, COUNT(*) AS total, " +
                "SUM(CASE WHEN status IN ('DONE','FAILED') THEN 1 ELSE 0 END) AS done " +
                "FROM compare_target WHERE job_id IN ($placeholders) GROUP BY job_id", *jobIds.toTypedArray()) { rs ->
            rs.getLong("job_id") to (rs.getInt("total") to rs.getInt("done"))
        }.toMap()
    }

    fun updateStage(id: Long, stage: String) {
        jdbc.update("UPDATE compare_job SET stage=? WHERE id=?", stage, id)
    }

    fun updateProgress(id: Long, doneUnits: Int, stage: String) {
        jdbc.update("UPDATE compare_job SET done_units=?, stage=? WHERE id=?", doneUnits, stage, id)
    }

    /** 重跑:状态翻 RUNNING,进度/错误/时间清零,归档标记保留 */
    fun markRerun(id: Long, totalUnits: Int) {
        jdbc.update("UPDATE compare_job SET status='RUNNING', stage=NULL, total_units=?, done_units=0, " +
            "error=NULL, started_at=CURRENT_TIMESTAMP, finished_at=NULL WHERE id=?", totalUnits, id)
    }

    /** 任务完成(done_units 兜底写满,消除进度计数与终态不一致) */
    fun finishJob(id: Long) {
        jdbc.update("UPDATE compare_job SET status='DONE', done_units=total_units, finished_at=CURRENT_TIMESTAMP WHERE id=?", id)
    }

    fun failJob(id: Long, error: String) {
        jdbc.update("UPDATE compare_job SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
            error, id)
    }

    fun setArchived(id: Long, archived: Boolean) {
        jdbc.update("UPDATE compare_job SET archived=? WHERE id=?", archived, id)
    }

    /** 服务重启:残留 RUNNING 任务统一置 FAILED(工作线程已随重启消亡) */
    fun failRunningOnStartup(error: String): Int =
        jdbc.update("UPDATE compare_job SET status='FAILED', error=?, finished_at=CURRENT_TIMESTAMP WHERE status='RUNNING'", error)

    /** 服务重启:失败任务下未终态(PENDING/RUNNING)的目标一并置 FAILED */
    fun failUnfinishedTargets(error: String): Int =
        jdbc.update("UPDATE compare_target SET status='FAILED', error=? WHERE status IN ('PENDING','RUNNING') " +
            "AND job_id IN (SELECT id FROM compare_job WHERE status='FAILED')", error)

    /** 删除任务:tx 级联删差异明细 + 目标 + 任务(无外键,与 object_dir 同惯例) */
    fun deleteJob(id: Long) {
        jdbc.tx { conn ->
            for (sql in listOf("DELETE FROM compare_diff WHERE job_id=?",
                "DELETE FROM compare_target WHERE job_id=?",
                "DELETE FROM compare_job WHERE id=?")) {
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
            }
        }
    }

    // ---------- 目标操作 ----------

    /** 落目标行:fieldMappingJson 为人工字段映射(基准列名 → 目标列名)的 JSON 对象,空 = 按字段名自动匹配 */
    fun insertTarget(jobId: Long, datasourceId: Long, dsName: String?, dbName: String, schemaName: String?,
                     tableName: String, fieldMappingJson: String? = null): Long =
        jdbc.insert("INSERT INTO compare_target(job_id, datasource_id, ds_name, db_name, schema_name, table_name, " +
            "field_mapping_json, status) VALUES (?,?,?,?,?,?,?,'PENDING')",
            jobId, datasourceId, dsName, dbName, schemaName, tableName, fieldMappingJson)

    fun listTargets(jobId: Long): List<TargetRow> =
        jdbc.query("SELECT * FROM compare_target WHERE job_id=? ORDER BY id", jobId, mapper = targetMapper)

    fun markTargetRunning(id: Long) {
        jdbc.update("UPDATE compare_target SET status='RUNNING' WHERE id=?", id)
    }

    /**
     * 目标比对完成:指标四项比率连同计数一次性落库(targetCount=目标侧实际读到的总行数,含多余行);
     * code/name/aiMatchedCount 为三种对齐来源各自命中的对象数,三者之和 = matchedCount
     * (老口径只有 code 匹配,故三列留空时按 matchedCount 解读)
     */
    fun updateTargetStats(id: Long, baseCount: Int, targetCount: Int, matchedCount: Int, missingCount: Int,
                          extraCount: Int, fieldMismatchCount: Int, coverage: Double, fieldConsistency: Double,
                          completeness: Double, score: Double,
                          codeMatchedCount: Int? = null, nameMatchedCount: Int? = null,
                          aiMatchedCount: Int? = null) {
        jdbc.update("UPDATE compare_target SET status='DONE', base_count=?, target_count=?, matched_count=?, " +
            "missing_count=?, extra_count=?, field_mismatch_count=?, coverage=?, field_consistency=?, " +
            "completeness=?, score=?, code_matched_count=?, name_matched_count=?, ai_matched_count=? WHERE id=?",
            baseCount, targetCount, matchedCount, missingCount, extraCount, fieldMismatchCount,
            coverage, fieldConsistency, completeness, score,
            codeMatchedCount, nameMatchedCount, aiMatchedCount, id)
    }

    fun failTarget(id: Long, error: String) {
        jdbc.update("UPDATE compare_target SET status='FAILED', error=? WHERE id=?", error, id)
    }

    /**
     * 给已完成的目标追加说明(如大模型归一化补配部分批次失败):
     * 追加而不是覆盖,保留既有内容;error 列在 DONE 状态下仅作「需要人工关注的说明」用
     */
    fun appendTargetNote(id: Long, note: String) {
        jdbc.update("UPDATE compare_target SET error = CASE WHEN error IS NULL OR error='' THEN ? " +
            "ELSE error || ' | ' || ? END WHERE id=?", note.take(1000), note.take(1000), id)
    }

    /** 重跑前清空既有结果:tx 删差异明细 + 目标(目标行由调用方随后重建) */
    fun clearResults(jobId: Long) {
        jdbc.tx { conn ->
            for (sql in listOf("DELETE FROM compare_diff WHERE job_id=?",
                "DELETE FROM compare_target WHERE job_id=?")) {
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, jobId)
                    ps.executeUpdate()
                }
            }
        }
    }

    // ---------- 差异明细操作 ----------

    /** 待落库的差异明细行(matchBy 为对象对齐来源 CODE/NAME/LLM,可空) */
    data class DiffInput(val objectKey: String?, val objectName: String?, val diffType: String,
                         val diffJson: String?, val matchBy: String? = null)

    /** 批量插入差异明细(单事务;调用方按 500 分批) */
    fun insertDiffs(jobId: Long, targetId: Long, rows: List<DiffInput>) {
        if (rows.isEmpty()) return
        jdbc.tx { conn ->
            conn.prepareStatement("INSERT INTO compare_diff(job_id, target_id, object_key, object_name, diff_type, " +
                "diff_json, match_by) VALUES (?,?,?,?,?,?,?)").use { ps ->
                for (r in rows) {
                    ps.setLong(1, jobId)
                    ps.setLong(2, targetId)
                    ps.setString(3, r.objectKey)
                    ps.setString(4, r.objectName)
                    ps.setString(5, r.diffType)
                    ps.setString(6, r.diffJson)
                    ps.setString(7, r.matchBy)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /**
     * 差异明细分页查询:targetId/diffType/kw 均可空组合过滤;kw 匹配对象编码或对象名称(like 包含);
     * 返回 (当前页行, 符合条件的总行数),按 id 升序
     */
    fun diffsPage(jobId: Long, targetId: Long?, diffType: String?, kw: String?,
                  page: Int, size: Int): Pair<List<DiffRow>, Long> {
        val where = StringBuilder("job_id=?")
        val args = ArrayList<Any?>()
        args.add(jobId)
        if (targetId != null) {
            where.append(" AND target_id=?")
            args.add(targetId)
        }
        if (!diffType.isNullOrBlank()) {
            where.append(" AND diff_type=?")
            args.add(diffType)
        }
        if (!kw.isNullOrBlank()) {
            where.append(" AND (object_key LIKE ? OR object_name LIKE ?)")
            val like = "%${kw.trim()}%"
            args.add(like)
            args.add(like)
        }
        val total = jdbc.queryOne("SELECT COUNT(*) FROM compare_diff WHERE $where", *args.toTypedArray()) { rs ->
            rs.getLong(1)
        } ?: 0L
        val pageArgs = args.toMutableList()
        pageArgs.add(size)
        pageArgs.add((page - 1).toLong() * size)
        val rows = jdbc.query("SELECT * FROM compare_diff WHERE $where ORDER BY id LIMIT ? OFFSET ?",
            *pageArgs.toTypedArray(), mapper = diffMapper)
        return rows to total
    }

    /** 按差异类型统计行数(报告汇总用):diff_type → 行数 */
    fun countByDiffType(jobId: Long): Map<String, Long> =
        jdbc.query("SELECT diff_type, COUNT(*) FROM compare_diff WHERE job_id=? GROUP BY diff_type", jobId) { rs ->
            rs.getString(1) to rs.getLong(2)
        }.toMap()

    /** 全部 DIFF 行的 diff_json(报告问题字段排行用;Java/Kotlin 侧解析聚合,不在 SQL 里做) */
    fun listDiffJsons(jobId: Long): List<String> =
        jdbc.query("SELECT diff_json FROM compare_diff WHERE job_id=? AND diff_type='DIFF' AND diff_json IS NOT NULL",
            jobId) { rs -> rs.getString(1) }

    /** 单目标全部非 SAME 明细(差异导出用),按 id 升序 */
    fun listDiffsForExport(jobId: Long, targetId: Long): List<DiffRow> =
        jdbc.query("SELECT * FROM compare_diff WHERE job_id=? AND target_id=? AND diff_type<>'SAME' ORDER BY id",
            jobId, targetId, mapper = diffMapper)

    /**
     * 导出用差异行数统计:targetId → (diffType → 行数),只统计非 SAME 行。
     * 总览表的「差异条数」与明细 sheet 拆分策略(一差异行一 sheet / 超量按目标降级)都只看这个计数,
     * 不必为拿计数把明细全量读进内存
     */
    fun countForExport(jobId: Long): Map<Long, Map<String, Int>> =
        jdbc.query("SELECT target_id, diff_type, COUNT(*) FROM compare_diff " +
                "WHERE job_id=? AND diff_type<>'SAME' GROUP BY target_id, diff_type", jobId) { rs ->
            Triple(rs.getLong(1), rs.getString(2), rs.getInt(3))
        }.groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, rows) -> rows.toMap() }
}
