package com.example.dq.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 系统设置「最大内存」的持久化:落数据目录 config.properties(dq.jvm.xmx-mb,单位 MB)。
 * JVM 堆启动后不可调,该值必须在 JVM 启动前可读——Tauri 拉起方(Rust)与本进程自我重启
 * (DqApplication.maybeReexecForXmx)都直接读这个文件,因此不放 H2 system_settings。
 */
public final class JvmMemoryConfig {

    public static final String KEY = "dq.jvm.xmx-mb";
    public static final int DEFAULT_MB = 1024;
    public static final int MIN_MB = 512;
    public static final int MAX_MB = 8192;

    private JvmMemoryConfig() {
    }

    public static Path file(Path dataDir) {
        return dataDir.resolve("config.properties");
    }

    /** 读取设置的最大内存(MB);未设置/非法值/超出范围一律回落默认(与 Tauri Rust 侧口径一致) */
    public static int readMb(Path dataDir) {
        String raw = load(dataDir).getProperty(KEY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MB;
        }
        try {
            int mb = Integer.parseInt(raw.trim());
            return mb < MIN_MB || mb > MAX_MB ? DEFAULT_MB : mb;
        } catch (NumberFormatException e) {
            return DEFAULT_MB;
        }
    }

    /** 保存(入参按范围收敛后落盘),保留文件里其他键;返回实际生效值 */
    public static int writeMb(Path dataDir, int mb) throws IOException {
        int clamped = clamp(mb);
        Properties props = load(dataDir);
        props.setProperty(KEY, String.valueOf(clamped));
        Files.createDirectories(dataDir);
        try (OutputStream out = Files.newOutputStream(file(dataDir))) {
            props.store(out, "dq-tool config (managed by system settings page)");
        }
        return clamped;
    }

    public static int clamp(int mb) {
        return Math.max(MIN_MB, Math.min(MAX_MB, mb));
    }

    private static Properties load(Path dataDir) {
        Properties props = new Properties();
        Path f = file(dataDir);
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                props.load(in);
            } catch (IOException ignored) {
                // 读失败按空配置处理,不影响启动
            }
        }
        return props;
    }
}
