package com.example.dq.model;

/** 字段级统计结果视图 */
public record ScanColumnView(
        String columnName,
        String columnType,      // 带长度/精度的展示类型
        String columnComment,   // 字段注释(数据库 COMMENT)
        Boolean nullable,
        String defaultValue,
        String keyLabel,        // PK / UNI / ""
        long totalRows,
        long nullCount,
        long emptyCount,
        long ruleHitCount,
        long valueCount,    // 有值数 = total - null - empty - ruleHit
        double fillRate     // 有值率 0-100
) {

    public static ScanColumnView of(String name, String displayType, String comment,
                                    Boolean nullable, String defaultValue, String keyLabel,
                                    long total, long nullCount, long emptyCount, long ruleHit) {
        long value = Math.max(0, total - nullCount - emptyCount - ruleHit);
        // 保留两位小数,向下截断:有未填充行时不因四舍五入虚报 100%
        double rate = total > 0 ? Math.floor(value * 10000.0 / total) / 100.0 : 0.0;
        return new ScanColumnView(name, displayType, comment, nullable, defaultValue, keyLabel,
                total, nullCount, emptyCount, ruleHit, value, rate);
    }
}
