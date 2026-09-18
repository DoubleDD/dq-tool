package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.IndexMeta
import com.example.dq.model.TableStat
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.MetaWriteQueue
import com.example.dq.repository.SchemaInit
import com.example.dq.util.SqlLogConnection
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.sql.Types
import java.util.concurrent.TimeUnit

/**
 * 元数据读拦截器(装饰器)语义:读到元数据且对应粒度未缓存 → 火忘回填 meta_*;
 * 已就绪跳过(防热路径写放大);无上下文纯透传;回填失败不影响读取。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MetaReadCachingDialectTest {

    /** 只覆盖本测试关心的几个读方法,其余委托给真实方言(不会被调到) */
    private class StubDialect : DbDialect by MySqlDialect() {
        var tables: List<TableStat> = emptyList()
        var columns: List<ColumnMeta> = emptyList()
        var indexes: List<IndexMeta> = emptyList()
        override fun listTables(conn: Connection, schema: String): List<TableStat> = tables
        override fun listColumns(conn: Connection, schema: String, table: String): List<ColumnMeta> = columns
        override fun listIndexes(conn: Connection, schema: String, table: String): List<IndexMeta> = indexes
    }

    private lateinit var ds: JdbcDataSource
    private lateinit var metaCache: MetaCacheRepository
    private lateinit var stub: StubDialect
    private lateinit var caching: MetaReadCachingDialect

    @BeforeEach
    fun setUp() {
        ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:meta-read-intercept-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        metaCache = MetaCacheRepository(Jdbc(ds))
        stub = StubDialect()
        caching = MetaReadCachingDialect(stub)
        stub.tables = listOf(TableStat("t1", 10L, 100L, "表1", "InnoDB"))
        stub.columns = listOf(
            ColumnMeta("id", "BIGINT", "bigint(20)", Types.BIGINT, false, null, "主键", true, 1, false),
            ColumnMeta("name", "VARCHAR", "varchar(50)", Types.VARCHAR, true, null, "姓名", false, 0, false),
        )
        stub.indexes = listOf(IndexMeta("uk_name", true, listOf("name")))
    }

    private fun ctxConn(meta: MetaCacheRepository = metaCache): Connection =
        // 裸连接不关闭(测试期间复用;DB_CLOSE_DELAY=-1 保证库随 JVM 存活)
        SqlLogConnection.wrap(ds.connection, MetaReadContext(1L, "", meta))

    private fun await(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("等待缓存回填超时")
            Thread.sleep(20)
        }
    }

    @Test
    fun `未缓存粒度的表清单读取后回填meta_table`() {
        val conn = ctxConn()
        val tables = caching.listTables(conn, "public")

        assertThat(tables).hasSize(1)
        await { metaCache.isTableCacheReady(1L, "", "public") }
        val cached = metaCache.listTables(1L, "", "public")
        assertThat(cached).hasSize(1)
        assertThat(cached[0].tableName).isEqualTo("t1")
        assertThat(cached[0].comment).isEqualTo("表1")
        assertThat(cached[0].estRows).isEqualTo(10L)
    }

    @Test
    fun `粒度已就绪则跳过重写`() {
        val conn = ctxConn()
        caching.listTables(conn, "public")
        await { metaCache.isTableCacheReady(1L, "", "public") }

        // 源数据变化后再读:拦截器不应覆盖已就绪缓存(刷新是显式路径的职责)
        stub.tables = listOf(TableStat("t1", 999L, 100L, "新注释", "InnoDB"))
        caching.listTables(conn, "public")
        Thread.sleep(300) // 留给可能的误写落库

        val cached = metaCache.listTables(1L, "", "public")
        assertThat(cached[0].comment).isEqualTo("表1")
        assertThat(cached[0].estRows).isEqualTo(10L)
    }

    @Test
    fun `无上下文连接纯透传不写缓存`() {
        val raw = ds.connection
        val tables = caching.listTables(raw, "public")

        assertThat(tables).hasSize(1)
        Thread.sleep(300)
        assertThat(metaCache.isTableCacheReady(1L, "", "public")).isFalse()
        raw.close()
    }

    @Test
    fun `字段与索引读取后回填`() {
        val conn = ctxConn()
        caching.listColumns(conn, "public", "t1")
        caching.listIndexes(conn, "public", "t1")

        await {
            metaCache.isColumnCacheReady(1L, "", "public", "t1") &&
                    metaCache.isIndexCacheReady(1L, "", "public", "t1")
        }
        val cols = metaCache.listColumns(1L, "", "public", "t1")
        assertThat(cols).hasSize(2)
        assertThat(cols[0].columnName).isEqualTo("id")
        assertThat(cols[0].primaryKey).isTrue()
        assertThat(cols[0].displayType).isEqualTo("bigint(20)")
        val idx = metaCache.listIndexes(1L, "", "public", "t1")
        assertThat(idx).hasSize(1)
        assertThat(idx[0].indexName).isEqualTo("uk_name")
    }

    @Test
    fun `回填失败不影响元数据读取`() {
        // 写队列已关闭的仓储:回填被丢弃,读取照常返回
        val deadRepo = MetaCacheRepository(Jdbc(ds), MetaWriteQueue().also { it.shutdown() })
        val conn = ctxConn(deadRepo)

        val tables = caching.listTables(conn, "public")

        assertThat(tables).hasSize(1)
        assertThat(tables[0].name).isEqualTo("t1")
    }
}
