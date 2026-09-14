package com.example.dq.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 配置加载(去 Spring 后替代 @ConfigurationProperties 绑定):
 * 读 classpath:application.yml,绑定到 DqProperties/AiProperties;缺省值在各 Properties 类字段上。
 * 系统属性优先于 yml:dq.data-dir 由安装版打包脚本注入 -Ddq.data-dir(兼容圆点写法的 dq.data.dir)。
 * 本类不得打日志:logback 首次打日志即初始化,dq.data-dir 系统属性必须在任何日志输出前设置(main 负责)。
 */
public final class ConfigLoader {

    public record AppConfig(DqProperties dq, AiProperties ai, int serverPort, String dataDir, String appVersion) {
    }

    private ConfigLoader() {
    }

    public static AppConfig load() {
        Map<String, Object> yaml = readYaml();

        DqProperties dq = new DqProperties();
        DqProperties.Scan scan = dq.getScan();
        scan.setWorkers(getInt(yaml, "dq.scan.workers", scan.getWorkers()));
        scan.setChunksPerTable(getInt(yaml, "dq.scan.chunks-per-table", scan.getChunksPerTable()));
        scan.setRowThreshold(getLong(yaml, "dq.scan.row-threshold", scan.getRowThreshold()));
        scan.setSizeThresholdBytes(getLong(yaml, "dq.scan.size-threshold-bytes", scan.getSizeThresholdBytes()));
        scan.setSampleRows(getLong(yaml, "dq.scan.sample-rows", scan.getSampleRows()));
        scan.setStatementTimeoutSeconds(getInt(yaml, "dq.scan.statement-timeout-seconds", scan.getStatementTimeoutSeconds()));
        dq.getSecurity().setSecret(getString(yaml, "dq.security.secret", dq.getSecurity().getSecret()));
        String publicKeyFile = getString(yaml, "dq.license.public-key-file", null);
        if (publicKeyFile != null && !publicKeyFile.isBlank()) {
            dq.getLicense().setPublicKeyFile(publicKeyFile);
            dq.getLicense().setPublicKey(readKeyFile(publicKeyFile));
        } else {
            // 兼容旧配置:未配公钥文件路径时允许内联公钥
            dq.getLicense().setPublicKey(getString(yaml, "dq.license.public-key", dq.getLicense().getPublicKey()));
        }
        // 签发私钥:配置即管理员实例(开放授权码管理);私钥内容只进配置对象,绝不进日志/接口
        // 环境变量 DQ_LICENSE_PRIVATE_KEY_FILE 优先(本地调试注入,不影响 CI 打包),其次 yml
        String privateKeyFile = System.getenv("DQ_LICENSE_PRIVATE_KEY_FILE");
        String privateKeySource = "环境变量";
        if (privateKeyFile == null || privateKeyFile.isBlank()) {
            privateKeyFile = getString(yaml, "dq.license.private-key-file", null);
            privateKeySource = "application.yml";
        }
        if (privateKeyFile != null && !privateKeyFile.isBlank()) {
            dq.getLicense().setPrivateKeyFile(privateKeyFile);
            dq.getLicense().setPrivateKey(readKeyFile(privateKeyFile));
            StartupLog.log("已加载授权码签发私钥(来源:" + privateKeySource + "):" + privateKeyFile);
        }
        dq.getDesktop().setShutdownTimeoutSeconds(getInt(yaml, "dq.desktop.shutdown-timeout-seconds",
                dq.getDesktop().getShutdownTimeoutSeconds()));
        // 静态资源目录(磁盘):优先级 系统属性 -Ddq.web.static-dir(jpackage/Tauri 打包注入) > 环境变量 > yml;
        // 相对路径按工作目录解析(与 dq.data-dir 同口径),${user.home} 手动展开(同 dataDir)
        String staticDir = System.getProperty("dq.web.static-dir");
        if (staticDir == null || staticDir.isBlank()) {
            staticDir = System.getenv("DQ_WEB_STATIC_DIR");
        }
        if (staticDir == null || staticDir.isBlank()) {
            staticDir = getString(yaml, "dq.web.static-dir", "");
        }
        dq.getWeb().setStaticDir(staticDir == null ? ""
                : staticDir.replace("${user.home}", System.getProperty("user.home")).trim());
        // 浏览器访问管控令牌:三个来源合并去重,任一非空即开启门禁(纯 Web 层,不进内核)。
        // yml 供用户书签固定访问;-D / DQ_ACCESS_TOKEN 供启动注入(Tauri 每次随机生成)。token 值不落日志。
        String envToken = System.getenv("DQ_ACCESS_TOKEN");
        for (String candidate : new String[]{
                System.getProperty("dq.access-token", ""),
                envToken == null ? "" : envToken,
                getString(yaml, "dq.access-token", "")}) {
            String token = candidate.trim();
            if (!token.isBlank() && !dq.getAccessTokens().contains(token)) {
                dq.getAccessTokens().add(token);
            }
        }
        dq.getLan().setEnabled(getBoolean(yaml, "dq.lan.enabled", dq.getLan().isEnabled()));
        dq.getLan().setDiscoveryPort(getInt(yaml, "dq.lan.discovery-port", dq.getLan().getDiscoveryPort()));
        dq.getLan().setAnnounceIntervalSeconds(getInt(yaml, "dq.lan.announce-interval-seconds",
                dq.getLan().getAnnounceIntervalSeconds()));

        AiProperties ai = new AiProperties();
        ai.setBaseUrl(getString(yaml, "ai.base-url", null));
        ai.setApiKey(getString(yaml, "ai.api-key", null));
        ai.setModel(getString(yaml, "ai.model", null));
        ai.setPeakValleyEnabled(getBoolean(yaml, "ai.peak-valley-enabled", ai.isPeakValleyEnabled()));
        ai.setPeakInputPrice(getDouble(yaml, "ai.peak-input-price", ai.getPeakInputPrice()));
        ai.setPeakOutputPrice(getDouble(yaml, "ai.peak-output-price", ai.getPeakOutputPrice()));
        ai.setValleyInputPrice(getDouble(yaml, "ai.valley-input-price", ai.getValleyInputPrice()));
        ai.setValleyOutputPrice(getDouble(yaml, "ai.valley-output-price", ai.getValleyOutputPrice()));
        ai.setWorkPeriods(getString(yaml, "ai.work-periods", ai.getWorkPeriods()));
        ai.setWeekendValley(getBoolean(yaml, "ai.weekend-valley", ai.isWeekendValley()));

        int serverPort = getInt(yaml, "server.port", 10000);
        String dataDir = firstNonBlank(
                System.getProperty("dq.data-dir"),
                System.getProperty("dq.data.dir"),
                getString(yaml, "dq.data-dir", "./data"));
        // 打包脚本注入的 -Ddq.data-dir=${user.home}/... 是字面量(原由 Spring 占位符解析),这里手动展开
        dataDir = dataDir.replace("${user.home}", System.getProperty("user.home"));
        return new AppConfig(dq, ai, serverPort, dataDir, readAppVersion());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream("/application.yml")) {
            if (in == null) {
                return Map.of();
            }
            Object loaded = new Yaml().load(in);
            return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败: " + e.getMessage(), e);
        }
    }

    /** 读密钥文件(授权公钥/签发私钥):classpath: 前缀读 jar 内资源,否则按文件系统路径读(展开 ${user.home});读不到直接报错 */
    static String readKeyFile(String location) {
        String expanded = location.replace("${user.home}", System.getProperty("user.home"));
        try {
            if (expanded.startsWith("classpath:")) {
                String resource = expanded.substring("classpath:".length());
                if (!resource.startsWith("/")) {
                    resource = "/" + resource;
                }
                try (InputStream in = ConfigLoader.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IllegalStateException("读取密钥文件失败,classpath 资源不存在: " + location);
                    }
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
            }
            return Files.readString(Path.of(expanded)).trim();
        } catch (IOException e) {
            throw new IllegalStateException("读取密钥文件失败: " + location + " (" + e.getMessage() + ")", e);
        }
    }

    /** 软件版本号:构建期由 gradle 注入 classpath:/app-version.txt(已去 0. 前缀,与安装包版本一致);读不到回落 dev */
    private static String readAppVersion() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream("/app-version.txt")) {
            if (in == null) {
                return "dev";
            }
            String version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return version.isEmpty() ? "dev" : version;
        } catch (IOException e) {
            return "dev";
        }
    }

    @SuppressWarnings("unchecked")
    private static Object get(Map<String, Object> root, String path) {
        Object node = root;
        for (String key : path.split("\\.")) {
            if (!(node instanceof Map)) {
                return null;
            }
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }

    private static String getString(Map<String, Object> root, String path, String fallback) {
        Object value = get(root, path);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int getInt(Map<String, Object> root, String path, int fallback) {
        Object value = get(root, path);
        if (value == null) {
            return fallback;
        }
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString().trim());
    }

    private static double getDouble(Map<String, Object> root, String path, double fallback) {
        Object value = get(root, path);
        if (value == null) {
            return fallback;
        }
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim());
    }

    private static boolean getBoolean(Map<String, Object> root, String path, boolean fallback) {
        Object value = get(root, path);
        if (value == null) {
            return fallback;
        }
        return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString().trim());
    }

    private static long getLong(Map<String, Object> root, String path, long fallback) {
        Object value = get(root, path);
        if (value == null) {
            return fallback;
        }
        return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString().trim());
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "./data";
    }
}
