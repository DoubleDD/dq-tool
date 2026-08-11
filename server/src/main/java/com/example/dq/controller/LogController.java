package com.example.dq.controller;

import com.example.dq.web.LogEntry;
import com.example.dq.web.LogStreamAppender;
import io.javalin.http.sse.SseClient;

/**
 * 实时日志 SSE 端点:客户端连接后先推送缓冲区历史日志,再实时推送新日志。
 * 客户端断开时自动取消订阅,释放资源。
 */
public class LogController {

    private final LogStreamAppender appender;

    public LogController(LogStreamAppender appender) {
        this.appender = appender;
    }

    /** SSE 端点 /api/logs/stream:推送实时日志 */
    public void stream(SseClient sseClient) {
        sseClient.keepAlive();

        // 先推送历史日志(按时间顺序,每条作为独立的 SSE 事件)
        for (LogEntry entry : appender.getRecentEntries()) {
            sseClient.sendEvent(entry);
        }

        // 订阅实时日志流:每条新日志推送给客户端
        Runnable unsubscribe = appender.subscribe(entry -> {
            if (!sseClient.terminated()) {
                sseClient.sendEvent(entry);
            }
        });

        // 客户端断开时取消订阅
        sseClient.onClose(unsubscribe);
    }
}
