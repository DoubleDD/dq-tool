package com.example.dq.util

import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** Navicat 11+(.ncx Ver 1.x)密码解密:roundtrip + 真实示例密文验证锚点 */
class NavicatCryptoTest {

    @Test
    fun `加密解密往返`() {
        assertEquals("mySecretPwd123", NavicatCrypto.decrypt(NavicatCrypto.encrypt("mySecretPwd123")))
        // 空串也可 roundtrip(PKCS7 填充整块)
        assertEquals("", NavicatCrypto.decrypt(NavicatCrypto.encrypt("")))
    }

    @Test
    fun `真实示例密文可解密为可打印密码串`() {
        // 来自 import-example/navicat.ncx 的真实密码密文(验证算法正确的锚点)
        val plain = NavicatCrypto.decrypt("6174CAC8F6D6D1C302838D640D0D59C0E3A2AF278CD6CA6C16195C5A7079EAD3")
        assertTrue(plain.isNotEmpty())
        assertTrue(plain.all { it.code in 0x20..0x7E }, "解密结果应全部为可打印字符: $plain")
        println("真实密文解密结果: $plain")
    }

    @Test
    fun `坏密文抛中文说明`() {
        assertThrows(IllegalArgumentException::class.java) {
            NavicatCrypto.decrypt("ZZZZ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NavicatCrypto.decrypt("6174CAC8F6D6D1C302838D640D0D59C0")
        }
    }
}
