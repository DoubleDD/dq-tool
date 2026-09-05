package com.example.dq.model

/** 批量设置表所属系统的入参;systemName trim 后空白表示清除 */
data class TableSystemBatchRequest(val tableNames: List<String> = emptyList(), val systemName: String? = null)
