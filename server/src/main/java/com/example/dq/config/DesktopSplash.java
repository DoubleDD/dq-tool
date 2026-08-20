package com.example.dq.config;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.SplashScreen;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * 桌面安装版启动画面:在 main 阶段(服务就绪前)立即弹出,让双击启动的用户第一时间得到反馈;
 * 服务完全就绪后由 DqApplication 关闭(此时 Chrome 已显示带启动进度的占位页,不留死区)。
 *
 * 两种形态(二选一,由打包方式决定):
 *  - 原生启动画面(优先):安装包通过 -splash:${APPDIR}/splash.png 启动,JVM 一启动(甚至在 main()
 *    之前、类加载期间)就显示品牌图,零 AWT 开销,把 JVM 启动 + 首次 AWT 初始化的"黑屏期"全部盖住。
 *    检测到原生 splash 后不再弹 Swing 窗口(首个 AWT 窗口显示会自动关掉原生 splash,造成空窗)。
 *    首次 AWT 初始化不做在这里——挪到开窗之后([ensureAwtInitialized]),让 Chrome 占位页更早出现,
 *    期间原生 splash 一直罩着。
 *  - Swing 启动画面(回退):未配置 -splash 的场景(开发模式手工运行),用
 *    JWindow + 不确定进度条,行为与改造前一致(创建即完成主线程 AWT 初始化)。
 * 两种形态都只在显式 -Djava.awt.headless=false(打包脚本注入)的安装版出现,
 * 普通 java -jar 服务器部署不受影响(调用方先判断 headless 再调 showEarly)。
 */
public final class DesktopSplash {

    private static volatile JWindow window;
    /** 原生 -splash 启动画面实例;非 null 表示当前由原生 splash 承担启动画面 */
    private static volatile SplashScreen nativeSplash;

    private DesktopSplash() {
    }

    /** 显示启动画面(幂等);AWT 失败只影响反馈,不阻塞主流程 */
    public static void showEarly() {
        // 原生 -splash 优先:JVM 启动即显示品牌图。查询 SplashScreen 本身很快(毫秒级),
        // 不触发重型 AWT 初始化(那部分推迟到 ensureAwtInitialized,开窗之后做);
        // 未配 -splash 或图片加载失败时(getSplashScreen 返回 null)回退 Swing 启动画面
        SplashScreen splash = safeSplashScreen();
        if (splash != null) {
            nativeSplash = splash;
            return;
        }
        try {
            SwingUtilities.invokeAndWait(DesktopSplash::createAndShow);
        } catch (Exception e) {
            // 启动画面只是反馈,失败不影响启动,但安装版无控制台,必须落启动日志
            StartupLog.log("启动画面显示失败(不影响启动)", e);
        }
    }

    /**
     * 原生 splash 生效时,由 DqApplication 在开窗后调用:在主线程完成首次 AWT 初始化
     * (原生 splash 一直罩着)。做掉之后,后续 AWT 调用(托盘图标创建等)不会落到非主线程做
     * 首次初始化(macOS 要求 AWT 在主线程初始化),托盘继续后台安装、不阻塞主流程。
     * Swing 形态已在 showEarly 完成初始化,此调用为幂等空操作。
     */
    public static void ensureAwtInitialized() {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment();
        } catch (Throwable e) {
            // AWT 初始化失败只影响桌面反馈,不阻塞启动
            StartupLog.log("AWT 环境初始化失败(不影响启动)", e);
        }
        if (nativeSplash == null) {
            // 记录原生 splash 实例供 close 使用(查询本身毫秒级,不会重复触发重型初始化)
            nativeSplash = safeSplashScreen();
        }
    }

    /** 关闭启动画面(幂等,未显示时为空操作);原生与 Swing 两种形态各自关闭 */
    public static void close() {
        SplashScreen nativeS = nativeSplash;
        if (nativeS != null) {
            nativeSplash = null;
            StartupLog.log("关闭启动画面");
            try {
                nativeS.close();
            } catch (Exception e) {
                // 已关闭/不可关闭只影响反馈
                StartupLog.log("关闭原生启动画面失败(不影响启动)", e);
            }
            return;
        }
        JWindow w = window;
        if (w == null) {
            return;
        }
        window = null;
        StartupLog.log("关闭启动画面");
        SwingUtilities.invokeLater(() -> {
            w.setVisible(false);
            w.dispose();
        });
    }

    /** 读取 JVM 原生启动画面实例;headless/未配置 -splash 时返回 null(不抛异常) */
    private static SplashScreen safeSplashScreen() {
        try {
            return SplashScreen.getSplashScreen();
        } catch (Throwable e) {
            // headless 等环境下 getSplashScreen 可能抛 HeadlessException
            return null;
        }
    }

    private static void createAndShow() {
        if (window != null) {
            return;
        }
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD0, 0xD0, 0xD0)),
                BorderFactory.createEmptyBorder(24, 40, 24, 40)));

        JLabel iconLabel = new JLabel(new ImageIcon(TrayManager.createImage()));
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        Font base = TrayManager.pickChineseFont(12);
        JLabel title = new JLabel("dq-tool 数据质量检测工具");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        if (base != null) {
            title.setFont(base.deriveFont(Font.BOLD, 16f));
        }
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel status = new JLabel("正在启动服务,请稍候…");
        if (base != null) {
            status.setFont(base);
        }
        status.setForeground(new Color(0x66, 0x66, 0x66));
        status.setAlignmentX(Component.CENTER_ALIGNMENT);

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setMaximumSize(new Dimension(220, 8));
        bar.setAlignmentX(Component.CENTER_ALIGNMENT);

        content.add(iconLabel);
        content.add(Box.createVerticalStrut(12));
        content.add(title);
        content.add(Box.createVerticalStrut(6));
        content.add(status);
        content.add(Box.createVerticalStrut(12));
        content.add(bar);

        JWindow w = new JWindow();
        w.setContentPane(content);
        w.pack();
        w.setLocationRelativeTo(null);
        w.setAlwaysOnTop(true);
        window = w;
        w.setVisible(true);
    }
}
