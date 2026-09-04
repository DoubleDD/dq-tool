package com.example.dq.service

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.ManualCollectAddResult
import com.example.dq.model.ManualCollectItem
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ManualCollectRepository
import com.example.dq.repository.SchemaInit
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 人工采集服务:批量采集幂等跳过、校验(数据源/schema/表名)、删除契约、列表数据源名补齐 */
class ManualCollectServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var collectRepo: ManualCollectRepository
    private lateinit var dsRepo: DataSourceRepository
    private lateinit var service: ManualCollectService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:manual-collect-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        collectRepo = ManualCollectRepository(jdbc)
        dsRepo = DataSourceRepository(jdbc)
        service = ManualCollectService(collectRepo, dsRepo)
    }

    private fun newDs(name: String): Long = dsRepo.insert(DataSourceConfig().apply {
        this.name = name
        dbType = DbType.MYSQL
        jdbcUrl = "jdbc:mysql://localhost:3306/x"
    })

    @Test
    fun `批量采集去重跳过且列表带数据源名`() {
        val dsId = newDs("水库库")
        val item = ManualCollectItem(dsId, null, "s1", "t1", "  水库信息表  ")
        // 首次全部新增;重复提交(含请求内重复)全部跳过
        assertEquals(ManualCollectAddResult(2, 0),
            service.addBatch(listOf(item, ManualCollectItem(dsId, null, "s1", "t2", null))))
        assertEquals(ManualCollectAddResult(0, 3),
            service.addBatch(listOf(item, item, ManualCollectItem(dsId, null, "s1", "t2", null))))

        val list = service.list()
        assertEquals(2, list.size)
        assertEquals("水库库", list[0].datasourceName)
        val t1 = list.first { it.tableName == "t1" }
        assertEquals("水库信息表", t1.tableComment)   // 注释 trim 快照
        assertEquals("", t1.dbName)                  // 无库概念归一空串
        assertTrue(t1.createdAt != null)

        // 库内 map:表名 -> 记录 id
        val map = service.tableCollectMap(dsId, null, "s1")
        assertEquals(setOf("t1", "t2"), map.keys)
    }

    @Test
    fun `db空串与null同一口径`() {
        val dsId = newDs("水库库")
        service.addBatch(listOf(ManualCollectItem(dsId, null, "s1", "t1", null)))
        // db 传空串与 null 命中同一条唯一键,不重复采集
        assertEquals(ManualCollectAddResult(0, 1),
            service.addBatch(listOf(ManualCollectItem(dsId, "", "s1", "t1", null))))
        assertEquals(1, service.tableCollectMap(dsId, "", "s1").size)
    }

    @Test
    fun `采集校验与删除契约`() {
        val dsId = newDs("水库库")
        assertThrows(IllegalArgumentException::class.java) { service.addBatch(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            service.addBatch(listOf(ManualCollectItem(9999L, null, "s1", "t1", null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.addBatch(listOf(ManualCollectItem(dsId, null, " ", "t1", null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.addBatch(listOf(ManualCollectItem(dsId, null, "s1", null, null)))
        }

        service.addBatch(listOf(ManualCollectItem(dsId, null, "s1", "t1", null)))
        val id = service.list()[0].id
        service.delete(id)
        assertTrue(service.list().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { service.delete(id) }
    }

    @Test
    fun `数据源删除后记录保留且数据源名为空串`() {
        val dsId = newDs("水库库")
        service.addBatch(listOf(ManualCollectItem(dsId, null, "s1", "t1", null)))
        dsRepo.delete(dsId)
        val list = service.list()
        assertEquals(1, list.size)
        assertEquals("", list[0].datasourceName)
    }
}
