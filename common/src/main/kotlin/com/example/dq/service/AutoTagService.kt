package com.example.dq.service

import com.example.dq.dialect.DialectFactory
import com.example.dq.model.ScanColumnView
import com.example.dq.model.TagKind
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 扫描后 AI 自动打标:表 DONE 后由 ChunkRunner 提交,独立守护线程池(2 worker)异步执行,绝不阻塞扫描 worker。
 * 上下文:表注释 / 字段注释 / AI 表描述;三者全空且表非空时,抽样业务数据(前 20 列、100 行、
 * 单元格截断 100 字符)一并发给大模型 —— 注意这超出了「只发元数据」的口径,复选框默认勾选即授权。
 * 只增不删(幂等 ensureTableTag);表已有任一 USER 标记、未配置大模型、无候选标记时静默跳过;
 * 同一 job 内首次 LLM 调用失败后熔断,该 job 剩余表不再调用(内存 Set,不持久化)。
 */
class AutoTagService(
    private val aiConfigService: AiConfigService,
    aiService: AiService,
    private val tagService: TagService,
    private val tagRepo: TagRepository,
    private val scanRepo: ScanRepository,
    private val tableDocRepo: TableDocRepository,
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    /** LLM 调用点(AiService 是 final class,测试经此注入 fake) */
    private val chat: (AiConfigService.Config, String, String) -> String =
        { c, s, u -> aiService.chat(c, s, u) },
) {

    private val executor = Executors.newFixedThreadPool(TAG_WORKERS) { r ->
        Thread(r, "dq-ai-tag-worker").apply { isDaemon = true }
    }

    /** 已熔断的任务(LLM 首次调用失败后,该 job 剩余表直接跳过) */
    private val disabledJobs = ConcurrentHashMap.newKeySet<Long>()

    /** 表 DONE 后由 ChunkRunner 调用;开关关闭或本 job 已熔断时直接忽略 */
    fun submit(jobId: Long, scanTableId: Long) {
        val job = scanRepo.findJob(jobId) ?: return
        if (!job.autoTag || disabledJobs.contains(jobId)) {
            return
        }
        executor.execute { runSafely(job, scanTableId) }
    }

    /** 队列任务体;internal 以便单测绕过队列同步驱动 */
    internal fun runSafely(job: ScanRepository.JobRow, scanTableId: Long) {
        if (disabledJobs.contains(job.id)) {
            return
        }
        try {
            autoTag(job, scanTableId)
        } catch (e: Exception) {
            log.warn("AI 自动打标失败 jobId={} scanTableId={}: {}", job.id, scanTableId, e.message)
        }
    }

    private fun autoTag(job: ScanRepository.JobRow, scanTableId: Long) {
        val config = aiConfigService.findConfig()
        if (config == null) {
            log.debug("AI 自动打标跳过:未配置大模型 jobId={}", job.id)
            return
        }
        val candidates = tagService.list().filter { it.kind == TagKind.USER }
        if (candidates.isEmpty()) {
            log.debug("AI 自动打标跳过:无候选 USER 标记 jobId={}", job.id)
            return
        }
        val table = scanRepo.findScanTable(scanTableId) ?: return
        val tableName = table.tableName ?: return
        // table_tag 的 db_name 空串兜底口径与空表标记联动一致
        val dbName = job.dbName ?: ""
        // 不覆盖用户标记:已有任一 USER 标记的表跳过(也避免重复扫描累积陈旧标记)
        if (tagRepo.hasUserTag(job.datasourceId, dbName, job.schemaName, tableName)) {
            log.debug("AI 自动打标跳过:表已有 USER 标记 jobId={} table={}", job.id, tableName)
            return
        }
        val columns = scanRepo.listScanColumns(scanTableId)
        val doc = tableDocRepo.findBySchema(job.datasourceId, dbName, job.schemaName)[tableName]

        // 无文本基础(表注释/字段注释/AI 描述全空)且表非空时,抽样业务数据作分类上下文;
        // 抽样失败退化为仅表名/字段名,不影响打标流程
        var sampleRows = emptyList<List<String>>()
        val noText = table.comment.isNullOrBlank() && doc.isNullOrBlank() &&
                columns.all { it.columnComment.isNullOrBlank() }
        if (noText && (table.totalRows ?: 0L) > 0) {
            try {
                sampleRows = sampleRows(job, tableName, columns.mapNotNull { it.columnName })
            } catch (e: Exception) {
                log.warn("AI 自动打标抽样失败,退化为仅表名/字段名 jobId={} table={}: {}", job.id, tableName, e.message)
            }
        }

        val answer: String
        try {
            answer = chat(config, SYSTEM_PROMPT, buildClassifyPrompt(
                candidates.map { it.name }, tableName, table.comment, doc, columns, sampleRows))
        } catch (e: Exception) {
            disabledJobs.add(job.id)
            log.warn("AI 自动打标调用大模型失败,本任务剩余表跳过 jobId={} table={}: {}", job.id, tableName, e.message)
            return
        }
        val tagName = parseTag(answer, candidates.map { it.name }) ?: return
        val tag = candidates.first { it.name == tagName }
        tagRepo.ensureTableTag(tag.id, job.datasourceId, dbName, job.schemaName, tableName)
        log.info("AI 自动打标 jobId={} table={} -> {}", job.id, tableName, tagName)
    }

    /** 借连接抽样前 20 列、最多 100 行;单元格 toString 后截断 100 字符 */
    private fun sampleRows(job: ScanRepository.JobRow, tableName: String, columnNames: List<String>): List<List<String>> {
        val ds = dataSourceService.get(job.datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)
        val rows = ArrayList<List<String>>()
        dataSourceService.getConnection(job.datasourceId).use { conn ->
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(job.datasourceId, job.dbName))
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    dialect.sampleRowsSql(job.schemaName, tableName,
                        columnNames.take(MAX_SAMPLE_COLUMNS), SAMPLE_ROWS)
                ).use { rs ->
                    val colCount = rs.metaData.columnCount
                    while (rs.next()) {
                        val row = ArrayList<String>(colCount)
                        for (i in 1..colCount) {
                            row.add(truncate(rs.getObject(i)?.toString(), MAX_CELL_CHARS))
                        }
                        rows.add(row)
                    }
                }
            }
        }
        return rows
    }

    companion object {
        private val log = LoggerFactory.getLogger(AutoTagService::class.java)

        /** 打标 worker 数:与扫描并行推进,LLM 调用为长阻塞请求,2 并发与前端批量生成描述同口径 */
        const val TAG_WORKERS = 2

        /** 抽样口径:前 20 列、100 行、单元格截断 100 字符 */
        const val MAX_SAMPLE_COLUMNS = 20
        const val SAMPLE_ROWS = 100
        const val MAX_CELL_CHARS = 100

        /** 发给模型的字段数上限,与 AiService.MAX_PROMPT_COLUMNS 同口径 */
        const val MAX_PROMPT_COLUMNS = 100

        const val SYSTEM_PROMPT = "从给定标记列表中选择最合适的一个,只输出标记名本身;没有合适的输出 NONE。"

        /** 拼分类 prompt:候选标记清单 + 表名/注释/AI 描述/字段 + 抽样数据 Markdown;纯函数便于单测 */
        @JvmStatic
        fun buildClassifyPrompt(
            candidateTags: List<String>,
            tableName: String,
            tableComment: String?,
            tableDoc: String?,
            columns: List<ScanColumnView>,
            sampleRows: List<List<String>>,
        ): String {
            val sb = StringBuilder()
            sb.append("候选标记:\n")
            for (name in candidateTags) {
                sb.append("- ").append(name).append('\n')
            }
            sb.append("\n表名:").append(tableName).append('\n')
            if (!tableComment.isNullOrBlank()) {
                sb.append("表注释:").append(tableComment).append('\n')
            }
            if (!tableDoc.isNullOrBlank()) {
                sb.append("表描述:").append(tableDoc).append('\n')
            }
            sb.append("字段(共 ").append(columns.size).append(" 个):\n")
            val limit = minOf(columns.size, MAX_PROMPT_COLUMNS)
            for (i in 0 until limit) {
                val c = columns[i]
                sb.append("- ").append(c.columnName)
                if (!c.columnType.isNullOrBlank()) {
                    sb.append(' ').append(c.columnType)
                }
                if (!c.columnComment.isNullOrBlank()) {
                    sb.append(" — ").append(c.columnComment)
                }
                sb.append('\n')
            }
            if (columns.size > limit) {
                sb.append("...(其余 ").append(columns.size - limit).append(" 个字段省略)\n")
            }
            if (sampleRows.isNotEmpty()) {
                val colCount = sampleRows[0].size
                val names = columns.take(MAX_SAMPLE_COLUMNS).mapNotNull { it.columnName }
                val header = if (names.size == colCount) names else (1..colCount).map { "col$it" }
                sb.append("抽样数据(前 ").append(sampleRows.size).append(" 行):\n")
                sb.append("| ").append(header.joinToString(" | ")).append(" |\n")
                sb.append("|").append(" --- |".repeat(colCount)).append('\n')
                for (row in sampleRows) {
                    sb.append("| ").append(row.joinToString(" | ") { cell(it) }).append(" |\n")
                }
            }
            sb.append("\n请从候选标记中选择最合适的一个。")
            return sb.toString()
        }

        /** 解析模型回答:trim + 去引号后精确匹配候选标记;NONE 或无匹配返回 null(不打标) */
        @JvmStatic
        fun parseTag(answer: String, candidateTags: List<String>): String? {
            val a = answer.trim().trim('"', '\'', '“', '”').trim()
            if (a.equals("NONE", ignoreCase = true)) {
                return null
            }
            return candidateTags.firstOrNull { it == a }
        }

        /** Markdown 单元格转义:竖线与换行会破坏表格结构 */
        private fun cell(value: String): String =
            value.replace("|", "\\|").replace("\n", " ").replace("\r", " ")

        private fun truncate(s: String?, max: Int): String =
            if (s == null) "" else if (s.length <= max) s else s.substring(0, max)
    }
}
