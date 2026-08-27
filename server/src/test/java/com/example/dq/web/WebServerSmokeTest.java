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
        // 共享内核初始化(H2 建表/迁移 + 中断恢复)在绑定后显式完成,与 DqApplication 启动时序一致
        server.finishInit();
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
        admin.finishInit();
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
    void 表数据预览缺省rows参数不报NPE() throws Exception {
        activateLicense();
        HttpResponse<String> created = send("POST", "/api/datasources",
                "{\"name\":\"预览源\",\"jdbcUrl\":\"jdbc:mysql://127.0.0.1:59998/db\",\"username\":\"root\",\"password\":\"p\"}");
        assertEquals(200, created.statusCode(), created.body());
        long id = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 不传 rows:缺省 100;目标库不可达(Hikari 池初始化失败,RuntimeException → 500 统一映射),
        // 不能再现 queryParamAsClass.getOrDefault(null) 的 Kotlin 非空 NPE
        HttpResponse<String> resp = get("/api/datasources/" + id + "/schemas/db/tables/t_preview/preview");
        assertEquals(500, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("message"), resp.body());
        assertFalse(resp.body().contains("NullPointerException"), resp.body());
    }

    @Test
    void 心跳接口不被拦截() throws Exception {
        assertEquals(204, get("/api/heartbeat").statusCode());
    }

    @Test
    void 通用列表导出全链路() throws Exception {
        activateLicense();
        // stage:提交所见表格数据 → token
        HttpResponse<String> staged = send("POST", "/api/list-exports",
                "{\"filename\":\"库列表\",\"sheets\":[{\"name\":\"库列表\",\"headers\":[\"库名\",\"表数量\"],"
                        + "\"rows\":[[\"dqtest\",\"120\"],[\"app\",\"\"]]}]}");
        assertEquals(200, staged.statusCode(), staged.body());
        String token = staged.body().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // download:xlsx 文件流 + Content-Disposition
        HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/api/list-exports/" + token)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Disposition").orElse("").contains("attachment"),
                String.valueOf(resp.headers().map()));
        byte[] body = resp.body();
        assertTrue(body.length > 4 && body[0] == 'P' && body[1] == 'K', "应为 xlsx(zip) 文件流");

        // 一次性:重复下载 404
        assertEquals(404, get("/api/list-exports/" + token).statusCode());

        // 参数校验:filename 空走 400 统一映射
        assertEquals(400, send("POST", "/api/list-exports",
                "{\"filename\":\"\",\"sheets\":[{\"name\":\"s\",\"headers\":[\"h\"],\"rows\":[]}]}").statusCode());
    }

    @Test
    void 就绪闸门与健康探针_未就绪503就绪后放行() throws Exception {
        // 另起一个未完成初始化的实例:绑定后、finishInit 前,业务接口应被闸门拦成 503
        DqProperties bootDq = new DqProperties();
        bootDq.getLicense().setPublicKey(Base64.getEncoder().encodeToString(licenseKeyPair.getPublic().getEncoded()));
        WebServer booting = new WebServer(new ConfigLoader.AppConfig(bootDq, new AiProperties(), 0,
                java.nio.file.Files.createTempDirectory("dq-booting-test").toString(), "1.5-test"));
        booting.start(0);
        try {
            java.util.function.Function<String, HttpResponse<String>> bootGet = (path) -> {
                try {
                    return client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + booting.port() + path)).build(),
                            HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            // 未就绪:健康探针 503(带 Retry-After 与 starting 状态),业务接口 503——
            // 闸门在授权校验之前短路,未激活也应返回 503 而非 401
            HttpResponse<String> health = bootGet.apply("/api/health");
            assertEquals(503, health.statusCode(), health.body());
            assertTrue(health.body().contains("starting"), health.body());
            assertEquals("1", health.headers().firstValue("Retry-After").orElse(""),
                    String.valueOf(health.headers().map()));
            assertEquals(503, bootGet.apply("/api/datasources").statusCode());

            // 静态页面不受闸门影响(首页秒出,等待后端就绪的占位)
            HttpResponse<String> index = bootGet.apply("/");
            assertEquals(200, index.statusCode());
            assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                    String.valueOf(index.headers().map()));

            // 就绪后:健康探针 200,业务接口回到授权前置校验(未激活 → 401)
            booting.finishInit();
            HttpResponse<String> readyHealth = bootGet.apply("/api/health");
            assertEquals(200, readyHealth.statusCode(), readyHealth.body());
            assertTrue(readyHealth.body().contains("ok"), readyHealth.body());
            assertEquals(401, bootGet.apply("/api/datasources").statusCode());
        } finally {
            booting.stop();
        }
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
    void 系统库清单端点按数据库类型返回() throws Exception {
        activateLicense();
        HttpResponse<String> mssql = get("/api/db-types/SQLSERVER/system-schemas");
        assertEquals(200, mssql.statusCode(), mssql.body());
        assertTrue(mssql.body().contains("master"), mssql.body());
        assertTrue(mssql.body().contains("reportservertempdb"), mssql.body());

        HttpResponse<String> mysql = get("/api/db-types/MYSQL/system-schemas");
        assertEquals(200, mysql.statusCode(), mysql.body());
        assertTrue(mysql.body().contains("performance_schema"), mysql.body());

        // 类型名大小写不敏感;未知类型走统一 400/500 语义
        HttpResponse<String> lower = get("/api/db-types/mysql/system-schemas");
        assertEquals(200, lower.statusCode(), lower.body());
    }

    @Test
    void AI用量统计端点与AI配置价格回显() throws Exception {
        activateLicense();
        // 空数据:汇总为 0、序列 30 天补零、场景与明细为空
        HttpResponse<String> stats = get("/api/ai-usage/stats?days=30");
        assertEquals(200, stats.statusCode(), stats.body());
        assertTrue(stats.body().contains("\"calls\":0"), stats.body());
        assertTrue(stats.body().contains("\"series\""), stats.body());
        assertTrue(stats.body().contains("\"scenes\":[]"), stats.body());
        HttpResponse<String> logs = get("/api/ai-usage/logs");
        assertEquals(200, logs.statusCode(), logs.body());
        assertTrue(logs.body().contains("\"items\":[]"), logs.body());
        assertTrue(logs.body().contains("\"total\":0"), logs.body());
        HttpResponse<String> scanSeries = get("/api/ai-usage/scan-series?days=30");
        assertEquals(200, scanSeries.statusCode(), scanSeries.body());
        assertEquals("[]", scanSeries.body());

        // AI 配置回显合并默认值后的计费价格(DeepSeek 默认价,峰谷计价默认开启)
        HttpResponse<String> cfg = get("/api/ai-config");
        assertEquals(200, cfg.statusCode(), cfg.body());
        assertTrue(cfg.body().contains("\"peakValleyEnabled\":true"), cfg.body());
        assertTrue(cfg.body().contains("\"peakInputPrice\":9.0"), cfg.body());
        assertTrue(cfg.body().contains("\"peakOutputPrice\":27.0"), cfg.body());
        assertTrue(cfg.body().contains("\"valleyInputPrice\":4.5"), cfg.body());
        assertTrue(cfg.body().contains("\"valleyOutputPrice\":13.5"), cfg.body());
        assertTrue(cfg.body().contains("\"workPeriods\":[\"09:00-12:00\",\"14:00-18:00\"]"), cfg.body());
        assertTrue(cfg.body().contains("\"weekendValley\":true"), cfg.body());

        // 保存自定义价格后回显更新(含工作时间段多段)
        HttpResponse<String> saved = send("PUT", "/api/ai-config",
                "{\"baseUrl\":\"http://localhost:1/v1\",\"apiKey\":\"k\",\"model\":\"m\"," +
                "\"peakValleyEnabled\":true,\"peakInputPrice\":2.0,\"peakOutputPrice\":8.0," +
                "\"valleyInputPrice\":1.0,\"valleyOutputPrice\":4.0," +
                "\"workPeriods\":[\"08:00-11:30\",\"13:00-17:00\"],\"weekendValley\":true}");
        assertEquals(200, saved.statusCode(), saved.body());
        HttpResponse<String> cfg2 = get("/api/ai-config");
        assertTrue(cfg2.body().contains("\"peakInputPrice\":2.0"), cfg2.body());
        assertTrue(cfg2.body().contains("\"peakOutputPrice\":8.0"), cfg2.body());
        assertTrue(cfg2.body().contains("\"valleyInputPrice\":1.0"), cfg2.body());
        assertTrue(cfg2.body().contains("\"workPeriods\":[\"08:00-11:30\",\"13:00-17:00\"]"), cfg2.body());

        // 关闭峰谷计价:回显 peakValleyEnabled=false
        HttpResponse<String> off = send("PUT", "/api/ai-config",
                "{\"baseUrl\":\"http://localhost:1/v1\",\"apiKey\":\"k\",\"model\":\"m\",\"peakValleyEnabled\":false}");
        assertEquals(200, off.statusCode(), off.body());
        HttpResponse<String> cfg3 = get("/api/ai-config");
        assertTrue(cfg3.body().contains("\"peakValleyEnabled\":false"), cfg3.body());
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

    @Test
    void 扫描记录导出导入端点() throws Exception {
        activateLicense();
        HttpResponse<String> created = send("POST", "/api/datasources",
                "{\"name\":\"迁移源\",\"jdbcUrl\":\"jdbc:mysql://localhost:3306/db\",\"username\":\"root\",\"password\":\"p\"}");
        assertEquals(200, created.statusCode(), created.body());
        long dsId = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 导出(无 ids = 全部;空库也返回合法文件):静态段路由不被 /api/scans/{jobId} 截获
        HttpResponse<String> exportResp = get("/api/scans/transfer/export");
        assertEquals(200, exportResp.statusCode(), exportResp.body());
        assertTrue(exportResp.body().contains("dq-tool-scans"), exportResp.body());
        assertTrue(exportResp.headers().firstValue("Content-Disposition").orElse("").contains("dq-scans-"),
                String.valueOf(exportResp.headers().map()));

        // 导入文件:一条 DONE 任务(含表/分段/字段明细)
        String importJson = "{\"app\":\"dq-tool-scans\",\"version\":1,\"exportedAt\":\"t\",\"jobs\":["
                + "{\"datasourceName\":\"迁移源\",\"schemaName\":\"public\",\"status\":\"DONE\","
                + "\"createdAt\":\"2026-08-01T10:00:00\",\"totalTables\":1,\"doneTables\":1,"
                + "\"tables\":[{\"tableName\":\"t_user\",\"status\":\"DONE\",\"totalRows\":100,"
                + "\"chunks\":[{\"seq\":0,\"status\":\"DONE\",\"rowCount\":100}],"
                + "\"columns\":[{\"columnName\":\"name\",\"columnType\":\"varchar(64)\",\"totalRows\":100,\"nullCount\":5}]}]}]}";
        String boundary = "----dq-test-boundary";

        // 预检:文件数据源分布 + 同名自动匹配
        HttpResponse<String> preview = multipartPost("/api/scans/transfer/preview", boundary, importJson, null);
        assertEquals(200, preview.statusCode(), preview.body());
        assertTrue(preview.body().contains("\"totalJobs\":1"), preview.body());
        assertTrue(preview.body().contains("\"matchedDatasourceId\":" + dsId), preview.body());

        // 导入:mapping 映射到本机数据源 → imported=1,列表可见
        HttpResponse<String> imported = multipartPost("/api/scans/transfer/import", boundary, importJson,
                "{\"迁移源\":" + dsId + "}");
        assertEquals(200, imported.statusCode(), imported.body());
        assertTrue(imported.body().contains("\"imported\":1"), imported.body());
        HttpResponse<String> list = get("/api/scans?datasourceId=" + dsId);
        assertTrue(list.body().contains("\"schemaName\":\"public\""), list.body());
        assertTrue(list.body().contains("2026-08-01"), list.body());

        // 重复导入:按 数据源+db+schema+created_at 去重 → skipped=1,warnings 带判重原因
        HttpResponse<String> again = multipartPost("/api/scans/transfer/import", boundary, importJson,
                "{\"迁移源\":" + dsId + "}");
        assertTrue(again.body().contains("\"skipped\":1"), again.body());
        assertTrue(again.body().contains("判重跳过"), again.body());

        // 非法文件走 400 统一映射
        HttpResponse<String> bad = multipartPost("/api/scans/transfer/preview", boundary, "不是 JSON", null);
        assertEquals(400, bad.statusCode(), bad.body());
    }

    /** multipart 上传:file 部分为扫描记录 JSON,可选 mapping 表单字段(JSON 字符串) */
    private HttpResponse<String> multipartPost(String path, String boundary, String fileContent, String mapping)
            throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"scans.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(fileContent.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        if (mapping != null) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"mapping\"\r\n\r\n"
                    + mapping + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 系统设置扫描参数读取保存恢复默认() throws Exception {
        // 未激活时被授权前置校验拦截
        assertEquals(401, get("/api/system-settings/scan").statusCode());

        activateLicense();
        // 初始:配置文件默认值(与 application.yml dq.scan.* 一致),customized=false
        HttpResponse<String> initial = get("/api/system-settings/scan");
        assertEquals(200, initial.statusCode(), initial.body());
        assertTrue(initial.body().contains("\"workers\":8"), initial.body());
        assertTrue(initial.body().contains("\"chunksPerTable\":100"), initial.body());
        assertTrue(initial.body().contains("\"sizeThresholdBytes\":10737418240"), initial.body());
        assertTrue(initial.body().contains("\"customized\":false"), initial.body());

        // 保存:自定义值生效,未提交字段保留默认
        HttpResponse<String> saved = send("PUT", "/api/system-settings/scan",
                "{\"workers\":16,\"chunksPerTable\":200}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(saved.body().contains("\"workers\":16"), saved.body());
        assertTrue(saved.body().contains("\"chunksPerTable\":200"), saved.body());
        assertTrue(saved.body().contains("\"customized\":true"), saved.body());

        // 恢复默认:回到配置文件值
        HttpResponse<String> reset = send("DELETE", "/api/system-settings/scan", null);
        assertEquals(200, reset.statusCode(), reset.body());
        assertTrue(reset.body().contains("\"workers\":8"), reset.body());
        assertTrue(reset.body().contains("\"customized\":false"), reset.body());
    }

    @Test
    void AI配置测试连接端点() throws Exception {
        activateLicense();
        // 配置不完整(无 key,且无已存/默认配置):409 提示,不发起真实调用
        HttpResponse<String> bad = send("POST", "/api/ai-config/test",
                "{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"m\"}");
        assertEquals(409, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("填写完整"), bad.body());

        // 完整配置:本地桩服务返回 200 → 连接成功
        com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/chat/completions", ex -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"p\"}}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        try {
            HttpResponse<String> ok = send("POST", "/api/ai-config/test",
                    "{\"baseUrl\":\"http://127.0.0.1:" + stub.getAddress().getPort() + "/v1\","
                            + "\"apiKey\":\"k\",\"model\":\"m\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(ok.body().contains("连接成功"), ok.body());
        } finally {
            stub.stop(0);
        }
    }
}
