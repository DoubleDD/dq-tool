package com.example.dq.scan

import com.example.dq.config.AppConfig
import com.example.dq.config.ScanConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.NullRule
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanJobView
import com.example.dq.model.ScanRequest
import com.example.dq.model.ScanStatus
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.repository.AiConfigRepository
import com.example.dq.service.AiConfigService
import com.example.dq.service.AiService
import com.example.dq.service.AutoTagService
import com.example.dq.service.DataSourceService
import com.example.dq.service.ExportService
import com.example.dq.service.MetadataService
import com.example.dq.service.ScanService
import com.example.dq.service.TagService
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 端到端:Testcontainers 起真实 MySQL/PG,走完整扫描流程(并发分段 + 空值规则 + 导出)。
 * 验证"分段累加 == 精确值"在真实数据库上成立。
 * 去 Spring 后改为手动构造:H2 内存库 + SchemaInit + 手动组装服务(对应原 dq.scan.workers=4 / chunks-per-table=10)。
 */
@Testcontainers
class ScanFlowTest {

    companion object {
        @Container
        @JvmField
        val MYSQL: MySQLContainer<*> = MySQLContainer<Nothing>("mysql:8.0")
            .withDatabaseName("dqtest")

        @Container
        @JvmField
        val PG: PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>("postgres:15")
            .withDatabaseName("dqtest")

        @Container
        @JvmField
        val MSSQL: MSSQLServerContainer<*> = MSSQLServerContainer<Nothing>(
            "mcr.microsoft.com/mssql/server:2019-CU18-ubuntu-20.04")

        private const val ROWS = 2000
    }

    private val config = AppConfig(
        dataDir = Files.createTempDirectory("dq-it"),
        scan = ScanConfig(workers = 4, chunksPerTable = 10))

    private val dataSourceService: DataSourceService
    private val scanService: ScanService
    private val exportService: ExportService
    private val metadataService: MetadataService

    init {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:dqit;DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        val scanRepo = ScanRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        val schemaStatRepo = SchemaStatRepository(jdbc)
        val crypto = CryptoUtil(config)
        val dialectFactory = DialectFactory
        dataSourceService = DataSourceService(dsRepo, crypto, dialectFactory, config, schemaStatRepo)
        val executor = ScanExecutor(config)
        val tagRepo = TagRepository(jdbc)
        val autoTagService = AutoTagService(
            AiConfigService(AiConfigRepository(jdbc), crypto, config), AiService(),
            TagService(tagRepo, dsRepo), tagRepo, scanRepo, TableDocRepository(jdbc),
            dataSourceService, dialectFactory)
        val chunkRunner = ChunkRunner(scanRepo, dataSourceService, dialectFactory, config, executor,
            TagService(tagRepo, dsRepo), autoTagService)
        scanService = ScanService(scanRepo, dsRepo, schemaStatRepo, dataSourceService,
            dialectFactory, config, executor, chunkRunner)
        exportService = ExportService(scanService)
        metadataService = MetadataService(dataSourceService, dialectFactory, scanRepo, schemaStatRepo,
            SchemaDocRepository(jdbc))
    }

    @Test
    fun `mysql全流程`() {
        seed(MYSQL.jdbcUrl, MYSQL.username, MYSQL.password, "mysql")
        val dsId = dataSourceService.create(DataSourceRequest(
            "it-mysql", MYSQL.jdbcUrl, MYSQL.username, MYSQL.password, null, null))

        // 首次访问库列表:从业务库元数据拉取表数量/占用空间并落缓存(覆盖方言聚合 SQL)
        val before = metadataService.listSchemaStats(dsId, null)
            .first { it.name == "dqtest" }
        assertEquals(1, before.tableCount)
        assertNotNull(before.sizeBytes)
        assertTrue(before.sizeBytes!! > 0)

        val jobId = scanService.createScan(ScanRequest(dsId, "dqtest", null, null, true,
            listOf(NullRule("status", listOf("0", "-1")),
                NullRule("remark", listOf("N/A"))), null))

        val job = awaitDone(jobId)
        assertEquals(ScanStatus.DONE, job.status) { "任务失败: " + job.error }
        assertEquals(100.0, job.progressPercent, 0.01)

        val users = job.tables!!.first { it.tableName == "users" }
        assertEquals(ScanStatus.DONE, users.status)
        assertEquals(ROWS.toLong(), users.totalRows)
        assertTrue(users.totalChunks >= 5)
        assertEquals(users.totalChunks, users.doneChunks)
        // 表级元数据:注释 + 存储引擎
        assertEquals("用户表", users.comment)
        assertEquals("InnoDB", users.storageInfo)

        val cols = scanService.getColumns(jobId, "users").associateBy { it.columnName }
        // 字段级元数据:注释/类型长度/键约束/可空
        assertEquals("姓名", cols["name"]!!.columnComment)
        assertTrue(cols["name"]!!.columnType!!.lowercase().startsWith("varchar(50)"))
        assertEquals("PK", cols["id"]!!.keyLabel)
        assertEquals(false, cols["id"]!!.nullable)
        // name:每 10 行 NULL → 200;每 7 行空串(排除同时为 10 的倍数的 28 行)→ 285-28=257
        val name = cols["name"]!!
        assertEquals(ROWS.toLong(), name.totalRows)
        assertEquals(200L, name.nullCount)
        assertEquals(257L, name.emptyCount)
        // status:NULL 200 行;0 和 -1 各 100 行 → 规则命中 200
        val status = cols["status"]!!
        assertEquals(200L, status.nullCount)
        assertEquals(200L, status.ruleHitCount)
        // remark:'N/A' 100 行
        assertEquals(100L, cols["remark"]!!.ruleHitCount)

        // 导出
        val out = ByteArrayOutputStream()
        exportService.export(jobId, out)
        assertTrue(out.size() > 1000)
        assertEquals('P'.code.toByte(), out.toByteArray()[0]) // xlsx 是 zip

        // 「字段汇总」sheet:数据行数 == 所有 DONE 表字段数之和(第 0 行是表头)
        XSSFWorkbook(ByteArrayInputStream(out.toByteArray())).use { wb ->
            val summary = wb.getSheet("字段汇总")
            assertNotNull(summary, "导出应包含「字段汇总」sheet")
            val expected = job.tables!!.filter { it.status == ScanStatus.DONE }
                .sumOf { scanService.getColumns(jobId, it.tableName!!).size }
            assertEquals(expected, summary.lastRowNum)
        }

        // DONE 的任务不允许续扫
        try {
            scanService.resume(jobId)
            throw AssertionError("DONE 任务续扫应抛异常")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("续扫"))
        }

        // 库列表页概览:表数量 + 占用空间 + 最近扫描状态(扫描后读缓存)
        val dqtest = metadataService.listSchemaStats(dsId, null)
            .first { it.name == "dqtest" }
        assertEquals(1, dqtest.tableCount)
        assertNotNull(dqtest.sizeBytes)
        assertEquals("DONE", dqtest.lastScanStatus)
        assertNotNull(dqtest.lastScanAt)
    }

    @Test
    fun `postgres全流程`() {
        seed(PG.jdbcUrl, PG.username, PG.password, "pg")
        val dsId = dataSourceService.create(DataSourceRequest(
            "it-pg", PG.jdbcUrl, PG.username, PG.password, null, null))

        val jobId = scanService.createScan(ScanRequest(dsId, "public", null, null, true,
            listOf(NullRule("status", listOf("0", "-1"))), null))

        val job = awaitDone(jobId)
        assertEquals(ScanStatus.DONE, job.status) { "任务失败: " + job.error }

        val cols = scanService.getColumns(jobId, "users").associateBy { it.columnName }
        assertEquals(ROWS.toLong(), cols["name"]!!.totalRows)
        assertEquals(200L, cols["name"]!!.nullCount)
        assertEquals(257L, cols["name"]!!.emptyCount)
        assertEquals(200L, cols["status"]!!.ruleHitCount)
        // PG 元数据:表/字段注释、默认表空间显示为空
        val users = job.tables!!.first { it.tableName == "users" }
        assertEquals("用户表", users.comment)
        assertEquals("姓名", cols["name"]!!.columnComment)

        // 库列表页概览:public 有 1 张表且最近扫描 DONE
        val pub = metadataService.listSchemaStats(dsId, null)
            .first { it.name == "public" }
        assertEquals(1, pub.tableCount)
        assertNotNull(pub.sizeBytes)
        assertEquals("DONE", pub.lastScanStatus)
    }

    @Test
    fun `mssql全流程`() {
        // mssql-jdbc 12.x 默认 encrypt=true,容器自签证书需要关闭
        val url = MSSQL.jdbcUrl + ";encrypt=false"
        seed(url, MSSQL.username, MSSQL.password, "mssql")
        val dsId = dataSourceService.create(DataSourceRequest(
            "it-mssql", url, MSSQL.username, MSSQL.password, null, null))

        val jobId = scanService.createScan(ScanRequest(dsId, "dbo", null, null, true,
            listOf(NullRule("status", listOf("0", "-1"))), null))

        val job = awaitDone(jobId)
        assertEquals(ScanStatus.DONE, job.status) { "任务失败: " + job.error }

        val users = job.tables!!.first { it.tableName == "users" }
        assertEquals(ROWS.toLong(), users.totalRows)
        assertTrue(users.totalChunks >= 5)
        assertEquals("用户表", users.comment) // 扩展属性 MS_Description

        val cols = scanService.getColumns(jobId, "users").associateBy { it.columnName }
        assertEquals(200L, cols["name"]!!.nullCount)
        assertEquals(257L, cols["name"]!!.emptyCount)
        assertEquals(200L, cols["status"]!!.ruleHitCount)
        assertEquals("姓名", cols["name"]!!.columnComment)
        assertEquals("PK", cols["id"]!!.keyLabel)
    }

    @Test
    fun `mssql多库选择`() {
        // 数据源 URL 不带 databaseName,连接落在默认库;通过 database 参数选择目标库
        val url = MSSQL.jdbcUrl + ";encrypt=false"
        DriverManager.getConnection(url, MSSQL.username, MSSQL.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("IF DB_ID('testdb2') IS NULL CREATE DATABASE testdb2")
            }
        }
        DriverManager.getConnection(url + ";databaseName=testdb2",
            MSSQL.username, MSSQL.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS t2")
                st.execute("CREATE TABLE t2(id BIGINT PRIMARY KEY, v VARCHAR(50))")
                for (i in 1..100) {
                    st.execute("INSERT INTO t2 VALUES($i,'v$i')")
                }
            }
        }

        val dsId = dataSourceService.create(DataSourceRequest(
            "it-mssql-multidb", url, MSSQL.username, MSSQL.password, null, null))

        // 库列表包含 testdb2;元数据查询随 database 参数落到目标库
        assertTrue(metadataService.listDatabases(dsId).contains("testdb2"))
        assertTrue(metadataService.listSchemas(dsId, "testdb2").contains("dbo"))
        assertTrue(metadataService.listTables(dsId, "testdb2", "dbo")
            .any { it.name == "t2" })
        // 默认库下没有 t2,确认没有串库
        assertTrue(metadataService.listTables(dsId, null, "dbo")
            .none { it.name == "t2" })

        val jobId = scanService.createScan(ScanRequest(dsId, "dbo", "testdb2", null, true, listOf(), null))
        val job = awaitDone(jobId)
        assertEquals(ScanStatus.DONE, job.status) { "任务失败: " + job.error }
        assertEquals("testdb2", job.dbName)
        val t2 = job.tables!!.first { it.tableName == "t2" }
        assertEquals(100L, t2.totalRows)

        // 库列表页概览按 database 隔离:testdb2 的 dbo 有 1 张表且最近扫描 DONE
        val dbo = metadataService.listSchemaStats(dsId, "testdb2")
            .first { it.name == "dbo" }
        assertEquals(1, dbo.tableCount)
        assertNotNull(dbo.sizeBytes)
        assertEquals("DONE", dbo.lastScanStatus)
    }

    /** 造数:name 每 10 行 NULL、每 7 行空串;status 每 10 行 NULL、每 20 行 0、每 20 行错开 -1;remark 每 20 行 'N/A' */
    private fun seed(url: String, user: String, pass: String, kind: String) {
        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS users")
                when (kind) {
                    "pg" -> {
                        st.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(50), status INT, remark VARCHAR(50))")
                        st.execute("COMMENT ON TABLE users IS '用户表'")
                        st.execute("COMMENT ON COLUMN users.name IS '姓名'")
                    }
                    "mssql" -> {
                        st.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(50), status INT, remark VARCHAR(50))")
                        st.execute("EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'用户表', "
                            + "@level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'users'")
                        st.execute("EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'姓名', "
                            + "@level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'users', "
                            + "@level2type=N'COLUMN', @level2name=N'name'")
                    }
                    else -> {
                        st.execute("CREATE TABLE users(id BIGINT PRIMARY KEY COMMENT '主键', "
                            + "name VARCHAR(50) COMMENT '姓名', status INT, remark VARCHAR(50)) "
                            + "ENGINE=InnoDB COMMENT='用户表'")
                    }
                }
                for (i in 1..ROWS) {
                    val name = if (i % 10 == 0) "NULL" else if (i % 7 == 0) "''" else "'n$i'"
                    val status = if (i % 10 == 0) "NULL"
                        else (if (i % 20 == 2) 0 else if (i % 20 == 5) -1 else i).toString()
                    val remark = if (i % 20 == 0) "'N/A'" else "'r$i'"
                    st.execute("INSERT INTO users VALUES($i,$name,$status,$remark)")
                }
                // 刷新统计信息,保证分段规划拿到准确的估算行数
                when (kind) {
                    "pg" -> st.execute("ANALYZE users")
                    "mysql" -> st.execute("ANALYZE TABLE users")
                }
            }
        }
    }

    private fun awaitDone(jobId: Long): ScanJobView {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val job = scanService.getJob(jobId)
            if (job.status == ScanStatus.DONE || job.status == ScanStatus.FAILED
                || job.status == ScanStatus.CANCELED
            ) {
                return job
            }
            Thread.sleep(500)
        }
        throw AssertionError("任务超时未完成")
    }
}
