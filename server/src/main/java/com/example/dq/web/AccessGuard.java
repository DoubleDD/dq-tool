package com.example.dq.web;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 浏览器访问管控门禁(纯 Web 层,不进内核):配置了 dq.access-token 后,浏览器直接打开后端端口须携带令牌。
 * 放行条件三选一:请求头 X-Dq-Token / query 参数 token / Cookie dq-access-token。
 * 经 query 命中时种 Cookie,后续整页跳转、window.open 下载、心跳自动放行(同源浏览器路径)。
 * Tauri 侧由 Rust 每次启动随机生成并经 -Ddq.access-token 注入,前端从 IPC api_base() 取到后加 X-Dq-Token 头,用户无感。
 *
 * 安全边界:token 是「防误开门槛 + 防同机浏览器恶意网页读本地 API」的门闩,不是鉴权;令牌值绝不进日志/接口;
 * 比较用 {@link MessageDigest#isEqual}(常量时间),避免计时侧信道(内网单机定位下的防御性措施)。
 * 豁免清单固定为 /api/health(就绪探针)、/api/license/status(实例发现握手,对端进程不可能知道本实例 token)、
 * /api/lan/share/**(实例间拉取出口,既有口径无鉴权);OPTIONS 预检必须放行,否则 CORS 预检必挂。
 */
final class AccessGuard {

    static final String HEADER = "X-Dq-Token";
    static final String QUERY = "token";
    static final String COOKIE = "dq-access-token";

    private AccessGuard() {
    }

    /** 路径是否免令牌(最小豁免清单,与 Tauri 探针/局域网共享口径一致) */
    static boolean isExempt(String path) {
        return path.equals("/api/health")
                || path.equals("/api/license/status")
                || path.startsWith("/api/lan/share/");
    }

    /**
     * 校验令牌;命中放行(经 query 命中时种 Cookie),未命中抛 {@link AccessBlockedException}。
     * 调用方仅在令牌非空(已开启管控)时调用。
     */
    static void enforce(Context ctx, List<String> tokens) {
        // OPTIONS 预检放行:CorsPlugin 要靠它补全 Access-Control-* 头,门禁不能拦预检
        if ("OPTIONS".equalsIgnoreCase(ctx.method().name())) {
            return;
        }
        String path = ctx.path();
        if (isExempt(path)) {
            return;
        }
        // 带扩展名的静态资源(js/css/图标,且不属于 /api/**)放行:资源本身不含敏感数据,
        // 拦掉只会让页面缺样式;页面路由(无扩展名)与全部 /api/** 仍受管控
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (!path.startsWith("/api") && lastSegment.contains(".")) {
            return;
        }
        if (matches(tokens, ctx.header(HEADER))) {
            return;
        }
        String fromQuery = ctx.queryParam(QUERY);
        if (matches(tokens, fromQuery)) {
            // 经 query 命中:种 Cookie(Path=/ + SameSite=Strict + HttpOnly),后续同源跳转/下载无感通过
            ctx.cookie(new Cookie(COOKIE, fromQuery, "/", -1, false, true, null, SameSite.STRICT));
            ctx.header("Cache-Control", "no-store");
            return;
        }
        if (matches(tokens, ctx.cookie(COOKIE))) {
            return;
        }
        throw new AccessBlockedException(path);
    }

    private static boolean matches(List<String> tokens, String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        boolean hit = false;
        for (String token : tokens) {
            // 不用 |= 短路:保证对所有候选做等长比较(集合通常只有一个 token)
            hit = constantTimeEquals(token, presented) || hit;
        }
        return hit;
    }

    /** 常量时间比较(委托 MessageDigest.isEqual;长度不同时其内部先比对长度后返回 false) */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 未命中门禁:标记是 /api 还是页面路径,供 exception mapper 决定 JSON / HTML 文案 */
    static final class AccessBlockedException extends RuntimeException {
        private final boolean api;

        AccessBlockedException(String path) {
            super("访问被拒绝");
            this.api = path != null && path.startsWith("/api");
        }

        boolean isApi() {
            return api;
        }
    }
}
