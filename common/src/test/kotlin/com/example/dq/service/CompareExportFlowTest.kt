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
            // 第 1 行基准表,第 2 行厂商库目标
            assertEquals("reservoir_base_info", overview.getRow(1).getCell(1).stringCellValue)
            assertEquals(100.0, overview.getRow(1).getCell(3).numericCellValue)
            assertEquals("水库基础信息表", overview.getRow(1).getCell(0).stringCellValue)
            assertEquals("t_reservoir_info", overview.getRow(2).getCell(1).stringCellValue)
            assertEquals("厂商运管系统", overview.getRow(2).getCell(2).stringCellValue)
            assertEquals(101.0, overview.getRow(2).getCell(3).numericCellValue)
            assertEquals(1.0, overview.getRow(2).getCell(5).numericCellValue)
            assertTrue(overview.getRow(2).getCell(8).stringCellValue.contains("行数相差 1"),
                overview.getRow(2).getCell(8).stringCellValue)
            // 总览 1 条差异数据(1 个目标)对应 1 个明细 sheet + 行级对比明细 + 字段级差异汇总 + 数据级字段对比汇总 + 列级对比明细
            assertEquals(6, wb.numberOfSheets)
            assertEquals("行级对比明细", wb.getSheetName(1))
            assertEquals("字段级差异汇总", wb.getSheetName(2))
            assertEquals("数据级字段对比差异总览", wb.getSheetName(3))
            assertEquals("列级对比明细", wb.getSheetName(4))
            assertTrue(wb.getSheetName(5).startsWith("1_t_reservoir_info"), wb.getSheetName(5))
            // 行级对比明细:首行即表头,一行一个「对象 × 比对目标」;R001 按编码配上,业务侧名称取目标真实值
            val rowLevel = wb.getSheetAt(1)
            assertEquals(listOf("基准表英文名", "基准表中文名", "基准编码", "基准名称",
                "业务表英文名", "业务表中文名", "业务表编码", "业务表名称", "差异说明", "差异类型"),
                (0..9).map { rowLevel.getRow(0).getCell(it).stringCellValue })
            assertEquals(listOf("reservoir_base_info", "水库基础信息表", "R001", "水库1",
                "t_reservoir_info", "", "R001", "改名水库1",
                "reservoir_name: 基准「水库1」→ 目标「改名水库1」", "不一致"),
                (0..9).map { rowLevel.getRow(1).getCell(it).stringCellValue })
            // 缺失 3 + 多余 4 + 不一致 5 = 12 行
            val expectedRows = target.missingCount!! + target.extraCount!! + target.fieldMismatchCount!!
            assertEquals(expectedRows, rowLevel.lastRowNum)
            // 字段级差异汇总:reservoir_code 无不一致(3 缺失 + 4 多余),reservoir_name 不一致 5 次
            val fieldSummary = wb.getSheetAt(2)
            assertEquals(listOf("reservoir_code", "reservoir_name"),
                (1..2).map { fieldSummary.getRow(it).getCell(2).stringCellValue })
            assertEquals(7.0, fieldSummary.getRow(1).getCell(5).numericCellValue)   // 3+4+0
            assertEquals(12.0, fieldSummary.getRow(2).getCell(5).numericCellValue)  // 3+4+5
            assertEquals(5.0, fieldSummary.getRow(2).getCell(8).numericCellValue)
            val detail = wb.getSheetAt(5)
            // 字段级明细:首行即表头(定位列 + 对象编码/名称 + 基准/业务两块各三列 + 差异原因),无上下文/图例行;
            // 定位列:厂商库已登记所属系统「厂商运管系统」;MySQL 单库口径 db 留空(schema 即库,不出「模式」列)
            val detailPrefix = listOf("厂商运管系统", "", "t_reservoir_info", "")
            assertEquals(listOf("业务系统名称", "库", "表名", "表中文名", "reservoir_code", "reservoir_name",
                "基准表字段", "字段中文", "基准表值", "业务表字段名", "业务表中文", "业务表值", "差异原因"),
                (0..12).map { detail.getRow(0).getCell(it).stringCellValue })
            // 一行一个「对象 × 不一致字段」:不一致 5 字段 + 缺失 3 + 多余 4 = 12 行
            val expected = target.missingCount!! + target.extraCount!! + target.fieldMismatchCount!!
            assertEquals(expected, lastDataRow(detail))
            // 明细按 不一致 → 缺失 → 多余 排列:首行是 R001 的 reservoir_name 字段级不一致
            assertEquals(detailPrefix + listOf("R001", "水库1", "reservoir_name", "", "水库1",
                "reservoir_name", "", "改名水库1", "文本不一致"),
                (0..12).map { detail.getRow(1).getCell(it).stringCellValue })
            // 缺失对象一对象一行,字段六列留空,差异原因写「基准有目标无」
            val missingRow = (1..1 + expected).first { detail.getRow(it).getCell(4).stringCellValue == "R091" }
            assertEquals("基准有目标无", detail.getRow(missingRow).getCell(12).stringCellValue)
            // 多余对象差异原因写「目标有基准无」
            assertEquals("目标有基准无", detail.getRow(missingRow + 5).getCell(12).stringCellValue)
        } finally {
            wb.close()
        }
    }

    /** 明细 sheet 最后一行数据行号(首行即表头,数据紧随其后) */
    private fun lastDataRow(detail: org.apache.poi.ss.usermodel.Sheet): Int = detail.lastRowNum

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
                st.execute("CREATE TABLE reservoir_base.reservoir_base_info(" +
                    "reservoir_code VARCHAR(32) PRIMARY KEY, reservoir_name VARCHAR(64)) " +
                    "ENGINE=InnoDB COMMENT='水库基础信息表'")
                st.execute("CREATE TABLE reservoir_vendor.t_reservoir_info(" +
                    "reservoir_code VARCHAR(32) PRIMARY KEY, reservoir_name VARCHAR(64)) ENGINE=InnoDB")
                for (i in 1..100) {
                    // 基准库 100 条全量
                    st.execute("INSERT INTO reservoir_base.reservoir_base_info VALUES('R%03d','水库%d')".format(i, i))
                    // 91~93 在厂商库缺行(基准有目标无 → 缺失 3 条);前 5 条名称被改(逐字段差异 5 处)
                    if (i in 91..93) continue
                    val vendorName = if (i in 1..5) "改名水库$i" else "水库$i"
                    st.execute("INSERT INTO reservoir_vendor.t_reservoir_info VALUES('R%03d','%s')"
                        .format(i, vendorName))
                }
                for (i in 1..4) { // 目标有基准无 → 多余 4 条
                    st.execute("INSERT INTO reservoir_vendor.t_reservoir_info VALUES('V%03d','厂区水库%d')".format(i, i))
                }
            }
        }
    }
}
