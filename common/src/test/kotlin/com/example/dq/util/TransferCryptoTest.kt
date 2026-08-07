package com.example.dq.util

import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 导出文件专用加解密:roundtrip 与空值直通 */
class TransferCryptoTest {

    @Test
    fun `加密解密往返`() {
        val enc = TransferCrypto.encrypt("P@ssw0rd!中文密码")
        assertEquals("P@ssw0rd!中文密码", TransferCrypto.decrypt(enc))
        // 随机 IV:同明文两次密文不同
        assertTrue(TransferCrypto.encrypt("P@ssw0rd!中文密码") != enc)
    }

    @Test
    fun `null 与空串直通`() {
        assertNull(TransferCrypto.encrypt(null))
        assertNull(TransferCrypto.decrypt(null))
        assertEquals("", TransferCrypto.encrypt(""))
        assertEquals("", TransferCrypto.decrypt(""))
    }

    @Test
    fun `坏密文抛中文说明`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            TransferCrypto.decrypt("aGVsbG8gd29ybGQgaGVsbG8=")
        }
        assertTrue(e.message!!.contains("密码密文无法解密"))
    }
}
