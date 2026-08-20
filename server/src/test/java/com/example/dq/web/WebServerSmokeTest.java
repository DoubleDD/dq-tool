package com.example.dq.web;

import com.example.dq.config.AiProperties;
import com.example.dq.config.ConfigLoader;
import com.example.dq.config.DqProperties;
import com.example.dq.license.LicenseCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebServer 起停与关键端点冒烟:共享内核装配(Flyway 迁移)+ Jackson 3 序列化 Kotlin 模型 + 授权前置校验
 * + 表标记端点全链路(CRUD/单表打标往返/两个统计视图/重名 409)。
 * 用临时数据目录,不触碰开发库;授权用测试临时生成的 Ed25519 密钥对激活。
 */
class WebServerSmokeTest {

    @TempDir
    Path dataDir;

    private WebServer server;
    private HttpClient client;
    private KeyPair licenseKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        // 测试密钥对:公钥注入配置,私钥只在测试里签发永久授权码
        licenseKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DqProperties dq = new DqProperties();
        dq.getLicense().setPublicKey(Base64.getEncoder().encodeToString(licenseKeyPair.getPublic().getEncoded()));
        server = new WebServer(new ConfigLoader.AppConfig(dq, new AiProperties(), 0,
                dataDir.toString(), "1.5-test"));
        server.start(0);
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

    private HttpResponse<String> send(String method, String path, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path));
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Content-Type", "application/json");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 激活永久授权,绕过业务接口的授权前置校验 */
    private void activateLicense() throws Exception {
        String code = LicenseCodec.encode("测试客户", null, licenseKeyPair.getPrivate(),
                "1.5-test", "jdbc:oracle:thin:@//secret-host:1521/ORCL", "scott", "ORCL", 1755000000000L);
        HttpResponse<String> resp = send("POST", "/api/license/activate",
                "{\"code\":\"" + code + "\"}");
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("\"activated\":true"), resp.body());
    }

    @Test
    void 授权状态回传扩展字段且不泄露serverUrl() throws Exception {
        activateLicense();
        HttpResponse<String> resp = get("/api/license/status");
        assertEquals(200, resp.statusCode());
        // username/sid/timestamp 回传前端展示
        assertTrue(resp.body().contains("\"username\":\"scott\""), resp.body());
        assertTrue(resp.body().contains("\"sid\":\"ORCL\""), resp.body());
        assertTrue(resp.body().contains("\"timestamp\":1755000000000"), resp.body());
        // server_url 属敏感信息,禁止出现在状态接口
        assertFalse(resp.body().contains("secret-host"), resp.body());
        assertFalse(resp.body().contains("serverUrl"), resp.body());
        // 软件版本号透出(页脚展示);默认实例非管理员
        assertTrue(resp.body().contains("\"appVersion\":\"1.5-test\""), resp.body());
        assertTrue(resp.body().contains("\"admin\":false"), resp.body());
    }

    @Test
    void 非管理员访问授权码管理返回403() throws Exception {
        // 管理端点在 /api/license 前缀下不被激活拦截,但未配置签发私钥一律 403
        assertEquals(403, get("/api/license/admin/codes").statusCode());
        assertEquals(403, send("POST", "/api/license/admin/codes",
                "{\"customer\":\"x\",\"expires\":\"permanent\"}").statusCode());
        assertEquals(403, send("DELETE", "/api/license/admin/codes/1", null).statusCode());
    }

    @Test
    void 管理员实例授权码管理全链路() throws Exception {
        // 另起一个配置了签发私钥的管理员实例(同一密钥对,生成的码可被本实例公钥验过)
        DqProperties adminDq = new DqProperties();
        adminDq.getLicense().setPublicKey(Base64.getEncoder().encodeToString(licenseKeyPair.getPublic().getEncoded()));
        adminDq.getLicense().setPrivateKey(Base64.getEncoder().encodeToString(licenseKeyPair.getPrivate().getEncoded()));
        WebServer admin = new WebServer(new ConfigLoader.AppConfig(adminDq, new AiProperties(), 0,
                java.nio.file.Files.createTempDirectory("dq-admin-test").toString(), "1.5-test"));
        admin.start(0);
        try {
            java.util.function.BiFunction<String, String, HttpResponse<String>> call = (method, path) -> {
                try {
                    HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + admin.port() + path));
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            // status:管理员标识
            HttpResponse<String> status = call.apply("GET", "/api/license/status");
            assertTrue(status.body().contains("\"admin\":true"), status.body());

            // 生成
            HttpRequest genReq = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + admin.port() + "/api/license/admin/codes"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"customer\":\"甲公司\",\"expires\":\"permanent\","
                                    + "\"serverUrl\":\"jdbc:oracle:thin:@//secret-host:1521/ORCL\","
                                    + "\"username\":\"scott\",\"sid\":\"ORCL\"}"))
                    .build();
            HttpResponse<String> gen = client.send(genReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, gen.statusCode(), gen.body());
            assertTrue(gen.body().contains("\"appVersion\":\"1.5-test\""), gen.body());
            assertTrue(gen.body().contains("\"code\":\"DQ1."), gen.body());
            // 管理接口可见 server_url(留档目的;用户实例状态接口仍不可见)
            assertTrue(gen.body().contains("secret-host"), gen.body());
            long id = Long.parseLong(gen.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

            // 列表
            HttpResponse<String> list = call.apply("GET", "/api/license/admin/codes");
            assertEquals(200, list.statusCode(), list.body());
            assertTrue(list.body().contains("甲公司"), list.body());

            // 生成的码可在用户实例上激活(密钥对一致)
            String code = gen.body().replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");
            HttpResponse<String> act = send("POST", "/api/license/activate", "{\"code\":\"" + code + "\"}");
            assertEquals(200, act.statusCode(), act.body());

            // 删除
            assertEquals(204, call.apply("DELETE", "/api/license/admin/codes/" + id).statusCode());
            HttpResponse<String> after = call.apply("GET", "/api/license/admin/codes");
            assertEquals("[]", after.body(), after.body());
        } finally {
            admin.stop();
        }
    }

    @Test
    void 授权状态接口可达且序列化Kotlin模型() throws Exception {
        HttpResponse<String> resp = get("/api/license/status");
        assertEquals(200, resp.statusCode());
        // LicenseStatusView(Kotlin data class)序列化字段齐全
        assertTrue(resp.body().contains("\"activated\":false"), resp.body());
    }

    @Test
    void 未激活时业务接口被授权前置校验拦截() throws Exception {
        HttpResponse<String> resp = get("/api/datasources");
        assertEquals(401, resp.statusCode());
        assertTrue(resp.body().contains("message"), resp.body());
    }

    @Test
    void 心跳接口不被拦截() throws Exception {
        assertEquals(204, get("/api/heartbeat").statusCode());
    }

    @Test
    void 静态资源缓存策略与SPA回退边界() throws Exception {
        // 入口 index.html:no-cache 每次重校验,防止升级后浏览器拿旧入口引用已不存在的旧 hash 资源(白屏)
        HttpResponse<String> index = get("/");
        assertEquals(200, index.statusCode());
        assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                String.valueOf(index.headers().map()));
        assertEquals("no-cache", index.headers().firstValue("Cache-Control").orElse(""),
                String.valueOf(index.headers().map()));

        // 前端路由(无扩展名):SPA 回退 index.html,同样 no-cache
        HttpResponse<String> route = get("/datasources");
        assertEquals(200, route.statusCode());
        assertTrue(route.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertEquals("no-cache", route.headers().firstValue("Cache-Control").orElse(""));

        // 缺失的静态文件(带扩展名):必须真实 404,绝不回退成 text/html(否则模块脚本 MIME 报错)
        HttpResponse<String> missing = get("/assets/not-exists-deadbeef.js");
        assertEquals(404, missing.statusCode());

        // 真实存在的指纹资源:长缓存 immutable(从 index.html 里取当前真实资源名,避免硬编码 hash)
        String jsPath = index.body().replaceAll("(?s).*src=\"(/assets/[^\"]+\\.js)\".*", "$1");
        assertTrue(jsPath.startsWith("/assets/"), index.body());
        HttpResponse<String> asset = get(jsPath);
        assertEquals(200, asset.statusCode());
        assertTrue(asset.headers().firstValue("Cache-Control").orElse("").contains("immutable"),
                String.valueOf(asset.headers().map()));
    }

    @Test
    void 标记列表含系统空表标记() throws Exception {
        activateLicense();
        HttpResponse<String> resp = get("/api/tags");
        assertEquals(200, resp.statusCode(), resp.body());
        // kind 序列化为枚举名
        assertTrue(resp.body().contains("\"name\":\"空表\""), resp.body());
        assertTrue(resp.body().contains("\"kind\":\"EMPTY\""), resp.body());
    }

    @Test
    void 标记新建编辑删除生命周期() throws Exception {
        activateLicense();
        // 新建
        HttpResponse<String> created = send("POST", "/api/tags", "{\"name\":\"水利对象表\",\"color\":\"#67C23A\"}");
        assertEquals(200, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"name\":\"水利对象表\""), created.body());
        assertTrue(created.body().contains("\"kind\":\"USER\""), created.body());
        long id = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 改名 + 改色
        HttpResponse<String> updated = send("PUT", "/api/tags/" + id, "{\"name\":\"基础水利对象表\",\"color\":\"#E6A23C\"}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"name\":\"基础水利对象表\""), updated.body());
        assertTrue(updated.body().contains("\"color\":\"#E6A23C\""), updated.body());

        // 删除
        assertEquals(200, send("DELETE", "/api/tags/" + id, null).statusCode());
        HttpResponse<String> list = get("/api/tags");
        assertFalse(list.body().contains("基础水利对象表"), list.body());
    }

    @Test
    void 标记重名返回409() throws Exception {
        activateLicense();
        assertEquals(200, send("POST", "/api/tags", "{\"name\":\"防洪业务表\"}").statusCode());
        HttpResponse<String> dup = send("POST", "/api/tags", "{\"name\":\"防洪业务表\"}");
        assertEquals(409, dup.statusCode(), dup.body());
        assertTrue(dup.body().contains("message"), dup.body());
    }

    @Test
    void 单表打标与整库标记查询往返() throws Exception {
        activateLicense();
        HttpResponse<String> created = send("POST", "/api/tags", "{\"name\":\"水资源业务表\"}");
        long tagId = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 打标(dsId 无实际数据源也行,打标关系只记录标识四元组)
        HttpResponse<String> put = send("PUT", "/api/datasources/1/schemas/public/tables/t_water/tags",
                "{\"tagIds\":[" + tagId + "]}");
        assertEquals(200, put.statusCode(), put.body());
        assertTrue(put.body().contains("\"name\":\"水资源业务表\""), put.body());

        // 整库 map 里能查到该表
        HttpResponse<String> map = get("/api/datasources/1/schemas/public/table-tags");
        assertEquals(200, map.statusCode(), map.body());
        assertTrue(map.body().contains("\"t_water\""), map.body());

        // 替换为空列表即摘除
        HttpResponse<String> cleared = send("PUT", "/api/datasources/1/schemas/public/tables/t_water/tags",
                "{\"tagIds\":[]}");
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertFalse(cleared.body().contains("水资源业务表"), cleared.body());
    }

    @Test
    void 标记统计与库维度标记计数端点可访问() throws Exception {
        activateLicense();
        HttpResponse<String> tags = get("/api/tags");
        long emptyId = Long.parseLong(tags.body()
                .replaceAll("(?s).*?\"id\":(\\d+),\"name\":\"空表\".*", "$1"));

        // 空数据下两个统计视图均返回 200 与结构字段
        HttpResponse<String> stats = get("/api/tags/" + emptyId + "/stats");
        assertEquals(200, stats.statusCode(), stats.body());
        assertTrue(stats.body().contains("\"totalTables\":0"), stats.body());
        assertTrue(stats.body().contains("\"schemas\":[]"), stats.body());

        HttpResponse<String> schemaStats = get("/api/datasources/1/schema-tag-stats");
        assertEquals(200, schemaStats.statusCode(), schemaStats.body());
        assertEquals("[]", schemaStats.body());
    }

    @Test
    void 库描述编辑与Word报告导出端点() throws Exception {
        activateLicense();
        HttpResponse<String> created = send("POST", "/api/datasources",
                "{\"name\":\"报告源\",\"jdbcUrl\":\"jdbc:mysql://127.0.0.1:59998/db\",\"username\":\"root\",\"password\":\"p\"}");
        assertEquals(200, created.statusCode(), created.body());
        long id = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 库描述编辑:本地 H2 读写,不连业务库
        HttpResponse<String> put = send("PUT", "/api/datasources/" + id + "/schemas/db_a/description",
                "{\"description\":\"地下水监测库\"}");
        assertEquals(200, put.statusCode(), put.body());
        // 超长描述走 400 统一映射
        assertEquals(400, send("PUT", "/api/datasources/" + id + "/schemas/db_a/description",
                "{\"description\":\"" + "x".repeat(513) + "\"}").statusCode());

        // 异步导出:提交任务 → 目标库不可达,后台执行失败 → 任务 FAILED 且带原因;未完成任务不可打开
        HttpResponse<String> submitted = send("POST", "/api/datasources/" + id + "/report/exports",
                "{\"schemas\":[\"db_a\"]}");
        assertEquals(200, submitted.statusCode(), submitted.body());
        long taskId = Long.parseLong(submitted.body().replaceAll(".*\"taskId\":(\\d+).*", "$1"));
        String status = "";
        for (int i = 0; i < 100 && !"FAILED".equals(status) && !"DONE".equals(status); i++) {
            Thread.sleep(200);
            String body = get("/api/report-exports?datasourceId=" + id).body();
            status = body.replaceAll("(?s).*\"status\":\"([A-Z]+)\".*", "$1");
        }
        assertEquals("FAILED", status, "目标库不可达,任务应失败");
        assertEquals(409, send("POST", "/api/report-exports/" + taskId + "/open", null).statusCode());
    }

    @Test
    void 数据源导出下载为JSON文件() throws Exception {        activateLicense();
        HttpResponse<String> created = send("POST", "/api/datasources",
                "{\"name\":\"导出源\",\"jdbcUrl\":\"jdbc:mysql://localhost:3306/db\",\"username\":\"root\",\"password\":\"p123\"}");
        assertEquals(200, created.statusCode(), created.body());
        long id = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        HttpResponse<String> resp = get("/api/datasources/export?ids=" + id);
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("dq-tool"), resp.body());
        assertTrue(resp.headers().firstValue("Content-Disposition").orElse("").contains("attachment"),
                String.valueOf(resp.headers().map()));

        // ids 缺失走 400 统一映射
        assertEquals(400, get("/api/datasources/export").statusCode());
    }

    @Test
    void 数据源带SSH字段创建与测试连接() throws Exception {
        activateLicense();
        // 创建:带 SSH 隧道字段(保存前的模式探测走一次性隧道,连不上不影响保存)
        HttpResponse<String> created = send("POST", "/api/datasources",
                "{\"name\":\"隧道源\",\"jdbcUrl\":\"jdbc:mysql://db.internal:3306/db\",\"username\":\"root\","
                        + "\"password\":\"p\",\"sshEnabled\":true,\"sshHost\":\"127.0.0.1\",\"sshPort\":59999,"
                        + "\"sshUsername\":\"ops\",\"sshAuthMethod\":\"password\",\"sshPassword\":\"sp\"}");
        assertEquals(200, created.statusCode(), created.body());

        // 列表回传 SSH 非秘密字段,秘密字段不回传
        HttpResponse<String> list = get("/api/datasources");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"sshEnabled\":true"), list.body());
        assertTrue(list.body().contains("\"sshHost\":\"127.0.0.1\""), list.body());
        assertTrue(list.body().contains("\"sshPort\":59999"), list.body());
        assertFalse(list.body().contains("\"sshPassword\":\"sp\""), list.body());

        // 测试连接:无 SSH、目标库不可达,返回 success=false
        HttpResponse<String> noSsh = send("POST", "/api/datasources/test",
                "{\"jdbcUrl\":\"jdbc:mysql://127.0.0.1:59998/db\",\"username\":\"root\",\"password\":\"p\"}");
        assertEquals(200, noSsh.statusCode(), noSsh.body());
        assertTrue(noSsh.body().contains("\"success\":false"), noSsh.body());

        // 测试连接:启用 SSH、跳板机端口拒连,走 409 统一映射并带「SSH 隧道连接失败」信息
        HttpResponse<String> withSsh = send("POST", "/api/datasources/test",
                "{\"jdbcUrl\":\"jdbc:mysql://db.internal:3306/db\",\"username\":\"root\",\"password\":\"p\","
                        + "\"sshEnabled\":true,\"sshHost\":\"127.0.0.1\",\"sshPort\":59999,"
                        + "\"sshUsername\":\"ops\",\"sshAuthMethod\":\"password\",\"sshPassword\":\"sp\"}");
        assertEquals(409, withSsh.statusCode(), withSsh.body());
        assertTrue(withSsh.body().contains("SSH 隧道连接失败"), withSsh.body());
    }

    @Test
    void 数据源导入multipart上传JSON() throws Exception {
        activateLicense();
        // 导入文件内容:固定密钥 TransferCrypto 加密的密码(此处直接放 null 密码,走同一导入路径)
        String exportJson = "{\"app\":\"dq-tool\",\"version\":1,\"exportedAt\":\"2026-08-06T00:00:00Z\","
                + "\"items\":[{\"name\":\"导入源\",\"jdbcUrl\":\"jdbc:mysql://localhost:3306/db2\","
                + "\"username\":\"root\",\"passwordEnc\":null,\"rowThreshold\":null,\"sizeThresholdBytes\":null}]}";

        String boundary = "----dq-test-boundary";
        byte[] fileBytes = exportJson.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ds.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(fileBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/api/datasources/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("导入源"), resp.body());
        assertTrue(resp.body().contains("\"imported\":[\"导入源\"]"), resp.body());

        // 不支持的扩展名走 400
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        bad.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ds.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nx\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest badReq = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/api/datasources/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bad.toByteArray()))
                .build();
        assertEquals(400, client.send(badReq, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
