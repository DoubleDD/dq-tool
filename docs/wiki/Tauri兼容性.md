# Tauri 兼容性

> dq-tool 项目文档,索引见根 [AGENTS.md](../../AGENTS.md)。Tauri 壳的侧车协议、常驻托盘、自动更新实现、打包细节见 [tauri/AGENTS.md](../../tauri/AGENTS.md);安装包与 CI 状态见 [打包与发布](打包与发布.md)。

本文是 **Tauri 兼容性问题台账 + 跨形态回归清单**,回答两类问题:

1. 同一个功能为什么“浏览器能用、Tauri 不能用”(或反过来),根因是什么、判据在哪;
2. 改完代码后,哪些形态必须各验一次、怎么验。

维护约定:**新增或改动交互后,对照最后一节「跨形态检查清单」逐条过**;发现新的 Tauri 特有问题,按本文格式补一条(现象 / 根因与判据 / 处置 / 回归方式),并把状态写清楚。

---

## 一、三种运行形态(判断兼容性的前提)

| 形态 | 页面来源 | API 地址 | 门禁 | 说明 |
|---|---|---|---|---|
| 浏览器 / jpackage `--app` | 服务端从 `-Ddq.web.static-dir` 磁盘目录或 classpath 发页面,**同源** | 相对 `/api`(同端口) | Cookie(服务端种)+ `X-Dq-Token` 头双保险 | 开发态(`make dev` / `dev-web`)与客户备用形态 |
| **Tauri 安装版**(Windows NSIS) | webview 经自定义协议 `dq://` 从 `versions/current` 指向的 `versions/<v>/static/` 磁盘直载(2026-09 分层分发起,前端不再内嵌 exe),**`dq://localhost` / `http://dq.localhost` 源** | `http://127.0.0.1:<动态端口>/api`(**跨域**) | 仅 `X-Dq-Token` 头(SSE 走 `?token=`) | **用户主用形态**,CI `windows-tauri` job 产出 |
| Tauri 绿色免安装 zip | 同安装版 | 同安装版 | 同安装版 | 数据目录 `<exe>/data`(`PORTABLE.txt` 标记)、禁用全量 NSIS 更新(业务层更新照常)、目标机需自带 WebView2 |

**兼容性缺陷的共同特征**:只在 Tauri 形态暴露——因为 Tauri 不是浏览器,而是「系统 WebView + Rust 宿主」,
宿主可以覆盖 WebView 的宿主级能力(拖放、下载、新窗口、ACL、自定义协议),也可以改变页面的 origin
(本地 `dq://` 直载 vs 服务端同源)。**只跑 `make dev` / 浏览器,这类问题永远测不出来。**

当前依赖版本(判据均以仓库 `tauri/src-tauri/Cargo.lock` 锁定版本为准):
`tauri 2.11.5`、`tauri-runtime 2.11.3`、`tauri-runtime-wry 2.11.4`、`tauri-utils 2.9.3`、`wry 0.55.1`、`tauri-plugin-updater 2.10.1`、`ureq 3.4.0`。

---

## 二、结论总表

| # | 问题 | 严重度 | 状态 | 影响形态 |
|---|---|---|---|---|
| A1 | Windows 下 HTML5 拖放全部失效(拖拽上传、树内拖动、卡片拖分组) | 高 | **已修复**(2026-09-17) | Tauri(Windows) |
| A2 | 相对 `/api` 下载/跳转把整个 webview 导航走 | 高 | 已规避(约定 + 封装) | Tauri |
| A3 | Cookie 门禁在 Tauri 跨域上下文失效 | 高 | 已规避(改走 token) | Tauri |
| A4 | 前端资源打进 fat jar → 杀软实时扫描导致 assets 404/白屏 | 高 | 已规避(纯 API + 直载) | Tauri(Windows) |
| A5 | 自定义 IPC 命令被 Tauri 2 ACL 直接拒绝 | 中 | 已规避(权限文件 + capability) | Tauri |
| A6 | 打包资源路径与 `resources/` 前缀差异导致找不到 jar | 高 | 已规避(三路径兼容) | Tauri 安装版 |
| A7 | 端口竞态:探测到的空闲端口被抢注 | 中 | 已规避(避让 + stdout 回填) | Tauri |
| A8 | 数据目录三态口径不一致会读错库 | 中 | 已规避(`-Ddq.data-dir` 三态) | Tauri 安装版/绿色版 |
| A9 | 常驻 + 单实例模型下调试跑到旧 jar | 低 | 已规避(操作约定) | Tauri 开发态 |
| B1 | 应用内自动更新拿不到新版本(`latest.json` 未生成) | 高 | **已修复**(2026-09-17) | Tauri 安装版 |
| B2 | Windows 安装版启动失败完全静默(无窗口/无日志/无提示) | 中 | **已修复**(2026-09-17) | Tauri 安装版 |
| C1 | 三平台真机 `Origin`/CORS/SSE 未实测回填(T14) | 中 | **待验证** | Tauri 全平台 |
| C2 | `downloadText`/`downloadDataUrl` 在 Tauri 的落盘行为未验证且不一致 | 中 | **待验证** | Tauri |
| C3 | 剪贴板能力与各入口兜底不一致 | 低 | **待验证** | Tauri |
| D1 | `identifier` 仍是占位 `com.example.dqtool` | 低 | 已知取舍 | Tauri |
| D2 | 未签名/未公证;macOS Tauri 构建停用 | 中 | 已知取舍 | Tauri(macOS/Windows) |
| D3 | 绿色版依赖目标机自带 WebView2 | 低 | 已知取舍 | Tauri 绿色版 |
| D4 | token 经 `-D` 进 argv,`ps` 可见 | 低 | 已知取舍 | Tauri |
| D5 | 自定义协议 `dq://` 对无扩展名路径回退 `index.html`(SPA 路由需要;有扩展名的缺失文件返回 404) | 低 | 已知取舍(诊断面板已覆盖) | Tauri |
| D6 | 内网/离线环境下自动更新外联 GitHub 必然失败 | 低 | 已知取舍(B1 已恢复,失败只记日志) | Tauri 安装版 |

---

## 三、已修复 / 已规避

### A1. Windows 下 HTML5 拖放全部失效(已修复,2026-09-17)

**现象**:Tauri 安装版(Windows)里,所有原生拖放交互无效——`el-upload` 拖拽区拖文件进去没反应、
对象管理目录树拖不动、数据源卡片拖到分组无反应;同一功能在浏览器 / jpackage 下正常。

**根因**:Tauri 默认开启自带拖放处理器(`WindowConfig.drag_drop_enabled` 默认 `true`,
见 `tauri-utils-2.9.3/src/config.rs:1943-1947`;`WebviewAttributes::default()` 同值,
`tauri-runtime-2.11.3/src/webview.rs:515`)。开启后 `tauri-runtime-wry` 无条件为 webview 装上处理器
(`tauri-runtime-wry-2.11.4/src/lib.rs:4862`),Windows 上 wry 会**枚举 WebView2 的全部子窗口、
`RevokeDragDrop` 撤销系统原本的 drop target、再 `RegisterDragDrop` 换成自己的**
(`wry-0.55.1/src/webview2/drag_drop.rs` 的 `DragDropController::inject_in_hwnd`)。换上的 `IDropTarget`:

- 只处理 `CF_HDROP`(文件列表);
- 非文件拖拽在 `DragOver` 里一律 `*pdwEffect = *cursor_effect` = `DROPEFFECT_NONE`
  (`cursor_effect` 初值即 `DROPEFFECT_NONE`),等于告诉系统“这里不接受拖放”。

于是浏览器端 `dragenter`/`dragover`/`drop` 一个都不派发。两类拖拽的失败方式:
**文件拖入**变成 Tauri 的 `tauri://drag-drop` 事件(本项目未监听 → 无人处理);
**页面内部拖拽**直接被拒。

关键点:**和前端有没有监听 Tauri 拖放事件无关**,只要没关闭处理器,覆盖就已经发生。
Tauri 官方在方法文档里写明了这一点:
「Disables the drag and drop handler. This is required to use HTML5 drag and drop APIs on the frontend on Windows.」
(`tauri-2.11.5/src/webview/webview_window.rs:1031`,配置项同义注释在 `tauri-utils-2.9.3/src/config.rs:1945`)。

**影响面**(修复前):

| 交互 | 位置 |
|---|---|
| 比对任务批量导入:拖 `.xlsx` 到上传区 | `web/src/views/CompareTasks.vue:168-180` |
| 抽样导出:导入 / 重新导入 Excel 拖拽区 | `web/src/views/SampleExports.vue:71-83`、`:100-112` |
| 数据源:导入 `.json` / `.ncx` 拖拽区 | `web/src/views/Datasources.vue:191-197` |
| 数据源:拖卡片到分组 | `web/src/views/Datasources.vue:87-89`(放置目标 `:72-83`) |
| 对象管理:树内拖动目录/挂载表、关系表拖到表节点 | `web/src/views/ObjectManage.vue:42-44`、`:56-58`、`:110-111` |

**处置**:窗口构建链加 `.disable_drag_drop_handler()`(`tauri/src-tauri/src/main.rs:163`,无条件跨平台)。
本项目不消费 `tauri://drag-drop` 事件,关闭零副作用;代价是 Rust 侧不再收到文件拖入事件(当前无人使用)。
G6 画布(ER 图 / 字段映射)的拖动走 pointer 事件,不受该机制影响,无需改动。

**回归方式**:Windows 上跑 Tauri 形态(安装版或 `make tauri`),逐条验上面 5 处拖放。
注意常驻 + 单实例模型:先走托盘「退出」再重跑,否则只是唤起旧实例、跑的还是旧 exe。

### A2. 相对 `/api` 会把 webview 导航走(已规避)

本地直载形态下页面源是 `dq://localhost`,相对路径 `/api/...` 会被解析到该源而不是后端,点击即整页导航、
SPA 崩溃重载。2026-09 差异明细「导出比对报告」事故(点击后菜单丢失、被甩回比对列表)就是全项目唯一一处
裸 `<a href="/api/...">` 漏网。

**约定**:所有下载/导出必须走 `web/src/utils/download.js` 的 `downloadFile` / `downloadText` / `downloadDataUrl`;
禁止裸 `<a href="/api/...">`、裸 `window.location` 赋值、裸 `window.open('/api/...')`。
新增下载入口别无选择(细则见 [前端页面与按钮逻辑](前端页面与按钮逻辑.md) 贯穿性机制 10)。

### A3. Cookie 门禁在 Tauri 跨域下失效(已规避)

webview 从本地源发往 `http://127.0.0.1:<port>` 属第三方 Cookie 上下文,`SameSite=Strict` 必失效,
axios 也未开 `withCredentials`。**Tauri 路径只走 `X-Dq-Token` 头**,Cookie 只保留给同源(浏览器/jpackage)那条路径。
`EventSource` 不能自定义请求头(`web/src/views/Logs.vue:102-107`),日志流走 `?token=` query(后端 `AccessGuard`
支持三选一,豁免清单保持最小)。前端令牌出口唯一:`web/src/api/base.js`。

### A4. 前端资源打进 fat jar(已规避)

旧形态把 `web/dist` 打进 75MB fat jar,Windows 杀软实时扫描造成 assets 404、白屏/卡启动。
现形态:`shadowJar` 排除 `static/**`,交付 jar 是纯 API 服务,Tauri 经自定义协议 `dq://` 从
`versions/<v>/static/` 磁盘直载页面(2026-09 分层分发起;此前为 `frontendDist` 内嵌直载,
windows-tauri 任务恢复的原因之一,见 CHANGELOG 2.0.6 段)。

### A5. 自定义 IPC 命令的 ACL 校验(已规避)

Tauri 2 对自定义 app 命令强制 ACL 校验,未登记则 IPC 层直接拒绝(报 `<cmd> not allowed. Plugin not found`,
不进 Rust 命令、后端无日志,错误只 toast 在前端)。每个命令须在 `tauri/src-tauri/permissions/*.toml`
声明 `allow-<命令名-连字符>` 并列入 `capabilities/default.json` 的 `permissions`。
坑:`permissions/` 目录不存在时 `build.rs` 不会对其发 `cargo:rerun-if-changed`,**首次新增权限文件后要
`touch build.rs`**。现有命令:`api_base`、`save_report_as`、`save_download`。

### A6. 打包资源路径与 `resources/` 前缀(已规避)

Tauri 2 的 `bundle.resources` glob 落盘会保留 `resources/` 前缀:Windows NSIS 装到 `$INSTDIR\resources\`,
macOS 在 `<exe>/../Resources/`。`bundled_resources_dir()`(`main.rs:357`)按
`<exe>/../Resources` → `<exe>` 同目录(旧布局)→ `<exe>/resources` 三路径探测 `backend/dq-tool.jar`;
`is_packaged()` 只在 release 构建才可能为真,避免 `tauri dev` 被 `target/debug/resources` 误判为安装版
(否则数据目录错走 `~/.dq-tool/data`、误启用自动更新);jar 定位里 **debug 构建优先于内嵌资源**,否则
dev 会一直跑打包残留的旧 jar。

### A7. 端口竞态与避让回填(已规避)

Rust 用 `TcpListener::bind(127.0.0.1:0)` 取空闲端口后释放传给 `--server.port=`,存在被抢注的竞态;
后端 `DqApplication` 向后避让并打印「端口 N 被占用,避让到 M」,Rust 的 stdout 读线程解析该行回填实际端口,
`api_base()` 每次读最新值(前端不得缓存首次端口)。**改 DqApplication 的输出格式必须同步 `main.rs` 的解析。**

### A8. 数据目录三态(已规避)

| 形态 | 数据目录 | 注入方式 |
|---|---|---|
| 开发(`make tauri`) | 仓库根 `./data`(可用 `DQ_DATA_DIR` 覆盖) | 不传,走后端默认 |
| 安装版 | `~/.dq-tool/data` | Rust 传 `-Ddq.data-dir` |
| 绿色 zip | `<exe>/data` | Rust 传 `-Ddq.data-dir`;靠 exe 旁 `PORTABLE.txt` 判定 |

`save_report_as` 从 `data_dir()/reports` 读产物,必须与后端 `-Ddq.data-dir` 同口径,否则报「报告文件不存在或已被移动」。

### A9. 常驻 + 单实例下的调试陷阱(操作约定)

关窗只隐藏不退出、单实例插件保证重复启动只唤起已有实例 → 改了前端/后端后直接重跑 `make tauri`,
看到的还是**旧 jar / 旧 exe**。退出走托盘菜单「退出」或 Cmd+Q(直接关窗只是隐藏,不算退出)。

---

## 四、本轮已处理的原待办(B1–B2)

### B1. 应用内自动更新拿不到新版本(已修复,2026-09-17)

**现象**:`tauri.conf.json` 配了 `plugins.updater`(公钥 + GitHub `latest.json` 地址),`main.rs` 的 `auto_update`
启动即查、之后每 30 分钟一次;但 `.github/workflows/release.yml` 的 `updater-manifest` 收尾任务整块被注释,
Release 上没有 `latest.json` → `check()` 永远 404,Tauri 安装版用户收不到任何更新提示。

**处置**:启用 `updater-manifest` 任务(取消注释;由它把 `windows-tauri` 上传的 `updater-sig-*` 签名合并成
`latest.json` 挂到 tag Release,平台 key `windows-x86_64` → `dq-tool-<v>-windows-x64-setup.exe`)。
macOS 线仍停用(macos-tauri 未启用,map 里对应条目保持注释)。

**回归**:推下一个 `v*` tag 后确认 Release 附件里有 `latest.json`,且装了旧版的 Tauri 安装版启动后能收到更新提示。
内网无出网环境仍会查询失败(只记日志、不影响使用)—— 这是 D6 的既有取舍。

### B2. Windows 安装版启动失败完全静默(已修复,2026-09-17)

**现象**:`main.rs` 顶部 `#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]` 让 release exe 成为
GUI 子系统进程(无控制台),而 `fatal()` 原先只 `eprintln!` 后 `exit(1)`。于是内嵌 jar/JRE 缺失(如被杀软隔离)、
空闲端口探测失败、60s 就绪超时、`Tauri 初始化失败` 等场景都表现为「双击没反应 / 窗口闪一下」,且不落任何
Rust 侧日志(Java 侧有 StartupLog,Rust 侧原先完全没有)。

**处置**:`fatal()` 改为 stderr + 落盘 + 弹框三步:
- `log_fatal()` 把带 UTC 时间戳的一行追加到 `<日志目录>/tauri-startup.log`;日志目录取数据目录同级 `logs/`
  (开发 `./logs`、安装版 `~/.dq-tool/logs`、绿色版 `<exe>/logs`),与后端 `-Ddq.data-dir` 口径一致;
- Windows release 用模块级 `unsafe extern "system"` 直接声明 `user32!MessageBoxW`(不为此引 `windows-sys` 依赖)
  弹一个置顶错误框,内容含失败原因与日志文件路径;dev 构建保留控制台可看,不弹框(该分支仅 release 编译);
- 写完再 `std::process::exit(1)`。

**回归**:Windows release 构建下人为制造一次失败(如把 `resources/backend/dq-tool.jar` 改名),确认弹出错误框
且 `~/.dq-tool/logs/tauri-startup.log` 有对应记录。macOS 侧已用 `cargo check` 与等价 FFI 片段
(`rustc --edition 2021 --crate-type lib --emit=metadata`)校验语法与类型;Windows-only 分支由 CI 的
`windows-tauri` job(或 `workflow_dispatch` 单跑该 job)编译把关。

---

## 五、待验证

### C1. 三平台真机 `Origin` / CORS / SSE(T14 未回填)

`docs/plans/Tauri直载前端-jar转纯API服务-实施计划.md` §8 的实测表 Windows 行仍空。
风险是**某平台 webview 完全不发 `Origin` 头**(不是 `null`),Javalin `CorsPlugin` 会直接 return、预检失败、API 全挂;
兜底方案是 Rust 侧 loopback 反代(单 origin、零 CORS,代价是 SSE 要自己写流式转发)。

**现成抓手(已内置,无需改代码)**:配置了 `dq.access-token` 时(Tauri 必配),后端对每种不同的 `/api` 请求
`Origin` 各记一条 INFO 日志「/api 请求来源 Origin=…」(每进程去重)。三平台各跑一次 `make tauri`,
看数据目录 `logs/dq-tool-*.log` 即可回填;若某平台**完全看不到该行**,即命中上述回落条件。
源码推断(Tauri 2.11.5,自定义协议 `dq` 与内置 `tauri` 协议同规则):macOS/Linux 窗口 URL 为 `dq://localhost`,Windows 为 `http://dq.localhost`,
预期 Origin 依次为 `dq://localhost` / `dq://localhost` / `http://dq.localhost`,后端已对前两者与 `null` 验证回 `*`。
**macOS 已回填**(2026-09-19 分层分发 dev 冒烟):日志实测 `Origin=dq://localhost`,API 请求正常;Windows 行仍待 CI/真机。

### C2. `downloadText` / `downloadDataUrl` 的落盘行为(Tauri 未验证、口径不一致)

`downloadFile`(后端流式接口)在 Tauri 下走 Rust `save_download`,直存 `<数据目录>/exports/` 并 toast 可点击打开文件;
但 `downloadText`(诊断报告、drawio 导出)与 `downloadDataUrl`(画布「另存为图片」)走
**Blob/DataURL + `<a download>`**(`web/src/utils/download.js:34`、`:49`)。Tauri 未装 `on_download` 处理器,
Windows WebView2 会走默认下载行为——**静默存到系统「下载」目录**,既不弹保存框也没有“已保存到…”提示,
与其它导出手感不同,用户容易以为没导出。

**处置建议**:统一收口到 `save_download`(前端把内容 POST 给后端再由 Rust 拉取,或扩一个直接传字节的 IPC 命令),
或至少在 Tauri 下实测确认落盘位置并补一句提示。

### C3. 剪贴板能力与兜底(低)

wry 的 `clipboard` 属性默认 `false`(`tauri-runtime-2.11.3/src/webview.rs:516`),项目未调
`enable_clipboard_access()`。Windows/WebView2 上 `navigator.clipboard` 一般仍可用(secure context),
但各复制入口的兜底不一致:

- `web/src/views/TableDetail.vue:846`、`web/src/views/LanShare.vue:567`:**有** textarea 兜底(正确写法);
- `web/src/views/Errors.vue:347`:复制失败会退化成**下载一个 txt**(Tauri 下又触发 C2 的静默下载);
- `web/src/views/LicenseAdmin.vue:212`:无 textarea 兜底,只提示“复制失败,请手动选择复制”。

**处置建议**:统一抽一个 `copyText(text)` 工具(Clipboard API → textarea 兜底),新入口一律引用。

---

## 六、已知取舍 / 风险(不作为缺陷处理,但需知晓)

- **D1 `identifier` 仍是占位** `com.example.dqtool`。它决定 WebView2 数据目录与单实例标识,**一旦改名**,
  用户的 localStorage(页签、收藏、主题、通知历史)与 WebView 缓存整体丢失,相当于一次数据迁移——要改趁早。
- **D2 签名与平台覆盖**:安装包未做代码签名/公证,Windows SmartScreen 可能拦、macOS Gatekeeper 可能拦未公证 dmg;
  `macos-tauri` CI 任务已停用(macOS 用户当前只能走 jpackage dmg)。自动更新为原地替换 `.app`,
  首次跨版本更新的 Gatekeeper 行为需真机验证一次。
- **D3 绿色免安装 zip 无安装器引导**,目标机需自带 WebView2(Win10 1803+/Win11 一般已装);
  且 `PORTABLE.txt` 标记文件不得删除(删掉会回落成安装版口径,数据写 `~/.dq-tool/data`)。
- **D4 token 经 `-Ddq.access-token=` 进 java argv**,同机 `ps` 可见。内网单机定位下接受;
  介意则改为 Java 生成 + stdout 输出、Rust 解析(端口避让已有该通道)。
- **D5 自定义协议对未知路径无条件回退 `index.html`**(`tauri-2.11.5/src/manager/mod.rs`,`{path}` → `{path}.html`
  → `{path}/index.html` → `index.html`)。这**成就**了两件事:`createWebHistory()` 与
  `api/index.js` 的 `window.location.href = '/activate'` 在 Tauri 下可用(深层路由刷新/整页跳转都能回到 SPA),
  **副作用**是缺失的 `.js` 也会返回 HTML 200,可能表现为 MIME 白屏——前端 `index.html` 的启动诊断面板
  已覆盖这类启动失败的可视化(资源加载失败面板 + 入口错配自愈)。
- **D6 内网/离线场景的自动更新外联**:GUI 启动即向 GitHub Releases 查询,失败只记日志。更新链路已于
  2026-09-17 恢复(B1);客户内网无出网条件时该查询必然失败(不影响使用),必要时加开关或改为离线包分发。

---

## 七、已验证不是问题(防误伤,改动时不要“顺手修”)

1. **深层路由与整页跳转**:`createWebHistory()` + `window.location.href='/activate'` 在 Tauri 下可用,
   靠的是自定义协议对未知路径回退 `index.html`(见 D5),**不需要改用 hash 模式**。
2. **所有 HTTP 入口已收口**:axios 实例(`web/src/api/index.js`,每请求刷新 `baseURL` 与 token 头)、
   `App.vue` 的 `/datasources` 与 `/heartbeat`、`stores/tabs.js`、`stores/backgroundTasks.js`、
   `utils/errorCapture.js` 的上报、`main.js` 的 `/health`、`router/index.js` 的 `/license/status`、
   `Logs.vue` 的 SSE、`utils/download.js` 的下载——全部经 `api/base.js` 的 `apiUrl`/`authHeaders`。
   新增网络调用时照此接入,不要写裸相对路径。
3. **G6 画布拖拽**(ER 图、字段映射、目录图谱)走 pointer 事件,与原生 HTML5 拖放无关,
   不受 A1 的处理器开关影响。
4. **localStorage 持久化**:WebView2 数据目录在 `%LOCALAPPDATA%\<identifier>`,与安装目录
   (`%LOCALAPPDATA%\Programs\dq-tool`)分离,NSIS `currentUser` 原地升级不会清掉本地偏好(除非改 D1)。
5. **无外部 CDN 依赖**:`web/src` 与 `web/index.html` 未引用任何外部 http(s) 资源,离线/内网可用。
6. **单实例与托盘退出**:关窗只隐藏、托盘「退出」/Cmd+Q 才杀后端子进程,退出路径已通过
   `RunEvent::ExitRequested|Exit` 与 Unix 信号处理器兜底,不留孤儿 java 进程。

---

## 八、跨形态检查清单(新增/改动功能后逐条过)

| 改动类型 | 必须确认 |
|---|---|
| 下载 / 导出 | 走 `utils/download.js`;Tauri 下由 Rust `save_download` 直存 `<数据目录>/exports/`,文件名取后端 `Content-Disposition` 不猜名(A2) |
| 拖放交互 | **Tauri(Windows)真机验一次**;不要重新打开 Tauri 拖放处理器(A1) |
| 自定义 IPC 命令 | `permissions/*.toml` 声明 + `capabilities/permissions` 列入 + `touch build.rs`(A5) |
| 新增 http 调用 | 经 `api/base.js` 的 `apiUrl`/`authHeaders`;SSE 用 `?token=`(A3) |
| 新增跳转 / `window.open` | 不得指向后端相对路径;深链依赖 SPA 回退,勿改成 hash 之外的方案(七.1) |
| 剪贴板 | 带 textarea 兜底(C3) |
| 新前端产物 / 静态资源 | 由 Tauri `versions/<v>/static/` 磁盘直载与 jpackage `static-dir` 各自保证,jar 里不得再出现 `static/**`(A4) |
| 打包资源 / 路径 | 三平台路径差异走 `bundled_resources_dir()`,不写死(A6) |
| 启动流程 / 端口 / 数据目录 | 同步 `main.rs`(A7、A8)与 `tauri/AGENTS.md` |
| 调试期 | 先彻底退出旧实例再跑(A9) |
| 发版 | 递增 `tauri.conf.json` 的 `version`;确认 `updater-manifest` 跑通、`latest.json` 已挂 Release(B1) |
