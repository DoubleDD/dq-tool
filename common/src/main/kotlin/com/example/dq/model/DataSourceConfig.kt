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
    /** 库过滤白名单:非空时库列表只显示这些库;空/NULL 表示不过滤 */
    var schemaFilter: List<String>? = null

    // ---- SSH 隧道(经跳板机连接目标库)----
    /** 是否启用 SSH 隧道 */
    var sshEnabled: Boolean? = null
    var sshHost: String? = null
    var sshPort: Int? = null
    var sshUsername: String? = null
    /** 认证方式:password / publickey */
    var sshAuthMethod: String? = null
    /** 解密后的 SSH 密码,仅在内存和请求中使用,不出库 */
    var sshPassword: String? = null
    /** 解密后的 SSH 私钥内容(非文件路径),仅在内存和请求中使用,不出库 */
    var sshPrivateKey: String? = null
    /** 解密后的 SSH 私钥口令,仅在内存和请求中使用,不出库 */
    var sshPassphrase: String? = null
}
