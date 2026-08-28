package com.example.dq.service

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 抽样导出上传 Excel 解析:表头定位与缺列报错、数值单元格经 DataFormatter 取显示字符串、
 * 空端口归一、全空行跳过、无效行收集;buildJdbcUrl/dsKey/keyOfExisting 往返一致
 */
class SampleTableExcelParserTest {

    private val headers = listOf(
        "所属水利对象类别名称", "系统编号", "实际系统或模式描述", "数据库类型", "地址", "端口",
        "用户名", "口令", "数据库名称", "模式名称", "表英文名称", "表中文名称")

    /** 内存构造 xlsx:rows 每行是 表头名→值 的 map(值为 String 或 Number,Number 模拟数值单元格) */
    private fun xlsx(headerOrder: List<String>, rows: List<Map<String, Any?>>): ByteArrayInputStream {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("导入数据")
        val headRow = sheet.createRow(0)
        headerOrder.forEachIndexed { i, h -> headRow.createCell(i).setCellValue(h) }
        rows.forEachIndexed { r, rowMap ->
            val row = sheet.createRow(r + 1)
            headerOrder.forEachIndexed { c, h ->
                when (val v = rowMap[h]) {
                    null -> Unit // 缺单元格(模拟空白)
                    is Number -> row.createCell(c).setCellValue(v.toDouble())
                    else -> row.createCell(c).setCellValue(v.toString())
                }
            }
        }
        val out = ByteArrayOutputStream()
        wb.use { it.write(out) }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun row(
        dbType: String = "mysql", host: String = "10.0.0.1", port: Any? = "3306",
        database: String = "app", table: String = "wr_bas_b",
    ): Map<String, Any?> = mapOf(
        "所属水利对象类别名称" to "01流域", "系统编号" to "101", "实际系统或模式描述" to "小水电系统",
        "数据库类型" to dbType, "地址" to host, "端口" to port, "用户名" to "root", "口令" to "p@ss",
        "数据库名称" to database, "模式名称" to database, "表英文名称" to table, "表中文名称" to "流域基本信息表")

    @Test
    fun `表头按名定位,列顺序无关`() {
        val input = xlsx(headers.reversed(), listOf(row()))
        val result = SampleTableExcelParser.parse(input)
        assertEquals(1, result.rows.size)
        assertEquals(0, result.skipped.size)
        val r = result.rows[0]
        assertEquals(DbType.MYSQL, r.dbType)
        assertEquals("10.0.0.1", r.host)
        assertEquals(3306, r.port)
        assertEquals("app", r.databaseName)
        assertEquals("wr_bas_b", r.tableName)
        assertEquals("小水电系统", r.sysDesc)
    }

    @Test
    fun `缺必需列表头报错并列出缺哪些`() {
        val noDbType = headers.filter { it != "数据库类型" && it != "地址" }
        val e = assertThrows(IllegalArgumentException::class.java) {
            SampleTableExcelParser.parse(xlsx(noDbType, listOf(row())))
        }
        assertTrue(e.message!!.contains("数据库类型"), "报错应列出缺的列: ${e.message}")
        assertTrue(e.message!!.contains("地址"), "报错应列出缺的列: ${e.message}")
    }

    @Test
    fun `数值单元格经 DataFormatter 取显示字符串`() {
        // 端口与口令是数值型单元格:端口要成 Int,口令要成普通字符串(不能是 123456.0)
        val input = xlsx(headers, listOf(row(port = 3306).plus("口令" to 123456)))
        val r = SampleTableExcelParser.parse(input).rows[0]
        assertEquals(3306, r.port)
        assertEquals("123456", r.password)
    }

    @Test
    fun `空端口归一为 null,生效端口按方言默认`() {
        val input = xlsx(headers, listOf(row(dbType = "sqlserver", port = null, database = "master")))
        val r = SampleTableExcelParser.parse(input).rows[0]
        assertNull(r.port)
        assertEquals(1433, r.effectivePort)
        assertEquals("jdbc:sqlserver://10.0.0.1:1433;databaseName=master", r.jdbcUrl)
    }

    @Test
    fun `地址前导空格被 trim`() {
        val input = xlsx(headers, listOf(row(host = "  10.0.0.2 ")))
        assertEquals("10.0.0.2", SampleTableExcelParser.parse(input).rows[0].host)
    }

    @Test
    fun `全空行跳过,空用户名口令归一为 null`() {
        val input = xlsx(headers, listOf(
            row().plus(mapOf("用户名" to "", "口令" to "")),
            emptyMap(), // 全空行
            row(table = "t2"),
        ))
        val result = SampleTableExcelParser.parse(input)
        assertEquals(2, result.rows.size)
        assertNull(result.rows[0].username)
        assertNull(result.rows[0].password)
        assertEquals(1, result.rows[0].seq)
        assertEquals(2, result.rows[1].seq)
    }

    @Test
    fun `缺必需列值与类型无法识别的行收集为无效行`() {
        val input = xlsx(headers, listOf(
            row(table = "t1"),
            row(dbType = "", table = "t2"),                    // 缺数据库类型
            row(dbType = "sqlite", table = "t3"),              // 类型无法识别
            row(host = "", table = "t4"),                      // 缺地址
            row(table = "t5"),
        ))
        val result = SampleTableExcelParser.parse(input)
        assertEquals(2, result.rows.size)
        assertEquals(3, result.skipped.size)
        assertEquals(2, result.skipped[0].seq)
        assertTrue(result.skipped[0].reason.contains("数据库类型"))
        assertTrue(result.skipped[1].reason.contains("sqlite"))
        assertTrue(result.skipped[2].reason.contains("地址"))
        // 有效行 seq 与无效行共享同一行序
        assertEquals(listOf(1, 5), result.rows.map { it.seq })
    }

    @Test
    fun `buildJdbcUrl 各类型拼法`() {
        val p = SampleTableExcelParser
        assertEquals("jdbc:mysql://h:3306/db", p.buildJdbcUrl(DbType.MYSQL, "h", 3306, "db"))
        assertEquals("jdbc:kingbase8://h:54321/db", p.buildJdbcUrl(DbType.KINGBASE, "h", 54321, "db"))
        assertEquals("jdbc:postgresql://h:5432/db", p.buildJdbcUrl(DbType.POSTGRESQL, "h", 5432, "db"))
        assertEquals("jdbc:highgo://h:5866/db", p.buildJdbcUrl(DbType.HIGHGO, "h", 5866, "db"))
        assertEquals("jdbc:sqlserver://h:1433;databaseName=db", p.buildJdbcUrl(DbType.SQLSERVER, "h", 1433, "db"))
        assertEquals("jdbc:oracle:thin:@//h:1521/db", p.buildJdbcUrl(DbType.ORACLE, "h", 1521, "db"))
        assertEquals("jdbc:dm://h:5236", p.buildJdbcUrl(DbType.DM, "h", 5236, "db"))
        assertEquals("jdbc:oceanbase://h:2881/db", p.buildJdbcUrl(DbType.OCEANBASE, "h", 2881, "db"))
    }

    @Test
    fun `dsKey 与 keyOfExisting 往返一致`() {
        // Excel 行 → 建档 jdbcUrl → 回读已存数据源:key 必须一致(重复导入才能命中去重)
        for (type in DbType.entries) {
            val row = SampleTableExcelParser.SampleTableRow(
                seq = 1, category = "c", sysNo = "1", sysDesc = "系统", dbType = type,
                host = "Db.Host", port = null, username = "sa", password = "x",
                databaseName = "app", schemaName = "app", tableName = "t", tableCnName = "表")
            val existing = DataSourceConfig()
            existing.dbType = type
            existing.jdbcUrl = row.jdbcUrl
            existing.username = "sa"
            assertEquals(row.dsKey, SampleTableExcelParser.keyOfExisting(existing),
                "类型 $type 的 dsKey 应往返一致")
        }
    }

    @Test
    fun `keyOfExisting 从既有 URL 形态提取库名`() {
        fun config(type: DbType, url: String, username: String? = "sa") = DataSourceConfig().apply {
            dbType = type; jdbcUrl = url; this.username = username
        }
        val p = SampleTableExcelParser
        // mysql 带参数:取 ? 前路径段;host 大写归一小写
        assertEquals("MYSQL|db.internal|3307|sa|app",
            p.keyOfExisting(config(DbType.MYSQL, "jdbc:mysql://Db.Internal:3307/app?useSSL=false")))
        assertEquals("SQLSERVER|h|1433|sa|app",
            p.keyOfExisting(config(DbType.SQLSERVER, "jdbc:sqlserver://h:1433;databaseName=app;encrypt=false")))
        assertEquals("ORACLE|h|1521|sa|ORCLPDB1",
            p.keyOfExisting(config(DbType.ORACLE, "jdbc:oracle:thin:@//h:1521/ORCLPDB1")))
        assertEquals("DM|h|5236|sa|",
            p.keyOfExisting(config(DbType.DM, "jdbc:dm://h:5236")))
        // 无库名的 sqlserver URL 不参与匹配
        assertNull(p.keyOfExisting(config(DbType.SQLSERVER, "jdbc:sqlserver://h:1433")))
        // 用户名空两侧一致
        assertEquals("MYSQL|h|3306||app",
            p.keyOfExisting(config(DbType.MYSQL, "jdbc:mysql://h:3306/app", null)))
    }
}
