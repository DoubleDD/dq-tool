package com.example.dq.service

import com.example.dq.dialect.DialectFactory
import com.example.dq.dialect.PostgresDialect
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.TableStat
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.MetaSyncRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ConnectException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.TimeUnit

/**
 * 元数据批量同步(H2 内存库,SchemaInit 全量迁移 + 真实 Repo/MetadataService 组装):
 * 同步后 meta_table/meta_column/meta_index/meta_schema_column/meta_column_count/schema_stat 落齐;
 * 业务库断开后浏览接口走缓存降级可用;改表结构再同步覆盖更新;
 * 单个数据源连不上记失败不中断整批;参数校验(400/409);重启残留置失败;取消语义。
 *
 * 打桩方式同 RelationInferFlowTest:DataSourceService 返回业务 H2 库真实连接;
 * 方言经 mockkObject(DialectFactory) 换成 H2PgDialect(PostgresDialect 的 pg_class 系元数据
 * 查询改成 JDBC DatabaseMetaData,其余 listColumns/listIndexes/countColumns/listSchemaColumns
 * 继承 AbstractDialect 的 JDBC 元数据实现,H2 均可跑)
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MetaSyncServiceTest {

    companion object {
        private const val DS_ID = 1L
        private const val BAD_DS_ID = 2L
        private const val SCHEMA = "PUBLIC"
    }

    /** 测试方言:PG 口径中依赖 pg_catalog 的元数据查询改写为 JDBC DatabaseMetaData(H2 可跑) */
    private class H2PgDialect : PostgresDialect() {
        override fun listSchemas(conn: Connection): List<String> {
            val schemas = ArrayList<String>()
            conn.metaData.schemas.use { rs ->
                while (rs.next()) {
                    val s = rs.getString("TABLE_SCHEM")
                    if (!s.equals("INFORMATION_SCHEMA", ignoreCase = true)) schemas.add(s)
                }
            }
            return schemas.sorted()
        }

        override fun listTables(conn: Connection, schema: String): List<TableStat> {
            val tables = ArrayList<TableStat>()
            conn.metaData.getTables(null, schema, null, arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    tables.add(TableStat(rs.getString("TABLE_NAME"), null, null,
                        rs.getString("REMARKS") ?: "", ""))
                }
            }
            return tables.sortedBy { it.name }
        }

        override fun countTablesBySchema(conn: Connection): Map<String, Int> =
            listSchemas(conn).associateWith { listTables(conn, it).size }

        override fun sumSizeBySchema(conn: Connection): Map<String, Long> = emptyMap()
    }

    private lateinit var metaSyncRepo: MetaSyncRepository
    private lateinit var metaCacheRepo: MetaCacheRepository
    private lateinit var schemaStatRepo: SchemaStatRepository
    private lateinit var dataSourceService: DataSourceService
    private lateinit var metadata: MetadataService
    private lateinit var syncService: MetaSyncService
    private lateinit var bizUrl: String

    /** 连接失败标记记录(kind 断言用) */
    private val connFailures = ArrayList<Triple<Long, String, String>>()

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:meta-sync-cfg-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        metaSyncRepo = MetaSyncRepository(jdbc)
        metaCacheRepo = MetaCacheRepository(jdbc)
        schemaStatRepo = SchemaStatRepository(jdbc)

        bizUrl = "jdbc:h2:mem:meta-sync-biz-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        biz { st ->
            st.execute("CREATE TABLE t1 (id INT PRIMARY KEY, name VARCHAR(50))")
            st.execute("COMMENT ON COLUMN t1.name IS '姓名'")
            st.execute("CREATE TABLE t2 (code VARCHAR(20))")
        }

        connFailures.clear()
        dataSourceService = mockk()
        every { dataSourceService.get(DS_ID) } returns DataSourceConfig().apply {
            name = "测试库"; dbType = DbType.POSTGRESQL
        }
        every { dataSourceService.get(BAD_DS_ID) } returns DataSourceConfig().apply {
            name = "离线库"; dbType = DbType.POSTGRESQL
        }
        stubBizOnline()
        // 坏数据源:连接立即失败(连接级)
        every { dataSourceService.getConnection(BAD_DS_ID, null) } throws
            SQLException("Communications link failure", ConnectException("Connection refused"))
        every { dataSourceService.get(999L) } throws IllegalArgumentException("数据源不存在: 999")
        every { dataSourceService.markConnFailure(any(), any(), any()) } answers {
            connFailures.add(Triple(firstArg(), secondArg(), thirdArg()))
        }
        justRun { dataSourceService.markConnRecovered(any()) }

        mockkObject(DialectFactory)
        every { DialectFactory.get(DbType.POSTGRESQL) } returns H2PgDialect()

        metadata = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc),
            schemaStatRepo, SchemaDocRepository(jdbc), metaCacheRepo)
        syncService = MetaSyncService(metaSyncRepo, metadata, dataSourceService, DialectFactory)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(DialectFactory)
    }

    /** 好数据源恢复在线(业务 H2 真实连接) */
    private fun stubBizOnline() {
        every { dataSourceService.getConnection(DS_ID, null) } answers { DriverManager.getConnection(bizUrl) }
    }

    /** 好数据源断开(连接级失败) */
    private fun stubBizOffline() {
        every { dataSourceService.getConnection(DS_ID, null) } throws
            SQLException("Communications link failure", ConnectException("Connection refused"))
    }

    /** 在业务库上执行 DDL/DML */
    private fun biz(block: (Statement) -> Unit) {
        DriverManager.getConnection(bizUrl).use { conn ->
            conn.createStatement().use { st -> block(st) }
        }
    }

    /** 提交并轮询到终态,返回任务详情 */
    private fun submitAndAwait(vararg dsIds: Long): com.example.dq.model.MetaSyncDetail {
        val jobId = syncService.submit(dsIds.toList())
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val detail = syncService.detail(jobId)
            if (detail.job.status != "PENDING" && detail.job.status != "RUNNING") return detail
            Thread.sleep(50)
        }
        throw AssertionError("同步任务超时未完成")
    }

    @Test
    fun `同步后缓存落齐 业务库断开后浏览接口降级可用`() {
        val detail = submitAndAwait(DS_ID)
        assertEquals("DONE", detail.job.status) { "任务失败: " + detail.job.error }
        val item = detail.items.single()
        assertEquals("DONE", item.status)
        assertEquals(0, item.dbCount) // 单库方言无库一层
        assertEquals(1, item.schemaCount)
        assertEquals(2, item.tableCount)
        assertEquals(1, detail.job.doneDs)

        // 缓存落齐:schema 清单/表清单/字段/索引/整库 lite 字段/字段总数/库概览
        assertTrue(metaCacheRepo.isSchemaListReady(DS_ID, ""))
        assertTrue(metaCacheRepo.isTableCacheReady(DS_ID, "", SCHEMA))
        assertTrue(metaCacheRepo.isColumnCacheReady(DS_ID, "", SCHEMA, "T1"))
        assertTrue(metaCacheRepo.isIndexCacheReady(DS_ID, "", SCHEMA, "T1"))
        assertTrue(metaCacheRepo.isSchemaColumnsReady(DS_ID, "", SCHEMA))
        assertNotNull(metaCacheRepo.getColumnCount(DS_ID, "", SCHEMA))
        assertTrue(schemaStatRepo.findAll(DS_ID, null).isNotEmpty())

        // 断开后浏览接口全部走缓存降级可用
        stubBizOffline()
        assertEquals(listOf(SCHEMA), metadata.listSchemas(DS_ID, null))
        assertEquals(listOf("T1", "T2"), metadata.listTables(DS_ID, null, SCHEMA).map { it.name })
        val cols = metadata.listTableColumns(DS_ID, null, SCHEMA, "T1")
        assertEquals(listOf("ID", "NAME"), cols.map { it.name })
        assertEquals("姓名", cols[1].comment)
        assertTrue(metadata.listTableIndexes(DS_ID, null, SCHEMA, "T1").isNotEmpty())
        assertTrue(metadata.listSchemaColumns(DS_ID, null, SCHEMA)
            .any { it.table == "T2" && it.name == "CODE" })
        assertEquals(3L, metadata.countColumns(DS_ID, null, SCHEMA))
        val stats = metadata.listSchemaStats(DS_ID, null)
        assertEquals(1, stats.size)
        assertEquals(2, stats[0].tableCount)
    }

    @Test
    fun `改表结构后再同步覆盖更新缓存`() {
        submitAndAwait(DS_ID)
        assertEquals(listOf("ID", "NAME"), metadata.listTableColumns(DS_ID, null, SCHEMA, "T1").map { it.name })

        biz { st ->
            st.execute("ALTER TABLE t1 ADD COLUMN c3 INT")
            st.execute("DROP TABLE t2")
        }
        val detail = submitAndAwait(DS_ID)
        assertEquals("DONE", detail.job.status) { "任务失败: " + detail.job.error }

        // 覆盖刷新生效:新字段出现、已删表从表清单移除(读本地缓存即最新)
        assertEquals(listOf("ID", "NAME", "C3"),
            metadata.listTableColumns(DS_ID, null, SCHEMA, "T1").map { it.name })
        assertEquals(listOf("T1"), metadata.listTables(DS_ID, null, SCHEMA).map { it.name })
        assertEquals(1, detail.items.single().tableCount)
    }

    @Test
    fun `数据源连不上明细记失败且不中断整批`() {
        val detail = submitAndAwait(BAD_DS_ID, DS_ID)
        assertEquals("FAILED", detail.job.status)
        assertEquals(1, detail.job.doneDs)
        assertEquals(1, detail.job.failedDs)

        val bad = detail.items.first { it.datasourceId == BAD_DS_ID }
        assertEquals("FAILED", bad.status)
        assertTrue(bad.error!!.contains("数据源连接失败"), bad.error)
        assertEquals("离线库", bad.datasourceName)
        // 连接失败经分类器落数据源标记(网络不可达)
        assertEquals(1, connFailures.size)
        assertEquals(BAD_DS_ID, connFailures[0].first)
        assertEquals("UNREACHABLE", connFailures[0].third)

        // 单个失败不中断:好数据源照常同步完成
        val good = detail.items.first { it.datasourceId == DS_ID }
        assertEquals("DONE", good.status)
        assertEquals(2, good.tableCount)
        assertTrue(metaCacheRepo.isTableCacheReady(DS_ID, "", SCHEMA))
    }

    @Test
    fun `同步中途断连降级读缓存时判失败 不拿陈旧缓存当成果`() {
        submitAndAwait(DS_ID) // 先同步一遍建缓存
        // 改表结构后,同步预检连接成功、随后的 refresh 回源全部断连:
        // 浏览路径有缓存会静默降级返回旧缓存,同步任务必须判失败而不是假成功
        biz { it.execute("ALTER TABLE t1 ADD COLUMN c9 INT") }
        var firstCall = true
        every { dataSourceService.getConnection(DS_ID, null) } answers {
            if (firstCall) {
                firstCall = false
                DriverManager.getConnection(bizUrl)
            } else {
                throw SQLException("Communications link failure", ConnectException("Connection refused"))
            }
        }
        val detail = submitAndAwait(DS_ID)
        assertEquals("FAILED", detail.job.status)
        val item = detail.items.single()
        assertEquals("FAILED", item.status)
        assertTrue(item.error!!.contains("数据源连接失败"), item.error)
    }

    @Test
    fun `参数校验 空列表与不存在的数据源拒绝 运行中冲突拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { syncService.submit(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { syncService.submit(listOf(999L)) }

        // 手工落一个 RUNNING 任务:再提交报 409 语义
        val runningId = metaSyncRepo.insertJob(1)
        metaSyncRepo.markRunning(runningId)
        assertThrows(IllegalStateException::class.java) { syncService.submit(listOf(DS_ID)) }
    }

    @Test
    fun `取消未开始任务生效 已结束任务取消报错`() {
        val jobId = metaSyncRepo.insertJob(1)
        metaSyncRepo.insertItems(jobId, listOf(DS_ID to "测试库"))
        syncService.cancel(jobId)
        assertEquals("CANCELED", metaSyncRepo.findJob(jobId)!!.status)
        assertThrows(IllegalStateException::class.java) { syncService.cancel(jobId) }

        // latest/detail 查询口径
        val latest = syncService.latest()!!
        assertEquals(jobId, latest.job.id)
        assertEquals(1, latest.items.size)
        assertThrows(IllegalArgumentException::class.java) { syncService.detail(9999L) }
    }

    @Test
    fun `重启残留任务与明细置失败`() {
        val jobId = metaSyncRepo.insertJob(1)
        metaSyncRepo.insertItems(jobId, listOf(DS_ID to "测试库"))
        metaSyncRepo.markRunning(jobId)
        metaSyncRepo.markItemRunning(metaSyncRepo.listItems(jobId).single().id)

        syncService.recoverUnfinished()

        val job = metaSyncRepo.findJob(jobId)!!
        assertEquals("FAILED", job.status)
        assertTrue(job.error!!.contains("服务重启"))
        assertEquals("FAILED", metaSyncRepo.listItems(jobId).single().status)
        assertTrue(!metaSyncRepo.hasRunning())
        // 已终态任务不受影响(幂等)
        syncService.recoverUnfinished()
        assertEquals("FAILED", metaSyncRepo.findJob(jobId)!!.status)
    }
}
