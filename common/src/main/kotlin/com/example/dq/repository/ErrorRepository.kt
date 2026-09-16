package com.example.dq.repository

import com.example.dq.model.ErrorEvent
import com.example.dq.model.ErrorPage
import com.example.dq.model.ErrorQuery
import com.example.dq.model.ErrorRecord
import com.example.dq.model.ErrorStats
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 错误中心仓储:error_record 单表读写。
 *
 * 聚合口径:fingerprint 唯一,已存在则 `occurrences + 1` 并刷新最近一次的
 * message/detail/context/last_seen;首次写入 INSERT。upsert 由 [com.example.dq.service.ErrorCenterService]
 * 的单写线程串行调用,唯一键冲突只可能出现在极端竞态,冲突时回退为累加。
 */
class ErrorRepository(private val jdbc: Jdbc) {

    private val selectColumns = """
        SELECT id, fingerprint, source, kind, level, message, detail, context, logger, thread, route,
               occurrences, first_seen, last_seen, app_version, status, note
        FROM error_record
    """.trimIndent()

    private val updateExisting = """
        UPDATE error_record
        SET message = ?, detail = ?, context = ?, logger = ?, thread = ?, route = ?,
            level = ?, kind = ?, last_seen = ?, occurrences = occurrences + 1, app_version = ?
        WHERE fingerprint = ?
    """.trimIndent()

    private val insertNew = """
        INSERT INTO error_record (fingerprint, source, kind, level, message, detail, context, logger, thread, route,
                                  occurrences, first_seen, last_seen, app_version, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'OPEN')
    """.trimIndent()

    /**
     * 写入一条错误事件(按指纹聚合)。
     * @return true = 新建了一条聚合记录;false = 累加到已有记录
     */
    fun upsert(event: ErrorEvent, fingerprint: String, appVersion: String?): Boolean {
        val ts = Timestamp.valueOf(event.occurredAt)
        val message = truncate(event.message, 2000)
        val updated = jdbc.update(
            updateExisting,
            message, event.detail, event.context, event.logger, event.thread, event.route,
            event.level.name, event.kind, ts, appVersion, fingerprint,
        )
        if (updated > 0) return false
        return try {
            jdbc.update(
                insertNew,
                fingerprint, event.source.name, event.kind, event.level.name, message,
                event.detail, event.context, event.logger, event.thread, event.route,
                ts, ts, appVersion,
            )
            true
        } catch (e: SQLException) {
            // 唯一键冲突(并发/重启竞态):退化为累加;仍失败则原样抛出交给上层记录
            val retried = jdbc.update(
                updateExisting,
                message, event.detail, event.context, event.logger, event.thread, event.route,
                event.level.name, event.kind, ts, appVersion, fingerprint,
            )
            if (retried == 0) throw e
            false
        }
    }

    fun findById(id: Long): ErrorRecord? =
        jdbc.queryOne("$selectColumns WHERE id = ?", id, mapper = ::map)

    /** 按 id 批量取(导出所选行用;ids 为空返回空表,顺序按最近发生时间倒序) */
    fun findByIds(ids: List<Long>): List<ErrorRecord> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.query(
            "$selectColumns WHERE id IN (${ids.joinToString(",") { "?" }}) ORDER BY last_seen DESC, id DESC",
            *ids.toTypedArray(),
            mapper = ::map,
        )
    }

    /** 按条件分页查询(最近发生时间倒序) */
    fun query(q: ErrorQuery): ErrorPage {
        val (where, args) = buildWhere(q)
        val total = jdbc.queryOne("SELECT COUNT(*) FROM error_record $where", *args.toTypedArray()) { it.getLong(1) } ?: 0L
        val size = q.size.coerceIn(1, 200)
        val page = q.page.coerceAtLeast(1)
        val offset = (page - 1).toLong() * size
        val items = jdbc.query(
            "$selectColumns $where ORDER BY last_seen DESC, id DESC LIMIT ? OFFSET ?",
            *(args + listOf(size, offset)).toTypedArray(),
            mapper = ::map,
        )
        return ErrorPage(total, page, size, items)
    }

    /** 统计卡片:总量 / 未处理 / 今日有发生 / 按来源 / 按级别 */
    fun stats(): ErrorStats {
        val total = count("SELECT COUNT(*) FROM error_record")
        val open = count("SELECT COUNT(*) FROM error_record WHERE status = 'OPEN'")
        val todayNew = jdbc.queryOne(
            "SELECT COUNT(*) FROM error_record WHERE last_seen >= ?",
            Timestamp.valueOf(LocalDate.now().atStartOfDay()),
        ) { it.getLong(1) } ?: 0L
        return ErrorStats(
            total = total,
            open = open,
            todayNew = todayNew,
            bySource = groupCount("SELECT source, COUNT(*) FROM error_record GROUP BY source"),
            byLevel = groupCount("SELECT level, COUNT(*) FROM error_record GROUP BY level"),
        )
    }

    /** 批量改状态;note 非空时一并写入(为空保留原备注) */
    fun updateStatus(ids: List<Long>, status: String, note: String? = null): Int {
        var affected = 0
        for (id in ids) {
            affected += if (note.isNullOrBlank()) {
                jdbc.update("UPDATE error_record SET status = ? WHERE id = ?", status, id)
            } else {
                jdbc.update("UPDATE error_record SET status = ?, note = ? WHERE id = ?", status, note, id)
            }
        }
        return affected
    }

    fun delete(ids: List<Long>): Int {
        var affected = 0
        for (id in ids) affected += jdbc.update("DELETE FROM error_record WHERE id = ?", id)
        return affected
    }

    /** 按筛选条件清空(无条件 = 清空全部);返回删除条数 */
    fun clear(q: ErrorQuery): Int {
        val (where, args) = buildWhere(q)
        return jdbc.update("DELETE FROM error_record $where", *args.toTypedArray())
    }

    /** 删除 last_seen 早于 before 的记录,返回删除条数 */
    fun purgeBefore(before: LocalDateTime): Int =
        jdbc.update("DELETE FROM error_record WHERE last_seen < ?", Timestamp.valueOf(before))

    /** 超上限时删除最旧的记录(保留 max 条,按 last_seen 倒序) */
    fun trimExcess(max: Int): Int {
        if (max <= 0) return 0
        return jdbc.update(
            """DELETE FROM error_record WHERE id NOT IN (
                   SELECT id FROM error_record ORDER BY last_seen DESC, id DESC LIMIT ?
               )""",
            max,
        )
    }

    private fun buildWhere(q: ErrorQuery): Pair<String, List<Any?>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (q.sources.isNotEmpty()) {
            clauses += "source IN (${q.sources.joinToString(",") { "?" }})"
            args.addAll(q.sources)
        }
        if (q.levels.isNotEmpty()) {
            clauses += "level IN (${q.levels.joinToString(",") { "?" }})"
            args.addAll(q.levels)
        }
        if (q.statuses.isNotEmpty()) {
            clauses += "status IN (${q.statuses.joinToString(",") { "?" }})"
            args.addAll(q.statuses)
        }
        if (!q.keyword.isNullOrBlank()) {
            clauses += "(LOWER(message) LIKE ? OR LOWER(kind) LIKE ? OR LOWER(route) LIKE ? OR LOWER(detail) LIKE ?)"
            val kw = "%" + q.keyword.trim().lowercase() + "%"
            args.addAll(listOf(kw, kw, kw, kw))
        }
        q.from?.let {
            clauses += "last_seen >= ?"
            args += Timestamp.valueOf(it)
        }
        q.to?.let {
            clauses += "last_seen <= ?"
            args += Timestamp.valueOf(it)
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to args
    }

    private fun count(sql: String): Long = jdbc.queryOne(sql) { it.getLong(1) } ?: 0L

    private fun groupCount(sql: String): Map<String, Long> =
        jdbc.query(sql) { it.getString(1) to it.getLong(2) }.toMap()

    private fun map(rs: ResultSet) = ErrorRecord(
        id = rs.getLong("id"),
        fingerprint = rs.getString("fingerprint"),
        source = rs.getString("source"),
        kind = rs.getString("kind"),
        level = rs.getString("level"),
        message = rs.getString("message"),
        detail = rs.getString("detail"),
        context = rs.getString("context"),
        logger = rs.getString("logger"),
        thread = rs.getString("thread"),
        route = rs.getString("route"),
        occurrences = rs.getInt("occurrences"),
        firstSeen = rs.getTimestamp("first_seen").toLocalDateTime(),
        lastSeen = rs.getTimestamp("last_seen").toLocalDateTime(),
        appVersion = rs.getString("app_version"),
        status = rs.getString("status"),
        note = rs.getString("note"),
    )

    private fun truncate(text: String, max: Int): String = if (text.length <= max) text else text.take(max)
}
