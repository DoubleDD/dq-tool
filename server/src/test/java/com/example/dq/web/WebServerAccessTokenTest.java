package com.example.dq.web;

import com.example.dq.config.AiProperties;
import com.example.dq.config.ConfigLoader;
import com.example.dq.config.DqProperties;
import com.example.dq.license.LicenseCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dq.access-token 门禁 + CorsPlugin 接入:
 * 无令牌 403、header/query/Cookie 三选一放行、豁免清单免令牌、OPTIONS 预检放行、
 * Origin: null 与 tauri://localhost 均回 Access-Control-Allow-Origin: *。
 * 未配置令牌时的既有行为由 WebServerSmokeTest 回归。
 */
class WebServerAccessTokenTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path dataDir;

    private WebServer server;
    private HttpClient client;
    private KeyPair licenseKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        licenseKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DqProperties dq = new DqProperties();
        dq.getLicense().setPublicKey(Base64.getEncoder().encodeToString(licenseKeyPair.getPublic().getEncoded()));
        dq.getAccessTokens().add(TOKEN);
        server = new WebServer(new ConfigLoader.AppConfig(dq, new AiProperties(), 0,
                dataDir.toString(), "1.5-test"));
        server.start(0);
        server.finishInit();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private String base() {
        return "http://localhost:" + server.port();
    }

    private HttpResponse<String> send(String method, String path, String tokenHeader, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path));
        if (tokenHeader != null) builder.header("X-Dq-Token", tokenHeader);
        if (cookie != null) builder.header("Cookie", cookie);
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithOrigin(String path, String origin) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + path))
                        .header("Origin", origin)
                        .header("X-Dq-Token", TOKEN)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void activateLicense() throws Exception {
        String code = LicenseCodec.encode("测试客户", null, licenseKeyPair.getPrivate(),
                "1.5-test", "", "", "", 1755000000000L);
        // 激活接口本身也受门禁保护,须带令牌
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/license/activate"))
                        .header("X-Dq-Token", TOKEN)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
    }

    @Test
    void 无令牌403_头与Cookie三选一放行() throws Exception {
        activateLicense();
        assertEquals(403, send("GET", "/api/datasources", null, null).statusCode());
        assertEquals(403, send("GET", "/api/datasources", "wrong-token", null).statusCode());
        assertEquals(403, send("GET", "/api/datasources", null, "dq-access-token=wrong").statusCode());
        // 未匹配的 /api/** 也在全局 before 被拦成 403,而非 404
        assertEquals(403, send("GET", "/api/not-exists", null, null).statusCode());

        assertEquals(200, send("GET", "/api/datasources", TOKEN, null).statusCode());
        assertEquals(200, send("GET", "/api/datasources", null, "dq-access-token=" + TOKEN).statusCode());
    }

    @Test
    void query令牌放行并种SameSiteCookie() throws Exception {
        activateLicense();
        HttpResponse<String> resp = send("GET", "/api/datasources?token=" + TOKEN, null, null);
        assertEquals(200, resp.statusCode(), resp.body());
        String setCookie = resp.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.contains("dq-access-token=" + TOKEN), setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
        assertTrue(setCookie.contains("Path=/"), setCookie);
    }

    @Test
    void 豁免清单免令牌_页面路由受控() throws Exception {
        // 就绪探针/授权状态/局域网共享出口免令牌
        assertEquals(200, send("GET", "/api/health", null, null).statusCode());
        assertEquals(200, send("GET", "/api/license/status", null, null).statusCode());
        assertEquals(200, send("GET", "/api/lan/share/info", null, null).statusCode());

        // 页面路由(无扩展名)无令牌 → 403 HTML;带 query 令牌 → 200(index.html)
        HttpResponse<String> blocked = send("GET", "/", null, null);
        assertEquals(403, blocked.statusCode());
        assertTrue(blocked.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                String.valueOf(blocked.headers().map()));
        assertEquals(200, send("GET", "/?token=" + TOKEN, null, null).statusCode());
    }

    @Test
    void OPTIONS预检不被门禁拦截且带CORS头() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/datasources"))
                .header("Origin", "tauri://localhost")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "x-dq-token")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertNotEquals(403, resp.statusCode(), resp.body());
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""),
                String.valueOf(resp.headers().map()));
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("x-dq-token"),
                String.valueOf(resp.headers().map()));
    }

    @Test
    void CORS任意来源回星号() throws Exception {
        activateLicense();
        for (String origin : new String[]{"null", "tauri://localhost", "http://tauri.localhost"}) {
            HttpResponse<String> resp = getWithOrigin("/api/datasources", origin);
            assertEquals(200, resp.statusCode(), origin + " -> " + resp.body());
            assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""),
                    origin + " -> " + resp.headers().map());
        }
    }
}
