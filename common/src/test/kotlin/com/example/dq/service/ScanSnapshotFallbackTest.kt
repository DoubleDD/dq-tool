package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanStatus
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.util.CryptoUtil
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.sql.Types
import java.util.concurrent.TimeUnit

/**
 * 扫描快照降级链路(H2 内存库 + 真实 DataSourceService 指向不可达地址):
 * 本地 meta_* 无缓存且回源连接失败时,从最近一次 DONE 扫描的 scan_table/scan_column 还原结构,
 * 顺带回填 meta_* 缓存(后续访问直接命中缓存,不再降级)。
 * 数据源地址用 127.0.0.1:1(立即 ECONNREFUSED),测试不会真的等待连接超时
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScanSnapshotFallbackTest {

    private lateinit var dsRepo: DataSourceRepository
    private lateinit var dsService: DataSourceService
    private lateinit var metaCacheRepo: MetaCacheRepository
    private lateinit var scanRepo: ScanRepository
    private lateinit var metadata: MetadataService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:scan-snapshot-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        dsRepo = DataSourceRepository(jdbc)
        metaCacheRepo = MetaCacheRepository(jdbc)
        scanRepo = ScanRepository(jdbc)
        val config = AppConfig(dataDir = Files.createTempDirectory("scan-snapshot"))
        dsService = DataSourceService(
            dsRepo, CryptoUtil(config), DialectFactory, config,
            SchemaStatRepository(jdbc), metaCacheRepo,
        )
        metadata = MetadataService(
            dsService, DialectFactory, scanRepo,
            SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo,
        )
    }

    /** 造一个 DONE 任务快照:t1(DONE,2 字段,含主键)+ t2(DONE,无字段) */
    private fun prepareSnapshot(dsId: Long): Long {
        val jobId = scanRepo.insertJob(dsId, null, "db1", false, "[]", 2)
        val t1 = scanRepo.insertScanTable(jobId, "t1", 100L, 2048L, "用户表", "InnoDB")
        val t2 = scanRepo.insertScanTable(jobId, "t2", 0L, 0L, "空表", "InnoDB")
        scanRepo.insertScanColumn(t1, ScanColumnView.of("id", "bigint(20)", "主键", false, null, "PK", 100, 0, 0, 0))
        scanRepo.insertScanColumn(t1, ScanColumnView.of("name", "varchar(50)", "姓名", true, null, "", 100, 3, 0, 0))
        scanRepo.finishTable(t1, ScanStatus.DONE, 100L, null)
        scanRepo.finishTable(t2, ScanStatus.DONE, 0L, null)
        scanRepo.updateJobStatus(jobId, ScanStatus.DONE)
        return jobId
    }

    @Test
    fun `断网无缓存时表列表从扫描快照还原并回填缓存`() {
        val dsId = dsService.create(
            DataSourceRequest("快照库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        prepareSnapshot(dsId)
        assertThat(metaCacheRepo.isTableCacheReady(dsId, "", "db1")).isFalse()

        val tables = metadata.listTables(dsId, null, "db1")

        assertThat(tables).extracting<String> { it.name }.containsExactlyInAnyOrder("t1", "t2")
        assertThat(tables.first { it.name == "t1" }.comment).isEqualTo("用户表")
        assertThat(tables.first { it.name == "t1" }.estRows).isEqualTo(100L)
        assertThat(metadata.consumeCacheFallback()).isTrue()
        // 快照已回填 meta_table:再次访问直接命中缓存,不再降级
        assertThat(metaCacheRepo.isTableCacheReady(dsId, "", "db1")).isTrue()
        metadata.listTables(dsId, null, "db1")
        assertThat(metadata.consumeCacheFallback()).isFalse()
    }

    @Test
    fun `断网无缓存时单表字段从扫描快照还原并回填缓存`() {
        val dsId = dsService.create(
            DataSourceRequest("快照字段库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        prepareSnapshot(dsId)

        val cols = metadata.listTableColumns(dsId, null, "db1", "t1")

        assertThat(cols).hasSize(2)
        val id = cols[0]
        assertThat(id.name).isEqualTo("id")
        assertThat(id.displayType).isEqualTo("bigint(20)")
        assertThat(id.primaryKey).isTrue()
        assertThat(id.nullable).isFalse()
        // 快照不含原始类型信息:jdbcType 记 OTHER(回源恢复后刷新即被真实结构覆盖)
        assertThat(id.jdbcType).isEqualTo(Types.OTHER)
        assertThat(metadata.consumeCacheFallback()).isTrue()
        assertThat(metaCacheRepo.isColumnCacheReady(dsId, "", "db1", "t1")).isTrue()
    }

    @Test
    fun `断网无缓存时字段总数从扫描快照还原`() {
        val dsId = dsService.create(
            DataSourceRequest("快照统计库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        prepareSnapshot(dsId)

        // t1 两个字段 + t2 无字段 = 2
        assertThat(metadata.countColumns(dsId, null, "db1")).isEqualTo(2L)
        assertThat(metadata.consumeCacheFallback()).isTrue()
        assertThat(metaCacheRepo.getColumnCount(dsId, "", "db1")).isEqualTo(2L)
    }

    @Test
    fun `表不在快照中时字段查询原样抛出连接异常`() {
        val dsId = dsService.create(
            DataSourceRequest("快照缺失库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        prepareSnapshot(dsId)

        val thrown = runCatching { metadata.listTableColumns(dsId, null, "db1", "t_unknown") }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(metadata.consumeCacheFallback()).isFalse()
        assertThat(metaCacheRepo.isColumnCacheReady(dsId, "", "db1", "t_unknown")).isFalse()
    }

    @Test
    fun `缓存就绪但缺表时从扫描快照补回缺失表`() {
        val dsId = dsService.create(
            DataSourceRequest("缓存缺表库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        prepareSnapshot(dsId)
        // 模拟缓存局部丢失:整粒度覆盖只写入 t1(t2 相当于从 meta_table 被删),就绪标记仍在
        metaCacheRepo.replaceTables(
            dsId, "", "db1",
            listOf(MetaCacheRepository.CachedTable("t1", "用户表", "InnoDB", 100L, 2048L))
        )
        assertThat(metaCacheRepo.isTableCacheReady(dsId, "", "db1")).isTrue()

        val tables = metadata.listTables(dsId, null, "db1")

        assertThat(tables).extracting<String> { it.name }.containsExactly("t1", "t2")
        // 缺失表已 merge 回缓存:再次访问照常返回,且不置降级标志(这不是缓存顶替回源)
        assertThat(metaCacheRepo.listTables(dsId, "", "db1").map { it.tableName }).containsExactly("t1", "t2")
        assertThat(metadata.consumeCacheFallback()).isFalse()
    }

    @Test
    fun `缓存就绪但无DONE扫描时缺表不补原样返回`() {
        val dsId = dsService.create(
            DataSourceRequest("无快照库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        metaCacheRepo.replaceTables(
            dsId, "", "db1",
            listOf(MetaCacheRepository.CachedTable("t1", "用户表", "InnoDB", 100L, 2048L))
        )

        val tables = metadata.listTables(dsId, null, "db1")

        assertThat(tables).extracting<String> { it.name }.containsExactly("t1")
    }

    @Test
    fun `快照还原取最近一个DONE任务`() {
        val dsId = dsService.create(
            DataSourceRequest("快照版本库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        prepareSnapshot(dsId)
        // 更新的 FAILED 任务(无字段快照)不应遮蔽更早的 DONE 任务
        val failedJob = scanRepo.insertJob(dsId, null, "db1", false, "[]", 1)
        scanRepo.updateJobStatus(failedJob, ScanStatus.FAILED)

        val tables = metadata.listTables(dsId, null, "db1")

        assertThat(tables).extracting<String> { it.name }.containsExactlyInAnyOrder("t1", "t2")
        assertThat(metadata.consumeCacheFallback()).isTrue()
    }
}
