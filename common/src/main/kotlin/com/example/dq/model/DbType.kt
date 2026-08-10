package com.example.dq.model

/** 支持的数据库类型 */
enum class DbType(val label: String) {
    MYSQL("MySQL"),
    POSTGRESQL("PostgreSQL"),
    DM("达梦 DM8"),
    KINGBASE("人大金仓 KingbaseES"),
    OCEANBASE("OceanBase"),
    SQLSERVER("SQL Server"),
    ORACLE("Oracle");

    companion object {

        /** 按 jdbcUrl 识别数据库类型 */
        fun fromJdbcUrl(url: String?): DbType {
            if (url == null) {
                throw IllegalArgumentException("jdbcUrl 不能为空")
            }
            val u = url.lowercase()
            if (u.startsWith("jdbc:mysql:")) return MYSQL
            if (u.startsWith("jdbc:oceanbase:")) return OCEANBASE
            if (u.startsWith("jdbc:postgresql:")) return POSTGRESQL
            if (u.startsWith("jdbc:dm:")) return DM
            if (u.startsWith("jdbc:kingbase8:")) return KINGBASE
            if (u.startsWith("jdbc:sqlserver:")) return SQLSERVER
            if (u.startsWith("jdbc:oracle:")) return ORACLE
            throw IllegalArgumentException("无法识别的 jdbcUrl: $url")
        }
    }
}
