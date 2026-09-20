package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.license.LicenseCodec
import com.example.dq.license.LicenseMenu
import com.example.dq.model.LicenseAdminRequiredException
import com.example.dq.model.LicenseGenerateRequest
import com.example.dq.model.LicenseMenuRequiredException
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

    /** 同时配置验签公钥与签发私钥的实例(可激活 + 可签发) */
    private fun newServiceWithKey(publicKeyBase64: String, privateKeyBase64: String = "", appVersion: String = "1.5"): LicenseService =
        LicenseService(licenseRepo, crypto, publicKeyBase64, recordRepo, privateKeyBase64, appVersion)

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

    @Test
    fun `激活授权码含菜单列表时status透出并校验`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = newServiceWithKey(
            Base64.getEncoder().encodeToString(kp.public.encoded),
            Base64.getEncoder().encodeToString(kp.private.encoded))
        // 管理员签发:显式勾选 3 个菜单(新格式无隐式基础集,未勾选的菜单一律不开放;
        // 导出中心授权恒显,解析时强制并入,见 LicenseMenu)
        val record = service.generateLicense(
            LicenseGenerateRequest("甲公司", "2027-12-31", menus = listOf("dashboard", "compare", "license-admin")))

        service.activate(record.code)
        val status = service.status()
        assertTrue(status.activated)
        assertEquals(setOf("dashboard", "export-center", "compare", "license-admin"), status.menus!!.toSet())
        // 勾选的菜单校验通过;未勾选的拒绝
        service.checkMenu(LicenseMenu.COMPARE)
        service.checkMenu(LicenseMenu.LICENSE_ADMIN, false)
        assertThrows(LicenseMenuRequiredException::class.java) { service.checkMenu(LicenseMenu.LOGS) }
        assertThrows(LicenseMenuRequiredException::class.java) { service.checkMenu(LicenseMenu.DATASOURCE) }
        // 留档可见菜单列表(按枚举声明顺序规范化;恒显的导出中心在激活解析时并入,不回写留档)
        assertEquals("dashboard,compare,license-admin", service.listLicenses().single().menus)
    }

    @Test
    fun `签发未传菜单时默认开放除数据比对与授权管理外的全部菜单`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = newServiceWithKey(
            Base64.getEncoder().encodeToString(kp.public.encoded),
            Base64.getEncoder().encodeToString(kp.private.encoded))
        val record = service.generateLicense(LicenseGenerateRequest("甲公司", "permanent"))

        service.activate(record.code)
        val status = service.status()
        assertTrue(status.activated)
        // 默认:全部菜单除 数据比对/授权管理
        val expectedSet = LicenseMenu.ALL.filter { it != LicenseMenu.COMPARE && it != LicenseMenu.LICENSE_ADMIN }.toSet()
        assertEquals(expectedSet.map { it.key }.toSet(), status.menus!!.toSet())
        service.checkMenu(LicenseMenu.LOGS)
        service.checkMenu(LicenseMenu.DATASOURCE)
        assertThrows(LicenseMenuRequiredException::class.java) {
            service.checkMenu(LicenseMenu.COMPARE)
        }
        assertThrows(LicenseMenuRequiredException::class.java) {
            service.checkMenu(LicenseMenu.LICENSE_ADMIN, false)
        }
        // 留档 menus 为默认菜单集
        assertEquals(LicenseMenu.encode(expectedSet), service.listLicenses().single().menus)
    }

    @Test
    fun `旧版签发端按旧功能列表推导菜单`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = newServiceWithKey(
            Base64.getEncoder().encodeToString(kp.public.encoded),
            Base64.getEncoder().encodeToString(kp.private.encoded))
        // 旧版只传 features(含受控 compare):按旧口径推导菜单
        val record = service.generateLicense(
            LicenseGenerateRequest("甲公司", "permanent", features = listOf("scan", "compare")))

        service.activate(record.code)
        val expected = LicenseMenu.ALL.filter { it != LicenseMenu.LICENSE_ADMIN }.map { it.key }.toSet()
        assertEquals(expected, service.status().menus!!.toSet())
        service.checkMenu(LicenseMenu.COMPARE)
        assertThrows(LicenseMenuRequiredException::class.java) {
            service.checkMenu(LicenseMenu.LICENSE_ADMIN, false)
        }
    }

    @Test
    fun `旧格式授权码按旧功能段推导菜单`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = newServiceWithKey(Base64.getEncoder().encodeToString(kp.public.encoded))
        // 手工构造 7 段旧格式授权码(无功能段),验签通过
        val legacyPayload = "老客户|2027-06-30|1.5||scott|ORCL|1755000000000".toByteArray()
        val sig = java.security.Signature.getInstance("Ed25519").apply {
            initSign(kp.private)
            update(legacyPayload)
        }.sign()
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val code = "DQ1.${b64.encodeToString(legacyPayload)}.${b64.encodeToString(sig)}"

        service.activate(code)
        val status = service.status()
        assertTrue(status.activated)
        // 无功能段:全部菜单除 数据比对/授权管理
        val expected = LicenseMenu.ALL.filter { it != LicenseMenu.COMPARE && it != LicenseMenu.LICENSE_ADMIN }.map { it.key }.toSet()
        assertEquals(expected, status.menus!!.toSet())
        service.checkMenu(LicenseMenu.DASHBOARD)
        assertThrows(LicenseMenuRequiredException::class.java) {
            service.checkMenu(LicenseMenu.COMPARE)
        }
        assertThrows(LicenseMenuRequiredException::class.java) {
            service.checkMenu(LicenseMenu.LICENSE_ADMIN, false)
        }
        // 免鉴权默认关
        assertFalse(status.bypassAuth)
        assertFalse(service.isAuthBypassed())
    }

    @Test
    fun `status回传当前激活码明文供前端输入框回填`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = newServiceWithKey(
            Base64.getEncoder().encodeToString(kp.public.encoded),
            Base64.getEncoder().encodeToString(kp.private.encoded))
        // 未激活(H2 无授权码):code 为 null,前端才回落兜底内测码
        assertNull(service.status().code)

        // 激活后:status 透出 H2 中当前授权码明文,前端激活页/更换授权码对话框回填它
        val record = service.generateLicense(LicenseGenerateRequest("甲公司", "permanent"))
        service.activate(record.code)
        assertEquals(record.code, service.status().code)
    }

    @Test
    fun `备注仅留档展示不写入授权码`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = newService(Base64.getEncoder().encodeToString(kp.private.encoded))

        // 带备注签发:留档与视图可见,授权码 payload 不变(解码成功即 10 段,无备注段)
        val record = service.generateLicense(
            LicenseGenerateRequest("甲公司", "2027-12-31", remark = "  张三对接的演示码  "))
        assertEquals("张三对接的演示码", record.remark)
        val payload = LicenseCodec.decodeAndVerify(record.code, kp.public)
        assertEquals("甲公司", payload.customer)
        assertEquals("张三对接的演示码", service.listLicenses().single().remark)

        // 空白备注按 NULL 存
        val blank = service.generateLicense(LicenseGenerateRequest("乙公司", "permanent", remark = "   "))
        assertNull(blank.remark)
    }

    @Test
    fun `免鉴权标记签发激活后生效且过期不生效`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val privateB64 = Base64.getEncoder().encodeToString(kp.private.encoded)
        val service = newServiceWithKey(
            Base64.getEncoder().encodeToString(kp.public.encoded), privateB64)

        // 勾选免鉴权:留档、payload、状态三者一致
        val record = service.generateLicense(
            LicenseGenerateRequest("甲公司", "2027-12-31", menus = listOf("dashboard"), bypassAuth = true))
        assertTrue(record.bypassAuth)
        assertTrue(LicenseCodec.decodeAndVerify(record.code, kp.public).bypassAuth)
        service.activate(record.code)
        assertTrue(service.status().bypassAuth)
        assertTrue(service.isAuthBypassed())

        // 默认不勾选:不豁免
        val plain = service.generateLicense(LicenseGenerateRequest("乙公司", "permanent"))
        assertFalse(plain.bypassAuth)
        assertFalse(LicenseCodec.decodeAndVerify(plain.code, kp.public).bypassAuth)

        // 过期授权码:即使带标记也不生效(直接写库构造过期激活态)
        val expiredCode = LicenseCodec.encode("丙公司", LocalDate.of(2020, 1, 1), kp.private, bypassAuth = true)
        licenseRepo.upsert(crypto.encrypt(expiredCode)!!, "丙公司", LocalDate.of(2020, 1, 1))
        val reloaded = newServiceWithKey(
            Base64.getEncoder().encodeToString(kp.public.encoded), privateB64)
        assertTrue(reloaded.status().expired)
        assertFalse(reloaded.status().bypassAuth)
        assertFalse(reloaded.isAuthBypassed())
    }
}
