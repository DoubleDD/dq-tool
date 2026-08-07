package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.license.LicenseCodec
import com.example.dq.model.LicenseAdminRequiredException
import com.example.dq.model.LicenseGenerateRequest
import com.example.dq.repository.Jdbc
import com.example.dq.repository.LicenseRecordRepository
import com.example.dq.repository.LicenseRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.time.LocalDate
import java.util.Base64

/** 授权码管理:管理员判定(签发私钥)、生成/留档/查看/删除全链路 */
class LicenseServiceTest {

    private lateinit var crypto: CryptoUtil
    private lateinit var licenseRepo: LicenseRepository
    private lateinit var recordRepo: LicenseRecordRepository

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:license-admin-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        crypto = CryptoUtil(AppConfig(dataDir = java.nio.file.Files.createTempDirectory("license-test")))
        licenseRepo = LicenseRepository(jdbc)
        recordRepo = LicenseRecordRepository(jdbc)
    }

    private fun newService(privateKeyBase64: String = "", appVersion: String = "1.5"): LicenseService =
        LicenseService(licenseRepo, crypto, "", recordRepo, privateKeyBase64, appVersion)

    @Test
    fun `非管理员实例管理方法一律拒绝`() {
        val service = newService(privateKeyBase64 = "")
        assertThrows(LicenseAdminRequiredException::class.java) { service.listLicenses() }
        assertThrows(LicenseAdminRequiredException::class.java) {
            service.generateLicense(LicenseGenerateRequest("甲公司", "permanent"))
        }
        assertThrows(LicenseAdminRequiredException::class.java) { service.deleteLicense(1L) }
        // status 视图:非管理员
        assertFalse(service.status().admin)
        assertEquals("1.5", service.status().appVersion)
    }

    @Test
    fun `私钥不可解析按非管理员处理`() {
        val service = newService(privateKeyBase64 = "!!!not-base64!!!")
        assertFalse(service.status().admin)
        assertThrows(LicenseAdminRequiredException::class.java) { service.listLicenses() }
    }

    @Test
    fun `管理员生成留档查看删除全链路`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = newService(Base64.getEncoder().encodeToString(kp.private.encoded))
        assertTrue(service.status().admin)

        val record = service.generateLicense(
            LicenseGenerateRequest("甲公司", "2027-12-31",
                serverUrl = "jdbc:oracle:thin:@//db.internal:1521/ORCL", username = "scott", sid = "ORCL"))
        assertTrue(record.id > 0)
        assertEquals("1.5", record.appVersion)
        assertEquals("甲公司", record.customer)
        assertEquals(LocalDate.of(2027, 12, 31), record.expiresAt)
        assertEquals("scott", record.username)
        assertTrue(record.code.startsWith("DQ1."), record.code)

        // 生成的码能被对应公钥验过,版本与扩展字段一致
        val payload = LicenseCodec.decodeAndVerify(record.code, kp.public)
        assertEquals("甲公司", payload.customer)
        assertEquals("1.5", payload.appVersion)
        assertEquals("jdbc:oracle:thin:@//db.internal:1521/ORCL", payload.serverUrl)
        assertEquals(record.issuedAt, payload.timestamp)

        // 留档查看:完整授权码解密回传
        val list = service.listLicenses()
        assertEquals(1, list.size)
        assertEquals(record.code, list[0].code)
        assertEquals("ORCL", list[0].sid)

        // 永久授权:expires_at 存 NULL
        val permanent = service.generateLicense(LicenseGenerateRequest("乙公司", "PERMANENT"))
        assertNull(permanent.expiresAt)
        assertNull(LicenseCodec.decodeAndVerify(permanent.code, kp.public).expiresAt)

        // 未传 SID 时自动生成(UUID 去横杠,32 位十六进制,每次不同)
        assertTrue(permanent.sid!!.matches(Regex("[0-9a-f]{32}")), permanent.sid)
        val auto2 = service.generateLicense(LicenseGenerateRequest("丙公司", "permanent"))
        assertNotEquals(permanent.sid, auto2.sid)

        // 删除
        service.deleteLicense(record.id)
        assertEquals(listOf("丙公司", "乙公司"), service.listLicenses().map { it.customer })
    }

    @Test
    fun `生成参数校验`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = newService(Base64.getEncoder().encodeToString(kp.private.encoded))
        // 有效期格式非法
        assertThrows(IllegalArgumentException::class.java) {
            service.generateLicense(LicenseGenerateRequest("甲公司", "明年"))
        }
        // 空客户名
        assertThrows(IllegalArgumentException::class.java) {
            service.generateLicense(LicenseGenerateRequest("  ", "permanent"))
        }
        // 字段含竖线
        assertThrows(IllegalArgumentException::class.java) {
            service.generateLicense(LicenseGenerateRequest("甲公司", "permanent", sid = "含|竖线"))
        }
    }
}
