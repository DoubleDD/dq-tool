package com.example.dq.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 错误指纹归一:同类错误归并、异类错误区分 */
class ErrorFingerprintTest {

    @Test
    fun `数字与引号字面量归一后同指纹`() {
        val a = ErrorFingerprint.of("BACKEND", "java.sql.SQLException", "表 'orders_2024' 查询失败: timeout 5000ms", null)
        val b = ErrorFingerprint.of("BACKEND", "java.sql.SQLException", "表 'orders_2025' 查询失败: timeout 9000ms", null)
        assertEquals(a, b)
    }

    @Test
    fun `来源与类型不同则指纹不同`() {
        val base = ErrorFingerprint.of("BACKEND", "java.lang.IllegalStateException", "状态冲突", null)
        assertNotEquals(base, ErrorFingerprint.of("FRONTEND", "java.lang.IllegalStateException", "状态冲突", null))
        assertNotEquals(base, ErrorFingerprint.of("BACKEND", "java.lang.NullPointerException", "状态冲突", null))
    }

    @Test
    fun `消息不同则指纹不同`() {
        val a = ErrorFingerprint.of("BACKEND", "E", "数据源不存在", null)
        val b = ErrorFingerprint.of("BACKEND", "E", "任务不存在", null)
        assertNotEquals(a, b)
    }

    @Test
    fun `堆栈首帧参与区分`() {
        val stack1 = """
            java.lang.RuntimeException: boom
            	at com.example.dq.service.ScanService.start(ScanService.kt:120)
            	at com.example.dq.web.WebServer.lambda(WebServer.java:300)
        """.trimIndent()
        val stack2 = """
            java.lang.RuntimeException: boom
            	at com.example.dq.service.CompareService.run(CompareService.kt:88)
        """.trimIndent()
        // 行号变化不影响(去掉 (file:line))
        val stack1b = stack1.replace("ScanService.kt:120", "ScanService.kt:456")
        assertEquals(
            ErrorFingerprint.of("BACKEND", "E", "boom", stack1),
            ErrorFingerprint.of("BACKEND", "E", "boom", stack1b),
        )
        assertNotEquals(
            ErrorFingerprint.of("BACKEND", "E", "boom", stack1),
            ErrorFingerprint.of("BACKEND", "E", "boom", stack2),
        )
    }

    @Test
    fun `归一化输出可读且截断`() {
        val normalized = ErrorFingerprint.normalize("FRONTEND", "TypeError", "Cannot read x of 'undefined' at 42", null)
        assertTrue(normalized.startsWith("FRONTEND|TypeError|"))
        assertTrue(normalized.contains("n"))
        assertTrue(ErrorFingerprint.normalizeText("a".repeat(2000)).length <= 500)
    }

    @Test
    fun `十六进制与UUID归一`() {
        val a = ErrorFingerprint.of("BACKEND", "E", "id=550e8400-e29b-41d4-a716-446655440000 code=0xDEADBEEF", null)
        val b = ErrorFingerprint.of("BACKEND", "E", "id=660e8400-e29b-41d4-a716-446655440001 code=0xBEEF", null)
        assertEquals(a, b)
    }
}
