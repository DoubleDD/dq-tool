package com.example.dq.model

import jakarta.validation.constraints.NotNull

/**
 * 发起扫描请求。
 *
 * @property database          目标数据库;仅支持多库选择的方言(如 SQL Server)使用,其他方言为 null
 * @property tables            选中的表;null 或空表示全库
 * @property forceFull         超阈值表也强制全量统计
 * @property nullRules         自定义空值规则
 * @property maxTableSizeBytes 表大小上限(字节);只扫描不超过该大小的表,null 表示不限制
 * @property autoTag           表扫描完成后由大模型从 USER 标记中自动选择打标
 * @property workers           并发 worker 线程数;null 表示使用配置默认值(dq.scan.workers)
 * @property genDoc            表扫描完成后由大模型生成表描述;null 视为 true(向后兼容老请求)
 * @property sampleRows        任务级采样行数:超过阈值的表只统计该数量样本;非法值(<1)钳到 1,与系统设置保存口径一致;
 *                             null 表示使用全局默认(规划采样表时回落)
 * @property autoTagMode       AI 自动打标对已有标记表的处理模式(SKIP/APPEND/OVERWRITE);null 或非法值视为 SKIP
 */
data class ScanRequest(
    @field:NotNull val datasourceId: Long?,
    @field:NotNull val schema: String?,
    val database: String?,
    val tables: List<String>?,
    val forceFull: Boolean = false,
    val nullRules: List<NullRule>?,
    val maxTableSizeBytes: Long?,
    val autoTag: Boolean = false,
    val workers: Int? = null,
    val genDoc: Boolean? = null,
    val sampleRows: Int? = null,
    val autoTagMode: String? = null,
)
