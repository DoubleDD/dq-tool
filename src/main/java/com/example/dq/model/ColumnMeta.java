package com.example.dq.model;

import java.sql.Types;

/** 字段元数据 */
public record ColumnMeta(
        String name,
        String typeName,
        String displayType,     // 带长度/精度的展示类型,如 varchar(50)、decimal(10,2)
        int jdbcType,
        boolean nullable,
        String defaultValue,
        String comment,
        boolean primaryKey,
        int pkSeq,              // 主键中的序号,非主键列为 0
        boolean uniqueIndexFirst // 是否为某唯一索引的第一列
) {

    /** 兼容旧构造:仅名称/类型/键信息 */
    public ColumnMeta(String name, String typeName, int jdbcType,
                      boolean primaryKey, int pkSeq, boolean uniqueIndexFirst) {
        this(name, typeName, typeName, jdbcType, true, null, "", primaryKey, pkSeq, uniqueIndexFirst);
    }

    /** 是否字符类型(做空串/空白检查) */
    public boolean isCharacter() {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> true;
            default -> false;
        };
    }

    /** 是否数值类型 */
    public boolean isNumeric() {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> true;
            default -> false;
        };
    }

    /** 是否可作为分段键(可比较排序的类型) */
    public boolean isComparable() {
        return isNumeric() || isCharacter() || switch (jdbcType) {
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    /** 键约束展示:PK / UNI / 空 */
    public String keyLabel() {
        if (primaryKey) return "PK";
        if (uniqueIndexFirst) return "UNI";
        return "";
    }
}
