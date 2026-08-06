package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * 系统托盘图标:桌面安装版以后台守护进程方式常驻,
 * 右键菜单提供「打开窗口」(重新拉起 --app 窗口)和「退出」(关窗口 + 结束后端进程)。
 * 托盘生效后心跳看门狗(DesktopSession)停用;托盘不可用的环境(headless、部分 Linux 桌面)自动跳过。
 * 图标为运行时绘制,不依赖外部图片资源。
 * 安装版在 main 阶段(Spring 就绪前)即调用 installEarly 安装,让双击启动的用户第一时间看到托盘图标;
 * Spring 就绪后 onReady 回填菜单动作依赖的引用并标记看门狗停用。
 */
@Component
public class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    /** 已安装的托盘图标;null 表示未安装(early 阶段与 ready 兜底共用,保证幂等) */
    private static volatile TrayIcon installedIcon;
    /** Spring 就绪后回填,托盘菜单动作使用;就绪前点击菜单做降级处理 */
    private static volatile BrowserOpener browserOpenerRef;
    private static volatile ApplicationContext ctxRef;

    private final BrowserOpener browserOpener;
    private final DesktopSession session;
    private final ApplicationContext ctx;

    public TrayManager(BrowserOpener browserOpener, DesktopSession session, ApplicationContext ctx) {
        this.browserOpener = browserOpener;
        this.session = session;
        this.ctx = ctx;
    }

    /**
     * 安装托盘图标(幂等),main 阶段与 Spring 就绪兜底都走这里。
     * 返回是否已生效(含此前已安装的情况);headless / 不支持托盘 / 添加失败返回 false。
     */
    public static synchronized boolean installEarly(String url) {
        if (installedIcon != null) {
            return true;
        }
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return false;
        }

        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem("打开窗口");
        // open 会先等旧实例退出(最多 3 秒),单独线程执行,避免阻塞 AWT 事件线程
        openItem.addActionListener(e -> new Thread(() -> openWindow(url), "tray-open-window").start());
        MenuItem exitItem = new MenuItem("退出");
        exitItem.addActionListener(e -> shutdown());
        // Windows 上 MenuItem 默认的 Dialog 逻辑字体可能映射到不含中文字形的物理字体(显示方块),
        // 显式指定支持中文的字体;macOS/Linux 探测不到中文字体时回退默认字体,行为不变
        Font menuFont = pickChineseFont(12);
        if (menuFont != null) {
            openItem.setFont(menuFont);
            exitItem.setFont(menuFont);
        }
        menu.add(openItem);
        menu.add(exitItem);

        TrayIcon icon = new TrayIcon(createImage(), "dq-tool 数据质量检测工具", menu);
        icon.setImageAutoSize(true);
        try {
            SystemTray.getSystemTray().add(icon);
            installedIcon = icon;
            return true;
        } catch (AWTException e) {
            log.warn("系统托盘图标添加失败,仍可由心跳看门狗兜底进程退出: {}", e.getMessage());
            return false;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        browserOpenerRef = browserOpener;
        ctxRef = ctx;
        String port = event.getApplicationContext().getEnvironment().getProperty("server.port", "10000");
        // 幂等:安装版在 main 阶段已装过;未走 early 路径的场景(如 IDE 直接运行)在这里兜底
        if (installEarly("http://localhost:" + port)) {
            session.markTrayActive();
            log.info("系统托盘图标已就绪(右键菜单:打开窗口 / 退出)");
        }
    }

    /** 托盘「打开窗口」;Spring 尚未就绪时服务还没起来,忽略本次点击(splash 正在提示启动中) */
    private static void openWindow(String url) {
        BrowserOpener opener = browserOpenerRef;
        if (opener == null) {
            log.debug("服务启动中,忽略托盘「打开窗口」点击");
            return;
        }
        opener.open(url);
    }

    /** 托盘「退出」:尽力关闭 --app 窗口,再优雅结束后端进程;单独线程执行,避免阻塞 AWT 事件线程 */
    private static void shutdown() {
        new Thread(() -> {
            BrowserOpener opener = browserOpenerRef;
            if (opener != null) {
                opener.closeWindow();
            }
            ApplicationContext context = ctxRef;
            // Spring 尚未就绪时没有上下文可优雅关闭,直接退出
            System.exit(context != null ? SpringApplication.exit(context, () -> 0) : 0);
        }, "tray-shutdown").start();
    }

    /**
     * 选一个能显示中文的字体:Windows 上 Dialog 逻辑字体在部分区域设置/精简 JRE 下
     * 不含中文字形(界面显示方块),按候选顺序用 canDisplay 探测;都找不到时返回 null 保持默认。
     * 托盘菜单与启动画面(DesktopSplash)共用。
     */
    static Font pickChineseFont(int size) {
        String[] candidates = {"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "PingFang SC", "Noto Sans CJK SC"};
        for (String name : candidates) {
            Font font = new Font(name, Font.PLAIN, size);
            if (font.canDisplay('打')) {
                return font;
            }
        }
        return null;
    }

    /** 运行时绘制 DQ 图标:圆角蓝底白字,64px,托盘按平台自动缩放;启动画面(DesktopSplash)共用 */
    static Image createImage() {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x40, 0x9E, 0xFF));
        g.fillRoundRect(0, 0, size, size, 16, 16);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        String text = "DQ";
        int w = g.getFontMetrics().stringWidth(text);
        int h = g.getFontMetrics().getAscent();
        g.drawString(text, (size - w) / 2, (size + h) / 2 - 4);
        g.dispose();
        return image;
    }
}
