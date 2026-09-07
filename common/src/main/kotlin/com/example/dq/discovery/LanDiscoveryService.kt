package com.example.dq.discovery

import com.example.dq.config.LanConfig
import com.example.dq.model.LanPeer
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardSocketOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 局域网实例自动发现:UDP 广播心跳(announce)+ 监听其他实例心跳,纯 JDK DatagramSocket 实现。
 *
 * - 心跳报文为小 JSON(<512 字节):app 标识 + 协议版本 + 实例 id/名称 + HTTP 端口 + 软件版本;
 * - 监听与广播共用同一端口,SO_REUSEADDR 允许同机多实例(主仓/工作树同时开发)共存;
 *   macOS 上再叠加 SO_REUSEPORT:BSD 只设 REUSEADDR 时广播报文只投递给同端口绑定 socket 中的一个
 *   (可能投给发送方自己),REUSEPORT 才让同机多实例都能收到广播;
 * - 心跳按广播目标逐个发送、互不影响:某个目标失败(如 VPN/网卡断开后残留广播地址 Host is down)
 *   只记带目标地址的 warn 并继续发其余目标;失败目标进 10 分钟冷却(暂停发送、日志不刷屏,到期重试),
 *   且自动探测的目标列表随即重解析以适应网卡上下线;
 * - 按报文中的 instanceId 过滤自身;peer 的 host 取报文来源 IP;
 * - 超过 3 个心跳间隔未再见到的实例在读取时判离线剔除(纯内存,重启后重新发现);
 * - 线程均为守护线程,start/stop 幂等;网络异常只记日志,不影响主服务。
 */
class LanDiscoveryService(
    private val config: LanConfig,
    /** 广播目标(地址+端口);空 = 启动时按网卡自动探测(端口用 config.discoveryPort)。
     *  测试可注入 127.0.0.1:对端端口 走回环单播,避免同端口双绑定的投递不确定性 */
    private val broadcastTargets: List<InetSocketAddress> = emptyList(),
) {

    private val log = LoggerFactory.getLogger(LanDiscoveryService::class.java)
    private val mapper = ObjectMapper()

    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null
    private var scheduler: java.util.concurrent.ScheduledExecutorService? = null
    private var receiver: Thread? = null

    /** 当前广播目标(start 时解析;自动探测模式下发送失败后重解析,适应网卡/VPN 上下线) */
    @Volatile
    private var targets: List<InetSocketAddress> = emptyList()

    /** 发送失败目标的冷却截止时间(冷却期内跳过发送,到期重试一次) */
    private val targetCooldown = ConcurrentHashMap<InetSocketAddress, Long>()

    /** 当前心跳身份(start 时快照;instanceName 每次发心跳时取,改名下个心跳即生效) */
    @Volatile
    private var selfInstanceId: String = ""

    private val peers = ConcurrentHashMap<String, LanPeer>()

    fun isRunning(): Boolean = running

    /**
     * 启动发现(幂等):绑定 UDP 端口、起接收线程与周期心跳。
     * @param httpPort 本机 HTTP 服务实际端口(随心跳广播,供 peer 回连拉数据)
     * @param instanceId 本机实例 id(持久化在 system_settings,由 LanShareService 保证已生成)
     * @param instanceName 实例名提供者(每次心跳实时取,页面改名后无需重启发现)
     */
    @Synchronized
    fun start(httpPort: Int, instanceId: String, instanceName: () -> String, appVersion: String) {
        if (running) {
            return
        }
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            // macOS 下同机多实例共存时需 REUSEPORT 广播才会投递给同端口所有绑定 socket;
            // Linux 广播本就全员投递(且其 REUSEPORT 语义不同),Windows 无此选项,故仅 macOS 设置
            if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
                runCatching { setOption(StandardSocketOptions.SO_REUSEPORT, true) }
            }
            broadcast = true
            bind(InetSocketAddress(config.discoveryPort))
            soTimeout = 1000
        }
        socket = s
        selfInstanceId = instanceId
        running = true

        targets = resolveBroadcastTargets()
        log.info("局域网发现已启动:UDP 端口 {}, 广播目标 {}", config.discoveryPort,
            targets.joinToString { targetText(it) })

        val announce = Runnable {
            try {
                val payload = mapper.writeValueAsBytes(
                    linkedMapOf(
                        "app" to PACKET_APP,
                        "v" to PACKET_VERSION,
                        "id" to instanceId,
                        "name" to instanceName(),
                        "httpPort" to httpPort,
                        "appVersion" to appVersion,
                    )
                )
                // 逐目标独立发送:单个目标失败(网卡/VPN 断开等)不中断其余目标;
                // 失败目标进冷却期暂停发送(如无链路载体的 169.254 自分配网卡必失败),到期重试一次
                val now = System.currentTimeMillis()
                var sendFailed = false
                for (target in targets) {
                    val cooldownUntil = targetCooldown[target]
                    if (cooldownUntil != null) {
                        if (now < cooldownUntil) {
                            continue
                        }
                        targetCooldown.remove(target) // 冷却到期,本轮重试
                    }
                    try {
                        s.send(DatagramPacket(payload, payload.size, target))
                    } catch (e: Exception) {
                        sendFailed = true
                        // 仅首次失败记日志(冷却期内不再刷屏),恢复发送成功的目标自然出冷却
                        val firstFailure = targetCooldown.put(target, now + TARGET_COOLDOWN_MILLIS) == null
                        if (running && firstFailure) {
                            log.warn("局域网心跳广播失败: 目标 {}, 原因: {}(暂停该目标 {} 分钟后重试)",
                                targetText(target), e.message, TARGET_COOLDOWN_MILLIS / 60_000)
                        }
                    }
                }
                // 自动探测的目标在发送失败后重解析:网卡/VPN 上下线后旧广播地址可能已失效
                if (sendFailed && broadcastTargets.isEmpty() && running) {
                    targets = resolveBroadcastTargets()
                }
            } catch (e: Exception) {
                if (running) {
                    log.warn("局域网心跳广播失败: {}", e.message)
                }
            }
        }
        val interval = config.announceIntervalSeconds.coerceIn(1, 300).toLong()
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "lan-announce").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(announce, 0, interval, TimeUnit.SECONDS) }

        receiver = Thread({
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    handlePacket(packet)
                } catch (e: java.net.SocketTimeoutException) {
                    // 周期性唤醒检查 running 标志
                } catch (e: Exception) {
                    if (running) {
                        log.warn("局域网心跳接收异常: {}", e.message)
                    }
                }
            }
        }, "lan-discovery-receiver")
        receiver!!.isDaemon = true
        receiver!!.start()
    }

    /** 停止发现(幂等):停心跳、关 socket、清空在线表 */
    @Synchronized
    fun stop() {
        if (!running) {
            return
        }
        running = false
        scheduler?.shutdownNow()
        scheduler = null
        // 关 socket 使接收线程的 receive 抛出后退出
        runCatching { socket?.close() }
        socket = null
        receiver = null
        peers.clear()
        targets = emptyList()
        targetCooldown.clear()
        selfInstanceId = ""
        log.info("局域网发现已停止")
    }

    /** 当前在线实例快照(剔除超时未心跳的;按实例名排序) */
    fun peers(): List<LanPeer> {
        val deadline = System.currentTimeMillis() - config.announceIntervalSeconds.coerceIn(1, 300) * 3_000L
        peers.entries.removeIf { it.value.lastSeenAt < deadline }
        return peers.values.sortedWith(compareBy({ it.instanceName }, { it.host }, { it.httpPort }))
    }

    /** 处理收到的心跳报文:校验 app/协议版本,忽略自身,按 instanceId upsert 在线表 */
    private fun handlePacket(packet: DatagramPacket) {
        val node = try {
            mapper.readTree(packet.data, packet.offset, packet.length)
        } catch (e: Exception) {
            return // 非本协议报文静默忽略
        }
        if (node.path("app").asText() != PACKET_APP || node.path("v").asInt() != PACKET_VERSION) {
            return
        }
        val id = node.path("id").asText("")
        if (id.isEmpty() || id == selfInstanceId) {
            return
        }
        val httpPort = node.path("httpPort").asInt(0)
        if (httpPort <= 0) {
            return
        }
        peers[id] = LanPeer(
            instanceId = id,
            instanceName = node.path("name").asText("").ifEmpty { "未命名实例" },
            host = packet.address.hostAddress,
            httpPort = httpPort,
            appVersion = node.path("appVersion").asText(""),
            lastSeenAt = System.currentTimeMillis(),
        )
    }

    /** 广播目标:注入的固定地址优先,否则遍历所有 up 且非回环网卡的广播地址,兜底 255.255.255.255 */
    private fun resolveBroadcastTargets(): List<InetSocketAddress> {
        if (broadcastTargets.isNotEmpty()) {
            return broadcastTargets
        }
        val out = LinkedHashSet<InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (ni.isUp && !ni.isLoopback) {
                    ni.interfaceAddresses.forEach { it.broadcast?.let(out::add) }
                }
            }
        } catch (e: Exception) {
            log.warn("枚举网卡广播地址失败,回落 255.255.255.255: {}", e.message)
        }
        if (out.isEmpty()) {
            runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        }
        return out.map { InetSocketAddress(it, config.discoveryPort) }
    }

    companion object {
        /** 心跳报文 app 标识(同网段其他程序的同端口报文据此忽略) */
        const val PACKET_APP = "dq-tool-lan"

        /** 心跳协议版本;报文演进只追加字段,变更语义时升版本(旧版按 v 不符忽略) */
        const val PACKET_VERSION = 1

        /** 发送失败目标的冷却时长(到期重试一次,避免无链路网卡的失败 warn 刷屏) */
        const val TARGET_COOLDOWN_MILLIS = 10 * 60_000L

        /** 广播目标日志渲染(host:port;未解析地址 address 为 null,退用 hostString) */
        private fun targetText(t: InetSocketAddress): String = "${t.address?.hostAddress ?: t.hostString}:${t.port}"
    }
}
