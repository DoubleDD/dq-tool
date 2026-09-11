package com.example.dq.repository

import com.example.dq.service.TableRelationService
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

import org.junit.jupiter.api.Assertions.assertEquals

/** ER 表间关系批量操作:批量确认/否决计数与状态落库 / 批量删除任意状态生效 / 空列表与超上限校验 */
class TableRelationRepositoryTest {

    private lateinit var jdbc: Jdbc
    private lateinit var repo: TableRelationRepository
    private lateinit var service: TableRelationService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:table-relation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = TableRelationRepository(jdbc)
        service = TableRelationService(repo, MetaCacheRepository(jdbc))
    }

    private fun insert(table: String, column: String, other: String, otherColumn: String): Long =
        repo.insertIfAbsent(1L, "", "public", table, column, other, otherColumn,
            "ONE_TO_MANY", "CANDIDATE", "NAME_MATCH", "HIGH", 0.9, null)!!

    @Test
    fun `批量确认与批量否决 返回实际更新数 状态落库`() {
        val id1 = insert("t1", "id", "t2", "t1_id")
        val id2 = insert("t1", "code", "t3", "t1_code")
        insert("t4", "id", "t5", "t4_id")

        // 不存在的 id 忽略,只计实际命中的行
        assertEquals(2, service.confirmBatch(listOf(id1, id2, 999L)))
        assertEquals(listOf("CONFIRMED", "CONFIRMED", "CANDIDATE"),
            repo.listBySchema(1L, "", "public").map { it.status })

        // 确认后仍可批量转否决(同单条口径,状态互转不做前置限制)
        assertEquals(2, service.rejectBatch(listOf(id1, id2)))
        assertEquals(listOf("REJECTED", "REJECTED", "CANDIDATE"),
            repo.listBySchema(1L, "", "public").map { it.status })

        assertEquals(0, service.confirmBatch(listOf(888L, 999L)))
    }

    @Test
    fun `批量删除任意状态生效 确认与否决的关系一并删除`() {
        val id1 = insert("t1", "id", "t2", "t1_id")
        val id2 = insert("t1", "code", "t3", "t1_code")
        val id3 = insert("t4", "id", "t5", "t4_id")
        service.confirmBatch(listOf(id2)) // 确认态同样可删
        service.rejectBatch(listOf(id3)) // 否决态同样可删

        // 不存在的 id 忽略,只计实际删除的行
        assertEquals(3, service.deleteBatch(listOf(id1, id2, id3, 999L)))
        assertEquals(0, repo.listBySchema(1L, "", "public").size)

        // 单条删除同样不限状态
        val id4 = insert("t6", "id", "t7", "t6_id")
        service.confirm(id4)
        service.delete(id4)
        assertEquals(0, repo.listBySchema(1L, "", "public").size)
        // 不存在的关系删除报参数错误
        assertThrows<IllegalArgumentException> { service.delete(id4) }
    }

    @Test
    fun `批量入参校验 空列表与超上限抛参数错误 恰好上限可用`() {
        assertThrows<IllegalArgumentException> { service.confirmBatch(emptyList()) }
        assertThrows<IllegalArgumentException> { service.rejectBatch(emptyList()) }
        assertThrows<IllegalArgumentException> { service.deleteBatch(emptyList()) }
        assertThrows<IllegalArgumentException> {
            service.confirmBatch((1L..1001L).toList())
        }
        // 恰好上限(1000)可用;注意数量校验在去重之前(原始列表超上限即拒绝,更保守)
        val ids = (1L..1000L).map { insert("t$it", "id", "o$it", "tid") }
        assertEquals(1000, service.deleteBatch(ids))
        assertEquals(0, repo.listBySchema(1L, "", "public").size)
    }

    @Test
    fun `repo 空集合兜底不拼非法 IN`() {
        assertEquals(0, repo.updateStatusBatch(emptyList(), "CONFIRMED"))
        assertEquals(0, repo.deleteByIds(emptyList()))
    }
}
