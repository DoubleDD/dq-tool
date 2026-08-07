package com.example.dq.config;

import java.security.Security;

/**
 * 老版本 SQL Server 兼容:重新允许 TLS 1.0/1.1。
 *
 * 背景:SQL Server 2014(未打全 TLS 1.2 补丁)及更早版本只支持 TLS 1.0/1.1,
 * 而 JDK 默认在 jdk.tls.disabledAlgorithms 中禁用这两个版本,mssql-jdbc 登录阶段的
 * TLS 握手被服务端直接断开("SQL Server did not return a response"),encrypt=false 也绕不过。
 * dq-tool 定位内网单机使用,启动时把 TLSv1/TLSv1.1 从禁用列表中摘掉,让驱动可回退到老协议;
 * 服务端支持 TLS 1.2+ 时仍会优先协商高版本,不影响常规连接。
 *
 * 必须在任何 TLS 使用(SSLContext 初始化/首次握手)之前调用:JDK 的
 * DisabledAlgorithmConstraints 在首次使用时一次性缓存该属性,之后再改无效。
 * 两个 JVM 入口(DqApplication.main / shell 的 Main.kt)都要调用。
 */
public final class LegacyTlsSupport {

    private LegacyTlsSupport() {
    }

    public static void enable() {
        String original = Security.getProperty("jdk.tls.disabledAlgorithms");
        String stripped = stripLegacyTls(original);
        if (stripped == null || stripped.equals(original)) {
            return; // 属性不存在或本就没禁用(用户已自行放开),不动
        }
        Security.setProperty("jdk.tls.disabledAlgorithms", stripped);
        StartupLog.log("已从 jdk.tls.disabledAlgorithms 移除 TLSv1/TLSv1.1,兼容仅支持老协议的 SQL Server");
    }

    /** 从禁用算法列表中移除 TLSv1/TLSv1.1 两个条目,其余条目原样保留;返回 null 表示属性不存在 */
    static String stripLegacyTls(String disabledAlgorithms) {
        if (disabledAlgorithms == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(disabledAlgorithms.length());
        for (String token : disabledAlgorithms.split(",")) {
            String t = token.trim();
            if (t.equals("TLSv1") || t.equals("TLSv1.1")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
