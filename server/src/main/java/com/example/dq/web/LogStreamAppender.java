package com.example.dq.web;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 实时日志流 Appender:捕获 Logback 日志事件,格式化为 {@link LogEntry} 后推送给所有 SSE 订阅者。
 * <p>
 * 同时维护一个有界环形缓冲区(默认 500 条),SSE 客户端连接时先推送历史日志,再实时推送新日志。
 * <p>
 * 该 Appender 在 {@link WebServer} 构造时以编程方式挂载到 root logger,
 * 不在 logback.xml 中声明(避免反射实例化导致无法获取引用)。
 */
public class LogStreamAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault());

    private static final int MAX_BUFFER = 500;

    /** 环形缓冲区:最近 MAX_BUFFER 条日志,SSE 连接时先推送这部分 */
    private final Deque<LogEntry> buffer = new ConcurrentLinkedDeque<>();

    /** SSE 订阅者列表:每条新日志会推送给所有订阅者 */
    private final List<Consumer<LogEntry>> subscribers = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
        String ts = TS_FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        String level = event.getLevel().toString();
        String thread = event.getThreadName();
        String logger = event.getLoggerName();
        String message = event.getFormattedMessage();
        String stackTrace = formatStackTrace(event.getThrowableProxy());

        LogEntry entry = new LogEntry(ts, level, thread, logger, message, stackTrace);

        // 环形缓冲区:超限时淘汰最旧
        buffer.addLast(entry);
        while (buffer.size() > MAX_BUFFER) {
            buffer.pollFirst();
        }

        // 推送给所有 SSE 订阅者
        for (Consumer<LogEntry> sub : subscribers) {
            try {
                sub.accept(entry);
            } catch (Exception ignored) {
                // 单个订阅者异常不影响其他订阅者(订阅者自行处理连接断开)
            }
        }
    }

    /** 将异常代理转为多行字符串(与 Logback 默认输出一致) */
    private String formatStackTrace(IThrowableProxy proxy) {
        if (proxy == null) return null;
        StringBuilder sb = new StringBuilder();
        appendThrowable(sb, proxy, "");
        return sb.toString();
    }

    private void appendThrowable(StringBuilder sb, IThrowableProxy proxy, String indent) {
        sb.append(indent).append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
        for (StackTraceElementProxy ste : proxy.getStackTraceElementProxyArray()) {
            sb.append(indent).append("\tat ").append(ste.toString()).append('\n');
        }
        if (proxy.getCause() != null) {
            sb.append(indent).append("Caused by: ");
            appendThrowable(sb, proxy.getCause(), indent);
        }
    }

    /** 返回缓冲区快照(连接时先推送历史日志) */
    public List<LogEntry> getRecentEntries() {
        return List.copyOf(buffer);
    }

    /**
     * 订阅实时日志流。
     *
     * @param consumer 日志消费者
     * @return 取消订阅的 Runnable,在 SSE onClose 时调用
     */
    public Runnable subscribe(Consumer<LogEntry> consumer) {
        subscribers.add(consumer);
        return () -> subscribers.remove(consumer);
    }
}
