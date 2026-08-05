package com.example.dq.model

/**
 * 自定义空值规则:某列(或 * 表示所有列)的额外"视为空"取值。
 */
data class NullRule(val column: String?, val values: List<String>) {

    fun matches(columnName: String): Boolean {
        return "*" == column || column != null && column.equals(columnName, ignoreCase = true)
    }
}
