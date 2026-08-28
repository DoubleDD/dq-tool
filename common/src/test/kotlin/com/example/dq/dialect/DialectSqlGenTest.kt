package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.NullRule
import com.example.dq.model.Range
import com.example.dq.service.PreviewService
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
    fun `sqlserver 版本标签按主版本号映射营销版本`() {
        assertEquals("2014 (12.0.5000.0)", SqlServerDialect.versionLabel("12.0.5000.0"))
        assertEquals("2016 (13.0.1601.5)", SqlServerDialect.versionLabel("13.0.1601.5"))
        assertEquals("2022 (16.0.1000.6)", SqlServerDialect.versionLabel("16.0.1000.6"))
        assertEquals("2008 (10.0.1600.22)", SqlServerDialect.versionLabel("10.0.1600.22"))
        assertEquals("2008 R2 (10.50.1600.1)", SqlServerDialect.versionLabel("10.50.1600.1"))
        // 未识别的主版本/非版本字符串原样返回,不丢信息
        assertEquals("17.0.1000.7", SqlServerDialect.versionLabel("17.0.1000.7"))
        assertEquals("unknown", SqlServerDialect.versionLabel("unknown"))
    }

    @Test
    fun `sqlserver 空串统计用 LTRIM RTRIM 兼容 2016 及以下`() {
        val sql = SqlServerDialect().buildColumnStatsSql("dbo", "t",
            listOf(col("name", Types.VARCHAR)), null, null, listOf(), false, 0L, null)
        // CAST 为 NVARCHAR(MAX):兼容 text/ntext 旧 LOB 类型(LTRIM/RTRIM 不支持)
        assertTrue(sql.contains("SUM(CASE WHEN LTRIM(RTRIM(CAST([name] AS NVARCHAR(MAX)))) = '' THEN 1 ELSE 0 END) AS c0_empty"))
        assertFalse(sql.contains("WHEN TRIM(")) // TRIM 是 SQL Server 2017+ 才有,不能出现在生成 SQL 里
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
    fun `系统库清单 各方言均给出小写集合`() {
        val dialects = listOf(MySqlDialect(), PostgresDialect(), SqlServerDialect(),
                OracleDialect(), DmDialect(), KingbaseDialect(), OceanBaseDialect(), HighGoDialect())
        for (d in dialects) {
            val names = d.systemSchemas()
            assertTrue(names.isNotEmpty(), d.type().name + " 应有系统库清单")
            assertEquals(names.map { it }, names.map { it.lowercase() },
                    d.type().name + " 系统库名须全小写,供前端大小写不敏感比对")
        }
    }

    @Test
    fun `系统库清单 覆盖各库已知系统库`() {
        assertTrue(MySqlDialect().systemSchemas().containsAll(
                listOf("information_schema", "mysql", "sys", "performance_schema")))
        assertTrue(OceanBaseDialect().systemSchemas().contains("oceanbase"))
        assertTrue(PostgresDialect().systemSchemas().containsAll(
                listOf("pg_catalog", "information_schema", "pg_toast")))
        assertTrue(SqlServerDialect().systemSchemas().containsAll(
                listOf("master", "model", "msdb", "tempdb", "reportserver", "reportservertempdb")))
        assertTrue(OracleDialect().systemSchemas().containsAll(listOf("sys", "system")))
        assertTrue(DmDialect().systemSchemas().containsAll(listOf("sys", "sysdba")))
        assertTrue(KingbaseDialect().systemSchemas().containsAll(
                listOf("kingbase", "test", "security", "template0", "template1")))
        assertTrue(HighGoDialect().systemSchemas().contains("pg_catalog"))
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

    @Test
    fun `分页预览 默认方言 LIMIT OFFSET 与总数`() {
        val sql = mysql.pageRowsSql("`db1`.`user`", listOf("id", "name"), null, null, 40, 20)
        assertEquals("SELECT `id`, `name` FROM `db1`.`user` LIMIT 20 OFFSET 40", sql)
        assertEquals("SELECT COUNT(*) FROM `db1`.`user`", mysql.countRowsSql("db1", "user", null))
    }

    @Test
    fun `分页预览 默认方言带 WHERE 与 ORDER BY`() {
        val sql = mysql.pageRowsSql("`db1`.`user`", listOf("id"), "age > 18", "id desc", 0, 20)
        assertEquals("SELECT `id` FROM `db1`.`user` WHERE age > 18 ORDER BY id desc LIMIT 20 OFFSET 0", sql)
        assertEquals("SELECT COUNT(*) FROM `db1`.`user` WHERE age > 18", mysql.countRowsSql("db1", "user", "age > 18"))
    }

    @Test
    fun `分页预览 SqlServer 2012起OFFSET_FETCH 2008用ROW_NUMBER`() {
        val mssql = SqlServerDialect()
        val cols = listOf("id")
        val modern = mssql.pageRowsSql("[dbo].[t]", cols, null, null, 40, 20, 15)
        assertEquals("SELECT [id] FROM [dbo].[t] ORDER BY (SELECT NULL) OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY", modern)
        val legacy = mssql.pageRowsSql("[dbo].[t]", cols, null, null, 40, 20, 10)
        assertTrue(legacy.contains("ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS dq_rn"), legacy)
        assertTrue(legacy.contains("dq_rn > 40 AND dq_rn <= 60"), legacy)
        // 带过滤与用户排序:WHERE 进 FROM 之后,用户排序替换 (SELECT NULL) 占位
        val filtered = mssql.pageRowsSql("[dbo].[t]", cols, "x = 1", "id desc", 40, 20, 15)
        assertEquals("SELECT [id] FROM [dbo].[t] WHERE x = 1 ORDER BY id desc OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY", filtered)
        val legacyFiltered = mssql.pageRowsSql("[dbo].[t]", cols, "x = 1", "id desc", 40, 20, 10)
        assertTrue(legacyFiltered.contains("ROW_NUMBER() OVER (ORDER BY id desc)"), legacyFiltered)
        assertTrue(legacyFiltered.contains("FROM [dbo].[t] WHERE x = 1) dq_p"), legacyFiltered)
    }

    @Test
    fun `分页预览 Oracle 12c起OFFSET_FETCH 11g用ROWNUM包装`() {
        val oracle = OracleDialect()
        val cols = listOf("id")
        val modern = oracle.pageRowsSql("\"SCOTT\".\"T\"", cols, null, null, 40, 20, 19)
        assertEquals("SELECT \"id\" FROM \"SCOTT\".\"T\" OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY", modern)
        val legacy = oracle.pageRowsSql("\"SCOTT\".\"T\"", cols, null, null, 40, 20, 11)
        assertTrue(legacy.contains("ROWNUM <= 60"), legacy)
        assertTrue(legacy.contains("dq_rn > 40"), legacy)
        // 带过滤与排序:12c 直接拼;11g 排序进最内层,ROWNUM 包装保序
        val filtered = oracle.pageRowsSql("\"SCOTT\".\"T\"", cols, "x = 1", "id desc", 40, 20, 19)
        assertEquals("SELECT \"id\" FROM \"SCOTT\".\"T\" WHERE x = 1 ORDER BY id desc OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY", filtered)
        val legacyFiltered = oracle.pageRowsSql("\"SCOTT\".\"T\"", cols, "x = 1", "id desc", 40, 20, 11)
        assertTrue(legacyFiltered.contains("(SELECT \"id\" FROM \"SCOTT\".\"T\" WHERE x = 1 ORDER BY id desc) dq_i"), legacyFiltered)
    }

    @Test
    fun `过滤输入归一 剥离前导关键字`() {
        assertEquals("x = 1", PreviewService.stripKeyword("  WHERE x = 1 ", "where"))
        assertEquals("x = 1", PreviewService.stripKeyword("where x = 1", "where"))
        assertEquals("id desc", PreviewService.stripKeyword("Order By id desc", "order by"))
        // 不以关键字开头或关键字后无空白时原样保留(如列名 whereabouts)
        assertEquals("whereabouts = 1", PreviewService.stripKeyword("whereabouts = 1", "where"))
        assertNull(PreviewService.stripKeyword("   ", "where"))
        assertNull(PreviewService.stripKeyword(null, "where"))
        assertNull(PreviewService.stripKeyword("WHERE", "where"))
    }
}
