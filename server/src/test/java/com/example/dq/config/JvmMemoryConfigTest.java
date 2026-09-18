package com.example.dq.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 最大内存设置:落 config.properties(dq.jvm.xmx-mb),未设置/非法值回落默认,保存保留文件里其他键 */
class JvmMemoryConfigTest {

    @TempDir
    Path dataDir;

    @Test
    void 未设置时返回默认值() {
        assertThat(JvmMemoryConfig.readMb(dataDir)).isEqualTo(JvmMemoryConfig.DEFAULT_MB);
    }

    @Test
    void 保存后可读回且保留其他键() throws Exception {
        Files.writeString(JvmMemoryConfig.file(dataDir), "dq.scan.workers=16\n");
        assertThat(JvmMemoryConfig.writeMb(dataDir, 2048)).isEqualTo(2048);
        assertThat(JvmMemoryConfig.readMb(dataDir)).isEqualTo(2048);
        assertThat(Files.readString(JvmMemoryConfig.file(dataDir))).contains("dq.scan.workers=16");
    }

    @Test
    void 超范围按边界收敛() throws Exception {
        assertThat(JvmMemoryConfig.writeMb(dataDir, 128)).isEqualTo(JvmMemoryConfig.MIN_MB);
        assertThat(JvmMemoryConfig.writeMb(dataDir, 65536)).isEqualTo(JvmMemoryConfig.MAX_MB);
    }

    @Test
    void 非法值回落默认() throws Exception {
        Files.writeString(JvmMemoryConfig.file(dataDir), "dq.jvm.xmx-mb=abc\n");
        assertThat(JvmMemoryConfig.readMb(dataDir)).isEqualTo(JvmMemoryConfig.DEFAULT_MB);
        Files.writeString(JvmMemoryConfig.file(dataDir), "dq.jvm.xmx-mb=99999\n");
        assertThat(JvmMemoryConfig.readMb(dataDir)).isEqualTo(JvmMemoryConfig.DEFAULT_MB);
    }
}
