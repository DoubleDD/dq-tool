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
import org.apache.poi.ss.usermodel.FillPatternType
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
            // 总览 + 行级对比明细 + 字段级差异汇总 + 数据级字段对比汇总 + 列级对比明细 + 总览里 1 条差异数据(1 个目标)对应 1 个明细 sheet
            assertEquals(6, wb.numberOfSheets)
            assertEquals("总览", wb.getSheetName(0))
            assertEquals("行级对比明细", wb.getSheetName(1))
            assertEquals("字段级差异汇总", wb.getSheetName(2))
            assertEquals("数据级字段对比差异总览", wb.getSheetName(3))
            assertEquals("列级对比明细", wb.getSheetName(4))
            assertTrue(wb.getSheetName(5).startsWith("1_t_reservoir_info"), wb.getSheetName(5))  // 序号_表名_数据源名

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

            // 行级对比明细 sheet(固定第二个):首行即表头,一行一个「对象 × 比对目标」;末列差异类型
            val rowLevel = wb.getSheetAt(1)
            assertEquals(listOf("基准表英文名", "基准表中文名", "基准编码", "基准名称",
                "业务表英文名", "业务表中文名", "业务表编码", "业务表名称", "差异说明", "差异类型"),
                (0..9).map { rowLevel.getRow(0).getCell(it).stringCellValue })
            // R001 DIFF 双侧编码/名称一致(身份对齐键无差异)→ 不再列入行级对比明细,
            // 其 name/capacity 字段级不一致仍体现在下方明细 sheet
            // R002 缺失:业务侧编码/名称留空、差异类型「缺失」;R900 多余:基准侧留空、差异类型「多余」
            assertEquals(listOf("reservoir_base_info", "表注释-reservoir_base_info", "R002", "乙水库",
                "t_reservoir_info", "表注释-t_reservoir_info", "", "", "基准有目标无", "缺失"),
                (0..9).map { rowLevel.getRow(1).getCell(it).stringCellValue })
            assertEquals(listOf("reservoir_base_info", "表注释-reservoir_base_info", "", "",
                "t_reservoir_info", "表注释-t_reservoir_info", "R900", "厂区水库", "目标有基准无", "多余"),
                (0..9).map { rowLevel.getRow(2).getCell(it).stringCellValue })
            assertEquals(2, rowLevel.lastRowNum)

            // 字段级差异汇总 sheet(固定第三个):首行即表头,一行一个「比对目标 × 基准字段」
            val fieldSummary = wb.getSheetAt(2)
            assertEquals(listOf("业务表英文名", "业务表中文名", "基准表字段", "字段中文", "业务表字段",
                "差异数量", "缺失", "多余", "不一致"),
                (0..8).map { fieldSummary.getRow(0).getCell(it).stringCellValue })
            // 该目标缺失 1(R002)+ 多余 1(R900),name/capacity 各 1 次不一致,id 无不一致;
            // 差异数量 = 缺失 + 多余 + 不一致(缺失/多余为对象级,各字段同值);
            // 无显式映射(按名称自动匹配):业务表字段与基准字段同名
            fun summaryRow(row: Int) = (0..4).map { fieldSummary.getRow(row).getCell(it).stringCellValue } +
                (5..8).map { fieldSummary.getRow(row).getCell(it).numericCellValue }
            assertEquals(listOf("t_reservoir_info", "表注释-t_reservoir_info", "id", "", "id", 2.0, 1.0, 1.0, 0.0),
                summaryRow(1))
            assertEquals(listOf("t_reservoir_info", "表注释-t_reservoir_info", "name", "", "name", 3.0, 1.0, 1.0, 1.0),
                summaryRow(2))
            assertEquals(listOf("t_reservoir_info", "表注释-t_reservoir_info", "capacity", "", "capacity", 3.0, 1.0, 1.0, 1.0),
                summaryRow(3))
            assertEquals(3, fieldSummary.lastRowNum)

            // 明细 sheet:首行即表头(无上下文/图例行)
            // (最左定位列 业务系统名称/库/表名/表中文名 + 对象编码/名称 + 基准/业务两块各三列 + 差异原因;
            //  MySQL 单库方言不出「模式」列)
            val detail = wb.getSheetAt(5)
            val prefix = listOf("厂商系统", "reservoir_vendor", "t_reservoir_info", "表注释-t_reservoir_info")
            assertEquals(listOf("业务系统名称", "库", "表名", "表中文名", "id", "对象名称", "基准表字段", "字段中文", "基准表值",
                "业务表字段名", "业务表中文", "业务表值", "差异原因"),
                (0..12).map { detail.getRow(0).getCell(it).stringCellValue })

            // 该系统的全部差异都在同一张 sheet 内,一行一个「对象 × 不一致字段」:
            // R001 有两个不一致字段(name/capacity)→ 展开成两行字段级明细
            assertEquals(prefix + listOf("R001", "甲水库", "name", "", "甲水库", "name", "", "甲水库(改)", "文本不一致"),
                (0..12).map { detail.getRow(1).getCell(it).stringCellValue })
            assertEquals(prefix + listOf("R001", "甲水库", "capacity", "", "100", "capacity", "", "200", "文本不一致"),
                (0..12).map { detail.getRow(2).getCell(it).stringCellValue })
            // R002 缺失 / R900 多余:一对象一行,字段六列留空,差异原因说明方向
            assertEquals(prefix + listOf("R002", "乙水库", "", "", "", "", "", "", "基准有目标无"),
                (0..12).map { detail.getRow(3).getCell(it).stringCellValue })
            assertEquals(prefix + listOf("R900", "厂区水库", "", "", "", "", "", "", "目标有基准无"),
                (0..12).map { detail.getRow(4).getCell(it).stringCellValue })
            assertEquals(4, detail.lastRowNum)

            // 差异格红底:DIFF 行标基准/业务两个取值格(其余格不标),缺失/多余整行标红
            fun fillOf(row: Int, col: Int) = detail.getRow(row).getCell(col).cellStyle.fillPattern
            assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(1, 8))   // R001 name 基准值
            assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(1, 11))  // R001 name 业务值
            assertEquals(FillPatternType.NO_FILL, fillOf(1, 4))            // 对象编码不标
            assertEquals(FillPatternType.NO_FILL, fillOf(1, 12))           // 差异原因不标
            assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(2, 8))   // R001 capacity 基准值
            assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(2, 11))  // R001 capacity 业务值
            for (c in 0..12) {                                             // R002 缺失 / R900 多余整行标红
                assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(3, c), "R002 col $c")
                assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(4, c), "R900 col $c")
            }
        } finally {
            wb.close()
        }
    }

    @Test
    fun `行级对比明细只列身份层面差异且差异说明只写编码名称`() {
        val env = Env()
        val jobId = env.repo.insertJob("身份口径", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id","name","capacity"]""", 2, displayField = "name")
        val targetId = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "reservoir_vendor", null, "t_reservoir_info")
        env.repo.insertDiffs(jobId, targetId, listOf(
            // 编码/名称双侧一致,仅 capacity 不一致:不列入行级 sheet(字段级差异见明细 sheet)
            CompareRepository.DiffInput("R001", "甲", "DIFF",
                """[{"field":"id","base":"R001","value":null},{"field":"name","base":"甲","value":null},{"field":"capacity","base":"1","value":"2"}]"""),
            // 靠名称配上、编码不同:列入;差异说明只写编码差异,capacity 不一致不展开
            CompareRepository.DiffInput("R002", "乙", "DIFF",
                """[{"field":"id","base":"R002","value":"X002"},{"field":"name","base":"乙","value":null},{"field":"capacity","base":"3","value":"4"}]"""),
            // 编码一致、名称不同:列入;差异说明只写名称差异
            CompareRepository.DiffInput("R003", "丙", "DIFF",
                """[{"field":"id","base":"R003","value":null},{"field":"name","base":"丙","value":"丙(改)"},{"field":"capacity","base":"5","value":"6"}]"""),
        ))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val rowLevel = wb.getSheetAt(1)
            // 身份字段不一致 → 差异类型「不一致」
            assertEquals(listOf("reservoir_base_info", "", "R002", "乙",
                "t_reservoir_info", "", "X002", "乙", "id: 基准「R002」→ 目标「X002」", "不一致"),
                (0..9).map { rowLevel.getRow(1).getCell(it).stringCellValue })
            assertEquals(listOf("reservoir_base_info", "", "R003", "丙",
                "t_reservoir_info", "", "R003", "丙(改)", "name: 基准「丙」→ 目标「丙(改)」", "不一致"),
                (0..9).map { rowLevel.getRow(2).getCell(it).stringCellValue })
            assertEquals(2, rowLevel.lastRowNum)  // R001 身份一致,不占行
        } finally {
            wb.close()
        }
    }

    @Test
    fun `明细sheet目标缺列行业务侧字段名中文值都留空`() {
        val env = Env()
        val jobId = env.repo.insertJob("缺列", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id","name"]""", 2)
        val targetId = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "reservoir_vendor", null, "t_reservoir_info")
        env.repo.insertDiffs(jobId, targetId, listOf(
            CompareRepository.DiffInput("R001", "甲", "DIFF",
                """[{"field":"id","base":"R001","value":null},{"field":"name","base":"甲","value":"${CompareService.MISSING_COLUMN_MARK}"}]"""),
        ))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val detail = wb.getSheetAt(5)
            // 基准侧字段名/值照常,业务侧字段名/中文/值三格留空,差异原因写「业务表无此字段」;
            // 最左定位列(未登记所属系统回落数据源名「厂商库」,表无注释留空)
            assertEquals(listOf("厂商库", "reservoir_vendor", "t_reservoir_info", "",
                "R001", "甲", "name", "", "甲", "", "", "", "业务表无此字段"),
                (0..12).map { detail.getRow(1).getCell(it).stringCellValue })
            assertEquals(1, detail.lastRowNum)
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
            // 总览 + 行级对比明细 + 字段级差异汇总 + 数据级字段对比汇总 + 列级对比明细 + 该系统唯一的明细 sheet(不因差异多而拆多张)
            assertEquals(6, wb.numberOfSheets)
            // 字段级差异汇总:唯一比对字段 id,1000 条缺失(多余/不一致为 0)
            val fieldSummary = wb.getSheetAt(2)
            assertEquals("id", fieldSummary.getRow(1).getCell(2).stringCellValue)
            assertEquals(1000.0, fieldSummary.getRow(1).getCell(5).numericCellValue)
            assertEquals(1000.0, fieldSummary.getRow(1).getCell(6).numericCellValue)
            assertEquals(0.0, fieldSummary.getRow(1).getCell(8).numericCellValue)
            assertEquals(1, fieldSummary.lastRowNum)
            val detail = wb.getSheetAt(5)
            assertEquals(listOf("业务系统名称", "库", "表名", "表中文名",
                "id", "对象名称", "基准表字段", "字段中文", "基准表值",
                "业务表字段名", "业务表中文", "业务表值", "差异原因"),
                (0..12).map { detail.getRow(0).getCell(it).stringCellValue })
            assertEquals("K1", detail.getRow(1).getCell(4).stringCellValue)
            assertEquals("基准有目标无", detail.getRow(1).getCell(12).stringCellValue)
            assertEquals("K$diffCount", detail.getRow(diffCount).getCell(4).stringCellValue)
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
            CompareRepository.DiffInput("A9", "同", "MISSING", """[{"field":"id","base":"A9","value":null}]"""),
        ))
        env.repo.insertDiffs(jobId, second, listOf(
            CompareRepository.DiffInput("B1", "丙", "MISSING", """[{"field":"id","base":"B1","value":null}]"""),
            CompareRepository.DiffInput("A9", "同", "EXTRA", """[{"field":"id","base":null,"value":"A9"}]"""),
        ))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            assertEquals(7, wb.numberOfSheets)  // 总览 + 行级对比明细 + 字段级差异汇总 + 数据级字段对比汇总 + 列级对比明细 + 两个系统各一张
            assertTrue(wb.getSheetName(5).startsWith("1_t_a_厂商A"), wb.getSheetName(5))
            assertTrue(wb.getSheetName(6).startsWith("2_t_b_厂商B"), wb.getSheetName(6))
            // 各自 sheet 只装自己的差异(首行即表头,数据从第 2 行起;对象编码在定位列之后第 5 列)
            assertEquals("A1", wb.getSheetAt(5).getRow(1).getCell(4).stringCellValue)
            assertEquals("A2", wb.getSheetAt(5).getRow(2).getCell(4).stringCellValue)
            assertEquals("B1", wb.getSheetAt(6).getRow(1).getCell(4).stringCellValue)
            // 字段级差异汇总按目标分块:厂商A(缺失 2 + 多余 1)、厂商B(缺失 1 + 多余 1)各两个比对字段
            val fieldSummary = wb.getSheetAt(2)
            assertEquals(listOf("t_a", "t_a", "t_b", "t_b"),
                (1..4).map { fieldSummary.getRow(it).getCell(0).stringCellValue })
            assertEquals(3.0, fieldSummary.getRow(1).getCell(5).numericCellValue)  // 厂商A id: 2+1+0
            assertEquals(2.0, fieldSummary.getRow(3).getCell(5).numericCellValue)  // 厂商B id: 1+1+0
            assertEquals(4, fieldSummary.lastRowNum)
            // 行级对比明细跨目标全量列出:厂商A 3 行 + 厂商B 2 行,A9 在两个目标各出现一次
            val rowLevel = wb.getSheetAt(1)
            assertEquals(listOf("A1", "A2", "A9", "B1", "A9"),
                (1..5).map { rowLevel.getRow(it).let { r ->
                    // 基准侧编码在 EXTRA 行留空,业务侧编码在 MISSING 行留空,合并取非空侧
                    r.getCell(2).stringCellValue.ifEmpty { r.getCell(6).stringCellValue } } })
            assertEquals("t_a", rowLevel.getRow(3).getCell(4).stringCellValue)   // A9 属厂商A
            assertEquals("t_b", rowLevel.getRow(5).getCell(4).stringCellValue)   // A9 属厂商B
            assertEquals(5, rowLevel.lastRowNum)
        } finally {
            wb.close()
        }
    }

    @Test
    fun `数据级字段对比差异总览一行一条数据按系统给字段数统计`() {
        val env = Env()
        val jobId = env.repo.insertJob("水库比对", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id","name","capacity"]""", 3, "name")
        // A 显式映射只连 id/name(capacity 未连线 = 未比对,不计入 A 的对比字段数),B 无映射(按同名自动匹配)
        val targetA = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "db_a", null, "t_a",
            """{"id":"aid","name":"aname"}""")
        val targetB = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "db_b", null, "t_b")
        env.repo.updateTargetStats(targetA, 100, 101, 98, 1, 0, 1, 0.98, 0.99, 1.0, 0.99)
        env.repo.updateTargetStats(targetB, 100, 100, 99, 0, 1, 1, 0.99, 1.0, 1.0, 0.99)
        env.repo.insertDiffs(jobId, targetA, listOf(
            CompareRepository.DiffInput("R001", "甲水库", "DIFF",
                """[{"field":"id","base":"R001","value":null,"matched":true},{"field":"name","base":"甲水库","value":"甲水库A","matched":false},{"field":"capacity","base":"100","value":"«字段缺失»","matched":false}]"""),
            CompareRepository.DiffInput("R002", "乙水库", "MISSING",
                """[{"field":"id","base":"R002","value":null},{"field":"name","base":"乙水库","value":null},{"field":"capacity","base":"150","value":null}]"""),
        ))
        env.repo.insertDiffs(jobId, targetB, listOf(
            CompareRepository.DiffInput("R001", "甲水库", "DIFF",
                """[{"field":"id","base":"R001","value":null,"matched":true},{"field":"name","base":"甲水库","value":null,"matched":true},{"field":"capacity","base":"100","value":"999","matched":false}]"""),
            // 多余对象不属基准侧任何行,不在本表展开
            CompareRepository.DiffInput("R900", "多余水库", "EXTRA",
                """[{"field":"id","base":null,"value":"R900"}]"""),
        ))
        // 所属系统:A 登记「厂商系统A」,B 未登记回落数据源名「厂商库」
        env.seedMeta(Triple(DS_TARGET, "db_a", "t_a"), Triple(DS_TARGET, "db_b", "t_b"),
            systems = mapOf(ctxKey(DS_TARGET, "db_a", "t_a") to "厂商系统A"))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            assertEquals("数据级字段对比差异总览", wb.getSheetName(3))
            val sheet = wb.getSheetAt(3)
            assertEquals(listOf("id", "name", "基准表字段数",
                "厂商系统A对比字段数", "厂商系统A相同字段数", "厂商系统A不同字段数",
                "厂商库对比字段数", "厂商库相同字段数", "厂商库不同字段数"),
                (0..8).map { sheet.getRow(0).getCell(it).stringCellValue })

            fun num(r: Int, c: Int) = sheet.getRow(r).getCell(c).numericCellValue
            // R001:A 比对 2(未连 capacity,其「字段缺失」不计)、不同 1(name)、相同 1;B 比对 3、不同 1(capacity)、相同 2
            assertEquals("R001", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals("甲水库", sheet.getRow(1).getCell(1).stringCellValue)
            assertEquals(listOf(3.0, 2.0, 1.0, 1.0, 3.0, 2.0, 1.0), (2..8).map { num(1, it) })
            // R002:在 A 整行缺失 → A 比对 2、不同 2、相同 0;B 无差异行视同完全一致(比对 3、相同 3、不同 0)
            assertEquals("R002", sheet.getRow(2).getCell(0).stringCellValue)
            assertEquals(listOf(3.0, 2.0, 0.0, 2.0, 3.0, 3.0, 0.0), (2..8).map { num(2, it) })
            assertEquals(2, sheet.lastRowNum)
        } finally {
            wb.close()
        }
    }

    @Test
    fun `列级对比明细按系统并排逐字段取值且左侧五列冻结`() {
        val env = Env()
        val jobId = env.repo.insertJob("水库比对", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id","name","capacity"]""", 3, "name")
        // A 显式映射只连 id/name(capacity 未连线 = 未比对,行里 A 侧写「无此字段」),B 无映射(按同名自动匹配)
        val targetA = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "db_a", null, "t_a",
            """{"id":"aid","name":"aname"}""")
        val targetB = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "db_b", null, "t_b")
        env.repo.updateTargetStats(targetA, 100, 101, 98, 1, 0, 1, 0.98, 0.99, 1.0, 0.99)
        env.repo.updateTargetStats(targetB, 100, 100, 99, 0, 1, 1, 0.99, 1.0, 1.0, 0.99)
        env.repo.insertDiffs(jobId, targetA, listOf(
            CompareRepository.DiffInput("R001", "甲水库", "DIFF",
                """[{"field":"id","base":"R001","value":null,"matched":true},{"field":"name","base":"甲水库","value":"甲水库A","matched":false},{"field":"capacity","base":"100","value":"«字段缺失»","matched":false}]"""),
            CompareRepository.DiffInput("R002", "乙水库", "MISSING",
                """[{"field":"id","base":"R002","value":null},{"field":"name","base":"乙水库","value":null},{"field":"capacity","base":"150","value":null}]"""),
        ))
        env.repo.insertDiffs(jobId, targetB, listOf(
            CompareRepository.DiffInput("R001", "甲水库", "DIFF",
                """[{"field":"id","base":"R001","value":null,"matched":true},{"field":"name","base":"甲水库","value":null,"matched":true},{"field":"capacity","base":"100","value":"999","matched":false}]"""),
            // 多余对象不属基准侧任何行,不在本表展开
            CompareRepository.DiffInput("R900", "多余水库", "EXTRA",
                """[{"field":"id","base":null,"value":"R900"}]"""),
        ))
        // 所属系统:A 登记「厂商系统A」,B 未登记回落数据源名「厂商库」
        env.seedMeta(Triple(DS_TARGET, "db_a", "t_a"), Triple(DS_TARGET, "db_b", "t_b"),
            systems = mapOf(ctxKey(DS_TARGET, "db_a", "t_a") to "厂商系统A"))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            assertEquals("列级对比明细", wb.getSheetName(4))
            val sheet = wb.getSheetAt(4)
            // 左侧 5 列冻结(横向滚动时身份与基准列不跟随)
            assertTrue(sheet.paneInformation.isFreezePane)
            assertEquals(5, sheet.paneInformation.verticalSplitLeftColumn.toInt())
            assertEquals(listOf("id", "name", "基准字段名", "基准字段中文", "基准表值",
                "厂商系统A业务表字段名", "厂商系统A业务表中文", "厂商系统A业务表值", "差异原因",
                "厂商库业务表字段名", "厂商库业务表中文", "厂商库业务表值", "差异原因"),
                (0..12).map { sheet.getRow(0).getCell(it).stringCellValue })

            fun row(r: Int) = (0..12).map { sheet.getRow(r).getCell(it)?.stringCellValue ?: "" }
            // R001:id 两侧一致(差异原因留空);name A 不一致、B 一致;
            // capacity A 未连线 → 「无此字段/未比对(无此字段)」,B 不一致
            assertEquals(listOf("R001", "甲水库", "id", "", "R001",
                "aid", "", "R001", "", "id", "", "R001", ""), row(1))
            assertEquals(listOf("R001", "甲水库", "name", "", "甲水库",
                "aname", "", "甲水库A", "文本不一致", "name", "", "甲水库", ""), row(2))
            assertEquals(listOf("R001", "甲水库", "capacity", "", "100",
                "无此字段", "", "", "未比对(无此字段)", "capacity", "", "999", "文本不一致"), row(3))
            // R002:A 整行缺失(已连字段写「基准有目标无」,capacity 未连线仍「无此字段」);B 无差异行视同一致(取值 = 基准值)
            assertEquals(listOf("R002", "乙水库", "id", "", "R002",
                "aid", "", "", "基准有目标无", "id", "", "R002", ""), row(4))
            assertEquals(listOf("R002", "乙水库", "name", "", "乙水库",
                "aname", "", "", "基准有目标无", "name", "", "乙水库", ""), row(5))
            assertEquals(listOf("R002", "乙水库", "capacity", "", "150",
                "无此字段", "", "", "未比对(无此字段)", "capacity", "", "150", ""), row(6))
            assertEquals(6, sheet.lastRowNum)
        } finally {
            wb.close()
        }
    }

    @Test
    fun `字段级差异汇总只列与业务表有连线的字段并显示业务表字段`() {
        val env = Env()
        val jobId = env.repo.insertJob("映射比对", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id","name","capacity"]""", 2)
        // 显式字段映射(第三步人工连线):capacity 未连线 → 汇总 sheet 不再列出
        val targetId = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "reservoir_vendor", null,
            "t_reservoir_info", """{"id":"rid","name":"rname"}""")
        env.repo.updateTargetStats(targetId, 100, 101, 98, 1, 0, 1, 0.98, 0.99, 1.0, 0.99)
        env.repo.insertDiffs(jobId, targetId, listOf(
            // capacity 的「字段缺失」差异在明细里存在,但因未连线不进汇总 sheet
            CompareRepository.DiffInput("R001", "甲水库", "DIFF",
                """[{"field":"id","base":"R001","value":null},{"field":"name","base":"甲水库","value":"甲水库(改)"},{"field":"capacity","base":"100","value":"«字段缺失»"}]"""),
            CompareRepository.DiffInput("R002", "乙水库", "MISSING",
                """[{"field":"id","base":"R002","value":null},{"field":"name","base":"乙水库","value":null}]"""),
        ))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val fieldSummary = wb.getSheetAt(2)
            // 只列连了线的 id/name 两行(保持任务字段顺序),业务表字段 = 映射的目标列名
            assertEquals(listOf("id", "rid"),
                listOf(fieldSummary.getRow(1).getCell(2).stringCellValue,
                    fieldSummary.getRow(1).getCell(4).stringCellValue))
            assertEquals(listOf("name", "rname"),
                listOf(fieldSummary.getRow(2).getCell(2).stringCellValue,
                    fieldSummary.getRow(2).getCell(4).stringCellValue))
            assertEquals(2, fieldSummary.lastRowNum)
            // name 行:缺失 1 + 多余 0 + 不一致 1 = 差异数量 2
            assertEquals(2.0, fieldSummary.getRow(2).getCell(5).numericCellValue)
            assertEquals(1.0, fieldSummary.getRow(2).getCell(8).numericCellValue)
        } finally {
            wb.close()
        }
    }
}
