package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

/**
 * 桌面安装包(jpackage)双击启动后自动用默认浏览器打开首页。
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
        try {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
                return;
            }
            String port = event.getApplicationContext().getEnvironment().getProperty("server.port", "8080");
            String url = "http://localhost:" + port;
            desktop.browse(new URI(url));
            log.info("已在默认浏览器打开 {}", url);
        } catch (Exception e) {
            log.warn("自动打开浏览器失败,请手动访问首页: {}", e.getMessage());
        }
    }
}
