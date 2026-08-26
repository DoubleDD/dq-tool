package com.example.dq.repository

import java.time.LocalDate
import java.time.LocalDateTime

/** AI 调用用量流水:每次大模型调用成功记一行,供 Token/费用统计 */
class AiUsageRepository(private val jdbc: Jdbc) {

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

    data class Totals(
        val calls: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cost: Double,
    )

    fun insert(scene: String, model: String, promptTokens: Long, completionTokens: Long,
               totalTokens: Long, cost: Double, promptCost: Double, completionCost: Double,
               period: String, createdAt: LocalDateTime) {
        jdbc.update(
            """INSERT INTO ai_usage_log(scene, model, prompt_tokens, completion_tokens, total_tokens, cost, prompt_cost, completion_cost, period, created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            scene, model, promptTokens, completionTokens, totalTokens, cost, promptCost, completionCost, period, createdAt
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

    /** 最近 N 条明细(倒序) */
    fun recent(limit: Int): List<LogRow> =
        jdbc.query(
            """SELECT id, scene, model, prompt_tokens, completion_tokens, total_tokens, cost, period, created_at
               FROM ai_usage_log ORDER BY id DESC LIMIT ?""",
            limit,
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
}
