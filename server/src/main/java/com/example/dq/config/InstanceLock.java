package com.example.dq.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * 单实例保护(同一数据目录只允许一个 dq-tool 进程):
 * 历史上第二个实例会经 H2 AUTO_SERVER 作为 TCP client 连到旧实例内嵌的 H2 server,
 * 跨版本时类不兼容直接崩(2026-08 实测:新版 client 连旧版 server 报
 * org.h2.jdbc.meta.DatabaseMetaServer NoClassDefFoundError,启动失败卡在启动画面)。
 * 现在第二个实例改为:桌面安装版把已有实例的窗口带出来并退出;headless 报错退出。
 *
 * 检测用两把文件锁(OS 级,进程死亡自动释放,不会有残留误报):
 * - dq-tool.instance.lock:本机制自有锁,获得后持有到进程结束(JVM 退出 OS 自动释放)
 * - dqconfig.lock.db:H2 库锁。旧版本实例没有 instance.lock,但 H2 运行时独占它,
 *   探测它能识别「旧版本残留进程」这一最需要防护的场景
 */
public final class InstanceLock {

    public enum Status {ACQUIRED, ALREADY_RUNNING}

    /** H2 库锁文件名,与 AppConfig.h2JdbcUrl 的库名(dqconfig)对应,改库名时同步 */
    private static final String H2_LOCK_FILE = "dqconfig.lock.db";
    private static final String INSTANCE_LOCK_FILE = "dq-tool.instance.lock";
    private static final int PORT_SCAN_RANGE = 100;

    /** 持有的实例锁(静态强引用防 GC 关闭 channel;永不显式释放,JVM 退出时 OS 回收) */
    private static FileChannel heldChannel;
    private static FileLock heldLock;

    private InstanceLock() {
    }

    /**
     * 尝试获得数据目录的实例锁。ACQUIRED = 本进程是唯一实例,正常启动;
     * ALREADY_RUNNING = 同数据目录已有实例在运行。检查本身失败(目录不可写等)按 ACQUIRED
     * 放行:宁可是原来的 AUTO_SERVER 行为,也不能让锁检查本身挡住启动。
     */
    public static synchronized Status acquire(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            FileChannel channel = FileChannel.open(dataDir.resolve(INSTANCE_LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // 同 JVM 已持有(理论上只调一次;单测重复获取场景)
                return Status.ALREADY_RUNNING;
            }
            if (lock == null) {
                closeQuietly(channel);
                return Status.ALREADY_RUNNING;
            }
            // 旧版本实例没有 instance.lock,靠 H2 库锁识别(探测即释放,不影响后续 H2 自己加锁)
            Path h2Lock = dataDir.resolve(H2_LOCK_FILE);
            if (Files.exists(h2Lock)) {
                try (FileChannel h2Channel = FileChannel.open(h2Lock, StandardOpenOption.WRITE);
                     FileLock h2Probe = h2Channel.tryLock()) {
                    if (h2Probe == null) {
                        lock.release();
                        closeQuietly(channel);
                        return Status.ALREADY_RUNNING;
                    }
                } catch (OverlappingFileLockException e) {
                    lock.release();
                    closeQuietly(channel);
                    return Status.ALREADY_RUNNING;
                }
            }
            heldChannel = channel;
            heldLock = lock;
            return Status.ACQUIRED;
        } catch (IOException e) {
            StartupLog.log("实例锁检查失败(按无实例继续启动): " + e);
            return Status.ACQUIRED;
        }
    }

    /**
     * 探测本机正在运行的 dq-tool 实例端口:从期望端口向后扫(与端口避让口径一致),
     * /api/license/status 返回 200 即视为本工具实例(该路径足够特异,不会误判其他程序)。
     * 找不到返回 -1(调用方回落到期望端口)。
     */
    public static int findRunningInstancePort(int configuredPort) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        for (int port = configuredPort; port <= configuredPort + PORT_SCAN_RANGE; port++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/license/status"))
                        .timeout(Duration.ofMillis(500)).GET().build();
                if (client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return port;
                }
            } catch (Exception ignored) {
                // 端口未监听/非本工具,继续探测下一个
            }
        }
        return -1;
    }

    private static Path Paths(Path dir, String name) {
        return dir.resolve(name);
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // 关闭失败无影响
        }
    }
}
