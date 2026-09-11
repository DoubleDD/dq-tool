package com.example.dq.repository

import com.example.dq.model.ScanColumnView
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 扫描快照里的超长源库文本(V42 放宽为 CLOB):MySQL ENUM 的完整枚举列表(COLUMN_TYPE)、
 * 超长注释与默认值写入不再失败,且完整保留不截断——扫描记录是历史快照,丢字会让离线查看与
 * 导出的结构与源库不符。
 */
class ScanMetadataTextTest {

    private lateinit var jdbc: Jdbc
    private lateinit var repo: ScanRepository

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:scan-text-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = ScanRepository(jdbc)
    }

    @Test
    fun `超长字段类型与注释完整写入扫描快照`() {
        val longEnum = "enum(" + (1..40).joinToString(",") { "'VALUE_$it'" } + ")"
        assertTrue(longEnum.length > 256, "测试值须超过 scan_column.column_type 原列宽")
        val longComment = "y".repeat(3000)
        val longDefault = "d".repeat(3000)
        val longTableComment = "x".repeat(3000)

        val jobId = repo.insertJob(1L, null, "public", false, "[]", 1)
        val tableId = repo.insertScanTable(jobId, "t_user", 100L, 1024L, longTableComment, "InnoDB")
        repo.insertScanColumn(
            tableId,
            ScanColumnView.of("status", longEnum, longComment, true, longDefault, "", 100, 0, 0, 0)
        )

        val c = repo.listScanColumns(tableId).single()
        assertEquals(longEnum, c.columnType)
        assertEquals(longComment, c.columnComment)
        assertEquals(longDefault, c.defaultValue)
        assertEquals(longTableComment, repo.listScanTables(jobId).single().comment)
    }
}
