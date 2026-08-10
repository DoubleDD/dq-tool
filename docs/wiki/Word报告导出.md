# Word 数据调研报告导出

> dq-tool 项目文档,索引见根 [AGENTS.md](../../AGENTS.md)。

**异步任务**:导出耗时(逐库聚合 + 逐节 LLM 分析),点击「导出报告」只提交任务,在「导出任务」页签查看进度与产物。库列表页复用复选框选库(未勾选时弹窗确认后默认导出全部已完成全表扫描的库;勾选未完成全表扫描的库任务会 FAILED 并列出库名)、表列表页导出当前库(`schemas` 限定范围)。

- 提交:`POST /api/datasources/{dsId}/report/exports?db=` body `{schemas:[...]}`(null=全部)→ `{taskId}`;列表:`GET /api/report-exports`;操作:`GET /api/report-exports/{id}/download`(浏览器环境显示「下载」)、`POST .../open`(调系统软件打开,顺序 MS Office → WPS → 默认关联,`util/SystemOpen.kt` 纯 ProcessBuilder 实现,不用 AWT)、`POST .../reveal`(打开文件目录并选中);tauri 套壳环境(检测 `window.__TAURI_INTERNALS__`)显示「另存为」,走 Rust 自定义命令 `save_report_as`(原生保存对话框 + 从数据目录复制产物,见 tauri/AGENTS.md)
- 任务落 `report_export` 表(V9):状态机 PENDING→RUNNING→DONE/FAILED,进度(progress_done/total + stage 阶段文字)由内核 `WordReportService.export` 的 onProgress 回调落库(总步数=逐库聚合 N + 标记节 S + 固定分析 8 + 渲染 1);单线程队列执行(`WordReportExportService`),服务重启时未完成任务置 FAILED
- 产物文件:`数据目录/reports/{数据源名}-数据调研报告-{taskId}.docx`(数据源名中的 `\/:*?"<>|` 替换为 `_`;任务 id 保证同名数据源多次导出不互相覆盖);渲染内核 `WordReportService` + poi-tl 模板 `common/src/main/resources/templates/data-survey-report.docx`;导出任务列表第一列「文件名」即该产物文件名,点击调系统软件打开

**渲染封面 + 一~四章全正文**:

- 封面(报告名/输出时间/数据库类型)
- 一 数据来源总览
- 二 数据质量总览:2.1 质量概况 LoopRow 表;2.3 填充率分桶/2.4 注释率/2.5 类型分布/2.6 冗余(_copy|_bak|_tmp 与数字日期后缀识别)/2.7 大体量表(>100 万行或 >1GB,增量列无数据源填「-」)为代码建表——每库 N 行首列 vMerge 分组,LoopRow 会串组,走 `WordReportTables.GroupTablePolicy`;2.2 只留分析文字,原「数据库业务分组」表与 2.1 重复已删
- 三 按 USER 表标记逐标记一节(`TagSectionsPolicy` 代码生成,(一)(二) 规则统计 + 两张明细表,(三) LLM 分析;打标表逐表取最近 DONE 快照、未扫描跳过、子集体积不做整库实时补算;无 USER 标记渲染提示语)
- 四 整体分析说明

要点:

- 各节分析文字由大模型生成(prompt 带代码算好的精确数字,统一 `analysis()` 入口,未配置/失败渲染「(待人工编写)」占位,LLM 调用点构造器可注入 fake)
- 数据取每库最近一次 **DONE 历史快照**(重扫进行中/最近失败自动回落更早 DONE 任务,不为导出重扫),空表=0 行、空字段=有值数 0,与 Excel 导出同口径
- **选中的库必须已完成全表扫描(快照覆盖当前全部表且无失败表),否则任务 FAILED 列出库名提示先扫描**
- 快照缺失的表体积(size_bytes 为 null)实时回业务库元数据补算,失败降级为部分合计并记 warn
- 「实例描述」列取库级描述(`schema_doc` 表 V8 迁移,库列表页可编辑,`PUT /api/datasources/{dsId}/schemas/{schema}/description`)
- 模板标签改造脚本 `scripts/make-word-template.py`(第一章)+ `scripts/make-word-template-ch234.py`(封面+二~四章;{{schemas}} 等循环锚点在表头首格独立 run,循环行用 `[col]` 语法)
- 目录页码由 Word 打开后 F9 更新域
