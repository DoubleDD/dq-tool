package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.ColumnMeta
import com.example.dq.model.CompareAiTrace
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.PendingReason
import com.example.dq.model.TestConnectionRequest
import com.example.dq.repository.CompareImportRepository
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.SystemSettingsRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.util.CryptoUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 比对任务批量导入服务测试(H2 内存库 + fake 接缝):
 * 数据源匹配三态(匹配 key 含数据库类型)、待新建命名口径(描述(库名)+重名后缀)、类别自由文本、任务名拼接、
 * confirm 两阶段时序(快速落库 MAPPING_RUNNING → 后台实测+推导 → MAPPING_REVIEW/DS_ERROR/IMPORT_ERROR)、
 * 同源实测去重、原件落盘与下载、口令不落库、
 * 未配置大模型入口拦截、confirm 用户改绑(REBOUND 直绑/不存在 id 拒绝/未知 key 忽略)
 */
class CompareImportServiceTest {

    private val config = AppConfig(dataDir = Files.createTempDirectory("compare-import-test"))
    private val dataSourceService: DataSourceService
    private val metadataService: MetadataService
    private val compareService: CompareService
    private val compareRepo: CompareRepository
    private val importRepo: CompareImportRepository
    private val dsRepo: DataSourceRepository

    /** 表字段 fake:表名 → 列清单(基准表 reservoir_base / 目标表 t_reservoir) */
    private val fakeTables = mapOf(
        "reservoir_base" to listOf("reservoir_code", "reservoir_name", "reservoir_type"),
        "t_reservoir" to listOf("v_code", "v_name", "v_type"),
    )
    private val columnsLister: (Long, String, String, String) -> List<ColumnMeta> = { _, _, _, table ->
        fakeTables[table]?.map { ColumnMeta(it, "varchar(64)", 12, false, 0, false) } ?: emptyList()
    }
    /** 大模型映射推导 fake:只推 reservoir_type,身份字段由表格锁定 */
    private val fakeChat = CompareService.AiChat { _, _, _ -> """{"reservoir_type": "v_type"}""" }
    private val fakeConfig = AiConfigService.Config("http://fake-llm", "key", "model", false)
    /** 实测 fake:默认连通;测试可改写为失败 */
    private var testError: String? = null

    init {
        val h2 = JdbcDataSource()
        h2.setURL("jdbc:h2:mem:compare-import-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(h2)
        val jdbc = Jdbc(h2)
        dsRepo = DataSourceRepository(jdbc)
        compareRepo = CompareRepository(jdbc)
        importRepo = CompareImportRepository(jdbc)
        val metaCacheRepo = MetaCacheRepository(jdbc)
        dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
            SchemaStatRepository(jdbc), metaCacheRepo)
        metadataService = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc),
            SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo)
        compareService = CompareService(compareRepo, dataSourceService, DialectFactory, metadataService,
            SystemSettingsService(SystemSettingsRepository(jdbc), config), TableSystemRepository(jdbc),
            columnsLister = columnsLister)
    }

    private fun newService(
        aiConfigured: Boolean = true,
        chat: CompareService.AiChat = fakeChat,
        tester: (TestConnectionRequest) -> String? = { testError },
        traceRecorder: (CompareAiTrace) -> Unit = {},
    ) = CompareImportService(
        importRepo, dsRepo, dataSourceService, metadataService,
        compareService, null, config,
        aiConfigProvider = { if (aiConfigured) fakeConfig else null },
        columnsLister = columnsLister,
        aiMappingChat = chat,
        dsTester = tester,
        aiTraceRecorder = traceRecorder)

    // ---------- Excel 构造 ----------

    private val headers = listOf(
        "所属水利对象类别名称", "实际系统或模式描述", "数据库类型", "IP地址", "端口",
        "用户名", "口令", "数据库名称", "模式名称", "表中文名称", "表英文名称",
        "是否基准表", "对象编码字段", "对象名称字段", "是否纳入采集范围")

    private fun xlsx(sheetName: String, rows: List<List<String>>): ByteArray = xlsx(sheetName to rows)

    private fun xlsx(vararg sheets: Pair<String, List<List<String>>>): ByteArray {
        val out = ByteArrayOutputStream()
        XSSFWorkbook().use { wb ->
            for ((sheetName, rows) in sheets) {
                val sheet = wb.createSheet(sheetName)
                val head = sheet.createRow(0)
                headers.forEachIndexed { i, h -> head.createCell(i).setCellValue(h) }
                rows.forEachIndexed { r, values ->
                    val row = sheet.createRow(r + 1)
                    values.forEachIndexed { i, v -> if (v.isNotEmpty()) row.createCell(i).setCellValue(v) }
                }
            }
            wb.write(out)
        }
        return out.toByteArray()
    }

    private fun baseRow(host: String = "10.0.0.1", category: String = "供(取)水量监测点") = listOf(
        category, "基准系统-汇集库", "mysql", host, "3306", "root", "pw1", "base_db", "",
        "取用水监测点基本信息表", "reservoir_base", "是", "reservoir_code", "reservoir_name", "是")

    private fun targetRow(host: String, db: String = "vendor_db", code: String = "", name: String = "",
                          user: String = "root", pw: String = "pw2") = listOf(
        "", "厂商系统-应用库", "mysql", host, "3306", user, pw, db, "",
        "取用水监测点基本信息表", "t_reservoir", "否", code, name, "是")

    private fun awaitBatchDone(id: Long) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val s = importRepo.findById(id)!!.status
            if (s == "DONE" || s == "FAILED") return
            Thread.sleep(100)
        }
        throw AssertionError("批次超时未完成")
    }

    /** 等后台映射推导结束(reason 离开 MAPPING_RUNNING;照 [awaitBatchDone] 轮询口径) */
    private fun awaitMappingSettled(jobId: Long): com.example.dq.repository.CompareRepository.JobRow {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val j = compareRepo.getJob(jobId)!!
            if (j.pendingReason != PendingReason.MAPPING_RUNNING.value) return j
            Thread.sleep(50)
        }
        throw AssertionError("任务映射推导超时未结束")
    }

    // ---------- 用例 ----------

    @Test
    fun `未配置大模型 submit 直接拦截 不落盘不落库`() {
        val service = newService(aiConfigured = false)
        val e = assertThrows(IllegalStateException::class.java) {
            service.submit("水库台账.xlsx", ByteArrayInputStream(xlsx("水库信息", listOf(baseRow(), targetRow("10.0.0.2")))))
        }
        assertTrue(e.message!!.contains("AI 配置"))
        assertFalse(Files.exists(config.dataDir.resolve("compare-imports")))
    }

    @Test
    fun `全部 sheet 无效时错误信息带出行级原因 不只报缺少基准行`() {
        val service = newService()
        // 基准行漏填「数据库名称」(库名被填进「模式名称」)、对比行同样缺 → sheet 整体跳过
        val bad = xlsx("示例任务", listOf(
            baseRow().toMutableList().also { it[7] = ""; it[8] = "hyd_ln" },
            targetRow("10.0.0.2").toMutableList().also { it[7] = "" }))
        val e = assertThrows(IllegalArgumentException::class.java) {
            service.submit("批量导入测试001.xlsx", ByteArrayInputStream(bad))
        }
        val msg = e.message!!
        assertTrue(msg.contains("示例任务"), msg)
        assertTrue(msg.contains("基准行无效") && msg.contains("数据库名称"), msg)
        assertTrue(msg.contains("另跳过"), msg) // 行级明细一并抛出
    }

    @Test
    fun `submit 数据源匹配三态 原件落盘 口令不落库`() {
        // 已建档数据源(地址+端口+库与基准行一致)
        val existingId = dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val service = newService()
        val batch = service.submit("水库台账.xlsx", ByteArrayInputStream(xlsx("水库信息", listOf(
            baseRow(),
            targetRow("10.0.0.2"),                          // 待新建(有口令)
            targetRow("10.0.0.3", user = "", pw = ""),      // 异常(无口令)
        ))))
        assertEquals("DS_REVIEW", batch.status)
        assertEquals(1, batch.taskCount)
        assertEquals(3, batch.dsReport.size)
        val byHost = batch.dsReport.associateBy { it["host"] as String }
        assertEquals("MATCHED", byHost["10.0.0.1"]!!["action"])
        assertEquals(existingId, (byHost["10.0.0.1"]!!["datasourceId"] as Int).toLong())
        assertEquals("CREATE", byHost["10.0.0.2"]!!["action"])
        assertEquals("ERROR", byHost["10.0.0.3"]!!["action"])
        assertTrue((byHost["10.0.0.3"]!!["error"] as String).contains("用户名/口令"))
        // 按表格「实际系统或模式描述」推算名与已建档名不一致 → 以地址为准并提示
        assertTrue((byHost["10.0.0.1"]!!["error"] as String).contains("以地址匹配为准"))
        // 口令不落库:报告 JSON 不含任何口令值
        val reportJson = importRepo.findById(batch.id)!!.dsReport!!
        assertFalse(reportJson.contains("pw1") || reportJson.contains("pw2"), reportJson)
        // 原件落盘:compare-imports/batch-<id>/<原文件名>
        val file = config.dataDir.resolve("compare-imports").resolve("batch-${batch.id}").resolve("水库台账.xlsx")
        assertTrue(Files.exists(file), file.toString())
        assertEquals(Files.size(file), importRepo.findById(batch.id)!!.fileSize)
    }

    @Test
    fun `confirm 快速返回 任务落 MAPPING_RUNNING 大模型阻塞也不拖住批次`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        // 大模型 fake 阻塞:验证批次 DONE 不等推导,推导完再翻 MAPPING_REVIEW
        val chatEntered = CountDownLatch(1)
        val chatRelease = CountDownLatch(1)
        val blockingChat = CompareService.AiChat { _, _, _ ->
            chatEntered.countDown()
            assertTrue(chatRelease.await(30, TimeUnit.SECONDS), "测试未放行大模型调用")
            """{"reservoir_type": "v_type"}"""
        }
        val service = newService(chat = blockingChat)
        val batch = service.submit("水库台账.xlsx", ByteArrayInputStream(xlsx("水库信息", listOf(
            baseRow(), targetRow("10.0.0.2", code = "v_code", name = "v_name")))))
        try {
            service.confirm(batch.id)
            awaitBatchDone(batch.id) // 大模型被阻塞,批次仍快速 DONE(旧同步口径下这里会超时)
            val jobId = service.get(batch.id).jobIds.single()
            assertEquals(PendingReason.MAPPING_RUNNING.value, compareRepo.getJob(jobId)!!.pendingReason)
            // 后台推导已启动且未完成期间任务保持 MAPPING_RUNNING
            assertTrue(chatEntered.await(30, TimeUnit.SECONDS), "后台映射推导未启动")
            assertEquals(PendingReason.MAPPING_RUNNING.value, compareRepo.getJob(jobId)!!.pendingReason)
        } finally {
            chatRelease.countDown()
        }
        val jobId = service.get(batch.id).jobIds.single()
        assertEquals(PendingReason.MAPPING_REVIEW.value, awaitMappingSettled(jobId).pendingReason)
    }

    @Test
    fun `confirm 全顺 后台推导后转 MAPPING_REVIEW 映射锁定与推导合并`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val service = newService()
        val batch = service.submit("水库台账.xlsx", ByteArrayInputStream(xlsx("水库信息", listOf(
            baseRow(), targetRow("10.0.0.2", code = "v_code", name = "v_name")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        val done = importRepo.findById(batch.id)!!
        assertEquals("DONE", done.status, done.error)

        val jobId = service.get(batch.id).jobIds.single()
        awaitMappingSettled(jobId)
        val detail = compareService.detail(jobId)
        assertEquals("水库台账-水库信息", detail.job.name)
        assertEquals("PENDING", detail.job.status)
        assertEquals(PendingReason.MAPPING_REVIEW.value, detail.job.pendingReason)
        assertEquals("供(取)水量监测点", detail.job.objectCategory) // 类别自由文本原样落库
        assertEquals(batch.id, detail.job.importId)
        assertEquals("水库台账.xlsx", detail.job.importFileName)
        assertEquals("CODE_NAME_LLM", detail.job.matchMode)
        assertEquals("COLUMN", detail.job.compareMode)
        // 固定口径:基准表全部字段为比对字段,主键/显示字段取自基准行(后台基准校验后归一为实际列名)
        assertEquals(listOf("reservoir_code", "reservoir_name", "reservoir_type"), detail.job.fields)
        assertEquals("reservoir_code", detail.job.keyField)
        assertEquals("reservoir_name", detail.job.displayField)
        // 映射 = 大模型推导(reservoir_type) + 锁定(reservoir_code/reservoir_name 用表格值)
        val target = detail.targets.single()
        assertEquals(mapOf("reservoir_type" to "v_type",
            "reservoir_code" to "v_code", "reservoir_name" to "v_name"), target.mapping)
        // 新建的数据源:命名「实际系统或模式描述(数据库名称)」,分组「比对批量导入」,后台实测连通
        val created = dsRepo.findById(target.datasourceId)!!
        assertEquals("厂商系统-应用库(vendor_db)", created.name)
        assertEquals("比对批量导入", created.groupName)
        assertEquals("OK", created.connStatus)
    }

    @Test
    fun `confirm 后台实测失败 数据源转异常 任务转 DS_ERROR 且 start 被拦`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        testError = "连接拒绝"
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        assertEquals("DONE", importRepo.findById(batch.id)!!.status)

        val jobId = service.get(batch.id).jobIds.single()
        // 实测在后台:批次 DONE 时任务处于推导中,实测失败后转 DS_ERROR
        val job = awaitMappingSettled(jobId)
        assertEquals("PENDING", job.status)
        assertEquals(PendingReason.DS_ERROR.value, job.pendingReason)
        assertTrue(job.error!!.contains("连接拒绝"), job.error)
        // 异常数据源也建档(ERROR 状态,便于修复后复活)
        val target = compareService.detail(jobId).targets.single()
        assertEquals("ERROR", dsRepo.findById(target.datasourceId)!!.connStatus)
        // DS_ERROR 不允许直接 start
        assertThrows(IllegalStateException::class.java) { compareService.start(jobId) }
    }

    @Test
    fun `confirm 无口令异常数据源 任务直接 DS_ERROR 不进推导队列`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val chatCalls = AtomicInteger()
        val service = newService(chat = CompareService.AiChat { _, _, _ ->
            chatCalls.incrementAndGet(); "{}"
        })
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.3", user = "", pw = "")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        // 无口令数据源同步标 ERROR,任务直接 DS_ERROR(不经过 MAPPING_RUNNING)
        val jobId = service.get(batch.id).jobIds.single()
        assertEquals(PendingReason.DS_ERROR.value, compareRepo.getJob(jobId)!!.pendingReason)
        val dsId = compareService.detail(jobId).targets.single().datasourceId
        assertEquals("ERROR", dsRepo.findById(dsId)!!.connStatus)
        assertEquals(0, chatCalls.get()) // 推导队列没接这个任务
    }

    @Test
    fun `confirm 同数据源被多任务引用 后台实测去重只测一次`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val testCalls = AtomicInteger()
        val service = newService(tester = { testCalls.incrementAndGet(); null })
        // 两个 sheet 引用同一个待新建目标数据源(地址+端口+库相同 → 只建一档)
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx(
            "s1" to listOf(baseRow(), targetRow("10.0.0.2", code = "v_code", name = "v_name")),
            "s2" to listOf(baseRow(), targetRow("10.0.0.2", code = "v_code", name = "v_name")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        val jobIds = service.get(batch.id).jobIds
        assertEquals(2, jobIds.size)
        for (jobId in jobIds) {
            assertEquals(PendingReason.MAPPING_REVIEW.value, awaitMappingSettled(jobId).pendingReason)
        }
        // 两个任务共享一个待实测数据源:实测去重,只调一次
        assertEquals(1, testCalls.get())
    }

    @Test
    fun `基准表不存在 后台校验后任务转 IMPORT_ERROR`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow().toMutableList().apply { set(10, "no_such_table") }, // 基准表名指向不存在的表
            targetRow("10.0.0.2")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        // 基准表校验在后台推导阶段做(phase 1 不连业务库)
        val job = awaitMappingSettled(service.get(batch.id).jobIds.single())
        assertEquals("PENDING", job.status)
        assertEquals(PendingReason.IMPORT_ERROR.value, job.pendingReason)
        assertTrue(job.error!!.contains("基准表"), job.error)
    }

    @Test
    fun `所属水利对象类别名称 自由文本原样落库 空则留空`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val service = newService()
        // 不在任何内置列表里的自定义类别也原样落库(不再校验、不落「其他」、不记提示)
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(category = "火电站"), targetRow("10.0.0.2")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        val view = service.get(batch.id)
        assertEquals("火电站", compareService.detail(view.jobIds.single()).job.objectCategory)
        assertTrue(view.dsReport.none { it["action"] == "NOTE" }, view.dsReport.toString())
        // 类别留空 → object_category 为 null
        val batch2 = service.submit("台账2.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(category = ""), targetRow("10.0.0.2")))))
        service.confirm(batch2.id)
        awaitBatchDone(batch2.id)
        assertNull(compareService.detail(service.get(batch2.id).jobIds.single()).job.objectCategory)
    }

    @Test
    fun `数据源匹配 key 含数据库类型 同地址异类型不匹配`() {
        // 已建档 PostgreSQL 数据源:地址+端口+库与基准行一致,但类型不同 → 不按旧口径误匹配
        dataSourceService.create(DataSourceRequest(
            "既有PG库", "jdbc:postgresql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2")))))
        val byHost = batch.dsReport.associateBy { it["host"] as String }
        assertEquals("CREATE", byHost["10.0.0.1"]!!["action"]) // mysql 行不匹配 postgresql 档
        assertEquals("CREATE", byHost["10.0.0.2"]!!["action"])
    }

    @Test
    fun `待新建数据源命名 描述加库名 重名加序号后缀`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        // 预先占用目标行按口径要取的名字(地址无关)
        dataSourceService.create(DataSourceRequest(
            "厂商系统-应用库(vendor_db)", "jdbc:mysql://10.9.9.9:3306/other_db", "root", "pw9", null, null))
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        val row = service.get(batch.id).dsReport.single { it["host"] == "10.0.0.2" }
        assertEquals("CREATED", row["action"])
        assertEquals("厂商系统-应用库(vendor_db) (2)", row["name"])
    }

    @Test
    fun `confirm 非 DS_REVIEW 批次拒绝`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        assertThrows(IllegalStateException::class.java) { service.confirm(batch.id) }
    }

    // ---------- confirm 用户改绑(overrides:行 key → 数据源 id,null = 待新建) ----------

    @Test
    fun `confirm 改绑到现有数据源 直接绑定不实测不建档`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        // 与表格地址不沾边的既有数据源,作为改绑目标
        val reboundId = dataSourceService.create(DataSourceRequest(
            "手工台账库", "jdbc:mysql://10.9.9.9:3306/manual_db", "root", "pw9", null, null))
        // 若改绑行误走建档实测必然失败,以此证明改绑行既不实测也不建档
        testError = "连接拒绝"
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2", code = "v_code", name = "v_name")))))
        val targetKey = batch.dsReport.single { it["host"] == "10.0.0.2" }["key"] as String
        assertEquals("CREATE", batch.dsReport.single { it["key"] == targetKey }["action"])
        service.confirm(batch.id, mapOf(targetKey to reboundId))
        awaitBatchDone(batch.id)
        val done = service.get(batch.id)
        assertEquals("DONE", done.status, importRepo.findById(batch.id)!!.error)
        // 报告记 REBOUND 且直接绑定;未新建数据源(仍只有 2 个),改绑目标连接状态未被实测改写
        val row = done.dsReport.single { it["key"] == targetKey }
        assertEquals("REBOUND", row["action"])
        assertEquals(reboundId, (row["datasourceId"] as Int).toLong())
        assertEquals(2, dsRepo.findAll().size)
        assertNull(dsRepo.findById(reboundId)!!.connStatus)
        // 任务经后台推导后转 MAPPING_REVIEW(改绑行未实测未建档),目标指向改绑数据源
        val detail = compareService.detail(done.jobIds.single())
        assertEquals(PendingReason.MAPPING_REVIEW.value, awaitMappingSettled(detail.job.id).pendingReason)
        assertEquals(reboundId, detail.targets.single().datasourceId)
    }

    @Test
    fun `confirm ERROR 行改绑现有数据源 任务不再 DS_ERROR`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val reboundId = dataSourceService.create(DataSourceRequest(
            "手工台账库", "jdbc:mysql://10.9.9.9:3306/manual_db", "root", "pw9", null, null))
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.3", code = "v_code", name = "v_name", user = "", pw = "")))))
        val errKey = batch.dsReport.single { it["host"] == "10.0.0.3" }["key"] as String
        assertEquals("ERROR", batch.dsReport.single { it["key"] == errKey }["action"])
        service.confirm(batch.id, mapOf(errKey to reboundId))
        awaitBatchDone(batch.id)
        val done = service.get(batch.id)
        assertEquals("DONE", done.status, importRepo.findById(batch.id)!!.error)
        // 改绑后不再异常:映射后台推导照常,任务转 MAPPING_REVIEW;未新建数据源
        val detail = compareService.detail(done.jobIds.single())
        assertEquals(PendingReason.MAPPING_REVIEW.value, awaitMappingSettled(detail.job.id).pendingReason)
        assertEquals(reboundId, detail.targets.single().datasourceId)
        assertEquals(2, dsRepo.findAll().size)
    }

    @Test
    fun `confirm 改绑不存在的数据源 id 拒绝 不消费待确认状态`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2")))))
        val targetKey = batch.dsReport.single { it["host"] == "10.0.0.2" }["key"] as String
        val e = assertThrows(IllegalArgumentException::class.java) {
            service.confirm(batch.id, mapOf(targetKey to 999999L))
        }
        assertTrue(e.message!!.contains("999999"), e.message)
        // 校验失败不消费 DS_REVIEW 状态,修正后可重新确认
        assertEquals("DS_REVIEW", importRepo.findById(batch.id)!!.status)
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        assertEquals("DONE", importRepo.findById(batch.id)!!.status)
    }

    @Test
    fun `confirm 映射 key 不在报告里 忽略按原匹配结果`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val otherId = dataSourceService.create(DataSourceRequest(
            "无关库", "jdbc:mysql://10.8.8.8:3306/other_db", "root", "pw8", null, null))
        val service = newService()
        val batch = service.submit("台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2", code = "v_code", name = "v_name")))))
        // 报告里不存在的行 key:忽略,目标行仍按原匹配结果(待新建)实测建档
        service.confirm(batch.id, mapOf("no-such-key" to otherId))
        awaitBatchDone(batch.id)
        val done = service.get(batch.id)
        assertEquals("DONE", done.status, importRepo.findById(batch.id)!!.error)
        val target = compareService.detail(done.jobIds.single()).targets.single()
        assertTrue(target.datasourceId != otherId)
        assertEquals("比对批量导入", dsRepo.findById(target.datasourceId)!!.groupName)
    }

    @Test
    fun `原件下载 文件名原样`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val service = newService()
        val batch = service.submit("水库台账.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2")))))
        val file = service.downloadFile(batch.id)
        assertEquals("水库台账.xlsx", file.fileName)
        assertTrue(Files.exists(file.path))
        // 路径注入清洗:分隔符被替换,不逃出批次目录
        val batch2 = service.submit("../evil.xlsx", ByteArrayInputStream(xlsx("s1", listOf(
            baseRow(), targetRow("10.0.0.2")))))
        assertEquals(".._evil.xlsx", service.downloadFile(batch2.id).fileName)
    }

    @Test
    fun `任务名拼接 文件名加sheet名 空与非法回退 超长截断`() {
        assertEquals("水库台账-水库信息", CompareImportService.taskName("水库台账.xlsx", "水库信息", 0))
        assertEquals("水库台账-sheet2", CompareImportService.taskName("水库台账.xlsx", "  ", 1))
        assertEquals("水库台账-sheet1", CompareImportService.taskName("水库台账.xlsx", "a/b", 0))
        assertEquals("无扩展名-表1", CompareImportService.taskName("无扩展名", "表1", 0))
        val long = CompareImportService.taskName("文".repeat(300) + ".xlsx", "sheet", 0)
        assertEquals(200, long.length)
        assertEquals("compare-import.xlsx", CompareImportService.sanitizeFileName(".."))
        assertEquals("a_b.xlsx", CompareImportService.sanitizeFileName("a/b.xlsx"))
        assertEquals("a_b.xlsx", CompareImportService.sanitizeFileName("a\\b.xlsx"))
    }

    @Test
    fun `recoverUnfinished 只清残留 BUILDING`() {
        val id = importRepo.insert("a.xlsx")
        importRepo.updateFileInfo(id, "/tmp/a.xlsx", 1)
        // DS_REVIEW 不受影响;BUILDING 清掉
        val reviewId = importRepo.insert("b.xlsx")
        importRepo.markDsReview(reviewId, "[]", 1)
        newService().recoverUnfinished()
        assertEquals("FAILED", importRepo.findById(id)!!.status)
        assertEquals("DS_REVIEW", importRepo.findById(reviewId)!!.status)
        assertNotNull(importRepo.findById(id)!!.error)
    }

    // ---------- AI 判定留痕(映射推导) ----------

    @Test
    fun `映射推导成功落 MAPPING 留痕 含推导映射与锁定项`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val traces = java.util.Collections.synchronizedList(ArrayList<CompareAiTrace>())
        val service = newService(traceRecorder = { traces.add(it) })
        val batch = service.submit("水库台账.xlsx", ByteArrayInputStream(xlsx("水库信息", listOf(
            baseRow(), targetRow("10.0.0.2", code = "v_code", name = "v_name")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        val jobId = service.get(batch.id).jobIds.single()
        awaitMappingSettled(jobId)
        val targetId = compareService.detail(jobId).targets.single().id
        val trace = traces.single { it.stage == CompareAiTrace.STAGE_MAPPING }
        assertEquals(jobId, trace.jobId)
        assertEquals(targetId, trace.targetId)
        assertEquals("COMPARE_MAPPING", trace.scene)
        assertEquals(1, trace.batchNo)
        assertEquals("model", trace.model)
        assertEquals("vendor_db.t_reservoir", trace.targetLabel)
        assertTrue(trace.requestContent!!.contains("[system]") && trace.requestContent!!.contains("[user]"))
        assertTrue(trace.responseContent!!.contains("v_type"))
        // result_json:推导映射(锁定优先合并后)+ 锁定项分开落
        val result = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .readValue(trace.resultJson, Map::class.java)
        assertEquals(mapOf("reservoir_type" to "v_type",
            "reservoir_code" to "v_code", "reservoir_name" to "v_name"), result["mapping"])
        assertEquals(mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name"), result["locked"])
    }

    @Test
    fun `映射推导调用失败也落留痕 仅锁定项加错误摘要`() {
        dataSourceService.create(DataSourceRequest(
            "既有基准库", "jdbc:mysql://10.0.0.1:3306/base_db", "root", "pw1", null, null))
        val traces = java.util.Collections.synchronizedList(ArrayList<CompareAiTrace>())
        val failingChat = CompareService.AiChat { _, _, _ -> throw IllegalStateException("模型超时") }
        val service = newService(chat = failingChat, traceRecorder = { traces.add(it) })
        val batch = service.submit("水库台账.xlsx", ByteArrayInputStream(xlsx("水库信息", listOf(
            baseRow(), targetRow("10.0.0.2", code = "v_code", name = "v_name")))))
        service.confirm(batch.id)
        awaitBatchDone(batch.id)
        val jobId = service.get(batch.id).jobIds.single()
        awaitMappingSettled(jobId)
        val trace = traces.single { it.stage == CompareAiTrace.STAGE_MAPPING }
        assertEquals(jobId, trace.jobId)
        assertTrue(trace.responseContent!!.contains("模型超时"), trace.responseContent)
        // 失败也带结构:映射为空、仅保留表格锁定项(人工审核补线的依据)
        val result = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .readValue(trace.resultJson, Map::class.java)
        assertEquals(emptyMap<String, String>(), result["mapping"])
        assertEquals(mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name"), result["locked"])
    }
}
