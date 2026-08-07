package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.TestConnectionRequest
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Base64

import org.junit.jupiter.api.Assertions.assertTrue

/**
 * SSH 隧道端到端:Testcontainers 同一 Network 上起 MySQL 8 + sshd 跳板机(linuxserver/openssh-server)。
 * 数据源 jdbcUrl 用容器网络别名 `jdbc:mysql://mysql:3306/dqtest` —— 该主机名只有同网络容器能解析,
 * 宿主机解析不了,因此查询成功即证明流量确实走了 SSH 本地端口转发(本测试的核心断言逻辑)。
 * 覆盖:密码认证 / 私钥认证 / testConnection 一次性隧道(含错误密码失败路径)。
 */
@Testcontainers
class SshTunnelIntegrationTest {

    /** 具名自类型容器:Kotlin 下 <Nothing> 自类型泛型会让继承来的 withNetwork 等方法无法解析,固定 SELF 即可 */
    class FixedMySQLContainer(image: String) : MySQLContainer<FixedMySQLContainer>(image)
    class SshdContainer(image: String) : GenericContainer<SshdContainer>(image)

    companion object {
        /** 测试内生成的 RSA 密钥对:公钥注入 sshd authorized_keys,私钥内容走数据源 sshPrivateKey */
        private val SSH_PRIVATE_KEY: String
        private val SSH_PUBLIC_KEY: String

        init {
            // java.security 生成 RSA 密钥对:私钥转 PKCS#8 PEM(即 SSHJ 可读的 "BEGIN PRIVATE KEY" 格式);
            // 公钥按 SSH wire 格式(string "ssh-rsa" + mpint e + mpint n)拼 authorized_keys 行
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val privB64 = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.UTF_8))
                .encodeToString(kp.private.encoded)
            SSH_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\n$privB64\n-----END PRIVATE KEY-----\n"
            val rsaPub = kp.public as RSAPublicKey
            val buf = ByteArrayOutputStream()
            DataOutputStream(buf).use { out ->
                fun writeField(b: ByteArray) {
                    out.writeInt(b.size)
                    out.write(b)
                }
                writeField("ssh-rsa".toByteArray(StandardCharsets.UTF_8))
                // BigInteger.toByteArray 为补码表示,正数最高位为 1 时自动补 0 字节,正好符合 SSH mpint 编码
                writeField(rsaPub.publicExponent.toByteArray())
                writeField(rsaPub.modulus.toByteArray())
            }
            SSH_PUBLIC_KEY = "ssh-rsa " + Base64.getEncoder().encodeToString(buf.toByteArray()) + " dq-ssh-test"
        }

        private val NETWORK: Network = Network.newNetwork()

        /** 目标库:加网络别名 mysql,只允许经跳板机访问(宿主机无法解析该主机名) */
        @Container
        @JvmField
        val MYSQL: FixedMySQLContainer = FixedMySQLContainer("mysql:8.0")
            .withDatabaseName("dqtest")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql")

        /** 跳板机:密码 + 公钥两种认证同时开启;镜像基于 s6,启动较慢,超时调大。
         *  坑 1:该镜像 sshd 监听 2222 而非 22;
         *  坑 2:镜像默认禁 TCP 转发(AllowTcpForwarding no),且生效配置是 init 生成的 /config/sshd/sshd_config
         *      (不是 /etc/ssh/sshd_config),custom-cont-init.d 脚本改配置后需 HUP 已在运行的 sshd 才生效 */
        @Container
        @JvmField
        val SSHD: SshdContainer = SshdContainer("linuxserver/openssh-server:latest")
            .withNetwork(NETWORK)
            .withExposedPorts(2222)
            .withEnv("USER_NAME", "tester")
            .withEnv("USER_PASSWORD", "testpass")
            .withEnv("PASSWORD_ACCESS", "true")
            .withEnv("SUDO_ACCESS", "false")
            .withEnv("PUBLIC_KEY", SSH_PUBLIC_KEY)
            .withCopyToContainer(
                Transferable.of(
                    "#!/bin/sh\n" +
                        "sed -i 's/^AllowTcpForwarding no/AllowTcpForwarding yes/' /config/sshd/sshd_config\n" +
                        "pgrep -f sshd.pam | head -1 | xargs -r kill -HUP\n",
                    0b111101101), // 0755
                "/custom-cont-init.d/zz-allow-forwarding.sh")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)))

        /** 走隧道的 JDBC URL:主机名为容器网络别名,只有跳板机能解析 */
        private const val TUNNEL_JDBC_URL = "jdbc:mysql://mysql:3306/dqtest"
    }

    private lateinit var dataSourceService: DataSourceService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ssh-it-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        val config = AppConfig(dataDir = Files.createTempDirectory("ssh-it"))
        dataSourceService = DataSourceService(DataSourceRepository(jdbc), CryptoUtil(config),
            DialectFactory, config, SchemaStatRepository(jdbc))
    }

    @Test
    fun `密码认证隧道`() {
        val dsId = dataSourceService.create(DataSourceRequest(
            "ssh-password", TUNNEL_JDBC_URL, MYSQL.username, MYSQL.password, null, null,
            sshEnabled = true, sshHost = SSHD.host, sshPort = SSHD.getMappedPort(2222),
            sshUsername = "tester", sshAuthMethod = "password", sshPassword = "testpass"))
        try {
            dataSourceService.getConnection(dsId).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM information_schema.tables").use { rs ->
                        assertTrue(rs.next())
                        assertTrue(rs.getLong(1) > 0, "经 SSH 隧道查询应有结果")
                    }
                }
            }
        } finally {
            // 验证清理(连接池驱逐 + 隧道会话关闭)不抛异常
            dataSourceService.delete(dsId)
        }
    }

    @Test
    fun `私钥认证隧道`() {
        val dsId = dataSourceService.create(DataSourceRequest(
            "ssh-publickey", TUNNEL_JDBC_URL, MYSQL.username, MYSQL.password, null, null,
            sshEnabled = true, sshHost = SSHD.host, sshPort = SSHD.getMappedPort(2222),
            sshUsername = "tester", sshAuthMethod = "publickey", sshPrivateKey = SSH_PRIVATE_KEY))
        try {
            dataSourceService.getConnection(dsId).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT 1").use { rs ->
                        assertTrue(rs.next())
                        assertTrue(rs.getInt(1) == 1)
                    }
                }
            }
        } finally {
            dataSourceService.delete(dsId)
        }
    }

    @Test
    fun `testConnection一次性隧道`() {
        // 不落库,直接请求参数走一次性隧道;返回 dbMode 可为 null,不抛异常即成功
        dataSourceService.testConnection(TestConnectionRequest(
            jdbcUrl = TUNNEL_JDBC_URL, username = MYSQL.username, password = MYSQL.password,
            sshEnabled = true, sshHost = SSHD.host, sshPort = SSHD.getMappedPort(2222),
            sshUsername = "tester", sshAuthMethod = "password", sshPassword = "testpass"))

        // 错误的 SSH 密码必须抛异常,验证失败路径不是静默通过
        val e = assertThrows<IllegalStateException> {
            dataSourceService.testConnection(TestConnectionRequest(
                jdbcUrl = TUNNEL_JDBC_URL, username = MYSQL.username, password = MYSQL.password,
                sshEnabled = true, sshHost = SSHD.host, sshPort = SSHD.getMappedPort(2222),
                sshUsername = "tester", sshAuthMethod = "password", sshPassword = "wrong-pass"))
        }
        assertTrue(e.message!!.contains("SSH"), "错误信息应指明 SSH 隧道连接失败: ${e.message}")
    }
}
