package com.example.dq.service

import com.example.dq.model.ColumnMeta
import com.example.dq.model.TableDocView
import com.example.dq.model.TableStat
import com.example.dq.repository.TableDocRepository
import java.sql.SQLException
import java.time.LocalDateTime

/** AI 表说明:生成(结构元数据走 MetadataService 缓存优先 → 大模型)与查询(本地 H2) */
class TableDocService(
    private val repository: TableDocRepository,
    private val aiConfigService: AiConfigService,
    private val aiService: AiService,
    private val metadataService: MetadataService,
) {

    /** 表列表页展示用:表名 -> 说明,本地查询不连业务库 */
    fun list(datasourceId: Long, database: String?, schema: String): Map<String, String> =
        repository.findBySchema(datasourceId, normalizeDb(database), schema)

    /**
     * 生成单表说明并落库;库差异只经 dialect(收敛在 MetadataService),无库特定分支;
     * 扫描触发的生成传 scanJobId 关联用量统计。
     *
     * 表/字段结构走 [MetadataService] 的缓存优先路径:首次联网访问从原始库回源并即时落 H2 缓存,
     * 数据源不可达时若有缓存则降级读缓存(与元数据浏览同一口径),不再直接独占业务库连接。
     */
    @Throws(SQLException::class)
    @JvmOverloads
    fun generate(datasourceId: Long, database: String?, schema: String, table: String,
                 scanJobId: Long? = null): TableDocView {
        val aiConfig = aiConfigService.requireConfig()
        val stat: TableStat = metadataService.listTables(datasourceId, database, schema)
            .firstOrNull { it.name == table }
            ?: throw IllegalArgumentException("表不存在:$table")
        val columns: List<ColumnMeta> = metadataService.listTableColumns(datasourceId, database, schema, table)
        val description: String
        try {
            description = aiService.describeTable(aiConfig, stat, columns, scanJobId)
        } catch (e: RuntimeException) {
            if (aiConfig.usingDefault) {
                // 默认配置不可用时不暴露任何默认接口细节,只引导用户自行配置
                throw IllegalStateException("AI 服务暂不可用,请在「AI 配置」中填写自己的大模型接口信息(接口地址 / API Key / 模型)", e)
            }
            throw e
        }
        // 使用默认配置生成时不落库/回显模型名,避免暴露默认配置
        val modelLabel = if (aiConfig.usingDefault) null else aiConfig.model
        // 仓储层 model 参数为非空 String,落空串;table_doc.model 当前无任何读取方,与 Java 版的 null 等价
        repository.upsert(datasourceId, normalizeDb(database), schema, table, description, modelLabel ?: "")
        return TableDocView(table, description, modelLabel, LocalDateTime.now())
    }

    /** 手动编辑描述(只改文字,保留生成模型标记) */
    fun update(datasourceId: Long, database: String?, schema: String, table: String, description: String?): TableDocView {
        if (description.isNullOrBlank()) {
            throw IllegalArgumentException("描述不能为空")
        }
        repository.updateDescription(datasourceId, normalizeDb(database), schema, table, description.trim())
        return TableDocView(table, description.trim(), null, LocalDateTime.now())
    }

    private companion object {
        /** 无库概念的方言 db 为 null,统一存空串保证唯一键 */
        fun normalizeDb(database: String?): String = database ?: ""
    }
}
