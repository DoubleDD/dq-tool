package com.example.dq.service

import com.example.dq.config.ScanConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import io.mockk.every
import io.mockk.mockk
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.sql.DriverManager

/**
 * 预览全量导出(exportTable):H2 内存库实测两行表头(首行中文名称=列注释,次行英文列名)、
 * WHERE 过滤、ORDER BY 排序、NULL 空单元格。
 * 打桩方式同 SqlConsoleServiceTest:DataSourceService 返回 H2 真实连接(PostgreSQL 方言,
 * JDBC 元数据取列 + LIMIT/OFFSET 分页 SQL 均兼容 H2),SystemSettingsService 返回带超时的扫描参数。
 */
class PreviewServiceTest {

    private lateinit var dbUrl: String
    private lateinit var service: PreviewService

    @BeforeEach
    fun setUp() {
        dbUrl = "jdbc:h2:mem:preview-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(dbUrl).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t_user (id INT PRIMARY KEY, name VARCHAR(50))")
                st.execute("COMMENT ON COLUMN t_user.id IS '主键'")
                st.execute("COMMENT ON COLUMN t_user.name IS '姓名'")
                st.execute("INSERT INTO t_user VALUES (1, 'a')")
                st.execute("INSERT INTO t_user VALUES (2, NULL)")
                st.execute("INSERT INTO t_user VALUES (3, 'c')")
            }
        }
        val dataSourceService = mockk<DataSourceService>()
        every { dataSourceService.get(1L) } returns DataSourceConfig().apply { dbType = DbType.POSTGRESQL }
        every { dataSourceService.getConnection(1L, null) } answers { DriverManager.getConnection(dbUrl) }
        val systemSettingsService = mockk<SystemSettingsService>()
        every { systemSettingsService.scanSettings() } returns ScanConfig(statementTimeoutSeconds = 30)
        service = PreviewService(dataSourceService, DialectFactory, systemSettingsService)
    }

    /** 导出并用 POI 读回,返回 (中文表头行, 英文表头行, 数据行) 的纯文本矩阵 */
    private fun exportRows(where: String?, orderBy: String?): Triple<List<String>, List<String>, List<List<String>>> {
        val out = ByteArrayOutputStream()
        service.exportTable(1L, null, "PUBLIC", "T_USER", where, orderBy, out)
        XSSFWorkbook(ByteArrayInputStream(out.toByteArray())).use { wb ->
            val sheet = wb.getSheetAt(0)
            val colCount = sheet.getRow(0).lastCellNum.toInt()
            // NULL 不建单元格,按表头列数取定长行(缺失格为空串)
            fun rowText(r: Int) = (0 until colCount).map { sheet.getRow(r).getCell(it)?.stringCellValue ?: "" }
            val headZh = rowText(0)
            val headEn = rowText(1)
            val rows = (2..sheet.lastRowNum).map { rowText(it) }
            return Triple(headZh, headEn, rows)
        }
    }

    @Test
    fun `无条件导出全部行且保持 ORDER BY 排序`() {
        val (headZh, headEn, rows) = exportRows(null, "id desc")
        // 两行表头:第一行中文名称(列注释,缺失回退列名),第二行英文名称,均不含数据类型
        assertEquals(listOf("主键", "姓名"), headZh)
        assertEquals(listOf("ID", "NAME"), headEn)
        assertEquals(listOf(listOf("3", "c"), listOf("2", ""), listOf("1", "a")), rows)
    }

    @Test
    fun `WHERE 过滤只导出符合条件的行且剥离误带前导关键字`() {
        val (headZh, headEn, rows) = exportRows("WHERE id > 1", "id")
        assertEquals(listOf("主键", "姓名"), headZh)
        assertEquals(listOf("ID", "NAME"), headEn)
        assertEquals(listOf(listOf("2", ""), listOf("3", "c")), rows)
    }
}
