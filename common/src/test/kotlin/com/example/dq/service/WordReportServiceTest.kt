package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.AiConfigRequest
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanStatus
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Word 报告导出:H2 造扫描快照 → 模板渲染 → POI 读回断言。
 * 覆盖:封面/一~四章渲染、未全表扫描拦截、历史 DONE 快照回落、体积实时补算降级、
 * 按标记分节、LLM 注入与占位;同时守护模板标签完整性(标签被改坏时渲染残留 {{ 或行数对不上)。
 */
class WordReportServiceTest {

    private lateinit var scanRepo: ScanRepository
    private lateinit var schemaStatRepo: SchemaStatRepository
    private lateinit var tagRepo: TagRepository
    private lateinit var metadataService: MetadataService
    private lateinit var dataSourceService: DataSourceService
    private lateinit var tableDocRepo: TableDocRepository
    private lateinit var aiConfigService: AiConfigService

    /** 默认注入固定文本的 fake LLM;占位分支的用例注入抛异常的 chat */
    private var chat: (AiConfigService.Config, String, String) -> String = { _, _, _ -> "模拟分析文字" }
    private var chatCalls = 0

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:word-report-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        val dsRepo = DataSourceRepository(jdbc)
        scanRepo = ScanRepository(jdbc)
        schemaStatRepo = SchemaStatRepository(jdbc)
        val schemaDocRepo = SchemaDocRepository(jdbc)
        tagRepo = TagRepository(jdbc)
        tableDocRepo = TableDocRepository(jdbc)
        val config = AppConfig(dataDir = Files.createTempDirectory("word-report-test"))
        val crypto = CryptoUtil(config)
        dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config, schemaStatRepo)
        metadataService = MetadataService(dataSourceService, DialectFactory, scanRepo, schemaStatRepo, schemaDocRepo)
        chatCalls = 0
        val countingChat: (AiConfigService.Config, String, String) -> String = { c, s, u ->
            chatCalls++
            chat(c, s, u)
        }
        aiConfigService = AiConfigService(AiConfigRepository(jdbc), crypto, config)
        service = WordReportService(dataSourceService, metadataService, scanRepo, schemaDocRepo, DialectFactory,
            tagRepo, tableDocRepo, aiConfigService, AiService(), countingChat)
    }

    private lateinit var service: WordReportService

    /** 写入一份假 AI 配置,让 findConfig() 可用、分析段落走注入的 fake LLM */
    private fun enableFakeAi() {
        aiConfigService.save(AiConfigRequest("http://localhost:1/v1", "test-key", "test-model"))
    }

    private fun createDataSource(name: String): Long =
        dataSourceService.create(DataSourceRequest(name, "jdbc:mysql://localhost:1/db", "root", "p", null, null))

    /** 造一个 DONE 任务:columns 为 表名 -> 字段定义 */
    private fun seedDoneJob(datasourceId: Long, schema: String,
                            tables: List<TableSeed>,
                            columns: Map<String, List<ColSeed>>) {
        val jobId = scanRepo.insertJob(datasourceId, null, schema, false, null, tables.size)
        for (t in tables) {
            val stId = scanRepo.insertScanTable(jobId, t.name, null, t.sizeBytes, t.comment, null)
            scanRepo.finishTable(stId, ScanStatus.DONE, t.totalRows, null)
            for (c in columns[t.name].orEmpty()) {
                scanRepo.insertScanColumn(stId,
                    ScanColumnView.of("c", c.type, c.comment, null, null, null, c.total, c.nulls, 0, 0))
            }
        }
        scanRepo.finishJob(jobId, ScanStatus.DONE, null)
    }

    /** 表种子:(表名, 总行数, 大小字节, 注释) */
    private data class TableSeed(val name: String, val totalRows: Long, val sizeBytes: Long, val comment: String? = null)

    /** 字段种子:(类型, 注释, 总行数, NULL 数) */
    private data class ColSeed(val type: String, val comment: String?, val total: Long, val nulls: Long)

    /** 预置 schema_stat 缓存,避免 listSchemaStats 首次访问回连业务库 */
    private fun seedSchemaStat(datasourceId: Long, vararg schemas: String, tableCount: Int = 1) {
        schemaStatRepo.replaceAll(datasourceId, null,
            schemas.map { SchemaStatRepository.CachedStat(it, tableCount, 1024L) })
    }

    private fun render(id: Long, schemas: List<String>? = null): XWPFDocument {
        val out = ByteArrayOutputStream()
        service.export(id, null, schemas, out)
        return XWPFDocument(ByteArrayInputStream(out.toByteArray()))
    }

    private fun paragraphs(doc: XWPFDocument): String = doc.paragraphs.joinToString("\n") { it.text }

    /** 按表头首行定位正文中的表格 */
    private fun tableByHeader(doc: XWPFDocument, vararg header: String): XWPFTable =
        doc.tables.first { t -> t.rows[0].tableCells.take(header.size).map { it.text.trim() } == header.toList() }

    private fun rowTexts(t: XWPFTable, r: Int): List<String> = t.rows[r].tableCells.map { it.text.trim() }

    @Test
    fun `封面与第一章渲染,数值正确且无标签残留`() {
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a", "db_b")
        // db_a:2 表(t2 为空表)3 字段(c2/c3 为空字段),100 行,1 MB,有值数 90
        seedDoneJob(id, "db_a",
            listOf(TableSeed("t1", 100L, 1024L * 1024), TableSeed("t2", 0L, 0L)),
            mapOf("t1" to listOf(ColSeed("int", "主键", 100, 10), ColSeed("varchar(64)", null, 100, 100)),
                "t2" to listOf(ColSeed("int", null, 0, 0))))
        // db_b:1 表 1 字段,50 行,3 MB,有值数 50
        seedDoneJob(id, "db_b",
            listOf(TableSeed("t3", 50L, 3L * 1024 * 1024)),
            mapOf("t3" to listOf(ColSeed("int", null, 50, 0))))
        metadataService.updateSchemaDescription(id, null, "db_a", "地下水监测库")

        render(id).use { doc ->
            val text = paragraphs(doc)
            // 封面
            assertTrue(text.contains("测试源数据调研报告"), text)
            assertTrue(text.contains("数据库类型:MySQL"), text)
            // 1.1 总体情况
            assertTrue(text.contains(
                "测试源数据源共计2个数据库实例,累计数据表总数3张,其中空表总数1张,空表总率33.33%," +
                    "累计字段4个,其中空字段2个,字段有值总率93.33%,累计数据总行数150行,累计数据总存储量约4.0 MB。"),
                "1.1 段落不符合预期,实际:\n" + text)

            // 1.2 数据库实例一览:表头 + 2 数据行 + 合计行
            val t12 = tableByHeader(doc, "序号", "数据库实例", "实例描述")
            assertEquals(4, t12.rows.size, "表头 + 2 库 + 合计")
            assertEquals(listOf("1", "db_a", "地下水监测库", "2", "3", "100", "1.0 MB"), rowTexts(t12, 1))
            assertEquals(listOf("2", "db_b", "", "1", "1", "50", "3.0 MB"), rowTexts(t12, 2))
            assertEquals(listOf("3", "4", "150", "4.0 MB"), rowTexts(t12, 3).takeLast(4))

            // 全文(含表格)无 poi-tl 标签残留
            val allText = text + doc.tables.joinToString("\n") { t ->
                t.rows.joinToString("\n") { r -> r.tableCells.joinToString("|") { it.text } }
            }
            assertTrue(!allText.contains("{{") && !allText.contains("[index]"), "存在未渲染的模板标签")
        }
    }

    @Test
    fun `第二章渲染,质量概况与五张分组表数值正确`() {
        enableFakeAi()
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a", tableCount = 4)
        seedDoneJob(id, "db_a", listOf(
            TableSeed("t_big", 2_000_000L, 2L * 1024 * 1024 * 1024, "大表注释"), // 大表(行数与体积双超阈值)
            TableSeed("t_empty", 0L, 0L),                                        // 空表
            TableSeed("orders_bak", 10L, 1024L),                                 // 备份表
            TableSeed("t_20250604", 5L, 1024L),                                  // 分表(日期后缀)
        ), mapOf(
            "t_big" to listOf(ColSeed("bigint", "主键", 2_000_000, 0), ColSeed("varchar(64)", null, 2_000_000, 2_000_000)),
            "t_empty" to listOf(ColSeed("datetime", null, 0, 0)),
            "orders_bak" to listOf(ColSeed("decimal(10,2)", null, 10, 5)),
            "t_20250604" to listOf(ColSeed("geometry", null, 5, 2)),
        ))

        render(id).use { doc ->
            // 2.1 整体数据质量概况:空表 1/4=25%,空字段 2/5=40%,平均有值率=(100+0+0+50+60)/5=42%
            val t21 = tableByHeader(doc, "数据库实例", "表数", "空表数")
            assertEquals(3, t21.rows.size, "表头 + 1 库 + 合计/平均")
            assertEquals(listOf("db_a", "4", "1", "25.00%", "5", "2", "40.00%", "42.00%"), rowTexts(t21, 1))
            assertEquals("42.00%", rowTexts(t21, 2).last())

            // 2.3 字段填充率分布:每库 4 行分桶,[2,0,2,1](0%、0%、50%、60%、100%)
            val t23 = tableByHeader(doc, "数据库实例", "有值率区间", "字段数", "占比")
            assertEquals(5, t23.rows.size)
            assertEquals(listOf("db_a", "＜10%(低填充)", "2", "40.00%"), rowTexts(t23, 1))
            assertEquals(listOf("", "10%-50%(中低填充)", "0", "0.00%"), rowTexts(t23, 2))
            assertEquals(listOf("", "50%-80%(中等填充)", "2", "40.00%"), rowTexts(t23, 3))
            assertEquals(listOf("", "≥80%(高填充)", "1", "20.00%"), rowTexts(t23, 4))
            // 首列纵向合并结构:组首行 restart,组内其余行 continue(防止 LoopRow 式串组回归)
            val tcPr1 = t23.rows[1].getCell(0).ctTc.tcPr
            val tcPr2 = t23.rows[2].getCell(0).ctTc.tcPr
            assertTrue(tcPr1?.vMerge != null && "restart" == tcPr1.vMerge.`val`.toString(), "组首行应 vMerge=restart")
            assertTrue(tcPr2?.vMerge != null && "continue" == tcPr2.vMerge.`val`.toString(), "组内行应 vMerge=continue")

            // 2.4 元数据完整性:表级注释 1/4,字段级注释 1/5
            val t24 = tableByHeader(doc, "数据库实例", "分析维度")
            assertEquals(3, t24.rows.size)
            assertEquals(listOf("db_a", "表级注释", "1", "3", "25.00%"), rowTexts(t24, 1))
            assertEquals(listOf("", "字段级注释", "1", "4", "20.00%"), rowTexts(t24, 2))

            // 2.5 数据类型分布:字符串/整数/小数/时间/其他 各 1
            val t25 = tableByHeader(doc, "数据库实例", "数据类型", "字段数", "占比", "类型示例")
            assertEquals(6, t25.rows.size)
            assertEquals(listOf("db_a", "字符串型", "1", "20.00%", "Varchar、char、text"), rowTexts(t25, 1))
            assertEquals(listOf("", "其他型", "1", "20.00%", "-"), rowTexts(t25, 5))

            // 2.6 数据冗余:空表/备份表/分表 各 1
            val t26 = tableByHeader(doc, "数据库实例", "冗余类别")
            assertEquals(4, t26.rows.size)
            assertEquals(listOf("db_a", "空表", "1", "25.00%", "t_empty"), rowTexts(t26, 1))
            assertEquals(listOf("", "备份表", "1", "25.00%", "orders_bak"), rowTexts(t26, 2))
            assertEquals(listOf("", "分表", "1", "25.00%", "t_20250604"), rowTexts(t26, 3))

            // 2.7 大体量表:仅 t_big,增量列「-」,表用途回落表注释
            val t27 = tableByHeader(doc, "数据库实例", "表名", "行数")
            assertEquals(2, t27.rows.size)
            assertEquals(listOf("db_a", "t_big", "2,000,000", "2.0 GB", "大表注释", "-", "-", "-", "2"), rowTexts(t27, 1))

            // 分析段落:fake LLM 文本落位(2.2-2.7 引导段 + 第四章)
            val text = paragraphs(doc)
            assertTrue(text.contains("模拟分析文字"), text)
        }
    }

    @Test
    fun `第三章按 USER 标记分节,未扫描打标表跳过`() {
        enableFakeAi()
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a", tableCount = 2)
        seedDoneJob(id, "db_a",
            listOf(TableSeed("t1", 100L, 1024L), TableSeed("t2", 0L, 2048L)),
            mapOf("t1" to listOf(ColSeed("int", null, 100, 0)), "t2" to listOf(ColSeed("int", null, 0, 0))))
        val tagA = tagRepo.create("水文数据", "#409EFF")
        val tagB = tagRepo.create("监测数据", "#67C23A")
        tagRepo.ensureTableTag(tagA.id, id, "", "db_a", "t1")
        tagRepo.ensureTableTag(tagB.id, id, "", "db_a", "t2")
        tagRepo.ensureTableTag(tagB.id, id, "", "db_a", "t_ghost") // 未扫描的打标表,跳过

        render(id).use { doc ->
            val text = paragraphs(doc)
            // 两个标记各一节,标题按 3.1/3.2 编号
            assertTrue(text.contains("3.1 水文数据数据(统计结果分析)"), text)
            assertTrue(text.contains("3.2 监测数据数据(统计结果分析)"), text)
            assertTrue(text.contains("另有 1 张打标表未扫描"), text)
            // 标记节的库一览表(7 列):水文数据 t1(100 行);监测数据 t2(0 行 2KB)
            val overviewTables = doc.tables.filter { t ->
                t.rows[0].tableCells.take(3).map { it.text.trim() } == listOf("序号", "数据库实例", "实例描述")
            }
            // 1.2 主表 + 2 个标记节各一张
            assertEquals(3, overviewTables.size)
            assertEquals(listOf("1", "db_a", "", "1", "1", "100", "1.0 KB"), rowTexts(overviewTables[1], 1))
            assertEquals(listOf("1", "db_a", "", "1", "1", "0", "2.0 KB"), rowTexts(overviewTables[2], 1))
        }
    }

    @Test
    fun `无 USER 标记时第三章渲染提示语`() {
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a")
        seedDoneJob(id, "db_a", listOf(TableSeed("t1", 1L, 1024L)), mapOf("t1" to listOf(ColSeed("int", null, 1, 0))))

        render(id).use { doc ->
            assertTrue(paragraphs(doc).contains("当前数据源没有可用的用户标记数据"), paragraphs(doc))
        }
    }

    @Test
    fun `LLM 不可用时分析段落渲染占位文字`() {
        chat = { _, _, _ -> throw IllegalStateException("大模型接口调用失败") }
        enableFakeAi()
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a")
        seedDoneJob(id, "db_a", listOf(TableSeed("t1", 1L, 1024L)), mapOf("t1" to listOf(ColSeed("int", null, 1, 0))))

        render(id).use { doc ->
            assertTrue(paragraphs(doc).contains("(待人工编写)"), paragraphs(doc))
        }
    }

    @Test
    fun `选中的库未完成全表扫描时拦截并列出库名`() {
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a", "db_c")
        seedDoneJob(id, "db_a", listOf(TableSeed("t1", 1L, 1024L)), mapOf("t1" to listOf(ColSeed("int", null, 1, 0))))
        // db_c 没有任何扫描任务

        val e = assertThrows(IllegalStateException::class.java) {
            service.export(id, null, null, ByteArrayOutputStream())
        }
        assertTrue(e.message!!.contains("db_c"), "错误信息应列出未扫描库名")
        assertTrue(!e.message!!.contains("db_a"), "已完成的库不应出现在错误信息里")
    }

    @Test
    fun `全部库都未完成扫描时拦截导出`() {
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a")
        // 任务仍是 RUNNING(未完成)
        val jobId = scanRepo.insertJob(id, null, "db_a", false, null, 1)
        scanRepo.markJobRunning(jobId)

        val e = assertThrows(IllegalStateException::class.java) {
            service.export(id, null, null, ByteArrayOutputStream())
        }
        assertTrue(e.message!!.contains("db_a"), e.message)
    }

    @Test
    fun `快照未覆盖当前全部表时视为未全表扫描`() {
        val id = createDataSource("测试源")
        // 当前库有 5 张表,最近一次 DONE 快照只扫了 1 张(如只勾选部分表扫描,或扫描后新增表)
        schemaStatRepo.replaceAll(id, null, listOf(SchemaStatRepository.CachedStat("db_a", 5, 1024L)))
        seedDoneJob(id, "db_a", listOf(TableSeed("t1", 1L, 1024L)), mapOf("t1" to listOf(ColSeed("int", null, 1, 0))))

        val e = assertThrows(IllegalStateException::class.java) {
            service.export(id, null, null, ByteArrayOutputStream())
        }
        assertTrue(e.message!!.contains("db_a"), e.message)
    }

    @Test
    fun `重扫进行中仍取历史 DONE 快照导出`() {
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a")
        seedDoneJob(id, "db_a", listOf(TableSeed("t1", 7L, 1024L)), mapOf("t1" to listOf(ColSeed("int", null, 7, 0))))
        // 最新任务是进行中的重扫,不应阻断导出,数据仍取历史 DONE 快照
        val runningId = scanRepo.insertJob(id, null, "db_a", false, null, 1)
        scanRepo.markJobRunning(runningId)

        render(id).use { doc ->
            val text = paragraphs(doc)
            assertTrue(text.contains("共计1个数据库实例"), text)
            assertTrue(text.contains("累计数据总行数7行"), text)
        }
    }

    @Test
    fun `表体积快照缺失时实时补算失败降级为部分合计`() {
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a")
        // sizeBytes 为 null(如 Oracle 受限账号);实时补算回连业务库(localhost:1 必失败)后降级
        val jobId = scanRepo.insertJob(id, null, "db_a", false, null, 1)
        val stId = scanRepo.insertScanTable(jobId, "t1", null, null, null, null)
        scanRepo.finishTable(stId, ScanStatus.DONE, 3L, null)
        scanRepo.insertScanColumn(stId, ScanColumnView.of("c", "int", null, null, null, null, 3, 0, 0, 0))
        scanRepo.finishJob(jobId, ScanStatus.DONE, null)

        render(id).use { doc ->
            assertTrue(paragraphs(doc).contains("累计数据总存储量约0 B"), paragraphs(doc))
        }
    }

    @Test
    fun `按 schemas 参数限定导出范围(表列表页单库导出)`() {
        val id = createDataSource("测试源")
        seedSchemaStat(id, "db_a", "db_b")
        seedDoneJob(id, "db_a", listOf(TableSeed("t1", 1L, 1024L)), mapOf("t1" to listOf(ColSeed("int", null, 1, 0))))
        seedDoneJob(id, "db_b", listOf(TableSeed("t2", 2L, 2048L)), mapOf("t2" to listOf(ColSeed("int", null, 2, 0))))

        render(id, listOf("db_b")).use { doc ->
            assertTrue(paragraphs(doc).contains("共计1个数据库实例"))
            assertEquals("db_b", tableByHeader(doc, "序号", "数据库实例").rows[1].tableCells[1].text.trim())
        }

        // 限定的库不存在时走 400
        assertThrows(IllegalArgumentException::class.java) {
            service.export(id, null, listOf("db_x"), ByteArrayOutputStream())
        }
    }
}
