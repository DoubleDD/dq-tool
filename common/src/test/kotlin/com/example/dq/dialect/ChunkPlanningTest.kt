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

    @Test
    fun `varchar主键 seek分段 累加等于全表`() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE msg(id VARCHAR(36) PRIMARY KEY, name VARCHAR(50), status INT)")
                // 5000 行:id 用可字典序排序的定宽码;name 每 10 行 1 个 NULL、每 7 行 1 个空串
                for (i in 1..5000) {
                    val id = "id" + "%05d".format(i)
                    val name = if (i % 10 == 0) "NULL" else if (i % 7 == 0) "''" else "'n$i'"
                    val status = if (i % 5 == 0) 0 else i
                    st.execute("INSERT INTO msg VALUES('$id',$name,$status)")
                }

                val cols = dialect.listColumns(conn, "PUBLIC", "MSG")
                val key = dialect.pickChunkKey(cols)
                assertEquals("ID", key!!.name)

                // chunksPerTable=8,step=5000/8=625:验证 seek 分段能切出多段且不重不漏
                val ranges = dialect.planChunks(conn, "PUBLIC", "MSG", key, 5000L, 8)
                assertTrue(ranges.size >= 5, "分段数过少: " + ranges.size)

                val full = runStats(conn, "MSG", cols, null, null)
                val sum = LongArray(full.size)
                var totalRows = 0L
                for (r in ranges) {
                    val part = runStats(conn, "MSG", cols, r, key)
                    totalRows += part[0]
                    for (i in sum.indices) {
                        sum[i] += part[i]
                    }
                }
                assertEquals(5000L, totalRows)
                for (i in full.indices) {
                    assertEquals(full[i], sum[i], "第 $i 个度量不一致")
                }
            }
        }
    }

    @Test
    fun `varchar键全NULL 只返回whole不重复统计`() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                // 键候选来自唯一索引首列,值全为 NULL
                st.execute("CREATE TABLE uniq_null(code VARCHAR(20) UNIQUE, name VARCHAR(50))")
                for (i in 1..10) {
                    st.execute("INSERT INTO uniq_null VALUES(NULL,'n$i')")
                }

                val cols = dialect.listColumns(conn, "PUBLIC", "UNIQ_NULL")
                val key = dialect.pickChunkKey(cols)
                assertEquals("CODE", key!!.name)

                val ranges = dialect.planChunks(conn, "PUBLIC", "UNIQ_NULL", key, 1000L, 10)
                // step=100 > 非 NULL 行数 0:查不到边界,必须只返回 whole,且不得追加 nullChunk 段
                assertEquals(1, ranges.size)
                assertTrue(ranges[0].start == null && ranges[0].end == null && !ranges[0].nullChunk,
                        "应为 whole 段: " + ranges)

                var totalRows = 0L
                for (r in ranges) {
                    totalRows += runStats(conn, "UNIQ_NULL", cols, r, key)[0]
                }
                assertEquals(10L, totalRows, "NULL 行不得被重复统计")
            }
        }
    }

    @Test
    fun `varchar键非NULL行不足step 只返回whole不重复统计`() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE uniq_few(code VARCHAR(20) UNIQUE, name VARCHAR(50))")
                // 非 NULL 键仅 5 行,另有 3 行 NULL
                for (i in 1..5) {
                    st.execute("INSERT INTO uniq_few VALUES('c$i','n$i')")
                }
                for (i in 1..3) {
                    st.execute("INSERT INTO uniq_few VALUES(NULL,'null$i')")
                }

                val cols = dialect.listColumns(conn, "PUBLIC", "UNIQ_FEW")
                val key = dialect.pickChunkKey(cols)
                assertEquals("CODE", key!!.name)

                // estRows=1000, chunksPerTable=10 → step=100 > 非 NULL 行数 5:查不到分段边界
                val ranges = dialect.planChunks(conn, "PUBLIC", "UNIQ_FEW", key, 1000L, 10)
                assertEquals(1, ranges.size)
                assertTrue(ranges[0].start == null && ranges[0].end == null && !ranges[0].nullChunk,
                        "应为 whole 段: " + ranges)

                var totalRows = 0L
                for (r in ranges) {
                    totalRows += runStats(conn, "UNIQ_FEW", cols, r, key)[0]
                }
                assertEquals(8L, totalRows, "NULL 行不得被重复统计")
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
