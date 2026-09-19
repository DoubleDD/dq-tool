package com.example.dq.controller;

import io.javalin.http.Context;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 导出中心埋点小工具(V66):各导出 controller 登记记录时的公共琐事。
 * 埋点只在导出成功(响应流写完)后调用;登记失败不影响导出本身(ExportCenterService 内已兜)。
 */
public final class ExportTrace {

    private ExportTrace() {
    }

    /** Content-Disposition 文件名(URLEncoder 编码过)解码回展示名;编码非法时原样返回 */
    public static String decode(String encodedFileName) {
        try {
            // '+' 按 form 编码还原为空格(与各处 URLEncoder 编码口径互逆)
            return URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return encodedFileName;
        }
    }

    /** 重新导出所需完整 API 路径(含 query);list-exports 一次性 token 场景请传 null(不可重放) */
    public static String fullPath(Context ctx) {
        String qs = ctx.queryString();
        return qs == null || qs.isBlank() ? ctx.path() : ctx.path() + "?" + qs;
    }
}
