package com.example.dq.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.SQLException

/** 回源失败降级本地缓存的编排语义(用回调捕获,不碰数据库/网络) */
class CacheFallbackTest {

    private data class Mark(val id: Long, val message: String, val kind: String)

    @Test
    fun `有缓存时连接失败降级返回缓存并记录标记`() {
        val failures = mutableListOf<Mark>()
        val successes = mutableListOf<Long>()
        val guard = CacheFallback({ id, m, k -> failures += Mark(id, m, k) }, { successes += it })

        val result = guard.fetch(
            datasourceId = 7,
            hasCache = { true },
            readCache = { listOf("cached") },
            fetch = { throw SQLException("Communications link failure", ConnectException("Connection refused")) },
        )

        assertThat(result).containsExactly("cached")
        assertThat(guard.consumeFallback()).isTrue()
        assertThat(guard.consumeFallback()).isFalse() // 读取即复位
        assertThat(failures).hasSize(1)
        assertThat(failures[0].id).isEqualTo(7)
        assertThat(failures[0].kind).isEqualTo("UNREACHABLE")
        assertThat(successes).isEmpty()
    }

    @Test
    fun `无缓存时原样抛出且不置降级标志`() {
        val guard = CacheFallback({ _, _, _ -> }, { })
        assertThatThrownBy {
            guard.fetch<Int>(1, hasCache = { false }, readCache = { 0 }) {
                throw SocketTimeoutException("Read timed out")
            }
        }.isInstanceOf(SocketTimeoutException::class.java)
        assertThat(guard.consumeFallback()).isFalse()
    }

    @Test
    fun `非连接类异常不降级不标记`() {
        var failed = false
        val guard = CacheFallback({ _, _, _ -> failed = true }, { })
        assertThatThrownBy {
            guard.fetch<Int>(1, hasCache = { true }, readCache = { 0 }) {
                throw SQLException("Table 'x' doesn't exist", "42S02")
            }
        }.isInstanceOf(SQLException::class.java)
        assertThat(failed).isFalse()
        assertThat(guard.consumeFallback()).isFalse()
    }

    @Test
    fun `回源成功回调自愈且不置降级标志`() {
        val successes = mutableListOf<Long>()
        val guard = CacheFallback({ _, _, _ -> }, { successes += it })
        val result = guard.fetch(9, hasCache = { false }, readCache = { emptyList<String>() }) { listOf("fresh") }
        assertThat(result).containsExactly("fresh")
        assertThat(successes).containsExactly(9L)
        assertThat(guard.consumeFallback()).isFalse()
    }
}
