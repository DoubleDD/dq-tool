package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.util.CryptoUtil
import io.mockk.mockk
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * 数据比对导出格式单测:总览行口径(条数/与基准差/匹配编码数/差异条数/差异原因)+
 * xlsx 结构(首 sheet「总览」、每个差异行一个 sheet、DIFF 行逐字段展开)+
 * 表中文名/所属系统的取数路径(结构缓存 + table_system,回落数据源名)
 */
class CompareExportTest {

    private companion object {
        private const val DS_BASE = 1L
        private const val DS_TARGET = 2L
        private const val JOB_ID = 10L

        /** 导出上下文键(与生产代码同一口径) */
        private fun ctxKey(datasourceId: Long, db: String, table: String) =
            CompareService.ExportContext.key(datasourceId, db, table)

        private fun jobRow() = CompareRepository.JobRow(
            JOB_ID, "水库比对", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", null, null, null, """["id","name","capacity"]""", "DONE", null, 3, 3, null, false, null, null, null)

        private fun targetRow(id: Long = 100L) = CompareRepository.TargetRow(
            id, JOB_ID, DS_TARGET, "厂商库", "reservoir_vendor", null, "t_reservoir_info", "DONE",
            baseCount = 100, targetCount = 101, matchedCount = 93,
            // 老任务口径:匹配来源计数未采集(NULL),展示层按「编码命中 = matchedCount、名称/大模型 = 0」解读
            codeMatchedCount = null, nameMatchedCount = null, aiMatchedCount = null,
            missingCount = 7, extraCount = 8,
            fieldMismatchCount = 5, coverage = 0.93, fieldConsistency = 0.98, completeness = 1.0,
            score = 0.97, error = null)
    }

    /** 内存 H2 环境:任务/目标/差异明细与元数据结构缓存都走真实仓储,只放松系统设置与 Markdown 依赖 */
    private class Env {
        val repo: CompareRepository
        val metaCacheRepo: MetaCacheRepository
        val tableSystemRepo: TableSystemRepository
        val service: CompareService

        init {
            val ds = JdbcDataSource()
            ds.setURL("jdbc:h2:mem:compare-export-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
            SchemaInit.run(ds)
            val jdbc = Jdbc(ds)
            repo = CompareRepository(jdbc)
            metaCacheRepo = MetaCacheRepository(jdbc)
            tableSystemRepo = TableSystemRepository(jdbc)
            val dsRepo = DataSourceRepository(jdbc)
            val config = AppConfig(dataDir = Files.createTempDirectory("compare-export-test"))
            val dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
                SchemaStatRepository(jdbc), metaCacheRepo)
            dataSourceService.create(DataSourceRequest(
                "基准库", "jdbc:mysql://localhost:3306/reservoir_base", "root", "p", null, null))
            dataSourceService.create(DataSourceRequest(
                "厂商库", "jdbc:mysql://localhost:3306/reservoir_vendor", "root", "p", null, null))
            val metadataService = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc),
                SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo)
            service = CompareService(repo, dataSourceService, DialectFactory,
                metadataService, mockk(relaxed = true), tableSystemRepo)
        }

        /** 预置结构缓存表注释(命中缓存即不回源)与 table_system 所属系统 */
        fun seedMeta(vararg tables: Triple<Long, String, String>, systems: Map<String, String> = emptyMap()) {
            for ((dsId, db, name) in tables) {
                val comment = "表注释-$name"
                val schema = if (db.isNotBlank()) db else ""
                val existing = metaCacheRepo.listTables(dsId, db, schema).map { it.tableName }
                metaCacheRepo.replaceTables(dsId, db, schema,
                    (existing + name).distinct().map { MetaCacheRepository.CachedTable(it, comment, null, null, null) })
                systems[ctxKey(dsId, db, name)]?.let { tableSystemRepo.upsert(dsId, db, schema, name, it) }
            }
        }

        /** 造一个已完成任务 + 一个目标(基准 100 行 / 目标 101 行,缺失 1 / 多余 2 / 不一致 2 处) */
        fun seedDiffs(): Long {
            val jobId = repo.insertJob("水库比对", DS_BASE, "reservoir_base", null, "reservoir_base_info",
                "id", """["id","name","capacity"]""", 2)
            val targetId = repo.insertTarget(jobId, DS_TARGET, "厂商库", "reservoir_vendor", null, "t_reservoir_info")
            repo.updateTargetStats(targetId, 100, 101, 98, 1, 2, 2, 0.98, 0.99, 1.0, 0.99)
            // 总览「差异条数」取自 compare_diff 明细,与下面 3 条差异保持一致
            // diff_json 为整行快照(diffObjects 落库口径):DIFF 行一致字段 value 为 null,EXTRA/MISSING 单侧齐备
            repo.insertDiffs(jobId, targetId, listOf(
                CompareRepository.DiffInput("R001", "甲水库", "DIFF",
                    """[{"field":"id","base":"R001","value":null},{"field":"name","base":"甲水库","value":"甲水库(改)"},{"field":"capacity","base":"100","value":"200"}]"""),
                CompareRepository.DiffInput("R002", "乙水库", "MISSING",
                    """[{"field":"id","base":"R002","value":null},{"field":"name","base":"乙水库","value":null},{"field":"capacity","base":"150","value":null}]"""), // 与生产同口径:MISSING 带整行快照
                CompareRepository.DiffInput("R900", "厂区水库", "EXTRA",
                    """[{"field":"id","base":null,"value":"R900"},{"field":"name","base":null,"value":"厂区水库"},{"field":"capacity","base":null,"value":"80"}]"""),
            ))
            return jobId
        }
    }

    // ---------- 总览行口径 ----------

    @Test
    fun `总览行首行为基准表且与基准差留空`() {
        val env = Env()
        val ctx = CompareService.ExportContext(
            comments = mapOf(ctxKey(DS_BASE, "reservoir_base", "reservoir_base_info") to "水库基础信息表"),
            fallbackSystem = mapOf(DS_BASE to "基准库", DS_TARGET to "厂商库"))
        val overview = env.service.buildOverviewRows(jobRow(), listOf(targetRow()), emptyMap(), ctx)

        assertEquals(2, overview.size)
        val base = overview[0]
        assertNull(base.targetId)
        assertEquals("reservoir_base_info", base.tableName)
        assertEquals("水库基础信息表", base.tableComment)
        assertEquals("基准库", base.systemName)
        assertEquals(100, base.rowCount)
        assertNull(base.diffFromBase)
        assertNull(base.matchedCount)
        assertNull(base.diffCount)
        assertEquals("—", base.dataUpdatedAt)
    }

    @Test
    fun `总览目标行按目标总行数计算与基准差并拼差异原因`() {
        val env = Env()
        val ctx = CompareService.ExportContext(
            comments = mapOf(ctxKey(DS_TARGET, "reservoir_vendor", "t_reservoir_info") to "水库信息"),
            systems = mapOf(ctxKey(DS_TARGET, "reservoir_vendor", "t_reservoir_info") to "厂商系统"),
            fallbackSystem = mapOf(DS_BASE to "基准库", DS_TARGET to "厂商库"))
        val overview = env.service.buildOverviewRows(jobRow(), listOf(targetRow()),
            mapOf(100L to mapOf("MISSING" to 1, "EXTRA" to 2, "DIFF" to 1)), ctx)

        val row = overview[1]
        assertEquals(100L, row.targetId)
        assertEquals("水库信息", row.tableComment)
        assertEquals("厂商系统", row.systemName)
        assertEquals(101, row.rowCount)
        assertEquals(1, row.diffFromBase)
        assertEquals(93, row.matchedCount)
        assertEquals(4, row.diffCount)
        val reason = row.diffReason!!
        assertTrue(reason.contains("基准有目标无的对象 7 条"), reason)
        assertTrue(reason.contains("目标有基准无的对象 8 条"), reason)
        assertTrue(reason.contains("字段不一致单元格 5 处"), reason)
        assertTrue(reason.contains("行数相差 1"), reason)
    }

    @Test
    fun `老任务没有target_count时用匹配数加多余数兜底`() {
        val env = Env()
        val legacy = targetRow().copy(targetCount = null)
        val ctx = CompareService.ExportContext(fallbackSystem = mapOf(DS_TARGET to "厂商库"))
        val overview = env.service.buildOverviewRows(jobRow(), listOf(legacy), emptyMap(), ctx)
        assertEquals(101, overview[1].rowCount)         // matched 93 + extra 8
        assertEquals(1, overview[1].diffFromBase)
        assertEquals("厂商库", overview[1].systemName)  // 未登记所属系统回落数据源名
        assertEquals(0, overview[1].diffCount)          // 无差异明细计数
    }

    @Test
    fun `未完成目标的原因写明状态`() {
        val env = Env()
        val failed = targetRow().copy(status = "FAILED", error = "连接超时")
        val overview = env.service.buildOverviewRows(jobRow(), listOf(failed), emptyMap(),
            CompareService.ExportContext())
        assertTrue(overview[1].diffReason!!.contains("比对未完成(FAILED)"), overview[1].diffReason!!)
        assertTrue(overview[1].diffReason!!.contains("连接超时"))
    }

    // ---------- xlsx 结构 ----------

    @Test
    fun `总览有几条差异数据就跟着几个明细sheet每张装该系统全部差异`() {
        val env = Env()
        val jobId = env.seedDiffs()
        // 表注释/所属系统走真实取数路径:结构缓存 + table_system
        env.seedMeta(
            Triple(DS_BASE, "reservoir_base", "reservoir_base_info"),
            Triple(DS_TARGET, "reservoir_vendor", "t_reservoir_info"),
            systems = mapOf(ctxKey(DS_TARGET, "reservoir_vendor", "t_reservoir_info") to "厂商系统"))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            // 总览 1 个 + 总览里 1 条差异数据(1 个目标)对应 1 个明细 sheet
            assertEquals(2, wb.numberOfSheets)
            assertEquals("总览", wb.getSheetName(0))
            assertTrue(wb.getSheetName(1).startsWith("1_"), wb.getSheetName(1))  // 序号与总览行对应

            val overview = wb.getSheetAt(0)
            assertEquals(listOf("表中文名", "表英文名称", "所属系统", "条数", "数据最新更新时间",
                "与基准差", "匹配编码数", "差异条数", "差异原因"),
                (0..8).map { overview.getRow(0).getCell(it)?.stringCellValue ?: "" })
            assertEquals("表注释-reservoir_base_info", overview.getRow(1).getCell(0).stringCellValue)
            assertEquals("reservoir_base_info", overview.getRow(1).getCell(1).stringCellValue)
            assertEquals("基准库", overview.getRow(1).getCell(2).stringCellValue)  // 未登记 → 数据源名
            assertEquals(100.0, overview.getRow(1).getCell(3).numericCellValue)
            assertEquals("—", overview.getRow(1).getCell(4).stringCellValue)
            assertEquals("表注释-t_reservoir_info", overview.getRow(2).getCell(0).stringCellValue)
            assertEquals("t_reservoir_info", overview.getRow(2).getCell(1).stringCellValue)
            assertEquals("厂商系统", overview.getRow(2).getCell(2).stringCellValue)  // table_system 登记值
            assertEquals(101.0, overview.getRow(2).getCell(3).numericCellValue)
            assertEquals(1.0, overview.getRow(2).getCell(5).numericCellValue)   // 与基准差
            assertEquals(98.0, overview.getRow(2).getCell(6).numericCellValue)  // 匹配编码数
            assertEquals(3.0, overview.getRow(2).getCell(7).numericCellValue)   // 差异条数
            assertTrue(overview.getRow(2).getCell(8).stringCellValue.contains("行数相差 1"))

            // 明细 sheet:第 1 行单行上下文,第 2 行空行,第 3 行表头(字段名 + 说明)
            val detail = wb.getSheetAt(1)
            val context = detail.getRow(0).getCell(0).stringCellValue
            assertTrue(context.startsWith(
                "厂商系统 · reservoir_vendor.t_reservoir_info ← reservoir_base.reservoir_base_info"), context)
            assertTrue(context.contains("主键 id"), context)
            assertTrue(context.contains("目标 101 行 / 基准 100 行"), context)
            assertTrue(context.contains("缺失 1 条、多余 1 条、不一致 1 条"), context)  // 差异构成取自 compare_diff 明细
            assertEquals(3, detail.getRow(2).lastCellNum.toInt() - 1) // id/name/capacity + 说明
            assertEquals(null, detail.getRow(1))
            assertEquals(listOf("id", "name", "capacity", "说明"),
                (0..3).map { detail.getRow(2).getCell(it).stringCellValue })

            // 该系统的全部 3 条差异都在同一张 sheet 内,一格一个对象:
            // R001 字段级不一致 → 基准值原样、目标值只显示改动字段,说明逐字段标注「基准 → 目标」方向
            assertEquals("R001", detail.getRow(3).getCell(0).stringCellValue)
            // 单列一格一个对象:展示该字段的目标取值(与基准不一致的字段),基准值在「说明」里对照
            assertEquals("甲水库(改)", detail.getRow(3).getCell(1).stringCellValue)
            assertEquals("200", detail.getRow(3).getCell(2).stringCellValue)
            assertEquals("name: 基准「甲水库」→ 目标「甲水库(改)」;capacity: 基准「100」→ 目标「200」",
                detail.getRow(3).getCell(3).stringCellValue)
            // R002 缺失:只有基准行(整行快照),目标值列留空
            assertEquals("R002", detail.getRow(4).getCell(0).stringCellValue)
            assertEquals("乙水库", detail.getRow(4).getCell(1).stringCellValue)
            assertEquals("150", detail.getRow(4).getCell(2).stringCellValue)
            assertEquals("基准有目标无", detail.getRow(4).getCell(3).stringCellValue)
            // R900 多余:基准无此行,各列展示目标侧取值
            assertEquals("R900", detail.getRow(5).getCell(0).stringCellValue)
            assertEquals("厂区水库", detail.getRow(5).getCell(1).stringCellValue)
            assertEquals("80", detail.getRow(5).getCell(2).stringCellValue)
            assertEquals("目标有基准无", detail.getRow(5).getCell(3).stringCellValue)
            assertEquals(5, detail.lastRowNum)
        } finally {
            wb.close()
        }
    }

    @Test
    fun `差异行再多也只出一张明细sheet且全部列出`() {
        val env = Env()
        val jobId = env.repo.insertJob("大任务", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id"]""", 2)
        val targetId = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "reservoir_vendor", null, "t_reservoir_info")
        env.repo.updateTargetStats(targetId, 1000, 900, 500, 300, 0, 100, 0.5, 0.8, 1.0, 0.7)
        val diffCount = 1000
        env.repo.insertDiffs(jobId, targetId, (1..diffCount).map {
            CompareRepository.DiffInput("K$it", "对象$it", "MISSING",
                """[{"field":"id","base":"K$it","value":null}]""")
        })
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            // 总览 + 该系统唯一的明细 sheet(不因差异多而拆多张)
            assertEquals(2, wb.numberOfSheets)
            val detail = wb.getSheetAt(1)
            assertEquals(listOf("id", "说明"),
                (0..1).map { detail.getRow(2).getCell(it).stringCellValue })
            assertEquals("K1", detail.getRow(3).getCell(0).stringCellValue)
            assertEquals("基准有目标无", detail.getRow(3).getCell(1).stringCellValue)
            assertEquals("K$diffCount", detail.getRow(2 + diffCount).getCell(0).stringCellValue)
        } finally {
            wb.close()
        }
    }

    @Test
    fun `多个系统各出一张明细sheet序号与总览行对应`() {
        val env = Env()
        val jobId = env.repo.insertJob("双系统", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id","name"]""", 3)
        val first = env.repo.insertTarget(jobId, DS_TARGET, "厂商A", "db_a", null, "t_a")
        val second = env.repo.insertTarget(jobId, DS_TARGET, "厂商B", "db_b", null, "t_b")
        env.repo.updateTargetStats(first, 100, 101, 98, 2, 3, 0, 0.9, 1.0, 1.0, 0.95)
        env.repo.updateTargetStats(second, 100, 99, 99, 1, 0, 0, 0.99, 1.0, 1.0, 0.99)
        env.repo.insertDiffs(jobId, first, listOf(
            CompareRepository.DiffInput("A1", "甲", "MISSING", """[{"field":"id","base":"A1","value":null}]"""),
            CompareRepository.DiffInput("A2", "乙", "EXTRA", """[{"field":"id","base":null,"value":"A2"}]"""),
        ))
        env.repo.insertDiffs(jobId, second, listOf(
            CompareRepository.DiffInput("B1", "丙", "MISSING", """[{"field":"id","base":"B1","value":null}]"""),
        ))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            assertEquals(3, wb.numberOfSheets)  // 总览 + 两个系统各一张
            assertTrue(wb.getSheetName(1).startsWith("1_厂商A"), wb.getSheetName(1))
            assertTrue(wb.getSheetName(2).startsWith("2_厂商B"), wb.getSheetName(2))
            // 各自 sheet 只装自己的差异
            assertEquals("A1", wb.getSheetAt(1).getRow(3).getCell(0).stringCellValue)
            assertEquals("A2", wb.getSheetAt(1).getRow(4).getCell(0).stringCellValue)
            assertEquals("B1", wb.getSheetAt(2).getRow(3).getCell(0).stringCellValue)
        } finally {
            wb.close()
        }
    }
}
