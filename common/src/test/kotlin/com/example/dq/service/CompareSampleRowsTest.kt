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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * 数据比对抽样(V70)单测(H2 内存库 + fake 字段清单):
 * 提交校验(1~500000 之外报 400)、落库回读(submit/updatePending)、
 * 存量兼容(批量导入 createPending 不写 sample_rows = NULL 全量)。
 * 执行器异步任务连 127.0.0.1:1 会快速失败,断言只看同步校验与落库字段,不等终态
 */
class CompareSampleRowsTest {

    private val config = AppConfig(dataDir = Files.createTempDirectory("compare-sample-test"))
    private val repo: CompareRepository
    private val dataSourceService: DataSourceService
    private val service: CompareService

    /** 表字段 fake:基准/目标同名字段(无映射按字段名自动匹配) */
    private val fakeTables = mapOf(
        "base_t" to listOf("code", "name"),
        "t_a" to listOf("code", "name"),
    )

    init {
        val h2 = JdbcDataSource()
        h2.setURL("jdbc:h2:mem:compare-sample-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(h2)
        val jdbc = Jdbc(h2)
        repo = CompareRepository(jdbc)
        val metaCacheRepo = MetaCacheRepository(jdbc)
        dataSourceService = DataSourceService(DataSourceRepository(jdbc), CryptoUtil(config),
            DialectFactory, config, SchemaStatRepository(jdbc), metaCacheRepo)
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

    private fun req(baseDsId: Long, targetDsId: Long, sampleRows: Int?) = CreateCompareJobRequest(
        name = "抽样测试" + System.nanoTime(), baseDatasourceId = baseDsId, baseDb = "db",
        baseSchema = null, baseTable = "base_t", keyField = "code",
        fields = listOf("code", "name"),
        targets = listOf(CompareTargetSpec(targetDsId, "db", null, "t_a")),
        sampleRows = sampleRows)

    @Test
    fun `提交 抽样条数越界报 400`() {
        val baseId = newDs("b"); val targetId = newDs("a")
        for (bad in listOf(0, -1, 500_001)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                service.submit(req(baseId, targetId, bad))
            }
            assertTrue(e.message!!.contains("抽样条数"), e.message)
        }
    }

    @Test
    fun `提交 抽样条数落库回读 留空为全量`() {
        val baseId = newDs("b"); val targetId = newDs("a")
        // 正常值:落库 + 详情/列表视图透出
        val jobId = service.submit(req(baseId, targetId, 100))
        assertEquals(100, repo.getJob(jobId)!!.sampleRows)
        assertEquals(100, service.detail(jobId).job.sampleRows)
        assertEquals(100, service.list(false).rows.first { it.id == jobId }.sampleRows)
        // 边界值 1 与 500000 放行
        assertEquals(1, repo.getJob(service.submit(req(baseId, targetId, 1)))!!.sampleRows)
        assertEquals(500_000, repo.getJob(service.submit(req(baseId, targetId, 500_000)))!!.sampleRows)
        // 留空 = 全量比对(旧行为)
        assertNull(repo.getJob(service.submit(req(baseId, targetId, null)))!!.sampleRows)
    }

    @Test
    fun `待处理任务编辑可改抽样条数 清空回落全量`() {
        val baseId = newDs("pb"); val targetId = newDs("pa")
        val jobId = service.createPending("抽样待处理" + System.nanoTime(), baseId, "db", null, "base_t",
            "code", listOf("code", "name"), "name",
            listOf(CompareService.PendingTargetSpec(targetId, "厂商库", "db", null, "t_a", null)),
            PendingReason.MAPPING_REVIEW, null, null)
        // 批量导入产物不写 sample_rows:NULL = 全量(兼容铁律,不动导入格式)
        assertNull(repo.getJob(jobId)!!.sampleRows)
        // 编辑填上抽样条数
        service.updatePending(jobId, req(baseId, targetId, 200))
        assertEquals(200, repo.getJob(jobId)!!.sampleRows)
        // 再编辑清空 = 回到全量
        service.updatePending(jobId, req(baseId, targetId, null))
        assertNull(repo.getJob(jobId)!!.sampleRows)
    }
}
