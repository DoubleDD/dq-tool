package com.example.dq.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单实例保护:同 JVM 内重复获取实例锁(模拟另一进程持有)应判 ALREADY_RUNNING;
 * H2 库锁被占(旧版本实例场景)同样判 ALREADY_RUNNING;锁释放后可再次获得。
 */
class InstanceLockTest {

    @TempDir
    Path dataDir;

    @Test
    void 首次获取成功且创建锁文件() {
        assertThat(InstanceLock.acquire(dataDir)).isEqualTo(InstanceLock.Status.ACQUIRED);
        assertThat(dataDir.resolve("dq-tool.instance.lock")).exists();
    }

    @Test
    void 实例锁已被持有时判已有实例() throws Exception {
        // 同 JVM 对同一文件重复 tryLock 抛 OverlappingFileLockException,等价于另一进程持有
        try (FileChannel channel = FileChannel.open(dataDir.resolve("dq-tool.instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertThat(InstanceLock.acquire(dataDir)).isEqualTo(InstanceLock.Status.ALREADY_RUNNING);
        }
    }

    @Test
    void H2库锁被占时判已有实例() throws Exception {
        // 旧版本实例没有 instance.lock,但 H2 运行时独占 dqconfig.lock.db
        try (FileChannel channel = FileChannel.open(dataDir.resolve("dqconfig.lock.db"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertThat(InstanceLock.acquire(dataDir)).isEqualTo(InstanceLock.Status.ALREADY_RUNNING);
        }
    }

    @Test
    void H2锁文件存在但未占用时可正常获取() throws Exception {
        // 上次正常退出后锁文件残留(锁已释放),不应误判
        Files.writeString(dataDir.resolve("dqconfig.lock.db"), "");
        assertThat(InstanceLock.acquire(dataDir)).isEqualTo(InstanceLock.Status.ACQUIRED);
    }
}
