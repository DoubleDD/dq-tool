# 扫描与 Excel 导出

> dq-tool 项目文档,索引见根 [AGENTS.md](../../AGENTS.md)。本文覆盖数据质量扫描能力与 Excel 导出。

- 表级:估算行数、数据+索引占用一览
- 字段级:NULL / 空串 / 自定义空值规则统计与有值率
- 表列表点击表名查看字段明细:已扫描的表直达最近一次扫描的字段级统计;未扫描的表进入结构元数据页(字段名/类型/注释/约束 + 下方索引结构:索引名/唯一性/索引列,接口 `GET /api/datasources/{dsId}/schemas/{schema}/tables/{table}/columns` 与 `.../indexes`,不含统计)
- 结构元数据本地缓存(库/表/字段/索引):库列表统计(schema_stat)、表清单/字段/索引(meta_table/meta_column/meta_index)首次访问从业务库拉取落 H2,之后浏览读缓存不连业务库;库列表/表列表/字段明细页各有「刷新」按钮(接口带 `?refresh=true`)强制从数据源拉最新结构并覆盖缓存;发起扫描时同步刷新表/字段/索引缓存(createScan 刷表清单,planTable 刷字段+索引,失败不影响扫描)
- 大表并发分段扫描(按主键/唯一键切分)、真实进度、断点续扫
- 并发 worker 数可在发起扫描弹窗中设置(1~128,留空用配置默认 `dq.scan.workers`):落库 `scan_job.workers` 供详情展示与续扫恢复;扫描启动时动态调整全局扫描线程池 `ScanExecutor.resize`(每次发起都按本次任务设定调整,避免上次设置残留)
- 扫描可选「生成表描述」(`gen_doc` 随 scan_job 持久化,默认开):每张表 DONE 后由 `ScanDocService` 独立守护线程池(2 worker)异步调 `TableDocService.generate` 生成 AI 表说明落 table_doc;已有非空描述/未配置大模型/任务取消或失败均跳过,同 job 首次 LLM 失败后熔断剩余表;前端扫描对话框复选默认勾选(AI 配置可用时)
- **AI 后续(自动打标/生成表描述)是扫描的串行收尾阶段**:入队/完成经 `ScanAiTracker` 按 job 计数,只有「全部表终态 + AI 后续清零」任务才收尾 DONE/FAILED,此前保持 RUNNING、总进度封顶 99%(表级进度已满但 AI 未走完不算完成);计数在内存,重启清零,断点续扫会为 DONE 表重新补齐 AI 后续(已打标/已有描述的表幂等跳过),收尾语义不受重启影响。打标/表描述按类别分别计数(`ScanAiTracker.AiKind`),经 `ScanJobView.ai`(`tagTotal/tagDone/docTotal/docDone`,连同任务开关快照 `autoTag`/`genDoc`)暴露给前端;扫描与 AI 是并行关系(表 DONE 即触发),前端据此用三段式进度条(`ScanProgressBar.vue`,扫描/AI 打标/AI 表描述 一行三段各自更新)展示,替代旧的单条总进度
- **手动结束(`POST /api/scans/{jobId}/finish` → `ScanService.finish`)**:AI 后续挂死(taskSubmitted 后 taskDone 永远不来)会让任务永远 RUNNING 卡 99%,此接口是人工兜底——与 cancel 同样中断仍在执行的扫描部分(置 CANCELED 挡收尾联动、取消待执行分段、`Statement.cancel()` 中断执行中 SQL、未完成表置 CANCELED),随后 `ScanAiTracker.reset` 清零放弃挂起的 AI 计数,收尾为 DONE(有失败表则 FAILED)而非 CANCELED,**结束后不可续扫**;详情页 RUNNING 时与「取消」并列显示「结束任务」按钮
- 非数值分段键(如 varchar 主键)的边界规划用 seek(keyset)+固定步进:每段从上一段边界之后按步进取边界,避免 OFFSET 深分页每次从索引头扫 N 行(O(N²),大表 varchar 键会把 MySQL 服务器 IO 打满导致新连接握手超时)
- 业务库执行的 SQL 全部打日志(独立 logger `com.example.dq.sql`,默认 INFO):`DataSourceService` 连接出口统一 JDK 代理包装(`SqlLogConnection`),拦截 Statement/PreparedStatement 的 execute 类调用打印完整 SQL 与绑定参数;排查慢 SQL/深分页等场景用,日志文件按天滚动可回溯。本地 H2(repository 包)不走该出口,不打日志;不需要时把 logback 中 `com.example.dq.sql` 调为 WARN/OFF。代理反射调用会拆包 `InvocationTargetException` 原样透出底层 `SQLException`(否则被包成 `UndeclaredThrowableException`,方言层 catch(SQLException) 的降级逻辑会失效)
- 扫描的调度单元是"分段(chunk)",不是表:分段状态持久化在 `scan_chunk` 表,断点续扫只重跑未完成分段
- 规划阶段(`planTable`)发现表不存在或没有字段(`listColumns` 为空)不算失败:直接按空表置 DONE、结果全 0(`ChunkRunner.completeEmptyTable`),联动「空表」标记但不触发 AI 后续(无字段无可分析);续扫(`resume`)同样按此跳过——旧行为是抛错让整个任务 FAILED,现已改为只跳过该表(FAILED 表翻转为 DONE 时不重复计入完成数)
- 无字段标记(`meta_table.no_columns`,V22):扫描规划/续扫或字段明细页访问发现表没有字段(如 Oracle IOT 溢出段 SYS_IOT_OVER_%)时置 TRUE,供识别「可跳过」的表;表有字段时清除。强制刷新表结构(`replaceTables` 整粒度覆盖)后标记随旧行自动还原——重新同步后字段有无未知,待下次访问字段列表或扫描时按实测重新标定
- 大表默认采样估算(行数 > 100 万或体积 > 10GB,阈值可在数据源级别覆盖);MySQL/达梦/OB 的采样是 LIMIT 顺序采样,结果有偏,UI 需标注"估算值"
- Oracle 把空字符串存为 NULL,空串统计恒为 0,这是数据库本身行为,不是 bug
- Oracle ORA-01000(超出打开游标的最大数)防护:扫描 SQL 是一次性字面量文本,连接池长会话上反复硬解析会让会话游标缓存(驻留游标计入 open_cursors)持续累积。工具侧两道防线:① 建池时 `connectionInitSql` 执行 `ALTER SESSION SET session_cached_cursors = 0`(`OracleDialect.connectionInitSql`,方言接口 `DbDialect.connectionInitSql` 默认无),关掉会话游标缓存,语句关闭即真正释放;② `ChunkRunner` 捕获 ORA-01000(异常链上 errorCode=1000,`OracleDialect.isOpenCursorsExceeded`)时调 `DataSourceService.recyclePool` 回收该数据源全部连接池,分段重试自动拿到全新会话;在建连接上的其他分段失败由既有重试逻辑兜底
- Oracle 表体积统计依赖段视图:23ai 起 ALL_SEGMENTS 被移除(DBA_SEGMENTS 仍在);受限账号看不到段视图时(无权限对象 Oracle 也报 ORA-00942)按 ALL_SEGMENTS → DBA_SEGMENTS → USER_SEGMENTS(仅当前用户)→ 不统计 逐级降级(23ai 链从 DBA_SEGMENTS 起),记 warn 日志;探测结果按「用户名@JDBC URL」内存缓存(OracleDialect.segViewCache,换账号/换服务器自动重探,进程重启重置),非首选落点超过 1 小时(SEG_VIEW_REPROBE_MS)从链头重探一次以捕获权限变更
- Oracle LONG/LONG RAW 列归一为 Types.OTHER(`OracleDialect.listColumns`):LONG 不能参与函数与比较(TRIM(LONG) 报 ORA-00932「应为 CHAR,但却获得 LONG」,比较/排序同样不允许),而驱动报为 Types.LONGVARCHAR 会被当成字符列做空串统计、被挑为分段键;归一后仍统计 NULL 数(IS NULL 对 LONG 合法),只是跳过空串统计且不作为分段键
- Oracle DATE/TIMESTAMP 分段键的字面量显式 TO_TIMESTAMP(`OracleDialect.literal` 覆写,格式 'YYYY-MM-DD HH24:MI:SS.FF'):字符串字面量与日期列比较走会话 NLS 隐式转换,格式不匹配即 ORA-01861「文字与格式字符串不匹配」,分段边界 seek、范围谓词、空值规则值均受影响;边界值读取(`readBoundaryValue` 覆写)用 getTimestamp 输出 yyyy-MM-dd HH:mm:ss.SSS 固定格式,round-trip 不依赖会话 NLS
- SQL Server 行数/体积统计用目录视图 sys.partitions + sys.allocation_units(行数口径 index_id 0/1 堆/聚集索引,体积为全部分区 used_pages × 8KB),不用 DMV sys.dm_db_partition_stats——后者要求 VIEW DATABASE STATE 权限,受限账号在库列表页整页报错;目录视图只受元数据可见性约束,普通账号即可
- SQL Server 空串统计的去空白表达式用 LTRIM/RTRIM 而非 TRIM(`SqlServerDialect.trimExpr`):TRIM 是 2017 才引入的内置函数,2016 及以下报「'TRIM' 不是可以识别的内置函数名称」;LTRIM/RTRIM 全版本可用且语义同为去两端空格。表达式内先 `CAST(col AS NVARCHAR(MAX))`:旧 LOB 类型 text/ntext 不支持 LTRIM/RTRIM 与 = '' 比较(报「参数数据类型 text 对于 rtrim 函数的参数 1 无效」),普通 (n)(var)char 转换后语义不变
- SQL Server 字段元数据(`SqlServerDialect.listColumns`/`countColumns`)整表走目录视图 sys.columns/sys.types/sys.indexes,不走 JDBC DatabaseMetaData:mssql-jdbc 的 `getColumns` 实现调用系统存储过程 sp_columns_100,SQL Server 2008 以下(≤2005)无此过程,整库扫描会在规划阶段全部报「找不到存储过程 'sp_columns_100'」;目录视图 2005+ 全版本可用。类型名 → JDBC 类型的映射(`jdbcTypeOf`,含 money/ntext/sysname/uniqueidentifier 等)与展示类型拼接(`displayType`,nvarchar 字节长度减半、-1 即 max)在方言内自维护

## Excel 导出

sheet 顺序:概览 / 表列表 / 「字段汇总」单 sheet 合并所有 DONE 表字段 / 每表字段明细多 sheet / 异常表,列可选,固定前列的表名列名为「英文表名」、表注释列名为「中文表名」。「表列表」含「表描述」可选列(取 table_doc 中 AI 生成/人工维护的表说明,非表注释,按数据源+库+schema 匹配,未生成则为空)。

两个入口共用同一组 sheet 写入逻辑(`ExportService` 内部把各 sheet 写入解耦为「表列表 + 取字段 lambda + 表描述 map」,任务版按 jobId+表名取字段,最新版按 scan_table 快照行 id 取字段):

- **按任务导出**:`GET /api/scans/{jobId}/export`(前端入口:扫描记录/任务详情的「导出 Excel」弹窗 `ExportButton.vue`),sheet 结构如上,概览含任务状态/强制全量/空值规则/起止时间
- **最新扫描结果导出**:`GET /api/datasources/{dsId}/schemas/{schema}/export-latest?db=`(前端入口:表列表页「导出」下拉 →「导出扫描结果」,ExportButton hideTrigger 模式经 ref 唤起弹窗),不依赖指定任务记录——每张表跨任务取最近一次表级 DONE 的扫描快照(`ScanRepository.latestDoneScanTables`,与表列表页「点击表名直达最新结果」同口径),字段明细按快照行 id 取(`listScanColumns`);sheet 少「异常表」(口径内全是 DONE,表列表「状态」列恒为 DONE),概览为最新口径文案(数据源/库·Schema/数据口径说明/最晚扫描完成时间 + 统计总结);无任何 DONE 数据时抛 `IllegalStateException`(409),前端同时按 `latestScans` 映射为空禁用按钮;单测 `ExportServiceTest`(跨任务快照取舍/空数据 409/任务导出 sheet 结构回归)

## 扫描结果 Word 导出(数据库表结构文档)

扫描结果除 Excel 外还可导出 Word 版「数据库表结构文档」:`GET /api/scans/{jobId}/export-word` 同步渲染下载(`dq-scan-{jobId}-表结构.docx`),前端入口是扫描结果导出弹窗(`ExportButton.vue`)底部的「导出 Word」。

- **渲染**:内核 `ScanWordExportService` + poi-tl 模板 `common/src/main/resources/templates/db-structure-report.docx`(由 `scripts/make-word-template-dbstruct.py` 把《水库矩阵平台数据库表结构文档_模板V1.0.docx》改造为标签模板);数据全部来自该任务快照(scan_table/scan_column)+ 表标记(table_tag)+ 库描述(schema_doc),不回连业务库
- **文档结构**:封面(数据源名)+ 一 总体情况(单行统计)/ 二 数据库清单(单行)/ 三 表清单(LoopRow,含表标签列,逗号分隔)/ 四 表结构(逐表「中文名:英文名」H3 + 六列字段表:字段英文/中文/类型/主键/非空/默认值;无注释处填「-」,默认值空填「—」)
- **单库口径**:一个扫描任务 = 一个库(schema),故一/二章各只一行;数据库名取 `db_name`(空则回退 schema 名)
- **四章逐表小节**用 `TableStructsPolicy` 深拷贝模板里的原型小节(H3 标题段 + 字段表)生成——标题编号(numId=7 ilvl=2,自动编 4.1.x)、表头蓝底/边框随克隆保留;渲染后删除原型与 `{{tableStructs}}` 锚点段;三章表清单走 LoopRow(`{{tables}}` 锚点在表头首格,循环行 `[name]` 等),与数据调研报告 1.2 同一写法
- **目录**:模板已置 `w:updateFields`,Word/WPS 打开时自动刷新目录条目与页码
- 渲染与策略有单测 `ScanWordExportTemplateTest`(标签残留/原型删除/行列数/零字段表/无表兜底)

## 数据源整库表结构 Word 导出

库列表页(`Schemas.vue`)「导出 → 导出表结构文档」导出**数据源下所有库(白名单过滤后)**的表结构文档:`GET /api/datasources/{dsId}/export-dbstruct-word` 同步渲染下载(`{数据源名}-数据库表结构.docx`,文件名特殊字符转 `_`),无需勾选、不要求先扫描。

- **数据口径**:与扫描结果 Word 导出(快照)不同,本功能走 `MetadataService` 实时元数据——meta_cache 缓存优先,未缓存回源业务库并落缓存;行数/体积为元数据估算值(`TableStat.estRows/sizeBytes`,null 按 0);表标签取全局表标记(table_tag),库描述取 schema_doc
- **多库两级循环**:多库方言(SQL Server/Kingbase)先 `listDatabases`(已过滤)再逐库展开 schema;单库方言每个 schema 自成一节(H2「数据库:X」= schema 名,与扫描结果导出 dbName 口径一致)。白名单过滤后无任何库 → `IllegalArgumentException`(400)
- **渲染**:内核 `DbStructExportService` + poi-tl 模板 `templates/db-structure-full.docx`(由 `scripts/make-word-template-fulldb.py` 改造,封面保持模板原样不替换数据源名,原型小节文本统一为 `PROTO_*` 标记便于单测断言删除);二章数据库清单走 LoopRow(`{{dbs}}`,每 库+模式 一行),三章表清单/四章表结构分别由 `TableListSectionsPolicy`/`StructSectionsPolicy` 按两级循环克隆模板原型(H2/H3 标题段 + 表格)生成;空 schema 表清单只留表头,无表 schema 四章输出「(无数据表)」;`TableStructsPolicy` 的 setParagraphText/fillRows/setCellText 已提为文件级 internal 函数共用
- **目录**:渲染后由 `rewriteTocEntries` 直接重写 sdt 缓存条目(编号与多级列表一致:N./N.M./N.M.K.),不刷域的查看器也能看到正确条目;TOC 域结构与 dirty/updateFields 标记保留,页码与跳转链接仍由 Word/WPS 刷新域时重建(页码只有排版引擎能算)
- 单测 `DbStructExportTemplateTest`(两库三模式:标签/PROTO 残留、标题样式、行列数、零字段表、无表 schema、空数据源兜底)

## 通用列表导出(各列表页「导出 Excel」)

数据源菜单下除数据源卡片页外的列表页(库列表「导出→导出当前列表」、表列表、字段明细/索引结构/数据预览三个 tab、扫描记录)都有导出按钮,导出内容与页面所见一致(含前端过滤结果,列与表格展示口径相同)。

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
