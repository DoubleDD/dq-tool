package com.example.dq.model

import java.time.LocalDateTime

/** 错误来源:前端 JS/接口、后端业务逻辑、数据库、后台任务、启动期 */
enum class ErrorSource {
    FRONTEND,
    BACKEND,
    DATABASE,
    TASK,
    STARTUP,
}

/** 错误级别(与日志级别同口径;FATAL 预留给启动失败等不可恢复错误) */
enum class ErrorLevel {
    WARN,
    ERROR,
    FATAL,
}

/** 人工处理状态:已处理/已忽略是人工结论,新发生次数只累加计数,不自动改回 OPEN */
enum class ErrorStatus {
    OPEN,
    RESOLVED,
    IGNORED,
}

/**
 * 一条待入库的错误事件(采集侧产出,聚合前)。
 *
 * @param source     来源分类
 * @param kind       错误类型(异常类名 / JS 错误类型 / API_ERROR 等)
 * @param level      级别
 * @param message    错误消息
 * @param detail     完整堆栈(可空)
 * @param context    上下文(前端路由/浏览器/接口响应体,或后端线程/参数摘要)
 * @param logger     后端 logger 名(前端可空)
 * @param thread     后端线程名(前端可空)
 * @param route      定位线索:前端路由 / 接口路径 / 任务标识
 * @param occurredAt 发生时间
 */
data class ErrorEvent(
    val source: ErrorSource,
    val kind: String,
    val level: ErrorLevel,
    val message: String,
    val detail: String? = null,
    val context: String? = null,
    val logger: String? = null,
    val thread: String? = null,
    val route: String? = null,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
)

/** 已聚合的错误记录(错误中心列表/详情共用) */
data class ErrorRecord(
    val id: Long,
    val fingerprint: String,
    val source: String,
    val kind: String,
    val level: String,
    /** 最近一次错误消息 */
    val message: String?,
    /** 最近一次完整堆栈 */
    val detail: String?,
    /** 最近一次上下文 */
    val context: String?,
    val logger: String?,
    val thread: String?,
    val route: String?,
    val occurrences: Int,
    val firstSeen: LocalDateTime,
    val lastSeen: LocalDateTime,
    val appVersion: String?,
    val status: String,
    val note: String?,
)

/** 错误中心列表筛选条件(所有条件为空 = 不过滤) */
data class ErrorQuery(
    val sources: List<String> = emptyList(),
    val levels: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val keyword: String? = null,
    val from: LocalDateTime? = null,
    val to: LocalDateTime? = null,
    val page: Int = 1,
    val size: Int = 50,
)

/** 错误中心分页结果 */
data class ErrorPage(
    val total: Long,
    val page: Int,
    val size: Int,
    val items: List<ErrorRecord>,
)

/** 错误中心统计卡片:总量/未处理/今日新增/按来源/按级别 */
data class ErrorStats(
    val total: Long,
    val open: Long,
    val todayNew: Long,
    val bySource: Map<String, Long>,
    val byLevel: Map<String, Long>,
)
