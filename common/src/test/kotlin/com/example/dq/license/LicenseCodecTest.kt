package com.example.dq.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.LocalDate
import java.util.Base64

/** 授权码编解码与验签(纯 JDK,无需容器) */
class LicenseCodecTest {

    private fun genKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    @Test
    fun `签发的码能验过且字段正确`() {
        val kp = genKeyPair()
        val code = LicenseCodec.encode("某某公司", LocalDate.of(2026, 12, 31), kp.private)

        val payload = LicenseCodec.decodeAndVerify(code, kp.public)

        assertEquals("某某公司", payload.customer)
        assertEquals(LocalDate.of(2026, 12, 31), payload.expiresAt)
    }

    @Test
    fun `容忍粘贴带入的空白和换行`() {
        val kp = genKeyPair()
        val code = LicenseCodec.encode("客户A", LocalDate.of(2027, 6, 30), kp.private)
        val messy = "  " + code.substring(0, 10) + "\n" + code.substring(10) + "  \n"

        val payload = LicenseCodec.decodeAndVerify(messy, kp.public)

        assertEquals("客户A", payload.customer)
    }

    @Test
    fun `篡改payload验签失败`() {
        val kp = genKeyPair()
        val code = LicenseCodec.encode("客户A", LocalDate.of(2027, 6, 30), kp.private)
        val parts = code.split(".")
        // 换一个 payload(改为客户B),签名不变
        val tamperedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("客户B|2027-06-30".toByteArray())
        val tampered = parts[0] + "." + tamperedPayload + "." + parts[2]

        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify(tampered, kp.public)
        }
    }

    @Test
    fun `错误公钥验签失败`() {
        val signer = genKeyPair()
        val other = genKeyPair()
        val code = LicenseCodec.encode("客户A", LocalDate.of(2027, 6, 30), signer.private)

        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify(code, other.public)
        }
    }

    @Test
    fun `乱码和错误格式拒绝`() {
        val kp = genKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify("not-a-license", kp.public)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify("DQ2.abc.def", kp.public)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify("", kp.public)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify(null, kp.public)
        }
    }

    @Test
    fun `签发侧校验客户标识`() {
        val kp = genKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.encode("含|竖线", LocalDate.now(), kp.private)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.encode("  ", LocalDate.now(), kp.private)
        }
    }

    @Test
    fun `永久授权码验签通过且永不过期`() {
        val kp = genKeyPair()
        val code = LicenseCodec.encode("永久客户", null, kp.private)

        val payload = LicenseCodec.decodeAndVerify(code, kp.public)

        assertEquals("永久客户", payload.customer)
        assertNull(payload.expiresAt)
        assertFalse(LicenseCodec.isExpired(payload.expiresAt, LocalDate.now().plusYears(100)))
    }

    @Test
    fun `到期日当天有效次日过期`() {
        val expires = LocalDate.of(2026, 12, 31)
        assertFalse(LicenseCodec.isExpired(expires, LocalDate.of(2026, 12, 31)))
        assertTrue(LicenseCodec.isExpired(expires, LocalDate.of(2027, 1, 1)))
    }
}
