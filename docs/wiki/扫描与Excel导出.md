# 扫描与 Excel 导出

> dq-tool 项目文档,索引见根 [AGENTS.md](../../AGENTS.md)。本文覆盖数据质量扫描能力与 Excel 导出。

- 表级:估算行数、数据+索引占用一览
- 字段级:NULL / 空串 / 自定义空值规则统计与有值率
- 大表并发分段扫描(按主键/唯一键切分)、真实进度、断点续扫
- 扫描的调度单元是"分段(chunk)",不是表:分段状态持久化在 `scan_chunk` 表,断点续扫只重跑未完成分段
- 大表默认采样估算(行数 > 100 万或体积 > 10GB,阈值可在数据源级别覆盖);MySQL/达梦/OB 的采样是 LIMIT 顺序采样,结果有偏,UI 需标注"估算值"
- Oracle 把空字符串存为 NULL,空串统计恒为 0,这是数据库本身行为,不是 bug
- Oracle 表体积统计依赖段视图:23ai 起 ALL_SEGMENTS 被移除(DBA_SEGMENTS 仍在);受限账号看不到段视图时(无权限对象 Oracle 也报 ORA-00942)按 ALL_SEGMENTS → DBA_SEGMENTS → USER_SEGMENTS(仅当前用户)→ 不统计 逐级降级(23ai 链从 DBA_SEGMENTS 起),记 warn 日志;探测结果按「用户名@JDBC URL」内存缓存(OracleDialect.segViewCache,换账号/换服务器自动重探,进程重启重置),非首选落点超过 1 小时(SEG_VIEW_REPROBE_MS)从链头重探一次以捕获权限变更

## Excel 导出

sheet 顺序:概览 / 表列表 / 「字段汇总」单 sheet 合并所有 DONE 表字段 / 每表字段明细多 sheet / 异常表,列可选,表名/表注释固定前列。
