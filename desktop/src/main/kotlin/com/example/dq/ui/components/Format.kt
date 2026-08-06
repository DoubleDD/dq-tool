package com.example.dq.ui.components

import com.example.dq.model.ScanStatus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** 格式化字节数(平移自 web/src/utils/format.js) */
fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "-"
    if (bytes == 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = min(floor(ln(bytes.toDouble()) / ln(1024.0)).toInt(), units.size - 1)
    val value = bytes / 1024.0.pow(i)
    return if (value >= 100) "${Math.round(value)} ${units[i]}" else "${(value * 10).toLong() / 10.0} ${units[i]}"
}

/** 数字千分位 */
fun formatNumber(n: Long?): String =
    if (n == null) "-" else "%,d".format(n)

/** 状态 → 中文文案 */
fun statusText(status: ScanStatus?): String = when (status) {
    ScanStatus.DONE -> "完成"
    ScanStatus.RUNNING -> "运行中"
    ScanStatus.FAILED -> "失败"
    ScanStatus.CANCELED -> "已取消"
    ScanStatus.INTERRUPTED -> "已中断"
    ScanStatus.PENDING -> "等待中"
    null -> "-"
}

private val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 日期时间格式化 */
fun formatDateTime(v: LocalDateTime?): String = v?.format(DATE_TIME_FMT) ?: "-"

/** 耗时(起止时间 → 可读文案) */
fun formatDuration(startedAt: LocalDateTime?, finishedAt: LocalDateTime?): String {
    if (startedAt == null) return "-"
    val end = finishedAt ?: LocalDateTime.now()
    var s = maxOf(0L, java.time.Duration.between(startedAt, end).seconds)
    if (s < 60) return "${s}秒"
    val m = s / 60; s %= 60
    if (m < 60) return "${m}分${s}秒"
    return "${m / 60}时${m % 60}分"
}
