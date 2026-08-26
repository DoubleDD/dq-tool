package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.config.AiDefaults
import com.example.dq.model.AiConfigRequest
import com.example.dq.model.AiScene
import com.example.dq.model.PriceConfig
import com.example.dq.model.WorkPeriod
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.AiUsageRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * AI 用量统计:两档计费(工作时间=高峰价 / 非工作时间=谷价,工作时间段可多段)、费用计算、
 * 记录落库与 stats/recentPage 聚合。
 */
class AiUsageServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var usageJdbc: Jdbc
    private lateinit var repo: AiUsageRepository
    private lateinit var aiConfigRepo: AiConfigRepository
    private lateinit var service: AiUsageService
    private lateinit var aiConfigService: AiConfigService

    private val config = AppConfig(
        dataDir = Files.createTempDirectory("dq-ai-usage-test"),
        ai = AiDefaults(apiKey = "k", baseUrl = "http://x/v1", model = "m"),
    )

    @BeforeEach
    fun setUp() {
        // 主库(ai_config 等)+ AI 用量独立库,与生产双库结构一致
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ai_usage_main_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        val usageDs = JdbcDataSource()
        usageDs.setURL("jdbc:h2:mem:ai_usage_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        usageDs.user = "sa"
        SchemaInit.run(usageDs, "db/migration-aiusage")
        jdbc = Jdbc(ds)
        usageJdbc = Jdbc(usageDs)
        repo = AiUsageRepository(usageJdbc)
        aiConfigRepo = AiConfigRepository(jdbc)
        service = AiUsageService(repo, aiConfigRepo, config) { jobId ->
            when (jobId) {
                1L -> AiUsageRepository.ScanJobLabel("生产库 db1 s1", LocalDateTime.of(2026, 8, 25, 9, 30))
                2L -> AiUsageRepository.ScanJobLabel("生产库 s2", LocalDateTime.of(2026, 8, 25, 10, 30))
                else -> null
            }
        }
        aiConfigService = AiConfigService(aiConfigRepo, CryptoUtil(config), config, AiService())
    }

    private val defaultPrice = PriceConfig(
        peakValleyEnabled = true,
        peakInputPrice = 9.0, peakOutputPrice = 27.0,
        valleyInputPrice = 4.5, valleyOutputPrice = 13.5,
        workPeriods = listOf(WorkPeriod(LocalTime.of(9, 0), LocalTime.of(12, 0)),
            WorkPeriod(LocalTime.of(14, 0), LocalTime.of(18, 0))),
        weekendValley = true,
    )

    // ---------- 两档计费规则(纯函数) ----------

    @Test
    fun `工作时间段按高峰价_其余时间按谷价`() {
        val mon10 = LocalDateTime.of(2026, 8, 24, 10, 0) // 周一,工作时间段 1
        val mon15 = LocalDateTime.of(2026, 8, 24, 15, 0) // 周一,工作时间段 2
        assertTrue(defaultPrice.inPeak(mon10))
        assertTrue(defaultPrice.inPeak(mon15))
        assertEquals("PEAK", defaultPrice.periodAt(mon10))

        // 8:00 / 12:00(边界不含)/ 13:00(午休,非工作时间)/ 20:00 为谷
        for (h in listOf(8, 12, 13, 20)) {
            val t = LocalDateTime.of(2026, 8, 24, h, 0)
            assertTrue(!defaultPrice.inPeak(t), "hour=$h")
            assertEquals("VALLEY", defaultPrice.periodAt(t))
        }
    }

    @Test
    fun `周末按谷价_关闭周末谷价后周末工作时间段仍按高峰价`() {
        val sunday10 = LocalDateTime.of(2026, 8, 23, 10, 0) // 周日,在时间段 1 内
        assertTrue(!defaultPrice.inPeak(sunday10))
        assertEquals(4.5, defaultPrice.cost(1_000_000, 0, sunday10), 1e-9)

        val noWeekendValley = defaultPrice.copy(weekendValley = false)
        assertTrue(noWeekendValley.inPeak(sunday10))
        assertEquals(9.0, noWeekendValley.cost(1_000_000, 0, sunday10), 1e-9)
    }

    @Test
    fun `时间段起止相同视为停用`() {
        val price = PriceConfig(
            peakValleyEnabled = true,
            peakInputPrice = 9.0, peakOutputPrice = 27.0,
            valleyInputPrice = 4.5, valleyOutputPrice = 13.5,
            workPeriods = listOf(WorkPeriod(LocalTime.of(9, 0), LocalTime.of(9, 0)),
                WorkPeriod(LocalTime.of(14, 0), LocalTime.of(18, 0))),
            weekendValley = false)
        assertTrue(!price.inPeak(LocalDateTime.of(2026, 8, 24, 10, 0))) // 段 1 停用
        assertTrue(price.inPeak(LocalDateTime.of(2026, 8, 24, 15, 0)))  // 段 2 仍生效
    }

    @Test
    fun `峰谷计价关闭时按单一价格计费且时段记为平`() {
        // 关闭峰谷计价:只用单一价(9/27),无论什么时间、是否周末
        val flat = PriceConfig(
            peakValleyEnabled = false,
            peakInputPrice = 9.0, peakOutputPrice = 27.0,
            valleyInputPrice = 4.5, valleyOutputPrice = 13.5,
            workPeriods = emptyList(), weekendValley = true,
        )
        val mon10 = LocalDateTime.of(2026, 8, 24, 10, 0)      // 工作日工作时间段
        val sun10 = LocalDateTime.of(2026, 8, 23, 10, 0)      // 周日
        assertTrue(!flat.inPeak(mon10))
        assertTrue(!flat.inPeak(sun10))
        // 单一价:1000 输入 + 500 输出 = (1000*9 + 500*27)/1e6 = 0.0225(与时段无关)
        assertEquals(0.0225, flat.cost(1000, 500, mon10), 1e-9)
        assertEquals(0.0225, flat.cost(1000, 500, sun10), 1e-9)
        assertEquals("FLAT", flat.periodAt(mon10))
        assertEquals("FLAT", flat.periodAt(sun10))
    }

    @Test
    fun `费用按所处时段选用对应输入输出价`() {
        // 工作时间:1000 输入 + 500 输出 = (1000*9 + 500*27)/1e6 = 0.0225
        val peak = defaultPrice.cost(1000, 500, LocalDateTime.of(2026, 8, 24, 10, 0))
        assertEquals(0.0225, peak, 1e-9)
        // 非工作时间(谷价):(1000*4.5 + 500*13.5)/1e6 = 0.01125
        val valley = defaultPrice.cost(1000, 500, LocalDateTime.of(2026, 8, 24, 20, 0))
        assertEquals(0.01125, valley, 1e-9)
    }

    @Test
    fun `parsePeriods解析多段字符串`() {
        val periods = PriceConfig.parsePeriods("09:00-12:00,14:00-18:00")
        assertEquals(2, periods.size)
        assertEquals(LocalTime.of(9, 0), periods[0].start)
        assertEquals(LocalTime.of(12, 0), periods[0].end)
        assertEquals(LocalTime.of(14, 0), periods[1].start)
        // 非法段被忽略;null/空串返回空列表
        assertEquals(1, PriceConfig.parsePeriods("09:00-12:00,坏段").size)
        assertTrue(PriceConfig.parsePeriods(null).isEmpty())
        assertTrue(PriceConfig.parsePeriods("").isEmpty())
    }

    // ---------- 记录与聚合 ----------

    @Test
    fun `record后stats汇总与按日序列正确且缺日补零`() {
        // 用相对当前的时间,避免测试依赖具体日期/星期
        val t1 = LocalDateTime.now().minusMinutes(5)
        val t2 = LocalDateTime.now()
        service.record(AiScene.TABLE_DOC, "deepseek-chat", 1000, 200, 1200, t1)
        service.record(AiScene.AUTO_TAG, "deepseek-chat", 500, 100, 600, t2)

        // 窗口覆盖最近 30 天 → 两条都纳入
        val stats = service.stats(days = 30)
        assertEquals(2, stats.summary.calls)
        assertEquals(1500, stats.summary.promptTokens)
        assertEquals(300, stats.summary.completionTokens)
        assertEquals(1800, stats.summary.totalTokens)
        // 费用 = 按当前价格配置逐条计算(与实现同口径,避免时段规则依赖具体星期)
        val price = service.currentPrice()
        val expected = price.cost(1000, 200, t1) + price.cost(500, 100, t2)
        assertEquals(expected, stats.summary.cost, 1e-9)

        // 序列长度 = 天数,当天为 2 次,其余为 0
        assertEquals(30, stats.series.size)
        val today = stats.series.last()
        assertEquals(2, today.calls)
        assertEquals(1800, today.totalTokens)
        assertEquals(0, stats.series.first().calls)

        // 分项费用 = 按当前价格配置逐条拆分后求和;补零日无分项(null)
        val p1 = price.costParts(1000, 200, t1)
        val p2 = price.costParts(500, 100, t2)
        assertEquals(p1.first + p2.first, today.promptCost!!, 1e-9)
        assertEquals(p1.second + p2.second, today.completionCost!!, 1e-9)
        assertEquals(null, stats.series.first().promptCost)

        // 场景分布:两个场景
        val scenes = stats.scenes.associateBy { it.scene }
        assertEquals("表说明", scenes[AiScene.TABLE_DOC.name]!!.label)
        assertEquals("自动打标", scenes[AiScene.AUTO_TAG.name]!!.label)
        assertEquals(1000, scenes[AiScene.TABLE_DOC.name]!!.promptTokens)
        assertEquals(500, scenes[AiScene.AUTO_TAG.name]!!.promptTokens)
    }

    @Test
    fun `费用按页面保存的价格配置计算`() {
        // 页面保存自定义价:工作时间 2/8,非工作时间 1/4(元/百万)
        aiConfigService.save(AiConfigRequest(
            baseUrl = "http://x/v1", apiKey = "k", model = "m",
            peakInputPrice = 2.0, peakOutputPrice = 8.0,
            valleyInputPrice = 1.0, valleyOutputPrice = 4.0,
            workPeriods = listOf("09:00-12:00", "14:00-18:00"), weekendValley = true,
        ))
        val service2 = AiUsageService(repo, aiConfigRepo, config)
        val t = LocalDateTime.now()
        service2.record(AiScene.TABLE_DOC, "deepseek-chat", 1000, 500, 1500, t)
        // 费用按自定义价计算:与默认价(9/27/4.5/13.5)必然不同,证明页面配置生效
        assertEquals(service2.currentPrice().cost(1000, 500, t), service2.stats(30).summary.cost, 1e-9)
        assertEquals(2.0, service2.currentPrice().peakInputPrice)
        assertEquals(8.0, service2.currentPrice().peakOutputPrice)
        assertEquals(1.0, service2.currentPrice().valleyInputPrice)
        assertEquals(2, service2.currentPrice().workPeriods.size)
    }

    @Test
    fun `recentPage按时间倒序分页且带场景中文标签`() {
        service.record(AiScene.TEST, "m1", 10, 1, 11, LocalDateTime.of(2026, 8, 24, 10, 0))
        service.record(AiScene.WORD_REPORT, "m2", 20, 2, 22, LocalDateTime.of(2026, 8, 24, 11, 0))
        val page1 = service.recentPage(1, 10)
        assertEquals(2, page1.total)
        val items = page1.items
        assertEquals(2, items.size)
        // 倒序:后插入的在前
        assertEquals("WORD_REPORT", items[0].scene)
        assertEquals("报告分析", items[0].sceneLabel)
        assertEquals("TEST", items[1].scene)
        assertEquals("连通测试", items[1].sceneLabel)
        assertEquals(11, items[1].totalTokens)
        // 第二页无数据但总数不变
        val page2 = service.recentPage(2, 1)
        assertEquals(2, page2.total)
        assertEquals(1, page2.items.size)
        assertEquals("TEST", page2.items[0].scene)
        val page3 = service.recentPage(3, 1)
        assertEquals(0, page3.items.size)
        // 未记录的场景名映射为原名
        assertEquals("UNKNOWN", AiScene.labelOf("UNKNOWN"))
    }

    @Test
    fun `totalTokens为0时不记录`() {
        service.record(AiScene.TEST, "m1", 0, 0, 0, LocalDateTime.of(2026, 8, 24, 10, 0))
        assertEquals(0, service.stats(30).summary.calls)
    }

    @Test
    fun `scanSeries按扫描任务聚合并带记录时快照标签`() {
        // job1 有两次调用,job2 一次;标签由记录时快照(resolver)写入,不依赖主库 join
        val now = LocalDateTime.now()
        service.record(AiScene.TABLE_DOC, "m1", 100, 10, 110, now, 1L)
        service.record(AiScene.AUTO_TAG, "m1", 200, 20, 220, now, 1L)
        service.record(AiScene.AUTO_TAG, "m1", 400, 40, 440, now, 2L)
        // 非扫描调用(手动表说明/连通测试)不计入按扫描维度
        service.record(AiScene.TEST, "m1", 1, 1, 2, now)

        val rows = service.scanSeries(30)
        assertEquals(2, rows.size)
        val j1 = rows[0]
        assertEquals(1L, j1.jobId)
        assertEquals(2, j1.calls)
        assertEquals(300, j1.promptTokens)
        assertEquals(330, j1.totalTokens)
        assertTrue(j1.label.startsWith("生产库 db1 s1"), j1.label)
        assertEquals("2026-08-25", j1.date)
        val j2 = rows[1]
        assertEquals(2L, j2.jobId)
        assertEquals(1, j2.calls)
        assertEquals(440, j2.totalTokens)
        assertTrue(j2.label.startsWith("生产库 s2"), j2.label)
    }

    @Test
    fun `record保存请求响应内容且超长截断`() {
        val now = LocalDateTime.now()
        service.record(AiScene.TABLE_DOC, "m1", 100, 10, 110, now, null, "请求内容X", "响应内容Y")
        val long = "a".repeat(60_000)
        service.record(AiScene.TABLE_DOC, "m1", 100, 10, 110, now, null, long, null)
        val contents = jdbc2Content()
        assertEquals(2, contents.size)
        assertEquals("请求内容X" to "响应内容Y", contents[0])
        assertTrue(contents[1].first!!.endsWith("...(截断)"), contents[1].first!!.takeLast(20))
        assertTrue(contents[1].first!!.length < 60_000)
        assertEquals(null, contents[1].second)
    }

    @Test
    fun `老库用量流水一次性搬迁到独立库`() {
        // 主库老表(V16/V20/V21 结构,无内容列)插两行,一行带 scan_job_id
        val now = LocalDateTime.now()
        jdbc.update(
            """INSERT INTO ai_usage_log(scene, model, prompt_tokens, completion_tokens, total_tokens, cost, prompt_cost, completion_cost, period, created_at, scan_job_id)
               VALUES ('TABLE_DOC','m1',100,10,110,0.5,0.3,0.2,'PEAK',?,1)""", now)
        jdbc.update(
            """INSERT INTO ai_usage_log(scene, model, prompt_tokens, completion_tokens, total_tokens, cost, period, created_at)
               VALUES ('TEST','m1',1,1,2,0.001,'VALLEY',?)""", now)

        val moved = repo.migrateLegacyIfEmpty(jdbc) { jobId ->
            if (jobId == 1L) AiUsageRepository.ScanJobLabel("生产库 db1 s1", LocalDateTime.of(2026, 8, 25, 9, 30)) else null
        }
        assertEquals(2, moved)
        assertEquals(2, repo.countAll())
        // 保留原时间/费用/时段,分项费用可空语义保留,扫描标签快照
        val page = service.recentPage(1, 10)
        assertEquals(2, page.total)
        val scans = service.scanSeries(30)
        assertEquals(1, scans.size)
        assertTrue(scans[0].label.startsWith("生产库 db1 s1"), scans[0].label)
        assertEquals("2026-08-25", scans[0].date)
        // 老表无内容列,搬迁后内容为 NULL
        assertEquals(listOf(null to null, null to null), jdbc2Content())
        // 幂等:新库非空不再搬迁
        assertEquals(0, repo.migrateLegacyIfEmpty(jdbc) { null })
        assertEquals(2, repo.countAll())
    }

    /** 直查独立库 request/response 内容列 */
    private fun jdbc2Content(): List<Pair<String?, String?>> =
        usageJdbc.query("SELECT request_content, response_content FROM ai_usage_log ORDER BY id") { rs ->
            rs.getString("request_content") to rs.getString("response_content")
        }
}
