package com.example.dq.service

import com.example.dq.dialect.DbDialect
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.AiScene
import com.example.dq.model.ColumnMeta
import com.example.dq.model.CompareAiTrace
import com.example.dq.model.CompareAiTraceView
import com.example.dq.model.CompareDiffPage
import com.example.dq.model.CompareDiffRow
import com.example.dq.model.CompareExportOverviewRow
import com.example.dq.model.CompareJobActiveView
import com.example.dq.model.CompareJobDetailView
import com.example.dq.model.CompareJobPage
import com.example.dq.model.CompareJobView
import com.example.dq.model.CompareMode
import com.example.dq.model.CompareReportView
import com.example.dq.model.CompareTargetIdentity
import com.example.dq.model.CompareTargetSpec
import com.example.dq.model.CompareTargetView
import com.example.dq.model.CreateCompareJobRequest
import com.example.dq.model.FieldDiff
import com.example.dq.model.FieldIssueRank
import com.example.dq.model.MappingSuggestRequest
import com.example.dq.model.MappingSuggestTargetView
import com.example.dq.model.MappingSuggestView
import com.example.dq.model.PendingReason
import com.example.dq.repository.CompareAiTraceRepository
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.util.ExcelCells
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Types
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据比对:以一张基准表为准,与多个目标系统(数据源)中的对应表按对象身份对齐后逐字段比对,
 * 产出差异明细(compare_diff)与质量报告(compare_target 四项比率)。
 * 长时任务模型同 SampleExportService:固定 2 线程池后台执行、任务/目标/明细落 H2、
 * updateStage/updateProgress 增量进度、recoverUnfinished 启动恢复;
 * 单个目标失败只把该 target 置 FAILED 记 error 继续下一个,基准读取失败才整个任务 FAILED。
 *
 * 对象对齐(匹配)逻辑一任务一套(compare_job.match_mode,新建向导第二步选择,[MatchMode]):
 * - EXACT:对象编码与对象名称都相等才算同一对象;
 * - CODE_THEN_NAME:先用编码配,编码没配上的再用对象名称配;
 * - CODE_NAME_LLM:在 1、2 的残余之上再交大模型归一化配对(见 [CompareMatchPrompts]);
 * - 老任务(match_mode 为空)= 只按编码对齐,与历史口径完全一致。
 * 对象身份字段(V62)下沉到目标级:任务级 keyFields 为「默认身份/并集」,各目标有效身份 =
 * identity_json 人工覆盖 ?? 「任务级 keyFields ∩ 该目标映射键」推导(纯函数 [resolveIdentity],
 * 无映射 = keyFields 全体走自动匹配老路径);行 map 键为身份列值 trim 后以「\u0001」拼接,单列即现状。
 * 比对核心 [diffObjects](逐字段)与对象对齐 [matchObjects] 均为纯函数(companion),便于单测。
 */
class CompareService(
    private val repo: CompareRepository,
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val metadataService: MetadataService,
    private val systemSettingsService: SystemSettingsService,
    /** 表所属系统(导出总览「所属系统」列;只读本地 H2,不连业务库) */
    private val tableSystemRepo: TableSystemRepository,
    /** AI 配置(匹配逻辑 3 用);未配置时降级为「残余不配」,不炸任务 */
    private val aiConfigService: AiConfigService? = null,
    /** 匹配逻辑 3 的 LLM 调用点(默认走 [AiService.chat],测试可注入 fake) */
    private val aiChat: AiChat = { config, system, user ->
        AiService().chat(config, system, user, AiScene.COMPARE_MATCH)
    },
    /** 列级对比字段映射预生成的 LLM 调用点(默认 COMPARE_MAPPING 场景,测试可注入 fake) */
    private val aiMappingChat: AiChat = { config, system, user ->
        AiService().chat(config, system, user, AiScene.COMPARE_MAPPING)
    },
    /** 「数据最新更新时间」时间字段语义匹配的 LLM 调用点(默认 COMPARE_TIME 场景,测试可注入 fake) */
    private val aiTimeChat: AiChat = { config, system, user ->
        AiService().chat(config, system, user, AiScene.COMPARE_TIME)
    },
    /** 佐证字段兜底识别的 LLM 调用点(默认 COMPARE_EVIDENCE 场景,测试可注入 fake) */
    private val aiEvidenceChat: AiChat = { config, system, user ->
        AiService().chat(config, system, user, AiScene.COMPARE_EVIDENCE)
    },
    /** 表字段清单读取点(默认走 [MetadataService] 缓存优先路径;测试可注入 fake 避免连业务库) */
    private val columnsLister: (datasourceId: Long, db: String, schema: String, table: String) -> List<ColumnMeta> =
        { dsId, db, schema, table -> metadataService.listTableColumns(dsId, db, schema, table) },
    /** 表注释读取点(默认走 [MetadataService] 缓存优先路径;取不到/失败返回 null,测试可注入 fake) */
    private val tableCommentLister: (datasourceId: Long, db: String, schema: String, table: String) -> String? =
        { dsId, db, schema, table ->
            try {
                metadataService.listTables(dsId, db.ifBlank { null }, schema)
                    .firstOrNull { it.name.equals(table, ignoreCase = true) }
                    ?.comment?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null // 元数据不可达静默降级:注释快照留空,不炸比对
            }
        },
    /** 报告导出件目录(V65 起服务端直存 `<数据目录>/compare`,任务 ID 前缀命名;不传(单测)时 exportFileOk 恒 false) */
    private val compareDir: Path? = null,
    /** AI 判定留痕仓储(AI 用量库 compare_ai_trace);空 = 不留痕不可查(单测默认) */
    private val aiTraceRepo: CompareAiTraceRepository? = null,
    /** AI 判定留痕记录点(默认写 [aiTraceRepo];测试注入捕获器,须线程安全——补配/消歧批次并发记录) */
    private val aiTraceRecorder: (CompareAiTrace) -> Unit = { t -> aiTraceRepo?.insert(t) },
) {

    /** 导出件 SHA-256 校验缓存:绝对路径 → (size, mtimeMillis, sha256hex);导出件写后不变,按 size+mtime 失效 */
    private val exportChecksumCache = ConcurrentHashMap<String, Triple<Long, Long, String>>()

    /** 匹配逻辑 3 的 LLM 调用点:给定可用 AI 配置与会话内容,返回模型回答 */
    fun interface AiChat {
        fun call(config: AiConfigService.Config, systemPrompt: String, userPrompt: String): String
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    /** 任务执行线程池(线程名 compare-N,守护) */
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "compare-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
    }

    /** 查询选择列:field=基准字段名(行 map 的键), column=该侧实际列名 */
    private data class SelectedCol(val field: String, val column: String)

    // ---------- 提交 / 重跑 ----------

    /**
     * 提交比对任务:同步校验(基准数据源存在、目标非空、fields 含全部身份字段、各表字段映射,
     * 忽略大小写;目标缺身份列直接报错,缺其他比对列记「列缺失」按不一致计)→ 落任务与目标
     * (RUNNING,total=1+目标数)→ 后台执行。返回任务 id
     */
    fun submit(req: CreateCompareJobRequest): Long {
        val r = resolveRequest(req)
        val jobId = repo.insertJob(r.name, r.baseDatasourceId, r.baseDb, req.baseSchema, r.baseTable,
            r.keyField, objectMapper.writeValueAsString(r.fields), 1 + r.targets.size,
            r.displayFields.firstOrNull(),
            r.matchMode.value, r.compareMode.value, objectMapper.writeValueAsString(r.keyFields), r.sampleRows,
            r.displayFields.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) })
        for (t in r.targets) {
            repo.insertTarget(jobId, t.datasourceId, t.dsName, t.db, t.schema, t.table, t.mappingJson, t.identityJson,
                t.displayName)
        }
        executor.execute { run(jobId) }
        log.info("比对任务已提交: id={}, 名称={}, 基准={}.{}, 目标数={}, 匹配逻辑={}, 对比模式={}",
            jobId, r.name, r.baseDb, r.baseTable, r.targets.size, r.matchMode.value, r.compareMode.value)
        return jobId
    }

    /** submit 的校验 + 归一结果(双侧字段都已归一为实际列名) */
    private data class ResolvedRequest(
        val name: String, val baseDatasourceId: Long, val baseDb: String, val baseTable: String,
        val keyField: String, val keyFields: List<String>, val fields: List<String>,
        /** 对象名称字段(V73,有序;取值 = 按字段顺序第一个非空值);空表 = 无可用名称字段 */
        val displayFields: List<String>,
        val matchMode: MatchMode, val compareMode: CompareMode, val targets: List<ResolvedTarget>,
        /** 抽样条数(双侧各按身份列排序取前 N 条);null = 全量比对 */
        val sampleRows: Int? = null)

    /**
     * submit / updatePending 共用的同步校验与归一:基准数据源存在、目标非空、fields 含全部身份字段、
     * 各表字段映射忽略大小写归一为实际列名(目标缺身份列直接报错,缺其他比对列记「列缺失」按不一致计)
     */
    private fun resolveRequest(req: CreateCompareJobRequest): ResolvedRequest {
        val name = req.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("任务名称不能为空")
        val baseDsId = req.baseDatasourceId ?: throw IllegalArgumentException("请选择基准数据源")
        val baseTable = req.baseTable?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("基准表不能为空")
        val keyField = req.keyField?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("请选择比对主键")
        // 任务级身份字段(多选):留空 = 仅 keyField 单字段(旧行为);给出时去空白去重
        val rawKeyFields = req.keyFields?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
            ?.takeIf { it.isNotEmpty() }
        val rawFields = req.fields?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
            ?: throw IllegalArgumentException("请选择比对字段")
        if (rawFields.isEmpty()) throw IllegalArgumentException("请选择比对字段")
        if (rawFields.none { it.equals(keyField, ignoreCase = true) }) {
            throw IllegalArgumentException("比对字段必须包含主键字段: $keyField")
        }
        // 多身份字段时同样必须全部参与比对(身份字段恒入比对字段,与 keyField 口径一致)
        if (rawKeyFields != null) {
            val missing = rawKeyFields.filter { k -> rawFields.none { it.equals(k, ignoreCase = true) } }
            if (missing.isNotEmpty()) {
                throw IllegalArgumentException("比对字段必须包含全部身份字段: ${missing.joinToString("、")}")
            }
        }
        val specs = req.targets?.filter { it.datasourceId != null && !it.table.isNullOrBlank() }
        if (specs.isNullOrEmpty()) throw IllegalArgumentException("请至少添加一个比对目标")

        // 基准侧:数据源存在 + 表字段映射(请求字段名归一为基准表实际列名,忽略大小写)
        dataSourceService.get(baseDsId)
        val baseDb = req.baseDb ?: ""
        val baseColumns = columnsLister(baseDsId, baseDb, effectiveSchema(req.baseSchema, baseDb), baseTable)
        if (baseColumns.isEmpty()) throw IllegalArgumentException("基准表不存在或没有字段: $baseTable")
        val baseByName = baseColumns.associateBy { it.name.lowercase() }
        if (baseByName[keyField.lowercase()] == null) {
            throw IllegalArgumentException("基准表不存在主键字段: $keyField")
        }
        val fields = rawFields.map { f ->
            baseByName[f.lowercase()]?.name ?: throw IllegalArgumentException("基准表不存在字段: $f")
        }
        val actualKey = baseByName.getValue(keyField.lowercase()).name
        // 任务级身份字段归一为基准表实际列名(忽略大小写);未给出 = [keyField] 单字段(旧行为)
        val actualKeyFields = rawKeyFields?.map { f ->
            baseByName[f.lowercase()]?.name ?: throw IllegalArgumentException("基准表不存在身份字段: $f")
        } ?: listOf(actualKey)
        // 对象名称(显示名)字段(V73 多选):显式给出逐个校验(∈比对字段、与身份字段互斥);
        // 未给出按 displayField 单值旧逻辑,再空回退第一个文本型非身份字段;此处即解析成实际列名
        val displayFields = resolveDisplayFields(req.displayFields, req.displayField, fields, baseByName, actualKeyFields)
        // 对象对齐匹配逻辑:一任务一套;匹配逻辑 2/3 的第二路按「对象名称」字段配对,故必须显式指定该字段
        val matchMode = normalizeMatchMode(req.matchMode)
        // 对比模式:空 = 行级(与既有行为一致);只影响向导默认字段与映射来源,执行引擎同一套
        val compareMode = CompareMode.normalize(req.compareMode)
        // 抽样条数(V70):双侧各按身份列排序取前 N 条;上限沿用单侧行数上限 MAX_SIDE_ROWS
        val sampleRows = req.sampleRows
        if (sampleRows != null && (sampleRows < 1 || sampleRows > MAX_SIDE_ROWS)) {
            throw IllegalArgumentException("抽样条数须为 1~$MAX_SIDE_ROWS 的整数")
        }
        if (matchMode.requiresName && displayFields.isEmpty()) {
            throw IllegalArgumentException(
                "${matchMode.label}需要按对象名称配对,请在「选择基准表」里指定对象名称字段")
        }

        // 目标侧:数据源存在 + 表存在 + 有效身份列必须存在(缺其他比对列允许,比对时记「列缺失」);
        // 字段映射(第三步人工连线)可选:给出时键值都归一为双侧实际列名,且必须覆盖该目标有效身份字段全部
        val resolved = specs.map { spec ->
            val dsId = spec.datasourceId!!
            val ds = dataSourceService.get(dsId)
            val db = spec.db ?: ""
            val table = spec.table!!.trim()
            val cols = columnsLister(dsId, db, effectiveSchema(spec.schema, db), table)
            if (cols.isEmpty()) throw IllegalArgumentException("目标表不存在或没有字段: ${ds.name}.$table")
            val colsByName = cols.associateBy { it.name.lowercase() }
            val mapping = normalizeMapping(spec.mapping, fields, baseByName, colsByName)
            // 该目标有效身份:人工覆盖(须 ⊆ 任务级身份字段)优先,否则按「任务级身份 ∩ 映射键」推导;推导为空拦下
            val identityKeys = normalizeIdentityOverride(spec.identity?.keys, actualKeyFields, baseByName)
            val identity = resolveIdentity(actualKeyFields, mapping ?: emptyMap(), identityKeys)
            ensureIdentityMapped(mapping, identity, ds.name, table)
            ensureIdentityColumns(mapping, identity, colsByName, ds.name, table)
            ResolvedTarget(dsId, ds.name, db, spec.schema, table,
                mapping?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) },
                identityKeys?.let { objectMapper.writeValueAsString(mapOf("keys" to it)) },
                spec.displayName?.trim()?.takeIf { it.isNotEmpty() })
        }
        return ResolvedRequest(name, baseDsId, baseDb, baseTable, actualKey, actualKeyFields, fields, displayFields,
            matchMode, compareMode, resolved, sampleRows)
    }

    private data class ResolvedTarget(val datasourceId: Long, val dsName: String?,
                                      val db: String, val schema: String?, val table: String,
                                      val mappingJson: String? = null, val identityJson: String? = null,
                                      /** 自定义显示名(V72;已归一:trim 且非空) */
                                      val displayName: String? = null)

    /** 目标级身份覆盖归一:keys 必须在基准表存在且 ⊆ 任务级身份字段,归一为基准表实际列名;空 = 未覆盖 */
    private fun normalizeIdentityOverride(raw: List<String>?, jobKeyFields: List<String>,
                                          baseByName: Map<String, ColumnMeta>): List<String>? {
        val keys = raw?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() }
            ?: return null
        return keys.map { k ->
            val col = baseByName[k.lowercase()]
                ?: throw IllegalArgumentException("目标身份字段在基准表不存在: $k")
            if (jobKeyFields.none { it.equals(col.name, ignoreCase = true) }) {
                throw IllegalArgumentException("目标身份字段不在任务身份字段内: $k")
            }
            col.name
        }
    }

    /** 硬约束:显式映射必须覆盖该目标有效身份字段的全部(缺一个都无法按身份对齐行;旧口径为必须含比对主键) */
    private fun ensureIdentityMapped(mapping: Map<String, String>?, identity: List<String>,
                                     dsName: String?, table: String) {
        if (mapping == null) return
        val missing = identity.filter { k -> mapping.keys.none { it.equals(k, ignoreCase = true) } }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "字段映射必须包含该目标的全部身份字段: ${missing.joinToString("、")}($dsName.$table)")
        }
    }

    /** 硬约束:目标侧身份列必须存在(经映射换名或按同名);错误消息点名列名 */
    private fun ensureIdentityColumns(mapping: Map<String, String>?, identity: List<String>,
                                      targetByName: Map<String, ColumnMeta>, dsName: String?, table: String) {
        val missing = identity.mapNotNull { k ->
            val targetCol = mapping?.entries?.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value ?: k
            if (targetByName[targetCol.lowercase()] == null) targetCol else null
        }
        if (missing.isEmpty()) return
        if (identity.size == 1) {
            // 单身份与旧口径同文案(老任务/老前端可读性一致)
            throw IllegalArgumentException("目标表缺少比对主键列: $dsName.$table 无 ${missing.single()}")
        }
        throw IllegalArgumentException("目标表缺少身份列: $dsName.$table 无 ${missing.joinToString("、")}")
    }

    /** 任务级身份字段:key_fields_json 为空(老任务/批量导入)退化为 [JobRow.keyField] 单元素,口径不变 */
    private fun jobKeyFields(job: CompareRepository.JobRow): List<String> =
        parseFields(job.keyFieldsJson).ifEmpty { listOf(job.keyField) }

    /** 任务级对象名称字段:display_fields_json 为空(老任务/批量导入)退化为 [JobRow.displayField] 单列或空表 */
    private fun jobDisplayFields(job: CompareRepository.JobRow): List<String> =
        parseFields(job.displayFieldsJson).ifEmpty { listOfNotNull(job.displayField?.takeIf { it.isNotBlank() }) }

    /** 导出 sheet 的「对象编码」列头:任务级身份字段中文名按「+」组合(老任务单字段与旧口径一致) */
    private fun identityHeader(job: CompareRepository.JobRow, ctx: ExportContext): String =
        jobKeyFields(job).joinToString("+") { ctx.fieldHeader(job.baseDatasourceId, job.baseDb, job.baseTable, it) }

    /** 导出 sheet 的「对象名称」列头:名称字段中文名按「+」组合(无名称字段回落 [OBJECT_NAME_HEADER],与旧口径一致) */
    private fun displayHeader(job: CompareRepository.JobRow, ctx: ExportContext): String =
        jobDisplayFields(job).takeIf { it.isNotEmpty() }
            ?.joinToString("+") { ctx.fieldHeader(job.baseDatasourceId, job.baseDb, job.baseTable, it) }
            ?: OBJECT_NAME_HEADER

    /**
     * 字段映射归一:null/空 = 不指定(执行时按字段名忽略大小写自动匹配,旧行为);
     * 给出时逐条校验(基准字段必须存在且在比对字段内、目标列必须存在),返回
     * `基准表实际列名 → 目标表实际列名`;「映射必须覆盖有效身份字段」由调用方按该目标有效身份逐一校验
     * ([ensureIdentityMapped],身份可能因目标级覆盖/推导而变化)。
     */
    private fun normalizeMapping(raw: Map<String, String>?, fields: List<String>,
                                 baseByName: Map<String, ColumnMeta>,
                                 targetByName: Map<String, ColumnMeta>): Map<String, String>? {
        if (raw.isNullOrEmpty()) return null
        val out = LinkedHashMap<String, String>()
        for ((baseField, targetCol) in raw) {
            val bf = baseField.trim()
            val tc = targetCol?.trim().orEmpty()
            if (bf.isEmpty() || tc.isEmpty()) continue
            val baseCol = baseByName[bf.lowercase()]
                ?: throw IllegalArgumentException("字段映射的基准字段不存在: $bf")
            if (fields.none { it.equals(baseCol.name, ignoreCase = true) }) {
                throw IllegalArgumentException("字段映射的基准字段不在比对字段内: $bf")
            }
            val targetColumn = targetByName[tc.lowercase()]
                ?: throw IllegalArgumentException("字段映射的目标列不存在: $tc")
            out[baseCol.name] = targetColumn.name
        }
        if (out.isEmpty()) return null
        return out
    }

    /** 解析落库的字段映射 JSON;解析失败按「未指定映射」处理(不炸任务,执行时回落自动匹配) */
    /** 字段注释快照 JSON(字段小写 → 注释);全部无注释时是 "{}",同样是有效快照(导出/报告不再回源兜底) */
    private fun snapshotComments(columns: List<ColumnMeta>): String =
        columns.filter { !it.comment.isNullOrBlank() }
            .associate { it.name.lowercase() to it.comment!! }
            .let { objectMapper.writeValueAsString(it) }

    /** 解析注释快照 JSON(字段小写 → 注释);NULL/解析失败返回 null(调用方走本地缓存兜底) */
    private fun parseCommentMap(json: String?): Map<String, String>? =
        json?.let {
            try {
                objectMapper.readValue<Map<String, String>>(it)
            } catch (e: Exception) {
                log.warn("注释快照 JSON 解析失败,按未快照处理: {}", e.message)
                null
            }
        }

    private fun parseMapping(json: String?): Map<String, String> =
        json?.takeIf { it.isNotBlank() }?.let {
            try {
                objectMapper.readValue<Map<String, String>>(it)
            } catch (e: Exception) {
                log.warn("字段映射 JSON 解析失败,按未指定处理: {}", e.message)
                emptyMap()
            }
        } ?: emptyMap()

    /** 重跑:仅 DONE/FAILED/CANCELED 可重跑;清空既有目标与差异明细,按原目标清单重建后重新执行 */
    fun rerun(jobId: Long) {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        if (job.status == "RUNNING") throw IllegalStateException("任务运行中,不能重跑")
        val oldTargets = repo.listTargets(jobId)
        if (oldTargets.isEmpty()) throw IllegalStateException("任务没有比对目标,不能重跑")
        repo.clearResults(jobId)
        repo.markRerun(jobId, 1 + oldTargets.size)
        for (t in oldTargets) {
            // 数据源名快照按最新库刷新(可能已改名);数据源被删则沿用旧快照,执行时该目标会失败
            val dsName = try {
                dataSourceService.get(t.datasourceId).name
            } catch (e: Exception) {
                t.dsName
            }
            // 自定义显示名随旧行保留(V72):重跑不重建向导表单,沿用用户改过的名字
            repo.insertTarget(jobId, t.datasourceId, dsName, t.dbName, t.schemaName, t.tableName,
                t.fieldMappingJson, t.identityJson, t.displayName)
        }
        executor.execute { run(jobId) }
        log.info("比对任务重跑: id={}, 目标数={}", jobId, oldTargets.size)
    }

    // ---------- 待处理(PENDING,比对任务批量导入) ----------

    /** 批量导入建任务时的目标行:mapping 为「基准列名 → 目标列名」(锁定字段 + 大模型推导合并结果),可空 */
    data class PendingTargetSpec(val datasourceId: Long, val dsName: String?, val db: String,
                                 val schema: String?, val table: String, val mapping: Map<String, String>?)

    /** 批量导入「先建任务数据」的返回:任务 id + 按输入顺序落库的目标 id(后台映射按顺序回写) */
    data class PendingCreatedJob(val jobId: Long, val targetIds: List<Long>)

    /**
     * 落「待处理」任务([CompareImportService] 专用入口):固定 match_mode=CODE_NAME_LLM、
     * compare_mode=COLUMN(导入任务口径),落 job(PENDING)+ targets(含 mapping),不进执行器。
     * fields/keyField/displayField 由调用方按基准表实际列名归一好(基准表读不出时按表格原值兜底)
     */
    fun createPending(name: String, baseDatasourceId: Long, baseDb: String, baseSchema: String?,
                      baseTable: String, keyField: String, fields: List<String>, displayField: String?,
                      targets: List<PendingTargetSpec>, pendingReason: PendingReason,
                      objectCategory: String?, importId: Long?): Long =
        createPendingWithTargetIds(name, baseDatasourceId, baseDb, baseSchema, baseTable, keyField, fields,
            displayField, targets, pendingReason, objectCategory, importId).jobId

    /**
     * [createPending] 的详细入口:返回任务 id + 目标 id 列表(与 [targets] 同序),
     * 供批量导入先快速建档(MAPPING_RUNNING),后台映射线程按目标 id 回写推导结果
     */
    fun createPendingWithTargetIds(name: String, baseDatasourceId: Long, baseDb: String, baseSchema: String?,
                                   baseTable: String, keyField: String, fields: List<String>, displayField: String?,
                                   targets: List<PendingTargetSpec>, pendingReason: PendingReason,
                                   objectCategory: String?, importId: Long?): PendingCreatedJob {
        require(name.isNotBlank() && fields.isNotEmpty() && keyField.isNotBlank()) { "待处理任务缺少必要字段" }
        // 导入 Excel 的对象名称列为单值:display_fields_json 存单元素数组,存储口径与新任务一致(读取不再区分来源)
        val displayFieldsJson = displayField?.takeIf { it.isNotBlank() }
            ?.let { objectMapper.writeValueAsString(listOf(it)) }
        val jobId = repo.insertPendingJob(name.take(200), baseDatasourceId, baseDb, baseSchema, baseTable,
            keyField, objectMapper.writeValueAsString(fields), 1 + targets.size, displayField,
            MatchMode.CODE_NAME_LLM.value, CompareMode.COLUMN.value,
            pendingReason.value, objectCategory, importId, displayFieldsJson)
        val targetIds = targets.map { t ->
            repo.insertTarget(jobId, t.datasourceId, t.dsName, t.db, t.schema, t.table,
                t.mapping?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) })
        }
        log.info("比对任务已建档(待处理): id={}, 名称={}, 原因={}, 目标数={}",
            jobId, name, pendingReason, targets.size)
        return PendingCreatedJob(jobId, targetIds)
    }

    /**
     * 后台字段映射结束(仅 MAPPING_RUNNING 的 PENDING 导入任务):回写基准表归一后的实际字段、
     * 新待处理原因/失败说明,并按 [targetMappings] 全量回写目标行 mapping(目标 id → 基准列名→目标列名,可空)。
     * 返回 false = 任务已删除/原因已不在 MAPPING_RUNNING(后台线程自行放弃,目标映射也不再回写)
     */
    fun completePendingMapping(jobId: Long, keyField: String, fields: List<String>, displayField: String?,
                               targetMappings: Map<Long, Map<String, String>?>,
                               pendingReason: PendingReason, error: String?): Boolean {
        require(fields.isNotEmpty() && keyField.isNotBlank()) { "字段映射结果缺少必要字段" }
        val updated = repo.finishPendingMapping(jobId, keyField, objectMapper.writeValueAsString(fields),
            displayField, pendingReason.value, error,
            displayField?.takeIf { it.isNotBlank() }?.let { objectMapper.writeValueAsString(listOf(it)) })
        if (updated == 0) return false
        for ((targetId, mapping) in targetMappings) {
            repo.updateTargetMapping(targetId,
                mapping?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) })
        }
        log.info("比对任务字段映射推导结束: id={}, 原因={}, 字段数={}, 目标数={}, 错误={}",
            jobId, pendingReason, fields.size, targetMappings.size, error)
        return true
    }

    /** 后台字段映射:给目标追加说明(单目标推导失败等;PENDING 目标行 error 列作说明用) */
    fun notePendingTarget(targetId: Long, note: String) = repo.appendTargetNote(targetId, note)

    /** 后台字段映射未结束前(MAPPING_RUNNING)不允许审核/编辑/开跑,避免与映射线程同时回写任务数据 */
    private fun ensureMappingReady(job: CompareRepository.JobRow) {
        if (job.pendingReason == PendingReason.MAPPING_RUNNING.value) {
            throw IllegalStateException("字段映射正在后台推导,请稍候再操作")
        }
    }

    /**
     * 「字段审核」确认映射并开始比对(审核弹窗「确认并开始比对」= confirm + start 合并):仅 PENDING;
     * 校验口径同 [submit](基准字段必须在比对字段内、目标列必须存在、映射必须覆盖该目标有效身份字段全部、
     * 目标缺身份列报错),全量替换各目标 mapping 后 PENDING→RUNNING 进执行器。也是 DS_ERROR 任务的复活出口(先修数据源再确认)
     *
     * [identities] 为目标级身份人工覆盖(targetId → keys,可选):按与 [resolveRequest] 相同口径校验
     * (⊆ 任务级 keyFields、非空)后落 `compare_target.identity_json`;只在 [identities] 里出现的 targetId
     * 沿用库中既有 mapping 做一致性校验、只更新身份
     */
    fun confirmMapping(jobId: Long, mappings: Map<Long, Map<String, String>>,
                       identities: Map<Long, CompareTargetIdentity?> = emptyMap()) {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        if (job.status != "PENDING") throw IllegalStateException("仅「待处理」任务可以确认字段映射")
        ensureMappingReady(job)
        val fields = parseFields(job.fieldsJson)
        val jobKeyFields = jobKeyFields(job)
        val baseColumns = columnsLister(job.baseDatasourceId, job.baseDb,
            effectiveSchema(job.baseSchema, job.baseDb), job.baseTable)
        if (baseColumns.isEmpty()) throw IllegalArgumentException("基准表不存在或没有字段: ${job.baseTable}")
        val baseByName = baseColumns.associateBy { it.name.lowercase() }
        val targets = repo.listTargets(jobId)
        val targetsById = targets.associateBy { it.id }
        // 并集遍历:mappings 更新映射、identities 更新身份;同一目标两者都有时按新映射 + 新身份一起校验
        for (targetId in (mappings.keys + identities.keys)) {
            val t = targetsById[targetId]
                ?: throw IllegalArgumentException("目标不属于该任务: $targetId")
            val ds = dataSourceService.get(t.datasourceId)
            val cols = columnsLister(t.datasourceId, t.dbName, effectiveSchema(t.schemaName, t.dbName), t.tableName)
            if (cols.isEmpty()) throw IllegalArgumentException("目标表不存在或没有字段: ${ds.name}.${t.tableName}")
            val colsByName = cols.associateBy { it.name.lowercase() }
            // 未提交新映射时沿用库中既有映射做校验(只更新身份的场景)
            val mapping = if (targetId in mappings) normalizeMapping(mappings[targetId], fields, baseByName, colsByName)
            else parseMapping(t.fieldMappingJson).takeIf { it.isNotEmpty() }
            // 该目标有效身份:本次携带的人工覆盖(须 ⊆ 任务级身份字段且非空)> 库中已有覆盖 > 按映射键推导;
            // 推导为空(一个身份字段都没连)在此拦下,口径同 submit
            val identityKeys = if (targetId in identities) {
                normalizeIdentityOverride(identities[targetId]?.keys, jobKeyFields, baseByName)
                    ?: throw IllegalArgumentException("目标身份字段不能为空: 目标 $targetId")
            } else {
                parseIdentityKeys(t.identityJson)
            }
            val identity = resolveIdentity(jobKeyFields, mapping ?: emptyMap(), identityKeys)
            ensureIdentityMapped(mapping, identity, ds.name, t.tableName)
            ensureIdentityColumns(mapping, identity, colsByName, ds.name, t.tableName)
            if (targetId in mappings) {
                repo.updateTargetMapping(targetId,
                    mapping?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) })
            }
            if (targetId in identities) {
                repo.updateTargetIdentity(targetId,
                    objectMapper.writeValueAsString(mapOf("keys" to identityKeys)))
            }
        }
        if (repo.markPendingRunning(jobId, 1 + targets.size) == 0) {
            throw IllegalStateException("任务状态已变化,请刷新后重试")
        }
        executor.execute { run(jobId) }
        log.info("比对任务映射已确认,开始比对: id={}, 目标数={}", jobId, targets.size)
    }

    /**
     * 「待处理」任务向导编辑提交:仅 PENDING;校验口径同 [submit](匹配逻辑/对比模式也随请求改,
     * 编辑不做限制,与终态任务编辑同口径),tx 内替换 job 元数据 + 删旧 targets 重建(照 [rerun] 清空重建写法),
     * 保持 PENDING——是否立即运行由前端再调 [confirmMapping]/[start]
     */
    fun updatePending(jobId: Long, req: CreateCompareJobRequest) {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        if (job.status != "PENDING") throw IllegalStateException("仅「待处理」任务可以编辑")
        ensureMappingReady(job)
        val r = resolveRequest(req)
        repo.replacePendingJob(jobId, r.name, r.baseDatasourceId, r.baseDb, req.baseSchema, r.baseTable,
            r.keyField, objectMapper.writeValueAsString(r.fields), 1 + r.targets.size,
            r.displayFields.firstOrNull(),
            r.matchMode.value, r.compareMode.value, objectMapper.writeValueAsString(r.keyFields),
            r.displayFields.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) },
            r.sampleRows,
            r.targets.map { CompareRepository.NewTarget(it.datasourceId, it.dsName, it.db, it.schema, it.table,
                it.mappingJson, it.identityJson, it.displayName) })
        log.info("待处理比对任务已更新: id={}, 名称={}, 目标数={}", jobId, r.name, r.targets.size)
    }

    /**
     * 向导编辑提交统一入口(PUT /api/compare-jobs/{id}):
     * PENDING(批量导入待处理)照 [updatePending] 口径保存并保持待处理;
     * 终态(DONE/FAILED/CANCELED,已完成任务再次编辑)校验同 [submit]、匹配逻辑/对比模式允许改,
     * tx 内替换元数据与目标清单并直接重跑(RUNNING),旧差异明细随目标清单一并清掉
     */
    fun update(jobId: Long, req: CreateCompareJobRequest) {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        if (job.status == "PENDING") {
            updatePending(jobId, req)
            return
        }
        if (job.status == "RUNNING") throw IllegalStateException("任务运行中,不能编辑")
        val r = resolveRequest(req)
        val updated = repo.replaceAndRestart(jobId, r.name, r.baseDatasourceId, r.baseDb, req.baseSchema,
            r.baseTable, r.keyField, objectMapper.writeValueAsString(r.fields), 1 + r.targets.size,
            r.displayFields.firstOrNull(), r.matchMode.value, r.compareMode.value,
            objectMapper.writeValueAsString(r.keyFields),
            r.displayFields.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) },
            r.sampleRows,
            r.targets.map { CompareRepository.NewTarget(it.datasourceId, it.dsName, it.db, it.schema, it.table,
                it.mappingJson, it.identityJson, it.displayName) })
        if (updated == 0) throw IllegalStateException("任务状态已变化,请刷新后重试")
        executor.execute { run(jobId) }
        log.info("比对任务已编辑并重跑: id={}, 名称={}, 目标数={}", jobId, r.name, r.targets.size)
    }

    /**
     * 更新单个目标的自定义显示名(V72,备用接口;向导内编辑走任务 PUT 随 targets 提交):
     * displayName 空/blank = 清除,展示回落数据源名快照;目标必须属于该任务
     */
    fun updateTargetDisplayName(jobId: Long, targetId: Long, displayName: String?) {
        repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        if (repo.listTargets(jobId).none { it.id == targetId }) {
            throw IllegalArgumentException("目标不属于该任务: $targetId")
        }
        repo.updateTargetDisplayName(targetId, displayName)
        log.info("比对目标显示名已更新: jobId={}, targetId={}, 显示名={}", jobId, targetId,
            displayName?.trim().orEmpty().ifEmpty { "(清除)" })
    }

    /**
     * 「待处理」任务直接开始比对(映射已就绪、无需再改的场景):仅 PENDING 且非 DS_ERROR
     * (数据源异常必须先修数据源、走 [confirmMapping] 确认);PENDING→RUNNING 进执行器
     */
    fun start(jobId: Long) {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        if (job.status != "PENDING") throw IllegalStateException("仅「待处理」任务可以开始比对")
        ensureMappingReady(job)
        if (job.pendingReason == PendingReason.DS_ERROR.value) {
            throw IllegalStateException("任务涉及异常数据源,请先修复数据源并经「字段审核」确认后再开始")
        }
        val targets = repo.listTargets(jobId)
        if (targets.isEmpty()) throw IllegalStateException("任务没有比对目标,不能开始比对")
        if (repo.markPendingRunning(jobId, 1 + targets.size) == 0) {
            throw IllegalStateException("任务状态已变化,请刷新后重试")
        }
        executor.execute { run(jobId) }
        log.info("待处理比对任务开始执行: id={}, 目标数={}", jobId, targets.size)
    }

    // ---------- 字段映射预生成(列级对比) ----------

    /**
     * 列级对比·字段映射预生成:取基准表需映射字段与逐目标表全列(本地缓存优先,断网降级同 submit),
     * 逐目标串行交大模型产出「基准字段 → 目标列」建议([CompareMappingPrompts],解析容错/主键兜底),
     * 人工在向导第三步画布审核后随任务提交。未配置大模型直接报错(交互场景需明确引导);
     * 单目标失败只记 note 不炸整体。返回与请求 targets 同序
     */
    fun suggestMappings(req: MappingSuggestRequest): MappingSuggestView {
        val baseDsId = req.baseDatasourceId ?: throw IllegalArgumentException("请选择基准数据源")
        val baseTable = req.baseTable?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("基准表不能为空")
        val keyField = req.keyField?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("请选择比对主键")
        val specs = req.targets?.filter { it.datasourceId != null && !it.table.isNullOrBlank() }
        if (specs.isNullOrEmpty()) throw IllegalArgumentException("请至少添加一个比对目标")
        val config = aiConfigService?.findConfig()
            ?: throw IllegalStateException("请先在「AI 配置」中完成大模型配置,再使用字段映射预生成")

        // 定位串(库.模式.表,空段省略),prompt 里标注两侧表用
        fun loc(db: String?, schema: String?, table: String) =
            listOfNotNull(db?.takeIf { it.isNotBlank() }, schema?.takeIf { it.isNotBlank() }, table)
                .joinToString(".")

        // 基准侧:数据源存在 + 字段清单(请求字段归一为实际列名;留空 = 全部字段)
        dataSourceService.get(baseDsId)
        val baseDb = req.baseDb ?: ""
        val baseColumns = columnsLister(baseDsId, baseDb, effectiveSchema(req.baseSchema, baseDb), baseTable)
        if (baseColumns.isEmpty()) throw IllegalArgumentException("基准表不存在或没有字段: $baseTable")
        val baseByName = baseColumns.associateBy { it.name.lowercase() }
        val fields = req.fields?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.map { f ->
            baseByName[f.lowercase()]?.name ?: throw IllegalArgumentException("基准表不存在字段: $f")
        }?.takeIf { it.isNotEmpty() } ?: baseColumns.map { it.name }
        if (fields.none { it.equals(keyField, ignoreCase = true) }) {
            throw IllegalArgumentException("需映射字段必须包含比对主键: $keyField")
        }
        val actualKey = baseByName[keyField.lowercase()]?.name
            ?: throw IllegalArgumentException("基准表不存在主键字段: $keyField")
        val baseFieldItems = fields.mapNotNull { baseByName[it.lowercase()] }
            .map { CompareMappingPrompts.columnItemOf(it) }
        val baseLoc = loc(baseDb, req.baseSchema, baseTable)

        // 逐目标:取全列 → 大模型预生成 → 解析过滤(字段在清单内/目标列存在/一对一/主键兜底)
        val views = specs.map { spec ->
            val dsId = spec.datasourceId!!
            val table = spec.table!!.trim()
            try {
                val ds = dataSourceService.get(dsId)
                val db = spec.db ?: ""
                val cols = columnsLister(dsId, db, effectiveSchema(spec.schema, db), table)
                if (cols.isEmpty()) throw IllegalArgumentException("目标表不存在或没有字段")
                val prompt = CompareMappingPrompts.buildMappingPrompt(
                    baseLoc, baseFieldItems, loc(db, spec.schema, table),
                    cols.map { CompareMappingPrompts.columnItemOf(it) })
                val answer = aiMappingChat.call(config, CompareMappingPrompts.SYSTEM_PROMPT, prompt)
                val mapping = CompareMappingPrompts.parseMappingSuggest(answer, fields, cols, actualKey)
                if (mapping.isEmpty()) {
                    MappingSuggestTargetView(dsId, db, spec.schema, table, null,
                        "未产出映射建议,请人工连线(可先按字段名自动匹配)")
                } else {
                    MappingSuggestTargetView(dsId, db, spec.schema, table, mapping,
                        "大模型预生成 ${mapping.size} 条映射,请人工审核后再提交")
                }
            } catch (e: Exception) {
                log.warn("字段映射预生成失败: ds={}, table={}", dsId, table, e)
                MappingSuggestTargetView(dsId, spec.db, spec.schema, table, null,
                    "预生成失败: ${e.message ?: "调用大模型失败"},请人工连线")
            }
        }
        return MappingSuggestView(views)
    }

    // ---------- 后台执行 ----------

    /** 任务执行体:读基准全量 → 逐目标比对落明细与指标 → 完成;基准失败整个任务 FAILED,单目标失败不炸任务。
     *  只接 RUNNING(PENDING 是等用户操作的静止状态,经 confirmMapping/start 翻转后才进执行器) */
    private fun run(jobId: Long) {
        val job = repo.getJob(jobId) ?: return
        if (job.status != "RUNNING") {
            log.warn("比对任务不在运行中,跳过执行: id={}, 状态={}", jobId, job.status)
            return
        }
        val jobT0 = System.currentTimeMillis()
        try {
            repo.updateStage(jobId, "连接数据源,读取基准表…${job.sampleRows?.let { "(抽样前 $it 条)" } ?: ""}")
            var stageT0 = jobT0
            val baseDs = dataSourceService.get(job.baseDatasourceId)
            val baseDialect = dialectFactory.get(baseDs.dbType!!)
            val baseSchema = effectiveSchema(job.baseSchema, job.baseDb)
            val baseColumns = columnsLister(job.baseDatasourceId, job.baseDb, baseSchema, job.baseTable)
            val baseByName = baseColumns.associateBy { it.name.lowercase() }
            val fields = parseFields(job.fieldsJson)
            // 任务级身份字段(老任务 key_fields_json 空退化 [keyField]);基准侧身份列必须全部存在
            val jobKeyFields = jobKeyFields(job)
            val keyColumns = jobKeyFields.map { f ->
                baseByName[f.lowercase()] ?: throw IllegalStateException(
                    if (jobKeyFields.size == 1) "基准表不存在主键字段: $f" else "基准表不存在身份字段: $f")
            }
            val numericFields = fields.mapNotNull { baseByName[it.lowercase()] }
                .filter { it.isNumeric() }.map { it.name }.toSet()
            // 对象名称(显示名)字段(V73 多选):提交时已解析入库;老任务(列为空)按同一规则回退,保证重跑口径一致
            val displayFields = jobDisplayFields(job).ifEmpty {
                listOfNotNull(resolveDisplayField(null, fields, baseByName, keyColumns.first().name))
            }
            // 对象对齐匹配逻辑:老任务(match_mode 空)按「只按编码」解读,与历史结果口径一致
            val mode = MatchMode.fromValue(job.matchMode)
            if (mode.requiresName && displayFields.isEmpty()) {
                log.warn("比对任务匹配逻辑 {} 缺少对象名称字段,退化为「只按编码对齐」: jobId={}", mode.value, jobId)
            }
            // 佐证字段识别(仅匹配逻辑 3 用;规则优先大模型兜底,识别不到为空 = 纯编码+名称口径)
            val evidenceFields = if (mode == MatchMode.CODE_NAME_LLM)
                resolveEvidenceFields(job.baseTable, fields, baseByName,
                    jobId = jobId, targetLabel = traceLabelOf(job.baseDb, job.baseSchema, job.baseTable))
            else emptyMap()
            // 比对字段中文名(基准侧注释优先,无注释回落字段名;同名消歧 prompt 的字段标签用,与导出口径一致)
            val fieldLabels = fields.associateWith { f ->
                baseByName[f.lowercase()]?.comment?.takeIf { it.isNotBlank() } ?: f
            }
            if (evidenceFields.isNotEmpty()) {
                log.info("比对佐证字段: jobId={}, {}", jobId,
                    evidenceFields.map { (k, f) -> "${k.label}($f)" }.joinToString("、"))
            }
            // 流程步骤日志(统一「比对步骤」前缀,现场照日志就能复述比对过程):第 1 步 任务配置解析
            log.info("比对步骤: jobId={}, 第1步 任务配置解析: 基准表 {} 字段 {} 个, 比对字段 {} 个, " +
                "身份字段 {}, 匹配逻辑 {}, 目标数待读",
                jobId, job.baseTable, baseColumns.size, fields.size, jobKeyFields(job).joinToString("+"), mode.value)
            // 基准表「数据最新更新时间」快照:探测时间字段取 MAX,失败/无字段留 NULL(导出留空)
            repo.updateBaseDataUpdatedAt(jobId, detectLatestDataTime(
                job.baseDatasourceId, job.baseDb, baseSchema, job.baseTable, baseColumns, baseDialect,
                jobId = jobId, targetLabel = traceLabelOf(job.baseDb, job.baseSchema, job.baseTable)))
            // 基准表中文名/字段注释快照:跑完后的报告/导出只读快照(现场 VPN 共用会被挤掉,跑完即断网)
            repo.updateBaseComments(jobId,
                tableCommentLister(job.baseDatasourceId, job.baseDb, baseSchema, job.baseTable),
                snapshotComments(baseColumns))
            val baseMetaMs = System.currentTimeMillis() - stageT0
            stageT0 = System.currentTimeMillis()
            // 抽样(V70):sample_rows 非空 = 双侧各「按身份列排序取前 N 条」,读取阶段文案注明便于现场看日志确认
            val sampleNote = job.sampleRows?.let { "(抽样前 $it 条)" } ?: ""
            val baseLoaded = loadRows(job.baseDatasourceId, job.baseDb, baseSchema, job.baseTable, baseDialect,
                fields.map { SelectedCol(it, baseByName.getValue(it.lowercase()).name) },
                keyColumns.map { it.name }, job.sampleRows)
            val baseMap = baseLoaded.rows
            if (baseLoaded.noKeyRows > 0) {
                log.warn("基准表 {} 读到 {} 行,其中 {} 行身份列为空(编码路不参与,仅名称/大模型可配对)",
                    job.baseTable, baseLoaded.totalRead, baseLoaded.noKeyRows)
            }
            repo.updateProgress(jobId, 1, "基准表读取完成(共 ${baseLoaded.totalRead} 行)$sampleNote")
            // 性能计时埋点(排查比对慢在哪一段):基准侧 元数据+快照 / 全量读取
            log.info("比对基准读取计时: jobId={}, 元数据+快照={}ms, 读取={}ms({}行)",
                jobId, baseMetaMs, System.currentTimeMillis() - stageT0, baseLoaded.totalRead)
            // 流程步骤日志:第 2 步 基准全量读取
            log.info("比对步骤: jobId={}, 第2步 基准读取完成: {} 行(身份列为空 {} 行){}",
                jobId, baseLoaded.totalRead, baseLoaded.noKeyRows, sampleNote)

            val targets = repo.listTargets(jobId)
            var done = 1
            for (t in targets) {
                // 进度文案展示名口径与导出一致:自定义显示名 > 库描述(schema_doc) > 数据源名快照
                val label = t.displayName?.takeIf { it.isNotBlank() }
                    ?: schemaDescOf(t.datasourceId, t.dbName, t.schemaName)
                    ?: t.dsName ?: "数据源${t.datasourceId}"
                repo.updateStage(jobId, "比对 $label…")
                repo.markTargetRunning(t.id)
                try {
                    compareOneTarget(job, t, fields, numericFields, displayFields, mode, baseMap,
                        baseLoaded.totalRead, evidenceFields, fieldLabels,
                        onLlmBatchProgress = { done, total ->
                            repo.updateStage(jobId, "比对 $label…(大模型补配 $done/$total 批)")
                        })
                } catch (e: Exception) {
                    log.warn("比对目标失败: jobId={}, 目标={}.{}: {}", jobId, t.dbName, t.tableName, e.message)
                    repo.failTarget(t.id, (e.message ?: "比对失败").take(2000))
                }
                done++
                repo.updateProgress(jobId, done, "比对 $label 完成")
            }
            repo.finishJob(jobId)
            log.info("比对任务完成: id={}, 目标数={}, 总耗时={}ms",
                jobId, targets.size, System.currentTimeMillis() - jobT0)
        } catch (e: Exception) {
            log.error("比对任务失败: id={}", jobId, e)
            repo.failJob(jobId, (e.message ?: "比对任务失败").take(2000))
        }
    }

    /**
     * 单个目标:目标侧字段解析 → 拉目标全量 → 按匹配逻辑对齐对象 → diffObjects → 批量落明细(500/批)
     * → 算指标落 compare_target。
     * 字段解析两种口径:任务带字段映射(第三步人工连线)时只认映射(`基准列名 → 目标列名`),
     * 未映射到的基准字段不进 select、由 diffObjects 记「列缺失」;无映射时按字段名忽略大小写自动匹配(旧行为)。
     * 对象身份按该目标有效身份字段组合判同:identity_json 人工覆盖优先,否则按「任务级身份 ∩ 映射键」
     * 推导(见 [resolveIdentity]);行 map 键为身份列值 trim 后以「\u0001」拼接(单字段即现状,老任务口径不变)。
     * 对齐口径按 [mode]:编码/名称两路纯对齐由 [matchObjects] 完成,匹配逻辑 3 的残余再由大模型补配;
     * 两侧实际列名都归一成基准字段名,故配对后的逐字段比较与旧口径完全一致。
     */
    private fun compareOneTarget(job: CompareRepository.JobRow, t: CompareRepository.TargetRow,
                                 fields: List<String>, numericFields: Set<String>, displayFields: List<String>,
                                 mode: MatchMode,
                                 baseMap: LinkedHashMap<String, Map<String, String?>>, baseTotalRead: Int,
                                 evidenceFields: Map<EvidenceKind, String> = emptyMap(),
                                 fieldLabels: Map<String, String> = emptyMap(),
                                 onLlmBatchProgress: (doneBatches: Int, totalBatches: Int) -> Unit = { _, _ -> }) {
        val ds = dataSourceService.get(t.datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)
        val schema = effectiveSchema(t.schemaName, t.dbName)
        // AI 判定留痕的目标快照(库.模式.表):记录时定型,任务/目标被删后留痕仍可读
        val traceTargetLabel = traceLabelOf(t.dbName, t.schemaName, t.tableName)
        // 性能计时埋点(排查比对慢在哪一段):逐目标 info 级分阶段耗时日志,见函数末尾汇总
        val targetT0 = System.currentTimeMillis()
        val cols = columnsLister(t.datasourceId, t.dbName, schema, t.tableName)
        if (cols.isEmpty()) throw IllegalStateException("目标表不存在或没有字段: ${t.tableName}")
        // 目标表中文名/字段注释快照(口径同基准表,见 run):跑完后的导出只读快照
        repo.updateTargetComments(t.id, tableCommentLister(t.datasourceId, t.dbName, schema, t.tableName),
            snapshotComments(cols))
        val byName = cols.associateBy { it.name.lowercase() }
        val mapping = parseMapping(t.fieldMappingJson).mapKeys { it.key.lowercase() }
        // 该目标的有效身份字段:人工覆盖(identity_json)优先,否则按「任务级身份 ∩ 映射键」推导
        // (纯函数 [resolveIdentity],与提交/审核校验同一口径;推导为空抛错,该目标判 FAILED 并点名列出)
        val identity = resolveIdentity(jobKeyFields(job), mapping, parseIdentityKeys(t.identityJson))
        // 目标侧身份列:显式映射只认映射(缺身份字段报错),无映射按字段名忽略大小写自动匹配(旧行为)
        val keyColumns = identity.map { baseField ->
            if (mapping.isEmpty()) {
                byName[baseField.lowercase()]
                    ?: throw IllegalStateException("目标表缺少身份列: $baseField")
            } else {
                val mapped = mapping[baseField.lowercase()]
                    ?: throw IllegalStateException("字段映射缺少身份字段: $baseField")
                byName[mapped.lowercase()]
                    ?: throw IllegalStateException("目标表缺少映射的身份列: $mapped")
            }
        }
        // 只选目标侧存在的列;缺失列不进 map,diffObjects 按「列缺失」全部计不一致
        val select = if (mapping.isEmpty()) {
            fields.mapNotNull { f -> byName[f.lowercase()]?.let { SelectedCol(f, it.name) } }
        } else {
            fields.mapNotNull { f ->
                mapping[f.lowercase()]?.let { col -> byName[col.lowercase()]?.let { SelectedCol(f, it.name) } }
            }
        }
        // 目标表「数据最新更新时间」快照:口径同基准表(探测时间字段取 MAX,失败/无字段留 NULL)
        repo.updateTargetDataUpdatedAt(t.id, detectLatestDataTime(
            t.datasourceId, t.dbName, schema, t.tableName, cols, dialect,
            jobId = job.id, targetId = t.id, targetLabel = traceTargetLabel))
        val metaMs = System.currentTimeMillis() - targetT0
        var stageT0 = System.currentTimeMillis()
        val targetLoaded = loadRows(t.datasourceId, t.dbName, schema, t.tableName, dialect, select,
            keyColumns.map { it.name }, job.sampleRows)
        val loadMs = System.currentTimeMillis() - stageT0
        val targetMap = targetLoaded.rows
        // 读取口径说明(服务端语句上限走分页 / 流式失败降级分页)透出到目标说明,现场不用翻日志
        targetLoaded.readNote?.let { repo.appendTargetNote(t.id, it) }
        // 流程步骤日志:第 3 步 目标全量读取
        log.info("比对步骤: jobId={}, 第3步 目标 {}.{} 读取完成: {} 行(有效身份 {}, 身份列为空 {} 行)",
            job.id, t.dbName, t.tableName, targetLoaded.totalRead,
            identity.joinToString("+"), targetLoaded.noKeyRows)
        if (targetLoaded.noKeyRows > 0) {
            // 身份列为空的行只走了名称/大模型两路(编码路对它无命中),透出避免「为什么编码没配上」的困惑
            log.warn("比对目标 {}.{} 读到 {} 行,其中 {} 行身份列为空(编码路不参与,仅名称/大模型可配对): jobId={}, targetId={}",
                t.dbName, t.tableName, targetLoaded.totalRead, targetLoaded.noKeyRows, job.id, t.id)
            repo.appendTargetNote(t.id, "身份列为空 ${targetLoaded.noKeyRows} 行(编码路不参与,按名称/大模型配对)")
        }

        // 对象对齐:编码/名称两路(纯函数),匹配逻辑 3 再对残余调用大模型归一化补配
        val nameFields = if (mode.requiresName) displayFields else emptyList()
        // 该目标的有效佐证字段 = 任务级佐证字段 ∩ 该目标映射覆盖(无映射按字段名自动匹配老路径要求目标侧
        // 存在同名列);目标侧取不到值的字段参与比较只会恒为中性,直接剔除并在说明里透出
        val effectiveEvidence = evidenceFields.filter { (_, field) ->
            if (mapping.isEmpty()) byName.containsKey(field.lowercase())
            else mapping.containsKey(field.lowercase())
        }
        if (evidenceFields.isNotEmpty()) {
            repo.appendTargetNote(t.id,
                if (effectiveEvidence.isNotEmpty())
                    "佐证字段: " + effectiveEvidence.map { (k, f) -> "${k.label}($f)" }.joinToString("、")
                else "佐证字段未在该目标映射内,按纯编码+名称匹配")
        }
        stageT0 = System.currentTimeMillis()
        var match = matchObjects(baseMap, targetMap, identity, nameFields, mode)
        val matchMs = System.currentTimeMillis() - stageT0
        var llmMs = 0L
        if (mode == MatchMode.CODE_NAME_LLM) {
            stageT0 = System.currentTimeMillis()
            // 佐证负证据拦截:名称路配上的对象若「区划冲突 + 位置/河流也冲突」(多属性独立指向不同),
            // 拆回残余交大模型补配/仲裁——异地同名的 1:1 配对不能凭名字直接定案
            // (编码路配对不动:编码归一化相等身份已定;单属性冲突不拆,任何单一字段都可能是错的)
            if (effectiveEvidence.isNotEmpty()) {
                val (stripped, stripCount) = stripStrongConflictNamePairs(match, baseMap, targetMap, effectiveEvidence)
                if (stripCount > 0) {
                    match = stripped
                    repo.appendTargetNote(t.id,
                        "$stripCount 对名称配对因行政区划与位置/河流均不一致拆回待判(多属性不一致)")
                }
            }
            // 流程步骤日志:第 4 步 确定性对齐(编码/名称两路 + 佐证拦截),残余交大模型
            val (baseResidue, targetResidue) = match.residues(baseMap, targetMap)
            log.info("比对步骤: jobId={}, 第4步 目标 {}.{} 对象对齐: 编码路 {} / 名称路 {} 对, " +
                "待补配残余 基准 {} 条 / 目标 {} 条",
                job.id, t.dbName, t.tableName, match.codeMatched, match.nameMatched,
                baseResidue.size, targetResidue.size)
            val ai = aiMatchResidues(match, baseMap, targetMap, identity, nameFields,
                effectiveEvidence, onLlmBatchProgress,
                jobId = job.id, targetId = t.id, targetLabel = traceTargetLabel)
            if (ai.pairs.isNotEmpty() || ai.note != null) {
                // 三路计数按来源分开累加:补配里既有归一化精确配上的 CODE/NAME 对,也有模型裁决的 LLM 对
                match = match.copy(pairs = match.pairs + ai.pairs,
                    codeMatched = match.codeMatched + ai.pairs.count { it.by == "CODE" },
                    nameMatched = match.nameMatched + ai.pairs.count { it.by == "NAME" },
                    aiMatched = ai.pairs.count { it.by == "LLM" },
                    llmNote = ai.note, llmFailed = ai.failed)
            }
            // 流程步骤日志:第 5 步 大模型归一化补配结果
            log.info("比对步骤: jobId={}, 第5步 目标 {}.{} 大模型补配: 新配对 {} 对" +
                "(归一化编码 {} / 名称 {} / 模型裁决 {})",
                job.id, t.dbName, t.tableName, ai.pairs.size,
                ai.pairs.count { it.by == "CODE" }, ai.pairs.count { it.by == "NAME" },
                ai.pairs.count { it.by == "LLM" })
            // 同名二轮消歧:同名歧义组带区分度裁剪后的字段取值(字段标签用基准侧注释)再交大模型重判,
            // 避免名称首配张冠李戴(如编码全空的多个同名水库,按经纬度/位置区分后重新配对;
            // 首轮 LLM 配对的同名行同样参与——它只见过编码+名称,并不比名称配对多知道什么)
            val refine = aiRefineSameNameGroups(match, baseMap, targetMap, identity, nameFields, fields,
                fieldLabels, jobId = job.id, targetId = t.id, targetLabel = traceTargetLabel)
            if (refine.pairs !== match.pairs) {
                // 三路计数按配对来源重算(LLM 配对可能被重画为名称配对,原计数会失真)
                match = match.copy(pairs = refine.pairs,
                    codeMatched = refine.pairs.count { it.by == "CODE" },
                    nameMatched = refine.pairs.count { it.by == "NAME" },
                    aiMatched = refine.pairs.count { it.by == "LLM" },
                    llmNote = refine.note ?: match.llmNote,
                    llmFailed = match.llmFailed || refine.failed)
            } else if (refine.note != null) {
                match = match.copy(llmNote = match.llmNote ?: refine.note,
                    llmFailed = match.llmFailed || refine.failed)
            }
            llmMs = System.currentTimeMillis() - stageT0
        }
        stageT0 = System.currentTimeMillis()
        val result = diffObjects(baseMap, targetMap, fields, identity, numericFields, displayFields,
            match.pairs)
        val diffMs = System.currentTimeMillis() - stageT0

        stageT0 = System.currentTimeMillis()
        val all = result.same + result.diff + result.missing + result.extra
        all.chunked(DIFF_BATCH_SIZE).forEach { batch ->
            repo.insertDiffs(job.id, t.id, batch.map { d ->
                CompareRepository.DiffInput(d.objectKey.take(500), d.objectName.take(500), d.diffType,
                    d.diffs?.let { objectMapper.writeValueAsString(truncateDiffs(it)) }, d.matchBy)
            })
        }
        val writeMs = System.currentTimeMillis() - stageT0

        // base_count = 基准表实际读到的总行数(含身份列为空被跳过的行;跳过行有 warn 日志可见),
        // 与 target_count 同为物理行数口径,导出总览「条数/与基准差」两侧可比
        val baseCount = baseTotalRead
        val coverage = if (baseCount == 0) 0.0 else result.matchedCount.toDouble() / baseCount
        val consistencyDenominator = result.matchedCount.toLong() * fields.size
        val fieldConsistency = if (consistencyDenominator == 0L) 1.0
        else 1.0 - result.fieldMismatchCount.toDouble() / consistencyDenominator
        val completeness = if (result.comparedCells == 0) 1.0
        else result.nonNullCells.toDouble() / result.comparedCells
        val score = coverage * 0.4 + fieldConsistency * 0.4 + completeness * 0.2
        // target_count = 目标侧实际读到的总行数(noKeyRows 行经代理键也进了比对,单列计数见 V63)
        repo.updateTargetStats(t.id, baseCount, targetLoaded.totalRead, result.matchedCount, result.missing.size,
            result.extra.size, result.fieldMismatchCount, coverage, fieldConsistency, completeness, score,
            match.codeMatched, match.nameMatched, match.aiMatched,
            noKeyRows = targetLoaded.noKeyRows)
        // 性能计时汇总:元数据+快照 / 读取 / 配对 / 大模型 / 比较 / 落库 / 合计,对照找慢的那段
        log.info("比对目标计时: jobId={}, 目标={}.{}, 元数据+快照={}ms, 读取={}ms({}行), 配对={}ms, 大模型={}ms, " +
            "比较={}ms, 落库={}ms({}行), 合计={}ms",
            job.id, t.dbName, t.tableName, metaMs, loadMs, targetLoaded.totalRead, matchMs, llmMs,
            diffMs, writeMs, all.size, System.currentTimeMillis() - targetT0)
        // 流程步骤日志:第 6 步 逐字段比对 + 指标落库
        log.info("比对步骤: jobId={}, 第6步 目标 {}.{} 比对完成: 一致 {} / 不一致 {} / 缺失 {} / 多余 {}, " +
            "明细落库 {} 行; 覆盖率 {}, 字段一致率 {}, 完整率 {}",
            job.id, t.dbName, t.tableName, result.same.size, result.diff.size,
            result.missing.size, result.extra.size, all.size,
            "%.2f".format(coverage), "%.2f".format(fieldConsistency), "%.2f".format(completeness))
        if (match.llmNote != null) {
            log.info("比对目标大模型补配: jobId={}, 目标={}.{}, {}", job.id, t.dbName, t.tableName, match.llmNote)
        }
        if (match.llmFailed) {
            // 大模型补配失败不炸任务(该目标仍按编码/名称口径完成),把原因挂到目标上供人工判断
            repo.appendTargetNote(t.id, match.llmNote ?: "大模型归一化匹配失败,残余对象按未匹配处理")
        }
    }

    /**
     * 单侧取数结果:行 map(键=身份列组合值;身份列为空的行以 [NO_KEY_ROW_PREFIX]+序号 的行内代理键进 map,
     * 不丢弃——编码路自然跳过它,名称/大模型两路可把它配对)与实际读到的总行数、身份列为空的行数
     */
    internal data class LoadedRows(
        val rows: LinkedHashMap<String, Map<String, String?>>,
        /** 实际从库中读到的总行数 = 导出总览「条数」口径(代理键行进 map 后恒等于 rows.size) */
        val totalRead: Int,
        /** 其中身份列任一为空的行数:无组合身份值,编码路不参与,仅名称/大模型两路可配对(透出到目标说明/导出差异原因) */
        val noKeyRows: Int,
        /** 读取口径说明(服务端语句上限走分页 / 流式失败降级分页),非空时目标侧透出到目标说明 */
        val readNote: String? = null,
    )

    /**
     * 拉全量进内存:key=身份列组合值(各身份列值 trim 后以「\u0001」拼接,单列即现状),
     * 行值 rs.getObject()?.toString();身份列任一为空的行**不丢弃**:以行内代理键([NO_KEY_ROW_PREFIX]+序号)
     * 进 map——「任意一边 code 空就用 name 匹配」的口径要求它们进名称/大模型配对,编码路对代理键自然无命中;
     * 单侧超过 [MAX_SIDE_ROWS] 抛 IllegalStateException;
     * [maxRows] 非空 = 抽样(V70):确定性「按身份列排序取前 N 条」,首页 limit 取 min(N, 5000),
     * 累计读满 N 即停(分页按首个身份列升序,双侧同一口径才有交集)
     *
     * 全量(非抽样)读取主路径是**流式单扫**(无 ORDER BY 无分页,一次顺序全扫,DB 侧成本最低),
     * 两种情况下走分页(按首个身份列 orderBy,pageSize 5000):
     * 1. 探测到服务端存在语句执行上限([DbDialect.probeServerStatementLimitSeconds])——流式长语句必被杀;
     * 2. 流式读被服务端中止或驱动不支持——降级分页, LoadedRows.readNote 透出原因
     */
    private fun loadRows(datasourceId: Long, database: String, schema: String, table: String,
                         dialect: DbDialect, select: List<SelectedCol>, keyColumns: List<String>,
                         maxRows: Int? = null): LoadedRows {
        // 身份列对应的行 map 键(基准字段名):身份字段恒入比对字段,select 必然包含全部身份列
        val keyFields = keyColumns.map { kc ->
            select.firstOrNull { it.column == kc }?.field
                ?: throw IllegalStateException("比对字段缺少身份列: $kc")
        }
        dataSourceService.getConnection(datasourceId, database.ifBlank { null }).use { conn ->
            if (maxRows == null) {
                val limitSec = dialect.probeServerStatementLimitSeconds(conn)
                if (limitSec != null) {
                    log.info("比对读取: 服务端存在语句执行上限 {}s,走分页读取: {}.{}", limitSec, schema, table)
                    return loadRowsPaged(conn, dialect, schema, table, select, keyFields, keyColumns, null,
                        "服务端语句执行上限 ${limitSec}s,按分页读取")
                }
                try {
                    return loadRowsStreaming(conn, dialect, schema, table, select, keyFields)
                } catch (e: Exception) {
                    // 行数上限是业务结论不是读取故障,直接抛(降级分页只会再读一遍再抛同一个错)
                    if (e is IllegalStateException && e.message?.startsWith("单侧行数超过上限") == true) throw e
                    log.warn("比对流式读取失败,降级分页读取: {}.{}: {}", schema, table, e.message)
                    return loadRowsPaged(conn, dialect, schema, table, select, keyFields, keyColumns, null,
                        "流式读取失败(${abbreviate(e.message)}),已降级分页读取")
                }
            }
            return loadRowsPaged(conn, dialect, schema, table, select, keyFields, keyColumns, maxRows, null)
        }
    }

    /** 流式单扫:一条无 ORDER BY 的 SELECT 全量顺序读,驱动按方言 [DbDialect.configureStreamingRead] 的配置分批/逐行流式返回 */
    private fun loadRowsStreaming(conn: java.sql.Connection, dialect: DbDialect, schema: String, table: String,
                                  select: List<SelectedCol>, keyFields: List<String>): LoadedRows {
        val map = LinkedHashMap<String, Map<String, String?>>()
        var totalRead = 0
        var noKeyRows = 0
        val oldAutoCommit = conn.autoCommit
        conn.createStatement().use { stmt ->
            try {
                dialect.configureStreamingRead(conn, stmt)
                // 流式读是单条长语句,不适用「单页超时」口径:不设 queryTimeout(驱动默认 0=不限制),
                // 挂死连接由方言连接超时属性(socket/ReadTimeout)兜底;服务端语句上限已由上游探测规避
                stmt.executeQuery(dialect.streamAllSql(schema, table, select.map { it.column })).use { rs ->
                    while (rs.next()) {
                        totalRead++
                        if (map.size >= MAX_SIDE_ROWS) {
                            throw IllegalStateException("单侧行数超过上限 ${MAX_SIDE_ROWS / 10000} 万,请缩小比对范围")
                        }
                        val row = LinkedHashMap<String, String?>(select.size)
                        select.forEachIndexed { i, c -> row[c.field] = rs.getObject(i + 1)?.toString() }
                        val key = compositeKey(row, keyFields)
                        if (key == null) {
                            noKeyRows++
                            map[NO_KEY_ROW_PREFIX + noKeyRows] = row
                        } else {
                            map[key] = row
                        }
                        if (totalRead % STREAM_PROGRESS_LOG_EVERY == 0) {
                            log.debug("比对流式读取进度: {}.{} 已读 {} 行", schema, table, totalRead)
                        }
                    }
                }
            } finally {
                // PG 系流式配置改了 autoCommit,归还池前恢复(Hikari 回收也会重置,双保险)
                if (conn.autoCommit != oldAutoCommit) conn.autoCommit = oldAutoCommit
            }
        }
        return LoadedRows(map, totalRead, noKeyRows)
    }

    /** 分页拉全量(抽样 / 服务端有语句上限 / 流式失败降级):按首个身份列 orderBy,pageSize 5000 */
    private fun loadRowsPaged(conn: java.sql.Connection, dialect: DbDialect, schema: String, table: String,
                              select: List<SelectedCol>, keyFields: List<String>, keyColumns: List<String>,
                              maxRows: Int?, readNote: String?): LoadedRows {
        val map = LinkedHashMap<String, Map<String, String?>>()
        var totalRead = 0
        var noKeyRows = 0
        conn.createStatement().use { stmt ->
            // 与分段扫描同口径的单条 SQL 超时(系统设置可改)
            stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
            var offset = 0L
            while (true) {
                var pageRows = 0
                // 抽样:首页与每页都只取「还差多少条」,读满 maxRows 即不再翻页
                val limit = if (maxRows == null) PAGE_SIZE
                else minOf(PAGE_SIZE, (maxRows - totalRead).coerceAtLeast(1))
                stmt.executeQuery(dialect.pageRowsSql(conn, schema, table, select.map { it.column },
                    null, dialect.quote(keyColumns.first()), offset, limit)).use { rs ->
                    while (rs.next()) {
                        pageRows++
                        totalRead++
                        if (map.size >= MAX_SIDE_ROWS) {
                            throw IllegalStateException("单侧行数超过上限 ${MAX_SIDE_ROWS / 10000} 万,请缩小比对范围")
                        }
                        val row = LinkedHashMap<String, String?>(select.size)
                        select.forEachIndexed { i, c -> row[c.field] = rs.getObject(i + 1)?.toString() }
                        val key = compositeKey(row, keyFields)
                        if (key == null) {
                            // 身份列为空:行内代理键进 map(编码路跳过、名称/大模型路可配对),不静默丢行
                            noKeyRows++
                            map[NO_KEY_ROW_PREFIX + noKeyRows] = row
                        } else {
                            map[key] = row
                        }
                    }
                }
                if (pageRows < limit) break
                if (maxRows != null && totalRead >= maxRows) break
                offset += PAGE_SIZE
            }
        }
        return LoadedRows(map, totalRead, noKeyRows, readNote)
    }

    // ---------- 数据最新更新时间(导出总览) ----------

    /**
     * 探测并读取一张表的「数据最新更新时间」:名称/注释规则优先([CompareTimeFieldPrompts.detectByRule]),
     * 没有命中则交大模型语义挑字段,命中后对该列执行 MAX() 取最新值。
     * 任何一步失败(未配置大模型/调用失败/无合适字段/取数失败)都返回 null,由调用方落 NULL、导出留空,
     * 绝不影响比对主流程(只记 debug)。
     */
    private fun detectLatestDataTime(datasourceId: Long, database: String, schema: String, table: String,
                                     columns: List<ColumnMeta>, dialect: DbDialect,
                                     jobId: Long? = null, targetId: Long? = null,
                                     targetLabel: String? = null): String? {
        val column = try {
            pickLatestTimeColumn(columns, table) { t, cols -> pickTimeColumnByAi(t, cols, jobId, targetId, targetLabel) }
        } catch (e: Exception) {
            log.debug("最新更新时间字段识别失败(忽略): {}: {}", table, e.message)
            null
        } ?: return null
        return try {
            dataSourceService.getConnection(datasourceId, database.ifBlank { null }).use { conn ->
                conn.createStatement().use { stmt ->
                    // 与分段扫描/比对读行同口径的单条 SQL 超时(系统设置可改)
                    stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
                    stmt.executeQuery(dialect.maxValueSql(schema, table, column.name)).use { rs ->
                        if (rs.next()) formatLatestTime(rs.getObject(1)) else null
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("最新更新时间取数失败(忽略): {}.{}.{}: {}", database, schema, table, e.message)
            null
        }
    }

    // ---------- AI 判定留痕(compare_ai_trace,记录失败绝不炸比对主流程) ----------

    /** 留痕记录:jobId 空(交互式预生成等无任务场景)不落;组装/写入任何失败只记 warn,绝不上抛 */
    private fun recordTrace(jobId: Long?, build: () -> CompareAiTrace) {
        if (jobId == null) return
        try {
            aiTraceRecorder(build())
        } catch (e: Exception) {
            log.warn("比对 AI 判定留痕记录失败(忽略): jobId={}: {}", jobId, e.message)
        }
    }

    /** 留痕的请求内容口径:与 AiService 落 ai_usage_log 的 [system]+[user] 拼装一致(落库前由仓储截断) */
    private fun traceRequestContent(systemPrompt: String, userPrompt: String): String =
        "[system]\n$systemPrompt\n\n[user]\n$userPrompt"

    /** 留痕 target_label 口径:库.模式.表(空段省略),与 suggestMappings 的定位串 loc 一致 */
    private fun traceLabelOf(db: String?, schema: String?, table: String) =
        listOfNotNull(db?.takeIf { it.isNotBlank() }, schema?.takeIf { it.isNotBlank() }, table)
            .joinToString(".")

    /** AI 判定留痕查询(任务 → 各次大模型调用的输入/输出/逐条判定;result_json 解析失败为 null,前端兜底展示原文) */
    fun listAiTraces(jobId: Long): List<CompareAiTraceView> {
        repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val traceRepo = aiTraceRepo ?: return emptyList()
        return traceRepo.listByJob(jobId).map { r ->
            CompareAiTraceView(r.id, r.jobId, r.targetId, r.targetLabel, r.scene, r.stage, r.batchNo,
                r.model, r.requestContent, r.responseContent, parseTraceResultJson(r.resultJson),
                r.durationMs, r.createdAt)
        }
    }

    /** result_json 解析为对象/数组;解析失败(老数据/截断)返回 null,前端兜底展示原文 */
    private fun parseTraceResultJson(json: String?): Any? =
        json?.let { runCatching { objectMapper.readValue(it, Any::class.java) }.getOrNull() }

    /** 时间字段语义匹配:未配置大模型/调用失败返回 null(降级为导出留空,不炸任务);每次调用落 TIME 留痕 */
    private fun pickTimeColumnByAi(table: String, columns: List<ColumnMeta>,
                                   jobId: Long? = null, targetId: Long? = null,
                                   targetLabel: String? = null): ColumnMeta? {
        val config = aiConfigService?.findConfig() ?: return null
        val prompt = CompareTimeFieldPrompts.buildPrompt(table, columns)
        val callStart = System.nanoTime()
        val answer = try {
            aiTimeChat.call(config, CompareTimeFieldPrompts.SYSTEM_PROMPT, prompt)
        } catch (e: Exception) {
            log.debug("最新更新时间字段语义匹配调用失败(忽略): {}: {}", table, e.message)
            val durationMs = (System.nanoTime() - callStart) / 1_000_000
            recordTrace(jobId) {
                CompareAiTrace(jobId = jobId!!, targetId = targetId, targetLabel = targetLabel,
                    scene = AiScene.COMPARE_TIME.name, stage = CompareAiTrace.STAGE_TIME,
                    model = config.model,
                    requestContent = traceRequestContent(CompareTimeFieldPrompts.SYSTEM_PROMPT, prompt),
                    responseContent = "调用失败: ${abbreviate(e.message)}", durationMs = durationMs)
            }
            return null
        }
        val durationMs = (System.nanoTime() - callStart) / 1_000_000
        val picked = CompareTimeFieldPrompts.parseAnswer(answer, columns)
        recordTrace(jobId) {
            CompareAiTrace(jobId = jobId!!, targetId = targetId, targetLabel = targetLabel,
                scene = AiScene.COMPARE_TIME.name, stage = CompareAiTrace.STAGE_TIME,
                model = config.model,
                requestContent = traceRequestContent(CompareTimeFieldPrompts.SYSTEM_PROMPT, prompt),
                responseContent = answer,
                resultJson = objectMapper.writeValueAsString(mapOf("field" to picked?.name)),
                durationMs = durationMs)
        }
        return picked
    }

    /** MAX() 取值的展示格式化:时间族统一「yyyy-MM-dd HH:mm:ss」,其余原样 toString */
    private fun formatLatestTime(value: Any?): String? = when (value) {
        null -> null
        is java.sql.Timestamp -> value.toLocalDateTime().format(LATEST_TIME_FORMATTER)
        is java.time.LocalDateTime -> value.format(LATEST_TIME_FORMATTER)
        is java.time.OffsetDateTime -> value.toLocalDateTime().format(LATEST_TIME_FORMATTER)
        is java.time.Instant -> java.time.LocalDateTime.ofInstant(value, java.time.ZoneId.systemDefault())
            .format(LATEST_TIME_FORMATTER)
        is java.util.Date -> java.time.LocalDateTime.ofInstant(value.toInstant(), java.time.ZoneId.systemDefault())
            .format(LATEST_TIME_FORMATTER)
        else -> value.toString()
    }

    // ---------- 佐证字段识别(匹配逻辑 3 的实体解析辅助证据) ----------

    /**
     * 识别基准表的佐证字段(行政区划/位置/所在河流;经纬度明确排除):规则(名称/注释)优先,
     * 规则一类都没命中才由大模型兜底挑(校验 ∈ 比对字段);识别不到返回空 map,
     * 匹配流程退化为纯编码+名称口径,不阻断任务。识别结果与「字段映射连线」求交集后逐目标生效
     */
    private fun resolveEvidenceFields(table: String, fields: List<String>,
                                      baseByName: Map<String, ColumnMeta>,
                                      jobId: Long? = null, targetLabel: String? = null): Map<EvidenceKind, String> {
        val hints = fields.map { f ->
            CompareEvidence.FieldHint(f, baseByName[f.lowercase()]?.comment)
        }
        val byRule = CompareEvidence.detectByRule(hints)
        if (byRule.isNotEmpty()) return byRule
        val config = aiConfigService?.findConfig() ?: return emptyMap()
        val prompt = CompareEvidence.buildDetectPrompt(table, hints)
        val callStart = System.nanoTime()
        val answer = try {
            aiEvidenceChat.call(config, CompareEvidence.SYSTEM_PROMPT, prompt)
        } catch (e: Exception) {
            log.debug("佐证字段兜底识别调用失败(忽略): {}: {}", table, e.message)
            val durationMs = (System.nanoTime() - callStart) / 1_000_000
            recordTrace(jobId) {
                CompareAiTrace(jobId = jobId!!, targetLabel = targetLabel,
                    scene = AiScene.COMPARE_EVIDENCE.name, stage = CompareAiTrace.STAGE_EVIDENCE,
                    model = config.model,
                    requestContent = traceRequestContent(CompareEvidence.SYSTEM_PROMPT, prompt),
                    responseContent = "调用失败: ${abbreviate(e.message)}", durationMs = durationMs)
            }
            return emptyMap()
        }
        val durationMs = (System.nanoTime() - callStart) / 1_000_000
        val parsed = CompareEvidence.parseDetectAnswer(answer, hints)
        recordTrace(jobId) {
            CompareAiTrace(jobId = jobId!!, targetLabel = targetLabel,
                scene = AiScene.COMPARE_EVIDENCE.name, stage = CompareAiTrace.STAGE_EVIDENCE,
                model = config.model,
                requestContent = traceRequestContent(CompareEvidence.SYSTEM_PROMPT, prompt),
                responseContent = answer,
                // 佐证字段可多类(行政区划/位置/所在河流),按「类名 → 列名」逐条落判定结果
                resultJson = objectMapper.writeValueAsString(
                    mapOf("fields" to parsed.entries.associate { (k, f) -> k.label to f })),
                durationMs = durationMs)
        }
        return parsed
    }

    // ---------- 查询 / 归档 / 删除 ----------

    /** 任务列表(新的在前,分页);includeArchived=true 时含已归档;filter 各维度下推 SQL AND 组合 */
    fun list(includeArchived: Boolean, filter: CompareRepository.JobFilter = CompareRepository.JobFilter(),
             page: Int? = null, size: Int? = null): CompareJobPage {
        val p = (page ?: 1).coerceAtLeast(1)
        val s = (size ?: 20).coerceIn(1, 500)
        val rows = repo.listJobs(includeArchived, filter, p, s).map { toJobView(it) }
        return CompareJobPage(rows, repo.countJobs(includeArchived, filter), p, s)
    }

    /** 后台任务中心轮询:RUNNING 任务瘦出行(跨全部库,1s 一轮;不解析比对字段 JSON/不解析数据源名) */
    fun listActive(): List<CompareJobActiveView> {
        val jobs = repo.listActiveJobs()
        val counts = repo.countActiveTargets(jobs.map { it.id })
        return jobs.map { r ->
            val (targetTotal, targetDone) = counts[r.id] ?: (0 to 0)
            CompareJobActiveView(
                r.id, r.name, r.baseDb, r.baseSchema, r.baseTable,
                r.compareMode ?: CompareMode.ROW.value, r.status, r.stage, r.totalUnits, r.doneUnits,
                if (r.totalUnits > 0) r.doneUnits * 100 / r.totalUnits else 0,
                r.error, r.startedAt, targetTotal, targetDone,
            )
        }
    }

    /** 任务详情:任务字段 + 目标指标列表 */
    fun detail(id: Long): CompareJobDetailView {
        val job = repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        return CompareJobDetailView(toJobView(job), repo.listTargets(id).map { toTargetView(it) })
    }

    /** 差异明细分页:targetId/diffType/kw 组合过滤;diffType 非法值 400 */
    fun diffs(jobId: Long, targetId: Long?, diffType: String?, kw: String?, page: Int?, size: Int?): CompareDiffPage {
        repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val type = diffType?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (type != null && type !in DIFF_TYPES) throw IllegalArgumentException("非法差异类型: $diffType")
        val p = (page ?: 1).coerceAtLeast(1)
        val s = (size ?: 20).coerceIn(1, 500)
        val (rows, total) = repo.diffsPage(jobId, targetId, type, kw, p, s)
        return CompareDiffPage(rows.map { r ->
            CompareDiffRow(r.id, r.targetId, r.objectKey, r.objectName, r.diffType,
                parseDiffs(r.diffJson), r.matchBy)
        }, total, p, s)
    }

    fun archive(id: Long, archived: Boolean) {
        repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        repo.setArchived(id, archived)
    }

    /** 删除任务(tx 级联删三表 + 跨库 AI 判定留痕,失败记 warn 不阻塞);RUNNING 中禁止删(PENDING 是静止状态,放行) */
    fun delete(id: Long) {
        val job = repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        if (job.status == "RUNNING") throw IllegalStateException("任务运行中,不能删除")
        repo.deleteJob(id)
        // AI 判定留痕在 AI 用量库,跨库不进主库 tx;删除失败只记 warn(仓储内部兜底),不影响任务删除
        aiTraceRepo?.deleteByJob(id)
        log.info("比对任务已删除: id={}, 名称={}", id, job.name)
    }

    /**
     * 批量删除(列表勾选删除):逐任务复用 [delete] 的级联删除口径;RUNNING 跳过(勾选后开跑的兜底,
     * 前端勾选时已不可选运行中任务),不存在的 id 也计入 skipped。返回 {deleted, skipped}
     */
    fun deleteBatch(ids: List<Long>): Map<String, Any> {
        val deleted = ArrayList<Long>()
        val skipped = ArrayList<Long>()
        for (id in ids.distinct()) {
            val job = repo.getJob(id)
            if (job == null || job.status == "RUNNING") {
                skipped.add(id)
                continue
            }
            repo.deleteJob(id)
            // AI 判定留痕级联清理(跨库,失败只记 warn,口径同单删)
            aiTraceRepo?.deleteByJob(id)
            deleted.add(id)
            log.info("比对任务已删除: id={}, 名称={}", id, job.name)
        }
        return mapOf("deleted" to deleted, "skipped" to skipped)
    }

    /**
     * 差异导出文件名:任务 ID 前缀 + 任务名(`\/:*?"<>|` 清洗为 `_`),任务名为空回退「{id}-比对总览.xlsx」。
     * ID 前缀避免同名任务互相覆盖;导入任务的任务名本身带 文件名-sheet 名,导出件可直接对上来源
     */
    fun exportFileName(id: Long): String {
        val job = repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        return exportFileNameOf(id, job.name)
    }

    /** 差异导出文件名(纯函数,[exportFileName]/[exportToFile]/列表 exportFileOk 共用同一命名口径) */
    private fun exportFileNameOf(id: Long, name: String): String {
        val base = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (base.isEmpty()) "$id-比对总览.xlsx" else "$id-$base.xlsx"
    }

    /** 导出结果视图:[exportToFile] 返回,前端通知(可打开文件/文件夹)用 */
    data class ExportFileResult(val path: String, val name: String, val size: Long, val checksum: String)

    /**
     * 导出比对报告到 <数据目录>/compare/(服务端直存,两种形态同一行为,不再走 downloadFile 流):
     * 临时文件生成 → SHA-256 → rename 正式名(同名覆盖只留最后一次;任务改名致文件名变化时清掉旧件)
     * → 落库 export_status/checksum(失败不落库,保留上次成功态)。
     */
    fun exportToFile(id: Long): ExportFileResult {
        val dir = compareDir ?: throw IllegalStateException("比对导出目录未配置")
        val job = repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        Files.createDirectories(dir)
        val name = exportFileNameOf(id, job.name)
        val target = dir.resolve(name)
        val tmp = dir.resolve("$name.${UUID.randomUUID().toString().substring(0, 8)}.part")
        try {
            Files.newOutputStream(tmp).use { out -> exportDiff(id, out) }
            val checksum = sha256(tmp)
            // 任务改名后旧名文件已无人引用,清掉避免堆积;先删旧再覆盖新,任一步失败都还留有一份完整件
            val old = job.exportFile?.takeIf { it != name }
            if (old != null) Files.deleteIfExists(dir.resolve(old))
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            repo.updateExport(id, name, checksum)
            exportChecksumCache.remove(target.toAbsolutePath().toString())
            log.info("比对报告导出完成: taskId={}, 文件={}({} bytes)", id, target, Files.size(target))
            return ExportFileResult(target.toAbsolutePath().toString(), name, Files.size(target), checksum)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    /** 「打开文件」可点口径(V65):已导出(DONE)且文件存在且 SHA-256 与库中记录一致;带缓存,列表轮询不反复整文件哈希 */
    fun exportFileOk(exportStatus: String?, exportFile: String?, exportChecksum: String?): Boolean {
        val dir = compareDir ?: return false
        if (exportStatus != "DONE" || exportFile == null || exportChecksum == null) return false
        val file = dir.resolve(exportFile)
        return Files.isRegularFile(file) && checksumCached(file) == exportChecksum
    }

    /** 「打开文件」路径:已导出且 checksum 一致才放行;文件被改/删给明确提示 */
    fun resolveExportPath(id: Long): Path {
        val dir = compareDir ?: throw IllegalStateException("比对导出目录未配置")
        val job = repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        val file = job.exportFile?.let { dir.resolve(it) }
        if (job.exportStatus != "DONE" || file == null || !Files.isRegularFile(file)) {
            throw IllegalStateException("尚未生成比对报告导出件,请先「导出表格」")
        }
        if (checksumCached(file) != job.exportChecksum) {
            throw IllegalStateException("比对报告导出件已被修改或损坏,请重新「导出表格」")
        }
        return file
    }

    /** 「打开文件夹」路径:有完整导出件则定位到它(文件管理器选中),否则退化为打开 compare 目录本身 */
    fun revealExportPath(id: Long): Path {
        val dir = compareDir ?: throw IllegalStateException("比对导出目录未配置")
        val job = repo.getJob(id) ?: throw IllegalArgumentException("比对任务不存在: $id")
        val file = job.exportFile?.let { dir.resolve(it) }
        if (file != null && Files.isRegularFile(file)) return file
        Files.createDirectories(dir)
        return dir
    }

    /** SHA-256 hex,按 (path,size,mtime) 缓存(导出件写后不变,文件被改即自动失效重算) */
    private fun checksumCached(file: Path): String {
        val abs = file.toAbsolutePath().toString()
        val size = Files.size(file)
        val mtime = Files.getLastModifiedTime(file).toMillis()
        val hit = exportChecksumCache[abs]
        if (hit != null && hit.first == size && hit.second == mtime) return hit.third
        val sha = sha256(file)
        exportChecksumCache[abs] = Triple(size, mtime, sha)
        return sha
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).buffered().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- 差异导出 / 质量报告 ----------

    /**
     * 差异导出(按客户既有核对表格式):
     * - sheet 1「总览」:一行一个系统(首行基准表),列口径见 [EXPORT_OVERVIEW_HEADERS]
     * - sheet 2「行级对比明细」:一行一个「对象 × 比对目标」,基准/业务两侧各带 表英文名/表中文名 +
     *   编码字段/编码 + 名称字段/名称(字段列取任务主键/显示名字段及其在目标表的映射列,无映射按同名),
     *   其后差异说明与末列差异类型(不一致/缺失/多余,见 [writeRowLevelSheet])
     * - sheet 3「字段级差异汇总」:一行一个「比对目标 × 基准字段」,只列与该业务表有连线的字段
     *   (无映射按名称自动匹配时列全部比对字段),统计差异数量并按 缺失/多余/不一致
     *   三类拆分(见 [writeFieldSummarySheet])
     * - sheet 4「数据级字段对比差异总览」:一行一条数据(对象),按系统给 对比字段数/相同字段数/不同字段数
     *   数量统计(见 [writeColumnDetailSheet])
     * - sheet 5「列级对比明细」:所有比对系统的逐字段取值横向合并,一行一个「对象 × 基准字段」,
     *   两行表头(第二行显示各侧表定位「表名 / 系统名 / （库.模式）」),左侧 5 列与两行表头冻结(见 [writeMergedDetailSheet])
     * - 其后每个比对目标一个字段级明细 sheet:sheet 名「序号_表名_数据源名」,序号与总览行顺序一一对应;
     *   每个 sheet 装该系统**全部**差异,一行一个「对象 × 字段」
     *   (DIFF 逐不一致字段展开,MISSING/EXTRA 按整行快照逐比对字段展开)
     *
     * 明细行数受 Excel 单表上限约束(1,048,576 行),超过 [MAX_ROWS_PER_SHEET] 时截断并在 sheet 内标注;
     * 系统数超过 Excel 的 sheet 上限(65530)时直接报错,避免导出损坏的文件。
     */
    fun exportDiff(jobId: Long, out: OutputStream) {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val targets = repo.listTargets(jobId)
        if (targets.size > MAX_SHEETS - 1) {
            throw IllegalStateException("比对系统数超过 Excel 上限(${MAX_SHEETS - 1}),无法导出为比对报告")
        }
        val context = exportContext(job, targets)
        // 每目标差异明细只读一次:总览「差异条数」、行级/字段级汇总与逐目标明细 sheet 复用同一份
        val diffsByTarget = LinkedHashMap<Long, List<CompareRepository.DiffRow>>()
        for (t in targets) diffsByTarget[t.id] = repo.listDiffsForExport(jobId, t.id)
        // 任务级身份字段(老任务 key_fields_json 空退化 [keyField])与各目标有效身份;
        // 推导失败(脏数据)按任务级身份兜底,导出必须能出文件
        val jobKeyFields = jobKeyFields(job)
        val identityByTarget = targets.associate { t ->
            t.id to try {
                resolveIdentity(jobKeyFields, parseMapping(t.fieldMappingJson), parseIdentityKeys(t.identityJson))
            } catch (e: Exception) {
                log.debug("目标有效身份字段推导失败,按任务级身份导出(忽略): targetId={}: {}", t.id, e.message)
                jobKeyFields
            }
        }
        val overview = buildOverviewRows(job, targets,
            targets.associate { t ->
                t.id to objectLevelDiffs(identityByTarget.getValue(t.id), diffsByTarget.getValue(t.id))
            }, context)

        SXSSFWorkbook(SXSSF_ROW_WINDOW).use { wb ->
            // 差异高亮样式(与前端差异明细页问题格同一底色 #ffebee):一眼定位不一致单元格
            val diffStyle = wb.createCellStyle()
            (diffStyle as XSSFCellStyle).apply {
                fillPattern = FillPatternType.SOLID_FOREGROUND
                setFillForegroundColor(XSSFColor(DIFF_FILL_RGB, null))
            }
            // 表名单元格多行格式(表名/系统名/（库.模式）)需要自动换行才能完整显示
            val wrapStyle = wb.createCellStyle().apply { wrapText = true }
            writeOverviewSheet(wb, overview, wrapStyle)
            // 行级对比明细(固定第二个 sheet):一行一个「对象 × 比对目标」
            writeRowLevelSheet(wb, job, targets, diffsByTarget, context, identityByTarget, wrapStyle)
            // 字段级差异汇总(固定第三个 sheet):一行一个「比对目标 × 基准字段」
            writeFieldSummarySheet(wb, job, targets, diffsByTarget, context, wrapStyle)
            // 数据级字段对比差异总览(固定第四个 sheet):一行一条数据,按系统给字段数统计
            writeColumnDetailSheet(wb, job, targets, diffsByTarget, context, diffStyle, wrapStyle)
            // 列级对比明细(固定第五个 sheet):所有系统逐字段取值横向合并,左侧 5 列冻结
            writeMergedDetailSheet(wb, job, targets, diffsByTarget, context, diffStyle, wrapStyle)
            // 总览里的每个目标行一个明细 sheet,序号与总览行顺序一致
            val used = mutableSetOf(OVERVIEW_SHEET_NAME, ROW_LEVEL_SHEET_NAME, FIELD_SUMMARY_SHEET_NAME,
                COLUMN_DETAIL_SHEET_NAME, MERGED_DETAIL_SHEET_NAME)
            var idx = 0
            for (t in targets) {
                val diffs = diffsByTarget[t.id].orEmpty()
                if (diffs.isEmpty()) continue
                // sheet 名「序号_表名_系统名」:系统名按「自定义显示名 > 库描述 > 数据源名快照」口径;同一系统多张表互比靠表名区分,表名必须靠前(31 字符上限从尾部截,别截掉表名)
                val sheet = wb.createSheet(
                    ExcelCells.sheetName("${++idx}_${t.tableName}_${t.displayName
                        ?: context.schemaDesc(t.datasourceId, t.dbName, t.tableName)
                        ?: t.dsName ?: "数据源" + t.datasourceId}", used))
                writeDiffDetailSheet(sheet, job, t, diffs, context, diffStyle)
                sheet.flushRows() // 行写盘,避免多个 sheet 同时驻留内存(临时文件由 wb.close() 统一清理)
            }
            wb.write(out)
        } // use 块关闭工作簿并清理临时文件(dispose 已废弃,close 已覆盖)
    }

    /** 总览行构建(纯函数,便于单测):第一行为基准表本身(无目标 id、与基准差留空),之后一行一个比对目标;
     *  抽样任务(V70,sample_rows 非空)在基准行与各目标行的「差异原因」披露「抽样比对」事实与口径 */
    internal fun buildOverviewRows(job: CompareRepository.JobRow, targets: List<CompareRepository.TargetRow>,
                                   objectDiffs: Map<Long, ObjectLevelDiffs>,
                                   ctx: ExportContext): List<CompareExportOverviewRow> {
        val baseCount = targets.mapNotNull { it.baseCount }.maxOrNull()
        val sampleReason = job.sampleRows?.let { "抽样比对:每侧仅取前 $it 条(按身份列排序)" }
        val rows = ArrayList<CompareExportOverviewRow>(targets.size + 1)
        val baseSystem = ctx.systemName(job.baseDatasourceId, job.baseDb, job.baseTable)
        rows.add(CompareExportOverviewRow(
            // 导出文件内表名统一单元格内三行「表名 / 系统名 / （库.模式）」格式(空段省略)
            tableName = tableDisplayName(baseSystem, job.baseDb, job.baseSchema, job.baseTable),
            tableComment = ctx.comment(job.baseDatasourceId, job.baseDb, job.baseTable),
            systemName = baseSystem,
            rowCount = baseCount,
            // 数据最新更新时间 = 比对执行时探测时间字段取 MAX 的快照;没有可用字段/取数失败/老任务留空
            dataUpdatedAt = job.baseDataUpdatedAt.orEmpty(),
            diffFromBase = null,
            matchedCount = null,
            matchedTotal = null,
            diffCount = null,
            diffReason = if (sampleReason != null) "基准表(抽样比对,不参与差异统计)" else BASELINE_REASON,
            targetId = null,
        ))
        // 基准行数缺失时退化为各目标自报的 base_count,保证「与基准差」仍可计算
        val perTargetBase = targets.mapNotNull { it.baseCount }.firstOrNull() ?: baseCount
        for (t in targets) {
            val targetCount = t.targetCountOrFallback()
            val system = ctx.systemName(t.datasourceId, t.dbName, t.tableName)
            rows.add(CompareExportOverviewRow(
                tableName = tableDisplayName(system, t.dbName, t.schemaName, t.tableName),
                tableComment = ctx.comment(t.datasourceId, t.dbName, t.tableName),
                systemName = system,
                rowCount = targetCount,
                // 口径同基准行:该目标表探测到的时间字段 MAX 快照,没有则留空
                dataUpdatedAt = t.dataUpdatedAt.orEmpty(),
                diffFromBase = if (targetCount != null && perTargetBase != null) targetCount - perTargetBase else null,
                // 匹配编码数 = 编码路命中数;老任务三路未采集(NULL)按旧口径回落 matched_count
                matchedCount = t.codeMatchedCount ?: t.matchedCount,
                // 匹配对象数 = 双侧都存在的对象总数(编码/名称/大模型三路之和)
                matchedTotal = t.matchedCount,
                // 差异条数 = 数量差异:缺失(基准有目标无)+ 多余(目标有基准无);
                // 总览只做行级数量对比,属性差异(编码不一致/字段值不一致)不计,
                // 由「行级对比明细」与各目标明细 sheet 体现
                diffCount = objectDiffs[t.id]?.let { it.missing + it.extra } ?: 0,
                diffReason = (t.diffReason(baseCount = perTargetBase, targetCount = targetCount,
                    identityDiff = objectDiffs[t.id]?.identity ?: 0) ?: "与基准完全一致")
                    .let { if (sampleReason != null) "$it;$sampleReason" else it },
                targetId = t.id,
            ))
        }
        return rows
    }

    /** 对象级差异三项计数:missing/extra 合计为总览「差异条数」(数量差异),identity(属性差异)只用于差异原因与行级对比明细 */
    internal data class ObjectLevelDiffs(val missing: Int, val extra: Int, val identity: Int) {
        /** 三项之和 = 「行级对比明细」sheet 行数(该 sheet 恰好列这三类对象) */
        val total: Int get() = missing + extra + identity
    }

    /** 按目标统计对象级差异:MISSING/EXTRA 为数量差异(总览差异条数),DIFF 看两侧身份取值(属性差异,行级对比明细) */
    private fun objectLevelDiffs(identityFields: List<String>, rows: List<CompareRepository.DiffRow>): ObjectLevelDiffs {
        var missing = 0
        var extra = 0
        var identity = 0
        for (row in rows) {
            when (row.diffType) {
                "MISSING" -> missing++
                "EXTRA" -> extra++
                else -> if (hasIdentityDiff(identityFields, row, parseValueMap(row.diffJson))) identity++
            }
        }
        return ObjectLevelDiffs(missing, extra, identity)
    }

    /** DIFF 行目标侧身份取值(身份组合 to 名称):diff_json 快照优先——字段条目缺失(老快照)回落
     *  object_key/object_name;新契约(带 matched)值为空按「目标侧确为空」展示空串,
     *  老快照(无 matched)值为空沿用旧口径回落 object_key */
    private fun targetIdentity(identity: List<String>, displayFields: List<String>, row: CompareRepository.DiffRow,
                               rowMap: Map<String, FieldDiff>): Pair<String?, String?> {
        val diffs = identity.map { f -> rowMap[f] }
        val hasAll = diffs.all { it != null && it.value != MISSING_COLUMN_MARK }
        val code = when {
            !hasAll -> row.objectKey
            diffs.any { d -> d?.matched == null } && diffs.any { d -> d?.value == null } -> row.objectKey
            else -> identity.joinToString("\u0001") { f -> rowMap[f]?.value ?: "" }
        }
        // 名称 = 名称字段数组第一个非「字段缺失」的目标侧取值;取不到回落该行已落的 object_name
        return code to (displayFields.firstNotNullOfOrNull { f ->
            rowMap[f]?.value?.takeIf { v -> v != MISSING_COLUMN_MARK }
        } ?: row.objectName)
    }

    /**
     * 是否对象身份层面的差异:身份取该目标的**有效身份字段**——组合身份相同即同一个对象,
     * 名称只是显示/兜底对齐字段,取值不同属普通字段差异(由字段级汇总与各目标明细 sheet 展开),
     * **不计入对象级差异**。
     * 计入口径:**两侧编码都非空且不同**才算「编码不一致的对象」(如按名称/大模型兜底配上、但双方编码对不上);
     * 一侧编码为空是「编码缺失」,属字段级差异(明细照常展开),不计对象级——否则整表无码的目标
     * 会把所有配对计成编码不一致,数量会超过对象总数。
     * 本判定只影响「行级对比明细」sheet 与总览「差异原因」的编码不一致分量;
     * 总览「差异条数」是数量口径(缺失+多余),不含编码不一致。
     * 判定用 [FieldDiff.matched] 显式标志;身份字段条目缺失或目标缺列的老快照
     * 回落「编码值非空且 ≠ object_key」的旧口径(不回归老数据)。
     */
    private fun hasIdentityDiff(identity: List<String>, row: CompareRepository.DiffRow,
                                rowMap: Map<String, FieldDiff>): Boolean {
        val diffs = identity.map { f -> rowMap[f] ?: return false }
        if (diffs.any { it.value == MISSING_COLUMN_MARK }) return false
        return if (diffs.any { it.matched != null }) {
            // 新契约:身份字段不一致、且基准/目标两侧取值都非空,才计对象级身份差异
            diffs.any { it.matched == false && !it.base.isNullOrEmpty() && !it.value.isNullOrEmpty() }
        } else {
            // 老快照(无 matched):按旧口径——编码值非空且 ≠ 基准侧 object_key 才判身份差异,值为空视为无差异
            val targetValues = diffs.map { it.value ?: return false }
            row.objectKey.orEmpty() != targetValues.joinToString("\u0001")
        }
    }

    /**
     * 导出上下文(总览附属信息 + 明细表头的中文字段名):
     * - 表中文名/所属系统按「数据源 + 库 + schema」预取一次
     * - 字段注释按需惰性查一次并缓存(明细 sheet 表头用字段注释做中文列名,无注释回落字段名)
     * 元数据不可达、table_system 无登记都静默降级,导出必须能出文件。
     */
    internal data class ExportContext(
        private val comments: Map<String, String> = emptyMap(),
        private val systems: Map<String, String> = emptyMap(),
        private val fallbackSystem: Map<Long, String?> = emptyMap(),
        /** 目标自定义显示名(V72):表键 → 显示名,系统名口径最高优先 */
        private val displayNames: Map<String, String> = emptyMap(),
        /** 目标库描述(schema_doc):表键 → 描述,系统名口径排在 table_system 登记之后、数据源名之前 */
        private val schemaDescs: Map<String, String> = emptyMap(),
        /** 明细表头用的字段注释:表键 → (字段小写 → 注释),由 exportContext 预取 */
        private val columnComments: Map<String, Map<String, String>> = emptyMap(),
    ) {
        /** 表中文名:表注释,取不到返回 null */
        fun comment(datasourceId: Long, db: String, table: String): String? =
            comments[key(datasourceId, db, table)]

        /** 所属系统:自定义显示名(V72)> table_system 登记值 > 库描述(schema_doc)> 数据源名,逐级回落 */
        fun systemName(datasourceId: Long, db: String, table: String): String? =
            displayNames[key(datasourceId, db, table)]
                ?: systems[key(datasourceId, db, table)]
                ?: schemaDescs[key(datasourceId, db, table)]
                ?: fallbackSystem[datasourceId]

        /** 目标库描述(明细 sheet 名等不参与 table_system 登记的展示位用);无描述返回 null */
        fun schemaDesc(datasourceId: Long, db: String, table: String): String? =
            schemaDescs[key(datasourceId, db, table)]

        /** 明细表头:字段注释优先(表中文名口径),无注释回落字段名 */
        fun fieldHeader(datasourceId: Long, db: String, table: String, field: String): String =
            columnComments[key(datasourceId, db, table)]?.get(field.lowercase()) ?: field

        /** 字段注释原文:无注释返回 null(明细 sheet 中文列留空,不回落字段名) */
        fun fieldComment(datasourceId: Long, db: String, table: String, field: String): String? =
            columnComments[key(datasourceId, db, table)]?.get(field.lowercase())

        companion object {
            /** 上下文键:数据源 + 库 + 表名(库名/表名忽略大小写) */
            fun key(datasourceId: Long, db: String, table: String): String =
                "$datasourceId\u0000${db.lowercase()}\u0000${table.lowercase()}"
        }
    }

    /**
     * 预取导出上下文:表注释 + 所属系统 + 明细表头字段注释。
     * 表/字段注释优先读比对执行时的快照(V64,跑完后断网也能导出带中文名的文件);老任务(快照 NULL)
     * 兜底走缓存优先(缓存未就绪回源业务库取,直连库即可拿到;不可达静默降级);所属系统读本地 H2;导出必须能出文件。
     */
    private fun exportContext(job: CompareRepository.JobRow,
                              targets: List<CompareRepository.TargetRow>): ExportContext {
        val comments = HashMap<String, String>()
        val systems = HashMap<String, String>()
        val fallbackSystem = HashMap<Long, String?>()
        val columnComments = HashMap<String, Map<String, String>>()
        // 目标自定义显示名(V72):系统名口径最高优先,随目标行快照,不依赖外部登记
        val displayNames = HashMap<String, String>()
        // 目标库描述(schema_doc,动态查非快照):系统名口径排在 table_system 登记之后、数据源名之前
        val schemaDescs = HashMap<String, String>()
        targets.forEach { t ->
            t.displayName?.takeIf { it.isNotBlank() }
                ?.let { displayNames[ExportContext.key(t.datasourceId, t.dbName, t.tableName)] = it }
            schemaDescOf(t.datasourceId, t.dbName, t.schemaName)
                ?.let { schemaDescs[ExportContext.key(t.datasourceId, t.dbName, t.tableName)] = it }
        }
        // 表中文名:快照优先;老任务(快照 NULL)按「数据源 + 库 + schema」走缓存优先兜底
        // (各目标 schema 各自快照在 compare_target 上,不能只用库名推)
        job.baseTableComment?.let { comments[ExportContext.key(job.baseDatasourceId, job.baseDb, job.baseTable)] = it }
        targets.forEach { t ->
            t.tableComment?.let { comments[ExportContext.key(t.datasourceId, t.dbName, t.tableName)] = it }
        }
        val scopes = LinkedHashSet<Triple<Long, String, String>>()
        scopes.add(Triple(job.baseDatasourceId, job.baseDb,
            effectiveSchema(job.baseSchema, job.baseDb)))
        targets.forEach {
            scopes.add(Triple(it.datasourceId, it.dbName, effectiveSchema(it.schemaName, it.dbName)))
        }
        if (job.baseTableComment == null || targets.any { it.tableComment == null }) {
            for ((dsId, db, schema) in scopes) {
                try {
                    for (t in metadataService.listTables(dsId, db.ifBlank { null }, schema)) {
                        val name = t.name ?: continue
                        val tableKey = ExportContext.key(dsId, db, name)
                        // 快照已有的表以快照为准(执行时口径),不覆盖
                        if (!comments.containsKey(tableKey)) {
                            t.comment?.takeIf { it.isNotBlank() }?.let { comments[tableKey] = it }
                        }
                    }
                } catch (e: Exception) {
                    log.debug("导出取表注释失败(忽略): 数据源{} 库{} schema{}: {}", dsId, db, schema, e.message)
                }
            }
        }
        for ((dsId, db, schema) in scopes) {
            fallbackSystem[dsId] = try {
                dataSourceService.get(dsId).name
            } catch (e: Exception) {
                null // 数据源已删除:所属系统留空(基准回落空串),不影响导出
            }
            try {
                for ((table, system) in tableSystemRepo.findBySchema(dsId, db, schema)) {
                    if (system.isNotBlank()) systems[ExportContext.key(dsId, db, table)] = system
                }
            } catch (e: Exception) {
                log.debug("导出取所属系统失败(忽略): 数据源{} 库{} schema{}: {}", dsId, db, schema, e.message)
            }
        }
        // 明细表头的字段注释:快照优先;老任务缓存优先兜底。无注释的字段回落字段名,取不到只记 debug;
        // 对象分组 sheet 的表头按基准口径取中文列名,基准表也要预取
        fun loadColumnComments(dsId: Long, db: String, schema: String, table: String, snapshotJson: String?) {
            val tableKey = ExportContext.key(dsId, db, table)
            if (columnComments.containsKey(tableKey)) return
            val snapshot = parseCommentMap(snapshotJson)
            if (snapshot != null) {
                columnComments[tableKey] = snapshot
                return
            }
            try {
                columnComments[tableKey] = metadataService.listTableColumns(dsId, db.ifBlank { null }, schema, table)
                    .filter { !it.comment.isNullOrBlank() }
                    .associate { it.name.lowercase() to it.comment!! }
            } catch (e: Exception) {
                log.debug("导出取字段注释失败(忽略): 数据源{} 库{} 表{}: {}", dsId, db, table, e.message)
            }
        }
        loadColumnComments(job.baseDatasourceId, job.baseDb, effectiveSchema(job.baseSchema, job.baseDb),
            job.baseTable, job.baseColumnComments)
        for (t in targets) {
            loadColumnComments(t.datasourceId, t.dbName, effectiveSchema(t.schemaName, t.dbName), t.tableName,
                t.columnComments)
        }
        return ExportContext(comments, systems, fallbackSystem, displayNames, schemaDescs, columnComments)
    }

    /** 总览 sheet:表头 + 一行一系统(首行基准表,与后续明细 sheet 序号一一对应) */
    private fun writeOverviewSheet(wb: SXSSFWorkbook, overview: List<CompareExportOverviewRow>,
                                   wrapStyle: CellStyle) {
        val sheet = wb.createSheet(OVERVIEW_SHEET_NAME)
        val head = sheet.createRow(0)
        EXPORT_OVERVIEW_HEADERS.forEachIndexed { i, h -> head.createCell(i).setCellValue(h) }
        var r = 1
        for (row in overview) {
            val excelRow = sheet.createRow(r++)
            excelRow.createCell(0).setCellValue(row.tableComment ?: "")
            // 表名为单元格内多行(表名/系统名/（库.模式）),需自动换行样式
            excelRow.createCell(1).apply { setCellValue(row.tableName); cellStyle = wrapStyle }
            excelRow.createCell(2).setCellValue(row.systemName ?: "")
            ExcelCells.cell(excelRow.createCell(3), row.rowCount)
            excelRow.createCell(4).setCellValue(row.dataUpdatedAt ?: "")
            ExcelCells.cell(excelRow.createCell(5), row.diffFromBase)
            ExcelCells.cell(excelRow.createCell(6), row.matchedCount)
            ExcelCells.cell(excelRow.createCell(7), row.matchedTotal)
            ExcelCells.cell(excelRow.createCell(8), row.diffCount)
            excelRow.createCell(9).setCellValue(row.diffReason ?: UNFINISHED_REASON)
        }
        sheet.flushRows()
    }

    /**
     * 行级对比明细 sheet(固定第二个 sheet,始终生成):一行一个「对象 × 比对目标」的行级差异。
     * - 首行即表头:基准表英文名/基准表中文名/基准编码字段/基准编码/基准名称字段/基准名称 +
     *   对比业务表英文名/对比业务表中文名/业务表编码字段/业务表编码/业务表名称字段/业务表名称 +
     *   「差异说明」+ 末列「差异类型」;表中文名取表注释,取不到留空
     * - 编码/名称字段列:基准侧 = 任务比对主键/显示名字段;业务侧 = 字段映射(人工连线)里该基准字段
     *   连到的目标列,无映射(按名称自动匹配,老任务)按基准字段同名——与字段级差异汇总「业务表字段」同口径;
     *   显示名未配置(老任务)时基准/业务两侧名称字段列都留空
     * - 编码/名称取双侧各自取值:DIFF 行业务侧优先取 diff_json 里目标侧真实值(靠名称/大模型配上的对象
     *   两侧编码不同,差异照常体现;老紧凑格式没有该字段快照时回落基准侧);MISSING 行业务侧留空、
     *   EXTRA 行基准侧留空(object_key/object_name 此时即目标侧取值)
     * - 只列身份层面有差异的行:**身份字段取比对主键(对象编码)**——DIFF 行两侧编码一致的直接跳过
     *   (名称等字段级不一致在各目标明细 sheet 体现),编码不同的(按名称/大模型兜底配上的)才列出;
     *   MISSING/EXTRA 行天然是对象级差异,始终列出
     * - 差异说明:DIFF 只展开身份字段(编码/名称)的差异(见 [describeDiff],字段名取基准表注释;
     *   其余字段的不一致属列级口径,不在此展开),MISSING/EXTRA 写「基准有目标无」/「目标有基准无」
     * - 差异类型(末列,见 [rowLevelTypeLabel]):不一致(DIFF)/ 缺失(MISSING)/ 多余(EXTRA),
     *   与字段级差异汇总的三分类同口径,便于导出后按类型筛选
     *
     * 行数超 [MAX_ROWS_PER_SHEET] 时截断并追加说明行(Excel 单表上限兜底)。
     */
    private fun writeRowLevelSheet(wb: SXSSFWorkbook, job: CompareRepository.JobRow,
                                   targets: List<CompareRepository.TargetRow>,
                                   diffsByTarget: Map<Long, List<CompareRepository.DiffRow>>,
                                   ctx: ExportContext,
                                   identityByTarget: Map<Long, List<String>>,
                                   wrapStyle: CellStyle) {
        val sheet = wb.createSheet(ROW_LEVEL_SHEET_NAME)
        var r = 0
        val head = sheet.createRow(r++)
        ROW_LEVEL_HEADERS.forEachIndexed { i, h -> head.createCell(i).setCellValue(h) }

        val baseComment = ctx.comment(job.baseDatasourceId, job.baseDb, job.baseTable).orEmpty()
        // 任务级对象名称字段(V73 多选):老任务由 displayField 单列退化;取值 = 按字段顺序第一个非空值
        val displayFields = jobDisplayFields(job)
        // 表名单元格统一单元格内三行「表名 / 系统名 / （库.模式）」格式
        val baseTableLabel = tableDisplayName(ctx.systemName(job.baseDatasourceId, job.baseDb, job.baseTable),
            job.baseDb, job.baseSchema, job.baseTable)
        var written = 0
        var truncated = false
        loop@ for (t in targets) {
            val targetComment = ctx.comment(t.datasourceId, t.dbName, t.tableName).orEmpty()
            val targetTableLabel = tableDisplayName(ctx.systemName(t.datasourceId, t.dbName, t.tableName),
                t.dbName, t.schemaName, t.tableName)
            // 该目标的有效身份字段(人工覆盖 ?? 推导;基准侧字段名)
            val identity = identityByTarget[t.id] ?: listOf(job.keyField)
            // 业务侧身份/名称字段:显式映射(人工连线)里基准字段连到的目标列;
            // 无映射(按名称自动匹配,老任务)回落基准字段同名,与字段级差异汇总「业务表字段」同口径
            val mappingLower = parseMapping(t.fieldMappingJson).mapKeys { it.key.lowercase() }
            val targetKeyField = identity.map { f -> mappingLower[f.lowercase()] ?: f }.joinToString("+")
            val targetNameField = displayFields.map { mappingLower[it.lowercase()] ?: it }.joinToString("+")
            for (row in diffsByTarget[t.id].orEmpty()) {
                if (written >= MAX_ROWS_PER_SHEET) {
                    truncated = true
                    break@loop
                }
                val rowMap = parseValueMap(row.diffJson)
                val extra = row.diffType == "EXTRA"
                // 业务侧身份/名称:DIFF 从 diff_json 取目标侧真实值(缺快照/缺列时回落基准侧),MISSING 留空
                val (targetCode, targetName) =
                    if (row.diffType == "DIFF") targetIdentity(identity, displayFields, row, rowMap)
                    else (null to null)

                // 行级 sheet 只呈现对象身份层面的差异:身份是「选择基准表」里指定(或被目标级覆盖)的对齐键,
                // DIFF 行两侧身份一致时,差异纯属字段级(名称等,各目标明细 sheet 已展开),此处不再占位
                if (row.diffType == "DIFF" && !hasIdentityDiff(identity, row, rowMap)) continue

                val excelRow = sheet.createRow(r++)
                excelRow.createCell(0).apply { setCellValue(baseTableLabel); cellStyle = wrapStyle }
                excelRow.createCell(1).setCellValue(baseComment)
                excelRow.createCell(2).setCellValue(identity.joinToString("+"))
                excelRow.createCell(3).setCellValue(if (extra) "" else row.objectKey.orEmpty())
                excelRow.createCell(4).setCellValue(displayFields.joinToString("+"))
                excelRow.createCell(5).setCellValue(if (extra) "" else row.objectName.orEmpty())
                excelRow.createCell(6).apply { setCellValue(targetTableLabel); cellStyle = wrapStyle }
                excelRow.createCell(7).setCellValue(targetComment)
                excelRow.createCell(8).setCellValue(targetKeyField)
                excelRow.createCell(9).setCellValue(if (extra) row.objectKey.orEmpty() else targetCode.orEmpty())
                excelRow.createCell(10).setCellValue(targetNameField)
                excelRow.createCell(11).setCellValue(if (extra) row.objectName.orEmpty() else targetName.orEmpty())
                // 差异说明只写身份字段(编码/名称)的差异:其余字段的不一致属列级口径,
                // 由字段级差异汇总与各目标明细 sheet 展开,行级 sheet 不重复
                val identityMap = rowMap.filterKeys { it in identity || it in displayFields }
                excelRow.createCell(12).setCellValue(describeDiff(row.diffType, identityMap) { f ->
                    ctx.fieldHeader(job.baseDatasourceId, job.baseDb, job.baseTable, f)
                })
                // 末列差异类型:不一致(DIFF)/ 缺失(MISSING)/ 多余(EXTRA),供核对表直接按类型筛选取值
                excelRow.createCell(13).setCellValue(rowLevelTypeLabel(row.diffType))
                written++
            }
        }
        if (truncated) {
            sheet.createRow(r).createCell(0)
                .setCellValue("(差异行数超过 $MAX_ROWS_PER_SHEET 行,已截断;完整明细请用差异明细页面筛选查看)")
        }
        sheet.flushRows()
    }

    /**
     * 字段级差异汇总 sheet(固定第三个 sheet,始终生成):一行一个「比对目标 × 基准字段」,
     * 统计该字段的差异数量并按 缺失/多余/不一致 三类拆分(差异相对基准而言):
     * - 首行即表头:业务表英文名/业务表中文名 + 基准表字段/字段中文(无注释留空)+ 业务表字段/业务表字段中文
     *   (目标表该列注释,无注释留空)+ 差异数量/缺失/多余/不一致(数值列)
     * - **只列与业务表有连线的字段**:任务带显式字段映射(第三步人工连线)时只列映射键对应的基准字段,
     *   未连线的基准字段(比对时对该目标按「字段缺失」计)属噪音不再列出;
     *   无映射(按名称自动匹配,老任务)时列全部比对字段
     * - 业务表字段 = 映射里该基准字段连到的目标列名;无映射按基准字段同名(自动匹配口径);
     *   业务表字段中文 = 目标表该列注释(取不到留空)
     * - 不一致 = 该字段在 DIFF 行里的不一致次数(口径同 [FieldDiff.isMismatch],含目标缺列);
     *   缺失/多余 = 该目标的 MISSING/EXTRA 对象数(对象级差异,整行缺失/多余即每个比对字段都缺/多,
     *   故同目标各字段同值);差异数量 = 缺失 + 多余 + 不一致 三类合计
     * - 行 = 连线字段(无差异字段也列出,计 0),差异里出现过的其余连线字段兜底追加在末尾
     */
    private fun writeFieldSummarySheet(wb: SXSSFWorkbook, job: CompareRepository.JobRow,
                                       targets: List<CompareRepository.TargetRow>,
                                       diffsByTarget: Map<Long, List<CompareRepository.DiffRow>>,
                                       ctx: ExportContext, wrapStyle: CellStyle) {
        val sheet = wb.createSheet(FIELD_SUMMARY_SHEET_NAME)
        var r = 0
        val head = sheet.createRow(r++)
        FIELD_SUMMARY_HEADERS.forEachIndexed { i, h -> head.createCell(i).setCellValue(h) }

        val fields = parseFields(job.fieldsJson)
        var truncated = false
        loop@ for (t in targets) {
            val rows = diffsByTarget[t.id].orEmpty()
            val missing = rows.count { it.diffType == "MISSING" }
            val extra = rows.count { it.diffType == "EXTRA" }
            // 字段 → 不一致次数(仅 DIFF 行,逐不一致字段计数)
            val mismatchByField = LinkedHashMap<String, Int>()
            for (row in rows) {
                if (row.diffType != "DIFF") continue
                for (d in parseValueMap(row.diffJson).values.filter { it.isMismatch() }) {
                    mismatchByField.merge(d.field, 1, Int::plus)
                }
            }
            // 只列与本目标有连线的字段(忽略大小写):有显式映射取映射键;
            // 无映射(按名称自动匹配,老任务)列全部比对字段,差异里出现过的其余字段兜底追加
            val mappingLower = parseMapping(t.fieldMappingJson).mapKeys { it.key.lowercase() }
            val columns = if (mappingLower.isEmpty()) {
                fields + mismatchByField.keys.filter { it !in fields }
            } else {
                val listed = fields.filter { mappingLower.containsKey(it.lowercase()) }
                listed + mismatchByField.keys.filter { it !in listed && mappingLower.containsKey(it.lowercase()) }
            }
            val targetComment = ctx.comment(t.datasourceId, t.dbName, t.tableName).orEmpty()
            for (f in columns) {
                if (r - 1 >= MAX_ROWS_PER_SHEET) {
                    truncated = true
                    break@loop
                }
                val mismatch = mismatchByField[f] ?: 0
                val targetColumn = mappingLower[f.lowercase()] ?: f
                val excelRow = sheet.createRow(r++)
                excelRow.createCell(0).apply {
                    setCellValue(tableDisplayName(
                        ctx.systemName(t.datasourceId, t.dbName, t.tableName), t.dbName, t.schemaName, t.tableName))
                    cellStyle = wrapStyle
                }
                excelRow.createCell(1).setCellValue(targetComment)
                excelRow.createCell(2).setCellValue(f)
                excelRow.createCell(3).setCellValue(
                    ctx.fieldComment(job.baseDatasourceId, job.baseDb, job.baseTable, f).orEmpty())
                excelRow.createCell(4).setCellValue(targetColumn)
                // 业务表字段中文 = 目标表该列注释;目标表无此列/无注释都留空
                excelRow.createCell(5).setCellValue(
                    ctx.fieldComment(t.datasourceId, t.dbName, t.tableName, targetColumn).orEmpty())
                ExcelCells.cell(excelRow.createCell(6), missing + extra + mismatch)
                ExcelCells.cell(excelRow.createCell(7), missing)
                ExcelCells.cell(excelRow.createCell(8), extra)
                ExcelCells.cell(excelRow.createCell(9), mismatch)
            }
        }
        if (truncated) {
            sheet.createRow(r).createCell(0)
                .setCellValue("(行数超过 $MAX_ROWS_PER_SHEET 行,已截断)")
        }
        sheet.flushRows()
    }

    /**
     * 数据级字段对比差异总览 sheet(固定第四个 sheet,始终生成):按数据行聚合的字段对比统计,
     * **一行一条数据(对象)**,只给数量、不精确到具体字段(逐字段取值看「列级对比明细」与各目标明细 sheet):
     * - 首行即表头:对象编码/对象名称(动态列名,与各目标明细 sheet 同口径)+ 基准表字段数 +
     *   每目标三列「对比字段数 / 相同字段数 / 不同字段数」,业务表名按统一显示格式
     *   (表名/系统名/（库.模式）三行)放在指标名前(所属系统与总览同口径,动态取名)
     * - 行集合:有差异的对象(任一目标存在 DIFF/MISSING 行;多余 EXTRA 对象不属基准侧,不展开)
     * - 基准表字段数 = 任务比对字段总数(整表恒同);
     *   对比字段数 = 该系统参与比对的字段数(显式映射 = 连线字段数,无映射 = 基准表字段数);
     *   不同字段数 = 比对字段中不一致的个数(该对象在此系统整行缺失 = 全部比对字段);
     *   相同字段数 = 对比字段数 − 不同字段数;不同字段数 > 0 标红
     * - 行数超 [MAX_ROWS_PER_SHEET] 时截断并追加说明行
     */
    private fun writeColumnDetailSheet(wb: SXSSFWorkbook, job: CompareRepository.JobRow,
                                       targets: List<CompareRepository.TargetRow>,
                                       diffsByTarget: Map<Long, List<CompareRepository.DiffRow>>,
                                       ctx: ExportContext, diffStyle: CellStyle, wrapStyle: CellStyle) {
        val sheet = wb.createSheet(COLUMN_DETAIL_SHEET_NAME)

        var r = 0
        val head = sheet.createRow(r++)
        head.createCell(0).setCellValue(identityHeader(job, ctx))
        head.createCell(1).setCellValue(displayHeader(job, ctx))
        head.createCell(2).setCellValue("基准表字段数")
        targets.forEachIndexed { i, t ->
            val sys = ctx.systemName(t.datasourceId, t.dbName, t.tableName) ?: "数据源${t.datasourceId}"
            // 业务表名用统一显示格式(表名/系统名/（库.模式）三行) + 指标名作第四行,多行需自动换行
            val table = tableDisplayName(sys, t.dbName, t.schemaName, t.tableName)
            listOf("对比字段数", "相同字段数", "不同字段数").forEachIndexed { j, metric ->
                head.createCell(3 + i * 3 + j).apply {
                    setCellValue("$table\n$metric")
                    cellStyle = wrapStyle
                }
            }
        }

        val fields = parseFields(job.fieldsJson)
        // 每目标:objectKey → 差异行(导出查询不含 SAME 行,缺席即视同该对象在该目标完全一致)
        val rowsByTarget = targets.map { t ->
            diffsByTarget[t.id].orEmpty()
                .filter { it.diffType == "DIFF" || it.diffType == "MISSING" }
                .associateBy { it.objectKey.orEmpty() }
        }
        // 整行快照按需解析缓存(一条快照最长 12000 字符,只解析真正要落表的行)
        val snapsByTarget = targets.map { mutableMapOf<String, Map<String, FieldDiff>>() }
        fun snap(ti: Int, key: String): Map<String, FieldDiff> =
            snapsByTarget[ti].getOrPut(key) { parseValueMap(rowsByTarget[ti][key]?.diffJson) }
        // 每目标的比对字段集合(小写):显式映射 = 连线字段(未连线 = 未比对,不计入对比字段数);
        // 无映射(按名称自动匹配)= 全部比对字段(null 标记)
        val comparedByTarget = targets.map { t ->
            parseMapping(t.fieldMappingJson).mapKeys { (k, _) -> k.lowercase() }.keys.takeIf { it.isNotEmpty() }
        }

        // 对象行序:首个目标差异行顺序(基准侧对象在前),其余目标新增对象顺次追加
        val orderedKeys = LinkedHashSet<String>()
        for (rows in rowsByTarget) orderedKeys.addAll(rows.keys)

        var truncated = false
        outer@ for (key in orderedKeys) {
            if (r - 1 >= MAX_ROWS_PER_SHEET) {
                truncated = true
                break@outer
            }
            val objName = rowsByTarget.firstNotNullOfOrNull { m -> m[key]?.objectName?.takeIf { n -> n.isNotBlank() } }.orEmpty()
            val excelRow = sheet.createRow(r++)
            excelRow.createCell(0).setCellValue(key)
            excelRow.createCell(1).setCellValue(objName)
            ExcelCells.cell(excelRow.createCell(2), fields.size)
            targets.forEachIndexed { ti, _ ->
                val c0 = 3 + ti * 3
                val compared = comparedByTarget[ti]
                val comparedCount = compared?.size ?: fields.size
                val row = rowsByTarget[ti][key]
                // 不同字段数:整行缺失 = 全部比对字段;无差异行 = 0;否则按快照逐比对字段计不一致(未连线字段不计)
                val diffCount = when {
                    row == null -> 0
                    row.diffType == "MISSING" -> comparedCount
                    else -> {
                        val snapMap = snap(ti, key)
                        if (compared == null) fields.count { snapMap[it]?.isMismatch() == true }
                        else fields.count { compared.contains(it.lowercase()) && snapMap[it]?.isMismatch() == true }
                    }
                }
                ExcelCells.cell(excelRow.createCell(c0), comparedCount)
                ExcelCells.cell(excelRow.createCell(c0 + 1), comparedCount - diffCount)
                val diffCell = excelRow.createCell(c0 + 2)
                ExcelCells.cell(diffCell, diffCount)
                if (diffCount > 0) diffCell.cellStyle = diffStyle
            }
        }
        if (truncated) {
            sheet.createRow(r).createCell(0)
                .setCellValue("(行数超过 $MAX_ROWS_PER_SHEET 行,已截断)")
        }
        sheet.flushRows()
    }

    /**
     * 列级对比明细 sheet(固定第五个 sheet,始终生成):所有比对系统的逐字段取值**横向合并**成一张宽表,
     * 一行一个「对象 × 基准字段」,多系统并排逐字段对照取值:
     * - 两行表头:首行 对象编码/对象名称(动态列名,与各目标明细 sheet 同口径)+ 基准字段名/基准字段中文/基准表值
     *   共 5 列,右侧每个比对系统 4 列「{所属系统}业务表字段名 / {所属系统}业务表中文 / {所属系统}业务表值 / 差异原因」
     *   (所属系统与总览同口径);第二行显示各侧表定位「表名 / 系统名 / （库.模式）」(单元格内三行,空段省略,
     *   基准侧写在基准表块首格「基准字段名」列,各系统写在其 4 列块首格);左侧 5 列与两行表头**冻结**
     *   (createFreezePane(5, 2),横向/纵向滚动时身份、基准列与表头不跟随)
     * - 行集合:有差异的对象(任一目标存在 DIFF/MISSING 行)× 各系统比对字段的**并集**(保持任务字段顺序):
     *   显式映射的任务,该系统比对字段 = 连线字段(未连线 = 未比对);无映射(按名称自动匹配)的任务 = 全部比对字段
     * - 某字段在该系统未比对(未连线):字段名列写「无此字段」,中文/值留空,差异原因写「未比对(无此字段)」;
     *   该对象在此系统整行缺失:业务表值留空,差异原因写「基准有目标无」并标红;
     *   不一致:基准表值与该业务表值两格标红,差异原因与各目标明细 sheet 同口径(文本不一致/业务表无此字段);
     *   一致:业务表三列照常填(取值与基准相同),差异原因留空
     * - 多余(EXTRA)对象不属基准侧任何行,不在本表展开(各目标明细 sheet 仍逐行列出)
     * - 行数超 [MAX_ROWS_PER_SHEET] 时截断并追加说明行
     */
    private fun writeMergedDetailSheet(wb: SXSSFWorkbook, job: CompareRepository.JobRow,
                                       targets: List<CompareRepository.TargetRow>,
                                       diffsByTarget: Map<Long, List<CompareRepository.DiffRow>>,
                                       ctx: ExportContext, diffStyle: CellStyle, wrapStyle: CellStyle) {
        val sheet = wb.createSheet(MERGED_DETAIL_SHEET_NAME)
        // 左侧 5 列(对象编码/名称 + 基准字段名/中文/值)+ 两行表头冻结:滚动比对时不丢身份、基准上下文与表头
        sheet.createFreezePane(5, 2)

        var r = 0
        val head = sheet.createRow(r++)
        head.createCell(0).setCellValue(identityHeader(job, ctx))
        head.createCell(1).setCellValue(displayHeader(job, ctx))
        head.createCell(2).setCellValue("基准字段名")
        head.createCell(3).setCellValue("基准字段中文")
        head.createCell(4).setCellValue("基准表值")
        targets.forEachIndexed { i, t ->
            val sys = ctx.systemName(t.datasourceId, t.dbName, t.tableName) ?: "数据源${t.datasourceId}"
            head.createCell(5 + i * 4).setCellValue("${sys}业务表字段名")
            head.createCell(6 + i * 4).setCellValue("${sys}业务表中文")
            head.createCell(7 + i * 4).setCellValue("${sys}业务表值")
            head.createCell(8 + i * 4).setCellValue("差异原因")
        }
        // 第二行表头:各侧表定位,统一单元格内三行「表名 / 系统名 / （库.模式）」格式(空段省略;
        // 系统名与首行动态列头同口径,未登记回落数据源名);基准侧写在基准表块首格(基准字段名列),
        // 与各系统写在其 4 列块首格同口径,避免压在身份列下
        val sub = sheet.createRow(r++)
        sub.createCell(2).apply {
            setCellValue(tableDisplayName(ctx.systemName(job.baseDatasourceId, job.baseDb, job.baseTable),
                job.baseDb, job.baseSchema, job.baseTable))
            cellStyle = wrapStyle
        }
        targets.forEachIndexed { i, t ->
            sub.createCell(5 + i * 4).apply {
                setCellValue(tableDisplayName(
                    ctx.systemName(t.datasourceId, t.dbName, t.tableName) ?: "数据源${t.datasourceId}",
                    t.dbName, t.schemaName, t.tableName))
                cellStyle = wrapStyle
            }
        }

        val fields = parseFields(job.fieldsJson)
        // 每目标:objectKey → 差异行(导出查询不含 SAME 行,缺席即视同该对象在该目标完全一致)
        val rowsByTarget = targets.map { t ->
            diffsByTarget[t.id].orEmpty()
                .filter { it.diffType == "DIFF" || it.diffType == "MISSING" }
                .associateBy { it.objectKey.orEmpty() }
        }
        // 整行快照按需解析缓存(一条快照最长 12000 字符,只解析真正要落表的行)
        val snapsByTarget = targets.map { mutableMapOf<String, Map<String, FieldDiff>>() }
        fun snap(ti: Int, key: String): Map<String, FieldDiff> =
            snapsByTarget[ti].getOrPut(key) { parseValueMap(rowsByTarget[ti][key]?.diffJson) }
        // 每目标的字段映射(基准字段小写 → 目标列名):「业务表字段名」列用
        val mappingByTarget = targets.map { parseMapping(it.fieldMappingJson).mapKeys { (k, _) -> k.lowercase() } }
        // 每系统的比对字段集合(小写):显式映射 = 连线字段(未连线 = 未比对);无映射 = 全部比对字段(null 标记)
        val comparedByTarget = targets.mapIndexed { ti, _ ->
            val m = mappingByTarget[ti]
            if (m.isEmpty()) null else m.keys
        }
        // 各系统比对字段并集(保持任务字段顺序):某字段在 A 没连、在 B 连了,行照样列出,A 侧写「无此字段」
        val unionFields = fields.filter { f -> comparedByTarget.any { it == null || it.contains(f.lowercase()) } }

        // 对象行序:首个目标差异行顺序(基准侧对象在前),其余目标新增对象顺次追加
        val orderedKeys = LinkedHashSet<String>()
        for (rows in rowsByTarget) orderedKeys.addAll(rows.keys)

        var truncated = false
        outer@ for (key in orderedKeys) {
            val objName = rowsByTarget.firstNotNullOfOrNull { m -> m[key]?.objectName?.takeIf { n -> n.isNotBlank() } }.orEmpty()
            for (f in unionFields) {
                if (r - 2 >= MAX_ROWS_PER_SHEET) {
                    truncated = true
                    break@outer
                }
                // 基准值:任一目标快照里的 base(同一张基准表,各目标同值)
                val base = rowsByTarget.indices.firstNotNullOfOrNull { ti -> snap(ti, key)[f]?.base }
                val excelRow = sheet.createRow(r++)
                // 身份列每行重复(不合并):冻结窗格下便于筛选/排序
                excelRow.createCell(0).setCellValue(key)
                excelRow.createCell(1).setCellValue(objName)
                excelRow.createCell(2).setCellValue(f)
                excelRow.createCell(3).setCellValue(
                    ctx.fieldComment(job.baseDatasourceId, job.baseDb, job.baseTable, f).orEmpty())
                val baseCell = excelRow.createCell(4)
                baseCell.setCellValue(base.orEmpty())
                var anyMismatch = false
                targets.forEachIndexed { ti, t ->
                    val c0 = 5 + ti * 4
                    if (comparedByTarget[ti]?.contains(f.lowercase()) == false) {
                        // 该系统未连线此字段(未比对):字段名占位「无此字段」,中文/值留空
                        excelRow.createCell(c0).setCellValue("无此字段")
                        excelRow.createCell(c0 + 3).setCellValue(REASON_NOT_COMPARED)
                        return@forEachIndexed
                    }
                    val targetColumn = mappingByTarget[ti][f.lowercase()] ?: f
                    val row = rowsByTarget[ti][key]
                    when {
                        // 无差异行 = 该目标与基准完全一致:三列照常填,取值与基准相同,差异原因留空
                        row == null -> {
                            excelRow.createCell(c0).setCellValue(targetColumn)
                            excelRow.createCell(c0 + 1).setCellValue(
                                ctx.fieldComment(t.datasourceId, t.dbName, t.tableName, targetColumn).orEmpty())
                            excelRow.createCell(c0 + 2).setCellValue(base.orEmpty())
                        }
                        // 该对象在此系统整行缺失:字段名/中文照常,值为空,值与差异原因两格标红
                        row.diffType == "MISSING" -> {
                            excelRow.createCell(c0).setCellValue(targetColumn)
                            excelRow.createCell(c0 + 1).setCellValue(
                                ctx.fieldComment(t.datasourceId, t.dbName, t.tableName, targetColumn).orEmpty())
                            excelRow.createCell(c0 + 2).apply { cellStyle = diffStyle }
                            excelRow.createCell(c0 + 3).apply {
                                setCellValue("基准有目标无")
                                cellStyle = diffStyle
                            }
                        }
                        else -> {
                            val fd = snap(ti, key)[f]
                            if (fd?.isMismatch() == true) {
                                anyMismatch = true
                                // 目标缺列:业务侧字段名/中文/值留空(与各目标明细 sheet 同口径),差异原因写「业务表无此字段」
                                val missingColumn = fd.value == MISSING_COLUMN_MARK
                                if (!missingColumn) {
                                    excelRow.createCell(c0).setCellValue(targetColumn)
                                    excelRow.createCell(c0 + 1).setCellValue(
                                        ctx.fieldComment(t.datasourceId, t.dbName, t.tableName, targetColumn).orEmpty())
                                }
                                excelRow.createCell(c0 + 2).apply {
                                    setCellValue(if (missingColumn) "" else fd.value.orEmpty())
                                    cellStyle = diffStyle
                                }
                                excelRow.createCell(c0 + 3).setCellValue(
                                    if (missingColumn) REASON_MISSING_COLUMN else REASON_TEXT_DIFF)
                            } else {
                                // 一致字段目标值与基准值相同(新格式快照一致字段 value 为 null,基准值即目标值)
                                excelRow.createCell(c0).setCellValue(targetColumn)
                                excelRow.createCell(c0 + 1).setCellValue(
                                    ctx.fieldComment(t.datasourceId, t.dbName, t.tableName, targetColumn).orEmpty())
                                excelRow.createCell(c0 + 2).setCellValue(fd?.base?.orEmpty() ?: base.orEmpty())
                            }
                        }
                    }
                }
                // 任一系统该字段不一致 → 基准表值标红(与各目标明细 sheet 的取值格标红同口径)
                if (anyMismatch) baseCell.cellStyle = diffStyle
            }
        }
        if (truncated) {
            sheet.createRow(r).createCell(0)
                .setCellValue("(行数超过 $MAX_ROWS_PER_SHEET 行,已截断)")
        }
        sheet.flushRows()
    }

    /**
     * 明细 sheet(字段级差异明细,对齐客户既有核对表):
     * - 首行即表头(无上下文/图例行):最左侧为业务表定位列「业务系统名称/库/模式/表名/表中文名」
     *   (整 sheet 同值逐行重复;「模式」按目标数据源方言动态显示——多库方言(SQL Server/Kingbase)
     *   才有独立模式层,单库方言不出该列)+
     *   对象编码/对象名称(取基准表字段注释做中文列名,无注释回落字段名,
     *   无显示名字段时名称列为「对象名称」)+ 基准表块(基准表字段/字段中文/基准表值)+
     *   业务表块(业务表字段名/业务表中文/业务表值)+ 末列「差异原因」
     * - 其后一行一个「对象 × 字段」:DIFF 行逐不一致字段各出一行,两侧块分别填字段英文名、
     *   中文注释(无注释留空)、取值——业务表字段名按任务字段映射还原目标列名(无映射按同名);
     *   差异格红底 = 基准/业务两个取值格标红。
     *   **业务表没有该列的字段(「字段缺失」按不一致计的)不落本 sheet**——那是两侧表结构差异、
     *   不是基准与业务表之间的数据差异;整行只剩这类字段的对象在本 sheet 也不出现。
     *   MISSING/EXTRA 整行缺失/多余:该对象的全部比对字段都缺/多,按 diff_json 整行快照逐字段展开
     *   (一字段一行)——MISSING 基准块照常填、业务表值留空(列在行不在),EXTRA 反之;
     *   缺失侧取值格与差异原因标红,差异原因写「基准有目标无」/「目标有基准无」;
     *   老数据没有字段级快照时兜底一对象一行(字段六列留空、整行标红)
     *
     * 行数超 [MAX_ROWS_PER_SHEET] 时截断并追加说明行(Excel 单表上限兜底)。
     */
    private fun writeDiffDetailSheet(sheet: SXSSFSheet, job: CompareRepository.JobRow,
                                     t: CompareRepository.TargetRow,
                                     rows: List<CompareRepository.DiffRow>, ctx: ExportContext,
                                     diffStyle: CellStyle) {
        // 业务表字段名 = 任务字段映射(基准字段 → 目标列名,忽略大小写)里的目标列,无映射按基准字段同名
        val mapping = parseMapping(t.fieldMappingJson).mapKeys { it.key.lowercase() }
        // 「模式」列按方言动态:多库方言(SQL Server/Kingbase)才有独立模式层;数据源已删按单库口径不出该列
        val showSchema = try {
            dataSourceService.get(t.datasourceId).dbType
                ?.let { dialectFactory.get(it).supportsMultiDatabase() } == true
        } catch (e: Exception) {
            false
        }
        // 最左侧定位列:业务系统名称/库/(模式)/表名/表中文名(整 sheet 同值,逐行重复便于筛选);
        // 系统名口径:自定义显示名 > table_system 登记/数据源名(ctx.systemName 已含)> 数据源名快照
        val systemName = t.displayName?.takeIf { it.isNotBlank() }
            ?: ctx.systemName(t.datasourceId, t.dbName, t.tableName)
            ?: t.dsName ?: "数据源${t.datasourceId}"
        val tableComment = ctx.comment(t.datasourceId, t.dbName, t.tableName).orEmpty()
        val prefix = if (showSchema) 5 else 4
        val lastCol = prefix + DETAIL_LAST_COL

        var r = 0
        val head = sheet.createRow(r++)
        head.createCell(0).setCellValue("业务系统名称")
        head.createCell(1).setCellValue("库")
        if (showSchema) head.createCell(2).setCellValue("模式")
        head.createCell(prefix - 2).setCellValue("表名")
        head.createCell(prefix - 1).setCellValue("表中文名")
        head.createCell(prefix).setCellValue(identityHeader(job, ctx))
        head.createCell(prefix + 1).setCellValue(displayHeader(job, ctx))
        DETAIL_BLOCK_HEADERS.forEachIndexed { i, h -> head.createCell(prefix + 2 + i).setCellValue(h) }

        /** 写一行:定位列/对象编码/名称 + 差异原因恒有;[d] 非空为字段级行(DIFF 填两侧取值、两个取值格标红;
         *  MISSING/EXTRA 由整行快照展开,缺失侧取值留空并连同差异原因标红),为空为无快照的缺失/多余兜底行
         *  (字段六列留空、整行标红) */
        fun writeRow(row: CompareRepository.DiffRow, d: FieldDiff?, reason: String) {
            val excelRow = sheet.createRow(r++)
            excelRow.createCell(0).setCellValue(systemName)
            excelRow.createCell(1).setCellValue(t.dbName)
            if (showSchema) excelRow.createCell(2).setCellValue(t.schemaName.orEmpty())
            excelRow.createCell(prefix - 2).setCellValue(t.tableName)
            excelRow.createCell(prefix - 1).setCellValue(tableComment)
            excelRow.createCell(prefix).setCellValue(row.objectKey.orEmpty())
            excelRow.createCell(prefix + 1).setCellValue(row.objectName.orEmpty())
            if (d != null) {
                // MISSING 整行缺失:基准块照常填、业务表值留空;EXTRA 反之
                val missingRow = row.diffType == "MISSING"
                val extraRow = row.diffType == "EXTRA"
                val targetColumn = mapping[d.field.lowercase()] ?: d.field
                excelRow.createCell(prefix + 2).setCellValue(d.field)
                excelRow.createCell(prefix + 3).setCellValue(
                    ctx.fieldComment(job.baseDatasourceId, job.baseDb, job.baseTable, d.field).orEmpty())
                val baseCell = excelRow.createCell(prefix + 4)
                baseCell.setCellValue(if (extraRow) "" else d.base.orEmpty())
                excelRow.createCell(prefix + 5).setCellValue(targetColumn)
                excelRow.createCell(prefix + 6).setCellValue(
                    ctx.fieldComment(t.datasourceId, t.dbName, t.tableName, targetColumn).orEmpty())
                val targetCell = excelRow.createCell(prefix + 7)
                targetCell.setCellValue(if (missingRow) "" else d.value.orEmpty())
                // 标红:DIFF 标基准/业务两个取值格;MISSING/EXTRA 只标缺失侧取值格
                when {
                    missingRow -> targetCell.cellStyle = diffStyle
                    extraRow -> baseCell.cellStyle = diffStyle
                    else -> {
                        baseCell.cellStyle = diffStyle
                        targetCell.cellStyle = diffStyle
                    }
                }
            }
            val reasonCell = excelRow.createCell(lastCol)
            reasonCell.setCellValue(reason)
            if (d == null) {
                // 缺失/多余整行标红:在全部内容格就位后补样式(createCell 会覆盖已存在的格子,不能提前建空格)
                for (c in 0..lastCol) (excelRow.getCell(c) ?: excelRow.createCell(c)).cellStyle = diffStyle
            } else if (row.diffType == "MISSING" || row.diffType == "EXTRA") {
                // 缺失/多余字段级行:差异原因一并标红(缺失侧取值格已在上面标红)
                reasonCell.cellStyle = diffStyle
            }
        }

        var written = 0
        var truncated = false
        loop@ for (row in rows) {
            when (row.diffType) {
                "DIFF" -> {
                    // 一字段一行:逐不一致字段展开;老数据/异常没有字段级明细时兜底一行
                    val mismatched = parseValueMap(row.diffJson).values.filter { it.isMismatch() }
                    if (mismatched.isEmpty()) {
                        if (written >= MAX_ROWS_PER_SHEET) { truncated = true; break@loop }
                        writeRow(row, null, "字段不一致")
                        written++
                        continue@loop
                    }
                    // 业务表没有该列(「字段缺失」按不一致计)属两侧表结构差异,不是数据差异,本 sheet 不落;
                    // 整行只剩这类字段的对象因此也不出现在本 sheet(差异仍计入总览/质量报告口径)
                    for (d in mismatched.filter { it.value != MISSING_COLUMN_MARK }) {
                        if (written >= MAX_ROWS_PER_SHEET) { truncated = true; break@loop }
                        writeRow(row, d, REASON_TEXT_DIFF)
                        written++
                    }
                }
                else -> { // MISSING/EXTRA:整行缺失/多余 → 按整行快照逐比对字段展开(该对象全部字段都缺/多)
                    val snapshot = parseValueMap(row.diffJson).values.toList()
                    val reason = if (row.diffType == "MISSING") "基准有目标无" else "目标有基准无"
                    if (snapshot.isEmpty()) {
                        // 老数据没有字段级快照:兜底一对象一行,字段六列留空
                        if (written >= MAX_ROWS_PER_SHEET) { truncated = true; break@loop }
                        writeRow(row, null, reason)
                        written++
                        continue@loop
                    }
                    for (d in snapshot) {
                        if (written >= MAX_ROWS_PER_SHEET) { truncated = true; break@loop }
                        writeRow(row, d, reason)
                        written++
                    }
                }
            }
        }
        if (truncated) {
            sheet.createRow(r).createCell(0)
                .setCellValue("(差异行数超过 $MAX_ROWS_PER_SHEET 行,已截断;完整明细请用差异明细页面筛选查看)")
        }
    }

    /** 解析 diff_json 为「字段 → (基准值, 目标值)」;MISSING/EXTRA 行同样是整行快照 */
    private fun parseValueMap(json: String?): Map<String, FieldDiff> =
        parseDiffs(json).orEmpty().associateBy { it.field }

    /**
     * 该字段是否不一致:新数据看显式 [FieldDiff.matched];旧数据/中间版本(matched=null)按旧契约
     * 「value 非空即不一致」解读(旧 DIFF 行只存不一致字段;中间版本一致字段 value 为 null)。
     */
    private fun FieldDiff.isMismatch(): Boolean = matched?.let { !it } ?: (value != null)

    /**
     * 一行差异的「说明」文案。
     * - MISSING/EXTRA 整行粒度,沿用简短口径;
     * - DIFF 逐字段展开:字段名用中文列名(与表头同口径,无注释回落字段名),
     *   显式标注「基准 → 目标」方向;空值显示「(空)」,目标缺列单独说明
     */
    private fun describeDiff(diffType: String, rowMap: Map<String, FieldDiff>,
                             labelOf: (String) -> String): String =
        when (diffType) {
            "MISSING" -> "基准有目标无"
            "EXTRA" -> "目标有基准无"
            else -> rowMap.values.filter { it.isMismatch() }
                .joinToString(";") { d ->
                    val label = labelOf(d.field)
                    when {
                        d.value == MISSING_COLUMN_MARK -> "$label: 目标无此列(基准为「${d.base.orEmpty().ifEmpty { "(空)" }}」)"
                        else -> "$label: 基准「${d.base?.takeIf { it.isNotEmpty() } ?: "(空)"}」→ 目标「${d.value?.takeIf { it.isNotEmpty() } ?: "(空)"}」"
                    }
                }
                .ifEmpty { "字段不一致" }
        }

    /** 老任务没有 target_count 时兜底:匹配数 + 多余数(等于目标侧实际行数) */
    private fun fallbackTargetCount(t: CompareRepository.TargetRow): Int? =
        if (t.matchedCount != null && t.extraCount != null) t.matchedCount + t.extraCount else null

    /** 目标行未落 target_count 时的展示用条数 */
    private fun CompareRepository.TargetRow.targetCountOrFallback(): Int? = targetCount ?: fallbackTargetCount(this)

    /**
     * 差异原因:自动按差异构成拼写(可导出后人工补充);目标未完成时给出明确说明。
     * 只写**对象级与行数口径**:字段级不一致的统计数字不在总览展示(客户核对表口径,
     * 字段级差异看「字段级差异汇总」与各目标明细 sheet);但字段不一致仍阻止「与基准完全一致」,
     * 且仅存在字段级不一致时给出定性说明(不附统计数字)。
     */
    private fun CompareRepository.TargetRow.diffReason(baseCount: Int?, targetCount: Int?,
                                                       identityDiff: Int = 0): String? {
        if (status != "DONE") return "比对未完成($status)" + (error?.let { ":$it" } ?: "")
        val extra = extraCount ?: 0
        val missing = missingCount ?: 0
        val mismatch = fieldMismatchCount ?: 0
        // 有身份列为空的行即不算「完全一致」:其编码未经过编码路校验,静默宣称一致会误导
        val noKey = noKeyRows ?: 0
        if (extra == 0 && missing == 0 && mismatch == 0 && noKey == 0) return "与基准完全一致"
        val parts = ArrayList<String>(5)
        if (missing > 0) parts.add("基准有目标无的对象 $missing 条")
        if (extra > 0) parts.add("目标有基准无的对象 $extra 条")
        if (identityDiff > 0) parts.add("编码不一致的对象 $identityDiff 条")
        if (baseCount != null && targetCount != null && baseCount != targetCount) {
            parts.add("行数相差 ${targetCount - baseCount}(目标 $targetCount / 基准 $baseCount)")
        }
        if (noKey > 0) parts.add("身份列为空 $noKey 行(仅按名称/大模型配对)")
        // 仅存在字段级不一致(无对象级差异)时给定性说明,不附统计数字
        if (mismatch > 0 && parts.isEmpty()) parts.add("存在字段级不一致(见字段级差异汇总)")
        return parts.joinToString(";")
    }

    /** 质量报告:目标指标 + 问题字段排行(解析 DIFF 行 diff_json 按字段计数取前 10,带基准表字段中文名)+ 汇总 */
    fun report(jobId: Long): CompareReportView {
        val job = repo.getJob(jobId) ?: throw IllegalArgumentException("比对任务不存在: $jobId")
        val targets = repo.listTargets(jobId).map { toTargetView(it) }
        val counts = repo.countByDiffType(jobId)
        val fieldCounts = HashMap<String, Long>()
        for (json in repo.listDiffJsons(jobId)) {
            // diff_json 为整行快照:以显式 matched 判定,new 契约下一致字段也有 value
            for (d in parseDiffs(json).orEmpty()) {
                if (d.isMismatch()) fieldCounts.merge(d.field, 1L, Long::plus)
            }
        }
        // 排行项的中文字段名:优先基准表字段注释快照(比对执行时采集,跑完断网也能出报告);
        // 老任务(快照 NULL)走缓存优先兜底(不可达静默降级),取不到为 null
        val fieldComments = parseCommentMap(job.baseColumnComments) ?: try {
            metadataService.listTableColumns(job.baseDatasourceId, job.baseDb.ifBlank { null },
                effectiveSchema(job.baseSchema, job.baseDb), job.baseTable)
                .filter { !it.comment.isNullOrBlank() }
                .associate { it.name.lowercase() to it.comment!! }
        } catch (e: Exception) {
            log.debug("报告取字段注释失败(忽略): jobId={}: {}", jobId, e.message)
            emptyMap()
        }
        val fieldIssues = fieldCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(FIELD_ISSUE_TOP_N)
            .map { FieldIssueRank(it.key, it.value, fieldComments[it.key.lowercase()]) }
        val consistencies = targets.mapNotNull { it.fieldConsistency }
        return CompareReportView(
            targets = targets,
            fieldIssues = fieldIssues,
            baseCount = targets.mapNotNull { it.baseCount }.maxOrNull() ?: 0,
            sameCount = counts["SAME"] ?: 0,
            diffObjectCount = counts["DIFF"] ?: 0,
            missingTotal = counts["MISSING"] ?: 0,
            extraTotal = counts["EXTRA"] ?: 0,
            avgFieldConsistency = if (consistencies.isEmpty()) 1.0 else consistencies.average(),
        )
    }

    /**
     * 服务重启恢复:残留 RUNNING 任务置 FAILED(ServiceEnv.initDatabase 装配时调用一次);
     * 批量导入侧残留「映射推导中」的 PENDING 任务转 MAPPING_REVIEW(推导线程已随重启消亡,
     * 映射只落了表格锁定项,人工在审核画布补线即可开跑——这是复活出口)
     */
    fun recoverUnfinished() {
        val n = repo.failRunningOnStartup("应用重启,任务中断")
        repo.failUnfinishedTargets("应用重启,任务中断")
        val m = repo.recoverMappingOnStartup("应用重启,映射推导中断,请人工审核补线")
        if (n > 0) {
            log.warn("服务重启,{} 个未完成的比对任务已置为失败", n)
        }
        if (m > 0) {
            log.warn("服务重启,{} 个批量导入任务的映射推导中断,已转「映射待审核」", m)
        }
    }

    // ---------- 内部辅助 ----------

    /**
     * 按数据源方言做库/schema 口径归一(批量导入落库与任务视图层共用):早期批量导入把表格「数据库名称」
     * 整体存进 db、schema 留空,与手工建任务口径(单库方言 db 空、schema=库名)不一致,前端编辑向导/
     * 字段审核画布按 schemas/{schema}/ 拼字段接口路径会 404(执行路径有 effectiveSchema 兜底,不受影响)。
     * 数据源已删/方言取不到时按单库口径归一(与差异导出「模式」列显隐的假设一致),保证 schema 有值可读
     */
    fun normalizeLocation(datasourceId: Long, db: String?, schema: String?): Pair<String, String?> {
        val multiDb = try {
            dataSourceService.get(datasourceId).dbType
                ?.let { dialectFactory.get(it).supportsMultiDatabase() } == true
        } catch (e: Exception) {
            false
        }
        return normalizeDbSchema(db, schema, multiDb)
    }

    private fun toJobView(r: CompareRepository.JobRow): CompareJobView {
        val dsName = try {
            dataSourceService.get(r.baseDatasourceId).name
        } catch (e: Exception) {
            null // 数据源已删除:名称留空,不影响任务展示
        }
        val (db, schema) = normalizeLocation(r.baseDatasourceId, r.baseDb, r.baseSchema)
        return CompareJobView(
            r.id, r.name, r.baseDatasourceId, dsName, db, schema, r.baseTable, r.keyField,
            jobKeyFields(r),
            r.displayField, jobDisplayFields(r), r.matchMode, r.compareMode ?: CompareMode.ROW.value, parseFields(r.fieldsJson),
            r.status, r.stage, r.totalUnits, r.doneUnits,
            if (r.totalUnits > 0) r.doneUnits * 100 / r.totalUnits else 0,
            r.error, r.archived, r.createdAt, r.startedAt, r.finishedAt,
            r.startedAt?.let { Duration.between(it, r.finishedAt ?: LocalDateTime.now()).toMillis() },
            r.pendingReason, r.objectCategory, r.importId, r.importFileName,
            // 「打开文件」置灰口径(V65):已导出且 checksum 一致;「打开文件夹」始终可点(退化开 compare 目录)
            exportFileOk = exportFileOk(r.exportStatus, r.exportFile, r.exportChecksum),
            sampleRows = r.sampleRows)
    }

    /**
     * 目标库描述(schema_doc,库列表页可编辑):默认显示名回落链「自定义名 > 库描述 > 数据源名快照」的一环。
     * 动态查不做快照(描述后续被改,页面/导出跟随);db/schema 归一与单/多库方言处理在
     * [MetadataService.schemaDescription] 内完成,数据源已删/无描述返回 null
     */
    private fun schemaDescOf(datasourceId: Long, db: String, schema: String?): String? =
        metadataService.schemaDescription(datasourceId, db.ifBlank { null }, schema)

    private fun toTargetView(t: CompareRepository.TargetRow): CompareTargetView {
        val (db, schema) = normalizeLocation(t.datasourceId, t.dbName, t.schemaName)
        return CompareTargetView(
            t.id, t.datasourceId, t.dsName, db, schema, t.tableName, t.status,
            t.baseCount, t.targetCount, t.matchedCount,
            // 老任务没有匹配来源计数:编码命中视为全部命中,名称/大模型补配记 0
            t.codeMatchedCount ?: t.matchedCount, t.nameMatchedCount ?: 0, t.aiMatchedCount ?: 0,
            t.missingCount, t.extraCount, t.fieldMismatchCount,
            t.coverage, t.fieldConsistency, t.completeness, t.score, t.error,
            parseMapping(t.fieldMappingJson).takeIf { it.isNotEmpty() },
            parseIdentityKeys(t.identityJson), t.displayName,
            schemaDescOf(t.datasourceId, db, schema))
    }

    private fun parseFields(json: String?): List<String> =
        json?.let {
            try {
                objectMapper.readValue<List<String>>(it)
            } catch (e: Exception) {
                log.warn("比对字段 JSON 解析失败: {}", e.message)
                emptyList()
            }
        } ?: emptyList()

    /**
     * 导出文件内表名统一显示格式:单元格内三行——表名 / 系统名 / （数据库名.模式名）;
     * 系统名空省略该行,库与模式都空省略第三行(单行太长,拆行展示)
     */
    private fun tableDisplayName(system: String?, db: String, schema: String?, table: String): String {
        val lines = ArrayList<String>(3)
        lines.add(table)
        if (!system.isNullOrBlank()) lines.add(system)
        val location = listOf(db, schema.orEmpty()).filter { it.isNotBlank() }
        if (location.isNotEmpty()) lines.add("（${location.joinToString(".")}）")
        return lines.joinToString("\n")
    }

    private fun parseDiffs(json: String?): List<FieldDiff>? =
        json?.let {
            try {
                objectMapper.readValue<List<FieldDiff>>(it)
            } catch (e: Exception) {
                log.warn("差异明细 JSON 解析失败: {}", e.message)
                null
            }
        }

    /** diff_json 值截断:防 CLOB/大字段撑爆明细表 */
    private fun truncateDiffs(diffs: List<FieldDiff>): List<FieldDiff> =
        diffs.map { d ->
            FieldDiff(d.field, d.base?.take(MAX_DIFF_VALUE_CHARS), d.value?.take(MAX_DIFF_VALUE_CHARS), d.matched)
        }

    // ---------- 匹配逻辑 3:大模型归一化补配(实例侧,复用注入的 LLM 调用点) ----------

    /** 匹配逻辑 3 的补配结果 */
    private data class AiMatchOutcome(val pairs: List<MatchedPair>, val note: String?, val failed: Boolean)

    /** [AiMatchOutcome] 的对外视图(单测断言用,不暴露内部实现) */
    internal data class AiMatchView(val pairs: List<MatchedPair>, val note: String?, val failed: Boolean)

    /** 单测入口:直接对给定残余跑一轮大模型补配(生产路径由 compareOneTarget 调用) */
    internal fun aiMatchResiduesForTest(match: MatchResult,
                                        baseMap: LinkedHashMap<String, Map<String, String?>>,
                                        targetMap: Map<String, Map<String, String?>>,
                                        keyFields: List<String>, nameFields: List<String>,
                                        evidenceFields: Map<EvidenceKind, String> = emptyMap(),
                                        jobId: Long? = null, targetId: Long? = null,
                                        targetLabel: String? = null): AiMatchView {
        val outcome = aiMatchResidues(match, baseMap, targetMap, keyFields, nameFields, evidenceFields,
            jobId = jobId, targetId = targetId, targetLabel = targetLabel)
        return AiMatchView(outcome.pairs, outcome.note, outcome.failed)
    }

    /**
     * 匹配逻辑 3:把编码/名称两路都没配上的双侧残余对象补配齐——**每条残余要么被配对、要么经
     * 语义判读后才能定性未匹配**,不允许不看就记缺失/多余。三段式:
     * 1. 没有残余 / 未配置大模型 / 残余超过 [LLM_RESIDUE_LIMIT](失控保险丝,防身份字段配错
     *    导致全表进残余)→ 不调用模型,残余保持缺失/多余并给出说明;
     * 2. 本地归一化精确补配(零成本,[appendNormalizedPairs]):先吃掉大小写/全半角/空白/
     *    括号补充说明类脏数据,配不上的才进模型;
     * 3. 相似度召回(零成本,[recallCandidates])+ 召回分自动处置 + 模型裁决:
     *    - 低于 [LLM_CANDIDATE_FLOOR] 的候选不进 prompt(「水库/电站」这类通用 bigram 会召回
     *      大量零相关候选,既费 token 又干扰模型);全部候选低于下限的基准不进模型,按未匹配如实计数;
     *    - top1 ≥ [LLM_AUTO_ACCEPT_MIN] 且与 top2 分差 ≥ [LLM_AUTO_ACCEPT_MARGIN] 的基准不进模型
     *      直接采纳(名称高度相似且无竞争,实质是名称路的放宽,配对来源记 NAME);
     *    - 其余基准只带自己的 top-K 候选按条目预算装批(避免全量两两组合的平方级 token),
     *      批次间无依赖,固定 [LLM_PARALLEL] 并发调用,[CompareMatchPrompts.parsePairs] 解析后
     *      再过候选集校验(只采纳落在该基准候选内的配对,防模型跨候选乱配),按批次顺序合并去重
     *      (与串行口径一致,并发不影响结果确定性);
     * - 与所有目标零字符交集(召回不到候选)的基准、未被任何基准召回的目标在说明里如实计数披露
     *   (召回覆盖不到的极端别名可能漏配,需人工核对);
     * - 单个批次失败只跳过该批(记说明并置 failed)继续下一批,**绝不因为模型返工而炸任务**;
     * - [onBatchProgress] 每合并完一批回调一次(已完成批数,总批数),供任务 stage 透出补配进度。
     */
    private fun aiMatchResidues(match: MatchResult, baseMap: LinkedHashMap<String, Map<String, String?>>,
                                targetMap: Map<String, Map<String, String?>>, keyFields: List<String>,
                                nameFields: List<String>,
                                evidenceFields: Map<EvidenceKind, String> = emptyMap(),
                                onBatchProgress: (doneBatches: Int, totalBatches: Int) -> Unit = { _, _ -> },
                                jobId: Long? = null, targetId: Long? = null,
                                targetLabel: String? = null): AiMatchOutcome {
        val (rawBaseResidue, rawTargetResidue) = match.residues(baseMap, targetMap)
        if (rawBaseResidue.isEmpty() || rawTargetResidue.isEmpty()) return AiMatchOutcome(emptyList(), null, false)
        val config = aiConfigService?.findConfig()
        if (config == null) {
            return AiMatchOutcome(emptyList(),
                "未配置大模型,${rawBaseResidue.size} 条基准侧残余与 ${rawTargetResidue.size} 条目标侧残余按未匹配处理",
                false)
        }
        if (rawBaseResidue.size > LLM_RESIDUE_LIMIT || rawTargetResidue.size > LLM_RESIDUE_LIMIT) {
            return AiMatchOutcome(emptyList(),
                "残余对象过多(基准 ${rawBaseResidue.size} 条 / 目标 ${rawTargetResidue.size} 条,上限 " +
                    "$LLM_RESIDUE_LIMIT),未做归一化补配", false)
        }

        val pairs = ArrayList<MatchedPair>()
        val errors = ArrayList<String>()
        val usedBaseKeys = HashSet<String>()
        val usedTargetKeys = HashSet<String>()
        // Phase 0:本地归一化精确补配(零成本),格式类脏数据不花 token 直接配掉
        appendNormalizedPairs(rawBaseResidue, rawTargetResidue, baseMap, targetMap,
            keyFields, nameFields, pairs, usedBaseKeys, usedTargetKeys)
        // Phase 0 的名称归一化配对同样过佐证负证据(异地同名不能凭名字定案),拆出的键回残余走召回/仲裁
        if (evidenceFields.isNotEmpty()) {
            val it = pairs.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.by != "NAME") continue
                if (CompareEvidence.verdictsOf(baseMap.getValue(p.baseKey), targetMap.getValue(p.targetKey),
                        evidenceFields).strongConflict()) {
                    it.remove()
                    usedBaseKeys.remove(p.baseKey)
                    usedTargetKeys.remove(p.targetKey)
                }
            }
        }
        // Phase 0 之后的新残余才进召回 + 模型
        val baseResidueKeys = rawBaseResidue.filter { it !in usedBaseKeys }
        val targetResidueKeys = rawTargetResidue.filter { it !in usedTargetKeys }
        if (baseResidueKeys.isEmpty() || targetResidueKeys.isEmpty()) {
            return AiMatchOutcome(pairs, null, false)
        }

        val baseSeqKeys = baseResidueKeys.withIndex().associate { (i, k) -> (i + 1) to k }
        val targetSeqKeys = targetResidueKeys.withIndex().associate { (i, k) -> (i + 1) to k }
        // 佐证取值(类型 label → 行内值):佐证字段 ∈ 比对字段,两侧行 map 的键均已归一为基准字段名;
        // 目标侧未映射该字段时取值为 null(缺失≠冲突,中性处理)
        fun evidenceOf(row: Map<String, String?>): Map<String, String?> =
            evidenceFields.entries.associateTo(LinkedHashMap()) { (kind, field) ->
                kind.label to idValue(row, field)
            }
        val baseItems = baseResidueKeys.mapIndexed { i, k ->
            CompareMatchPrompts.MatchItem(i + 1, compositeKey(baseMap.getValue(k), keyFields),
                nameValue(baseMap.getValue(k), nameFields), evidenceOf(baseMap.getValue(k)))
        }
        val targetItems = targetResidueKeys.mapIndexed { i, k ->
            CompareMatchPrompts.MatchItem(i + 1, compositeKey(targetMap.getValue(k), keyFields),
                nameValue(targetMap.getValue(k), nameFields), evidenceOf(targetMap.getValue(k)))
        }
        // Phase 1:相似度召回(零成本)+ 佐证裁决(一致加分/冲突分级)+ 召回分自动处置
        val scored = recallCandidates(baseItems, targetItems, LLM_CANDIDATES_PER_BASE, LLM_POOL_SCORE_CAP)
        val noCandidateBases = baseItems.count { it.seq !in scored }
        val candidates = HashMap<Int, List<Int>>()
        // 仲裁通道:基准序号 → 区划单属性冲突的目标序号(prompt 里中性标注,交模型综合判)
        val regionConflicts = HashMap<Int, MutableSet<Int>>()
        var autoAccepted = 0
        var lowScoreSkipped = 0
        var strongRejected = 0
        var conflictRejectedBases = 0
        // 候选评估:目标序号 + 综合分(召回分 + 佐证一致加分)+ 佐证裁决
        data class Eval(val targetSeq: Int, val score: Double, val verdicts: CompareEvidence.Verdicts)
        for (b in baseItems) {
            val sc = scored[b.seq] ?: continue
            val baseKey = baseSeqKeys[b.seq] ?: continue
            val evals = ArrayList<Eval>(sc.size)
            for (cand in sc) {
                val targetKey = targetSeqKeys[cand.targetSeq] ?: continue
                val v = CompareEvidence.verdictsOf(
                    baseMap.getValue(baseKey), targetMap.getValue(targetKey), evidenceFields)
                // 多属性独立冲突(区划冲突 + 位置/河流也冲突):确定性按不同主体处理,不交模型
                if (v.strongConflict()) {
                    strongRejected++
                    continue
                }
                if (v.arbitrableConflict()) {
                    regionConflicts.getOrPut(b.seq) { mutableSetOf() }.add(cand.targetSeq)
                }
                evals.add(Eval(cand.targetSeq, cand.score + v.bonus(), v))
            }
            evals.sortByDescending { it.score }
            val kept = evals.filter { it.score >= LLM_CANDIDATE_FLOOR }
            if (kept.isEmpty()) {
                if (evals.isEmpty()) conflictRejectedBases++ else lowScoreSkipped++
                continue
            }
            val top1 = kept[0]
            val top2 = kept.getOrNull(1)
            // 自动采纳禁区:有任何属性冲突的候选无论分数多高都必须交模型
            if (!top1.verdicts.anyConflict() && top1.score >= LLM_AUTO_ACCEPT_MIN &&
                (top2 == null || top1.score - top2.score >= LLM_AUTO_ACCEPT_MARGIN)) {
                val targetKey = targetSeqKeys[top1.targetSeq]
                if (targetKey != null && usedBaseKeys.add(baseKey) && usedTargetKeys.add(targetKey)) {
                    pairs.add(MatchedPair(baseKey, targetKey, "NAME"))
                    autoAccepted++
                }
                continue // 无论采纳是否撞上已用键,该基准都不再进模型
            }
            candidates[b.seq] = kept.map { it.targetSeq }
        }
        val seenTargets = candidates.values.flatten().toHashSet()
        val unseenTargets = targetItems.count { it.seq !in seenTargets }
        // Phase 2:按条目预算装批,批次并发模型裁决(按批次顺序合并,候选集校验后跨批去重落地)
        val codeLabel = keyFields.joinToString("+")
        val nameLabel = nameFields.firstOrNull() ?: "名称"
        val targetsBySeq = targetItems.associateBy { it.seq }
        val batches = packCandidateBatches(baseItems.filter { it.seq in candidates },
            candidates, LLM_MAX_ITEMS_PER_REQUEST)
        // 流程步骤日志(补配内部分派明细,线程名 compare-N 归属任务):交模型前各通道的条目数
        log.info("比对步骤: 第5步 补配分派: 本地归一化已配 {} 对, 召回后 直采 {} / 仲裁候选 {} 条 / " +
            "多属性确定性拆 {} 条 / 低分跳过 {} 条, 交模型 {} 条基准( {} 批)",
            pairs.size, autoAccepted, regionConflicts.values.sumOf { it.size }, strongRejected,
            lowScoreSkipped, candidates.size, batches.size)
        if (batches.isNotEmpty()) {
            // 批次结果:解析出的配对;调用失败时 error 非空(该批跳过)
            data class BatchOutcome(val parsed: List<CompareMatchPrompts.Pair>, val error: String?)
            val llmPool = Executors.newFixedThreadPool(LLM_PARALLEL) { r ->
                Thread(r, "compare-llm-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
            }
            try {
                val futures = batches.mapIndexed { bi, batch ->
                    val batchNo = bi + 1
                    llmPool.submit(java.util.concurrent.Callable {
                        val prompt = CompareMatchPrompts.buildCandidateMatchPrompt(codeLabel, nameLabel,
                            batch, targetsBySeq, candidates, regionConflicts)
                        try {
                            val callStart = System.nanoTime()
                            val answer = aiChat.call(config, CompareMatchPrompts.SYSTEM_PROMPT, prompt)
                            val durationMs = (System.nanoTime() - callStart) / 1_000_000
                            val parsed = CompareMatchPrompts.parsePairs(
                                answer, batch.map { it.seq }, targetItems.map { it.seq })
                            // 留痕:本批逐条判定(配上的对 + 未配上的基准;序号 → code/name 用批内条目还原)
                            recordTrace(jobId) {
                                val matchedBaseSeqs = parsed.map { it.baseSeq }.toSet()
                                CompareAiTrace(jobId = jobId!!, targetId = targetId, targetLabel = targetLabel,
                                    scene = AiScene.COMPARE_MATCH.name, stage = CompareAiTrace.STAGE_RESIDUE,
                                    batchNo = batchNo, model = config.model,
                                    requestContent = traceRequestContent(CompareMatchPrompts.SYSTEM_PROMPT, prompt),
                                    responseContent = answer,
                                    resultJson = objectMapper.writeValueAsString(mapOf(
                                        "pairs" to parsed.map { p ->
                                            val b = batch.firstOrNull { it.seq == p.baseSeq }
                                            val t = targetsBySeq[p.targetSeq]
                                            mapOf("baseCode" to b?.code, "baseName" to b?.name,
                                                "targetCode" to t?.code, "targetName" to t?.name)
                                        },
                                        "unmatchedBase" to batch.filter { it.seq !in matchedBaseSeqs }
                                            .map { mapOf("code" to it.code, "name" to it.name) },
                                    )),
                                    durationMs = durationMs)
                            }
                            BatchOutcome(parsed, null)
                        } catch (e: Exception) {
                            // 调用失败的批次同样留痕(错误摘要进 response,result_json 空):「AI 没判」也可查
                            recordTrace(jobId) {
                                CompareAiTrace(jobId = jobId!!, targetId = targetId, targetLabel = targetLabel,
                                    scene = AiScene.COMPARE_MATCH.name, stage = CompareAiTrace.STAGE_RESIDUE,
                                    batchNo = batchNo, model = config.model,
                                    requestContent = traceRequestContent(CompareMatchPrompts.SYSTEM_PROMPT, prompt),
                                    responseContent = "调用失败: ${abbreviate(e.message)}")
                            }
                            BatchOutcome(emptyList(), e.message)
                        }
                    })
                }
                // 按批次顺序合并:与串行口径一致(先批次的配对优先),并发只省时间不省确定性
                futures.forEachIndexed { i, f ->
                    val outcome = f.get()
                    if (outcome.error != null) {
                        log.warn("大模型归一化补配调用失败(跳过该批): {}", outcome.error)
                        if (errors.size < LLM_ERROR_KEEP) errors.add(abbreviate(outcome.error))
                    } else {
                        for (p in CompareMatchPrompts.filterPairsByCandidates(outcome.parsed, candidates)) {
                            val baseKey = baseSeqKeys[p.baseSeq] ?: continue
                            val targetKey = targetSeqKeys[p.targetSeq] ?: continue
                            if (!usedBaseKeys.add(baseKey) || !usedTargetKeys.add(targetKey)) continue
                            pairs.add(MatchedPair(baseKey, targetKey, "LLM"))
                        }
                    }
                    onBatchProgress(i + 1, batches.size)
                }
            } finally {
                llmPool.shutdown()
            }
        }
        val notes = ArrayList<String>()
        if (errors.isNotEmpty()) {
            notes.add("大模型归一化补配部分批次失败(${errors.joinToString(";")}),失败批次的对象按未匹配处理")
        }
        if (autoAccepted > 0) {
            notes.add("$autoAccepted 条基准对象候选相似度足够高,未交大模型直接按名称配对")
        }
        if (strongRejected > 0) {
            notes.add("$strongRejected 条候选因行政区划与位置/河流均不一致按不同主体处理(未交模型)")
        }
        if (conflictRejectedBases > 0) {
            notes.add("$conflictRejectedBases 条基准对象的候选均因多属性不一致被排除,按未匹配处理")
        }
        if (lowScoreSkipped > 0) {
            notes.add("$lowScoreSkipped 条基准对象召回候选相似度过低,未交大模型按未匹配处理")
        }
        if (noCandidateBases > 0) {
            notes.add("$noCandidateBases 条基准对象与所有目标的编码/名称无字符交集,无法召回候选,按未匹配处理" +
                "(召回覆盖不到的极端别名可能漏配,需人工核对)")
        }
        if (unseenTargets > 0) {
            notes.add("$unseenTargets 条目标对象未被任何基准召回为候选,若实际存在对应基准" +
                "(如零字符交集的别名)会被误报为多余,需人工核对")
        }
        return AiMatchOutcome(pairs, notes.takeIf { it.isNotEmpty() }?.joinToString(";"), errors.isNotEmpty())
    }

    /** 单测入口:直接对给定配对结果跑同名二轮消歧(生产路径由 compareOneTarget 在匹配逻辑 3 下调用) */
    internal fun aiRefineSameNameGroupsForTest(match: MatchResult,
                                               baseMap: LinkedHashMap<String, Map<String, String?>>,
                                               targetMap: Map<String, Map<String, String?>>,
                                               keyFields: List<String>, nameFields: List<String>,
                                               fields: List<String>,
                                               fieldLabels: Map<String, String> = emptyMap(),
                                               jobId: Long? = null, targetId: Long? = null,
                                               targetLabel: String? = null): AiMatchView {
        val outcome = aiRefineSameNameGroups(match, baseMap, targetMap, keyFields, nameFields, fields, fieldLabels,
            jobId = jobId, targetId = targetId, targetLabel = targetLabel)
        return AiMatchView(outcome.pairs, outcome.note, outcome.failed)
    }

    /**
     * 同名二轮消歧(匹配逻辑 3 的第二段):首轮(编码/名称/大模型)配对后,同名歧义组
     * (某名称双侧都有、至少一侧 ≥2 条,见 [sameNameAmbiguousGroups])带**经区分度裁剪的字段取值**
     * ([pickDiscriminatingFields]:剔除身份/名称/全空/全同值/UUID 形态/序号类字段,砍掉零信息量噪音)
     * 再交大模型判定哪些是同一名下的同一对象、哪些只是重名——解决「多个同名水库靠名称首配
     * 会张冠李戴」的问题(如 4 个编码全空的「石门」按经纬度/位置区分)。
     * 多个歧义组按条目预算装批(每组成本 = 1 + 双侧条数)、固定 [LLM_PARALLEL] 并发调用,
     * 输出带组号的配对数组([CompareMatchPrompts.parseGroupPairs] 解析,组号/序号越界丢弃),
     * 按批次顺序合并,与串行口径一致。
     * - [AiMatchOutcome.pairs] 语义与 [aiMatchResidues] 不同:返回**替换后的完整配对列表**,无变化时原列表引用返回;
     * - 无歧义组 / 未配置大模型 / 参与对象超 [LLM_RESIDUE_LIMIT] → 不调用模型,返回原列表(后者附说明);
     * - 单批调用失败只跳过该批(记说明并置 failed),批内各组保持首轮配对,绝不炸任务。
     */
    private fun aiRefineSameNameGroups(match: MatchResult,
                                       baseMap: LinkedHashMap<String, Map<String, String?>>,
                                       targetMap: Map<String, Map<String, String?>>,
                                       keyFields: List<String>, nameFields: List<String>,
                                       fields: List<String>,
                                       fieldLabels: Map<String, String> = emptyMap(),
                                       jobId: Long? = null, targetId: Long? = null,
                                       targetLabel: String? = null): AiMatchOutcome {
        val groups = sameNameAmbiguousGroups(match.pairs, baseMap, targetMap, nameFields)
        if (groups.isEmpty()) return AiMatchOutcome(match.pairs, null, false)
        val total = groups.values.sumOf { it.baseKeys.size + it.targetKeys.size }
        val config = aiConfigService?.findConfig()
            ?: return AiMatchOutcome(match.pairs,
                "同名对象二轮消歧未执行(未配置大模型,歧义组 ${groups.size} 个/共 $total 条)", false)
        if (total > LLM_RESIDUE_LIMIT) {
            return AiMatchOutcome(match.pairs,
                "同名对象二轮消歧未执行(参与对象 $total 超过上限 $LLM_RESIDUE_LIMIT)", false)
        }
        // 逐组字段裁剪 + 组内序号映射 + prompt 条目构造(组号跨批全局 1 起)
        // 字段标签:基准侧注释优先([fieldLabels],无注释回落字段名,与导出口径一致),模型按中文语义判读
        fun labelOf(f: String) = fieldLabels[f] ?: f
        data class GroupCtx(val name: String, val baseSeqKeys: Map<Int, String>,
                            val targetSeqKeys: Map<Int, String>,
                            val items: CompareMatchPrompts.SameNameGroupItems,
                            val pickedFields: List<String>)
        val ctxs = groups.values.toList().mapIndexed { gi, g ->
            val baseSeqKeys = g.baseKeys.withIndex().associate { (i, k) -> (i + 1) to k }
            val targetSeqKeys = g.targetKeys.withIndex().associate { (i, k) -> (i + 1) to k }
            val memberRows = g.baseKeys.map { baseMap.getValue(it) } + g.targetKeys.map { targetMap.getValue(it) }
            val picked = pickDiscriminatingFields(fields, keyFields, nameFields, memberRows)
            fun itemOf(seq: Int, k: String, m: Map<String, Map<String, String?>>): CompareMatchPrompts.SameNameItem {
                val row = m.getValue(k)
                return CompareMatchPrompts.SameNameItem(seq, compositeKey(row, keyFields),
                    nameValue(row, nameFields), picked.map { f -> labelOf(f) to row[f] })
            }
            GroupCtx(g.name, baseSeqKeys, targetSeqKeys,
                CompareMatchPrompts.SameNameGroupItems(gi + 1, g.name,
                    g.baseKeys.mapIndexed { i, k -> itemOf(i + 1, k, baseMap) },
                    g.targetKeys.mapIndexed { i, k -> itemOf(i + 1, k, targetMap) }),
                picked.map { labelOf(it) })
        }
        // 装批:每组成本 = 1 + 双侧条数;批内并发调用,按批次顺序合并(与串行口径一致)
        val ctxBySeq = ctxs.associateBy { it.items.groupSeq }
        val batches = ArrayList<List<GroupCtx>>()
        run {
            var current = ArrayList<GroupCtx>()
            var used = 0
            for (ctx in ctxs) {
                val cost = 1 + ctx.items.bases.size + ctx.items.targets.size
                if (current.isNotEmpty() && used + cost > LLM_MAX_ITEMS_PER_REQUEST) {
                    batches.add(current); current = ArrayList(); used = 0
                }
                current.add(ctx); used += cost
            }
            if (current.isNotEmpty()) batches.add(current)
        }
        val decisions = HashMap<String, List<Pair<String, String>>>()
        val errors = ArrayList<String>()
        // 批次结果:解析出的组配配对;调用失败时 error 非空(批内各组保持首轮配对)
        data class BatchOutcome(val parsed: List<CompareMatchPrompts.GroupPair>, val error: String?)
        val llmPool = Executors.newFixedThreadPool(LLM_PARALLEL) { r ->
            Thread(r, "compare-llm-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
        }
        try {
            val futures = batches.mapIndexed { bi, batch ->
                val batchNo = bi + 1
                llmPool.submit(java.util.concurrent.Callable {
                    val prompt = CompareMatchPrompts.buildSameNameBatchPrompt(
                        batch.map { it.items }, batch.associate { it.items.groupSeq to it.pickedFields })
                    val valid = batch.associate {
                        it.items.groupSeq to (it.baseSeqKeys.keys to it.targetSeqKeys.keys)
                    }
                    try {
                        val callStart = System.nanoTime()
                        val answer = aiChat.call(config, CompareMatchPrompts.SAME_NAME_SYSTEM_PROMPT, prompt)
                        val durationMs = (System.nanoTime() - callStart) / 1_000_000
                        val parsed = CompareMatchPrompts.parseGroupPairs(answer, valid)
                        // 留痕:本批逐组逐条判定(组名 + 基准名称 → 目标编码/名称,序号用组内条目还原)
                        recordTrace(jobId) {
                            CompareAiTrace(jobId = jobId!!, targetId = targetId, targetLabel = targetLabel,
                                scene = AiScene.COMPARE_MATCH.name, stage = CompareAiTrace.STAGE_SAME_NAME,
                                batchNo = batchNo, model = config.model,
                                requestContent = traceRequestContent(CompareMatchPrompts.SAME_NAME_SYSTEM_PROMPT, prompt),
                                responseContent = answer,
                                resultJson = objectMapper.writeValueAsString(mapOf(
                                    "pairs" to parsed.mapNotNull { gp ->
                                        val ctx = ctxBySeq[gp.groupSeq] ?: return@mapNotNull null
                                        val b = ctx.items.bases.firstOrNull { it.seq == gp.baseSeq }
                                        val t = ctx.items.targets.firstOrNull { it.seq == gp.targetSeq }
                                        mapOf("group" to ctx.name, "baseName" to b?.name,
                                            "targetCode" to t?.code, "targetName" to t?.name)
                                    })),
                                durationMs = durationMs)
                        }
                        BatchOutcome(parsed, null)
                    } catch (e: Exception) {
                        // 调用失败的批次同样留痕(错误摘要进 response,result_json 空):「AI 没判」也可查
                        recordTrace(jobId) {
                            CompareAiTrace(jobId = jobId!!, targetId = targetId, targetLabel = targetLabel,
                                scene = AiScene.COMPARE_MATCH.name, stage = CompareAiTrace.STAGE_SAME_NAME,
                                batchNo = batchNo, model = config.model,
                                requestContent = traceRequestContent(CompareMatchPrompts.SAME_NAME_SYSTEM_PROMPT, prompt),
                                responseContent = "调用失败: ${abbreviate(e.message)}")
                        }
                        BatchOutcome(emptyList(), e.message)
                    }
                })
            }
            for (f in futures) {
                val outcome = f.get()
                if (outcome.error != null) {
                    log.warn("同名二轮消歧调用失败(跳过该批): {}", outcome.error)
                    if (errors.size < LLM_ERROR_KEEP) errors.add(abbreviate(outcome.error))
                    continue
                }
                for (gp in outcome.parsed) {
                    val ctx = ctxBySeq[gp.groupSeq] ?: continue
                    val b = ctx.baseSeqKeys[gp.baseSeq] ?: continue
                    val t = ctx.targetSeqKeys[gp.targetSeq] ?: continue
                    decisions.computeIfAbsent(ctx.name) { ArrayList() }.let { list ->
                        list as MutableList
                        if (list.none { it.first == b || it.second == t }) list.add(b to t)
                    }
                }
            }
        } finally {
            llmPool.shutdown()
        }
        val newPairs = applySameNameRefine(match.pairs, groups, decisions)
        val refined = decisions.values.sumOf { it.size }
        if (refined > 0) log.info("比对步骤: 第5步 同名二轮消歧重配 {} 对(歧义组 {} 个, {} 批)",
            refined, groups.size, batches.size)
        val note = if (errors.isEmpty()) null
        else "同名二轮消歧部分组失败(${errors.joinToString(";")}),失败组保持首轮配对"
        return AiMatchOutcome(newPairs, note, errors.isNotEmpty())
    }

    /** 错误信息缩写:进目标备注/日志前限长 */
    private fun abbreviate(message: String?): String =
        (message ?: "未知错误").let { if (it.length <= 200) it else it.take(200) + "…" }

    companion object {
        /** 任务执行线程池的线程序号(线程命名 compare-N) */
        private val THREAD_IDX = AtomicInteger()

        /** 分页拉全量的每页行数 */
        const val PAGE_SIZE = 5000

        /** 流式读取每读这么多行打一条 debug 进度日志(大表单条长语句期间可见读取进度) */
        private const val STREAM_PROGRESS_LOG_EVERY = 20000

        /** 单侧行数上限:超出抛 IllegalStateException,任务判 FAILED */
        const val MAX_SIDE_ROWS = 500_000

        /**
         * 身份列为空的行的行内代理键前缀:这类行没有可对齐的组合身份值,loadRows 不给它 continue 丢弃,
         * 而是以「\u0002+序号」进行 map(真实组合键不会出现该前缀,与既有 \u0001 拼接键同一假设),
         * 编码路自然跳过它、名称/大模型两路可把它配对(「任意一边 code 空就用 name 匹配」口径);
         * 明细/导出落 object_key 时经 [isNoKeyRow] 识别,回落用显示名展示
         */
        const val NO_KEY_ROW_PREFIX = "\u0002"

        /** 行 map 的键是否为身份列为空行的行内代理键(见 [NO_KEY_ROW_PREFIX]) */
        fun isNoKeyRow(key: String): Boolean = key.startsWith(NO_KEY_ROW_PREFIX)

        /** 差异明细批量落库的每批行数 */
        private const val DIFF_BATCH_SIZE = 500

        /**
         * diff_json 单值截断长度:匹配逻辑 2/3 下同一对象可能两侧编码/名称都不同,
         * 说明文案(基准「…」→ 目标「…」)会明显变长,故放宽到 12000 字符防截断误导。
         */
        private const val MAX_DIFF_VALUE_CHARS = 12000

        /** 问题字段排行取前 N */
        private const val FIELD_ISSUE_TOP_N = 10

        /** 目标缺列时 diff_json 里 value 的特殊标记 */
        const val MISSING_COLUMN_MARK = "«字段缺失»"

        /**
         * 大模型归一化补配的残余上限(双侧残余各不超过该值才送模型):只做失控保险丝
         * (身份字段配错导致全表进残余),正常业务量级(召回 + 裁决结构下成本约为 N×K)不再触顶;
         * 超限不补配并在目标备注里说明
         */
        const val LLM_RESIDUE_LIMIT = 20000

        /** 每条基准残余召回多少条候选目标送模型裁决(top-K,相似度降序) */
        const val LLM_CANDIDATES_PER_BASE = 20

        /**
         * 单请求条目预算(每条基准 1 + 其候选数):贪心装批;佐证字段进 prompt 后单候选成本上升,
         * 预算从 600 收到 400,控制单请求 token
         */
        const val LLM_MAX_ITEMS_PER_REQUEST = 400

        /** 召回精排前的候选池命中截断:防「全部同名」场景退化成全量两两比对 */
        const val LLM_POOL_SCORE_CAP = 2000

        /**
         * 候选相似度下限:低于该分的候选不进 prompt——「水库/电站」这类通用 bigram 会召回大量
         * 零相关候选,既费 token 又干扰模型;基准的全部候选低于下限时该基准不进模型按未匹配处理
         */
        const val LLM_CANDIDATE_FLOOR = 0.5

        /**
         * 召回高分自动采纳线:top1 ≥ 该分且与 top2 分差 ≥ [LLM_AUTO_ACCEPT_MARGIN] 时不交模型直接配对
         * (名称高度相似且无竞争,实质是名称路的放宽,来源记 NAME;拿不准的一律进模型,宁保守不错配)
         */
        const val LLM_AUTO_ACCEPT_MIN = 2.2

        /** 自动采纳的 top1/top2 最小分差(防两个高分候选二选一撞错) */
        const val LLM_AUTO_ACCEPT_MARGIN = 0.6

        /** 大模型补配批次并发数:批次间无依赖,合并按批次顺序保证结果与串行一致;对端限流的保守值 */
        private const val LLM_PARALLEL = 3

        /** 单次请求失败的累计记录上限(避免 note 过长) */
        private const val LLM_ERROR_KEEP = 3

        /** 匹配逻辑归一:空 = EXACT(新建默认);未知值报 400,避免静默按别的口径跑 */
        internal fun normalizeMatchMode(raw: String?): MatchMode = MatchMode.normalize(raw)

        private val DIFF_TYPES = setOf("SAME", "DIFF", "MISSING", "EXTRA")

        /** 导出总览 sheet 名(固定第一个 sheet) */
        const val OVERVIEW_SHEET_NAME = "总览"

        /** 行级对比明细 sheet 名(固定第二个 sheet) */
        private const val ROW_LEVEL_SHEET_NAME = "行级对比明细"

        /** 字段级差异汇总 sheet 名(固定第三个 sheet) */
        private const val FIELD_SUMMARY_SHEET_NAME = "字段级差异汇总"

        /** 数据级字段对比差异总览 sheet 名(固定第四个 sheet):一行一条数据,按系统给对比/相同/不同字段数 */
        private const val COLUMN_DETAIL_SHEET_NAME = "数据级字段对比差异总览"

        /** 列级对比明细 sheet 名(固定第五个 sheet):所有比对系统逐字段取值横向合并,左侧 5 列冻结 */
        private const val MERGED_DETAIL_SHEET_NAME = "列级对比明细"

        /** 列级对比明细「差异原因」:该字段在此系统未连线、未参与比对(各系统比对字段取并集,缺的系统占位);
         *  注意与 [REASON_MISSING_COLUMN]「业务表无此字段」区分:这里是**没连线/没比对**,不是业务表缺这一列 */
        private const val REASON_NOT_COMPARED = "未比对(无此字段)"

        /** 字段级差异汇总表头:按「比对目标 × 基准字段」统计差异数量,并按 缺失/多余/不一致 三类拆分;
         *  业务表字段 = 字段映射里该基准字段连到的目标列(无映射按同名,自动匹配口径);
         *  业务表字段中文 = 目标表该列的注释(无注释留空,不回落字段名) */
        private val FIELD_SUMMARY_HEADERS = listOf(
            "业务表英文名", "业务表中文名", "基准表字段", "字段中文", "业务表字段", "业务表字段中文",
            "差异数量", "缺失", "多余", "不一致")

        /** 行级对比明细表头:基准/业务两侧各 表英文名/表中文名 + 编码字段/编码 + 名称字段/名称 + 差异说明 + 末列差异类型 */
        private val ROW_LEVEL_HEADERS = listOf(
            "基准表英文名", "基准表中文名", "基准编码字段", "基准编码", "基准名称字段", "基准名称",
            "业务表英文名", "业务表中文名", "业务表编码字段", "业务表编码", "业务表名称字段", "业务表名称",
            "差异说明", "差异类型")

        /** 行级对比明细「差异类型」文案:不一致 / 缺失 / 多余(与字段级差异汇总的三分类同口径,均相对基准而言) */
        private const val ROW_TYPE_DIFF = "不一致"
        private const val ROW_TYPE_MISSING = "缺失"
        private const val ROW_TYPE_EXTRA = "多余"

        /** 行级差异类型标签:DIFF=不一致 / MISSING=缺失 / EXTRA=多余;SAME 等异常值兜底按不一致 */
        private fun rowLevelTypeLabel(diffType: String): String = when (diffType) {
            "MISSING" -> ROW_TYPE_MISSING
            "EXTRA" -> ROW_TYPE_EXTRA
            else -> ROW_TYPE_DIFF
        }

        /** 总览 sheet 表头(与客户既有核对表列口径一致;「匹配对象数」为本工具追加列——核对表原本只有编码一路匹配) */
        private val EXPORT_OVERVIEW_HEADERS = listOf(
            "表中文名", "表英文名称", "所属系统", "条数", "数据最新更新时间", "与基准差", "匹配编码数",
            "匹配对象数", "差异条数", "差异原因")

        /** 差异格红底色(与前端差异明细页问题格同一底色 #ffebee) */
        private val DIFF_FILL_RGB = byteArrayOf(0xFF.toByte(), 0xEB.toByte(), 0xEE.toByte())

        /** 明细 sheet 表头(对象编码/名称两列之后的固定七列):基准/业务两块各 字段英文名/中文名/值 + 差异原因 */
        private val DETAIL_BLOCK_HEADERS = listOf(
            "基准表字段", "字段中文", "基准表值", "业务表字段名", "业务表中文", "业务表值", "差异原因")

        /** 明细 sheet 末列(差异原因)列号:对象编码/名称 + 固定七列 */
        private const val DETAIL_LAST_COL = 8

        /** 明细 sheet 无显示名字段时「对象名称」列表头兜底 */
        private const val OBJECT_NAME_HEADER = "对象名称"

        /** 差异原因:字段取值不一致(对齐客户既有核对表口径) */
        private const val REASON_TEXT_DIFF = "文本不一致"

        /** 差异原因:目标表缺该列 */
        private const val REASON_MISSING_COLUMN = "业务表无此字段"

        /** 「数据最新更新时间」MAX() 取值的展示格式(时间族统一到秒) */
        private val LATEST_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * 时间字段选取:名称/注释规则优先([CompareTimeFieldPrompts.detectByRule]),
         * 未命中且提供了 [aiPicker](大模型语义匹配;未配置大模型时传 null)才交大模型;[aiPicker] 返回 null 表示也没有
         */
        internal fun pickLatestTimeColumn(columns: List<ColumnMeta>, table: String,
                                          aiPicker: ((String, List<ColumnMeta>) -> ColumnMeta?)?): ColumnMeta? =
            CompareTimeFieldPrompts.detectByRule(columns) ?: aiPicker?.invoke(table, columns)

        /** 基准行差异原因占位 */
        private const val BASELINE_REASON = "基准表(不参与差异统计)"

        /** 目标未完成时的差异原因兜底 */
        private const val UNFINISHED_REASON = "比对未完成,无差异明细"

        /** 明细 sheet 的最大数据行数(Excel 单表上限 1,048,576 行,留出上下文与表头余量) */
        const val MAX_ROWS_PER_SHEET = 1_000_000

        /** Excel 单工作簿 sheet 上限(65530),总览占 1 个 */
        const val MAX_SHEETS = 65_530

        /** SXSSF 滑动窗口行数 */
        private const val SXSSF_ROW_WINDOW = 200

        /** schema 归一:未填时用库名兜底(MySQL 场景 schema=库名,与抽样导出口径一致) */
        internal fun effectiveSchema(schema: String?, db: String): String =
            schema?.takeIf { it.isNotBlank() } ?: db.takeIf { it.isNotBlank() } ?: ""

        /**
         * 库/schema 存储口径归一(纯函数):单库方言「库就是 schema」——schema 空而 db 有值时把 db 并回
         * schema、db 置空,与手工建任务口径一致(前端选表/字段接口一律按 schemas/{schema}/ 拼路径,
         * schema 空串会拼出 // 直接 404);多库方言(db/schema 各一层)或本就合规的行原样返回
         */
        internal fun normalizeDbSchema(db: String?, schema: String?, multiDb: Boolean): Pair<String, String?> =
            if (!multiDb && schema.isNullOrBlank() && !db.isNullOrBlank()) Pair("", db.trim())
            else Pair(db.orEmpty(), schema)

        /** 是否文本型字段(显示名选取口径:字符型或 CLOB) */
        internal fun isTextType(c: ColumnMeta): Boolean =
            c.isCharacter() || c.jdbcType == Types.CLOB || c.jdbcType == Types.NCLOB

        /**
         * 对象名称(显示名)字段解析:
         * - 用户指定优先:`specified` 必须属于 `fields`(忽略大小写),归一为基准表实际列名;
         * - 未指定则回退「比对字段中第一个文本型非主键字段」;
         * - 都没有返回 null(object_name 落空串)。
         * `fields` 需为基准表实际列名,返回值可直接作为行 map 的键(与 displayName 取值口径一致)。
         */
        internal fun resolveDisplayField(specified: String?, fields: List<String>,
                                         baseByName: Map<String, ColumnMeta>, keyName: String): String? {
            val picked = specified?.trim()?.takeIf { it.isNotEmpty() }
            if (picked != null) {
                if (fields.none { it.equals(picked, ignoreCase = true) }) {
                    throw IllegalArgumentException("对象名称字段必须在比对字段内: $picked")
                }
                return baseByName[picked.lowercase()]?.name
                    ?: throw IllegalArgumentException("基准表不存在字段: $picked")
            }
            return fields.mapNotNull { baseByName[it.lowercase()] }
                .firstOrNull { !it.name.equals(keyName, ignoreCase = true) && isTextType(it) }?.name
        }

        /**
         * 对象名称字段多选归一(V73):显式给出时逐个校验(∈比对字段、归一实际列名、与身份字段互斥否则 400);
         * 未给出按 [specifiedSingle] 单值旧逻辑(同样做互斥校验),再空回退第一个文本型非身份字段
         * (多身份字段时全部排除,保证互斥;单身份老任务与 [resolveDisplayField] 回退口径一致)
         */
        internal fun resolveDisplayFields(specified: List<String>?, specifiedSingle: String?, fields: List<String>,
                                          baseByName: Map<String, ColumnMeta>,
                                          keyFields: List<String>): List<String> {
            val multi = specified?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                ?.takeIf { it.isNotEmpty() }
            if (multi != null) {
                return multi.map { f ->
                    val col = resolveDisplayField(f, fields, baseByName, keyFields.first())!!
                    if (keyFields.any { it.equals(col, ignoreCase = true) }) {
                        throw IllegalArgumentException("身份字段与对象名称字段不能重复: $col")
                    }
                    col
                }
            }
            val single = specifiedSingle?.trim()?.takeIf { it.isNotEmpty() }
            if (single != null) {
                val col = resolveDisplayField(single, fields, baseByName, keyFields.first())!!
                if (keyFields.any { it.equals(col, ignoreCase = true) }) {
                    throw IllegalArgumentException("身份字段与对象名称字段不能重复: $col")
                }
                return listOf(col)
            }
            return listOfNotNull(fields.mapNotNull { baseByName[it.lowercase()] }
                .firstOrNull { c -> keyFields.none { it.equals(c.name, ignoreCase = true) } && isTextType(c) }?.name)
        }

        /** 单条对象差异:diffs 仅 DIFF 行有值 */
        /** 单条对象差异:diffs 仅 DIFF 行有值;matchBy 为该行对象的对齐来源(CODE/NAME/LLM) */
        data class ObjectDiff(val objectKey: String, val objectName: String,
                              val diffType: String, val diffs: List<FieldDiff>?,
                              val matchBy: String? = null)

        /** 比对结果:四类明细 + 算指标所需计数(comparedCells=matched×比对字段数,即目标侧已比对单元格数) */
        data class CompareDiffResult(
            val same: List<ObjectDiff>,
            val diff: List<ObjectDiff>,
            val missing: List<ObjectDiff>,
            val extra: List<ObjectDiff>,
            /** 双侧都存在的对象数(SAME+DIFF) */
            val matchedCount: Int,
            /** 不一致字段单元格总数(含「列缺失」) */
            val fieldMismatchCount: Int,
            /** 目标侧已比对单元格数(matched × fields.size) */
            val comparedCells: Int,
            /** 其中非空单元格数(列缺失按空计) */
            val nonNullCells: Int,
            /** 按编码对齐上的对象数 */
            val codeMatched: Int,
            /** 按对象名称补配上的对象数 */
            val nameMatched: Int,
            /** 大模型归一化补配上的对象数 */
            val aiMatched: Int,
        )

        /**
         * 比对核心纯函数:把已经对齐好的对象逐字段比较,产出四类明细与指标计数。
         * 行 map 的键为基准字段名(目标侧列名在取数时已归一成基准字段名);目标行只含目标侧存在的列,
         * 缺列(key 不在行 map)记「列缺失」按不一致计。
         * 比较规则:字符串 trim;NULL 与空串视为一致;numericFields 中的字段去千分位逗号后转
         * BigDecimal 用 compareTo==0 判定(解析失败回落字符串比较);身份字段(组合)值相等的行才自动配对。
         *
         * 对象对齐(appliedPairs,见 [matchObjects] 与实例侧大模型补配):为空时退化为「两侧行键相等」的
         * 旧口径(老测试与直接调用方);给出时按配对逐对比较,两侧键不等的对象(靠名称/大模型配上的)也算命中,
         * 明细的 object_key 统一取**基准侧键**(配对行以基准对象为视角),两侧各自的键值差异照常在字段级体现。
         * 配对里任一侧不在行 map(防御脏数据)时该配对忽略,对应的行退回缺失/多余。
         *
         * DIFF/MISSING/EXTRA 三类明细都会为**全部比对字段**各生成一条 [FieldDiff],这样界面与导出
         * 单看 diff_json 就能「每格显示真实值」:
         * - DIFF 行:base=基准值、value=目标真实值(一致字段也有值),matched 标明该字段是否一致;
         * - MISSING 行:base=基准值、value 为 null(目标整行不存在),matched 恒 false;
         * - EXTRA 行:base 为 null(基准整行不存在)、value=目标真实值,matched 恒 false。
         */
        fun diffObjects(baseMap: LinkedHashMap<String, Map<String, String?>>,
                        targetMap: Map<String, Map<String, String?>>,
                        fields: List<String>, keyFields: List<String>,
                        numericFields: Set<String> = emptySet(),
                        displayFields: List<String> = emptyList(),
                        appliedPairs: List<MatchedPair> = emptyList()): CompareDiffResult {
            val same = ArrayList<ObjectDiff>()
            val diff = ArrayList<ObjectDiff>()
            val missing = ArrayList<ObjectDiff>()
            val extra = ArrayList<ObjectDiff>()
            var fieldMismatchCount = 0
            var comparedCells = 0
            var nonNullCells = 0
            var codeMatched = 0
            var nameMatched = 0
            var aiMatched = 0
            // 已配上的目标侧键:未出现在配对里的目标行才是「多余」
            val pairedTargetKeys = HashSet<String>()

            // 配对建立:显式配对优先(名称/大模型配对两侧键不等),其余按「身份(组合)值相等」自动配对(旧口径)
            val keyedTarget = LinkedHashMap<String, String>(targetMap.size)
            for ((targetKey, row) in targetMap) {
                compositeKey(row, keyFields)?.let { keyedTarget.putIfAbsent(it, targetKey) }
            }
            val pairs = ArrayList<MatchedPair>(minOf(baseMap.size, targetMap.size) + appliedPairs.size)
            val pairedBaseKeys = HashSet<String>()
            for (p in appliedPairs) {
                if (p.baseKey !in baseMap || p.targetKey !in targetMap) continue
                if (!pairedBaseKeys.add(p.baseKey) || !pairedTargetKeys.add(p.targetKey)) continue
                pairs.add(p)
            }
            for ((baseKey, baseRow) in baseMap) {
                if (baseKey in pairedBaseKeys) continue
                val targetKey = compositeKey(baseRow, keyFields)?.let { keyedTarget[it] } ?: continue
                if (targetKey in pairedTargetKeys) continue
                pairs.add(MatchedPair(baseKey, targetKey, "CODE"))
                pairedBaseKeys.add(baseKey)
                pairedTargetKeys.add(targetKey)
            }

            for (pair in pairs) {
                val baseRow = baseMap[pair.baseKey] ?: continue
                val targetRow = targetMap[pair.targetKey] ?: continue
                when (pair.by) {
                    "CODE" -> codeMatched++
                    "NAME" -> nameMatched++
                    else -> aiMatched++
                }
                // 配对的双侧对象以「基准侧键」为该对象的标识(缺失/多余行才用自己一侧的键);
                // 身份列为空行的行内代理键不落库/展示,回落用显示名(编码差异本身由字段级体现)
                val objectKey = if (isNoKeyRow(pair.baseKey)) displayName(baseRow, displayFields) else pair.baseKey
                val name = displayName(baseRow, displayFields)
                val rowDiffs = ArrayList<FieldDiff>(fields.size)
                var allEqual = true
                for (f in fields) {
                    comparedCells++
                    val baseValue = baseRow[f]?.trim()
                    val rowDiff = if (!targetRow.containsKey(f)) {
                        // 目标缺该列:全部按不一致计,value 用特殊标记说明
                        fieldMismatchCount++
                        allEqual = false
                        FieldDiff(f, baseValue, MISSING_COLUMN_MARK, matched = false)
                    } else {
                        val targetValue = targetRow[f]
                        if (!targetValue.isNullOrBlank()) nonNullCells++
                        val equal = valuesEqual(f, baseRow[f], targetValue, numericFields)
                        if (!equal) {
                            fieldMismatchCount++
                            allEqual = false
                        }
                        // 一致字段同样保存目标真实值(matched=true),界面/导出才能每格显示真实值
                        FieldDiff(f, baseValue, targetValue?.trim(), matched = equal)
                    }
                    rowDiffs.add(rowDiff)
                }
                if (allEqual) {
                    same.add(ObjectDiff(objectKey, name, "SAME", null, pair.by))
                } else {
                    diff.add(ObjectDiff(objectKey, name, "DIFF", rowDiffs, pair.by))
                }
            }
            for ((baseKey, baseRow) in baseMap) {
                if (baseKey in pairedBaseKeys) continue
                // 整行快照:基准值齐备、目标缺行(matched=false 表示该字段「目标侧缺失」);
                // 身份列为空行的代理键回落显示名展示
                missing.add(ObjectDiff(objectKeyOf(baseKey, baseRow, displayFields),
                    displayName(baseRow, displayFields), "MISSING",
                    fields.map { FieldDiff(it, baseRow[it]?.trim(), null, matched = false) }))
            }
            for ((targetKey, targetRow) in targetMap) {
                if (targetKey in pairedTargetKeys) continue
                // 整行快照:目标值齐备、基准缺行(base 全 null,matched=false 表示「基准侧缺失」);
                // 身份列为空行的代理键回落显示名展示
                extra.add(ObjectDiff(objectKeyOf(targetKey, targetRow, displayFields),
                    displayName(targetRow, displayFields), "EXTRA",
                    fields.map { FieldDiff(it, null, targetRow[it]?.trim(), matched = false) }))
            }
            val matchedCount = same.size + diff.size
            return CompareDiffResult(same, diff, missing, extra, matchedCount,
                fieldMismatchCount, comparedCells, nonNullCells, codeMatched, nameMatched, aiMatched)
        }

        /** 显示名:名称字段数组第一个非空值的 trim,无名称字段或值为空则空串(多名称字段取第一个非空,见 [nameValue]) */
        private fun displayName(row: Map<String, String?>, displayFields: List<String>): String =
            nameValue(row, displayFields) ?: ""

        /**
         * 差异明细的对象标识:身份列为空行的行内代理键不落库(界面上会显示成乱码前缀),
         * 回落用该行的显示名;真实组合键原样返回
         */
        private fun objectKeyOf(key: String, row: Map<String, String?>, displayFields: List<String>): String =
            if (isNoKeyRow(key)) displayName(row, displayFields) else key

        /** 字段值比较:先按「空」归一(NULL/空白=空),数值字段走 BigDecimal 归一,其余 trim 后字符串相等 */
        internal fun valuesEqual(field: String, baseValue: String?, targetValue: String?,
                                 numericFields: Set<String>): Boolean {
            val b = baseValue?.trim()
            val t = targetValue?.trim()
            val bEmpty = b.isNullOrEmpty()
            val tEmpty = t.isNullOrEmpty()
            if (bEmpty || tEmpty) return bEmpty && tEmpty // NULL 与空串视为一致
            if (field in numericFields) {
                val bdB = parseNumber(b!!)
                val bdT = parseNumber(t!!)
                if (bdB != null && bdT != null) return bdB.compareTo(bdT) == 0
            }
            return b == t
        }

        /** 数值解析:去千分位逗号后转 BigDecimal,失败返回 null */
        private fun parseNumber(s: String): BigDecimal? =
            try {
                BigDecimal(s.replace(",", ""))
            } catch (e: NumberFormatException) {
                null
            }
    }
}

// ---------- 对象对齐(匹配逻辑):与实例无关的纯模型与算法,放文件级便于单测与复用 ----------

/**
 * 对象对齐(匹配)逻辑(compare_job.match_mode),一任务一套,新建向导第二步由用户选择。
 * 规则来源:对比表与基准表的身份字段(code/name)常对不上,故提供判定「是否同一个对象」的口径。
 * 界面提供两种:[EXACT](编码+名称)与 [CODE_NAME_LLM](先编码后名称+大模型归一化);
 * [CODE_THEN_NAME] 为旧版选项、界面不再提供,存量任务兼容保留;
 * [LEGACY] 不是用户可选项:老任务 match_mode 为空时按「只按对象编码对齐」解读,保证历史结果口径不回归。
 */
enum class MatchMode(val value: String, val label: String) {
    /**
     * 选项 1(界面默认;界面文案「编码+名称」是历史叫法,不代表名称参与配对):
     * **只按对象编码配对**——编码相同即同一对象,名称写法不同也算命中(名称差异按字段级 DIFF 体现);
     * 编码没配上的对象不做名称/大模型补配,直接判缺失/多余
     */
    EXACT("EXACT", "编码+名称"),
    /** 旧版选项(界面不再提供,存量任务兼容保留):有编码先用编码配,编码没配上的再用对象名称配 */
    CODE_THEN_NAME("CODE_THEN_NAME", "先编码后名称"),
    /** 选项 2:先编码配、再按名称配,都没配上的残余交大模型归一化配对 */
    CODE_NAME_LLM("CODE_NAME_LLM", "先编码后名称+大模型归一化"),
    /** 老任务(数据库 match_mode 为空):只按对象编码对齐 */
    LEGACY("", "仅编码(老任务)"),
    ;

    /** 是否需要用「对象名称」字段配对(决定提交时是否强制指定该字段) */
    val requiresName: Boolean get() = this != LEGACY

    companion object {
        /** 数据库值 → 枚举;未知值/空值一律按 [LEGACY] 解读(不炸老任务) */
        fun fromValue(value: String?): MatchMode =
            entries.firstOrNull { it != LEGACY && it.value.equals(value?.trim(), ignoreCase = true) } ?: LEGACY

        /** 请求值归一:空 = [EXACT](新建默认);未知值报 [IllegalArgumentException],避免静默按别的口径跑 */
        fun normalize(raw: String?): MatchMode {
            val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return EXACT
            return entries.firstOrNull { it != LEGACY && it.value.equals(v, ignoreCase = true) }
                ?: throw IllegalArgumentException("非法匹配逻辑: $raw")
        }
    }
}

/** 一对已对齐的对象:baseKey/targetKey 为两侧行 map 的键,by 为对齐来源(CODE/NAME/LLM) */
data class MatchedPair(val baseKey: String, val targetKey: String, val by: String)

/** 对象对齐结果:pairs 之外的两侧行即缺失/多余 */
data class MatchResult(
    val pairs: List<MatchedPair>,
    val codeMatched: Int,
    val nameMatched: Int,
    val aiMatched: Int = 0,
    /** 大模型补配的说明(超限/未配置/分批失败等),仅在需要人工关注时给出 */
    val llmNote: String? = null,
    /** 大模型补配是否有失败批次(调用方据此把说明挂到目标上) */
    val llmFailed: Boolean = false,
)

/**
 * 同名消歧的字段区分度裁剪(纯函数):同名组带字段取值交模型前,剔除没有信息量的字段——
 * 1. 身份/名称字段(已以 code=/name= 单独展示,重复出现是噪音);
 * 2. 组内双侧全空的字段(取值全是 (空),零信息量);
 * 3. 组内取值完全相同的字段(无区分度);
 * 4. 取值全为 UUID 形态的字段(另一侧系统不录入,无比较意义,如 GUID);
 * 5. 序号类字段(名称命中 xh/seq/sort/order 或注释含「序号」,仅行号无业务含义);
 * 裁完为空 = 没有比字段可判,回退「剔除身份/名称后的原清单」(宁多勿丢)。
 * [rows] = 组内全部成员行(基准+目标,行 map 键为基准字段名)
 */
internal fun pickDiscriminatingFields(fields: List<String>, keyFields: List<String>,
                                      nameFields: List<String>,
                                      rows: List<Map<String, String?>>): List<String> {
    val skip = (keyFields + nameFields).mapTo(HashSet()) { it.lowercase() }
    val candidates = fields.filter { it.lowercase() !in skip }
    val uuid = Regex("[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}")
    val seqNames = setOf("xh", "seq", "sort", "order_no", "orderno", "serial", "rownum")
    val picked = candidates.filter { f ->
        if (f.lowercase() in seqNames) return@filter false
        val values = rows.map { idValue(it, f) }
        if (values.all { it == null }) return@filter false              // 全空
        if (values.distinct().size <= 1) return@filter false            // 全同值
        val nonNull = values.filterNotNull()
        if (nonNull.isNotEmpty() && nonNull.all { uuid.matches(it.lowercase()) }) return@filter false // UUID 形态
        true
    }
    return picked.ifEmpty { candidates }
}

/**
 * 佐证负证据拦截(纯函数):把「名称路配对 + 多属性独立冲突(区划冲突 + 位置/河流也冲突)」的配对
 * 从配对列表里摘除,返回新结果与摘除数——异地同名不能凭名字定案,拆回残余交大模型补配/仲裁;
 * 编码路配对不动(编码归一化相等身份已定),单属性冲突不拆(任何单一字段都可能是错的,缺失≠冲突)
 */
internal fun stripStrongConflictNamePairs(match: MatchResult,
                                          baseMap: Map<String, Map<String, String?>>,
                                          targetMap: Map<String, Map<String, String?>>,
                                          evidenceFields: Map<EvidenceKind, String>): Pair<MatchResult, Int> {
    if (evidenceFields.isEmpty()) return match to 0
    val stripped = match.pairs.filter { p ->
        p.by == "NAME" &&
            CompareEvidence.verdictsOf(baseMap.getValue(p.baseKey), targetMap.getValue(p.targetKey),
                evidenceFields).strongConflict()
    }
    if (stripped.isEmpty()) return match to 0
    val removed = stripped.toSet()
    return match.copy(pairs = match.pairs.filter { it !in removed },
        nameMatched = (match.nameMatched - stripped.size).coerceAtLeast(0)) to stripped.size
}

/**
 * 对象对齐纯函数:把基准/目标全量行按身份标识配对,产出「哪两条是同一个对象」。
 * - [MatchMode.LEGACY](老任务,数据库无值):只按对象编码([keyFields] 组合值)配对;
 * - [MatchMode.EXACT]:编码与对象名称都相等才配对;
 * - [MatchMode.CODE_THEN_NAME] / [MatchMode.CODE_NAME_LLM]:先按编码配,残余再按对象名称配
 *   ([MatchMode.CODE_NAME_LLM] 的大模型归一化补配在实例侧 [CompareService] 里继续做);
 * - 编码按 trim 后**区分大小写**比较(历史口径不变),名称按 trim 后忽略大小写比较;空值不参与配对;
 *   重复键保留先出现的行;
 * - 身份列为空的行(行 map 里是 loadRows 给的行内代理键,[CompareService.isNoKeyRow] 可识别):
 *   编码路对它无命中,留在残余里交给名称路/大模型路——即「任意一边 code 空就用 name 匹配」的口径;
 * - 名称字段缺失(未提供)时等效只按编码配对。
 * [keyFields] 为该目标的有效身份字段(见 [resolveIdentity]),组合值即行 map 键口径;
 * 单字段时与旧的单 keyField 行为完全一致。
 * 配对结果按「编码命中在前、名称命中在后」的顺序返回,便于调用方按来源计数。
 */
internal fun matchObjects(baseMap: LinkedHashMap<String, Map<String, String?>>,
                          targetMap: Map<String, Map<String, String?>>,
                          keyFields: List<String>, nameFields: List<String>, mode: MatchMode): MatchResult {
    val pairs = ArrayList<MatchedPair>(minOf(baseMap.size, targetMap.size))
    val matchedBase = HashSet<String>()
    val matchedTarget = HashSet<String>()
    // 第一路:按编码配对(老任务与规则 1 都靠它)
    appendCodePairs(baseMap, targetMap, keyFields, pairs, matchedBase, matchedTarget)
    val codeMatched = pairs.size
    // 第二路:编码没配上的残余再按对象名称配对(规则 1 要求名称也相等,不额外补配)
    var nameMatched = 0
    if (mode != MatchMode.LEGACY && mode != MatchMode.EXACT && nameFields.isNotEmpty()) {
        appendNamePairs(baseMap, targetMap, nameFields, pairs, matchedBase, matchedTarget)
        nameMatched = pairs.size - codeMatched
    }
    return MatchResult(pairs, codeMatched, nameMatched)
}

/**
 * 按编码(组合身份值)配对:两侧各身份字段都非空才参与,命中一对记一对。
 * 编码比较**区分大小写**(与历史口径一致:老实现直接拿 trim 后的编码值当行 map 的键做相等判定),
 * 保证老任务与新任务在规则 1/2 第一路上的结果完全相同;大小写/格式不统一的脏数据交给匹配逻辑 3 处理。
 * 目标侧先按组合身份值建 HashMap 索引(putIfAbsent 保留先出现行;loadRows 的行 map 键即组合身份值,
 * 重复行已塌缩,与旧逐行扫描口径一致,O(n+m) 替掉原 O(n·m) 扫描),基准侧逐行查表。
 */
private fun appendCodePairs(baseMap: Map<String, Map<String, String?>>,
                            targetMap: Map<String, Map<String, String?>>,
                            keyFields: List<String>, pairs: MutableList<MatchedPair>,
                            matchedBase: MutableSet<String>, matchedTarget: MutableSet<String>) {
    val targetByCode = HashMap<String, String>(targetMap.size)
    for ((targetKey, row) in targetMap) {
        val code = compositeKey(row, keyFields) ?: continue
        targetByCode.putIfAbsent(code, targetKey)
    }
    for ((baseKey, baseRow) in baseMap) {
        if (baseKey in matchedBase) continue
        val code = compositeKey(baseRow, keyFields) ?: continue
        val targetKey = targetByCode[code] ?: continue
        if (targetKey in matchedTarget) continue
        pairs.add(MatchedPair(baseKey, targetKey, "CODE"))
        matchedBase.add(baseKey)
        matchedTarget.add(targetKey)
    }
}

/** 按对象名称配对(忽略大小写):只处理编码路没配上的残余,名称为空的行不参与;多名称字段取第一个非空值([nameValue]) */
internal fun appendNamePairs(baseMap: Map<String, Map<String, String?>>,
                             targetMap: Map<String, Map<String, String?>>,
                             nameFields: List<String>, pairs: MutableList<MatchedPair>,
                             matchedBase: MutableSet<String>, matchedTarget: MutableSet<String>) {
    // 目标侧残余按名称建索引(重名只留先出现的一条),基准侧残余逐个查
    val targetByName = HashMap<String, String>()
    for ((targetKey, row) in targetMap) {
        if (targetKey in matchedTarget) continue
        val name = nameValue(row, nameFields) ?: continue
        targetByName.putIfAbsent(name.lowercase(), targetKey)
    }
    for ((baseKey, baseRow) in baseMap) {
        if (baseKey in matchedBase) continue
        val name = nameValue(baseRow, nameFields) ?: continue
        val targetKey = targetByName[name.lowercase()] ?: continue
        if (targetKey in matchedTarget) continue
        pairs.add(MatchedPair(baseKey, targetKey, "NAME"))
        matchedBase.add(baseKey)
        matchedTarget.add(targetKey)
    }
}

/** 身份标识取值:trim 后为空按「没有该字段值」处理(空值不参与身份匹配) */
internal fun idValue(row: Map<String, String?>, field: String): String? =
    row[field]?.trim()?.takeIf { it.isNotEmpty() }

/** 对象名称取值(V73 多名称字段):按字段顺序取第一个非空(trim 后非空)的值;全部为空返回 null */
internal fun nameValue(row: Map<String, String?>, fields: List<String>): String? =
    fields.firstNotNullOfOrNull { idValue(row, it) }

/**
 * 组合身份键:各身份字段值 trim 后以「\u0001」拼接(单字段即该字段 trim 值,与旧口径一致);
 * 任一身份字段值为空返回 null(该行不参与配对,落缺失/多余)
 */
internal fun compositeKey(row: Map<String, String?>, fields: List<String>): String? =
    fields.map { idValue(row, it) ?: return null }.joinToString("\u0001")

/**
 * 目标级有效身份字段推导(确定性纯函数,提交校验/审核/执行/导出共用同一口径,与 mapping 永不双写):
 * - [identityKeys] 非空(人工覆盖,compare_target.identity_json 的 keys):用其 keys,
 *   必须是任务级身份字段 [jobKeyFields] 的子集(忽略大小写校验,按传入原样返回);
 * - 无映射([mapping] 为空,按字段名自动匹配老路径):任务级身份字段全体;
 * - 否则取 [jobKeyFields] ∩ 映射键(该目标已连线的基准字段),保持任务级顺序——
 *   只连上一个自动认它,连上多个默认组合身份(全部相等才算同一行);
 * - 推导结果为空抛 [IllegalArgumentException](目标一个身份字段都没连,提交/审核拦下,错误点名列出)。
 */
internal fun resolveIdentity(jobKeyFields: List<String>, mapping: Map<String, String>,
                             identityKeys: List<String>?): List<String> {
    if (!identityKeys.isNullOrEmpty()) {
        val invalid = identityKeys.filter { k -> jobKeyFields.none { it.equals(k, ignoreCase = true) } }
        if (invalid.isNotEmpty()) {
            throw IllegalArgumentException("目标身份字段不在任务身份字段内: ${invalid.joinToString("、")}")
        }
        return identityKeys
    }
    if (mapping.isEmpty()) return jobKeyFields
    val derived = jobKeyFields.filter { k -> mapping.keys.any { it.equals(k, ignoreCase = true) } }
    if (derived.isEmpty()) {
        throw IllegalArgumentException(
            "目标表未连线任何身份字段(${jobKeyFields.joinToString("、")}),无法确定该目标的对象身份")
    }
    return derived
}

/** 解析 compare_target.identity_json({"keys":[...]});空/解析失败按「未人工覆盖」处理(回落推导) */
internal fun parseIdentityKeys(json: String?): List<String>? =
    json?.takeIf { it.isNotBlank() }?.let {
        try {
            identityJsonMapper.readTree(it)?.get("keys")
                ?.takeIf { k -> k.isArray }
                ?.mapNotNull { n -> n.asText(null)?.trim()?.takeIf(String::isNotEmpty) }
                ?.takeIf { k -> k.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

/** [parseIdentityKeys] 自用 mapper(文件级纯函数,不依赖实例) */
private val identityJsonMapper = jacksonObjectMapper()

/** 对象对齐结果里没配上的双侧残余(baseKey 列表, targetKey 列表) */
internal fun MatchResult.residues(baseMap: LinkedHashMap<String, Map<String, String?>>,
                                  targetMap: Map<String, Map<String, String?>>): Pair<List<String>, List<String>> {
    val matchedBase = pairs.mapTo(HashSet()) { it.baseKey }
    val matchedTarget = pairs.mapTo(HashSet()) { it.targetKey }
    return baseMap.keys.filter { it !in matchedBase } to targetMap.keys.filter { it !in matchedTarget }
}

// ---------- 匹配逻辑 3 的本地预处理(归一化精确补配 + 相似度召回 + 装批,全部零成本纯函数) ----------

/** 编码归一化:trim、lowercase、去空白——吃掉大小写/首尾空白/内嵌空格类脏数据 */
internal fun normalizeCodeForMatch(value: String): String =
    value.trim().lowercase().filter { !it.isWhitespace() }

/** 名称括号段剥离正则:(…) (…) […] 【…】 〔…〕整段去掉,「甲水库(改)」→「甲水库」 */
private val NAME_BRACKET_REGEX = Regex("[(（\\[【〔][^()（）\\[\\]【】〔〕]*[)）\\]】〕]")

/**
 * 名称归一化:trim、lowercase、去空白、全角字母数字转半角、剥括号补充说明整段——
 * 「甲水库(改)」「甲水库」「ＡＢ 水库」归一为同一名称;
 * 仅用于匹配逻辑 3 的本地补配/召回,不改变匹配逻辑 1/2 的名称相等口径
 */
internal fun normalizeNameForMatch(value: String): String {
    var s = value.trim().lowercase()
    s = s.map { c ->
        when (c) {
            in '０'..'９' -> '0' + (c - '０')
            in 'ａ'..'ｚ' -> 'a' + (c - 'ａ')
            else -> c
        }
    }.joinToString("")
    s = NAME_BRACKET_REGEX.replace(s, "")
    return s.filter { !it.isWhitespace() }
}

/** 名称归一化后的字符二元组(bigram)集合;长度 1 退化为单字集合;空名称返回空集 */
internal fun nameBigrams(normalizedName: String): Set<String> {
    if (normalizedName.isEmpty()) return emptySet()
    if (normalizedName.length == 1) return setOf(normalizedName)
    val set = HashSet<String>(normalizedName.length)
    for (i in 0 until normalizedName.length - 1) set.add(normalizedName.substring(i, i + 2))
    return set
}

/** 编码归一化后切字母段/数字段(长度≥2 才算),如 "sk-001-a" → {"sk","001"} */
private val CODE_TOKEN_REGEX = Regex("[a-z]+|[0-9]+")

internal fun codeTokens(normalizedCode: String): Set<String> =
    CODE_TOKEN_REGEX.findAll(normalizedCode).map { it.value }.filter { it.length >= 2 }.toSet()

/** 二元组 Jaccard 相似度;两侧任一空返回 0 */
internal fun bigramJaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    var inter = 0
    for (x in a) if (x in b) inter++
    return inter.toDouble() / (a.size + b.size - inter)
}

/** 包含关系分:短串被长串包含时返回 短/长(覆盖全称/简称),否则 0 */
internal fun containmentScore(a: String, b: String): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val (shorter, longer) = if (a.length <= b.length) a to b else b to a
    return if (longer.contains(shorter)) shorter.length.toDouble() / longer.length else 0.0
}

/**
 * 匹配逻辑 3 Phase 0:本地归一化精确补配(确定性、零 token):只处理传入的残余
 * (编码/名称两路之后的),编码归一化相等 → CODE,名称归一化相等 → NAME;
 * 索引保留先出现行,与 matchObjects 同口径;调用方用 [matchedBase]/[matchedTarget]
 * 收走新配对、并把两侧残余过滤成新残余
 */
internal fun appendNormalizedPairs(baseResidueKeys: List<String>, targetResidueKeys: List<String>,
                                   baseMap: Map<String, Map<String, String?>>,
                                   targetMap: Map<String, Map<String, String?>>,
                                   keyFields: List<String>, nameFields: List<String>,
                                   pairs: MutableList<MatchedPair>,
                                   matchedBase: MutableSet<String>,
                                   matchedTarget: MutableSet<String>) {
    val targetByNormCode = HashMap<String, String>()
    for (tk in targetResidueKeys) {
        val code = compositeKey(targetMap.getValue(tk), keyFields)?.let { normalizeCodeForMatch(it) } ?: continue
        targetByNormCode.putIfAbsent(code, tk)
    }
    for (bk in baseResidueKeys) {
        if (bk in matchedBase) continue
        val code = compositeKey(baseMap.getValue(bk), keyFields)?.let { normalizeCodeForMatch(it) } ?: continue
        val tk = targetByNormCode[code] ?: continue
        if (tk in matchedTarget) continue
        pairs.add(MatchedPair(bk, tk, "CODE")); matchedBase.add(bk); matchedTarget.add(tk)
    }
    if (nameFields.isEmpty()) return
    val targetByNormName = HashMap<String, String>()
    for (tk in targetResidueKeys) {
        if (tk in matchedTarget) continue
        val name = nameValue(targetMap.getValue(tk), nameFields)
            ?.let { normalizeNameForMatch(it) }?.takeIf { it.isNotEmpty() } ?: continue
        targetByNormName.putIfAbsent(name, tk)
    }
    for (bk in baseResidueKeys) {
        if (bk in matchedBase) continue
        val name = nameValue(baseMap.getValue(bk), nameFields)
            ?.let { normalizeNameForMatch(it) }?.takeIf { it.isNotEmpty() } ?: continue
        val tk = targetByNormName[name] ?: continue
        if (tk in matchedTarget) continue
        pairs.add(MatchedPair(bk, tk, "NAME")); matchedBase.add(bk); matchedTarget.add(tk)
    }
}

/** 召回候选:目标全局序号 + 综合相似度分(2×名称 Jaccard + 包含关系分 + 编码共享加分) */
internal data class ScoredCandidate(val targetSeq: Int, val score: Double)

/**
 * 匹配逻辑 3 Phase 1:相似度召回(零 token)——为每条基准残余从目标残余里召回 ≤ [k] 条候选。
 * 倒排索引 = 目标名称 bigram + 编码 token → 目标序号;每条基准查索引得候选池,池子超过
 * [scoreCap] 先按命中次数截断(防「全部同名」场景退化成全量两两比对),再按
 * 「2×名称 Jaccard + 包含关系分 + 编码共享加分」精排取 top-K(分数随候选返回,
 * 供调用方做高分自动采纳/低分过滤);
 * 与所有目标零字符交集(编码/名称 bigram、token 全不中)的基准不进返回表,由调用方如实计数披露。
 */
internal fun recallCandidates(bases: List<CompareMatchPrompts.MatchItem>,
                              targets: List<CompareMatchPrompts.MatchItem>,
                              k: Int, scoreCap: Int): Map<Int, List<ScoredCandidate>> {
    data class TInfo(val seq: Int, val normCode: String, val normName: String,
                     val bigrams: Set<String>, val tokens: Set<String>)

    val tInfos = targets.map { t ->
        val nc = t.code?.let { normalizeCodeForMatch(it) } ?: ""
        val nn = t.name?.let { normalizeNameForMatch(it) } ?: ""
        TInfo(t.seq, nc, nn, nameBigrams(nn), codeTokens(nc))
    }
    val index = HashMap<String, MutableList<Int>>()
    for ((i, ti) in tInfos.withIndex()) {
        for (g in ti.bigrams) index.getOrPut(g) { ArrayList() }.add(i)
        for (tk in ti.tokens) index.getOrPut("t:$tk") { ArrayList() }.add(i)
    }
    val result = HashMap<Int, List<ScoredCandidate>>(bases.size)
    for (b in bases) {
        val nc = b.code?.let { normalizeCodeForMatch(it) } ?: ""
        val nn = b.name?.let { normalizeNameForMatch(it) } ?: ""
        val bg = nameBigrams(nn)
        val tk = codeTokens(nc)
        if (bg.isEmpty() && tk.isEmpty()) continue // 编码+名称全无标识,无可召回
        val hits = HashMap<Int, Int>()
        for (g in bg) index[g]?.forEach { i -> hits[i] = (hits[i] ?: 0) + 1 }
        for (t in tk) index["t:$t"]?.forEach { i -> hits[i] = (hits[i] ?: 0) + 1 }
        if (hits.isEmpty()) continue
        val pool = if (hits.size > scoreCap) {
            hits.entries.sortedByDescending { it.value }.take(scoreCap).map { it.key }
        } else hits.keys.toList()
        val scored = ArrayList<ScoredCandidate>(pool.size)
        for (i in pool) {
            val ti = tInfos[i]
            val codeBonus = if (tk.isNotEmpty() && ti.tokens.any { it in tk }) 1.0 else 0.0
            scored.add(ScoredCandidate(ti.seq,
                2.0 * bigramJaccard(bg, ti.bigrams) + containmentScore(nn, ti.normName) + codeBonus))
        }
        scored.sortByDescending { it.score }
        result[b.seq] = scored.take(k)
    }
    return result
}

/**
 * 匹配逻辑 3 Phase 2 装批:按条目预算(每条基准 1 + 其候选数)贪心装批,
 * 返回每批的基准条目;无候选的基准不在任何批(调用方已如实计数披露)
 */
internal fun packCandidateBatches(baseItems: List<CompareMatchPrompts.MatchItem>,
                                  candidates: Map<Int, List<Int>>,
                                  itemBudget: Int): List<List<CompareMatchPrompts.MatchItem>> {
    val batches = ArrayList<List<CompareMatchPrompts.MatchItem>>()
    var current = ArrayList<CompareMatchPrompts.MatchItem>()
    var used = 0
    for (b in baseItems) {
        val cost = 1 + (candidates[b.seq]?.size ?: 0)
        if (current.isNotEmpty() && used + cost > itemBudget) {
            batches.add(current); current = ArrayList(); used = 0
        }
        current.add(b); used += cost
    }
    if (current.isNotEmpty()) batches.add(current)
    return batches
}

/** 同名歧义组:某名称(忽略大小写)双侧都存在、且至少一侧有 ≥2 条同名对象——只按名称首配会张冠李戴,需二轮消歧 */
internal data class SameNameGroup(val name: String, val baseKeys: List<String>, val targetKeys: List<String>)

/**
 * 识别同名歧义组(纯函数,供同名二轮消歧使用):
 * - 组内参与对象 = 该名称下「未配对、按名称(NAME)或按大模型(LLM)配对」的行——首轮大模型补配
 *   的 prompt 只带编码+名称,对同名组并不比对名称配对多知道什么,同样要进二轮;
 *   按编码(CODE)配对的行身份已定(编码相等),不重配;
 * - 只有双侧各有 ≥1 条参与对象才成组(一侧为空无歧义可消);无名称字段返回空表。
 */
internal fun sameNameAmbiguousGroups(pairs: List<MatchedPair>,
                                     baseMap: Map<String, Map<String, String?>>,
                                     targetMap: Map<String, Map<String, String?>>,
                                     nameFields: List<String>): Map<String, SameNameGroup> {
    if (nameFields.isEmpty()) return emptyMap()
    val pairByBase = pairs.associateBy { it.baseKey }
    val pairByTarget = pairs.associateBy { it.targetKey }
    fun free(pair: MatchedPair?) = pair == null || pair.by != "CODE"
    fun indexOf(m: Map<String, Map<String, String?>>): Map<String, List<String>> =
        m.entries.groupBy({ e -> nameValue(e.value, nameFields)?.lowercase() }, { e -> e.key })
            .mapNotNull { (n, ks) -> n?.let { low -> low to ks } }.toMap()
    val baseIdx = indexOf(baseMap)
    val targetIdx = indexOf(targetMap)
    val groups = LinkedHashMap<String, SameNameGroup>()
    for ((name, baseKeys) in baseIdx) {
        val targetKeys = targetIdx[name] ?: continue
        if (baseKeys.size < 2 && targetKeys.size < 2) continue
        val bFree = baseKeys.filter { free(pairByBase[it]) }
        val tFree = targetKeys.filter { free(pairByTarget[it]) }
        if (bFree.isNotEmpty() && tFree.isNotEmpty()) groups[name] = SameNameGroup(name, bFree, tFree)
    }
    return groups
}

/**
 * 应用同名二轮消歧结果(纯函数):[decisions] 为「组名 → (基准键 to 目标键)配对」,
 * 仅 [sameNameAmbiguousGroups] 给出的组与参与键生效(其余丢弃,防模型跨组乱配),同组内一对一只取先出现的。
 * 某组存在有效新配对时,该组**整体重画**:先摘除组内原 NAME 配对再落新配对——首轮名称首配
 * 在组内不再可信,未获新配对的参与对象回落缺失/多余;配对来源仍记 NAME(配对键仍是名称,二轮只消歧同名实例)。
 * decisions 为空或全部无效时返回原列表。
 */
internal fun applySameNameRefine(pairs: List<MatchedPair>, groups: Map<String, SameNameGroup>,
                                 decisions: Map<String, List<Pair<String, String>>>): List<MatchedPair> {
    if (decisions.isEmpty()) return pairs
    // 只重画「有有效新配对」的组;过滤无效的组保持首轮配对
    val redraw = HashMap<String, SameNameGroup>()
    val newPairs = ArrayList<MatchedPair>()
    for ((name, ds) in decisions) {
        val g = groups[name] ?: continue
        val seenB = HashSet<String>()
        val seenT = HashSet<String>()
        var added = 0
        for ((b, t) in ds) {
            if (b !in g.baseKeys || t !in g.targetKeys) continue
            if (!seenB.add(b) || !seenT.add(t)) continue
            newPairs.add(MatchedPair(b, t, "NAME"))
            added++
        }
        if (added > 0) redraw[name] = g
    }
    if (redraw.isEmpty()) return pairs
    // 摘除范围 = 重画组的全部参与行涉及的**非编码**配对(含首轮大模型补配:它只见过编码+名称,
    // 对同名组同样不可信;编码配对身份已定不动)——否则参与行会被新旧两条配对同时占住
    val participantsB = redraw.values.flatMapTo(HashSet()) { it.baseKeys }
    val participantsT = redraw.values.flatMapTo(HashSet()) { it.targetKeys }
    val kept = pairs.filterNot { p ->
        p.by != "CODE" && (p.baseKey in participantsB || p.targetKey in participantsT)
    }
    return kept + newPairs
}
