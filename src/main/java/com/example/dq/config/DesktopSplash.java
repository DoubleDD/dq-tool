package com.example.dq.config;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

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
 * 桌面安装版启动画面:在 main 阶段(Spring 就绪前)立即弹出,让双击启动的用户第一时间得到反馈;
 * 服务完全就绪后由 BrowserOpener 关闭,紧接着打开 --app 应用窗口。
 * 与托盘图标同属早期反馈,只在显式 -Djava.awt.headless=false(打包脚本注入)的安装版出现,
 * 普通 java -jar 服务器部署不受影响。
 */
public final class DesktopSplash {

    private static volatile JWindow window;

    private DesktopSplash() {
    }

    /** 显示启动画面(幂等);AWT 失败只影响反馈,不阻塞主流程 */
    public static void showEarly() {
        try {
            SwingUtilities.invokeAndWait(DesktopSplash::createAndShow);
        } catch (Exception ignored) {
            // 启动画面只是反馈,失败不影响启动
        }
    }

    /** 关闭启动画面(幂等,未显示时为空操作) */
    public static void close() {
        JWindow w = window;
        if (w == null) {
            return;
        }
        window = null;
        SwingUtilities.invokeLater(() -> {
            w.setVisible(false);
            w.dispose();
        });
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
