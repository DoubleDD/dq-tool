package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.Range
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 分段规划正确性:分段统计的累加结果必须与全表一条 SQL 完全一致。
 * 用 H2 内存库代替真实库(PostgresDialect 的双引号/LIMIT 语法与 H2 默认兼容)。
 */
class ChunkPlanningTest {

    private val dialect = PostgresDialect()

    @BeforeEach
    fun setup() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP ALL OBJECTS")
            }
        }
    }

    private fun connect(): Connection {
        return DriverManager.getConnection("jdbc:h2:mem:chunktest;DB_CLOSE_DELAY=-1")
    }

    @Test
    fun `数值主键分段 累加等于全表`() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(50), status INT)")
                // 1000 行:id 1..1000;name 每 10 行 1 个 NULL、每 7 行 1 个空串;status 每 5 行一个 0
                for (i in 1..1000) {
                    val name = if (i % 10 == 0) "NULL" else if (i % 7 == 0) "''" else "'n$i'"
                    val status = if (i % 5 == 0) 0 else i
                    st.execute("INSERT INTO users VALUES($i,$name,$status)")
                }

                val cols = dialect.listColumns(conn, "PUBLIC", "USERS")
                assertEquals(3, cols.size)
                val key = dialect.pickChunkKey(cols)
                assertEquals("ID", key!!.name)

                val ranges = dialect.planChunks(conn, "PUBLIC", "USERS", key, 1000L, 10)
                assertTrue(ranges.size >= 5, "分段数过少: " + ranges.size)

                val full = runStats(conn, "USERS", cols, null, null)
                val sum = LongArray(full.size)
                var totalRows = 0L
                for (r in ranges) {
                    val part = runStats(conn, "USERS", cols, r, key)
                    totalRows += part[0]
                    for (i in sum.indices) {
                        sum[i] += part[i]
                    }
                }
                assertEquals(1000L, totalRows)
                for (i in full.indices) {
                    assertEquals(full[i], sum[i], "第 $i 个度量不一致")
                }
                // 期望值:total, id_null, name_null, name_empty, status_null
                assertEquals(1000L, full[0])
                assertEquals(100L, full[2])  // name NULL 数(10 的倍数)
                assertEquals(128L, full[3])  // name 空串数(7 的倍数 142 个,其中 14 个同为 10 的倍数被判为 NULL)
            }
        }
    }

    @Test
    fun `可空唯一键 NULL行进补充分段`() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE codes(code VARCHAR(20) UNIQUE, v INT)")
                for (i in 1..100) {
                    st.execute("INSERT INTO codes VALUES('c" + "%03d".format(i) + "'," + i + ")")
                }
                st.execute("INSERT INTO codes VALUES(NULL, 999)")
                st.execute("INSERT INTO codes VALUES(NULL, 998)")

                val cols = dialect.listColumns(conn, "PUBLIC", "CODES")
                val key = dialect.pickChunkKey(cols)
                assertEquals("CODE", key!!.name)

                val ranges = dialect.planChunks(conn, "PUBLIC", "CODES", key, 102L, 10)
                assertTrue(ranges.any { it.nullChunk }, "应包含 NULL 键补充分段")

                var totalRows = 0L
                for (r in ranges) {
                    totalRows += runStats(conn, "CODES", cols, r, key)[0]
                }
                assertEquals(102L, totalRows)
            }
        }
    }

    /** 执行统计 SQL,返回 [total, 各列度量...] */
    private fun runStats(conn: Connection, table: String, cols: List<ColumnMeta>,
                         range: Range?, key: ColumnMeta?): LongArray {
        val sql = dialect.buildColumnStatsSql("PUBLIC", table,
            cols, range, key, listOf(), false, 0, null)
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                val out = LongArray(rs.metaData.columnCount)
                for (i in out.indices) {
                    out[i] = rs.getLong(i + 1)
                }
                return out
            }
        }
    }
}
