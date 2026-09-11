# SQL 控制台

> 一级页面「SQL 控制台」(`/sql-console`,侧边栏入口):选择数据源后直接执行任意 SQL(类 DataGrip 交互),查看结果集或受影响行数。纯新增独立功能,不依赖扫描任务。数据源下拉另含只读的「本地 H2 库」(应用自身配置库,见「本地 H2 库(只读)」)。

## 页面与 API

| 入口 | 说明 |
|---|---|
| `POST /api/datasources/{dsId}/sql/execute` | 执行 SQL 原文,返回结果集(列名+行)或受影响行数 |
| `GET /api/datasources/{dsId}/schemas/{schema}/columns` | 整库字段清单(表名+字段名+展示类型+字段备注;智能提示用),多库方言带 `?db=`;**本地 H2 缓存(meta_schema_column)优先,`?refresh=true` 强制回源覆盖**;表太多时按 `?tables=a&tables=b`(重复参数)指定表子集分批拉取(已缓存的表读本地,未缓存/refresh 的表逐表回源落缓存) |
| `POST /api/sql-console/local-h2/execute` | **本地 H2 库(应用自身 dqconfig 配置库)只读查询**:仅放行查询语句,写语句一律 400(见下节) |
| `GET /api/sql-console/local-h2/schemas` | 本地 H2 库 schema 清单(PUBLIC / INFORMATION_SCHEMA 等),控制台库下拉用 |
| `GET /api/sql-console/local-h2/tables?schema=` | 本地 H2 库表/视图清单(表名+表注释,智能提示用);schema 缺省=库默认 schema |
| `GET /api/sql-console/local-h2/columns?schema=` | 本地 H2 库整库字段清单(所属表+字段名+展示类型+备注,智能提示用) |

- 页面:`web/src/views/SqlConsole.vue`(CodeMirror 6 编辑器铺满页面剩余高度 + 动态列结果表 + localStorage 历史);业务内核:`common` 的 `SqlConsoleService`(业务库入口)与 `LocalH2ConsoleService`(本地 H2 只读入口);server 仅薄壳 `SqlConsoleController`(路由注册在 `WebServer`)。
- 请求体 `{ "sql": "...", "schema": "目标库(可空)" }`,sql 为 SQL 原文,透传业务库(JDBC)执行;**一次请求执行单条语句**(前端只发送选中片段或光标所在语句);schema 为工具栏库下拉选中的目标库(本地 H2 入口下即 schema),空=默认库。
- 响应(query=true 有结果集):`{ query, columns, rows, total, truncated, updateCount: -1, durationMs }`;非查询:`{ query: false, columns: [], rows: [], updateCount, durationMs }`(DDL 的 updateCount 可为 -1)。

## 本地 H2 库(只读)

- 数据源下拉**首项「本地 H2 库(只读)」**是应用自身的 dqconfig 配置库,不是业务数据源:只出现在 SQL 控制台下拉(前端合成项,id 为 `__local_h2__`),不进数据源管理页,走独立的 `/api/sql-console/local-h2/*` 路由;业务内核 `LocalH2ConsoleService` 直接复用应用主库 Hikari 连接池(不新建池)。
- **只读是语句级保证**,不是数据库级:H2 没有可借的会话级只读——`Connection.setReadOnly` 是空实现(`isReadOnly()` 仍返回 false、写入照常成功)、`SET READONLY` 不支持、`ACCESS_MODE_DATA=r` 只在首次打开库时生效(对本应用已以读写模式打开的库无效),均已实测。因此三重把关:
  1. **首关键字白名单**:只放行 `SELECT / WITH / EXPLAIN / SHOW / VALUES / TABLE`,其余(含 `SET`/`CALL`/DDL/DML)直接 400;
  2. **H2 自身解析器只读判定**:`Parser(session).prepareCommand(sql).isReadOnly` 兜住白名单看不出的写语句——`EXPLAIN ANALYZE DELETE`、`EXPLAIN PLAN FOR DELETE`(二者都会**真实执行**内层语句)、`SELECT 1; DELETE …` 拼接写语句、`SELECT CSVWRITE(...)` 等会被判非只读而拒绝;拿不到 H2 会话/判定失败时按不安全处理(拒绝执行),不让只读保证静默失效;
  3. 校验在 `Statement.execute` 之前完成,不过校验的语句根本不会下发到库(该库本身仍以读写模式打开,因为它就是应用自己的库)。
  说明:上述解析器判定用了 H2 内部 API(h2 版本固定在 `gradle/libs.versions.toml`,现为 2.3.232),**升级 H2 时必须回归 `LocalH2ConsoleServiceTest`**;池化连接是 Hikari 代理,校验前先 `Connection.unwrap` 取原始 H2 连接。
- **schema**:库下拉列出本地库自身 schema(`GET /api/sql-console/local-h2/schemas`,如 PUBLIC / INFORMATION_SCHEMA);选中后用 JDBC `Connection.setSchema` 切会话默认 schema(先切再校验:解析器要在目标 schema 下才认得未限定表名),归还池化连接前恢复原值,防应用自身查询串库。
- 结果集口径、`MAX_ROWS=1000` 截断、单元格 1000 字符截断、单条超时(`statementTimeoutSeconds`)与业务库入口完全一致(共用同一条序列化实现);错误映射同为 400(非查询语句/空白 SQL)/ 502(执行报错)。
- 与业务库入口的差别:本地 H2 没有「数据源 / 多库」概念,库下拉即 schema;不进服务端/浏览器缓存;刷新按钮禁用;Cmd/Ctrl+点击表名跳转字段明细不适用(本地库没有对应的数据源页),前端已禁用。

## 执行口径(与数据预览同约定)

- 连接走 `DataSourceService.getConnection`,SSH 隧道/连接池/多库切 catalog 自动生效;`use` 块结束即归还池。
- **选库执行**(请求体 schema 非空时):多库方言(SQL Server/Kingbase)把目标库当 database 走按库分池切 catalog;其余方言(`DbDialect.useSchema`)会话级切默认 schema——MySQL/OceanBase 切 catalog(即 USE)、PG/瀚高 `SET search_path`、Oracle `ALTER SESSION SET CURRENT_SCHEMA`、达梦 `SET SCHEMA`(2022 年后版本),归还池化连接前用 `DbDialect.currentSchema` 取到的旧值恢复,防串库。
- 单条 SQL 超时 = 系统设置 `statementTimeoutSeconds`(与分段扫描/数据预览同口径)。
- 列名取 `ResultSetMetaData.getColumnLabel`;行值 `getObject().toString()`,NULL 保 null,单元格截断 1000 字符(防 CLOB 撑爆响应)。
- **结果集最多返回 1000 行**(超出即停,`truncated=true` 告知前端截断),防止大表无过滤查询打爆内存/响应。
- 错误映射沿用统一异常:SQL 错误 502,空白 SQL 400(`@NotBlank`);目标库连不上(池初始化失败)按 RuntimeException → 500,与表数据预览同口径。
- 业务数据源入口不做语句限制/只读限制:本工具仅内网单机部署,任何能访问端口的人都能操作所有数据源(与项目定位一致);唯一例外是本地 H2 库入口(应用自身配置库),只读,见上节。

## 前端交互

- **库下拉**:选数据源后自动加载——多库方言(SQL Server/Kingbase)列数据库(`GET /datasources/:id/databases`),其余列 schema(`GET /datasources/:id/schemas`),均受库过滤白名单约束;空=数据源默认库,可清除。选中「本地 H2 库(只读)」时改列本地库自身 schema(`GET /sql-console/local-h2/schemas`)。
- **智能提示**:CodeMirror autocompletion 动态补全源,顺序为表(`class` 「表」徽标 + 表注释 detail)→ 字段(`property` 「列」徽标 + 「类型 · 归属表 · 备注」detail,跨表同名字段靠 detail 区分)→ SQL 关键字(`StandardSQL.dialect.words`,「词」徽标)。选定数据源+库后自动拉取该库的表清单与**整库字段清单**(`/schemas/:schema/columns`,多库方言按 库→schema 两级枚举,上限 20 个 schema);选中库补未限定名(执行会话已切库),非多库方言未选库时只跨库枚举表名(字段量太大不拉)补 `schema.table` 限定名 + 跨库去重的未限定名。**补全元数据三级获取:浏览器 localStorage(`dq-sql-console-meta`,按 数据源|库 覆盖更新,最多 10 条按时间淘汰,空结果不写防冲掉好缓存)→ 服务端 H2 缓存(meta_schema_column)→ 源库**:再次选中时先上 localStorage 秒出提示,后台拉新后覆盖。**表数超 100 时字段按 50 表/批分批拉取**(`?tables=a&tables=b` 重复参数,防整库一次拉超时),先上表名补全再随每批返回渐进补齐字段。弹层 fixed 定位防编辑器 `overflow:hidden` 裁剪。选中「本地 H2 库(只读)」时改从本地库直接读结构(`/sql-console/local-h2/tables|columns`,一次性拉取,不走缓存与分批)。
- **刷新按钮**(库下拉与「历史」之间):tooltip「从源库刷新表/字段提示(覆盖本地缓存)」,点击对当前补全元数据强制回源(接口带 `refresh=true`,覆盖服务端 H2 缓存与 localStorage),成功后提示「表/字段提示已从源库刷新」;未选数据源时禁用,选中「本地 H2 库(只读)」时同样禁用(本地库无源库/缓存概念)。
- **执行**:按钮或 `Ctrl/Cmd+Enter`;类 DataGrip 执行目标——有选中片段只执行选中部分(剥掉末尾分号/空白),无选中执行**光标所在语句**(`utils/sqlStatements.js` 按顶层分号切分,识别引号/反引号字符串与 `--`/`#`/`/* */` 注释);光标在语句外空白处时**只认紧贴的语句**(中间没有空行,上下都紧贴取上面一条),被空行隔开则不执行并提示「光标处没有 SQL 语句」;编辑器下方实时提示将执行的语句;未选数据源时按钮禁用。选中「本地 H2 库(只读)」时改投 `POST /sql-console/local-h2/execute`(只读,写语句 400 并在结果区内联展示拒因)。
- **布局**:页面纵向 flex 铺满主区域,编辑器占满剩余高度(最低 220px,超出滚动),结果区按需占位(上限约半页,超高内部滚动)。
- **结果**:查询 → 动态列 `el-table` + 本地分页(20/50/100/200),截断时 warning 提示;单元格内制表符按 8 列 tab 位展开成对齐空位(`white-space:pre` + `tab-size:8`,同 DataGrip 的 EXPLAIN 对齐效果),换行/回车/`\0` 及其余 C0/DEL 控制码转义为可见符号(`\n` `\r` `\0` `\xNN`)加醒目底色展示,避免控制字符在表格里塌陷看不出来;非查询 → 「执行成功,影响 N 行,耗时 X ms」;失败 → 结果区内联错误(axios 拦截器全局弹窗之外再原地显示 message,便于改后重试)。
- **历史**:localStorage 键 `dq-sql-console-history`,最近 50 条(带时间戳 + 执行时的 `dsId`/`schema`,SQL 统一补末尾分号入库,按 SQL+数据源+库 去重保留最新),「历史」抽屉点击回填编辑器并自动切回该条的数据源和库(切数据源后等库清单加载完再选库;数据源已删除/库已不在清单时兜底只回填 SQL 或保持默认库;旧格式条目无 dsId,仅回填 SQL);仅本机存储,不落服务端。
- **Cmd/Ctrl+点击表名跳转字段明细**:按住 Cmd(mac)/Ctrl 悬停在语句内表位关键字(FROM/JOIN/INTO/UPDATE/TABLE/TRUNCATE/DESCRIBE/DESC)后的表名上时显示下划线+手型,点击 `router.push` 到该表字段明细页(页签体系自动落到 `ds-{id}` 页签)。识别 `schema.table` 限定名(点 schema 部分同样生效)与 `` `t` ``/`"t"`/`[t]` 引号包裹;要求光标落在顶层分号切分出的语句区间内,且表名能在智能提示已加载的表清单里反查出 schema(未选库时从 `schema.table` 限定名条目反查,跨库同名取第一个;多库方言带 `?db=` 当前选中的数据库),未选数据源或匹配不到表时不响应。
