package com.example.dq.repository

import com.example.dq.service.TableSystemService
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** 表所属系统:upsert 幂等覆盖 / 四元组隔离 / 批量 delete 计数 / batchSet 空白清除 / db null 归一 */
class TableSystemRepositoryTest {

    private lateinit var jdbc: Jdbc
    private lateinit var repo: TableSystemRepository
    private lateinit var service: TableSystemService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:table-system-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = TableSystemRepository(jdbc)
        service = TableSystemService(repo)
    }

    @Test
    fun `upsert 幂等覆盖同一张表只保留最新系统`() {
        repo.upsert(1L, "", "public", "users", "系统A")
        repo.upsert(1L, "", "public", "users", "系统A") // 幂等:重复设置不产生第二条
        repo.upsert(1L, "", "public", "users", "系统B") // 覆盖

        assertEquals("系统B", repo.findBySchema(1L, "", "public")["users"])
        assertEquals(1, count("table_system"))
    }

    @Test
    fun `四元组隔离 数据源库 schema 互不可见`() {
        repo.upsert(1L, "", "public", "users", "系统A")
        repo.upsert(1L, "testdb", "dbo", "users", "系统B")
        repo.upsert(2L, "", "public", "users", "系统C")

        assertEquals("系统A", repo.findBySchema(1L, "", "public")["users"])
        assertEquals("系统B", repo.findBySchema(1L, "testdb", "dbo")["users"])
        assertEquals("系统C", repo.findBySchema(2L, "", "public")["users"])
        // 清除其中一个四元组不影响其他
        assertEquals(1, repo.delete(1L, "", "public", listOf("users")))
        assertEquals("系统B", repo.findBySchema(1L, "testdb", "dbo")["users"])
        assertEquals("系统C", repo.findBySchema(2L, "", "public")["users"])
    }

    @Test
    fun `批量 delete 返回删除数 重复删除为 0`() {
        repo.upsert(1L, "", "public", "t1", "系统A")
        repo.upsert(1L, "", "public", "t2", "系统A")
        repo.upsert(1L, "", "public", "t3", "系统A")

        assertEquals(2, repo.delete(1L, "", "public", listOf("t1", "t2")))
        assertEquals(setOf("t3"), repo.findBySchema(1L, "", "public").keys)
        assertEquals(0, repo.delete(1L, "", "public", listOf("t1", "t2")))
        assertEquals(0, repo.delete(1L, "", "public", emptyList()))
    }

    @Test
    fun `batchSet 空白清除 非空设置 返回计数`() {
        val set = service.batchSet(1L, null, "public", listOf("t1", "t2", " t2 "), " 核心系统 ")
        assertEquals(2, set.updated)   // trim + 去重后两张表
        assertEquals(0, set.cleared)
        assertEquals("核心系统", repo.findBySchema(1L, "", "public")["t1"]) // systemName 两端空白被 trim

        val cleared = service.batchSet(1L, null, "public", listOf("t1", "t3"), "  ")
        assertEquals(0, cleared.updated)
        assertEquals(1, cleared.cleared) // t3 本就无所属系统,只删到 t1
        assertNull(repo.findBySchema(1L, "", "public")["t1"])
        assertEquals("核心系统", repo.findBySchema(1L, "", "public")["t2"])

        // systemName 为 null 同样表示清除
        val clearedNull = service.batchSet(1L, null, "public", listOf("t2"), null)
        assertEquals(1, clearedNull.cleared)
        assertTrue(repo.findBySchema(1L, "", "public").isEmpty())
    }

    @Test
    fun `db null 归一空串 与显式空串读写同一条记录`() {
        service.batchSet(1L, null, "public", listOf("t1"), "系统A")
        // null 与 "" 归一为同一行(空串兜底保证唯一键)
        assertEquals("系统A", service.list(1L, "", "public")["t1"])
        assertEquals(1, count("table_system"))
        // 多库方言的 db 名与空串互不影响
        service.batchSet(1L, "testdb", "dbo", listOf("t1"), "系统B")
        assertEquals("系统A", service.list(1L, null, "public")["t1"])
        assertEquals("系统B", service.list(1L, "testdb", "dbo")["t1"])
    }

    private fun count(table: String): Int =
        jdbc.queryOne("SELECT COUNT(*) FROM $table") { rs -> rs.getInt(1) }!!
}
