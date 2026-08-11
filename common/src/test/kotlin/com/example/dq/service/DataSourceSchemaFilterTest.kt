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
    fun `库列表过滤语义`() {
        val all = listOf("information_schema", "mysql", "report_agent", "sys", "xxl_job")
        // null / 空名单不过滤
        assertEquals(all, MetadataService.applySchemaFilter(all, null))
        assertEquals(all, MetadataService.applySchemaFilter(all, emptyList()))
        // 非空名单只保留命中的库,保持方言返回顺序;名单里不存在的库不产生条目
        assertEquals(
            listOf("report_agent", "xxl_job"),
            MetadataService.applySchemaFilter(all, listOf("xxl_job", "report_agent", "不存在"))
        )
        // 全不命中得到空列表
        assertEquals(emptyList<String>(), MetadataService.applySchemaFilter(all, listOf("不存在")))
    }

    @Test
    fun `多库方言能力标志仅 SQL Server 为真`() {
        // 白名单作用层级依赖该标志:多库方言过滤 databases,单库方言过滤 schemas
        assertEquals(true, com.example.dq.dialect.SqlServerDialect().supportsMultiDatabase())
        com.example.dq.model.DbType.entries.filter { it != com.example.dq.model.DbType.SQLSERVER }.forEach { type ->
            assertEquals(false, DialectFactory.get(type).supportsMultiDatabase(), type.name)
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
}
