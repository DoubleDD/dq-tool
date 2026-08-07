package com.example.dq.shell

import com.example.dq.config.ConfigLoader
import com.example.dq.web.WebServer
import java.awt.GraphicsEnvironment
import java.net.ServerSocket
import kotlin.system.exitProcess

/**
 * shell 模块入口:同一 JVM 进程内启动 server 的 Web 服务,再用 JCEF 窗口加载页面。
 *
 * 与 DqApplication 的关键差异 —— 浏览器/托盘抑制方案:
 * 不走 DqApplication.main,直接装配 WebServer,且绝不调用 WebServer.onReady()。
 * onReady 在非 headless 环境下会打开外部浏览器(BrowserOpener)并安装 server 托盘
 * (TrayManager,其「打开窗口」同样指向外部浏览器);shell 的窗口是内嵌 JCEF,
 * 这两个桌面动作必须抑制。server 未提供抑制开关,但两者都只由 onReady/installEarly 触发,
 * 不调用即彻底抑制,无需改动 server。心跳看门狗(DesktopSession)只在 BrowserOpener
 * 成功拉起 --app 窗口后才武装,shell 路径下永不武装,天然失效。
 */

private const val MAX_PORT_OFFSET = 100

fun main(args: Array<String>) {
    // JCEF 需要图形显示;headless 环境(如 SSH)直接给出明确报错,而不是在 native 初始化里崩溃
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("[dq-tool-shell] 当前环境无图形显示(headless),JCEF 窗口无法启动")
        exitProcess(1)
    }

    val config = ConfigLoader.load()
    // logback 首次打日志即初始化,dq.data-dir 必须先于任何日志输出(与 DqApplication 一致)
    System.setProperty("dq.data-dir", config.dataDir())

    // 端口解析与避让逻辑复刻 DqApplication(其方法为 private 无法复用,改动请两边同步):
    // --server.port= 启动参数 > SERVER_PORT 环境变量 > application.yml;0 表示随机分配
    val configuredPort = resolveConfiguredPort(args, config.serverPort())
    val port = if (configuredPort == 0) 0 else findAvailablePort(configuredPort)
    if (configuredPort != 0 && port != configuredPort) {
        println("[dq-tool-shell] 端口 $configuredPort 被占用,避让到 $port")
    }

    // WebServer 构造时完成共享内核装配(H2 连接池 + Flyway 迁移 + 业务服务)
    val server = WebServer(config)
    server.start(port)
    val actualPort = server.port()
    println("[dq-tool-shell] 后端已就绪: http://localhost:$actualPort")

    // Ctrl+C / kill 兜底:关 Javalin + H2 连接池(窗口关闭路径见 ShellWindow.shutdown)
    Runtime.getRuntime().addShutdownHook(Thread({ runCatching { server.stop() } }, "shell-stop"))

    ShellWindow.open(server, "http://localhost:$actualPort")
}

private fun resolveConfiguredPort(args: Array<String>, defaultPort: Int): Int {
    for (arg in args) {
        if (arg.startsWith("--server.port=")) {
            return arg.removePrefix("--server.port=").toIntOrNull() ?: defaultPort
        }
    }
    val env = System.getenv("SERVER_PORT")
    if (!env.isNullOrBlank()) {
        return env.trim().toIntOrNull() ?: defaultPort
    }
    return defaultPort
}

/** 从期望端口开始向后探测,返回第一个可用端口(与 DqApplication.findAvailablePort 口径一致) */
private fun findAvailablePort(preferred: Int): Int {
    for (port in preferred..preferred + MAX_PORT_OFFSET) {
        try {
            ServerSocket(port).use { return port }
        } catch (_: Exception) {
            // 端口被占用,继续探测下一个
        }
    }
    throw IllegalStateException(
        "端口 $preferred ~ ${preferred + MAX_PORT_OFFSET} 均被占用,请通过 --server.port= 指定其他端口"
    )
}
