package com.example.dq.dialect

import com.example.dq.model.DbType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

/** ORA-01000 游标耗尽防护:连接初始化 SQL 与异常识别(纯单测,不连库) */
class OracleCursorGuardTest {

    private val oracle = OracleDialect()

    @Test
    fun `Oracle 新建连接关闭会话游标缓存,其余方言无初始化 SQL`() {
        assertEquals("ALTER SESSION SET session_cached_cursors = 0", oracle.connectionInitSql())
        for (type in DbType.entries) {
            if (type == DbType.ORACLE) continue
            assertNull(DialectFactory.get(type).connectionInitSql(), type.name)
        }
    }

    @Test
    fun `识别 ORA-01000 直接抛出、包裹在 cause、挂在 nextException 链`() {
        assertTrue(oracle.isOpenCursorsExceeded(SQLException("超出打开游标的最大数", "42000", 1000)))
        assertTrue(oracle.isOpenCursorsExceeded(RuntimeException(SQLException("包裹", "42000", 1000))))
        val head = SQLException("复合异常首条", "42000", 942)
        head.nextException = SQLException("第二条", "42000", 1000)
        assertTrue(oracle.isOpenCursorsExceeded(head))
    }

    @Test
    fun `不误伤其他错误`() {
        assertFalse(oracle.isOpenCursorsExceeded(SQLException("表或视图不存在", "42000", 942)))
        assertFalse(oracle.isOpenCursorsExceeded(IllegalStateException("boom")))
        assertFalse(MySqlDialect().isOpenCursorsExceeded(SQLException("x", "42000", 1000)))
    }
}
