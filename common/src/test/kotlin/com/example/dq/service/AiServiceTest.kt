package com.example.dq.service

import com.example.dq.model.ColumnMeta
import com.example.dq.model.TableStat
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test

import java.net.InetSocketAddress
import java.sql.Types
import java.util.concurrent.atomic.AtomicReference

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 表说明 prompt 组装(纯函数,不依赖大模型接口) */
class AiServiceTest {

    @Test
    fun `prompt包含表信息与字段要素`() {
        val table = TableStat("user_order", 12_500_000L, 1_280_000_000L, "订单表", "InnoDB")
        val cols = listOf(
            ColumnMeta("id", "bigint", "bigint(20)", Types.BIGINT, false, null, "主键", true, 1, false),
            ColumnMeta("mobile", "varchar", "varchar(20)", Types.VARCHAR, true, null, "手机号", false, 0, true),
            ColumnMeta("amount", "decimal", "decimal(10,2)", Types.DECIMAL, false, "0", "", false, 0, false))

        val prompt = AiService.buildTablePrompt(table, cols)

        assertTrue(prompt.contains("表名:user_order"))
        assertTrue(prompt.contains("表注释:订单表"))
        assertTrue(prompt.contains("引擎/表空间:InnoDB"))
        assertTrue(prompt.contains("约行数:12500000"))
        assertTrue(prompt.contains("字段(共 3 个)"))
        assertTrue(prompt.contains("- id bigint(20) [PK] 非空 — 主键"))
        assertTrue(prompt.contains("- mobile varchar(20) [UNI] — 手机号"))
        assertTrue(prompt.contains("- amount decimal(10,2) 非空"))
        assertFalse(prompt.contains("省略"))
    }

    @Test
    fun `空注释与空行数不出现在prompt中`() {
        val table = TableStat("t1", null, null)
        val prompt = AiService.buildTablePrompt(table, listOf(
            ColumnMeta("c1", "int", Types.INTEGER, false, 0, false)))

        assertFalse(prompt.contains("表注释"))
        assertFalse(prompt.contains("约行数"))
        assertTrue(prompt.contains("字段(共 1 个)"))
    }

    @Test
    fun `超过字段上限时截断并标注省略数量`() {
        val table = TableStat("wide_table", 1L, 1L)
        val cols = ArrayList<ColumnMeta>()
        for (i in 1..AiService.MAX_PROMPT_COLUMNS + 30) {
            cols.add(ColumnMeta("col_$i", "int", Types.INTEGER, false, 0, false))
        }

        val prompt = AiService.buildTablePrompt(table, cols)

        assertTrue(prompt.contains("字段(共 " + (AiService.MAX_PROMPT_COLUMNS + 30) + " 个)"))
        assertTrue(prompt.contains("col_" + AiService.MAX_PROMPT_COLUMNS))
        assertFalse(prompt.contains("col_" + (AiService.MAX_PROMPT_COLUMNS + 1) + " "))
        assertTrue(prompt.contains("其余 30 个字段省略"))
    }

    // ---------- 连通性测试(test) ----------

    private fun configFor(port: Int) =
        AiConfigService.Config("http://127.0.0.1:$port/v1", "test-key", "test-model", usingDefault = false)

    /** 起一个最小 HTTP 服务,记录收到的请求体并按 handler 应答 */
    private fun startServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Pair<HttpServer, AtomicReference<String>> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val received = AtomicReference<String>()
        server.createContext("/v1/chat/completions") { ex ->
            received.set(String(ex.requestBody.readAllBytes()))
            handler(ex)
        }
        server.start()
        return server to received
    }

    @Test
    fun `测试连接_接口返回200视为成功`() {
        val (server, received) = startServer { ex ->
            val body = "{\"choices\":[{\"message\":{\"content\":\"p\"}}]}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        try {
            AiService().test(configFor(server.address.port))
            // 请求体包含模型与鉴权信息
            val body = received.get()
            assertTrue(body.contains("\"model\":\"test-model\""), body)
            assertTrue(body.contains("\"max_tokens\":1"), body)
            assertTrue(body.contains("\"ping\""), body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `测试连接_非2xx响应抛中文异常`() {
        val (server, _) = startServer { ex ->
            val body = "{\"error\":{\"message\":\"invalid api key\"}}".toByteArray()
            ex.sendResponseHeaders(401, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        try {
            val e = assertThrows(IllegalStateException::class.java) {
                AiService().test(configFor(server.address.port))
            }
            assertTrue(e.message!!.contains("HTTP 401"), e.message)
            assertTrue(e.message!!.contains("invalid api key"), e.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `测试连接_地址不可达抛中文异常`() {
        // 先占一个端口再释放,确保无服务监听
        val probe = java.net.ServerSocket(0)
        val freePort = probe.localPort
        probe.close()
        val e = assertThrows(IllegalStateException::class.java) {
            AiService().test(configFor(freePort))
        }
        assertTrue(e.message!!.contains("测试失败"), e.message)
    }
}
