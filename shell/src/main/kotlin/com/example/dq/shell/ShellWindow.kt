package com.example.dq.shell

import com.example.dq.web.WebServer
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefApp.CefAppState
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * JCEF 窗口:内嵌 Chromium 加载本进程 Web 服务的页面。
 * 生命周期约定:关闭窗口 = 退出整个进程(停 CEF → 停后端 → exit)。
 * CEF 初始化经 jcefmaven 的 CefAppBuilder:natives 从 classpath(fat jar 内嵌的 tar.gz)
 * 解压到 installDir,自动补 java.library.path 与 macOS 的 framework/子进程路径。
 *
 * 线程模型刻意与 jcefmaven 官方示例 MainFrame 一致:CefApp 初始化、createBrowser、
 * JFrame 组装全部在 main 线程完成,不包 SwingUtilities.invokeLater ——
 * macOS 上 createBrowser 的 native 调用要与 AppKit 主线程同步,从 EDT 发起会静默死锁(窗口不出现)。
 */
object ShellWindow {

    @Volatile
    private var cefApp: CefApp? = null

    @Volatile
    private var frame: JFrame? = null

    @Volatile
    private var server: WebServer? = null

    private val shuttingDown = AtomicBoolean(false)

    /** 初始化 CEF(阻塞,首次需解压约 100MB natives)并打开主窗口;由 main 线程调用,不返回前窗口已可见 */
    fun open(server: WebServer, url: String) {
        this.server = server
        val builder = CefAppBuilder()
        // natives 解压目录放用户目录:jpackage 安装目录只读,且解压结果可跨启动复用(install.lock 标记)
        val installDir = File(System.getProperty("user.home"), ".dq-tool/jcef-bundle")
        builder.setInstallDir(installDir)
        // 窗口化渲染(非离屏 OSR):macOS arm64 上 OSR 会因 CEF/JOGL 争抢主线程崩溃
        // (java-cef issue #514),窗口化是唯一可靠模式;Windows/Linux 桌面窗口化同样是常规选择
        builder.cefSettings.windowless_rendering_enabled = false
        // CEF 缓存目录显式指定:默认值的进程单例锁会导致多实例/异常退出后无法启动(CEF 官方警告)
        builder.cefSettings.root_cache_path = File(installDir, "cache").absolutePath
        // 启动前清掉 HTTP 缓存:server 不发 Cache-Control,前端每次发版资产哈希变化,
        // 磁盘缓存里的旧 index.html 会引用已不存在的 js(SPA 兜底返回 text/html 被模块加载器拒绝 → 白屏)。
        // 只删缓存目录,保留 Default/ 下的 localStorage 等用户数据(主题/页签状态)
        val rootCache = File(builder.cefSettings.root_cache_path)
        listOf("Cache", "Code Cache", "GPUCache").forEach { name ->
            File(rootCache, "Default/$name").deleteRecursively()
        }
        // 远程调试端口 + Chromium 日志落 stderr:页面白屏/JS 报错时的排障入口
        // (http://localhost:9222 打开 DevTools;生产包可保留,仅监听 localhost)
        builder.cefSettings.remote_debugging_port = 9222
        builder.addJcefArgs("--enable-logging=stderr", "--v=0")
        // macOS 上不能用 CefApp.addAppHandler,必须经 builder 注入(jcefmaven README 明确要求)
        builder.setAppHandler(object : MavenCefAppHandlerAdapter() {
            override fun stateHasChanged(state: CefAppState?) {
                if (state == CefAppState.TERMINATED) {
                    shutdown()
                }
            }
        })
        val app = builder.build()
        cefApp = app

        // 以下 UI 组装在 main 线程执行(与官方示例一致,见类注释)
        val browser = app.createClient().createBrowser(url, false, false)
        val f = JFrame("dq-tool 数据质量检测工具")
        // 关闭动作统一走 shutdown():dispose CEF + 停后端 + 退进程
        f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        f.setSize(1440, 900)
        f.setLocationRelativeTo(null)
        f.contentPane.add(browser.uiComponent, BorderLayout.CENTER)
        f.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                shutdown()
            }
        })
        f.isVisible = true
        frame = f
        ShellTray.install(onOpen = ::focusWindow, onExit = ::shutdown)
    }

    /** 托盘「打开窗口」:窗口最小化/被遮挡时拉回前台 */
    private fun focusWindow() {
        SwingUtilities.invokeLater {
            frame?.let {
                it.isVisible = true
                it.extendedState = JFrame.NORMAL
                it.toFront()
                it.requestFocus()
            }
        }
    }

    /** 统一退出出口(关窗/托盘退出/CEF 终止):幂等,换线程执行避免阻塞 AWT 事件线程 */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return
        }
        thread(name = "shell-shutdown") {
            runCatching { cefApp?.dispose() }
            runCatching { server?.stop() }
            exitProcess(0)
        }
    }
}
