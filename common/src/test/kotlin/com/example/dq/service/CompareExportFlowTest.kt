package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.CreateCompareJobRequest
import com.example.dq.model.CompareTargetSpec
import com.example.dq.model.DataSourceRequest
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager

/**
 * 端到端:Testcontainers 起真实 MySQL(基准库 + 厂商库),跑完整比对流程后按新格式导出,
 * 验证「总览 sheet 一行一个系统 + 每个差异行一个 sheet」在真实数据链路上成立
 * (target_count 落库、表注释/所属系统取数、差异原因拼写)。
 */
@Testcontainers
class CompareExportFlowTest {

    companion object {
        @Container
        @JvmField
        val MYSQL: MySQLContainer<Nothing> = MySQLContainer<Nothing>("mysql:8.0")
            .withDatabaseName("reservoir_base")

        init {
            // MySQLContainer 只建一个库并把 test 账号权限限定在该库;比对需要两个库,
            // 容器启动后用 root 建厂商库并授权(表由本测试自己建)
            MYSQL.start()
            MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.password, "-e",
                "CREATE DATABASE IF NOT EXISTS reservoir_vendor;" +
                    "GRANT ALL PRIVILEGES ON reservoir_vendor.* TO 'test'@'%'; FLUSH PRIVILEGES;")
        }
    }

    private val config = AppConfig(dataDir = Files.createTempDirectory("compare-it"))
    private val dataSourceService: DataSourceService
    private val metadataService: MetadataService
    private val compareService: CompareService
    private val compareRepo: CompareRepository
    private val tableSystemRepo: TableSystemRepository

    init {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:compare-it-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        compareRepo = CompareRepository(jdbc)
        tableSystemRepo = TableSystemRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        val metaCacheRepo = MetaCacheRepository(jdbc)
        val crypto = CryptoUtil(config)
        dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config,
            SchemaStatRepository(jdbc), metaCacheRepo)
        metadataService = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc),
            SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo)
        compareService = CompareService(compareRepo, dataSourceService, DialectFactory, metadataService,
            SystemSettingsService(SystemSettingsRepository(jdbc), config), tableSystemRepo)
    }

    @Test
    fun `mysql双库比对后导出总览与逐差异明细`() {
        seed()
        // 基准库 URL:元数据回源按连接当前库取表清单,连接必须落在基准库上
        val containerUrl = MYSQL.jdbcUrl.substringBeforeLast("/") + "/"
        val baseId = dataSourceService.create(DataSourceRequest(
            "基准库", containerUrl + "reservoir_base", MYSQL.username, MYSQL.password, null, null))
        val vendorId = dataSourceService.create(DataSourceRequest(
            "厂商库", MYSQL.jdbcUrl, MYSQL.username, MYSQL.password, null, null))

        // 基准库表注释 + 厂商库所属系统:总览「表中文名/所属系统」两列的取数来源
        tableSystemRepo.upsert(vendorId, "", "reservoir_vendor", "t_reservoir_info", "厂商运管系统")

        val jobId = compareService.submit(CreateCompareJobRequest(
            name = "水库比对-E2E", baseDatasourceId = baseId, baseDb = "", baseSchema = "reservoir_base",
            baseTable = "reservoir_base_info", keyField = "reservoir_code",
            fields = listOf("reservoir_code", "reservoir_name"),
            targets = listOf(CompareTargetSpec(vendorId, "", "reservoir_vendor", "t_reservoir_info"))))
        awaitDone(jobId)

        // 目标总行数(含多余行)已落库:与基准差 = 目标行数 − 基准行数
        val target = compareRepo.listTargets(jobId).single()
        assertEquals(100, target.baseCount)
        assertEquals(101, target.targetCount)
        assertEquals("DONE", target.status)

        val out = ByteArrayOutputStream()
        compareService.exportDiff(jobId, out)
        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            assertEquals("总览", wb.getSheetName(0))
            val overview = wb.getSheetAt(0)
            assertEquals("匹配编码数", overview.getRow(0).getCell(6).stringCellValue)
            assertEquals("匹配对象数", overview.getRow(0).getCell(7).stringCellValue)
            // 第 1 行基准表,第 2 行厂商库目标;表名为单元格内三行「表名 / 系统名 / （库.模式）」格式
            assertEquals("reservoir_base_info\n基准库\n（reservoir_base）", overview.getRow(1).getCell(1).stringCellValue)
            assertEquals(100.0, overview.getRow(1).getCell(3).numericCellValue)
            assertEquals("水库基础信息表", overview.getRow(1).getCell(0).stringCellValue)
            // 数据最新更新时间:比对时按名称命中 update_time → MAX(update_time),两侧各取各自最新
            assertEquals("2026-01-03 07:30:00", overview.getRow(1).getCell(4).stringCellValue)
            assertEquals("t_reservoir_info\n厂商运管系统\n（reservoir_vendor）", overview.getRow(2).getCell(1).stringCellValue)
            assertEquals("厂商运管系统", overview.getRow(2).getCell(2).stringCellValue)
            assertEquals(101.0, overview.getRow(2).getCell(3).numericCellValue)
            assertEquals("2026-02-02 10:00:00", overview.getRow(2).getCell(4).stringCellValue)
            assertEquals(1.0, overview.getRow(2).getCell(5).numericCellValue)
            // EXACT 任务全部按编码命中(100 条基准 − 3 条缺失):匹配编码数 = 匹配对象数 = 97
            assertEquals(97.0, overview.getRow(2).getCell(6).numericCellValue)
            assertEquals(97.0, overview.getRow(2).getCell(7).numericCellValue)
            assertTrue(overview.getRow(2).getCell(9).stringCellValue.contains("行数相差 1"),
                overview.getRow(2).getCell(9).stringCellValue)
            // 总览 1 条差异数据(1 个目标)对应 1 个明细 sheet + 行级对比明细 + 字段级差异汇总 + 数据级字段对比汇总 + 列级对比明细
            assertEquals(6, wb.numberOfSheets)
            assertEquals("行级对比明细", wb.getSheetName(1))
            assertEquals("字段级差异汇总", wb.getSheetName(2))
            assertEquals("数据级字段对比差异总览", wb.getSheetName(3))
            assertEquals("列级对比明细", wb.getSheetName(4))
            assertTrue(wb.getSheetName(5).startsWith("1_t_reservoir_info"), wb.getSheetName(5))
            // 行级对比明细:首行即表头,一行一个「对象 × 比对目标」;只列对象级差异(缺失/多余/编码不一致),
            // 5 条仅名称改动的对象按编码配上,属字段级差异,不在本 sheet(见下方各目标明细 sheet)
            val rowLevel = wb.getSheetAt(1)
            assertEquals(listOf("基准表英文名", "基准表中文名", "基准编码字段", "基准编码", "基准名称字段", "基准名称",
                "业务表英文名", "业务表中文名", "业务表编码字段", "业务表编码", "业务表名称字段", "业务表名称",
                "差异说明", "差异类型"),
                (0..13).map { rowLevel.getRow(0).getCell(it).stringCellValue })
            // 编码/名称字段列:基准侧 = 主键 reservoir_code / 显示名自动取 reservoir_name,业务侧无映射按同名回落
            assertEquals(listOf("reservoir_base_info\n基准库\n（reservoir_base）", "水库基础信息表", "reservoir_code", "R091", "reservoir_name", "水库91",
                "t_reservoir_info\n厂商运管系统\n（reservoir_vendor）", "", "reservoir_code", "", "reservoir_name", "", "基准有目标无", "缺失"),
                (0..13).map { rowLevel.getRow(1).getCell(it).stringCellValue })
            // 缺失 3 + 多余 4 = 7 行(名称不一致 5 条不计入对象级差异)
            val expectedRows = target.missingCount!! + target.extraCount!!
            assertEquals(expectedRows, rowLevel.lastRowNum)
            // 总览「差异条数」= 数量差异:缺失 3 + 多余 4 = 7(编码全部配上,无属性差异)
            assertEquals(7.0, overview.getRow(2).getCell(8).numericCellValue)
            // 字段级差异汇总:reservoir_code 无不一致(3 缺失 + 4 多余),reservoir_name 不一致 5 次;
            // 表头含「业务表字段中文」列(该表未设字段注释,取值留空)
            val fieldSummary = wb.getSheetAt(2)
            assertEquals(listOf("业务表英文名", "业务表中文名", "基准表字段", "字段中文", "业务表字段", "业务表字段中文",
                "差异数量", "缺失", "多余", "不一致"),
                (0..9).map { fieldSummary.getRow(0).getCell(it).stringCellValue })
            assertEquals(listOf("reservoir_code", "reservoir_name"),
                (1..2).map { fieldSummary.getRow(it).getCell(2).stringCellValue })
            assertEquals("", fieldSummary.getRow(1).getCell(5).stringCellValue)
            assertEquals(7.0, fieldSummary.getRow(1).getCell(6).numericCellValue)   // 3+4+0
            assertEquals(12.0, fieldSummary.getRow(2).getCell(6).numericCellValue)  // 3+4+5
            assertEquals(5.0, fieldSummary.getRow(2).getCell(9).numericCellValue)
            val detail = wb.getSheetAt(5)
            // 字段级明细:首行即表头(定位列 + 对象编码/名称 + 基准/业务两块各三列 + 差异原因),无上下文/图例行;
            // 定位列:厂商库已登记所属系统「厂商运管系统」;MySQL 单库口径 db 留空(schema 即库,不出「模式」列)
            val detailPrefix = listOf("厂商运管系统", "", "t_reservoir_info", "")
            assertEquals(listOf("业务系统名称", "库", "表名", "表中文名", "reservoir_code", "reservoir_name",
                "基准表字段", "字段中文", "基准表值", "业务表字段名", "业务表中文", "业务表值", "差异原因"),
                (0..12).map { detail.getRow(0).getCell(it).stringCellValue })
            // 一行一个「对象 × 字段」:不一致 5 字段 + 缺失 3 对象×2 比对字段 + 多余 4 对象×2 比对字段 = 19 行
            // (缺失/多余按整行快照逐比对字段展开,比对字段为 reservoir_code/reservoir_name 两个)
            val expected = target.fieldMismatchCount!! +
                (target.missingCount!! + target.extraCount!!) * 2
            assertEquals(expected, lastDataRow(detail))
            // 明细按 不一致 → 缺失 → 多余 排列:首行是 R001 的 reservoir_name 字段级不一致
            assertEquals(detailPrefix + listOf("R001", "水库1", "reservoir_name", "", "水库1",
                "reservoir_name", "", "改名水库1", "文本不一致"),
                (0..12).map { detail.getRow(1).getCell(it).stringCellValue })
            // 缺失对象逐比对字段展开:基准块照常填、业务表值留空,差异原因写「基准有目标无」
            val missingRow = (1..1 + expected).first { detail.getRow(it).getCell(4).stringCellValue == "R091" }
            assertEquals(detailPrefix + listOf("R091", "水库91", "reservoir_code", "", "R091",
                "reservoir_code", "", "", "基准有目标无"),
                (0..12).map { detail.getRow(missingRow).getCell(it).stringCellValue })
            assertEquals(detailPrefix + listOf("R091", "水库91", "reservoir_name", "", "水库91",
                "reservoir_name", "", "", "基准有目标无"),
                (0..12).map { detail.getRow(missingRow + 1).getCell(it).stringCellValue })
            // 多余对象逐比对字段展开:基准表值留空、业务块照常填,差异原因写「目标有基准无」
            val extraRow = (missingRow..1 + expected).first {
                detail.getRow(it).getCell(12).stringCellValue == "目标有基准无" }
            assertEquals(detailPrefix + listOf("V001", "厂区水库1", "reservoir_code", "", "",
                "reservoir_code", "", "V001", "目标有基准无"),
                (0..12).map { detail.getRow(extraRow).getCell(it).stringCellValue })
        } finally {
            wb.close()
        }
    }

    /** 明细 sheet 最后一行数据行号(首行即表头,数据紧随其后) */
    private fun lastDataRow(detail: org.apache.poi.ss.usermodel.Sheet): Int = detail.lastRowNum

    /**
     * 抽样比对(V70):sample_rows=5 的任务双侧各「按身份列排序取前 5 条」,
     * base_count/target_count 落样本量 5,比对正常完成,导出总览披露抽样口径
     */
    @Test
    fun `抽样任务只取前N条且总览带抽样说明`() {
        val containerUrl = MYSQL.jdbcUrl.substringBeforeLast("/") + "/"
        DriverManager.getConnection(containerUrl + "reservoir_base", MYSQL.username, MYSQL.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS reservoir_base.sample_base")
                st.execute("DROP TABLE IF EXISTS reservoir_vendor.sample_target")
                st.execute("CREATE TABLE reservoir_base.sample_base(" +
                    "code VARCHAR(32) PRIMARY KEY, name VARCHAR(64)) ENGINE=InnoDB")
                st.execute("CREATE TABLE reservoir_vendor.sample_target(" +
                    "code VARCHAR(32) PRIMARY KEY, name VARCHAR(64)) ENGINE=InnoDB")
                // 双侧各 10 行:R001~R008 完全一致,R009/R010 名称不同(抽样前 5 条碰不到它们)
                for (i in 1..10) {
                    st.execute(("INSERT INTO reservoir_base.sample_base VALUES('R%03d','水库%d')").format(i, i))
                    val name = if (i >= 9) "改名水库$i" else "水库$i"
                    st.execute(("INSERT INTO reservoir_vendor.sample_target VALUES('R%03d','%s')").format(i, name))
                }
            }
        }
        val baseId = dataSourceService.create(DataSourceRequest(
            "抽样基准库", containerUrl + "reservoir_base", MYSQL.username, MYSQL.password, null, null))
        val vendorId = dataSourceService.create(DataSourceRequest(
            "抽样厂商库", MYSQL.jdbcUrl, MYSQL.username, MYSQL.password, null, null))
        val jobId = compareService.submit(CreateCompareJobRequest(
            name = "抽样比对-E2E", baseDatasourceId = baseId, baseDb = "", baseSchema = "reservoir_base",
            baseTable = "sample_base", keyField = "code",
            fields = listOf("code", "name"), sampleRows = 5,
            targets = listOf(CompareTargetSpec(vendorId, "", "reservoir_vendor", "sample_target"))))
        awaitDone(jobId)

        // 条数 = 样本量(各取前 5 条);R001~R005 完全一致 → 与基准完全一致(指标为样本口径)
        val target = compareRepo.listTargets(jobId).single()
        assertEquals("DONE", target.status)
        assertEquals(5, target.baseCount)
        assertEquals(5, target.targetCount)
        assertEquals(5, target.matchedCount)
        assertEquals(0, target.fieldMismatchCount)

        val out = ByteArrayOutputStream()
        compareService.exportDiff(jobId, out)
        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val overview = wb.getSheetAt(0)
            assertEquals("基准表(抽样比对,不参与差异统计)", overview.getRow(1).getCell(9).stringCellValue)
            assertEquals(5.0, overview.getRow(1).getCell(3).numericCellValue)
            assertEquals(5.0, overview.getRow(2).getCell(3).numericCellValue)
            val reason = overview.getRow(2).getCell(9).stringCellValue
            assertTrue(reason.contains("与基准完全一致"), reason)
            assertTrue(reason.contains("抽样比对:每侧仅取前 5 条(按身份列排序)"), reason)
        } finally {
            wb.close()
        }
    }

    /**
     * 身份列为空的行:以行内代理键进比对——编码路对它无命中,名称路按名称精准配对
     * (「任意一边 code 空就用 name 匹配」);配不上的落多余;no_key_rows 单列计数并透出
     */
    @Test
    fun `目标表身份列为空的行经名称配对且条数按实际读到行数`() {
        val containerUrl = MYSQL.jdbcUrl.substringBeforeLast("/") + "/"
        DriverManager.getConnection(containerUrl + "reservoir_base", MYSQL.username, MYSQL.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS reservoir_base.reservoir_base_info2")
                st.execute("DROP TABLE IF EXISTS reservoir_vendor.t_reservoir_nullkey")
                st.execute("CREATE TABLE reservoir_base.reservoir_base_info2(" +
                    "reservoir_code VARCHAR(32) PRIMARY KEY, reservoir_name VARCHAR(64)) ENGINE=InnoDB")
                // 目标表编码列不带主键约束,允许 NULL(身份列全空的脏数据场景)
                st.execute("CREATE TABLE reservoir_vendor.t_reservoir_nullkey(" +
                    "reservoir_code VARCHAR(32), reservoir_name VARCHAR(64)) ENGINE=InnoDB")
                for (i in 1..5) {
                    st.execute(("INSERT INTO reservoir_base.reservoir_base_info2 VALUES('R%03d','水库%d')")
                        .format(i, i))
                }
                // 7 行:R001~R003 编码对上;编码 NULL 但名称「水库4」与基准 R004 相同 → 名称路应配成同一对象
                // (DIFF:基准有编码 R004、目标编码空);(NULL,NULL) 名称也为空 → 配不上落多余;
                // V001/V002 编码多余 → 多余;R005 缺失 → 缺失
                st.execute("INSERT INTO reservoir_vendor.t_reservoir_nullkey VALUES" +
                    "('R001','水库1'),('R002','水库2'),('R003','水库3'),(NULL,'水库4')," +
                    "('V001','厂区水库1'),('V002','厂区水库2'),(NULL,NULL)")
            }
        }
        val baseId = dataSourceService.create(DataSourceRequest(
            "基准库2", containerUrl + "reservoir_base", MYSQL.username, MYSQL.password, null, null))
        val vendorId = dataSourceService.create(DataSourceRequest(
            "厂商库2", MYSQL.jdbcUrl, MYSQL.username, MYSQL.password, null, null))
        // 先编码后名称:编码空/配不上的一律按对象名称精准配对
        val jobId = compareService.submit(CreateCompareJobRequest(
            name = "空身份行-E2E", baseDatasourceId = baseId, baseDb = "", baseSchema = "reservoir_base",
            baseTable = "reservoir_base_info2", keyField = "reservoir_code",
            fields = listOf("reservoir_code", "reservoir_name"),
            displayField = "reservoir_name", matchMode = "CODE_THEN_NAME",
            targets = listOf(CompareTargetSpec(vendorId, "", "reservoir_vendor", "t_reservoir_nullkey"))))
        awaitDone(jobId)

        val target = compareRepo.listTargets(jobId).single()
        assertEquals("DONE", target.status)
        // 条数 = 实际读到的总行数;身份列空行 2 行单列计数
        assertEquals(5, target.baseCount)
        assertEquals(7, target.targetCount)
        assertEquals(2, target.noKeyRows)
        // R001~R003 编码配 + 水库4 名称配 = 4 命中(编码 3 / 名称 1);R005 缺失;
        // 多余 3:V001/V002 + (NULL,NULL);名称配对行编码不一致(基准 R004 vs 目标空)→ 计入对象级差异
        assertEquals(4, target.matchedCount)
        assertEquals(3, target.codeMatchedCount)
        assertEquals(1, target.nameMatchedCount)
        assertEquals(1, target.missingCount)
        assertEquals(3, target.extraCount)

        val out = ByteArrayOutputStream()
        compareService.exportDiff(jobId, out)
        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val overview = wb.getSheetAt(0)
            assertEquals(7.0, overview.getRow(2).getCell(3).numericCellValue)
            assertEquals(2.0, overview.getRow(2).getCell(5).numericCellValue)
            // 匹配编码数 = 3(R001~R003 编码命中),匹配对象数 = 4(编码 3 + 名称 1)
            assertEquals(3.0, overview.getRow(2).getCell(6).numericCellValue)
            assertEquals(4.0, overview.getRow(2).getCell(7).numericCellValue)
            val reason = overview.getRow(2).getCell(9).stringCellValue
            assertTrue(reason.contains("身份列为空 2 行(仅按名称/大模型配对)"), reason)
            assertTrue(reason.contains("行数相差 2(目标 7 / 基准 5)"), reason)
            // 行级对比明细(对象级):缺失 R005 + 多余 3(V001/V002/(NULL,NULL)) = 4 行;
            // 名称配上的「水库4」是字段级差异(基准有编码、目标编码空 ≠ 编码不一致),不进本 sheet
            val rowLevel = wb.getSheetAt(1)
            val allRows = (1..rowLevel.lastRowNum).map { r ->
                (0..13).map { c -> rowLevel.getRow(r).getCell(c)?.stringCellValue ?: "" }
            }
            assertEquals(1, allRows.count { it[13] == "缺失" })
            // 多余行里应有 (NULL,NULL) 那行(object_key 回落显示名为空、名称也为空,但行存在)
            assertEquals(3, allRows.count { it[13] == "多余" })
            assertEquals(null, allRows.firstOrNull { it[5] == "水库4" && it[11] == "水库4" })
            // 总览「差异条数」= 数量差异:缺失 1 + 多余 3 = 4(编码不一致 0,一侧为空的配对是属性差异不计)
            assertEquals(4.0, overview.getRow(2).getCell(8).numericCellValue)
            // 但字段级明细照常展开:R004(水库4)的 reservoir_code 不一致(基准 R004 → 目标空)
            val detail = wb.getSheetAt(5)
            val codeDiff = (1..detail.lastRowNum).map { r ->
                (0..12).map { c -> detail.getRow(r).getCell(c)?.stringCellValue ?: "" }
            }.firstOrNull { it[4] == "R004" && it[6] == "reservoir_code" }
            assertNotNull(codeDiff, "名称配对对象的编码差异应在字段级明细展开")
            assertEquals("R004", codeDiff!![8])
            assertEquals("", codeDiff[11])
        } finally {
            wb.close()
        }
    }

    private fun awaitDone(jobId: Long) {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val job = compareService.detail(jobId).job
            if (job.status == "DONE") return
            if (job.status == "FAILED" || job.status == "CANCELED") {
                throw AssertionError("比对任务未成功: " + job.status + " / " + job.error)
            }
            Thread.sleep(300)
        }
        throw AssertionError("比对任务超时未完成")
    }

    /** 基准库 100 条(厂商缺 R091~R093 → 缺失 3),厂商库 101 条(多 V001~V004 → 多余 4,名称改动 5 条) */
    private fun seed() {
        val containerUrl = MYSQL.jdbcUrl.substringBeforeLast("/") + "/"
        DriverManager.getConnection(containerUrl + "reservoir_base", MYSQL.username, MYSQL.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS reservoir_base.reservoir_base_info")
                st.execute("DROP TABLE IF EXISTS reservoir_vendor.t_reservoir_info")
                // update_time 为「数据最新更新时间」探测用:两侧都带中文注释,名称命中 update 类规则
                st.execute("CREATE TABLE reservoir_base.reservoir_base_info(" +
                    "reservoir_code VARCHAR(32) PRIMARY KEY, reservoir_name VARCHAR(64), " +
                    "update_time DATETIME COMMENT '更新时间') " +
                    "ENGINE=InnoDB COMMENT='水库基础信息表'")
                st.execute("CREATE TABLE reservoir_vendor.t_reservoir_info(" +
                    "reservoir_code VARCHAR(32) PRIMARY KEY, reservoir_name VARCHAR(64), " +
                    "update_time DATETIME COMMENT '更新时间') ENGINE=InnoDB")
                for (i in 1..100) {
                    // 基准库 100 条全量(R100 时间最新,验证取的是 MAX 而不是首行)
                    val baseTime = if (i == 100) "2026-01-03 07:30:00" else "2026-01-01 08:00:00"
                    st.execute(("INSERT INTO reservoir_base.reservoir_base_info VALUES" +
                        "('R%03d','水库%d','%s')").format(i, i, baseTime))
                    // 91~93 在厂商库缺行(基准有目标无 → 缺失 3 条);前 5 条名称被改(逐字段差异 5 处)
                    if (i in 91..93) continue
                    val vendorName = if (i in 1..5) "改名水库$i" else "水库$i"
                    st.execute(("INSERT INTO reservoir_vendor.t_reservoir_info VALUES" +
                        "('R%03d','%s','2026-02-01 09:00:00')").format(i, vendorName))
                }
                for (i in 1..4) { // 目标有基准无 → 多余 4 条(V004 时间最新)
                    val vendorTime = if (i == 4) "2026-02-02 10:00:00" else "2026-02-01 09:00:00"
                    st.execute(("INSERT INTO reservoir_vendor.t_reservoir_info VALUES" +
                        "('V%03d','厂区水库%d','%s')").format(i, i, vendorTime))
                }
            }
        }
    }
}
