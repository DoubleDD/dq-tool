package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanImportResult
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
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 并发导入回归:同一导出文件两个线程同时导入(两个浏览器页签同时拉取/拉取与手工导入并行),
 * 修复前会出现 ①双方同时通过判重 → 重复扫描任务 ②标记按名「先查后建」竞态 → tag_def 唯一键
 * 「Unique index or primary key violation」导致任务导入失败(局域网同步报「主键重复」)。
 */
class TransferImportConcurrencyTest {

    private class Env {
        val scanRepo: ScanRepository
        val tagRepo: TagRepository
        val tableDocRepo: TableDocRepository
        val service: ScanTransferService
        val tagService: TagService
        private val dataSourceService: DataSourceService

        init {
            val ds = JdbcDataSource()
            ds.setURL("jdbc:h2:mem:transfer-import-conc-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
            SchemaInit.run(ds)
            val jdbc = Jdbc(ds)
            val dsRepo = DataSourceRepository(jdbc)
            scanRepo = ScanRepository(jdbc)
            tagRepo = TagRepository(jdbc)
            tableDocRepo = TableDocRepository(jdbc)
            val config = AppConfig(dataDir = Files.createTempDirectory("transfer-import-conc"))
            dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
                SchemaStatRepository(jdbc), MetaCacheRepository(jdbc))
            service = ScanTransferService(scanRepo, dsRepo, tagRepo, tableDocRepo)
            tagService = TagService(tagRepo, dsRepo)
        }

        fun createDs(name: String): Long =
            dataSourceService.create(
                DataSourceRequest(name, "jdbc:mysql://localhost:3306/db", "root", "p", null, null))
    }

    private fun seedDoneJob(env: Env, dsId: Long): Long {
        val jobId = env.scanRepo.insertJob(dsId, null, "public", false, null, 1)
        env.scanRepo.markJobRunning(jobId)
        env.scanRepo.incrementJobDoneTables(jobId)
        env.scanRepo.finishJob(jobId, ScanStatus.DONE, null)
        val tableId = env.scanRepo.insertScanTable(jobId, "t_user", 100L, 1024L, "用户表", "InnoDB")
        env.scanRepo.markTablePlanned(tableId, "id", false, null, 1)
        env.scanRepo.finishTable(tableId, ScanStatus.DONE, 100L, null)
        env.scanRepo.insertScanColumn(tableId,
            ScanColumnView.of("name", "varchar(64)", "姓名", true, null, "", 100, 5, 3, 0))
        return jobId
    }

    @Test
    fun `并发导入同一文件仅一个导入成功另一个判重跳过 无唯一键冲突`() {
        val src = Env()
        val srcDsId = src.createDs("生产库")
        seedDoneJob(src, srcDsId)
        val tag = src.tagRepo.create("核心表", "#F56C6C", null)
        src.tagRepo.ensureTableTag(tag.id, srcDsId, "", "public", "t_user")
        val out = ByteArrayOutputStream()
        src.service.export(emptyList(), out)
        val bytes = out.toByteArray()

        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        val latch = CountDownLatch(1)
        val results = arrayOfNulls<ScanImportResult>(2)
        val threads = (0 until 2).map { i ->
            Thread {
                latch.await()
                results[i] = dst.service.importJson(ByteArrayInputStream(bytes), mapOf("生产库" to dstDsId))
            }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }

        // 全局只导入成功一次,另一个必须判重跳过;任何一方都不得出现唯一键冲突类失败
        assertEquals(1, results.map { it!!.imported }.sum(),
            "两个线程都导入成功说明判重竞态未消除: ${results.map { it!!.imported to it.skipped }}")
        assertEquals(1, results.map { it!!.skipped }.sum())
        for (r in results) {
            assertEquals(0, r!!.failed, "导入失败 warnings=${r.warnings}")
            assertTrue(r.warnings.none { it.contains("violation", ignoreCase = true) }, "${r.warnings}")
        }
        assertEquals(1, dst.scanRepo.listJobs(dstDsId, null, null).size, "并发导入不得产生重复任务")
        assertTrue(dst.tagRepo.findByName("核心表") != null)
    }

    /**
     * 手工新建标记(标记管理页)与扫描记录导入并发:
     * 修复前一方会撞 tag_def.name 唯一键——导入侧任务计失败、手工侧裸「主键冲突」500;
     * 修复后手工侧要么先建成(无异常),要么按重名语义报「标记名称已存在」,导入侧一律成功采用。
     */
    @Test
    fun `手工新建标记与扫描导入并发 不互撞唯一键`() {
        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        val rounds = 10
        for (i in 0 until rounds) {
            val tagName = "竞态标记-$i"
            // 携带全新标记的扫描记录文件(库名逐轮不同,避开任务判重)
            val json = """
                {"app":"dq-tool-scans","version":1,"exportedAt":"t",
                 "tagDefs":[{"name":"$tagName","color":"#F56C6C"}],
                 "jobs":[{"datasourceName":"生产库","schemaName":"s$i","status":"DONE","createdAt":"2026-08-01T10:00:00",
                          "tables":[{"tableName":"t1","status":"DONE","tags":["$tagName"]}]}]}
            """.trimIndent().toByteArray()

            val latch = CountDownLatch(1)
            var importResult: ScanImportResult? = null
            var importError: Exception? = null
            var createError: Exception? = null
            var createOk = false
            val importThread = Thread {
                try {
                    latch.await()
                    importResult = dst.service.importJson(ByteArrayInputStream(json), mapOf("生产库" to dstDsId))
                } catch (e: Exception) {
                    importError = e
                }
            }
            val createThread = Thread {
                try {
                    latch.await()
                    dst.tagService.create(tagName, "#409EFF")
                    createOk = true
                } catch (e: Exception) {
                    createError = e
                }
            }
            importThread.start()
            createThread.start()
            latch.countDown()
            importThread.join()
            createThread.join()

            // 断言全部在主线程做(子线程里抛断言错误只会静默杀死线程)
            assertEquals(null, importError, "第 $i 轮导入线程不应抛异常")
            val r = importResult!!
            assertEquals(0, r.failed, "第 $i 轮导入失败 warnings=${r.warnings}")
            assertTrue(r.warnings.none { it.contains("violation", ignoreCase = true) }, "${r.warnings}")
            when {
                // 手工先建成:无异常,导入侧采用既有标记
                createOk -> { /* 合法胜出 */ }
                // 导入先建成:手工侧必须按重名语义报错,绝不许裸唯一键冲突冒出
                else -> assertTrue(
                    createError is IllegalStateException && createError!!.message!!.contains("标记名称已存在"),
                    "第 $i 轮手工新建异常类型/消息不合预期: ${createError?.javaClass?.name}: ${createError?.message}")
            }
            assertEquals(1, dst.tagRepo.listAll().count { it.name == tagName }, "第 $i 轮同名标记必须只有一个")
        }
    }
}
