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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

        /**
         * 预置结构缓存字段注释(命中缓存即不回源,供「字段中文/业务表字段中文」取数);
         * db/schema 与 seedMeta 同口径(schema 取库名)
         */
        fun seedColumns(dsId: Long, db: String, table: String, vararg columns: Pair<String, String?>) {
            metaCacheRepo.replaceColumns(dsId, db, db, table,
                columns.mapIndexed { i, (name, comment) ->
                    MetaCacheRepository.CachedColumn(i, name, "VARCHAR", "VARCHAR", 12, true, null, comment, false, 0, false)
                })
        }

        /** 造一个已完成任务 + 一个目标(基准 100 行 / 目标 101 行,缺失 1 / 多余 2 / 不一致 2 处) */
        fun seedDiffs(): Long {
            val jobId = repo.insertJob("水库比对", DS_BASE, "reservoir_base", null, "reservoir_base_info",
                "id", """["id","name","capacity"]""", 2)
            val targetId = repo.insertTarget(jobId, DS_TARGET, "厂商库", "reservoir_vendor", null, "t_reservoir_info")
            repo.updateTargetStats(targetId, 100, 101, 98, 1, 2, 2, 0.98, 0.99, 1.0, 0.99)
            // 总览「差异条数」为数量口径:缺失 1(R002)+ 多余 1(R900)= 2;
            // R001 纯字段值不一致、编码/名称不一致(属性差异)都不计
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
        // 老任务/未采集:数据最新更新时间为空(不再用「—」占位)
        assertEquals("", base.dataUpdatedAt)
    }

    @Test
    fun `总览数据最新更新时间取比对落库快照`() {
        val env = Env()
        val jobId = env.seedDiffs()
        val targetId = env.repo.listTargets(jobId).single().id
        // 比对执行时探测时间字段取 MAX 的快照,由仓储回写;导出只读快照
        env.repo.updateBaseDataUpdatedAt(jobId, "2026-09-01 12:00:00")
        env.repo.updateTargetDataUpdatedAt(targetId, "2026-09-02 08:30:00")
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val overview = wb.getSheetAt(0)
            assertEquals("2026-09-01 12:00:00", overview.getRow(1).getCell(4).stringCellValue)  // 基准表
            assertEquals("2026-09-02 08:30:00", overview.getRow(2).getCell(4).stringCellValue)  // 目标表
        } finally {
            wb.close()
        }
    }

    @Test
    fun `总览目标行按目标总行数计算与基准差并拼差异原因`() {
        val env = Env()
        val ctx = CompareService.ExportContext(
            comments = mapOf(ctxKey(DS_TARGET, "reservoir_vendor", "t_reservoir_info") to "水库信息"),
            systems = mapOf(ctxKey(DS_TARGET, "reservoir_vendor", "t_reservoir_info") to "厂商系统"),
            fallbackSystem = mapOf(DS_BASE to "基准库", DS_TARGET to "厂商库"))
        val overview = env.service.buildOverviewRows(jobRow(), listOf(targetRow()),
            mapOf(100L to CompareService.ObjectLevelDiffs(missing = 1, extra = 2, identity = 1)), ctx)

        val row = overview[1]
        assertEquals(100L, row.targetId)
        assertEquals("水库信息", row.tableComment)
        assertEquals("厂商系统", row.systemName)
        assertEquals(101, row.rowCount)
        assertEquals(1, row.diffFromBase)
        // 老任务三路未采集(NULL):匹配编码数按旧口径回落 matched_count;匹配对象数 = matched_count
        assertEquals(93, row.matchedCount)
        assertEquals(93, row.matchedTotal)
        // 差异条数 = 数量口径(缺失 + 多余):1 + 2 = 3;编码不一致(identity 1)是属性差异,不计
        assertEquals(3, row.diffCount)
        val reason = row.diffReason!!
        assertTrue(reason.contains("基准有目标无的对象 7 条"), reason)
        assertTrue(reason.contains("目标有基准无的对象 8 条"), reason)
        assertTrue(reason.contains("编码不一致的对象 1 条"), reason)
        assertTrue(reason.contains("行数相差 1"), reason)
        // 总览不含字段级差异统计(客户核对表口径,字段级看「字段级差异汇总」sheet),只保留对象级与行数口径
        assertFalse(reason.contains("字段不一致单元格"), reason)
        assertFalse(reason.contains("5 处"), reason)
    }

    @Test
    fun `仅字段级不一致时差异原因给定性说明且不附统计数字`() {
        val env = Env()
        // 对象级三项全零、无身份列空行,只有字段不一致(条数与基准一致,不触发行数差):不写「与基准完全一致」,给定性说明
        val fieldOnly = targetRow().copy(missingCount = 0, extraCount = 0, fieldMismatchCount = 5,
            targetCount = 100)
        val reason = env.service.buildOverviewRows(jobRow(), listOf(fieldOnly), emptyMap(),
            CompareService.ExportContext())[1].diffReason!!
        assertEquals("存在字段级不一致(见字段级差异汇总)", reason)
    }

    @Test
    fun `新任务匹配编码数取编码路命中数与匹配对象数分列`() {
        val env = Env()
        // 三路已采集:匹配编码数 = 编码路 80,匹配对象数 = 三路之和 93
        val fresh = targetRow().copy(codeMatchedCount = 80, nameMatchedCount = 11, aiMatchedCount = 2)
        val row = env.service.buildOverviewRows(jobRow(), listOf(fresh), emptyMap(),
            CompareService.ExportContext())[1]
        assertEquals(80, row.matchedCount)
        assertEquals(93, row.matchedTotal)
        // 基准行两列留空
        val base = env.service.buildOverviewRows(jobRow(), listOf(fresh), emptyMap(),
            CompareService.ExportContext())[0]
        assertEquals(null, base.matchedCount)
        assertEquals(null, base.matchedTotal)
    }

    @Test
    fun `差异原因透出身份列为空的行数且不误报完全一致`() {
        val env = Env()
        // 对象级三项全零(缺失/多余/字段不一致都没有)但有身份列为空的行:不得写「与基准完全一致」
        val noKey = targetRow().copy(missingCount = 0, extraCount = 0, fieldMismatchCount = 0,
            matchedCount = 100, targetCount = 106, noKeyRows = 6)
        val overview = env.service.buildOverviewRows(jobRow(), listOf(noKey), emptyMap(),
            CompareService.ExportContext())
        val reason = overview[1].diffReason!!
        assertTrue(reason.contains("身份列为空 6 行(仅按名称/大模型配对)"), reason)
        assertNotEquals("与基准完全一致", reason)

        // 无身份列为空行且三项全零:维持「与基准完全一致」
        val clean = targetRow().copy(missingCount = 0, extraCount = 0, fieldMismatchCount = 0)
        val cleanReason = env.service.buildOverviewRows(jobRow(), listOf(clean), emptyMap(),
            CompareService.ExportContext())[1].diffReason
        assertEquals("与基准完全一致", cleanReason)
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
                "与基准差", "匹配编码数", "匹配对象数", "差异条数", "差异原因"),
                (0..9).map { overview.getRow(0).getCell(it)?.stringCellValue ?: "" })
            assertEquals("表注释-reservoir_base_info", overview.getRow(1).getCell(0).stringCellValue)
            assertEquals("reservoir_base_info", overview.getRow(1).getCell(1).stringCellValue)
            assertEquals("基准库", overview.getRow(1).getCell(2).stringCellValue)  // 未登记 → 数据源名
            assertEquals(100.0, overview.getRow(1).getCell(3).numericCellValue)
            assertEquals("", overview.getRow(1).getCell(4).stringCellValue)  // 未采集时间快照 → 留空
            assertEquals("表注释-t_reservoir_info", overview.getRow(2).getCell(0).stringCellValue)
            assertEquals("t_reservoir_info", overview.getRow(2).getCell(1).stringCellValue)
            assertEquals("厂商系统", overview.getRow(2).getCell(2).stringCellValue)  // table_system 登记值
            assertEquals(101.0, overview.getRow(2).getCell(3).numericCellValue)
            assertEquals(1.0, overview.getRow(2).getCell(5).numericCellValue)   // 与基准差
            assertEquals(98.0, overview.getRow(2).getCell(6).numericCellValue)  // 匹配编码数
            assertEquals(2.0, overview.getRow(2).getCell(8).numericCellValue)   // 差异条数:缺失 1 + 多余 1(属性差异不计)
            assertTrue(overview.getRow(2).getCell(9).stringCellValue.contains("行数相差 1"))

            // 行级对比明细 sheet(固定第二个):首行即表头,一行一个「对象 × 比对目标」;末列差异类型
            val rowLevel = wb.getSheetAt(1)
            assertEquals(listOf("基准表英文名", "基准表中文名", "基准编码字段", "基准编码", "基准名称字段", "基准名称",
                "业务表英文名", "业务表中文名", "业务表编码字段", "业务表编码", "业务表名称字段", "业务表名称",
                "差异说明", "差异类型"),
                (0..13).map { rowLevel.getRow(0).getCell(it).stringCellValue })
            // R001 DIFF 双侧编码/名称一致(身份对齐键无差异)→ 不再列入行级对比明细,
            // 其 name/capacity 字段级不一致仍体现在下方明细 sheet
            // R002 缺失:业务侧编码/名称留空、差异类型「缺失」;R900 多余:基准侧留空、差异类型「多余」
            // 编码/名称字段列与取值无关恒填:基准侧 = 主键 id / 显示名未配置留空,业务侧无映射按同名回落
            assertEquals(listOf("reservoir_base_info", "表注释-reservoir_base_info", "id", "R002", "", "乙水库",
                "t_reservoir_info", "表注释-t_reservoir_info", "id", "", "", "", "基准有目标无", "缺失"),
                (0..13).map { rowLevel.getRow(1).getCell(it).stringCellValue })
            assertEquals(listOf("reservoir_base_info", "表注释-reservoir_base_info", "id", "", "", "",
                "t_reservoir_info", "表注释-t_reservoir_info", "id", "R900", "", "厂区水库", "目标有基准无", "多余"),
                (0..13).map { rowLevel.getRow(2).getCell(it).stringCellValue })
            assertEquals(2, rowLevel.lastRowNum)

            // 字段级差异汇总 sheet(固定第三个):首行即表头,一行一个「比对目标 × 基准字段」
            val fieldSummary = wb.getSheetAt(2)
            assertEquals(listOf("业务表英文名", "业务表中文名", "基准表字段", "字段中文", "业务表字段", "业务表字段中文",
                "差异数量", "缺失", "多余", "不一致"),
                (0..9).map { fieldSummary.getRow(0).getCell(it).stringCellValue })
            // 该目标缺失 1(R002)+ 多余 1(R900),name/capacity 各 1 次不一致,id 无不一致;
            // 差异数量 = 缺失 + 多余 + 不一致(缺失/多余为对象级,各字段同值);
            // 无显式映射(按名称自动匹配):业务表字段与基准字段同名;无字段注释时「字段中文/业务表字段中文」都留空
            fun summaryRow(row: Int) = (0..5).map { fieldSummary.getRow(row).getCell(it).stringCellValue } +
                (6..9).map { fieldSummary.getRow(row).getCell(it).numericCellValue }
            assertEquals(listOf("t_reservoir_info", "表注释-t_reservoir_info", "id", "", "id", "", 2.0, 1.0, 1.0, 0.0),
                summaryRow(1))
            assertEquals(listOf("t_reservoir_info", "表注释-t_reservoir_info", "name", "", "name", "", 3.0, 1.0, 1.0, 1.0),
                summaryRow(2))
            assertEquals(listOf("t_reservoir_info", "表注释-t_reservoir_info", "capacity", "", "capacity", "", 3.0, 1.0, 1.0, 1.0),
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

            // 该系统的全部差异都在同一张 sheet 内,一行一个「对象 × 字段」:
            // R001 有两个不一致字段(name/capacity)→ 展开成两行字段级明细
            assertEquals(prefix + listOf("R001", "甲水库", "name", "", "甲水库", "name", "", "甲水库(改)", "文本不一致"),
                (0..12).map { detail.getRow(1).getCell(it).stringCellValue })
            assertEquals(prefix + listOf("R001", "甲水库", "capacity", "", "100", "capacity", "", "200", "文本不一致"),
                (0..12).map { detail.getRow(2).getCell(it).stringCellValue })
            // R002 缺失 / R900 多余:整行缺失/多余按整行快照逐比对字段展开——
            // 缺失对象基准块照常填、业务表值留空;多余对象反之,差异原因说明方向
            assertEquals(prefix + listOf("R002", "乙水库", "id", "", "R002", "id", "", "", "基准有目标无"),
                (0..12).map { detail.getRow(3).getCell(it).stringCellValue })
            assertEquals(prefix + listOf("R002", "乙水库", "name", "", "乙水库", "name", "", "", "基准有目标无"),
                (0..12).map { detail.getRow(4).getCell(it).stringCellValue })
            assertEquals(prefix + listOf("R002", "乙水库", "capacity", "", "150", "capacity", "", "", "基准有目标无"),
                (0..12).map { detail.getRow(5).getCell(it).stringCellValue })
            assertEquals(prefix + listOf("R900", "厂区水库", "id", "", "", "id", "", "R900", "目标有基准无"),
                (0..12).map { detail.getRow(6).getCell(it).stringCellValue })
            assertEquals(prefix + listOf("R900", "厂区水库", "name", "", "", "name", "", "厂区水库", "目标有基准无"),
                (0..12).map { detail.getRow(7).getCell(it).stringCellValue })
            assertEquals(prefix + listOf("R900", "厂区水库", "capacity", "", "", "capacity", "", "80", "目标有基准无"),
                (0..12).map { detail.getRow(8).getCell(it).stringCellValue })
            assertEquals(8, detail.lastRowNum)

            // 差异格红底:DIFF 行标基准/业务两个取值格(其余格不标);
            // 缺失/多余字段级行只标缺失侧取值格与差异原因(缺失=业务表值,多余=基准表值)
            fun fillOf(row: Int, col: Int) = detail.getRow(row).getCell(col).cellStyle.fillPattern
            assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(1, 8))   // R001 name 基准值
            assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(1, 11))  // R001 name 业务值
            assertEquals(FillPatternType.NO_FILL, fillOf(1, 4))            // 对象编码不标
            assertEquals(FillPatternType.NO_FILL, fillOf(1, 12))           // 差异原因不标
            assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(2, 8))   // R001 capacity 基准值
            assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(2, 11))  // R001 capacity 业务值
            for (row in 3..5) {                                            // R002 缺失:业务表值 + 差异原因标红
                assertEquals(FillPatternType.NO_FILL, fillOf(row, 8), "R002 row $row 基准值不标")
                assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(row, 11), "R002 row $row 业务值")
                assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(row, 12), "R002 row $row 差异原因")
            }
            for (row in 6..8) {                                            // R900 多余:基准表值 + 差异原因标红
                assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(row, 8), "R900 row $row 基准值")
                assertEquals(FillPatternType.NO_FILL, fillOf(row, 11), "R900 row $row 业务值不标")
                assertEquals(FillPatternType.SOLID_FOREGROUND, fillOf(row, 12), "R900 row $row 差异原因")
            }
        } finally {
            wb.close()
        }
    }

    @Test
    fun `行级对比明细列对象级差异行且总览差异条数只看数量差异`() {
        val env = Env()
        val jobId = env.repo.insertJob("身份口径", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id","name","capacity"]""", 2, displayField = "name")
        val targetId = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "reservoir_vendor", null, "t_reservoir_info")
        env.repo.insertDiffs(jobId, targetId, listOf(
            // 编码一致,仅 capacity 不一致:不列入行级 sheet(字段级差异见明细 sheet)
            CompareRepository.DiffInput("R001", "甲", "DIFF",
                """[{"field":"id","base":"R001","value":null},{"field":"name","base":"甲","value":null},{"field":"capacity","base":"1","value":"2"}]"""),
            // 靠名称配上、编码不同:列入;差异说明只写编码差异,capacity 不一致不展开
            CompareRepository.DiffInput("R002", "乙", "DIFF",
                """[{"field":"id","base":"R002","value":"X002"},{"field":"name","base":"乙","value":null},{"field":"capacity","base":"3","value":"4"}]"""),
            // 编码一致、仅名称不同:属字段级差异,不列入(名称不是对象身份字段)
            CompareRepository.DiffInput("R003", "丙", "DIFF",
                """[{"field":"id","base":"R003","value":null},{"field":"name","base":"丙","value":"丙(改)"},{"field":"capacity","base":"5","value":"6"}]"""),
        ))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val rowLevel = wb.getSheetAt(1)
            // 只有「编码不同」的对象列入,差异类型「不一致」;编码/名称字段列恒填(基准 id/name,无映射业务侧同名)
            assertEquals(listOf("reservoir_base_info", "", "id", "R002", "name", "乙",
                "t_reservoir_info", "", "id", "X002", "name", "乙", "id: 基准「R002」→ 目标「X002」", "不一致"),
                (0..13).map { rowLevel.getRow(1).getCell(it).stringCellValue })
            assertEquals(1, rowLevel.lastRowNum)  // R001(仅字段差异)与 R003(仅名称不同)都不占行
            // 总览「差异条数」= 数量差异(缺失 + 多余)= 0:行级 sheet 那 1 行是编码不一致(属性差异),
            // 只在「行级对比明细」/明细 sheet 体现,不进总览数量口径
            assertEquals(0.0, wb.getSheetAt(0).getRow(2).getCell(8).numericCellValue)
        } finally {
            wb.close()
        }
    }

    @Test
    fun `明细sheet不落业务表无此字段的行只放数据差异`() {
        val env = Env()
        val jobId = env.repo.insertJob("缺列", DS_BASE, "reservoir_base", null, "reservoir_base_info",
            "id", """["id","name","capacity"]""", 2)
        val targetId = env.repo.insertTarget(jobId, DS_TARGET, "厂商库", "reservoir_vendor", null, "t_reservoir_info")
        env.repo.insertDiffs(jobId, targetId, listOf(
            // R001:唯一「差异」是业务表没有 name/capacity 两列(结构差异)→ 整对象不进明细 sheet
            CompareRepository.DiffInput("R001", "甲", "DIFF",
                """[{"field":"id","base":"R001","value":null},{"field":"name","base":"甲","value":"${CompareService.MISSING_COLUMN_MARK}"},{"field":"capacity","base":"100","value":"${CompareService.MISSING_COLUMN_MARK}"}]"""),
            // R002:name 是真实数据差异(保留);capacity 业务表无此列(该字段行丢弃)
            CompareRepository.DiffInput("R002", "乙", "DIFF",
                """[{"field":"id","base":"R002","value":null},{"field":"name","base":"乙","value":"乙(改)"},{"field":"capacity","base":"200","value":"${CompareService.MISSING_COLUMN_MARK}"}]"""),
        ))
        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val detail = wb.getSheetAt(5)
            // 只放基准表与业务表之间的数据差异:业务表无此字段的字段行不落,R001 因此整行不存在;
            // 最左定位列(未登记所属系统回落数据源名「厂商库」,表无注释留空)
            assertEquals(listOf("厂商库", "reservoir_vendor", "t_reservoir_info", "",
                "R002", "乙", "name", "", "乙", "name", "", "乙(改)", "文本不一致"),
                (0..12).map { detail.getRow(1).getCell(it).stringCellValue })
            assertEquals(1, detail.lastRowNum)  // 仅表头 + R002 一行
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
            assertEquals(1000.0, fieldSummary.getRow(1).getCell(6).numericCellValue)
            assertEquals(1000.0, fieldSummary.getRow(1).getCell(7).numericCellValue)
            assertEquals(0.0, fieldSummary.getRow(1).getCell(9).numericCellValue)
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
            assertEquals(3.0, fieldSummary.getRow(1).getCell(6).numericCellValue)  // 厂商A id: 2+1+0
            assertEquals(2.0, fieldSummary.getRow(3).getCell(6).numericCellValue)  // 厂商B id: 1+1+0
            assertEquals(4, fieldSummary.lastRowNum)
            // 行级对比明细跨目标全量列出:厂商A 3 行 + 厂商B 2 行,A9 在两个目标各出现一次
            val rowLevel = wb.getSheetAt(1)
            assertEquals(listOf("A1", "A2", "A9", "B1", "A9"),
                (1..5).map { rowLevel.getRow(it).let { r ->
                    // 基准侧编码在 EXTRA 行留空,业务侧编码在 MISSING 行留空,合并取非空侧
                    r.getCell(3).stringCellValue.ifEmpty { r.getCell(9).stringCellValue } } })
            assertEquals("t_a", rowLevel.getRow(3).getCell(6).stringCellValue)   // A9 属厂商A
            assertEquals("t_b", rowLevel.getRow(5).getCell(6).stringCellValue)   // A9 属厂商B
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

            // 行级对比明细「编码/名称字段」列:显式映射目标取连线到的目标列(A: aid/aname),无映射按同名回落(B: id/name)
            val rowLevel = wb.getSheetAt(1)
            assertEquals(listOf("reservoir_base_info", "", "id", "R002", "name", "乙水库",
                "t_a", "表注释-t_a", "aid", "", "aname", "", "基准有目标无", "缺失"),
                (0..13).map { rowLevel.getRow(1).getCell(it).stringCellValue })
            assertEquals(listOf("reservoir_base_info", "", "id", "", "name", "",
                "t_b", "表注释-t_b", "id", "R900", "name", "多余水库", "目标有基准无", "多余"),
                (0..13).map { rowLevel.getRow(2).getCell(it).stringCellValue })
            assertEquals(2, rowLevel.lastRowNum)
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
            // 左侧 5 列 + 两行表头冻结(滚动比对时不丢身份、基准上下文与表头)
            assertTrue(sheet.paneInformation.isFreezePane)
            assertEquals(5, sheet.paneInformation.verticalSplitLeftColumn.toInt())
            assertEquals(2, sheet.paneInformation.horizontalSplitTopRow.toInt())
            assertEquals(listOf("id", "name", "基准字段名", "基准字段中文", "基准表值",
                "厂商系统A业务表字段名", "厂商系统A业务表中文", "厂商系统A业务表值", "差异原因",
                "厂商库业务表字段名", "厂商库业务表中文", "厂商库业务表值", "差异原因"),
                (0..12).map { sheet.getRow(0).getCell(it).stringCellValue })
            // 第二行表头:各侧表定位 [库名][schema][表名](schema 为空省略该段)
            assertEquals("[reservoir_base][reservoir_base_info]", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals("[db_a][t_a]", sheet.getRow(1).getCell(5).stringCellValue)
            assertEquals("[db_b][t_b]", sheet.getRow(1).getCell(9).stringCellValue)

            fun row(r: Int) = (0..12).map { sheet.getRow(r).getCell(it)?.stringCellValue ?: "" }
            // R001:id 两侧一致(差异原因留空);name A 不一致、B 一致;
            // capacity A 未连线 → 「无此字段/未比对(无此字段)」,B 不一致
            assertEquals(listOf("R001", "甲水库", "id", "", "R001",
                "aid", "", "R001", "", "id", "", "R001", ""), row(2))
            assertEquals(listOf("R001", "甲水库", "name", "", "甲水库",
                "aname", "", "甲水库A", "文本不一致", "name", "", "甲水库", ""), row(3))
            assertEquals(listOf("R001", "甲水库", "capacity", "", "100",
                "无此字段", "", "", "未比对(无此字段)", "capacity", "", "999", "文本不一致"), row(4))
            // R002:A 整行缺失(已连字段写「基准有目标无」,capacity 未连线仍「无此字段」);B 无差异行视同一致(取值 = 基准值)
            assertEquals(listOf("R002", "乙水库", "id", "", "R002",
                "aid", "", "", "基准有目标无", "id", "", "R002", ""), row(5))
            assertEquals(listOf("R002", "乙水库", "name", "", "乙水库",
                "aname", "", "", "基准有目标无", "name", "", "乙水库", ""), row(6))
            assertEquals(listOf("R002", "乙水库", "capacity", "", "150",
                "无此字段", "", "", "未比对(无此字段)", "capacity", "", "150", ""), row(7))
            assertEquals(7, sheet.lastRowNum)
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
        // 字段注释走结构缓存:基准/业务两侧都预置,业务表字段中文取映射后目标列的注释
        env.seedColumns(DS_BASE, "reservoir_base", "reservoir_base_info",
            "id" to "编码", "name" to "名称", "capacity" to "容量")
        env.seedColumns(DS_TARGET, "reservoir_vendor", "t_reservoir_info",
            "rid" to "目标编码", "rname" to null)
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
            // 只列连了线的 id/name 两行(保持任务字段顺序),业务表字段 = 映射的目标列名,
            // 业务表字段中文 = 目标列注释(无注释留空,不回落字段名)
            assertEquals(listOf("id", "编码", "rid", "目标编码"),
                (2..5).map { fieldSummary.getRow(1).getCell(it).stringCellValue })
            assertEquals(listOf("name", "名称", "rname", ""),
                (2..5).map { fieldSummary.getRow(2).getCell(it).stringCellValue })
            assertEquals(2, fieldSummary.lastRowNum)
            // name 行:缺失 1 + 多余 0 + 不一致 1 = 差异数量 2
            assertEquals(2.0, fieldSummary.getRow(2).getCell(6).numericCellValue)
            assertEquals(1.0, fieldSummary.getRow(2).getCell(9).numericCellValue)
        } finally {
            wb.close()
        }
    }

    // ---------- 注释快照(V64):跑完断网(VPN 被挤掉)也能导出/出报告 ----------

    @Test
    fun `导出表中文名与字段中文优先读比对快照不依赖结构缓存`() {
        val env = Env()
        val jobId = env.seedDiffs()
        val targetId = env.repo.listTargets(jobId).single().id
        // 比对执行时采集的注释快照;不 seed 任何结构缓存,注释值与缓存路径不同即可证快照优先
        env.repo.updateBaseComments(jobId, "快照-基准表",
            """{"id":"快照-编码注释","name":"快照-名称注释","capacity":"快照-库容注释"}""")
        env.repo.updateTargetComments(targetId, "快照-目标表", """{"id":"快照-目标编码注释"}""")

        val out = ByteArrayOutputStream()
        env.service.exportDiff(jobId, out)

        val wb = XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
        try {
            val overview = wb.getSheetAt(0)
            assertEquals("快照-基准表", overview.getRow(1).getCell(0).stringCellValue)
            assertEquals("快照-目标表", overview.getRow(2).getCell(0).stringCellValue)
            // 字段级差异汇总:基准字段中文取基准表快照,业务表字段中文取目标表快照(该列无注释留空)
            val fieldSummary = wb.getSheetAt(2)
            assertEquals(listOf("id", "快照-编码注释", "id", "快照-目标编码注释"),
                (2..5).map { fieldSummary.getRow(1).getCell(it).stringCellValue })
            assertEquals(listOf("name", "快照-名称注释", "name", ""),
                (2..5).map { fieldSummary.getRow(2).getCell(it).stringCellValue })
        } finally {
            wb.close()
        }
    }

    @Test
    fun `报告问题字段排行中文字段名读快照且老任务离线兜底留空不抛异常`() {
        val env = Env()
        val jobId = env.seedDiffs()
        // 离线兜底:老任务无快照、无结构缓存(数据源指向不可达 MySQL,缓存优先回源失败静默降级)→ 注释留空
        val offline = env.service.report(jobId)
        assertEquals(listOf("capacity" to null, "name" to null),
            offline.fieldIssues.map { it.field to it.comment })

        // 比对执行时采集的字段注释快照 → 排行带中文名(未快照到的字段仍留空)
        env.repo.updateBaseComments(jobId, null, """{"name":"快照-名称注释"}""")
        val reported = env.service.report(jobId)
        assertEquals(listOf("capacity" to null, "name" to "快照-名称注释"),
            reported.fieldIssues.map { it.field to it.comment })
    }
}
