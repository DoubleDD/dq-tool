package com.example.dq.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 手动添加实例地址清单(持久化串)的解析与格式化 */
class LanShareServiceManualPeersTest {

    @Test
    fun `解析逗号分隔的 host 端口 清单`() {
        assertEquals(
            listOf("192.168.1.10" to 10000, "10.0.0.2" to 8080),
            LanShareService.parseManualPeers("192.168.1.10:10000, 10.0.0.2:8080"),
        )
    }

    @Test
    fun `空值与空白解析为空清单`() {
        assertEquals(emptyList<Pair<String, Int>>(), LanShareService.parseManualPeers(null))
        assertEquals(emptyList<Pair<String, Int>>(), LanShareService.parseManualPeers(""))
        assertEquals(emptyList<Pair<String, Int>>(), LanShareService.parseManualPeers(" , ,"))
    }

    @Test
    fun `非法项被忽略`() {
        assertEquals(
            listOf("192.168.1.10" to 10000),
            LanShareService.parseManualPeers("192.168.1.10:10000,no-port,:8080,bad:abc,bad:0,bad:99999"),
        )
    }

    @Test
    fun `格式化与解析互逆,空清单存 NULL`() {
        val list = listOf("192.168.1.10" to 10000, "10.0.0.2" to 8080)
        assertEquals(list, LanShareService.parseManualPeers(LanShareService.formatManualPeers(list)))
        assertNull(LanShareService.formatManualPeers(emptyList()))
    }
}
