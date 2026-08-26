# 扫描与 Excel 导出

> dq-tool 项目文档,索引见根 [AGENTS.md](../../AGENTS.md)。本文覆盖数据质量扫描能力与 Excel 导出。

- 表级:估算行数、数据+索引占用一览
- 字段级:NULL / 空串 / 自定义空值规则统计与有值率
- 表列表点击表名查看字段明细:已扫描的表直达最近一次扫描的字段级统计;未扫描的表进入结构元数据页(字段名/类型/注释/约束 + 下方索引结构:索引名/唯一性/索引列,接口 `GET /api/datasources/{dsId}/schemas/{schema}/tables/{table}/columns` 与 `.../indexes`,不含统计)
- 结构元数据本地缓存(库/表/字段/索引):库列表统计(schema_stat)、表清单/字段/索引(meta_table/meta_column/meta_index)首次访问从业务库拉取落 H2,之后浏览读缓存不连业务库;库列表/表列表/字段明细页各有「刷新」按钮(接口带 `?refresh=true`)强制从数据源拉最新结构并覆盖缓存;发起扫描时同步刷新表/字段/索引缓存(createScan 刷表清单,planTable 刷字段+索引,失败不影响扫描)
- 大表并发分段扫描(按主键/唯一键切分)、真实进度、断点续扫
- 并发 worker 数可在发起扫描弹窗中设置(1~128,留空用配置默认 `dq.scan.workers`):落库 `scan_job.workers` 供详情展示与续扫恢复;扫描启动时动态调整全局扫描线程池 `ScanExecutor.resize`(每次发起都按本次任务设定调整,避免上次设置残留)
- 扫描可选「生成表描述」(`gen_doc` 随 scan_job 持久化,默认开):每张表 DONE 后由 `ScanDocService` 独立守护线程池(2 worker)异步调 `TableDocService.generate` 生成 AI 表说明落 table_doc;已有非空描述/未配置大模型/任务取消或失败均跳过,同 job 首次 LLM 失败后熔断剩余表;前端扫描对话框复选默认勾选(AI 配置可用时)
- **AI 后续(自动打标/生成表描述)是扫描的串行收尾阶段**:入队/完成经 `ScanAiTracker` 按 job 计数,只有「全部表终态 + AI 后续清零」任务才收尾 DONE/FAILED,此前保持 RUNNING、总进度封顶 99%(表级进度已满但 AI 未走完不算完成);计数在内存,重启清零,断点续扫会为 DONE 表重新补齐 AI 后续(已打标/已有描述的表幂等跳过),收尾语义不受重启影响
- 非数值分段键(如 varchar 主键)的边界规划用 seek(keyset)+固定步进:每段从上一段边界之后按步进取边界,避免 OFFSET 深分页每次从索引头扫 N 行(O(N²),大表 varchar 键会把 MySQL 服务器 IO 打满导致新连接握手超时)
- 业务库执行的 SQL 全部打日志(独立 logger `com.example.dq.sql`,默认 INFO):`DataSourceService` 连接出口统一 JDK 代理包装(`SqlLogConnection`),拦截 Statement/PreparedStatement 的 execute 类调用打印完整 SQL 与绑定参数;排查慢 SQL/深分页等场景用,日志文件按天滚动可回溯。本地 H2(repository 包)不走该出口,不打日志;不需要时把 logback 中 `com.example.dq.sql` 调为 WARN/OFF。代理反射调用会拆包 `InvocationTargetException` 原样透出底层 `SQLException`(否则被包成 `UndeclaredThrowableException`,方言层 catch(SQLException) 的降级逻辑会失效)
- 扫描的调度单元是"分段(chunk)",不是表:分段状态持久化在 `scan_chunk` 表,断点续扫只重跑未完成分段
- 大表默认采样估算(行数 > 100 万或体积 > 10GB,阈值可在数据源级别覆盖);MySQL/达梦/OB 的采样是 LIMIT 顺序采样,结果有偏,UI 需标注"估算值"
- Oracle 把空字符串存为 NULL,空串统计恒为 0,这是数据库本身行为,不是 bug
- Oracle 表体积统计依赖段视图:23ai 起 ALL_SEGMENTS 被移除(DBA_SEGMENTS 仍在);受限账号看不到段视图时(无权限对象 Oracle 也报 ORA-00942)按 ALL_SEGMENTS → DBA_SEGMENTS → USER_SEGMENTS(仅当前用户)→ 不统计 逐级降级(23ai 链从 DBA_SEGMENTS 起),记 warn 日志;探测结果按「用户名@JDBC URL」内存缓存(OracleDialect.segViewCache,换账号/换服务器自动重探,进程重启重置),非首选落点超过 1 小时(SEG_VIEW_REPROBE_MS)从链头重探一次以捕获权限变更

## Excel 导出

sheet 顺序:概览 / 表列表 / 「字段汇总」单 sheet 合并所有 DONE 表字段 / 每表字段明细多 sheet / 异常表,列可选,固定前列的表名列名为「英文表名」、表注释列名为「中文表名」。「表列表」含「表描述」可选列(取 table_doc 中 AI 生成/人工维护的表说明,非表注释,按数据源+库+schema 匹配,未生成则为空)。

## 扫描结果 Word 导出(数据库表结构文档)

扫描结果除 Excel 外还可导出 Word 版「数据库表结构文档」:`GET /api/scans/{jobId}/export-word` 同步渲染下载(`dq-scan-{jobId}-表结构.docx`),前端入口是扫描结果导出弹窗(`ExportButton.vue`)底部的「导出 Word」。

- **渲染**:内核 `ScanWordExportService` + poi-tl 模板 `common/src/main/resources/templates/db-structure-report.docx`(由 `scripts/make-word-template-dbstruct.py` 把《水库矩阵平台数据库表结构文档_模板V1.0.docx》改造为标签模板);数据全部来自该任务快照(scan_table/scan_column)+ 表标记(table_tag)+ 库描述(schema_doc),不回连业务库
- **文档结构**:封面(数据源名)+ 一 总体情况(单行统计)/ 二 数据库清单(单行)/ 三 表清单(LoopRow,含表标签列,逗号分隔)/ 四 表结构(逐表「中文名:英文名」H3 + 六列字段表:字段英文/中文/类型/主键/非空/默认值;无注释处填「-」,默认值空填「—」)
- **单库口径**:一个扫描任务 = 一个库(schema),故一/二章各只一行;数据库名取 `db_name`(空则回退 schema 名)
- **四章逐表小节**用 `TableStructsPolicy` 深拷贝模板里的原型小节(H3 标题段 + 字段表)生成——标题编号(numId=7 ilvl=2,自动编 4.1.x)、表头蓝底/边框随克隆保留;渲染后删除原型与 `{{tableStructs}}` 锚点段;三章表清单走 LoopRow(`{{tables}}` 锚点在表头首格,循环行 `[name]` 等),与数据调研报告 1.2 同一写法
- **目录**:模板已置 `w:updateFields`,Word/WPS 打开时自动刷新目录条目与页码
- 渲染与策略有单测 `ScanWordExportTemplateTest`(标签残留/原型删除/行列数/零字段表/无表兜底)

## 通用列表导出(各列表页「导出 Excel」)

数据源菜单下除数据源卡片页外的列表页(库列表、表列表、字段明细/索引结构/数据预览三个 tab、扫描记录)都有「导出 Excel」按钮,导出内容与页面所见一致(含前端过滤结果,列与表格展示口径相同)。

- **机制**(`ListExportService` common + `ListExportController` server):前端把当前表格的表头与行(展示口径字符串,空单元格传空串)POST `/api/list-exports` → 后端 POI 渲染 xlsx 内存暂存并返回一次性 token → 前端 `utils/listExport.js` 的 `exportListToExcel` 拿 token 后走既有 `downloadFile` GET `/api/list-exports/{token}` 下载(桌面端 Tauri 原生保存对话框零改动);token 取走即删,5 分钟过期
- **与扫描结果导出的分工**:扫描结果 Excel(`ExportService`)是含业务查询的多 sheet 定制结构;通用列表导出不含任何业务查询,数据完全由前端按展示口径组装,因此各列表页可直接复用
- 数据预览 tab 为服务端分页(每页固定 20 条),导出的是当前页;rows 集合元素不建模为可空——Jackson 3 Kotlin 模块(NewStrictNullChecks)对嵌套集合内的 null 一律 400

## 扫描记录导出/导入(跨机器迁移)

把扫描记录(任务 + 事件时间线 + 表级/分段/字段明细)导出为 JSON 文件,在另一台机器的部署一键导入,扫描记录列表即可看到导入的历史记录。导出还随任务携带每张表的 USER 表标记与表描述(AI 花钱生成的标注数据),导入成功一个任务后随即合并进本机全局标记/描述,避免换机后重新打标与重新生成描述。实现:`ScanTransferService`(common)+ `ScanTransferController`(server),前端入口在扫描记录页(`Scans.vue`)工具栏「导出记录/导入记录」。

- **格式**:`ScanExportFile(app="dq-tool-scans", version=1, exportedAt, jobs[], tagDefs[])`;job 含 events/tables,table 含 chunks/columns + `tags`(USER 标记名列表,EMPTY 系统空表标记由扫描自动维护不导出)+ `doc`(表描述);文件级 `tagDefs` 收引用到的 USER 标记定义(name/color/description);不导出任何内部 id,导入时全部重新生成;`null_rules`/`col_stats` CLOB JSON 原文透传;时间字段为 ISO_LOCAL_DATE_TIME 字符串(与 H2 TIMESTAMP 列的 LocalDateTime 读写口径一致),导入后按时间排序不受影响;tagDefs/tags/doc 为 v1 格式内追加字段,旧导出文件按缺省(空)导入
- **数据源对齐**:与标记导入一致走「预检 + 映射」——预检返回文件内各数据源的 job 数、本机同名数据源 id(前端自动预选)、本机全部数据源;导入 mapping 为「文件数据源名 → 本机数据源 id」,0/缺失/指向不存在的数据源 = 跳过该数据源的全部任务(计入 skipped)
- **去重幂等**:同数据源 + db_name(可空等值,空白一律落 NULL)+ schema_name + created_at 已存在则跳过,重复导入同一文件不产生重复记录
- **结果明细**:跳过(未映射/映射目标不存在/判重)与失败均逐条记 `warnings`(任务标签 + 原因),导入完成弹窗在汇总行下方逐行展示
- **导入事务**:单任务在 `ScanRepository.insertImportedCascade` 同一事务内按 job → event → table → chunk/column 顺序插入,任一失败整体回滚;单任务失败计入 failed 并记 warning,不中断整批
- **标注数据合并**:任务导入成功后随即合并其携带的表标记/表描述——标记定义按 name 合并(不存在则创建、已存在的 USER 标记用文件里的 color/description 覆盖、与系统空表标记重名不动)、表标记 ensure 幂等插入、表描述 upsert 覆盖(model 记 `import`,同 AnnotationTransferService 口径);标注合并失败只记 warning,不影响已导入的扫描记录
- **API**:`GET /api/scans/transfer/export?ids=1,2`(ids 可空 = 全部,下载 `dq-scans-yyyyMMdd-HHmmss.json`)、`POST /api/scans/transfer/preview`(multipart file)、`POST /api/scans/transfer/import`(multipart file + formParam `mapping` JSON);三个路由在 WebServer 中先于 `/api/scans/{jobId}` 注册,避免被路径参数截获
