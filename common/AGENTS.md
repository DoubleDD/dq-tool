# AGENTS.md — common(共享业务内核)

> 本文件面向 AI 编码代理。这是 dq-tool 的共享内核模块(`:common`),承载 server(Javalin Web 版)的全部业务逻辑。项目文档与代码注释统一使用中文。

## 定位与边界

- **纯 Kotlin JVM 库,无任何 Web/桌面框架依赖**:不得引入 Javalin、Servlet 等壳层依赖
- **纯阻塞 API**:禁止 `suspend fun`,异步/轮询由壳层负责
- **内部 JSON 只用 Jackson 2**(`com.fasterxml`,如 AiService/ScanService/ChunkRunner 自持 mapper);server web 层的 Jackson 3(`tools.jackson`)不得 import 进内核
- 所有数据库差异收敛在 `dialect/` 包,service/scan 层不允许出现库特定 SQL 或分支;标识符必须经 `DbDialect.quote()`
- 扫描调度单元是分段(chunk),状态持久化在 `scan_chunk`,断点续扫只重跑未完成分段

## 结构

```
build.gradle.kts           kotlin("jvm")(版本在根声明)+ HikariCP(api 暴露)/H2/POI+poi-tl/Jackson2/Flyway/slf4j/8 个 JDBC 驱动 runtimeOnly
src/main/kotlin/com/example/dq/
  config/AppConfig.kt      内核配置(data class):dataDir/scan/securitySecret/ai 默认值/licensePublicKey;
                           server 由 KernelConfigAdapter 从 yml 映射
  dialect/                 DbDialect + AbstractDialect + 8 方言 + DialectFactory(object 单例)
  discovery/               LanDiscoveryService 局域网实例发现(纯 JDK DatagramSocket 广播心跳+监听,SO_REUSEADDR 同机多实例、macOS 叠加 SO_REUSEPORT 保证广播全员投递,逐目标独立发送互不中断、失败目标 10 分钟冷却防刷屏+重解析自动探测目标,peer 表纯内存;广播目标可注入走回环单测)
  model/                   data class/枚举;3 个请求类带 @field:NotNull/@field:NotBlank(server 用 hibernate-validator 触发)
  repository/              Jdbc.kt 薄封装 + 13 仓储(TableSystemRepository:表所属系统四元组存储,一表一系统,upsert 幂等 + 批量清除;MetaCacheRepository:库/schema 清单(meta_database)+ 表/字段/索引结构本地缓存 + 整库字段清单缓存(meta_schema_column,SQL 控制台智能提示用,支持 schema 级整库覆盖与按表分批覆盖),懒加载 + 手动/扫描刷新,meta_cache_flag 区分「未缓存」与「已缓存但为空」;AiUsageRepository:AI 调用用量流水——独立 H2 库 dqaiusage,含请求/响应内容,记录时快照扫描标签不跨库 join,启动时一次性搬迁主库老数据 migrateLegacyIfEmpty,按日/按场景/按扫描聚合 + 最近明细分页)+ SchemaInit(Flyway 迁移封装,location 参数区分主库/AI 用量库脚本目录)
  scan/                    ScanExecutor(线程池)/ ChunkRunner(表 DONE 后联动 TagService 自动打/摘「空表」标记 + 提交 AutoTagService、ScanDocService 异步入队)/ ScanAiTracker(AI 后续按 job 计数与任务收尾:全部表终态且 AI 清零才置 DONE/FAILED,此前 RUNNING 进度封顶 99%;打标/表描述按 AiKind 分类计数,经 ScanJobView.ai 暴露给前端三段式进度条;reset 供 ScanService.finish 手动结束清零放弃挂起的 AI 后续)/ InterruptRecovery
  service/                 28 个服务(含 LanShareService 局域网共享编排——实例身份/发现按开关启停/共享出口与拉取导入均复用 Transfer 口径(细则见 docs/wiki/局域网共享.md)、ChangelogService 更新日志——classpath /CHANGELOG.md 解析成版本条目供 /api/changelog,资源缺失兜底空列表、TableSystemService 表所属系统——批量设置/清除,systemName 空白=清除,随 AnnotationTransfer 导出导入、LicenseService、ExportService 扫描结果 Excel 导出——SXSSF 流式写 xlsx,按任务快照(概览/表列表/字段汇总/字段明细/异常表五 sheet)与最新快照(exportLatest:每表最近一次表级 DONE 跨任务取,无 DONE 数据 409,不生成异常表 sheet)双入口,sheet 写入解耦为「表列表+取字段 lambda+表描述 map」、ListExportService 通用列表导出——前端 POST 所见表格数据(表头+行字符串)渲染 xlsx 内存暂存,返回一次性 token 供 GET 下载(取走即删/5 分钟过期)、DataSourceTransferService 数据源导入导出、AnnotationTransferService 标记与描述数据导出/导入——按 name 合并标记、按数据源名匹配表级行、ScanTransferService 扫描记录导出/导入——任务+事件+表/分段/字段明细打包 JSON 跨机迁移,随任务携带表级 USER 标记(定义收文件级 tagDefs)与空表系统标记关系(只导名字,定义由各实例迁移自建)及表描述,导入时按 name 合并标记、ensure 打标(含空表关系)、upsert 描述(标注合并失败只记 warning),数据源预检映射,按 数据源+db+schema+created_at 去重,单任务事务插入不中断整批,PreviewService 表数据预览——服务端分页(pageRowsSql 方言分页 + COUNT(*) 实时总数)、行值序列化字符串/null、单元格截断 1000 字符、exportTable 同过滤/排序口径全量导出(SXSSF 流式 xlsx,pageRowsSql offset=0+limit=20 万行硬上限静默截断,不做 COUNT(*))、SqlConsoleService SQL 控制台——任意 SQL 原文透传(JDBC)执行,有 ResultSet 返回列名(getColumnLabel)+行(上限 1000 行,超出截断标记 truncated),否则返回受影响行数(DDL 可 -1),行值序列化/单元格截断同预览、单条超时同分段扫描,支持选库执行(schema 参数:多库方言走按库分池切 catalog,其余方言经 DbDialect.useSchema/currentSchema 会话级切 schema 并在归还池化连接前恢复防串库)、SshTunnelService SSH 隧道本地端口转发、AutoTagService 扫描后 AI 自动打标——2 worker 守护线程池,表注释/字段注释/表描述全空时抽样 100 行业务数据发给 LLM 选标记(候选=AI 类型 USER 标记,带标记描述;MANUAL 人工类型不发给大模型),幂等只增不删,入队/完成经 ScanAiTracker 计数串入扫描收尾,LLM 调用点可注入 fake 便于单测,构造器注入、ScanDocService 扫描后生成表描述——scan_job.gen_doc 开关(默认开),表 DONE 后 2 worker 守护线程异步复用 TableDocService.generate,已有描述/未配大模型/任务终止跳过,入队/完成经 ScanAiTracker 计数串入扫描收尾,LLM 失败按 job 熔断、WordReportService Word 数据调研报告导出——poi-tl 模板渲染(模板 resources/templates/data-survey-report.docx),封面+一~四章全量;取每库最近 DONE 历史快照,选中库未全表扫描 409 拦截,体积快照缺失实时补算;分组 vMerge 表与第三章按 USER 标记分节走 WordReportTables 代码建表(LoopRow 不适用合并组);分析文字经可注入 LLM 调用点生成,失败落「(待人工编写)」、ScanWordExportService 扫描结果 Word 导出(数据库表结构文档)——poi-tl 模板 resources/templates/db-structure-report.docx,纯快照数据同步渲染,三章表清单 LoopRow、四章逐表小节由 TableStructsPolicy 克隆模板原型生成;模板由 scripts/make-word-template-dbstruct.py 改造、DbStructExportService 数据源整库表结构 Word 导出——poi-tl 模板 resources/templates/db-structure-full.docx(scripts/make-word-template-fulldb.py 改造,多库版),导出数据源下所有白名单过滤后的库;数据走 MetadataService 实时元数据(meta_cache 缓存优先,未扫描也可导出),行数/体积为元数据估算;三章按 库→模式、四章按 模式→表 两级循环,由 TableListSectionsPolicy/StructSectionsPolicy 克隆模板原型生成,同步渲染下载、AiUsageService AI 调用 Token/费用统计——AiService 每次调用成功回调记录(场景 TABLE_DOC/AUTO_TAG/WORD_REPORT/TEST),费用按价格配置计算,价格默认 DeepSeek 官方价(2026-08,工作时间输入 9 元/输出 27 元,非工作时间 4.5/13.5 元每百万)且支持峰谷计价开关(默认开,关闭则只用单一输入/输出价,不区分时段;开启时按两档计费:工作时间段可多段,默认 9-12/14-18,按高峰价,其余时间与周末按谷价,时段记 FLAT/PEAK/VALLEY),统计汇总/按日序列/按扫描任务序列(扫描后表描述/自动打标的调用经 scan_job_id 关联)/场景分布/最近明细分页)、SampleExportService 表格批量导入数据源+表数据抽样导出——上传 xlsx 经 SampleTableExcelParser 解析(DataFormatter 取显示字符串,必需列缺失行收集 ROW_SKIPPED),按 dsKey(type|host小写|port|username|database,DM 不含库名)去重,测连 4 线程并发+单条 20s 超时,建档/复用/修复并写 data_source.conn_status 标记,再按 数据源×类别 分组各产出一个 xlsx(首 sheet「预览目录」最后回填,逐表 sampleRowsSql 抽样 50 行,单元格截断 1000),任务目录打包 zip;任务/明细落 sample_export/sample_export_item,单线程 executor;运行中可暂停/恢复(任务级检查点挂起等待,数据源实测与单表查询中途不打断),支持批量删除(完成/失败删记录与产物,暂停中=取消并删,运行/排队跳过),服务重启 PENDING/RUNNING/PAUSED 统一置 FAILED)
  license/LicenseCodec.kt  授权码编解码与 Ed25519 验签(object 纯函数;payload 第 8 段为逗号分隔功能列表)
  license/LicenseFeature.kt 授权码功能清单(9 项;基础功能集=全部业务功能恒可用,受控功能 logs/license_admin 需授权码显式包含)
  env/ServiceEnv.kt        服务组装器:双 H2 连接池(主库 dqconfig + AI 用量库 dqaiusage)+ 全部 service 对象图,server 启动时构建一次;
                           构造不做持久化重活(建表/迁移/中断恢复),由 initDatabase() 显式完成——
                           server 先绑定端口开窗、前端轮询 /api/health,再调 initDatabase 置就绪;
                           对 Java 友好(属性即 getter,dataSource 暴露给 server 的 AppShutdown)
  util/CryptoUtil.kt       AES-GCM(数据库密文);TransferCrypto(导出文件固定口令)/NavicatCrypto(.ncx 密码解密);TransferJson(三种导出文件 JSON 解析入口:UTF-8 优先、GB18030 兜底且记 warn 日志、忽略未知字段);
                           JdbcUrlRewriter(JDBC URL host:port 解析与改写,SSH 隧道用)
src/main/resources/db/migration/
  V1__baseline.sql         共有表全量建表
  V2__license_info.sql     授权信息表 + 老库列补齐(ALTER IF NOT EXISTS)
  V3__tag.sql              表标记:tag_def(含系统「空表」标记幂等插入)+ table_tag(外键级联删除)
  V4__data_source_ssh.sql  数据源 SSH 隧道配置列(ALTER IF NOT EXISTS;三个秘密字段加密存储)
  V5__data_source_schema_filter.sql  数据源库过滤白名单列(ALTER IF NOT EXISTS;逗号分隔,空=不过滤)
  V6__license_record.sql   授权码签发留档表(授权码管理,仅配置签发私钥的管理员实例;含绑定的软件版本号)
  V7__scan_job_auto_tag.sql  scan_job.auto_tag 列(AI 自动打标开关,ALTER IF NOT EXISTS;持久化保证断点续扫/重启后续扫仍读到开关)
  V8__schema_doc.sql         库级描述表 schema_doc(库列表页可编辑,Word 报告「实例描述」列)
  V9__report_export.sql       Word 报告异步导出任务表 report_export(任务列表轮询进度)
  V10__meta_cache.sql        结构元数据本地缓存:meta_table(表清单/注释/估算行数/体积)/meta_column(字段)/meta_index(索引列展开多行)+ meta_cache_flag(缓存存在标记)
  V11__scan_job_workers.sql   scan_job.workers 列(并发线程数,留空用全局默认)
  V12__license_record_features.sql  license_record.features 列(授权码功能列表留档,8 段新格式;旧记录 NULL=仅基础功能)
  V13__system_settings.sql    系统设置单行表(全局扫描默认值覆盖,NULL=用配置默认)
  V14__scan_job_gen_doc.sql   scan_job.gen_doc 列(扫描后生成表描述开关,默认 TRUE)
  V15__tag_description.sql    tag_def.description 列(标记描述,供 AI 自动打标理解标记含义)
  V16__ai_usage_log.sql      AI 调用用量流水表(场景/模型/输入输出 total token/费用/峰谷时段;2026-08 已迁独立库,主库此表仅为搬迁源,不再写入)
  V17__ai_config_price.sql   ai_config 计费价格列(峰谷计价开关 peak_valley_enabled、工作时间高峰输入/输出价(关闭时兼任单一价)、非工作时间谷价输入/输出价、工作时间段 work_periods(可多段,HH:mm-HH:mm,...)、周末按谷价;NULL=用默认 DeepSeek 价)
  V18__data_source_group.sql  data_source.group_name 列(数据源分组名,自由文本,空=未分组;ALTER IF NOT EXISTS)
  V19__system_settings_browser.sql  system_settings.browser_app 列(应用模式首选浏览器 id,NULL=自动;清单由 server 壳层探测,ALTER IF NOT EXISTS)
  V20__ai_usage_log_split_cost.sql  ai_usage_log 输入/输出分项费用列 prompt_cost/completion_cost(统计页悬停分项金额;老数据 NULL=无分项)
  V21__ai_usage_log_scan_job.sql    ai_usage_log.scan_job_id 列(扫描触发的表描述/自动打标调用关联扫描任务,其余为 NULL;统计页趋势图「按扫描」维度)
  V22__meta_table_no_columns.sql    meta_table.no_columns 列(无字段标记:扫描/续扫/字段明细访问发现表没有字段时置 TRUE 供跳过;replaceTables 覆盖刷新自动还原)
  V23__kingbase_multi_db.sql        Kingbase 升级多库方言:清空存量 KINGBASE 数据源的 schema 级旧白名单(新口径下白名单作用于数据库级,旧值匹配不到任何数据库)
  V24__sample_export.sql            表格批量导入+抽样导出:sample_export/sample_export_item 任务与明细表 + data_source.conn_status/conn_error/conn_checked_at 连接状态标记列(ALTER IF NOT EXISTS)
  V25__scan_job_db_version.sql      scan_job.db_version 列(目标数据库版本号,任务创建时从连接元数据快照;ALTER IF NOT EXISTS)
  V26__sample_export_item_sample_limit.sql  sample_export_item.sample_limit 列(抽样导出按 Excel「数据量」列逐表控制抽样条数,NULL=默认 50;ALTER IF NOT EXISTS)
  V27__meta_schema_column.sql     SQL 控制台整库字段清单本地缓存 meta_schema_column(listSchemaColumns lite 版:表名/字段名/展示类型/注释);缓存标记复用 meta_cache_flag(kind=SCOLUMN,table_name 空串=schema 级,具体表名=分批缓存)
  V28__manual_collect.sql         人工采集:manual_collect 收藏清单表(数据源+库+schema+表四元组唯一,table_comment 注释快照;无外键,数据源删除后记录保留)
  V29__meta_database.sql          库/schema 清单本地缓存 meta_database(db_name 空串=数据源级库清单,db_name=库名=该库 schema 清单;ordinal 保持方言返回顺序);缓存存全量、白名单读取路径过滤;标记复用 meta_cache_flag(kind=DATABASE/SCHEMA)
  V30__tag_def_tag_type.sql       tag_def.tag_type 列(标记类型数字码:0=系统 / 1=可用于AI打标 / 2=仅用于人工打标,AI 自动打标只把类型 1 发给大模型;存量 USER 默认 1 保持旧行为,空表标记置 0)
  V31__table_system.sql           表所属系统 table_system(数据源+库+schema+表四元组唯一,一表一系统;独立于 tag 标记体系,随 AnnotationTransfer 导出导入)
  V32__table_tag_source.sql       table_tag.source 列(打标来源:MANUAL 人工打标 / AI 自动打标 / SYSTEM 系统联动-空表标记;存量默认 MANUAL,空表标记关系修正为 SYSTEM;字段明细页「标签」页签按 人工/系统 区分展示)
  V33__lan_share.sql             局域网共享:system_settings 加 lan_enabled/instance_id/instance_name 三列(ALTER IF NOT EXISTS)
  V34__lan_manual_peers.sql      局域网共享:system_settings.lan_manual_peers 列(手动添加实例清单,逗号分隔 host:port;广播发现不可用的网络按地址直连探测)
src/main/resources/db/migration-aiusage/   AI 用量独立库(dqaiusage)迁移脚本,独立 flyway_schema_history
  V1__ai_usage_log.sql      用量流水全量建表:token/费用/峰谷时段 + scan_job_id + 扫描标签快照(scan_label/scan_created_at)+ 请求/响应内容(request_content/response_content CLOB,记录截断 5 万字符,供 prompt 调优回溯)
src/test/kotlin/           方言/分段/级联删除/标记(TagRepository/TagService)/AI prompt/授权码/Flyway 迁移单测 + Testcontainers 端到端(MySQL/PG/SQLServer;SSH 隧道 SshTunnelIntegrationTest:linuxserver/openssh-server 跳板机 + MySQL 网络别名,注意该镜像 sshd 监听 2222 且默认 AllowTcpForwarding no 需 custom-cont-init.d 打开)
```

## 库表结构变更(取代旧 schema.sql 追加 ALTER 规则)

- 一律新增 `db/migration/V{n}__描述.sql`;**已发布的迁移文件禁止修改**(Flyway checksum 校验)
- 不允许破坏性变更(DROP/改列类型);新库从 V1 全量执行,老库 baseline 后增量执行,两条路径必须收敛
- `SchemaInit.run` 已配置 `baselineOnMigrate(true)` + `baselineVersion("1")`,勿改
- **快速路径(2026-08 启动优化)**:迁移脚本随软件版本固定,`SchemaInit.run` 先查 `flyway_schema_history`
  最新成功版本并与 classpath 扫描出的最大脚本版本比较,已是最新(≥)直接跳过 Flyway(实测省 ~0.8s 启动);
  无 history 表(新库/老库)、脚本扫描失败等一切异常都回落执行 Flyway,宁慢勿错。返回 Boolean 表示是否实际执行

## 构建与测试

```bash
./gradlew :common:test    # 在仓库根目录执行;Testcontainers 需 Docker
```

- OrbStack 用户:`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock ./gradlew :common:test`
- 达梦/金仓/OceanBase/Oracle/瀚高 方言无自动化覆盖,改动只能接真实环境手动验证
- LLM 实际调用无自动化覆盖(prompt 组装有 AiServiceTest)
