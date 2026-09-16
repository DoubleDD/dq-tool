package com.example.dq.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.example.dq.model.ErrorEvent;
import com.example.dq.model.ErrorLevel;
import com.example.dq.model.ErrorSource;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 错误采集 Appender:把 Logback 日志事件里的「真正的错误」转成 {@link ErrorEvent} 交给错误中心入库。
 *
 * <p>采集口径(与「运行日志」页的 SSE 全量流区分,这里只挑错误):
 * <ul>
 *   <li>ERROR 级别全部采集;</li>
 *   <li>WARN 级别仅在带异常(throwable)时采集 —— 降级/业务提醒类 warn 不污染错误中心;</li>
 *   <li>异常链含 {@link SQLException} 记为数据库错误,其余为后端错误;</li>
 *   <li>跳过内部 logger({@code dq.error-center.internal})与自身,避免采集失败自我循环。</li>
 * </ul>
 *
 * <p>与 {@link LogStreamAppender} 一样在 {@link WebServer} 构造时编程式挂到 root logger。
 * 内核(H2 建表/迁移)未就绪前 sink 为空,此时事件进有界缓冲;{@link #setSink} 注入后回灌,
 * 保证启动期异常不丢。
 */
public class ErrorCaptureAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** 错误中心内部 logger:采集失败的自诊断日志,绝不能反向采集(否则自噬) */
    private static final String INTERNAL_LOGGER = "dq.error-center.internal";

    private static final String SELF_LOGGER = ErrorCaptureAppender.class.getName();

    /** sink 注入前的有界缓冲:启动早期(内核未就绪)产生的错误 */
    private static final int MAX_PENDING = 1000;

    private final Deque<ErrorEvent> pending = new ArrayDeque<>();

    /** 错误中心入库出口;由 WebServer.finishInit 在内核就绪后注入 */
    private volatile Consumer<ErrorEvent> sink;

    @Override
    protected void append(ILoggingEvent event) {
        if (!shouldCollect(event)) {
            return;
        }
        ErrorEvent entry = toEvent(event);
        if (entry == null) {
            return;
        }
        Consumer<ErrorEvent> current = sink;
        if (current != null) {
            safeAccept(current, entry);
            return;
        }
        synchronized (pending) {
            // 双检:setSink 在同一把锁内赋值并清空缓冲,不存在「已注入却仍入缓冲」的窗口
            if (sink != null) {
                safeAccept(sink, entry);
                return;
            }
            if (pending.size() >= MAX_PENDING) {
                pending.pollFirst();
            }
            pending.addLast(entry);
        }
    }

    /**
     * 注入错误中心入库出口,并回灌此前缓冲的事件(由内核就绪后的 finishInit 调用)。
     */
    public void setSink(Consumer<ErrorEvent> sink) {
        List<ErrorEvent> toFlush;
        synchronized (pending) {
            this.sink = sink;
            toFlush = new ArrayList<>(pending);
            pending.clear();
        }
        for (ErrorEvent e : toFlush) {
            safeAccept(sink, e);
        }
    }

    /** 采集失败绝不影响日志链路本身 */
    private void safeAccept(Consumer<ErrorEvent> sink, ErrorEvent entry) {
        try {
            sink.accept(entry);
        } catch (Exception ignored) {
            // 错误中心自身故障不能反过来影响业务日志输出
        }
    }

    private boolean shouldCollect(ILoggingEvent event) {
        String logger = event.getLoggerName();
        if (logger == null) {
            return false;
        }
        if (logger.equals(INTERNAL_LOGGER) || logger.startsWith(INTERNAL_LOGGER)
                || logger.equals(SELF_LOGGER) || logger.startsWith(SELF_LOGGER)) {
            return false;
        }
        Level level = event.getLevel();
        if (level == Level.ERROR) {
            return true;
        }
        // WARN 只在带异常时采集:预期内降级(授权拦截/方言降级等)只写 warn 不带栈,不进错误中心
        return level == Level.WARN && event.getThrowableProxy() != null;
    }

    private ErrorEvent toEvent(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        String message = event.getFormattedMessage();
        String kind;
        if (throwable != null) {
            kind = throwable.getClassName();
        } else {
            // 无异常的 ERROR 日志:用 logger 末段当类型,便于分组
            String loggerName = event.getLoggerName();
            kind = loggerName == null ? "ERROR" : loggerName.substring(loggerName.lastIndexOf('.') + 1);
        }
        try {
            return new ErrorEvent(
                    isDatabaseError(throwable) ? ErrorSource.DATABASE : ErrorSource.BACKEND,
                    kind,
                    ErrorLevel.valueOf(event.getLevel().toString()),
                    message,
                    formatStackTrace(throwable),
                    null,
                    event.getLoggerName(),
                    event.getThreadName(),
                    null,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault())
            );
        } catch (Exception e) {
            // 例如非标准级别(OFF/ALL),跳过而不是让日志链路抛错
            return null;
        }
    }

    /** 异常链中任一环是 SQLException 即视为数据库错误(业务库/H2 的 SQL 失败) */
    private boolean isDatabaseError(IThrowableProxy proxy) {
        IThrowableProxy current = proxy;
        int guard = 0;
        while (current != null && guard++ < 50) {
            if (SQLException.class.getName().equals(current.getClassName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 与 Logback 默认输出一致的堆栈文本(含 Caused by 链) */
    private String formatStackTrace(IThrowableProxy proxy) {
        if (proxy == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendThrowable(sb, proxy, "");
        return sb.toString();
    }

    private void appendThrowable(StringBuilder sb, IThrowableProxy proxy, String indent) {
        sb.append(indent).append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
        for (StackTraceElementProxy ste : proxy.getStackTraceElementProxyArray()) {
            // StackTraceElementProxy.toString() 自带 "at " 前缀,不要再补,否则输出 "at at ..."
            sb.append(indent).append('\t').append(ste).append('\n');
        }
        if (proxy.getCause() != null) {
            sb.append(indent).append("Caused by: ");
            appendThrowable(sb, proxy.getCause(), indent);
        }
    }
}
