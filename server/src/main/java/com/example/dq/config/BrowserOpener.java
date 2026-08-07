package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 桌面安装包(jpackage)双击启动后自动打开首页:
 * 优先探测 Chrome / Edge 并以 --app= 应用模式拉起(独立窗口,无地址栏/标签页),
 * 探测不到再回落到系统默认浏览器。
 * headless 的服务器部署(java -jar、容器)自动跳过。
 * 打开窗口的逻辑同时供托盘菜单(TrayManager)「打开窗口」复用。
 * 应用模式使用独立的 --user-data-dir(~/.dq-tool/browser-profile):若与用户日常浏览器共用配置,
 * 浏览器已在运行时新进程会把窗口交接给已有实例后立即退出,进程句柄失效,托盘「退出」就杀不到窗口;
 * 独立配置保证窗口属于本进程拉起的浏览器实例,句柄一直有效,closeWindow() 能可靠关闭。
 */
public class BrowserOpener {

    private static final Logger log = LoggerFactory.getLogger(BrowserOpener.class);

    private final DesktopSession session;
    /** 最近一次拉起的 --app 浏览器实例主进程(独立 user-data-dir,句柄一直有效) */
    private volatile Process lastAppProcess;

    public BrowserOpener(DesktopSession session) {
        this.session = session;
    }

    /** 服务已完全就绪(原 ApplicationReadyEvent 挂载点):关闭启动画面(未显示时为空操作),紧接着打开应用窗口 */
    public void openBrowser(int port) {
        DesktopSplash.close();
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        open("http://localhost:" + port);
    }

    /** 打开应用窗口:优先 --app 应用模式,失败回落到系统默认浏览器 */
    public void open(String url) {
        if (openInAppMode(url)) {
            return;
        }
        openInDefaultBrowser(url);
    }

    /** 关闭由本进程拉起的 --app 窗口(整个独立浏览器实例);先优雅终止,超时后强杀 */
    public void closeWindow() {
        Process p = lastAppProcess;
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    /**
     * 用 Chromium 系浏览器的应用模式打开,返回是否成功。
     * 重复打开时先关掉上一个实例:同一 user-data-dir 下浏览器已在运行时,
     * 新进程同样会交接后立即退出,先杀旧实例才能保证 lastAppProcess 始终是活的主进程。
     */
    private boolean openInAppMode(String url) {
        String browser = findChromiumBrowser();
        if (browser == null) {
            return false;
        }
        closeWindow();
        List<String> command = new ArrayList<>();
        command.add(browser);
        command.add("--user-data-dir=" + browserProfileDir());
        // 独立配置下跳过首次运行向导/默认浏览器检查/崩溃恢复提示,窗口体验与共用配置一致
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--hide-crash-restore-bubble");
        command.add("--app=" + url);
        try {
            lastAppProcess = new ProcessBuilder(command).start();
            session.markAppModeOpened();
            log.info("已用应用模式打开 {} ({})", url, browser);
            return true;
        } catch (Exception e) {
            log.warn("应用模式启动浏览器失败,回退到默认浏览器: {}", e.getMessage());
            return false;
        }
    }

    /** 应用模式专用的浏览器配置目录,与用户日常浏览器配置隔离 */
    private Path browserProfileDir() {
        return Path.of(System.getProperty("user.home"), ".dq-tool", "browser-profile");
    }

    private void openInDefaultBrowser(String url) {
        try {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
                return;
            }
            desktop.browse(new URI(url));
            log.info("已在默认浏览器打开 {}", url);
        } catch (Exception e) {
            log.warn("自动打开浏览器失败,请手动访问 {}: {}", url, e.getMessage());
        }
    }

    /**
     * 按平台常见安装位置探测 Chromium 系浏览器可执行文件,找不到返回 null。
     * Windows 优先 Edge(系统自带),macOS/Linux 优先 Chrome。
     */
    private String findChromiumBrowser() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String pf = System.getenv("ProgramFiles");
            String pf86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LOCALAPPDATA");
            addCandidate(candidates, pf86, "Microsoft\\Edge\\Application\\msedge.exe");
            addCandidate(candidates, pf, "Microsoft\\Edge\\Application\\msedge.exe");
            addCandidate(candidates, pf, "Google\\Chrome\\Application\\chrome.exe");
            addCandidate(candidates, pf86, "Google\\Chrome\\Application\\chrome.exe");
            addCandidate(candidates, local, "Google\\Chrome\\Application\\chrome.exe");
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
        } else {
            // Linux: 在 PATH 里找
            return findOnPath("google-chrome", "microsoft-edge", "chromium", "chromium-browser");
        }
        return candidates.stream().filter(p -> Files.isExecutable(Path.of(p))).findFirst().orElse(null);
    }

    private void addCandidate(List<String> candidates, String base, String relative) {
        if (base != null && !base.isBlank()) {
            candidates.add(base + "\\" + relative);
        }
    }

    private String findOnPath(String... names) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            for (String name : names) {
                Path p = Path.of(dir, name);
                if (Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }
}
