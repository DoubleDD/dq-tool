package com.example.dq.service

import com.example.dq.model.DataSourceConfig
import com.example.dq.util.JdbcUrlRewriter
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 隧道管理:为启用了 SSH 隧道的数据源建立本地端口转发(JSch Session + setPortForwardingL),
 * 应用侧用改写后的 127.0.0.1:本地端口 JDBC URL 直连目标库。
 * 两种用法:
 * - 长驻隧道(ensureTunnel/close):随数据源连接池使用,连接池驱逐(update/delete)时一并关闭
 * - 一次性隧道(openOneShot):测试连接、保存前模式探测等短操作,用完 close 断开会话
 */
class SshTunnelService {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 数据源级长驻隧道:dsId → 会话 + 本地转发端口 */
    private val tunnels = ConcurrentHashMap<Long, ManagedTunnel>()

    private class ManagedTunnel(val session: Session, val localPort: Int)

    /** 一次性隧道句柄:close 断开 SSH 会话(配合 use 使用) */
    class CloseableTunnel internal constructor(
        private val session: Session,
        val localPort: Int,
    ) : Closeable {
        override fun close() {
            try {
                session.disconnect()
            } catch (e: Exception) {
                // 关闭失败不影响主流程,只记日志
                LoggerFactory.getLogger(SshTunnelService::class.java)
                    .debug("SSH 会话关闭异常: {}", e.message)
            }
        }
    }

    /** 取数据源长驻隧道的本地转发端口;已有连接中的隧道直接复用,断开/不存在则关旧建新 */
    fun ensureTunnel(dsId: Long, c: DataSourceConfig): Int {
        val existing = tunnels[dsId]
        if (existing != null && existing.session.isConnected) {
            return existing.localPort
        }
        close(dsId)
        val session = connect(
            identityName = "ds-$dsId",
            sshHost = c.sshHost, sshPort = c.sshPort, sshUsername = c.sshUsername,
            sshAuthMethod = c.sshAuthMethod, sshPassword = c.sshPassword,
            sshPrivateKey = c.sshPrivateKey, sshPassphrase = c.sshPassphrase,
        )
        // 转发建立失败时先断开会话再抛错,避免泄漏
        val localPort = try {
            forward(session, c.jdbcUrl)
        } catch (e: Exception) {
            try {
                session.disconnect()
            } catch (_: Exception) {
            }
            throw e
        }
        tunnels[dsId] = ManagedTunnel(session, localPort)
        log.info("数据源 {} SSH 隧道已建立:127.0.0.1:{}(经 {}@{})", dsId, localPort, c.sshUsername, c.sshHost)
        return localPort
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
        val session = connect(
            identityName = "oneshot", sshHost = sshHost, sshPort = sshPort, sshUsername = sshUsername,
            sshAuthMethod = sshAuthMethod, sshPassword = sshPassword,
            sshPrivateKey = sshPrivateKey, sshPassphrase = sshPassphrase,
        )
        val localPort = try {
            forward(session, jdbcUrl)
        } catch (e: Exception) {
            try {
                session.disconnect()
            } catch (_: Exception) {
            }
            throw e
        }
        return CloseableTunnel(session, localPort)
    }

    /** 关闭并移除数据源的长驻隧道(连接池驱逐/删除数据源时调用);不存在时无操作 */
    fun close(dsId: Long) {
        val t = tunnels.remove(dsId) ?: return
        try {
            t.session.disconnect()
        } catch (e: Exception) {
            log.debug("数据源 {} SSH 会话关闭异常: {}", dsId, e.message)
        }
    }

    /** 建立 SSH 会话:password 认证走 setPassword,publickey 认证走 addIdentity(私钥内容存库,非文件路径) */
    private fun connect(
        identityName: String,
        sshHost: String?,
        sshPort: Int?,
        sshUsername: String?,
        sshAuthMethod: String?,
        sshPassword: String?,
        sshPrivateKey: String?,
        sshPassphrase: String?,
    ): Session {
        try {
            val jsch = JSch()
            val session = jsch.getSession(sshUsername, sshHost, sshPort ?: 22)
            if (sshAuthMethod == "publickey") {
                val keyBytes = (sshPrivateKey ?: "").toByteArray(StandardCharsets.UTF_8)
                val passBytes = sshPassphrase?.takeIf { it.isNotEmpty() }
                    ?.toByteArray(StandardCharsets.UTF_8)
                jsch.addIdentity(identityName, keyBytes, null, passBytes)
            } else {
                session.setPassword(sshPassword)
            }
            // 内网跳板机场景,跳过 known_hosts 校验
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(10_000)
            return session
        } catch (e: Exception) {
            log.warn("SSH 隧道连接失败 {}@{}:{}: {}", sshUsername, sshHost, sshPort ?: 22, e.message)
            throw IllegalStateException("SSH 隧道连接失败: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** 建本地端口转发:远端目标取 JDBC URL 解析出的真实 host:port,本地端口 0 由系统自动分配 */
    private fun forward(session: Session, jdbcUrl: String?): Int {
        val (remoteHost, remotePort) = JdbcUrlRewriter.extractHostPort(jdbcUrl ?: "")
        return session.setPortForwardingL("127.0.0.1", 0, remoteHost, remotePort)
    }
}
