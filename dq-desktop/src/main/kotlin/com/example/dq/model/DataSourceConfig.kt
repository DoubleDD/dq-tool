package com.example.dq.model

/** 数据源配置(存 H2) */
class DataSourceConfig {

    var id: Long? = null
    var name: String? = null
    var dbType: DbType? = null
    var jdbcUrl: String? = null
    var username: String? = null
    /** 解密后的密码,仅在内存和请求中使用,不出库 */
    var password: String? = null
    var rowThreshold: Long? = null
    var sizeThresholdBytes: Long? = null
    /** 数据库兼容模式(如 Kingbase 的 pg/oracle/mysql),保存数据源时探测,可空 */
    var dbMode: String? = null
}
