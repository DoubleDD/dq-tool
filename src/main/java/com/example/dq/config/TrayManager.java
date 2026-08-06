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
 */
@Component
public class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    private final BrowserOpener browserOpener;
    private final DesktopSession session;
    private final ApplicationContext ctx;

    public TrayManager(BrowserOpener browserOpener, DesktopSession session, ApplicationContext ctx) {
        this.browserOpener = browserOpener;
        this.session = session;
        this.ctx = ctx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void install(ApplicationReadyEvent event) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return;
        }
        String port = event.getApplicationContext().getEnvironment().getProperty("server.port", "10000");
        String url = "http://localhost:" + port;

        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem("打开窗口");
        // open 会先等旧实例退出(最多 3 秒),单独线程执行,避免阻塞 AWT 事件线程
        openItem.addActionListener(e -> new Thread(() -> browserOpener.open(url), "tray-open-window").start());
        MenuItem exitItem = new MenuItem("退出");
        exitItem.addActionListener(e -> shutdown());
        menu.add(openItem);
        menu.add(exitItem);

        TrayIcon icon = new TrayIcon(createImage(), "dq-tool 数据质量检测工具", menu);
        icon.setImageAutoSize(true);
        try {
            SystemTray.getSystemTray().add(icon);
            session.markTrayActive();
            log.info("系统托盘图标已就绪(右键菜单:打开窗口 / 退出)");
        } catch (AWTException e) {
            log.warn("系统托盘图标添加失败,仍可由心跳看门狗兜底进程退出: {}", e.getMessage());
        }
    }

    /** 托盘「退出」:尽力关闭 --app 窗口,再优雅结束后端进程;单独线程执行,避免阻塞 AWT 事件线程 */
    private void shutdown() {
        new Thread(() -> {
            browserOpener.closeWindow();
            System.exit(SpringApplication.exit(ctx, () -> 0));
        }, "tray-shutdown").start();
    }

    /** 运行时绘制托盘图标:圆角蓝底白字 DQ,64px,托盘按平台自动缩放 */
    private Image createImage() {
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
