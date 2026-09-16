package com.example.dq.web;

import com.example.dq.model.ErrorLevel;
import com.example.dq.model.ErrorSource;
import com.example.dq.service.ErrorCenterService;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 错误中心静态出口:给「内核就绪之前/之后都可能触发」的壳层代码(未捕获线程异常处理器、
 * 启动阶段兜底、非注入式工具类)一个可空的安全上报入口。
 *
 * <p>内核未就绪时 {@link #get()} 为 null,调用方按「无错误中心」降级(仍写 startup.log);
 * 绑定由 {@link WebServer} 在 finishInit 注入内核后完成。
 */
public final class ErrorCenterHolder {

    private static final AtomicReference<ErrorCenterService> REF = new AtomicReference<>();

    private ErrorCenterHolder() {
    }

    /** 绑定内核错误中心服务(WebServer.finishInit 调用) */
    public static void bind(ErrorCenterService service) {
        REF.set(service);
    }

    /** 当前错误中心服务;内核未就绪时为 null */
    public static ErrorCenterService get() {
        return REF.get();
    }

    /** 上报异常(未捕获线程异常等);未就绪时静默忽略,由调用方另行落 startup.log */
    public static void report(Throwable t, String route, String context) {
        ErrorCenterService service = REF.get();
        if (service == null || t == null) {
            return;
        }
        try {
            service.reportThrowable(t, route, context);
        } catch (Exception ignored) {
            // 上报失败不影响业务
        }
    }

    /** 上报一条错误(无异常对象时用,如启动失败的自定义文案) */
    public static void report(ErrorSource source, ErrorLevel level, String kind, String message,
                              Throwable t, String route, String context) {
        ErrorCenterService service = REF.get();
        if (service == null) {
            return;
        }
        try {
            service.report(source, kind, level, message, t, route, context);
        } catch (Exception ignored) {
            // 上报失败不影响业务
        }
    }
}
