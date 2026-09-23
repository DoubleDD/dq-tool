package com.example.dq.service

import com.example.dq.model.AiScene
import com.example.dq.model.BatchAiTagResult
import com.example.dq.model.ColumnMeta
import com.example.dq.model.ScanColumnView
import com.example.dq.model.TagSource
import com.example.dq.repository.TagRepository
import org.slf4j.LoggerFactory

/**
 * 批量 AI 打标(表列表「批量打标」弹窗 AI 页签):前端 2 worker 逐表调用,每张表把勾选候选标记清单
 * 交给大模型选一个最合适的并落标(source=AI)。
 * 与扫描后 [AutoTagService] 的差异:非扫描场景(无 scanJobId、不经扫描队列、无熔断),
 * 上下文只用 表注释 + 字段(含字段注释),不传 AI 表描述、不抽样业务数据;
 * 已有同标记(任意来源)不重复打(保留原关系来源),备份表直接跳过不调大模型;
 * 候选 = 勾选的标记 ∩ 可用于 AI 打标的 USER 标记(AI 类型,见 TagService.aiCandidates)。
 */
class BatchAiTagService(
    private val aiConfigService: AiConfigService,
    aiService: AiService,
    private val tagService: TagService,
    private val tagRepo: TagRepository,
    private val metadataService: MetadataService,
    /** LLM 调用点(AiService 是 final class,测试经此注入 fake);场景固定为自动打标,无扫描任务关联 */
    private val chat: (AiConfigService.Config, String, String, Long?) -> String =
        { c, s, u, jobId -> aiService.chat(c, s, u, AiScene.AUTO_TAG, jobId) },
) {

    /**
     * 单表 AI 打标:备份表跳过;未配置大模型抛 IllegalStateException;
     * 无候选(勾选 ∩ AI 类型为空)或表不存在抛 IllegalArgumentException;
     * 模型未匹配返回不打标结果;命中且表上已有同标记(任意来源)跳过保留原关系。
     */
    fun tagTable(datasourceId: Long, database: String?, schema: String, table: String,
                 tagIds: List<Long>): BatchAiTagResult {
        // 备份表绝不参与 AI 打标(「备份表」系统标记由扫描联动维护)
        if (BackupTableRule.isBackupTable(table)) {
            return BatchAiTagResult(skipped = true, reason = "备份表不参与 AI 打标")
        }
        val config = requireUsable(tagIds)
        val candidates = resolveCandidates(tagIds)
        val db = database ?: ""
        val stat = metadataService.listTables(datasourceId, database, schema)
            .firstOrNull { it.name == table }
            ?: throw IllegalArgumentException("表不存在:$table")
        val columns = metadataService.listTableColumns(datasourceId, database, schema, table)
        val answer = chat(config, AutoTagService.SYSTEM_PROMPT, AutoTagService.buildClassifyPrompt(
            candidates.map { it.name to it.description }, table, stat.comment, null,
            columns.map { it.toScanColumnView() }, emptyList()), null)
        val tagName = AutoTagService.parseTag(answer, candidates.map { it.name })
            ?: return BatchAiTagResult(reason = "模型未匹配到合适的标记")
        val tag = candidates.first { it.name == tagName }
        // 表已有同一标记(任意来源)时不重复打:MERGE 会把既有关系来源改写,保留原关系不动
        val currentTags = tagRepo.tableTagsBySchema(datasourceId, db, schema)[table].orEmpty()
        if (currentTags.any { it.id == tag.id }) {
            return BatchAiTagResult(skipped = true, tagName = tagName, reason = "表已有该标记,跳过")
        }
        tagRepo.ensureTableTag(tag.id, datasourceId, db, schema, table, TagSource.AI)
        log.info("批量 AI 打标 dsId={} table={} -> {}", datasourceId, table, tagName)
        return BatchAiTagResult(applied = true, tagName = tagName)
    }

    /**
     * 可用性快速校验(批量任务提交与单表打标共用):未配置大模型抛 IllegalStateException;
     * 勾选标记 ∩ AI 类型候选为空抛 IllegalArgumentException;返回校验通过的 AI 配置。
     */
    fun requireUsable(tagIds: List<Long>): AiConfigService.Config {
        val config = aiConfigService.requireConfig()
        if (resolveCandidates(tagIds).isEmpty()) {
            throw IllegalArgumentException("没有可用的候选标记")
        }
        return config
    }

    /** 候选 = 勾选的标记 ∩ 可用于 AI 打标的 USER 标记(AI 类型,见 TagService.aiCandidates) */
    private fun resolveCandidates(tagIds: List<Long>) =
        tagService.aiCandidates().filter { it.id in tagIds }

    /** ColumnMeta → ScanColumnView:统计字段对分类 prompt 无意义置 0(prompt 只取 名称/展示类型/注释) */
    private fun ColumnMeta.toScanColumnView(): ScanColumnView =
        ScanColumnView.of(name, displayType, comment, nullable, defaultValue,
            if (primaryKey) "PK" else "", 0, 0, 0, 0)

    private companion object {
        private val log = LoggerFactory.getLogger(BatchAiTagService::class.java)
    }
}
