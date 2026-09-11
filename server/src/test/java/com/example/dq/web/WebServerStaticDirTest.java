package com.example.dq.web;

import com.example.dq.config.AiProperties;
import com.example.dq.config.ConfigLoader;
import com.example.dq.config.DqProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dq.web.static-dir 生效:配置了磁盘目录时从磁盘发静态、清单同口径、SPA 回退与 404 边界保持
 * (未配置时退回 classpath 的形态由 WebServerSmokeTest 回归)。
 */
class WebServerStaticDirTest {

    @TempDir
    Path dataDir;

    @TempDir
    Path staticDir;

    private WebServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(staticDir.resolve("index.html"),
                "<!doctype html><html><body><script src=\"/assets/app-abc123.js\"></script></body></html>");
        Files.createDirectories(staticDir.resolve("assets"));
        Files.writeString(staticDir.resolve("assets/app-abc123.js"), "console.log('dq')");
        DqProperties dq = new DqProperties();
        dq.getWeb().setStaticDir(staticDir.toString());
        server = new WebServer(new ConfigLoader.AppConfig(dq, new AiProperties(), 0,
                dataDir.toString(), "1.5-test"));
        server.start(0);
        // 静态与清单端点不依赖共享内核;不调 finishInit 让本用例更快
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 从磁盘目录发静态且清单同口径() throws Exception {
        HttpResponse<String> index = get("/");
        assertEquals(200, index.statusCode(), index.body());
        assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                String.valueOf(index.headers().map()));
        assertEquals("no-store", index.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(index.body().contains("app-abc123.js"), index.body());

        HttpResponse<String> js = get("/assets/app-abc123.js");
        assertEquals(200, js.statusCode(), js.body());
        assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"),
                String.valueOf(js.headers().map()));
        assertTrue(js.headers().firstValue("Cache-Control").orElse("").contains("immutable"),
                String.valueOf(js.headers().map()));

        // 清单出口来自磁盘枚举,供启动诊断比对
        HttpResponse<String> manifest = get("/api/assets-manifest");
        assertEquals(200, manifest.statusCode(), manifest.body());
        assertTrue(manifest.body().contains("/assets/app-abc123.js"), manifest.body());
        assertTrue(manifest.body().contains("/index.html"), manifest.body());

        // SPA 回退:无扩展名前端路由回 index.html
        HttpResponse<String> spa = get("/datasources");
        assertEquals(200, spa.statusCode());
        assertTrue(spa.body().contains("app-abc123.js"));

        // 缺失的带扩展名资源真实 404,绝不给 HTML(否则 MIME 白屏)
        assertEquals(404, get("/assets/not-exists-deadbeef.js").statusCode());
    }
}
