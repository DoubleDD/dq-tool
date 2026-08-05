package com.example.dq.model

import kotlin.math.floor

/** 字段级统计结果视图 */
data class ScanColumnView(
    val columnName: String?,
    val columnType: String?,      // 带长度/精度的展示类型
    val columnComment: String?,   // 字段注释(数据库 COMMENT)
    val nullable: Boolean?,
    val defaultValue: String?,
    val keyLabel: String?,        // PK / UNI / ""
    val totalRows: Long,
    val nullCount: Long,
    val emptyCount: Long,
    val ruleHitCount: Long,
    val valueCount: Long,    // 有值数 = total - null - empty - ruleHit
    val fillRate: Double     // 有值率 0-100
) {

    companion object {

        fun of(name: String?, displayType: String?, comment: String?,
               nullable: Boolean?, defaultValue: String?, keyLabel: String?,
               total: Long, nullCount: Long, emptyCount: Long, ruleHit: Long): ScanColumnView {
            val value = maxOf(0L, total - nullCount - emptyCount - ruleHit)
            // 保留两位小数,向下截断:有未填充行时不因四舍五入虚报 100%
            val rate = if (total > 0) floor(value * 10000.0 / total) / 100.0 else 0.0
            return ScanColumnView(name, displayType, comment, nullable, defaultValue, keyLabel,
                total, nullCount, emptyCount, ruleHit, value, rate)
        }
    }
}
