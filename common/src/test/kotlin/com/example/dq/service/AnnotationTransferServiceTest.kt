package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 标记与描述数据导出/导入:JSON 往返、标记按名合并、数据源名匹配跳过、文件校验、重复导入幂等 */
class AnnotationTransferServiceTest {

    /** 一套独立内存库环境,模拟一台机器(源/目标各一套即模拟跨实例) */
    private class Env {
        val tagRepo: TagRepository
        val tableDocRepo: TableDocRepository
        private val dataSourceService: DataSourceService
        val service: AnnotationTransferService

        init {
            val ds = JdbcDataSource()
            ds.setURL("jdbc:h2:mem:annotation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
            SchemaInit.run(ds)
            val jdbc = Jdbc(ds)
            val dsRepo = DataSourceRepository(jdbc)
            tagRepo = TagRepository(jdbc)
            tableDocRepo = TableDocRepository(jdbc)
            val config = AppConfig(dataDir = Files.createTempDirectory("annotation-transfer-test"))
            dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
                SchemaStatRepository(jdbc), MetaCacheRepository(jdbc))
            service = AnnotationTransferService(tagRepo, tableDocRepo, dsRepo)
        }

        fun createDs(name: String): Long =
            dataSourceService.create(
                DataSourceRequest(name, "jdbc:mysql://localhost:3306/db", "root", "p", null, null))
    }

    @Test
    fun `导出导入往返保留标记定义表标记与表描述`() {
        val src = Env()
        val dsId = src.createDs("生产库")
        val core = src.tagRepo.create("核心表", "#F5222D", "核心业务表")
        val log = src.tagRepo.create("日志表", "#52C41A", null)
        src.tagRepo.ensureTableTag(core.id, dsId, "", "public", "users")
        src.tagRepo.ensureTableTag(log.id, dsId, "", "public", "login_log")
        src.tableDocRepo.upsert(dsId, "", "public", "users", "用户主表说明", "model-x")

        val out = ByteArrayOutputStream()
        src.service.export(out)
        val json = out.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"app\" : \"dq-tool-annotations\""), json)
        assertTrue(json.contains("核心表"), json)

        val dst = Env()
        val dstDsId = dst.createDs("生产库")
        val result = dst.service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, result.tagsCreated)
        assertEquals(0, result.tagsUpdated)
        assertEquals(2, result.tableTagsAdded)
        assertEquals(0, result.tableTagsSkipped)
        assertEquals(1, result.docsUpserted)
        assertEquals(0, result.docsSkipped)

        // 标记定义(含描述)落库
        val imported = dst.tagRepo.findByName("核心表")!!
        assertEquals("#F5222D", imported.color)
        assertEquals("核心业务表", imported.description)
        // 表-标记关联按数据源名对齐到本机数据源 id
        val tableTags = dst.tagRepo.tableTagsBySchema(dstDsId, "", "public")
        assertEquals(listOf("核心表"), tableTags["users"]!!.map { it.name })
        assertEquals(listOf("日志表"), tableTags["login_log"]!!.map { it.name })
        // 表描述落库
        assertEquals("用户主表说明", dst.tableDocRepo.findBySchema(dstDsId, "", "public")["users"])

        // 重复导入幂等:关系不重复,描述被同内容覆盖
        val again = dst.service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(0, again.tagsCreated)
        assertEquals(2, again.tagsUpdated)
        assertEquals(2, dst.tagRepo.tableTagsBySchema(dstDsId, "", "public").values.flatten().size)
    }

    @Test
    fun `数据源名不同时按显式映射导入 映射为0强制跳过`() {
        val src = Env()
        val srcDsId = src.createDs("生产库")
        val core = src.tagRepo.create("核心表", "#F5222D", null)
        src.tagRepo.ensureTableTag(core.id, srcDsId, "", "public", "users")
        src.tableDocRepo.upsert(srcDsId, "", "public", "users", "用户主表说明", "m")
        val out = ByteArrayOutputStream()
        src.service.export(out)

        // 预检:聚合出文件里的数据源分布
        val preview = src.service.preview(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, preview.tags)
        assertEquals(1, preview.datasources.size)
        assertEquals("生产库", preview.datasources[0].datasourceName)
        assertEquals(1, preview.datasources[0].tableTags)
        assertEquals(1, preview.datasources[0].tableDocs)

        val dst = Env()
        val localId = dst.createDs("本地生产")  // 同一数据源,不同命名:按名匹配会落空
        // 无映射:表级行全部跳过
        val noMapping = dst.service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, noMapping.tableTagsSkipped)
        assertEquals(1, noMapping.docsSkipped)
        // 显式映射:导入到本地数据源
        val mapped = dst.service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to localId))
        assertEquals(1, mapped.tableTagsAdded)
        assertEquals(0, mapped.tableTagsSkipped)
        assertEquals(1, mapped.docsUpserted)
        assertEquals(listOf("核心表"), dst.tagRepo.tableTagsBySchema(localId, "", "public")["users"]!!.map { it.name })
        assertEquals("用户主表说明", dst.tableDocRepo.findBySchema(localId, "", "public")["users"])
        // 映射为 0:强制跳过(即使存在同名数据源)
        val dst2 = Env()
        dst2.createDs("生产库")
        val skipped = dst2.service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to 0L))
        assertEquals(1, skipped.tableTagsSkipped)
        assertEquals(1, skipped.docsSkipped)
    }

    @Test
    fun `标记按名称合并更新颜色与描述`() {
        val dst = Env()
        dst.createDs("生产库")
        dst.tagRepo.create("核心表", "#OLD", "旧描述")

        val json = """
            {"app":"dq-tool-annotations","version":1,"exportedAt":"t",
             "tags":[
               {"name":"核心表","color":"#NEW","description":"新描述"},
               {"name":"新标记","color":"#0F0","description":null}
             ],
             "tableTags":[], "tableDocs":[]}
        """.trimIndent()
        val result = dst.service.importJson(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, result.tagsCreated)
        assertEquals(1, result.tagsUpdated)

        val merged = dst.tagRepo.findByName("核心表")!!
        assertEquals("#NEW", merged.color)
        assertEquals("新描述", merged.description)
        assertEquals("新标记", dst.tagRepo.findByName("新标记")!!.name)
    }

    @Test
    fun `找不到同名数据源或标记的表级行跳过并计数`() {
        val dst = Env()
        dst.createDs("生产库")
        dst.tagRepo.create("核心表", "#F00", null)

        val json = """
            {"app":"dq-tool-annotations","version":1,"exportedAt":"t",
             "tags":[],
             "tableTags":[
               {"datasourceName":"生产库","dbName":"","schemaName":"public","tableName":"users","tagName":"核心表"},
               {"datasourceName":"不存在的库","dbName":"","schemaName":"public","tableName":"users","tagName":"核心表"},
               {"datasourceName":"生产库","dbName":"","schemaName":"public","tableName":"users","tagName":"幽灵标记"}
             ],
             "tableDocs":[
               {"datasourceName":"生产库","dbName":"","schemaName":"public","tableName":"users","description":"说明"},
               {"datasourceName":"不存在的库","dbName":"","schemaName":"public","tableName":"users","description":"说明"}
             ]}
        """.trimIndent()
        val result = dst.service.importJson(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, result.tableTagsAdded)
        assertEquals(2, result.tableTagsSkipped)
        assertEquals(1, result.docsUpserted)
        assertEquals(1, result.docsSkipped)
    }

    @Test
    fun `错误 app 标识版本与非 JSON 抛参数错误`() {
        val dst = Env()
        val badApp = """{"app":"other","version":1,"exportedAt":"t","tags":[],"tableTags":[],"tableDocs":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            dst.service.importJson(ByteArrayInputStream(badApp.toByteArray()))
        }
        val badVersion = """{"app":"dq-tool-annotations","version":2,"exportedAt":"t","tags":[],"tableTags":[],"tableDocs":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            dst.service.importJson(ByteArrayInputStream(badVersion.toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            dst.service.importJson(ByteArrayInputStream("这不是 JSON".toByteArray()))
        }
    }

    @Test
    fun `系统空表标记不参与导出与覆盖`() {
        val src = Env()
        // 源库只有系统「空表」标记时,tags 为空
        val out = ByteArrayOutputStream()
        src.service.export(out)
        assertTrue(out.toString(Charsets.UTF_8).contains("\"tags\" : [ ]"), out.toString(Charsets.UTF_8))

        // 导入文件里的「空表」定义不覆盖系统标记
        val dst = Env()
        val emptyBefore = dst.tagRepo.findEmptyTag()!!
        val json = """
            {"app":"dq-tool-annotations","version":1,"exportedAt":"t",
             "tags":[{"name":"${emptyBefore.name}","color":"#000","description":"想覆盖"}],
             "tableTags":[], "tableDocs":[]}
        """.trimIndent()
        val result = dst.service.importJson(ByteArrayInputStream(json.toByteArray()))
        assertEquals(0, result.tagsCreated)
        assertEquals(0, result.tagsUpdated)
        val emptyAfter = dst.tagRepo.findEmptyTag()!!
        assertEquals(emptyBefore.color, emptyAfter.color)
    }
}
