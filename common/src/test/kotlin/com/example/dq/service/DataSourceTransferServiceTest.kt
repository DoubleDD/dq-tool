package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 数据源导入/导出:JSON 往返、重名改名、文件校验、坏条目不中断、Navicat .ncx 导入 */
class DataSourceTransferServiceTest {

    private lateinit var dsRepo: DataSourceRepository
    private lateinit var crypto: CryptoUtil
    private lateinit var dataSourceService: DataSourceService
    private lateinit var service: DataSourceTransferService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ds-transfer-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        dsRepo = DataSourceRepository(jdbc)
        val config = AppConfig(dataDir = Files.createTempDirectory("ds-transfer-test"))
        crypto = CryptoUtil(config)
        dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config, SchemaStatRepository(jdbc))
        service = DataSourceTransferService(dsRepo, crypto, dataSourceService)
    }

    private fun createDs(name: String, password: String, rowThreshold: Long? = null): Long =
        dataSourceService.create(
            DataSourceRequest(name, "jdbc:mysql://localhost:3306/db", "root", password, rowThreshold, null))

    @Test
    fun `导出导入往返保留名称用户名密码与行数阈值`() {
        val id1 = createDs("生产库", "secret-1", 5_000_000L)
        val id2 = createDs("测试库", "secret-2")

        val out = ByteArrayOutputStream()
        service.export(listOf(id1, id2), out)
        val json = out.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"app\" : \"dq-tool\""), json)
        // 导出文件里不出现明文密码,也不是实例密钥加密的密文
        assertTrue(!json.contains("secret-1"), json)

        // 清空后导入(等价于跨实例:新内存库)
        dsRepo.findAll().forEach { dsRepo.delete(it.id!!) }
        val result = service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, result.total)
        assertEquals(2, result.imported.size)
        assertTrue(result.failed.isEmpty(), result.failed.toString())

        val all = dsRepo.findAll().sortedBy { it.name }
        assertEquals(listOf("测试库", "生产库"), all.map { it.name })
        val prod = all.first { it.name == "生产库" }
        assertEquals("root", prod.username)
        assertEquals(5_000_000L, prod.rowThreshold)
        assertEquals("secret-1", crypto.decrypt(prod.password))
        val test = all.first { it.name == "测试库" }
        assertEquals("secret-2", crypto.decrypt(test.password))
    }

    @Test
    fun `重名自动递增改名并记录 renamed`() {
        createDs("A", "x")
        val out = ByteArrayOutputStream()
        // 手工构造含两个 A 的导出文件
        val json = """
            {"app":"dq-tool","version":1,"exportedAt":"2026-08-06T00:00:00Z","items":[
              {"name":"A","jdbcUrl":"jdbc:mysql://h:3306/d1","username":"u",
               "passwordEnc":"${com.example.dq.util.TransferCrypto.encrypt("p1")}","rowThreshold":null,"sizeThresholdBytes":null},
              {"name":"A","jdbcUrl":"jdbc:mysql://h:3306/d2","username":"u",
               "passwordEnc":"${com.example.dq.util.TransferCrypto.encrypt("p2")}","rowThreshold":null,"sizeThresholdBytes":null}
            ]}
        """.trimIndent()
        out.write(json.toByteArray())

        val result = service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, result.imported.size)
        assertTrue(result.failed.isEmpty(), result.failed.toString())
        assertEquals(setOf("A", "A (2)", "A (3)"), dsRepo.findAll().map { it.name }.toSet())
        // renamed 记录原名→新名(两条同名条目后者覆盖前者,最终映射到 "A (3)")
        assertEquals("A (3)", result.renamed["A"])
    }

    @Test
    fun `错误 app 标记与空条目抛参数错误`() {
        val badApp = """{"app":"other","version":1,"exportedAt":"t","items":[{"name":"x"}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            service.importJson(ByteArrayInputStream(badApp.toByteArray()))
        }
        val badVersion = """{"app":"dq-tool","version":2,"exportedAt":"t","items":[{"name":"x"}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            service.importJson(ByteArrayInputStream(badVersion.toByteArray()))
        }
        val empty = """{"app":"dq-tool","version":1,"exportedAt":"t","items":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            service.importJson(ByteArrayInputStream(empty.toByteArray()))
        }
        val notJson = "这不是 JSON"
        assertThrows(IllegalArgumentException::class.java) {
            service.importJson(ByteArrayInputStream(notJson.toByteArray()))
        }
    }

    @Test
    fun `坏条目不中断整批并收集失败明细`() {
        val json = """
            {"app":"dq-tool","version":1,"exportedAt":"t","items":[
              {"name":"好的","jdbcUrl":"jdbc:mysql://h:3306/d","username":"u",
               "passwordEnc":"${com.example.dq.util.TransferCrypto.encrypt("p")}","rowThreshold":null,"sizeThresholdBytes":null},
              {"name":"坏的","jdbcUrl":"not-a-jdbc-url","username":"u",
               "passwordEnc":"${com.example.dq.util.TransferCrypto.encrypt("p")}","rowThreshold":null,"sizeThresholdBytes":null},
              {"name":"密文坏","jdbcUrl":"jdbc:mysql://h:3306/d2","username":"u",
               "passwordEnc":"!!!not-base64!!!","rowThreshold":null,"sizeThresholdBytes":null}
            ]}
        """.trimIndent()
        val result = service.importJson(ByteArrayInputStream(json.toByteArray()))
        assertEquals(3, result.total)
        assertEquals(listOf("好的"), result.imported)
        assertEquals(2, result.failed.size)
        assertEquals(setOf("坏的", "密文坏"), result.failed.map { it.name }.toSet())
        assertTrue(result.failed.all { it.reason.isNotBlank() })
    }

    @Test
    fun `Navicat ncx 导入还原 SSH 隧道配置`() {
        val ncx = javaClass.getResourceAsStream("/ncx/navicat.ncx")!!
        val result = service.importNcx(ncx)
        assertEquals(1, result.total)
        assertEquals(listOf("内蒙大气生产库"), result.imported)
        assertTrue(result.failed.isEmpty(), result.failed.toString())
        // SSH_PrivateKey 为空,无私钥提示;也不再有旧的「已忽略」警告
        assertTrue(result.warnings.none { it.contains("已忽略") }, result.warnings.toString())

        val c = dsRepo.findAll().single()
        assertEquals("jdbc:postgresql://10.8.22.20:8070/nmstzc_atmosphere_main", c.jdbcUrl)
        assertEquals("nmstzc_atmosphere_main", c.username)
        // 密码经 NavicatCrypto 解密后由实例密钥重新加密入库,解密回来应为可打印串
        val plain = crypto.decrypt(c.password)!!
        assertTrue(plain.isNotEmpty() && plain.all { it.code in 0x20..0x7E })
        // SSH 配置落库:host/port/用户名/认证方式透传,密码解密后重新加密
        assertEquals(true, c.sshEnabled)
        assertEquals("172.30.3.135", c.sshHost)
        assertEquals(22, c.sshPort)
        assertEquals("admin", c.sshUsername)
        assertEquals("password", c.sshAuthMethod)
        val sshPlain = crypto.decrypt(c.sshPassword)!!
        assertTrue(sshPlain.isNotEmpty() && sshPlain.all { it.code in 0x20..0x7E }, sshPlain)
    }

    @Test
    fun `ncx 未保存密码计入失败`() {
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Connections Ver="1.5">
              <Connection ConnectionName="无密码库" ConnType="MYSQL" Host="h" Port="3306"
                          Database="d" UserName="u" Password="" SavePassword="false" SSH="false"/>
              <Connection ConnectionName="未知类型" ConnType="SQLITE" Host="h" Port="1"
                          Database="d" UserName="u" Password="ABC" SavePassword="true" SSH="false"/>
            </Connections>
        """.trimIndent()
        val result = service.importNcx(ByteArrayInputStream(ncx.toByteArray()))
        assertEquals(2, result.total)
        assertTrue(result.imported.isEmpty())
        assertEquals(2, result.failed.size)
        assertEquals("未保存密码", result.failed.first { it.name == "无密码库" }.reason)
        assertTrue(result.failed.first { it.name == "未知类型" }.reason.contains("不支持的连接类型"))
    }

    @Test
    fun `导出导入往返保留 SSH 隧道配置与秘密字段`() {
        val id = dataSourceService.create(
            DataSourceRequest("跳板机库", "jdbc:mysql://db.internal:3306/app", "root", "secret-1", null, null,
                sshEnabled = true, sshHost = "jump.internal", sshPort = 2222, sshUsername = "ops",
                sshAuthMethod = "publickey", sshPassword = "ssh-secret",
                sshPrivateKey = "-----BEGIN KEY-----\nfake\n-----END KEY-----", sshPassphrase = "pp-secret"))

        val out = ByteArrayOutputStream()
        service.export(listOf(id), out)
        val json = out.toString(Charsets.UTF_8)
        // 导出文件含 SSH 非秘密字段,但不出现任何秘密明文
        assertTrue(json.contains("jump.internal"), json)
        assertTrue(!json.contains("ssh-secret"), json)
        assertTrue(!json.contains("BEGIN KEY"), json)
        assertTrue(!json.contains("pp-secret"), json)

        dsRepo.findAll().forEach { dsRepo.delete(it.id!!) }
        val result = service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, result.imported.size)
        assertTrue(result.failed.isEmpty(), result.failed.toString())

        val c = dsRepo.findAll().single()
        assertEquals(true, c.sshEnabled)
        assertEquals("jump.internal", c.sshHost)
        assertEquals(2222, c.sshPort)
        assertEquals("ops", c.sshUsername)
        assertEquals("publickey", c.sshAuthMethod)
        assertEquals("ssh-secret", crypto.decrypt(c.sshPassword))
        assertEquals("-----BEGIN KEY-----\nfake\n-----END KEY-----", crypto.decrypt(c.sshPrivateKey))
        assertEquals("pp-secret", crypto.decrypt(c.sshPassphrase))
    }

    @Test
    fun `ncx 私钥认证提示手动粘贴私钥`() {
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Connections Ver="1.5">
              <Connection ConnectionName="私钥库" ConnType="MYSQL" Host="db" Port="3306"
                          Database="d" UserName="u"
                          Password="${com.example.dq.util.NavicatCrypto.encrypt("p1")}" SavePassword="true"
                          SSH="true" SSH_Host="jump" SSH_Port="22" SSH_UserName="ops"
                          SSH_AuthenMethod="PUBLICKEY" SSH_Password=""
                          SSH_PrivateKey="C:\Users\x\.ssh\id_rsa"
                          SSH_Passphrase="${com.example.dq.util.NavicatCrypto.encrypt("pp1")}"/>
            </Connections>
        """.trimIndent()
        val result = service.importNcx(ByteArrayInputStream(ncx.toByteArray()))
        assertEquals(listOf("私钥库"), result.imported)
        assertTrue(result.failed.isEmpty(), result.failed.toString())
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("手动粘贴私钥"), result.warnings.toString())

        val c = dsRepo.findAll().single()
        assertEquals(true, c.sshEnabled)
        assertEquals("jump", c.sshHost)
        assertEquals("publickey", c.sshAuthMethod)
        // 私钥文件内容读不到,不入库;口令解密落库
        assertEquals(null, c.sshPrivateKey)
        assertEquals("pp1", crypto.decrypt(c.sshPassphrase))
    }

    @Test
    fun `ncx SSH 密码密文损坏计入失败`() {
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Connections Ver="1.5">
              <Connection ConnectionName="坏SSH密码" ConnType="MYSQL" Host="db" Port="3306"
                          Database="d" UserName="u"
                          Password="${com.example.dq.util.NavicatCrypto.encrypt("p1")}" SavePassword="true"
                          SSH="true" SSH_Host="jump" SSH_Port="22" SSH_UserName="ops"
                          SSH_AuthenMethod="PASSWORD" SSH_Password="!!!not-hex!!!"/>
            </Connections>
        """.trimIndent()
        val result = service.importNcx(ByteArrayInputStream(ncx.toByteArray()))
        assertTrue(result.imported.isEmpty())
        assertEquals(1, result.failed.size)
        assertEquals("坏SSH密码", result.failed[0].name)
    }

    @Test
    fun `DataGrip 剪贴板原文导入解析名称地址用户名`() {
        // 与 DataGrip「复制数据源到剪贴板」的原文一致:# 注释行包裹 + XML 块
        val clip = """
            #DataSourceSettings#
            #LocalDataSource: phoenix@172.30.1.145
            #BEGIN#
            <data-source source="LOCAL" name="phoenix@172.30.1.145" group="测试环境"
                uuid="d64e05f7-2fb8-4f73-808b-0d9e7f1b1ebf"><database-info product="PostgreSQL" version="9.5.16"
                dbms="POSTGRES" /><driver-ref>postgresql</driver-ref>
                <jdbc-driver>org.postgresql.Driver</jdbc-driver>
                <jdbc-url>jdbc:postgresql://localhost:1/report_agent_trace</jdbc-url>
                <secret-storage>master_key</secret-storage><user-name>postgres</user-name>
            </data-source>
            #END#
        """.trimIndent()
        val result = service.importDataGrip(clip)
        assertEquals(1, result.total)
        assertEquals(listOf("phoenix@172.30.1.145"), result.imported)
        assertTrue(result.failed.isEmpty(), result.failed.toString())
        // 该格式不含密码,必须提示用户补充
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("密码"), result.warnings.toString())

        val c = dsRepo.findAll().single()
        assertEquals("jdbc:postgresql://localhost:1/report_agent_trace", c.jdbcUrl)
        assertEquals("postgres", c.username)
        assertEquals(null, crypto.decrypt(c.password))
    }

    @Test
    fun `DataGrip 多块导入坏条目不中断`() {
        val clip = """
            #DataSourceSettings#
            #BEGIN#
            <data-source source="LOCAL" name="mysql库" uuid="u1">
                <jdbc-url>jdbc:mysql://localhost:1/db1</jdbc-url>
                <user-name>root</user-name>
            </data-source>
            #END#
            #BEGIN#
            <data-source source="LOCAL" name="缺地址" uuid="u2">
                <user-name>root</user-name>
            </data-source>
            #END#
        """.trimIndent()
        val result = service.importDataGrip(clip)
        assertEquals(2, result.total)
        assertEquals(listOf("mysql库"), result.imported)
        assertEquals(1, result.failed.size)
        assertEquals("缺地址", result.failed[0].name)
        assertEquals("缺少 jdbc-url", result.failed[0].reason)
    }

    @Test
    fun `DataGrip 无效内容与空定义抛参数错误`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.importDataGrip("这不是 XML,也不是合法剪贴板内容 <data-source")
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.importDataGrip("#DataSourceSettings#\n#BEGIN#\n#END#\n")
        }
    }

    @Test
    fun `导出导入往返保留库过滤白名单`() {
        val id = dataSourceService.create(
            DataSourceRequest("过滤库", "jdbc:mysql://localhost:1/db", "root", "secret-1", null, null,
                schemaFilter = listOf("report_agent", "xxl_job")))

        val out = ByteArrayOutputStream()
        service.export(listOf(id), out)
        val json = out.toString(Charsets.UTF_8)
        assertTrue(json.contains("report_agent"), json)

        dsRepo.findAll().forEach { dsRepo.delete(it.id!!) }
        val result = service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, result.imported.size)
        assertTrue(result.failed.isEmpty(), result.failed.toString())
        assertEquals(listOf("report_agent", "xxl_job"), dsRepo.findAll().single().schemaFilter)
    }

    @Test
    fun `导出不存在的数据源抛参数错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            service.export(listOf(9999L), ByteArrayOutputStream())
        }
        assertTrue(e.message!!.contains("数据源不存在"))
    }
}
