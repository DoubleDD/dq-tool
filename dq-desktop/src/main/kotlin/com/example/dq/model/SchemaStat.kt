package com.example.dq.model

import java.time.LocalDateTime

/**
 * 库/schema 概览统计(库列表页用)。
 *
 * @property name                schema 名
 * @property tableCount          表数量(方言不支持或查询失败时为 null)
 * @property sizeBytes           数据+索引总字节(方言不支持或查询失败时为 null)
 * @property lastScanStatus      最近一次扫描任务状态(从未扫描为 null)
 * @property lastScanAt          最近一次扫描时间(依次取 完成/开始/创建 时间的首个非空值)
 * @property lastScanJobId       最近一次扫描任务 id(从未扫描为 null)
 * @property lastScanDoneTables  最近任务已完成表数,供列表页展示进度(从未扫描为 null)
 * @property lastScanTotalTables 最近任务总表数(从未扫描为 null)
 */
data class SchemaStat(val name: String?, val tableCount: Int?, val sizeBytes: Long?, val lastScanStatus: String?,
                      val lastScanAt: LocalDateTime?,
                      val lastScanJobId: Long?, val lastScanDoneTables: Int?, val lastScanTotalTables: Int?)
