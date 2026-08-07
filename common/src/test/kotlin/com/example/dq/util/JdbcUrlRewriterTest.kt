package com.example.dq.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** JDBC URL 主机端口解析与改写:7 种方言、Oracle SID 形态、缺省端口回落、不可解析报错 */
class JdbcUrlRewriterTest {

    @Test
    fun `七种方言带端口 URL 解析`() {
        assertEquals("db.internal" to 3307, JdbcUrlRewriter.extractHostPort("jdbc:mysql://db.internal:3307/app?useSSL=false"))
        assertEquals("10.0.0.2" to 5433, JdbcUrlRewriter.extractHostPort("jdbc:postgresql://10.0.0.2:5433/app"))
        assertEquals("10.0.0.3" to 1434, JdbcUrlRewriter.extractHostPort("jdbc:sqlserver://10.0.0.3:1434;databaseName=app;encrypt=false"))
        assertEquals("10.0.0.4" to 1522, JdbcUrlRewriter.extractHostPort("jdbc:oracle:thin:@//10.0.0.4:1522/ORCLPDB1"))
        assertEquals("10.0.0.5" to 5237, JdbcUrlRewriter.extractHostPort("jdbc:dm://10.0.0.5:5237"))
        assertEquals("10.0.0.6" to 54322, JdbcUrlRewriter.extractHostPort("jdbc:kingbase8://10.0.0.6:54322/app"))
        assertEquals("10.0.0.7" to 2883, JdbcUrlRewriter.extractHostPort("jdbc:oceanbase://10.0.0.7:2883/app"))
    }

    @Test
    fun `缺省端口按库类型回落默认端口`() {
        assertEquals("h" to 3306, JdbcUrlRewriter.extractHostPort("jdbc:mysql://h/app"))
        assertEquals("h" to 5432, JdbcUrlRewriter.extractHostPort("jdbc:postgresql://h/app"))
        assertEquals("h" to 1433, JdbcUrlRewriter.extractHostPort("jdbc:sqlserver://h;databaseName=app"))
        assertEquals("h" to 1521, JdbcUrlRewriter.extractHostPort("jdbc:oracle:thin:@//h/ORCLPDB1"))
        assertEquals("h" to 5236, JdbcUrlRewriter.extractHostPort("jdbc:dm://h"))
        assertEquals("h" to 54321, JdbcUrlRewriter.extractHostPort("jdbc:kingbase8://h/app"))
        assertEquals("h" to 2881, JdbcUrlRewriter.extractHostPort("jdbc:oceanbase://h/app"))
    }

    @Test
    fun `Oracle SID 形态解析`() {
        assertEquals("orcl-host" to 1521, JdbcUrlRewriter.extractHostPort("jdbc:oracle:thin:@orcl-host:1521:ORCL"))
    }

    @Test
    fun `改写为本地转发端口并保留其余部分`() {
        assertEquals(
            "jdbc:mysql://127.0.0.1:13306/app?useSSL=false",
            JdbcUrlRewriter.rewrite("jdbc:mysql://db.internal:3307/app?useSSL=false", 13306))
        assertEquals(
            "jdbc:sqlserver://127.0.0.1:11433;databaseName=app;encrypt=false",
            JdbcUrlRewriter.rewrite("jdbc:sqlserver://10.0.0.3:1434;databaseName=app;encrypt=false", 11433))
        assertEquals(
            "jdbc:oracle:thin:@//127.0.0.1:11521/ORCLPDB1",
            JdbcUrlRewriter.rewrite("jdbc:oracle:thin:@//10.0.0.4:1522/ORCLPDB1", 11521))
        // Oracle SID 形态:替换后仍是 SID 形态
        assertEquals(
            "jdbc:oracle:thin:@127.0.0.1:11521:ORCL",
            JdbcUrlRewriter.rewrite("jdbc:oracle:thin:@orcl-host:1521:ORCL", 11521))
    }

    @Test
    fun `原缺省端口的改写后显式补上本地端口`() {
        assertEquals(
            "jdbc:mysql://127.0.0.1:13306/app",
            JdbcUrlRewriter.rewrite("jdbc:mysql://h/app", 13306))
        assertEquals(
            "jdbc:dm://127.0.0.1:15236",
            JdbcUrlRewriter.rewrite("jdbc:dm://h", 15236))
    }

    @Test
    fun `不可解析的 URL 抛参数错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            JdbcUrlRewriter.extractHostPort("not-a-jdbc-url")
        }
        assertTrue(e.message!!.contains("无法从 JDBC URL 解析主机端口"))
        assertThrows(IllegalArgumentException::class.java) {
            JdbcUrlRewriter.rewrite("not-a-jdbc-url", 13306)
        }
        // 空串与无主机形态同样不可解析
        assertThrows(IllegalArgumentException::class.java) {
            JdbcUrlRewriter.extractHostPort("")
        }
    }
}
