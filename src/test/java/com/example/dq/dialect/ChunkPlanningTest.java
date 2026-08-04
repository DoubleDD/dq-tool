package com.example.dq.dialect;

import com.example.dq.model.ColumnMeta;
import com.example.dq.model.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分段规划正确性:分段统计的累加结果必须与全表一条 SQL 完全一致。
 * 用 H2 内存库代替真实库(PostgresDialect 的双引号/LIMIT 语法与 H2 默认兼容)。
 */
class ChunkPlanningTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:chunktest;DB_CLOSE_DELAY=-1");
    }

    @Test
    void 数值主键分段_累加等于全表() throws Exception {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(50), status INT)");
            // 1000 行:id 1..1000;name 每 10 行 1 个 NULL、每 7 行 1 个空串;status 每 5 行一个 0
            for (int i = 1; i <= 1000; i++) {
                String name = i % 10 == 0 ? "NULL" : (i % 7 == 0 ? "''" : "'n" + i + "'");
                int status = i % 5 == 0 ? 0 : i;
                st.execute("INSERT INTO users VALUES(" + i + "," + name + "," + status + ")");
            }

            List<ColumnMeta> cols = dialect.listColumns(conn, "PUBLIC", "USERS");
            assertEquals(3, cols.size());
            ColumnMeta key = dialect.pickChunkKey(cols);
            assertEquals("ID", key.name());

            List<Range> ranges = dialect.planChunks(conn, "PUBLIC", "USERS", key, 1000, 10);
            assertTrue(ranges.size() >= 5, "分段数过少: " + ranges.size());

            long[] full = runStats(conn, "USERS", cols, null, null);
            long[] sum = new long[full.length];
            long totalRows = 0;
            for (Range r : ranges) {
                long[] part = runStats(conn, "USERS", cols, r, key);
                totalRows += part[0];
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += part[i];
                }
            }
            assertEquals(1000, totalRows);
            for (int i = 0; i < full.length; i++) {
                assertEquals(full[i], sum[i], "第 " + i + " 个度量不一致");
            }
            // 期望值:total, id_null, name_null, name_empty, status_null
            assertEquals(1000, full[0]);
            assertEquals(100, full[2]);  // name NULL 数(10 的倍数)
            assertEquals(128, full[3]);  // name 空串数(7 的倍数 142 个,其中 14 个同为 10 的倍数被判为 NULL)
        }
    }

    @Test
    void 可空唯一键_NULL行进补充分段() throws Exception {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE codes(code VARCHAR(20) UNIQUE, v INT)");
            for (int i = 1; i <= 100; i++) {
                st.execute("INSERT INTO codes VALUES('c" + String.format("%03d", i) + "'," + i + ")");
            }
            st.execute("INSERT INTO codes VALUES(NULL, 999)");
            st.execute("INSERT INTO codes VALUES(NULL, 998)");

            List<ColumnMeta> cols = dialect.listColumns(conn, "PUBLIC", "CODES");
            ColumnMeta key = dialect.pickChunkKey(cols);
            assertEquals("CODE", key.name());

            List<Range> ranges = dialect.planChunks(conn, "PUBLIC", "CODES", key, 102, 10);
            assertTrue(ranges.stream().anyMatch(Range::nullChunk), "应包含 NULL 键补充分段");

            long totalRows = 0;
            for (Range r : ranges) {
                totalRows += runStats(conn, "CODES", cols, r, key)[0];
            }
            assertEquals(102, totalRows);
        }
    }

    /** 执行统计 SQL,返回 [total, 各列度量...] */
    private long[] runStats(Connection conn, String table, List<ColumnMeta> cols,
                            Range range, ColumnMeta key) throws Exception {
        String sql = dialect.buildColumnStatsSql("PUBLIC", table,
                cols, range, key, List.of(), false, 0, null);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            long[] out = new long[rs.getMetaData().getColumnCount()];
            for (int i = 0; i < out.length; i++) {
                out[i] = rs.getLong(i + 1);
            }
            return out;
        }
    }
}
