package com.example.dq.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * SQL 日志代理验证:包装 H2 连接后,execute 类调用会把完整 SQL(含 PreparedStatement 参数)
 * 打到 com.example.dq.sql logger,且执行结果不受影响。
 */
class SqlLogConnectionTest {

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var sqlLogger: Logger

    @BeforeEach
    fun setup() {
        sqlLogger = LoggerFactory.getLogger("com.example.dq.sql") as Logger
        sqlLogger.level = Level.INFO
        appender = ListAppender<ILoggingEvent>()
        appender.start()
        sqlLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        sqlLogger.detachAppender(appender)
    }

    private fun connect(): Connection {
        return SqlLogConnection.wrap(DriverManager.getConnection("jdbc:h2:mem:sqllog;DB_CLOSE_DELAY=-1"))
    }

    private fun sqlEvents(): List<String> {
        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `Statement 查询 SQL 完整打印`() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t(id INT PRIMARY KEY, name VARCHAR(20))")
                st.executeUpdate("INSERT INTO t VALUES(1, 'a')")
                st.executeQuery("SELECT * FROM t").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
        val lines = sqlEvents()
        assertTrue(lines.any { it.contains("CREATE TABLE t") }, "缺建表 SQL: " + lines)
        assertTrue(lines.any { it.contains("INSERT INTO t VALUES(1, 'a')") }, "缺 INSERT SQL: " + lines)
        assertTrue(lines.any { it.contains("SELECT * FROM t") }, "缺 SELECT SQL: " + lines)
    }

    @Test
    fun `PreparedStatement 参数渲染进日志`() {
        connect().use { conn ->
            conn.prepareStatement("CREATE TABLE u(id INT PRIMARY KEY, name VARCHAR(20))").use { st ->
                st.execute()
            }
            conn.prepareStatement("INSERT INTO u VALUES(?, ?)").use { st ->
                st.setInt(1, 7)
                st.setString(2, "it's")
                st.executeUpdate()
            }
            conn.prepareStatement("SELECT * FROM u WHERE id = ?").use { st ->
                st.setInt(1, 7)
                st.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("it's", rs.getString(2))
                }
            }
        }
        val lines = sqlEvents()
        val insert = lines.firstOrNull { it.contains("INSERT INTO u") }
        assertTrue(insert != null, "缺 PREPARED INSERT: " + lines)
        assertTrue(insert!!.contains("VALUES(?, ?)") && insert.contains("1=7") && insert.contains("2='it''s'"),
                "INSERT 模板或参数未渲染(应含 VALUES(?, ?) 与 1=7、2='it''s'): " + insert)
        val select = lines.firstOrNull { it.contains("SELECT * FROM u") }
        assertTrue(select != null && select.contains("id = ?") && select.contains("1=7"),
                "缺 PREPARED SELECT 模板/参数: " + lines)
    }

    @Test
    fun `NULL 参数渲染为 NULL`() {
        connect().use { conn ->
            conn.prepareStatement("CREATE TABLE v(id INT PRIMARY KEY, name VARCHAR(20))").use { st ->
                st.execute()
            }
            conn.prepareStatement("INSERT INTO v VALUES(?, ?)").use { st ->
                st.setInt(1, 3)
                st.setNull(2, java.sql.Types.VARCHAR)
                st.executeUpdate()
            }
        }
        val insert = sqlEvents().firstOrNull { it.contains("INSERT INTO v") }
        assertTrue(insert != null && insert.contains("2=NULL"), "NULL 参数未渲染: " + insert)
    }

    @Test
    fun `包装后 close 与池语义不受影响`() {
        val raw = DriverManager.getConnection("jdbc:h2:mem:sqllogclose;DB_CLOSE_DELAY=-1")
        raw.createStatement().use { it.execute("CREATE TABLE x(id INT)") }
        val wrapped = SqlLogConnection.wrap(raw)
        wrapped.createStatement().use { st ->
            st.executeUpdate("INSERT INTO x VALUES(9)")
        }
        wrapped.close()
        // close 后 raw 也关闭(透传),再使用抛 SQLException
        val closed = try {
            raw.createStatement().use { it.executeQuery("SELECT 1") }
            false
        } catch (e: SQLException) {
            true
        }
        assertTrue(closed, "close 应透传到真实连接")
    }

    @Test
    fun `控制方法setFetchSize 不记为绑定参数`() {
        connect().use { conn ->
            conn.prepareStatement("CREATE TABLE f(id INT PRIMARY KEY, name VARCHAR(20))").use { st ->
                st.execute()
            }
            conn.prepareStatement("SELECT * FROM f WHERE id = ?").use { st ->
                st.setInt(1, 5)
                st.fetchSize = 100
                st.queryTimeout = 3
                st.executeQuery().use { rs -> }
            }
        }
        val select = sqlEvents().firstOrNull { it.contains("SELECT * FROM f") }
        assertTrue(select != null, "缺 SELECT: " + sqlEvents())
        assertTrue(select!!.contains("1=5"), "应含参数 1=5: " + select)
        assertFalse(select.contains("100"), "fetchSize 100 不应记为参数: " + select)
        assertFalse(select.contains("3"), "queryTimeout 3 不应记为参数: " + select)
    }

    @Test
    fun `流参数渲染为占位符`() {
        connect().use { conn ->
            conn.prepareStatement("CREATE TABLE s(id INT PRIMARY KEY, body CLOB)").use { st ->
                st.execute()
            }
            conn.prepareStatement("INSERT INTO s VALUES(?, ?)").use { st ->
                st.setInt(1, 1)
                st.setCharacterStream(2, java.io.StringReader("hello"))
                st.executeUpdate()
            }
        }
        val insert = sqlEvents().firstOrNull { it.contains("INSERT INTO s") }
        assertTrue(insert != null, "缺 INSERT: " + sqlEvents())
        assertTrue(insert!!.contains("2=<lob>"), "流参数应渲染为 <lob>: " + insert)
        assertFalse(insert.contains("StringReader"), "不得打出对象地址: " + insert)
    }

    @Test
    fun `statement复用后 陈旧参数被清空`() {
        connect().use { conn ->
            conn.prepareStatement("CREATE TABLE r(id INT PRIMARY KEY, name VARCHAR(20))").use { st ->
                st.execute()
            }
            conn.prepareStatement("INSERT INTO r VALUES(?, ?)").use { st ->
                st.setInt(1, 1)
                st.setString(2, "first")
                st.executeUpdate()
                // 复用同一 statement,重新 set 全部参数
                st.setInt(1, 2)
                st.setString(2, "second")
                st.executeUpdate()
            }
        }
        val inserts = sqlEvents().filter { it.contains("INSERT INTO r") }
        assertEquals(2, inserts.size, "应有两条 INSERT 日志: " + inserts)
        assertTrue(inserts[0].contains("1=1") && inserts[0].contains("2='first'"),
                "第一条参数错误: " + inserts[0])
        assertTrue(inserts[1].contains("1=2") && inserts[1].contains("2='second'")
                && !inserts[1].contains("first"), "第二条混入陈旧参数: " + inserts[1])
    }
}
