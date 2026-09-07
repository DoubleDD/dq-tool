package com.example.dq.discovery

import com.example.dq.config.LanConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 局域网发现:两个实例走回环单播互指(各自监听独立端口、心跳目标指向对方端口),
 * 避免同机同端口双绑定的投递不确定性;覆盖互发现、忽略自身、改名即时生效、非本协议报文忽略、
 * 单个广播目标失败不中断其余目标、stop 语义。
 */
class LanDiscoveryServiceTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    /** 取一个空闲 UDP 端口(随即释放,测试窗口内被抢占概率可忽略) */
    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    /** 轮询等待条件成立(心跳间隔 1s,给足 8s 余量),超时断言失败 */
    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(condition()) { "等待条件超时" }
    }

    @Test
    fun `两个实例互发现,按 instanceId 忽略自身报文`() {
        val portA = freeUdpPort()
        val portB = freeUdpPort()
        val a = LanDiscoveryService(LanConfig(true, portA, 1), listOf(InetSocketAddress(loopback, portB)))
        val b = LanDiscoveryService(LanConfig(true, portB, 1), listOf(InetSocketAddress(loopback, portA)))
        try {
            a.start(11001, "id-a", { "实例甲" }, "1.9.9")
            b.start(11002, "id-b", { "实例乙" }, "1.9.8")

            awaitTrue { a.peers().any { it.instanceId == "id-b" } }
            awaitTrue { b.peers().any { it.instanceId == "id-a" } }

            val pb = a.peers().first { it.instanceId == "id-b" }
            assertEquals("实例乙", pb.instanceName)
            assertEquals(11002, pb.httpPort)
            assertEquals("1.9.8", pb.appVersion)
            assertEquals("127.0.0.1", pb.host)
            // 自身报文(同端口回环也会收到自己发的)不入在线表
            assertTrue(a.peers().none { it.instanceId == "id-a" })
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun `实例名提供者在每次心跳时实时取,改名下个心跳即对 peer 生效`() {
        val portA = freeUdpPort()
        val portB = freeUdpPort()
        var nameA = "旧名字"
        val a = LanDiscoveryService(LanConfig(true, portA, 1), listOf(InetSocketAddress(loopback, portB)))
        val b = LanDiscoveryService(LanConfig(true, portB, 1), listOf(InetSocketAddress(loopback, portA)))
        try {
            a.start(11001, "id-a", { nameA }, "1.9.9")
            b.start(11002, "id-b", { "实例乙" }, "1.9.8")
            awaitTrue { b.peers().any { it.instanceId == "id-a" && it.instanceName == "旧名字" } }

            nameA = "新名字"
            awaitTrue { b.peers().any { it.instanceId == "id-a" && it.instanceName == "新名字" } }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun `单个广播目标发送失败不中断其余目标的心跳`() {
        val portA = freeUdpPort()
        val portB = freeUdpPort()
        // 未解析地址 send 必抛 IllegalArgumentException,模拟失效网卡广播地址(Host is down)场景
        val bad = InetSocketAddress.createUnresolved("broken-target", portB)
        val a = LanDiscoveryService(LanConfig(true, portA, 1),
            listOf(bad, InetSocketAddress(loopback, portB)))
        val b = LanDiscoveryService(LanConfig(true, portB, 1), listOf(InetSocketAddress(loopback, portA)))
        try {
            a.start(11001, "id-a", { "实例甲" }, "1.9.9")
            b.start(11002, "id-b", { "实例乙" }, "1.9.8")
            // 坏目标排在前,后续正常目标仍能收到心跳
            awaitTrue { b.peers().any { it.instanceId == "id-a" } }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun `非本协议报文与协议版本不符的心跳静默忽略`() {
        val portA = freeUdpPort()
        val a = LanDiscoveryService(LanConfig(true, portA, 60), listOf(InetSocketAddress(loopback, freeUdpPort())))
        try {
            a.start(11001, "id-a", { "实例甲" }, "1.9.9")
            DatagramSocket().use { sender ->
                // 垃圾字节
                sender.send(DatagramPacket("hello".toByteArray(), 5, loopback, portA))
                // app 标识不符
                val other = """{"app":"other","v":1,"id":"x","httpPort":1}""".toByteArray()
                sender.send(DatagramPacket(other, other.size, loopback, portA))
                // 协议版本不符
                val v99 = """{"app":"dq-tool-lan","v":99,"id":"x","name":"x","httpPort":11002}""".toByteArray()
                sender.send(DatagramPacket(v99, v99.size, loopback, portA))
            }
            Thread.sleep(500)
            assertTrue(a.peers().isEmpty())
        } finally {
            a.stop()
        }
    }

    @Test
    fun `stop 幂等并清空在线表,stop 后可重新 start`() {
        val portA = freeUdpPort()
        val portB = freeUdpPort()
        val a = LanDiscoveryService(LanConfig(true, portA, 1), listOf(InetSocketAddress(loopback, portB)))
        val b = LanDiscoveryService(LanConfig(true, portB, 1), listOf(InetSocketAddress(loopback, portA)))
        try {
            a.start(11001, "id-a", { "实例甲" }, "1.9.9")
            b.start(11002, "id-b", { "实例乙" }, "1.9.8")
            awaitTrue { a.peers().isNotEmpty() }

            a.stop()
            a.stop() // 幂等
            assertFalse(a.isRunning())
            assertTrue(a.peers().isEmpty())

            a.start(11001, "id-a", { "实例甲" }, "1.9.9")
            assertTrue(a.isRunning())
            awaitTrue { a.peers().any { it.instanceId == "id-b" } }
        } finally {
            a.stop()
            b.stop()
        }
    }
}
