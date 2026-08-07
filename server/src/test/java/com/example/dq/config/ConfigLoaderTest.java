package com.example.dq.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 授权公钥文件路径解析:classpath 资源 / 外部文件 / 读不到报错 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void 默认配置从classpath公钥文件加载() {
        ConfigLoader.AppConfig config = ConfigLoader.load();
        assertEquals("classpath:license-public.key", config.dq().getLicense().getPublicKeyFile());
        String expected = ConfigLoader.readKeyFile("classpath:license-public.key");
        assertTrue(expected.startsWith("MCow"), expected);
        assertEquals(expected, config.dq().getLicense().getPublicKey());
    }

    @Test
    void 外部文件路径读取并去首尾空白() throws Exception {
        Path keyFile = tempDir.resolve("my-public.key");
        Files.writeString(keyFile, "  MCowBQYDK2VwAyEATESTKEY==\n");
        assertEquals("MCowBQYDK2VwAyEATESTKEY==", ConfigLoader.readKeyFile(keyFile.toString()));
    }

    @Test
    void 文件不存在时抛状态异常() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.readKeyFile(tempDir.resolve("missing.key").toString()));
        assertTrue(e.getMessage().contains("读取密钥文件失败"), e.getMessage());

        assertThrows(IllegalStateException.class,
                () -> ConfigLoader.readKeyFile("classpath:not-exists.key"));
    }
}
