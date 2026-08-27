package com.example.dq.config;

import java.security.Security;

/**
 * 老版本 SQL Server 兼容:重新允许 TLS 1.0/1.1 与 RSA 密钥交换套件。
 *
 * 背景:SQL Server 2014(未打全 TLS 1.2 补丁)及更早版本只支持 TLS 1.0/1.1,
 * 而 JDK 默认在 jdk.tls.disabledAlgorithms 中禁用这两个版本,mssql-jdbc 登录阶段的
 * TLS 握手被服务端直接断开("SQL Server did not return a response"),encrypt=false 也绕不过。
 * JDK 24+ 还额外禁用了 TLS_RSA_*(RSA 密钥交换套件),老版本 Windows Schannel 只提供这类
 * 套件,不摘掉会出现协议对了但没有共同密码套件,握手同样被服务端掐断(2026-08 实测:
 * 仅摘 TLSv1/TLSv1.1 在 JDK 25 上仍连不上 SQL Server 2014)。
 * dq-tool 定位内网单机使用,启动时把 TLSv1/TLSv1.1/TLS_RSA_* 从禁用列表中摘掉,让驱动可
 * 回退到老协议;服务端支持 TLS 1.2+ 时仍会优先协商高版本,不影响常规连接。
 *
 * 注意:还有一类更老的服务端连现代格式的 TLS 1.2 ClientHello 都处理不了(直接断开,
 * 与协议版本无关),此时仅放宽禁用列表不够,需在数据源 JDBC URL 上加 sslProtocol=TLSv1.1
 * (mssql-jdbc 连接属性,只让该连接走老格式握手,不影响应用其他 TLS 调用)。
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
        StartupLog.log("已从 jdk.tls.disabledAlgorithms 移除 TLSv1/TLSv1.1/TLS_RSA_*,兼容仅支持老协议的 SQL Server");
    }

    /** 从禁用算法列表中移除 TLSv1/TLSv1.1/TLS_RSA_* 条目,其余条目原样保留;返回 null 表示属性不存在 */
    static String stripLegacyTls(String disabledAlgorithms) {
        if (disabledAlgorithms == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(disabledAlgorithms.length());
        for (String token : disabledAlgorithms.split(",")) {
            String t = token.trim();
            if (t.equals("TLSv1") || t.equals("TLSv1.1") || t.equals("TLS_RSA_*")) {
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
