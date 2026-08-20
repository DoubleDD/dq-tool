package com.example.dq.web;

/**
 * 共享内核尚未就绪(启动早期绑定后、H2 建表/迁移/中断恢复完成前)的业务接口拦截信号,
 * 由 WebServer 统一映射为 503 + Retry-After。前端挂载应用前轮询 /api/health,
 * 轮询到 200 前页面停留在 index.html 的「正在连接服务」占位,不弹业务错误。
 */
public class ServiceNotReadyException extends RuntimeException {

    public ServiceNotReadyException() {
        super("服务启动中,请稍候…");
    }
}
