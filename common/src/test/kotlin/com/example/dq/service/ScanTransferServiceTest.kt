package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanStatus
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 扫描记录导出/导入:JSON 往返内容保真、任务级去重幂等、数据源映射跳过、文件校验、单任务失败不中断整批 */
class ScanTransferServiceTest {

    /** 一套独立内存库环境,模拟一台机器(源/目标各一套即模拟跨实例) */
    private class Env {
        val scanRepo: ScanRepository
        val tagRepo: TagRepository
        val tableDocRepo: TableDocRepository
        val service: ScanTransferService
        private val dataSourceService: DataSourceService

        init {
            val ds = JdbcDataSource()
            ds.setURL("jdbc:h2:mem:scan-transfer-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
            SchemaInit.run(ds)
            val jdbc = Jdbc(ds)
            val dsRepo = DataSourceRepository(jdbc)
            scanRepo = ScanRepository(jdbc)
            tagRepo = TagRepository(jdbc)
            tableDocRepo = TableDocRepository(jdbc)
            val config = AppConfig(dataDir = Files.createTempDirectory("scan-transfer-test"))
            dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
                SchemaStatRepository(jdbc), MetaCacheRepository(jdbc))
            service = ScanTransferService(scanRepo, dsRepo, tagRepo, tableDocRepo)
        }

        fun createDs(name: String): Long =
            dataSourceService.create(
                DataSourceRequest(name, "jdbc:mysql://localhost:3306/db", "root", "p", null, null))
    }

    /** 在源库造一个 DONE 任务:含事件时间线、一张表、一个分段、一条字段统计 */
    private fun seedDoneJob(env: Env, dsId: Long): Long {
        val jobId = env.scanRepo.insertJob(dsId, null, "public", false,
            """[{"column":"name","values":["N/A"]}]""", 1, autoTag = true, workers = 4, genDoc = false)
        env.scanRepo.markJobRunning(jobId)
        env.scanRepo.incrementJobDoneTables(jobId)
        env.scanRepo.finishJob(jobId, ScanStatus.DONE, null)
        val tableId = env.scanRepo.insertScanTable(jobId, "t_user", 100L, 1024L, "用户表", "InnoDB")
        env.scanRepo.markTablePlanned(tableId, "id", false, null, 1)
        env.scanRepo.finishTable(tableId, ScanStatus.DONE, 100L, null)
        val chunkId = env.scanRepo.insertChunk(tableId, 0, "1", null, false)
        env.scanRepo.markChunkDone(chunkId, 100L, """[{"column":"name","nullCount":5}]""")
        env.scanRepo.insertScanColumn(tableId,
            ScanColumnView.of("name", "varchar(64)", "姓名", true, null, "", 100, 5, 3, 0))
        return jobId
    }

    @Test
    fun `导出导入往返保留任务事件表分段字段明细`() {
        val src = Env()
        val srcDsId = src.createDs("生产库")
        val srcJobId = seedDoneJob(src, srcDsId)
        val srcJob = src.scanRepo.findJob(srcJobId)!!

        val out = ByteArrayOutputStream()
        src.service.export(listOf(srcJobId), out)
        val json = out.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"app\" : \"dq-tool-scans\""), json)
        assertTrue(json.contains("t_user"), json)

        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        val result = dst.service.importJson(ByteArrayInputStream(out.toByteArray()),
            mapOf("生产库" to dstDsId))
        assertEquals(1, result.total)
        assertEquals(1, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertTrue(result.warnings.isEmpty())

        // 任务本体:状态/开关/时间透传,创建时间与源一致(列表按时间排序不受影响)
        val dstJob = dst.scanRepo.listJobs(dstDsId, null, null).single()
        assertEquals(ScanStatus.DONE, dstJob.status)
        assertEquals("public", dstJob.schemaName)
        assertNull(dstJob.dbName)
        assertEquals(1, dstJob.totalTables)
        assertEquals(1, dstJob.doneTables)
        assertEquals(true, dstJob.autoTag)
        assertEquals(4, dstJob.workers)
        assertEquals(false, dstJob.genDoc)
        assertEquals("""[{"column":"name","values":["N/A"]}]""", dstJob.nullRulesJson)
        assertEquals(srcJob.createdAt, dstJob.createdAt)
        // 事件时间线:PENDING/RUNNING/DONE 三条
        assertEquals(listOf(ScanStatus.PENDING, ScanStatus.RUNNING, ScanStatus.DONE),
            dst.scanRepo.listJobEvents(dstJob.id).map { it.status })
        // 表级结果
        val dstTable = dst.scanRepo.listScanTables(dstJob.id).single()
        assertEquals("t_user", dstTable.tableName)
        assertEquals(ScanStatus.DONE, dstTable.status)
        assertEquals("id", dstTable.chunkKey)
        assertEquals(100L, dstTable.totalRows)
        assertEquals(1024L, dstTable.sizeBytes)
        assertEquals("用户表", dstTable.comment)
        assertEquals("InnoDB", dstTable.storageInfo)
        assertEquals(1, dstTable.totalChunks)
        // 分段:统计行数与 col_stats JSON 原文透传
        val dstChunk = dst.scanRepo.listChunks(dstTable.id).single()
        assertEquals(ScanStatus.DONE, dstChunk.status)
        assertEquals(100L, dstChunk.rowCount)
        assertEquals("""[{"column":"name","nullCount":5}]""", dstChunk.colStatsJson)
        // 字段统计:派生的有值数/有值率由视图模型重算
        val dstCol = dst.scanRepo.listScanColumns(dstTable.id).single()
        assertEquals("name", dstCol.columnName)
        assertEquals("varchar(64)", dstCol.columnType)
        assertEquals("姓名", dstCol.columnComment)
        assertEquals(100L, dstCol.totalRows)
        assertEquals(5L, dstCol.nullCount)
        assertEquals(3L, dstCol.emptyCount)
        assertEquals(92L, dstCol.valueCount)
    }

    @Test
    fun `导出导入携带表标记与表描述`() {
        val src = Env()
        val srcDsId = src.createDs("生产库")
        val srcJobId = seedDoneJob(src, srcDsId)
        // 源实例的标注数据:USER 标记定义(含颜色/描述)+ 打标关系 + AI 生成的表描述;空表系统标记只导名字不入 tagDefs
        val tag = src.tagRepo.create("核心表", "#F56C6C", "核心业务表")
        src.tagRepo.ensureTableTag(tag.id, srcDsId, "", "public", "t_user")
        src.tagRepo.findEmptyTag()?.let { src.tagRepo.ensureTableTag(it.id, srcDsId, "", "public", "t_user") }
        src.tableDocRepo.upsert(srcDsId, "", "public", "t_user", "用户主表,存登录账号", "deepseek-chat")

        val out = ByteArrayOutputStream()
        src.service.export(listOf(srcJobId), out)
        val json = out.toString(Charsets.UTF_8)
        assertTrue(json.contains("核心表"), json)
        assertTrue(json.contains("空表"), json)
        assertTrue(json.contains("用户主表,存登录账号"), json)

        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        val result = dst.service.importJson(ByteArrayInputStream(out.toByteArray()),
            mapOf("生产库" to dstDsId))
        assertEquals(1, result.imported)
        assertTrue(result.warnings.isEmpty())

        // 标记定义按名创建(颜色/描述透传),打标关系落库;空表系统标记定义不动、关系由导入补上
        val dstTag = dst.tagRepo.findByName("核心表")!!
        assertEquals("#F56C6C", dstTag.color)
        assertEquals("核心业务表", dstTag.description)
        val dstTags = dst.tagRepo.tableTagsBySchema(dstDsId, "", "public")["t_user"]!!
        assertEquals(listOf("核心表", "空表"), dstTags.map { it.name }.sorted())
        // 表描述 upsert 落库,model 记 import
        assertEquals("用户主表,存登录账号", dst.tableDocRepo.findBySchema(dstDsId, "", "public")["t_user"])
    }

    @Test
    fun `重复导入不产生重复打标 描述幂等覆盖`() {
        val src = Env()
        val srcDsId = src.createDs("生产库")
        seedDoneJob(src, srcDsId)
        val tag = src.tagRepo.create("核心表", "#F56C6C", null)
        src.tagRepo.ensureTableTag(tag.id, srcDsId, "", "public", "t_user")
        src.tableDocRepo.upsert(srcDsId, "", "public", "t_user", "用户主表", "deepseek-chat")
        val out = ByteArrayOutputStream()
        src.service.export(emptyList(), out)

        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        dst.service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to dstDsId))
        // 第二次导入:任务判重跳过,标注数据不重复不报错
        val second = dst.service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to dstDsId))
        assertEquals(1, second.skipped)
        val dstTags = dst.tagRepo.tableTagsBySchema(dstDsId, "", "public")["t_user"]!!
        assertEquals(listOf("核心表"), dstTags.map { it.name })
    }

    @Test
    fun `重复导入同一文件被去重跳过`() {
        val src = Env()
        seedDoneJob(src, src.createDs("生产库"))
        val out = ByteArrayOutputStream()
        src.service.export(emptyList(), out) // 空列表 = 导出全部

        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        val first = dst.service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to dstDsId))
        assertEquals(1, first.imported)
        val second = dst.service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to dstDsId))
        assertEquals(1, second.total)
        assertEquals(0, second.imported)
        assertEquals(1, second.skipped)
        assertEquals(1, dst.scanRepo.listJobs(dstDsId, null, null).size)
    }

    @Test
    fun `未映射或映射为0的数据源跳过 预检返回分布与本机清单`() {
        val src = Env()
        seedDoneJob(src, src.createDs("生产库"))
        val out = ByteArrayOutputStream()
        src.service.export(emptyList(), out)

        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        // 预检:文件数据源分布 + 同名自动匹配 + 本机数据源清单
        val preview = dst.service.preview(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, preview.totalJobs)
        assertEquals(1, preview.datasources.size)
        assertEquals("生产库", preview.datasources[0].datasourceName)
        assertEquals(1, preview.datasources[0].jobs)
        assertEquals(dstDsId, preview.datasources[0].matchedDatasourceId)
        assertEquals(listOf(dstDsId), preview.localDatasources.map { it.id })

        // 无映射:全部跳过
        val noMapping = dst.service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, noMapping.total)
        assertEquals(0, noMapping.imported)
        assertEquals(1, noMapping.skipped)
        // 映射为 0:显式跳过
        val zero = dst.service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to 0L))
        assertEquals(1, zero.skipped)
        // 映射到不存在的数据源:跳过并记 warning
        val ghost = dst.service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to 999L))
        assertEquals(1, ghost.skipped)
        assertEquals(1, ghost.warnings.size)
        assertEquals(0, dst.scanRepo.listJobs(null, null, null).size)
    }

    @Test
    fun `单个任务失败不中断整批`() {
        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        // 一个正常任务 + 一个创建时间格式非法的任务
        val json = """
            {"app":"dq-tool-scans","version":1,"exportedAt":"t",
             "jobs":[
               {"datasourceName":"生产库","schemaName":"s1","status":"DONE","createdAt":"2026-08-01T10:00:00"},
               {"datasourceName":"生产库","schemaName":"s2","status":"DONE","createdAt":"不是时间"}
             ]}
        """.trimIndent()
        val result = dst.service.importJson(ByteArrayInputStream(json.toByteArray()),
            mapOf("生产库" to dstDsId))
        assertEquals(2, result.total)
        assertEquals(1, result.imported)
        assertEquals(1, result.failed)
        assertEquals(1, result.warnings.size)
        assertEquals(listOf("s1"), dst.scanRepo.listJobs(dstDsId, null, null).map { it.schemaName })
    }

    @Test
    fun `GBK 转存的导出文件可导入且中文保真`() {
        val src = Env()
        seedDoneJob(src, src.createDs("生产库"))
        val out = ByteArrayOutputStream()
        src.service.export(emptyList(), out)
        // 模拟客户用记事本另存(中文 Windows ANSI=GBK)后的文件
        val gbk = out.toString(Charsets.UTF_8).toByteArray(charset("GBK"))

        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        // 预检与正式导入都要能解析 GBK
        assertEquals(1, dst.service.preview(ByteArrayInputStream(gbk)).totalJobs)
        val result = dst.service.importJson(ByteArrayInputStream(gbk), mapOf("生产库" to dstDsId))
        assertEquals(1, result.imported)
        val dstJob = dst.scanRepo.listJobs(dstDsId, null, null).single()
        val dstTable = dst.scanRepo.listScanTables(dstJob.id).single()
        assertEquals("用户表", dstTable.comment)
        assertEquals("姓名", dst.scanRepo.listScanColumns(dstTable.id).single().columnComment)
    }

    @Test
    fun `未知字段不拦截导入`() {
        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        // 模拟未来版本追加的字段:旧版本软件导入时不应被拦
        val json = """
            {"app":"dq-tool-scans","version":1,"exportedAt":"t","futureTop":{"x":1},
             "jobs":[{"datasourceName":"生产库","schemaName":"s1","status":"DONE",
                      "createdAt":"2026-08-01T10:00:00","newJobField":true}]}
        """.trimIndent()
        val result = dst.service.importJson(ByteArrayInputStream(json.toByteArray()),
            mapOf("生产库" to dstDsId))
        assertEquals(1, result.imported)
    }

    @Test
    fun `错误 app 标识版本与非 JSON 抛参数错误`() {
        val dst = Env()
        val badApp = """{"app":"other","version":1,"exportedAt":"t","jobs":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            dst.service.importJson(ByteArrayInputStream(badApp.toByteArray()))
        }
        val badVersion = """{"app":"dq-tool-scans","version":2,"exportedAt":"t","jobs":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            dst.service.importJson(ByteArrayInputStream(badVersion.toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            dst.service.preview(ByteArrayInputStream("这不是 JSON".toByteArray()))
        }
    }
}
