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

import java.nio.file.Files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

/** 数据源 SSH 隧道配置持久化:列读写、加密入库、编辑「留空不改」、list 不回传秘密字段 */
class DataSourceSshPersistenceTest {

    private lateinit var dsRepo: DataSourceRepository
    private lateinit var crypto: CryptoUtil
    private lateinit var service: DataSourceService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ds-ssh-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        dsRepo = DataSourceRepository(jdbc)
        val config = AppConfig(dataDir = Files.createTempDirectory("ds-ssh-test"))
        crypto = CryptoUtil(config)
        service = DataSourceService(dsRepo, crypto, DialectFactory, config, SchemaStatRepository(jdbc))
    }

    private fun sshReq(name: String) = DataSourceRequest(
        name, "jdbc:mysql://db.internal:3306/app", "root", "p1", null, null,
        sshEnabled = true, sshHost = "jump.internal", sshPort = 2222, sshUsername = "ops",
        sshAuthMethod = "password", sshPassword = "ssh-p1", sshPrivateKey = null, sshPassphrase = null)

    @Test
    fun `创建与读取 SSH 列`() {
        val id = service.create(sshReq("跳板机库"))

        val c = service.get(id)
        assertEquals(true, c.sshEnabled)
        assertEquals("jump.internal", c.sshHost)
        assertEquals(2222, c.sshPort)
        assertEquals("ops", c.sshUsername)
        assertEquals("password", c.sshAuthMethod)
        assertEquals("ssh-p1", c.sshPassword)

        // 库里存的是密文,不是明文
        val raw = dsRepo.findById(id)!!
        assertEquals("ssh-p1", crypto.decrypt(raw.sshPassword))
        assertNull(crypto.decrypt(raw.sshPrivateKey))
    }

    @Test
    fun `编辑时 SSH 秘密字段留空沿用旧值`() {
        val id = service.create(sshReq("跳板机库"))

        // 编辑:改 SSH 主机与端口,秘密字段全部留空
        service.update(id, DataSourceRequest(
            "跳板机库", "jdbc:mysql://db.internal:3306/app", "root", null, null, null,
            sshEnabled = true, sshHost = "jump2.internal", sshPort = 22, sshUsername = "ops2",
            sshAuthMethod = "password", sshPassword = null, sshPrivateKey = null, sshPassphrase = null))

        val c = service.get(id)
        assertEquals("jump2.internal", c.sshHost)
        assertEquals(22, c.sshPort)
        assertEquals("ops2", c.sshUsername)
        assertEquals("ssh-p1", c.sshPassword) // 留空未改
        assertEquals("p1", c.password) // 主密码同样沿用

        // 再编辑:显式更新 SSH 密码
        service.update(id, DataSourceRequest(
            "跳板机库", "jdbc:mysql://db.internal:3306/app", "root", null, null, null,
            sshEnabled = true, sshHost = "jump2.internal", sshPort = 22, sshUsername = "ops2",
            sshAuthMethod = "password", sshPassword = "ssh-p2", sshPrivateKey = null, sshPassphrase = null))
        assertEquals("ssh-p2", service.get(id).sshPassword)
    }

    @Test
    fun `list 不回传任何秘密字段`() {
        service.create(sshReq("跳板机库"))
        val all = service.list()
        assertEquals(1, all.size)
        val c = all[0]
        assertEquals(true, c.sshEnabled)
        assertEquals("jump.internal", c.sshHost)
        assertNull(c.password)
        assertNull(c.sshPassword)
        assertNull(c.sshPrivateKey)
        assertNull(c.sshPassphrase)
    }
}
