# AI 功能与表标记

> dq-tool 项目文档,索引见根 [AGENTS.md](../../AGENTS.md)。本文覆盖 AI 表说明、AI 自动打标、表标记与统计。

## AI 表说明

大模型(OpenAI 兼容接口)根据表结构生成表用途描述,页面配置接口信息,手动触发生成,也支持手动编辑,结果存 H2;扫描时可勾选「生成表描述」随扫描自动批量生成(见 [扫描与Excel导出](扫描与Excel导出.md))。

## AI 自动打标

扫描时可选(auto_tag 随 scan_job 持久化,断点续扫仍生效);每张表 DONE 后由 `AutoTagService` 独立守护线程池(2 worker)异步执行(与扫描并行,不占扫描 worker)——以表注释/字段注释/AI 表描述为上下文(三者全空且表非空时抽样前 20 列 100 行业务数据,单元格截断 100 字符),连同候选标记列表(仅 **AI 类型**的 USER 标记,带各标记的描述,帮助模型理解标记含义、精准选择;无描述只列名字;MANUAL 人工类型标记不发给大模型)发给大模型选一个标记自动打上(幂等 ensureTableTag);**对已有标记的表按任务 `auto_tag_mode` 三选一处理(`AutoTagMode`,`scan_job.auto_tag_mode` V54 持久化,存量默认 SKIP 保持升级前行为;续扫补提交打标读 job 行自然生效)**:
- **SKIP(默认,老行为)**:表已有任一 USER 标记即跳过不调大模型,旧标记原样保留;
- **APPEND(增量追加)**:不跳过,模型选出的标记幂等追加,旧标记全部保留;模型返回 NONE/无匹配不动;
- **OVERWRITE(全量覆盖)**:不跳过,先删该表全部 **AI 来源**旧标记关系(`TagRepository.deleteAiTableTags`,不碰 MANUAL/SYSTEM 行)再按模型回答落新标记,回答 NONE/无匹配即「覆盖为空」。
- 三模式共用的边界:模型选中的标记表上已有同标记(其他来源)时不重复打——`ensureTableTag` 的 MERGE 会把既有关系来源改写,保留原关系不动;LLM 调用失败时不清旧标记(删除发生在拿到回答之后)。
- **备份表不参与 AI 打标**:表名以 `_copy`/`_bak`/`_backup`/`_tmp` + 可选序号结尾(不区分大小写,`BackupTableRule` 全项目唯一口径,与 Word 报告「数据冗余分析」同一份规则)的表,由扫描系统联动直接打「备份表」标记,AI 自动打标**直接跳过、不调大模型**(判定不依赖大模型配置,故先于配置/候选检查),并顺手清掉该表历史扫描留下的 AI 来源标记(source=AI;人工 MANUAL 与系统标记不动):这类备份/临时表本就无需业务分类,跳过既省 token 又避免陈旧 AI 标记与新系统标记并存。
未配置大模型/无候选标记静默跳过,同 job 首次 LLM 失败后熔断剩余表;前端共享扫描对话框 `ScanDialog.vue` 有复选(默认勾选),勾选时出现「旧标签处理」单选组(跳过/增量追加/全量覆盖),**提交扫描前前端统一校验 AI 可用性**(`utils/aiCheck.js confirmAiUsable`:先查 `GET /api/ai-config` 的 available 即合并默认配置后有效配置完整,完整再 `POST /api/ai-config/test` 传 {} 实测已存生效配置),不可用弹确认框「将跳过 AI 相关功能」,用户选继续则照常提交、AI 部分由后端静默跳过,选取消留在弹窗。打标与「生成表描述」同属扫描的 AI 收尾阶段:经 `ScanAiTracker` 计数,全部表终态且 AI 清零前任务不收尾(详见 [扫描与Excel导出](扫描与Excel导出.md))。

### 批量 AI 打标(表列表批量打标弹窗 AI 页签,异步后台任务)

不依赖扫描,表列表勾选多张表 → 「批量打标」弹窗切到「AI 打标」页签,候选为**可用于 AI 打标的 USER 标记**(kind=USER 且 tagType=AI,前端默认全选、可取消勾选),「开始打标」先 `confirmAiUsable` 校验 AI 可用性,再 `POST /api/datasources/{dsId}/schemas/{schema}/ai-tag-batch?db=`(body `{tableNames, tagIds}`)——提交校验(数据源存在/表清单非空/`requireUsable`:大模型已配置且勾选 ∩ AI 候选非空)通过后**立即返回 `{taskId}` 关窗**,任务体后台执行(固定 2 线程池,一任务占一 worker、任务内逐表顺序执行,模板同 WordReportExportService;任务落 H2 `ai_tag_batch_task` V75,状态机 PENDING→RUNNING→DONE/FAILED,服务重启残留置 FAILED 不做断点续跑)。进度与完成经**后台任务中心**跟踪:前端 `stores/backgroundTasks.js` 的 `ai-tag` kind 适配器 1s 轮询 `GET /api/ai-tag-batch/active`(active 视图含每个未完成任务——含 PENDING 排队——的 datasourceId/dbName/schemaName/**tableNames 表名清单** V76 落库,JSON 数组),行内显示「批量 AI 打标 N 张表 · 打标中 done/total」;表列表页按 数据源+库+schema 匹配合并活跃任务的 tableNames 得「打标中的表」集合,**标记列对集合内的表前置旋转 loading 图标**(已有标签照常展示),任务从 active 消失即终态、回源 `GET /api/ai-tag-batch/{id}` 弹完成通知(可点击跳表列表页;业务页不随通知自动刷新,但**打标中集合从非空变空时表列表自动重拉打标 map** 显示最新标签,与推导/比对「页面不自动刷新」惯例不冲突——这是本页自己 watch 集合的增量刷新),汇总口径 成功打标/未匹配/跳过失败 三类计数落任务行(tagged/unmatched/skipped_count)。

任务体逐表调 `BatchAiTagService.tagTable` 并分类计数(每张表处理前推进 `progress_done/stage=表名`):

- **上下文口径**:表注释 + 字段(含字段注释),经 MetadataService 缓存优先路径取元数据(未扫描的表也可打标,回源失败有缓存则降级);不传 AI 表描述、**不抽样业务数据**(与扫描后自动打标的抽样口径不同);prompt 组装/回答解析复用 `AutoTagService` 的纯函数(`buildClassifyPrompt`/`parseTag`,ColumnMeta 转 ScanColumnView 只取 名称/展示类型/注释)。
- **落标**:模型选中的标记以 **source=AI** 幂等落标(`ensureTableTag`);表上已有同标记(任意来源)不重复打、保留原关系来源(同扫描打标的边界口径);模型回答 NONE/幻觉标记则该表不打标(计未匹配)。
- **备份表直接跳过不调大模型**(`BackupTableRule` 同一份规则,先于配置/候选检查,计跳过);单表异常(表不存在/候选标记被删/元数据回源失败等)计 跳过/失败 不中断其余表;非扫描场景(无 scanJobId、不经扫描队列、无任务级熔断),AI 用量按场景 AUTO_TAG 照常统计(不关联扫描任务)。

## 备份表系统标记

与「空表」同款的**系统标记**(`tag_def.kind=BACKUP`,`tag_type=0` 系统、不可作为 AI 打标候选;V55 迁移按 name 幂等插入,升级时若已存在用户自建的「备份表」标记会连同其打标关系一并转为系统标记,关系不丢):

- **判定**:`BackupTableRule.isBackupTable(tableName)` = 表名匹配 `_(copy|bak|backup|tmp)\d*$`(不区分大小写);`bak_order`、`tmp_order`、`t_order_copy_log` 这类前缀/中间出现的不命中。
- **联动**:表 DONE 后由 `ChunkRunner` 调 `TagService.syncBackupTag` 自动打/摘(命中打上、不命中摘除,幂等,来源记 `SYSTEM`);**与「AI 自动打标」开关无关**(纯表名规则零成本),无字段的空表跳过路径(`completeEmptyTable`)同样联动。
- **不可人工操作**:打标弹窗/批量打标/标注导入都不含系统标记,编辑/删除走不了(400);`AnnotationTransferService`、`MetadataTransferService` 只导出导入 USER 标记,扫描记录导出/导入则与「空表」一样**只导名字**(定义由各实例迁移自建)并补打标关系。

## 表标记

全局表级标记(数据源+库+表名,一表多标记,名称+颜色+描述+**标记类型**:`tag_def.tag_type` V30 迁移,数字码 0=系统(空表 kind=EMPTY、备份表 kind=BACKUP)/ 1=可用于AI打标 / 2=仅用于人工打标,缺省 1 与升级前行为一致,存量 USER 标记全部 1);**打标来源**(`table_tag.source` V32 迁移):MANUAL 人工打标(打标弹窗勾选/批量打标/标注导入)/ AI 自动打标 / SYSTEM 系统联动(空表、备份表标记),字段明细页「标签」页签按 人工打标/系统打标 区分展示与筛选;「空表」系统标记随扫描结果自动打/摘、「备份表」按表名自动打/摘(见上节),用户标记在表列表/字段明细页「标签」页签的打标弹窗勾选(单表整体替换,可就地新建,新建默认仅用于人工打标),也可在表列表勾选多表后点「批量打标」弹窗批量打上选中标记(`PUT .../table-tags`,只增不删、幂等,返回新增/已存在跳过计数),编辑/删除统一在「标记统计」页维护(列表带类型徽标 系统/可用于AI打标/仅用于人工打标,弹窗可改类型;系统标记不可编辑/删除);库列表标签块点击筛选 + 独立「标记统计」页(需求 `docs/requirements/表标记与统计需求.md`,实施见 `docs/plans/表标记与统计-实施计划.md`)。

## 表所属系统

独立于标记体系:一张表最多归属一个「所属系统」(`table_system`,数据源+库+schema+表四元组唯一,一表一系统,重复设置覆盖),用于区分表属于哪个业务系统。**前端入口暂缓**:批量设置按钮已改为「批量打标」(走标记体系),「所属系统」列默认隐藏(可在「列设置」开启显示);后端接口(`GET/PUT .../table-systems`,systemName 空白=清除,返回 updated/cleared)与「标记与描述数据」导出/导入(见下节)保留,后续要恢复前端只需重接 `TableSystemDialog`(组件文件仍在 `web/src/components/`)。

## 标记与描述数据导出/导入

换机迁移用:`AnnotationTransferService` 把 USER 标记定义(name/color/description/tagType,不含 id;tagType 为 v1 内追加可选字段,旧文件缺省 → 新建落 AI、更新保留原值)、USER 标记的表-标记关联、全部表描述(table_doc)、表所属系统(table_system)打包成 JSON(`app=dq-tool-annotations, version=1`;tableSystems 为 v1 内追加可选字段,旧文件按空导入),表级数据导出为 数据源名+db+schema+table(不导内部 id)。导入按 name 合并标记(不存在创建、已存在覆盖 color/description;系统空表标记不动);表级行的数据源对应走**显式映射**——不同机器上同一数据源命名可能不同,导入前先 `POST /api/annotations/import/preview` 解析文件里的数据源分布,前端弹窗让用户把每个文件数据源映射到本机数据源(同名自动预填,可选「不导入」=映射值 0),`POST /api/annotations/import` 带 mapping JSON 执行;未给映射时回退按数据源名匹配(兼容无映射直接导入),匹配不到的行跳过并计数。表标记 ensure 幂等插入、表描述 upsert 覆盖(model 记 `import`)、所属系统 upsert 覆盖。接口 `GET /api/annotations/export`(附件下载)、`POST /api/annotations/import`(multipart,返回 新建/更新标记、新增/跳过表标记、覆盖/跳过描述、覆盖/跳过所属系统 摘要);入口在系统设置页「标记与描述数据」卡片。导入解析经 `TransferJson` 支持 GBK 转存文件兜底与未知字段忽略(口径见 扫描与Excel导出.md)。

## AI Token 与费用统计

> 需求:所有 AI 调用统一记 token 用量与费用,价格可配置(默认 DeepSeek 官方价),支持峰谷价(工作时间/非工作时间两档),统计页柱状图可在金额/token 间切换。

- **记录口径**:`AiService.chat/describeTable/test` 每次调用成功后解析响应 `usage`(prompt/completion/total token)回调 `AiUsageService.record` 落库 `ai_usage_log` 一行(scene/model/输入输出 total/cost/时段 PEAK|VALLEY/时间 + 请求内容 `[system]+[user]` 与模型返回正文,落库截断 5 万字符,供 prompt 调优回溯);响应无 usage(部分兼容接口)或统计落库失败均静默忽略,不影响调用主流程。场景:表说明 TABLE_DOC、自动打标 AUTO_TAG、报告分析 WORD_REPORT、关系推导 RELATION_INFER、比对匹配 COMPARE_MATCH(数据比对匹配逻辑 3 的残余对象归一化配对,见 [数据比对](数据比对.md))、比对映射 COMPARE_MAPPING(数据比对列级对比的字段映射预生成与批量导入映射推导)、比对时间 COMPARE_TIME(数据比对导出「数据最新更新时间」在名称/注释未命中时的字段语义匹配)、连通测试 TEST(表说明与连通测试在 `AiService` 内部打点,自动打标/报告分析/关系推导/比对匹配/比对映射/比对时间经各自服务注入的 chat lambda 默认实现传入场景)。
- **独立库存储**:用量流水存独立 H2 文件库 `data/dqaiusage.mv.db`(调用量大且含请求/响应内容,与主库 dqconfig 分离;独立 Flyway 脚本目录 `db/migration-aiusage`);扫描触发的调用在记录时快照 `scan_job_id` + 标签(数据源名 库/schema + 任务创建时间,冗余存储不跨库 join,任务/数据源删除不影响统计);老版本主库 `ai_usage_log` 数据启动时一次性搬迁(`migrateLegacyIfEmpty`,新库非空即跳过,主库老表保留不再写入)。
- **计费价格配置**(随「AI 配置」保存,ai_config 扩展列 V17 迁移):**峰谷计价开关**(默认开,关闭则只用单一输入/输出价,不区分时段);开启时字段=工作时间(高峰)输入价/输出价 + 非工作时间(谷价)输入价/输出价(元/百万 token)+ **工作时间段**(可多段,`HH:mm-HH:mm,...`,如 `09:00-12:00,14:00-18:00`)+ 周末按谷价开关;任一字段未设回落到配置默认值。**默认 = DeepSeek 官方价**(2026-08 起,旗舰 V4-Pro):工作时间输入 9 元/输出 27 元,非工作时间输入 4.5 元/输出 13.5 元(每百万 token);换用其他模型请在页面按实际修改。config.properties 可用 `ai.peak-valley-enabled` / `ai.peak-input-price` / `ai.work-periods` 等键覆盖默认。
- **计费规则**(`PriceConfig` 纯函数,同 DeepSeek 官方峰谷规则):峰谷计价关闭时按单一输入/输出价计费,时段记 FLAT;开启时处于工作时间段(工作日)按高峰价,其余时间与周末按谷价(非工作时间),时段记 PEAK/VALLEY;工作时间段可多段,起止相同视为停用,`weekendValley=false` 时周末也按工作时间段走高峰价。单次费用 = (输入 token × 对应时段输入价 + 输出 token × 对应时段输出价)/ 100 万。
- **统计页**(`/ai-usage` 侧边栏「AI 统计」):指标卡(调用次数/输入/输出/总 Token/总费用)+ 消耗柱状图(纯 SVG 自绘 `UsageBarChart`,金额=单柱、Token=输入/输出堆叠柱,悬停显示明细;时间范围 7/30/90 天,金额/Token 可切换;维度可按天/按扫描切换——按扫描=按扫描任务聚合,扫描后表描述与自动打标的调用经 `ai_usage_log.scan_job_id` 关联,悬停显示数据源/库/schema+时间标签,手动生成/连通测试等无任务关联的调用不计入该维度)+ 场景分布表 + 最近调用明细分页(带峰/谷时段标记,页大小 20/50/100)。接口 `GET /api/ai-usage/stats?days=`(汇总+每日序列缺日补零+场景分布)、`GET /api/ai-usage/scan-series?days=`(按扫描任务聚合序列)与 `GET /api/ai-usage/logs?page=&size=`(明细倒序分页,返回 items+total)。
