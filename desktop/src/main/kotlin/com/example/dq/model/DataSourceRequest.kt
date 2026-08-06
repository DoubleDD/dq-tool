package com.example.dq.model

/** 数据源新增/编辑请求。password 为空表示不修改(编辑时)。 */
data class DataSourceRequest(
    val name: String?,
    val jdbcUrl: String?,
    val username: String?,
    val password: String?,
    val rowThreshold: Long?,
    val sizeThresholdBytes: Long?
)
