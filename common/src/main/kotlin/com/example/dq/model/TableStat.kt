package com.example.dq.model

/**
 * 表级元数据统计。
 *
 * @property name        表名
 * @property estRows     元数据估算行数(可能为 null)
 * @property sizeBytes   数据+索引总字节数(可能为 null)
 * @property comment     表注释(数据库 COMMENT,可能为空串)
 * @property storageInfo 存储信息:MySQL/OB 为存储引擎,PG/金仓/达梦为表空间(可能为空串)
 */
data class TableStat(val name: String?, val estRows: Long?, val sizeBytes: Long?, val comment: String?, val storageInfo: String?) {

    constructor(name: String?, estRows: Long?, sizeBytes: Long?) : this(name, estRows, sizeBytes, "", "")
}
