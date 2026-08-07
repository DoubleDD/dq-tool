package com.example.dq.service

import com.example.dq.model.DataSourceConfig
import com.example.dq.util.JdbcUrlRewriter
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 隧道管理:为启用了 SSH 隧道的数据源建立本地端口转发(SSHJ SSHClient + LocalPortForwarder),
 * 应用侧用改写后的 127.0.0.1:本地端口 JDBC URL 直连目标库。
 * 两种用法:
 * - 长驻隧道(ensureTunnel/close):随数据源连接池使用,连接池驱逐(update/delete)时一并关闭
 * - 一次性隧道(openOneShot):测试连接、保存前模式探测等短操作,用完 close 断开会话
 */
class SshTunnelService {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 数据源级长驻隧道:dsId → 会话 + 本地转发 */
    private val tunnels = ConcurrentHashMap<Long, ManagedTunnel>()

    private class ManagedTunnel(
        val ssh: SSHClient,
        val forwarding: Forwarding,
    )

    /** 本地转发三要素:转发器 + 自带 ServerSocket + accept 循环线程;localPort 即实际分配的本地端口 */
    internal class Forwarding(
        val forwarder: LocalPortForwarder,
        val serverSocket: ServerSocket,
        val listener: Thread,
        val localPort: Int,
    )

    /** 一次性隧道句柄:close 断开转发与 SSH 会话(配合 use 使用) */
    class CloseableTunnel internal constructor(
        private val ssh: SSHClient,
        private val forwarding: Forwarding,
    ) : Closeable {
        val localPort: Int get() = forwarding.localPort
        override fun close() {
            shutdown(ssh, forwarding, "一次性隧道")
        }
    }

    companion object {
        private val staticLog = LoggerFactory.getLogger(SshTunnelService::class.java)

        /** 关闭隧道:转发器(停监听线程并关 ServerSocket)→ 监听线程(登记竞态兜底)→ ServerSocket(幂等兜底)→ 断开 SSH 会话 */
        private fun shutdown(ssh: SSHClient, f: Forwarding, label: String) {
            try {
                f.forwarder.close()
            } catch (e: Exception) {
                staticLog.debug("{} 转发器关闭异常: {}", label, e.message)
            }
            // forwarder.close() 依赖 listen() 登记 runningThread,竞态下可能未生效,这里兜底中断 accept 循环
            f.listener.interrupt()
            try {
                f.serverSocket.close()
            } catch (e: Exception) {
                staticLog.debug("{} 本地监听关闭异常: {}", label, e.message)
            }
            try {
                ssh.disconnect()
            } catch (e: Exception) {
                staticLog.debug("{} SSH 会话关闭异常: {}", label, e.message)
            }
        }
    }

    /** 取数据源长驻隧道的本地转发端口;已有连接中的隧道直接复用,断开/不存在则关旧建新 */
    fun ensureTunnel(dsId: Long, c: DataSourceConfig): Int {
        val existing = tunnels[dsId]
        if (existing != null && existing.ssh.isConnected) {
            return existing.forwarding.localPort
        }
        close(dsId)
        val ssh = connect(
            sshHost = c.sshHost, sshPort = c.sshPort, sshUsername = c.sshUsername,
            sshAuthMethod = c.sshAuthMethod, sshPassword = c.sshPassword,
            sshPrivateKey = c.sshPrivateKey, sshPassphrase = c.sshPassphrase,
        )
        // 转发建立失败时先断开会话再抛错,避免泄漏
        val forwarding = try {
            forward(ssh, c.jdbcUrl)
        } catch (e: Exception) {
            try {
                ssh.disconnect()
            } catch (_: Exception) {
            }
            throw e
        }
        tunnels[dsId] = ManagedTunnel(ssh, forwarding)
        log.info("数据源 {} SSH 隧道已建立:127.0.0.1:{}(经 {}@{})", dsId, forwarding.localPort, c.sshUsername, c.sshHost)
        return forwarding.localPort
    }

    /** 建一次性隧道(测试连接/模式探测用);转发失败时先断开会话再抛错,避免泄漏 */
    fun openOneShot(
        sshHost: String?,
        sshPort: Int?,
        sshUsername: String?,
        sshAuthMethod: String?,
        sshPassword: String?,
        sshPrivateKey: String?,
        sshPassphrase: String?,
        jdbcUrl: String,
    ): CloseableTunnel {
        val ssh = connect(
            sshHost = sshHost, sshPort = sshPort, sshUsername = sshUsername,
            sshAuthMethod = sshAuthMethod, sshPassword = sshPassword,
            sshPrivateKey = sshPrivateKey, sshPassphrase = sshPassphrase,
        )
        val forwarding = try {
            forward(ssh, jdbcUrl)
        } catch (e: Exception) {
            try {
                ssh.disconnect()
            } catch (_: Exception) {
            }
            throw e
        }
        return CloseableTunnel(ssh, forwarding)
    }

    /** 关闭并移除数据源的长驻隧道(连接池驱逐/删除数据源时调用);不存在时无操作 */
    fun close(dsId: Long) {
        val t = tunnels.remove(dsId) ?: return
        shutdown(t.ssh, t.forwarding, "数据源 $dsId 隧道")
    }

    /** 建立 SSH 连接并认证:password 走 authPassword,publickey 从私钥内容字符串加载(存库,非文件路径) */
    private fun connect(
        sshHost: String?,
        sshPort: Int?,
        sshUsername: String?,
        sshAuthMethod: String?,
        sshPassword: String?,
        sshPrivateKey: String?,
        sshPassphrase: String?,
    ): SSHClient {
        try {
            val ssh = SSHClient()
            ssh.connectTimeout = 10_000
            // 内网跳板机场景,跳过 known_hosts 校验(等价 StrictHostKeyChecking=no)
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect(sshHost, sshPort ?: 22)
            try {
                if (sshAuthMethod == "publickey") {
                    ssh.authPublickey(sshUsername, loadKeyProvider(ssh, sshPrivateKey, sshPassphrase))
                } else {
                    ssh.authPassword(sshUsername, sshPassword)
                }
            } catch (e: Exception) {
                // 认证失败先断开再抛错,避免半开连接泄漏
                try {
                    ssh.disconnect()
                } catch (_: Exception) {
                }
                throw e
            }
            return ssh
        } catch (e: Exception) {
            log.warn("SSH 隧道连接失败 {}@{}:{}: {}", sshUsername, sshHost, sshPort ?: 22, e.message)
            throw IllegalStateException("SSH 隧道连接失败: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * 从私钥内容字符串加载 KeyProvider。SSHJ 的 loadKeys(String) 重载只认文件路径,
     * 这里用 KeyProviderUtil 检测格式后从 Reader 纯内存加载(PKCS#8 PEM / OpenSSH 新格式均可),
     * 不落临时文件;passphrase 经 PasswordFinder 传给 provider 解密私钥。
     */
    private fun loadKeyProvider(ssh: SSHClient, sshPrivateKey: String?, sshPassphrase: String?): FileKeyProvider {
        val content = sshPrivateKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("publickey 认证缺少私钥内容")
        val format = KeyProviderUtil.detectKeyFileFormat(StringReader(content), false)
        val provider: FileKeyProvider = Factory.Named.Util.create(
            ssh.transport.config.fileKeyProviderFactories, format.toString())
            ?: throw IllegalArgumentException("不支持的私钥格式: $format")
        val pwdf = sshPassphrase?.takeIf { it.isNotEmpty() }
            ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        provider.init(StringReader(content), pwdf)
        return provider
    }

    /** 建本地端口转发:远端目标取 JDBC URL 解析出的真实 host:port,本地端口 0 由系统自动分配 */
    private fun forward(ssh: SSHClient, jdbcUrl: String?): Forwarding {
        val (remoteHost, remotePort) = JdbcUrlRewriter.extractHostPort(jdbcUrl ?: "")
        // SSHJ 需自备 ServerSocket 接受本地连接;bind 127.0.0.1:0 自动分配端口
        val serverSocket = ServerSocket()
        try {
            serverSocket.bind(InetSocketAddress("127.0.0.1", 0))
            val localPort = serverSocket.localPort
            val forwarder = ssh.newLocalPortForwarder(
                Parameters("127.0.0.1", localPort, remoteHost, remotePort), serverSocket)
            // 0.40 的 newLocalPortForwarder 只创建不监听;listen() 在调用线程跑 accept 循环,
            // 必须放独立守护线程,否则会阻塞调用方。线程内 listen() 会把自己登记为 runningThread,
            // forwarder.close() 据此中断线程并关 ServerSocket
            val listener = Thread {
                try {
                    forwarder.listen()
                } catch (e: Exception) {
                    log.debug("本地转发监听线程退出 127.0.0.1:{}: {}", localPort, e.message)
                }
            }.apply {
                isDaemon = true
                name = "ssh-forward-$localPort"
                start()
            }
            return Forwarding(forwarder, serverSocket, listener, localPort)
        } catch (e: Exception) {
            try {
                serverSocket.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }
}
