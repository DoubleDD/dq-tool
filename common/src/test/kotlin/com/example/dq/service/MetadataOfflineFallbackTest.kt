package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
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
import java.util.concurrent.TimeUnit

/**
 * 元数据浏览降级链路(H2 内存库 + 真实 DataSourceService 指向不可达地址):
 * refresh=true 强制回源失败时应返回本地缓存、写数据源网络不可达标记、置降级标志。
 * 数据源地址用 127.0.0.1:1(立即 ECONNREFUSED),测试不会真的等待连接超时
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MetadataOfflineFallbackTest {

    private lateinit var dsRepo: DataSourceRepository
    private lateinit var dsService: DataSourceService
    private lateinit var metaCacheRepo: MetaCacheRepository
    private lateinit var statRepo: SchemaStatRepository
    private lateinit var metadata: MetadataService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:metadata-offline-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        dsRepo = DataSourceRepository(jdbc)
        metaCacheRepo = MetaCacheRepository(jdbc)
        statRepo = SchemaStatRepository(jdbc)
        val config = AppConfig(dataDir = Files.createTempDirectory("metadata-offline"))
        dsService = DataSourceService(
            dsRepo, CryptoUtil(config), DialectFactory, config,
            SchemaStatRepository(jdbc), metaCacheRepo,
        )
        metadata = MetadataService(
            dsService, DialectFactory, ScanRepository(jdbc),
            SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo,
        )
    }

    @Test
    fun `刷新时数据源不可达降级返回本地缓存并标记网络不可达`() {
        val dsId = dsService.create(
            DataSourceRequest("离线库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        // 预置本地缓存:模拟此前联网时已同步过该 schema 的表结构
        metaCacheRepo.replaceTables(
            dsId, "", "db1",
            listOf(MetaCacheRepository.CachedTable("t1", "表1", "InnoDB", 10L, 100L))
        )

        // refresh=true 强制回源,127.0.0.1:1 连不上 → 应降级返回缓存而不是抛异常
        val tables = metadata.listTables(dsId, null, "db1", refresh = true)

        assertThat(tables).extracting<String> { it.name }.containsExactly("t1")
        assertThat(metadata.consumeCacheFallback()).isTrue()
        val marked = dsRepo.findById(dsId)!!
        assertThat(marked.connStatus).isEqualTo("ERROR")
        assertThat(marked.connKind).isEqualTo("UNREACHABLE")
        assertThat(marked.connError).isNotBlank()
        assertThat(marked.connCheckedAt).isNotNull()
    }

    @Test
    fun `无缓存时回源失败原样抛出并仍写标记`() {
        val dsId = dsService.create(
            DataSourceRequest("空缓存库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        val thrown = runCatching { metadata.listTables(dsId, null, "db1", refresh = true) }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(metadata.consumeCacheFallback()).isFalse()
        assertThat(dsRepo.findById(dsId)!!.connStatus).isEqualTo("ERROR")
    }

    @Test
    fun `字段总数与DDL断网时降级返回缓存`() {
        val dsId = dsService.create(
            DataSourceRequest("离线DDL库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        // 预置本地缓存:模拟此前联网时已同步过
        metaCacheRepo.replaceColumnCount(dsId, "", "db1", 42L)
        metaCacheRepo.replaceDdl(dsId, "", "db1", "t1", "CREATE TABLE t1 (id INT)")

        // refresh=true 强制回源失败 → 降级返回缓存
        assertThat(metadata.countColumns(dsId, null, "db1", refresh = true)).isEqualTo(42L)
        assertThat(metadata.consumeCacheFallback()).isTrue()
        assertThat(metadata.tableDdl(dsId, null, "db1", "t1", refresh = true))
            .isEqualTo("CREATE TABLE t1 (id INT)")
        assertThat(metadata.consumeCacheFallback()).isTrue()
        assertThat(dsRepo.findById(dsId)!!.connKind).isEqualTo("UNREACHABLE")
    }

    @Test
    fun `多库方言库清单缓存未建立且不可达时从本地沉淀推导库清单`() {
        val dsId = dsService.create(
            DataSourceRequest("离线多库", "jdbc:sqlserver://127.0.0.1:1;encrypt=false", "sa", "pw", null, null)
        )
        // 预置本地沉淀:表结构缓存(meta_table)见过 HNFA,库概览缓存(schema_stat)见过 BASIC
        metaCacheRepo.replaceTables(
            dsId, "HNFA", "dbo",
            listOf(MetaCacheRepository.CachedTable("t1", "表1", null, null, null))
        )
        statRepo.upsert(dsId, "BASIC", SchemaStatRepository.CachedStat("dbo", 2, 100L))

        // 库清单缓存从未建立 + 回源不可达 → 从本地沉淀推导库清单
        val dbs = metadata.listDatabases(dsId)

        assertThat(dbs).containsExactlyInAnyOrder("HNFA", "BASIC")
        assertThat(metadata.consumeCacheFallback()).isTrue()
    }

    @Test
    fun `schema清单缓存未建立且不可达时从库概览缓存推导`() {
        val dsId = dsService.create(
            DataSourceRequest("离线schema库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        // 预置库概览缓存:模拟库列表页此前联网时沉淀过
        statRepo.upsert(dsId, null, SchemaStatRepository.CachedStat("db1", 3, 1024L))
        statRepo.upsert(dsId, null, SchemaStatRepository.CachedStat("db2", 1, 512L))

        val schemas = metadata.listSchemas(dsId, null)

        assertThat(schemas).containsExactlyInAnyOrder("db1", "db2")
        assertThat(metadata.consumeCacheFallback()).isTrue()
    }

    @Test
    fun `单库方言库清单缓存未建立且不可达时从库概览缓存推导`() {
        val dsId = dsService.create(
            DataSourceRequest("离线单库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        statRepo.upsert(dsId, null, SchemaStatRepository.CachedStat("db1", 3, 1024L))

        // 单库方言的「库」就是 schema:库清单同样能从库概览缓存推导
        val dbs = metadata.listDatabases(dsId)

        assertThat(dbs).containsExactly("db1")
        assertThat(metadata.consumeCacheFallback()).isTrue()
    }

    @Test
    fun `本地无任何沉淀时库清单回源失败原样抛出`() {
        val dsId = dsService.create(
            DataSourceRequest("无沉淀库", "jdbc:mysql://127.0.0.1:1/nodb", "root", "pw", null, null)
        )
        val thrown = runCatching { metadata.listDatabases(dsId) }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(metadata.consumeCacheFallback()).isFalse()
        assertThat(dsRepo.findById(dsId)!!.connStatus).isEqualTo("ERROR")
    }

    @Test
    fun `多库方言按默认库名访问时兜底读空串槽位缓存`() {
        // JDBC URL 配置的默认库是 master;扫描/db='' 浏览沉淀的表缓存落在空串槽位
        val dsId = dsService.create(
            DataSourceRequest("默认库槽位", "jdbc:sqlserver://127.0.0.1:1;databaseName=master;encrypt=false", "sa", "pw", null, null)
        )
        metaCacheRepo.replaceTables(
            dsId, "", "dbo",
            listOf(MetaCacheRepository.CachedTable("t1", "表1", null, 10L, 100L))
        )

        // 比对选表器按库名 master 访问默认库:应兜底命中空串槽位缓存(无需回源)
        val tables = metadata.listTables(dsId, "master", "dbo")

        assertThat(tables).extracting<String> { it.name }.containsExactly("t1")
    }

    @Test
    fun `结构覆盖刷新会失效DDL与字段总数缓存`() {
        val dsId = 99L // 只验证仓储失效语义,无需真实数据源
        metaCacheRepo.replaceDdl(dsId, "", "db1", "t1", "ddl-1")
        metaCacheRepo.replaceColumnCount(dsId, "", "db1", 3L)
        assertThat(metaCacheRepo.getDdl(dsId, "", "db1", "t1")).isEqualTo("ddl-1")
        assertThat(metaCacheRepo.getColumnCount(dsId, "", "db1")).isEqualTo(3L)

        // 字段覆盖刷新 → 该表 DDL 与 schema 字段总数失效
        metaCacheRepo.replaceColumns(dsId, "", "db1", "t1", emptyList())
        assertThat(metaCacheRepo.getDdl(dsId, "", "db1", "t1")).isNull()
        assertThat(metaCacheRepo.getColumnCount(dsId, "", "db1")).isNull()

        // 表清单覆盖刷新 → 整 schema 的单表 DDL 与字段总数失效
        metaCacheRepo.replaceDdl(dsId, "", "db1", "t1", "ddl-2")
        metaCacheRepo.replaceColumnCount(dsId, "", "db1", 5L)
        metaCacheRepo.replaceTables(dsId, "", "db1", emptyList())
        assertThat(metaCacheRepo.getDdl(dsId, "", "db1", "t1")).isNull()
        assertThat(metaCacheRepo.getColumnCount(dsId, "", "db1")).isNull()
    }
}
