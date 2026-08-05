package com.example.dq.model;

import java.time.LocalDateTime;

/**
 * 库/schema 概览统计(库列表页用)。
 *
 * @param name                schema 名
 * @param tableCount          表数量(方言不支持或查询失败时为 null)
 * @param lastScanStatus      最近一次扫描任务状态(从未扫描为 null)
 * @param lastScanAt          最近一次扫描时间(依次取 完成/开始/创建 时间的首个非空值)
 * @param lastScanJobId       最近一次扫描任务 id(从未扫描为 null)
 * @param lastScanDoneTables  最近任务已完成表数,供列表页展示进度(从未扫描为 null)
 * @param lastScanTotalTables 最近任务总表数(从未扫描为 null)
 */
public record SchemaStat(String name, Integer tableCount, String lastScanStatus, LocalDateTime lastScanAt,
                         Long lastScanJobId, Integer lastScanDoneTables, Integer lastScanTotalTables) {
}
