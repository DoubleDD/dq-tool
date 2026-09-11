package com.example.dq.service

import com.example.dq.config.ScanConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * 本地 H2 库只读查询:语句级只读校验(首关键字白名单 + H2 解析器只读判定)、结果集口径、
 * 选 schema 执行后归还池化连接前恢复、本地库结构清单。
 * 用 H2 内存库 + 单连接池(maximumPoolSize=1)构造:同一物理连接被反复借出,
 * 便于断言会话 schema 被恢复(不串库);造数据绕过被测服务直接用池连接(写语句本就被拒)。
 */
class LocalH2ConsoleServiceTest {

    private lateinit var hikari: HikariDataSource
    private lateinit var service: LocalH2ConsoleService

    @BeforeEach
    fun setUp() {
        hikari = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:localh2-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
            maximumPoolSize = 1
        })
        val settings = mockk<SystemSettingsService>()
        every { settings.scanSettings() } returns ScanConfig(statementTimeoutSeconds = 30)
        service = LocalH2ConsoleService(hikari, settings)
        hikari.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t (id INT, name VARCHAR(50), amount DECIMAL(10,2))")
                st.execute("INSERT INTO t VALUES (1, 'a', 1.50)")
                st.execute("INSERT INTO t VALUES (2, NULL, NULL)")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        hikari.close()
    }

    /** 直接借池连接取行数(与业务无关的造数/核对用) */
    private fun rowCount(): Int = hikari.connection.use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM t").use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun tableExists(name: String): Boolean = hikari.connection.use { conn ->
        conn.metaData.getTables(null, null, name, null).use { rs -> rs.next() }
    }

    @Test
    fun `SELECT 返回列名与行数据且 NULL 保 null`() {
        val r = service.execute("""SELECT id AS "id_col", name AS "name_col" FROM t ORDER BY id""")
        assertTrue(r.query)
        assertEquals(listOf("id_col", "name_col"), r.columns)
        assertEquals(listOf(listOf("1", "a"), listOf("2", null)), r.rows)
        assertEquals(2, r.total)
        assertFalse(r.truncated)
        assertEquals(-1, r.updateCount)
        assertTrue(r.durationMs >= 0)
    }

    @Test
    fun `注释与末尾分号不影响只读查询`() {
        assertEquals(2, service.execute("-- 统计\nSELECT COUNT(*) FROM t;").rows[0][0]?.toInt())
        assertEquals(2, service.execute("/* 统计 */ SELECT COUNT(*) FROM t").rows[0][0]?.toInt())
    }

    @Test
    fun `SHOW 与 VALUES 等查询类语句放行`() {
        assertTrue(service.execute("SHOW TABLES").query)
        assertEquals("1", service.execute("VALUES (1)").rows[0][0])
        assertTrue(service.execute("TABLE t").query)
    }

    @Test
    fun `写语句一律被拒且数据与结构未被改动`() {
        val writes = listOf(
            "INSERT INTO t VALUES (3, 'c', 3)",
            "UPDATE t SET name = 'x'",
            "DELETE FROM t",
            "MERGE INTO t KEY(id) VALUES (1, 'z', 9)",
            "TRUNCATE TABLE t",
            "DROP TABLE t",
            "CREATE TABLE t2 (x INT)",
            "ALTER TABLE t ADD COLUMN extra INT",
            "GRANT SELECT ON t TO PUBLIC",
            "SET SCHEMA INFORMATION_SCHEMA",
            "CALL 1 + 1",
            "RUNSCRIPT FROM '/tmp/x.sql'",
            "SELECT CSVWRITE('/tmp/h2check/out.csv', 'SELECT 1')",
        )
        for (sql in writes) {
            assertThrows(IllegalArgumentException::class.java, { service.execute(sql) }, "应拒绝:$sql")
        }
        assertEquals(2, rowCount())
        assertFalse(tableExists("T2"))
    }

    @Test
    fun `会真实执行的 EXPLAIN ANALYZE 写语句被拒`() {
        // H2 的 EXPLAIN ANALYZE/PLAN FOR 会真实执行内层语句,首关键字白名单看不出来,靠 H2 解析器只读判定拦下
        assertThrows(IllegalArgumentException::class.java) { service.execute("EXPLAIN ANALYZE DELETE FROM t") }
        assertEquals(2, rowCount())
        assertThrows(IllegalArgumentException::class.java) { service.execute("EXPLAIN PLAN FOR DELETE FROM t") }
        assertEquals(2, rowCount())
        // 只读的 EXPLAIN 仍放行
        assertTrue(service.execute("EXPLAIN SELECT * FROM t").query)
    }

    @Test
    fun `多语句拼接的写操作被拒且未执行`() {
        assertThrows(IllegalArgumentException::class.java) { service.execute("SELECT 1; DELETE FROM t") }
        assertThrows(IllegalArgumentException::class.java) { service.execute("SELECT * FROM t; DROP TABLE t") }
        assertEquals(2, rowCount())
        assertTrue(tableExists("T"))
    }

    @Test
    fun `选 schema 执行后归还池化连接前恢复默认 schema`() {
        // 未限定名 TABLES 只在 INFORMATION_SCHEMA 下可解析:能跑通即说明会话已切过去
        assertTrue(service.execute("SELECT TABLE_NAME FROM TABLES", "INFORMATION_SCHEMA").query)
        // 归还连接后默认 schema 已恢复 PUBLIC(否则应用自身查询会串库)
        assertEquals("PUBLIC", hikari.connection.use { it.schema })
        assertThrows(SQLException::class.java) { service.execute("SELECT TABLE_NAME FROM TABLES") }
    }

    @Test
    fun `空白 SQL 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { service.execute("") }
        assertThrows(IllegalArgumentException::class.java) { service.execute("   ") }
    }

    @Test
    fun `结果集超 MAX_ROWS 截断并标记 truncated`() {
        val r = service.execute("SELECT * FROM SYSTEM_RANGE(1, ${SqlConsoleService.MAX_ROWS + 1})")
        assertTrue(r.query)
        assertEquals(SqlConsoleService.MAX_ROWS, r.rows.size)
        assertTrue(r.truncated)
    }

    @Test
    fun `单元格超长截断到 MAX_CELL_CHARS`() {
        hikari.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE tlong (v CLOB)")
                st.execute("INSERT INTO tlong VALUES (REPEAT('x', ${SqlConsoleService.MAX_CELL_CHARS + 100}))")
            }
        }
        val rows = service.execute("SELECT v FROM tlong").rows
        assertEquals(SqlConsoleService.MAX_CELL_CHARS, rows[0][0]?.length)
    }

    @Test
    fun `schema 与表字段清单读本地库静态结构`() {
        assertTrue(service.listSchemas().containsAll(listOf("PUBLIC", "INFORMATION_SCHEMA")))

        val tables = service.listTables("PUBLIC")
        assertEquals(listOf("T"), tables.map { it.name })

        val columns = service.listColumns(null)
        assertEquals(listOf("ID", "NAME", "AMOUNT"), columns.filter { it.table == "T" }.map { it.name })
        assertEquals("INTEGER", columns.first { it.name == "ID" }.type)
        assertEquals("CHARACTER VARYING(50)", columns.first { it.name == "NAME" }.type)
        assertEquals("DECIMAL(10,2)", columns.first { it.name == "AMOUNT" }.type)
        assertTrue(columns.all { it.table != "TABLES" }, "PUBLIC 下不应混入 INFORMATION_SCHEMA 的表")

        assertTrue(service.listColumns("INFORMATION_SCHEMA").any { it.table == "TABLES" })
    }
}
