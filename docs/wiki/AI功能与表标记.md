# AI 功能与表标记

> dq-tool 项目文档,索引见根 [AGENTS.md](../../AGENTS.md)。本文覆盖 AI 表说明、AI 自动打标、表标记与统计。

## AI 表说明

大模型(OpenAI 兼容接口)根据表结构生成表用途描述,页面配置接口信息,手动触发生成,也支持手动编辑,结果存 H2;扫描时可勾选「生成表描述」随扫描自动批量生成(见 [扫描与Excel导出](扫描与Excel导出.md))。

## AI 自动打标

扫描时可选(auto_tag 随 scan_job 持久化,断点续扫仍生效);每张表 DONE 后由 `AutoTagService` 独立守护线程池(2 worker)异步执行(与扫描并行,不占扫描 worker)——以表注释/字段注释/AI 表描述为上下文(三者全空且表非空时抽样前 20 列 100 行业务数据,单元格截断 100 字符),连同全局 USER 标记列表(带各标记的描述,帮助模型理解标记含义、精准选择;无描述只列名字)发给大模型选一个标记自动打上(幂等 ensureTableTag,只增不删;表已有 USER 标记跳过不覆盖);未配置大模型/无候选标记静默跳过,同 job 首次 LLM 失败后熔断剩余表;前端扫描对话框与库列表整库扫描确认框均有复选(默认勾选 = `GET /api/ai-config` 的 available,即合并默认配置后有效配置完整)。

## 表标记

全局表级标记(数据源+库+表名,一表多标记,名称+颜色+描述);「空表」系统标记随扫描结果自动打/摘,用户标记在表列表打标弹窗集中管理;库列表标签块点击筛选 + 独立「标记统计」页(需求 `docs/requirements/表标记与统计需求.md`,实施见 `docs/plans/表标记与统计-实施计划.md`)。

## 标记与描述数据导出/导入

换机迁移用:`AnnotationTransferService` 把 USER 标记定义(name/color/description,不含 id)、USER 标记的表-标记关联、全部表描述(table_doc)打包成 JSON(`app=dq-tool-annotations, version=1`),表级数据导出为 数据源名+db+schema+table(不导内部 id)。导入按 name 合并标记(不存在创建、已存在覆盖 color/description;系统空表标记不动);表级行的数据源对应走**显式映射**——不同机器上同一数据源命名可能不同,导入前先 `POST /api/annotations/import/preview` 解析文件里的数据源分布,前端弹窗让用户把每个文件数据源映射到本机数据源(同名自动预填,可选「不导入」=映射值 0),`POST /api/annotations/import` 带 mapping JSON 执行;未给映射时回退按数据源名匹配(兼容无映射直接导入),匹配不到的行跳过并计数。表标记 ensure 幂等插入、表描述 upsert 覆盖(model 记 `import`)。接口 `GET /api/annotations/export`(附件下载)、`POST /api/annotations/import`(multipart,返回 新建/更新标记、新增/跳过表标记、覆盖/跳过描述 六项摘要);入口在系统设置页「标记与描述数据」卡片。

## AI Token 与费用统计

> 需求:所有 AI 调用统一记 token 用量与费用,价格可配置(默认 DeepSeek 官方价),支持峰谷价(工作时间/非工作时间两档),统计页柱状图可在金额/token 间切换。

- **记录口径**:`AiService.chat/describeTable/test` 每次调用成功后解析响应 `usage`(prompt/completion/total token)回调 `AiUsageService.record` 落库 `ai_usage_log` 一行(scene/model/输入输出 total/cost/时段 PEAK|VALLEY/时间);响应无 usage(部分兼容接口)或统计落库失败均静默忽略,不影响调用主流程。场景:表说明 TABLE_DOC、自动打标 AUTO_TAG、报告分析 WORD_REPORT、连通测试 TEST(表说明与连通测试在 `AiService` 内部打点,自动打标/报告分析经各自服务注入的 chat lambda 默认实现传入场景)。
- **计费价格配置**(随「AI 配置」保存,ai_config 扩展列 V17 迁移):**峰谷计价开关**(默认开,关闭则只用单一输入/输出价,不区分时段);开启时字段=工作时间(高峰)输入价/输出价 + 非工作时间(谷价)输入价/输出价(元/百万 token)+ **工作时间段**(可多段,`HH:mm-HH:mm,...`,如 `09:00-12:00,14:00-18:00`)+ 周末按谷价开关;任一字段未设回落到配置默认值。**默认 = DeepSeek 官方价**(2026-08 起,旗舰 V4-Pro):工作时间输入 9 元/输出 27 元,非工作时间输入 4.5 元/输出 13.5 元(每百万 token);换用其他模型请在页面按实际修改。config.properties 可用 `ai.peak-valley-enabled` / `ai.peak-input-price` / `ai.work-periods` 等键覆盖默认。
- **计费规则**(`PriceConfig` 纯函数,同 DeepSeek 官方峰谷规则):峰谷计价关闭时按单一输入/输出价计费,时段记 FLAT;开启时处于工作时间段(工作日)按高峰价,其余时间与周末按谷价(非工作时间),时段记 PEAK/VALLEY;工作时间段可多段,起止相同视为停用,`weekendValley=false` 时周末也按工作时间段走高峰价。单次费用 = (输入 token × 对应时段输入价 + 输出 token × 对应时段输出价)/ 100 万。
- **统计页**(`/ai-usage` 侧边栏「AI 统计」):指标卡(调用次数/输入/输出/总 Token/总费用)+ 消耗柱状图(纯 SVG 自绘 `UsageBarChart`,金额=单柱、Token=输入/输出堆叠柱,悬停显示明细;时间范围 7/30/90 天,金额/Token 可切换)+ 场景分布表 + 最近 50 条调用明细(带峰/谷时段标记)。接口 `GET /api/ai-usage/stats?days=`(汇总+每日序列缺日补零+场景分布)与 `GET /api/ai-usage/logs?limit=`(明细倒序)。
