package com.example.dq.repository

import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 比对任务列表筛选([CompareRepository.listJobs] + JobFilter)测试(H2 内存库):
 * 关键字/状态/基准数据源/比对模式/匹配逻辑/表的标记(基准表或任一目标表命中)
 */
class CompareJobFilterTest {

    private lateinit var jdbc: Jdbc
    private lateinit var repo: CompareRepository
    private lateinit var tagRepo: TagRepository

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:compare-filter-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = CompareRepository(jdbc)
        tagRepo = TagRepository(jdbc)
    }

    /** 落一个 RUNNING 任务;baseSchema 默认 null(单库口径,与 table_tag 空串 schema 对齐走 COALESCE) */
    private fun newJob(name: String, baseDsId: Long, baseTable: String,
                       matchMode: String? = null, compareMode: String? = null): Long =
        repo.insertJob(name, baseDsId, "db", null, baseTable, "code", "[]", 1,
            matchMode = matchMode, compareMode = compareMode)

    private fun ids(filter: CompareRepository.JobFilter): List<Long> =
        repo.listJobs(true, filter).map { it.id }

    @Test
    fun `关键字按任务名 基准表名模糊命中`() {
        val j1 = newJob("LNS_RES 数据比对", 1L, "lns_res_business")
        val j2 = newJob("水库任务", 2L, "reservoir_base_info")

        assertEquals(listOf(j1), ids(CompareRepository.JobFilter(kw = "lns_res")))
        assertEquals(listOf(j2), ids(CompareRepository.JobFilter(kw = "RESERVOIR_BASE"))) // 大小写不敏感
        assertEquals(2, ids(CompareRepository.JobFilter(kw = "res")).size) // 任务名/基准表名任一命中
    }

    @Test
    fun `状态多选与基准数据源筛选`() {
        val j1 = newJob("任务A", 1L, "t1")
        val j2 = newJob("任务B", 2L, "t2")
        jdbc.update("UPDATE compare_job SET status='DONE' WHERE id=?", j1)
        jdbc.update("UPDATE compare_job SET status='FAILED' WHERE id=?", j2)

        assertEquals(listOf(j1), ids(CompareRepository.JobFilter(status = listOf("DONE"))))
        assertEquals(2, ids(CompareRepository.JobFilter(status = listOf("DONE", "FAILED"))).size)
        assertEquals(0, ids(CompareRepository.JobFilter(status = listOf("RUNNING"))).size)
        assertEquals(listOf(j2), ids(CompareRepository.JobFilter(datasourceId = 2L)))
    }

    @Test
    fun `比对模式 行级含 compare_mode NULL 老任务`() {
        val legacy = newJob("老任务", 1L, "t1") // compare_mode NULL
        val row = newJob("行级任务", 1L, "t2", compareMode = "ROW")
        newJob("列级任务", 1L, "t3", compareMode = "COLUMN")

        assertEquals(setOf(legacy, row), ids(CompareRepository.JobFilter(compareMode = "ROW")).toSet())
        assertEquals(1, ids(CompareRepository.JobFilter(compareMode = "COLUMN")).size)
    }

    @Test
    fun `匹配逻辑 LEGACY 命中 match_mode 空老任务`() {
        val legacy = newJob("老任务", 1L, "t1")
        newJob("精确任务", 1L, "t2", matchMode = "EXACT")

        assertEquals(listOf(legacy), ids(CompareRepository.JobFilter(matchMode = "LEGACY")))
        assertEquals(1, ids(CompareRepository.JobFilter(matchMode = "EXACT")).size)
    }

    @Test
    fun `表的标记 基准表或任一目标表命中 多标记 OR`() {
        val tagA = tagRepo.create("标记A", "#409EFF")
        val tagB = tagRepo.create("标记B", "#67C23A")
        val jBase = newJob("基准带标记", 1L, "t_base")
        val jTarget = newJob("目标带标记", 2L, "t_plain")
        val jNone = newJob("无标记", 3L, "t_other")
        repo.insertTarget(jTarget, 9L, "厂商库", "db", null, "t_vendor")

        // jBase:基准表打标记A;jTarget:仅目标表打标记A;jNone:目标表打标记B
        tagRepo.ensureTableTag(tagA.id, 1L, "db", "", "t_base")
        tagRepo.ensureTableTag(tagA.id, 9L, "db", "", "t_vendor")
        repo.insertTarget(jNone, 8L, null, "db", null, "t_extra")
        tagRepo.ensureTableTag(tagB.id, 8L, "db", "", "t_extra")

        assertEquals(setOf(jBase, jTarget), ids(CompareRepository.JobFilter(tagIds = listOf(tagA.id))).toSet())
        assertEquals(listOf(jNone), ids(CompareRepository.JobFilter(tagIds = listOf(tagB.id))))
        // 多标记 OR:三个任务都命中
        assertEquals(3, ids(CompareRepository.JobFilter(tagIds = listOf(tagA.id, tagB.id))).size)
        // 未使用的标记不命中
        val tagC = tagRepo.create("标记C", "#909399")
        assertTrue(ids(CompareRepository.JobFilter(tagIds = listOf(tagC.id))).isEmpty())
    }

    @Test
    fun `筛选与归档开关组合`() {
        val j1 = newJob("任务A", 1L, "t1")
        jdbc.update("UPDATE compare_job SET archived=TRUE WHERE id=?", j1)

        assertTrue(repo.listJobs(false).none { it.id == j1 })
        assertEquals(listOf(j1), repo.listJobs(true, CompareRepository.JobFilter(kw = "任务A")).map { it.id })
    }

    @Test
    fun `分页 新的在前 总数与当前页同口径`() {
        // 25 个任务:同毫秒落库 created_at 可能并列,排序有 id DESC 兜底 = 建序倒序
        val ids = (1..25).map { newJob("任务$it", 1L, "t$it") }

        val page1 = repo.listJobs(true, page = 1, size = 10)
        assertEquals(10, page1.size)
        assertEquals(ids.takeLast(10).reversed(), page1.map { it.id })
        val page3 = repo.listJobs(true, page = 3, size = 10)
        assertEquals(ids.take(5).reversed(), page3.map { it.id })
        assertTrue(repo.listJobs(true, page = 4, size = 10).isEmpty())
        // 总数与筛选同口径:不带筛选 25,带状态筛选只数命中的
        assertEquals(25L, repo.countJobs(true))
        jdbc.update("UPDATE compare_job SET status='DONE' WHERE id=?", ids[0])
        assertEquals(1L, repo.countJobs(true, CompareRepository.JobFilter(status = listOf("DONE"))))
    }
}
