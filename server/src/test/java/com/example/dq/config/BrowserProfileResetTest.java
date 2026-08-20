package com.example.dq.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BrowserOpener.resetProfileIfFrontendChanged:前端 hash 变化时清理浏览器配置目录(含 HTTP 缓存)。
 * 覆盖:hash 不一致删目录并更新标记、hash 一致不动、标记缺失(升级后首次启动)按变化处理。
 */
class BrowserProfileResetTest {

    @TempDir
    Path dir;

    private Path profileDir() {
        return dir.resolve("browser-profile");
    }

    private Path marker() {
        return dir.resolve("browser-profile.frontend-hash");
    }

    @Test
    void hash不一致时删除整个配置目录并更新标记() throws Exception {
        Files.createDirectories(profileDir().resolve("Cache"));
        Files.writeString(profileDir().resolve("Cache/index.html"), "旧缓存");
        Files.writeString(marker(), "old-hash");

        boolean cleaned = BrowserOpener.resetProfileIfFrontendChanged(profileDir(), marker(), "new-hash");

        assertTrue(cleaned);
        assertFalse(Files.exists(profileDir()), "配置目录应被整体删除");
        assertEquals("new-hash", Files.readString(marker()), "标记应更新为新 hash");
    }

    @Test
    void hash一致时不动配置目录() throws Exception {
        Files.createDirectories(profileDir());
        Files.writeString(profileDir().resolve("keep.txt"), "保留");
        Files.writeString(marker(), "same-hash");

        boolean cleaned = BrowserOpener.resetProfileIfFrontendChanged(profileDir(), marker(), "same-hash");

        assertFalse(cleaned);
        assertTrue(Files.exists(profileDir().resolve("keep.txt")), "配置目录应保持不变");
    }

    @Test
    void 标记缺失视为已变化按升级处理() throws Exception {
        Files.createDirectories(profileDir());
        Files.writeString(profileDir().resolve("stale.txt"), "旧版本缓存");

        boolean cleaned = BrowserOpener.resetProfileIfFrontendChanged(profileDir(), marker(), "any-hash");

        assertTrue(cleaned);
        assertFalse(Files.exists(profileDir()));
        assertEquals("any-hash", Files.readString(marker()));
    }

    @Test
    void 配置目录不存在时只写标记不报错() throws Exception {
        boolean cleaned = BrowserOpener.resetProfileIfFrontendChanged(profileDir(), marker(), "any-hash");

        assertTrue(cleaned, "标记缺失即视为变化(只是没有目录可删)");
        assertEquals("any-hash", Files.readString(marker()));
    }
}
