package com.example.dq.repository

import com.example.dq.service.TableRelationService
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** ER 表间关系批量操作:批量确认/否决计数与状态落库 / 批量删除任意状态生效 / 空列表与超上限校验 /
 *  人工审核备注落库 / 审核数据(原始 vs 最终)差异组装 */
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

    @Test
    fun `批量审核落库备注并标记人工已审核 未带备注的行保留原备注`() {
        val id1 = insert("t1", "id", "t2", "t1_id")
        val id2 = insert("t3", "id", "t4", "t3_id")
        // 只有 id1 填了否决原因,id2 未改备注
        assertEquals(2, service.rejectBatch(listOf(id1, id2), mapOf(id1.toString() to "重复字段,误报")))

        val r1 = repo.findById(id1)!!
        assertEquals("REJECTED", r1.status)
        assertEquals("重复字段,误报", r1.remark)
        assertTrue(r1.reviewed)
        assertTrue(r1.reviewedAt != null)
        val r2 = repo.findById(id2)!!
        assertEquals("REJECTED", r2.status)
        assertNull(r2.remark)
        assertTrue(r2.reviewed)
        // 非数字备注键忽略、不存在的 id 忽略
        assertEquals(1, service.confirmBatch(listOf(id1, 999L), mapOf("abc" to "x", id1.toString() to "确认原因")))
        val after = repo.findById(id1)!!
        assertEquals("CONFIRMED", after.status)
        assertEquals("确认原因", after.remark)
    }

    @Test
    fun `推导入库保留人工已审核的行 原始关系快照随推导刷新`() {
        // 手工造一条人工已否决的最终行 + 它的原始快照
        val id = repo.insertIfAbsent(1L, "", "public", "t1", "id", "t2", "t1_id",
            "ONE_TO_MANY", "REJECTED", "NAME_MATCH", "LOW", 0.1, "人工否决", reviewed = true)!!
        repo.upsertOriginal(1L, "", "public", "t1", "id", "t2", "t1_id",
            "ONE_TO_MANY", "CANDIDATE", "NAME_MATCH", "LOW", 0.1, null)

        // 重新推导命中同一对,方向翻转:最终行原样保留(状态/方向/备注都不动,返回 null 表示不是新关系)
        assertNull(repo.upsertDerived(1L, "", "public", "t2", "t1_id", "t1", "id",
            "ONE_TO_ONE", "SEMANTIC", "HIGH", 0.9, "新推导说明"))
        val kept = repo.findById(id)!!
        assertEquals("REJECTED", kept.status)
        assertEquals("t1", kept.oneTable)
        assertEquals("t2", kept.manyTable)
        assertEquals("ONE_TO_MANY", kept.cardinality)
        assertEquals("人工否决", kept.remark)
        assertTrue(kept.reviewed)

        // 原始关系快照按最新推导刷新(方向翻转 + 新验证数据),人工审核不影响它
        val originals = repo.listOriginalsBySchema(1L, "", "public")
        assertEquals(1, originals.size)
        assertEquals("t2", originals[0].oneTable)
        assertEquals("ONE_TO_ONE", originals[0].cardinality)
        assertEquals("SEMANTIC", originals[0].source)
        assertEquals(0.9, originals[0].overlapRatio!!, 1e-9)

        // 未审核的新关系照常插入为候选
        val fresh = repo.upsertDerived(1L, "", "public", "t3", "id", "t4", "t3_id",
            "ONE_TO_MANY", "NAME_MATCH", "HIGH", 0.8, null)
        assertTrue(fresh != null)
        assertFalse(repo.findById(fresh!!)!!.reviewed)
    }

    @Test
    fun `审核数据组装 原始与最终的差异 含人工新增删除与修改`() {
        // 最终:未审核候选(与原始一致,无变化)/ 人工确认(状态变化)/ 人工补充(只存在于最终→新增)
        val unchanged = insert("t1", "id", "t2", "t1_id")
        val confirmed = insert("t3", "id", "t4", "t3_id")
        service.confirm(confirmed)
        repo.insertIfAbsent(1L, "", "public", "t5", "id", "t6", "t5_id",
            "ONE_TO_ONE", "CONFIRMED", "MANUAL", null, null, "人工补充", reviewed = true)
        // 原始:与最终同 key 的两条 + 一条只剩原始快照(人工删除)
        repo.upsertOriginal(1L, "", "public", "t1", "id", "t2", "t1_id",
            "ONE_TO_MANY", "CANDIDATE", "NAME_MATCH", "HIGH", 0.9, null)
        repo.upsertOriginal(1L, "", "public", "t3", "id", "t4", "t3_id",
            "ONE_TO_MANY", "CANDIDATE", "NAME_MATCH", "HIGH", 0.9, null)
        repo.upsertOriginal(1L, "", "public", "t7", "id", "t8", "t7_id",
            "ONE_TO_MANY", "CANDIDATE", "NAME_MATCH", "LOW", 0.1, null)

        val audit = service.audit(1L, null, "public", null)
        assertEquals(3, audit.finals.size)
        assertEquals(3, audit.originals.size)
        assertEquals(3, audit.changes.size)
        val byType = audit.changes.groupBy { it.changeType }
        assertEquals(1, byType["ADDED"]!!.size)
        val added = byType["ADDED"]!!.single()
        assertNull(added.before)
        assertEquals("t5", added.after!!.oneTable)
        assertEquals(1, byType["REMOVED"]!!.size)
        val removed = byType["REMOVED"]!!.single()
        assertEquals("t7", removed.before!!.oneTable)
        assertNull(removed.after)
        val modified = byType["MODIFIED"]!!.single()
        assertEquals(listOf("STATUS"), modified.changedFields)
        assertEquals("CANDIDATE", modified.before!!.status)
        assertEquals("CONFIRMED", modified.after!!.status)
        assertTrue(audit.finals.any { it.id == unchanged })

        // 表过滤:只保留该表参与的关系(已删除的原始关系仍能从快照捞出)
        val byTable = service.audit(1L, null, "public", "t7")
        assertEquals(1, byTable.originals.size)
        assertEquals(0, byTable.finals.size)
        assertEquals(1, byTable.changes.size)
        assertEquals("REMOVED", byTable.changes[0].changeType)
    }
}
