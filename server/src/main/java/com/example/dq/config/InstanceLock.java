package com.example.dq.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 单实例保护(同一数据目录只允许一个 dq-tool 进程):
 * 历史上第二个实例会经 H2 AUTO_SERVER 作为 TCP client 连到旧实例内嵌的 H2 server,
 * 跨版本时类不兼容直接崩(2026-08 实测:新版 client 连旧版 server 报
 * org.h2.jdbc.meta.DatabaseMetaServer NoClassDefFoundError,启动失败卡在启动画面)。
 * 现在第二个实例改为:桌面安装版在「同一构建」(运行中实例前端指纹与本机一致,见
 * fetchRemoteFrontendHash)时把已有实例的窗口带出来并退出;构建不同(升级换包)时直接
 * 结束旧实例进程(killProcessListeningOn,按端口反查 PID)并等锁释放后继续本次启动——
 * 旧实例给不了新包的接口与前端资源,直接带窗口会资源 404 卡死,自动结束失败才弹窗提示
 * 用户手动结束;实例残留但端口无响应(找不到 PID)时弹窗提示;headless 报错退出。
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

    /**
     * 读取运行中实例首页(GET /,即其内嵌前端 index.html)内容的 SHA-256,用于与本进程
     * 内嵌前端指纹(BrowserOpener.frontendHash)比对:一致才是同一构建,才允许「带出已有
     * 实例窗口并退出」;不一致(升级换包,哪怕版本号相同但重新构建过)说明旧实例给不了
     * 新包的服务与前端资源。请求失败/超时/非 200 返回 null(按不同构建处理)。
     */
    public static String fetchRemoteFrontendHash(int port) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                    .timeout(Duration.ofMillis(800)).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
        } catch (Exception e) {
            // 连接拒绝/超时/响应中断一律按探测失败处理
            return null;
        }
    }

    /**
     * 关闭监听指定端口的进程(升级换包时结束不同构建的旧实例,随后 waitAcquire 等锁释放)。
     * 安全性:端口已经过 /api/license/status 探针与前端指纹两次确认是本工具旧实例
     * (见 findRunningInstancePort / fetchRemoteFrontendHash),按端口反查 PID 强杀不会误伤;
     * 进程死亡后 OS 自动释放实例锁/H2 锁,H2 本身崩溃安全。任何一步失败返回 false,
     * 由调用方回落到「提示用户手动结束进程」。
     */
    public static boolean killProcessListeningOn(int port) {
        try {
            List<Long> pids = findListeningPids(port);
            if (pids.isEmpty()) {
                StartupLog.log("按端口反查旧实例 PID 失败(port=" + port + "),无法自动关闭");
                return false;
            }
            boolean ok = true;
            for (long pid : pids) {
                StartupLog.log("结束旧实例进程 pid=" + pid + " (port=" + port + ")");
                ok &= killPid(pid);
            }
            return ok;
        } catch (Exception e) {
            StartupLog.log("按端口关闭旧实例进程失败(port=" + port + "): " + e);
            return false;
        }
    }

    /**
     * 旧实例被杀后等待其实例锁/H2 锁释放并重新获得锁;超时仍未获得返回 false。
     * 复用 acquire 的双文件锁检查,每 300ms 重试一次。
     */
    public static boolean waitAcquire(Path dataDir, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (acquire(dataDir) == Status.ACQUIRED) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 按平台反查监听指定 TCP 端口的进程 PID 列表(已去重:同一进程 IPv4/IPv6 双栈会命中多行) */
    private static List<Long> findListeningPids(int port) throws IOException, InterruptedException {
        if (isWindows()) {
            return parseNetstatListeningPids(runAndRead("netstat", "-ano"), port);
        }
        // macOS / Linux(桌面安装版只覆盖这两类 + Windows;lsof 缺失时异常由调用方按失败处理);
        // LinkedHashSet 去重:同一进程多个 socket 监听同端口会输出多行,重复 kill 第二次必失败
        java.util.Set<Long> pids = new java.util.LinkedHashSet<>();
        for (String line : runAndRead("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t").split("\\R")) {
            if (!line.isBlank()) {
                pids.add(Long.parseLong(line.trim()));
            }
        }
        return new ArrayList<>(pids);
    }

    /**
     * 解析 Windows netstat -ano 输出,取本地地址为指定端口且 LISTENING 的行的 PID。
     * 行格式: TCP    127.0.0.1:10000      0.0.0.0:0              LISTENING       12345
     * 独立成静态方法以便单测(cmd 输出解析易错)。
     */
    static List<Long> parseNetstatListeningPids(String netstatOutput, int port) {
        // LinkedHashSet 去重:同一进程同时监听 IPv4/IPv6 双栈会出现两行,重复 kill 第二次必失败
        java.util.Set<Long> pids = new java.util.LinkedHashSet<>();
        for (String line : netstatOutput.split("\\R")) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 5 || !"LISTENING".equalsIgnoreCase(t[3])) {
                continue;
            }
            String localAddr = t[1];
            String localPort = localAddr.substring(localAddr.lastIndexOf(':') + 1);
            if (String.valueOf(port).equals(localPort)) {
                pids.add(Long.parseLong(t[t.length - 1]));
            }
        }
        return new ArrayList<>(pids);
    }

    private static boolean killPid(long pid) throws IOException, InterruptedException {
        Process process = isWindows()
                ? new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid)).start()
                : new ProcessBuilder("kill", "-9", String.valueOf(pid)).start();
        return process.waitFor() == 0;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String runAndRead(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
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
