package com.example.dq.dialect;

import com.example.dq.model.ColumnMeta;
import com.example.dq.model.NullRule;
import com.example.dq.model.Range;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统计 SQL 生成的纯单元测试(不连库) */
class DialectSqlGenTest {

    private final MySqlDialect mysql = new MySqlDialect();
    private final PostgresDialect pg = new PostgresDialect();

    private static ColumnMeta col(String name, int jdbcType) {
        return new ColumnMeta(name, "VARCHAR", jdbcType, false, 0, false);
    }

    private static ColumnMeta pk(String name, int jdbcType) {
        return new ColumnMeta(name, "BIGINT", jdbcType, true, 1, false);
    }

    @Test
    void 基本聚合_SQL_包含总数与空值统计() {
        List<ColumnMeta> cols = List.of(col("name", Types.VARCHAR), col("age", Types.INTEGER));
        String sql = mysql.buildColumnStatsSql("db1", "user", cols,
                null, null, List.of(), false, 0, null);

        assertTrue(sql.contains("SELECT COUNT(*) AS total"));
        assertTrue(sql.contains("SUM(CASE WHEN `name` IS NULL THEN 1 ELSE 0 END) AS c0_null"));
        assertTrue(sql.contains("SUM(CASE WHEN TRIM(`name`) = '' THEN 1 ELSE 0 END) AS c0_empty"));
        assertTrue(sql.contains("SUM(CASE WHEN `age` IS NULL THEN 1 ELSE 0 END) AS c1_null"));
        assertFalse(sql.contains("c1_empty")); // 非字符列不统计空串
        assertTrue(sql.contains("FROM `db1`.`user`"));
        assertFalse(sql.contains("WHERE"));
    }

    @Test
    void 分段谓词_数值键不加引号() {
        List<ColumnMeta> cols = List.of(col("name", Types.VARCHAR));
        ColumnMeta key = pk("id", Types.BIGINT);
        String sql = mysql.buildColumnStatsSql("db1", "user", cols,
                new Range("100", "200", false), key, List.of(), false, 0, null);

        assertTrue(sql.contains("WHERE `id` >= 100 AND `id` < 200"));
    }

    @Test
    void 分段谓词_末段无上界() {
        ColumnMeta key = pk("id", Types.BIGINT);
        String sql = mysql.buildColumnStatsSql("db1", "user", List.of(col("name", Types.VARCHAR)),
                new Range("900", null, false), key, List.of(), false, 0, null);
        assertTrue(sql.contains("`id` >= 900"));
        assertFalse(sql.contains("`id` <"));
    }

    @Test
    void NULL键补充分段() {
        ColumnMeta key = pk("id", Types.BIGINT);
        String sql = mysql.buildColumnStatsSql("db1", "user", List.of(col("name", Types.VARCHAR)),
                new Range(null, null, true), key, List.of(), false, 0, null);
        assertTrue(sql.contains("WHERE `id` IS NULL"));
    }

    @Test
    void 字符串键范围_值加引号并转义() {
        ColumnMeta key = new ColumnMeta("code", "VARCHAR", Types.VARCHAR, true, 1, false);
        String sql = pg.buildColumnStatsSql("public", "t", List.of(col("v", Types.INTEGER)),
                new Range("a'1", "b2", false), key, List.of(), false, 0, null);
        assertTrue(sql.contains("\"code\" >= 'a''1' AND \"code\" < 'b2'"));
    }

    @Test
    void 自定义空值规则_数值列原样_字符列加引号() {
        List<ColumnMeta> cols = List.of(col("status", Types.INTEGER), col("remark", Types.VARCHAR));
        List<NullRule> rules = List.of(
                new NullRule("status", List.of("0", "-1")),
                new NullRule("remark", List.of("N/A", "it's")));
        String sql = mysql.buildColumnStatsSql("db1", "t", cols,
                null, null, rules, false, 0, null);

        assertTrue(sql.contains("`status` IN (0, -1)"));
        assertTrue(sql.contains("`remark` IN ('N/A', 'it''s')"));
        assertTrue(sql.contains("AS c0_rule"));
        assertTrue(sql.contains("AS c1_rule"));
    }

    @Test
    void 通配规则_命中所有列() {
        List<ColumnMeta> cols = List.of(col("a", Types.INTEGER), col("b", Types.VARCHAR));
        List<NullRule> rules = List.of(new NullRule("*", List.of("0")));
        String sql = mysql.buildColumnStatsSql("db1", "t", cols,
                null, null, rules, false, 0, null);
        assertTrue(sql.contains("`a` IN (0)"));
        assertTrue(sql.contains("`b` IN ('0')"));
    }

    @Test
    void selector布局与SQL列序一致() {
        List<ColumnMeta> cols = List.of(col("name", Types.VARCHAR), col("age", Types.INTEGER));
        List<NullRule> rules = List.of(new NullRule("age", List.of("0")));
        List<boolean[]> layout = mysql.selectorLayout(cols, rules);
        assertEquals(2, layout.size());
        assertTrue(layout.get(0)[0]);   // name 是字符列,有空串统计
        assertFalse(layout.get(0)[1]);  // name 无规则
        assertFalse(layout.get(1)[0]);  // age 非字符
        assertTrue(layout.get(1)[1]);   // age 有规则
    }

    @Test
    void PG采样_使用TABLESAMPLE() {
        String sql = pg.buildColumnStatsSql("public", "big_t", List.of(col("v", Types.INTEGER)),
                null, null, List.of(), true, 100_000, 10_000_000L);
        assertTrue(sql.contains("TABLESAMPLE SYSTEM (1.0)"));
        assertFalse(sql.contains("LIMIT"));
    }

    @Test
    void MySQL采样_退化为LIMIT() {
        String sql = mysql.buildColumnStatsSql("db1", "big_t", List.of(col("v", Types.INTEGER)),
                null, null, List.of(), true, 100_000, 10_000_000L);
        assertTrue(sql.contains("LIMIT 100000"));
    }

    @Test
    void Oracle采样_使用SAMPLE_BLOCK() {
        OracleDialect oracle = new OracleDialect();
        String sql = oracle.buildColumnStatsSql("SCOTT", "big_t", List.of(col("v", Types.INTEGER)),
                null, null, List.of(), true, 100_000, 10_000_000L);
        assertTrue(sql.contains("SAMPLE BLOCK (1.0)"));
        assertTrue(sql.contains("FROM \"SCOTT\".\"big_t\""));
    }

    @Test
    void Oracle分页_FETCH_FIRST() {
        OracleDialect oracle = new OracleDialect();
        ColumnMeta key = pk("id", Types.BIGINT);
        String sql = oracle.buildColumnStatsSql("SCOTT", "t", List.of(col("v", Types.INTEGER)),
                new Range("5", "9", false), key, List.of(), false, 0, null);
        assertTrue(sql.contains("\"id\" >= 5 AND \"id\" < 9"));
    }

    @Test
    void SqlServer采样_使用TABLESAMPLE行数() {
        SqlServerDialect mssql = new SqlServerDialect();
        String sql = mssql.buildColumnStatsSql("dbo", "big_t", List.of(col("v", Types.INTEGER)),
                null, null, List.of(), true, 100_000, 10_000_000L);
        assertTrue(sql.contains("TABLESAMPLE (100000 ROWS)"));
        assertTrue(sql.contains("FROM [dbo].[big_t]"));
    }

    @Test
    void SqlServer标识符_方括号转义() {
        SqlServerDialect mssql = new SqlServerDialect();
        ColumnMeta key = pk("id", Types.BIGINT);
        String sql = mssql.buildColumnStatsSql("dbo", "t", List.of(col("v", Types.INTEGER)),
                new Range("1", "2", false), key, List.of(), false, 0, null);
        assertTrue(sql.contains("[id] >= 1 AND [id] < 2"));
    }

    @Test
    void 分段键选择_单列主键优先() {
        ColumnMeta id = pk("id", Types.BIGINT);
        ColumnMeta other = new ColumnMeta("uk_col", "VARCHAR", Types.VARCHAR, false, 0, true);
        ColumnMeta picked = mysql.pickChunkKey(List.of(other, id));
        assertEquals("id", picked.name());
    }

    @Test
    void 分段键选择_联合主键取首列() {
        ColumnMeta a = new ColumnMeta("a", "BIGINT", Types.BIGINT, true, 1, false);
        ColumnMeta b = new ColumnMeta("b", "BIGINT", Types.BIGINT, true, 2, false);
        ColumnMeta picked = mysql.pickChunkKey(List.of(b, a));
        assertEquals("a", picked.name());
    }

    @Test
    void 分段键选择_无键可用返回null() {
        ColumnMeta blob = new ColumnMeta("data", "BLOB", Types.BLOB, false, 0, false);
        assertNull(mysql.pickChunkKey(List.of(blob)));
    }

    @Test
    void Oracle表清单_23ai以下用ALL_SEGMENTS() {
        String sql = OracleDialect.listTablesSql(11);
        assertTrue(sql.contains("FROM all_segments s"));
        assertFalse(sql.contains("user_segments"));
    }

    @Test
    void Oracle表清单_23ai起用USER_SEGMENTS且限定当前用户() {
        String sql = OracleDialect.listTablesSql(23);
        assertTrue(sql.contains("FROM user_segments s"));
        assertTrue(sql.contains("SYS_CONTEXT('USERENV','SESSION_USER')"));
        assertFalse(sql.contains("all_segments"));
    }

    @Test
    void DM表清单_默认用ALL_SEGMENTS统计大小() {
        String sql = DmDialect.listTablesSql(true);
        assertTrue(sql.contains("FROM all_segments s"));
    }

    @Test
    void DM表清单_降级模式不引用ALL_SEGMENTS() {
        String sql = DmDialect.listTablesSql(false);
        assertFalse(sql.contains("all_segments"));
        assertTrue(sql.contains("FROM all_tables t"));
    }

    @Test
    void Oracle边界值_12c起OFFSET_FETCH() {
        OracleDialect oracle = new OracleDialect();
        String sql = oracle.boundaryQuerySql("\"S\".\"t\"", "\"id\"", 100, 12);
        assertTrue(sql.contains("OFFSET 100 ROWS FETCH NEXT 1 ROWS ONLY"));
    }

    @Test
    void Oracle边界值_11g用ROWNUM双层包装() {
        OracleDialect oracle = new OracleDialect();
        String sql = oracle.boundaryQuerySql("\"S\".\"t\"", "\"id\"", 100, 11);
        assertTrue(sql.contains("ROWNUM <= 101"));
        assertTrue(sql.contains("dq_rn = 101"));
        assertFalse(sql.contains("OFFSET"));
    }

    @Test
    void OracleNULL探测_按版本分页() {
        OracleDialect oracle = new OracleDialect();
        assertTrue(oracle.nullChunkProbeSql("\"S\".\"t\"", "\"id\"", 11).contains("ROWNUM <= 1"));
        assertTrue(oracle.nullChunkProbeSql("\"S\".\"t\"", "\"id\"", 19).contains("FETCH FIRST 1 ROWS ONLY"));
    }
}
