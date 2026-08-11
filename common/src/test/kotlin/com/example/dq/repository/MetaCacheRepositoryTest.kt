package com.example.dq.repository

import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 结构元数据缓存(meta_table/meta_column/meta_index + flag)读写与覆盖语义 */
class MetaCacheRepositoryTest {

    private lateinit var repo: MetaCacheRepository
    private lateinit var jdbc: Jdbc

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:meta_cache_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = MetaCacheRepository(jdbc)
    }

    private fun table(name: String, comment: String? = null) =
        MetaCacheRepository.CachedTable(name, comment, "InnoDB", 100L, 2048L)

    private fun col(name: String, ordinal: Int) = MetaCacheRepository.CachedColumn(
        ordinal, name, "VARCHAR", "varchar(50)", 12, true, null, "注释", false, 0, false
    )

    // ---------- 表缓存 ----------

    @Test
    fun `表缓存 写入后可就绪并往返`() {
        assertFalse(repo.isTableCacheReady(1, "", "db1"))
        repo.replaceTables(1, "", "db1", listOf(table("t1"), table("t2")))
        assertTrue(repo.isTableCacheReady(1, "", "db1"))
        val cached = repo.listTables(1, "", "db1")
        assertEquals(listOf("t1", "t2"), cached.map { it.tableName })
        assertEquals("InnoDB", cached.first().storageInfo)
        assertEquals(100L, cached.first().estRows)
        // 不同 schema/数据源互不影响
        assertFalse(repo.isTableCacheReady(1, "", "db2"))
        assertFalse(repo.isTableCacheReady(2, "", "db1"))
    }

    @Test
    fun `表缓存 覆盖刷新替换旧清单`() {
        repo.replaceTables(1, "", "db1", listOf(table("t1"), table("t2")))
        repo.replaceTables(1, "", "db1", listOf(table("t1"))) // t2 删除
        assertEquals(listOf("t1"), repo.listTables(1, "", "db1").map { it.tableName })
    }

    // ---------- 字段缓存 ----------

    @Test
    fun `字段缓存 按 ordinal 排序往返`() {
        assertFalse(repo.isColumnCacheReady(1, "", "db1", "t1"))
        repo.replaceColumns(1, "", "db1", "t1", listOf(col("id", 0), col("name", 1)))
        assertTrue(repo.isColumnCacheReady(1, "", "db1", "t1"))
        val cached = repo.listColumns(1, "", "db1", "t1")
        assertEquals(listOf("id", "name"), cached.map { it.columnName })
        assertEquals("varchar(50)", cached[1].displayType)
        assertEquals("注释", cached[1].comment)
        // 表级缓存与字段缓存互不干扰
        assertFalse(repo.isColumnCacheReady(1, "", "db1", "t2"))
    }

    // ---------- 索引缓存(含空索引场景:flag 区分「未缓存」与「已缓存但为空」) ----------

    @Test
    fun `索引缓存 空列表也标记就绪 避免重复拉取`() {
        assertFalse(repo.isIndexCacheReady(1, "", "db1", "t1"))
        // 无索引的表:缓存空列表后必须标记就绪
        repo.replaceIndexes(1, "", "db1", "t1", emptyList())
        assertTrue(repo.isIndexCacheReady(1, "", "db1", "t1"))
        assertEquals(0, repo.listIndexes(1, "", "db1", "t1").size)
    }

    @Test
    fun `索引缓存 列按 ordinal 展开为多行`() {
        repo.replaceIndexes(1, "", "db1", "t1", listOf(
            MetaCacheRepository.CachedIndex("idx_ab", false, 0, "a"),
            MetaCacheRepository.CachedIndex("idx_ab", false, 1, "b"),
            MetaCacheRepository.CachedIndex("uk_c", true, 0, "c")
        ))
        assertTrue(repo.isIndexCacheReady(1, "", "db1", "t1"))
        val cached = repo.listIndexes(1, "", "db1", "t1")
        assertEquals(3, cached.size)
        val rows = cached.groupBy { it.indexName }
        assertEquals(listOf("a", "b"), rows["idx_ab"]!!.map { it.columnName })
        assertTrue(rows["uk_c"]!!.first().unique)
    }

    // ---------- 级联清理 ----------

    @Test
    fun `删除数据源 级联清理全部缓存与标记`() {
        repo.replaceTables(1, "", "db1", listOf(table("t1")))
        repo.replaceColumns(1, "", "db1", "t1", listOf(col("id", 0)))
        repo.replaceIndexes(1, "", "db1", "t1", listOf(MetaCacheRepository.CachedIndex("uk", true, 0, "id")))
        repo.deleteByDatasource(1)
        assertFalse(repo.isTableCacheReady(1, "", "db1"))
        assertFalse(repo.isColumnCacheReady(1, "", "db1", "t1"))
        assertFalse(repo.isIndexCacheReady(1, "", "db1", "t1"))
        assertEquals(0, repo.listTables(1, "", "db1").size)
        assertEquals(0, repo.listColumns(1, "", "db1", "t1").size)
        assertEquals(0, repo.listIndexes(1, "", "db1", "t1").size)
        // 其他数据源缓存不受影响
        assertFalse(repo.isTableCacheReady(2, "", "db1"))
    }
}
