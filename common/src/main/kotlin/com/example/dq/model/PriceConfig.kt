package com.example.dq.model

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 工作时间段(高峰时段):[start, end),起=止视为停用。粒度为分钟(如 09:00-12:00)。
 */
data class WorkPeriod(val start: LocalTime, val end: LocalTime) {
    fun contains(time: LocalTime): Boolean =
        !start.equals(end) && !time.isBefore(start) && time.isBefore(end)
}

/**
 * AI 计费价格配置(元/百万 token)。
 * - 峰谷计价关闭(peakValleyEnabled=false):只用单一输入/输出价(peakInputPrice/peakOutputPrice 兼任),不区分时段。
 * - 峰谷计价开启:按「工作时间 / 非工作时间」两档计费(同 DeepSeek 官方峰谷规则)——工作时间段(工作日)按高峰价,
 *   其余时间与周末按谷价;工作时间段可多段(缺省 09:00-12:00、14:00-18:00,午休 12:00-14:00 自然算非工作时间)。
 */
data class PriceConfig(
    /** 是否启用峰谷计价(false 时只用单一输入/输出价) */
    val peakValleyEnabled: Boolean,
    /** 工作时间(高峰)输入价(元/百万 token);峰谷计价关闭时用作单一输入价 */
    val peakInputPrice: Double,
    /** 工作时间(高峰)输出价(元/百万 token);峰谷计价关闭时用作单一输出价 */
    val peakOutputPrice: Double,
    /** 非工作时间(谷价)输入价(元/百万 token) */
    val valleyInputPrice: Double,
    /** 非工作时间(谷价)输出价(元/百万 token) */
    val valleyOutputPrice: Double,
    /** 工作时间段(高峰时段),可多段 */
    val workPeriods: List<WorkPeriod>,
    /** 周末(周六/周日)全天按谷价计费(非工作时间) */
    val weekendValley: Boolean,
) {

    /** 该时刻是否处于工作时间(高峰);weekendValley=true 时周末恒为非工作时间;峰谷计价关闭时恒为 false */
    fun inPeak(time: LocalDateTime): Boolean {
        if (!peakValleyEnabled) {
            return false
        }
        if (weekendValley && (time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY)) {
            return false
        }
        val t = time.toLocalTime()
        return workPeriods.any { it.contains(t) }
    }

    /** 该时刻的计费时段标记:PEAK(工作时间)/ VALLEY(非工作时间)/ FLAT(峰谷计价关闭,落库用) */
    fun periodAt(time: LocalDateTime): String =
        if (!peakValleyEnabled) "FLAT" else if (inPeak(time)) "PEAK" else "VALLEY"

    /** 单次调用费用(元):峰谷计价关闭用单一价(peakInput/peakOutput 兼任);开启则按所处时段选用高峰价/谷价的输入输出价分别计价 */
    fun cost(promptTokens: Long, completionTokens: Long, time: LocalDateTime): Double {
        val (pc, cc) = costParts(promptTokens, completionTokens, time)
        return pc + cc
    }

    /** 费用拆分为(输入费用, 输出费用),计价规则与 cost 一致;落库分项费用供统计页分项展示 */
    fun costParts(promptTokens: Long, completionTokens: Long, time: LocalDateTime): Pair<Double, Double> {
        // 关闭峰谷计价时视为走高(单一)价;开启时按是否落在工作时间段取高峰价/谷价
        val peak = if (peakValleyEnabled) inPeak(time) else true
        val pi = if (peak) peakInputPrice else valleyInputPrice
        val po = if (peak) peakOutputPrice else valleyOutputPrice
        return (promptTokens * pi / 1_000_000.0) to (completionTokens * po / 1_000_000.0)
    }

    companion object {
        /** 解析工作时间段字符串 "09:00-12:00,14:00-18:00"(每段 HH:mm-HH:mm) */
        fun parsePeriods(raw: String?): List<WorkPeriod> =
            raw?.split(',')?.mapNotNull { seg ->
                val parts = seg.trim().split('-')
                if (parts.size == 2) {
                    runCatching {
                        WorkPeriod(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()))
                    }.getOrNull()
                } else null
            } ?: emptyList()

        /** 工作时间段序列化为 "09:00-12:00,14:00-18:00" */
        fun encodePeriods(periods: List<WorkPeriod>): String =
            periods.joinToString(",") { "${fmt(it.start)}-${fmt(it.end)}" }

        private fun fmt(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
    }
}
