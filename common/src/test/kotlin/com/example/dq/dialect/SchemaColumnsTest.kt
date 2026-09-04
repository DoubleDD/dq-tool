package com.example.dq.dialect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/** 整库字段清单(SQL 控制台智能提示):H2 内存库实测 JDBC 元数据批量取字段 */
class SchemaColumnsTest {

    @Test
    fun `schema 口径方言整库取字段`() {
        val url = "jdbc:h2:mem:schemacols-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t_user (id INT PRIMARY KEY, user_name VARCHAR(50), amount DECIMAL(10,2))")
                st.execute("CREATE TABLE t_order (oid INT, note VARCHAR(200))")
                st.execute("COMMENT ON COLUMN t_user.user_name IS '用户姓名'")
            }
            // catalogBased=false 的方言走 schemaPattern=schema;H2 默认 schema 为 PUBLIC
            val cols = PostgresDialect().listSchemaColumns(conn, "PUBLIC")
            val byTable = cols.groupBy({ it.table }, { it.name })
            assertEquals(listOf("ID", "USER_NAME", "AMOUNT"), byTable["T_USER"])
            assertEquals(listOf("OID", "NOTE"), byTable["T_ORDER"])
            assertTrue(cols.all { it.type.isNotBlank() }, "展示类型不应为空: $cols")
            // 字段备注随清单一并返回(无备注的字段为空串)
            assertEquals("用户姓名", cols.first { it.table == "T_USER" && it.name == "USER_NAME" }.comment)
            assertEquals("", cols.first { it.table == "T_USER" && it.name == "ID" }.comment)
        }
    }

    @Test
    fun `catalog 口径方言整库取字段`() {
        val url = "jdbc:h2:mem:schemacolscat-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t_a (id INT, v VARCHAR(10))")
            }
            // catalogBased=true 的方言走 catalog=schema;H2 的 catalog 即数据库名
            // (H2 的 INFORMATION_SCHEMA 与业务表同 catalog,这里只断言业务表;MySQL 系各库独立 catalog 无此问题)
            val cols = MySqlDialect().listSchemaColumns(conn, conn.catalog)
            val tA = cols.filter { it.table == "T_A" }
            assertEquals(listOf("ID", "V"), tA.map { it.name })
        }
    }

    @Test
    fun `按表名限定取字段 只返回该表`() {
        val url = "jdbc:h2:mem:schemacolstbl-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t_user (id INT PRIMARY KEY, user_name VARCHAR(50))")
                st.execute("CREATE TABLE t_order (oid INT, note VARCHAR(200))")
            }
            // 3 参重载(SQL 控制台字段分批拉取用):table 限定为具体表名
            val cols = PostgresDialect().listSchemaColumns(conn, "PUBLIC", "T_USER")
            assertEquals(listOf("ID", "USER_NAME"), cols.map { it.name })
            assertTrue(cols.all { it.table == "T_USER" }, "不应混入其它表字段: $cols")
            assertTrue(cols.all { it.type.isNotBlank() }, "展示类型不应为空: $cols")
        }
    }
}
