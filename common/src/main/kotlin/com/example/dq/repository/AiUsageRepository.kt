package com.example.dq.repository

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * AI 调用用量流水:每次大模型调用成功记一行,供 Token/费用统计。
 * 独立 H2 库(dqaiusage,与主库 dqconfig 分离):调用量大且含请求/响应内容;
 * 扫描标签在记录时快照(冗余 scan_label/scan_created_at),不跨库 join scan_job/data_source。
 */
class AiUsageRepository(private val jdbc: Jdbc) {

    /** 记录时快照的扫描标签(由服务层经主库 scan_job/data_source 解析) */
    data class ScanJobLabel(val label: String, val createdAt: LocalDateTime?)

    data class DayStat(
        val date: String,
        val calls: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cost: Double,
        /** 输入/输出分项费用;老流水无分项数据(列为 NULL)时聚合结果为 null */
        val promptCost: Double?,
        val completionCost: Double?,
    )

    data class SceneStat(
        val scene: String,
        val calls: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cost: Double,
    )

    data class LogRow(
        val id: Long,
        val scene: String,
        val model: String,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cost: Double,
        val period: String,
        val createdAt: LocalDateTime,
    )

    /** 按扫描任务聚合的一行(标签为记录时快照,任务/数据源被删时为空,展示层兜底) */
    data class ScanStat(
        val jobId: Long,
        val label: String?,
        val jobCreatedAt: LocalDateTime?,
        val calls: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cost: Double,
        val promptCost: Double?,
        val completionCost: Double?,
    )

    data class Totals(
        val calls: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cost: Double,
    )

    fun insert(scene: String, model: String, promptTokens: Long, completionTokens: Long,
               totalTokens: Long, cost: Double, promptCost: Double?, completionCost: Double?,
               period: String, createdAt: LocalDateTime, scanJobId: Long?, scanLabel: String?,
               scanCreatedAt: LocalDateTime?, requestContent: String?, responseContent: String?) {
        jdbc.update(
            """INSERT INTO ai_usage_log(scene, model, prompt_tokens, completion_tokens, total_tokens, cost, prompt_cost, completion_cost, period, created_at, scan_job_id, scan_label, scan_created_at, request_content, response_content)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            scene, model, promptTokens, completionTokens, totalTokens, cost, promptCost, completionCost,
            period, createdAt, scanJobId, scanLabel, scanCreatedAt, requestContent, responseContent
        )
    }

    /** [from, toExclusive) 内的按日聚合(只含当天有记录的日子,缺日由服务层补零) */
    fun daySeries(from: LocalDate, toExclusive: LocalDate): List<DayStat> =
        jdbc.query(
            """SELECT CAST(created_at AS DATE) AS d, COUNT(*) AS calls,
                      SUM(prompt_tokens) AS pt, SUM(completion_tokens) AS ct,
                      SUM(total_tokens) AS tt, SUM(cost) AS c,
                      SUM(prompt_cost) AS pc, SUM(completion_cost) AS cc
               FROM ai_usage_log
               WHERE created_at >= ? AND created_at < ?
               GROUP BY CAST(created_at AS DATE)""",
            from.atStartOfDay(), toExclusive.atStartOfDay(),
        ) { rs ->
            val pc = rs.getDouble("pc")
            val pcNull = rs.wasNull()
            val cc = rs.getDouble("cc")
            val ccNull = rs.wasNull()
            DayStat(
                rs.getObject("d", LocalDate::class.java).toString(),
                rs.getLong("calls"),
                rs.getLong("pt"),
                rs.getLong("ct"),
                rs.getLong("tt"),
                rs.getDouble("c"),
                if (pcNull) null else pc,
                if (ccNull) null else cc,
            )
        }

    /** [from, toExclusive) 内按场景聚合 */
    fun sceneSeries(from: LocalDate, toExclusive: LocalDate): List<SceneStat> =
        jdbc.query(
            """SELECT scene, COUNT(*) AS calls,
                      SUM(prompt_tokens) AS pt, SUM(completion_tokens) AS ct,
                      SUM(total_tokens) AS tt, SUM(cost) AS c
               FROM ai_usage_log
               WHERE created_at >= ? AND created_at < ?
               GROUP BY scene""",
            from.atStartOfDay(), toExclusive.atStartOfDay(),
        ) { rs ->
            SceneStat(
                rs.getString("scene"),
                rs.getLong("calls"),
                rs.getLong("pt"),
                rs.getLong("ct"),
                rs.getLong("tt"),
                rs.getDouble("c"),
            )
        }

    /** [from, toExclusive) 内汇总 */
    fun totals(from: LocalDate, toExclusive: LocalDate): Totals =
        jdbc.queryOne(
            """SELECT COUNT(*) AS calls, COALESCE(SUM(prompt_tokens),0) AS pt,
                      COALESCE(SUM(completion_tokens),0) AS ct, COALESCE(SUM(total_tokens),0) AS tt,
                      COALESCE(SUM(cost),0) AS c
               FROM ai_usage_log
               WHERE created_at >= ? AND created_at < ?""",
            from.atStartOfDay(), toExclusive.atStartOfDay(),
        ) { rs ->
            Totals(
                rs.getLong("calls"),
                rs.getLong("pt"),
                rs.getLong("ct"),
                rs.getLong("tt"),
                rs.getDouble("c"),
            )
        } ?: Totals(0, 0, 0, 0, 0.0)

    /** [from, toExclusive) 内按扫描任务聚合(只含关联了 scan_job_id 的调用,按扫描时间升序) */
    fun scanSeries(from: LocalDate, toExclusive: LocalDate): List<ScanStat> =
        jdbc.query(
            """SELECT scan_job_id AS job_id,
                      MIN(scan_label) AS label, MIN(scan_created_at) AS job_created,
                      COUNT(*) AS calls,
                      SUM(prompt_tokens) AS pt, SUM(completion_tokens) AS ct,
                      SUM(total_tokens) AS tt, SUM(cost) AS c,
                      SUM(prompt_cost) AS pc, SUM(completion_cost) AS cc
               FROM ai_usage_log
               WHERE scan_job_id IS NOT NULL AND created_at >= ? AND created_at < ?
               GROUP BY scan_job_id
               ORDER BY job_created, job_id""",
            from.atStartOfDay(), toExclusive.atStartOfDay(),
        ) { rs ->
            val pc = rs.getDouble("pc")
            val pcNull = rs.wasNull()
            val cc = rs.getDouble("cc")
            val ccNull = rs.wasNull()
            val jobCreated = rs.getTimestamp("job_created")
            ScanStat(
                rs.getLong("job_id"),
                rs.getString("label"),
                jobCreated?.toLocalDateTime(),
                rs.getLong("calls"),
                rs.getLong("pt"),
                rs.getLong("ct"),
                rs.getLong("tt"),
                rs.getDouble("c"),
                if (pcNull) null else pc,
                if (ccNull) null else cc,
            )
        }

    /** 明细分页(倒序),offset/limit 已由调用方钳制 */
    fun recentPage(offset: Int, limit: Int): List<LogRow> =
        jdbc.query(
            """SELECT id, scene, model, prompt_tokens, completion_tokens, total_tokens, cost, period, created_at
               FROM ai_usage_log ORDER BY id DESC LIMIT ? OFFSET ?""",
            limit, offset,
        ) { rs ->
            LogRow(
                rs.getLong("id"),
                rs.getString("scene"),
                rs.getString("model"),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getLong("total_tokens"),
                rs.getDouble("cost"),
                rs.getString("period"),
                rs.getObject("created_at", LocalDateTime::class.java),
            )
        }

    /** 明细总数(分页用) */
    fun countAll(): Long =
        jdbc.queryOne("SELECT COUNT(*) AS c FROM ai_usage_log") { rs -> rs.getLong("c") } ?: 0L

    /**
     * 老库(主库 dqconfig)ai_usage_log 一次性搬迁:新库为空且老表可读时整体复制,
     * 保留原 created_at/费用快照;老表无请求/响应内容列,置 NULL;扫描标签经 resolver 现解析。
     * 老表不存在(全新部署)或读取失败静默跳过。返回搬迁条数。
     */
    fun migrateLegacyIfEmpty(legacy: Jdbc, resolver: (Long) -> ScanJobLabel?): Int {
        if (countAll() > 0) {
            return 0
        }
        data class LegacyRow(
            val scene: String, val model: String, val promptTokens: Long, val completionTokens: Long,
            val totalTokens: Long, val cost: Double, val promptCost: Double?, val completionCost: Double?,
            val period: String, val createdAt: LocalDateTime, val scanJobId: Long?,
        )
        val rows: List<LegacyRow> = try {
            legacy.query(
                """SELECT scene, model, prompt_tokens, completion_tokens, total_tokens, cost,
                          prompt_cost, completion_cost, period, created_at, scan_job_id
                   FROM ai_usage_log""",
            ) { rs ->
                val pc = rs.getDouble("prompt_cost")
                val pcNull = rs.wasNull()
                val cc = rs.getDouble("completion_cost")
                val ccNull = rs.wasNull()
                val jobId = rs.getLong("scan_job_id")
                val jobIdNull = rs.wasNull()
                LegacyRow(
                    rs.getString("scene"), rs.getString("model"),
                    rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getLong("total_tokens"),
                    rs.getDouble("cost"), if (pcNull) null else pc, if (ccNull) null else cc,
                    rs.getString("period"), rs.getObject("created_at", LocalDateTime::class.java),
                    if (jobIdNull) null else jobId,
                )
            }
        } catch (e: Exception) {
            log.info("老库 AI 用量表不可读,跳过搬迁: {}", e.message)
            return 0
        }
        if (rows.isEmpty()) {
            return 0
        }
        for (r in rows) {
            val label = r.scanJobId?.let {
                try { resolver(it) } catch (e: Exception) { null }
            }
            insert(
                r.scene, r.model, r.promptTokens, r.completionTokens, r.totalTokens,
                r.cost, r.promptCost, r.completionCost, r.period, r.createdAt,
                r.scanJobId, label?.label, label?.createdAt, null, null,
            )
        }
        log.info("老库 AI 用量流水搬迁完成: {} 条", rows.size)
        return rows.size
    }

    private companion object {
        val log = LoggerFactory.getLogger(AiUsageRepository::class.java)
    }
}
