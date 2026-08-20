package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * 系统托盘图标:桌面安装版以后台守护进程方式常驻,
 * 右键菜单提供「打开窗口」(重新拉起 --app 窗口)和「退出」(关窗口 + 结束后端进程)。
 * 托盘生效后心跳看门狗(DesktopSession)停用;托盘不可用的环境(headless、部分 Linux 桌面)自动跳过。
 * 图标为运行时绘制,不依赖外部图片资源。
 * 右键菜单全平台统一用 Swing JPopupMenu(托盘图标本身是 AWT SystemTray API,没有 Swing 替代品):
 * Windows 的 AWT 原生菜单 peer 渲染中文必现方块(显式设字体也无效),Swing 由 Java2D 绘制,
 * 字体回退正常,并设置系统外观让菜单字体/样式跟随操作系统默认。
 * 安装版在 main 阶段(服务就绪前)即调用 installEarly 安装,让双击启动的用户第一时间看到托盘图标;
 * 服务就绪后 onReady 回填菜单动作依赖的引用并标记看门狗停用。
 */
public class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    /** 已安装的托盘图标;null 表示未安装(early 阶段与 ready 兜底共用,保证幂等) */
    private static volatile TrayIcon installedIcon;
    /** 后台安装进行中标志:onReady 见其置位则跳过同步安装,不等待后台线程的锁(启动优化) */
    private static volatile boolean installInProgress;
    /** 服务就绪后回填,托盘菜单动作使用;就绪前点击菜单做降级处理 */
    private static volatile BrowserOpener browserOpenerRef;
    private static volatile AppShutdown shutdownRef;

    private final BrowserOpener browserOpener;
    private final DesktopSession session;
    private final AppShutdown shutdown;

    public TrayManager(BrowserOpener browserOpener, DesktopSession session, AppShutdown shutdown) {
        this.browserOpener = browserOpener;
        this.session = session;
        this.shutdown = shutdown;
    }

    /**
     * 安装托盘图标(幂等),main 阶段与服务就绪兜底都走这里。
     * 返回是否已生效(含此前已安装的情况);headless / 不支持托盘 / 添加失败返回 false。
     */
    public static synchronized boolean installEarly(String url) {
        if (installedIcon != null) {
            return true;
        }
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return false;
        }

        TrayIcon icon = new TrayIcon(createImage(), "dq-tool 数据质量检测工具", null);
        icon.setImageAutoSize(true);
        // 全平台统一用 Swing 菜单:Windows 的 AWT 菜单 peer 渲染中文必现方块(设字体无效),
        // Swing 由 Java2D 绘制,字体回退正常;系统外观让菜单字体/样式跟随操作系统默认
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("设置系统外观失败,托盘菜单使用默认外观: {}", e.getMessage());
        }
        icon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowSwingMenu(e, url);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowSwingMenu(e, url);
            }
        });
        try {
            SystemTray.getSystemTray().add(icon);
            installedIcon = icon;
            return true;
        } catch (AWTException e) {
            log.warn("系统托盘图标添加失败,仍可由心跳看门狗兜底进程退出: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 后台线程安装托盘图标(启动优化):托盘不是启动关键路径,不再阻塞主流程。
     * AWT 已由 DqApplication 在开窗后于主线程完成初始化,后台安装安全;
     * 与 onReady 的兜底安装靠 synchronized + 幂等检查互斥,重复调用自动跳过。
     */
    public static void installEarlyAsync(String url) {
        installInProgress = true;
        Thread t = new Thread(() -> {
            try {
                boolean ok = installEarly(url);
                StartupLog.log("托盘图标安装" + (ok ? "成功" : "失败/不可用"));
            } catch (Throwable e) {
                StartupLog.log("托盘图标后台安装异常(不影响启动)", e);
            } finally {
                installInProgress = false;
            }
        }, "tray-install");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 服务就绪(原 ApplicationReadyEvent 挂载点):回填菜单动作引用;托盘兜底安装。
     * 启动优化:后台安装(installEarlyAsync)进行中或已完成时不重复安装、不等待其锁——
     * 托盘安装在本机可能耗时秒级(首建托盘图标),主流程不为其停顿,托盘稍后自行就绪;
     * 未走后台安装的场景(如 IDE 直接运行)在这里同步兜底安装。
     */
    public void onReady(int port) {
        browserOpenerRef = browserOpener;
        shutdownRef = shutdown;
        if (installedIcon != null || installInProgress) {
            if (installedIcon != null) {
                session.markTrayActive();
                log.info("系统托盘图标已就绪(右键菜单:打开窗口 / 退出)");
            }
            return;
        }
        // 未启动过后台安装(IDE 直接运行等):同步兜底安装,行为与原实现一致
        if (installEarly("http://localhost:" + port)) {
            session.markTrayActive();
            log.info("系统托盘图标已就绪(右键菜单:打开窗口 / 退出)");
        }
    }

    /** 托盘「打开窗口」;服务尚未就绪时忽略本次点击(splash 正在提示启动中) */
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
            AppShutdown appShutdown = shutdownRef;
            // 服务尚未就绪时没有装配退出封装,直接退出
            if (appShutdown != null) {
                appShutdown.exit();
            } else {
                System.exit(0);
            }
        }, "tray-shutdown").start();
    }

    /** 托盘右键(press/release 都可能带 popup 标记,两个事件都检查) */
    private static void maybeShowSwingMenu(MouseEvent e, String url) {
        if (e.isPopupTrigger()) {
            showSwingMenu(url);
        }
    }

    /**
     * 在鼠标位置弹出 Swing 托盘菜单。
     * 位置不取 TrayIcon 鼠标事件坐标 —— 该坐标在 Windows 高分屏缩放下是错的(JDK 已知问题),
     * 直接读指针当前位置:右键那一刻指针就停在托盘图标上。
     * Swing 菜单需要一个可见的 invoker 才能正常工作(点其他位置失焦自动关闭),
     * 用 0 大小的隐藏 JDialog 做锚点;菜单关闭后销毁锚点,避免句柄泄漏。
     */
    private static void showSwingMenu(String url) {
        Point pointer = MouseInfo.getPointerInfo().getLocation();
        JDialog anchor = new JDialog((Frame) null);
        anchor.setUndecorated(true);
        anchor.setSize(0, 0);
        anchor.setLocation(pointer);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("打开窗口");
        openItem.addActionListener(e -> new Thread(() -> openWindow(url), "tray-open-window").start());
        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.addActionListener(e -> shutdown());
        menu.add(openItem);
        menu.add(exitItem);
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                anchor.dispose();
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                anchor.dispose();
            }
        });

        anchor.setVisible(true);
        menu.show(anchor, 0, 0);
    }

    /**
     * 选一个能显示中文的字体:Windows 上 Dialog 逻辑字体在部分区域设置/精简 JRE 下
     * 不含中文字形(界面显示方块),按候选顺序用 canDisplay 探测;都找不到时返回 null 保持默认。
     * 启动画面(DesktopSplash)使用。
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
