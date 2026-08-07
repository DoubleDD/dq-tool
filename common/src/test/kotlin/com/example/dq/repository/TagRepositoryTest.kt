package com.example.dq.repository

import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanStatus
import com.example.dq.model.TagKind
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 表标记仓储:CRUD / 唯一约束 / 级联删除 / 整库 map / 整体替换 / 统计口径 */
class TagRepositoryTest {

    private lateinit var jdbc: Jdbc
    private lateinit var repo: TagRepository
    private lateinit var scanRepo: ScanRepository

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:tag-test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = TagRepository(jdbc)
        scanRepo = ScanRepository(jdbc)
    }

    @Test
    fun `系统空表标记随迁移自动插入`() {
        val empty = repo.findEmptyTag()
        assertNotNull(empty)
        assertEquals("空表", empty!!.name)
        assertEquals("#909399", empty.color)
        assertEquals(TagKind.EMPTY, empty.kind)
        // 列表接口包含系统标记
        assertTrue(repo.listAll().any { it.kind == TagKind.EMPTY })
    }

    @Test
    fun `创建与列表带打标表数`() {
        val tag = repo.create("水利对象表", "#FF0000")
        assertEquals(TagKind.USER, tag.kind)

        repo.ensureTableTag(tag.id, 1L, "", "s1", "t1")
        repo.ensureTableTag(tag.id, 1L, "", "s1", "t1") // 幂等:重复打标不产生第二条

        val listed = repo.listAll().first { it.id == tag.id }
        assertEquals(1L, listed.tableCount)
    }

    @Test
    fun `名称唯一索引兜底重名`() {
        repo.create("防洪业务表", "#409EFF")
        assertThrows(Exception::class.java) { repo.create("防洪业务表", "#409EFF") }
    }

    @Test
    fun `删除标记级联解除打标关系`() {
        val tag = repo.create("水资源业务表", "#409EFF")
        repo.ensureTableTag(tag.id, 1L, "", "s1", "t1")
        repo.ensureTableTag(tag.id, 1L, "", "s1", "t2")

        repo.delete(tag.id)

        assertEquals(0, count("table_tag"))
        assertNull(repo.findById(tag.id))
    }

    @Test
    fun `整库打标 map 与整体替换 USER 标记`() {
        val tagA = repo.create("标记A", "#409EFF")
        val tagB = repo.create("标记B", "#67C23A")
        val empty = repo.findEmptyTag()!!
        // t1 同时打了空表标记与 USER 标记
        repo.ensureTableTag(empty.id, 1L, "", "s1", "t1")
        repo.replaceUserTags(1L, "", "s1", "t1", listOf(tagA.id, tagB.id))
        repo.replaceUserTags(1L, "", "s1", "t2", listOf(tagA.id))

        var map = repo.tableTagsBySchema(1L, "", "s1")
        assertEquals(setOf("空表", "标记A", "标记B"), map["t1"]!!.map { it.name }.toSet())
        assertEquals(listOf("标记A"), map["t2"]!!.map { it.name })

        // 整体替换只动 USER 标记,空表标记保留
        repo.replaceUserTags(1L, "", "s1", "t1", listOf(tagB.id))
        map = repo.tableTagsBySchema(1L, "", "s1")
        assertEquals(setOf("空表", "标记B"), map["t1"]!!.map { it.name }.toSet())

        // 传空列表 = 清空 USER 标记
        repo.replaceUserTags(1L, "", "s1", "t1", emptyList())
        map = repo.tableTagsBySchema(1L, "", "s1")
        assertEquals(listOf("空表"), map["t1"]!!.map { it.name })
    }

    @Test
    fun `库维度标记计数只返回有标记表的库`() {
        val tagA = repo.create("标记A", "#409EFF")
        val empty = repo.findEmptyTag()!!
        repo.ensureTableTag(tagA.id, 1L, "", "s1", "t1")
        repo.ensureTableTag(tagA.id, 1L, "", "s1", "t2")
        repo.ensureTableTag(empty.id, 1L, "", "s1", "t2")
        repo.ensureTableTag(tagA.id, 1L, "", "s2", "t3")

        val rows = repo.schemaTagCounts(1L, "")
        assertEquals(setOf("s1", "s2"), rows.map { it.schemaName }.toSet())
        val s1 = rows.filter { it.schemaName == "s1" }
        assertEquals(2, s1.size) // 标记A + 空表
        assertEquals(2, s1.first { it.tagId == tagA.id }.count)
        assertEquals(1, s1.first { it.kind == TagKind.EMPTY }.count)
        // 不同数据源/库互不可见
        assertTrue(repo.schemaTagCounts(2L, "").isEmpty())
        assertTrue(repo.schemaTagCounts(1L, "otherdb").isEmpty())
    }

    @Test
    fun `分布统计只累计最近一次 DONE 扫描快照`() {
        val tagA = repo.create("标记A", "#409EFF")
        val tagB = repo.create("标记B", "#67C23A")
        // s1:t1 打 A(两次扫描,应取最近一次),t2 打 A+B(从未扫描)
        repo.ensureTableTag(tagA.id, 1L, "", "s1", "t1")
        doneScan(1L, null, "s1", "t1", 10L, 2)
        doneScan(1L, null, "s1", "t1", 100L, 5)
        repo.ensureTableTag(tagA.id, 1L, "", "s1", "t2")
        repo.ensureTableTag(tagB.id, 1L, "", "s1", "t2")
        // s2:t3 打 A;testdb/dbo:t4 打 A(多库方言 db_name 非空口径)
        repo.ensureTableTag(tagA.id, 1L, "", "s2", "t3")
        doneScan(1L, null, "s2", "t3", 50L, 3)
        repo.ensureTableTag(tagA.id, 2L, "testdb", "dbo", "t4")
        doneScan(2L, "testdb", "dbo", "t4", 7L, 2)

        val distA = repo.schemaDistribution(tagA.id).associateBy { Triple(it.datasourceId, it.dbName, it.schemaName) }
        assertEquals(3, distA.size)
        val s1 = distA[Triple(1L, "", "s1")]!!
        assertEquals(2, s1.tableCount)          // t2 未扫描仍计入表数
        assertEquals(100L, s1.totalRows)        // 取最近一次快照,未扫描的 t2 不计入行/列
        assertEquals(5L, s1.totalColumns)
        val s2 = distA[Triple(1L, "", "s2")]!!
        assertEquals(1, s2.tableCount)
        assertEquals(50L, s2.totalRows)
        assertEquals(3L, s2.totalColumns)
        val dbo = distA[Triple(2L, "testdb", "dbo")]!!
        assertEquals(7L, dbo.totalRows)

        // 标记B 只打了未扫描的 t2:表数照计,行/列为 null
        val distB = repo.schemaDistribution(tagB.id)
        assertEquals(1, distB.size)
        assertEquals(1, distB[0].tableCount)
        assertNull(distB[0].totalRows)
        assertNull(distB[0].totalColumns)

        // 全局打标表数跨标记去重:t1/t2/t3/t4 = 4(t2 打两个标记只算一次)
        assertEquals(4, repo.countCoveredTables())
    }

    /** 造一条 DONE 扫描快照:任务 + 表(totalRows)+ 指定数量的字段结果 */
    private fun doneScan(datasourceId: Long, dbName: String?, schema: String, table: String, rows: Long, columns: Int) {
        val jobId = scanRepo.insertJob(datasourceId, dbName, schema, false, "[]", 1)
        val tableId = scanRepo.insertScanTable(jobId, table, null, null, null, null)
        scanRepo.finishTable(tableId, ScanStatus.DONE, rows, null)
        for (i in 1..columns) {
            scanRepo.insertScanColumn(tableId, ScanColumnView.of("c$i", "int", null, true, null, "", rows, 0, 0, 0))
        }
    }

    private fun count(table: String): Int =
        jdbc.queryOne("SELECT COUNT(*) FROM $table") { rs -> rs.getInt(1) }!!
}
