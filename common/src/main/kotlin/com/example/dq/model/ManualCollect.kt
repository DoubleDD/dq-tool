package com.example.dq.model

import java.time.LocalDateTime

/**
 * 人工采集(收藏)清单的一行:数据源+库+schema+表 四元组定位一张表;
 * datasourceName 由仓储 LEFT JOIN data_source 补齐(数据源被删后为空串,记录保留)。
 */
data class ManualCollect(
    val id: Long,
    val datasourceId: Long,
    val datasourceName: String,
    val dbName: String,
    val schemaName: String,
    val tableName: String,
    val tableComment: String?,
    val createdAt: LocalDateTime?,
)

/** 批量采集请求项(server 层请求体映射而来);dbName 可空,内核归一为空串(与 table_tag 口径一致) */
data class ManualCollectItem(
    val datasourceId: Long,
    val dbName: String?,
    val schemaName: String?,
    val tableName: String?,
    val tableComment: String?,
)

/** 批量采集结果:added 新增数,skipped 已存在(含请求内重复)跳过数 */
data class ManualCollectAddResult(val added: Int, val skipped: Int)
