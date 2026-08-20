package com.example.dq.config;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 启动早期日志(纯 JDK 实现,不依赖 logback/slf4j):
 * Windows 安装版无控制台,双击启动后从进程创建到 logback 初始化之间(读配置、端口探测、
 * 托盘与启动画面)以及任何未捕获异常导致的闪退/卡死,都没有任何记录,排障无从下手。
 * 本类在 main 第一行即初始化,直接向 日志目录/startup.log 追加带时间戳的启动步骤日志;
 * logback 就绪后的业务日志仍在同目录 dq-tool.log,两者互补。
 * 所有方法绝不抛异常:日志失败只允许降级到标准输出,不能影响启动流程。
 */
public final class StartupLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static BufferedWriter writer;
    private static Path file;

    // ---- 启动耗时统计:t0 为 init 时刻(main 第一行),mark 记录阶段打点,logTiming 输出汇总 ----
    private static long startNanos;
    private static long lastMarkNanos;

    private record StageMark(String name, long millis) {
    }

    private static final java.util.List<StageMark> marks = new java.util.ArrayList<>();

    private StartupLog() {
    }

    /**
     * 记录一个启动阶段的完成打点(阶段名 + 距上一阶段的耗时);只累计不输出,
     * 由 logTiming 在启动结束时统一输出汇总。只在 main 线程调用(并行线程的耗时单独 log)。
     */
    public static synchronized void mark(String stage) {
        long now = System.nanoTime();
        if (lastMarkNanos == 0) {
            // init 未先于 mark 调用(理论上不会发生),以首次打点为基准
            startNanos = now;
            lastMarkNanos = now;
        }
        marks.add(new StageMark(stage, (now - lastMarkNanos) / 1_000_000));
        lastMarkNanos = now;
    }

    /** 输出启动耗时统计汇总(表格:各阶段耗时 + 自 main 起的总耗时);启动失败路径同样可调用 */
    public static synchronized void logTiming() {
        if (marks.isEmpty()) {
            return;
        }
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
        // 中文双宽:列宽按显示宽度算,否则表格边框对不齐
        int nameWidth = displayWidth("阶段");
        int timeWidth = displayWidth("耗时");
        for (StageMark m : marks) {
            nameWidth = Math.max(nameWidth, displayWidth(m.name()));
            timeWidth = Math.max(timeWidth, displayWidth(m.millis() + "ms"));
        }
        StringBuilder table = new StringBuilder();
        String top = "┌" + "─".repeat(nameWidth + 2) + "┬" + "─".repeat(timeWidth + 2) + "┐";
        String mid = "├" + "─".repeat(nameWidth + 2) + "┼" + "─".repeat(timeWidth + 2) + "┤";
        String bottom = "└" + "─".repeat(nameWidth + 2) + "┴" + "─".repeat(timeWidth + 2) + "┘";
        table.append(top).append('\n');
        table.append(row("阶段", "耗时", nameWidth, timeWidth)).append('\n');
        table.append(mid).append('\n');
        for (StageMark m : marks) {
            table.append(row(m.name(), m.millis() + "ms", nameWidth, timeWidth)).append('\n');
        }
        table.append(mid).append('\n');
        table.append(row("总计(自 main 起)", totalMs + "ms", nameWidth, timeWidth)).append('\n');
        table.append(bottom);
        log("启动耗时统计(main 线程):" + System.lineSeparator() + table);
        marks.clear();
    }

    private static String row(String name, String time, int nameWidth, int timeWidth) {
        return "│ " + padDisplay(name, nameWidth) + " │ " + padDisplay(time, timeWidth) + " │";
    }

    /** 显示宽度:CJK/全角字符按 2 列计(等宽字体/终端下对齐用) */
    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            width += c > 0x2E7F ? 2 : 1;
        }
        return width;
    }

    /** 按显示宽度右侧补空格 */
    private static String padDisplay(String s, int width) {
        int pad = width - displayWidth(s);
        return pad > 0 ? s + " ".repeat(pad) : s;
    }

    /**
     * 日志目录解析规则(全程序单点,DqApplication/shell 设置 logback 的 dq.log-dir 也走这里):
     * 数据目录同级的 logs/ —— ./data → ./logs,~/.dq-tool/data → ~/.dq-tool/logs。
     */
    public static Path logDirFor(String dataDir) {
        return Path.of(expandUserHome(dataDir)).resolveSibling("logs");
    }

    /**
     * 在 main 第一行调用:按 dq.data-dir 系统属性(打包脚本注入)定位数据目录,
     * 在其同级 logs/ 打开 startup.log(追加)。
     * 此时 application.yml 还没读,目录解析与 ConfigLoader 保持同一优先级(系统属性 → ./data),
     * 配置加载完成后再用 adoptDataDir 校正;目录不可写时依次降级到 ~/.dq-tool/logs、临时目录。
     */
    public static synchronized void init() {
        if (writer != null) {
            return;
        }
        // 耗时统计基准:main 第一行,近似进程启动时刻(不含 JVM 引导)
        startNanos = System.nanoTime();
        lastMarkNanos = startNanos;
        String dir = firstNonBlank(System.getProperty("dq.data-dir"), System.getProperty("dq.data.dir"), "./data");
        open(logDirFor(dir));
        if (writer == null) {
            open(Path.of(System.getProperty("user.home", "."), ".dq-tool", "logs"));
        }
        if (writer == null) {
            open(Path.of(System.getProperty("java.io.tmpdir"), "dq-tool", "logs"));
        }
        log("---------- dq-tool 启动日志 pid=" + ProcessHandle.current().pid() + " ----------");
        log("运行环境: java=" + System.getProperty("java.version")
                + ", os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "/" + System.getProperty("os.arch")
                + ", user.dir=" + System.getProperty("user.dir")
                + ", java.awt.headless=" + System.getProperty("java.awt.headless"));
    }

    /** 配置加载完成后调用:若最终数据目录与早期定位不一致,切换 startup.log 到正确位置 */
    public static synchronized void adoptDataDir(String dataDir) {
        Path target = logDirFor(dataDir).resolve("startup.log");
        if (writer == null || target.equals(file)) {
            return;
        }
        log("配置确认数据目录为 " + dataDir + ",startup.log 切换到 " + target);
        closeQuietly();
        open(logDirFor(dataDir));
    }

    /** 当前日志文件位置(可能为 null,表示只能输出到控制台),用于排障提示 */
    public static synchronized Path file() {
        return file;
    }

    public static void log(String message) {
        String line = TS.format(LocalDateTime.now()) + " [" + Thread.currentThread().getName() + "] " + message;
        System.out.println(line);
        write(line);
    }

    public static void log(String message, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log(message + ": " + t + System.lineSeparator() + sw);
    }

    private static synchronized void write(String line) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // 写日志失败不再尝试修复,避免影响启动
        }
    }

    private static void open(Path logDir) {
        try {
            Files.createDirectories(logDir);
            Path target = logDir.resolve("startup.log");
            writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            file = target;
        } catch (Exception e) {
            writer = null;
            file = null;
            System.out.println("[dq-tool] startup.log 打开失败(" + logDir + "): " + e.getMessage());
        }
    }

    private static void closeQuietly() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
            // 关闭失败无影响
        }
        writer = null;
    }

    private static String expandUserHome(String dir) {
        return dir.replace("${user.home}", System.getProperty("user.home", "."));
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
