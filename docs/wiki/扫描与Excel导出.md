# 扫描与 Excel 导出

> dq-tool 项目文档,索引见根 [AGENTS.md](../../AGENTS.md)。本文覆盖数据质量扫描能力与 Excel 导出。

- 表级:估算行数、数据+索引占用一览
- 字段级:NULL / 空串 / 自定义空值规则统计与有值率
- 表列表点击表名查看字段明细:已扫描的表直达最近一次扫描的字段级统计;未扫描的表进入结构元数据页(字段名/类型/注释/约束 + 下方索引结构:索引名/唯一性/索引列,接口 `GET /api/datasources/{dsId}/schemas/{schema}/tables/{table}/columns` 与 `.../indexes`,不含统计)
- 结构元数据本地缓存(库/表/字段/索引):库列表统计(schema_stat)、表清单/字段/索引(meta_table/meta_column/meta_index)首次访问从业务库拉取落 H2,之后浏览读缓存不连业务库;库列表/表列表/字段明细页各有「刷新」按钮(接口带 `?refresh=true`)强制从数据源拉最新结构并覆盖缓存;发起扫描时同步刷新表/字段/索引缓存(createScan 刷表清单,planTable 刷字段+索引,失败不影响扫描)
- 大表并发分段扫描(按主键/唯一键切分)、真实进度、断点续扫
- 非数值分段键(如 varchar 主键)的边界规划用 seek(keyset)+固定步进:每段从上一段边界之后按步进取边界,避免 OFFSET 深分页每次从索引头扫 N 行(O(N²),大表 varchar 键会把 MySQL 服务器 IO 打满导致新连接握手超时)
- 业务库执行的 SQL 全部打日志(独立 logger `com.example.dq.sql`,默认 INFO):`DataSourceService` 连接出口统一 JDK 代理包装(`SqlLogConnection`),拦截 Statement/PreparedStatement 的 execute 类调用打印完整 SQL 与绑定参数;排查慢 SQL/深分页等场景用,日志文件按天滚动可回溯。本地 H2(repository 包)不走该出口,不打日志;不需要时把 logback 中 `com.example.dq.sql` 调为 WARN/OFF
- 扫描的调度单元是"分段(chunk)",不是表:分段状态持久化在 `scan_chunk` 表,断点续扫只重跑未完成分段
- 大表默认采样估算(行数 > 100 万或体积 > 10GB,阈值可在数据源级别覆盖);MySQL/达梦/OB 的采样是 LIMIT 顺序采样,结果有偏,UI 需标注"估算值"
- Oracle 把空字符串存为 NULL,空串统计恒为 0,这是数据库本身行为,不是 bug
- Oracle 表体积统计依赖段视图:23ai 起 ALL_SEGMENTS 被移除(DBA_SEGMENTS 仍在);受限账号看不到段视图时(无权限对象 Oracle 也报 ORA-00942)按 ALL_SEGMENTS → DBA_SEGMENTS → USER_SEGMENTS(仅当前用户)→ 不统计 逐级降级(23ai 链从 DBA_SEGMENTS 起),记 warn 日志;探测结果按「用户名@JDBC URL」内存缓存(OracleDialect.segViewCache,换账号/换服务器自动重探,进程重启重置),非首选落点超过 1 小时(SEG_VIEW_REPROBE_MS)从链头重探一次以捕获权限变更

## Excel 导出

sheet 顺序:概览 / 表列表 / 「字段汇总」单 sheet 合并所有 DONE 表字段 / 每表字段明细多 sheet / 异常表,列可选,表名/表注释固定前列。
