package com.example.dq.model;

/**
 * 表级元数据统计。
 *
 * @param name        表名
 * @param estRows     元数据估算行数(可能为 null)
 * @param sizeBytes   数据+索引总字节数(可能为 null)
 * @param comment     表注释(数据库 COMMENT,可能为空串)
 * @param storageInfo 存储信息:MySQL/OB 为存储引擎,PG/金仓/达梦为表空间(可能为空串)
 */
public record TableStat(String name, Long estRows, Long sizeBytes, String comment, String storageInfo) {

    public TableStat(String name, Long estRows, Long sizeBytes) {
        this(name, estRows, sizeBytes, "", "");
    }
}
