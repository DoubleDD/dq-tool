package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.AiScene
import com.example.dq.model.AiUsageDay
import com.example.dq.model.AiUsageLogView
import com.example.dq.model.AiUsageScene
import com.example.dq.model.AiUsageStatsView
import com.example.dq.model.AiUsageSummary
import com.example.dq.model.PriceConfig
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.AiUsageRepository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * AI 调用 Token/费用统计:每次大模型调用成功后由 AiService 回调本服务落库;
 * 费用按当前价格配置计算(默认 DeepSeek 官方价,支持峰谷价——工作日峰时段按高峰价,
 * 其余时间与周末按谷价);对外提供统计汇总/每日序列/场景分布/最近明细。
 */
class AiUsageService(
    private val repo: AiUsageRepository,
    private val aiConfigRepo: AiConfigRepository,
    private val config: AppConfig,
) {

    /** 当前生效价格配置:页面已存值优先,空字段回落到配置默认(DeepSeek 官方价) */
    fun currentPrice(): PriceConfig {
        val row = aiConfigRepo.get()
        val d = config.ai
        return PriceConfig(
            peakValleyEnabled = row?.peakValleyEnabled ?: d.peakValleyEnabled,
            peakInputPrice = row?.peakInputPrice ?: d.peakInputPrice,
            peakOutputPrice = row?.peakOutputPrice ?: d.peakOutputPrice,
            valleyInputPrice = row?.valleyInputPrice ?: d.valleyInputPrice,
            valleyOutputPrice = row?.valleyOutputPrice ?: d.valleyOutputPrice,
            workPeriods = PriceConfig.parsePeriods(row?.workPeriods ?: d.workPeriods),
            weekendValley = row?.weekendValley ?: d.weekendValley,
        )
    }

    /** 记一条用量(AiService 调用;totalTokens<=0 视为无有效用量不记录) */
    fun record(scene: AiScene, model: String, promptTokens: Long, completionTokens: Long,
               totalTokens: Long, time: LocalDateTime) {
        if (totalTokens <= 0) {
            return
        }
        val price = currentPrice()
        val (promptCost, completionCost) = price.costParts(promptTokens, completionTokens, time)
        repo.insert(
            scene.name, model, promptTokens, completionTokens, totalTokens,
            price.cost(promptTokens, completionTokens, time), promptCost, completionCost,
            price.periodAt(time), time,
        )
    }

    /** 最近 days 天的统计(含今天,不足补零),days 已由调用方钳制 */
    fun stats(days: Int): AiUsageStatsView {
        val from = LocalDate.now().minusDays((days - 1).toLong())
        val toExclusive = LocalDate.now().plusDays(1)
        val dayMap = repo.daySeries(from, toExclusive).associateBy { it.date }
        val series = (0 until days).map { i ->
            val d = from.plusDays(i.toLong())
            val stat = dayMap[d.toString()]
            AiUsageDay(
                date = d.toString(),
                calls = stat?.calls ?: 0L,
                promptTokens = stat?.promptTokens ?: 0L,
                completionTokens = stat?.completionTokens ?: 0L,
                totalTokens = stat?.totalTokens ?: 0L,
                cost = stat?.cost ?: 0.0,
                promptCost = stat?.promptCost,
                completionCost = stat?.completionCost,
            )
        }
        val scenes = repo.sceneSeries(from, toExclusive).map {
            AiUsageScene(
                scene = it.scene,
                label = AiScene.labelOf(it.scene),
                calls = it.calls,
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens,
                cost = it.cost,
            )
        }
        val t = repo.totals(from, toExclusive)
        return AiUsageStatsView(
            summary = AiUsageSummary(t.calls, t.promptTokens, t.completionTokens, t.totalTokens, t.cost),
            series = series,
            scenes = scenes,
        )
    }

    /** 最近调用明细(倒序),limit 已由调用方钳制 */
    fun recent(limit: Int): List<AiUsageLogView> =
        repo.recent(limit).map {
            AiUsageLogView(
                id = it.id,
                scene = it.scene,
                sceneLabel = AiScene.labelOf(it.scene),
                model = it.model,
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens,
                cost = it.cost,
                period = it.period,
                createdAt = it.createdAt.toString(),
            )
        }
}
