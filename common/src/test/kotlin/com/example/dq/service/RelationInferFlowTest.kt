package com.example.dq.service

import com.example.dq.config.ScanConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.AnchorField
import com.example.dq.model.AnchorFieldRequest
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.RelationInferJob
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.RelationInferJobRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TableRelationRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import com.sun.net.httpserver.HttpServer
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.sql.SQLTransientConnectionException
import java.sql.Statement
import java.time.LocalDateTime

/**
 * ER 关系推导(H2 内存库,SchemaInit 到 V35 + 真实 Repo/Service 组装):
 * 名字匹配全流程、方向归一化幂等、否决对再推导回炉为候选(含方向翻转整行刷新)/已确认不降级、候选上限截断、三态流转、
 * 手动新增、graph 组装、重启恢复;语义匹配(M2)经 HttpServer 假 LLM 端点 + 真实 AiService 覆盖:
 * 两阶段全流程(source=SEMANTIC)、单批失败容错(名字匹配结果保住+附注)、双通道命中 source 优先 NAME_MATCH、
 * 无 AI 配置拒绝;DataSourceService 打桩返回指向业务 H2 库的真实连接
 * (POSTGRESQL 方言:双引号标识符 H2 原生支持),业务表用带引号小写名建在 PUBLIC。
 */
class RelationInferFlowTest {

    private lateinit var jdbc: Jdbc
    private lateinit var relationRepo: TableRelationRepository
    private lateinit var jobRepo: RelationInferJobRepository
    private lateinit var metaCacheRepo: MetaCacheRepository
    private lateinit var tableDocRepo: TableDocRepository
    private lateinit var dataSourceService: DataSourceService
    private lateinit var systemSettingsService: SystemSettingsService
    private lateinit var aiConfigService: AiConfigService
    private lateinit var inferService: RelationInferService
    private lateinit var relationService: TableRelationService
    private lateinit var bizUrl: String

    companion object {
        private const val DS_ID = 1L
        private const val SCHEMA = "PUBLIC"
        private const val NO_AI_MESSAGE = "请先在「AI 配置」中填写完整的大模型接口信息(接口地址 / API Key / 模型)"
    }

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:relation-cfg-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        relationRepo = TableRelationRepository(jdbc)
        jobRepo = RelationInferJobRepository(jdbc)
        metaCacheRepo = MetaCacheRepository(jdbc)
        tableDocRepo = TableDocRepository(jdbc)

        bizUrl = "jdbc:h2:mem:relation-biz-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        dataSourceService = mockk()
        every { dataSourceService.get(any()) } returns DataSourceConfig().apply { dbType = DbType.POSTGRESQL }
        // 每次调用给一条新连接(用例内共享同一业务库,DDL/DML/SELECT 跨调用可见)
        every { dataSourceService.getConnection(any<Long>(), null) } answers { DriverManager.getConnection(bizUrl) }
        // 连接状态标记是辅助观测:默认打桩为空操作,各用例按需覆盖为「不可达」并 verify 调用
        justRun { dataSourceService.markConnFailure(any(), any(), any()) }
        justRun { dataSourceService.markConnRecovered(any()) }
        systemSettingsService = mockk()
        every { systemSettingsService.scanSettings() } returns ScanConfig(statementTimeoutSeconds = 30)
        // 默认无 AI 配置:useSemantic=true 提交被拒;语义用例另行 stub 或注入 fake chat
        aiConfigService = mockk()
        every { aiConfigService.requireConfig() } throws IllegalStateException(NO_AI_MESSAGE)

        inferService = newInferService()
        relationService = TableRelationService(relationRepo, metaCacheRepo)
    }

    private fun newInferService(
        candidateLimit: Int = RelationInferService.MAX_CANDIDATES,
        tableBatchSize: Int = RelationInferService.TABLE_BATCH_SIZE,
        chat: (AiConfigService.Config, String, String) -> String =
            { c, s, u -> AiService().chat(c, s, u, com.example.dq.model.AiScene.RELATION_INFER, null) },
    ) = RelationInferService(relationRepo, jobRepo, metaCacheRepo, dataSourceService,
        systemSettingsService, DialectFactory, aiConfigService, tableDocRepo, AiService(),
        chat = chat, candidateLimit = candidateLimit, tableBatchSize = tableBatchSize)

    /** 在业务库上执行 DDL/DML */
    private fun biz(block: (Statement) -> Unit) {
        DriverManager.getConnection(bizUrl).use { conn ->
            conn.createStatement().use { st -> block(st) }
        }
    }

    /** 数据源打桩:POSTGRESQL + 连接状态标记(conn_status=ERROR,checkedAt 决定是否落在新鲜窗口内) */
    private fun stubDatasourceError(checkedAt: LocalDateTime) {
        every { dataSourceService.get(any()) } returns DataSourceConfig().apply {
            dbType = DbType.POSTGRESQL
            connStatus = "ERROR"
            connKind = "UNREACHABLE"
            connCheckedAt = checkedAt
        }
    }

    /** 回填整库字段清单缓存(等价于 SQL 控制台智能提示已拉取过) */
    private fun cacheColumns(vararg rows: Pair<String, List<String>>) {
        metaCacheRepo.replaceSchemaColumns(DS_ID, "", SCHEMA, rows.flatMap { (table, cols) ->
            cols.mapIndexed { i, c -> MetaCacheRepository.CachedSchemaColumn(table, i, c, "varchar(50)", "") }
        })
    }

    /**
     * 基准 fixture:reservoir(R1~R10 唯一)→ flood_ctrl(R1~R5 各两次)/ basin(R1~R8 唯一)/
     * empty_tbl(空表)/ no_overlap(X1~X3 唯一,与 reservoir 无交集)
     */
    private fun seedBase() {
        biz { st ->
            st.execute("""CREATE TABLE "reservoir" ("res_code" VARCHAR(50) PRIMARY KEY, "rname" VARCHAR(50))""")
            for (i in 1..10) st.execute("""INSERT INTO "reservoir" VALUES ('R$i', 'n$i')""")
            st.execute("""CREATE TABLE "flood_ctrl" ("fc_id" INT PRIMARY KEY, "res_code" VARCHAR(50))""")
            for (i in 1..10) st.execute("""INSERT INTO "flood_ctrl" VALUES ($i, 'R${(i - 1) % 5 + 1}')""")
            st.execute("""CREATE TABLE "basin" ("basin_code" VARCHAR(50) PRIMARY KEY, "res_code" VARCHAR(50))""")
            for (i in 1..8) st.execute("""INSERT INTO "basin" VALUES ('B$i', 'R$i')""")
            st.execute("""CREATE TABLE "empty_tbl" ("res_code" VARCHAR(50))""")
            st.execute("""CREATE TABLE "no_overlap" ("res_code" VARCHAR(50))""")
            for (i in 1..3) st.execute("""INSERT INTO "no_overlap" VALUES ('X$i')""")
        }
        cacheColumns(
            "reservoir" to listOf("res_code", "rname"),
            "flood_ctrl" to listOf("fc_id", "res_code"),
            "basin" to listOf("basin_code", "res_code"),
            "empty_tbl" to listOf("res_code"),
            "no_overlap" to listOf("res_code"),
        )
    }

    private fun submitAndAwait(table: String = "reservoir", columns: List<String> = listOf("res_code"),
                               service: RelationInferService = inferService): RelationInferJob =
        submitAndAwaitFields(table, columns.map { AnchorFieldRequest(it) }, service)

    private fun submitAndAwaitFields(table: String, fields: List<AnchorFieldRequest>,
                                     service: RelationInferService = inferService,
                                     aliasOnly: Boolean = false): RelationInferJob {
        val jobId = service.submitInfer(DS_ID, null, SCHEMA, table, fields, false, aliasOnly)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val job = jobRepo.findById(jobId)!!
            if (job.status != "RUNNING") return job
            Thread.sleep(50)
        }
        throw AssertionError("推导任务超时未完成")
    }

    @Test
    fun `迁移跑通到 V35 两表可查`() {
        assertEquals("35", jdbc.queryOne(
            """SELECT "version" FROM "flyway_schema_history" WHERE "script"='V35__table_relation.sql'""") { it.getString(1) })
        assertEquals(0, jdbc.queryOne("SELECT COUNT(*) FROM table_relation") { it.getInt(1) })
        assertEquals(0, jdbc.queryOne("SELECT COUNT(*) FROM relation_infer_job") { it.getInt(1) })
    }

    @Test
    fun `名字匹配推导全流程 候选入库且基数置信度正确`() {
        seedBase()
        val job = submitAndAwait()

        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(4, job.totalSteps) // flood_ctrl/basin/empty_tbl/no_overlap 四对,锚点表自身已排除
        assertEquals(4, job.doneSteps)
        assertEquals(4, job.foundCount) // empty_tbl 空表不再剔除,按未验证候选入库

        val relations = relationService.list(DS_ID, null, SCHEMA, null, null)
        assertEquals(4, relations.size)

        // 一端唯一 → ONE_TO_MANY,one 侧存唯一方(reservoir);交集率 5/min(10,5)=1.0 → HIGH
        val flood = relations.first { it.manyTable == "flood_ctrl" }
        assertEquals("ONE_TO_MANY", flood.cardinality)
        assertEquals("reservoir", flood.oneTable)
        assertEquals("res_code", flood.oneColumn)
        assertEquals("CANDIDATE", flood.status)
        assertEquals("NAME_MATCH", flood.source)
        assertEquals("HIGH", flood.confidence)
        assertEquals(1.0, flood.overlapRatio!!, 0.0001)
        assertNull(flood.remark) // 本名命中不加映射备注

        // 两端唯一 → ONE_TO_ONE,字典序小者(basin)入 one 侧
        val basin = relations.first { it.cardinality == "ONE_TO_ONE" && (it.oneTable == "basin" || it.manyTable == "basin") }
        assertEquals("basin", basin.oneTable)
        assertEquals("reservoir", basin.manyTable)
        assertEquals("HIGH", basin.confidence)

        // 交集 0% 只降置信不否决:LOW 入库
        val noOverlap = relations.first { it.oneTable == "no_overlap" || it.manyTable == "no_overlap" }
        assertEquals("LOW", noOverlap.confidence)
        assertEquals(0.0, noOverlap.overlapRatio!!, 0.0001)

        // 空表(样本为空)不剔除:按未验证候选入库——交集率/置信度留空,锚点侧唯一 → ONE_TO_MANY,remark 注明待复核
        val empty = relations.first { it.oneTable == "empty_tbl" || it.manyTable == "empty_tbl" }
        assertEquals("ONE_TO_MANY", empty.cardinality)
        assertEquals("reservoir", empty.oneTable) // 有数据侧经判重 SQL 证唯一
        assertNull(empty.confidence)
        assertNull(empty.overlapRatio)
        assertTrue(empty.remark!!.contains("样本为空"), empty.remark)
    }

    @Test
    fun `字段缓存未就绪时实时拉取业务库并回填`() {
        seedBase()
        // 清掉 seedBase 回填的缓存,模拟从未浏览过该库
        jdbc.update("DELETE FROM meta_schema_column WHERE datasource_id=?", DS_ID)
        jdbc.update("DELETE FROM meta_cache_flag WHERE datasource_id=?", DS_ID)
        assertTrue(!metaCacheRepo.isSchemaColumnsReady(DS_ID, "", SCHEMA))

        val job = submitAndAwait()
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertTrue(job.foundCount >= 3)
        // 实时拉取后已整粒度回填缓存
        assertTrue(metaCacheRepo.isSchemaColumnsReady(DS_ID, "", SCHEMA))
        assertTrue(metaCacheRepo.listSchemaColumns(DS_ID, "", SCHEMA)
            .any { it.tableName == "flood_ctrl" && it.columnName == "res_code" })
    }

    @Test
    fun `数据源不可达时降级用本地结构缓存推导 候选未验证且写连接状态标记`() {
        // 场景:只浏览过部分表(per-table 字段缓存有、schema 级就绪标记没有),此时数据源已不可达
        metaCacheRepo.replaceSchemaTableColumns(DS_ID, "", SCHEMA, "reservoir",
            listOf(MetaCacheRepository.CachedSchemaColumn("reservoir", 0, "res_code", "varchar(50)", "")))
        metaCacheRepo.replaceSchemaTableColumns(DS_ID, "", SCHEMA, "basin",
            listOf(MetaCacheRepository.CachedSchemaColumn("basin", 0, "res_code", "varchar(50)", "")))
        assertFalse(metaCacheRepo.isSchemaColumnsReady(DS_ID, "", SCHEMA))
        every { dataSourceService.getConnection(any<Long>(), null) } throws
            SQLTransientConnectionException("ds-1 - Connection is not available, request timed out after 30005ms")

        val job = submitAndAwait("reservoir", listOf("res_code"))
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(1, job.foundCount)
        // 只在回源取字段清单时试一次连接;验证阶段不再重试(省一次连接池超时等待)
        verify(exactly = 1) { dataSourceService.getConnection(DS_ID, null) }
        // 连不上→写数据源连接状态标记(UNREACHABLE),并在任务附注里说明本轮是缓存降级
        verify(exactly = 1) { dataSourceService.markConnFailure(DS_ID, any(), "UNREACHABLE") }
        assertTrue(jobRepo.findById(job.id)!!.error!!.contains("数据源连接不可达"))

        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        assertTrue(rel.remark!!.contains("数据源连接不可达"), rel.remark)
        assertNull(rel.confidence) // 未做值交集 → 交集率/置信度留空
        assertNull(rel.overlapRatio)
        assertEquals("reservoir", rel.oneTable) // 无索引佐证暂按锚点侧为「一」
    }

    @Test
    fun `数据源不可达且本地无结构缓存时任务失败`() {
        every { dataSourceService.getConnection(any<Long>(), null) } throws
            SQLTransientConnectionException("ds-1 - Connection is not available, request timed out after 30005ms")

        val job = submitAndAwait("reservoir", listOf("res_code"))
        assertEquals("FAILED", job.status)
        assertTrue(job.error!!.contains("Connection is not available"), job.error)
        verify(exactly = 1) { dataSourceService.markConnFailure(DS_ID, any(), "UNREACHABLE") }
        assertTrue(relationService.list(DS_ID, null, SCHEMA, null, null).isEmpty())
    }

    @Test
    fun `已知不可达窗口内不重试连接 直接用本地结构缓存推导`() {
        // 只浏览过部分表(per-table 缓存有、schema 级就绪标记没有),且数据源刚实测失败过(标记在 TTL 内)
        metaCacheRepo.replaceSchemaTableColumns(DS_ID, "", SCHEMA, "reservoir",
            listOf(MetaCacheRepository.CachedSchemaColumn("reservoir", 0, "res_code", "varchar(50)", "")))
        metaCacheRepo.replaceSchemaTableColumns(DS_ID, "", SCHEMA, "basin",
            listOf(MetaCacheRepository.CachedSchemaColumn("basin", 0, "res_code", "varchar(50)", "")))
        stubDatasourceError(LocalDateTime.now().minusMinutes(1))

        val job = submitAndAwait("reservoir", listOf("res_code"))
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(1, job.foundCount)
        // 新鲜窗口内一次连接都不取:既不回源取字段清单,也不取验证连接(免得白等连接池超时)
        verify(exactly = 0) { dataSourceService.getConnection(any<Long>(), null) }
        verify(exactly = 0) { dataSourceService.markConnFailure(any(), any(), any()) }

        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        assertTrue(rel.remark!!.contains("数据源连接不可达"), rel.remark)
        assertNull(rel.confidence)
    }

    @Test
    fun `字段清单缓存已就绪且处于不可达窗口 验证阶段也不连库`() {
        seedBase() // schema 级字段缓存就绪:正常路径本会为验证取一次连接
        stubDatasourceError(LocalDateTime.now())

        val job = submitAndAwait()
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(4, job.foundCount)
        verify(exactly = 0) { dataSourceService.getConnection(any<Long>(), null) }
        // 全部候选未验证入库:交集率/置信度留空,remark 注明不可达
        relationService.list(DS_ID, null, SCHEMA, null, null).forEach {
            assertTrue(it.remark!!.contains("数据源连接不可达"), "${it.oneTable}.${it.oneColumn} remark=${it.remark}")
            assertNull(it.confidence)
            assertNull(it.overlapRatio)
        }
    }

    @Test
    fun `不可达标记超出 TTL 后重新实测并刷新标记`() {
        seedBase()
        stubDatasourceError(LocalDateTime.now().minusMinutes(RelationInferService.KNOWN_DOWN_TTL.toMinutes() + 5))
        every { dataSourceService.getConnection(any<Long>(), null) } throws
            SQLTransientConnectionException("ds-1 - Connection is not available, request timed out after 30005ms")

        val job = submitAndAwait()
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        // 超窗后照常实测一次(失败即重写标记时间,标记不会永久生效)
        verify(exactly = 1) { dataSourceService.getConnection(any<Long>(), null) }
        verify(exactly = 1) { dataSourceService.markConnFailure(DS_ID, any(), "UNREACHABLE") }
        relationService.list(DS_ID, null, SCHEMA, null, null).forEach { assertNull(it.confidence) }
    }

    @Test
    fun `窗口内已知不可达且本地无缓存时快速失败`() {
        stubDatasourceError(LocalDateTime.now().minusMinutes(2))

        val job = submitAndAwait("reservoir", listOf("res_code"))
        assertEquals("FAILED", job.status)
        assertTrue(job.error!!.contains("无字段结构缓存可兜"), job.error)
        verify(exactly = 0) { dataSourceService.getConnection(any<Long>(), null) }
    }

    @Test
    fun `两端互换推导命中唯一键幂等不重复`() {
        biz { st ->
            st.execute("""CREATE TABLE "zebra" ("code" VARCHAR(50) PRIMARY KEY)""")
            st.execute("""CREATE TABLE "apple" ("code" VARCHAR(50) PRIMARY KEY)""")
            for (i in 1..5) {
                st.execute("""INSERT INTO "zebra" VALUES ('V$i')""")
                st.execute("""INSERT INTO "apple" VALUES ('V$i')""")
            }
        }
        cacheColumns("zebra" to listOf("code"), "apple" to listOf("code"))

        val job1 = submitAndAwait("zebra", listOf("code"))
        assertEquals(1, job1.foundCount)
        // 方向归一化:字典序小者 apple 入 one 侧
        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        assertEquals("apple", rel.oneTable)
        assertEquals("zebra", rel.manyTable)
        assertEquals("ONE_TO_ONE", rel.cardinality)

        // 换一端为锚点再推导:命中唯一键,不重复验证不重复插入
        val job2 = submitAndAwait("apple", listOf("code"))
        assertEquals("DONE", job2.status)
        assertEquals(0, job2.foundCount)
        assertEquals(1, relationService.list(DS_ID, null, SCHEMA, null, null).size)
    }

    @Test
    fun `已否决对再推导回炉为候选 已确认对跳过且不降级`() {
        seedBase()
        submitAndAwait()
        val relations = relationService.list(DS_ID, null, SCHEMA, null, null)
        val flood = relations.first { it.manyTable == "flood_ctrl" }
        val basin = relations.first { it.oneTable == "basin" }
        relationService.reject(flood.id)
        relationService.confirm(basin.id)

        val job2 = submitAndAwait()
        assertEquals("DONE", job2.status)
        assertEquals(1, job2.foundCount) // 否决对重新验证回炉;确认对/已存在候选跳过
        // 否决对回炉为候选(同 id 刷新验证数据),确认保持确认且置信度不被新推导覆盖
        val floodAfter = relationRepo.findById(flood.id)!!
        assertEquals("CANDIDATE", floodAfter.status)
        assertEquals("HIGH", floodAfter.confidence)
        val basinAfter = relationRepo.findById(basin.id)!!
        assertEquals("CONFIRMED", basinAfter.status)
        assertEquals("HIGH", basinAfter.confidence)
        assertEquals(4, relationService.list(DS_ID, null, SCHEMA, null, null).size)
    }

    @Test
    fun `否决回炉按最新推导方向整行刷新 不产生反向重复行`() {
        // a/b 各一行且值相同:推导得 a.id↔b.id ONE_TO_ONE(字典序 a 入 one 侧)
        biz { st ->
            st.execute("""CREATE TABLE "a" ("id" VARCHAR(50) PRIMARY KEY, "code" VARCHAR(50))""")
            st.execute("""INSERT INTO "a" VALUES ('A1', 'C1')""")
            st.execute("""CREATE TABLE "b" ("id" VARCHAR(50), "code" VARCHAR(50))""")
            st.execute("""INSERT INTO "b" VALUES ('A1', 'C1')""")
        }
        cacheColumns("a" to listOf("id", "code"), "b" to listOf("id", "code"))
        // 预置一条反向存储的否决行(one=b.id, many=a.id),模拟历史推导方向与本轮相反
        val rejectedId = relationRepo.insertIfAbsent(DS_ID, "", SCHEMA,
            "b", "id", "a", "id", "ONE_TO_MANY", "REJECTED", "NAME_MATCH", null, null, null)!!

        val job = submitAndAwait("a", listOf("id", "code"))
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(2, job.foundCount) // 反向否决行回炉 + code 对新插入
        val revived = relationRepo.findById(rejectedId)!!
        assertEquals("CANDIDATE", revived.status)
        assertEquals("ONE_TO_ONE", revived.cardinality) // 方向按最新推导重算:a 入 one 侧
        assertEquals("a", revived.oneTable)
        assertEquals("b", revived.manyTable)
        assertEquals("HIGH", revived.confidence)
        assertEquals(2, relationService.list(DS_ID, null, SCHEMA, null, null).size) // 无反向重复行
    }

    @Test
    fun `confirm reject 互转与删除不限状态`() {
        seedBase()
        submitAndAwait()
        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).first { it.manyTable == "flood_ctrl" }

        relationService.confirm(rel.id)
        assertEquals("CONFIRMED", relationRepo.findById(rel.id)!!.status)
        relationService.reject(rel.id)
        assertEquals("REJECTED", relationRepo.findById(rel.id)!!.status)
        relationService.confirm(rel.id)
        assertEquals("CONFIRMED", relationRepo.findById(rel.id)!!.status)

        // 删除不限状态(确认关系也可删,误删可重新推导找回);不存在的 id 抛错
        relationService.delete(rel.id)
        assertNull(relationRepo.findById(rel.id))
        assertThrows(IllegalArgumentException::class.java) { relationService.delete(9999L) }

        val candidate = relationService.list(DS_ID, null, SCHEMA, "CANDIDATE", null).first()
        relationService.delete(candidate.id)
        assertNull(relationRepo.findById(candidate.id))
    }

    @Test
    fun `候选对超上限截断`() {
        biz { st ->
            st.execute("""CREATE TABLE "reservoir" ("res_code" VARCHAR(50) PRIMARY KEY)""")
            for (i in 1..10) st.execute("""INSERT INTO "reservoir" VALUES ('R$i')""")
            for (w in 1..8) {
                st.execute("""CREATE TABLE "w$w" ("res_code" VARCHAR(50))""")
                st.execute("""INSERT INTO "w$w" VALUES ('R1'), ('R1')""")
            }
        }
        cacheColumns("reservoir" to listOf("res_code"),
            *((1..8).map { "w$it" to listOf("res_code") }.toTypedArray()))

        val job = submitAndAwait(service = newInferService(5))
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(5, job.totalSteps) // 命中 8 对,截断到上限 5
        assertEquals(5, job.doneSteps)
        assertEquals(5, job.foundCount)
        assertEquals(5, relationService.list(DS_ID, null, SCHEMA, null, null).size)
    }

    @Test
    fun `仅映射名匹配 填了映射名的字段本名不参与搜索`() {
        seedBase()
        // res_code 填了映射名 → 只用映射名 basin_code 命中 basin;本名不参与,故不再命中 flood_ctrl/empty_tbl/no_overlap
        val job = submitAndAwaitFields("reservoir",
            listOf(AnchorFieldRequest("res_code", listOf("basin_code"))), aliasOnly = true)
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(1, job.foundCount)
        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        // 两端唯一 → ONE_TO_ONE,字典序小者 basin 入 one 侧
        assertEquals("basin", rel.oneTable)
        assertEquals("basin_code", rel.oneColumn)
        assertEquals("reservoir", rel.manyTable)
        assertTrue(rel.remark!!.contains("经映射字段名 basin_code 命中"))
        assertEquals("LOW", rel.confidence) // B1..B8 与 R1..R10 交集为 0,只降置信不否决
    }

    @Test
    fun `仅映射名匹配 未填映射名的字段不受影响仍按本名搜索`() {
        seedBase()
        // res_code 未填映射名 → 无别名可依赖,开关不影响它,本名照常参与,结果与 aliasOnly=false 一致(4 对)
        val job = submitAndAwaitFields("reservoir", listOf(AnchorFieldRequest("res_code")), aliasOnly = true)
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(4, job.totalSteps)
        assertEquals(4, job.foundCount)
        assertEquals(4, relationService.list(DS_ID, null, SCHEMA, null, null).size)
    }

    @Test
    fun `仅映射名匹配 逐字段生效 有映射名只用映射名无映射名按本名`() {
        biz { st ->
            st.execute("""CREATE TABLE "reservoir" ("res_code" VARCHAR(50) PRIMARY KEY, "rname" VARCHAR(50))""")
            st.execute("""CREATE TABLE "basin" ("basin_code" VARCHAR(50), "rname" VARCHAR(50))""")
            st.execute("""CREATE TABLE "flood_ctrl" ("res_code" VARCHAR(50))""")
        }
        cacheColumns("reservoir" to listOf("res_code", "rname"),
            "basin" to listOf("basin_code", "rname"), "flood_ctrl" to listOf("res_code"))

        val job = submitAndAwaitFields("reservoir", listOf(
            AnchorFieldRequest("res_code", listOf("basin_code")), // 填了映射名 → 只用映射名,本名 res_code 不参与
            AnchorFieldRequest("rname"),                          // 未填映射名 → 不受开关影响,仍按本名
        ), aliasOnly = true)
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        val pairs = relationService.list(DS_ID, null, SCHEMA, null, null).map {
            setOf(it.oneTable, it.oneColumn) to setOf(it.manyTable, it.manyColumn)
        }
        assertEquals(2, pairs.size)
        assertTrue(pairs.contains(setOf("reservoir", "res_code") to setOf("basin", "basin_code")))
        assertTrue(pairs.contains(setOf("reservoir", "rname") to setOf("basin", "rname")))
        // 本名被映射名顶掉的字段不会再与 flood_ctrl.res_code 建关系
        assertTrue(relationService.list(DS_ID, null, SCHEMA, null, null).none { it.oneTable == "flood_ctrl" || it.manyTable == "flood_ctrl" })
    }

    @Test
    fun `空库样本为空不剔除 无索引佐证暂按锚点侧为一`() {
        // 空库场景(用户的 smart_ugadp_v2):两端表均无数据、无索引缓存,精准命中也要能产出候选建 ER 图
        biz { st ->
            st.execute("""CREATE TABLE "d_work_order" ("id" VARCHAR(50))""")
            st.execute("""CREATE TABLE "d_task" ("work_order_id" VARCHAR(50))""")
        }
        cacheColumns("d_work_order" to listOf("id"), "d_task" to listOf("work_order_id"))

        val job = submitAndAwaitFields("d_work_order",
            listOf(AnchorFieldRequest("id", listOf("work_order_id"))), aliasOnly = true)
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(1, job.foundCount)
        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        assertEquals("ONE_TO_MANY", rel.cardinality)
        assertEquals("d_work_order", rel.oneTable) // 无从判重,暂按锚点侧为「一」
        assertEquals("id", rel.oneColumn)
        assertEquals("d_task", rel.manyTable)
        assertNull(rel.confidence) // 未验证:置信度/交集率留空
        assertNull(rel.overlapRatio)
        assertTrue(rel.remark!!.contains("经映射字段名 work_order_id 命中"), rel.remark)
        assertTrue(rel.remark!!.contains("样本为空"), rel.remark)
        assertTrue(rel.remark!!.contains("暂按锚点侧为「一」"), rel.remark)
    }

    @Test
    fun `置信度与交集率纯函数边界`() {
        assertEquals("HIGH", RelationInferService.confidenceOf(0.8))
        assertEquals("HIGH", RelationInferService.confidenceOf(1.0))
        assertEquals("MEDIUM", RelationInferService.confidenceOf(0.79))
        assertEquals("MEDIUM", RelationInferService.confidenceOf(0.01))
        assertEquals("LOW", RelationInferService.confidenceOf(0.0))

        assertEquals(1.0, RelationInferService.overlapRatio(setOf("a", "b"), setOf("a", "b", "c")), 0.0001)
        assertEquals(0.5, RelationInferService.overlapRatio(setOf("a", "b"), setOf("b", "c", "d", "e")), 0.0001)
        assertEquals(0.0, RelationInferService.overlapRatio(setOf("a"), setOf("b")), 0.0001)
        assertEquals(0.0, RelationInferService.overlapRatio(emptySet(), setOf("b")), 0.0001)

        // 方向归一化纯函数:字典序小者入 one 侧,字段名参与二级排序
        val (one, many) = RelationInferService.normalizeDirection(
            RelationInferService.ColRef("b_t", "x"), RelationInferService.ColRef("a_t", "y"))
        assertEquals("a_t", one.table)
        assertEquals("b_t", many.table)
    }

    @Test
    fun `手动新增直接 CONFIRMED 方向归一化且幂等`() {
        // 用户把 zebra 放在 one 侧:ONE_TO_ONE 方向无语义,归一化为 apple 入 one 侧
        val first = relationService.addManual(DS_ID, null, SCHEMA,
            "zebra", "code", "apple", "code", "ONE_TO_ONE", "手工确认")
        val id = first.id
        assertFalse(first.existing)
        val rel = relationRepo.findById(id)!!
        assertEquals("CONFIRMED", rel.status)
        assertEquals("MANUAL", rel.source)
        assertEquals("apple", rel.oneTable)
        assertEquals("zebra", rel.manyTable)
        assertNull(rel.confidence)
        assertNull(rel.overlapRatio)
        // 命中唯一键的重复手动新增:不重复插入,返回原 id 并保持 CONFIRMED,existing=true
        val dup = relationService.addManual(DS_ID, null, SCHEMA,
            "apple", "code", "zebra", "code", "ONE_TO_ONE", null)
        assertEquals(id, dup.id)
        assertTrue(dup.existing)
        assertEquals(1, relationService.list(DS_ID, null, SCHEMA, null, null).size)
        // ONE_TO_MANY 方向由用户指定,原样保留(one 侧=唯一方)
        val id2 = relationService.addManual(DS_ID, null, SCHEMA,
            "zebra", "code", "apple", "code2", "ONE_TO_MANY", null).id
        val rel2 = relationRepo.findById(id2)!!
        assertEquals("zebra", rel2.oneTable)
        assertEquals("apple", rel2.manyTable)

        assertThrows(IllegalArgumentException::class.java) {
            relationService.addManual(DS_ID, null, SCHEMA, "a", "x", "b", "y", "BAD_CARD", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            relationService.addManual(DS_ID, null, SCHEMA, "a", "x", "a", "x", "ONE_TO_ONE", null)
        }
        // 删除不限状态:确认关系也可删(误删可重新推导找回)
        relationService.delete(id)
        assertNull(relationRepo.findById(id))
    }

    @Test
    fun `图组装 全库总图仅确认边含孤儿表 星型含候选边`() {
        seedBase()
        metaCacheRepo.replaceTables(DS_ID, "", SCHEMA, listOf(
            MetaCacheRepository.CachedTable("reservoir", "水库表", null, null, null),
            MetaCacheRepository.CachedTable("flood_ctrl", "防汛调度表", null, null, null),
            MetaCacheRepository.CachedTable("basin", "流域表", null, null, null),
            MetaCacheRepository.CachedTable("no_overlap", null, null, null, null),
            MetaCacheRepository.CachedTable("orphan_tbl", "孤儿表", null, null, null),
        ))
        submitAndAwait()
        val relations = relationService.list(DS_ID, null, SCHEMA, null, null)
        relationService.confirm(relations.first { it.manyTable == "flood_ctrl" }.id)

        // 全库总图:仅 CONFIRMED 边;节点含无任何确认关联的孤儿表(注释取自 meta_table 缓存)
        val full = relationService.graph(DS_ID, null, SCHEMA, null, false)
        assertEquals(1, full.edges.size)
        assertEquals("CONFIRMED", full.edges[0].status)
        val nodeComments = full.nodes.associate { it.name to it.comment }
        assertEquals("孤儿表", nodeComments["orphan_tbl"])
        assertEquals("水库表", nodeComments["reservoir"])
        assertEquals("防汛调度表", nodeComments["flood_ctrl"])

        // 候选显隐开关:打开后含 CANDIDATE 边
        val fullWithCandidate = relationService.graph(DS_ID, null, SCHEMA, null, true)
        assertEquals(4, fullWithCandidate.edges.size)

        // 星型图:该表参与的 CONFIRMED+CANDIDATE 边;节点只含锚点与边两端表(孤儿表不出现)
        val star = relationService.graph(DS_ID, null, SCHEMA, "reservoir", false)
        assertEquals(4, star.edges.size)
        assertEquals(setOf("reservoir", "flood_ctrl", "basin", "no_overlap", "empty_tbl"), star.nodes.map { it.name }.toSet())

        // 否决边不入图
        relationService.reject(relations.first { it.oneTable == "basin" }.id)
        assertEquals(3, relationService.graph(DS_ID, null, SCHEMA, "reservoir", false).edges.size)
    }

    @Test
    fun `两端均重复标疑似多对多`() {
        biz { st ->
            st.execute("""CREATE TABLE "m1" ("code" VARCHAR(50))""")
            st.execute("""CREATE TABLE "m2" ("code" VARCHAR(50))""")
            for (i in 1..2) {
                st.execute("""INSERT INTO "m1" VALUES ('V$i'), ('V$i')""")
                st.execute("""INSERT INTO "m2" VALUES ('V$i'), ('V$i')""")
            }
        }
        cacheColumns("m1" to listOf("code"), "m2" to listOf("code"))

        val job = submitAndAwait("m1", listOf("code"))
        assertEquals(1, job.foundCount)
        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        assertEquals("SUSPECT_MANY_TO_MANY", rel.cardinality)
        assertNotNull(rel.remark)
        assertTrue(rel.remark!!.contains("疑似多对多"))
        assertEquals("m1", rel.oneTable) // 方向同 ONE_TO_ONE 归一化
        assertEquals("m2", rel.manyTable)
    }

    @Test
    fun `唯一索引缓存短路判唯一免查询`() {
        biz { st ->
            st.execute("""CREATE TABLE "reservoir" ("res_code" VARCHAR(50) PRIMARY KEY)""")
            for (i in 1..5) st.execute("""INSERT INTO "reservoir" VALUES ('R$i')""")
            // dup_idx 实际有重复值,但缓存索引声称 res_code 单列唯一 → 短路为唯一侧
            st.execute("""CREATE TABLE "dup_idx" ("res_code" VARCHAR(50))""")
            st.execute("""INSERT INTO "dup_idx" VALUES ('R1'), ('R1')""")
        }
        cacheColumns("reservoir" to listOf("res_code"), "dup_idx" to listOf("res_code"))
        metaCacheRepo.replaceIndexes(DS_ID, "", SCHEMA, "dup_idx",
            listOf(MetaCacheRepository.CachedIndex("uk_res_code", true, 0, "res_code")))

        val job = submitAndAwait()
        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        // dup_idx 被索引短路判为唯一:两端唯一 → ONE_TO_ONE(而非 SUSPECT_MANY_TO_MANY)
        assertEquals("ONE_TO_ONE", rel.cardinality)
        assertEquals("dup_idx", rel.oneTable) // 字典序 d < r
    }

    @Test
    fun `重启残留 RUNNING 任务标 FAILED`() {
        val id = jobRepo.insert(DS_ID, "", SCHEMA, "reservoir", listOf(AnchorField("res_code")), false)
        assertEquals("RUNNING", jobRepo.findById(id)!!.status)
        assertEquals(1, inferService.failRunningOnStartup())
        val job = jobRepo.findById(id)!!
        assertEquals("FAILED", job.status)
        assertTrue(job.error!!.contains("服务重启"))
        assertNotNull(job.finishedAt)
        // 已终态任务不受影响
        assertEquals(0, inferService.failRunningOnStartup())
    }

    @Test
    fun `无AI配置时useSemantic提交被拒 参数校验照常`() {
        // 无 AI 配置:useSemantic=true 抛 IllegalStateException(壳层映射 409),且不产生任务
        val e = assertThrows(IllegalStateException::class.java) {
            inferService.submitInfer(DS_ID, null, SCHEMA, "reservoir", listOf(AnchorFieldRequest("res_code")), true)
        }
        assertEquals(NO_AI_MESSAGE, e.message)
        assertEquals(0, jdbc.queryOne("SELECT COUNT(*) FROM relation_infer_job") { it.getInt(1) })

        // useSemantic=false 不受 AI 配置影响;参数校验照常
        assertThrows(IllegalArgumentException::class.java) {
            inferService.submitInfer(DS_ID, null, SCHEMA, " ", listOf(AnchorFieldRequest("res_code")), false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            inferService.submitInfer(DS_ID, null, SCHEMA, "reservoir", listOf(AnchorFieldRequest(" ")), false)
        }
    }

    // ---------- 锚点字段映射名(aliases) ----------

    /**
     * 映射名 fixture:d_work_order(id 唯一 W1~W8)→ f_assign(work_order_id 重复 W1~W5)/
     * g_report(workOrderId 唯一 W1~W5);关联表均不同名引用锚点字段,纯本名匹配找不到
     */
    private fun seedWorkOrder() {
        biz { st ->
            st.execute("""CREATE TABLE "d_work_order" ("id" VARCHAR(50) PRIMARY KEY, "title" VARCHAR(50))""")
            for (i in 1..8) st.execute("""INSERT INTO "d_work_order" VALUES ('W$i', 't$i')""")
            st.execute("""CREATE TABLE "f_assign" ("assign_id" INT PRIMARY KEY, "work_order_id" VARCHAR(50))""")
            for (i in 1..10) st.execute("""INSERT INTO "f_assign" VALUES ($i, 'W${(i - 1) % 5 + 1}')""")
            st.execute("""CREATE TABLE "g_report" ("rep_id" INT PRIMARY KEY, "workOrderId" VARCHAR(50))""")
            for (i in 1..5) st.execute("""INSERT INTO "g_report" VALUES ($i, 'W$i')""")
        }
        cacheColumns(
            "d_work_order" to listOf("id", "title"),
            "f_assign" to listOf("assign_id", "work_order_id"),
            "g_report" to listOf("rep_id", "workOrderId"),
        )
    }

    @Test
    fun `映射名命中全流程 实际字段名入库且 remark 注明`() {
        seedWorkOrder()
        val job = submitAndAwaitFields("d_work_order",
            listOf(AnchorFieldRequest("id", listOf("work_order_id", "workOrderId"))))

        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(2, job.totalSteps)
        assertEquals(2, job.foundCount)
        // 任务详情带 fields 结构(anchor_columns 落库 JSON 解析而来)
        assertEquals(listOf(AnchorField("id", listOf("work_order_id", "workOrderId"))), job.fields)

        val relations = relationService.list(DS_ID, null, SCHEMA, null, null)
        assertEquals(2, relations.size)

        // 重复侧 → ONE_TO_MANY,one 侧存唯一方 d_work_order.id;many 侧存其他表实际字段名
        val assign = relations.first { it.manyTable == "f_assign" }
        assertEquals("d_work_order", assign.oneTable)
        assertEquals("id", assign.oneColumn)
        assertEquals("work_order_id", assign.manyColumn)
        assertEquals("ONE_TO_MANY", assign.cardinality)
        assertEquals("经映射字段名 work_order_id 命中", assign.remark)
        assertEquals("HIGH", assign.confidence) // 交集 5/min(8,5)=1.0

        // 两端唯一 → ONE_TO_ONE(字典序 d<g,one 侧 d_work_order);驼峰实际名保留真实大小写
        val report = relations.first { it.manyTable == "g_report" }
        assertEquals("ONE_TO_ONE", report.cardinality)
        assertEquals("d_work_order", report.oneTable)
        assertEquals("workOrderId", report.manyColumn)
        assertEquals("经映射字段名 workOrderId 命中", report.remark)
    }

    @Test
    fun `映射名忽略大小写命中 remark 保留用户写法`() {
        seedWorkOrder()
        val job = submitAndAwaitFields("d_work_order",
            listOf(AnchorFieldRequest("id", listOf("WORK_ORDER_ID"))))

        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(1, job.foundCount) // g_report.workOrderId 与映射名不只是大小写差,不命中
        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        assertEquals("work_order_id", rel.manyColumn) // 实际字段名(真实大小写)
        assertEquals("经映射字段名 WORK_ORDER_ID 命中", rel.remark) // 命中用的映射名保留用户写法
    }

    @Test
    fun `映射名去空去重 任务字段JSON归一`() {
        seedWorkOrder()
        val job = submitAndAwaitFields("d_work_order",
            listOf(AnchorFieldRequest("id", listOf(" ", "work_order_id", "WORK_ORDER_ID", " work_order_id "))))

        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        // 空白剔除、忽略大小写去重(保留首次写法);落库/详情均为归一后结构
        assertEquals(listOf(AnchorField("id", listOf("work_order_id"))), job.fields)
        // 同一字段对只出一条候选
        assertEquals(1, job.totalSteps)
        assertEquals(1, job.foundCount)
        assertEquals(1, relationService.list(DS_ID, null, SCHEMA, null, null).size)
    }

    @Test
    fun `同名锚点字段重复条目归一 只出一条候选`() {
        seedWorkOrder()
        val job = submitAndAwaitFields("d_work_order", listOf(
            AnchorFieldRequest("id", listOf("work_order_id")),
            AnchorFieldRequest(" ID ", listOf("work_order_id", "workOrderId")))) // 同名(空白+大小写差异),去重保留首条

        assertEquals("DONE", job.status) { "任务失败: " + job.error }
        assertEquals(listOf(AnchorField("id", listOf("work_order_id"))), job.fields)
        assertEquals(1, job.totalSteps)
        assertEquals(1, job.foundCount)
        val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
        assertEquals("work_order_id", rel.manyColumn)
    }

    // ---------- 语义匹配通道(M2,HttpServer 假 LLM 端点 + 真实 AiService 走 HTTP) ----------

    /** 起最小假 LLM 端点:answer 返回内容字符串(200)或 null(500 模拟失败) */
    private fun startFakeLlm(answer: (body: String) -> String?): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val content = answer(String(ex.requestBody.readAllBytes()))
            val (status, resp) = if (content != null) {
                200 to "{\"choices\":[{\"message\":{\"content\":\"" + jsonEscape(content) + "\"}}]}"
            } else {
                500 to "{\"error\":{\"message\":\"boom\"}}"
            }
            val bytes = resp.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** 指向假端点的 AI 配置 stub + 走真实 HTTP 调用的推导服务 */
    private fun semanticService(port: Int, tableBatchSize: Int = RelationInferService.TABLE_BATCH_SIZE): RelationInferService {
        every { aiConfigService.requireConfig() } returns
            AiConfigService.Config("http://127.0.0.1:$port/v1", "k", "m", usingDefault = false)
        return newInferService(tableBatchSize = tableBatchSize)
    }

    private fun submitSemanticAndAwait(service: RelationInferService): RelationInferJob {
        val jobId = service.submitInfer(DS_ID, null, SCHEMA, "reservoir",
            listOf(AnchorFieldRequest("res_code")), true)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val job = jobRepo.findById(jobId)!!
            if (job.status != "RUNNING") return job
            Thread.sleep(50)
        }
        throw AssertionError("推导任务超时未完成")
    }

    /**
     * 语义 fixture:reservoir(res_code 唯一,注释「水库编码」)为锚点;
     * flood_ctrl(res_code 重复)名字匹配命中;water_gate(gate_res 唯一,注释「所属水库编码」)仅语义通道可命中
     * (字段名不同);water_gate.note 无注释(阶段二不进 prompt);bad=true 时额外建 bad_tbl(表注释,无字段注释)
     */
    private fun seedSemantic(withBadTable: Boolean = false) {
        biz { st ->
            st.execute("""CREATE TABLE "reservoir" ("res_code" VARCHAR(50) PRIMARY KEY)""")
            for (i in 1..10) st.execute("""INSERT INTO "reservoir" VALUES ('R$i')""")
            st.execute("""CREATE TABLE "flood_ctrl" ("fc_id" INT PRIMARY KEY, "res_code" VARCHAR(50))""")
            for (i in 1..10) st.execute("""INSERT INTO "flood_ctrl" VALUES ($i, 'R${(i - 1) % 5 + 1}')""")
            st.execute("""CREATE TABLE "water_gate" ("gate_res" VARCHAR(50) PRIMARY KEY, "note" VARCHAR(50))""")
            for (i in 1..5) st.execute("""INSERT INTO "water_gate" VALUES ('R$i', 'n$i')""")
            if (withBadTable) {
                st.execute("""CREATE TABLE "bad_tbl" ("x" VARCHAR(50))""")
                st.execute("""INSERT INTO "bad_tbl" VALUES ('v')""")
            }
        }
        val columns = ArrayList<MetaCacheRepository.CachedSchemaColumn>()
        columns.add(MetaCacheRepository.CachedSchemaColumn("reservoir", 0, "res_code", "varchar(50)", "水库编码"))
        columns.add(MetaCacheRepository.CachedSchemaColumn("flood_ctrl", 0, "fc_id", "int", "调度编号"))
        columns.add(MetaCacheRepository.CachedSchemaColumn("flood_ctrl", 1, "res_code", "varchar(50)", "水库编码"))
        columns.add(MetaCacheRepository.CachedSchemaColumn("water_gate", 0, "gate_res", "varchar(50)", "所属水库编码"))
        columns.add(MetaCacheRepository.CachedSchemaColumn("water_gate", 1, "note", "varchar(50)", ""))
        if (withBadTable) {
            columns.add(MetaCacheRepository.CachedSchemaColumn("bad_tbl", 0, "x", "varchar(50)", ""))
        }
        metaCacheRepo.replaceSchemaColumns(DS_ID, "", SCHEMA, columns)
        val tables = ArrayList<MetaCacheRepository.CachedTable>()
        tables.add(MetaCacheRepository.CachedTable("reservoir", "水库信息表", null, null, null))
        tables.add(MetaCacheRepository.CachedTable("flood_ctrl", "防汛调度表", null, null, null))
        // water_gate 无表注释:靠 table_doc 描述进阶段一 prompt(回退规则)
        tables.add(MetaCacheRepository.CachedTable("water_gate", null, null, null, null))
        if (withBadTable) {
            tables.add(MetaCacheRepository.CachedTable("bad_tbl", "坏表", null, null, null))
        }
        metaCacheRepo.replaceTables(DS_ID, "", SCHEMA, tables)
        tableDocRepo.upsert(DS_ID, "", SCHEMA, "water_gate", "闸门控制记录,按水库编码关联水库", "test-model")
    }

    @Test
    fun `语义匹配全流程 假端点 粗筛精判验证入库 source=SEMANTIC`() {
        seedSemantic()
        val server = startFakeLlm { body ->
            when {
                body.contains("待匹配表") -> "[{\"anchor\":\"res_code\",\"column\":\"gate_res\"}]"
                body.contains("候选表") -> "[\"water_gate\"]"
                else -> "[]"
            }
        }
        try {
            val job = submitSemanticAndAwait(semanticService(server.address.port))
            assertEquals("DONE", job.status) { "任务失败: " + job.error }
            assertTrue(job.useSemantic)
            assertEquals("VERIFY", job.stage) // 终态停留在验证阶段
            // 进度 = 1 批次粗筛 + 1 张精判表 + 2 对验证
            assertEquals(4, job.totalSteps)
            assertEquals(4, job.doneSteps)
            assertEquals(2, job.foundCount)
            assertNull(job.error)

            val relations = relationService.list(DS_ID, null, SCHEMA, null, null)
            assertEquals(2, relations.size)
            // 名字匹配命中照旧(source=NAME_MATCH)
            val flood = relations.first { it.manyTable == "flood_ctrl" }
            assertEquals("NAME_MATCH", flood.source)
            assertEquals("ONE_TO_MANY", flood.cardinality)
            // 语义命中:gate_res 字段名不同,仅语义通道能发现;两端唯一 → ONE_TO_ONE(one 侧字典序小者 reservoir)
            val gate = relations.first { it.manyTable == "water_gate" }
            assertEquals("SEMANTIC", gate.source)
            assertEquals("CANDIDATE", gate.status)
            assertEquals("ONE_TO_ONE", gate.cardinality)
            assertEquals("reservoir", gate.oneTable)
            assertEquals("res_code", gate.oneColumn)
            assertEquals("gate_res", gate.manyColumn)
            assertEquals("HIGH", gate.confidence) // 交集 5/5=1.0
            assertEquals(1.0, gate.overlapRatio!!, 0.0001)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `语义通道单批失败容错 job DONE 且名字匹配结果保住`() {
        seedSemantic(withBadTable = true)
        // batchSize=1 → 粗筛三批(bad_tbl / flood_ctrl / water_gate,按表名序),bad_tbl 批次 500
        val server = startFakeLlm { body ->
            when {
                body.contains("待匹配表") -> "[{\"anchor\":\"res_code\",\"column\":\"gate_res\"}]"
                body.contains("- bad_tbl —") -> null // 该批调用失败
                body.contains("候选表") -> if (body.contains("water_gate")) "[\"water_gate\"]" else "[]"
                else -> "[]"
            }
        }
        try {
            val job = submitSemanticAndAwait(semanticService(server.address.port, tableBatchSize = 1))
            assertEquals("DONE", job.status) { "任务失败: " + job.error }
            assertEquals(2, job.foundCount) // flood_ctrl(名字匹配)+ water_gate(语义)
            // 进度 = 3 批次粗筛 + 1 张精判表 + 2 对验证
            assertEquals(6, job.totalSteps)
            assertEquals(6, job.doneSteps)
            // 单批失败附注在 job.error,任务不炸
            assertNotNull(job.error)
            assertTrue(job.error!!.contains("语义表级粗筛第 1 批失败"), job.error)

            val relations = relationService.list(DS_ID, null, SCHEMA, null, null)
            assertEquals(2, relations.size)
            assertEquals("NAME_MATCH", relations.first { it.manyTable == "flood_ctrl" }.source)
            assertEquals("SEMANTIC", relations.first { it.manyTable == "water_gate" }.source)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `双通道命中同一字段对 source 优先 NAME_MATCH`() {
        seedSemantic()
        // 语义通道也命中 flood_ctrl.res_code(名字匹配已发现):合并为一条,source 保持 NAME_MATCH
        val server = startFakeLlm { body ->
            when {
                body.contains("待匹配表") -> "[{\"anchor\":\"res_code\",\"column\":\"res_code\"}]"
                body.contains("候选表") -> "[\"flood_ctrl\"]"
                else -> "[]"
            }
        }
        try {
            val job = submitSemanticAndAwait(semanticService(server.address.port))
            assertEquals("DONE", job.status) { "任务失败: " + job.error }
            // 进度 = 1 批次粗筛 + 1 张精判表 + 1 对验证
            assertEquals(3, job.totalSteps)
            assertEquals(1, job.foundCount)

            val rel = relationService.list(DS_ID, null, SCHEMA, null, null).single()
            assertEquals("flood_ctrl", rel.manyTable)
            assertEquals("NAME_MATCH", rel.source) // 双通道命中,名字匹配证据更硬
        } finally {
            server.stop(0)
        }
    }
}
