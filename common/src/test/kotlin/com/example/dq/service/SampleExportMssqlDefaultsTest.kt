package com.example.dq.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 批量导入时 SQL Server 高级连接参数默认值补全(与数据源页「高级」页签默认组合一致) */
class SampleExportMssqlDefaultsTest {

    @Test
    fun `非 SQL Server URL 原样返回`() {
        val url = "jdbc:mysql://10.0.0.1:3306/water"
        assertEquals(url, SampleExportService.withMssqlDefaults(url))
    }

    @Test
    fun `SQL Server URL 缺参时补齐三个默认参数`() {
        assertEquals(
            "jdbc:sqlserver://10.0.0.1:1433;databaseName=HNFA;encrypt=true;trustServerCertificate=true;sslProtocol=TLSv1.1",
            SampleExportService.withMssqlDefaults("jdbc:sqlserver://10.0.0.1:1433;databaseName=HNFA"))
    }

    @Test
    fun `已有参数不覆盖且大小写不敏感`() {
        assertEquals(
            "jdbc:sqlserver://h:1433;Encrypt=false;trustservercertificate=false;sslprotocol=TLSv1.2",
            SampleExportService.withMssqlDefaults(
                "jdbc:sqlserver://h:1433;Encrypt=false;trustservercertificate=false;sslprotocol=TLSv1.2"))
    }

    @Test
    fun `部分缺失只补缺失项`() {
        assertEquals(
            "jdbc:sqlserver://h:1433;encrypt=false;trustServerCertificate=true;sslProtocol=TLSv1.1",
            SampleExportService.withMssqlDefaults("jdbc:sqlserver://h:1433;encrypt=false"))
    }
}
