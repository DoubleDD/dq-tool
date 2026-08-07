package com.example.dq.shell

import java.awt.Color
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * 最小系统托盘:打开窗口 / 退出。
 * server 的 TrayManager 菜单动作耦合 BrowserOpener(「打开窗口」拉起外部浏览器 --app 窗口),
 * 与 shell 的 JCEF 窗口模型不符,无法复用,此处自写最小实现(实现思路借鉴 TrayManager:
 * Swing JPopupMenu 规避 Windows AWT 原生菜单渲染中文方块、隐藏 JDialog 做菜单锚点、运行时绘制图标)。
 * 托盘不可用的环境(headless、部分 Linux 桌面)静默跳过,窗口关闭仍可直接退出进程。
 */
object ShellTray {

    fun install(onOpen: () -> Unit, onExit: () -> Unit) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
            // 设置系统外观失败,托盘菜单使用默认外观
        }
        val icon = TrayIcon(createImage(), "dq-tool 数据质量检测工具", null)
        icon.isImageAutoSize = true
        val showMenu = {
            // 菜单位置直接读指针当前位置(TrayIcon 事件坐标在 Windows 高分屏缩放下是错的,JDK 已知问题)
            val pointer = MouseInfo.getPointerInfo().location
            val anchor = JDialog(null as Frame?)
            anchor.isUndecorated = true
            anchor.setSize(0, 0)
            anchor.location = pointer

            val menu = JPopupMenu()
            val openItem = JMenuItem("打开窗口")
            openItem.addActionListener { onOpen() }
            val exitItem = JMenuItem("退出")
            exitItem.addActionListener { onExit() }
            menu.add(openItem)
            menu.add(exitItem)
            menu.addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                }

                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                    anchor.dispose()
                }

                override fun popupMenuCanceled(e: PopupMenuEvent) {
                    anchor.dispose()
                }
            })
            anchor.isVisible = true
            menu.show(anchor, 0, 0)
        }
        icon.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showMenu()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showMenu()
            }
        })
        try {
            SystemTray.getSystemTray().add(icon)
        } catch (_: Exception) {
            // 托盘添加失败不影响主流程,窗口关闭即可退出进程
        }
    }

    /** 运行时绘制 DQ 图标:圆角蓝底白字,64px,托盘按平台自动缩放(与 server 的 TrayManager 同款) */
    private fun createImage(): Image {
        val size = 64
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setColor(Color(0x40, 0x9E, 0xFF))
        g.fillRoundRect(0, 0, size, size, 16, 16)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
        val text = "DQ"
        val w = g.fontMetrics.stringWidth(text)
        val h = g.fontMetrics.ascent
        g.drawString(text, (size - w) / 2, (size + h) / 2 - 4)
        g.dispose()
        return image
    }
}
