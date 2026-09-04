package com.example.dq.service

import com.example.dq.config.ScanConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQL 控制台:任意 SQL 原文执行,结果集(列名/行/NULL/截断)与受影响行数返回、空白 SQL 校验。
 * DataSourceService/SystemSettingsService 为 final Kotlin 类,用 mockk 打桩:
 * getConnection 返回指向 H2 内存库的真实连接(每用例独立库名,用例间互不干扰);
 * scanSettings 返回带超时的扫描参数。
 */
class SqlConsoleServiceTest {

    private lateinit var dataSourceService: DataSourceService
    private lateinit var systemSettingsService: SystemSettingsService
    private lateinit var service: SqlConsoleService
    private lateinit var dbUrl: String

    @BeforeEach
    fun setUp() {
        dbUrl = "jdbc:h2:mem:sqlconsole-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        dataSourceService = mockk()
        // 每次调用给一条新连接(用例内共享同一 H2 库,DDL/DML/SELECT 跨调用可见)
        every { dataSourceService.getConnection(any<Long>(), null) } answers { DriverManager.getConnection(dbUrl) }
        systemSettingsService = mockk()
        every { systemSettingsService.scanSettings() } returns ScanConfig(statementTimeoutSeconds = 30)
        service = SqlConsoleService(dataSourceService, systemSettingsService, DialectFactory)
    }

    @Test
    fun `SELECT 返回列名与行数据且 NULL 保 null`() {
        service.execute(1L, "CREATE TABLE t (id INT, name VARCHAR(50))")
        service.execute(1L, "INSERT INTO t VALUES (1, 'a')")
        service.execute(1L, "INSERT INTO t VALUES (2, NULL)")

        val r = service.execute(1L, """SELECT id AS "id_col", name AS "name_col" FROM t ORDER BY id""")
        assertTrue(r.query)
        assertEquals(listOf("id_col", "name_col"), r.columns)
        assertEquals(listOf(listOf("1", "a"), listOf("2", null)), r.rows)
        assertEquals(2, r.total)
        assertFalse(r.truncated)
        assertEquals(-1, r.updateCount)
        assertTrue(r.durationMs >= 0)
    }

    @Test
    fun `CREATE 与 INSERT 返回受影响行数`() {
        val ddl = service.execute(1L, "CREATE TABLE u (id INT)")
        assertFalse(ddl.query)
        assertEquals(0, ddl.updateCount)
        assertEquals(emptyList<String>(), ddl.columns)
        assertEquals(emptyList<List<String?>>(), ddl.rows)

        val ins = service.execute(1L, "INSERT INTO u VALUES (1)")
        assertFalse(ins.query)
        assertEquals(1, ins.updateCount)
    }

    @Test
    fun `空白 SQL 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { service.execute(1L, "") }
        assertThrows(IllegalArgumentException::class.java) { service.execute(1L, "   ") }
    }

    @Test
    fun `结果集超 MAX_ROWS 截断并标记 truncated`() {
        val r = service.execute(1L, "SELECT * FROM GENERATE_SERIES(1, ${SqlConsoleService.MAX_ROWS + 1})")
        assertTrue(r.query)
        assertEquals(SqlConsoleService.MAX_ROWS, r.rows.size)
        assertEquals(SqlConsoleService.MAX_ROWS, r.total)
        assertTrue(r.truncated)
    }

    @Test
    fun `选库执行时非多库方言会话级切 schema 且归还前恢复`() {
        // MySQL 方言以 catalog 切库;连接打桩 relaxed mock,只观察 catalog 读写
        val conn = mockk<Connection>(relaxed = true)
        every { conn.catalog } returns "db1"
        every { dataSourceService.get(1L) } returns DataSourceConfig().apply { dbType = DbType.MYSQL }
        every { dataSourceService.getConnection(1L, null) } returns conn

        service.execute(1L, "SELECT 1", "db2")

        // 先切到目标库 db2,执行完恢复旧值 db1,防池化连接串库
        verifyOrder {
            conn.setCatalog("db2")
            conn.setCatalog("db1")
        }
    }

    @Test
    fun `选库执行时多库方言目标库直达 getConnection 按库分池`() {
        val conn = mockk<Connection>(relaxed = true)
        every { dataSourceService.get(1L) } returns DataSourceConfig().apply { dbType = DbType.SQLSERVER }
        every { dataSourceService.getConnection(1L, "db2") } returns conn

        service.execute(1L, "SELECT 1", "db2")

        // SQL Server 由 getConnection 内部切 catalog,服务层不再会话级切 schema
        verify { dataSourceService.getConnection(1L, "db2") }
        verify(exactly = 0) { conn.setCatalog(any()) }
    }
}
