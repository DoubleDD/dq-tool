package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.ColumnMeta
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.PendingReason
import com.example.dq.model.TableStat
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
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * 库/schema/表名位置归一(reconcileLocation)测试:
 * 手写路径(比对导入表格)落库的名称按元数据清单归一为实际值——大小写校正、
 * 单库方言(MySQL 等)schema 槽位误填「模式名称」时回退 db 槽位命中、清单读不到保持原值;
 * 清单全部经注入 fake,不连业务库
 */
class CompareReconcileLocationTest {

    private val config = AppConfig(dataDir = Files.createTempDirectory("compare-reconcile-test"))
    private val repo: CompareRepository
    private val jdbc: Jdbc
    private val dataSourceService: DataSourceService
    private val metadataService: MetadataService
    private val service: CompareService
    private val mysqlDsId: Long
    private val mssqlDsId: Long

    /** MySQL 数据源 schema 清单(服务器实际报告小写) */
    private val mysqlSchemas = listOf("qysglpt_wi_user_wi_user", "hzhz_orcl_lnhzz")

    init {
        val h2 = JdbcDataSource()
        h2.setURL("jdbc:h2:mem:compare-reconcile-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(h2)
        jdbc = Jdbc(h2)
        repo = CompareRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        val metaCacheRepo = MetaCacheRepository(jdbc)
        dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
            SchemaStatRepository(jdbc), metaCacheRepo)
        metadataService = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc),
            SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo)
        mysqlDsId = dataSourceService.create(DataSourceRequest(
            "mysql", "jdbc:mysql://127.0.0.1:1/qysglpt_wi_user_wi_user", "u", "p", null, null))
        mssqlDsId = dataSourceService.create(DataSourceRequest(
            "mssql", "jdbc:sqlserver://127.0.0.1:1;databaseName=fxkhzhxxpt_basic", "u", "p", null, null))
        service = newService(
            databaseLister = { dsId -> if (dsId == mssqlDsId) listOf("fxkhzhxxpt_basic") else emptyList() },
            schemaLister = { dsId, db ->
                when {
                    dsId == mysqlDsId -> mysqlSchemas
                    dsId == mssqlDsId && db == "fxkhzhxxpt_basic" -> listOf("dbo")
                    else -> emptyList()
                }
            },
            tablesLister = { _, _, schema ->
                when (schema) {
                    "qysglpt_wi_user_wi_user" -> listOf(TableStat("wi_user", 0, 0, "", ""))
                    "hzhz_orcl_lnhzz" -> listOf(TableStat("att_lk_base", 0, 0, "", ""))
                    "dbo" -> listOf(TableStat("t_a", 0, 0, "", ""))
                    else -> emptyList()
                }
            })
    }

    private fun newService(
        databaseLister: (Long) -> List<String>,
        schemaLister: (Long, String?) -> List<String>,
        tablesLister: (Long, String?, String) -> List<TableStat>,
    ) = CompareService(repo, dataSourceService, DialectFactory, metadataService,
        SystemSettingsService(SystemSettingsRepository(jdbc), config), TableSystemRepository(jdbc),
        columnsLister = { _, _, _, _ -> listOf(ColumnMeta("code", "varchar(64)", 12, true, 1, false)) },
        databaseLister = databaseLister, schemaLister = schemaLister, tablesLister = tablesLister)

    @Test
    fun `单库方言大小写归一 db槽位命中后并入schema`() {
        // 早期导入形态:base_db 存库名、schema 空,视图归一后 schema 带混合大小写
        val (db, schema, table) = service.reconcileLocation(mysqlDsId, "", "QYSGLPT_WI_USER_WI_USER", "WI_USER")
        assertEquals("", db)
        assertEquals("qysglpt_wi_user_wi_user", schema)
        assertEquals("wi_user", table)
    }

    @Test
    fun `单库方言 schema槽位误填模式名称时回退db槽位命中`() {
        // 表格「数据库名称=hzhz_ORCL_LNHZZ、模式名称=LNHZZ」:LNHZZ 不是 MySQL 真实库名,回退用 db 命中
        val (db, schema, table) = service.reconcileLocation(mysqlDsId, "hzhz_ORCL_LNHZZ", "LNHZZ", "ATT_LK_BASE")
        assertEquals("", db)
        assertEquals("hzhz_orcl_lnhzz", schema)
        assertEquals("att_lk_base", table)
    }

    @Test
    fun `多库方言 db与schema分别归一`() {
        val (db, schema, table) = service.reconcileLocation(mssqlDsId, "FXKHZHXXPT_BASIC", "DBO", "T_A")
        assertEquals("fxkhzhxxpt_basic", db)
        assertEquals("dbo", schema)
        assertEquals("t_a", table)
    }

    @Test
    fun `清单不命中时保持原值`() {
        val (db, schema, table) = service.reconcileLocation(mysqlDsId, "ghost_db", "ghost_schema", "ghost_table")
        assertEquals("ghost_db", db)
        assertEquals("ghost_schema", schema)
        assertEquals("ghost_table", table)
    }

    @Test
    fun `清单读取异常时保持原值`() {
        val broken = newService(
            databaseLister = { throw RuntimeException("断网") },
            schemaLister = { _, _ -> throw RuntimeException("断网") },
            tablesLister = { _, _, _ -> throw RuntimeException("断网") })
        val (db, schema, table) = broken.reconcileLocation(mysqlDsId, "hzhz_ORCL_LNHZZ", "LNHZZ", "ATT_LK_BASE")
        assertEquals("hzhz_ORCL_LNHZZ", db)
        assertEquals("LNHZZ", schema)
        assertEquals("ATT_LK_BASE", table)
    }

    @Test
    fun `归一并回写基准表位置 值变化时落库`() {
        val jobId = service.createPending("任务", mysqlDsId, "hzhz_ORCL_LNHZZ", "LNHZZ", "ATT_LK_BASE",
            "code", listOf("code"), "code",
            listOf(CompareService.PendingTargetSpec(mysqlDsId, "mysql", "hzhz_ORCL_LNHZZ", "LNHZZ",
                "att_lk_base", emptyMap())),
            PendingReason.MAPPING_REVIEW, null, null)
        service.reconcileAndUpdateBaseLocation(jobId, mysqlDsId, "hzhz_ORCL_LNHZZ", "LNHZZ", "ATT_LK_BASE")
        val job = repo.getJob(jobId)!!
        assertEquals("", job.baseDb)
        assertEquals("hzhz_orcl_lnhzz", job.baseSchema)
        assertEquals("att_lk_base", job.baseTable)
    }
}
