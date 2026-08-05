package com.example.dq.model

import java.sql.Types

/** 字段元数据 */
data class ColumnMeta(
    val name: String,
    val typeName: String,
    val displayType: String,     // 带长度/精度的展示类型,如 varchar(50)、decimal(10,2)
    val jdbcType: Int,
    val nullable: Boolean,
    val defaultValue: String?,
    val comment: String?,
    val primaryKey: Boolean,
    val pkSeq: Int,              // 主键中的序号,非主键列为 0
    val uniqueIndexFirst: Boolean // 是否为某唯一索引的第一列
) {

    /** 兼容旧构造:仅名称/类型/键信息 */
    constructor(name: String, typeName: String, jdbcType: Int,
                primaryKey: Boolean, pkSeq: Int, uniqueIndexFirst: Boolean) :
            this(name, typeName, typeName, jdbcType, true, null, "", primaryKey, pkSeq, uniqueIndexFirst)

    /** 是否字符类型(做空串/空白检查) */
    fun isCharacter(): Boolean {
        return when (jdbcType) {
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
            Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> true
            else -> false
        }
    }

    /** 是否数值类型 */
    fun isNumeric(): Boolean {
        return when (jdbcType) {
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
            Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> true
            else -> false
        }
    }

    /** 是否可作为分段键(可比较排序的类型) */
    fun isComparable(): Boolean {
        return isNumeric() || isCharacter() || when (jdbcType) {
            Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> true
            else -> false
        }
    }

    /** 键约束展示:PK / UNI / 空 */
    fun keyLabel(): String {
        if (primaryKey) return "PK"
        if (uniqueIndexFirst) return "UNI"
        return ""
    }
}
