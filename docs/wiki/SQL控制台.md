# SQL 控制台

> 一级页面「SQL 控制台」(`/sql-console`,侧边栏入口):选择数据源后直接执行任意 SQL(类 DataGrip 交互),查看结果集或受影响行数。纯新增独立功能,不依赖扫描任务。

## 页面与 API

| 入口 | 说明 |
|---|---|
| `POST /api/datasources/{dsId}/sql/execute` | 执行 SQL 原文,返回结果集(列名+行)或受影响行数 |

- 页面:`web/src/views/SqlConsole.vue`(CodeMirror 6 编辑器 + 动态列结果表 + localStorage 历史);业务内核:`common` 的 `SqlConsoleService`;server 仅薄壳 `SqlConsoleController`(路由注册在 `WebServer`)。
- 请求体 `{ "sql": "..." }`,sql 为 SQL 原文,透传业务库(JDBC)执行;**一次请求执行单条语句**(有选中片段时前端只发送选中部分)。
- 响应(query=true 有结果集):`{ query, columns, rows, total, truncated, updateCount: -1, durationMs }`;非查询:`{ query: false, columns: [], rows: [], updateCount, durationMs }`(DDL 的 updateCount 可为 -1)。

## 执行口径(与数据预览同约定)

- 连接走 `DataSourceService.getConnection`,SSH 隧道/连接池/多库切 catalog 自动生效;`use` 块结束即归还池。
- 单条 SQL 超时 = 系统设置 `statementTimeoutSeconds`(与分段扫描/数据预览同口径)。
- 列名取 `ResultSetMetaData.getColumnLabel`;行值 `getObject().toString()`,NULL 保 null,单元格截断 1000 字符(防 CLOB 撑爆响应)。
- **结果集最多返回 1000 行**(超出即停,`truncated=true` 告知前端截断),防止大表无过滤查询打爆内存/响应。
- 错误映射沿用统一异常:SQL 错误 502,空白 SQL 400(`@NotBlank`);目标库连不上(池初始化失败)按 RuntimeException → 500,与表数据预览同口径。
- 不做语句限制/只读限制:本工具仅内网单机部署,任何能访问端口的人都能操作所有数据源(与项目定位一致)。

## 前端交互

- **执行**:按钮或 `Ctrl/Cmd+Enter`;优先执行编辑器**选中片段**(模拟 DataGrip),无选中取全文;未选数据源时按钮禁用。
- **结果**:查询 → 动态列 `el-table` + 本地分页(20/50/100/200),截断时 warning 提示;非查询 → 「执行成功,影响 N 行,耗时 X ms」;失败 → 结果区内联错误(axios 拦截器全局弹窗之外再原地显示 message,便于改后重试)。
- **历史**:localStorage 键 `dq-sql-console-history`,最近 50 条(带时间戳,同 SQL 去重保留最新),「历史」抽屉点击回填编辑器;仅本机存储,不落服务端。
