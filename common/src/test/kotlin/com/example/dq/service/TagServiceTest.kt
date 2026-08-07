package com.example.dq.service

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanStatus
import com.example.dq.model.TagKind
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.TagRepository
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 表标记服务:校验(重名 409 / 空表标记 400)、空表标记联动、统计组装口径 */
class TagServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var tagRepo: TagRepository
    private lateinit var scanRepo: ScanRepository
    private lateinit var dsRepo: DataSourceRepository
    private lateinit var service: TagService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:tag-svc-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        tagRepo = TagRepository(jdbc)
        scanRepo = ScanRepository(jdbc)
        dsRepo = DataSourceRepository(jdbc)
        service = TagService(tagRepo, dsRepo)
    }

    @Test
    fun `重名创建与改名抛状态冲突`() {
        service.create("水利对象表", null)
        assertThrows(IllegalStateException::class.java) { service.create("水利对象表", "#FF0000") }

        val other = service.create("防洪业务表", null)
        assertThrows(IllegalStateException::class.java) { service.update(other.id, "水利对象表", null) }
        // 改成自己的名字不算重名
        service.update(other.id, "防洪业务表", "#67C23A")
        assertEquals("#67C23A", tagRepo.findById(other.id)!!.color)
    }

    @Test
    fun `空表标记不可编辑删除或手动打摘`() {
        val empty = tagRepo.findEmptyTag()!!
        assertThrows(IllegalArgumentException::class.java) { service.update(empty.id, "改名", null) }
        assertThrows(IllegalArgumentException::class.java) { service.delete(empty.id) }
        assertThrows(IllegalArgumentException::class.java) {
            service.replaceTableTags(1L, null, "s1", "t1", listOf(empty.id))
        }
        // 不存在的标记 id 同样 400
        assertThrows(IllegalArgumentException::class.java) {
            service.replaceTableTags(1L, null, "s1", "t1", listOf(9999L))
        }
    }

    @Test
    fun `空表标记随扫描结果自动打摘且幂等`() {
        // 空表打上;重复联动不产生重复关系
        service.syncEmptyTag(1L, null, "s1", "t1", 0L)
        service.syncEmptyTag(1L, null, "s1", "t1", 0L)
        var tags = service.tableTags(1L, null, "s1")["t1"]!!
        assertEquals(1, tags.size)
        assertEquals(TagKind.EMPTY, tags[0].kind)

        // 非空摘除;重复摘除不报错
        service.syncEmptyTag(1L, null, "s1", "t1", 5L)
        service.syncEmptyTag(1L, null, "s1", "t1", 5L)
        assertTrue(service.tableTags(1L, null, "s1").isEmpty())

        // 手动打的 USER 标记不受联动影响
        val userTag = service.create("水利对象表", null)
        service.replaceTableTags(1L, null, "s1", "t2", listOf(userTag.id))
        service.syncEmptyTag(1L, null, "s1", "t2", 0L)
        tags = service.tableTags(1L, null, "s1")["t2"]!!
        assertEquals(setOf("水利对象表", "空表"), tags.map { it.name }.toSet())
    }

    @Test
    fun `标记统计组装口径`() {
        val dsId = dsRepo.insert(DataSourceConfig().apply {
            name = "水库库"
            dbType = DbType.MYSQL
            jdbcUrl = "jdbc:mysql://localhost:3306/x"
        })
        val tagA = service.create("水利对象表", null)
        val tagB = service.create("防洪业务表", null)
        // t1 打 A+B 且已扫描(100 行 4 列);t2 只打 A 且未扫描
        service.replaceTableTags(dsId, null, "s1", "t1", listOf(tagA.id, tagB.id))
        service.replaceTableTags(dsId, null, "s1", "t2", listOf(tagA.id))
        val jobId = scanRepo.insertJob(dsId, null, "s1", false, "[]", 1)
        val tableId = scanRepo.insertScanTable(jobId, "t1", null, null, null, null)
        scanRepo.finishTable(tableId, ScanStatus.DONE, 100L, null)
        repeat(4) { i ->
            scanRepo.insertScanColumn(tableId, ScanColumnView.of("c$i", "int", null, true, null, "", 100, 0, 0, 0))
        }

        val statsA = service.stats(tagA.id)
        assertEquals("水利对象表", statsA.tag.name)
        assertEquals(2, statsA.totalTables)               // t2 未扫描也计表数
        assertEquals(100L, statsA.totalRows)              // t2 不计入行/列
        assertEquals(4L, statsA.totalColumns)
        assertEquals(2, statsA.coveredTables)             // t1 打两个标记只算一次
        assertEquals(1, statsA.schemas.size)
        assertEquals("水库库", statsA.schemas[0].datasourceName)
        assertEquals(2, statsA.schemas[0].tableCount)

        // 多标记重复计入:B 的总表数也含 t1,行/列取同一快照
        val statsB = service.stats(tagB.id)
        assertEquals(1, statsB.totalTables)
        assertEquals(100L, statsB.totalRows)
        assertEquals(2, statsB.coveredTables)

        // 全部打标表都未扫描时行/列为 null(前端显示「—」)
        val tagC = service.create("未扫描标记", null)
        service.replaceTableTags(dsId, null, "s1", "t2", listOf(tagC.id))
        val statsC = service.stats(tagC.id)
        assertEquals(1, statsC.totalTables)
        assertNull(statsC.totalRows)
        assertNull(statsC.totalColumns)

        // 库维度计数:replace 后 t2 只剩标记C
        val schemaStats = service.schemaTagStats(dsId, null)
        assertEquals(1, schemaStats.size)
        assertEquals("s1", schemaStats[0].schemaName)
        val counts = schemaStats[0].tags.associate { it.tagName to it.count }
        assertEquals(1, counts["水利对象表"])
        assertEquals(1, counts["防洪业务表"])
        assertEquals(1, counts["未扫描标记"])
    }
}
