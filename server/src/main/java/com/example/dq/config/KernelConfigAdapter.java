package com.example.dq.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * server 配置(ConfigLoader 读 application.yml)→ 共享内核 AppConfig 的映射适配。
 * 内核 AppConfig 是 Kotlin data class,Java 侧只能走全参构造(默认值仅 Kotlin 可见),此处逐字段显式映射。
 */
public final class KernelConfigAdapter {

    private KernelConfigAdapter() {
    }

    public static AppConfig toKernelConfig(ConfigLoader.AppConfig config) {
        DqProperties dq = config.dq();
        DqProperties.Scan s = dq.getScan();
        ScanConfig scan = new ScanConfig(
                s.getWorkers(),
                s.getChunksPerTable(),
                s.getRowThreshold(),
                s.getSizeThresholdBytes(),
                s.getSampleRows(),
                s.getStatementTimeoutSeconds());
        AiProperties ai = config.ai();
        AiDefaults aiDefaults = new AiDefaults(
                nullToEmpty(ai.getApiKey()),
                nullToEmpty(ai.getBaseUrl()),
                nullToEmpty(ai.getModel()));

        Path dataDir = Path.of(config.dataDir());
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new IllegalStateException("创建数据目录失败: " + dataDir, e);
        }

        return new AppConfig(
                dataDir,
                scan,
                dq.getSecurity().getSecret(),
                aiDefaults,
                nullToEmpty(dq.getLicense().getPublicKey()),
                nullToEmpty(dq.getLicense().getPrivateKey()),
                nullToEmpty(config.appVersion()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
