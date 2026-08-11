package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.NullRule
import com.example.dq.model.Range
import org.junit.jupiter.api.Test

import java.sql.Types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** 统计 SQL 生成的纯单元测试(不连库) */
class DialectSqlGenTest {

    private val mysql = MySqlDialect()
    private val pg = PostgresDialect()

    private fun col(name: String, jdbcType: Int): ColumnMeta {
        return ColumnMeta(name, "VARCHAR", jdbcType, false, 0, false)
    }

    private fun pk(name: String, jdbcType: Int): ColumnMeta {
        return ColumnMeta(name, "BIGINT", jdbcType, true, 1, false)
    }

    @Test
    fun `基本聚合 SQL 包含总数与空值统计`() {
        val cols = listOf(col("name", Types.VARCHAR), col("age", Types.INTEGER))
        val sql = mysql.buildColumnStatsSql("db1", "user", cols,
            null, null, listOf(), false, 0L, null)

        assertTrue(sql.contains("SELECT COUNT(*) AS total"))
        assertTrue(sql.contains("SUM(CASE WHEN `name` IS NULL THEN 1 ELSE 0 END) AS c0_null"))
        assertTrue(sql.contains("SUM(CASE WHEN TRIM(`name`) = '' THEN 1 ELSE 0 END) AS c0_empty"))
        assertTrue(sql.contains("SUM(CASE WHEN `age` IS NULL THEN 1 ELSE 0 END) AS c1_null"))
        assertFalse(sql.contains("c1_empty")) // 非字符列不统计空串
        assertTrue(sql.contains("FROM `db1`.`user`"))
        assertFalse(sql.contains("WHERE"))
    }

    @Test
    fun `分段谓词 数值键不加引号`() {
        val cols = listOf(col("name", Types.VARCHAR))
        val key = pk("id", Types.BIGINT)
        val sql = mysql.buildColumnStatsSql("db1", "user", cols,
            Range("100", "200", false), key, listOf(), false, 0L, null)

        assertTrue(sql.contains("WHERE `id` >= 100 AND `id` < 200"))
    }

    @Test
    fun `分段谓词 末段无上界`() {
        val key = pk("id", Types.BIGINT)
        val sql = mysql.buildColumnStatsSql("db1", "user", listOf(col("name", Types.VARCHAR)),
            Range("900", null, false), key, listOf(), false, 0L, null)
        assertTrue(sql.contains("`id` >= 900"))
        assertFalse(sql.contains("`id` <"))
    }

    @Test
    fun `NULL键补充分段`() {
        val key = pk("id", Types.BIGINT)
        val sql = mysql.buildColumnStatsSql("db1", "user", listOf(col("name", Types.VARCHAR)),
            Range(null, null, true), key, listOf(), false, 0L, null)
        assertTrue(sql.contains("WHERE `id` IS NULL"))
    }

    @Test
    fun `字符串键范围 值加引号并转义`() {
        val key = ColumnMeta("code", "VARCHAR", Types.VARCHAR, true, 1, false)
        val sql = pg.buildColumnStatsSql("public", "t", listOf(col("v", Types.INTEGER)),
            Range("a'1", "b2", false), key, listOf(), false, 0L, null)
        assertTrue(sql.contains("\"code\" >= 'a''1' AND \"code\" < 'b2'"))
    }

    @Test
    fun `自定义空值规则 数值列原样 字符列加引号`() {
        val cols = listOf(col("status", Types.INTEGER), col("remark", Types.VARCHAR))
        val rules = listOf(
            NullRule("status", listOf("0", "-1")),
            NullRule("remark", listOf("N/A", "it's")))
        val sql = mysql.buildColumnStatsSql("db1", "t", cols,
            null, null, rules, false, 0L, null)

        assertTrue(sql.contains("`status` IN (0, -1)"))
        assertTrue(sql.contains("`remark` IN ('N/A', 'it''s')"))
        assertTrue(sql.contains("AS c0_rule"))
        assertTrue(sql.contains("AS c1_rule"))
    }

    @Test
    fun `通配规则 命中所有列`() {
        val cols = listOf(col("a", Types.INTEGER), col("b", Types.VARCHAR))
        val rules = listOf(NullRule("*", listOf("0")))
        val sql = mysql.buildColumnStatsSql("db1", "t", cols,
            null, null, rules, false, 0L, null)
        assertTrue(sql.contains("`a` IN (0)"))
        assertTrue(sql.contains("`b` IN ('0')"))
    }

    @Test
    fun `selector布局与SQL列序一致`() {
        val cols = listOf(col("name", Types.VARCHAR), col("age", Types.INTEGER))
        val rules = listOf(NullRule("age", listOf("0")))
        val layout = mysql.selectorLayout(cols, rules)
        assertEquals(2, layout.size)
        assertTrue(layout[0][0])   // name 是字符列,有空串统计
        assertFalse(layout[0][1])  // name 无规则
        assertFalse(layout[1][0])  // age 非字符
        assertTrue(layout[1][1])   // age 有规则
    }

    @Test
    fun `PG采样 使用TABLESAMPLE`() {
        val sql = pg.buildColumnStatsSql("public", "big_t", listOf(col("v", Types.INTEGER)),
            null, null, listOf(), true, 100_000L, 10_000_000L)
        assertTrue(sql.contains("TABLESAMPLE SYSTEM (1.0)"))
        assertFalse(sql.contains("LIMIT"))
    }

    @Test
    fun `MySQL采样 退化为LIMIT`() {
        val sql = mysql.buildColumnStatsSql("db1", "big_t", listOf(col("v", Types.INTEGER)),
            null, null, listOf(), true, 100_000L, 10_000_000L)
        assertTrue(sql.contains("LIMIT 100000"))
    }

    @Test
    fun `Oracle采样 使用SAMPLE_BLOCK`() {
        val oracle = OracleDialect()
        val sql = oracle.buildColumnStatsSql("SCOTT", "big_t", listOf(col("v", Types.INTEGER)),
            null, null, listOf(), true, 100_000L, 10_000_000L)
        assertTrue(sql.contains("SAMPLE BLOCK (1.0)"))
        assertTrue(sql.contains("FROM \"SCOTT\".\"big_t\""))
    }

    @Test
    fun `Oracle分页 FETCH_FIRST`() {
        val oracle = OracleDialect()
        val key = pk("id", Types.BIGINT)
        val sql = oracle.buildColumnStatsSql("SCOTT", "t", listOf(col("v", Types.INTEGER)),
            Range("5", "9", false), key, listOf(), false, 0L, null)
        assertTrue(sql.contains("\"id\" >= 5 AND \"id\" < 9"))
    }

    @Test
    fun `SqlServer采样 使用TABLESAMPLE行数`() {
        val mssql = SqlServerDialect()
        val sql = mssql.buildColumnStatsSql("dbo", "big_t", listOf(col("v", Types.INTEGER)),
            null, null, listOf(), true, 100_000L, 10_000_000L)
        assertTrue(sql.contains("TABLESAMPLE (100000 ROWS)"))
        assertTrue(sql.contains("FROM [dbo].[big_t]"))
    }

    @Test
    fun `SqlServer标识符 方括号转义`() {
        val mssql = SqlServerDialect()
        val key = pk("id", Types.BIGINT)
        val sql = mssql.buildColumnStatsSql("dbo", "t", listOf(col("v", Types.INTEGER)),
            Range("1", "2", false), key, listOf(), false, 0L, null)
        assertTrue(sql.contains("[id] >= 1 AND [id] < 2"))
    }

    @Test
    fun `分段键选择 单列主键优先`() {
        val id = pk("id", Types.BIGINT)
        val other = ColumnMeta("uk_col", "VARCHAR", Types.VARCHAR, false, 0, true)
        val picked = mysql.pickChunkKey(listOf(other, id))
        assertEquals("id", picked!!.name)
    }

    @Test
    fun `分段键选择 联合主键取首列`() {
        val a = ColumnMeta("a", "BIGINT", Types.BIGINT, true, 1, false)
        val b = ColumnMeta("b", "BIGINT", Types.BIGINT, true, 2, false)
        val picked = mysql.pickChunkKey(listOf(b, a))
        assertEquals("a", picked!!.name)
    }

    @Test
    fun `分段键选择 无键可用返回null`() {
        val blob = ColumnMeta("data", "BLOB", Types.BLOB, false, 0, false)
        assertNull(mysql.pickChunkKey(listOf(blob)))
    }

    @Test
    fun `Oracle表清单 23ai以下用ALL_SEGMENTS`() {
        val sql = OracleDialect.listTablesSql(11)
        assertTrue(sql.contains("FROM all_segments s"))
        assertFalse(sql.contains("user_segments"))
    }

    @Test
    fun `Oracle表清单 23ai起用USER_SEGMENTS且限定当前用户`() {
        val sql = OracleDialect.listTablesSql(23)
        assertTrue(sql.contains("FROM user_segments s"))
        assertTrue(sql.contains("SYS_CONTEXT('USERENV','SESSION_USER')"))
        assertFalse(sql.contains("all_segments"))
    }

    @Test
    fun `Oracle表清单 降级模式不引用段视图`() {
        assertFalse(OracleDialect.listTablesSql(19, false).contains("segments"))
        assertFalse(OracleDialect.listTablesSql(23, false).contains("segments"))
        assertTrue(OracleDialect.listTablesSql(23, false).contains("FROM all_tables t"))
    }

    @Test
    fun `Oracle表清单 DBA_SEGMENTS降级变体`() {
        val sql = OracleDialect.listTablesSql("dba_segments")
        assertTrue(sql.contains("FROM dba_segments s"))
        assertFalse(sql.contains("all_segments"))
        assertFalse(sql.contains("user_segments"))
    }

    @Test
    fun `Oracle段视图降级链 按版本定起点`() {
        assertEquals(listOf("all_segments", "dba_segments", "user_segments", null),
                OracleDialect.segmentViewChain(19))
        assertEquals(listOf("dba_segments", "user_segments", null),
                OracleDialect.segmentViewChain(23))
    }

    @Test
    fun `Oracle段视图探测 无缓存从头探测`() {
        val chain = OracleDialect.segmentViewChain(19)
        assertEquals(chain, OracleDialect.segmentViewPlan(chain, null, 0L))
    }

    @Test
    fun `Oracle段视图探测 命中缓存直接从落点开始`() {
        val chain = OracleDialect.segmentViewChain(19)
        val cached = OracleDialect.SegViewChoice("dba_segments", 1000L)
        assertEquals(listOf("dba_segments", "user_segments", null),
                OracleDialect.segmentViewPlan(chain, cached, 2000L))
        // 缓存落点为「不统计」时同样直接避开所有段视图
        assertEquals(listOf<String?>(null),
                OracleDialect.segmentViewPlan(chain, OracleDialect.SegViewChoice(null, 1000L), 2000L))
    }

    @Test
    fun `Oracle段视图探测 超过重探间隔回到链头`() {
        val chain = OracleDialect.segmentViewChain(19)
        val cached = OracleDialect.SegViewChoice("user_segments", 1000L)
        assertEquals(chain, OracleDialect.segmentViewPlan(chain, cached,
                1000L + OracleDialect.SEG_VIEW_REPROBE_MS + 1))
    }

    @Test
    fun `DM表清单 默认用ALL_SEGMENTS统计大小`() {
        val sql = DmDialect.listTablesSql(true)
        assertTrue(sql.contains("FROM all_segments s"))
    }

    @Test
    fun `DM表清单 降级模式不引用ALL_SEGMENTS`() {
        val sql = DmDialect.listTablesSql(false)
        assertFalse(sql.contains("all_segments"))
        assertTrue(sql.contains("FROM all_tables t"))
    }

    @Test
    fun `Oracle边界值 12c起OFFSET_FETCH`() {
        val oracle = OracleDialect()
        val sql = oracle.boundaryQuerySql("\"S\".\"t\"", "\"id\"", null, 100L, 12)
        assertTrue(sql.contains("OFFSET 100 ROWS FETCH NEXT 1 ROWS ONLY"))
    }

    @Test
    fun `Oracle边界值 12c带prev生成seek条件`() {
        val oracle = OracleDialect()
        val sql = oracle.boundaryQuerySql("\"S\".\"t\"", "\"id\"", "a'1", 100L, 12)
        assertTrue(sql.contains("\"id\" > 'a''1'"))
        assertTrue(sql.contains("OFFSET 100 ROWS FETCH NEXT 1 ROWS ONLY"))
    }

    @Test
    fun `Oracle边界值 11g用ROWNUM双层包装`() {
        val oracle = OracleDialect()
        val sql = oracle.boundaryQuerySql("\"S\".\"t\"", "\"id\"", null, 100L, 11)
        assertTrue(sql.contains("ROWNUM <= 101"))
        assertTrue(sql.contains("dq_rn = 101"))
        assertFalse(sql.contains("OFFSET"))
        // prev 非空时 seek 条件加在内层子查询
        val seek = oracle.boundaryQuerySql("\"S\".\"t\"", "\"id\"", "abc", 100L, 11)
        assertTrue(seek.contains("\"id\" > 'abc'"))
        assertFalse(seek.contains("OFFSET"))
    }

    @Test
    fun `OracleNULL探测 按版本分页`() {
        val oracle = OracleDialect()
        assertTrue(oracle.nullChunkProbeSql("\"S\".\"t\"", "\"id\"", 11).contains("ROWNUM <= 1"))
        assertTrue(oracle.nullChunkProbeSql("\"S\".\"t\"", "\"id\"", 19).contains("FETCH FIRST 1 ROWS ONLY"))
    }

    @Test
    fun `SqlServer边界值 2012起OFFSET_FETCH`() {
        val mssql = SqlServerDialect()
        val sql = mssql.boundaryQuerySql("[dbo].[t]", "[id]", null, 100L, 11)
        assertTrue(sql.contains("OFFSET 100 ROWS FETCH NEXT 1 ROWS ONLY"))
        // prev 非空时生成 seek 条件
        val seek = mssql.boundaryQuerySql("[dbo].[t]", "[id]", "k1", 100L, 11)
        assertTrue(seek.contains("[id] > 'k1'"))
        assertTrue(seek.contains("OFFSET 100 ROWS FETCH NEXT 1 ROWS ONLY"))
    }

    @Test
    fun `SqlServer边界值 2008用ROW_NUMBER包装`() {
        val mssql = SqlServerDialect()
        val sql = mssql.boundaryQuerySql("[dbo].[t]", "[id]", null, 100L, 10)
        assertTrue(sql.contains("ROW_NUMBER() OVER (ORDER BY [id])"))
        assertTrue(sql.contains("dq_rn = 101"))
        assertFalse(sql.contains("OFFSET"))
        // prev 非空时 seek 条件加进内层
        val seek = mssql.boundaryQuerySql("[dbo].[t]", "[id]", "k1", 100L, 10)
        assertTrue(seek.contains("[id] > 'k1'"))
        assertFalse(seek.contains("OFFSET"))
    }

    @Test
    fun `SqlServerNULL探测 按版本分页`() {
        val mssql = SqlServerDialect()
        assertTrue(mssql.nullChunkProbeSql("[dbo].[t]", "[id]", 10).contains("TOP 1"))
        assertFalse(mssql.nullChunkProbeSql("[dbo].[t]", "[id]", 10).contains("OFFSET"))
        assertTrue(mssql.nullChunkProbeSql("[dbo].[t]", "[id]", 15).contains("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"))
    }
}
