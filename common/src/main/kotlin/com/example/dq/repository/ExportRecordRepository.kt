package com.example.dq.repository

import com.example.dq.model.ExportCenterItem
import com.example.dq.model.ExportCenterPage
import com.example.dq.model.ExportKind
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * 导出中心(V66 登记 / V67 push / **点击即登记的状态机**,复用 V66 status 列无新迁移):
 * 点击导出先落一条 RUNNING(导出中心立刻可见),落盘/失败/重启中断各有终态。
 * 单表全量;筛选/分页直接作用本表。rel_path 为相对数据目录的详细路径,打开/定位的唯一依据。
 * 关联口径:同步类按 file_name(landed 回填)或 params.path(失败标记);异步任务按 params.key。
 */
class ExportRecordRepository(private val jdbc: Jdbc) {

    fun insert(kind: String, title: String, fileName: String?, fileSize: Long?, paramsJson: String?,
               relPath: String?, status: String, checksum: String? = null): Long =
        jdbc.insert("INSERT INTO export_record(kind,title,file_name,file_size,params_json,rel_path,status,checksum) " +
            "VALUES (?,?,?,?,?,?,?,?)", kind, title, fileName, fileSize, paramsJson, relPath, status, checksum)

    fun delete(id: Long): Int = jdbc.update("DELETE FROM export_record WHERE id=?", id)

    /** 文件名关联最新一条 RUNNING 登记(landed 回填用;单用户工具,并发同名取最新即可) */
    fun findLatestRunningByFileName(fileName: String): Long? =
        jdbc.queryOne("SELECT id FROM export_record WHERE file_name=? AND status='RUNNING' ORDER BY id DESC LIMIT 1",
            fileName) { rs -> rs.getLong(1) }

    /** params_json 精确匹配(序列化格式固定,{"key":"…"}/{"path":"…"} 两种形态),取最新一条 */
    fun findLatestByParams(paramsJson: String): Long? =
        jdbc.queryOne("SELECT id FROM export_record WHERE params_json=? ORDER BY id DESC LIMIT 1",
            paramsJson) { rs -> rs.getLong(1) }

    /** 落盘成功终态:回填 rel_path + 实测大小 + SHA-256 + SUCCESS */
    fun updateLanded(id: Long, relPath: String, fileSize: Long?, checksum: String?) {
        jdbc.update("UPDATE export_record SET rel_path=?, file_size=?, checksum=?, status='SUCCESS' WHERE id=?",
            relPath, fileSize, checksum, id)
    }

    /** 终态更新:成功(补文件名/路径/大小/校验和)或失败(带 error);缺省字段不动已填值 */
    fun updateFinal(id: Long, status: String, fileName: String?, relPath: String?, fileSize: Long?,
                    checksum: String?, error: String?) {
        jdbc.update("UPDATE export_record SET status=?, file_name=COALESCE(?, file_name), " +
            "rel_path=COALESCE(?, rel_path), file_size=COALESCE(?, file_size), " +
            "checksum=COALESCE(?, checksum), error=? WHERE id=?",
            status, fileName, relPath, fileSize, checksum, error, id)
    }

    /** 服务重启:残留 RUNNING 一律 FAILED(导出执行体已随 JVM 消亡) */
    fun failRunning(error: String): Int =
        jdbc.update("UPDATE export_record SET status='FAILED', error=? WHERE status='RUNNING'", error)

    /** 分页列表:kind 等值、keyword 对 title/file_name LIKE、created_at 起止(含当日由 service 侧 +1 天) */
    fun page(kind: String?, keyword: String?, start: LocalDateTime?, endExclusive: LocalDateTime?,
             page: Int, size: Int): ExportCenterPage {
        val where = StringBuilder()
        val args = mutableListOf<Any?>()
        if (!kind.isNullOrBlank()) {
            where.append(if (where.isEmpty()) " WHERE" else " AND").append(" kind=?")
            args.add(kind)
        }
        if (!keyword.isNullOrBlank()) {
            where.append(if (where.isEmpty()) " WHERE" else " AND")
                .append(" (LOWER(title) LIKE ? OR LOWER(file_name) LIKE ?)")
            val like = "%${keyword.lowercase()}%"
            args.add(like); args.add(like)
        }
        if (start != null) {
            where.append(if (where.isEmpty()) " WHERE" else " AND").append(" created_at>=?")
            args.add(Timestamp.valueOf(start))
        }
        if (endExclusive != null) {
            where.append(if (where.isEmpty()) " WHERE" else " AND").append(" created_at<?")
            args.add(Timestamp.valueOf(endExclusive))
        }

        val total = jdbc.queryOne("SELECT COUNT(*) FROM export_record$where", *args.toTypedArray()) { rs -> rs.getLong(1) } ?: 0L
        val rows = jdbc.query(
            "SELECT * FROM export_record$where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            *args.toMutableList().apply { add(size); add((page - 1L) * size) }.toTypedArray(),
            mapper = itemMapper)
        return ExportCenterPage(rows, total, page, size)
    }

    private val itemMapper: (ResultSet) -> ExportCenterItem = { rs ->
        val kind = rs.getString("kind")
        val parsedKind = ExportKind.parse(kind) ?: ExportKind.SCAN_EXCEL
        ExportCenterItem(
            id = "$kind:${rs.getLong("id")}",
            kind = parsedKind,
            title = rs.getString("title"),
            fileName = rs.getString("file_name"),
            // 用 getObject 取可空列:wasNull() 会被后续列读取清掉,不能隔行用
            fileSize = rs.getObject("file_size") as Long?,
            storage = if (parsedKind == ExportKind.REPORT_DOCX || parsedKind == ExportKind.SAMPLE_ZIP ||
                parsedKind == ExportKind.COMPARE_XLSX) "DISK" else "NONE",
            relPath = rs.getString("rel_path"),
            checksum = rs.getString("checksum"),
            status = rs.getString("status"),
            error = rs.getString("error"),
            createdAt = rs.getTimestamp("created_at")?.toLocalDateTime(),
            finishedAt = null)
    }
}
