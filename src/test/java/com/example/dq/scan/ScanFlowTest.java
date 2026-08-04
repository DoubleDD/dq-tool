package com.example.dq.scan;

import com.example.dq.model.DataSourceRequest;
import com.example.dq.model.NullRule;
import com.example.dq.model.ScanColumnView;
import com.example.dq.model.ScanJobView;
import com.example.dq.model.ScanRequest;
import com.example.dq.model.ScanStatus;
import com.example.dq.model.ScanTableView;
import com.example.dq.service.DataSourceService;
import com.example.dq.service.ExportService;
import com.example.dq.service.MetadataService;
import com.example.dq.service.ScanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端:Testcontainers 起真实 MySQL/PG,走完整扫描流程(并发分段 + 空值规则 + 导出)。
 * 验证"分段累加 == 精确值"在真实数据库上成立。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dqit;DB_CLOSE_DELAY=-1",
        "dq.scan.workers=4",
        "dq.scan.chunks-per-table=10"
})
@Testcontainers
class ScanFlowTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("dqtest");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("dqtest");

    @Container
    static final MSSQLServerContainer<?> MSSQL = new MSSQLServerContainer<>(
            "mcr.microsoft.com/mssql/server:2019-CU18-ubuntu-20.04");

    @Autowired
    DataSourceService dataSourceService;
    @Autowired
    ScanService scanService;
    @Autowired
    ExportService exportService;
    @Autowired
    MetadataService metadataService;

    private static final int ROWS = 2000;

    @Test
    void mysql全流程() throws Exception {
        seed(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), "mysql");
        long dsId = dataSourceService.create(new DataSourceRequest(
                "it-mysql", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), null, null));

        long jobId = scanService.createScan(new ScanRequest(dsId, "dqtest", null, null, true,
                List.of(new NullRule("status", List.of("0", "-1")),
                        new NullRule("remark", List.of("N/A")))));

        ScanJobView job = awaitDone(jobId);
        assertEquals(ScanStatus.DONE, job.status(), () -> "任务失败: " + job.error());
        assertEquals(100.0, job.progressPercent(), 0.01);

        ScanTableView users = job.tables().stream()
                .filter(t -> t.tableName().equals("users")).findFirst().orElseThrow();
        assertEquals(ScanStatus.DONE, users.status());
        assertEquals(ROWS, users.totalRows());
        assertTrue(users.totalChunks() >= 5);
        assertEquals(users.totalChunks(), users.doneChunks());
        // 表级元数据:注释 + 存储引擎
        assertEquals("用户表", users.comment());
        assertEquals("InnoDB", users.storageInfo());

        Map<String, ScanColumnView> cols = scanService.getColumns(jobId, "users").stream()
                .collect(Collectors.toMap(ScanColumnView::columnName, Function.identity()));
        // 字段级元数据:注释/类型长度/键约束/可空
        assertEquals("姓名", cols.get("name").columnComment());
        assertTrue(cols.get("name").columnType().toLowerCase().startsWith("varchar(50)"));
        assertEquals("PK", cols.get("id").keyLabel());
        assertEquals(Boolean.FALSE, cols.get("id").nullable());
        // name:每 10 行 NULL → 200;每 7 行空串(排除同时为 10 的倍数的 28 行)→ 285-28=257
        ScanColumnView name = cols.get("name");
        assertEquals(ROWS, name.totalRows());
        assertEquals(200, name.nullCount());
        assertEquals(257, name.emptyCount());
        // status:NULL 200 行;0 和 -1 各 100 行 → 规则命中 200
        ScanColumnView status = cols.get("status");
        assertEquals(200, status.nullCount());
        assertEquals(200, status.ruleHitCount());
        // remark:'N/A' 100 行
        assertEquals(100, cols.get("remark").ruleHitCount());

        // 导出
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.export(jobId, out);
        assertTrue(out.size() > 1000);
        assertEquals('P', out.toByteArray()[0]); // xlsx 是 zip

        // DONE 的任务不允许续扫
        try {
            scanService.resume(jobId);
            throw new AssertionError("DONE 任务续扫应抛异常");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("续扫"));
        }
    }

    @Test
    void postgres全流程() throws Exception {
        seed(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), "pg");
        long dsId = dataSourceService.create(new DataSourceRequest(
                "it-pg", PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), null, null));

        long jobId = scanService.createScan(new ScanRequest(dsId, "public", null, null, true,
                List.of(new NullRule("status", List.of("0", "-1")))));

        ScanJobView job = awaitDone(jobId);
        assertEquals(ScanStatus.DONE, job.status(), () -> "任务失败: " + job.error());

        Map<String, ScanColumnView> cols = scanService.getColumns(jobId, "users").stream()
                .collect(Collectors.toMap(ScanColumnView::columnName, Function.identity()));
        assertEquals(ROWS, cols.get("name").totalRows());
        assertEquals(200, cols.get("name").nullCount());
        assertEquals(257, cols.get("name").emptyCount());
        assertEquals(200, cols.get("status").ruleHitCount());
        // PG 元数据:表/字段注释、默认表空间显示为空
        ScanTableView users = job.tables().stream()
                .filter(t -> t.tableName().equals("users")).findFirst().orElseThrow();
        assertEquals("用户表", users.comment());
        assertEquals("姓名", cols.get("name").columnComment());
    }

    @Test
    void mssql全流程() throws Exception {
        // mssql-jdbc 12.x 默认 encrypt=true,容器自签证书需要关闭
        String url = MSSQL.getJdbcUrl() + ";encrypt=false";
        seed(url, MSSQL.getUsername(), MSSQL.getPassword(), "mssql");
        long dsId = dataSourceService.create(new DataSourceRequest(
                "it-mssql", url, MSSQL.getUsername(), MSSQL.getPassword(), null, null));

        long jobId = scanService.createScan(new ScanRequest(dsId, "dbo", null, null, true,
                List.of(new NullRule("status", List.of("0", "-1")))));

        ScanJobView job = awaitDone(jobId);
        assertEquals(ScanStatus.DONE, job.status(), () -> "任务失败: " + job.error());

        ScanTableView users = job.tables().stream()
                .filter(t -> t.tableName().equals("users")).findFirst().orElseThrow();
        assertEquals(ROWS, users.totalRows());
        assertTrue(users.totalChunks() >= 5);
        assertEquals("用户表", users.comment()); // 扩展属性 MS_Description

        Map<String, ScanColumnView> cols = scanService.getColumns(jobId, "users").stream()
                .collect(Collectors.toMap(ScanColumnView::columnName, Function.identity()));
        assertEquals(200, cols.get("name").nullCount());
        assertEquals(257, cols.get("name").emptyCount());
        assertEquals(200, cols.get("status").ruleHitCount());
        assertEquals("姓名", cols.get("name").columnComment());
        assertEquals("PK", cols.get("id").keyLabel());
    }

    @Test
    void mssql多库选择() throws Exception {
        // 数据源 URL 不带 databaseName,连接落在默认库;通过 database 参数选择目标库
        String url = MSSQL.getJdbcUrl() + ";encrypt=false";
        try (Connection conn = DriverManager.getConnection(url, MSSQL.getUsername(), MSSQL.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("IF DB_ID('testdb2') IS NULL CREATE DATABASE testdb2");
        }
        try (Connection conn = DriverManager.getConnection(url + ";databaseName=testdb2",
                 MSSQL.getUsername(), MSSQL.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t2");
            st.execute("CREATE TABLE t2(id BIGINT PRIMARY KEY, v VARCHAR(50))");
            for (int i = 1; i <= 100; i++) {
                st.execute("INSERT INTO t2 VALUES(" + i + ",'v" + i + "')");
            }
        }

        long dsId = dataSourceService.create(new DataSourceRequest(
                "it-mssql-multidb", url, MSSQL.getUsername(), MSSQL.getPassword(), null, null));

        // 库列表包含 testdb2;元数据查询随 database 参数落到目标库
        assertTrue(metadataService.listDatabases(dsId).contains("testdb2"));
        assertTrue(metadataService.listSchemas(dsId, "testdb2").contains("dbo"));
        assertTrue(metadataService.listTables(dsId, "testdb2", "dbo").stream()
                .anyMatch(t -> t.name().equals("t2")));
        // 默认库下没有 t2,确认没有串库
        assertTrue(metadataService.listTables(dsId, null, "dbo").stream()
                .noneMatch(t -> t.name().equals("t2")));

        long jobId = scanService.createScan(new ScanRequest(dsId, "dbo", "testdb2", null, true, List.of()));
        ScanJobView job = awaitDone(jobId);
        assertEquals(ScanStatus.DONE, job.status(), () -> "任务失败: " + job.error());
        assertEquals("testdb2", job.dbName());
        ScanTableView t2 = job.tables().stream()
                .filter(t -> t.tableName().equals("t2")).findFirst().orElseThrow();
        assertEquals(100, t2.totalRows());
    }

    /** 造数:name 每 10 行 NULL、每 7 行空串;status 每 10 行 NULL、每 20 行 0、每 20 行错开 -1;remark 每 20 行 'N/A' */
    private void seed(String url, String user, String pass, String kind) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            if (kind.equals("pg")) {
                st.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(50), status INT, remark VARCHAR(50))");
                st.execute("COMMENT ON TABLE users IS '用户表'");
                st.execute("COMMENT ON COLUMN users.name IS '姓名'");
            } else if (kind.equals("mssql")) {
                st.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(50), status INT, remark VARCHAR(50))");
                st.execute("EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'用户表', "
                        + "@level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'users'");
                st.execute("EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'姓名', "
                        + "@level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'users', "
                        + "@level2type=N'COLUMN', @level2name=N'name'");
            } else {
                st.execute("CREATE TABLE users(id BIGINT PRIMARY KEY COMMENT '主键', "
                        + "name VARCHAR(50) COMMENT '姓名', status INT, remark VARCHAR(50)) "
                        + "ENGINE=InnoDB COMMENT='用户表'");
            }
            for (int i = 1; i <= ROWS; i++) {
                String name = i % 10 == 0 ? "NULL" : (i % 7 == 0 ? "''" : "'n" + i + "'");
                String status = i % 10 == 0 ? "NULL" : String.valueOf(i % 20 == 2 ? 0 : (i % 20 == 5 ? -1 : i));
                String remark = i % 20 == 0 ? "'N/A'" : "'r" + i + "'";
                st.execute("INSERT INTO users VALUES(" + i + "," + name + "," + status + "," + remark + ")");
            }
            // 刷新统计信息,保证分段规划拿到准确的估算行数
            if (kind.equals("pg")) {
                st.execute("ANALYZE users");
            } else if (kind.equals("mysql")) {
                st.execute("ANALYZE TABLE users");
            }
        }
    }

    private ScanJobView awaitDone(long jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            ScanJobView job = scanService.getJob(jobId);
            if (job.status() == ScanStatus.DONE || job.status() == ScanStatus.FAILED
                    || job.status() == ScanStatus.CANCELED) {
                return job;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("任务超时未完成");
    }
}
