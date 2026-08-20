package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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
 * 打开窗口前按 /static/index.html 内容 hash 校验前端是否变化(vite 产物名带内容 hash,前端一构建就变),
 * 变化则删除整个 browser-profile 目录(含 HTTP 缓存)再拉起,浏览器自动重建空配置——
 * 防止旧缓存 index.html 引用新包里不存在的旧 hash 资源,模块脚本 MIME 报错白屏。
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
            StartupLog.log("headless 环境,不自动打开浏览器,访问 http://localhost:" + port);
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

    /**
     * 第二个实例专用(InstanceLock 检测到同数据目录已有实例在运行时):
     * 把已有实例的页面用 --app 窗口/默认浏览器带出来,随后本进程退出。
     * 静态入口,不触碰会话(DesktopSession)与窗口句柄(lastAppProcess)管理 ——
     * 同一 user-data-dir 下新拉起的浏览器进程会交接给已有实例拉起的浏览器主进程,
     * 窗口仍归已有实例的托盘「退出」统一关闭,管理关系不破坏。
     */
    public static void reopenExisting(String url) {
        String browser = findChromiumBrowser();
        if (browser != null) {
            try {
                new ProcessBuilder(browser, "--user-data-dir=" + browserProfileDir(),
                        "--no-first-run", "--no-default-browser-check", "--hide-crash-restore-bubble",
                        "--app=" + url).start();
                StartupLog.log("已用应用模式打开已有实例页面 " + url + " (" + browser + ")");
                return;
            } catch (Exception e) {
                StartupLog.log("应用模式打开已有实例页面失败,回落默认浏览器", e);
            }
        }
        try {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(new URI(url));
                StartupLog.log("已在默认浏览器打开已有实例页面 " + url);
            } else {
                StartupLog.log("当前环境不支持自动打开浏览器,请手动访问已有实例页面 " + url);
            }
        } catch (Exception e) {
            StartupLog.log("打开已有实例页面失败,请手动访问 " + url, e);
        }
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
            StartupLog.log("未探测到 Chrome/Edge,回落到系统默认浏览器打开 " + url);
            return false;
        }
        closeWindow();
        // 前端 hash 校验:内容变了说明升过级,先清掉浏览器配置目录(含 HTTP 缓存)再开窗——
        // 旧缓存的 index.html 会引用新包里已不存在的旧 hash 资源,模块脚本拿到 SPA 回退的
        // text/html 报 MIME 错误白屏(2026-08 免安装版实测)。删目录后浏览器启动时自动重建
        String frontendHash = frontendHash();
        if (frontendHash != null) {
            try {
                if (resetProfileIfFrontendChanged(browserProfileDir(), profileHashMarker(), frontendHash)) {
                    StartupLog.log("前端资源 hash 已变化,已清理浏览器配置目录(含缓存): " + browserProfileDir());
                    log.info("前端资源 hash 已变化,已清理浏览器配置目录 {}", browserProfileDir());
                }
            } catch (Exception e) {
                // 清理失败(如文件被占用)不阻断打开窗口
                log.warn("清理浏览器配置目录失败,按原样打开窗口: {}", e.getMessage());
            }
        }
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
            StartupLog.log("已用应用模式打开 " + url + " (" + browser + ")");
            log.info("已用应用模式打开 {} ({})", url, browser);
            return true;
        } catch (Exception e) {
            StartupLog.log("应用模式启动浏览器失败,回退到默认浏览器(" + browser + ")", e);
            log.warn("应用模式启动浏览器失败,回退到默认浏览器: {}", e.getMessage());
            return false;
        }
    }

    /** 应用模式专用的浏览器配置目录,与用户日常浏览器配置隔离 */
    private static Path browserProfileDir() {
        return Path.of(System.getProperty("user.home"), ".dq-tool", "browser-profile");
    }

    /**
     * 前端 hash 标记文件:记录上次打开窗口时的前端 hash。
     * 必须放在 profile 目录**外面**——放里面会随目录一起被删,下次启动标记缺失又会误判为"已变化",
     * 把浏览器刚重建的空配置再删一遍,陷入每次启动都清缓存的死循环。
     */
    private static Path profileHashMarker() {
        return browserProfileDir().resolveSibling("browser-profile.frontend-hash");
    }

    /**
     * 当前内嵌前端的标识 hash:取 /static/index.html 内容的 SHA-256。
     * vite 产物文件名带内容 hash,index.html 里引用的资源名随每次前端构建变化,其内容 hash 即前端版本指纹。
     * 无内嵌前端(纯 API 测试环境)返回 null,跳过校验。
     */
    private static String frontendHash() {
        try (InputStream in = BrowserOpener.class.getResourceAsStream("/static/index.html")) {
            if (in == null) {
                return null;
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("计算前端 hash 失败,跳过浏览器配置目录校验: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 前端 hash 与标记文件不一致时删除整个浏览器配置目录并更新标记,返回是否发生了清理。
     * 独立成包私有静态方法以便单测(不依赖 user.home 与 classpath)。
     */
    static boolean resetProfileIfFrontendChanged(Path profileDir, Path markerFile, String frontendHash)
            throws IOException {
        String recorded = Files.exists(markerFile) ? Files.readString(markerFile).trim() : null;
        if (frontendHash.equals(recorded)) {
            return false;
        }
        if (Files.exists(profileDir)) {
            try (var stream = Files.walk(profileDir)) {
                for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(markerFile.getParent());
        Files.writeString(markerFile, frontendHash);
        return true;
    }

    private void openInDefaultBrowser(String url) {
        try {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
                StartupLog.log("当前环境不支持自动打开浏览器,请手动访问 " + url);
                return;
            }
            desktop.browse(new URI(url));
            StartupLog.log("已在默认浏览器打开 " + url);
            log.info("已在默认浏览器打开 {}", url);
        } catch (Exception e) {
            StartupLog.log("自动打开浏览器失败,请手动访问 " + url, e);
            log.warn("自动打开浏览器失败,请手动访问 {}: {}", url, e.getMessage());
        }
    }

    /**
     * 按平台常见安装位置探测 Chromium 系浏览器可执行文件,找不到返回 null。
     * Windows 优先 Edge(系统自带),macOS/Linux 优先 Chrome。
     */
    private static String findChromiumBrowser() {
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

    private static void addCandidate(List<String> candidates, String base, String relative) {
        if (base != null && !base.isBlank()) {
            candidates.add(base + "\\" + relative);
        }
    }

    private static String findOnPath(String... names) {
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
