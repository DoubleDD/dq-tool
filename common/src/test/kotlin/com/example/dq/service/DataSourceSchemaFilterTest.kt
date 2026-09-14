package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows

/** 数据源库过滤白名单:存储往返、更新替换/清空、过滤语义 */
class DataSourceSchemaFilterTest {

    private lateinit var dsRepo: DataSourceRepository
    private lateinit var jdbc: Jdbc
    private lateinit var schemaStatRepo: SchemaStatRepository
    private lateinit var dataSourceService: DataSourceService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ds-schema-filter-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        dsRepo = DataSourceRepository(jdbc)
        schemaStatRepo = SchemaStatRepository(jdbc)
        val config = AppConfig(dataDir = Files.createTempDirectory("ds-schema-filter-test"))
        dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config, schemaStatRepo, MetaCacheRepository(jdbc))
    }

    private fun req(schemaFilter: List<String>?) =
        DataSourceRequest("库", "jdbc:mysql://localhost:1/db", "root", "p", null, null, schemaFilter = schemaFilter)

    @Test
    fun `创建与更新往返保留库过滤白名单`() {
        val id = dataSourceService.create(req(listOf("report_agent", " xxl_job ", "report_agent")))
        // 去空白 + 去重后落库
        assertEquals(listOf("report_agent", "xxl_job"), dsRepo.findById(id)!!.schemaFilter)
        // list 出库同样带回(秘密字段不出库但白名单不是秘密)
        assertEquals(listOf("report_agent", "xxl_job"), dataSourceService.list().single().schemaFilter)

        dataSourceService.update(id, req(listOf("smart_ugadp_v2")))
        assertEquals(listOf("smart_ugadp_v2"), dsRepo.findById(id)!!.schemaFilter)
    }

    @Test
    fun `空列表与 null 都归一为不过滤`() {
        val id = dataSourceService.create(req(listOf("a")))
        assertEquals(listOf("a"), dsRepo.findById(id)!!.schemaFilter)

        dataSourceService.update(id, req(emptyList()))
        assertNull(dsRepo.findById(id)!!.schemaFilter)

        dataSourceService.update(id, req(listOf("b")))
        dataSourceService.update(id, req(null))
        assertNull(dsRepo.findById(id)!!.schemaFilter)
    }

    @Test
    fun `库过滤规则 未配置白名单时默认排除系统库`() {
        val all = listOf("information_schema", "mysql", "report_agent", "sys", "xxl_job")
        val sys = setOf("information_schema", "mysql", "sys", "performance_schema")
        // null / 空名单 = 默认规则:全部业务库,排除系统库(与库过滤页签「系统库默认不勾选」一致)
        assertEquals(listOf("report_agent", "xxl_job"), MetadataService.applySchemaFilter(all, null, sys))
        assertEquals(listOf("report_agent", "xxl_job"), MetadataService.applySchemaFilter(all, emptyList(), sys))
        // 显式白名单只保留命中的库,保持方言返回顺序;系统库被显式勾选时照常返回
        assertEquals(
            listOf("mysql", "report_agent", "xxl_job"),
            MetadataService.applySchemaFilter(all, listOf("xxl_job", "mysql", "report_agent", "不存在"), sys)
        )
        // 全不命中得到空列表
        assertEquals(emptyList<String>(), MetadataService.applySchemaFilter(all, listOf("不存在"), sys))
        // 系统库名大小写不敏感(Oracle/DM 的 schema 名是大写、方言清单存小写)
        assertEquals(
            listOf("REPORT_AGENT"),
            MetadataService.applySchemaFilter(listOf("SYS", "REPORT_AGENT"), null, sys)
        )
    }

    @Test
    fun `多库方言能力标志仅 SQL Server 与 Kingbase 为真`() {
        // 白名单作用层级依赖该标志:多库方言过滤 databases,单库方言过滤 schemas
        val multi = setOf(com.example.dq.model.DbType.SQLSERVER, com.example.dq.model.DbType.KINGBASE)
        com.example.dq.model.DbType.entries.forEach { type ->
            assertEquals(type in multi, DialectFactory.get(type).supportsMultiDatabase(), type.name)
        }
    }

    @Test
    fun `库列表页单独更新白名单且不影响其他配置`() {
        val id = dataSourceService.create(req(null))
        dataSourceService.updateSchemaFilter(id, listOf("a", " b ", "a"))
        assertEquals(listOf("a", "b"), dsRepo.findById(id)!!.schemaFilter)
        // 只更新白名单,密码等其他字段不受影响
        assertEquals("p", dataSourceService.get(id).password)

        dataSourceService.updateSchemaFilter(id, null)
        assertNull(dsRepo.findById(id)!!.schemaFilter)
        assertThrows(IllegalArgumentException::class.java) {
            dataSourceService.updateSchemaFilter(9999L, listOf("a"))
        }
    }

    @Test
    fun `schema-stats 概览读取路径按白名单过滤旧缓存`() {
        val id = dataSourceService.create(req(listOf("report_agent", "xxl_job")))
        // 模拟白名单设置前已建立的旧缓存(含系统库)
        schemaStatRepo.replaceAll(id, null, listOf(
            SchemaStatRepository.CachedStat("information_schema", 10, 1000),
            SchemaStatRepository.CachedStat("report_agent", 5, 100),
            SchemaStatRepository.CachedStat("xxl_job", 8, 200),
        ))
        val meta = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc), schemaStatRepo, SchemaDocRepository(jdbc),
            MetaCacheRepository(jdbc))
        val stats = meta.listSchemaStats(id, null)
        assertEquals(listOf("report_agent", "xxl_job"), stats.map { it.name })
    }

    @Test
    fun `未配置白名单时概览读取路径排除系统库`() {
        // 与库过滤页签「系统库默认不勾选」保持一致:未配置白名单 = 默认规则(排除方言系统库)
        val id = dataSourceService.create(req(null))
        schemaStatRepo.replaceAll(id, null, listOf(
            SchemaStatRepository.CachedStat("information_schema", 10, 1000),
            SchemaStatRepository.CachedStat("mysql", 3, 300),
            SchemaStatRepository.CachedStat("report_agent", 5, 100),
        ))
        val meta = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc), schemaStatRepo, SchemaDocRepository(jdbc),
            MetaCacheRepository(jdbc))
        val stats = meta.listSchemaStats(id, null)
        assertEquals(listOf("report_agent"), stats.map { it.name })
    }
}
