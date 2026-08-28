package com.example.dq.dialect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Kingbase 多库支持的纯单元测试(不连库):URL 库名解析/改写、未指定库时的候选回落 */
class KingbaseDialectTest {

    private val dialect = KingbaseDialect()

    @Test
    fun `多库方言标记与驱动名`() {
        assertTrue(dialect.supportsMultiDatabase())
        assertEquals("com.kingbase8.Driver", dialect.driverClassName())
    }

    @Test
    fun `URL 库名解析`() {
        assertEquals("oms", KingbaseDialect.databaseOf("jdbc:kingbase8://10.0.0.1:54321/oms"))
        assertEquals("oms", KingbaseDialect.databaseOf("jdbc:kingbase8://10.0.0.1:54321/oms?ssl=false"))
        assertNull(KingbaseDialect.databaseOf("jdbc:kingbase8://10.0.0.1:54321/"))
        assertNull(KingbaseDialect.databaseOf("jdbc:kingbase8://10.0.0.1:54321"))
        assertNull(KingbaseDialect.databaseOf("jdbc:kingbase8://10.0.0.1:54321/?ssl=false"))
    }

    @Test
    fun `按库分池改写 URL 路径段`() {
        assertEquals("jdbc:kingbase8://10.0.0.1:54321/oms",
            dialect.jdbcUrlForDatabase("jdbc:kingbase8://10.0.0.1:54321/kingbase", "oms"))
        assertEquals("jdbc:kingbase8://10.0.0.1:54321/oms",
            dialect.jdbcUrlForDatabase("jdbc:kingbase8://10.0.0.1:54321/", "oms"))
        // 查询串保留(SSH 隧道改写后的 URL 同样适用)
        assertEquals("jdbc:kingbase8://127.0.0.1:15432/oms?ssl=false",
            dialect.jdbcUrlForDatabase("jdbc:kingbase8://127.0.0.1:15432/?ssl=false", "oms"))
    }

    @Test
    fun `已指定库时候选只有自身`() {
        assertEquals(listOf("jdbc:kingbase8://10.0.0.1:54321/oms"),
            dialect.connectionUrlCandidates("jdbc:kingbase8://10.0.0.1:54321/oms"))
    }

    @Test
    fun `未指定库时回落维护库候选`() {
        assertEquals(
            listOf(
                "jdbc:kingbase8://10.0.0.1:54321/",
                "jdbc:kingbase8://10.0.0.1:54321/kingbase",
                "jdbc:kingbase8://10.0.0.1:54321/test",
                "jdbc:kingbase8://10.0.0.1:54321/template1"),
            dialect.connectionUrlCandidates("jdbc:kingbase8://10.0.0.1:54321/"))
    }

    @Test
    fun `系统库为数据库层级的维护库`() {
        val sys = dialect.systemSchemas()
        assertTrue(sys.contains("kingbase"))
        assertTrue(sys.contains("template0"))
        assertTrue(sys.contains("security"))
    }
}
