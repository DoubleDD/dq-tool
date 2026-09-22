package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.ColumnMeta
import com.example.dq.model.CompareTargetSpec
import com.example.dq.model.CreateCompareJobRequest
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.PendingReason
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.SystemSettingsRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * 比对任务「待处理(PENDING)」状态机测试(H2 内存库 + fake 字段清单):
 * createPending/confirmMapping/updatePending/start/delete/archive/recoverUnfinished/导出文件名。
 * 执行器异步跑起的任务连 127.0.0.1:1 会快速失败,断言只看「已离开 PENDING」与状态字段,不等终态
 */
class ComparePendingJobTest {

    private val config = AppConfig(dataDir = Files.createTempDirectory("compare-pending-test"))
    private val repo: CompareRepository
    private val dataSourceService: DataSourceService
    private val service: CompareService

    /** 表字段 fake:表名 → 列清单 */
    private val fakeTables = mapOf(
        "reservoir_base" to listOf("reservoir_code", "reservoir_name"),
        "t_reservoir" to listOf("v_code", "v_name"),
    )

    init {
        val h2 = JdbcDataSource()
        h2.setURL("jdbc:h2:mem:compare-pending-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(h2)
        val jdbc = Jdbc(h2)
        repo = CompareRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        val metaCacheRepo = MetaCacheRepository(jdbc)
        dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
            SchemaStatRepository(jdbc), metaCacheRepo)
        val metadataService = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc),
            SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo)
        service = CompareService(repo, dataSourceService, DialectFactory, metadataService,
            SystemSettingsService(SystemSettingsRepository(jdbc), config), TableSystemRepository(jdbc),
            columnsLister = { _, _, _, table ->
                fakeTables[table]?.map { ColumnMeta(it, "varchar(64)", 12, false, 0, false) } ?: emptyList()
            })
    }

    /** 连接必失败(端口 1 即刻拒绝)的数据源,供执行器异步任务快速落幕 */
    private fun newDs(name: String): Long = dataSourceService.create(DataSourceRequest(
        name, "jdbc:mysql://127.0.0.1:1/db_$name", "u", "p", null, null))

    private fun newPendingJob(reason: PendingReason = PendingReason.MAPPING_REVIEW,
                              mapping: Map<String, String>? = mapOf("reservoir_code" to "v_code",
                                  "reservoir_name" to "v_name")): Long {
        val baseId = newDs("base" + System.nanoTime())
        val targetId = newDs("vendor" + System.nanoTime())
        return service.createPending("任务A", baseId, "db", null, "reservoir_base",
            "reservoir_code", listOf("reservoir_code", "reservoir_name"), "reservoir_name",
            listOf(CompareService.PendingTargetSpec(targetId, "厂商库", "db", null, "t_reservoir", mapping)),
            reason, "水库", null)
    }

    @Test
    fun `createPending 落 PENDING 不进执行器 列表补新字段`() {
        val jobId = newPendingJob()
        val job = service.detail(jobId).job
        assertEquals("PENDING", job.status)
        assertEquals(PendingReason.MAPPING_REVIEW.value, job.pendingReason)
        assertEquals("水库", job.objectCategory)
        assertNull(job.startedAt) // 静止状态:未启动
        assertNull(job.durationMillis)
        // 后台任务中心不轮询 PENDING
        assertTrue(service.listActive().none { it.id == jobId })
        // 列表含 pendingReason
        assertEquals(PendingReason.MAPPING_REVIEW.value,
            service.list(false).rows.first { it.id == jobId }.pendingReason)
    }

    @Test
    fun `库schema 视图归一 单库方言 db 空 schema 回库名`() {
        // 批量导入早期口径:库名存 db、schema 留空(手工任务是 db 空、schema=库名);
        // 详情/列表视图须归一为手工口径,否则前端按 schemas/{schema}/ 拼字段接口路径会 404
        val jobId = newPendingJob() // 存的就是 db="db"、schema=null(MySQL 数据源)
        val detail = service.detail(jobId)
        assertEquals("", detail.job.baseDb)
        assertEquals("db", detail.job.baseSchema)
        assertEquals("", detail.targets.single().db)
        assertEquals("db", detail.targets.single().schema)
        val listed = service.list(false).rows.first { it.id == jobId }
        assertEquals("", listed.baseDb)
        assertEquals("db", listed.baseSchema)
        // 落库原值不动(归一只在视图层;执行路径本就有 effectiveSchema 兜底)
        val row = repo.getJob(jobId)!!
        assertEquals("db", row.baseDb)
        assertNull(row.baseSchema)
        // 纯函数:多库方言不并、schema 已有值不动、db 空不动
        assertEquals(Pair("", "hyd_ln"), CompareService.normalizeDbSchema("hyd_ln", null, false))
        assertEquals(Pair("hyd_ln", null), CompareService.normalizeDbSchema("hyd_ln", null, true))
        assertEquals(Pair("db", "dbo"), CompareService.normalizeDbSchema("db", "dbo", false))
        assertEquals(Pair("", null), CompareService.normalizeDbSchema("", null, false))
        // schema 即 catalog 的方言(MySQL 系):忽略「模式名称」列(常误填别名),db 有值一律并回 schema;
        // 库名空、库名写进「模式名称」列的现场实例兜底取模式名;本就合规的手工任务行不动
        assertEquals(Pair("", "qysglpt_WI_USER_WI_USER"),
            CompareService.normalizeDbSchema("qysglpt_WI_USER_WI_USER", "WI_USER", false, schemaIsCatalog = true))
        assertEquals(Pair("", "mydb"), CompareService.normalizeDbSchema("", "mydb", false, schemaIsCatalog = true))
        assertEquals(Pair("", "mydb"), CompareService.normalizeDbSchema("mydb", null, false, schemaIsCatalog = true))
        assertEquals(Pair("", "mydb"), CompareService.normalizeDbSchema("mydb", "WI_USER", false, schemaIsCatalog = true))
    }

    @Test
    fun `confirmMapping 非 PENDING 拒绝`() {
        val baseId = newDs("b"); val targetId = newDs("v")
        val runningId = repo.insertJob("运行中", baseId, "db", null, "reservoir_base",
            "reservoir_code", """["reservoir_code"]""", 2)
        repo.insertTarget(runningId, targetId, "厂商库", "db", null, "t_reservoir")
        assertThrows(IllegalStateException::class.java) {
            service.confirmMapping(runningId, emptyMap())
        }
    }

    @Test
    fun `confirmMapping 主键未映射报错 任务保持 PENDING`() {
        val jobId = newPendingJob()
        val targetId = repo.listTargets(jobId).single().id
        // 映射缺比对主键 → 400
        assertThrows(IllegalArgumentException::class.java) {
            service.confirmMapping(jobId, mapOf(targetId to mapOf("reservoir_name" to "v_name")))
        }
        // 映射的目标列不存在 → 400
        assertThrows(IllegalArgumentException::class.java) {
            service.confirmMapping(jobId, mapOf(targetId to mapOf("reservoir_code" to "no_such_col")))
        }
        // 目标不属于该任务 → 400
        assertThrows(IllegalArgumentException::class.java) {
            service.confirmMapping(jobId, mapOf(targetId + 9999 to mapOf("reservoir_code" to "v_code")))
        }
        assertEquals("PENDING", repo.getJob(jobId)!!.status)
    }

    @Test
    fun `confirmMapping 校验通过 PENDING 转 RUNNING 进执行器`() {
        val jobId = newPendingJob()
        val targetId = repo.listTargets(jobId).single().id
        service.confirmMapping(jobId, mapOf(targetId to mapOf(
            "reservoir_code" to "v_code", "reservoir_name" to "v_name")))
        val job = repo.getJob(jobId)!!
        // 状态已翻转(执行器异步连 127.0.0.1:1 会快速失败,只断言离开 PENDING 与翻转痕迹)
        assertNotEquals("PENDING", job.status)
        assertNull(job.pendingReason)
        assertNotNull(job.startedAt)
        // 映射已全量落库
        assertEquals(mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name"),
            service.detail(jobId).targets.single().mapping)
    }

    @Test
    fun `updatePending 仅 PENDING 可调用 替换元数据并重建目标`() {
        val baseId = newDs("b"); val targetId = newDs("v")
        val runningId = repo.insertJob("运行中", baseId, "db", null, "reservoir_base",
            "reservoir_code", """["reservoir_code"]""", 2)
        assertThrows(IllegalStateException::class.java) {
            service.updatePending(runningId, CreateCompareJobRequest(
                name = "x", baseDatasourceId = baseId, baseDb = "db", baseSchema = null,
                baseTable = "reservoir_base", keyField = "reservoir_code",
                fields = listOf("reservoir_code"),
                targets = listOf(CompareTargetSpec(targetId, "db", null, "t_reservoir"))))
        }

        val jobId = newPendingJob(PendingReason.DS_ERROR)
        val oldTargetId = repo.listTargets(jobId).single().id
        service.updatePending(jobId, CreateCompareJobRequest(
            name = "改名任务", baseDatasourceId = baseId, baseDb = "db", baseSchema = null,
            baseTable = "reservoir_base", keyField = "reservoir_code",
            fields = listOf("reservoir_code", "reservoir_name"),
            targets = listOf(CompareTargetSpec(targetId, "db", null, "t_reservoir",
                mapping = mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name")))))
        val job = repo.getJob(jobId)!!
        assertEquals("改名任务", job.name)
        assertEquals("PENDING", job.status) // 保持待处理,是否运行由 confirmMapping/start 决定
        // 编辑后映射仍需人工审核:原因归一 MAPPING_REVIEW(DS_ERROR 也被翻过)
        assertEquals(PendingReason.MAPPING_REVIEW.value, job.pendingReason)
        // 匹配逻辑/对比模式随请求改(编辑不做限制):请求未带按默认归一 EXACT/ROW,覆盖导入固定口径 CODE_NAME_LLM/COLUMN
        assertEquals("EXACT", job.matchMode)
        assertEquals("ROW", job.compareMode)
        // 旧目标删除重建
        val targets = repo.listTargets(jobId)
        assertEquals(1, targets.size)
        assertNotEquals(oldTargetId, targets[0].id)
    }

    @Test
    fun `update 终态任务编辑保存后替换元数据并直接重跑`() {
        val baseId = newDs("b"); val targetId = newDs("v")
        val jobId = repo.insertJob("老任务", baseId, "db", null, "reservoir_base",
            "reservoir_code", """["reservoir_code"]""", 2)
        val oldTargetId = repo.insertTarget(jobId, targetId, "厂商库", "db", null, "t_reservoir")
        repo.finishJob(jobId) // 置 DONE,绕开执行器
        assertEquals("DONE", repo.getJob(jobId)!!.status)

        service.update(jobId, CreateCompareJobRequest(
            name = "改名重跑", baseDatasourceId = baseId, baseDb = "db", baseSchema = null,
            baseTable = "reservoir_base", keyField = "reservoir_code",
            fields = listOf("reservoir_code", "reservoir_name"),
            matchMode = "EXACT", compareMode = "COLUMN",
            targets = listOf(CompareTargetSpec(targetId, "db", null, "t_reservoir",
                mapping = mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name")))))

        val job = repo.getJob(jobId)!!
        assertEquals("改名重跑", job.name)
        // 保存即重跑:已离开 DONE(执行器异步连 127.0.0.1:1 可能已转瞬 FAILED,两种都接受)
        assertTrue(job.status == "RUNNING" || job.status == "FAILED")
        assertEquals("EXACT", job.matchMode)
        assertEquals("COLUMN", job.compareMode)
        assertNotNull(job.startedAt)
        // 旧目标删除重建、映射落库
        val targets = repo.listTargets(jobId)
        assertEquals(1, targets.size)
        assertNotEquals(oldTargetId, targets[0].id)
        assertEquals(mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name"),
            service.detail(jobId).targets.single().mapping)

        // PENDING 走 updatePending 老口径:保持待处理不重跑
        val pendingId = newPendingJob(PendingReason.MAPPING_REVIEW)
        service.update(pendingId, CreateCompareJobRequest(
            name = "待处理改名", baseDatasourceId = baseId, baseDb = "db", baseSchema = null,
            baseTable = "reservoir_base", keyField = "reservoir_code",
            fields = listOf("reservoir_code", "reservoir_name"),
            targets = listOf(CompareTargetSpec(targetId, "db", null, "t_reservoir",
                mapping = mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name")))))
        assertEquals("PENDING", repo.getJob(pendingId)!!.status)
    }

    @Test
    fun `start DS_ERROR 拦截 MAPPING_REVIEW 放行`() {
        val dsErrorJob = newPendingJob(PendingReason.DS_ERROR)
        assertThrows(IllegalStateException::class.java) { service.start(dsErrorJob) }
        assertEquals("PENDING", repo.getJob(dsErrorJob)!!.status)

        val jobId = newPendingJob(PendingReason.MAPPING_REVIEW)
        service.start(jobId)
        val job = repo.getJob(jobId)!!
        assertNotEquals("PENDING", job.status)
        assertNull(job.pendingReason)
        // 离开 PENDING 后不能再 start(RUNNING 或异步快速失败后的 FAILED 都拦)
        assertThrows(IllegalStateException::class.java) { service.start(jobId) }
    }

    @Test
    fun `delete 与 archive 对 PENDING 放行`() {
        val jobId = newPendingJob()
        service.archive(jobId, true)
        assertTrue(repo.getJob(jobId)!!.archived)
        service.delete(jobId)
        assertNull(repo.getJob(jobId))
    }

    @Test
    fun `deleteBatch 跳过 RUNNING 与不存在 删除其余`() {
        val baseId = newDs("b"); val targetId = newDs("v")
        // insertJob 落库即 RUNNING;finishJob 置 DONE;PENDING 走 createPending
        val runningId = repo.insertJob("运行中", baseId, "db", null, "reservoir_base",
            "reservoir_code", """["reservoir_code"]""", 2)
        val doneId = repo.insertJob("完成任务", baseId, "db", null, "reservoir_base",
            "reservoir_code", """["reservoir_code"]""", 2)
        repo.finishJob(doneId)
        val pendingId = newPendingJob()

        val res = service.deleteBatch(listOf(runningId, doneId, pendingId, 999999L))
        @Suppress("UNCHECKED_CAST")
        val deleted = res["deleted"] as List<Long>
        @Suppress("UNCHECKED_CAST")
        val skipped = res["skipped"] as List<Long>
        assertEquals(setOf(doneId, pendingId), deleted.toSet())
        assertEquals(setOf(runningId, 999999L), skipped.toSet())
        assertNull(repo.getJob(doneId))
        assertNull(repo.getJob(pendingId))
        assertNotNull(repo.getJob(runningId))
    }

    @Test
    fun `recoverUnfinished 只清 RUNNING 残留 不动 PENDING`() {
        val baseId = newDs("b"); val targetId = newDs("v")
        val runningId = repo.insertJob("残留运行中", baseId, "db", null, "reservoir_base",
            "reservoir_code", """["reservoir_code"]""", 2)
        repo.insertTarget(runningId, targetId, "厂商库", "db", null, "t_reservoir")
        val pendingId = newPendingJob()

        service.recoverUnfinished()
        assertEquals("FAILED", repo.getJob(runningId)!!.status)
        val pending = repo.getJob(pendingId)!!
        assertEquals("PENDING", pending.status)
        assertEquals(PendingReason.MAPPING_REVIEW.value, pending.pendingReason)
        assertNull(pending.error)
    }

    @Test
    fun `MAPPING_RUNNING 任务 审核编辑直启均被拦`() {
        val jobId = newPendingJob(PendingReason.MAPPING_RUNNING)
        val targetId = repo.listTargets(jobId).single().id
        // 映射还在后台推导:审核/编辑/直启一律拒绝,避免与映射线程同时回写任务数据
        assertThrows(IllegalStateException::class.java) {
            service.confirmMapping(jobId, mapOf(targetId to mapOf("reservoir_code" to "v_code")))
        }
        assertThrows(IllegalStateException::class.java) { service.start(jobId) }
        assertThrows(IllegalStateException::class.java) {
            service.updatePending(jobId, CreateCompareJobRequest(
                name = "x", baseDatasourceId = 1L, baseDb = "db", baseSchema = null,
                baseTable = "reservoir_base", keyField = "reservoir_code",
                fields = listOf("reservoir_code"),
                targets = listOf(CompareTargetSpec(targetId, "db", null, "t_reservoir"))))
        }
        assertEquals(PendingReason.MAPPING_RUNNING.value, repo.getJob(jobId)!!.pendingReason)
        // 删除照常放行(映射线程回写时会发现任务已不在,自行放弃)
        service.delete(jobId)
        assertNull(repo.getJob(jobId))
    }

    @Test
    fun `completePendingMapping 仅 MAPPING_RUNNING 可回写`() {
        val jobId = newPendingJob(PendingReason.MAPPING_RUNNING)
        val targetId = repo.listTargets(jobId).single().id
        assertTrue(service.completePendingMapping(jobId, "reservoir_code",
            listOf("reservoir_code", "reservoir_name"), "reservoir_name",
            mapOf(targetId to mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name")),
            PendingReason.MAPPING_REVIEW, null))
        val job = repo.getJob(jobId)!!
        assertEquals(PendingReason.MAPPING_REVIEW.value, job.pendingReason)
        assertEquals(mapOf("reservoir_code" to "v_code", "reservoir_name" to "v_name"),
            service.detail(jobId).targets.single().mapping)
        // 原因已翻走的任务再回写返回 false(不覆盖后续人工动作)
        assertFalse(service.completePendingMapping(jobId, "reservoir_code",
            listOf("reservoir_code"), null, emptyMap(), PendingReason.IMPORT_ERROR, "x"))
        assertEquals(PendingReason.MAPPING_REVIEW.value, repo.getJob(jobId)!!.pendingReason)
    }

    @Test
    fun `recoverUnfinished 把残留 MAPPING_RUNNING 转 MAPPING_REVIEW 记中断原因`() {
        val runningMappingId = newPendingJob(PendingReason.MAPPING_RUNNING)
        val reviewId = newPendingJob(PendingReason.MAPPING_REVIEW)
        service.recoverUnfinished()
        val recovered = repo.getJob(runningMappingId)!!
        assertEquals("PENDING", recovered.status)
        assertEquals(PendingReason.MAPPING_REVIEW.value, recovered.pendingReason)
        assertTrue(recovered.error!!.contains("人工审核补线"), recovered.error)
        // 其他 PENDING 原因不受影响
        val untouched = repo.getJob(reviewId)!!
        assertEquals(PendingReason.MAPPING_REVIEW.value, untouched.pendingReason)
        assertNull(untouched.error)
    }

    @Test
    fun `导出文件名跟随任务名 非法字符清洗 空名回退 ID 前缀格式`() {
        val baseId = newDs("b")
        val id1 = repo.insertJob("水库台账-表1", baseId, "db", null, "t", "code", """["code"]""", 1)
        assertEquals("$id1-水库台账-表1.xlsx", service.exportFileName(id1))
        val id2 = repo.insertJob("a/b\\c:d*e?f\"g<h>i|j", baseId, "db", null, "t", "code", """["code"]""", 1)
        assertEquals("$id2-a_b_c_d_e_f_g_h_i_j.xlsx", service.exportFileName(id2))
        val id3 = repo.insertJob("  ", baseId, "db", null, "t", "code", """["code"]""", 1)
        assertEquals("$id3-比对总览.xlsx", service.exportFileName(id3))
    }
}
