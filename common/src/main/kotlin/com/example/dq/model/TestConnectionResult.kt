package com.example.dq.model

/**
 * 连接测试详情(编辑对话框「测试连接」的 DataGrip 风格反馈):
 * 数据库产品/驱动的名称与版本、连接耗时、SSL 启发式判断、数据库兼容模式(无则 null)
 */
data class TestConnectionResult(
    val dbmsName: String?,
    val dbmsVersion: String?,
    val driverName: String?,
    val driverVersion: String?,
    val pingMs: Long,
    val ssl: Boolean,
    val dbMode: String?,
)
