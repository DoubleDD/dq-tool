package com.example.dq.web;

/**
 * 日志条目:Logback 事件的结构化表示,通过 SSE 以 JSON 推送给前端。
 *
 * @param timestamp  时间戳(ISO 8601 带时区)
 * @param level      日志级别(TRACE/DEBUG/INFO/WARN/ERROR)
 * @param thread     线程名
 * @param logger     logger 名称(通常是类全限定名)
 * @param message    格式化后的日志消息
 * @param stackTrace 异常堆栈(无异常时为 null)
 */
public record LogEntry(
        String timestamp,
        String level,
        String thread,
        String logger,
        String message,
        String stackTrace
) {}
