package com.example.dq.web;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LogStreamAppender 单条长度截断验证:超大 message/stackTrace 在源头截断,
 * 防止错误风暴或超大 SQL 日志经环形缓冲 + SSE 把浏览器内存打爆。
 */
class LogStreamAppenderTest {

    private LogStreamAppender newAppender(LoggerContext ctx) {
        LogStreamAppender appender = new LogStreamAppender();
        appender.setContext(ctx);
        appender.start();
        return appender;
    }

    @Test
    void 超大消息被截断() {
        LoggerContext ctx = new LoggerContext();
        LogStreamAppender appender = newAppender(ctx);
        ch.qos.logback.classic.Logger logger = ctx.getLogger("t.truncate");
        logger.addAppender(appender);

        logger.info("x".repeat(10000));

        assertEquals(1, appender.getRecentEntries().size());
        LogEntry entry = appender.getRecentEntries().get(0);
        assertTrue(entry.message().length() < 4200, "message 应截断到 4000 附近,实际: " + entry.message().length());
        assertTrue(entry.message().contains("已截断"), "缺截断标记: " + entry.message().substring(entry.message().length() - 60));
        ctx.stop();
    }

    @Test
    void 短消息原样保留() {
        LoggerContext ctx = new LoggerContext();
        LogStreamAppender appender = newAppender(ctx);
        ch.qos.logback.classic.Logger logger = ctx.getLogger("t.keep");
        logger.addAppender(appender);

        logger.info("hello {}", "world");

        LogEntry entry = appender.getRecentEntries().get(0);
        assertEquals("hello world", entry.message());
        ctx.stop();
    }
}
