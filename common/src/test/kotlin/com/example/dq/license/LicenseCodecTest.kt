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
        val code = LicenseCodec.encode("某某公司", LocalDate.of(2026, 12, 31), kp.private,
            timestamp = 1755000000000L)

        val payload = LicenseCodec.decodeAndVerify(code, kp.public)

        assertEquals("某某公司", payload.customer)
        assertEquals(LocalDate.of(2026, 12, 31), payload.expiresAt)
        // 未传扩展字段时解码为 null
        assertNull(payload.serverUrl)
        assertNull(payload.username)
        assertNull(payload.sid)
        assertEquals(1755000000000L, payload.timestamp)
    }

    @Test
    fun `扩展字段 server_url username sid timestamp 往返`() {
        val kp = genKeyPair()
        val code = LicenseCodec.encode("某某公司", LocalDate.of(2026, 12, 31), kp.private,
            serverUrl = "jdbc:oracle:thin:@//db.internal:1521/ORCL",
            username = "scott", sid = "ORCL", timestamp = 1755000000000L)

        val payload = LicenseCodec.decodeAndVerify(code, kp.public)

        assertEquals("某某公司", payload.customer)
        assertEquals("jdbc:oracle:thin:@//db.internal:1521/ORCL", payload.serverUrl)
        assertEquals("scott", payload.username)
        assertEquals("ORCL", payload.sid)
        assertEquals(1755000000000L, payload.timestamp)
    }

    @Test
    fun `软件版本段随授权码往返`() {
        val kp = genKeyPair()
        val code = LicenseCodec.encode("某某公司", LocalDate.of(2026, 12, 31), kp.private,
            appVersion = "1.5", username = "scott", timestamp = 1755000000000L)

        val payload = LicenseCodec.decodeAndVerify(code, kp.public)

        assertEquals("1.5", payload.appVersion)
        assertEquals("scott", payload.username)
        assertEquals(1755000000000L, payload.timestamp)
    }

    @Test
    fun `兼容无版本段的六段格式授权码`() {
        val kp = genKeyPair()
        // 手工构造 6 段旧格式 payload(客户名|有效期|server_url|username|sid|timestamp)
        val legacyPayload = "老客户|2027-06-30|http://db:1521|scott|ORCL|1755000000000".toByteArray()
        val sig = java.security.Signature.getInstance("Ed25519").apply {
            initSign(kp.private)
            update(legacyPayload)
        }.sign()
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val code = "DQ1.${b64.encodeToString(legacyPayload)}.${b64.encodeToString(sig)}"

        val payload = LicenseCodec.decodeAndVerify(code, kp.public)

        assertEquals("老客户", payload.customer)
        assertNull(payload.appVersion)
        assertEquals("http://db:1521", payload.serverUrl)
        assertEquals("scott", payload.username)
        assertEquals("ORCL", payload.sid)
        assertEquals(1755000000000L, payload.timestamp)
    }

    @Test
    fun `兼容两段旧格式授权码`() {
        val kp = genKeyPair()
        // 手工构造旧格式 payload(客户名|有效期),验签逻辑不变
        val legacyPayload = "老客户|2027-06-30".toByteArray()
        val sig = java.security.Signature.getInstance("Ed25519").apply {
            initSign(kp.private)
            update(legacyPayload)
        }.sign()
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val code = "DQ1.${b64.encodeToString(legacyPayload)}.${b64.encodeToString(sig)}"

        val payload = LicenseCodec.decodeAndVerify(code, kp.public)

        assertEquals("老客户", payload.customer)
        assertEquals(LocalDate.of(2027, 6, 30), payload.expiresAt)
        assertNull(payload.serverUrl)
        assertNull(payload.username)
        assertNull(payload.sid)
        assertNull(payload.timestamp)
    }

    @Test
    fun `段数不对或时间戳非数字拒绝`() {
        val kp = genKeyPair()
        val b64 = Base64.getUrlEncoder().withoutPadding()
        fun signPayload(text: String): String {
            val payload = text.toByteArray()
            val sig = java.security.Signature.getInstance("Ed25519").apply {
                initSign(kp.private)
                update(payload)
            }.sign()
            return "DQ1.${b64.encodeToString(payload)}.${b64.encodeToString(sig)}"
        }
        // 3 段:不是旧格式也不是新格式
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify(signPayload("客户A|2027-06-30|extra"), kp.public)
        }
        // 6 段但 timestamp 非数字
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify(signPayload("客户A|2027-06-30|||ORCL|not-a-number"), kp.public)
        }
        // 空客户名
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decodeAndVerify(signPayload("|2027-06-30"), kp.public)
        }
    }

    @Test
    fun `签发侧校验扩展字段不含竖线`() {
        val kp = genKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.encode("客户A", LocalDate.now(), kp.private, appVersion = "含|竖线")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.encode("客户A", LocalDate.now(), kp.private, serverUrl = "含|竖线")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.encode("客户A", LocalDate.now(), kp.private, username = "含|竖线")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.encode("客户A", LocalDate.now(), kp.private, sid = "含|竖线")
        }
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
