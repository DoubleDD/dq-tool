package com.example.dq.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

/**
 * 配置加载(去 Spring 后替代 @ConfigurationProperties 绑定):
 * 读 classpath:application.yml,绑定到 DqProperties/AiProperties;缺省值在各 Properties 类字段上。
 * 系统属性优先于 yml:dq.data-dir 由安装版打包脚本注入 -Ddq.data-dir(兼容圆点写法的 dq.data.dir)。
 * 本类不得打日志:logback 首次打日志即初始化,dq.data-dir 系统属性必须在任何日志输出前设置(main 负责)。
 */
public final class ConfigLoader {

    public record AppConfig(DqProperties dq, AiProperties ai, int serverPort, String dataDir) {
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
        dq.getLicense().setPublicKey(getString(yaml, "dq.license.public-key", dq.getLicense().getPublicKey()));
        dq.getDesktop().setShutdownTimeoutSeconds(getInt(yaml, "dq.desktop.shutdown-timeout-seconds",
                dq.getDesktop().getShutdownTimeoutSeconds()));

        AiProperties ai = new AiProperties();
        ai.setBaseUrl(getString(yaml, "ai.base-url", null));
        ai.setApiKey(getString(yaml, "ai.api-key", null));
        ai.setModel(getString(yaml, "ai.model", null));

        int serverPort = getInt(yaml, "server.port", 10000);
        String dataDir = firstNonBlank(
                System.getProperty("dq.data-dir"),
                System.getProperty("dq.data.dir"),
                getString(yaml, "dq.data-dir", "./data"));
        // 打包脚本注入的 -Ddq.data-dir=${user.home}/... 是字面量(原由 Spring 占位符解析),这里手动展开
        dataDir = dataDir.replace("${user.home}", System.getProperty("user.home"));
        return new AppConfig(dq, ai, serverPort, dataDir);
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
