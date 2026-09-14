package com.example.dq.dialect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 用户眼中的「库」清单统一口径([DbDialect.listDatabasesOrSchemas]):
 * 多库方言取 database 列表,单库方言的库就是 schema(MySQL 的 schema 即库),listDatabases 未实现返回空时回落 listSchemas。
 *
 * 回归背景:数据源编辑对话框「库过滤」页签的「加载库列表」曾对 MySQL 走 listDatabases 拿到空列表,
 * 该空值被写进数据源级库清单缓存(连同 schema 清单同一批 db_name='' 的行一起清掉),
 * 表现为提示「目标库没有可选择的库」、已选 0/0,而目标库实际有库。
 */
class DatabaseListFallbackTest {

    @Test
    fun `单库方言的库清单回落到 schema 列表`() {
        val url = "jdbc:h2:mem:dblist-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st -> st.execute("CREATE TABLE t_demo (id INT)") }
            val dialect = MySqlDialect()
            assertTrue(dialect.listDatabases(conn).isEmpty(), "MySQL 不实现 listDatabases,应返回空")
            val names = dialect.listDatabasesOrSchemas(conn)
            assertTrue(names.isNotEmpty(), "回落 listSchemas 不应为空: $names")
            assertEquals(dialect.listSchemas(conn), names)
        }
    }

    @Test
    fun `多库方言取 database 列表 不回落到 schema`() {
        val url = "jdbc:h2:mem:dbmulti-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url).use { conn ->
            val dialect = object : MySqlDialect() {
                override fun listDatabases(conn: Connection): List<String> = listOf("db_a", "db_b")
            }
            assertEquals(listOf("db_a", "db_b"), dialect.listDatabasesOrSchemas(conn))
        }
    }
}
