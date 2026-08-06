package com.example.dq.model;

/** 支持的数据库类型 */
public enum DbType {
    MYSQL,
    POSTGRESQL,
    DM,
    KINGBASE,
    OCEANBASE,
    SQLSERVER,
    ORACLE;

    /** 按 jdbcUrl 识别数据库类型 */
    public static DbType fromJdbcUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("jdbcUrl 不能为空");
        }
        String u = url.toLowerCase();
        if (u.startsWith("jdbc:mysql:")) return MYSQL;
        if (u.startsWith("jdbc:oceanbase:")) return OCEANBASE;
        if (u.startsWith("jdbc:postgresql:")) return POSTGRESQL;
        if (u.startsWith("jdbc:dm:")) return DM;
        if (u.startsWith("jdbc:kingbase8:")) return KINGBASE;
        if (u.startsWith("jdbc:sqlserver:")) return SQLSERVER;
        if (u.startsWith("jdbc:oracle:")) return ORACLE;
        throw new IllegalArgumentException("无法识别的 jdbcUrl: " + url);
    }
}
