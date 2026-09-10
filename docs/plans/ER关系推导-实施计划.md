# ER 关系推导 — 实施计划

> 前置文档:[ER关系推导-讨论稿](ER关系推导-讨论稿.md)(术语/决策/流程图以此为准,本文不再重复论证)。
> 状态:**已实施**(M1~M3 全部完成,正式文档见 [ER关系推导](../wiki/ER关系推导.md))。

## 一、总体形态

```
用户选锚点表+字段(三处入口)
  → RelationInferService 推导(名字匹配 / +两阶段语义匹配)
  → 值交集验证 + 基数验证(新方言 SQL)
  → table_relation 三态入库(候选/确认/否决)
  → GET /api/relation-graph 组装 nodes+edges
  → 前端 G6 乌鸦脚 ER 图(星型图 / 全库总图)
```

分层铁律不变:业务逻辑全在 common 内核,server 只注册路由;数据库差异收敛在 `dialect/`。

## 二、Flyway 迁移(V35__table_relation.sql)

当前最高 V34,新表两张(风格对齐 `V24__sample_export.sql`):

- **table_relation**(关系表,无外键,数据源删除后保留,同 manual_collect 惯例;无库概念方言 db_name 存空串):
  `id` IDENTITY 主键;四元组 `datasource_id / db_name / schema_name`;
  方向化存储 `one_table / one_column`(唯一侧)与 `many_table / many_column`(重复侧);
  `cardinality`(ONE_TO_ONE / ONE_TO_MANY / SUSPECT_MANY_TO_MANY);
  `status`(CANDIDATE / CONFIRMED / REJECTED);`source`(NAME_MATCH / SEMANTIC / MANUAL);
  `confidence`(HIGH / MEDIUM / LOW,可空);`overlap_ratio` DOUBLE(值交集率,可空);
  `remark`(如「疑似多对多」原因);created_at / updated_at;
  唯一键 = 四元组 + 两端表/字段六元组。
  **方向归一化**:ONE_TO_ONE 时按(表名,字段名)字典序小者入 one 侧,保证同一关系重复推导命中唯一键、幂等 upsert。
- **relation_infer_job**(推导任务表,参考 sample_export):四元组 + `anchor_table` + `anchor_columns`(逗号分隔)+ `use_semantic` BOOLEAN + `status`(RUNNING/DONE/FAILED)+ `stage`(NAME_MATCH / SEMANTIC_TABLE / SEMANTIC_COLUMN / VERIFY)+ `total_steps / done_steps / found_count` + error + 三个时间戳。

## 三、common 内核改动

### 3.1 model 包

- `TableRelation.kt`:数据类 + 三枚举(RelationStatus / RelationSource / RelationCardinality)+ RelationInferJob 视图模型;请求类带 `@field:NotNull/@field:NotBlank`(server 校验惯例)。

### 3.2 repository 包

- `TableRelationRepository`:按库/按表查询(状态过滤)、upsert(唯一键幂等)、状态流转(confirm/reject,可互转)、删除、跳过已否决对的查询(推导时排除 status=REJECTED 的字段对)。
- `RelationInferJobRepository`:创建/进度更新/终态翻转;**启动时将残留 RUNNING 标 FAILED**(error=服务重启中断;推导可重跑,不做断点续推)。

### 3.3 dialect 包(新增 2 个方法,8 方言)

值交集采样与基数验证都需要方言化分页,现有 `sampleRowsSql` 不带 DISTINCT 不可复用:

- `distinctSampleSql(schema, table, column, limit)`:`SELECT DISTINCT col FROM t WHERE col IS NOT NULL` + 方言限流(LIMIT / FETCH FIRST / TOP / ROWNUM)。
- `hasDuplicateSql(schema, table, column)`:分组计数判重 `... GROUP BY col HAVING COUNT(*)>1` + 方言取首行。
- AbstractDialect 给 LIMIT 系默认实现,Oracle/SQLServer/DM 等按现有 `pageRowsSql` 的差异分布覆盖;标识符必须走 `quote()`。

### 3.4 service 包

- **RelationInferService**(核心,纯阻塞):
  1. **名字匹配**:读 `MetaCacheRepository.listSchemaColumns`(整库字段缓存;未就绪则实时拉 `dialect.listSchemaColumns` 并回填缓存),忽略大小写命中锚点字段同名者,排除锚点表自身。
  2. **语义匹配(可选,前置 `AiConfigService.requireConfig()`)**:两阶段分批调 `AiService.chat(...)`;**阶段一表级**——锚点表(注释+`table_doc` 描述)对全库表分批(每批约 100 张)让 LLM 筛相关表,每张表有描述用描述、无描述回退表注释(meta_table.comment);**阶段二字段级**——对粗筛表逐表把字段名+类型+注释与锚点字段发给 LLM 定对应。prompt 组装写成 `@JvmStatic` 纯函数(AiServiceTest 惯例)。
  3. **合并去重**:两通道候选合并,跳过已否决对、命中唯一键的已存在对不重复插。
  4. **值交集验证**:两端各 `distinctSampleSql(≤1000)`,值统一序列化为字符串并 trim 后求交集率(规避 NUMBER/VARCHAR 类型差);交集 0% 只降置信不否决。
  5. **基数验证**:先查缓存索引(meta_index)——唯一索引/主键单列覆盖直接定「一」侧短路;否则 `hasDuplicateSql`。两端唯一=ONE_TO_ONE,一端唯一=ONE_TO_MANY,两端重复=SUSPECT_MANY_TO_MANY(标 remark,图上红色);空表/查询失败=剔除不入候选。
  6. **置信度**:高=交集率≥80%;中=名字命中+交集>0 或语义+交集≥80%;低=其余入库候选。
  7. **防护**:单轮候选对上限(如 200,超出截断并 remark 提示);所有验证 SQL 串行执行、单条超时沿用分段扫描口径。
  8. **异步**:统一走任务模型(偏离讨论稿「名字匹配同步秒回」的说明:单一路径最简,名字匹配通常 1s 内完成,前端无感);`Executors.newSingleThreadExecutor()`,stage/progress 落 relation_infer_job;LLM 调用 scene 传新枚举。
- **TableRelationService**:关系 CRUD(confirm/reject 互转、候选删除、手动补充 source=MANUAL 直接 CONFIRMED)、图数据组装(节点=库内表+注释+字段清单按档位裁剪;边=关系;`table` 参数空=全库总图仅确认关联+孤儿表,非空=该表星型含候选)。
- **AiScene 枚举**新增 `RELATION_INFER("关系推导")`(用量落 ai_usage_log,无需动独立库迁移)。
- **ServiceEnv**:注册两仓储+两服务;`initDatabase()` 加 `relationInferJobRepository.failRunningOnStartup()`。
- **授权**:不加受控功能项(AI 功能本就在 BASE_FEATURES);语义匹配运行时无 AI 配置即 400 提示。

## 四、server 壳层(WebServer.java + RelationController.java)

沿用 AtomicReference 延迟装配 + record 校验 + 统一异常映射惯例:

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/relation-infer` | 提交推导(datasourceId/dbName/schemaName/table/columns[]/useSemantic),返回 job id |
| GET | `/api/relation-infer-jobs` | 按库查询任务列表(前端 1s 轮询) |
| GET | `/api/relation-infer-jobs/{id}` | 任务详情 |
| GET | `/api/relations` | 关系列表(status/table 过滤) |
| POST | `/api/relations` | 手动补充关系(直接 CONFIRMED) |
| POST | `/api/relations/{id}/confirm` `/reject` | 状态流转(可互转) |
| DELETE | `/api/relations/{id}` | 删除(仅候选态) |
| GET | `/api/relation-graph` | 图数据(table/includeCandidate 参数) |

路径注意避开 `{id}` 前缀冲突(sample_export 已有教训:`-delete` 批量删用独立路径)。

## 五、前端(web)

- **新依赖**:`@antv/g6`(v5);项目当前零图库依赖,release 打包由 Gradle `buildWebForRelease` 自动覆盖,无需额外保障。
- **组件拆分**:
  - `components/RelationGraphCanvas.vue` — G6 画布(自定义边端标记近似乌鸦脚;候选虚线/确认实线/疑似多对多红色;力导向↔层次布局切换;节点三档:仅表名/仅关联字段/全字段;点表名跳字段明细新页签;点边弹确认/否决/编辑基数;「以此表为锚点继续推导」入口)。**布局不持久化**。
  - `components/RelationInferDialog.vue` — 选锚点字段(表字段多选)+ 语义匹配开关(先查 `/api/ai-config` 的 `available`,未配置则禁用+提示),提交后轮询进度。
  - `views/RelationGraph.vue` — 独立页(全库总图为主,数据源+库选择器、候选显隐开关、刷新)。
- **路由/页签注册**:`router/index.js` 加 `/relations`;`stores/tabs.js` 的 `PAGE_TABS` 加标题;`App.vue` nav 数组加「ER 关系」(顶部独立按钮)。
- **三处入口**:① `Tables.vue` 行内「推导关联」按钮 → RelationInferDialog;② 顶部导航「ER 关系」→ RelationGraph 页;③ `TableDetail.vue` 加「推导关联」按钮 + 内嵌「ER 关系」页签(复用 RelationGraphCanvas,传入该表星型数据)。
- **api/index.js**:按现有范式加上述端点封装;轮询抄 `SampleExports.vue` 的 1s setInterval + 终态停轮询 + keep-alive 失活暂停模式。

## 六、测试

- **H2 单测**(SampleExportTwoPhaseTest 范式:内存库 + SchemaInit + 真实 Repo/Service 组装):
  - 名字匹配全流程:灌 meta_schema_column 缓存 → 推导 → 候选入库 → confirm/reject/delete 三态流转;
  - 方向归一化(同一关系两端互换命中唯一键)、跳过已否决对、候选上限截断;
  - 置信度分级纯函数、两阶段 prompt 组装纯函数断言;
  - 值交集计算(字符串归一化、空集、全等)。
- **方言单测**:distinctSampleSql/hasDuplicateSql 八库语法断言(现有方言测试惯例)。
- **LLM fake**:JDK HttpServer 假端点(AiServiceTest 范式)测两阶段语义匹配解析与降级。
- **Testcontainers(可选)**:MySQL/PG 真实库跑值交集+基数验证端到端。

## 七、文档与红线

- 功能落地时:新增 `docs/wiki/ER关系推导.md`(讨论稿结论迁入)+ 根 AGENTS.md 文档索引;同步 `docs/wiki/前端页面与按钮逻辑.md`(新页面/按钮/轮询);`common/AGENTS.md` 结构段(新服务/新表/新方言方法)。
- 根 `CHANGELOG.md` 人维护新增条目(verifyChangelog 硬校验)。
- 导入导出兼容铁律:本功能暂无导入导出;后续做 ER 数据跨实例迁移时,走 AnnotationTransfer 同款「忽略未知字段+版本检测」口径。

## 八、里程碑

- **M1 最小闭环**:V35 迁移 + 仓储 + 名字匹配 + 值交集/基数验证 + 三态 API + G6 图页(星型+全库总图+点边确认/否决)+ Tables 行内入口。交付即可用。
- **M2 语义匹配**:两阶段 LLM + AiScene + 异步任务进度 + 无 AI 配置降级。
- **M3 打磨**:手动连线补充、TableDetail 内嵌页签、疑似多对多展示优化、文档/wiki 归档。
- 图片/Word 导出按讨论稿结论延后,不在本计划。
