package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 桌面安装包(jpackage)双击启动后自动打开首页:
 * 优先探测 Chrome / Edge 并以 --app= 应用模式拉起(独立窗口,无地址栏/标签页),
 * 探测不到再回落到系统默认浏览器。
 * headless 的服务器部署(java -jar、容器)自动跳过。
 */
@Component
public class BrowserOpener {

    private static final Logger log = LoggerFactory.getLogger(BrowserOpener.class);

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser(ApplicationReadyEvent event) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        String port = event.getApplicationContext().getEnvironment().getProperty("server.port", "8080");
        String url = "http://localhost:" + port;
        if (openInAppMode(url)) {
            return;
        }
        openInDefaultBrowser(url);
    }

    /**
     * 用 Chromium 系浏览器的应用模式打开,返回是否成功。
     */
    private boolean openInAppMode(String url) {
        String browser = findChromiumBrowser();
        if (browser == null) {
            return false;
        }
        try {
            new ProcessBuilder(browser, "--app=" + url).start();
            log.info("已用应用模式打开 {} ({})", url, browser);
            return true;
        } catch (Exception e) {
            log.warn("应用模式启动浏览器失败,回退到默认浏览器: {}", e.getMessage());
            return false;
        }
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
