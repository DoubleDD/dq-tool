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
                      val createdAt: LocalDateTime?, val startedAt: LocalDateTime?, val finishedAt: LocalDateTime?,
                      /** 待处理原因(仅 PENDING;V59) */
                      val pendingReason: String? = null,
                      /** 所属水利对象类别名称(批量导入取自表格基准行,自由文本不校验;V59) */
                      val objectCategory: String? = null,
                      /** 来源导入批次 id(向导手工建为 NULL;V59) */
                      val importId: Long? = null,
                      /** 来源导入文件名(LEFT JOIN compare_import 取;查询 SQL 必须带该别名) */
                      val importFileName: String? = null,
                      /** 基准表最新更新时间快照(比对执行时探测时间字段取 MAX;V60,未采集/无字段为 NULL) */
                      val baseDataUpdatedAt: String? = null,
                      /** 任务级身份字段数组 JSON(V62);NULL = 老任务,读取时由 [keyField] 单列退化 [keyField] */
                      val keyFieldsJson: String? = null)

    /** 任务查询统一带 import_id 左联 compare_import 取来源文件名([jobMapper] 依赖 import_file_name 别名) */
    private val jobSelect = "SELECT j.*, i.file_name AS import_file_name " +
        "FROM compare_job j LEFT JOIN compare_import i ON i.id = j.import_id"

    private val jobMapper: (ResultSet) -> JobRow = { rs ->
        JobRow(rs.getLong("id"), rs.getString("name"), rs.getLong("base_datasource_id"),
            rs.getString("base_db") ?: "", rs.getString("base_schema"), rs.getString("base_table"),
            rs.getString("key_field"), rs.getString("display_field"), rs.getString("match_mode"),
            rs.getString("compare_mode"), rs.getString("fields_json"),
            rs.getString("status"), rs.getString("stage"),
            rs.getInt("total_units"), rs.getInt("done_units"), rs.getString("error"), rs.getBoolean("archived"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"),
            rs.getString("pending_reason"), rs.getString("object_category"),
            rs.getLong("import_id").let { if (rs.wasNull()) null else it }, rs.getString("import_file_name"),
            rs.getString("base_data_updated_at"), rs.getString("key_fields_json"))
    }

    // ---------- 目标行 ----------

    data class TargetRow(val id: Long, val jobId: Long, val datasourceId: Long, val dsName: String?,
                         val dbName: String, val schemaName: String?, val tableName: String, val status: String,
                         val baseCount: Int?, val targetCount: Int?, val matchedCount: Int?,
                         val codeMatchedCount: Int?, val nameMatchedCount: Int?, val aiMatchedCount: Int?,
                         val missingCount: Int?,
                         val extraCount: Int?, val fieldMismatchCount: Int?, val coverage: Double?,
                         val fieldConsistency: Double?, val completeness: Double?, val score: Double?,
                         val error: String?, val fieldMappingJson: String? = null,
                         /** 该目标表最新更新时间快照(比对执行时探测时间字段取 MAX;V60,未采集/无字段为 NULL) */
                         val dataUpdatedAt: String? = null,
                         /** 目标级身份字段人工覆盖 {"keys":[...]}(V62);NULL = 按任务级 keyFields ∩ 映射键推导 */
                         val identityJson: String? = null,
                         /** 目标表身份列为空的行数:代理键进比对,编码路不参与、名称/大模型可配对(V63,老任务 NULL = 0) */
                         val noKeyRows: Int? = null)

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
            rs.getString("field_mapping_json"), rs.getString("data_updated_at"), rs.getString("identity_json"),
            intOrNull(rs, "no_key_rows"))
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
     * compareMode 为对比模式(ROW/COLUMN),空 = 行级(老任务兼容);
     * keyFieldsJson 为任务级身份字段全量数组(keyField 旧列仍写 keys 第一项;老任务 NULL,读取退化 [keyField])
     */
    fun insertJob(name: String, baseDatasourceId: Long, baseDb: String, baseSchema: String?, baseTable: String,
                  keyField: String, fieldsJson: String, totalUnits: Int, displayField: String? = null,
                  matchMode: String? = null, compareMode: String? = null, keyFieldsJson: String? = null): Long =
        jdbc.insert("INSERT INTO compare_job(name, base_datasource_id, base_db, base_schema, base_table, " +
            "key_field, fields_json, display_field, match_mode, compare_mode, key_fields_json, status, total_units, started_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,'RUNNING',?,CURRENT_TIMESTAMP)",
            name, baseDatasourceId, baseDb, baseSchema, baseTable, keyField, fieldsJson, displayField,
            matchMode, compareMode, keyFieldsJson, totalUnits)

    /**
     * 落「待处理」任务(批量导入专用,V59):status=PENDING、不进执行器、started_at 留空;
     * pendingReason 记待处理原因(DS_ERROR/MAPPING_RUNNING/MAPPING_REVIEW/IMPORT_ERROR),
     * objectCategory 为所属水利对象类别名称(取自导入表格基准行原值,自由文本不校验),importId 为来源批次
     */
    fun insertPendingJob(name: String, baseDatasourceId: Long, baseDb: String, baseSchema: String?,
                         baseTable: String, keyField: String, fieldsJson: String, totalUnits: Int,
                         displayField: String?, matchMode: String?, compareMode: String?,
                         pendingReason: String, objectCategory: String?, importId: Long?): Long =
        jdbc.insert("INSERT INTO compare_job(name, base_datasource_id, base_db, base_schema, base_table, " +
            "key_field, fields_json, display_field, match_mode, compare_mode, status, pending_reason, " +
            "object_category, import_id, total_units) VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?)",
            name, baseDatasourceId, baseDb, baseSchema, baseTable, keyField, fieldsJson, displayField,
            matchMode, compareMode, pendingReason, objectCategory, importId, totalUnits)

    /** 待处理任务编辑时重建的目标行(照 rerun 的清空重建口径) */
    data class NewTarget(val datasourceId: Long, val dsName: String?, val dbName: String,
                         val schemaName: String?, val tableName: String, val fieldMappingJson: String?,
                         /** 目标级身份字段人工覆盖 {"keys":[...]}(V62),可空 */
                         val identityJson: String? = null)

    /**
     * 「待处理」任务编辑提交(向导编辑模式):tx 内替换 job 元数据 + 删旧目标(与差异明细)按新清单重建。
     * 保持 PENDING 与 import 溯源列不动;匹配逻辑/对比模式随请求改(编辑不做限制,与终态任务编辑同口径);
     * pending_reason 归一为 MAPPING_REVIEW(编辑后映射仍需人工审核),进度/错误清零
     */
    fun replacePendingJob(id: Long, name: String, baseDatasourceId: Long, baseDb: String, baseSchema: String?,
                          baseTable: String, keyField: String, fieldsJson: String, totalUnits: Int,
                          displayField: String?, matchMode: String?, compareMode: String?,
                          keyFieldsJson: String?,
                          targets: List<NewTarget>) {
        jdbc.tx { conn ->
            conn.prepareStatement(
                "UPDATE compare_job SET name=?, base_datasource_id=?, base_db=?, base_schema=?, base_table=?, " +
                    "key_field=?, fields_json=?, display_field=?, match_mode=?, compare_mode=?, key_fields_json=?, total_units=?, done_units=0, stage=NULL, " +
                    "base_data_updated_at=NULL, error=NULL, pending_reason='MAPPING_REVIEW' " +
                "WHERE id=? AND status='PENDING'").use { ps ->
                ps.setString(1, name)
                ps.setLong(2, baseDatasourceId)
                ps.setString(3, baseDb)
                ps.setString(4, baseSchema)
                ps.setString(5, baseTable)
                ps.setString(6, keyField)
                ps.setString(7, fieldsJson)
                ps.setString(8, displayField)
                ps.setString(9, matchMode)
                ps.setString(10, compareMode)
                ps.setString(11, keyFieldsJson)
                ps.setInt(12, totalUnits)
                ps.setLong(13, id)
                ps.executeUpdate()
            }
            for (sql in listOf("DELETE FROM compare_diff WHERE job_id=?",
                "DELETE FROM compare_target WHERE job_id=?")) {
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("INSERT INTO compare_target(job_id, datasource_id, ds_name, db_name, " +
                "schema_name, table_name, field_mapping_json, identity_json, status) VALUES (?,?,?,?,?,?,?,?,'PENDING')").use { ps ->
                for (t in targets) {
                    ps.setLong(1, id)
                    ps.setLong(2, t.datasourceId)
                    ps.setString(3, t.dsName)
                    ps.setString(4, t.dbName)
                    ps.setString(5, t.schemaName)
                    ps.setString(6, t.tableName)
                    ps.setString(7, t.fieldMappingJson)
                    ps.setString(8, t.identityJson)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** 待处理 → 运行中:进度/错误/待处理原因清零,补 started_at;WHERE 守 PENDING 防并发双启,返回影响行数 */
    fun markPendingRunning(id: Long, totalUnits: Int): Int =
        jdbc.update("UPDATE compare_job SET status='RUNNING', stage=NULL, total_units=?, done_units=0, " +
            "error=NULL, pending_reason=NULL, " +
            "started_at=CURRENT_TIMESTAMP, finished_at=NULL WHERE id=? AND status='PENDING'", totalUnits, id)

    /**
     * 终态任务(DONE/FAILED/CANCELED)「编辑并重跑」(向导编辑提交,2026-09):tx 内替换 job 元数据
     * (终态任务匹配逻辑/对比模式允许改,与新建同口径)+ 删旧 targets(与差异明细)按新清单重建,
     * 并直接置 RUNNING 补 started_at;WHERE 守终态防并发(编辑期间有人点了重跑/删除),
     * 返回 UPDATE 影响行数(0 = 状态已变化,调用方抛错,tx 回滚)
     */
    fun replaceAndRestart(id: Long, name: String, baseDatasourceId: Long, baseDb: String, baseSchema: String?,
                          baseTable: String, keyField: String, fieldsJson: String, totalUnits: Int,
                          displayField: String?, matchMode: String, compareMode: String,
                          keyFieldsJson: String?,
                          targets: List<NewTarget>): Int = jdbc.tx { conn ->
        val updated = conn.prepareStatement(
            "UPDATE compare_job SET name=?, base_datasource_id=?, base_db=?, base_schema=?, base_table=?, " +
                "key_field=?, fields_json=?, display_field=?, match_mode=?, compare_mode=?, key_fields_json=?, total_units=?, " +
                "done_units=0, stage=NULL, error=NULL, pending_reason=NULL, status='RUNNING', " +
                "started_at=CURRENT_TIMESTAMP, finished_at=NULL, base_data_updated_at=NULL " +
            "WHERE id=? AND status IN ('DONE','FAILED','CANCELED')").use { ps ->
            ps.setString(1, name)
            ps.setLong(2, baseDatasourceId)
            ps.setString(3, baseDb)
            ps.setString(4, baseSchema)
            ps.setString(5, baseTable)
            ps.setString(6, keyField)
            ps.setString(7, fieldsJson)
            ps.setString(8, displayField)
            ps.setString(9, matchMode)
            ps.setString(10, compareMode)
            ps.setString(11, keyFieldsJson)
            ps.setInt(12, totalUnits)
            ps.setLong(13, id)
            ps.executeUpdate()
        }
        if (updated == 0) {
            0
        } else {
            for (sql in listOf("DELETE FROM compare_diff WHERE job_id=?",
                "DELETE FROM compare_target WHERE job_id=?")) {
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("INSERT INTO compare_target(job_id, datasource_id, ds_name, db_name, " +
                "schema_name, table_name, field_mapping_json, identity_json, status) VALUES (?,?,?,?,?,?,?,?,'PENDING')").use { ps ->
                for (t in targets) {
                    ps.setLong(1, id)
                    ps.setLong(2, t.datasourceId)
                    ps.setString(3, t.dsName)
                    ps.setString(4, t.dbName)
                    ps.setString(5, t.schemaName)
                    ps.setString(6, t.tableName)
                    ps.setString(7, t.fieldMappingJson)
                    ps.setString(8, t.identityJson)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            updated
        }
    }

    /**
     * 后台字段映射结束(批量导入):回写基准表归一后的实际字段 + 新待处理原因 + 失败说明;
     * 目标行 mapping 由调用方逐 target [updateTargetMapping]。WHERE 守「PENDING + MAPPING_RUNNING」:
     * 任务已删除/已被人工处理(原因翻走)时返回 0,后台线程据此放弃回写
     */
    fun finishPendingMapping(id: Long, keyField: String, fieldsJson: String, displayField: String?,
                             pendingReason: String, error: String?): Int =
        jdbc.update("UPDATE compare_job SET key_field=?, fields_json=?, display_field=?, pending_reason=?, error=? " +
            "WHERE id=? AND status='PENDING' AND pending_reason='MAPPING_RUNNING'",
            keyField, fieldsJson, displayField, pendingReason, error?.take(1000), id)

    /** 服务重启:残留「映射推导中」的 PENDING 任务转 MAPPING_REVIEW(推导线程已随重启消亡,人工审核补线复活) */
    fun recoverMappingOnStartup(error: String): Int =
        jdbc.update("UPDATE compare_job SET pending_reason='MAPPING_REVIEW', error=? " +
            "WHERE status='PENDING' AND pending_reason='MAPPING_RUNNING'", error.take(1000))

    fun getJob(id: Long): JobRow? =
        jdbc.queryOne("$jobSelect WHERE j.id=?", id, mapper = jobMapper)

    /** 任务列表筛选条件(全部可空/空集合 = 不筛;matchMode 传 LEGACY 表示老任务「仅编码」即 match_mode IS NULL) */
    data class JobFilter(val kw: String? = null,
                         val status: List<String> = emptyList(),
                         val tagIds: List<Long> = emptyList(),
                         val datasourceId: Long? = null,
                         val matchMode: String? = null,
                         val compareMode: String? = null)

    /** 列表 WHERE 子句(含 " WHERE " 前缀,空条件为空串)与参数:计数与分页查询共用一份,保证总数与当前页同口径 */
    private fun jobWhere(includeArchived: Boolean, filter: JobFilter): Pair<String, List<Any?>> {
        val where = StringBuilder()
        val args = mutableListOf<Any?>()
        if (!includeArchived) where.append("j.archived=FALSE")

        fun and(cond: String) {
            where.append(if (where.isEmpty()) cond else " AND $cond")
        }

        filter.kw?.takeIf { it.isNotBlank() }?.let { kw ->
            and("(LOWER(j.name) LIKE ? OR LOWER(j.base_table) LIKE ? OR LOWER(i.file_name) LIKE ?)")
            val like = "%${kw.trim().lowercase()}%"
            args.add(like); args.add(like); args.add(like)
        }
        if (filter.status.isNotEmpty()) {
            and("j.status IN (${filter.status.joinToString(",") { "?" }})")
            args.addAll(filter.status)
        }
        filter.datasourceId?.let { and("j.base_datasource_id=?"); args.add(it) }
        filter.matchMode?.takeIf { it.isNotBlank() }?.let {
            // 仅编码口径含老任务(match_mode NULL/空串=老任务仅按编码对齐,V49)
            if (it == "LEGACY") and("(j.match_mode IS NULL OR j.match_mode='')")
            else { and("j.match_mode=?"); args.add(it) }
        }
        filter.compareMode?.takeIf { it.isNotBlank() }?.let {
            // 行级口径含老任务(compare_mode NULL=行级老任务兼容,V50)
            if (it == "ROW") and("(j.compare_mode='ROW' OR j.compare_mode IS NULL)")
            else { and("j.compare_mode=?"); args.add(it) }
        }
        // 表的标记:基准表或任一比对目标表打了所选标记之一即命中(多标记 OR;schema 空串口径对齐 table_tag)
        if (filter.tagIds.isNotEmpty()) {
            val inClause = filter.tagIds.joinToString(",") { "?" }
            and("(EXISTS(SELECT 1 FROM table_tag tt WHERE tt.tag_id IN ($inClause)" +
                " AND tt.datasource_id=j.base_datasource_id AND tt.db_name=j.base_db" +
                " AND tt.schema_name=COALESCE(j.base_schema,'') AND tt.table_name=j.base_table)" +
                " OR EXISTS(SELECT 1 FROM compare_target ct JOIN table_tag tt" +
                " ON tt.tag_id IN ($inClause) AND tt.datasource_id=ct.datasource_id" +
                " AND tt.db_name=ct.db_name AND tt.schema_name=COALESCE(ct.schema_name,'')" +
                " AND tt.table_name=ct.table_name WHERE ct.job_id=j.id))")
            args.addAll(filter.tagIds); args.addAll(filter.tagIds)
        }

        return (if (where.isEmpty()) "" else " WHERE $where") to args
    }

    /** 任务列表总数(与 listJobs 同口径;FROM 与 jobSelect 同带 compare_import 左联,kw 命中来源文件名) */
    fun countJobs(includeArchived: Boolean, filter: JobFilter = JobFilter()): Long {
        val (where, args) = jobWhere(includeArchived, filter)
        return jdbc.queryOne("SELECT COUNT(*) FROM compare_job j " +
            "LEFT JOIN compare_import i ON i.id = j.import_id" + where,
            *args.toTypedArray()) { rs -> rs.getLong(1) } ?: 0L
    }

    /** 任务列表:新的在前;includeArchived=false 时不返回已归档任务;filter 各维度下推 SQL AND 组合;
     *  page/size 均缺省 = 不分页(兼容老调用),否则 1 起页码 + LIMIT/OFFSET(与 diffsPage 同写法) */
    fun listJobs(includeArchived: Boolean, filter: JobFilter = JobFilter(),
                 page: Int? = null, size: Int? = null): List<JobRow> {
        val (where, args) = jobWhere(includeArchived, filter)
        var sql = "$jobSelect$where ORDER BY j.created_at DESC, j.id DESC"
        val allArgs = args.toMutableList()
        if (page != null && size != null) {
            sql += " LIMIT ? OFFSET ?"
            allArgs.add(size)
            allArgs.add((page - 1).toLong() * size)
        }
        return jdbc.query(sql, *allArgs.toTypedArray(), mapper = jobMapper)
    }

    /** 后台任务中心轮询:RUNNING 任务(跨全部库,id 升序;PENDING 是等用户操作的静止状态,不轮询) */
    fun listActiveJobs(): List<JobRow> =
        jdbc.query("$jobSelect WHERE j.status='RUNNING' ORDER BY j.id", mapper = jobMapper)

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

    /** 落目标行:fieldMappingJson 为人工字段映射(基准列名 → 目标列名)的 JSON 对象,空 = 按字段名自动匹配;
     *  identityJson 为目标级身份字段人工覆盖 {"keys":[...]}(V62),空 = 按任务级 keyFields ∩ 映射键推导 */
    fun insertTarget(jobId: Long, datasourceId: Long, dsName: String?, dbName: String, schemaName: String?,
                     tableName: String, fieldMappingJson: String? = null, identityJson: String? = null): Long =
        jdbc.insert("INSERT INTO compare_target(job_id, datasource_id, ds_name, db_name, schema_name, table_name, " +
            "field_mapping_json, identity_json, status) VALUES (?,?,?,?,?,?,?,?,'PENDING')",
            jobId, datasourceId, dsName, dbName, schemaName, tableName, fieldMappingJson, identityJson)

    fun listTargets(jobId: Long): List<TargetRow> =
        jdbc.query("SELECT * FROM compare_target WHERE job_id=? ORDER BY id", jobId, mapper = targetMapper)

    fun markTargetRunning(id: Long) {
        jdbc.update("UPDATE compare_target SET status='RUNNING' WHERE id=?", id)
    }

    /**
     * 目标比对完成:指标四项比率连同计数一次性落库
     * (targetCount=目标侧实际读到的总行数,含代理键行进 map 的身份列空行,见 V63 口径;
     * noKeyRows=其中身份列为空的行数:编码路不参与、名称/大模型可配对,透出到目标说明/导出差异原因);
     * code/name/aiMatchedCount 为三种对齐来源各自命中的对象数,三者之和 = matchedCount
     * (老口径只有 code 匹配,故三列留空时按 matchedCount 解读)
     */
    fun updateTargetStats(id: Long, baseCount: Int, targetCount: Int, matchedCount: Int, missingCount: Int,
                          extraCount: Int, fieldMismatchCount: Int, coverage: Double, fieldConsistency: Double,
                          completeness: Double, score: Double,
                          codeMatchedCount: Int? = null, nameMatchedCount: Int? = null,
                          aiMatchedCount: Int? = null, noKeyRows: Int = 0) {
        jdbc.update("UPDATE compare_target SET status='DONE', base_count=?, target_count=?, matched_count=?, " +
            "missing_count=?, extra_count=?, field_mismatch_count=?, coverage=?, field_consistency=?, " +
            "completeness=?, score=?, code_matched_count=?, name_matched_count=?, ai_matched_count=?, " +
            "no_key_rows=? WHERE id=?",
            baseCount, targetCount, matchedCount, missingCount, extraCount, fieldMismatchCount,
            coverage, fieldConsistency, completeness, score,
            codeMatchedCount, nameMatchedCount, aiMatchedCount, noKeyRows, id)
    }

    fun failTarget(id: Long, error: String) {
        jdbc.update("UPDATE compare_target SET status='FAILED', error=? WHERE id=?", error, id)
    }

    /** 回写基准表最新更新时间快照(比对执行时探测时间字段取 MAX;null = 没有可用时间字段/取数失败) */
    fun updateBaseDataUpdatedAt(jobId: Long, value: String?) {
        jdbc.update("UPDATE compare_job SET base_data_updated_at=? WHERE id=?", value, jobId)
    }

    /** 回写某目标表最新更新时间快照(口径同 [updateBaseDataUpdatedAt]) */
    fun updateTargetDataUpdatedAt(id: Long, value: String?) {
        jdbc.update("UPDATE compare_target SET data_updated_at=? WHERE id=?", value, id)
    }

    /** 全量替换目标字段映射(「字段审核」确认时落库;mappingJson 为 基准列名 → 目标列名 JSON,空 = 自动匹配) */
    fun updateTargetMapping(id: Long, mappingJson: String?) {
        jdbc.update("UPDATE compare_target SET field_mapping_json=? WHERE id=?", mappingJson, id)
    }

    /** 全量替换目标级身份字段覆盖(「字段审核」携带 identities 时落库;{"keys":[...]},null = 清除覆盖回落推导) */
    fun updateTargetIdentity(id: Long, identityJson: String?) {
        jdbc.update("UPDATE compare_target SET identity_json=? WHERE id=?", identityJson, id)
    }

    /**
     * 给已完成的目标追加说明(如大模型归一化补配部分批次失败):
     * 追加而不是覆盖,保留既有内容;error 列在 DONE 状态下仅作「需要人工关注的说明」用
     */
    fun appendTargetNote(id: Long, note: String) {
        jdbc.update("UPDATE compare_target SET error = CASE WHEN error IS NULL OR error='' THEN ? " +
            "ELSE error || ' | ' || ? END WHERE id=?", note.take(1000), note.take(1000), id)
    }

    /** 重跑前清空既有结果:tx 删差异明细 + 目标(目标行由调用方随后重建)+ 清基准表时间快照 */
    fun clearResults(jobId: Long) {
        jdbc.tx { conn ->
            conn.prepareStatement("UPDATE compare_job SET base_data_updated_at=NULL WHERE id=?").use { ps ->
                ps.setLong(1, jobId)
                ps.executeUpdate()
            }
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
}
